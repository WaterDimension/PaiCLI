package com.paicli.plan;

import java.util.*;

/**
 * 任务节点 - 表示一个可执行的任务单元
 */
public class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private volatile TaskStatus status;   // volatile 保证多线程可见性
    private volatile String result;
    private volatile String error;
    private final List<String> dependencies;  // 依赖的其他任务ID
    private final List<String> dependents;    // 依赖此任务的其他任务ID
    private volatile long startTime;
    private volatile long endTime;

    public enum TaskType {
        PLANNING,      // 规划任务
        FILE_READ,     // 读取文件
        FILE_WRITE,    // 写入文件
        COMMAND,       // 执行命令
        ANALYSIS,      // 分析结果
        VERIFICATION   // 验证结果
    }

    public enum TaskStatus {
        PENDING,       // 等待执行
        RUNNING,       // 执行中
        COMPLETED,     // 已完成
        FAILED,        // 失败
        SKIPPED        // 跳过
    }

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type);
        this.dependencies.addAll(dependencies);
    }

    // Getters
    public String getId() { return id; }
    public String getDescription() { return description; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    // 返回的是新创建的副本，防御性拷贝：外部拿到列表后不会改变Task内部的列表，保证了封装性
    public List<String> getDependencies() { return new ArrayList<>(dependencies); }
    public List<String> getDependents() { return new ArrayList<>(dependents); }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    // Setters
    public void setStatus(TaskStatus status) { this.status = status; }
    public void setResult(String result) { this.result = result; }
    public void setError(String error) { this.error = error; }

    // 1.关系添加
    // 1.1添加一个依赖此任务的任务ID（即这个任务完成后可以触发那些任务）
    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    // 1.2添加一个此任务依赖的任务ID（即这个任务需要等待哪些任务完成才能执行）
    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    // 状态标记方法(core)
    /*
        记录时间戳有两个用途：
            一是统计执行耗时，二是分析任务瓶颈。如果某个任务总是耗时很长，可能需要优化或者拆分。
     */
    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 获取执行耗时（毫秒）
     */
    public long getDuration() {
        if (startTime == 0) return 0;  //从未执行过
        if (endTime == 0) return System.currentTimeMillis() - startTime;  //正在执行中，计算到当前的耗时
        return endTime - startTime;
    }

    /**
     * 是否可以执行（所有依赖都已完成）
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) return false;

        // 检查所有依赖的任务是否都已完成
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, status);
    }
}
