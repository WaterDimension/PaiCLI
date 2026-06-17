# PaiCLI Multi-Agent 协作设计与源码解析

本文面向第一次学习 Agent 系统的读者，结合 PaiCLI 当前实现，解释 Multi-Agent 协作为什么这样设计、实际运行时是怎么流转的、关键 API 做了什么、哪些地方最容易踩坑。

本文聚焦的代码入口：

- [Main.java](/D:/backend/paicli-main/src/main/java/com/paicli/cli/Main.java:796)
- [CliCommandParser.java](/D:/backend/paicli-main/src/main/java/com/paicli/cli/CliCommandParser.java:116)
- [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:138)
- [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:173)
- [AgentRole.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentRole.java:6)
- [AgentMessage.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentMessage.java:14)

## 1. 先理解它在系统里的位置

PaiCLI 当前有三条主执行路径：

1. `ReAct`，入口是 `Agent.java`
2. `Plan-and-Execute`，入口是 `PlanExecuteAgent.java`
3. `Multi-Agent`，入口是 `AgentOrchestrator.java`

Multi-Agent 不是把很多模型实例随便拼起来，而是一个被宿主程序强约束的协作系统：

- 一个主控编排器 `AgentOrchestrator`
- 三种固定角色 `Planner / Worker / Reviewer`
- 工具、记忆、Skill、MCP 环境全部复用主系统

这意味着 PaiCLI 的 Multi-Agent 是“中心化调度 + 角色化认知分工”，而不是“去中心化群聊式 Agent 社会”。

这套模式的目标很明确：

- 把复杂任务拆解成更小、职责单一的思考单元
- 把并发、依赖、重试、失败收敛这些确定性逻辑放回 Java 代码控制
- 让 LLM 负责局部智能，而不是整个流程控制

## 2. 为什么单 Agent 不够

第一次学 Agent，最容易误解的一点是：多 Agent 不是因为“模型越多越聪明”，而是因为单 Agent 往往同时承担了互相冲突的职责。

单 Agent 往往同时要做四件事：

1. 理解用户需求
2. 规划步骤
3. 调工具执行
4. 验收结果

这样会产生几个典型问题：

- 规划时容易被执行细节打断
- 执行时缺少独立质量检查
- 多步骤任务的依赖和并发关系不稳定
- 模型可能边做边改计划，导致执行轨迹不稳定

PaiCLI 的设计选择是把四种职责拆开：

- `Orchestrator` 负责调度和状态机
- `Planner` 负责计划生成
- `Worker` 负责工具调用和产出结果
- `Reviewer` 负责质量检查和反馈

这是一个非常典型的 Agent 工程化做法：把开放性留给模型，把确定性留给宿主代码。

## 3. 整体架构图

### 3.1 角色图

```text
User
  |
  v
Main (/team)
  |
  v
AgentOrchestrator
  |-- Planner  : 只规划，不调用工具
  |-- Worker-1 : 执行步骤，可调用工具
  |-- Worker-2 : 执行步骤，可调用工具
  `-- Reviewer : 只审查，不调用工具
