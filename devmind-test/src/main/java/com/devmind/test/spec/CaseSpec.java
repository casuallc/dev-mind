package com.devmind.test.spec;

import java.util.List;
import java.util.Map;

/**
 * CAP-104 统一用例描述规范（T1.1 冻结）。type = HTTP / COMMAND / MANUAL；
 * JUNIT 不在此模型内，作为套件级脚本资产由 CAP-69 执行并映射为用例结果。
 */
public record CaseSpec(
        String caseKey,
        String title,
        String type,
        String priority,
        List<String> tags,
        List<String> preconditions,
        List<String> steps,
        List<AssertionSpec> assertions,
        List<String> cleanup,
        ExecutorSpec executor,
        HttpSpec http,
        CommandSpec command,
        ManualSpec manual,
        SourceSpec source) {

    public static final String HTTP = "HTTP";
    public static final String COMMAND = "COMMAND";
    public static final String MANUAL = "MANUAL";

    public static final String LOCATION_SERVER = "SERVER";
    public static final String LOCATION_NODE = "NODE";

    /** 执行位置、超时与参数注入；location 缺省 HTTP=SERVER、COMMAND=NODE。 */
    public record ExecutorSpec(String location, List<String> labels, String nodeId,
                               Integer timeoutSec, RetrySpec retry, Map<String, String> env) {
    }

    /** 重试策略（M2 编排生效，M1 记录不执行）。 */
    public record RetrySpec(Integer max, List<String> on) {
    }

    public record HttpSpec(String method, String path, Map<String, String> params,
                           Map<String, String> headers, String body) {
    }

    /**
     * COMMAND 执行配置。{@code contractVersion} 为空 = 现有命令语义（退出码/stdout/文件断言）；
     * {@code "1"} = CAP-104 附录 C 程序契约（要求 source.kind=ASSET 且资产为已评审 PROGRAM 包）。
     */
    public record CommandSpec(String command, String workingDir, String contractVersion) {
    }

    public record ManualSpec(String instructions) {
    }

    /** 来源：MANUAL / ASSET / LEGACY */
    public record SourceSpec(String kind, Long assetId, String note) {
    }
}
