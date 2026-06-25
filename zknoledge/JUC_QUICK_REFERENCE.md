# PaiCLI JUC 快速查阅表

## 🔥 最常用的 JUC 工具（必背）

### 1️⃣ ExecutorService - 并行执行任务

```java
// 场景：3 个工具要同时执行，而不是一个接一个
int threads = Math.min(3, MAX_PARALLEL);
ExecutorService executor = Executors.newFixedThreadPool(threads);

try {
    // 将任务包装成 Callable（有返回值的 Runnable）
    List<Callable<Result>> tasks = List.of(
        () -> tool1.execute(),
        () -> tool2.execute(),
        () -> tool3.execute()
    );
    
    // invokeAll：并行执行，等所有完成（或超时），返回 Future 列表
    List<Future<Result>> futures = executor.invokeAll(tasks, 90, TimeUnit.SECONDS);
    
    // 收集结果
    List<Result> results = new ArrayList<>();
    for (Future<Result> future : futures) {
        if (future.isCancelled()) {
            results.add(Result.timedOut());
        } else {
            try {
                results.add(future.get());  // 一般已完成，不阻塞
            } catch (ExecutionException e) {
                results.add(Result.failed(e.getCause()));
            }
        }
    }
} finally {
    executor.shutdownNow();  // 必须关闭！
}
```

**为什么用这个？**
- ✅ 三个工具从 1+2+3=6 秒 → 3 秒（最长的那个）
- ✅ synchronized 只能串行，没法并行
- ✅ 自动管理线程生命周期，不用手写 Thread

**常见错误**
```java
// ❌ 忘记 shutdownNow()
for (Callable<Result> task : tasks) {
    executor.submit(task);
}
// 线程一直活着！

// ✅ 正确
try {
    // ...
} finally {
    executor.shutdownNow();  // 一定记得
}
```

---

### 2️⃣ ConcurrentHashMap - 高并发读写

```java
// 场景：多个线程同时读写一个 Map（如内存缓存、配置存储）
private Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();

// 写入（可从多个线程调用）
public void store(MemoryEntry entry) {
    entries.put(entry.getId(), entry);  // ✅ 线程安全
}

// 读取（高效，无锁）
public Optional<MemoryEntry> retrieve(String id) {
    return Optional.ofNullable(entries.get(id));  // ✅ 快速
}

// 删除
public boolean delete(String id) {
    return entries.remove(id) != null;  // ✅ 线程安全
}

// 遍历（安全，但可能看不到最新修改）
public void printAll() {
    for (MemoryEntry e : entries.values()) {
        System.out.println(e);  // ✅ 不会 CME
    }
}
```

**为什么用这个？**
- ✅ 比 `Collections.synchronizedMap()` 快 10 倍
- ✅ 内部分段锁，多线程可同时访问
- ✅ 不会 ConcurrentModificationException

**对比 synchronized HashMap**
```java
// ❌ synchronized HashMap（全局锁）
private final Map<K, V> map = Collections.synchronizedMap(new HashMap<>());

public V get(K key) {
    return map.get(key);  // 即使只是读，也要加全局锁！
}

// ✅ ConcurrentHashMap（分段锁）
private final Map<K, V> map = new ConcurrentHashMap<>();

public V get(K key) {
    return map.get(key);  // 无锁读，不用等！
}
```

---

### 3️⃣ AtomicInteger/AtomicBoolean - 原子操作

```java
// 场景 1：计数（频繁累加）
private AtomicInteger tokenCounter = new AtomicInteger(0);

public void addTokens(int count) {
    tokenCounter.addAndGet(count);  // ✅ 原子累加，无锁
}

public int getTokenCount() {
    return tokenCounter.get();  // ✅ 无锁读，比 synchronized 快 100 倍
}

// 场景 2：取消信号（频繁检查）
private AtomicBoolean cancelled = new AtomicBoolean(false);

public void cancel() {
    cancelled.set(true);  // ✅ 原子设置
}

public boolean isCancelled() {
    return cancelled.get();  // ✅ 频繁检查不用加锁
}

// 场景 3：CAS 更新（比较后更新）
private AtomicReference<Config> config = new AtomicReference<>(defaultConfig);

public void updateIfChanged(Config newConfig) {
    config.getAndUpdate(old -> newConfig.isNewer() ? newConfig : old);  // ✅ 原子更新
}
```

**为什么用这个？**
- ✅ 比 synchronized 快 100 倍（无锁，用 CAS）
- ✅ 适合频繁读的场景
- ✅ 代码意图清晰

**什么时候不能用 volatile？**
```java
// ❌ volatile 不保证原子性
private volatile int counter = 0;

public void increment() {
    counter++;  // 不是原子的！
    // 1. 读 counter = 5
    // 2. 加 1 = 6
    // 3. 写回 counter = 6
    // 中间可能有其他线程修改，导致数据丢失
}

// ✅ AtomicInteger 保证原子性
private AtomicInteger counter = new AtomicInteger(0);

public void increment() {
    counter.incrementAndGet();  // ✅ CAS，原子的
}
```