```

### 3.2 共享基础设施

Multi-Agent 并不是独立于主系统的另一套基础设施，它复用了：

- `ToolRegistry`
- `MemoryManager`
- MCP resource prompt 注入
- SkillRegistry 和 SkillContextBuffer

入口代码在 [Main.java](/D:/backend/paicli-main/src/main/java/com/paicli/cli/Main.java:800)：

- 创建 `AgentOrchestrator`
- `orchestrator.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt)`
- `orchestrator.setSkillSystem(skillRegistry, skillContextBuffer)`

这点非常重要。它说明：

- Multi-Agent 和 ReAct 共享工具世界
- Multi-Agent 不是“平行宇宙”
- 角色只是认知职责不同，不是能力栈完全不同

## 4. `/team` 是如何进入 Multi-Agent 的

### 4.1 命令解析

`/team` 在 [CliCommandParser.java](/D:/backend/paicli-main/src/main/java/com/paicli/cli/CliCommandParser.java:116) 被识别为 `SWITCH_TEAM`。

关键逻辑：

- `/team`：下一条任务使用 Multi-Agent
- `/team <任务>`：立即用 Multi-Agent 运行当前任务

### 4.2 Main 中切换执行路径

在 [Main.java](/D:/backend/paicli-main/src/main/java/com/paicli/cli/Main.java:796)：

- 如果当前是 `SWITCH_TEAM`
- 创建 `AgentOrchestrator`
- 注入 MCP/Skill 上下文
- 调 `orchestrator.run(taskInput)`

这意味着 CLI 层负责“路由”，而不是 Multi-Agent 自己接管整套交互。

## 5. 核心设计：主控编排器 + 固定角色

### 5.1 角色定义

[AgentRole.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentRole.java:6) 定义了三个角色：

- `PLANNER`：规划者
- `WORKER`：执行者
- `REVIEWER`：检查者

这不是提示词里的松散约定，而是代码层面的硬枚举。好处是：

- 角色集合有限，行为更稳定
- PromptMode 可以稳定映射
- 调试和测试都更容易

### 5.2 通信模型

[AgentMessage.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentMessage.java:14) 用 record 统一了角色间消息：

- `TASK`
- `RESULT`
- `FEEDBACK`
- `APPROVAL`
- `REJECTION`
- `ERROR`

虽然当前 orchestrator 的核心逻辑更多依赖“直接调用方法 + 返回 AgentMessage”，不是一个异步消息总线，但这个模型很重要，因为它让协作语义变得显式。

初学者可以把它理解成：系统虽然没有上 Actor 框架，但已经先把 Actor 式通信语义抽出来了。

## 6. 协作流程总览

### 6.1 主流程

`AgentOrchestrator.run()` 在 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:138)。

它的执行顺序是：

1. 记录用户输入到 `MemoryManager`
2. 调用 Planner 生成计划
3. 解析计划 JSON 为 `ExecutionStep`
4. 按依赖关系找出可执行步骤
5. 单步串行执行，多步批量并行执行
6. 每个步骤结果交给 Reviewer 审查
7. 未通过则附带反馈重试
8. 汇总最终结果

### 6.2 时序图式讲解

```text
User
  |
  |  /team <task>
  v
Main
  |
  | create AgentOrchestrator
  v
AgentOrchestrator
  |
  | task("请为以下任务制定执行计划")
  v
Planner SubAgent
  |
  | output JSON plan
  v
AgentOrchestrator
  |
  | parsePlan -> DAG-like steps
  | getExecutableSteps
  |
  |---- if one step ------------------------------|
  |                                               |
  |   pick one Worker                             |
  |   runStep                                     |
  |                                               |
  |---- if multiple independent steps ----------- |
      spawn parallel batch
      each step gets one Worker
      each step gets one local Reviewer

for each step:
  Worker
    |
    | executeWithContext(task + dependency context)
    | may call tools repeatedly
    v
  result
    |
    v
  Reviewer
    |
    | review(originalTask, result)
    v
  approved?
    | yes -> commit step result
    | no  -> issues feedback -> Worker retry

all steps done
  |
  v
buildFinalResult
  |
  v
