# PaiCLI 长期记忆与上下文压缩深度学习文档

## 一、架构概览

### 1.1 记忆系统的三层架构

```
┌─────────────────────────────────────────────────────────────┐
│                      记忆管理体系                            │
│                                                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌─────────┐  │
│  │ Working Context  │  │ Short-term Memory│  │Long-term│  │
│  │   (工作上下文)   │  │   (短期记忆)     │  │ Memory  │  │
│  │                  │  │                  │  │(长期记忆)│  │
│  │ - 当前轮次输入   │  │ - 近期对话历史   │  │- 跨会话 │  │
│  │ - Tool 调用结果  │  │ - 滑动窗口管理   │  │  事实   │  │
│  │ - 不压缩         │  │ - 自动压缩       │  │- 手动   │  │
│  │                  │  │ - LLM 摘要       │  │  持久化 │  │
│  └──────────────────┘  └──────────────────┘  └─────────┘  │
│           ↓                      ↓                  ↓       │
│  注入 conversationHistory   注入 system prompt     注入    │
│                           (压缩后摘要)         system prompt│
└─────────────────────────────────────────────────────────────┘
                              ↓
                    发送给 LLM 进行推理
```

**核心设计理念**：
- **分层存储**：工作上下文（当前轮）、短期记忆（近期）、长期记忆（永久）
- **职责分离**：短期记忆自动管理，长期记忆手动触发
- **智能压缩**：短期记忆达到阈值时自动压缩，长期记忆不压缩（精炼事实）

---

## 二、长期记忆（LongTermMemory）

### 2.1 为什么需要长期记忆？

**核心问题**：短期记忆会随着对话推进被压缩或淘汰，但有些信息必须跨会话保留。

**典型场景**：

#### 场景 1：用户偏好持久化

```
用户: 我喜欢用 tab 缩进，4 个空格宽度
Agent: [保存到长期记忆]

--- 第二天新会话 ---

用户: 帮我写一个 Python 类
Agent: [从长期记忆检索到: "用户喜欢 tab 缩进，4 空格"]
      [生成的代码使用 tab]
```

#### 场景 2：项目信息记忆

```
用户: 这个项目用 Spring Boot 2.7.x，Java 17，MySQL 8.0
Agent: /save 项目技术栈: Spring Boot 2.7.x, Java 17, MySQL 8.0

--- 一周后 ---

用户: 添加一个用户注册接口
Agent: [从长期记忆检索到项目技术栈]
      [生成符合 Spring Boot 2.7.x 的代码，使用 Java 17 特性]
```

#### 场景 3：浏览器登录状态

```
用户: [通过浏览器登录了 GitHub]
Agent: [自动保存: "用户已登录 github.com"]

--- 第二天 ---

用户: 帮我创建一个 GitHub issue
Agent: [检索到: "用户已登录 github.com"]
      [不再提示登录，直接操作]
```

---

### 2.2 核心设计原则

#### 原则 1：手动触发，拒绝自动提取

**为什么不自动提取？**

```java
// ❌ 错误做法：自动提取
用户: 帮我创建一个 Spring Boot 项目
Agent: [自动提取: "用户需要 Spring Boot 项目"]
      [保存到长期记忆]

--- 第二天 ---

用户: 帮我写一个 Python 脚本
Agent: [从长期记忆检索到: "用户需要 Spring Boot 项目"]
      [困惑: 到底要 Python 还是 Spring Boot？]
```

**问题根源**：
- **临时任务 vs 永久偏好**：模型难以区分"一次性任务"和"跨会话稳定事实"
- **噪音污染**：大量临时指令被误存，干扰后续检索
- **上下文冲突**：旧的临时任务与新输入冲突

**PaiCLI 的解决方案**：

```java
// ✅ 正确做法：手动触发
用户: 这个项目默认用 Java 17
Agent: 好的 
用户: /save 项目使用 Java 17
Agent: [保存到长期记忆]

// 或者用户明确说明
用户: 记住，我喜欢用 tab 缩进
Agent: [检测到"记住"关键词，提示用户]
      好的，是否保存到长期记忆？输入 /save 来保存
用户: /save 用户偏好: tab 缩进
Agent: [保存]
```

**代码实现**：

```java
// MemoryManager.java
public void storeFact(String fact, String scope) {
    String normalizedScope = normalizeScope(scope);  // "project" or "global"
    Map<String, String> metadata = "global".equals(normalizedScope)
            ? Map.of("source", "fact", "scope", "global")
            : Map.of("source", "fact", "scope", "project", "project", currentProject);
    
    MemoryEntry entry = new MemoryEntry(
            "fact-" + UUID.randomUUID().toString().substring(0, 8),
            fact,
            MemoryEntry.MemoryType.FACT,
            metadata,
            MemoryEntry.estimateTokens(fact)
    );
    longTermMemory.store(entry);
}
```

---

#### 原则 2：作用域隔离（Project vs Global）

**两种作用域**：

| 作用域 | 存储内容 | 可见性 | 使用场景 |
|-------|---------|--------|----------|
| **project** | 项目级事实 | 仅当前项目可见 | 技术栈、目录结构、编码规范 |
| **global** | 全局偏好 | 所有项目可见 | 用户习惯、通用规则、账号信息 |

**实现机制**：

```java
// LongTermMemory.java
public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
    String scope = scopeOf(entry);
    if ("global".equals(scope)) {
        return true;  // 全局记忆对所有项目可见
    }
    
    // 项目级记忆只对匹配的项目可见
    String entryProject = entry.getMetadata().get("project");
    return projectKey != null && Objects.equals(entryProject, projectKey);
}

public static String scopeOf(MemoryEntry entry) {
    String scope = entry.getMetadata().get("scope");
    if ("project".equalsIgnoreCase(scope)) {
        return "project";
    }
    return "global";
}
```

**使用示例**：

```bash
# 项目级记忆（默认）
/save 这个项目使用 Spring Boot 2.7.x

# 全局记忆
/save --global 我喜欢用中文交流

# 检索时自动过滤
/memory search Spring Boot   # 只显示当前项目的相关记忆
```

**项目识别**：

```java
// MemoryManager.java
private static String normalizeProjectKey(String path) {
    try {
        Path candidate = Path.of(path).toAbsolutePath().normalize();
        if (Files.exists(candidate)) {
            return candidate.toRealPath().toString();  // 解析符号链接
        }
        return candidate.toString();
    } catch (Exception e) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }
}
```

---

#### 原则 3：磁盘持久化 + 自动去重

