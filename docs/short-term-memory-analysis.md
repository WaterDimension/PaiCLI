# PaiCLI 短期记忆模块深度学习文档

## 一、架构概览

### 1.1 核心设计理念

PaiCLI 的短期记忆模块采用**双轨压缩**机制：

```
┌─────────────────────────────────────────────────────────────┐
│                        Agent 主循环                          │
│                                                              │
│  ┌──────────────────┐         ┌──────────────────────┐     │
│  │ ConversationMemory│         │ conversationHistory  │     │
│  │   (短期记忆条目)  │         │ (LLM 消息列表)       │     │
│  │                   │         │                      │     │
│  │ ContextCompressor │         │ HistoryCompactor     │     │
│  └──────────────────┘         └──────────────────────┘     │
│         ↓                              ↓                     │
│   摘要旧条目、保留近期            分割 user 边界、摘要旧消息   │
└─────────────────────────────────────────────────────────────┘
```

- ConversationMemory 存的是结构化的 MemoryEntry （带ID、类型、元数据），被压缩器消费
- conversationHistory 存的是 LLM 原生格式的 Message （只有 role 和 content），直接发给 LLM

**两条压缩路径并行存在**：

- **ConversationMemory + ContextCompressor**：管理 PaiCLI 的短期记忆条目（MemoryEntry），用于关键词检索和上下文构建
- **conversationHistory + ConversationHistoryCompactor**：压缩 Agent 真正发给 LLM 的消息列表（List<Message>），解决 token 超限问题


### 1.2 核心类职责

| 类名 | 职责 | 关键方法 |
|------|------|----------|
| `ConversationMemory` | 维护对话历史的短期记忆条目，自动淘汰旧条目 | `store()`, `evictOldest()`, `getUsageRatio()` |
| `ContextCompressor` | 将 ConversationMemory 中的旧条目 Map-Reduce 摘要 | `compress()`, `extractFacts()` |
| `ConversationHistoryCompactor` | 压缩 Agent 发给 LLM 的消息列表 | `compactIfNeeded()`, `compactNow()` |
| `MemoryManager` | 统一门面，协调短期/长期记忆、压缩、检索 | `addUserMessage()`, `compressIfNeeded()` |
| `TokenBudget` | 跟踪 token 使用，判断是否需压缩 | `needsCompression()`, `getAvailableForConversation()` |
| `MemoryEntry` | 记忆条目的基础数据单元 | `estimateTokens()` |
| `MemoryRetriever` | 从短期/长期记忆中检索相关信息 | `retrieve()`, `retrieveLongTerm()` |
| `ContextProfile` | 上下文策略配置（window、预算、触发阈值） | `from()`, `compressionTriggerTokens()` |

---

## 二、为什么采用这种实现？

### 2.1 问题背景

在 LLM Agent 的持续对话中，面临三个核心矛盾：

1. **上下文窗口有限**：即使是 200K 窗口的模型，长对话也会耗尽空间
2. **历史信息有价值**：用户之前的需求、已执行的操作、达成的决策不能丢弃
3. **即时响应要求**：压缩不能让用户等待过久


### 2.2 设计决策与理由

#### 决策 1：双轨压缩而非单轨

**原始设计假设**（Phase 3）：
- Agent 从 ConversationMemory 重建消息发给 LLM
- 只需压缩 ConversationMemory

**实际情况**：
- Agent 直接维护 `conversationHistory`（List<Message>），与 ConversationMemory 并行
- 压缩 ConversationMemory 并不能缩短发给 LLM 的 token

**解决方案**（Phase 12）：
- 新增 `ConversationHistoryCompactor`，在 LLM 调用前直接压缩消息列表
- 保留 `ContextCompressor`，用于关键词检索和上下文构建

**理由**：
- 两条路径服务不同目的，不能合并
- 避免推翻原有 Memory 系统的架构

---

#### 决策 2：Map-Reduce 压缩策略

**为什么分片摘要再合并？**

```java
// ContextCompressor 的压缩流程
List<String> mapPhase(oldEntries) {
    for (chunk : oldEntries.partition(5)) {
        summary = llmClient.chat("摘要这 5 条消息");
        summaries.add(summary);
    }
    return summaries;
}

String reducePhase(summaries) {
    return llmClient.chat("合并这些摘要");
}
```


**优势**：
1. **并行性**：每个 chunk 可以并行调用 LLM（当前串行，但架构支持改造）
2. **局部性**：每次只需处理 5 条消息，prompt 更简洁
3. **降级策略**：某个 chunk 失败只影响部分摘要，可直接截断前 200 字作为备选

**劣势**：
- 需要多次 LLM 调用（开销较大）
- 信息损失可能累积（摘要的摘要）

---

#### 决策 3：保留最近 N 轮不压缩

**ConversationHistoryCompactor 的关键约束**：

