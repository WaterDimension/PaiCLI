# PaiCLI JUC 详细分析 - 为初学者精讲

## 1. ToolRegistry.java - 工具并行执行 (ExecutorService)

### 核心问题场景

**需求**：Agent 从 LLM 一次可能调用多个工具
```
用户：帮我分析这个项目
↓
LLM 决定：需要同时调用
  - read_file(README.md)
  - read_file(pom.xml) 
  - execute_command(mvn compile)
```

**传统 synchronized 方案的问题**：
```java
// ❌ 不行：这会串行执行，太慢
synchronized void executeTools(List<ToolInvocation> invocations) {
    for (ToolInvocation inv : invocations) {
        executeToolOutput(inv.name(), inv.args);  // 一个接一个，如果 read_file 要 2 秒，3 个就要 6 秒
    }
}
```

### 现实代码分析

```java
public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
    // ────── 第一步：检查取消信号 ──────
    if (CancellationContext.isCancelled()) {  // 用户按了 Ctrl+C？
        return invocations.stream()
                .map(invocation -> ToolExecutionResult.failed(invocation, "用户取消了此次工具调用"))
                .toList();
    }

    // ────── 第二步：单个工具优化路径 ──────
    if (invocations.size() == 1) {
        // 只有 1 个工具，不必创建线程池，直接执行
        ToolInvocation invocation = invocations.get(0);
        long startedAt = System.nanoTime();
        ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
        return List.of(ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt)));
    }

    // ────── 第三步：多工具并行执行 ──────
    int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);  // 最多 4 个线程
    
    // 创建固定大小的线程池（不是 synchronized！）
    ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
        Thread thread = new Thread(r, "paicli-tool-executor");
        thread.setDaemon(true);  // 主线程退出时，这些 daemon 线程也会退出
        return thread;
    });

    try {
        // 把每个工具调用包装成 Callable<T>（可返回结果的任务）
        List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                    // 每个任务都会在线程池的某个线程里执行
                    if (CancellationContext.isCancelled()) {
                        return ToolExecutionResult.failed(invocation, "用户取消了此次工具调用");
                    }
                    long startedAt = System.nanoTime();
                    ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
                    return ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt));
                })
                .toList();

        // ⭐ 关键：invokeAll(tasks, timeout, TimeUnit)
        // 含义：
        //   1. 把所有任务提交给线程池
        //   2. 等待所有任务完成，或者超时
        //   3. 返回所有 Future 对象
        // 它会阻塞当前线程直到：
        //   - 所有任务都完成，或
        //   - 超过 toolBatchTimeoutSeconds 秒（例如 90 秒）
        List<Future<ToolExecutionResult>> futures =
                executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

        // ────── 第四步：收集结果 ──────
        List<ToolExecutionResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            ToolInvocation invocation = invocations.get(i);
            Future<ToolExecutionResult> future = futures.get(i);
            
            // 检查该工具是否超时
            if (future.isCancelled()) {
                results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                continue;
            }

            try {
                // 从 Future 取出结果（通常已经完成，不会再等）
                results.add(future.get());
            } catch (InterruptedException e) {
                // 当前线程被打断？
                Thread.currentThread().interrupt();  // 恢复中断状态
                results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
            } catch (ExecutionException e) {
                // 工具执行时抛异常
                Throwable cause = e.getCause();
                String message = cause == null || cause.getMessage() == null
                        ? "未知错误"
                        : cause.getMessage();
                results.add(ToolExecutionResult.failed(invocation, message));
            }
        }
        return results;
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return invocations.stream()
                .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                .toList();
    } finally {
        // 重要：无论如何都要关闭线程池！
        executor.shutdownNow();
    }
}
```

### 为什么要用 ExecutorService？

| 需求 | synchronized 做不了 | ExecutorService 做得到 |
|-----|------------------|-------------------|
| **多个任务并行运行** | ❌ 只能串行 | ✅ `invokeAll()` 同时启动多个线程 |
| **设置超时** | ❌ 没办法 | ✅ `invokeAll(tasks, timeout, TimeUnit)` |
| **获得每个任务的结果** | ❌ 要手写 Thread 和 join | ✅ `Future<T>.get()` |
| **中途取消所有任务** | ❌ 得自己管理 | ✅ `executor.shutdownNow()` |
| **线程复用** | ❌ 每次都创建新线程 | ✅ 线程池复用线程 |

### 实际场景示例

```
【场景 1】LLM 调用 1 个工具：read_file(README.md)
→ 直接执行，不创建线程池（优化路径）
→ 时间：2 秒

【场景 2】LLM 调用 3 个工具：read_file(README.md)、read_file(pom.xml)、execute_command(mvn compile)
→ 创建 3 个线程的线程池
→ 线程 1 执行 read_file(README.md)  → 2 秒完成
→ 线程 2 执行 read_file(pom.xml)    → 1 秒完成
→ 线程 3 执行 execute_command(mvn)  → 5 秒完成
→ invokeAll 阻塞当前线程，等待所有 Future 完成
→ 最多 5 秒就全部完成（并行），而不是 2+1+5=8 秒（串行）

【场景 3】中途用户按 Ctrl+C 取消
→ CancellationContext.isCancelled() 返回 true
→ 所有正在执行的工具立刻返回"已取消"
→ executor.shutdownNow() 强制关闭所有线程
```

### 关键概念速记

```
ExecutorService 的生命周期：
  1. 创建：Executors.newFixedThreadPool(n)
  2. 提交任务：invokeAll(tasks, timeout, TimeUnit)
  3. 等待完成：invokeAll() 会阻塞
  4. 关闭：executor.shutdownNow()
  
Future<T> 的含义：
  - 代表"将来会完成的异步任务"
  - .get()：等待结果，可能阻塞
  - .isCancelled()：是否被取消
  - .get(timeout, unit)：带超时的等待
```

---

## 2. RipgrepCodeSearchEngine.java - 代码搜索的并行处理

### 核心问题场景

**需求**：用户执行 `grep_code` 命令搜索代码库
```
grep_code("TODO", ".", regex=false, max_results=200)
→ 需要在后台运行 ripgrep 进程
→ ripgrep 输出 JSON 流到 stdout
→ 需要实时读取 JSON，同时进程可能在运行
```

**问题**：
- ripgrep 进程在 stdout 不停输出数据
- 如果在主线程 blocked 等待进程完成，就读不到 stdout → 死锁
- 需要**一个独立线程来读 stdout**，主线程才能安心等进程

### 现实代码分析