User
```

### 6.3 一句话抓住本质

Multi-Agent 的本质不是“多个 agent 聊天”，而是：

“Planner 负责生成依赖图，Worker 负责执行节点，Reviewer 负责验收节点，Orchestrator 负责整个状态机。”

## 7. `AgentOrchestrator` 源码精读

这一节按真实执行顺序精读主逻辑。

### 7.1 构造器：先把角色团队搭起来

构造阶段见 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:92)。

它做了几件关键事：

1. 保存 `llmClient`
2. 保存 `ToolRegistry`
3. 让 `ToolRegistry` 感知当前 `ContextProfile` 和模型名
4. 让 `MemoryManager` 对齐当前项目路径
5. 创建 1 个 planner、2 个 workers、1 个 reviewer

这里有一个重要工程点：它只创建两个 worker，而不是“随计划动态扩展无限 worker”。这说明当前实现更重视稳定性和可控并发，而不是盲目放大并行。

### 7.2 `run()` 第一段：规划阶段

`run()` 开始后，第一段是规划：

- 将用户输入记入 `memoryManager.addUserMessage(userInput)`
- 构造 `AgentMessage.task("orchestrator", "...")`
- 调 `planner.execute(planMessage, out)`

这里的关键理解是：

- Planner 不是返回 Java 对象
- Planner 返回的是文本
- Orchestrator 再把文本解析成计划结构

这是一种典型的 LLM 协作模式：让模型输出结构化文本，再由宿主程序做强校验。

### 7.3 `parsePlan()`：把 LLM 的自由输出收敛成结构

见 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:222)。

这个方法做了三件关键事：

1. 去掉 ```json code fence
2. 读取 `steps`，兼容 `tasks`
3. 重新编号并重写依赖关系

为什么要“重编号”很重要。

Planner prompt 虽然要求输出 `step_1`、`step_2`，但模型并不总是完全可信：

- 可能输出 `s1`、`s2`
- 可能顺序不稳
- 可能依赖引用的是原始 id

所以 `parsePlan()` 先建立 `idMapping`，再统一改写为：

- `step_1`
- `step_2`
- `step_3`

这一步的意义是把“模型生成的不稳定标识”收敛成“程序内部稳定标识”。

这是 Agent 工程里很重要的一条原则：

不要把模型返回的外部标识直接当内部主键使用。

### 7.4 `getExecutableSteps()`：把计划图变成调度批次

见 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:287)。

逻辑很简单但非常关键：

- 只挑 `PENDING` 的步骤
- 且其 `dependencies` 全部已经 `COMPLETED`

这其实就是一个最简 DAG 调度器的“取当前可执行层”的逻辑。

它不做复杂拓扑排序缓存，而是每轮重新扫描全部步骤。对于 5 到 10 步的小计划，这是完全合理的工程取舍：

- 简单
- 易读
- 足够快

### 7.5 单步串行和多步并行

在 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:183)：

- 如果当前批次只有 1 个 step，就串行执行
- 如果当前批次有多个 step，就走 `runBatchParallel()`

这样做有两个好处：

1. 单步场景保持实时流式输出体验
2. 多步场景才为并发付额外复杂度

这是典型的“性能优化只在需要时触发”的设计。

### 7.6 `runBatchParallel()`：并行实现的几个关键点

见 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:410)。

这一段是整个 Multi-Agent 里最值得学习的工程实现之一。

#### 难点 1：同一个 Worker 不能被并发复用

实现方式：

- 用 `BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);`
- 每个并行步骤执行前 `take()`
- 执行完再 `offer()` 回池

这解决的是“SubAgent 内部有对话历史状态，不能被两个线程同时写”的问题。

如果不这么做，会发生：

- 两个步骤共享同一个 `conversationHistory`
- 工具结果串线
- 流式输出混乱

#### 难点 2：Reviewer 不能共享实例

并行路径里每个 step 都会 new 一个局部 reviewer：

- `new SubAgent("reviewer-" + step.id(), AgentRole.REVIEWER, ...)`

原因同样是对话历史隔离。如果所有并行步骤共享一个 reviewer，对话上下文会污染。

#### 难点 3：终端输出不能交错

并行步骤会各自写入 `ByteArrayOutputStream`，完成后再按 `step_id` 顺序 flush 到真实 stdout。

这是很务实的终端并发设计：

- 真正执行是并行的
- 用户看到的输出仍然是稳定有序的

否则多线程直接抢 stdout，日志会不可读。

