package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.runtime.CancellationContext;
import com.paicli.tool.ToolRegistry;
import com.paicli.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Agent 编排器 - Multi-Agent 系统的"主"
 *
 * 负责管理团队、分配任务、路由消息、解决冲突。
 * 采用主从架构：编排器是主，子代理是从。
 *
 * 协作流程：
 * 1. 用户提交任务 -> 编排器交给规划者
 * 2. 规划者拆解任务 -> 编排器解析计划
 * 3. 编排器按依赖顺序将子任务分配给执行者
 * 4. 执行者返回结果 -> 编排器交给检查者
 * 5. 检查者通过则完成，否则带上反馈重新分配给执行者
 * 6. 所有子任务完成后，编排器汇总返回最终结果
 *
 * 并行策略：
 * - 同一依赖批次内部 **并行** 执行（最多 Worker 池大小并发，默认 2）
 * - 每个并行步骤使用独立的 PrintStream 缓冲流式输出，批次结束后按 step_id 顺序 flush 到 stdout，
 *   避免多线程写同一个终端流造成交错，同时仍让用户看到结构化的执行过程
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - Worker 通过 {@link java.util.concurrent.BlockingQueue} 池化分配，确保同一 Worker 不会被两个步骤并发占用
 * - Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争
 */
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final LlmClient llmClient;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final PrintStream out;
    private Supplier<String> externalContextSupplier = () -> "";

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                                  List<String> dependencies, String result,
                                  StepStatus status) {
        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }

        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING);
        }
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public AgentOrchestrator(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out);
    }

    /*
        保存llmClient、 工具注册表和记忆管理器的引用，初始化规划者、执行者和检查者子代理。
        创建 1 个 planner、2 个 workers、1 个 reviewer
     */
    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
        this.toolRegistry = toolRegistry;
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        this.workers = List.of(
                new SubAgent("worker-1", AgentRole.WORKER, llmClient, toolRegistry),
                new SubAgent("worker-2", AgentRole.WORKER, llmClient, toolRegistry)
        );
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
        this.memoryManager = memoryManager;
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        planner.setExternalContextSupplier(this.externalContextSupplier);
        workers.forEach(worker -> worker.setExternalContextSupplier(this.externalContextSupplier));
        reviewer.setExternalContextSupplier(this.externalContextSupplier);
    }

    /**
     * 把 Skill 系统下发给所有 SubAgent。Multi-Agent 三个角色共享同一 SkillRegistry（索引一致），
     * 但共享同一 SkillContextBuffer——简化实现，避免角色级 buffer 隔离的工程开销。
     * 任务书 §3.6 描述的"角色独立 buffer"作为可观察的优化项暂未启用。
     */
    public void setSkillSystem(com.paicli.skill.SkillRegistry skillRegistry,
                               com.paicli.skill.SkillContextBuffer skillContextBuffer) {
        planner.setSkillRegistry(skillRegistry);
        planner.setSkillContextBuffer(skillContextBuffer);
        for (SubAgent worker : workers) {
            worker.setSkillRegistry(skillRegistry);
            worker.setSkillContextBuffer(skillContextBuffer);
        }
        reviewer.setSkillRegistry(skillRegistry);
        reviewer.setSkillContextBuffer(skillContextBuffer);
    }

    /**
     * 运行多 Agent 协作任务
     */
    public String run(String userInput) {
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        // 添加用户信息到短期记忆，供后续子代理调用
        memoryManager.addUserMessage(userInput);
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }

        // 1. 规划阶段：让规划者拆解任务
        out.println(AnsiStyle.heading("📋 第一阶段：规划"));
        out.println("🧑‍💼 规划者正在分析任务...\n");

        AgentMessage planMessage = AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + userInput);
        AgentMessage planResult = planner.execute(planMessage, out);  //ReAct循环
        planner.clearHistory();  //清空 planner 的 conversationHistory （保留 system prompt），这样下次再执行不会携带上一次的对话

        //取消检查
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }

        //失败处理
        if (planResult.type() == AgentMessage.Type.ERROR) {
            return "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            return "❌ 规划失败：规划者未能生成有效计划";
        }

        // 2. 解析计划: JSON → ExecutionStep 列表
        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            return "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planResult.content();
        }
        //steps = [step_1(PENDING), step_2(PENDING), step_3(PENDING)]

        // 2.1 打印执行计划
        out.println(AnsiStyle.heading("📋 执行计划"));
        out.println(summarizeSteps(steps) + "\n");
        /*
            📋 执行计划
            ⏳ [step_1] 读取 index.html (依赖: 无)
            ⏳ [step_2] 修改标题 (依赖: step_1)
            ⏳ [step_3] 验证修改 (依赖: step_2)
        */
        // 3. 执行阶段：按依赖顺序分配给执行者
        out.println(AnsiStyle.heading("⚡ 第二阶段：执行"));
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();  //保证多线程安全
        int singleStepCursor = 0;  // 轮询计数器，均匀分配任务到两个 Worker 
        int batchIndex = 0;

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前多 Agent 任务。";
            }
            // 关键：找出当前可执行的步骤（状态为 PENDING 且所有依赖已完成）--拓扑排序的"贪心"版本
            List<ExecutionStep> executable = getExecutableSteps(steps);
            if (executable.isEmpty()) {
                break;  // 全部完成或全被阻塞
            }
            batchIndex++;

            // 串行 vs 并行分支

            // 1. 串行分支
            if (executable.size() == 1) {
                // 单步批次：直接串行流式输出，保持实时打字观感（让 Worker 的 ReAct 输出 直接流式写入终端）
                ExecutionStep step = executable.get(0);
                SubAgent worker = workers.get(singleStepCursor % workers.size());  //轮询分配 Worker0/1
                singleStepCursor++;
                String context = buildStepContext(steps, step);  //把已完成的前置步骤结果拼成上下文
                runStep(step, steps, retryCount, worker, reviewer, context, out); //执行（Worker 执行 → Reviewer 审查 → 可能重试）
                worker.clearHistory();
            } else {
                // 2. 并行分支（线程池，缓冲输出，按顺序 flush）
                // 多步批次：真正并行执行，每步用独立的 PrintStream 缓冲，完成后按 step_id 顺序 flush
                out.println("⚡ 批次 #" + batchIndex + "：" + executable.size()
                        + " 个独立步骤并行执行（最多 " + workers.size() + " 个并发 Worker）\n");
                runBatchParallel(executable, steps, retryCount);  //两个 Worker 同时干不同的步骤
            }
        }

        // 处理因前置失败而无法执行的残留步骤（显式提示用户）
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                out.println("⏭️ 步骤 [" + step.id() + "] 因前置步骤失败被跳过: " + step.description());
            }
        }

        // 6. 汇总结果
        String finalResult = buildFinalResult(steps);
        memoryManager.addAssistantMessage("[多Agent结果] " + finalResult);

        return finalResult;
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            // 1.清理 JSON 字符串中的 ```json``` 格式，可能的格式错误（如空格、换行符等）
            String cleaned = planJson.replaceAll("```json\\s*", "") //大模型输出经常带代码块标记 json
                    .replaceAll("```\\s*", "")
                    .trim();

            // 2. 解析json
            JsonNode root = mapper.readTree(cleaned);
            JsonNode stepsNode = root.path("steps");  // 先找 "steps"
            // 如果没有，尝试 "tasks"（兼容 Plan-and-Execute 格式）
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                // 尝试 "tasks" 字段（兼容 Plan-and-Execute 的格式）
                stepsNode = root.path("tasks");
            }

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                log.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;

            // 第一遍：创建步骤（重编号:原始 JSON 的 id 可能是 "1" 、 "step1" ，统一改成 "step_1" 、 "step_2" ...）。）
            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);

                String description = stepNode.path("description").asText();
                String type = stepNode.path("type").asText("COMMAND");
                steps.add(ExecutionStep.pending(newId, description, type, new ArrayList<>()));
            }

            // 第二遍：建立依赖.例如原始 JSON 说 step_2 依赖 "1" ，变成依赖 "step_1" 。
            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                String newId = "step_" + stepIndex++;
                JsonNode depsNode = stepNode.path("dependencies");
                if (depsNode.isArray()) {
                    List<String> deps = new ArrayList<>();
                    for (JsonNode dep : depsNode) {
                        // 关键：把原始 ID "1" 映射为 "step_1"（map没有，就默认用1）
                        String mapped = idMapping.getOrDefault(dep.asText(), dep.asText());
                        deps.add(mapped);
                    }
                    // 替换步骤的依赖
                    int idx = stepIndex - 2;
                    if (idx >= 0 && idx < steps.size()) {
                        ExecutionStep old = steps.get(idx);
                        steps.set(idx, new ExecutionStep(old.id(), old.description(), old.type(),
                                deps, old.result(), old.status()));
                    }
                }
            }

            return steps;
        } catch (Exception e) {
            log.error("Failed to parse plan JSON", e);
            return List.of();
        }
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        // 1. 建立状态映射表
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        // 2. 筛选：状态为 PENDING 且所有依赖已完成
        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    /**
     * 解析检查者的审批结果
     *
     * 解析失败时采取保守策略：默认判为"不通过"，避免在审查者异常输出时让问题结果直接放行。
     */
    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            // 无法解析 JSON：必须同时不含否定关键词且含有肯定关键词，才视为通过
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    /**
     * 解析检查者反馈的问题
     */
    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode issue : issuesNode) {
                    sb.append("- ").append(issue.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("- ").append(suggestion.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            // 返回 summary 作为备选
            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取工具注册表（用于同步项目路径）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    // 因为 ExecutionStep 是 record （不可变对象），不能用 step.setStatus(COMPLETED) 修改。所以每次都是创建一个 新的 record 实例 替换掉旧的：
    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    /**
     * 并行执行一批相互独立的步骤。
     * @param batch 待执行的步骤批次，每个步骤状态为 PENDING 且所有依赖已完成
     * @param steps 所有步骤列表，用于构建上下文和更新执行状态
     * @param retryCount 重试计数映射，用于记录每个步骤的重试次数
     * 每个步骤获取一个 Worker（池化，避免同一 Worker 被两个步骤并发占用），同时创建独立的 Reviewer 实例，
     * 流式输出写入步骤本地的 ByteArrayOutputStream；所有任务完成后按 step_id 顺序将缓冲区 flush 到 stdout。
     */
    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        // 并行度 = 最少(步骤数, Worker 数) >= 2
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "paicli-multi-agent");
            t.setDaemon(true);
            return t;
        });
        // Worker 池：用 BlockingQueue 保证同一 Worker 不会被两个步骤同时占用
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);

        // 每个步骤独立输出缓冲区， 避免多线程写乱
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        // 提交所有task到线程池
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            String context = buildStepContext(steps, step);

            // executor.submit(runnable) 会立刻返回一个 Future 对象
            futures.add(executor.submit(() -> {
                SubAgent worker = null;     //取一个空闲Worker

                // 每个并行任务内部，每次都新建一个独立的 Reviewer
                SubAgent localReviewer = new SubAgent(      //创建独立Reviewer实例
                        "reviewer-" + step.id(), AgentRole.REVIEWER, llmClient, toolRegistry);
                try {
                    worker = workerPool.take();  //BlockingQueue.take() 在队列为空时会 自动阻塞等待
                    runStep(step, steps, retryCount, worker, localReviewer, context, stepOut);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                } finally {
                    if (worker != null) {
                        worker.clearHistory();
                        workerPool.offer(worker);        //用完放回
                    }
                    stepOut.flush();
                }
                return null;
            }));
        }
         // 等待全部完成
        for (Future<?> f : futures) {
            try {
                f.get();   // ← 阻塞等待，直到这个任务完成
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch wait interrupted");
            } catch (ExecutionException e) {
                log.error("Parallel step task failed", e.getCause());
            }
        }
        executor.shutdownNow();

        // 按 step_id 顺序 flush 各步骤的缓冲输出，保证用户看到的执行过程有稳定顺序
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                out.print(buf.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /**
     * 执行单个步骤（Worker 执行 + Reviewer 审查 + 最多 2 次重试）。
     *
     * 此方法被串行和并行两条路径共享，通过 {@code out} 控制流式输出目的地。
     */
    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         SubAgent worker, SubAgent reviewer, String context,
                         PrintStream out) {
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        // NOTE 注意：step 是 steps 列表中的引用副本（record 是不可变的），
        // 所以不能 step = step.withResult(...) 直接改，必须通过 updateStep 替换列表中的元素

        // ── 取消检查 → FAILED ──
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = worker.executeWithContext(taskMsg, context, out);
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        // ── LLM 调用失败 → FAILED ──
        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return;
        }
        // ── 空结果 → FAILED ──
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return;
        }

        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        // ── 审查阶段 ──
        AgentMessage reviewResult = reviewer.review(step.description(), result.content(), out);
        reviewer.clearHistory();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println("⚠️ 步骤 [" + step.id() + "] 审查阶段 LLM 调用失败，保留当前执行结果\n");
            updateStep(steps, step.id(), step.withResult(result.content()));
            return;
        }

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));  // 改 PENDING → COMPLETED ✅
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            out.println("   反馈: " + issues + "\n");

            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackContext, out);
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                continue;
            }

            acceptedResult = retryResult.content();
            AgentMessage retryReview = reviewer.review(step.description(), acceptedResult, out);
            reviewer.clearHistory();

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                log.warn("Reviewer failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                approved = true;
                issues = "";
                break;
            }

            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        updateStep(steps, step.id(), step.withResult(acceptedResult));
        if (approved) {
            out.println("✅ 步骤 [" + step.id() + "] 重试后审查通过\n");
        } else {
            out.println("⚠️ 步骤 [" + step.id() + "] 超过最大重试次数，保留当前结果\n");
        }
    }

    /*
        @param steps 执行步骤列表
        @param currentStep 当前执行步骤
        @return 完成的前置步骤结果上下文
        用于 Worker 执行时的 ReAct 输入，保持实时打字观感
    */
    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");

        for (ExecutionStep step : steps) {
            // 如果当前步骤currentStep依赖于该步骤，且该步骤已完成，则拼接上下文
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500
                            ? step.result().substring(0, 500) + "..."
                            : step.result();
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    /*
        @param steps 执行步骤列表
        @return 执行计划摘要
    */
    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无"
                    : String.join(", ", step.dependencies());
            sb.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    step.status() == StepStatus.COMPLETED ? "✅" : "⏳",
                    step.id(), step.description(), deps));
        }
        return sb.toString();
    }

    /**
     * 构建最终汇总。
     *
     * 注意：Worker/Reviewer 的完整输出在执行阶段已经通过流式渲染打印给用户，
     * 此处只返回"步骤状态 + 简短预览"作为总结，避免同一段内容被打印 2-3 次。
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            }
        }

        return result.toString();
    }
}
