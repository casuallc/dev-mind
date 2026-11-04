package com.devmind.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * devmind.security 配置（CAP-66）。
 *
 * <p><b>server-url</b>：平台对外绝对基址（报告上行端点要拼进 exec env 下发 runner，
 * 服务端无法自知公网地址，CAP-73 {@code verify.server-url} 先例）——空 = 发起扫描即 400 fail-fast。</p>
 *
 * <p><b>internal-group-prefixes</b>：SCA 内部构件白名单（groupId 前缀，如 com.apusic），
 * 与项目追加（security_project_configs）合并；命中构件的 CPE 匹配视为噪声直接过滤。</p>
 */
@ConfigurationProperties(prefix = "devmind.security")
public class SecurityProperties {

    /** 平台对外绝对基址（如 https://dev.example.com），空 = 发起扫描 400 */
    private String serverUrl = "";

    /** 平台级内部构件 groupId 前缀白名单 */
    private List<String> internalGroupPrefixes = new ArrayList<>();

    /** EPSS/KEV 情报同步开关（离线环境关 false，同步失败不阻塞扫描） */
    private boolean intelEnabled = true;

    /** EPSS CSV 数据源（FIRST.org 公开下载；内网可换镜像） */
    private String epssUrl = "https://epss.empiricalsecurity.com/epss_scores-current.csv.gz";

    /** CISA KEV 目录 JSON 数据源 */
    private String kevUrl = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";

    /** 报告上传 token 有效期（分钟），引擎跑超时未上传即失效 → 该引擎 FAILED */
    private long uploadTokenTtlMinutes = 120;

    /** 报告上传大小上限（字节），默认 100MB */
    private long uploadMaxBytes = 100L * 1024 * 1024;

    /** 单引擎 exec 步超时（秒）：dependency-check 首次拉 NVD 缓存可达数十分钟，远超全局 30 分钟默认 */
    private long execTimeoutSec = 2 * 3600L;

    /** SAST 引擎 semgrep 的规则集（--config 值；离线环境配本地规则目录/文件） */
    private String semgrepConfig = "auto";

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public List<String> getInternalGroupPrefixes() { return internalGroupPrefixes; }
    public void setInternalGroupPrefixes(List<String> internalGroupPrefixes) { this.internalGroupPrefixes = internalGroupPrefixes; }
    public boolean isIntelEnabled() { return intelEnabled; }
    public void setIntelEnabled(boolean intelEnabled) { this.intelEnabled = intelEnabled; }
    public String getEpssUrl() { return epssUrl; }
    public void setEpssUrl(String epssUrl) { this.epssUrl = epssUrl; }
    public String getKevUrl() { return kevUrl; }
    public void setKevUrl(String kevUrl) { this.kevUrl = kevUrl; }
    public long getUploadTokenTtlMinutes() { return uploadTokenTtlMinutes; }
    public void setUploadTokenTtlMinutes(long uploadTokenTtlMinutes) { this.uploadTokenTtlMinutes = uploadTokenTtlMinutes; }
    public long getUploadMaxBytes() { return uploadMaxBytes; }
    public void setUploadMaxBytes(long uploadMaxBytes) { this.uploadMaxBytes = uploadMaxBytes; }
    public long getExecTimeoutSec() { return execTimeoutSec; }
    public void setExecTimeoutSec(long execTimeoutSec) { this.execTimeoutSec = execTimeoutSec; }
    public String getSemgrepConfig() { return semgrepConfig; }
    public void setSemgrepConfig(String semgrepConfig) { this.semgrepConfig = semgrepConfig; }
}