```java
@Override
public CodeSearchResult search(CodeSearchRequest request) {
    if (Boolean.getBoolean("paicli.search.disable.rg") || !isRipgrepAvailable()) {
        return fallback(request);  // 没装 ripgrep？用 Java 扫描
    }

    Process process = null;
    ExecutorService readerExecutor = null;
    try {
        ProcessBuilder pb = new ProcessBuilder(command(request));
        pb.directory(request.projectRoot().toFile());
        pb.redirectErrorStream(true);
        process = pb.start();  // 启动 ripgrep 进程

        // ────── 关键：创建单独的线程来读 stdout ──────
        readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-rg-reader");
            thread.setDaemon(true);
            return thread;
        });

        Process runningProcess = process;
        
        // 提交一个任务：在独立线程里读取 ripgrep 输出
        Future<ParsedRipgrepOutput> outputFuture = readerExecutor.submit(
                () -> parseOutput(runningProcess.getInputStream(), runningProcess, request)
        );

        // 等待进程完成，最多 8 秒
        boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        
        if (!finished) {
            // 超时了：进程还在运行，强制杀死
            process.destroyForcibly();
            outputFuture.cancel(true);  // 取消读线程
            return new CodeSearchResult("rg", List.of(), true, "rg 搜索超时 " + TIMEOUT.toSeconds() + " 秒");
        }

        // 进程已完成，从 Future 取出读取结果
        ParsedRipgrepOutput parsed = outputFuture.get(1, TimeUnit.SECONDS);
        
        readerExecutor.shutdownNow();
        
        // 检查结果是否完整
        boolean partial = parsed.partial();
        String partialReason = parsed.partialReason();
        if (!partial && parsed.matches().size() >= request.maxResults()) {
            partial = true;
            partialReason = "已达到 max_results=" + request.maxResults();
        }
        return new CodeSearchResult("rg", parsed.matches(), partial, partialReason);
    } catch (Exception e) {
        if (process != null) {
            process.destroyForcibly();
        }
        return fallback(request);
    } finally {
        if (readerExecutor != null) {
            readerExecutor.shutdownNow();
        }
    }
}
```

### 为什么要用 ExecutorService？

**场景描述**：
```
主线程：
  1. 启动 ripgrep 进程
  2. 调用 process.waitFor(8, SECONDS)  ← 这里会阻塞
  3. 进程完成后，取 stdout 数据
  
❌ 错误的方式：
  if (process.waitFor() == 0) {  // 这里阻塞等进程
      // 进程完成了，现在读 stdout
      String output = readInputStream(process.getInputStream());
  }
  ↓ 问题：进程 stdout 缓冲区满了，进程会卡住等我们读
  ↓ 但我们在 waitFor() 里阻塞，读不了
  ↓ 死锁！

✅ 正确的方式：
  // 边等进程，边有独立线程读 stdout
  Future<ParsedRipgrepOutput> outputFuture = executor.submit(
      () -> parseOutput(process.getInputStream())  // 独立线程立刻开始读
  );
  process.waitFor(8, SECONDS);  // 主线程在这里安心等
  ParsedRipgrepOutput result = outputFuture.get();  // 结果已经读好了
```

### 关键 JUC 组件

| 组件 | 作用 | 为什么必须 |
|-----|------|---------|
| **ExecutorService** | 创建单线程线程池 | 避免在主线程读 stdout，否则死锁 |
| **Future<T>** | 代表异步读取结果 | 不用 Future 就得用 volatile + synchronized 自己同步 |
| **TimeUnit** | 超时单位 | `process.waitFor(8, SECONDS)` 8 秒超时 |
| **submit()** | 异步提交任务 | 立刻返回 Future，不等读完 |
| **get(timeout, unit)** | 取结果（带超时） | `outputFuture.get(1, SECONDS)` 最多等 1 秒 |
| **cancel(true)** | 取消任务 | 进程超时时强制中断读线程 |

---

## 3. NotificationRouter.java - MCP 通知异步派发（防死锁）

### 核心问题场景

**MCP 协议**：
- MCP server 可以主动推送通知给 client
- 通知来自 server 的 stdout reader 线程
- client 收到通知后，可能要**发送请求**回 server

**死锁场景**：
```
┌─ stdout reader 线程
│  1. 从 server stdout 读到通知消息
│  2. 调用 handler.accept(notification)
│  3. handler 内部可能调用 sendRequest()
│  4. sendRequest() 等待响应...
│
└─ 但响应也要通过 stdout 读取！
   ↓ 而 stdout reader 现在在 handler 里卡着
   ↓ 没人读 stdout，响应进不来
   ↓ sendRequest() 超时
   ↓ 死锁！
```

### 现实代码分析

```java
public class NotificationRouter implements Consumer<JsonNode>, AutoCloseable {
    private final Map<String, Consumer<JsonNode>> handlers = new ConcurrentHashMap<>();
    
    // ⭐ 关键：单线程线程池，用来异步派发通知
    private final ExecutorService dispatcher;

    public NotificationRouter() {
        AtomicInteger threadId = new AtomicInteger();
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "paicli-mcp-notifications-" + threadId.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void on(String method, Consumer<JsonNode> handler) {
        if (method == null || method.isBlank() || handler == null) {
            return;
        }
        // 用 ConcurrentHashMap 存储，多线程调用 on() 时不互斥
        handlers.put(method, handler);
    }

    @Override
    public void accept(JsonNode message) {
        if (message == null || message.has("id")) {
            return;  // 只处理通知（没有 id 的消息），忽略请求/响应
        }
        
        String method = message.path("method").asText("");
        Consumer<JsonNode> handler = handlers.get(method);
        if (handler == null) {
            return;
        }
        
        JsonNode params = message.path("params");
        
        // ⭐ 关键：不在当前线程（stdout reader）执行 handler
        // 而是提交给 dispatcher 线程池异步执行
        try {
            dispatcher.submit(() -> {
                try {
                    handler.accept(params);  // handler 在独立线程里执行
                } catch (Exception ignored) {
                    // 通知 handler 失败不能影响 transport 流
                }
            });
        } catch (Exception ignored) {
            // executor 已关停（PaiCLI 退出中），忽略
        }
    }

    @Override
    public void close() {
        dispatcher.shutdownNow();
    }
}
```

### 为什么要用 ExecutorService？

**对比方案**：

```java
// ❌ 方案 1：同步执行（会死锁）
@Override
public void accept(JsonNode message) {
    Consumer<JsonNode> handler = handlers.get(method);
    handler.accept(params);  // ← stdout reader 线程在这卡住
                             // ← handler 发请求，等响应
                             // ← 但响应也要从 stdout 读
                             // ↓ 死锁！
}

// ❌ 方案 2：用 synchronized 自己管理线程（复杂且容易出bug）
private Thread handlerThread;
@Override
public void accept(JsonNode message) {
    handlerThread = new Thread(() -> {
        handler.accept(params);
    });
    handlerThread.start();  // ← 每次都创建新线程，浪费
    // ← 而且没法管理线程生命周期
}

// ✅ 方案 3：用 ExecutorService（简洁、安全）
private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(...);
@Override
public void accept(JsonNode message) {
    dispatcher.submit(() -> handler.accept(params));  // ← 异步派发
    // ← 立刻返回，stdout reader 可以继续读
}
```

### 关键 JUC 组件

| 组件 | 作用 | 为什么必须 |
|-----|------|---------|
| **ExecutorService** | 单线程线程池 | 保证通知按顺序派发（单线程），避免竞态 |
| **submit()** | 异步提交 | 不等任务完成就返回，stdout reader 不被阻塞 |
| **ConcurrentHashMap** | handlers 存储 | 多线程调用 on() 不互斥，不用加 synchronized |
| **AtomicInteger** | 线程计数器 | 线程名 id 递增，不需要 synchronized |
| **shutdownNow()** | 关闭 | PaiCLI 退出时停止派发线程 |

---

## 4. LongTermMemory.java - 内存存储的并发安全

### 核心问题场景

**需求**：长期记忆跨对话持久化
```
【会话 1】用户告诉 Agent 一个事实 → /save "MySQL 连接串"
【会话 2】用户打开新会话 → Agent 需要读出之前保存的事实
```