```java
// 找到所有 user message 的索引
List<Integer> userIndices = findUserIndices(history);

// 保留最近 retainRecentRounds 个 user 轮次
int splitIdx = userIndices.get(userIndices.size() - retainRecentRounds);

// 只压缩 [system后, splitIdx前) 的消息
```

**理由**：
1. **近期上下文最重要**：用户刚说的话不能被摘要丢失细节
2. **tool_call/tool_result 成对协议**：必须在 user 边界切割，避免破坏工具调用的完整性
3. **用户体验**：最近 3 轮的原始对话保留，模型能直接引用

**为什么是 3 轮？**
- 1 轮太少（只保留当前输入，丢失上下文）
- 5+ 轮太多（压缩效果不明显）
- 3 轮是经验值平衡点

---

#### 决策 4：自动触发 vs 手动触发

**自动压缩触发条件**（ContextProfile）：

```java
// Claude Code 风格的自动压缩阈值
int autoCompactTriggerTokens(int window) {
    int summaryReserve = min(20_000, window / 4);  // 预留摘要输出空间
    int buffer = min(13_000, window / 8);           // 自动压缩缓冲
    int trigger = window - summaryReserve - buffer;
    return max(1_000, trigger);
}

// 示例：200K 窗口
// trigger = 200_000 - 20_000 - 13_000 = 167_000 tokens
// 占用率约 83.5% 触发
```

**设计理由**：
1. **提前触发**：不等到窗口满才压缩，留足空间给摘要输出和安全缓冲
2. **窗口自适应**：小窗口（32K）提前触发，大窗口（1M）更宽容
3. **可预测性**：用户能通过 `/memory` 看到使用率，不是黑盒

**手动触发**（`/compact`）：
- 用户明确要求时立即压缩，跳过 token 阈值判断
- 只保留最近 1 轮（vs 自动触发的 3 轮）

---

#### 决策 5：滑动窗口 + 摘要注入

**压缩后的记忆重建**：

```java
// 旧实现（错误）：直接丢弃旧消息
history = history.subList(splitIdx, history.size());

// 新实现（正确）：摘要注入
history = [
    system_message,
    user("[已压缩的历史对话摘要]\n" + summary),
    assistant("好的，我已了解之前的上下文，请继续。"),
    ...recentMessages
];
```


**理由**：
- **上下文连续性**：模型能知道"之前发生了什么"
- **摘要作为 user 角色**：符合对话协议，模型能自然理解
- **assistant 确认消息**：避免模型误以为需要回答摘要里的问题

---

## 三、实现细节深入剖析

### 3.1 ConversationMemory：LRU 式滑动窗口

**核心数据结构**：

```java
private final LinkedHashMap<String, MemoryEntry> entries;  // 保持插入顺序
private int currentTokens;
private int maxTokens;
private final List<MemoryEntry> compressedSummaries;  // 已淘汰的条目
```

**自动淘汰机制**：

```java
public void store(MemoryEntry entry) {
    entries.put(entry.getId(), entry);
    currentTokens += entry.getTokenCount();
    
    // LRU 驱逐：超出预算时淘汰最旧条目
    while (currentTokens > maxTokens && entries.size() > 1) {
        evictOldest();
    }
}

private void evictOldest() {
    Iterator<Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
    if (it.hasNext()) {
        Entry<String, MemoryEntry> oldest = it.next();
        it.remove();
        currentTokens -= oldest.getValue().getTokenCount();
        compressedSummaries.add(oldest.getValue());  // 保留被淘汰的条目
    }
}
```


**设计亮点**：
1. **LinkedHashMap 保持顺序**：插入顺序即时间顺序，驱逐时只需取第一个
2. **立即驱逐而非延迟**：`store()` 时同步驱逐，保证 `currentTokens` 始终准确
3. **保留摘要**：被淘汰的条目不是直接丢弃，而是存入 `compressedSummaries`，供后续检索或审计

**为什么不用 LRUCache？**
- 需要精确控制 token 预算（而非条目数量）
- 需要自定义淘汰时的副作用（存入 compressedSummaries）
- LinkedHashMap + 手动驱逐更直观，方便调试

---

### 3.2 ConversationHistoryCompactor：user 边界切割

**关键约束**：必须在 user message 边界切割，避免破坏 tool_call/tool_result 协议。

**实现逻辑**：

```java
// 1. 找出所有 user message 的索引
List<Integer> userIndices = new ArrayList<>();
for (int i = systemEnd; i < history.size(); i++) {
    if ("user".equals(history.get(i).role())) {
        userIndices.add(i);
    }
}

// 2. 至少保留 retainRecentRounds 个 user 轮次
if (userIndices.size() <= retainRecentRounds) {
    return false;  // 太少，跳过压缩
}

// 3. 切割点：倒数第 N 个 user message
int splitIdx = userIndices.get(userIndices.size() - retainRecentRounds);
```


