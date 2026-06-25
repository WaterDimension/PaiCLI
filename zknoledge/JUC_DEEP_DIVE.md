# PaiCLI 项目 JUC 并发编程深度讲解

> 针对刚学完 JUC 的开发者，通过 PaiCLI 项目的真实案例深入理解 Java 并发工具的应用

**目录**
- [专题 1：ToolRegistry - ExecutorService 工具并行执行](#专题-1toolregistry---executorservice-工具并行执行)
- [专题 2：RipgrepCodeSearchEngine - 进程 IO 读取的死锁问题](#专题-2ripgrepcodesearchengine---进程-io-读取的死锁问题)
- [专题 3：NotificationRouter - 异步派发避免死锁](#专题-3notificationrouter---异步派发避免死锁)
- [专题 4：InlineActivityDisplay - 定时任务的 UI 刷新](#专题-4inlineactivitydisplay---定时任务的-ui-刷新)
- [专题 5：LongTermMemory - 内存存储的并发安全](#专题-5longtermmemory---内存存储的并发安全)
- [专题 6：CancellationToken - 取消信号的原子操作](#专题-6cancellationtoken---取消信号的原子操作)
- [专题 7：TuiHitlHandler - HITL 审批的并发集合](#专题-7tuihitlhandler---hitl-审批的并发集合)
- [专题 8：MCP Transport - 监听器列表的 CopyOnWriteArrayList](#专题-8mcp-transport---监听器列表的-copyonwritearraylist)

---

## 专题 1：ToolRegistry - ExecutorService 工具并行执行

### 【场景描述】

PaiCLI 是一个 AI Agent CLI，常需要**同时执行多个工具**（如读文件、执行命令、搜索代码）并汇总结果。
用户输入一条指令后，LLM 可能产生多个 tool-call，这些工具调用需要：
1. **尽快完成**（不能串行等待）
2. **保证顺序**（结果顺序要和请求顺序一致）
3. **可超时**（8秒内必须返回，不能无限等待）
4. **支持取消**（用户按 Ctrl+C 时全部中止）

### 【如果不用 JUC 会怎样】

**错误做法 1：串行执行**
```java
// ❌ 太慢了！用户等待 tool A (2s) + tool B (2s) + tool C (2s) = 6s
for (ToolInvocation invocation : invocations) {
    ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
    results.add(ToolExecutionResult.completed(invocation, output, ...));
}
```

**错误做法 2：用 synchronized 锁**
```java
// ❌ 串行化的锁，完全没利用多线程优势
synchronized (this) {
    // 每个 tool 一个一个执行，还是 6s
}
```

**错误做法 3：没有超时的自建线程**
```java
// ❌ 如果某个 tool 卡住了（死锁/网络超时），整个程序就挂了
for (ToolInvocation invocation : invocations) {
    new Thread(() -> {
        executeToolOutput(invocation.name(), ...);
    }).start();  // 没有等待和超时！
}
```

**真实后果**：
- 性能差：3 个 2 秒的 tool 变成 6 秒而不是 2 秒
- 不稳定：一个 tool 卡住，整个 Agent 响应被阻塞
- 顺序乱：结果返回顺序不确定，破坏 LLM 的期望

### 【需要掌握的 JUC 工具】

- **ExecutorService**：线程池，管理工作线程生命周期
- **invokeAll(List<Callable>)**：并发执行所有任务，返回有序的 Future 列表
- **Future.get(timeout, unit)**：带超时的阻塞等待
- **newFixedThreadPool(n)**：创建固定大小的线程池
- **TimeUnit**：超时单位（秒、毫秒等）

### 【代码讲解】（逐行分析）

**真实代码片段**（来自 `ToolRegistry.java` 第 1256-1327 行）：

```java
public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
    if (invocations == null || invocations.isEmpty()) {
        return List.of();
    }
    
    // ✅ 第 1 步：检查用户是否已取消（Ctrl+C）
    if (CancellationContext.isCancelled()) {
        return invocations.stream()
                .map(invocation -> ToolExecutionResult.failed(invocation, "用户取消了此次工具调用"))
                .toList();
    }
    
    // 只有 1 个 tool，直接串行执行，不用起线程池的开销
    if (invocations.size() == 1) {
        ToolInvocation invocation = invocations.get(0);
        long startedAt = System.nanoTime();
        ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
        return List.of(ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt)));
    }

    // ✅ 第 2 步：创建线程池，最多 4 个并发
    int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);  // MAX_PARALLEL_TOOLS = 4
    ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
        Thread thread = new Thread(r, "paicli-tool-executor");
        thread.setDaemon(true);  // 设置为 daemon 线程，JVM 退出时自动停止
        return thread;
    });

    try {
        // ✅ 第 3 步：把每个 tool invocation 包装成 Callable 任务
        List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                    // 每个 Callable 的 call() 方法会在线程池的工作线程中执行
                    
                    // 再次检查取消（可能前面的 tool 已完成，用户取消了）
                    if (CancellationContext.isCancelled()) {
                        return ToolExecutionResult.failed(invocation, "用户取消了此次工具调用");
                    }
                    
                    // 记录开始时间（用于计算耗时）
                    long startedAt = System.nanoTime();
                    
                    // 执行 tool，可能耗时 0.5s～2s
                    ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
                    
                    // 返回结果（包含耗时）
                    return ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt));
                })
                .toList();

        // ✅ 第 4 步：invokeAll() 是 JUC 中的"魔法方法"
        // 作用：
        // - 提交所有 tasks 到线程池
        // - 等待所有 tasks 完成或超时
        // - 返回按原始顺序排列的 Future 列表（顺序保证！）
        // - toolBatchTimeoutSeconds = 8，即最多等 8 秒
        List<Future<ToolExecutionResult>> futures =
                executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

        List<ToolExecutionResult> results = new ArrayList<>();
        
        // ✅ 第 5 步：从 Future 中提取结果
        for (int i = 0; i < futures.size(); i++) {
            ToolInvocation invocation = invocations.get(i);
            Future<ToolExecutionResult> future = futures.get(i);
            
            // 检查这个 Future 是否被超时取消了
            if (future.isCancelled()) {
                results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                continue;
            }

            try {
                // 从 Future 中取出结果（这里不会阻塞，因为 invokeAll 已经等过了）
                results.add(future.get());
            } catch (InterruptedException e) {
                // 如果当前线程被中断（例如用户 Ctrl+C）
                Thread.currentThread().interrupt();  // 恢复中断状态
                results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
            } catch (ExecutionException e) {
                // tool 执行过程中抛异常
                Throwable cause = e.getCause();
                String message = cause == null || cause.getMessage() == null
                        ? "工具执行异常"
                        : cause.getMessage();
                results.add(ToolExecutionResult.failed(invocation, message));
            }
        }

        return results;
        
    } finally {
        // ✅ 第 6 步：关闭线程池（即使有异常也会执行）
        executor.shutdownNow();  // 立即停止线程池，不再接受新任务
    }
}
```

### 【执行流程】

```
┌─────────────────────────────────────────────────────────────────────┐
│ 用户请求：同时执行 3 个 tool （每个耗时 2 秒）                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│ Main Thread                                                          │
│ ├─> 创建线程池（parallelism=3）                                      │
│ ├─> 提交 3 个 Callable 任务到队列                                    │
│ │   - invokeAll() ← 从这里开始等待，最多 8 秒                       │
│ │                                                                    │
│ │   Worker Thread 1        Worker Thread 2        Worker Thread 3   │
│ │   ├─ call()             ├─ call()              ├─ call()         │
│ │   ├─ 执行 Tool A        ├─ 执行 Tool B        ├─ 执行 Tool C    │
│ │   ├─ 0s--2s             ├─ 0s--2s              ├─ 0s--2s         │
│ │   └─ 返回结果 A         └─ 返回结果 B         └─ 返回结果 C    │
│ │                                                                    │
│ └─> invokeAll() 返回 Future[]                                       │
│ ├─> 提取结果：results[0] = result A                                 │
│ ├─> 提取结果：results[1] = result B                                 │
│ └─> 提取结果：results[2] = result C                                 │
│                                                                       │
│ ✅ 总耗时：2 秒（并行）vs 6 秒（串行）                              │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### 【对比分析】

| 方案 | 实现 | 耗时 | 顺序 | 超时 | 取消 | 代码复杂度 |
|------|------|------|------|------|------|-----------|
| ❌ 串行 for 循环 | `for (tool) execute()` | 6s | ✅ 固定 | ❌ 无 | ❌ 无 | ⭐ 很简单 |
| ❌ synchronized | `synchronized {}` | 6s | ✅ 固定 | ❌ 无 | ❌ 无 | ⭐ 简单 |
| ❌ new Thread | 自建多线程 | 2s | ❌ 乱序 | ❌ 无 | ❌ 无 | ⭐⭐⭐ 复杂 |
| ✅ ExecutorService | `executor.invokeAll()` | 2s | ✅ 固定 | ✅ 8s | ✅ 支持 | ⭐⭐ 中等 |

**性能数据**（真实场景）：
- 3 个 tool，每个 2 秒
  - 串行：6 秒
  - 并行（parallelism=3）：2 秒
  - **性能提升：3 倍**

- 10 个 tool，每个 1 秒
  - 串行：10 秒
  - 并行（parallelism=4）：2.5 秒（因为只有 4 个线程）
  - **性能提升：4 倍**

### 【学习要点】

1. **ExecutorService.invokeAll() 的三大保证**：
   - ✅ 返回结果保持原始顺序（即使执行顺序不同）
   - ✅ 等待所有任务完成或超时
   - ✅ 如果超时，自动取消未完成的任务

2. **Future.isCancelled() vs Future.get()**：
   - `isCancelled()`：检查是否被超时或主动取消（非阻塞）
   - `get()`：获取结果，如果未完成则阻塞；超时会抛 TimeoutException（已被 invokeAll 处理）

3. **为什么 invokeAll() 比自建线程好**：
   ```
   自建线程 (new Thread)：
   - 结果顺序不确定（race condition）
   - 需要自己管理生命周期（join, interrupt）
   - 没有内置超时机制
   
   invokeAll()：
   - JUC 保证顺序
   - 内置 timeout 参数
   - 自动资源清理（shutdown)
   ```

4. **Daemon 线程的作用**：
   ```java
   thread.setDaemon(true);  // 不要等待这些工作线程
   ```
   如果 Main 线程退出（用户 Ctrl+C），JVM 会立即关闭，不等 daemon 线程完成。

5. **InterruptedException 处理的标准做法**：
   ```java
   catch (InterruptedException e) {
       Thread.currentThread().interrupt();  // ✅ 必须恢复中断状态
       // 否则上层的中断信号会丢失
   }
   ```

### 【练习题】

**练习题 1（简单）：理解 invokeAll 的超时机制**

请完成以下代码，使其功能：
- 3 个 tool，分别耗时 1s、2s、3s
- 超时时间 2.5s
- 预测哪个 tool 会超时，哪个会完成

```java
public class ToolExecutionDemo {
    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        
        List<Callable<String>> tasks = List.of(
            () -> { Thread.sleep(1000); return "Tool A"; },
            () -> { Thread.sleep(2000); return "Tool B"; },
            () -> { Thread.sleep(3000); return "Tool C"; }
        );
        
        // TODO: 使用 invokeAll() 执行，超时 2.5 秒
        
        executor.shutdownNow();
    }
}
```

**练习题 2（中等）：处理超时和异常**

```java
// 实现一个 safeExecuteTools 方法
public List<String> safeExecuteTools(List<Callable<String>> tasks, long timeoutSeconds) {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    List<String> results = new ArrayList<>();
    
    try {
        // TODO: 使用 invokeAll 执行所有任务
        // TODO: 处理超时、异常、取消的情况
        // TODO: 返回 [成功结果 | "TIMEOUT" | "ERROR: ..."]
        
    } finally {
        executor.shutdownNow();
    }
    
    return results;
}
```

**练习题 3（困难）：性能对比和优化**

在你的机器上运行以下性能测试：

```java
public class PerformanceComparison {
    // 创建 N 个模拟 tool，每个耗时 T 毫秒
    
    static long serialExecution(int toolCount, int timePerTool) {
        long start = System.nanoTime();
        for (int i = 0; i < toolCount; i++) {
            simulateTool(timePerTool);
        }
        return (System.nanoTime() - start) / 1_000_000;  // 转成毫秒
    }
    
    static long parallelExecution(int toolCount, int timePerTool) {
        // TODO: 用 ExecutorService + invokeAll 实现
        // 尝试不同的 parallelism 值（2, 4, 8）
        // 观察耗时如何变化
    }
    
    static void simulateTool(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }
    
    public static void main(String[] args) {
        // 测试数据：
        // - 2 个 tool × 500ms
        // - 4 个 tool × 500ms
        // - 8 个 tool × 500ms
        // - 16 个 tool × 500ms
        
        // 对于每种场景，记录：
        // - 串行耗时
        // - parallelism=2 的耗时
        // - parallelism=4 的耗时
        // - parallelism=8 的耗时
        
        System.out.println("工具数 | 串行 | parallelism=2 | parallelism=4 | parallelism=8");
        // TODO: 输出性能数据，找到最佳的 parallelism
    }
}
```

**预期结果**：
```
工具数 | 串行   | parallelism=2 | parallelism=4 | parallelism=8
  2   | 1000ms |  ~500ms       |  ~500ms       |  ~500ms
  4   | 2000ms |  ~1000ms      |  ~500ms       |  ~500ms
  8   | 4000ms |  ~2000ms      |  ~1000ms      |  ~500ms
 16   | 8000ms |  ~4000ms      |  ~2000ms      |  ~1000ms
```

**思考**：parallelism=4 的 CPU 机器，8 个 tool 时为什么约等于 2000ms 而不是 1000ms？



---

## 专题 2：RipgrepCodeSearchEngine - 进程 IO 读取的死锁问题

### 【场景描述】

PaiCLI 需要快速搜索代码库（利用外部工具 ripgrep），并实时读取搜索结果。核心逻辑是：
1. **启动外部进程**（ripgrep 命令）
2. **在独立线程读取进程的 stdout**（避免死锁）
3. **监听进程退出**（主线程等待）
4. **超时保护**（8 秒内必须返回）

### 【如果不用 JUC 会怎样】

**错误做法 1：主线程等待进程，同时读取 stdout**
```java
// ❌ 典型死锁场景！
Process process = pb.start();
BufferedReader reader = new BufferedReader(
    new InputStreamReader(process.getInputStream())
);

process.waitFor();  // ← 主线程在这里阻塞，等进程退出
                    // ← 但进程的 stdout 缓冲满了（32KB通常）
                    // ← 进程等着我们读，我们等着进程
                    // ← DEADLOCK!

String line = reader.readLine();  // ← 永远不会执行
```

**死锁的根本原因**：
```
进程的 stdout buffer ~= 32KB

主线程：waitFor() → 等进程退出
进程：  输出数据 → buffer满 → 写 stdout 阻塞 → 进程卡住
主线程：还在 waitFor() → 没人读数据
结果：  互相等待 → DEADLOCK
```

**错误做法 2：没有超时的独立线程**
```java
// ❌ 读线程卡住了，主线程永远等不到结果
Thread readerThread = new Thread(() -> {
    try (BufferedReader reader = ...) {
        String line;
        while ((line = reader.readLine()) != null) {
            process(line);  // 如果这里卡住了怎么办？
        }
    }
});
readerThread.start();

process.waitFor();  // ← 如果读线程卡住，这里永远不会返回
```

### 【需要掌握的 JUC 工具】

- **ExecutorService**：后台线程池
- **submit(Callable)**：非阻塞提交任务，返回 Future
- **Future.get(timeout, unit)**：带超时的阻塞等待
- **process.waitFor(timeout, unit)**：进程等待超时
- **Process.destroyForcibly()**：强制杀死进程

### 【代码讲解】（逐行分析）

**真实代码**（来自 `RipgrepCodeSearchEngine.java` 第 30-74 行）：

```java
@Override
public CodeSearchResult search(CodeSearchRequest request) {
    if (Boolean.getBoolean("paicli.search.disable.rg") || !isRipgrepAvailable()) {
        return fallback(request);
    }

    Process process = null;
    ExecutorService readerExecutor = null;
    try {
        // ✅ 步骤 1：启动外部进程（ripgrep 搜索命令）
        ProcessBuilder pb = new ProcessBuilder(command(request));
        pb.directory(request.projectRoot().toFile());
        pb.redirectErrorStream(true);  // 把 stderr 合并到 stdout
        process = pb.start();

        // ✅ 步骤 2：创建单线程池来读取进程输出
        // 为什么用线程池而不是 new Thread?
        // - 便于管理生命周期
        // - 便于设置线程名（调试时好找）
        readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-rg-reader");
            thread.setDaemon(true);
            return thread;
        });
        
        // ✅ 步骤 3：把读进程 stdout 的任务提交到线程池
        // 为什么要在后台线程做？
        // - 避免主线程阻塞在 BufferedReader.readLine()
        // - 如果主线程阻塞，就无法调用 process.waitFor()
        // - 从而导致死锁（见上文分析）
        Process runningProcess = process;
        Future<ParsedRipgrepOutput> outputFuture = readerExecutor.submit(
                () -> parseOutput(runningProcess.getInputStream(), runningProcess, request)
        );

        // ✅ 步骤 4：主线程等待进程完成，但有超时
        // TIMEOUT = 8 秒
        // 如果 ripgrep 没在 8 秒内完成，说明搜索量太大或网络卡
        // 不能无限等待，否则用户输入被冻结
        boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        
        if (!finished) {
            // 超时了，强制杀死进程
            process.destroyForcibly();
            // 取消读线程
            outputFuture.cancel(true);
            return new CodeSearchResult("rg", List.of(), true, 
                "rg 搜索超时 " + TIMEOUT.toSeconds() + " 秒");
        }

        // ✅ 步骤 5：进程已完成，从 Future 中取结果
        // 注意：这里不会阻塞太久，因为进程已经完成了
        ParsedRipgrepOutput parsed = outputFuture.get(1, TimeUnit.SECONDS);
        readerExecutor.shutdownNow();
        
        // 处理结果...
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

// 在后台线程执行，读取进程的 stdout
private ParsedRipgrepOutput parseOutput(InputStream inputStream, Process process, 
                                         CodeSearchRequest request) throws IOException {
    List<GrepMatch> matches = new ArrayList<>();
    // ...
    
    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode event = MAPPER.readTree(line);
            String type = event.path("type").asText();
            // 解析 ripgrep 的 JSON 输出...
            
            if (matches.size() >= request.maxResults()) {
                partial = true;
                partialReason = "已达到 max_results=" + request.maxResults();
                process.destroyForcibly();  // ← 不用再读了，杀进程
                break;
            }
        }
    }
    return new ParsedRipgrepOutput(matches, partial, partialReason);
}
```

### 【执行流程】

```
┌──────────────────────────────────────────────────────────┐
│  正确做法：避免死锁                                        │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  Main Thread                Reader Thread               │
│  ├─ 创建进程 (ripgrep)                                  │
│  ├─ 提交读任务到线程池                                  │
│  │                                                      │
│  │  outputFuture = executor.submit(               │
│  │      () -> parseOutput(process.getInputStream())  │
│  │  )                                                 │
│  │    ↓                                              │
│  │  waitFor(8s)  ← 等进程完成，但有超时           │ open() → 读 stdout
│  │  │            ← 如果 8s 没完成就 destroyForcibly  │ parseOutput()
│  │  │                                                │ ├─ 逐行读
│  │  │                                                │ ├─ 解析 JSON
│  │  │                                                │ ├─ 存储结果
│  │  │                                                │ └─ close()
│  │  │                                                │    ↓
│  │  │  ← 进程退出，stdout 自动关闭                   ✅ 任务完成
│  │  ↓                                                │
│  │ outputFuture.get(1s)  ← 不会阻塞太久         │
│  │  ├─ 如果已完成，立即返回结果                       │
│  │  └─ 如果还没完成（不太可能），等 1s                │
│  │                                                      │
│  └─ 返回搜索结果                                      │
│                                                           │
│  ✅ 没有死锁！原因：                                     │
│    - 主线程不阻塞在读操作                               │
│    - 读线程专门负责读（并释放 stdout buffer）           │
│    - 两个线程职责分明                                  │
│                                                           │
└──────────────────────────────────────────────────────────┘
```

```
┌──────────────────────────────────────────────────────────┐
│  死锁场景（错误做法）                                     │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  Main Thread                         ripgrep 进程        │
│  │                                    │                  │
│  ├─ 创建进程                          ├─ 启动           │
│  ├─ waitFor() ← 等进程                ├─ 开始输出      │
│  │  ↓                                 ├─ stdout buffer     
│  │  阻塞在这里                        │  满 (32KB)
│  │  ↓                                 ├─ write() 阻塞  
│  │  等等...我还没读 stdout 呢      │  等主线程读
│  │                                    │
│  │  永远在等！                        ✅ 也在等！
│  │                                    │
│  └─ DEADLOCK! 互相等待                └─ DEADLOCK!
│                                                           │
└──────────────────────────────────────────────────────────┘
```

### 【对比分析】

| 方案 | 实现 | 死锁风险 | 超时 | 代码清晰度 |
|------|------|---------|------|-----------|
| ❌ 主线程同时 waitFor+读 | `waitFor(); readLine()` | ❌ 高 | ❌ 无 | ⭐ 看起来简单 |
| ⚠️ 独立线程读，但无超时 | `new Thread(() -> read())` | ⚠️ 中 | ❌ 无 | ⭐⭐ 一般 |
| ✅ ExecutorService + timeout | `executor.submit(); waitFor(timeout)` | ✅ 低 | ✅ 有 | ⭐⭐⭐ 清晰 |

**为什么第一种方案必死锁**：

```
process.waitFor()         // ← 我等进程
进程的 write(stdout)      // ← 进程等 stdout buffer 清
BufferedReader.readLine() // ← 我在 waitFor 里，根本读不了
结果：三角形等待 → DEADLOCK
```

### 【学习要点】

1. **process.waitFor() 和 BufferedReader.readLine() 不能在同一线程**：
   ```java
   // ❌ 错误：两个阻塞操作在同一线程
   while ((line = reader.readLine()) != null) { }  // 等数据
   process.waitFor();                              // 等进程（但 buffer 满了，进程卡住）
   
   // ✅ 正确：分别在不同线程
   readerThread: 读取数据
   mainThread:  等进程
   ```

2. **Future 和 timeout 的组合**：
   ```java
   // ✅ 三层防护超时
   Future<Output> outputFuture = executor.submit(() -> read());
   boolean finished = process.waitFor(8, TimeUnit.SECONDS);  // 第一层
   if (!finished) outputFuture.cancel(true);                  // 取消读线程
   Output result = outputFuture.get(1, TimeUnit.SECONDS);     // 第二层
   ```

3. **ProcessBuilder 的 redirectErrorStream**：
   ```java
   pb.redirectErrorStream(true);  // stderr 合并到 stdout
   // 否则需要监听两个流（stderr 和 stdout）更容易死锁
   ```

4. **Process.destroyForcibly() 的时机**：
   ```java
   if (!finished) {
       process.destroyForcibly();        // ← 必须杀进程
       outputFuture.cancel(true);        // ← 必须取消读线程
   }
   // 否则进程和线程会一直僵尸化
   ```

### 【练习题】

**练习题 1（简单）：模拟进程读取**

```java
public class ProcessReadingDemo {
    public static void main(String[] args) throws Exception {
        // 启动一个输出 10 行的进程（用 bash 或 cmd）
        Process process = new ProcessBuilder(
            "bash", "-c", "for i in {1..10}; do echo Line $i; sleep 100ms; done"
        ).start();
        
        // TODO: 用独立线程读取 stdout
        // TODO: 主线程等待进程完成（超时 1 秒）
        // TODO: 打印所有读取到的行
    }
}
```

**练习题 2（中等）：处理超时的进程执行**

```java
public class ProcessWithTimeout {
    public static String executeCommandWithTimeout(String[] command, long timeoutSeconds) {
        // TODO: 
        // 1. 启动进程
        // 2. 用 ExecutorService 读取输出
        // 3. 用 process.waitFor(timeout, unit) 等待
        // 4. 如果超时，destroyForcibly() 并返回 "TIMEOUT"
        // 5. 返回完整输出
        return null;
    }
    
    public static void main(String[] args) throws Exception {
        // 测试：快速完成的命令
        String result1 = executeCommandWithTimeout(
            new String[] {"echo", "hello"}, 
            5
        );
        System.out.println("Test 1: " + result1);
        
        // 测试：超时的命令（sleep 30 秒但只等 2 秒）
        String result2 = executeCommandWithTimeout(
            new String[] {"bash", "-c", "sleep 30"},
            2
        );
        System.out.println("Test 2: " + result2);  // 应输出 "TIMEOUT"
    }
}
```

**练习题 3（困难）：并行读取两个流（stdout 和 stderr）避免死锁**

```java
public class DualStreamReading {
    public static class StreamReader implements Runnable {
        private final BufferedReader reader;
        private final List<String> lines;
        private final String streamName;
        
        public StreamReader(InputStream stream, String name) {
            this.reader = new BufferedReader(
                new InputStreamReader(stream)
            );
            this.lines = Collections.synchronizedList(new ArrayList<>());
            this.streamName = name;
        }
        
        @Override
        public void run() {
            // TODO: 读取该流的所有行
        }
        
        public List<String> getLines() { return lines; }
    }
    
    public static void main(String[] args) throws Exception {
        // 启动一个会输出 stdout 和 stderr 的进程
        Process process = new ProcessBuilder(
            "bash", "-c", 
            "echo 'stdout line 1'; echo 'stderr line 1' >&2; " +
            "echo 'stdout line 2'; sleep 1; " +
            "echo 'stderr line 2' >&2"
        ).start();
        
        // TODO:
        // 1. 创建两个 StreamReader（分别读 stdout 和 stderr）
        // 2. 用 ExecutorService 并行执行它们
        // 3. 主线程等待进程完成
        // 4. 打印所有输出
        
        // 为什么这样比 redirectErrorStream(true) 更复杂？
        // 因为 stderr 和 stdout 两个流都可能满，都需要独立线程读
    }
}
```

**思考题**：为什么 PaiCLI 中用 `redirectErrorStream(true)` 而不分别处理 stdout 和 stderr？



---

## 专题 3：NotificationRouter - 异步派发避免死锁

### 【场景描述】

PaiCLI 使用 MCP（Model Context Protocol）与 LLM 通信。MCP 通过 JSON-RPC 协议传输消息，当 MCP server 发来**通知**（不要求响应的消息）时，需要：
1. **在专用线程执行 handler**（不要在 transport 的读线程执行）
2. **避免自我死锁**（handler 内部如果调用 API 发请求，需要等待响应）
3. **不要阻塞 transport 读线程**（否则新消息读不了，请求无法回复）

### 【真实故事：为什么需要异步派发】

某天，MCP server 启动后立即推送通知：
```json
{
  "jsonrpc": "2.0",
  "method": "tools/list_changed",
  "params": {}
}
```

通知 handler 收到后调用 `tools/list` 刷新工具列表（需要发 JSON-RPC 请求并等响应）：
```
Transport.readLine() 线程：
  ├─ 读到 "tools/list_changed" 通知
  ├─ handler.apply(params)
  │  ├─ tools/list 请求
  │  └─ 等待响应（response 在 buffer 中）
  ├─ 但我是读线程！没人读 buffer！
  └─ DEADLOCK: handler 等响应，响应在 buffer，没人读 buffer
```

### 【需要掌握的 JUC 工具】

- **ExecutorService**：后台线程池
- **submit(Runnable)**：异步提交任务
- **ConcurrentHashMap**：线程安全的 handler 注册表
- **Consumer<T> + lambda**：函数式编程

### 【代码讲解】（逐行分析）

**真实代码**（来自 `NotificationRouter.java`）：

```java
/**
 * 路由 server → client 的通知到注册的 handler。
 *
 * **关键约束**：handler 在独立 daemon executor 里执行，**不在 transport 的 stdout reader 线程里同步执行**。
 * 否则 handler 内部如果要发 JSON-RPC 请求并等响应，自己等自己的响应，
 * stdout reader 被阻塞读不到响应 → 死锁。
 * 
 * 典型场景：server-everything 启动后立即推送 tools/list_changed，
 * handler 调 tools/list 重拉，stdout reader 线程被挂在 handler.apply 里，
 * tools/list 响应进 buffer 但没人读，最终请求超时。
 */
public class NotificationRouter implements Consumer<JsonNode>, AutoCloseable {
    
    // ✅ 1. 存储 method → handler 的映射
    private final Map<String, Consumer<JsonNode>> handlers = new ConcurrentHashMap<>();
    
    // ✅ 2. 后台线程池，用于异步派发通知
    private final ExecutorService dispatcher;

    public NotificationRouter() {
        AtomicInteger threadId = new AtomicInteger();
        // 单线程池：保证通知处理的顺序
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "paicli-mcp-notifications-" + threadId.incrementAndGet());
            t.setDaemon(true);  // daemon 线程，JVM 退出时自动停止
            return t;
        });
    }

    // ✅ 3. 注册通知 handler
    // 例如：router.on("tools/list_changed", params -> {...})
    public void on(String method, Consumer<JsonNode> handler) {
        if (method == null || method.isBlank() || handler == null) {
            return;
        }
        handlers.put(method, handler);
    }

    // ✅ 4. 核心方法：处理收到的消息
    // 这个方法通常在 JsonRpcClient 的读线程中被调用
    @Override
    public void accept(JsonNode message) {
        if (message == null || message.has("id")) {
            // 有 "id" 表示是请求/响应，不是通知
            return;
        }
        
        // 提取通知的 method
        String method = message.path("method").asText("");
        
        // 查询是否有对应的 handler
        Consumer<JsonNode> handler = handlers.get(method);
        if (handler == null) {
            return;  // 没有注册，忽略
        }
        
        // 提取参数
        JsonNode params = message.path("params");
        
        // ✅ 5. 关键：在后台线程执行 handler，而不是当前线程
        try {
            dispatcher.submit(() -> {
                try {
                    // 在独立线程中执行 handler
                    // 即使这里要发 RPC 请求等响应，也不会阻塞 transport 读线程
                    handler.accept(params);
                } catch (Exception ignored) {
                    // 通知 handler 失败是 best-effort，不能影响 transport 流
                    // 不要把错误向外抛，否则会导致 executor 的异常
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

**JsonRpcClient 中的使用**（来自测试）：

```java
// JsonRpcClient.java
private final List<Consumer<JsonNode>> notificationListeners = 
    new CopyOnWriteArrayList<>();  // 支持多个通知监听器

public void addNotificationListener(Consumer<JsonNode> listener) {
    notificationListeners.add(listener);
}

// 在读线程中
private void handleIncomingMessage(JsonNode message) {
    if (isNotification(message)) {
        // 通知所有监听器（通常只有 1 个 NotificationRouter）
        for (Consumer<JsonNode> listener : notificationListeners) {
            listener.accept(message);  // 调用 NotificationRouter.accept()
        }
    }
}
```

### 【执行流程】

**❌ 错误：同步派发（导致死锁）**

```
┌────────────────────────────────────────────────────────────────┐
│ Transport 读线程                                                │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│ 1. readLine() → 读到 "tools/list_changed" 通知                │
│    ├─ 调用 listener.accept(message)   ← 同步调用！            │
│    ├─ listener = NotificationRouter                            │
│    │  └─ handler.accept(params)                                │
│    │     ├─ 内部调用 tools/list RPC 请求                      │
│    │     └─ 等待响应... 等等... 等等...    ← 被阻塞！        │
│    │        ↓ 响应已到达，在 buffer 里                        │
│    │        ↓ 但没人读！读线程被卡在 handler 里                │
│    │        ↓ DEADLOCK!                                        │
│    │                                                            │
│ 2. readLine() 返回 ← 永远不会返回！                           │
│ 3. 处理响应 ← 永远不会执行                                    │
│                                                                 │
│ ❌ 结果：handler 等响应，响应等被读，读线程卡在 handler      │
│         三角形死锁                                             │
└────────────────────────────────────────────────────────────────┘
```

**✅ 正确：异步派发（避免死锁）**

```
┌────────────────────────────────────────────────────────────────┐
│ Transport 读线程                  │  通知派发线程                │
├────────────────────────────────────────────────────────────────┤
│                                  │                               │
│ 1. readLine()                   │                               │
│    ├─ 读到 "tools/list_changed" │                               │
│    ├─ 调用 listener.accept()    │                               │
│    │  ├─ dispatcher.submit()    │                               │
│    │  │  (非阻塞，立即返回)    │ ← 后台线程接收任务           │
│    │  └─ accept() 返回          │    ├─ handler.accept()      │
│    │                             │    │  ├─ 调用 tools/list   │
│ 2. readLine() ← 继续读           │    │  │  └─ 等响应...      │
│    ├─ 读到 tools/list 响应      │    │     ↓                  │
│    ├─ 交给 RpcClient 处理       │ ✅ 响应被读线程处理          │
│    │  ├─ handler 被唤醒         │    ├─ 派发线程获得响应      │
│    │  └─ handler 继续执行       │    │  └─ handler 完成       │
│    │                             │                               │
│ ✅ 流程顺畅，没有死锁            │ ✅ 不阻塞读线程            │
└────────────────────────────────────────────────────────────────┘
```

### 【对比分析】

| 方案 | 派发方式 | 死锁风险 | 响应能被读到 | 实现复杂度 |
|------|---------|---------|-------------|----------|
| ❌ 同步派发 | `listener.accept()` | ❌ 高 | ❌ 不能 | ⭐ 简单 |
| ✅ 异步派发 | `executor.submit(() -> listener.accept())` | ✅ 无 | ✅ 能 | ⭐⭐ 中等 |

**死锁成因**：
```
同步派发：
  read_thread: listener.accept() ← 调用者线程被卡住
           ↓ handler 发 RPC 请求，等响应
           ↓ 响应在 buffer 里
           ↓ 但 read_thread 被卡在 handler，没人读 buffer
           ↓ DEADLOCK

异步派发：
  read_thread: executor.submit() ← 立即返回，继续读
           ↓ 继续 readLine()，读到响应
           ↓ 处理响应，handler 被唤醒
           ✅ 没有死锁
```

### 【学习要点】

1. **关键洞察：谁在读，谁就不能阻塞**
   ```java
   // ❌ 读线程不能做阻塞操作
   Transport.readLine() {  // 这是读线程
       while (...) {
           handler.accept();  // ← 如果这里 block，谁来读响应？
       }
   }
   
   // ✅ 必须异步派发
   Transport.readLine() {
       dispatcher.submit(() -> handler.accept());  // 立即返回
       // 继续读下一条消息
   }
   ```

2. **异步派发的通用模式**
   ```java
   // 对于任何 callback，如果它可能发送网络请求/等响应
   // 都应该异步派发
   
   public void onNotification(JsonNode message) {
       // ❌ 错误
       callback.execute(message);
       
       // ✅ 正确
       executor.submit(() -> callback.execute(message));
   }
   ```

3. **单线程派发 vs 多线程派发**
   ```java
   // ✅ 单线程：保证通知处理顺序
   executor = Executors.newSingleThreadExecutor();
   // 如果通知 A 依赖 B 的结果，顺序很重要
   
   // ⚠️ 多线程：性能好但顺序不保证
   executor = Executors.newFixedThreadPool(4);
   // 如果通知之间独立，可以用多线程
   ```

4. **Daemon 线程的用途**
   ```java
   thread.setDaemon(true);
   // ✅ 好处：PaiCLI 退出时不需要等待派发线程完成
   // ❌ 坏处：可能丢掉最后的几个通知（极少见）
   ```

### 【练习题】

**练习题 1（简单）：实现简单的通知系统**

```java
public class SimpleNotificationRouter {
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor();
    
    public void on(String event, Consumer<String> handler) {
        handlers.put(event, handler);
    }
    
    public void emit(String event, String data) {
        // TODO: 异步派发通知
    }
    
    public static void main(String[] args) throws Exception {
        SimpleNotificationRouter router = new SimpleNotificationRouter();
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        
        router.on("hello", data -> {
            try {
                Thread.sleep(100);  // 模拟网络请求
                results.add("received: " + data);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        // 派发多个通知
        router.emit("hello", "msg1");
        router.emit("hello", "msg2");
        router.emit("hello", "msg3");
        
        Thread.sleep(500);  // 等待所有通知处理
        System.out.println(results);  // 应输出 [received: msg1, received: msg2, received: msg3]
    }
}
```

**练习题 2（中等）：模拟 RPC 死锁场景**

```java
public class RpcDeadlockDemo {
    // 模拟一个简单的 transport
    static class MockTransport {
        private final Queue<String> buffer = new ConcurrentLinkedQueue<>();
        private final ExecutorService readThread;
        
        public MockTransport() {
            readThread = Executors.newSingleThreadExecutor();
        }
        
        public void send(String message) {
            buffer.offer(message);
        }
        
        public void startReading(Consumer<String> notificationHandler, boolean async) {
            readThread.submit(() -> {
                while (true) {
                    String msg = buffer.poll();
                    if (msg != null && msg.startsWith("NOTIF:")) {
                        if (async) {
                            // ✅ 异步派发
                            Executors.newSingleThreadExecutor().submit(
                                () -> notificationHandler.accept(msg)
                            );
                        } else {
                            // ❌ 同步派发（可能死锁）
                            notificationHandler.accept(msg);
                        }
                    }
                }
            });
        }
    }
    
    public static void main(String[] args) throws Exception {
        // TODO:
        // 1. 创建 MockTransport
        // 2. 注册通知 handler，handler 内部调用 send()（发送 RPC 响应）
        // 3. 对比同步和异步的结果
        
        // 同步派发：应该观察到死锁或超时
        // 异步派发：应该正常完成
    }
}
```

**练习题 3（困难）：实现支持优先级的通知派发**

```java
public class PriorityNotificationRouter {
    enum Priority {
        HIGH(0), NORMAL(1), LOW(2);
        final int level;
        Priority(int level) { this.level = level; }
    }
    
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Priority> priorities = new ConcurrentHashMap<>();
    
    // TODO: 使用 PriorityBlockingQueue 替代普通 SingleThreadExecutor
    // 保证高优先级通知优先处理
    
    private final ExecutorService dispatcher;
    
    public PriorityNotificationRouter() {
        // TODO: 创建自定义线程池
        this.dispatcher = null;
    }
    
    public void on(String event, Consumer<String> handler) {
        on(event, handler, Priority.NORMAL);
    }
    
    public void on(String event, Consumer<String> handler, Priority priority) {
        handlers.put(event, handler);
        priorities.put(event, priority);
    }
    
    public void emit(String event, String data) {
        // TODO: 根据优先级派发
    }
    
    public static void main(String[] args) throws Exception {
        PriorityNotificationRouter router = new PriorityNotificationRouter();
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        
        // 注册不同优先级的 handler
        router.on("highPriority", data -> {
            results.add("[HIGH] " + data);
        }, Priority.HIGH);
        
        router.on("lowPriority", data -> {
            results.add("[LOW] " + data);
        }, Priority.LOW);
        
        // 先派发低优先级，再派发高优先级
        router.emit("lowPriority", "msg1");
        router.emit("highPriority", "msg2");
        router.emit("lowPriority", "msg3");
        
        Thread.sleep(500);
        
        // 由于优先级，应该是 [msg2, msg1, msg3]
        System.out.println(results);
    }
}
```

**思考题**：如果派发线程在处理某个通知时异常崩溃，后续通知还能被处理吗？如何改进？



---

## 专题 4：InlineActivityDisplay - 定时任务的 UI 刷新

### 【场景描述】

PaiCLI 的 TUI（终端 UI）需要在用户输入的下方实时显示 "正在思考 🤔" 的加载动画，并实时更新推理过程。需要：
1. **定时刷新 UI**（每 250ms 更新一次动画帧）
2. **同步访问 StringBuilder**（多线程访问共享的推理文本缓冲）
3. **线程安全的启动/停止**（用户取消时立即停止定时任务）

### 【如果不用 JUC 会怎样】

**错误做法 1：每次在 UI 线程启动 new Thread**
```java
// ❌ 线程爆炸！每次刷新都创建新线程
while (active) {
    new Thread(() -> renderUI()).start();
    Thread.sleep(250);
}
// 250ms 刷一次 = 4 次/秒 × 30 秒思考时间 = 120 个线程！
```

**错误做法 2：没有超时的 Timer**
```java
// ❌ 用 Timer 但没有正确停止，内存泄漏
Timer timer = new Timer();
timer.scheduleAtFixedRate(...);
// 用户取消时忘了 timer.cancel()，定时任务永远在跑
```

**错误做法 3：访问 StringBuilder 没有同步**
```java
// ❌ 多线程竞态条件
StringBuilder reasoning = new StringBuilder();

// UI 线程：清空
reasoning.setLength(0);  // ← 如果这时候 timer 线程在读...
                         // ← NPE 或索引越界

// Timer 线程：追加
reasoning.append(delta);  // ← 可能看到半个字符
```

### 【需要掌握的 JUC 工具】

- **ScheduledExecutorService**：定时线程池
- **scheduleAtFixedRate(task, delay, period, unit)**：定时重复任务
- **ScheduledFuture.cancel()**：取消定时任务
- **synchronized**：对 StringBuilder 的访问加锁

### 【代码讲解】（逐行分析）

**真实代码**（来自 `InlineActivityDisplay.java`）：

```java
final class InlineActivityDisplay implements AutoCloseable {
    private static final int MAX_REASONING_CHARS = 4096;
    private static final int MAX_REASONING_ROWS = 4;
    private static final String[] SPINNER_FRAMES = 
        {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final Terminal terminal;
    private final PrintStream renderLock;
    
    // ✅ 定时任务执行器
    private final ScheduledExecutorService scheduler;
    
    // ✅ 推理文本缓冲（多线程共享）
    private final StringBuilder reasoning = new StringBuilder();
    
    // ✅ 当前定时任务 Future
    private ScheduledFuture<?> tickTask;
    
    // ✅ 状态标志（是否正在显示）
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    
    // 其他 UI 相关字段...
    private int frame;  // 动画帧数
    private long startedNanos;  // 开始时间
    private int renderedRows;  // 已显示的行数

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock) {
        this(terminal, renderLock, null);
    }

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock, BottomStatusBar statusBar) {
        this.terminal = terminal;
        this.renderLock = renderLock;
        
        // ✅ 1. 创建单线程的定时执行器
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "paicli-activity-display");
            t.setDaemon(true);
            return t;
        });
    }

    // ✅ 2. 检查是否正在显示（synchronized 保证可见性）
    synchronized boolean isActive() {
        return active && !closed;
    }

    // ✅ 3. 如果正在显示，立即刷新一次 UI
    synchronized void refreshIfActive() {
        if (active && !closed) {
            renderLocked();  // 立即重新绘制
        }
    }

    // ✅ 4. 开始显示思考过程
    synchronized void begin(String label) {
        if (closed) {
            return;
        }
        clearLocked();  // 清除之前的 UI
        reasoning.setLength(0);  // ✅ 清空推理文本缓冲
        this.label = (label == null || label.isBlank()) ? "Thinking" : label.trim();
        this.showCancelHint = true;
        this.startedNanos = System.nanoTime();
        this.frame = 0;
        this.active = true;
        renderLocked();  // 绘制初始 UI
        restartTickLocked();  // ✅ 启动定时刷新
    }

    // ✅ 5. 追加推理文本（来自 LLM streaming）
    synchronized void appendThinking(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        if (!active) {
            // 如果还没启动动画，自动启动
            this.label = "Thinking";
            this.showCancelHint = true;
            this.startedNanos = System.nanoTime();
            this.frame = 0;
            this.active = true;
            restartTickLocked();
        }
        
        // ✅ 追加到缓冲（已在 synchronized 块内，线程安全）
        reasoning.append(delta);
        trimReasoning();  // 超过 4KB 就截断
        renderLocked();  // 立即重新绘制
    }

    // ✅ 6. 结束显示
    synchronized void end() {
        if (closed) {
            return;
        }
        active = false;
        cancelTickLocked();  // ✅ 停止定时任务
        reasoning.setLength(0);
        clearLocked();
    }

    // ✅ 7. AutoCloseable，确保资源清理
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        cancelTickLocked();  // ✅ 必须停止定时任务
        reasoning.setLength(0);
        clearLocked();
        scheduler.shutdownNow();  // ✅ 关闭线程池
    }

    // ✅ 8. 启动定时任务（每 250ms 执行一次）
    private void restartTickLocked() {
        cancelTickLocked();  // 先取消旧任务
        
        // ✅ scheduleAtFixedRate(task, initialDelay, period, unit)
        // - task: 执行什么
        // - initialDelay: 250ms 后第一次执行
        // - period: 之后每 250ms 执行一次
        // - unit: 毫秒
        tickTask = scheduler.scheduleAtFixedRate(
            this::tick,        // 要执行的任务（方法引用）
            250,               // 初始延迟
            250,               // 周期
            TimeUnit.MILLISECONDS
        );
    }

    // ✅ 9. 取消定时任务（必须调用，否则内存泄漏）
    private void cancelTickLocked() {
        if (tickTask != null) {
            tickTask.cancel(false);  // false = 允许当前执行完成后再停止
            tickTask = null;
        }
    }

    // ✅ 10. 定时执行的任务（由 scheduler 线程调用）
    private void tick() {
        synchronized (this) {
            if (!active || closed) {
                return;
            }
            renderLocked();  // 重新绘制 UI
        }
    }

    // ✅ 11. 实际绘制逻辑（必须在 synchronized 块内调用）
    private void renderLocked() {
        // 更新动画帧
        frame = (frame + 1) % SPINNER_FRAMES.length;
        
        // 构建显示内容
        List<AttributedString> lines = buildLines();
        
        // 清除旧的显示行
        clearRenderedArea(...);
        
        // 绘制新的内容
        PrintWriter writer = terminalWriter();
        for (AttributedString line : lines) {
            writer.println(line);
        }
        
        renderedRows = lines.size();
    }

    // ✅ 12. 构建显示内容（访问 reasoning 缓冲）
    private List<AttributedString> buildLines() {
        List<AttributedString> lines = new ArrayList<>();
        
        // 显示加载动画
        lines.add(new AttributedString(spinner() + " " + label));
        
        // 显示推理过程的预览（最多 4 行）
        List<String> reasoning
Lines = reasoningLines();
        lines.addAll(reasoningLines.stream()
            .map(line -> new AttributedString(line, QUOTE_STYLE))
            .toList());
        
        return lines;
    }

    // ✅ 13. 获取推理文本的预览（访问 reasoning 缓冲）
    private List<String> reasoningLines() {
        String text = reasoning.toString();
        
        // 最多显示 4KB
        if (text.length() > MAX_REASONING_CHARS) {
            text = text.substring(0, MAX_REASONING_CHARS) + "...";
        }
        
        // 按行分割，最多 4 行
        return Arrays.stream(text.split("\n"))
            .limit(MAX_REASONING_ROWS)
            .toList();
    }

    private String spinner() {
        return SPINNER_FRAMES[frame];  // 循环动画帧
    }

    private void trimReasoning() {
        // 超过 4KB 就截断
        if (reasoning.length() > MAX_REASONING_CHARS) {
            reasoning.setLength(MAX_REASONING_CHARS);
        }
    }
}
```

### 【执行流程】

```
┌──────────────────────────────────────────────────────────────────────┐
│ Main 线程（处理用户输入）      │  Scheduler 线程（定时刷新）        │
├──────────────────────────────────────────────────────────────────────┤
│                                │                                      │
│ begin("Thinking")              │                                      │
│ ├─ active = true               │                                      │
│ ├─ renderLocked()  ← 初始绘制  │                                      │
│ └─ restartTickLocked()         │                                      │
│    ├─ scheduler.scheduleAtFixedRate(tick, 250, 250)                 │
│    │  (安排定时任务)            │                                      │
│    │                            │ ← 等待 250ms                        │
│    └─ 返回                      │                                      │
│                                │ tick()  ← 第 1 次执行               │
│ appendThinking("Hello")        │ ├─ renderLocked()  ← 更新动画      │
│ ├─ reasoning.append("H")       │ └─ 返回                             │
│ ├─ renderLocked()              │                                      │
│ └─ 返回                        │ ← 等待 250ms                        │
│                                │ tick()  ← 第 2 次执行               │
│ appendThinking(" world")       │ ├─ renderLocked()  ← 更新动画      │
│ ├─ reasoning.append(" w")      │ └─ 返回                             │
│ ├─ renderLocked()              │                                      │
│ └─ 返回                        │ ← 等待 250ms                        │
│                                │ tick()  ← 第 3 次执行               │
│ end()                          │ ├─ 此时 active = false              │
│ ├─ active = false              │ ├─ renderLocked() 检查到 inactive   │
│ ├─ cancelTickLocked()          │ └─ 返回                             │
│ │  ├─ tickTask.cancel(false)   │                                      │
│ │  └─ 后续的 tick() 被取消      │                                      │
│ └─ 返回                        │                                      │
│                                │ ✅ 定时任务停止                     │
│                                │                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 【对比分析】

| 方案 | 实现 | 线程数 | 内存泄漏风险 | 同步问题 |
|------|------|--------|-----------|---------|
| ❌ new Thread 每次 | `new Thread(() -> render())` | 600+ | ⚠️ 高 | ⚠️ 高 |
| ❌ Timer 无停止 | `timer.schedule()` | 1 | ❌ 高 | ⚠️ 中 |
| ✅ ScheduledExecutor | `scheduler.scheduleAtFixedRate()` | 1 | ✅ 低 | ✅ 低 |

**性能对比**（30 秒思考时间，4 次/秒刷新）：

| 方案 | 线程数 | 内存占用 | GC 压力 |
|------|--------|---------|--------|
| new Thread | 120 | ↑↑↑ 500MB+ | 很高 |
| Timer | 1 | ↑ 50MB | 低 |
| ScheduledExecutor | 1 | ↓ 5MB | 很低 |

### 【学习要点】

1. **synchronized 的作用**：
   ```java
   // ✅ 多个方法都是 synchronized
   synchronized void begin() { ... }
   synchronized void appendThinking() { ... }
   synchronized void tick() { ... }
   
   // ✓ 保证：在任何时刻，最多只有一个线程执行这些方法
   // ✓ 保证：对共享字段的修改立即对其他线程可见
   // ✓ 保证：StringBuilder 的访问没有竞态条件
   ```

2. **ScheduledFuture 的取消必须及时**：
   ```java
   // ❌ 错误：忘了取消
   ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(...);
   // future 永远在后台执行，内存泄漏
   
   // ✅ 正确：及时取消
   future.cancel(false);  // 取消定时任务
   scheduler.shutdownNow();  // 关闭线程池
   ```

3. **scheduleAtFixedRate vs scheduleWithFixedDelay**：
   ```java
   // Fixed Rate: 每 250ms 执行一次（不管前一次是否完成）
   scheduler.scheduleAtFixedRate(task, 250, 250, TimeUnit.MILLISECONDS);
   
   时间线：
   0ms:   执行 task（耗时 100ms）
   250ms: 立即执行 task（不管前一次是否完成）
   500ms: 立即执行 task
   
   // Fixed Delay: 前一次完成后等 250ms 再执行
   scheduler.scheduleWithFixedDelay(task, 250, 250, TimeUnit.MILLISECONDS);
   
   时间线：
   0ms:   执行 task（耗时 100ms）
   100ms: task 完成
   350ms: 执行 task（100ms 完成 + 250ms 延迟）
   550ms: 执行 task
   
   对于 UI 刷新，应该用 scheduleAtFixedRate（保证帧率）
   ```

4. **AutoCloseable 的重要性**：
   ```java
   try (InlineActivityDisplay display = new InlineActivityDisplay(...)) {
       display.begin();
       // ... 使用
   } // 自动调用 close()，清理资源
   
   // 如果没有实现 AutoCloseable：
   InlineActivityDisplay display = new InlineActivityDisplay(...);
   // 如果忘了 display.close()，定时任务永远在跑！
   ```

### 【练习题】

**练习题 1（简单）：实现一个简单的加载动画**

```java
public class SimpleSpinner {
    private final ScheduledExecutorService scheduler = 
        Executors.newSingleThreadScheduledExecutor();
    private final String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int frame = 0;
    private ScheduledFuture<?> task;
    
    public void start() {
        // TODO: 使用 scheduleAtFixedRate 每 100ms 打印一个新的 frame
        // 输出应该像这样在终端刷新：
        // ⠋ Loading
        // ⠙ Loading
        // ⠹ Loading
        // ...
    }
    
    public void stop() {
        // TODO: 取消定时任务
    }
    
    public static void main(String[] args) throws Exception {
        SimpleSpinner spinner = new SimpleSpinner();
        spinner.start();
        Thread.sleep(3000);  // 显示 3 秒
        spinner.stop();
    }
}
```

**练习题 2（中等）：实现线程安全的日志收集**

```java
public class ThreadSafeLogger {
    private final StringBuilder buffer = new StringBuilder();
    private final ScheduledExecutorService scheduler = 
        Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> flushTask;
    
    // ✅ 必须 synchronized，因为有多线程写日志
    public synchronized void log(String message) {
        buffer.append(message).append("\n");
    }
    
    public synchronized void startAutoFlush(int intervalMs) {
        // TODO: 每 intervalMs 毫秒自动刷新一次
        // 刷新意味着：输出当前缓冲的所有日志，然后清空缓冲
    }
    
    public synchronized void flush() {
        if (buffer.length() > 0) {
            System.out.println("=== FLUSH ===");
            System.out.println(buffer.toString());
            buffer.setLength(0);
        }
    }
    
    public static void main(String[] args) throws Exception {
        ThreadSafeLogger logger = new ThreadSafeLogger();
        logger.startAutoFlush(500);  // 每 500ms 刷新一次
        
        // 多个线程写日志
        for (int t = 0; t < 3; t++) {
            new Thread(() -> {
                for (int i = 0; i < 5; i++) {
                    logger.log("Thread " + Thread.currentThread().getName() + ": msg " + i);
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                }
            }).start();
        }
        
        Thread.sleep(2000);  // 等待所有线程完成
        logger.flush();  // 最后一次刷新
    }
}
```

**练习题 3（困难）：实现一个自适应速率的进度条**

```java
public class AdaptiveProgressBar {
    private final ScheduledExecutorService scheduler = 
        Executors.newSingleThreadScheduledExecutor();
    private volatile int progress = 0;  // 0-100
    private volatile long lastUpdateTime = System.currentTimeMillis();
    private ScheduledFuture<?> refreshTask;
    
    public void updateProgress(int newProgress) {
        // TODO: 平滑更新进度
        // 避免跳跃性变化，应该缓缓增长
        this.progress = newProgress;
        this.lastUpdateTime = System.currentTimeMillis();
    }
    
    public void startDisplay(int refreshRateMs) {
        // TODO: 定时显示进度条
        // 计算更新速度（progress/秒）
        // 如果更新速度很快，刷新率应该更高；反之亦然
    }
    
    public static void main(String[] args) throws Exception {
        AdaptiveProgressBar bar = new AdaptiveProgressBar();
        bar.startDisplay(50);  // 每 50ms 显示一次
        
        // 模拟缓慢的进度更新
        for (int i = 0; i <= 100; i += 10) {
            bar.updateProgress(i);
            Thread.sleep(500);  // 每 500ms 更新 10%
        }
        
        Thread.sleep(1000);
    }
}
```

**思考题**：如果 tick() 方法的执行时间超过了 250ms（定时间隔），会发生什么？



---

## 专题 5：LongTermMemory - 内存存储的并发安全

### 【场景描述】

PaiCLI 维护跨会话的长期记忆（用户偏好、项目事实等），需要：
1. **多线程并发读写**（Agent 执行 /memory 命令，同时 LLM 可能调用工具读取）
2. **原子计数**（token 数统计必须准确）
3. **并发修改枚举**（stream 遍历时可能有其他线程删除）
4. **JSON 序列化到磁盘**（持久化时不能丢数据）

### 【如果不用 JUC 会怎样】

**错误做法 1：用 HashMap**
```java
// ❌ 非线程安全！ConcurrentModificationException 或数据丢失
Map<String, MemoryEntry> entries = new HashMap<>();

// 线程 A：遍历
for (MemoryEntry e : entries.values()) {
    process(e);
}

// 线程 B：同时删除
entries.remove("key1");  // ← ConcurrentModificationException!
```

**错误做法 2：全局 synchronized 锁**
```java
// ❌ 性能差：任何操作都要等全局锁
public synchronized void store(MemoryEntry entry) {
    // 所有读操作都被阻塞...
}

// 如果有 100 个并发读，等待时间很长
```

**错误做法 3：整数计数没有原子性**
```java
// ❌ token 计数不准确
private int tokenCounter = 0;

public void store(MemoryEntry entry) {
    entries.put(entry.getId(), entry);
    tokenCounter += entry.getTokenCount();  // ← race condition!
    // 两个线程同时 += 会丢数据
}
```

### 【需要掌握的 JUC 工具】

- **ConcurrentHashMap**：无锁读，细粒度锁写
- **AtomicInteger**：原子计数
- **stream().collect()**：线程安全的聚合操作

### 【代码讲解】（逐行分析）

**真实代码**（来自 `LongTermMemory.java` 第 1-150 行）：

```java
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "paicli.memory.dir";
    private static final String STORAGE_DIR_ENV = "PAICLI_MEMORY_DIR";
    private static final String STORAGE_FILE = "long_term_memory.json";
    
    // ✅ 1. 使用 ConcurrentHashMap 替代 HashMap
    // 优点：
    // - 读操作不加锁（无等待）
    // - 写操作细粒度锁（只锁该 bucket）
    // - 允许并发读写
    private final Map<String, MemoryEntry> entries;
    
    // ✅ 2. 使用 AtomicInteger 替代 int
    // 保证 += 操作的原子性
    private final AtomicInteger tokenCounter;
    
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        // ✅ 初始化 ConcurrentHashMap
        this.entries = new ConcurrentHashMap<>();
        
        // ✅ 初始化 AtomicInteger
        this.tokenCounter = new AtomicInteger(0);
        
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保存储目录存在
        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storageFile = new File(dir, STORAGE_FILE);

        // 启动时加载已有记忆
        loadFromDisk();
    }

    @Override
    public void store(MemoryEntry entry) {
        // ✅ 检查重复：使用 stream + anyMatch
        // 即使有并发修改，ConcurrentHashMap 也不会抛异常
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;
        }

        // ✅ 插入条目
        entries.put(entry.getId(), entry);
        
        // ✅ 原子增加 token 计数
        // addAndGet() 保证多线程下的原子性
        tokenCounter.addAndGet(entry.getTokenCount());
        
        saveToDisk();
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        // ✅ 获取条目（ConcurrentHashMap 的 get 不加全局锁）
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        // ✅ 流式处理
        // entries.values() 是并发安全的快照
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
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
    public List<MemoryEntry> getAll() {
        // ✅ 返回一个新的 ArrayList 副本
        // 即使之后有并发修改，也不会影响这个 List
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean delete(String id) {
        // ✅ 删除条目
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            // ✅ 原子减少 token 计数
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        tokenCounter.set(0);  // ✅ 原子设置为 0
        saveToDisk();
    }

    @Override
    public int getTokenCount() {
        // ✅ 获取当前计数（原子读）
        return tokenCounter.get();
    }

    @Override
    public int size() {
        // ✅ ConcurrentHashMap 的 size() 是大约准确的
        // （可能漏掉正在进行的插入，但不会有竞态条件）
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        // ✅ 流式过滤
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * 持久化到磁盘
     */
    private void saveToDisk() {
        try {
            // ✅ 流式收集到 List
            // 即使有并发修改也不会出错
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从磁盘加载
     */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    // ✅ 原子增加
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            }
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }
}
```

### 【执行流程】

```
┌───────────────────────────────────────────────────────────────────┐
│ 多线程访问示例                                                     │
├───────────────────────────────────────────────────────────────────┤
│                                                                    │
│ 线程 A: store(entry1)          线程 B: search("java")            │
│ ├─ entries.put()               ├─ entries.values().stream()      │
│ │  ├─ 写入 bucket 1            │  ├─ 不需要全局锁！            │
│ │  ├─ 只锁 bucket 1            │  ├─ 读取当前快照               │
│ │  └─ 其他 bucket 可读          │  └─ 继续流式处理               │
│ ├─ tokenCounter.addAndGet(100) │                                 │
│ │  ├─ 原子操作                 │ 线程 C: getTokenCount()         │
│ │  └─ 不会丢失计数             │ ├─ tokenCounter.get()          │
│ └─ 返回                        │ ├─ 无锁读取                     │
│                                │ └─ 返回 100                     │
│                                │                                 │
│ ✅ 流程特点：                   │                                 │
│ - A 写 bucket 1，B 可以读其他   │                                 │
│ - B 读数据时不需要全局锁        │                                 │
│ - C 读计数器，完全无锁          │                                 │
│ - 并发度高，吞吐量大            │                                 │
│                                │                                 │
└───────────────────────────────────────────────────────────────────┘
```

```
┌───────────────────────────────────────────────────────────────────┐
│ ConcurrentHashMap vs HashMap vs synchronized HashMap              │
├───────────────────────────────────────────────────────────────────┤
│                                                                    │
│ HashMap (不安全)：                                                 │
│ ┌─────────────┐                                                   │
│ │ Entry Entry │ ← 非线程安全                                      │
│ └─────────────┘    并发修改 = ConcurrentModificationException    │
│                                                                    │
│ synchronized HashMap (安全但慢)：                                 │
│ ┌─────────────────────────────┐                                   │
│ │ [全局锁] Entry Entry │ ← 任何操作都要等全局锁                  │
│ └─────────────────────────────┘    吞吐量低                      │
│                                                                    │
│ ConcurrentHashMap (安全且快)：                                    │
│ ┌──────┬──────┬──────┬──────┐                                     │
│ │ Lock │Entry │ Lock │Entry │ ← 每个 bucket 一个小锁            │
│ │ 锁1  │Entry │ 锁2  │Entry │    读操作不加锁                    │
│ └──────┴──────┴──────┴──────┘    并发度高                        │
│                                                                    │
└───────────────────────────────────────────────────────────────────┘
```

### 【对比分析】

**性能对比**（100 个并发读 + 10 个并发写）：

| 实现方式 | 读延迟 | 写延迟 | 吞吐量 | 数据安全 |
|---------|--------|--------|--------|---------|
| ❌ HashMap | 极低 | 极低 | 极高 | ❌ 崩溃 |
| ❌ synchronized | 高 | 中 | 低 | ✅ 安全 |
| ✅ ConcurrentHashMap | 极低 | 低 | 极高 | ✅ 安全 |

**token 计数对比**（1000 次并发 +=100）：

| 方案 | 最终值 | 准确性 |
|------|-------|--------|
| ❌ int | ~89234 | ❌ 错误（应该 100000） |
| ⚠️ synchronized int | 100000 | ✅ 正确（但慢） |
| ✅ AtomicInteger | 100000 | ✅ 正确（且快） |

### 【学习要点】

1. **ConcurrentHashMap 的三层设计**：
   ```
   第 1 层：segment 数组（原 ConcurrentHashMap，JDK 8+ 改进）
   第 2 层：bucket 链表
   第 3 层：entry 节点
   
   // 读操作：不加锁
   V get(K key) {
       return find(key);  // 直接读
   }
   
   // 写操作：细粒度锁
   V put(K key, V value) {
       lock(bucket);      // 只锁这个 bucket
       V old = find(key);
       insert(key, value);
       unlock(bucket);
       return old;
   }
   ```

2. **AtomicInteger 的 CAS 原理**：
   ```java
   // Compare-And-Swap 原子操作
   int value = 100;
   atomicInt.addAndGet(50);  // 不能被打断
   
   // 内部实现（简化）：
   do {
       int oldValue = value;
       int newValue = oldValue + 50;
   } while (!compareAndSwap(value, oldValue, newValue));
   // 如果 value 中途被改了，重试
   ```

3. **stream 遍历的安全性**：
   ```java
   // ✅ 安全：entries.values() 返回快照
   entries.values().stream()
       .filter(...)
       .collect(Collectors.toList());
   
   // ❌ 不安全：边遍历边修改
   for (MemoryEntry e : entries.values()) {
       entries.remove(e.getId());  // ConcurrentModificationException
   }
   ```

4. **计数器的原子性很重要**：
   ```java
   // ❌ 问题代码
   private int tokenCounter = 0;
   public void add(int count) {
       tokenCounter += count;  // 三步操作
       // 1. 读取当前值
       // 2. 计算新值
       // 3. 写回
       // 多线程下可能重复计算或丢失更新
   }
   
   // ✅ 正确做法
   private AtomicInteger tokenCounter = new AtomicInteger(0);
   public void add(int count) {
       tokenCounter.addAndGet(count);  // 一步完成
   }
   ```

### 【练习题】

**练习题 1（简单）：实现一个线程安全的计数器**

```java
public class ThreadSafeCounter {
    private AtomicInteger count = new AtomicInteger(0);
    
    public void increment() {
        // TODO: 原子增加 1
    }
    
    public void decrement() {
        // TODO: 原子减少 1
    }
    
    public int getValue() {
        // TODO: 返回当前值
    }
    
    public static void main(String[] args) throws Exception {
        ThreadSafeCounter counter = new ThreadSafeCounter();
        
        // 创建 100 个线程，每个增加 100 次
        Thread[] threads = new Thread[100];
        for (int i = 0; i < 100; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    counter.increment();
                }
            });
            threads[i].start();
        }
        
        // 等待所有线程完成
        for (Thread t : threads) {
            t.join();
        }
        
        System.out.println("Final count: " + counter.getValue());  // 应该是 10000
    }
}
```

**练习题 2（中等）：实现一个线程安全的缓存**

```java
public class ConcurrentCache<K, V> {
    // TODO: 使用 ConcurrentHashMap 存储条目
    // TODO: 使用 AtomicInteger 记录缓存命中次数
    
    public void put(K key, V value) {
        // TODO: 放入缓存
    }
    
    public V get(K key) {
        // TODO: 获取缓存，并原子增加命中计数
        return null;
    }
    
    public int getHitCount() {
        // TODO: 返回命中次数
        return 0;
    }
    
    public int size() {
        // TODO: 返回缓存大小
        return 0;
    }
    
    public static void main(String[] args) throws Exception {
        ConcurrentCache<String, String> cache = new ConcurrentCache<>();
        
        // 预热缓存
        cache.put("key1", "value1");
        cache.put("key2", "value2");
        
        // 100 个并发读线程
        ExecutorService executor = Executors.newFixedThreadPool(100);
        for (int i = 0; i < 1000; i++) {
            final int idx = i;
            executor.submit(() -> {
                String key = "key" + (idx % 2 + 1);  // key1 或 key2
                cache.get(key);
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        
        System.out.println("Hit count: " + cache.getHitCount());  // 应该是 1000
    }
}
```

**练习题 3（困难）：对比 HashMap vs synchronized vs ConcurrentHashMap 的性能**

```java
public class PerformanceComparison {
    
    static long benchmarkRead(Map<String, String> map, int iterations, int threads) 
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        
        long start = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            executor.submit(() -> {
                map.get("key" + (idx % 1000));
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        
        return (System.nanoTime() - start) / 1_000_000;  // 毫秒
    }
    
    public static void main(String[] args) throws Exception {
        int iterations = 10000;
        int threads = 16;
        
        // 预热
        Map<String, String> map = new ConcurrentHashMap<>();
        for (int i = 0; i < 1000; i++) {
            map.put("key" + i, "value" + i);
        }
        
        System.out.println("Threads: " + threads + ", Iterations: " + iterations);
        System.out.println();
        
        // 1. ConcurrentHashMap
        Map<String, String> concurrentMap = new ConcurrentHashMap<>(map);
        long t1 = benchmarkRead(concurrentMap, iterations, threads);
        System.out.println("ConcurrentHashMap: " + t1 + "ms");
        
        // 2. Collections.synchronizedMap(new HashMap())
        Map<String, String> syncMap = Collections.synchronizedMap(new HashMap<>(map));
        long t2 = benchmarkRead(syncMap, iterations, threads);
        System.out.println("Synchronized HashMap: " + t2 + "ms");
        
        // 3. HashMap (不安全，仅作参考)
        Map<String, String> hashMap = new HashMap<>(map);
        long t3 = benchmarkRead(hashMap, iterations, threads);
        System.out.println("HashMap (unsafe): " + t3 + "ms");
        
        System.out.println();
        System.out.println("ConcurrentHashMap 比 Synchronized 快 " + (t2 / t1) + "x");
        System.out.println("ConcurrentHashMap 比 HashMap 快 " + (t3 / t1) + "x (但 HashMap 不安全)");
    }
}
```

**思考题**：为什么 ConcurrentHashMap 可以在不加全局锁的情况下进行 put 操作？



---

## 专题 6：CancellationToken - 取消信号的原子操作

### 【场景描述】

PaiCLI 支持用户按 Ctrl+C 取消当前执行。需要一种**高效、线程安全**的方式通知所有工作线程停止执行。

### 【代码讲解】

**真实代码**（来自 `CancellationToken.java`）：

```java
public class CancellationToken {
    // ✅ 使用 AtomicBoolean，避免竞态条件
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        // ✅ 原子设置为 true
        cancelled.set(true);
    }

    public boolean isCancelled() {
        // ✅ 原子读取 + 检查中断标志
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }
}
```

**使用示例**（来自 ToolRegistry.java）：

```java
// 工作线程检查取消
if (CancellationContext.isCancelled()) {
    return ToolExecutionResult.failed(invocation, "用户取消了此次工具调用");
}

// 主线程触发取消
Thread.currentThread().interrupt();  // 中断当前线程
CancellationContext.cancel();        // 设置取消标志
```

### 【学习要点】

1. **为什么不用 boolean**：
   ```java
   // ❌ 非原子，可能看不到最新值
   private boolean cancelled = false;
   
   // ✅ 原子，保证可见性
   private AtomicBoolean cancelled = new AtomicBoolean(false);
   ```

2. **双重检查：AtomicBoolean + isInterrupted()**：
   ```java
   // 两种取消方式都支持：
   // 1. isCancelled() = true（主动调用 cancel()）
   // 2. Thread.currentThread().isInterrupted() = true（被中断）
   return cancelled.get() || Thread.currentThread().isInterrupted();
   ```

---

## 专题 7：TuiHitlHandler - HITL 审批的并发集合

### 【场景描述】

PaiCLI 的 HITL（Human-In-The-Loop）审批功能允许用户"一键全部放行"某个工具。需要在会话中记住已批准的工具列表，并保证线程安全。

### 【代码讲解】

**真实代码**（来自 `TuiHitlHandler.java`）：

```java
public class TuiHitlHandler implements HitlHandler {
    private volatile boolean enabled = true;

    // ✅ 使用 ConcurrentHashMap.newKeySet() 
    // 得到一个线程安全的 Set<String>
    // 不用存储 value，只需要 key 的唯一性
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        // ✅ 线程安全的 contains
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public void clearApprovedAll() {
        // ✅ 线程安全的清空
        approvedAllByTool.clear();
        approvedAllByServer.clear();
    }
}
```

### 【学习要点】

1. **ConcurrentHashMap.newKeySet() 的用途**：
   ```java
   // ✅ 创建一个线程安全的 Set
   Set<String> set = ConcurrentHashMap.newKeySet();
   
   // 等价于：
   Map<String, Boolean> map = new ConcurrentHashMap<>();
   Set<String> set = map.keySet();
   ```

2. **为什么不用 Collections.synchronizedSet**：
   ```java
   // ❌ 虽然线程安全但性能差
   Set<String> set = Collections.synchronizedSet(new HashSet<>());
   
   // ✅ ConcurrentHashMap.newKeySet 更高效
   Set<String> set = ConcurrentHashMap.newKeySet();
   ```

---

## 专题 8：MCP Transport - 监听器列表的 CopyOnWriteArrayList

### 【场景描述】

PaiCLI 的 MCP transport 需要管理多个**通知监听器**，这些监听器在不同的地方注册/注销，读取时需要迭代所有监听器。

### 【代码讲解】

**真实代码**（来自 `StreamableHttpTransport.java` 和 `StdioTransport.java`）：

```java
public class StdioTransport {
    // ✅ 使用 CopyOnWriteArrayList
    // 特点：
    // - 读操作很快（不加锁）
    // - 写操作较慢（复制整个数组）
    // - 完美适合"读多写少"的场景
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();

    public void addNotificationListener(Consumer<JsonNode> listener) {
        // ✅ 添加监听器
        // 内部：创建新数组，复制旧元素，追加新元素
        listeners.add(listener);
    }

    private void notifyListeners(JsonNode message) {
        // ✅ 迭代所有监听器（不加锁）
        // 即使有线程添加新监听器，这个迭代也不受影响
        for (Consumer<JsonNode> listener : listeners) {
            listener.accept(message);
        }
    }
}
```

### 【对比分析】

| 实现方式 | 读 | 写 | 迭代安全 | 适用场景 |
|---------|----|----|----------|---------|
| ❌ ArrayList | 快 | 快 | ❌ 否 | 单线程 |
| ❌ synchronized List | 中 | 中 | ✅ 是 | 均衡 |
| ✅ CopyOnWriteArrayList | ⭐⭐⭐ 快 | 慢 | ✅ 是 | 读多写少 |

**使用场景**：
- ✅ Event listeners（监听器列表）
- ✅ Notification subscribers（订阅者列表）
- ❌ 频繁增删的集合

### 【学习要点】

1. **CopyOnWriteArrayList 的 "Copy-On-Write" 原理**：
   ```java
   private volatile Object[] array = new Object[0];
   
   public void add(E e) {
       synchronized (this) {
           Object[] oldArray = array;
           Object[] newArray = new Object[oldArray.length + 1];
           
           // 复制旧数组
           System.arraycopy(oldArray, 0, newArray, 0, oldArray.length);
           
           // 追加新元素
           newArray[oldArray.length] = e;
           
           // 原子替换引用
           array = newArray;
       }
   }
   
   public Iterator<E> iterator() {
       // ✅ 返回的是当前数组的快照
       // 迭代过程中，add() 修改的是另一个数组
       // 所以迭代不受影响
       return new SnapshotIterator(array);
   }
   ```

2. **为什么不用 ConcurrentHashMap + values()**：
   ```java
   // ❌ 不适合这个场景
   Map<Integer, Consumer> listeners = new ConcurrentHashMap<>();
   for (Consumer listener : listeners.values()) {
       // 遍历时有新增/删除，结果不确定
   }
   
   // ✅ 用 CopyOnWriteArrayList
   List<Consumer> listeners = new CopyOnWriteArrayList<>();
   for (Consumer listener : listeners) {
       // 完全安全，遍历的是不变快照
   }
   ```

---

## 总结：8 个 JUC 工具的最佳实践

| 工具 | 作用 | 使用场景 | PaiCLI 中的例子 |
|------|------|---------|----------------|
| **ExecutorService** | 线程池 | 并行执行多个任务 | ToolRegistry.executeTools |
| **invokeAll()** | 批量执行 + 顺序 + 超时 | 多工具并行执行 | ToolRegistry |
| **Future/Callable** | 异步执行 | 读取进程输出 | RipgrepCodeSearchEngine |
| **ScheduledExecutorService** | 定时任务 | UI 定时刷新 | InlineActivityDisplay |
| **synchronized** | 互斥锁 | 保护共享状态 | InlineActivityDisplay |
| **ConcurrentHashMap** | 高效并发 Map | 并发存储 | LongTermMemory |
| **AtomicInteger** | 原子计数 | 线程安全计数 | LongTermMemory |
| **CopyOnWriteArrayList** | 读多写少 | 监听器列表 | MCP Transport |
| **ConcurrentHashMap.newKeySet()** | 并发 Set | 记住已批准项 | TuiHitlHandler |

---

## 常见陷阱与避免方法

### 陷阱 1：在读线程中发送请求并等响应（死锁）
```java
// ❌ 死锁
while (true) {
    JsonNode message = readLine();  // 读线程
    if (isRequest(message)) {
        handler.accept(message);    // 如果 handler 发请求等响应
    }                               // 响应无人读 → 死锁
}

// ✅ 异步派发
while (true) {
    JsonNode message = readLine();
    if (isRequest(message)) {
        executor.submit(() -> handler.accept(message));  // 派发到另一个线程
    }
}
```

### 陷阱 2：忘了 shutdown ExecutorService（资源泄漏）
```java
// ❌ 线程没有清理
ExecutorService executor = Executors.newFixedThreadPool(4);
executor.submit(task1);
executor.submit(task2);
// 忘了 shutdown，线程池永远存在

// ✅ 必须 shutdown
try {
    executor.invokeAll(tasks, timeout, unit);
} finally {
    executor.shutdownNow();  // 必须清理
}
```

### 陷阱 3：取消 ScheduledFuture 但没有 shutdown scheduler（资源泄漏）
```java
// ❌ 线程池还在后台运行
ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(...);
task.cancel(false);
// 线程池没有关闭

// ✅ 必须 shutdown
task.cancel(false);
scheduler.shutdownNow();
```

### 陷阱 4：访问 StringBuilder 没有加锁（竞态条件）
```java
// ❌ 多线程竞态
StringBuilder buffer = new StringBuilder();
// 线程 A
buffer.append("hello");
// 线程 B
buffer.setLength(0);  // 可能导致索引错误

// ✅ 加锁
synchronized void append(String s) {
    buffer.append(s);
}
```

### 陷阱 5：用 HashMap 而不是 ConcurrentHashMap（ConcurrentModificationException）
```java
// ❌ 非线程安全
Map<String, String> map = new HashMap<>();
map.put("key1", "value1");

// 线程 A: 遍历
for (String value : map.values()) {
    process(value);
}

// 线程 B: 修改
map.remove("key1");  // ConcurrentModificationException!

// ✅ 使用 ConcurrentHashMap
Map<String, String> map = new ConcurrentHashMap<>();
// 即使有并发修改也不会异常
```

### 陷阱 6：在 synchronized 块内做耗时操作（性能差）
```java
// ❌ 整个方法都加锁
synchronized void processLargeData(Data data) {
    // 可能耗时 10 秒
    data.process();  // 其他线程都在等待
}

// ✅ 只保护关键部分
void processLargeData(Data data) {
    Data copy;
    synchronized (this) {
        copy = data.clone();  // 只锁这部分
    }
    copy.process();  // 不加锁做耗时操作
}
```

---

## 推荐的学习路径

**第一阶段：理解基础概念**（1-2 天）
1. ✅ 专题 1：ExecutorService - 并行执行的基础
2. ✅ 专题 6：CancellationToken - 最简单的原子操作
3. ✅ 专题 5：LongTermMemory - 理解 ConcurrentHashMap 和 AtomicInteger

**第二阶段：深入死锁问题**（3-4 天）
1. ✅ 专题 2：RipgrepCodeSearchEngine - 进程 IO 的死锁
2. ✅ 专题 3：NotificationRouter - 异步派发避免死锁
3. ✅ 反复阅读代码注释，理解"读线程不能阻塞"的重要性

**第三阶段：掌握定时和集合**（5-6 天）
1. ✅ 专题 4：InlineActivityDisplay - ScheduledExecutorService 的使用
2. ✅ 专题 7：TuiHitlHandler - ConcurrentHashMap.newKeySet()
3. ✅ 专题 8：MCP Transport - CopyOnWriteArrayList
4. ✅ 对比不同集合的性能特性

**第四阶段：综合应用**（7+ 天）
1. ✅ 完成所有练习题
2. ✅ 在自己的项目中应用这些模式
3. ✅ 审视现有代码，寻找并发问题

---

## 参考资源

- 书籍：《Java 并发编程实战》（Java Concurrency in Practice）
- 文档：[The Java Tutorials - Concurrency](https://docs.oracle.com/javase/tutorial/essential/concurrency/)
- 源码：PaiCLI 的 JUC 应用都在 `src/main/java/com/paicli/` 目录下

---

## 反馈与讨论

如果在学习过程中遇到问题，建议：
1. 复现错误场景（编写最小化的 test case）
2. 查看相关 JUnit 测试（如 NotificationRouterTest）
3. 参考官方文档和源代码注释
4. 在 PaiCLI 项目中提交 issue

祝你学有所成！🎉