**并发问题**：
- 用户可能在 UI 线程操作（新增/删除/修改记忆）
- Agent 在 agent 线程查询记忆
- 同时可能有后台线程持久化到磁盘
- **需要线程安全**而不能相互阻塞

### 现实代码分析

```java
public class LongTermMemory implements Memory {
    // ⭐ ConcurrentHashMap：线程安全的记忆存储
    private final Map<String, MemoryEntry> entries;
    
    // ⭐ AtomicInteger：线程安全的 token 计数
    private final AtomicInteger tokenCounter;
    
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this.entries = new ConcurrentHashMap<>();  // ✅ 线程安全
        this.tokenCounter = new AtomicInteger(0);  // ✅ 无锁原子操作
        this.mapper = new ObjectMapper();
        // ...
    }

    @Override
    public void store(MemoryEntry entry) {
        // 检查重复（这里可能在 UI 线程或 agent 线程调用）
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;
        }

        entries.put(entry.getId(), entry);  // ✅ ConcurrentHashMap.put() 是线程安全的
        tokenCounter.addAndGet(entry.getTokenCount());  // ✅ 原子操作
        saveToDisk();  // 持久化到磁盘
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));  // ✅ 线程安全的 get()
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        return entries.values().stream()  // ✅ ConcurrentHashMap 的 values() 返回集合视图
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();  // ✅ 原子读，不用 synchronized
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);  // ✅ 线程安全的 remove()
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());  // ✅ 原子减
            saveToDisk();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();  // ✅ 线程安全
        tokenCounter.set(0);  // ✅ 原子设置
        saveToDisk();
    }

    private void saveToDisk() {
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            mapper.writeValue(storageFile, dataList);  // 写磁盘
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
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }
}
```

### 为什么要用 ConcurrentHashMap + AtomicInteger？

**对比方案**：

```java
// ❌ 方案 1：用 synchronized（太粗糙）
private Map<String, MemoryEntry> entries = new HashMap<>();  // 线程不安全

public synchronized void store(MemoryEntry entry) {  // 整个方法加锁
    entries.put(entry.getId(), entry);
}

public synchronized int getTokenCount() {  // 为了读一个数，整个方法加锁
    return tokenCounter;
}
// 问题：
// 1. store() 锁着的时候，getTokenCount() 也得等，效率低
// 2. 存储了大量数据时，keys() 可能 block 很久

// ✅ 方案 2：ConcurrentHashMap + AtomicInteger（细粒度并发）
private Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
private AtomicInteger tokenCounter = new AtomicInteger();

public void store(MemoryEntry entry) {  // 不用加 synchronized
    entries.put(entry.getId(), entry);  // ConcurrentHashMap.put() 自己处理锁
    tokenCounter.addAndGet(entry.getTokenCount());  // AtomicInteger 用 CAS，不用锁
}

public int getTokenCount() {  // 读完全不用锁
    return tokenCounter.get();  // 原子读
}
// 优势：
// 1. store() 执行期间，其他线程仍可读 getTokenCount()
// 2. ConcurrentHashMap 内部分段，多线程可以同时修改不同 bucket
// 3. 没有全局锁竞争
```

### 关键 JUC 组件

| 组件 | 作用 |
|-----|------|
| **ConcurrentHashMap** | entries 存储，并发 put/get/remove 不互斥 |
| **AtomicInteger** | tokenCounter，无锁计数 |

---

## 5. CancellationToken + CancellationContext - 跨线程取消（AtomicBoolean）

### 核心问题场景

**需求**：用户在执行 Agent 任务时按 Ctrl+C 取消
```
【主线程】
  1. 启动 agent 执行任务
  2. agent 在线程池里执行多个工具
  3. 用户按 Ctrl+C
  4. 需要立刻停止所有工具执行

【问题】工具执行在其他线程里，怎么通知它们停止？
```

**传统 synchronized 方案的问题**：
```java
// ❌ 方案 1：用 volatile boolean（不是原子操作）
private volatile boolean cancelled = false;

public void cancel() {
    cancelled = true;  // 可能有可见性问题
}

public boolean isCancelled() {
    return cancelled;  // 多个线程同时读写，可能看不到最新值
}

// ❌ 方案 2：用 synchronized（太慢）
private boolean cancelled = false;

public synchronized void cancel() {
    cancelled = true;  // 加锁很重
}

public synchronized boolean isCancelled() {  // 频繁调用，每次都加锁
    return cancelled;
}
// 问题：工具执行期间每 10ms 检查一次 isCancelled()，每次都加锁很浪费
```

### 现实代码分析

```java
// CancellationToken.java
public class CancellationToken {
    // ⭐ AtomicBoolean：无锁的原子布尔值
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);  // ✅ 原子设置，线程安全
    }

    public boolean isCancelled() {
        // ✅ 原子读，比 synchronized 快 100 倍
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }
}

// CancellationContext.java（线程本地存储）
public final class CancellationContext {
    private static final ThreadLocal<CancellationToken> context = new ThreadLocal<>();

    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        context.set(token);
        return token;
    }

    public static boolean isCancelled() {
        CancellationToken token = context.get();
        return token != null && token.isCancelled();  // ✅ 快速检查
    }

    public static void clear() {
        context.remove();
    }
}
```

### 使用场景

```java
// Agent.java 执行工具
public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
    // 第一步：检查是否被取消
    if (CancellationContext.isCancelled()) {
        return invocations.stream()
                .map(invocation -> ToolExecutionResult.failed(invocation, "用户取消了此次工具调用"))
                .toList();
    }

    // 第二步：创建线程池执行工具
    ExecutorService executor = Executors.newFixedThreadPool(4, ...);
    List<Callable<ToolExecutionResult>> tasks = invocations.stream()
            .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                // 工具执行中每隔一段时间检查取消信号
                if (CancellationContext.isCancelled()) {
                    return ToolExecutionResult.failed(invocation, "已取消");
                }
                // 执行工具...
                return executeToolOutput(invocation.name(), ...);
            })
            .toList();

    // ...
}

// Main.java - CLI 层处理 Ctrl+C
try {
    CancellationToken token = CancellationContext.startRun();
    agent.execute(userInput);  // 执行任务
} catch (InterruptedException e) {
    // 用户按 Ctrl+C 了
    token.cancel();  // ✅ 原子操作，通知所有线程停止
}
```

### 为什么要用 AtomicBoolean？

| 需求 | volatile | synchronized | AtomicBoolean |
|-----|---------|-------------|---------------|
| **线程安全** | ❌ 有 race | ✅ 安全 | ✅ 安全 |
| **性能** | ✅ 快 | ❌ 慢（加锁） | ✅ 快（CAS） |
| **代码清晰** | ❌ 需要 volatile | ✅ 清晰 | ✅ 最清晰 |
| **适合频繁调用** | ❌ 可见性问题 | ❌ 加锁开销大 | ✅ CAS 无锁 |

**为什么 volatile 不够**：
```java
// volatile 保证可见性，但不保证原子性
private volatile boolean cancelled = false;

// Thread 1
cancelled = true;  // 写

// Thread 2
if (cancelled) {   // 读
    // ...
}

// 虽然 Thread 1 的写对 Thread 2 可见，但如果多个线程都在写，可能有竞态
// 例如：
// Thread 1 写 false
// Thread 2 写 true
// 哪个最终值？不确定！
```

### 关键 JUC 组件