**持久化机制**：

```java
// LongTermMemory.java
private void saveToDisk() {
    try {
        List<Map<String, Object>> dataList = entries.values().stream()
                .map(this::entryToMap)
                .collect(Collectors.toList());
        mapper.writeValue(storageFile, dataList);  // 写入 JSON
    } catch (IOException e) {
        log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
    }
}

private void loadFromDisk() {
    if (!storageFile.exists()) return;
    
    try {
        List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
        for (Map<String, Object> data : dataList) {
            MemoryEntry entry = mapToEntry(data);
            if (entry != null) {
                entries.put(entry.getId(), entry);
                tokenCounter.addAndGet(entry.getTokenCount());
            }
        }
        log.info("加载了 {} 条长期记忆", entries.size());
    } catch (IOException e) {
        log.warn("加载长期记忆失败: {}", e.getMessage(), e);
    }
}
```

**存储位置**：

```bash
# 默认位置
~/.paicli/memory/long_term_memory.json

# 自定义位置（通过环境变量或系统属性）
PAICLI_MEMORY_DIR=/custom/path
# 或
-Dpaicli.memory.dir=/custom/path
```

**JSON 格式**：

```json
[
  {
    "id": "fact-a1b2c3d4",
    "content": "项目使用 Spring Boot 2.7.x, Java 17",
    "type": "FACT",
    "timestamp": "2024-12-15T10:30:00Z",
    "metadata": {
      "source": "fact",
      "scope": "project",
      "project": "/Users/xxx/projects/my-app"
    },
    "tokenCount": 15
  }
]
```


**自动去重**：

```java
@Override
public void store(MemoryEntry entry) {
    // 内容完全相同的条目，跳过存储
    boolean duplicate = entries.values().stream()
            .anyMatch(e -> e.getContent().equals(entry.getContent()));
    if (duplicate) {
        return;  // 不保存重复内容
    }
    
    entries.put(entry.getId(), entry);
    tokenCounter.addAndGet(entry.getTokenCount());
    saveToDisk();  // 每次存储后立即持久化
}
```

**为什么每次存储都持久化？**
- **数据安全**：防止程序崩溃导致记忆丢失
- **实时可见**：用户 `/save` 后立即写盘，可以手动查看 JSON
- **无性能问题**：长期记忆写入频率低（用户手动触发），I/O 开销可接受

---

### 2.3 检索机制

**关键词匹配检索**：

```java
public List<MemoryEntry> search(String query, int limit, String projectKey) {
    Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
    
    return entries.values().stream()
            .filter(entry -> isVisibleInProject(entry, projectKey))  // 作用域过滤
            .filter(entry -> {
                // 1. 匹配内容
                if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                    return true;
                }
                // 2. 匹配元数据
                return entry.getMetadata().values().stream()
                        .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
            })
            .limit(limit)
            .collect(Collectors.toList());
}
```

**MemoryQueryTokenizer 的分词策略**：

```java
// MemoryQueryTokenizer.java (推测实现)
public static Set<String> tokenize(String text) {
    // 1. 转小写
    String lower = text.toLowerCase(Locale.ROOT);
    
    // 2. 分词（按空格、标点）
    String[] tokens = lower.split("[\\s\\p{Punct}]+");
    
    // 3. 去除停用词（如"的"、"是"、"在"）
    Set<String> result = new HashSet<>();
    for (String token : tokens) {
        if (token.length() > 1 && !isStopWord(token)) {
            result.add(token);
        }
    }
    return result;
}
```


**注入到 system prompt**：

```java
// MemoryRetriever.java
public String buildContextForQuery(String query, int maxTokens, String projectKey) {
    List<MemoryEntry> relevant = retrieveLongTerm(query, 10, projectKey);
    if (relevant.isEmpty()) return "";
    
    StringBuilder context = new StringBuilder();
    context.append("## 相关长期记忆\n\n");
    
    int usedTokens = 0;
    for (MemoryEntry entry : relevant) {
        if (usedTokens + entry.getTokenCount() > maxTokens) break;
        
        context.append("- [").append(entry.getType()).append("] ")
                .append(entry.getContent()).append("\n");
        usedTokens += entry.getTokenCount();
    }
    
    context.append("\n");
    return context.toString();
}
```

**注入时机**：

```
System Prompt 构建顺序：
1. 基础角色定义
2. 工具列表
3. ← 长期记忆注入（相关事实）
4. 项目上下文 (PAI.md)
5. Skill 上下文
6. 当前任务指令
```

---

### 2.4 命令行接口

| 命令 | 功能 | 示例 |
|------|------|------|
| `/save <事实>` | 保存项目级记忆 | `/save 项目使用 Java 17` |
| `/save --global <事实>` | 保存全局记忆 | `/save --global 我喜欢中文交流` |
| `/memory` | 查看记忆系统状态 | `/memory` |
| `/memory list` | 列出所有长期记忆 | `/memory list` |
| `/memory search <关键词>` | 搜索长期记忆 | `/memory search Spring` |
| `/memory delete <id>` | 删除指定记忆 | `/memory delete fact-a1b2c3d4` |
| `/memory clear` | 清空长期记忆 | `/memory clear` |

**输出示例**：

```bash
> /memory

上下文策略: window: 200000 | 压缩阈值: 84% (167000 tokens)
短期记忆: 12条 / 15234 tokens (预算: 90000, 使用率: 17%, 已压缩: 3条)
长期记忆: 5条 / 876 tokens (项目: 2条 | 全局: 3条)
Token 统计: 调用 8 次 | 总输入: 45231 | 总输出: 12456 | cached: 8900

> /memory list

[fact-a1b2c3d4] (project) 项目使用 Spring Boot 2.7.x, Java 17
[fact-e5f6g7h8] (project) 编码规范：4空格缩进，UTF-8编码
[fact-i9j0k1l2] (global) 用户偏好中文交流
[fact-m3n4o5p6] (global) 浏览器已登录 github.com
[fact-q7r8s9t0] (global) 默认使用 vim 作为编辑器
```

---

## 三、上下文压缩（ContextCompressor）

### 3.1 为什么需要上下文压缩？

**核心问题**：短期记忆会随着对话推进不断增长，最终超出模型的上下文窗口。

**压缩前**：

