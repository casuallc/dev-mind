package com.devmind.security.engine;

import java.util.Map;

/**
 * 归一化发现（CAP-66 FR-02）：引擎适配器 parse 产物，fingerprint 由适配器按引擎分型公式计算
 * （{@link ScanEngineAdapter#fingerprint}），状态机（NEW/EXISTING/…）由 FindingService 比对层赋予。
 *
 * @param engine     sca / sast / secrets
 * @param ruleId     SCA=CVE 编号；SAST=semgrep ruleId；secrets=gitleaks 规则名（即密钥类型）
 * @param severity   CRITICAL / HIGH / MEDIUM / LOW / INFO（适配器已按引擎映射表归一）
 * @param title      列表展示标题
 * @param filePath   仓库相对路径（SCA 为 null——jar 物理路径随版本漂移，不作定位依据）
 * @param lineNo     命中行号（可空）
 * @param lineContent 目标行内容（SAST 原样、secrets 已脱敏；计算 codeHash 用，不落明文密钥）
 * @param cve        SCA 的 CVE 编号（情报富化/CVE 抑制键）
 * @param groupId    SCA 构件 groupId（白名单过滤/CVE 抑制键）
 * @param artifactId SCA 构件 artifactId
 * @param depVersion SCA 构件当前版本
 * @param depScope   SCA Maven scope（compile/test/provided…，降权标记依据）
 * @param directDep  SCA 是否直接依赖（依赖树解析回填；null=未知/非 SCA）
 * @param detail     引擎原始字段透传（CVE 描述/CVSS 向量/CWE/CPE 证据/EPSS·KEV 富化/引入链等），
 *                   Jackson 序列化进 detail_json；secrets 命中必须已脱敏
 */
public record NormalizedFinding(
        String engine,
        String ruleId,
        String severity,
        String title,
        String filePath,
        Integer lineNo,
        String lineContent,
        String cve,
        String groupId,
        String artifactId,
        String depVersion,
        String depScope,
        Boolean directDep,
        Map<String, Object> detail) {

    public static final String CRITICAL = "CRITICAL";
    public static final String HIGH = "HIGH";
    public static final String MEDIUM = "MEDIUM";
    public static final String LOW = "LOW";
    public static final String INFO = "INFO";
}