| 组件 | 作用 |
|-----|------|
| **AtomicBoolean** | 无锁原子布尔值，频繁读不要加锁 |
| **ThreadLocal** | 线程本地存储，每个线程一份 token |

---

## 6. InlineActivityDisplay.java - 定时刷新UI（ScheduledExecutorService）

### 核心问题场景

**需求**：显示 Agent 执行进度
```
* 思考中... (⠙ 转转转) (3s)
  │ 正在调用 web_search...
  │ 正在处理结果...
```

**需求**：
1. 每 250ms 刷新一次 spinner 动画
2. 显示运行时长
3. 同时不能阻塞 Agent 执行

**传统方案的问题**：
```java
// ❌ 方案 1：在主线程手动循环（会卡UI）
while (isActive) {
    frame++;
    render();
    Thread.sleep(250);  // 这会卡住 agent 执行！
}

// ❌ 方案 2：创建手动 Thread（复杂且容易出bug）
new Thread(() -> {
    while (isActive) {
        frame++;
        render();
        Thread.sleep(250);
    }
}).start();
// 问题：
// 1. 用户中途停止显示时，Thread 还在跑，得手动 interrupt
// 2. 没法设置超时
// 3. 容易忘记清理
```

### 现实代码分析

```java
public final class InlineActivityDisplay implements AutoCloseable {
    private static final int MAX_REASONING_CHARS = 4096;
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    
    private final Terminal terminal;
    private final PrintStream renderLock;
    
    // ⭐ ScheduledExecutorService：定时任务线程池
    private final ScheduledExecutorService scheduler;
    
    // ⭐ ScheduledFuture：代表定时任务
    private ScheduledFuture<?> tickTask;
    
    private boolean active;
    private int frame;
    private long startedNanos;

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock) {
        this.terminal = terminal;
        this.renderLock = renderLock;
        
        // 创建单线程的定时任务线程池
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "paicli-activity-display");
            t.setDaemon(true);
            return t;
        });
    }

    // 开始显示思考动画
    synchronized void begin(String label) {
        if (closed) {
            return;
        }
        clearLocked();
        this.label = (label == null || label.isBlank()) ? "Thinking" : label.trim();
        this.startedNanos = System.nanoTime();
        this.frame = 0;
        this.active = true;
        renderLocked();
        restartTickLocked();  // 启动定时刷新
    }

    // 追加思考内容
    synchronized void appendThinking(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        if (!active) {
            this.active = true;
            this.startedNanos = System.nanoTime();
            this.frame = 0;
            restartTickLocked();  // 如果还没启动，现在启动
        }
        reasoning.append(delta);
        trimReasoning();
        renderLocked();
    }

    synchronized void end() {
        if (closed) {
            return;
        }
        active = false;
        cancelTickLocked();  // 停止定时任务
        reasoning.setLength(0);
        clearLocked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        cancelTickLocked();  // 停止定时任务
        reasoning.setLength(0);
        clearLocked();
        scheduler.shutdownNow();  // 关闭线程池
    }

    // ⭐ 启动定时任务：每 250ms 执行一次 tick()
    private void restartTickLocked() {
        cancelTickLocked();  // 先取消旧任务
        
        // 创建定时任务：初始延迟 250ms，之后每 250ms 执行一次
        tickTask = scheduler.scheduleAtFixedRate(
            this::tick,           // 要执行的任务
            250,                  // 初始延迟
            250,                  // 周期
            TimeUnit.MILLISECONDS // 时间单位
        );
    }

    private void cancelTickLocked() {
        if (tickTask != null) {
            tickTask.cancel(false);  // 取消任务，不打断正在执行的
            tickTask = null;
        }
    }

    // 每 250ms 执行一次
    private void tick() {
        synchronized (this) {
            if (!active || closed) {
                return;
            }
            frame++;  // spinner 切到下一帧
            renderLocked();  // 重绘
        }
    }

    private void renderLocked() {
        if (!active || closed) {
            return;
        }
        synchronized (renderLock) {
            PrintWriter writer = terminalWriter();
            clearRenderedArea(writer);
            List<AttributedString> lines = buildLines();
            for (int i = 0; i < lines.size(); i++) {
                writer.print(lines.get(i).toAnsi(terminal));
                writer.print(AnsiSeq.CLEAR_TO_EOL);
                if (i < lines.size() - 1) {
                    writer.print('\n');
                }
            }
            renderedRows = lines.size();
            writer.flush();
            terminal.flush();
        }
    }

    private List<AttributedString> buildLines() {
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        List<AttributedString> lines = new ArrayList<>();
        
        String suffix = " (esc to cancel, " + elapsedSeconds() + "s)";  // 显示运行时长
        lines.add(fit("  " + spinner() + " " + label + "..." + suffix, cols, STATUS_STYLE));
        
        // 显示最近的推理内容预览（最多 4 行）
        List<String> quoteLines = reasoningLines();
        // ...

        return lines;
    }

    private String spinner() {
        return SPINNER_FRAMES[Math.floorMod(frame, SPINNER_FRAMES.length)];
    }

    private long elapsedSeconds() {
        return Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos));
    }
}
```

### 为什么要用 ScheduledExecutorService？

**对比方案**：

```java
// ❌ 方案 1：手动 Thread + sleep（复杂）
private Thread tickThread;

void begin() {
    tickThread = new Thread(() -> {
        while (active) {
            try {
                frame++;
                render();
                Thread.sleep(250);  // ← 不精确，可能会漂移
            } catch (InterruptedException e) {
                break;
            }
        }
    });
    tickThread.start();
}

void end() {
    active = false;
    if (tickThread != null) {
        tickThread.interrupt();  // ← 得手动打断
        try {
            tickThread.join();    // ← 得手动等待
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
// 问题：
// 1. 代码复杂，容易忘记 cleanup
// 2. sleep() 精度不够，timing 会漂移
// 3. 没有取消 mechanism

// ✅ 方案 2：ScheduledExecutorService（简洁）
private ScheduledFuture<?> tickTask;
private final ScheduledExecutorService scheduler = 
    Executors.newSingleThreadScheduledExecutor(...);

void begin() {
    tickTask = scheduler.scheduleAtFixedRate(
        this::tick,     // 要执行的任务
        250,            // 初始延迟
        250,            // 周期（精确）
        TimeUnit.MILLISECONDS
    );
}

void end() {
    if (tickTask != null) {
        tickTask.cancel(false);  // ← 一行代码取消
    }
}

void close() {
    scheduler.shutdownNow();  // ← 一行代码关闭
}
// 优点：
// 1. 代码简洁，只需 2 行
// 2. 精确的周期（基于系统定时器）
// 3. 自动 cleanup
```

### 关键 JUC 组件

| 组件 | 作用 |
|-----|------|
| **ScheduledExecutorService** | 定时任务线程池 |
| **scheduleAtFixedRate()** | 周期执行任务（固定周期） |
| **ScheduledFuture** | 代表定时任务，可取消 |
| **TimeUnit** | 精确的时间单位（MILLISECONDS） |
| **cancel()** | 停止定时任务 |
| **shutdownNow()** | 关闭线程池 |

---

## 7. TuiHitlHandler.java - HITL 审批的并发安全（ConcurrentHashMap）

### 核心问题场景