```
[system] 你是一个编程助手...
[user] 帮我创建一个 Spring Boot 项目
[assistant] {tool_call: create_project}
[tool] result: 项目已创建于 /tmp/demo
[assistant] 项目已创建
[user] 列出文件
[assistant] {tool_call: list_dir}
[tool] result: [pom.xml, src/, ...]
[assistant] 文件列表如下...
[user] 读取 pom.xml
[assistant] {tool_call: read_file}
[tool] result: <project>...</project> (5000 tokens)
[assistant] pom.xml 内容如下...
[user] 添加依赖
[assistant] {tool_call: write_file}
[tool] result: 已写入
[assistant] 依赖已添加
[user] 当前输入  ← 此时累计已 20K tokens，接近阈值
```

**压缩后**：

```
[system] 你是一个编程助手...
[user] [已压缩的历史对话摘要]
      用户要求创建 Spring Boot 项目并添加依赖，已完成项目创建和依赖配置。
[assistant] 好的，我已了解之前的上下文，请继续。
[user] 读取 pom.xml  ← 保留最近 3 轮
[assistant] {tool_call: read_file}
[tool] result: <project>...</project>
[assistant] pom.xml 内容如下...
[user] 当前输入
```

---

### 3.2 Map-Reduce 压缩策略

**核心思想**：将长对话分片摘要（Map），再合并摘要（Reduce）

```
┌─────────────────────────────────────────────────────┐
│                    旧对话历史                        │
│  [15 条消息，共 12K tokens]                         │
└─────────────────────────────────────────────────────┘
                    ↓ partition(5)
        ┌───────────┼───────────┬───────────┐
        ↓           ↓           ↓           ↓
    Chunk 1     Chunk 2     Chunk 3     Chunk 4
    [5 条]      [5 条]      [5 条]      [5 条]
        ↓           ↓           ↓           ↓
    摘要 1      摘要 2      摘要 3       (没有)
    [200字]     [180字]     [190字]
        └───────────┼───────────┘
                    ↓ reducePhase
            ┌───────────────┐
            │  合并后的摘要  │
            │    [300字]     │
            └───────────────┘
                    ↓
        回注到 ConversationMemory
```

**代码实现**：

```java
// ContextCompressor.java
public String compress(ConversationMemory memory) {
    List<MemoryEntry> allEntries = memory.getAll();
    if (allEntries.size() <= retainRecentRounds) {
        return null;  // 太少，不压缩
    }
    
    // 1. 分割：旧消息 vs 近期消息
    int splitPoint = allEntries.size() - retainRecentRounds;  
    List<MemoryEntry> oldEntries = new ArrayList<>(allEntries.subList(0, splitPoint));  //旧的0-splitPoint论
    List<MemoryEntry> recentEntries = new ArrayList<>(allEntries.subList(splitPoint, allEntries.size())); //最新的3轮
    
    // 2. Map 阶段：分片摘要
    List<String> chunkSummaries = mapPhase(oldEntries);
    if (chunkSummaries.isEmpty()) {
        return null;
    }
    
    // 3. Reduce 阶段：合并摘要
    String finalSummary;
    if (chunkSummaries.size() == 1) {
        finalSummary = chunkSummaries.get(0);
    } else {
        finalSummary = reducePhase(chunkSummaries);
    }
    
    // 4. 清空旧记忆，注入摘要，保留近期记忆
    memory.clear();
    memory.store(summaryEntry);  // 摘要条目
    for (MemoryEntry entry : recentEntries) {
        memory.store(entry);  // 近期条目
    }
    
    return finalSummary;
}
```

---

### 3.3 Map 阶段：分片摘要

**固定分片策略**：

```java
private List<String> mapPhase(List<MemoryEntry> oldEntries) {
    List<String> summaries = new ArrayList<>();
    int chunkSize = 5;  // 固定每片 5 条消息
    List<List<MemoryEntry>> chunks = partition(oldEntries, chunkSize);
    
    for (List<MemoryEntry> chunk : chunks) {
        StringBuilder chunkText = new StringBuilder();
        for (MemoryEntry entry : chunk) {
            chunkText.append(entry.getType()).append(": ")
                    .append(entry.getContent()).append("\n\n");
        }
        
        try {
            String prompt = String.format(MAP_PROMPT, chunkText);
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.system("你是一个对话摘要助手。"),
                    LlmClient.Message.user(prompt)
            );
            
            LlmClient.ChatResponse response = llmClient.chat(messages, null);
            summaries.add(response.content());
        } catch (IOException e) {
            // 降级：直接截取前 200 字
            String fallback = chunkText.substring(0, Math.min(200, chunkText.length()));
            summaries.add("[压缩] " + fallback);
        }
    }
    
    return summaries;
}
```

**MAP_PROMPT 提示词**：

```
请将以下对话片段压缩成一段简洁的摘要，保留关键信息：
- 用户的需求和意图
- 已执行的操作和结果
- 做出的决策和结论
- 重要的技术细节

对话片段：
%s

请用中文输出摘要，控制在200字以内。
```

**为什么是 5 条/片？**

| 分片大小 | 优势 | 劣势 |
|---------|------|------|
| 2-3 条 | 摘要精细 | LLM 调用次数过多，延迟高 |
| **5 条** | **平衡点**：局部上下文完整，调用次数可控 | - |
| 10+ 条 | 减少调用次数 | 摘要粗糙，信息损失大 |


**降级策略**：

```java
try {
    // 尝试 LLM 摘要
    LlmClient.ChatResponse response = llmClient.chat(messages, null);
    summaries.add(response.content());
} catch (IOException e) {
    System.err.println("⚠️ 摘要生成失败: " + e.getMessage());
    // 降级：直接截取前 200 字
    String fallback = chunkText.substring(0, Math.min(200, chunkText.length()));
    summaries.add("[压缩] " + fallback);
}
```

**为什么需要降级？**
1. **网络故障**：LLM API 不可用
2. **配额耗尽**：达到 API 调用限制
3. **超时**：LLM 响应过慢

**降级效果**：
- 保留原文片段（前 200 字）
- 虽然没有语义压缩，但至少保留了关键信息
- 避免整个压缩流程失败

---

### 3.4 Reduce 阶段：合并摘要

```java
private String reducePhase(List<String> summaries) {
    String joined = String.join("\n\n---\n\n", summaries);
    
    try {
        String prompt = String.format(REDUCE_PROMPT, joined);
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("你是一个摘要合并助手。"),
                LlmClient.Message.user(prompt)
        );
        
        LlmClient.ChatResponse response = llmClient.chat(messages, null);
        return response.content();
    } catch (IOException e) {
        System.err.println("⚠️ 摘要合并失败: " + e.getMessage());
        // 降级：直接拼接
        return String.join("；", summaries);
    }
}
```