#### 难点 4：并发控制不是越大越好

线程池大小取：

- `Math.min(batch.size(), workers.size())`

因为当前只有两个 worker，所以最大并发就是 2。系统没有试图把“步骤数”直接映射成“线程数”，这是一个保守但稳定的策略。

### 7.7 `runStep()`：单步执行与审查重试

见 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:481)。

这是一个完整的“执行节点状态机”：

1. Worker 执行 step
2. Reviewer 审查结果
3. 审查通过则写入 `COMPLETED`
4. 审查不通过则提取 `issues`
5. 把 `issues` 拼到上下文，重新让 Worker 执行
6. 最多 2 次重试
7. 超过重试次数则保留最后结果

这里最值得学习的是：Reviewer 不直接改结果，它只返回结构化反馈。真正的修改动作仍交给 Worker。

这能保持职责清晰：

- Reviewer 负责判断质量
- Worker 负责产生结果
- Orchestrator 负责循环控制

### 7.8 `buildStepContext()`：只给 Worker 必要的依赖上下文

见 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:580)。

它只把当前步骤的已完成依赖注入进来，并且只保留依赖结果前 500 字预览。

这么做是为了平衡两个目标：

- 让 Worker 拿到足够上下文
- 不让每个步骤都带上整个历史，导致 prompt 膨胀

这是一种很常见的 Agent 上下文裁剪手法：按局部依赖做上下文投影。

### 7.9 `buildFinalResult()`：最终只做摘要，不重复输出正文

见 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:619)。

设计意图写得很明确：

- 执行过程中的完整输出已经实时打印过
- 这里不再重复打印全文
- 只保留步骤状态和结果预览

这是 CLI 产品化里很重要的一个经验：执行日志和最终摘要要分层，否则用户会看到大量重复内容。

## 8. `SubAgent` 源码精读

`SubAgent` 是一个“可配置角色的轻量 Agent 壳子”。它是 Multi-Agent 体系里最核心的复用单元。

### 8.1 `SubAgent` 的本质

`SubAgent` 不是一个独立类型层级的 PlannerAgent、WorkerAgent、ReviewerAgent。

PaiCLI 只做了一个通用 `SubAgent`，然后通过三样东西区分角色：

1. `AgentRole`
2. `PromptMode`
3. 工具权限

好处是：

- 代码少
- 协作语义统一
- 修改 agent 主循环时不需要维护三套分支实现

### 8.2 系统提示词怎么组装

在 [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:84)：

- `getSystemPrompt()` 调 `PromptAssembler.assemble(...)`
- `promptMode()` 根据角色切到 `TEAM_PLANNER / TEAM_WORKER / TEAM_REVIEWER`

对应 prompt 文件：

- [team-planner.md](/D:/backend/paicli-main/src/main/resources/prompts/modes/team-planner.md:1)
- [team-worker.md](/D:/backend/paicli-main/src/main/resources/prompts/modes/team-worker.md:1)
- [team-reviewer.md](/D:/backend/paicli-main/src/main/resources/prompts/modes/team-reviewer.md:1)

这说明多 Agent 的差异主要是 prompt 驱动的，而不是 Java 逻辑大分叉。

### 8.3 `execute()`：Worker 实际跑的是一个受控 ReAct 循环

主方法在 [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:173)。

你可以把它理解成“Multi-Agent 版本的微型 ReAct”：

1. 刷新 system prompt
2. 将任务注入 `conversationHistory`
3. 创建流式渲染器
4. 创建 `AgentBudget`
5. 循环调用 `llmClient.chat(...)`
6. 如果有 tool calls，就执行工具并继续下一轮
7. 如果没有 tool calls，就把 content 作为最终结果返回

这说明 Worker 其实是“一个会多轮调工具的 agent”，而不是“一次性文本函数”。

### 8.4 为什么只有 Worker 能调工具

