package com.devmind.common.program;

/**
 * CAP-104 M1.5 程序型用例契约 v1（服务端与 runner 共用，CAP-104 附录 C 冻结）。
 *
 * <p>程序包为已构建好的 ZIP：包根含 {@code .devmind-program.json} 与入口脚本；
 * 平台不执行 mvn/gradle/npm install/pip install 等构建命令。执行时 runner 把包解到临时目录，
 * 平台注入 {@code DEVMIND_INPUT/DEVMIND_RESULT/DEVMIND_ARTIFACTS} 三个绝对路径。</p>
 */
public final class ProgramContract {

    /** exec 帧 bundle.kind（test = CAP-104 程序型用例） */
    public static final String KIND = "test-program";

    /** 包内清单固定入口名 */
    public static final String MANIFEST_NAME = ".devmind-program.json";

    /** 当前冻结的契约版本 */
    public static final String CONTRACT_VERSION = "1";

    /** 平台生成的输入文件（相对程序包根） */
    public static final String INPUT_PATH = ".devmind/input.json";

    /** 程序写出的结构化结果（相对程序包根） */
    public static final String RESULT_PATH = ".devmind/result.json";

    /** 程序产物目录（相对程序包根） */
    public static final String ARTIFACTS_PATH = "artifacts";

    public static final String INPUT_ENV = "DEVMIND_INPUT";
    public static final String RESULT_ENV = "DEVMIND_RESULT";
    public static final String ARTIFACTS_ENV = "DEVMIND_ARTIFACTS";

    /**
     * 平台把 input.json 以 base64 随 exec 帧 env 下发，runner 落盘后从子进程环境移除。
     * 输入不含 secret（secret 只走其他环境变量）。
     */
    public static final String INPUT_B64_ENV = "DEVMIND_PROGRAM_INPUT_B64";

    /**
     * runner 回收 result.json 时上行到 exec_log 的保留行：{meta} {size} {sha256}，
     * 内容按 {@link #RESULT_CHUNK_MARKER} 分片上行（WS 文本帧上限 512KB）。
     */
    public static final String RESULT_MARKER = "DEVMIND_PROGRAM_RESULT ";

    /** result.json 分片行：{marker}{seq} {base64(chunk)} */
    public static final String RESULT_CHUNK_MARKER = "DEVMIND_PROGRAM_RESULT_CHUNK ";

    /** artifact 元数据行：{marker}{base64(name)} {size} {sha256} */
    public static final String ARTIFACT_MARKER = "DEVMIND_PROGRAM_ARTIFACT ";

    /** artifact 分片行：{marker}{base64(name)} {seq} {base64(chunk)} */
    public static final String ARTIFACT_CHUNK_MARKER = "DEVMIND_PROGRAM_ARTIFACT_CHUNK ";

    /** 单帧分片原始字节上限（base64 后约 171KB，远低于 512KB WS 上限） */
    public static final int CHUNK_BYTES = 128 * 1024;

    /** 证据类型（进入 sha256 + prev_hash 链） */
    public static final String EVIDENCE_MANIFEST = "PROGRAM_MANIFEST";
    public static final String EVIDENCE_INPUT = "INPUT_JSON";
    public static final String EVIDENCE_RESULT = "RESULT_JSON";
    public static final String EVIDENCE_STDOUT = "STDOUT";
    public static final String EVIDENCE_STDERR = "STDERR";
    public static final String EVIDENCE_ARTIFACT = "ARTIFACT";
    public static final String EVIDENCE_SUMMARY = "PROGRAM_SUMMARY";

    /** 单文件上限（result.json 与单个 artifact） */
    public static final long MAX_RESULT_BYTES = 1024 * 1024;
    public static final long MAX_ARTIFACT_BYTES = 2L * 1024 * 1024;
    public static final long MAX_ARTIFACTS_TOTAL_BYTES = 20L * 1024 * 1024;
    public static final int MAX_ARTIFACTS = 100;

    /** input.json 单文件上限 */
    public static final long MAX_INPUT_BYTES = 1024 * 1024;

    /** runner 解包规模上限（上传侧另有 TestAssetProperties 限制） */
    public static final int MAX_PACKAGE_FILES = 5000;
    public static final long MAX_PACKAGE_FILE_BYTES = 256L * 1024 * 1024;
    public static final long MAX_PACKAGE_TOTAL_BYTES = 1024L * 1024 * 1024;

    private ProgramContract() {
    }
}