**REDUCE_PROMPT 提示词**：

```
请将以下多个摘要合并成一个整体摘要，保留所有关键信息。

各片段摘要：
%s

请用中文输出合并摘要，控制在300字以内。
```

**为什么需要 Reduce？**

**不合并的问题**：

```
摘要 1: 用户要求创建项目
摘要 2: 配置了依赖
摘要 3: 修改了 README
摘要 4: 运行了测试
```

**合并后**：

```
用户创建了 Spring Boot 项目，配置了依赖和 README，并成功运行了测试。
```

**优势**：
- **语义连贯**：将分散的片段连接成完整叙述
- **去除冗余**：多个摘要可能重复提到相同信息
- **信息整合**：前后因果关系更清晰

---

### 3.5 事实提取（Extract Facts）

**核心功能**：从对话中提取稳定事实，存入长期记忆

```java
public List<String> extractFacts(List<MemoryEntry> entries, LongTermMemory longTermMemory) {
    if (entries.isEmpty()) return List.of();
    
    // 1. 构建对话文本
    StringBuilder conversation = new StringBuilder();
    for (MemoryEntry entry : entries) {
        conversation.append(resolveSource(entry).toUpperCase())
                .append("(").append(entry.getType()).append("): ")
                .append(entry.getContent()).append("\n\n");
    }
    
    // 2. 调用 LLM 提取事实
    String prompt = String.format(EXTRACT_FACTS_PROMPT, conversation);
    LlmClient.ChatResponse response = llmClient.chat(...);
    String factsText = response.content();
    
    // 3. 逐行解析并过滤
    List<String> facts = new ArrayList<>();
    for (String line : factsText.split("\n")) {
        String fact = normalizeFactLine(line);
        if (isPersistentFactCandidate(fact)) {
            facts.add(fact);
            // 存入长期记忆
            longTermMemory.store(factEntry);
        }
    }
    return facts;
}
```

**EXTRACT_FACTS_PROMPT 提示词**：

```
请从以下对话中提取"跨会话仍然成立、未来复用仍有价值"的稳定事实，格式为每行一条：
- 用户偏好和习惯
- 项目信息（名称、路径、技术栈）
- 重要决策和约定

只保留用户明确说明、或工具/代码库可验证的信息。
绝对不要提取以下内容：
- 当前这一轮让你执行的临时任务、步骤、todo
- 一次性的文件名、目录名、输出要求
- 模型自己的猜测、纠错、提醒、推断
- "用户想要/需要/让我/请你..." 这类请求句

对话内容：
%s

请每行一条事实，不要多余解释。
```


**事实过滤规则**：

```java
private boolean isPersistentFactCandidate(String fact) {
    if (fact == null || fact.length() <= 5) {
        return false;  // 太短
    }
    
    String normalized = fact.toLowerCase(Locale.ROOT);
    
    // 1. 拒绝临时任务关键词
    for (String prefix : EPHEMERAL_FACT_PREFIXES) {
        if (normalized.startsWith(prefix.toLowerCase())) {
            return false;
        }
    }
    
    // 2. 拒绝推测性语言
    for (String cue : SPECULATION_CUES) {
        if (normalized.contains(cue.toLowerCase())) {
            return false;
        }
    }
    
    // 3. 包含冒号（结构化事实）
    if (normalized.contains("：") || normalized.contains(":")) {
        return true;
    }
    
    // 4. 包含稳定事实提示词
    for (String hint : DURABLE_FACT_HINTS) {
        if (normalized.contains(hint.toLowerCase())) {
            return true;
        }
    }
    
    return false;
}
```

**过滤规则详解**：

| 规则类型 | 关键词 | 作用 | 示例 |
|---------|--------|------|------|
| **临时任务** | "用户想"、"帮我"、"新建"、"创建" | 拒绝一次性指令 | ❌ "用户想创建一个文件" |
| **推测性** | "可能"、"应该"、"猜测" | 拒绝不确定信息 | ❌ "用户可能喜欢 vim" |
| **结构化** | 包含 ":" 或 "：" | 优先保留 | ✅ "项目: Spring Boot 2.7.x" |
| **稳定事实** | "用户偏好"、"项目"、"版本"、"约定" | 优先保留 | ✅ "用户偏好 4 空格缩进" |

**示例对话**：

```
USER: 这个项目用 Java 17，Spring Boot 2.7.x
ASSISTANT: 好的，记下了
USER: 默认用 4 空格缩进
ASSISTANT: 明白
USER: 帮我创建一个 Controller
ASSISTANT: {tool_call}
```

**提取结果**：

```
✅ 项目: Java 17, Spring Boot 2.7.x
✅ 编码规范: 4 空格缩进
❌ 帮我创建一个 Controller（临时任务）
```

---

## 四、常见技术方案对比

### 4.1 长期记忆存储方案

| 方案 | 代表产品 | 存储方式 | 检索方式 | 优势 | 劣势 | PaiCLI 选择 |
|-----|---------|---------|---------|------|------|------------|
| **本地 JSON** | - | 文件系统 | 关键词匹配 | 简单、可审计 | 不支持语义检索 | ✅ 当前方案 |
| **SQLite** | Cursor | 关系数据库 | SQL 查询 | 支持复杂查询 | 依赖数据库 | ⚠️ 未来可选 |
| **向量数据库** | MemGPT, LangChain | embedding + 索引 | 语义相似度 | 精确检索 | 需要 embedding 模型 | ❌ 仅用于 RAG |
| **键值存储** | Redis | 内存/持久化 | key-value | 极快 | 无法复杂查询 | ❌ 不适合 |
| **云端存储** | ChatGPT Memory | 云端 API | 云端检索 | 跨设备同步 | 隐私问题 | ❌ 不采用 |

---

### 4.2 PaiCLI 的选择：本地 JSON

**为什么选择 JSON？**

**优势**：

1. **零依赖**：无需安装数据库
2. **可审计**：用户可以直接打开 JSON 查看/编辑
3. **简单可靠**：序列化/反序列化逻辑清晰
4. **版本控制**：可以纳入 git（用户自愿）

**劣势**：
1. **不支持语义检索**：只能关键词匹配
2. **大规模检索慢**：数千条记忆时可能变慢

**改进方向**：
- 可选支持 SQLite（`--memory-backend=sqlite`）
- 可选支持向量检索（集成到 RAG 模块）

---

### 4.3 上下文压缩方案对比