---

### 4️⃣ CopyOnWriteArrayList - 读多写少的列表

```java
// 场景：事件监听器列表（频繁遍历，很少 add/remove）
private List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

public void subscribe(Consumer<Event> listener) {
    listeners.add(listener);  // ✅ 创建新数组（代价大，但次数少）
}

public void publish(Event event) {
    // ✅ 遍历时不加锁，即使其他线程在 add
    // 底层返回当前数组的快照，新 listeners 下次遍历才看到
    for (Consumer<Event> listener : listeners) {
        listener.accept(event);
    }
}
```

**为什么用这个？**
- ✅ 遍历快（无锁）
- ✅ 修改稍慢（创建新数组），但修改很少
- ✅ 不会 ConcurrentModificationException

**对比 Collections.synchronizedList()**
```java
// ❌ 同步列表（全局锁，遍历慢）
List<Listener> listeners = Collections.synchronizedList(new ArrayList<>());
for (Listener l : listeners) {  // 每次遍历都加锁
    l.handle(event);
}

// ✅ CopyOnWriteArrayList（读无锁）
List<Listener> listeners = new CopyOnWriteArrayList<>();
for (Listener l : listeners) {  // 无锁！
    l.handle(event);
}
```

---

### 5️⃣ ScheduledExecutorService - 定时任务

```java
// 场景：每 250ms 刷新一次 UI 动画
private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "ui-ticker");
    t.setDaemon(true);
    return t;
});

private ScheduledFuture<?> tickTask;

public void startAnimation() {
    // 每 250ms 执行一次 tick()
    tickTask = scheduler.scheduleAtFixedRate(
        this::tick,              // 要执行的任务
        250,                     // 初始延迟
        250,                     // 周期
        TimeUnit.MILLISECONDS
    );
}

public void stopAnimation() {
    if (tickTask != null) {
        tickTask.cancel(false);  // 停止定时任务
    }
}

private void tick() {
    frame++;  // 下一帧
    render();  // 重绘
}

@Override
public void close() {
    scheduler.shutdownNow();  // 关闭线程池
}
```

**为什么用这个？**
- ✅ 精确的周期（基于系统定时器）
- ✅ 比 Thread.sleep() 好得多
- ✅ 自动管理线程

**vs 手动 Thread + sleep**
```java
// ❌ 手动管理（容易出 bug）
new Thread(() -> {
    while (running) {
        tick();
        Thread.sleep(250);  // 精度不高
    }
}).start();
// 问题：
// 1. 没法精确控制
// 2. 停止时要手动 interrupt
// 3. 代码复杂

// ✅ ScheduledExecutorService（简洁）
scheduler.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
// 优点：
// 1. 精确
// 2. 停止：tickTask.cancel()
// 3. 代码简洁
```

---

## 🎯 选择工具的决策树

```
需要并行执行多个任务？
├─ 是 → ExecutorService
└─ 否 ↓

需要并发读写数据结构？
├─ 是 → 是否读多写少？
│       ├─ 读多写少（如缓存）→ ConcurrentHashMap
│       ├─ 读多写极少（如监听器）→ CopyOnWriteArrayList
│       └─ 读写均衡 → 还是用 ConcurrentHashMap
└─ 否 ↓

需要频繁读一个状态值？
├─ 是 → AtomicInteger/AtomicBoolean/AtomicReference
└─ 否 ↓

需要定时执行任务？
├─ 是 → ScheduledExecutorService
└─ 否 ↓

需要多线程同步屏障（等待 N 个事件）？
├─ 是 → CountDownLatch
└─ 否 → 可能不需要 JUC
```

---

## 💡 三种并发方案对比

### 场景：后台执行网络请求，主线程等待结果

```java
// ❌ 方案 1：手动线程 + volatile（容易出 bug）
private volatile String result = null;

new Thread(() -> {
    result = fetchData();
}).start();

// 等待结果（不能设超时！）
while (result == null) {
    Thread.sleep(100);  // 忙轮询，浪费 CPU
}
System.out.println(result);

// ❌ 方案 2：synchronized（效率低）
private String result = null;

public synchronized void fetchAndSet() {
    result = fetchData();
    notifyAll();  // 唤醒等待线程
}

public synchronized String waitForResult() {
    while (result == null) {
        wait();  // 等待，但要处理 InterruptedException
    }
    return result;
}

// ✅ 方案 3：ExecutorService + Future（最佳）
ExecutorService executor = Executors.newSingleThreadExecutor();

Future<String> future = executor.submit(() -> fetchData());

try {
    String result = future.get(5, TimeUnit.SECONDS);  // ✅ 可设超时
    System.out.println(result);
} catch (TimeoutException e) {
    System.out.println("超时");
    future.cancel(true);  // 取消任务
} finally {
    executor.shutdownNow();
}
```

---

## 🚨 最常犯的错误

### 错误 1：用 synchronized 做所有并发事务

