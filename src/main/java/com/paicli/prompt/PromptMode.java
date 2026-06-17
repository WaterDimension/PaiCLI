package com.paicli.prompt;

public enum PromptMode {
    /*
        代码不会因为写了 TEAM_PLANNER 就自动会规划；真正让它表现成“规划者”的，是这条枚举值最终把系统提示词指向了
        team-planner.md
     */
    AGENT("modes/agent.md"),
    PLAN("modes/plan.md"),
    PLANNER("modes/planner.md"),
    TEAM_PLANNER("modes/team-planner.md"),
    TEAM_WORKER("modes/team-worker.md"),
    TEAM_REVIEWER("modes/team-reviewer.md");

    private final String resourcePath;

    PromptMode(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String resourcePath() {
        return resourcePath;
    }
}