| 方案 | 代表产品 | 压缩方式 | 优势 | 劣势 | PaiCLI 选择 |
|-----|---------|---------|------|------|------------|
| **LLM 摘要** | Claude Code, Cursor | LLM 生成摘要 | 保留语义 | 延迟高、成本高 | ✅ 核心方案 |
| **提取式摘要** | BART, Pegasus | 抽取关键句 | 快速、准确 | 需要额外模型 | ⚠️ 可选 |
| **截断** | 早期 ChatGPT | 直接丢弃旧消息 | 零开销 | 丢失上下文 | ❌ 不采用 |
| **滑动窗口** | 简单 Agent | 保留最近 N 轮 | 简单 | 丢失远期信息 | ✅ 与摘要结合 |
| **Prompt Caching** | Claude (原生) | 缓存不变部分 | 降低成本 | 不减少窗口占用 | ✅ 自动支持 |

---

### 4.4 方案 1：LLM 摘要（PaiCLI 采用）

**优势**：
- **语义保留**：LLM 理解对话意图，生成连贯摘要
- **灵活性**：可以根据 prompt 调整摘要风格
- **通用性**：适用于各种对话内容

**劣势**：
- **延迟**：每次压缩需要多次 LLM 调用（Map + Reduce）
- **成本**：每次压缩消耗 API 配额
- **不确定性**：LLM 生成的摘要可能遗漏关键信息

**适用场景**：
- 对话内容复杂（包含代码、工具调用、多轮推理）
- 用户可以容忍 5-10 秒的压缩延迟
- API 成本可接受

---

### 4.5 方案 2：提取式摘要（未来可选）

**原理**：用专门的摘要模型（如 BART, Pegasus）抽取关键句

```python
# 伪代码
from transformers import pipeline

summarizer = pipeline("summarization", model="facebook/bart-large-cnn")

def extractive_summarize(messages):
    text = "\n".join([msg.content for msg in messages])
    summary = summarizer(text, max_length=200, min_length=50)
    return summary[0]['summary_text']
```

**优势**：
- **速度快**：本地模型推理，无网络延迟
- **成本低**：无 API 调用
- **确定性**：同样输入产生同样输出

**劣势**：
- **需要额外模型**：增加部署复杂度（模型文件 ~1GB）
- **领域适应性差**：通用摘要模型可能不适合代码对话
- **上下文窗口有限**：BART 最多支持 1024 tokens

**适用场景**：
- 离线环境
- 对延迟敏感
- 对话内容相对简单（纯文本，无代码）

---

### 4.6 方案 3：混合压缩（探索方向）

**核心思想**：根据消息类型选择不同策略

```
┌─────────────────────────────────────────┐
│           对话历史分类                   │
│                                         │
│  ┌────────┐  ┌────────┐  ┌──────────┐ │
│  │ 代码片段│  │ 工具结果│  │ 用户偏好 │ │
│  └────────┘  └────────┘  └──────────┘ │
│      ↓            ↓            ↓        │
│  AST 摘要    统计摘要    直接保留      │
└─────────────────────────────────────────┘
```

| 消息类型 | 压缩策略 | 理由 |
|---------|---------|------|
| 代码相关 | AST 摘要（提取函数签名、类结构） | 保留结构信息 |
| 工具结果 | 统计摘要（文件数、行数、关键字段） | 只保留关键指标 |
| 用户偏好 | 直接保留原文 | 不能丢失 |
| 闲聊 | 直接丢弃 | 无价值 |
| 其他 | LLM 摘要 | 通用处理 |

**优势**：
- 针对性强，信息损失少
- 某些类型可以快速处理（无需 LLM）

**劣势**：
- 复杂度高，需要维护多种策略
- 消息分类本身可能需要 LLM

---

## 五、核心流程图

### 5.1 长期记忆存储流程

```
用户输入: /save 项目使用 Java 17
   ↓
CliCommandParser.parse()
   ↓
CommandType.MEMORY_SAVE
   ↓
parseMemorySave()  # 解析 --global 标志
   ↓
MemoryManager.storeFact(fact, scope)
   ↓
构建 MemoryEntry
   ├─ id: "fact-" + UUID
   ├─ content: fact
   ├─ type: FACT
   ├─ metadata: {scope, project}
   └─ tokenCount: estimateTokens(fact)
   ↓
LongTermMemory.store(entry)
   ↓
判断: 内容是否重复?
   ├─ 是 → 跳过存储
   └─ 否 → entries.put(id, entry)
   ↓
saveToDisk()
   ↓
JSON 序列化 → ~/.paicli/memory/long_term_memory.json
```

---

### 5.2 上下文压缩流程

```
MemoryManager.compressIfNeeded()
   ↓
判断: tokenBudget.needsCompression()?
   └─ 否 → 直接返回
   ↓
ContextCompressor.compress(shortTermMemory)
   ↓
获取所有条目: memory.getAll()
   ↓
判断: allEntries.size() <= retainRecentRounds?
   └─ 是 → 跳过压缩
   ↓
分割: oldEntries + recentEntries
   ↓
Map 阶段: 分片摘要
   ├─ partition(oldEntries, 5) → chunks
   ├─ for chunk in chunks:
   │    └─ llmClient.chat(MAP_PROMPT) → summary
   └─ 收集所有 summary
   ↓
Reduce 阶段: 合并摘要
   ├─ 拼接所有 summary
   └─ llmClient.chat(REDUCE_PROMPT) → finalSummary
   ↓
重建记忆
   ├─ memory.clear()
   ├─ memory.store(summaryEntry)
   └─ for entry in recentEntries:
        memory.store(entry)
   ↓
返回 finalSummary
```

---

### 5.3 事实提取流程

```
ContextCompressor.extractFacts(entries, longTermMemory)
   ↓
构建对话文本
   for entry in entries:
       conversation += entry.type + ": " + entry.content
   ↓
调用 LLM 提取事实
   llmClient.chat(EXTRACT_FACTS_PROMPT) → factsText
   ↓
逐行解析
   for line in factsText.split("\n"):
       fact = normalizeFactLine(line)  # 去除 "- " 前缀
       ↓
       判断: isPersistentFactCandidate(fact)?
       ├─ 包含临时任务关键词? → 拒绝
       ├─ 包含推测性语言? → 拒绝
       ├─ 包含冒号? → 接受
       ├─ 包含稳定事实提示词? → 接受
       └─ 否则 → 拒绝
       ↓
       接受 → longTermMemory.store(factEntry)
   ↓
返回提取的事实列表
```