**示例对话**：

```
[system] ...
[user] 帮我创建项目              ← userIndices[0]
[assistant] {tool_call: create_project}
[tool] result: success
[assistant] 项目已创建
[user] 列出文件                   ← userIndices[1]
[assistant] {tool_call: list_dir}
[tool] result: [...]
[assistant] 文件列表如下
[user] 读取 README.md             ← userIndices[2] ← splitIdx（保留最近 3 轮）
[assistant] {tool_call: read_file}
[tool] result: ...
[assistant] 内容如下
[user] 当前输入                   ← userIndices[3]（最近一轮）
```

**压缩后的结构**：

```
[system] ...
[user] [已压缩的历史对话摘要]\n用户要求创建项目并列出文件，Agent已完成...
[assistant] 好的，我已了解之前的上下文，请继续。
[user] 读取 README.md             ← 保留
[assistant] {tool_call: read_file}
[tool] result: ...
[assistant] 内容如下
[user] 当前输入
```

**为什么要保留 tool_call/tool_result 的完整性？**
- tool_result 必须跟在对应的 tool_call 后面，否则 LLM 协议解析失败
- 在 user 边界切割能保证所有 assistant → tool → assistant 链路完整

---

### 3.3 Token 估算策略

**MemoryEntry.estimateTokens()**：

```java
public static int estimateTokens(String text) {
    if (text == null || text.isEmpty()) return 0;
    
    // 统计中文字符数（CJK 统一汉字区）
    long chineseChars = text.chars()
        .filter(c -> c > 0x4E00 && c < 0x9FFF)
        .count();
    
    long otherChars = text.length() - chineseChars;
    
    // 中文约 1.5 字/token，英文约 4 字符/token
    return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
}
```

**为什么不调用真实的 tokenizer？**

**优势**：
1. **速度快**：本地计算，无需加载 tokenizer 模型
2. **无依赖**：不需要 tiktoken、sentencepiece 等库
3. **足够准确**：误差在 10-20%，对预算管理足够

**劣势**：
- 对代码（特殊符号多）估算偏低
- 对 JSON（嵌套结构多）估算不准

**实际影响**：
- 预算管理不需要绝对精确，留 10-20% 缓冲即可
- 真实 token 数由 LLM 返回的 `usage` 补正

---

### 3.4 MemoryManager：统一协调层

**核心职责**：

```java
public void addUserMessage(String content) {
    // 1. 包装成 MemoryEntry
    MemoryEntry entry = new MemoryEntry(...);
    
    // 2. 存入短期记忆
    shortTermMemory.store(entry);
    
    // 3. 检查是否需要压缩
    compressIfNeeded();
}

public boolean compressIfNeeded() {
    // 判断是否达到压缩阈值
    if (!tokenBudget.needsCompression(shortTermMemory, 
                                      contextProfile.compressionTriggerRatio())) {
        return false;
    }
    
    // 调用 ContextCompressor 压缩
    String summary = compressor.compress(shortTermMemory);
    
    if (summary != null) {
        log.info("短期记忆压缩完成: {} -> {} tokens", 
                 beforeTokens, afterTokens);
    }
    
    return summary != null;
}
```

**设计模式**：Facade（门面模式）

- Agent 不直接操作 ConversationMemory、ContextCompressor、TokenBudget
- 所有记忆操作通过 MemoryManager 统一入口
- 内部协调压缩、检索、预算管理

---

### 3.5 ContextProfile：窗口自适应策略

**核心公式**：

```java
// 短期记忆预算 = 窗口大小 × 0.45
private static int shortTermBudget(int window) {
    return Math.max(4_000, (int) Math.floor(window * 0.45));
}

// 自动压缩触发点 = 窗口 - 摘要预留 - 安全缓冲
private static int autoCompactTriggerTokens(int window) {
    int summaryReserve = Math.min(20_000, window / 4);   // 摘要输出空间
    int buffer = Math.min(13_000, window / 8);            // 安全缓冲
    int trigger = window - summaryReserve - buffer;
    return Math.max(1_000, trigger);
}
```

**示例计算**：

| 窗口大小 | 短期记忆预算 | 压缩触发点 | 触发比例 |
|---------|------------|----------|---------|
| 32K     | 14.4K      | 23K      | 72%     |
| 128K    | 57.6K      | 95K      | 74%     |
| 200K    | 90K        | 167K     | 84%     |
| 1M      | 450K       | 967K     | 97%     |

**设计理念**：
1. **没有模式分档**：全模型走同一套公式，只是窗口大小不同
2. **大窗口更宽容**：1M 窗口可以到 97% 才压缩，32K 窗口在 72% 就压缩
3. **预留足够空间**：摘要输出（20K）+ 安全缓冲（13K）防止突发超限

---

## 四、常见技术方案对比

### 4.1 业界常见的 Context Window 管理方案

