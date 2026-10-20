package com.devmind.copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.copilot.* —— CAP-76 平台智能体网关配置。主类 {@code @ConfigurationPropertiesScan}
 * 全局扫描，无需注册。
 */
@ConfigurationProperties(prefix = "devmind.copilot")
public class CopilotProperties {

    /** 首次部署默认关闭；配置并验证 CHAT 端点后显式开启 */
    private boolean enabled = false;

    /** 空 = 平台默认 CHAT 端点 */
    private String endpointId = "";

    /** native | json | auto */
    private String toolMode = "auto";

    private int maxSteps = 8;

    private int maxToolCallsPerStep = 3;

    private int maxWritesPerTurn = 3;

    private int maxToolResultChars = 4000;

    private int toolContextCharBudget = 16000;

    private int historyCharBudget = 24000;

    private double temperature = 0.2;

    private int toolTimeoutSeconds = 30;

    private int modelTimeoutSeconds = 90;

    private int turnTimeoutSeconds = 180;

    private int confirmTtlMinutes = 10;

    /** 危险操作完整预览的分段长度（不受 bot reply-max-chars 约束） */
    private int confirmPreviewChunkChars = 3000;

    /** 超过则拒绝创建确认单（绝不截断后执行） */
    private int confirmPreviewMaxChunks = 10;

    private int maxTurnsPerMinute = 10;

    private int maxConcurrentModel = 4;

    private boolean replyProgressCard = true;

    /** identitySupported=false 的渠道是否允许仅凭 defaultActor 做写操作 */
    private boolean allowDefaultActorWrites = false;

    /** 审计保留天数（0 = 不清理） */
    private int auditRetentionDays = 180;

    /** 仅保留期删除 */
    private String cleanupCron = "0 40 3 * * *";

    /** PENDING→EXPIRED / EXECUTING→FAILED；空 = 关闭 */
    private String actionSweepCron = "0 * * * * *";

    /** submit_plan 单计划最大步骤数（FR-33，M7） */
    private int planMaxSteps = 5;

    /** submit_plan 异步执行整体上限（分钟，FR-34，M8）：sweep 判 EXECUTING 计划中断的阈值 */
    private int planExecutionTimeoutMinutes = 120;

    /** await 终态轮询间隔（秒，FR-35；下限 5） */
    private int planAwaitPollSeconds = 10;

    /** await timeoutMinutes 上限（FR-35） */
    private int planAwaitMaxTimeoutMinutes = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }
    public String getToolMode() { return toolMode; }
    public void setToolMode(String toolMode) { this.toolMode = toolMode; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public int getMaxToolCallsPerStep() { return maxToolCallsPerStep; }
    public void setMaxToolCallsPerStep(int maxToolCallsPerStep) { this.maxToolCallsPerStep = maxToolCallsPerStep; }
    public int getMaxWritesPerTurn() { return maxWritesPerTurn; }
    public void setMaxWritesPerTurn(int maxWritesPerTurn) { this.maxWritesPerTurn = maxWritesPerTurn; }
    public int getMaxToolResultChars() { return maxToolResultChars; }
    public void setMaxToolResultChars(int maxToolResultChars) { this.maxToolResultChars = maxToolResultChars; }
    public int getToolContextCharBudget() { return toolContextCharBudget; }
    public void setToolContextCharBudget(int toolContextCharBudget) { this.toolContextCharBudget = toolContextCharBudget; }
    public int getHistoryCharBudget() { return historyCharBudget; }
    public void setHistoryCharBudget(int historyCharBudget) { this.historyCharBudget = historyCharBudget; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getToolTimeoutSeconds() { return toolTimeoutSeconds; }
    public void setToolTimeoutSeconds(int toolTimeoutSeconds) { this.toolTimeoutSeconds = toolTimeoutSeconds; }
    public int getModelTimeoutSeconds() { return modelTimeoutSeconds; }
    public void setModelTimeoutSeconds(int modelTimeoutSeconds) { this.modelTimeoutSeconds = modelTimeoutSeconds; }
    public int getTurnTimeoutSeconds() { return turnTimeoutSeconds; }
    public void setTurnTimeoutSeconds(int turnTimeoutSeconds) { this.turnTimeoutSeconds = turnTimeoutSeconds; }
    public int getConfirmTtlMinutes() { return confirmTtlMinutes; }
    public void setConfirmTtlMinutes(int confirmTtlMinutes) { this.confirmTtlMinutes = confirmTtlMinutes; }
    public int getConfirmPreviewChunkChars() { return confirmPreviewChunkChars; }
    public void setConfirmPreviewChunkChars(int confirmPreviewChunkChars) {
        this.confirmPreviewChunkChars = confirmPreviewChunkChars;
    }
    public int getConfirmPreviewMaxChunks() { return confirmPreviewMaxChunks; }
    public void setConfirmPreviewMaxChunks(int confirmPreviewMaxChunks) {
        this.confirmPreviewMaxChunks = confirmPreviewMaxChunks;
    }
    public int getMaxTurnsPerMinute() { return maxTurnsPerMinute; }
    public void setMaxTurnsPerMinute(int maxTurnsPerMinute) { this.maxTurnsPerMinute = maxTurnsPerMinute; }
    public int getMaxConcurrentModel() { return maxConcurrentModel; }
    public void setMaxConcurrentModel(int maxConcurrentModel) { this.maxConcurrentModel = maxConcurrentModel; }
    public boolean isReplyProgressCard() { return replyProgressCard; }
    public void setReplyProgressCard(boolean replyProgressCard) { this.replyProgressCard = replyProgressCard; }
    public boolean isAllowDefaultActorWrites() { return allowDefaultActorWrites; }
    public void setAllowDefaultActorWrites(boolean allowDefaultActorWrites) {
        this.allowDefaultActorWrites = allowDefaultActorWrites;
    }
    public int getAuditRetentionDays() { return auditRetentionDays; }
    public void setAuditRetentionDays(int auditRetentionDays) { this.auditRetentionDays = auditRetentionDays; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
    public String getActionSweepCron() { return actionSweepCron; }
    public void setActionSweepCron(String actionSweepCron) { this.actionSweepCron = actionSweepCron; }
    public int getPlanMaxSteps() { return planMaxSteps; }
    public void setPlanMaxSteps(int planMaxSteps) { this.planMaxSteps = planMaxSteps; }
    public int getPlanExecutionTimeoutMinutes() { return planExecutionTimeoutMinutes; }
    public void setPlanExecutionTimeoutMinutes(int v) { this.planExecutionTimeoutMinutes = v; }
    public int getPlanAwaitPollSeconds() { return planAwaitPollSeconds; }
    public void setPlanAwaitPollSeconds(int v) { this.planAwaitPollSeconds = v; }
    public int getPlanAwaitMaxTimeoutMinutes() { return planAwaitMaxTimeoutMinutes; }
    public void setPlanAwaitMaxTimeoutMinutes(int v) { this.planAwaitMaxTimeoutMinutes = v; }
}