---

## 六、常见问题与解答

### Q1: 长期记忆会不会越来越多，最终影响性能？

**A**: 会，但有缓解机制

**当前状态**：
- 关键词检索：O(n) 扫描所有条目
- 注入 system prompt：有 token 预算限制（maxTokens）

**性能影响**：
- **100 条记忆**：几乎无影响（毫秒级）
- **1000 条记忆**：检索约 10-50ms（可接受）
- **10000 条记忆**：检索约 100-500ms（开始明显）

**缓解策略**：
1. **定期清理**：`/memory clear` 手动清空（或删除无用记忆）
2. **未来优化**：引入索引（如 SQLite）或向量检索

---

### Q2: 为什么不用向量检索（embedding）？

**A**: 短期记忆不需要，长期记忆成本高

**短期记忆**：
- **时间跨度短**：当前会话（几十分钟到几小时）
- **重要性：顺序连贯性 > 精确检索**
- **数据量小**：几十条消息
- **结论**：关键词匹配足够

**长期记忆**：
- **时间跨度长**：跨会话（天/周/月）
- **重要性：精确检索 > 顺序**
- **数据量大**：数百到数千条
- **结论**：向量检索更优

**为什么当前未实现？**
1. **增加依赖**：需要 embedding 模型（如 sentence-transformers）
2. **增加开销**：每次存储需要编码（额外延迟）
3. **复杂度**：需要管理向量索引

**未来计划**：
- 可选支持向量检索（`--memory-backend=vector`）
- 复用 RAG 模块的 `VectorStore`

---

### Q3: Map-Reduce 压缩会不会信息损失严重？

**A**: 会损失，但可控

**信息损失类型**：

| 损失类型 | 示例 | 缓解策略 |
|---------|------|---------|
| **细节丢失** | 5 条消息→200 字摘要 | 保留最近 3 轮不压缩 |
| **因果链断裂** | "因为 A 所以 B" → "完成了 B" | Reduce 阶段重建因果 |
| **工具参数丢失** | `read_file("a.txt")` → "读取了文件" | 工具结果已在 conversationHistory |
| **数值丢失** | "修改了 10 个文件" → "修改了文件" | 提示词要求保留数量 |

**实际影响**：
- 用户最近的操作和决策不受影响（保留原文）
- 旧对话的非关键细节可以丢失
- 关键事实通过 `/save` 存入长期记忆

---

### Q4: 为什么不并行调用 Map 阶段？

**A**: 当前串行，但架构支持

**当前实现**：

```java
for (List<MemoryEntry> chunk : chunks) {
    // 串行调用
    LlmClient.ChatResponse response = llmClient.chat(messages, null);
    summaries.add(response.content());
}
```

**并行改造**（未来优化）：

```java
List<CompletableFuture<String>> futures = new ArrayList<>();
for (List<MemoryEntry> chunk : chunks) {
    futures.add(CompletableFuture.supplyAsync(() -> {
        try {
            LlmClient.ChatResponse response = llmClient.chat(messages, null);
            return response.content();
        } catch (IOException e) {
            return "[压缩失败]";
        }
    }));
}

List<String> summaries = futures.stream()
        .map(CompletableFuture::join)
        .collect(Collectors.toList());
```

**为什么未实现？**
1. **触发频率低**：大窗口下可能几十轮才压缩一次
2. **API 限流**：部分 LLM 服务限制并发请求数
3. **代码简洁性**：串行逻辑更清晰

**优化收益**：
- 3 个 chunk 串行：约 6-9 秒
- 3 个 chunk 并行：约 2-3 秒
- 节省 4-6 秒（收益明显）

---

### Q5: extractFacts 什么时候调用？

**A**: 当前未自动调用，需手动触发或未来增强

**当前状态**：
- `extractFacts()` 方法存在于 `ContextCompressor`
- 但没有在 Agent 主循环中自动调用
- 用户只能通过 `/save` 手动保存事实

**未来计划**：

**方案 1：压缩时自动提取**

```java
public String compress(ConversationMemory memory) {
    // ... Map-Reduce 压缩 ...
    
    // 从旧条目中提取事实
    List<String> facts = extractFacts(oldEntries, longTermMemory);
    if (!facts.isEmpty()) {
        log.info("自动提取了 {} 条事实到长期记忆", facts.size());
    }
    
    return finalSummary;
}
```

**方案 2：新增命令**

```bash
/extract-facts   # 从当前短期记忆提取事实
```

**方案 3：智能提示**

```
Agent: [检测到用户说了"记住"、"默认"等关键词]
       检测到可能的稳定事实："用户偏好 4 空格缩进"
       是否保存到长期记忆？(y/n)
```

---

### Q6: 如何调试长期记忆和压缩？

**A**: 多层日志 + 命令行工具 + 文件审查

**1. 命令行工具**：

```bash
# 查看记忆状态
> /memory
上下文策略: window: 200000 | 压缩阈值: 84% (167000 tokens)
短期记忆: 12条 / 15234 tokens (预算: 90000, 使用率: 17%, 已压缩: 3条)
长期记忆: 5条 / 876 tokens (项目: 2条 | 全局: 3条)

# 列出长期记忆
> /memory list

# 搜索长期记忆
> /memory search Java

# 手动触发压缩（测试）
> /compact
```

**2. 日志输出**：

```
[INFO] ContextCompressor - 上下文占用达到压缩阈值（84%），触发短期记忆压缩
[INFO] ContextCompressor - Map 阶段: 处理 3 个 chunk
[INFO] ContextCompressor - Reduce 阶段: 合并 3 个摘要
[INFO] ContextCompressor - 短期记忆压缩完成: 15234 -> 5678 tokens
[INFO] LongTermMemory - 加载了 5 条长期记忆
[INFO] LongTermMemory - 保存记忆: fact-a1b2c3d4
```

**3. 文件审查**：

```bash
# 查看长期记忆 JSON
cat ~/.paicli/memory/long_term_memory.json

# 导出当前会话（含压缩后的历史）
> /export
# 输出到: ~/.paicli/exports/session-20241215-103000.md
```

**4. 单元测试**：

```bash
# 测试长期记忆
mvn test -Dtest=LongTermMemoryTest

# 测试上下文压缩
mvn test -Dtest=ContextCompressorTest

# 测试记忆管理器
mvn test -Dtest=MemoryManagerTest
```

---

## 七、优化方向与未来演进

### 7.1 短期优化（已在路线图）