| 方案 | 代表产品 | 优势 | 劣势 | PaiCLI 选择 |
|-----|---------|------|------|------------|
| **固定窗口截断** | 早期 ChatGPT | 实现简单，无开销 | 丢失历史上下文 | ❌ 不采用 |
| **滑动窗口** | Cursor（部分） | 保留近期对话 | 丢失远期重要信息 | ✅ 基础方案 |
| **LLM 摘要压缩** | Claude Code, Cursor | 保留语义信息 | LLM 调用开销大 | ✅ 核心方案 |
| **嵌入向量检索** | MemGPT, LangChain | 精确检索相关上下文 | 需要 embedding 模型 | ⚠️ 仅用于长期记忆 |
| **分层记忆** | AutoGPT, BabyAGI | 长短期分离，精细管理 | 复杂度高 | ✅ 短期+长期双层 |
| **Prompt Caching** | Claude (原生), GPT-4 Turbo | 减少重复 token 开销 | 需模型支持 | ✅ ContextProfile 感知 |

---

### 4.2 方案 1：固定窗口截断

**实现**：

```java
// 最简单的实现
public List<Message> getRecentMessages(List<Message> history, int maxTokens) {
    int tokens = 0;
    List<Message> result = new ArrayList<>();
    
    // 从最新消息往前取，直到超出预算
    for (int i = history.size() - 1; i >= 0; i--) {
        int msgTokens = estimateTokens(history.get(i));
        if (tokens + msgTokens > maxTokens) break;
        result.add(0, history.get(i));
        tokens += msgTokens;
    }
    
    return result;
}
```


**优势**：
- 实现极简，零开销
- 完全可预测

**劣势**：
- 长对话后，旧的关键决策被完全丢弃
- 用户说过的偏好、项目信息无法保留

**适用场景**：
- 短对话场景（如单次问答）
- 对历史无依赖的任务（如翻译、纠错）

**为什么 PaiCLI 不采用？**
- Agent 任务是连续性的（前面的操作影响后续决策）
- 用户偏好和项目信息必须跨会话保留

---

### 4.3 方案 2：滑动窗口（无摘要）

**实现**：

```java
// 保留最近 N 条消息，直接丢弃旧消息
public void slideWindow(List<Message> history, int maxMessages) {
    if (history.size() > maxMessages) {
        // 保留 system message + 最近 N-1 条
        Message system = history.get(0);
        List<Message> recent = history.subList(
            history.size() - maxMessages + 1, 
            history.size()
        );
        history.clear();
        history.add(system);
        history.addAll(recent);
    }
}
```

**优势**：
- 实现简单，无 LLM 调用开销
- 保证最近对话完整


**劣势**：
- 旧对话完全丢失，模型"失忆"
- 用户提到"之前你说过..."模型会回答"我不记得"

**适用场景**：
- 中等长度对话（10-20 轮）
- 任务间弱依赖

**PaiCLI 的改进**：
- 保留滑动窗口作为基础
- 增加 LLM 摘要机制，避免完全失忆

---

### 4.4 方案 3：LLM 摘要压缩（Map-Reduce）

**PaiCLI 采用的核心方案**，已在第二节详细分析。

**关键对比点**：

| 实现细节 | Cursor | Claude Code | PaiCLI |
|---------|--------|-------------|--------|
| 分片策略 | 5-10 条/片 | 动态分片 | 5 条/片（固定） |
| 保留轮次 | 最近 2-3 轮 | 最近 3-5 轮 | 最近 3 轮（可配置） |
| 摘要位置 | system message | user message | user message |
| 降级策略 | 截断前 200 字 | 无 | 截断前 200 字 |
| 并行调用 | ✅ | ✅ | ❌（架构支持，未实现） |

**PaiCLI 的特色**：
- **双轨压缩**：ConversationMemory + conversationHistory 并行
- **user 边界切割**：保证 tool_call/tool_result 协议完整性
- **窗口自适应**：大窗口更宽容，小窗口早压缩

---

### 4.5 方案 4：嵌入向量检索（RAG 式）

**MemGPT / LangChain 的方案**：

```python
# 伪代码示例
class VectorMemory:
    def store(self, message):
        # 1. 用 embedding 模型编码消息
        vector = embedding_model.encode(message.content)
        
        # 2. 存入向量数据库
        vector_db.insert(message.id, vector, metadata=message)
    
    def retrieve(self, query, top_k=5):
        # 3. 编码查询
        query_vector = embedding_model.encode(query)
        
        # 4. 相似度搜索
        results = vector_db.search(query_vector, top_k)
        
        return [r.metadata for r in results]
```

**优势**：
- 精确检索语义相关的历史消息
- 不受时间顺序限制（旧消息也能检索到）
- 可扩展到超大历史（百万条）