**需求**：HITL (Human In The Loop) 审批机制
```
【场景】Agent 要执行危险操作（如 execute_command）时需要人工审批
  1. Agent 线程：发起审批请求 → 等待用户批准/拒绝
  2. UI 线程：用户在弹窗里点 "批准所有"
  3. 存储"该工具已批准"的状态
  4. 后续调用同一工具时不再弹窗

【并发问题】
  - Agent 线程在查询：isApprovedAllByTool("execute_command")?
  - UI 线程在修改：approvedAllByTool.add("execute_command")
  - 需要线程安全，且不能互相阻塞
```

### 现实代码分析

```java
public class TuiHitlHandler implements HitlHandler {
    private volatile boolean enabled = true;

    // ⭐ ConcurrentHashMap.newKeySet()：创建线程安全的 Set
    // 比起 Collections.synchronizedSet()，它用分段锁，并发度高
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();

    private final WindowBasedTextGUI gui;

    public TuiHitlHandler(WindowBasedTextGUI gui) {
        this.gui = gui;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;  // 用 volatile boolean
    }

    // ⭐ 查询操作：Agent 线程频繁调用
    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
        // ✅ ConcurrentHashMap.contains() 不加全局锁
        // ✅ 其他线程同时修改不会 block
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllByServer.contains(serverName);
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        if (!enabled) {
            return ApprovalResult.approve();
        }

        String mcpServer = com.paicli.hitl.ApprovalPolicy.mcpServerName(request.toolName());
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();

        // 检查是否已全部放行
        if (!sensitivePerCall && isApprovedAllByTool(request.toolName())) {
            return ApprovalResult.approveAll();
        }
        if (!sensitivePerCall && isApprovedAllByServer(mcpServer)) {
            return ApprovalResult.approveAllByServer();
        }

        // 显示审批弹窗（UI 线程）
        String title = "⚠️  HITL 审批请求";
        String content = request.toDisplayText() + "\n\nYes: 批准本次  No: 拒绝  Cancel: 跳过";

        MessageDialogBuilder dialog = new MessageDialogBuilder()
                .setTitle(title)
                .setText(content)
                .addButton(MessageDialogButton.Yes)
                .addButton(MessageDialogButton.No)
                .addButton(MessageDialogButton.Cancel);
        MessageDialogButton decision = dialog.build().showDialog(gui);

        // 用户点了"批准所有"？更新 Set
        if (decision == MessageDialogButton.Yes) {
            // 这里可能会把 toolName 加进 approvedAllByTool
            // ✅ ConcurrentHashMap.add() 线程安全
            return ApprovalResult.approve();
        }
        if (decision == MessageDialogButton.Cancel) {
            return ApprovalResult.skip();
        }
        return ApprovalResult.reject("用户在 TUI 中拒绝");
    }

    // ⭐ 清除批准记录（可能在 Agent 重置时调用）
    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();  // ✅ 线程安全
        approvedAllByServer.clear();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null) {
            approvedAllByServer.remove(serverName);  // ✅ 线程安全
        }
    }
}
```

### 为什么要用 ConcurrentHashMap？

**对比方案**：

```java
// ❌ 方案 1：Collections.synchronizedSet()（全局锁）
private final Set<String> approvedAllByTool = 
    Collections.synchronizedSet(new HashSet<>());

public boolean isApprovedAllByTool(String toolName) {
    // 每次 contains() 都加全局锁！
    return approvedAllByTool.contains(toolName);
}
// 问题：
// - Agent 线程在 contains() 时，UI 线程 remove() 得等
// - 频繁调用 contains()，会成为性能瓶颈

// ✅ 方案 2：ConcurrentHashMap.newKeySet()（分段锁）
private final Set<String> approvedAllByTool = 
    ConcurrentHashMap.newKeySet();

public boolean isApprovedAllByTool(String toolName) {
    // ConcurrentHashMap 内部分段，不加全局锁
    return approvedAllByTool.contains(toolName);  // ✅ 高效
}
// 优点：
// - 多个线程可以同时读
// - 修改只影响对应 segment，其他 segment 不受影响
// - 性能 10 倍以上提升
```

### 关键 JUC 组件

| 组件 | 作用 |
|-----|------|
| **ConcurrentHashMap.newKeySet()** | 线程安全的 Set，比 synchronizedSet 快 |
| **volatile boolean** | 简单状态标志（enabled），无需原子性 |

---

## 8. MCP Transport 的 CopyOnWriteArrayList（异步监听器）

### 核心问题场景

**MCP 协议**：Client 需要监听 server 推送的消息
```
【设计】
  listener1.onMessage(msg);  ← 调用多个监听器
  listener2.onMessage(msg);
  listener3.onMessage(msg);

【问题】
  1. 正在遍历 listeners 列表
  2. 同时有线程要 add/remove listener
  3. ConcurrentModificationException！
```

### 现实代码分析

```java
// StdioTransport.java - MCP 通过 stdio 通信
public class StdioTransport implements McpTransport {
    private final Process process;
    private final BufferedWriter stdin;
    
    // ⭐ CopyOnWriteArrayList：监听器列表
    // 适合"读多写少"的场景（频繁遍历，很少修改）
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();

    // ...

    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);  // ✅ 线程安全，创建新数组（复制）
        }
    }

    // 当收到 server 推送的消息时
    private void handleMessage(JsonNode message) {
        // ⭐ 遍历所有监听器，调用它们
        for (Consumer<JsonNode> listener : listeners) {
            try {
                listener.accept(message);  // ✅ 即使有线程在 add/remove，也不会 CME
            } catch (Exception e) {
                log.warn("监听器处理失败", e);
            }
        }
    }
}

// StreamableHttpTransport.java - MCP 通过 HTTP 流通信
public class StreamableHttpTransport implements McpTransport {
    private final String url;
    private final Map<String, String> headers;
    
    // ⭐ 同样用 CopyOnWriteArrayList
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();

    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // HTTP 长连接收到消息时
    private void handleServerSentMessage(JsonNode message) {
        for (Consumer<JsonNode> listener : listeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                log.warn("监听器处理失败", e);
            }
        }
    }
}

// JsonRpcClient.java
public class JsonRpcClient {
    private final List<Consumer<JsonNode>> notificationListeners = 
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public void onNotification(Consumer<JsonNode> listener) {
        notificationListeners.add(listener);
    }

    private void dispatchNotification(JsonNode notification) {
        for (Consumer<JsonNode> listener : notificationListeners) {
            try {
                listener.accept(notification);
            } catch (Exception ignored) {
                // 通知派发失败不能影响其他监听器
            }
        }
    }
}
```

### 为什么要用 CopyOnWriteArrayList？

**对比方案**：

```java
// ❌ 方案 1：ArrayList + synchronized（容易出bug）
private final List<Consumer<JsonNode>> listeners = new ArrayList<>();

public synchronized void onReceive(Consumer<JsonNode> listener) {
    listeners.add(listener);
}

private synchronized void handleMessage(JsonNode message) {
    for (Consumer<JsonNode> listener : listeners) {
        listener.accept(message);  // ← 遍历期间不能修改！
    }
}
// 问题：
// 1. onReceive() 期间不能 dispatch，反之亦然
// 2. handleMessage() 加 synchronized，可能 block 太久
// 3. listener.accept() 如果很慢，其他线程得等

// ✅ 方案 2：CopyOnWriteArrayList（读多写少）
private final List<Consumer<JsonNode>> listeners = 
    new CopyOnWriteArrayList<>();

public void onReceive(Consumer<JsonNode> listener) {
    listeners.add(listener);  // ← add 时创建新数组
}

private void handleMessage(JsonNode message) {
    for (Consumer<JsonNode> listener : listeners) {
        listener.accept(message);  // ← 遍历不会 CME
    }
}
// 优点：
// 1. 遍历不加锁（readers 很快）
// 2. add/remove 虽然创建新数组，但次数少
// 3. 整体吞吐高
```