| 优化项 | 现状 | 目标 | 难度 | 收益 |
|-------|------|------|------|------|
| **并行 Map** | 串行调用 LLM | 并行压缩 3-5 个 chunk | 低 | 节省 4-6 秒 |
| **自动事实提取** | 仅手动 `/save` | 压缩时自动提取 | 中 | 减少手动操作 |
| **长期记忆索引** | 线性扫描 | SQLite / 倒排索引 | 中 | 支持大规模记忆 |
| **向量检索** | 关键词匹配 | embedding 语义检索 | 高 | 精确检索 |
| **压缩策略配置** | 硬编码 | 用户可配置 chunkSize, retainRounds | 低 | 灵活性 |

---

### 7.2 中期优化（需架构调整）

#### 优化 1：智能事实提取

**当前问题**：
- 用户需要手动 `/save`
- 模型无法主动识别稳定事实

**改进方案**：

```java
// ExplicitMemoryHints.java (已有类似逻辑)
public class IntelligentFactDetector {
    
    // 检测用户输入是否包含稳定事实提示
    public static Optional<String> detectFactHint(String userInput) {
        String lower = userInput.toLowerCase(Locale.ROOT);
        
        // 1. 显式提示词
        if (lower.contains("记住") || lower.contains("默认") || 
            lower.contains("偏好") || lower.contains("总是")) {
            return Optional.of(extractFact(userInput));
        }
        
        // 2. 结构化表达
        if (lower.matches(".*项目.*使用.*") || 
            lower.matches(".*技术栈.*")) {
            return Optional.of(userInput);
        }
        
        return Optional.empty();
    }
    
    // 在 Agent 主循环中调用
    Optional<String> hint = IntelligentFactDetector.detectFactHint(userInput);
    if (hint.isPresent()) {
        ui.println("💡 检测到可能的稳定事实: " + hint.get());
        ui.println("   是否保存到长期记忆？输入 'y' 确认，或手动 /save");
        // ... 等待用户确认 ...
    }
}
```

---

#### 优化 2：长期记忆分类管理

**当前问题**：
- 所有长期记忆混在一起
- 无法按类型筛选

**改进方案**：

```java
public enum FactCategory {
    USER_PREFERENCE,   // 用户偏好（缩进、语言等）
    PROJECT_INFO,      // 项目信息（技术栈、路径）
    ACCOUNT_STATUS,    // 账号状态（已登录网站）
    CODING_STANDARD,   // 编码规范
    TOOL_CONFIG        // 工具配置
}

// MemoryEntry 增加 category 字段
public class MemoryEntry {
    private final FactCategory category;
    // ...
}

// 按类别检索
List<MemoryEntry> preferences = longTermMemory.getByCategory(USER_PREFERENCE);

// 按类别清理
/memory clear --category=ACCOUNT_STATUS  # 清理账号状态（如过期登录）
```

---

#### 优化 3：长期记忆时效性管理

**当前问题**：
- 长期记忆永久保留
- 过期信息（如旧版本号）无法自动清理

**改进方案**：

```java
public class MemoryEntry {
    private final Instant expiresAt;  // 过期时间（可选）
    private final int accessCount;    // 访问次数
    private final Instant lastAccessed;  // 最后访问时间
}

// 自动清理策略
public class MemoryJanitor {
    
    public void cleanExpired(LongTermMemory memory) {
        Instant now = Instant.now();
        List<String> expiredIds = memory.getAll().stream()
                .filter(e -> e.expiresAt() != null && e.expiresAt().isBefore(now))
                .map(MemoryEntry::getId)
                .toList();
        
        for (String id : expiredIds) {
            memory.delete(id);
            log.info("清理过期记忆: {}", id);
        }
    }
    
    public void cleanUnused(LongTermMemory memory, Duration threshold) {
        Instant cutoff = Instant.now().minus(threshold);
        List<String> unusedIds = memory.getAll().stream()
                .filter(e -> e.lastAccessed().isBefore(cutoff))
                .filter(e -> e.accessCount() < 3)  // 很少访问
                .map(MemoryEntry::getId)
                .toList();
        
        // ... 清理 ...
    }
}

// 用户命令
/memory clean --expired        # 清理过期记忆
/memory clean --unused=30d     # 清理 30 天未访问的记忆
```

---

### 7.3 长期演进（探索方向）

#### 方向 1：多模态记忆

**当前限制**：只支持文本

**探索方向**：

```java
// 支持图片记忆
/save --image=screenshot.png "这是项目的架构图"

// 支持代码片段记忆
/save --code="def hello(): print('Hello')" "常用的打招呼函数"

// 存储格式
{
  "id": "fact-a1b2c3d4",
  "content": "这是项目的架构图",
  "type": "FACT",
  "attachments": [
    {
      "type": "image",
      "path": "~/.paicli/memory/attachments/arch-diagram.png",
      "embedding": [0.1, 0.2, ...]  # 图片 embedding
    }
  ]
}
```

---

#### 方向 2：记忆冲突检测与合并

**问题场景**：

```
旧记忆: "项目使用 Java 11"
新输入: "升级到 Java 17"
```

**当前行为**：
- 两条记忆共存（可能冲突）

**改进方案**：

```java
public class MemoryConflictResolver {
    
    public void checkConflict(MemoryEntry newEntry, LongTermMemory memory) {
        // 1. 检测冲突
        List<MemoryEntry> conflicts = memory.getAll().stream()
                .filter(e -> isConflicting(e, newEntry))
                .toList();
        
        if (conflicts.isEmpty()) {
            memory.store(newEntry);
            return;
        }
        
        // 2. 提示用户
        ui.println("检测到冲突:");
        for (MemoryEntry conflict : conflicts) {
            ui.println("  旧: " + conflict.getContent());
        }
        ui.println("  新: " + newEntry.getContent());
        ui.println("操作: (r)替换 / (m)合并 / (k)保留两者?");
        
        // 3. 根据用户选择处理
        // ...
    }
}
```

---

#### 方向 3：跨项目记忆共享

**问题场景**：

```
项目 A: Spring Boot 2.7.x
项目 B: Spring Boot 2.7.x (相同技术栈)

当前: 需要在每个项目重新 /save
期望: 从项目 A 复制技术栈记忆到项目 B
```

**改进方案**：

```bash
# 导出项目记忆
/memory export project-a.json

# 在另一个项目导入
/memory import project-a.json

# 或者标记记忆为"可共享"
/save --shareable "常用的编码规范"

# 跨项目检索可共享记忆
/memory search --scope=shareable "编码规范"
```

