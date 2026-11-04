package com.devmind.security.engine;

import java.util.List;

/**
 * 扫描引擎适配器 SPI（CAP-66 FR-02）：引擎按 Spring Bean 注册，主链路按 engineId 查找。
 * 归一化（parse/fingerprint）在<b>服务端</b>做——解析器升级随平台发版，不强依赖 runner 版本；
 * runner 只负责跑引擎二进制 + curl 回传原始报告。
 *
 * <p>脚本约定：POSIX sh（runner 一律以 execShell=bash 执行，Windows 为 Git Bash）；
 * 引擎以<b>裸命令名</b>调用（过 runner execAllowlist 前缀校验，路径解析在 runner 侧
 * {@code scan.<engine>.path} 配置 → prepend PATH）；每行首 token 须命中白名单或 shell 内置命令。</p>
 */
public interface ScanEngineAdapter {

    /** 引擎标识：sca / sast / secrets（标签路由、表存储、上报 format 同源） */
    String engineId();

    /** 展示名（任务详情引擎子步骤条） */
    String displayName();

    /** 引擎二进制裸命令名（runner 侧 where/which 探测与 PATH 注入依据） */
    String binaryName();

    /** 工具自检命令（--version 级，输出首行即版本回显） */
    String versionCommand();

    /** 主报告格式（security_scan_reports.format）：sca=json，sast/secrets=sarif */
    String reportFormat();

    /** 主报告文件名（脚本产出与上传同名） */
    String reportFileName();

    /**
     * 完整 exec 脚本：引擎调用 + 报告上传尾步（curl -F 上行 {@code DEVMIND_SCAN_UPLOAD_URL}）。
     * 引擎退出码非零但报告已产出（semgrep/gitleaks 发现即 1）时须放行到上传步；
     * 报告缺失时上传失败 → 步骤失败 → 该引擎 FAILED。
     */
    String execScript(ScanExecContext ctx);

    /** 主报告字节 → 归一化发现集（fingerprint 未填，由 {@link #fingerprint} 逐条计算） */
    List<NormalizedFinding> parse(byte[] report);

    /**
     * fingerprint 分型公式（FR-04）：
     * SCA = sha1("sca" | cve | groupId:artifactId)；
     * SAST/secrets = sha1(engineId | ruleId | 仓库相对路径 | 目标行去空白哈希)。
     */
    String fingerprint(NormalizedFinding f);

    /** 工具：sha1 十六进制（fingerprint 与 codeHash 共用） */
    static String sha1(String... parts) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(String.join("|", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