**劣势**：
- 需要 embedding 模型（额外开销）
- 检索结果可能不连贯（跳跃式）
- 无法保证近期对话的完整性


**PaiCLI 的选择**：
- **短期记忆**：不用向量检索（保证近期对话完整性）
- **长期记忆**：用向量检索（`VectorStore` + `CodeRetriever`）

**为什么区别对待？**

| 维度 | 短期记忆 | 长期记忆 |
|-----|---------|---------|
| 时间跨度 | 当前会话（小时级） | 跨会话（天/周级） |
| 重要性 | 顺序连贯性 > 精确检索 | 精确检索 > 顺序 |
| 数据量 | 几十条 | 数千条 |
| 更新频率 | 极高 | 低 |

短期记忆的核心诉求是"保持对话连贯性"，而不是"找到最相关的历史片段"。

---

### 4.6 方案 5：分层记忆（短期 + 长期）

**PaiCLI 的双层架构**：

```
┌─────────────────────────────────────────────────────────┐
│                    MemoryManager                         │
│                                                          │
│  ┌──────────────────┐         ┌────────────────────┐   │
│  │ ConversationMemory│         │  LongTermMemory    │   │
│  │   (短期记忆)      │         │   (长期记忆)       │   │
│  │                   │         │                    │   │
│  │ - 当前会话        │         │ - 跨会话事实       │   │
│  │ - 滑动窗口+摘要   │         │ - 向量检索         │   │
│  │ - 自动淘汰        │         │ - 手动持久化       │   │
│  └──────────────────┘         └────────────────────┘   │
│           ↓                            ↓                 │
│    注入 conversationHistory     注入 system prompt      │
└─────────────────────────────────────────────────────────┘
```


**职责分工**：

| 记忆层 | 存储内容 | 触发方式 | 注入位置 | 生命周期 |
|-------|---------|---------|---------|---------|
| 短期 | 对话消息、工具结果 | 自动 | conversationHistory | 当前会话 |
| 长期 | 用户偏好、项目信息、关键事实 | `/save` 手动 | system prompt | 跨会话持久化 |

**为什么需要长期记忆？**

**场景 1**：用户偏好

```
用户: 我喜欢用 tab 缩进，不要用空格
Agent: [保存到长期记忆]

--- 第二天新会话 ---

用户: 帮我写个 Python 函数
Agent: [从长期记忆检索到"用户喜欢 tab 缩进"] 
      [生成的代码使用 tab]
```

**场景 2**：项目信息

```
用户: 这个项目用 Spring Boot 2.7.x，Java 17
Agent: [保存到长期记忆]

--- 一周后 ---

用户: 添加一个 REST 接口
Agent: [从长期记忆检索到"Spring Boot 2.7.x, Java 17"]
      [生成符合该版本的代码]
```

**设计约束**：
- 长期记忆只能通过 `/save` 或用户明确要求保存（不自动提取）
- 原因：避免噪音（临时任务被误存为永久事实）

---

### 4.7 方案 6：Prompt Caching（Claude 原生支持）

**原理**：

```
首次请求：
[system] <-- 缓存点，后续请求复用
[user] 当前输入

后续请求：
[system] <-- 从缓存读取（不计入 input tokens）
[user] 新输入
```

**效果**：
- system prompt（通常占 10-20K tokens）不再重复计费
- 缓存命中率高时，可节省 80% 的 input token 开销

**PaiCLI 的支持**：

```java
// ContextProfile 感知 prompt caching
public record ContextProfile(
    ...
    boolean promptCachingSupported,  // 模型是否支持
    String promptCacheMode           // "none" / "ephemeral" / "persistent"
) {
    public static ContextProfile from(LlmClient llmClient) {
        return new ContextProfile(
            ...,
            llmClient.supportsPromptCaching(),
            llmClient.promptCacheMode()
        );
    }
}
```

**为什么不是核心方案？**
- Prompt Caching 是**成本优化**，不是**上下文管理**
- 即使缓存，超出 window 仍需压缩

---

## 五、核心流程图

### 5.1 短期记忆存储流程

```
用户输入
   ↓
MemoryManager.addUserMessage()
   ↓
包装成 MemoryEntry
   ↓
ConversationMemory.store()
   ↓
判断: currentTokens > maxTokens?
   ├─ 是 → evictOldest() 淘汰最旧条目
   └─ 否 → 直接存储
   ↓
MemoryManager.compressIfNeeded()
   ↓
判断: 达到压缩阈值?
   ├─ 是 → ContextCompressor.compress()
   │        ├─ Map 阶段：分片摘要
   │        ├─ Reduce 阶段：合并摘要
   │        └─ 回注摘要到 ConversationMemory
   └─ 否 → 跳过压缩
```

---

### 5.2 conversationHistory 压缩流程