```java
// ❌ 错误（低效）
public synchronized void process(List<Data> items) {
    for (Data item : items) {
        System.out.println(item);
    }
}

// ✅ 正确（用 ConcurrentHashMap）
private final Map<String, Data> items = new ConcurrentHashMap<>();

public void process() {
    for (Data item : items.values()) {
        System.out.println(item);  // 无锁读，并发度高
    }
}
```

### 错误 2：创建完 ExecutorService 忘记关闭

```java
// ❌ 错误
public void execute() {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    executor.submit(() -> doWork());
    // 忘记 shutdownNow()，线程一直活着！内存泄漏！
}

// ✅ 正确
public void execute() {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
        executor.submit(() -> doWork());
    } finally {
        executor.shutdownNow();  // 无论如何都要关
    }
}
```

### 错误 3：Future.get() 忘记处理异常

```java
// ❌ 错误
Future<Result> future = executor.submit(() -> doWork());
Result result = future.get();  // 如果 doWork() 抛异常会 ExecutionException

// ✅ 正确
Future<Result> future = executor.submit(() -> doWork());
try {
    Result result = future.get(5, TimeUnit.SECONDS);
} catch (ExecutionException e) {
    System.out.println("任务失败: " + e.getCause());
} catch (TimeoutException e) {
    System.out.println("任务超时");
    future.cancel(true);
}
```

### 错误 4：在 synchronized 块里做耗时操作

```java
// ❌ 错误（会卡所有其他线程）
public synchronized void process(List<Data> items) {
    for (Data item : items) {
        slowNetworkCall(item);  // 每个 1 秒，锁住了！
    }
}

// ✅ 正确（让每个任务独立运行）
public void process(List<Data> items) {
    for (Data item : items) {
        executor.submit(() -> slowNetworkCall(item));  // 异步执行
    }
}
```

---

## 📋 PaiCLI 中的实际例子

### 例 1：工具并行执行（ToolRegistry.java）

```java
// LLM 同时调用 3 个工具
List<ToolInvocation> invocations = List.of(
    new ToolInvocation("read_file", {"path": "README.md"}),
    new ToolInvocation("read_file", {"path": "pom.xml"}),
    new ToolInvocation("execute_command", {"command": "mvn compile"})
);

// 创建 3 线程的线程池
int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);  // 3
ExecutorService executor = Executors.newFixedThreadPool(parallelism);

// 执行
List<Future<ToolExecutionResult>> futures = executor.invokeAll(
    invocations.stream()
        .map(inv -> (Callable<ToolExecutionResult>) () -> executeToolOutput(inv.name(), inv.args))
        .toList(),
    90,  // 超时 90 秒
    TimeUnit.SECONDS
);

// 结果：
// 原来串行 2+1+5=8 秒
// 现在并行 5 秒（最长的那个）
// ✅ 提升 60%
```

### 例 2：内存存储（LongTermMemory.java）

```java
// 多线程同时读写记忆
private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();

// UI 线程：保存记忆
entries.put(id, entry);  // ✅ 无锁

// Agent 线程：查询记忆
Optional<MemoryEntry> found = retrieve(id);  // ✅ 不用等 UI 线程

// 搜索记忆
List<MemoryEntry> results = entries.values().stream()
    .filter(e -> matches(e, query))
    .collect(toList());  // ✅ 同时进行的 put 不会影响
```

### 例 3：MCP 通知派发（NotificationRouter.java）

```java
// 问题：server 推送通知到 stdout reader 线程
// handler 内部可能要发请求回 server（会等响应）
// 如果同步执行 handler，stdout reader 被卡住，响应进不来 → 死锁

// 解决：异步派发
private ScheduledExecutorService dispatcher = Executors.newSingleThreadExecutor(...);

@Override
public void accept(JsonNode notification) {
    String method = notification.path("method").asText();
    Consumer<JsonNode> handler = handlers.get(method);
    if (handler == null) return;
    
    // ⭐ 关键：不在当前线程执行，而是提交给 dispatcher
    dispatcher.submit(() -> handler.accept(notification));
    // ✅ 立刻返回，stdout reader 继续工作
    // ✅ handler 在独立线程里执行，即使它发请求也不会死锁
}
```

---

## 🎓 学习路线

### 第 1 阶段：掌握基础（1 周）
- [ ] ExecutorService 的 submit() / invokeAll()
- [ ] ConcurrentHashMap vs synchronized HashMap
- [ ] Future 和 ExecutionException

### 第 2 阶段：掌握常用工具（1 周）
- [ ] AtomicInteger/AtomicBoolean
- [ ] CopyOnWriteArrayList
- [ ] ScheduledExecutorService

### 第 3 阶段：实战练习（1-2 周）
- [ ] 写一个工具执行器（ToolRegistry 式）
- [ ] 写一个内存缓存系统（LongTermMemory 式）
- [ ] 写一个事件系统（NotificationRouter 式）

### 第 4 阶段：高级话题（可选）
- [ ] ThreadLocal 和线程本地存储
- [ ] Semaphore 和 Barrier
- [ ] CAS 和无锁编程

