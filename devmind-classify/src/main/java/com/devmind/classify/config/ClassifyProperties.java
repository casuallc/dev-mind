package com.devmind.classify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.classify.* — CAP-57 分类服务配置。主类 {@code @ConfigurationPropertiesScan} 全局扫描，
 * 无需注册。
 */
@ConfigurationProperties(prefix = "devmind.classify")
public class ClassifyProperties {

    /**
     * 安装包存储目录（服务端本地磁盘；上传流式落盘到这里，节点经 HTTP 拉取）。
     *
     * <p>默认相对 cwd 的 {@code data/classify-packages}（与 H2 文件库同级的运行时数据）；
     * 分发包部署在 {@code config/application-local.yml} 指到数据盘。</p>
     */
    private String storageDir = "data/classify-packages";

    /** 健康轮询间隔（毫秒）：对 STARTING/RUNNING/UNHEALTHY 实例打 /healthz */
    private long healthIntervalMs = 30_000;

    /**
     * start 后的健康宽限期（毫秒，默认 10min）：GB 级权重加载慢，宽限期内 healthz 失败
     * 保持 STARTING 不判 UNHEALTHY；超期失败才转 UNHEALTHY（不自动拉起）。
     */
    private long startGraceMs = 600_000;

    /** healthz 单次调用超时（秒）：轮询不是流量路径，10s 足够分辨「没起」与「在忙」 */
    private int healthTimeoutSeconds = 10;

    /** 试分类单次调用超时（秒）：批量/长 state 推理比 healthz 慢，给 60s */
    private int playgroundTimeoutSeconds = 60;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public long getHealthIntervalMs() { return healthIntervalMs; }
    public void setHealthIntervalMs(long healthIntervalMs) { this.healthIntervalMs = healthIntervalMs; }
    public long getStartGraceMs() { return startGraceMs; }
    public void setStartGraceMs(long startGraceMs) { this.startGraceMs = startGraceMs; }
    public int getHealthTimeoutSeconds() { return healthTimeoutSeconds; }
    public void setHealthTimeoutSeconds(int healthTimeoutSeconds) { this.healthTimeoutSeconds = healthTimeoutSeconds; }
    public int getPlaygroundTimeoutSeconds() { return playgroundTimeoutSeconds; }
    public void setPlaygroundTimeoutSeconds(int playgroundTimeoutSeconds) { this.playgroundTimeoutSeconds = playgroundTimeoutSeconds; }
}