---

## 八、总结与关键要点

### 8.1 核心设计原则

1. **分层存储**：工作上下文 + 短期记忆 + 长期记忆
2. **手动触发**：长期记忆只通过 `/save` 保存，避免噪音
3. **作用域隔离**：project vs global，避免跨项目污染
4. **Map-Reduce 压缩**：分片摘要再合并，保留语义
5. **降级策略**：LLM 失败时截断原文，保证可用性

---

### 8.2 关键技术选型

| 决策点 | 选择 | 理由 |
|-------|------|------|
| 长期记忆存储 | 本地 JSON | 简单、可审计、无依赖 |
| 检索方式 | 关键词匹配 | 足够用，无需 embedding |
| 压缩策略 | LLM 摘要 (Map-Reduce) | 保留语义，通用性强 |
| 事实提取 | 手动触发 (`/save`) | 避免噪音，可控性强 |
| 作用域管理 | project + global | 隔离项目记忆，共享通用偏好 |

---

### 8.3 实现亮点

1. **自动去重**：内容完全相同的记忆不重复存储
2. **磁盘持久化**：每次存储后立即写盘，防止丢失
3. **事实过滤**：多重规则过滤临时任务和推测性语言
4. **降级策略**：LLM 失败时截断原文，不阻塞压缩
5. **作用域可见性**：global 记忆对所有项目可见，project 记忆隔离

---

### 8.4 学习建议

**从简单到复杂**：

1. **入门**：先看 `LongTermMemory`（理解持久化 + 去重）
2. **进阶**：看 `ContextCompressor`（理解 Map-Reduce 压缩）
3. **深入**：看 `extractFacts()`（理解事实过滤规则）
4. **全局**：看 `MemoryManager`（理解如何协调长短期记忆）
5. **实战**：使用 `/save`, `/memory`, `/compact` 命令，观察效果

**调试技巧**：

```bash
# 1. 查看长期记忆状态
/memory

# 2. 列出所有长期记忆
/memory list

# 3. 搜索长期记忆
/memory search Java

# 4. 查看 JSON 文件
cat ~/.paicli/memory/long_term_memory.json

# 5. 触发手动压缩并观察日志
/compact
tail -f logs/paicli.log | grep "compress"

# 6. 导出会话查看压缩后的历史
/export
cat ~/.paicli/exports/session-*.md
```

---

### 8.5 延伸阅读

**学术论文**：
- [MemGPT: Towards LLMs as Operating Systems](https://arxiv.org/abs/2310.08560) - 长期记忆管理
- [RecurrentGPT: Interactive Generation of Long Texts](https://arxiv.org/abs/2305.13304) - 长文本生成与记忆
- [Reflexion: Language Agents with Verbal Reinforcement Learning](https://arxiv.org/abs/2303.11366) - 记忆与反思

**开源项目**：
- **LangChain**: `ConversationSummaryMemory` / `ConversationEntityMemory`
- **AutoGPT**: 长期记忆的向量存储实现
- **MemGPT**: OS 式的分层记忆管理
- **Semantic Kernel**: Microsoft 的记忆插件系统

**对比阅读**：
- ChatGPT Memory（云端存储，自动提取）
- Claude Code 的压缩策略（闭源，只能从行为推测）
- Cursor 的项目记忆（SQLite 存储）

---

## 九、附录：代码片段速查

### A.1 如何保存长期记忆

```java
// 项目级记忆（默认）
memoryManager.storeFact("项目使用 Java 17");

// 全局记忆
memoryManager.storeFact("用户偏好中文交流", "global");

// 命令行
/save 项目使用 Java 17
/save --global 用户偏好中文交流
```

---

### A.2 如何检索长期记忆

```java
// 检索当前项目的长期记忆
List<MemoryEntry> results = memoryManager.searchLongTerm("Java", 10);

// 检索所有长期记忆（含 global）
List<MemoryEntry> allMemories = memoryManager.listLongTerm();

// 命令行
/memory search Java
/memory list
```

---

### A.3 如何触发上下文压缩

```java
// 自动压缩（达到阈值时）
boolean compressed = memoryManager.compressIfNeeded();

// 手动压缩（ContextCompressor）
ContextCompressor compressor = new ContextCompressor(llmClient);
String summary = compressor.compress(shortTermMemory);

// 命令行
/compact   # 手动触发压缩
```

---

### A.4 如何提取事实

```java
// 从短期记忆提取事实
ContextCompressor compressor = new ContextCompressor(llmClient);
List<MemoryEntry> entries = shortTermMemory.getAll();
List<String> facts = compressor.extractFacts(entries, longTermMemory);

// 输出提取结果
for (String fact : facts) {
    System.out.println("提取的事实: " + fact);
}
```

---

## 十、完整示例：记忆系统的一次生命周期

```
1. 启动 PaiCLI
   ↓
   LongTermMemory.loadFromDisk()
   # 加载: 项目使用 Java 17, 用户偏好中文

2. 用户输入: "帮我创建一个 Controller"
   ↓
   MemoryManager.addUserMessage()
   # 短期记忆: [user] 帮我创建一个 Controller

3. Agent 执行工具调用
   ↓
   MemoryManager.addToolResult("create_file", "...")
   # 短期记忆: [user], [assistant], [tool]

4. 对话继续，短期记忆增长到 15K tokens
   ↓
   MemoryManager.compressIfNeeded()
   # 判断: 未达到阈值 (167K)，跳过压缩

5. 对话继续，达到 170K tokens
   ↓
   ContextCompressor.compress()
   # Map: 分 3 片摘要
   # Reduce: 合并摘要
   # 结果: 170K → 60K tokens

6. 用户输入: "/save 编码规范：4 空格缩进"
   ↓
   MemoryManager.storeFact("编码规范：4 空格缩进")
   ↓
   LongTermMemory.store()
   ↓
   saveToDisk()
   # 持久化到 ~/.paicli/memory/long_term_memory.json

7. 下次启动
   ↓
   LongTermMemory.loadFromDisk()
   # 加载: Java 17, 中文, 编码规范
   ↓
   buildContextForQuery()
   # 注入 system prompt: "相关长期记忆: ..."
```

---

**文档完成！** 🎉

这份文档系统地介绍了 PaiCLI 的长期记忆与上下文压缩机制，包括设计理念、实现细节、技术方案对比、常见问题、优化方向和完整示例。希望对你的学习有帮助！
