# PaiCLI Multi-Agent 流程、处理策略与问题汇总

本文基于当前仓库源码整理 `Multi-Agent` 模式（`/team`）的真实执行路径、关键处理策略，以及本次讨论中暴露出来的 issue 与实现偏差。

目标不是复述理想设计，而是回答三个问题：

1. 用户输入 `/team ...` 后，真实数据如何流动
2. 系统在规划、调度、审查、重试、压缩、Skill/图片注入时采用了什么策略
3. 当前实现里有哪些需要特别注意的边界和问题

---

## 1. 总体模块地图

`/team` 模式涉及的核心模块如下：

- `src/main/java/com/paicli/cli/Main.java`
  负责 CLI 命令分流、创建 `AgentOrchestrator`、注入 MCP/Skill 上下文、挂接取消控制。
- `src/main/java/com/paicli/agent/AgentOrchestrator.java`
  Multi-Agent 主编排器。负责规划、解析计划、调度 Worker、审查结果、重试、汇总。
- `src/main/java/com/paicli/agent/SubAgent.java`
  Planner / Worker / Reviewer 的统一执行内核。每个角色有独立 `conversationHistory`，共享 `ToolRegistry` 与 `LlmClient`。
- `src/main/java/com/paicli/skill/SkillContextBuffer.java`
  Skill 的“一次性前置注入缓冲区”。
- `src/main/java/com/paicli/image/ImageReferenceParser.java`
  把 `@image:` / `@clipboard` 转成多模态 user message。
- `src/main/java/com/paicli/memory/ConversationHistoryCompactor.java`
  压缩真正发给 LLM 的 `conversationHistory`，不是压 `MemoryManager.shortTermMemory`。
- `src/main/java/com/paicli/memory/MemoryManager.java`
  记录短期记忆和长期记忆；在 Multi-Agent 模式下主要承接用户输入和最终结果摘要。

---

## 2. `/team 帮我写一篇关于AI的论文` 的真实入口路径

真实入口不是“CLI 直接调用 `AgentOrchestrator.run()`”这么简单，而是先经过 `Main` 的模式分流与取消包装。

### 2.1 CLI 分流

`Main.java` 在识别到 `nextTaskUseTeamMode` 或 `SWITCH_TEAM` 后，构造：

- `AgentOrchestrator orchestrator = createTeamAgent(activeClient, reactAgent, ui);`
- `orchestrator.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);`
- `orchestrator.setSkillSystem(skillRegistry, skillContextBuffer);`
- `orchestrator.run(taskInput);`

然后整体包进：

- `runWithCancelSupport(...)`
- `snapshotService.runTurn("team", taskInput, runTask::call)`

所以真实调用链是：

```text
用户输入 "/team 帮我写一篇关于AI的论文"
  -> Main 解析为 team 模式
  -> createTeamAgent(...)
  -> setExternalContextSupplier(...)
  -> setSkillSystem(...)
  -> runWithCancelSupport(...)
  -> snapshotService.runTurn("team", ...)
  -> AgentOrchestrator.run("帮我写一篇关于AI的论文")
```

这里的两个外围策略很重要：

- `runWithCancelSupport(...)`：提供 ESC 取消能力
- `snapshotService.runTurn(...)`：为本轮 team 任务生成 side snapshot 语义边界

---

## 3. 数据流：从输入到最终汇总

## 3.1 进入 orchestrator

`AgentOrchestrator.run(userInput)` 开头会先：

```java
memoryManager.addUserMessage(userInput);
```

这一步写入的是 `MemoryManager.shortTermMemory`，不是 `SubAgent.conversationHistory`。

因此此时有两套上下文并行存在：

- `MemoryManager.shortTermMemory`
  用于项目短期记忆、检索、压缩
- `SubAgent.conversationHistory`
  用于真正发给 LLM

这是本项目 memory 体系里最容易混淆的一点。

### 3.2 规划阶段