```
Agent.run() 主循环
   ↓
准备调用 LLM 前
   ↓
ConversationHistoryCompactor.compactIfNeeded()
   ↓
判断: estimateTokens(history) >= triggerTokens?
   └─ 否 → 直接返回
   ↓
找出所有 user message 的索引
   ↓
判断: userIndices.size() <= retainRecentRounds?
   └─ 是 → 太少，跳过压缩
   ↓
计算切割点: splitIdx = userIndices[倒数第N个]
   ↓
摘要 [system后, splitIdx前) 的消息
   ↓
重建 history:
   [system]
   [user(摘要)]
   [assistant(确认)]
   [保留的近期消息...]
   ↓
history.clear() + history.addAll(rebuilt)
```

---

### 5.3 记忆检索流程

```
用户输入: "之前的项目用什么技术栈？"
   ↓
MemoryManager.retrieveRelevant(query, limit)
   ↓
MemoryRetriever.retrieve()
   ↓
   ├─ 从 shortTermMemory 检索
   │    └─ 关键词匹配 + 时间衰减打分
   │
   └─ 从 longTermMemory 检索
        └─ 关键词匹配 + 权重加成（×1.2）
   ↓
合并结果 → 按分数排序 → 返回 top-K
   ↓
MemoryRetriever.buildContextForQuery()
   ↓
组装成文本:
   "## 相关长期记忆\n\n- [FACT] 项目使用 Spring Boot 2.7.x\n..."
   ↓
注入到 system prompt
   ↓
发送给 LLM
```

---

## 六、常见问题与解答

### Q1: 为什么要双轨压缩（ConversationMemory + conversationHistory）？

**A**: 历史原因 + 职责分离

- **Phase 3 设计假设**：Agent 从 ConversationMemory 重建消息发给 LLM
- **实际实现**：Agent 直接维护 conversationHistory（List<Message>）
- **现状**：两条路径并行，各有用途
  - ConversationMemory：关键词检索、上下文构建
  - conversationHistory：真正发给 LLM 的消息

**理想架构**：统一为单一消息列表，但需大规模重构。

---

### Q2: Map-Reduce 压缩会不会丢失信息？

**A**: 会，但可控

**信息损失类型**：
1. **细节丢失**：5 条消息压缩成 200 字，必然丢失部分细节
2. **摘要偏见**：LLM 可能选择性保留某些信息

**缓解策略**：
1. **保留近期消息**：最近 3 轮不压缩，保证细节完整
2. **长期记忆补充**：关键事实通过 `/save` 存入长期记忆
3. **降级策略**：摘要失败时截断前 200 字（保留原文）

**实际影响**：
- 用户最近的操作和决策不受影响
- 旧对话的非关键细节可以丢失

---

### Q3: 为什么不用真实的 tokenizer？

**A**: 性能 vs 精度权衡

**真实 tokenizer 的问题**：
1. **加载慢**：tiktoken（OpenAI）需要加载 100MB+ 的词表
2. **依赖重**：需要 native library（Windows/Linux/macOS 分别打包）
3. **跨模型不一致**：GPT、Claude、Kimi 的 tokenizer 都不同

**粗略估算的优势**：
1. **零依赖**：纯 Java 实现
2. **极快**：微秒级（vs 毫秒级）
3. **足够准确**：误差 10-20%，对预算管理足够


**改进方向**：
- 可选支持 tiktoken（需要时手动开启）
- 用 LLM 返回的 `usage` 统计校准估算公式

---

### Q4: 压缩会不会导致延迟？

**A**: 会，但可优化

**当前实现**：
- Map 阶段串行调用 LLM（5 条/片 × N 片）
- Reduce 阶段再调用一次 LLM
- 总延迟：约 5-10 秒（取决于模型速度）

**优化方向**：
1. **并行 Map**：多个 chunk 并行调用 LLM
2. **异步压缩**：在后台压缩，不阻塞用户输入
3. **增量压缩**：每次只压缩新增部分，避免全量重算

**现状**：
- 触发频率低（大窗口下可能几十轮才压缩一次）
- 用户感知不强（压缩期间显示"正在压缩历史对话..."）

---

### Q5: 为什么长期记忆不自动提取？

**A**: 避免噪音污染

**如果自动提取会发生什么**：

```
用户: 帮我创建一个 Spring Boot 项目
Agent: [自动提取事实: "用户需要 Spring Boot 项目"]

--- 第二天 ---

用户: 帮我写一个 Python 脚本
Agent: [从长期记忆检索到: "用户需要 Spring Boot 项目"]
      [困惑: 到底要 Python 还是 Spring Boot？]
```


**核心问题**：
- 临时任务 vs 永久偏好，模型难以区分
- 误存的"伪事实"会干扰后续决策

**当前方案**：
- 只通过 `/save` 手动保存
- 用户明确说"记住这个规则"时保存

**改进方向**：
- 增加事实过滤器（AGENTS.md 中的 `EPHEMERAL_FACT_PREFIXES`）
- 定期清理未激活的事实