**内部机制**：

```java
// CopyOnWriteArrayList 的核心逻辑
public class CopyOnWriteArrayList<E> {
    private volatile Object[] array;  // 当前数组
    private final Object lock = new Object();

    public void add(E e) {
        synchronized (lock) {  // ← 写操作加锁
            Object[] oldArray = array;
            Object[] newArray = Arrays.copyOf(oldArray, oldArray.length + 1);
            newArray[oldArray.length] = e;
            array = newArray;  // ← 原子替换
        }
    }

    public Iterator<E> iterator() {
        // ← 读操作不加锁！返回当前数组的快照
        return new ArrayIterator(array);
    }
}

// 场景演示
【时刻 1】listener = [L1, L2]
【时刻 2】主线程开始遍历：
           for (Listener l : listeners) { ... }
           ↓ 此时迭代器绑定到 array = [L1, L2] 的快照
【时刻 3】同时 UI 线程 add(L3)：
           synchronized (lock) {
               array = [L1, L2, L3];  // ← 创建新数组
           }
【时刻 4】主线程继续遍历原快照：[L1, L2]
           ↓ 不会看到 L3，但也不会 CME！
【时刻 5】主线程下次遍历时才会看到 L3
```

### 关键 JUC 组件

| 组件 | 作用 | 场景 |
|-----|------|------|
| **CopyOnWriteArrayList** | 线程安全的 List，读不加锁 | 监听器列表（read >> write） |
| **volatile Object[]** | 原子引用替换 | 新数组替换旧数组 |

---

## 9. DurableTaskManager - 数据库任务队列（ConcurrentHashMap + synchronized）

### 核心问题场景

**需求**：持久化任务队列（可恢复）
```
【场景】Runtime API 接收异步任务
  1. API 收到 POST /task 创建任务
  2. 任务存进 SQLite 数据库
  3. 多个 worker 线程竞争领取任务执行
  4. 执行完后标记为 COMPLETED/FAILED
  5. 任务应该持久化，即使 PaiCLI 重启也能查询

【并发问题】
  - 多个 worker 同时竞争 claimNext()（先来先得）
  - 同时用户可能查询 list() / find()
  - 保存结果时需要原子性（不能一半 update 了，一半没）
```

### 现实代码分析

```java
public class DurableTaskManager implements Closeable {
    private static final String JDBC_URL = "jdbc:sqlite:...";
    private final Path dbPath;
    private final TaskRunner runner;
    private final int workerCount;
    
    // ⭐ ConcurrentHashMap：内存缓存（快速查询）
    private final Map<String, DurableTask> taskCache = new ConcurrentHashMap<>();
    
    // ⭐ ExecutorService：多个 worker 线程处理任务
    private final ExecutorService workers;
    
    private Connection dbConnection;
    private volatile boolean running = false;

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount) throws SQLException {
        this.dbPath = dbPath;
        this.runner = runner;
        this.workerCount = workerCount;
        this.workers = Executors.newFixedThreadPool(
            workerCount,
            r -> {
                Thread t = new Thread(r, "paicli-task-worker");
                t.setDaemon(true);
                return t;
            }
        );
        initDatabase();
    }

    public synchronized void start() throws SQLException {
        if (running) return;
        running = true;
        
        // 启动 N 个 worker 线程
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::workerLoop);  // 每个 worker 一个线程
        }
    }

    public synchronized DurableTask enqueue(String prompt) throws SQLException {
        DurableTask task = new DurableTask(UUID.randomUUID().toString(), prompt);
        
        // 插入数据库
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.executeUpdate("INSERT INTO tasks (id, status, prompt, created_at) VALUES (" +
                    "'" + task.id() + "', 'QUEUED', ..., NOW())");
        }
        
        // 缓存（并发读）
        taskCache.put(task.id(), task);  // ✅ ConcurrentHashMap，不加锁
        
        return task;
    }

    public synchronized List<DurableTask> list(int limit) throws SQLException {
        // 从缓存读（快速）
        return taskCache.values().stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    public synchronized Optional<DurableTask> find(String id) throws SQLException {
        // 从缓存读
        return Optional.ofNullable(taskCache.get(id));  // ✅ 快速
    }

    // ⭐ 关键：worker 线程竞争领取任务
    // synchronized 确保同一时刻只有一个 worker 领取
    private synchronized DurableTask claimNext() throws SQLException {
        try (Statement stmt = dbConnection.createStatement()) {
            // SQL：SELECT 最老的 QUEUED 任务，并立刻 UPDATE 为 RUNNING
            ResultSet rs = stmt.executeQuery(
                "SELECT * FROM tasks WHERE status = 'QUEUED' ORDER BY created_at ASC LIMIT 1"
            );
            if (rs.next()) {
                String taskId = rs.getString("id");
                
                // 原子地更新状态为 RUNNING
                stmt.executeUpdate(
                    "UPDATE tasks SET status = 'RUNNING', started_at = NOW() WHERE id = '" + taskId + "'"
                );
                
                return fromRow(rs);
            }
        }
        return null;
    }

    // 每个 worker 线程不停地执行这个循环
    private void workerLoop() {
        while (running) {
            try {
                DurableTask task = claimNext();  // ⭐ synchronized，同一时刻只有一个 worker 拿到
                if (task == null) {
                    Thread.sleep(1000);  // 没任务，休眠 1 秒
                    continue;
                }

                // 执行任务（不在 synchronized 块里）
                TaskResult result = runner.run(task.prompt());

                // 标记为完成
                markTerminal(task.id(), TaskStatus.COMPLETED, result.output(), null, Instant.now());

            } catch (Exception e) {
                // ...
            }
        }
    }

    // ⭐ 标记任务完成（原子更新）
    private synchronized void markTerminal(String id, TaskStatus status, String result, 
                                          String error, Instant startedAt) throws SQLException {
        try (Statement stmt = dbConnection.createStatement()) {
            // UPDATE 数据库
            stmt.executeUpdate(
                "UPDATE tasks SET status = '" + status + "', result = '" + result + "', " +
                "completed_at = NOW() WHERE id = '" + id + "'"
            );
            
            // 更新缓存
            DurableTask updated = find(id).orElse(null);
            if (updated != null) {
                taskCache.put(id, updated);  // ✅ ConcurrentHashMap，其他线程可同时读
            }
        }
    }

    private void initTables() throws SQLException {
        // CREATE TABLE tasks (...)
    }

    private synchronized void recoverRunningTasks() throws SQLException {
        // 重启后恢复未完成的任务
    }

    @Override
    public void close() throws IOException {
        running = false;
        workers.shutdownNow();
    }
}
```

### 为什么要用 synchronized + ConcurrentHashMap？