编排器构造：

```java
AgentMessage planMessage = AgentMessage.task(
    "orchestrator",
    "请为以下任务制定执行计划：\n" + userInput
);
```

然后调用：

```java
AgentMessage planResult = planner.execute(planMessage, out);
planner.clearHistory();
```

这里的真实行为是：

1. `planner` 是一个 `SubAgent("planner", AgentRole.PLANNER, ...)`
2. `planner.execute(...)` 进入统一 ReAct 循环
3. `SubAgent.execute(...)` 先执行：
   - `pruneHistoricalImagePayloads()`
   - `refreshSystemPrompt()`
   - `prependSkillBodies(task.content())`
4. 然后把处理后的任务内容经 `ImageReferenceParser.userMessage(...)` 注入 `planner.conversationHistory`
5. 调用 LLM 获取规划结果
6. 返回后立刻 `planner.clearHistory()`，只保留 system prompt

所以 planner 的一次规划回合内，发给 LLM 的 user message 可能长这样：

```text
## 已加载 Skill：academic-paper
...skill body...

---
请为以下任务制定执行计划：
帮我写一篇关于AI的论文
```

前提是当前 `SkillContextBuffer` 在这一轮规划前非空。

### 3.3 计划解析

`parsePlan(planResult.content())` 会做两件事：

1. 清洗 markdown code fence，例如 ```` ```json ```` / ````
2. 支持两种顶层字段：
   - `steps`
   - `tasks`

解析策略是“两遍走”：

第一遍：

- 读取原始 step
- 无论原始 `id` 是 `1`、`step1`、`read_file` 还是中文，都统一改写成：
  - `step_1`
  - `step_2`
  - `step_3`

第二遍：

- 遍历原始 `dependencies`
- 用 `idMapping` 把依赖映射到标准化后的 `step_N`

结果是内部步骤状态统一为不可变 record：

```text
ExecutionStep(
  id,
  description,
  type,
  dependencies,
  result,
  status
)
```

初始状态全部是 `PENDING`。

### 3.4 调度执行

编排器核心循环：

```java
while (true) {
    List<ExecutionStep> executable = getExecutableSteps(steps);
    if (executable.isEmpty()) break;
    ...
}
```

`getExecutableSteps()` 的筛选条件很明确：

- 当前 step 状态是 `PENDING`
- 所有依赖 step 状态都是 `COMPLETED`

这意味着：

- 只要 planner 没输出依赖，所有 step 都会被认为相互独立
- 相互独立的 step 会在同一批次并行

### 3.5 串行与并行分支

如果当前批次只有 1 个 step：

- 走串行路径
- 直接把 Worker 的流式输出写到终端

如果当前批次有 2 个及以上 step：

- 走 `runBatchParallel(...)`
- 并发度 = `min(batch.size(), workers.size())`
- 当前默认 `workers.size() == 2`

所以“3 个独立步骤”不会 3 个全并发，而是：

- 最多 2 个同时跑
- 剩下的排队等 Worker 归还到池中

### 3.6 单个步骤的执行语义

每个 step 都进入 `runStep(...)`，内部顺序固定：

1. 组装 `taskMsg = AgentMessage.task("orchestrator", step.description())`
2. `worker.executeWithContext(taskMsg, context, out)`
3. 如果 Worker 失败或返回空，直接标 `FAILED`
4. 否则交给 Reviewer：
   - `reviewer.review(step.description(), result.content(), out)`
5. 审查通过：
   - `COMPLETED`
6. 审查不通过：
   - 提取 `issues`
   - 最多重试 `MAX_RETRIES_PER_STEP = 2`
7. 超过最大重试次数：
   - 仍然保留当前结果
   - 最终状态仍会被写成 `withResult(acceptedResult)`，即 `COMPLETED`

最后这一点非常关键：当前实现里，“超过最大重试次数但仍未通过审查”不会标 `FAILED`，而是保留结果并结束。

### 3.7 最终汇总

`buildFinalResult(steps)` 只做摘要，不重复输出完整执行细节。

最终文本会按 step 顺序汇总：

- step 状态
- step 描述
- 截断后的结果预览

然后写入：

```java
memoryManager.addAssistantMessage("[多Agent结果] " + finalResult);
```

所以 `MemoryManager.shortTermMemory` 最终至少会新增两条：

- user: 原始 team 任务
- assistant: team 汇总结果

---

## 4. 关键处理策略

## 4.1 规划策略

Multi-Agent 当前不是先做静态 DAG 校验，而是：

- 让 Planner 直接自由输出 JSON 计划
- 运行时再做宽松解析和标准化

特点：

- 优点：对不同模型输出更宽容
- 风险：planner 一旦漏掉 `dependencies`，执行层就会把步骤默认当成可并行

结论：

- 该系统的“并行安全性”高度依赖 planner 质量
- 编排器本身不做强约束式依赖补全

## 4.2 依赖调度策略

调度采用“按已满足依赖批次推进”的拓扑贪心：

- 每轮取所有当前可执行的 `PENDING` step
- 单步串行、多步并行
- 下一轮再重新计算可执行集合

特点：

- 简单稳定
- 不做复杂优先级排序
- 不做最长路径、关键路径、成本感知调度

## 4.3 并行策略

并行执行时采用三层保护：

1. 固定大小线程池
2. `BlockingQueue<SubAgent>` 做 Worker 池化
3. 每步独立 `ByteArrayOutputStream + PrintStream`

这样解决了三个问题：

- 同一 Worker 不会同时被两个步骤占用
- 多线程输出不会互相打乱
- 用户最终看到的 step 输出顺序稳定

实现上，执行顺序和显示顺序被刻意分离：

- 执行顺序：线程调度决定
- 显示顺序：按 batch 中 step 顺序 flush

## 4.4 上下文拼装策略

Worker 不是拿整个 plan 历史执行，而是只拿“已完成依赖”的摘要上下文：

```text
总任务上下文：
已完成的依赖步骤 [step_x]: ...
结果：...