关键逻辑在 [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:316)：

```java
private boolean shouldUseTools() {
    return role == AgentRole.WORKER;
}
```

这个设计非常关键。

如果 Planner 和 Reviewer 也能调用工具，会出现几个问题：

- 计划阶段可能直接开始做事，职责混乱
- 审查阶段可能偷偷重新读取文件，自检和执行边界消失
- 并发状态更难追踪

PaiCLI 选择了明确的权限分层：

- Planner 只能输出计划
- Reviewer 只能输出审查 JSON
- Worker 才能碰工具世界

这就是“角色职责边界”的代码化。

### 8.5 工具调用循环怎么接上 ToolRegistry

当 `llmClient.chat(...)` 返回 `toolCalls` 后：

- `SubAgent` 把 assistant 消息加进 `conversationHistory`
- 调 `executeToolCalls(response.toolCalls())`
- 将每个工具结果追加为 `tool` message
- 如有图片结果，再追加图片类型 `user` message
- 继续下一轮 LLM

关键代码在：

- [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:232)
- [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:330)
- [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:346)

这就是典型的 tool-augmented loop。

### 8.6 对话历史为什么要压缩

在每轮调用 LLM 前，`SubAgent` 会做：

- `injectPendingLspDiagnostics(out)`
- `maybeCompactHistory(out)`

见 [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:203)。

压缩器是 [ConversationHistoryCompactor.java](/D:/backend/paicli-main/src/main/java/com/paicli/memory/ConversationHistoryCompactor.java:77)。

它不是压 `MemoryManager` 里的抽象记忆，而是直接压 `conversationHistory`，把早期消息变成摘要，保留最近几轮。

这个设计说明 PaiCLI 非常清楚一个现实问题：

Agent 真正发给 LLM 的，不是“概念上的记忆”，而是消息列表本身。  
如果不压缩消息列表，所谓记忆压缩就只是概念正确、实际无效。

### 8.7 为什么要剪掉历史图片 payload

`pruneHistoricalImagePayloads()` 会把历史图片内容删掉，只保留文本提示。

原因很现实：

- 图片很占上下文
- 旧截图反复进入每轮推理没有意义
- Multi-Agent 下更容易放大上下文成本

这是一个典型的多模态上下文治理点。

## 9. Prompt 层在 Multi-Agent 中扮演什么角色

### 9.1 `PromptAssembler`

[PromptAssembler.java](/D:/backend/paicli-main/src/main/java/com/paicli/prompt/PromptAssembler.java:20) 负责把多段 prompt 组装在一起：

- `base.md`
- personality
- role mode
- approvals
- runtime context
- project context
- skills
- context management
- handoff

Multi-Agent 的角色差异主要体现在 `PromptMode`：

- `TEAM_PLANNER`
- `TEAM_WORKER`
- `TEAM_REVIEWER`

定义见 [PromptMode.java](/D:/backend/paicli-main/src/main/java/com/paicli/prompt/PromptMode.java:7)。

### 9.2 Planner prompt 的设计重点

[team-planner.md](/D:/backend/paicli-main/src/main/resources/prompts/modes/team-planner.md:1) 的核心约束是：

- 只输出 JSON
- 每步必须有 id
- 通过 `dependencies` 表达依赖
- 可以独立完成的步骤不要强行加依赖

这意味着 Planner 实际上被要求输出一个小型 DAG，而不是随便列 todo list。

### 9.3 Worker prompt 的设计重点

[team-worker.md](/D:/backend/paicli-main/src/main/resources/prompts/modes/team-worker.md:1) 的重点是：

- 代码理解优先用 `glob_files / grep_code / read_file`
- `ANALYSIS` / `VERIFICATION` 任务如果上下文足够，可直接输出结果

这能防止 Worker 在明明只需分析时也滥用工具。

### 9.4 Reviewer prompt 的设计重点