---

### Q6: user 边界切割是否会导致压缩不彻底？

**A**: 会，但是必要的权衡

**示例场景**：

```
[user] 帮我列出文件
[assistant] {tool_call: list_dir}
[tool] result: [100 个文件...]  ← 10K tokens
[assistant] 文件列表如下...
[user] 读取第一个文件        ← splitIdx 只能选这里或更后面
```

**问题**：
- 工具结果占用大量 token（10K）
- 但因为在 user 边界前，无法单独压缩

**为什么不支持更精细的切割？**
1. **协议完整性**：tool_call → tool_result → assistant 必须成对
2. **上下文依赖**：assistant 的回复依赖 tool_result

**缓解方案**：
- `addToolResult()` 时自动截断超长结果（保留前 500 字）
- 完整结果保留在 conversationHistory（供模型引用）

---

### Q7: 如何调试记忆系统？

**A**: 多层日志 + 命令行工具

**日志输出**：

```java
log.info("上下文占用达到压缩阈值（{}%），触发短期记忆压缩", 
         (int) (triggerRatio * 100));

log.info("短期记忆压缩完成: {} -> {} tokens, summaryPreview={}", 
         beforeTokens, afterTokens, preview);

log.info("compacted conversationHistory: tokens {} -> {}, messages {} -> {}", 
         currentTokens, afterTokens, oldSize, newSize);
```

**命令行工具**：

```bash
# 查看记忆系统状态
/memory

# 输出：
# 上下文策略: window: 200000 | 压缩阈值: 84% (167000 tokens)
# 短期记忆: 12条 / 15234 tokens (预算: 90000, 使用率: 17%, 已压缩: 3条)
# 长期记忆: 5条 / 876 tokens (项目: 2条 | 全局: 3条)
# Token 统计: 调用 8 次 | 总输入: 45231 | 总输出: 12456 | ...

# 列出长期记忆
/memory list

# 搜索长期记忆
/memory search Spring Boot

# 导出当前会话（含 system prompt）
/export
```

---

## 七、优化方向与未来演进

### 7.1 短期优化（已在路线图）

| 优化项 | 现状 | 目标 | 难度 |
|-------|------|------|------|
| 并行 Map | 串行调用 LLM | 多线程并行压缩 | 低 |
| 异步压缩 | 同步阻塞 | 后台压缩，不阻塞用户 | 中 |
| 增量压缩 | 全量重算 | 只压缩新增部分 | 高 |
| 工具结果截断 | 固定 500 字 | 根据重要性动态截断 | 中 |
| 压缩策略配置 | 硬编码 | 用户可配置 chunkSize, retainRounds | 低 |

---

### 7.2 中期优化（需架构调整）

**统一双轨压缩**：

```java
// 理想架构：单一消息列表 + 分层管理
public class UnifiedMemory {
    private List<Message> messages;           // 唯一的消息列表
    private int recentBoundary;               // 近期/旧消息分界点
    
    public void compress() {
        // 1. 摘要旧消息
        String summary = summarizeOld(messages.subList(0, recentBoundary));
        
        // 2. 替换旧消息
        messages = [system, user(summary), assistant(confirm), ...recent];
        recentBoundary = 3;
    }
}
```

**优势**：
- 单一真实来源（Single Source of Truth）
- 压缩逻辑统一
- 避免 ConversationMemory vs conversationHistory 的不一致

**代价**：
- 需重构 Agent、MemoryManager、Planner
- 需迁移测试用例

---

### 7.3 长期演进（探索方向）

#### 方向 1：混合压缩策略

**当前**：LLM 摘要（慢但准确）

**探索**：根据消息类型选择策略

| 消息类型 | 策略 | 理由 |
|---------|------|------|
| 代码相关 | 抽取 AST 摘要 | 保留结构信息 |
| 工具结果 | 统计摘要 | 只保留关键指标 |
| 用户偏好 | 直接保留 | 不能丢失 |
| 闲聊 | 直接丢弃 | 无价值 |

---

#### 方向 2：分级记忆（三层）

```
┌─────────────────────────────────────────┐
│            工作记忆 (Working Memory)     │  ← 当前轮次的输入输出
│            - 0-1 轮                      │
│            - 不压缩                      │
└─────────────────────────────────────────┘
              ↓ 超过 1 轮
┌─────────────────────────────────────────┐
│            短期记忆 (Short-term)         │  ← 近期对话
│            - 1-20 轮                     │
│            - LLM 摘要压缩                │
└─────────────────────────────────────────┘
              ↓ 超过 20 轮或手动保存
┌─────────────────────────────────────────┐
│            长期记忆 (Long-term)          │  ← 跨会话事实
│            - 永久存储                    │
│            - 向量检索                    │
└─────────────────────────────────────────┘
```