```java
// ❌ 方案 1：无锁（race condition）
private DurableTask claimNext() throws SQLException {
    // 假如两个 worker 同时执行
    ResultSet rs1 = SELECT ... WHERE status = 'QUEUED' LIMIT 1;  // Worker 1 读到 task#123
    ResultSet rs2 = SELECT ... WHERE status = 'QUEUED' LIMIT 1;  // Worker 2 也读到 task#123
    // ↓ 两个 worker 都认为拿到了 task#123
    // ↓ 都去执行，导致重复执行！
    UPDATE tasks SET status = 'RUNNING' WHERE id = 'task#123';   // Worker 1
    UPDATE tasks SET status = 'RUNNING' WHERE id = 'task#123';   // Worker 2
}

// ✅ 方案 2：synchronized + SELECT...UPDATE 原子性
private synchronized DurableTask claimNext() throws SQLException {
    // 同一时刻只有一个 worker 进这个方法
    ResultSet rs = SELECT ... WHERE status = 'QUEUED' LIMIT 1;
    if (rs.next()) {
        String taskId = rs.getString("id");
        UPDATE tasks SET status = 'RUNNING' WHERE id = taskId;  // ✅ 原子
        // ↓ 其他 worker 在这个方法外阻塞，下轮循环才能进来
        return fromRow(rs);
    }
    return null;
}

// ✅ 方案 3：ConcurrentHashMap 缓存 + synchronized DB 操作
public Optional<DurableTask> find(String id) {
    // 缓存命中，立刻返回（读多，无锁）
    return Optional.ofNullable(taskCache.get(id));
}

private synchronized void markTerminal(...) {
    // DB 操作用 synchronized（写少）
    stmt.executeUpdate(...);
    // 然后更新缓存（无锁）
    taskCache.put(id, updated);
}
// 优点：
// - 大部分读操作不用等（从缓存读）
// - 只有 DB 写操作时加锁（次数少）
```

### 关键 JUC 组件

| 组件 | 作用 |
|-----|------|
| **ConcurrentHashMap** | 任务缓存，读不加锁 |
| **ExecutorService** | 多个 worker 线程 |
| **synchronized** | claimNext() 和 markTerminal() 用于 DB 操作原子性 |

---

## 10. 总结表格 - 快速查阅

### 按 JUC 工具分类

| JUC 工具 | 文件 | 场景 | 为什么不能用 synchronized |
|---------|------|------|--------------------------|
| **ExecutorService** | ToolRegistry.java | 并行执行多个工具 | synchronized 只能串行，效率太低 |
| **ExecutorService** | RipgrepCodeSearchEngine.java | 后台线程读进程输出，避免死锁 | 如果不用独立线程，主线程会卡住 |
| **ScheduledExecutorService** | InlineActivityDisplay.java | 定时刷新 UI（每 250ms） | Thread.sleep() 精度低，代码复杂 |
| **ConcurrentHashMap** | LongTermMemory.java | 记忆存储，频繁读写 | synchronized 会变成全局锁瓶颈 |
| **ConcurrentHashMap** | TuiHitlHandler.java | 批准黑名单，读多写少 | 没必要每次都加全局锁 |
| **ConcurrentHashMap** | DurableTaskManager.java | 任务缓存 | 大多数读操作不用等 |
| **CopyOnWriteArrayList** | StdioTransport.java | 监听器列表，遍历多 | 并发修改会 CME，Collections.synchronized 全锁 |
| **CopyOnWriteArrayList** | StreamableHttpTransport.java | 监听器列表 | 同上 |
| **CopyOnWriteArrayList** | JsonRpcClient.java | 通知监听器列表 | 同上 |
| **AtomicBoolean** | CancellationToken.java | 取消信号，频繁读 | volatile 可见性有问题，synchronized 太慢 |
| **AtomicInteger** | LongTermMemory.java | Token 计数 | 原子操作，无需锁 |
| **AtomicInteger** | NotificationRouter.java | 线程计数器 | 只是简单计数，不需要锁 |
| **AtomicLong** | NetworkPolicy.java | 速率限制计数 | 频繁累加，不能用锁 |
| **AtomicReference** | CancellationContext.java | 线程本地 token 引用 | 需要原子更新引用 |
| **CountDownLatch** | 测试代码 | 等待 N 个事件同时完成 | synchronized 无法表达这种同步 |
| **Future<T>** | ToolRegistry.java | 获取异步任务结果 | Thread.join() 不能获得返回值 |
| **TimeUnit** | 所有地方 | 超时单位统一、转换安全 | 手写单位转换容易出错 |

---

### 按使用场景分类

#### 1. 并行任务执行
```java
场景：需要多个任务同时运行
工具：ExecutorService + Future
文件：ToolRegistry.java, RipgrepCodeSearchEngine.java
为什么：synchronized 只能串行，不能并行
```

#### 2. 后台定时任务
```java
场景：每 N 毫秒执行一次
工具：ScheduledExecutorService + ScheduledFuture
文件：InlineActivityDisplay.java
为什么：Thread.sleep() 精度低，管理复杂
```

#### 3. 高并发读写
```java
场景：频繁读，很少写
工具：ConcurrentHashMap（读多写少场景）
文件：LongTermMemory.java, TuiHitlHandler.java, DurableTaskManager.java
为什么：synchronized 会变成全局锁，成为瓶颈
```

#### 4. 监听器列表（读多写少极端情况）
```java
场景：遍历列表很频繁，但很少 add/remove
工具：CopyOnWriteArrayList
文件：StdioTransport.java, StreamableHttpTransport.java, JsonRpcClient.java
为什么：Collections.synchronizedList 每次遍历都加全局锁，CopyOnWrite 读不加锁
```

#### 5. 单个变量的原子操作
```java
场景：只是简单读/写，不需要复杂同步
工具：AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference
文件：CancellationToken.java, LongTermMemory.java 等
为什么：频繁调用会成为瓶颈，Atomic 用 CAS 无锁，性能高 100 倍
```

#### 6. 多线程同步屏障
```java
场景：等待 N 个事件都发生
工具：CountDownLatch
文件：测试代码
为什么：synchronized 无法表达这种同步语义
```

---

## 11. 设计模式速记

### 模式 1：ExecutorService 并行执行

```java
// 场景：需要并行执行多个任务，保留结果顺序，支持超时
public List<Result> executeParallel(List<Task> tasks) {
    int parallelism = Math.min(tasks.size(), MAX_PARALLEL);
    ExecutorService executor = Executors.newFixedThreadPool(parallelism);
    
    try {
        List<Callable<Result>> callables = tasks.stream()
            .map(task -> (Callable<Result>) () -> task.execute())
            .toList();
        
        List<Future<Result>> futures = executor.invokeAll(callables, timeout, SECONDS);
        
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Future<Result> future = futures.get(i);
            if (future.isCancelled()) {
                results.add(Result.timedOut());
            } else {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    results.add(Result.failed(e.getCause()));
                }
            }
        }
        return results;
    } finally {
        executor.shutdownNow();
    }
}
```

### 模式 2：ScheduledExecutorService 定时任务

```java
// 场景：每 N ms 执行一次，可随时停止
private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(...);
private ScheduledFuture<?> task;

public void startTick() {
    task = scheduler.scheduleAtFixedRate(
        this::tick,              // 要执行的任务
        initialDelay,            // 初始延迟
        period,                  // 周期
        TimeUnit.MILLISECONDS
    );
}

public void stopTick() {
    if (task != null) {
        task.cancel(false);  // 不打断正在执行的
    }
}

public void close() {
    scheduler.shutdownNow();
}
```

### 模式 3：ConcurrentHashMap + 少量 synchronized