[team-reviewer.md](/D:/backend/paicli-main/src/main/resources/prompts/modes/team-reviewer.md:1) 要求固定输出：

```json
{
  "approved": true,
  "summary": "...",
  "issues": [],
  "suggestions": []
}
```

这类 prompt 的核心不在“让模型有多聪明”，而在“让 orchestrator 可解析、可重试、可测试”。

## 10. 关键 API 和难点解析

这一节讲实现层真正值得注意的 API。

### 10.1 `LlmClient.chat(...)`

定义在 [LlmClient.java](/D:/backend/paicli-main/src/main/java/com/paicli/llm/LlmClient.java:11)。

核心签名：

```java
ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
```

这意味着 PaiCLI 的 LLM 抽象一次调用同时承载四件事：

1. 输入消息列表
2. 工具定义列表
3. 流式输出监听
4. 返回结构化结果

返回的 `ChatResponse` 里最关键的是：

- `content`
- `reasoningContent`
- `toolCalls`
- `inputTokens / outputTokens / cachedInputTokens`

这使得一个 Agent 循环可以既支持：

- 普通文本回答
- 推理内容流式显示
- 工具调用
- token 统计

### 10.2 `ToolRegistry.executeTools(...)`

定义在 [ToolRegistry.java](/D:/backend/paicli-main/src/main/java/com/paicli/tool/ToolRegistry.java:1256)。

这个 API 的设计很重要：

- 单个工具调用直接执行
- 多个工具调用自动并行
- 返回结果顺序与原始 `tool_call` 顺序一致

这使上层 agent 不必亲自管理多工具并发，也不必担心消息回灌顺序错乱。

对 Multi-Agent 来说，这里形成了“双层并发”：

1. Orchestrator 层并行多个步骤
2. Worker 内单轮 tool-calls 也可能并行

这是理解系统复杂度的关键点。

### 10.3 `AgentBudget`

定义在 [AgentBudget.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentBudget.java:33)。

它不是“结束 agent 的主规则”，只是三个保险阀：

- token 预算
- 停滞检测
- 最大迭代轮数

最值得注意的是停滞检测：

- 连续多轮工具调用签名完全一致，判定为死循环

这里不是看“文本相似”，而是看“工具名 + 参数是否完全相同”。这是一个更工程化、更可解释的死循环检测方式。

### 10.4 `CancellationContext`

定义在 [CancellationContext.java](/D:/backend/paicli-main/src/main/java/com/paicli/runtime/CancellationContext.java:12)。

Multi-Agent 在多个点检查：

- `run()` 开头
- 每轮批次前
- `runStep()` 内
- `ToolRegistry.executeTools()` 内

这说明取消语义不是只在最外层做一次判断，而是贯穿整个执行链。

对于并行系统来说，这点很关键。否则用户取消后，后台步骤仍会继续乱跑。

## 11. 这套实现里最难的几个点

### 11.1 难点一：如何把“模型输出的计划”变成“程序可执行的图”

Planner 输出的是文本 JSON，不是可信 Java 对象。  
所以必须做：

- 清理 markdown fence
- 容错字段名
- 重新编号
- 重写依赖引用

否则后续调度会完全建立在脆弱字符串上。

### 11.2 难点二：如何在并行场景里隔离 agent 内部状态

`SubAgent` 有内部 `conversationHistory`，不是无状态函数。  
所以不能简单把一个 `SubAgent` 扔给多个线程用。

PaiCLI 的做法：

- Worker 用池化占用
- Reviewer 并行为每步新建实例

这是状态隔离问题，不是线程池问题。

### 11.3 难点三：如何避免流式输出打架

Agent 天生偏流式，但并发天生会抢 stdout。  
PaiCLI 的方案是：

- 串行步骤直连输出
- 并行步骤先写缓冲
- 批次结束再有序 flush

这是终端产品里比“算得对”更难被注意到的工程点。