**优势**：
- 工作记忆永不压缩（保证当前任务完整性）
- 短期记忆按需压缩（平衡性能）
- 长期记忆精确检索（跨会话复用）

---

#### 方向 3：智能摘要（语义感知）

**当前**：固定 5 条/片，不考虑内容

**探索**：根据语义边界分片

```java
// 伪代码
List<Chunk> smartPartition(List<Message> messages) {
    List<Chunk> chunks = [];
    Chunk current = new Chunk();
    
    for (Message msg : messages) {
        // 检测主题切换
        if (isTopicShift(current.lastMessage, msg)) {
            chunks.add(current);
            current = new Chunk();
        }
        current.add(msg);
    }
    
    return chunks;
}

boolean isTopicShift(Message prev, Message next) {
    // 用 embedding 计算语义相似度
    double similarity = cosineSimilarity(
        embed(prev.content), 
        embed(next.content)
    );
    return similarity < 0.5;  // 主题切换阈值
}
```

**优势**：
- 同一主题的对话不被拆散
- 摘要更连贯

**代价**：
- 需要 embedding 模型
- 增加计算开销

---

## 八、总结与关键要点

### 8.1 核心设计原则

1. **双轨压缩**：ConversationMemory（检索） + conversationHistory（LLM）
2. **滑动窗口 + 摘要**：保留近期完整对话 + 压缩旧对话
3. **user 边界切割**：保证 tool_call/tool_result 协议完整性
4. **窗口自适应**：大窗口宽容，小窗口早压缩
5. **分层记忆**：短期自动 + 长期手动

---

### 8.2 关键技术选型

| 决策点 | 选择 | 理由 |
|-------|------|------|
| 压缩策略 | Map-Reduce LLM 摘要 | 保留语义，支持并行 |
| 切割边界 | user message | 保证协议完整性 |
| 保留轮次 | 最近 3 轮 | 平衡上下文完整性和压缩效果 |
| Token 估算 | 粗略估算（中文 1.5 字/token） | 零依赖，足够准确 |
| 触发时机 | 83-97%（窗口自适应） | 提前触发，留足缓冲 |
| 长期记忆 | 手动触发 `/save` | 避免噪音污染 |

---

### 8.3 实现亮点

1. **LinkedHashMap LRU**：插入顺序即时间顺序，驱逐时取第一个
2. **降级策略**：摘要失败时截断前 200 字
3. **摘要回注**：压缩后注入 user+assistant 确认消息
4. **工具结果截断**：避免超长结果撑满预算
5. **ContextProfile**：全模型统一公式，窗口自适应

---

### 8.4 学习建议

**从简单到复杂**：

1. **入门**：先看 `ConversationMemory`（理解滑动窗口 + LRU）
2. **进阶**：看 `ConversationHistoryCompactor`（理解 user 边界切割）
3. **深入**：看 `ContextCompressor`（理解 Map-Reduce 摘要）
4. **全局**：看 `MemoryManager`（理解如何协调各组件）
5. **实战**：跑测试用例，观察日志输出

**调试技巧**：

```bash
# 1. 观察压缩触发
/memory            # 查看使用率

# 2. 手动触发压缩
/compact           # 立即压缩，观察效果

# 3. 导出会话
/export            # 查看完整的 system prompt + 消息历史

# 4. 查看日志
tail -f logs/paicli.log | grep "compress"
```

---

### 8.5 延伸阅读

**学术论文**：
- [MemGPT: Towards LLMs as Operating Systems](https://arxiv.org/abs/2310.08560)
- [Retrieval-Augmented Generation for Large Language Models: A Survey](https://arxiv.org/abs/2312.10997)

**开源项目**：
- **LangChain**：`ConversationBufferWindowMemory` / `ConversationSummaryMemory`
- **AutoGPT**：短期 + 长期 + 工作记忆的三层架构
- **MemGPT**：OS 式的分层记忆管理

**对比阅读**：
- Claude Code 的压缩策略（闭源，只能从行为推测）
- Cursor 的 @ 引用 + 上下文管理
- GitHub Copilot 的增量上下文（每次只看当前文件）

---

## 九、附录：代码片段速查

### A.1 如何添加用户消息到短期记忆

```java
MemoryManager memoryManager = new MemoryManager(llmClient);

// 添加用户输入
memoryManager.addUserMessage("帮我创建一个 Spring Boot 项目");

// 添加助手回复
memoryManager.addAssistantMessage("好的，正在创建项目...");

// 添加工具结果（自动截断超长结果）
memoryManager.addToolResult("create_project", "项目已创建: /path/to/project");
```

---

### A.2 如何手动触发压缩

```java
// 自动压缩（达到阈值时）
boolean compressed = memoryManager.compressIfNeeded();

// 手动压缩（立即执行，只保留最近 1 轮）
ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(llmClient);
boolean result = compactor.compactNow(conversationHistory);
```

