package com.devmind.decisionlab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.decision-lab.* — CAP-56 决策实验室配置。主类 {@code @ConfigurationPropertiesScan}
 * 全局扫描，无需注册。
 */
@ConfigurationProperties(prefix = "devmind.decision-lab")
public class DecisionLabProperties {

    /**
     * 评测/微调脚本目录（服务端打包执行包时读它，节点拉包后跑）。
     *
     * <p>默认值是仓库内的相对路径，开发态（cwd=仓库根）直接可用；分发包里脚本随
     * {@code lab/} 目录外置分发，需在 {@code config/application-local.yml} 指向实际位置。
     * 空 = 未配置，此时发起评测/微调会被直接拒绝并说明该配哪一项。</p>
     */
    private String scriptsDir = "tools/laya-sidecar/lab";

    /**
     * 节点上 python 解释器路径（命令行首 token）。
     *
     * <p><b>必须是无空白的单 token</b>：runner 的 execAllowlist 逐行取首 token 校验前缀，
     * 带空格的路径会被节点白名单打回（"命令不在 execAllowlist 白名单"）。Windows 下用
     * venv 的 {@code Scripts/python.exe} 全路径、Linux 下用 {@code /opt/laya/venv/bin/python}
     * 之类不含空格的路径即可。含空白时触发阶段就拒绝，不把这个问题留到节点上报错。</p>
     */
    private String pythonPath = "python";

    /** 同时进行的评测/微调上限（一次运行独占一个节点许可，跑太多会把节点许可占满） */
    private int maxConcurrentRuns = 2;

    /** 单次运行的默认超时（秒）：批量推理 + 可选基线对照 + 温度校准 */
    private long defaultTimeoutSec = 3600;

    /** 单次运行允许的最大超时（秒）：默认 24 小时（RLCD 训练可能跑整夜） */
    private long maxTimeoutSec = 86400;

    // ---------------- FR-05 微调默认值（请求里可逐项覆盖） ----------------

    /**
     * 默认训练轮数。
     *
     * <p>默认值存在的意义只是"不传也能跑起来"，不是"推荐的超参"——RLCD 的收敛性只能靠真机看指标，
     * 页面上跑第一轮时就该把它改成本次实验真正要用的值。</p>
     */
    private int defaultEpochs = 3;

    /** 默认学习率（RLCD 的 REINFORCE 类更新对学习率敏感，默认偏小） */
    private double defaultLearningRate = 1e-4;

    private int defaultBatchSize = 8;

    /** 默认训练随机种子 */
    private long defaultTrainSeed = 42;

    /**
     * 默认切分种子。
     *
     * <p>固定默认值的好处是"两次跑同一份集、只改超参"时验证集是同一批样本——
     * 否则指标的变化里混着"换了一批考题"，说不清是模型变好了还是题目变简单了。</p>
     */
    private long defaultSplitSeed = 42;

    /** 默认训练占比（验证集 = 剩下那部分，至少 1 条） */
    private double defaultTrainRatio = 0.8;

    /**
     * 默认多卡启动前缀（空 = 单进程）。
     *
     * <p>形如 {@code torchrun --nproc_per_node=2}；由脚本按 shlex 拆开当子进程前缀，
     * 不占 runner execAllowlist 的首 token（首 token 永远是 pythonPath）。</p>
     */
    private String defaultLauncher = "";

    public String getScriptsDir() { return scriptsDir; }
    public void setScriptsDir(String scriptsDir) { this.scriptsDir = scriptsDir; }
    public String getPythonPath() { return pythonPath; }
    public void setPythonPath(String pythonPath) { this.pythonPath = pythonPath; }
    public int getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(int maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
    public long getDefaultTimeoutSec() { return defaultTimeoutSec; }
    public void setDefaultTimeoutSec(long defaultTimeoutSec) { this.defaultTimeoutSec = defaultTimeoutSec; }
    public long getMaxTimeoutSec() { return maxTimeoutSec; }
    public void setMaxTimeoutSec(long maxTimeoutSec) { this.maxTimeoutSec = maxTimeoutSec; }
    public int getDefaultEpochs() { return defaultEpochs; }
    public void setDefaultEpochs(int defaultEpochs) { this.defaultEpochs = defaultEpochs; }
    public double getDefaultLearningRate() { return defaultLearningRate; }
    public void setDefaultLearningRate(double defaultLearningRate) { this.defaultLearningRate = defaultLearningRate; }
    public int getDefaultBatchSize() { return defaultBatchSize; }
    public void setDefaultBatchSize(int defaultBatchSize) { this.defaultBatchSize = defaultBatchSize; }
    public long getDefaultTrainSeed() { return defaultTrainSeed; }
    public void setDefaultTrainSeed(long defaultTrainSeed) { this.defaultTrainSeed = defaultTrainSeed; }
    public long getDefaultSplitSeed() { return defaultSplitSeed; }
    public void setDefaultSplitSeed(long defaultSplitSeed) { this.defaultSplitSeed = defaultSplitSeed; }
    public double getDefaultTrainRatio() { return defaultTrainRatio; }
    public void setDefaultTrainRatio(double defaultTrainRatio) { this.defaultTrainRatio = defaultTrainRatio; }
    public String getDefaultLauncher() { return defaultLauncher; }
    public void setDefaultLauncher(String defaultLauncher) { this.defaultLauncher = defaultLauncher; }
}