### 11.4 难点四：如何让 Reviewer 既严格又不会卡死流程

Reviewer 不是永远可靠的，可能：

- 输出空内容
- 输出非 JSON
- 审查本身 LLM 调用失败

PaiCLI 的策略是：

- 审查解析失败时保守判定不通过
- 但如果 reviewer LLM 调用本身失败，则保留当前结果
- 最多重试 2 次，避免无限循环

这是一种典型的“质量控制不能反过来拖死主流程”的平衡设计。

## 12. 测试证明了什么

Multi-Agent 不是只靠“看起来能跑”。  
测试已经覆盖了关键协作语义：

- 计划依赖解析：[AgentOrchestratorTest.java](/D:/backend/paicli-main/src/test/java/com/paicli/agent/AgentOrchestratorTest.java:52)
- 可并行步骤识别：[AgentOrchestratorTest.java](/D:/backend/paicli-main/src/test/java/com/paicli/agent/AgentOrchestratorTest.java:173)
- 审查失败重试：[AgentOrchestratorTest.java](/D:/backend/paicli-main/src/test/java/com/paicli/agent/AgentOrchestratorTest.java:251)
- 并行执行确实发生：[AgentOrchestratorTest.java](/D:/backend/paicli-main/src/test/java/com/paicli/agent/AgentOrchestratorTest.java:293)
- 前置失败阻塞后续：[AgentOrchestratorTest.java](/D:/backend/paicli-main/src/test/java/com/paicli/agent/AgentOrchestratorTest.java:360)

如果你是第一次学 agent，这一点特别值得记住：

真正可维护的 Agent 系统，测试的重点不是“模型说得像不像”，而是“协作语义是否成立”。

## 13. 用初学者视角重新总结一遍

如果把 PaiCLI 的 Multi-Agent 用最朴素的话总结，可以这样理解：

- `Planner` 像项目经理，负责拆活
- `Worker` 像工程师，负责做活
- `Reviewer` 像 code reviewer 或 QA，负责挑问题
- `Orchestrator` 像调度系统，负责决定谁先干、谁并行干、返工几次、最后怎么汇总

但和现实团队不一样的地方在于：

- 真正的流程控制权不在角色手里
- 全部控制权都在 Java 宿主程序里

这就是 Agent 工程的核心思想之一：

“让 LLM 承担擅长的局部认知，让程序承担必须稳定的流程控制。”

## 14. 你接下来该怎么继续学

如果要把这套代码真正吃透，建议按这个顺序读：

1. 从 [Main.java](/D:/backend/paicli-main/src/main/java/com/paicli/cli/Main.java:796) 看 `/team` 怎么进入编排器
2. 从 [AgentOrchestrator.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/AgentOrchestrator.java:138) 看主状态机
3. 从 [SubAgent.java](/D:/backend/paicli-main/src/main/java/com/paicli/agent/SubAgent.java:173) 看 Worker 的 ReAct 循环
4. 从 [ToolRegistry.java](/D:/backend/paicli-main/src/main/java/com/paicli/tool/ToolRegistry.java:1256) 看工具并行执行
5. 从 [AgentOrchestratorTest.java](/D:/backend/paicli-main/src/test/java/com/paicli/agent/AgentOrchestratorTest.java:251) 看重试和并发的测试用例

## 15. 最终结论

PaiCLI 的 Multi-Agent 实现不是在追求“炫技式多模型协作”，而是在做一套可控、可测、可产品化的协作执行框架。

它最有价值的设计点不是“有三个角色”，而是下面这四条：

- 角色职责清晰，权限边界明确
- 计划、执行、审查由不同认知单元承担
- 依赖、并发、重试由宿主程序掌控
- 工具、上下文、取消、压缩、输出都有工程化收口

如果你刚开始学 agent，建议把它看成一句话：

“这不是多个 AI 在聊天，而是宿主程序用多个专职 LLM 回合去实现一个受控工作流。”