```java
// 场景：大部分读操作，少数写操作
private final Map<String, Data> cache = new ConcurrentHashMap<>();

public Data get(String key) {
    return cache.get(key);  // ✅ 无锁读
}

public void update(String key, Data value) {
    // ✅ 写操作可以加 synchronized（次数少）
    // 或者不加（最终一致性接受）
    cache.put(key, value);
}

public void delete(String key) {
    cache.remove(key);  // ✅ 无锁删除
}
```

### 模式 4：CopyOnWriteArrayList + 频繁遍历

```java
// 场景：频繁遍历，很少修改
private final List<Listener> listeners = new CopyOnWriteArrayList<>();

public void register(Listener listener) {
    listeners.add(listener);  // ✅ 创建新数组，代价相对大
}

public void dispatchEvent(Event event) {
    // ✅ 遍历不加锁，即使其他线程在修改
    for (Listener listener : listeners) {
        listener.onEvent(event);
    }
}
```

### 模式 5：Atomic* + 频繁查询

```java
// 场景：频繁查询状态，很少修改
private final AtomicBoolean cancelled = new AtomicBoolean(false);
private final AtomicInteger counter = new AtomicInteger(0);

public void cancel() {
    cancelled.set(true);  // ✅ 原子设置
}

public boolean isCancelled() {
    // ✅ 原子读，不用 synchronized
    return cancelled.get();
}

public void increment() {
    counter.incrementAndGet();  // ✅ 原子累加
}
```

---

## 12. 常见错误与最佳实践

### ❌ 错误 1：用 synchronized 做高并发读

```java
// 错误
private synchronized List<Data> readAll() {
    return new ArrayList<>(dataMap.values());  // 锁住了所有 readers
}

// 正确
private final Map<String, Data> dataMap = new ConcurrentHashMap<>();

private List<Data> readAll() {
    return new ArrayList<>(dataMap.values());  // ✅ 无锁
}
```

### ❌ 错误 2：创建线程而不用线程池

```java
// 错误
for (Task task : tasks) {
    new Thread(() -> task.execute()).start();  // 每次创建线程，资源泄漏
}

// 正确
ExecutorService executor = Executors.newFixedThreadPool(10);
for (Task task : tasks) {
    executor.submit(() -> task.execute());  // ✅ 线程复用
}
executor.shutdownNow();
```

### ❌ 错误 3：在 synchronized 块里做耗时操作

```java
// 错误
public synchronized void process(List<Data> items) {
    for (Data item : items) {
        Thread.sleep(1000);  // 锁住了，其他线程都得等 1 秒
        heavyCompute(item);
    }
}

// 正确
public void process(List<Data> items) {
    for (Data item : items) {
        Thread.sleep(1000);  // ✅ 没加锁，其他线程可以进来
        heavyCompute(item);
    }
}
```

### ❌ 错误 4：忘记关闭 ExecutorService

```java
// 错误
public void executeTools(List<Tool> tools) {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    executor.invokeAll(...);
    // 忘记 shutdownNow()，线程一直运行！
}

// 正确
public void executeTools(List<Tool> tools) {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
        executor.invokeAll(...);
    } finally {
        executor.shutdownNow();  // ✅ 一定要关
    }
}
```

### ❌ 错误 5：用 Future 但忘记处理异常

```java
// 错误
Future<Result> future = executor.submit(() -> doWork());
Result result = future.get();  // 如果 doWork() 抛异常，这里会 ExecutionException

// 正确
Future<Result> future = executor.submit(() -> doWork());
try {
    Result result = future.get();
} catch (ExecutionException e) {
    // ✅ 处理工作线程的异常
    log.error("任务执行失败", e.getCause());
}
```

### ✅ 最佳实践 1：选择合适的并发工具

```
                高并发读？    低并发写？   用什么
                ───────────────────────────
HashMap         ✅ 是        ✅ 是      → ConcurrentHashMap
HashSet         ✅ 是        ✅ 是      → ConcurrentHashMap.newKeySet()
ArrayList       ❌ 不        ✅ 很少    → CopyOnWriteArrayList
boolean         ✅ 频繁      ✅ 很少    → AtomicBoolean
int counter     ✅ 频繁      ✅ 很少    → AtomicInteger
Object ref      ✅ 频繁      ✅ 很少    → AtomicReference
```

### ✅ 最佳实践 2：优先用 JUC 而不是 synchronized

```java
// ❌ 过时的方法
private synchronized void method1() { ... }
private final Object lock = new Object();
public void method2() {
    synchronized (lock) { ... }
}

// ✅ 推荐
private ExecutorService executor = Executors.newFixedThreadPool(4);  // 并发任务
private Map<K, V> map = new ConcurrentHashMap<>();  // 高并发读写
private AtomicInteger counter = new AtomicInteger();  // 原子计数
```

### ✅ 最佳实践 3：用 try-finally 保证资源释放

```java
ExecutorService executor = Executors.newFixedThreadPool(4);
try {
    // 使用 executor
    executor.invokeAll(...);
} finally {
    // ✅ 无论如何都要关闭
    executor.shutdownNow();
}
```

---

## 13. 学习建议

### 你应该重点掌握的（按优先级）

1. **ExecutorService + Future**（最常用）
   - 什么时候用线程池 vs 直接 new Thread
   - invokeAll() vs execute() vs submit()
   - Future.get() 的阻塞语义

2. **ConcurrentHashMap**（解决 95% 的并发问题）
   - 什么时候用 synchronized HashMap
   - 分段锁原理（高级，可选）

3. **AtomicInteger/AtomicBoolean**（频繁查询状态时）
   - 什么时候用 volatile 不够
   - CAS 原理（高级，可选）

4. **CountDownLatch**（多线程同步）
   - 等待 N 个事件都发生的场景

5. **其他工具**（按需学习）
   - CopyOnWriteArrayList（读多写少）
   - ScheduledExecutorService（定时任务）
   - Semaphore, Barrier（高级）

### 推荐练习

```
【练习 1】写一个工具执行器，支持并行执行 3 个任务，设置超时
【练习 2】写一个缓存系统，支持多线程读写
【练习 3】写一个事件系统，支持监听器注册和事件派发
【练习 4】实现一个简单的任务队列，支持多个 worker 竞争
【练习 5】实现一个定时任务调度器，每 1 秒执行一次
```

---

## 14. 参考资源

- Java Concurrency in Practice（书籍，必读）
- Java 官方文档：https://docs.oracle.com/javase/tutorial/essential/concurrency/
- JUC Javadoc：https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/package-summary.html

---

## 总结：PaiCLI 为什么用 JUC？

**核心哲学**：
1. **避免全局锁**：用细粒度的并发原语而不是 synchronized
2. **线程复用**：用线程池而不是频繁创建线程
3. **异步非阻塞**：用 Future/Callable 而不是 Thread.join()
4. **最适当的工具**：
   - 并行任务 → ExecutorService
   - 高并发读 → ConcurrentHashMap/CopyOnWriteArrayList
   - 原子操作 → AtomicInteger/AtomicBoolean
   - 定时任务 → ScheduledExecutorService

**结果**：
- ✅ 工具调用从 8 秒（串行）降到 5 秒（并行）
- ✅ 内存查询不再互相阻塞
- ✅ UI 动画流畅（不卡顿）
- ✅ 代码更清晰（用 Future 而不是 Thread.join()）