当前任务：<当前 step 描述>
```

这意味着：

- 非依赖步骤的结果不会自动进入当前 step 上下文
- 上下文是 dependency-scoped，不是全局广播
- 每条依赖结果最多截断到 500 字符预览

## 4.5 审查与重试策略

Reviewer 审查采用“保守放行”与“局部降级”混合策略：

- 正常情况：
  - 解析 reviewer JSON
  - 看 `approved`
  - 读取 `issues` / `suggestions`
- JSON 解析失败时：
  - 含否定关键词 -> 不通过
  - 无否定但有肯定关键词 -> 通过
  - 两类关键词都不明显 -> 默认不通过

但还有两个特殊降级：

1. Reviewer 本身 LLM 调用失败
   - 当前 step 直接保留 Worker 结果
   - 不阻塞主流程
2. 重试阶段 Reviewer 调用失败
   - 直接视为通过

这说明当前 Multi-Agent 的核心原则不是“审查严格正确优先”，而是“任务连续完成优先”。

## 4.6 历史压缩策略

`ConversationHistoryCompactor` 压缩的是：

- `SubAgent.conversationHistory`

不是：

- `MemoryManager.shortTermMemory`

压缩触发点在每次子代理调 LLM 之前。

压缩规则：

1. token 超过阈值才触发
2. 至少保留最近 `retainRecentRounds = 3` 个 user 轮次
3. 只在 user message 边界切分
4. 把旧消息摘要成一条 user + 一条 assistant 占位消息

设计目的：

- 避免切断 tool_call / tool_result 协议边界
- 真正缩短即将发给 LLM 的上下文

## 4.7 Skill 注入策略

`SubAgent.prependSkillBodies(content)` 的策略是：

- 只有 `SkillContextBuffer` 非空才注入
- `drain()` 一次性消费
- 拼接到本轮 user message 最前面

即：

```text
[已加载 skill body]
---
[原始任务]
```

这带来两个后果：

1. Skill 只对“下一轮真正构造 user message 的 agent”生效
2. 谁先消费 buffer，谁先拿到完整 skill body

## 4.8 图片输入策略

`ImageReferenceParser.userMessage()` 支持：

- `@image:path`
- `@image:<path>`
- `@clipboard`

策略不是把图片路径原样塞给模型，而是：

1. 解析引用
2. 加载图片
3. 压缩/转换为可接受 payload
4. 构造成 multi-part message

这样模型收到的是“文本 + 图片附件”，不是路径字符串，也不是裸 base64 文本。

## 4.9 工具图片结果追加策略

当工具返回图片，例如截图：

- 文本结果仍作为 `tool` message 写入
- 图片部分额外转成一个新的 `user` multi-part message 追加到 `conversationHistory`

这样后续 LLM 才能真正“看图”，而不是看一串 base64。

---

## 5. 本次 issue 核对结论

下面是基于当前源码，对这次讨论里几个关键判断的校对。

## 5.1 你前面的主流程描述，大体成立

成立的部分：

- `/team` 最终确实进入 `AgentOrchestrator.run(...)`
- orchestrator 确实先写 user 到 `MemoryManager`
- planner 确实通过 `SubAgent.execute()` 跑 ReAct
- 计划确实会解析成 `ExecutionStep`
- 依赖为空时确实会并行
- 最终确实会把汇总结果写回 `MemoryManager`

## 5.2 但有一个关键前提不能写死：academic-paper 不应默认“已加载”

你的示例里默认假设：

- planner 前已经加载了 `academic-paper`

这个只能作为“某次具体会话的情形”，不能当作 Multi-Agent 固定流程。

真实规则是：

- 只有 `SkillContextBuffer` 里当时有内容，才会注入
- 否则 planner 收到的只是原始 task

所以“`/team 帮我写一篇关于AI的论文` 默认会吃到 academic-paper skill”这个结论不成立。

## 5.3 `SkillContextBuffer` 的注释与 Multi-Agent 实现存在冲突

`SkillContextBuffer.java` 注释写的是：

- “三个 SubAgent 角色 + 主 Agent 各持一个独立实例，不共享 buffer”

但 `AgentOrchestrator.setSkillSystem()` 的真实实现是：

- planner / workers / reviewer 全部注入同一个 `skillContextBuffer`

而且类注释里也明确承认了这一点：

- 当前是“简化实现，共享同一 SkillContextBuffer”
- “角色独立 buffer”暂未启用

这是一个明确的“文档注释与实际行为不一致”。

## 5.4 共享 Skill buffer 的实际后果

因为 `drain()` 是一次性消费，而 planner / worker / reviewer 共用同一个 buffer，所以：

- 通常最先执行的 planner 会把 skill body 一次性吃掉
- 后续 worker / reviewer 在同一轮 team 任务里通常拿不到同一份 skill body

这意味着当前 Multi-Agent 的 Skill 生效方式更像：

- “让规划阶段先看到 skill”

而不是：

- “让每个角色都各自看到完整 skill”

这也是你这次讨论里最值得记录的问题之一。

## 5.5 “所有步骤无依赖会并行”这个判断是对的，但前提是 planner 没给依赖

`parsePlan()` 不会主动脑补依赖，也不会从自然语言描述推导依赖。

所以：

- planner 漏依赖
  -> orchestrator 当独立步骤处理
  -> 可能过早并行

这是当前 Multi-Agent 正确性最依赖 prompt 质量的地方。

## 5.6 “未通过审查后会失败”这个直觉不完全对

当前实现更准确地说是：

- 审查不通过 -> 最多重试 2 次
- 超过重试次数 -> 保留当前结果
- 最终并不一定标失败

所以 Reviewer 更像“质量增强器”，不是严格闸门。

## 5.7 `MemoryManager` 与 `conversationHistory` 是两层记忆，不要混为一谈

这次讨论里如果后续要继续分析 memory 行为，必须区分：

- `MemoryManager.shortTermMemory`
  面向记忆系统
- `SubAgent.conversationHistory`
  面向真实 LLM 输入

否则会误判“为什么压缩了 memory，但 token 还很大”这类问题。

---

## 6. 建议作为团队共识写清楚的策略

如果要把 Multi-Agent 的处理策略讲清楚，建议对外统一成下面这套说法。

### 6.1 调度策略

- Planner 自由产出计划 JSON
- Orchestrator 只做宽松解析、标准化与依赖驱动调度
- 没依赖就视为可并行

### 6.2 执行策略

- 单步批次串行实时输出
- 多步批次并行执行、顺序回放输出
- Worker 只拿依赖步骤上下文，不拿全局执行历史

### 6.3 质量策略

- Reviewer 提供质量把关
- 但系统优先保证任务完成，不因 reviewer 异常完全阻塞
- 失败是“硬失败”，未通过审查不是绝对失败

### 6.4 上下文策略

- system prompt 由角色决定
- Skill body 通过一次性 buffer 前置到下一轮 user message
- 图片通过多模态附件注入
- conversationHistory 超阈值时做 user-boundary 摘要压缩

### 6.5 当前已知边界

- Skill buffer 在 Multi-Agent 中是共享的，不是角色隔离的
- Planner 漏依赖会直接影响并行正确性
- Reviewer 失败时系统会降级放行
- 超过重试上限后当前结果仍可能被接受

---

## 7. 这次 issue 汇总

可以把这次个人 issue 收敛成以下几条：

1. 不能把 `academic-paper` 当成 Multi-Agent 默认加载行为；它只在 buffer 有内容时发生。
2. `SkillContextBuffer` 文档注释与 `AgentOrchestrator.setSkillSystem()` 的实际共享实现不一致。
3. 共享 buffer + `drain()` 一次性消费，导致 skill 通常只会先作用于最先消费它的那个角色，当前通常是 planner。
4. Multi-Agent 的并行正确性强依赖 planner 输出依赖；编排器不会主动补依赖。
5. Reviewer 当前是增强型审查，不是强阻塞闸门；系统整体偏“完成优先”。
6. 记忆层面必须区分 `MemoryManager.shortTermMemory` 与 `SubAgent.conversationHistory`，它们解决的问题不同。

---

## 8. 一张压缩版流程图

```text
用户输入 /team 任务
  -> Main 分流到 team 模式
  -> runWithCancelSupport + snapshotService.runTurn
  -> AgentOrchestrator.run
     -> memoryManager.addUserMessage
     -> planner.execute(可能先吃掉 skill buffer)
     -> planner.clearHistory
     -> parsePlan(JSON -> step_1...step_n)
     -> while(getExecutableSteps):
        -> 单步: 串行 runStep
        -> 多步: runBatchParallel
           -> worker.executeWithContext
           -> reviewer.review
           -> 不通过则最多重试 2 次
     -> buildFinalResult
     -> memoryManager.addAssistantMessage("[多Agent结果] ...")
     -> 返回 CLI 输出
```

---

## 9. 最后结论

当前 PaiCLI 的 Multi-Agent，不是一个“严格 DAG 执行器 + 严格审查门禁 + 角色隔离技能系统”，而是一个：

- 以 Planner 质量为前提的宽松计划驱动器
- 以完成任务为优先的执行/审查协作器
- 以最小工程复杂度落地的共享 Skill buffer 实现

所以如果后续要继续演进，最值得优先明确的不是“再加多少角色”，而是三件事：

1. Skill buffer 是否要改成角色隔离
2. Planner 输出依赖不足时是否要加执行前校验
3. Reviewer 未通过是否应与最终步骤状态强绑定
