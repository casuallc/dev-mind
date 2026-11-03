package com.devmind.test.executor;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.program.ProgramContract;
import com.devmind.common.program.ProgramManifest;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.test.assertion.AssertionEvaluator;
import com.devmind.test.assertion.CommandObservation;
import com.devmind.test.assertion.HttpObservation;
import com.devmind.test.model.TestAssetEntity;
import com.devmind.test.service.EvidenceUploadTokenService;
import com.devmind.test.service.TestAssetService;
import com.devmind.test.spec.AssertionResult;
import com.devmind.test.spec.AssertionSpec;
import com.devmind.test.spec.CaseSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CAP-104 单用例执行：HTTP（服务端直连）、COMMAND（节点 exec 帧）、MANUAL（人工判定占位）。
 * 断言统一由 {@link AssertionEvaluator} 产出 expected/actual；过程数据返回给 TestRunService 落库+证据。
 */
@Service
public class CaseExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CaseExecutionService.class);
    private static final String FILE_MARKER = "DEVMIND_FILE ";
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(token|secret|password|passwd|credential).*");

    private final AgentNodeRouter router;
    private final AgentNodeStepRunner stepRunner;
    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final TestAssetService assetService;
    private final EvidenceUploadTokenService evidenceTokens;
    private final ObjectMapper mapper;

    public CaseExecutionService(AgentNodeRouter router, AgentNodeStepRunner stepRunner,
                                ProjectService projectService, EnvironmentService environmentService,
                                TestAssetService assetService, EvidenceUploadTokenService evidenceTokens,
                                ObjectMapper mapper) {
        this.router = router;
        this.stepRunner = stepRunner;
        this.projectService = projectService;
        this.environmentService = environmentService;
        this.assetService = assetService;
        this.evidenceTokens = evidenceTokens;
        this.mapper = mapper;
    }

    public CaseSpec parseSpec(String json) {
        try {
            return mapper.readValue(json, CaseSpec.class);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "CaseSpec 解析失败: " + rootMessage(e));
        }
    }

    public CaseExecOutcome execute(String caseTitle, CaseSpec spec, CaseExecInput in) {
        Instant started = Instant.now();
        long startMs = System.currentTimeMillis();
        String type = spec.type() == null ? "" : spec.type().strip().toUpperCase(Locale.ROOT);
        try {
            return switch (type) {
                case CaseSpec.HTTP -> executeHttp(spec, in, started, startMs);
                case CaseSpec.COMMAND -> isProgram(spec)
                        ? executeProgram(caseTitle, spec, in, started, startMs)
                        : executeCommand(caseTitle, spec, in, started, startMs);
                case CaseSpec.MANUAL -> manual(spec, in, started);
                default -> blocked("未知用例类型: " + spec.type(), "UNKNOWN", in, started, startMs);
            };
        } catch (DevMindException e) {
            return blocked(e.getMessage(), "INFRA", in, started, startMs);
        } catch (Exception e) {
            log.warn("用例执行异常: {}", rootMessage(e));
            return error(rootMessage(e), "UNKNOWN", in, started, startMs);
        }
    }

    // ---------------- HTTP ----------------

    private CaseExecOutcome executeHttp(CaseSpec spec, CaseExecInput in,
                                        Instant started, long startMs) {
        CaseSpec.HttpSpec http = spec.http();
        String location = location(spec, CaseSpec.LOCATION_SERVER);
        if (CaseSpec.LOCATION_NODE.equals(location)) {
            return blocked("HTTP 用例 NODE 执行未在 M1 开放（节点侧网络验证计划 M2），请改用 SERVER",
                    "INFRA", in, started, startMs);
        }
        String url = resolveUrl(http, in.baseUrl());
        if (url == null) {
            return blocked("HTTP 用例缺少目标 baseUrl（运行/环境均未配置）", "PRECHECK", in, started, startMs);
        }
        int timeout = timeoutSec(spec, 30);
        URI uri = buildUri(url, http.params());
        String requestSummary = http.method() + " " + maskedUriSummary(url, http.params());
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(timeout, 10))).build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout));
            String method = http.method() == null ? "GET" : http.method().strip().toUpperCase(Locale.ROOT);
            String body = http.body();
            if (body != null && !body.isBlank()) {
                rb.header("Content-Type", "application/json");
            }
            if (http.headers() != null) {
                http.headers().forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null) {
                        rb.header(k.strip(), v);
                    }
                });
            }
            rb.method(method, body == null || body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            long dur = System.currentTimeMillis() - startMs;
            Map<String, List<String>> headers = new LinkedHashMap<>();
            resp.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
            HttpObservation obs = new HttpObservation(resp.statusCode(), headers, resp.body());
            List<AssertionResult> assertions = AssertionEvaluator.evaluateHttp(spec.assertions(), obs);
            boolean pass = AssertionEvaluator.allPass(assertions);
            String responseSummary = resp.statusCode()
                    + (resp.body() == null || resp.body().isBlank() ? "" : " " + truncate(resp.body(), 500));
            String error = pass ? null : failedMessages(assertions);
            Map<String, Object> snapshot = snapshot(in, null, location, spec);
            return new CaseExecOutcome(pass ? "pass" : "fail", pass ? null : "ASSERTION",
                    assertions, requestSummary, responseSummary, error, dur, null,
                    requestSummary, null, truncate(resp.body(), 64 * 1024), "", null,
                    started, Instant.now(), snapshot, null);
        } catch (HttpTimeoutException e) {
            return error("请求超时: " + rootMessage(e), "TIMEOUT", in, started, startMs);
        } catch (Exception e) {
            return error("请求异常: " + rootMessage(e), "INFRA", in, started, startMs);
        }
    }

    private String resolveUrl(CaseSpec.HttpSpec http, String baseUrl) {
        String path = http.path() == null ? "" : http.path().strip();
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String base = baseUrl.replaceAll("/+$", "");
        return base + (path.isEmpty() ? "" : path.startsWith("/") ? path : "/" + path);
    }

    private URI buildUri(String url, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        if (params != null) {
            params.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    builder.queryParam(k, v);
                }
            });
        }
        return builder.build(true).toUri();
    }

    private String maskedUriSummary(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(isSensitive(e.getKey()) ? "******" : e.getValue());
        }
        return sb.toString();
    }

    // ---------------- 程序型用例（CAP-104 M1.5） ----------------

    private boolean isProgram(CaseSpec spec) {
        return spec.command() != null && ProgramContract.CONTRACT_VERSION
                .equals(spec.command().contractVersion() == null ? null : spec.command().contractVersion().strip());
    }

    /**
     * 附录 C 契约 v1：服务端生成 input.json（secret 过滤）→ 节点树形解包程序包 → 注入三个绝对路径 →
     * 跑入口脚本 → 解析 result.json/artifacts → 退出码优先兜底。程序包由 runner 凭节点 token 拉取，
     * 服务端只下发 assetId（kind=test-program），不暴露文件系统路径。
     */
    private CaseExecOutcome executeProgram(String caseTitle, CaseSpec spec, CaseExecInput in,
                                           Instant started, long startMs) {
        CaseSpec.CommandSpec cmd = spec.command();
        Long assetId = spec.source() == null ? null : spec.source().assetId();
        if (assetId == null) {
            return blocked("程序型用例缺少 source.assetId（需引用已评审 PROGRAM 程序包）",
                    "PRECHECK", in, started, startMs);
        }
        TestAssetEntity asset;
        try {
            asset = assetService.require(assetId);
        } catch (DevMindException e) {
            return blocked(e.getMessage(), "PRECHECK", in, started, startMs);
        }
        if (!TestAssetEntity.ROLE_PROGRAM.equals(asset.getAssetRole())) {
            return blocked("资产 " + assetId + " 不是 PROGRAM 程序包", "PRECHECK", in, started, startMs);
        }
        if (!TestAssetEntity.APPROVED.equals(asset.getReviewStatus())) {
            return blocked("程序资产未评审通过，不可执行: " + assetId, "PRECHECK", in, started, startMs);
        }
        ProgramManifest manifest;
        try {
            manifest = ProgramManifest.parse(asset.getManifestJson());
        } catch (IllegalArgumentException e) {
            return blocked("程序清单不可用: " + e.getMessage(), "PRECHECK", in, started, startMs);
        }
        if (asset.getSha256() == null || asset.getSha256().isBlank()) {
            return blocked("程序资产缺少 sha256，无法保证不可变性", "PRECHECK", in, started, startMs);
        }

        // CAP-106 FR-06：env 先算出来喂预检——toolchain 快照缺失但注入 env 含 PATH/JAVA_HOME
        // 覆盖时不误杀（判定下沉 runner 执行期探测）
        Map<String, String> env = commandEnv(spec, in);
        String nodeId;
        try {
            nodeId = routeNode(spec, in);
            router.requireProgramCompatible(nodeId, manifest, env);
        } catch (DevMindException e) {
            return blocked(e.getMessage(), "PRECHECK", in, started, startMs);
        }
        Map<String, Object> input = programInput(spec, in);
        String inputJson;
        try {
            inputJson = mapper.writeValueAsString(input);
        } catch (Exception e) {
            return blocked("程序输入生成失败: " + rootMessage(e), "PRECHECK", in, started, startMs);
        }
        byte[] inputBytes = inputJson.getBytes(StandardCharsets.UTF_8);
        if (inputBytes.length > ProgramContract.MAX_INPUT_BYTES) {
            return blocked("程序输入超限（" + inputBytes.length + " > "
                    + ProgramContract.MAX_INPUT_BYTES + " 字节）", "PRECHECK", in, started, startMs);
        }
        env.put(ProgramContract.INPUT_B64_ENV, Base64.getEncoder().encodeToString(inputBytes));
        env.put("DEVMIND_RUN_ID", in.runId() == null ? "" : String.valueOf(in.runId()));
        env.put("DEVMIND_CASE_KEY", in.caseKey() == null ? "" : in.caseKey());
        attachEvidenceEnv(env, in, nodeId);

        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        List<String> protocol = new ArrayList<>();
        var res = stepRunner.runStep(nodeId, in.projectId(), "testcase-run-" + in.runId(),
                (int) Math.floorMod(in.runId() == null ? 1L : in.runId(), 10_000L),
                new StepSpec(caseTitle == null ? "case" : caseTitle,
                        cmd.command(), cmd.workingDir(), "test"),
                env, null, new AgentExecCommand.LabBundleRef(ProgramContract.KIND, String.valueOf(assetId)),
                (long) timeoutSec(spec, 300),
                line -> {
                    if (line == null) {
                        return;
                    }
                    if (isProgramMarker(line)) {
                        addBounded(protocol, line, 5000);
                    } else if (line.startsWith("[stderr] ")) {
                        addBounded(stderr, line.substring("[stderr] ".length()));
                    } else {
                        addBounded(stdout, line);
                    }
                });
        long dur = System.currentTimeMillis() - startMs;

        // CAP-106 FR-07：预检放行后 runner 执行期探测仍不满足（env 覆盖是假的/路径里没 java）
        // → BLOCKED 语义，入口脚本未执行，不算程序 INFRA 错误
        if (res.error() != null && res.error().startsWith("[toolchain]")) {
            return blocked(res.error(), "PRECHECK", in, started, startMs);
        }

        ProgramResultParser.Collected collected;
        ProgramResultParser.Decoded decoded = null;
        String protocolError = null;
        try {
            collected = ProgramResultParser.collect(protocol);
            decoded = ProgramResultParser.decode(collected.resultJson());
        } catch (IllegalArgumentException e) {
            collected = new ProgramResultParser.Collected(null, List.of());
            protocolError = e.getMessage();
        }

        String outText = truncate(cleanStdout(stdout), 64 * 1024);
        String errText = String.join("\n", stderr);
        Integer code = res.exitCode();
        Map<String, CommandObservation.FileState> fileStates = artifactFileStates(collected.artifacts());
        CommandObservation obs = new CommandObservation(code == null ? -1 : code, outText, errText, fileStates);

        List<AssertionResult> assertions = new ArrayList<>();
        if (spec.assertions() != null) {
            List<AssertionSpec> declared = spec.assertions().stream()
                    .filter(a -> a != null && !AssertionSpec.CUSTOM.equalsIgnoreCase(
                            a.source() == null ? "" : a.source()))
                    .toList();
            if (!declared.isEmpty()) {
                assertions.addAll(AssertionEvaluator.evaluateCommand(declared, obs));
            }
        }
        if (decoded != null) {
            assertions.addAll(decoded.assertions());
        }

        String status;
        String category = null;
        String error = null;
        if (res.error() != null && !res.error().isBlank()) {
            status = "error";
            category = res.error().contains("超时") ? "TIMEOUT" : "INFRA";
            error = res.error();
        } else if (code != null && code == 0) {
            if (decoded == null) {
                status = "error";
                category = "SCRIPT_ERROR";
                error = "程序输出不合契约: " + (protocolError == null ? "result.json 缺失" : protocolError);
            } else {
                status = decoded.status();
                boolean anyFail = assertions.stream().anyMatch(a -> !a.pass());
                if ("pass".equals(status) && anyFail) {
                    status = "fail";
                    category = "ASSERTION";
                    error = failedMessages(assertions);
                } else if ("fail".equals(status)) {
                    category = "ASSERTION";
                    if (decoded.message() != null) {
                        error = decoded.message();
                    } else if (anyFail) {
                        error = failedMessages(assertions);
                    } else {
                        error = "程序判定 fail";
                    }
                } else if ("error".equals(status)) {
                    category = "SCRIPT_ERROR";
                    error = decoded.message() == null ? "程序判定 error" : decoded.message();
                } else if ("blocked".equals(status)) {
                    category = "PRECHECK";
                    error = decoded.message() == null ? "程序判定前置不满足" : decoded.message();
                }
            }
        } else {
            int exit = code == null ? -1 : code;
            status = exitStatus(exit);
            category = exitCategory(exit);
            error = decoded != null && decoded.message() != null
                    ? decoded.message() : "程序退出码 " + exit + "（result.json 状态不覆盖退出码）";
            if (assertions.stream().noneMatch(a -> !a.pass())) {
                String expected = "0";
                assertions.add(new AssertionResult(AssertionSpec.EXIT_CODE, null,
                        AssertionSpec.OP_EQUALS, expected, String.valueOf(exit), false,
                        "期望退出码 0，实际 " + exit));
            }
        }

        String requestSummary = "program:" + assetId + " " + manifest.name() + "@" + manifest.version()
                + " → node:" + nodeId;
        String responseSummary = "exit=" + (code == null ? "?" : code)
                + " status=" + (decoded == null ? "?" : decoded.status())
                + (decoded != null && decoded.message() != null ? " " + decoded.message()
                : error != null ? " " + error : "");
        Map<String, Object> snapshot = snapshot(in, nodeId, CaseSpec.LOCATION_NODE, spec);
        snapshot.put("programAssetId", assetId);
        snapshot.put("programSha256", asset.getSha256());
        snapshot.put("programRuntime", manifest.runtime());
        snapshot.put("programEntrypoint", manifest.entrypoint());

        ProgramOutcome program = new ProgramOutcome(assetId, asset.getSha256(),
                manifest.contractVersion(), manifest, asset.getManifestJson(), inputJson,
                collected.resultJson() == null ? null : new String(collected.resultJson(), StandardCharsets.UTF_8),
                decoded == null ? null : decoded.status(),
                decoded == null ? null : decoded.message(),
                decoded == null ? Map.of() : decoded.metrics(),
                collected.artifacts());

        CaseExecOutcome base = new CaseExecOutcome(status, category, assertions, requestSummary,
                truncate(responseSummary, 1000), truncate(error, 4000), dur, nodeId, cmd.command(), code,
                outText, errText, cmd.workingDir(), started, Instant.now(), snapshot, null, program);
        String cleanup = runCleanup(spec, in, nodeId);
        return new CaseExecOutcome(base.status(), base.failureCategory(), base.assertions(),
                base.requestSummary(), base.responseSummary(), base.error(), base.durationMs(),
                base.nodeId(), base.command(), base.exitCode(), base.stdout(), base.stderr(),
                base.workingDir(), base.startedAt(), base.finishedAt(), base.envSnapshot(), cleanup,
                base.program());
    }

    private boolean isProgramMarker(String line) {
        return line.startsWith(ProgramContract.RESULT_MARKER)
                || line.startsWith(ProgramContract.RESULT_CHUNK_MARKER)
                || line.startsWith(ProgramContract.ARTIFACT_MARKER)
                || line.startsWith(ProgramContract.ARTIFACT_CHUNK_MARKER);
    }

    /** 输入 JSON v1：secret 只走进程环境（commandEnv 原样保留），这里只放可暴露参数。 */
    private Map<String, Object> programInput(CaseSpec spec, CaseExecInput in) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("contractVersion", ProgramContract.CONTRACT_VERSION);
        root.put("runId", in.runId() == null ? null : String.valueOf(in.runId()));
        root.put("caseId", in.caseId() == null ? null : String.valueOf(in.caseId()));
        root.put("caseKey", in.caseKey());
        root.put("projectId", in.projectId());
        EnvironmentEntity env = in.environment();
        if (env != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", env.getId() == null ? null : String.valueOf(env.getId()));
            e.put("name", env.getName());
            if (in.baseUrl() != null && !in.baseUrl().isBlank()) {
                e.put("baseUrl", in.baseUrl());
            }
            e.put("productVersion", env.getProductVersion());
            root.put("environment", e);
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (env != null) {
            params.putAll(environmentService.variablesOf(env));
        }
        if (spec.executor() != null && spec.executor().env() != null) {
            params.putAll(spec.executor().env());
        }
        params.putAll(in.parameters());
        params.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null
                || isSensitive(e.getKey()));
        root.put("parameters", params);
        root.put("timeoutSec", timeoutSec(spec, 300));
        return root;
    }

    private Map<String, CommandObservation.FileState> artifactFileStates(
            List<ProgramOutcome.ProgramArtifact> artifacts) {
        Map<String, CommandObservation.FileState> states = new LinkedHashMap<>();
        for (ProgramOutcome.ProgramArtifact a : artifacts) {
            CommandObservation.FileState state = new CommandObservation.FileState(
                    true, a.content() == null ? 0 : a.content().length, a.sha256());
            states.put(a.path(), state);
            states.put(ProgramContract.ARTIFACTS_PATH + "/" + a.path(), state);
        }
        return states;
    }

    /** 附录 C 退出码兜底：非 0 优先于 result.json 的 status。 */
    static String exitStatus(int exit) {
        return switch (exit) {
            case 1 -> "fail";
            case 2 -> "blocked";
            default -> "error";
        };
    }

    static String exitCategory(int exit) {
        return switch (exit) {
            case 1 -> "ASSERTION";
            case 2 -> "PRECHECK";
            case 3 -> "SCRIPT_ERROR";
            default -> "UNKNOWN";
        };
    }

    // ---------------- COMMAND ----------------

    private CaseExecOutcome executeCommand(String caseTitle, CaseSpec spec, CaseExecInput in,
                                           Instant started, long startMs) {
        CaseSpec.CommandSpec cmd = spec.command();
        List<String> files = filePaths(spec.assertions());
        String wrapped = wrapCommand(cmd.command(), files);
        Map<String, String> env = commandEnv(spec, in);
        String nodeId;
        try {
            nodeId = routeNode(spec, in);
        } catch (DevMindException e) {
            return blocked(e.getMessage(), "PRECHECK", in, started, startMs);
        }
        attachEvidenceEnv(env, in, nodeId);
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        var res = stepRunner.runStep(nodeId, in.projectId(), "testcase-run-" + in.runId(),
                (int) Math.floorMod(in.runId() == null ? 1L : in.runId(), 10_000L),
                new StepSpec(caseTitle == null ? "case" : caseTitle, wrapped,
                        cmd.workingDir(), "test"),
                env, null, null, (long) timeoutSec(spec, 300),
                line -> {
                    if (line == null) {
                        return;
                    }
                    if (line.startsWith(FILE_MARKER)) {
                        stdout.add(line);
                    } else if (line.startsWith("[stderr] ")) {
                        addBounded(stderr, line.substring("[stderr] ".length()));
                    } else {
                        addBounded(stdout, line);
                    }
                });
        long dur = System.currentTimeMillis() - startMs;
        Map<String, CommandObservation.FileState> fileStates = parseFiles(files, stdout);
        String outText = cleanStdout(stdout);
        String errText = String.join("\n", stderr);
        CommandObservation obs = new CommandObservation(res.exitCode(), outText, errText, fileStates);
        List<AssertionResult> assertions = AssertionEvaluator.evaluateCommand(spec.assertions(), obs);
        boolean assertionsPass = AssertionEvaluator.allPass(assertions);
        String category = null;
        String status;
        String error = null;
        if (res.error() != null && !res.error().isBlank()) {
            category = res.error().contains("超时") ? "TIMEOUT" : "INFRA";
            status = "error";
            error = res.error();
        } else if (!assertionsPass) {
            status = "fail";
            category = "ASSERTION";
            error = failedMessages(assertions);
        } else {
            status = "pass";
        }
        CaseExecOutcome base = new CaseExecOutcome(status, category, assertions,
                "node:" + nodeId, outText.isBlank() ? "exit=" + res.exitCode() : outText,
                error, dur, nodeId, wrapped, res.exitCode(), outText, errText,
                cmd.workingDir(), started, Instant.now(), snapshot(in, nodeId, CaseSpec.LOCATION_NODE, spec),
                null);
        String cleanup = runCleanup(spec, in, nodeId);
        return new CaseExecOutcome(base.status(), base.failureCategory(), base.assertions(),
                base.requestSummary(), base.responseSummary(), base.error(), base.durationMs(),
                base.nodeId(), base.command(), base.exitCode(), base.stdout(), base.stderr(),
                base.workingDir(), base.startedAt(), base.finishedAt(), base.envSnapshot(), cleanup);
    }

    private String wrapCommand(String command, List<String> files) {
        String base = command == null ? "" : command.strip();
        if (files.isEmpty()) {
            return "{ " + base + "; }";
        }
        StringBuilder sb = new StringBuilder("{ ").append(base).append("; ec=$?; ");
        for (String f : files) {
            String p = shQuote(f);
            sb.append("echo \"").append(FILE_MARKER)
                    .append("$(printf '%s' ").append(p).append(" | base64 | tr -d '\\n') ")
                    .append("$( [ -e ").append(p).append(" ] && echo 1 || echo 0) ")
                    .append("$( [ -f ").append(p).append(" ] && wc -c < ").append(p).append(" | tr -d ' ' || echo 0) ")
                    .append("$( [ -f ").append(p).append(" ] && sha256sum ").append(p)
                    .append(" | cut -d' ' -f1 || echo -)\"; ");
        }
        return sb.append("exit $ec; }").toString();
    }

    private Map<String, CommandObservation.FileState> parseFiles(List<String> files, List<String> stdout) {
        Map<String, CommandObservation.FileState> out = new LinkedHashMap<>();
        for (String line : stdout) {
            if (!line.startsWith(FILE_MARKER)) {
                continue;
            }
            String[] parts = line.substring(FILE_MARKER.length()).strip().split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            try {
                String path = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                boolean exists = "1".equals(parts[1]);
                long size = 0;
                try {
                    size = Long.parseLong(parts[2]);
                } catch (NumberFormatException ignored) {
                    // 大小解析失败按 0
                }
                String sha = "-".equals(parts[3]) ? null : parts[3];
                out.put(path, new CommandObservation.FileState(exists, size, sha));
            } catch (IllegalArgumentException e) {
                log.debug("文件证据 marker 解码失败: {}", line);
            }
        }
        for (String f : files) {
            out.putIfAbsent(f, new CommandObservation.FileState(false, 0, null));
        }
        return out;
    }

    private String cleanStdout(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith(FILE_MARKER)) {
                sb.append(line).append('\n');
            }
        }
        return truncate(sb.toString().strip(), 64 * 1024);
    }

    private String runCleanup(CaseSpec spec, CaseExecInput in, String nodeId) {
        if (spec.cleanup() == null || spec.cleanup().isEmpty() || nodeId == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String command : spec.cleanup()) {
            if (command == null || command.isBlank()) {
                continue;
            }
            try {
                var res = stepRunner.runStep(nodeId, in.projectId(), "cleanup-run-" + in.runId(), 99,
                        new StepSpec("cleanup", command, null, "test"), Map.of(), null, null, 120L,
                        line -> {
                            if (out.length() < 4000) {
                                out.append(line).append('\n');
                            }
                        });
                out.append(res.ok() ? "[cleanup ok] " : "[cleanup failed] ")
                        .append(res.error() == null ? "" : res.error()).append('\n');
            } catch (Exception e) {
                out.append("[cleanup failed] ").append(rootMessage(e)).append('\n');
            }
        }
        return out.toString();
    }

    private Map<String, String> commandEnv(CaseSpec spec, CaseExecInput in) {
        Map<String, String> env = new LinkedHashMap<>();
        if (in.environment() != null) {
            env.putAll(environmentService.variablesOf(in.environment()));
        }
        if (spec.executor() != null && spec.executor().env() != null) {
            env.putAll(spec.executor().env());
        }
        env.putAll(in.parameters());
        env.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        return env;
    }

    /**
     * FR-24：给命令/程序注入一次性证据上传凭据。token 仅存在进程环境，不进入快照、日志与证据正文；
     * 命令结束后可用 {@code curl -H "X-Evidence-Token: $DEVMIND_EVIDENCE_TOKEN"} 上传截图/大日志。
     */
    private void attachEvidenceEnv(Map<String, String> env, CaseExecInput in, String nodeId) {
        if (in.runId() == null) {
            return;
        }
        EvidenceUploadTokenService.Issued issued = evidenceTokens.issue(
                in.projectId(), in.runId(), null, nodeId, "ATTACHMENT");
        env.put("DEVMIND_EVIDENCE_TOKEN", issued.token());
        env.put("DEVMIND_EVIDENCE_URL", "/api/test-runs/" + in.runId() + "/evidence");
        env.put("DEVMIND_EVIDENCE_EXPIRES_AT", issued.expiresAt().toString());
        env.put("DEVMIND_EVIDENCE_MAX_BYTES", String.valueOf(issued.maxBytes()));
    }

    private String routeNode(CaseSpec spec, CaseExecInput in) {
        String explicit = spec.executor() != null && spec.executor().nodeId() != null
                && !spec.executor().nodeId().isBlank() ? spec.executor().nodeId().strip() : null;
        if (explicit == null && in.explicitNodeId() != null && !in.explicitNodeId().isBlank()) {
            explicit = in.explicitNodeId().strip();
        }
        if (explicit == null && in.environment() != null) {
            List<String> nodes = environmentService.nodeIdsOf(in.environment());
            if (!nodes.isEmpty()) {
                explicit = nodes.get(0);
            }
        }
        List<String> labels = spec.executor() == null || spec.executor().labels() == null
                ? List.of() : spec.executor().labels();
        String projectDefault = in.projectId() == null ? null
                : projectService.requireProject(in.projectId()).agentNodeId();
        String node = router.route(explicit, projectDefault, labels);
        router.requireExecCapable(node);
        return node;
    }

    // ---------------- MANUAL / 通用 ----------------

    private CaseExecOutcome manual(CaseSpec spec, CaseExecInput in, Instant started) {
        String note = spec.manual() != null && spec.manual().instructions() != null
                ? spec.manual().instructions() : "手工用例：请在运行详情中执行人工判定";
        return new CaseExecOutcome("skip", null, List.of(), "manual", "awaiting review", note,
                Duration.between(started, Instant.now()).toMillis(), null, null, null, "", "",
                null, started, Instant.now(), snapshot(in, null, null, spec), null);
    }

    private CaseExecOutcome blocked(String message, String category, CaseExecInput in,
                                    Instant started, long startMs) {
        return new CaseExecOutcome("blocked", category, List.of(), null, null, message,
                System.currentTimeMillis() - startMs, null, null, null, "", "",
                null, started, Instant.now(), snapshot(in, null, null, null), null);
    }

    private CaseExecOutcome error(String message, String category, CaseExecInput in,
                                  Instant started, long startMs) {
        return new CaseExecOutcome("error", category, List.of(), null, null, message,
                System.currentTimeMillis() - startMs, null, null, null, "", "",
                null, started, Instant.now(), snapshot(in, null, null, null), null);
    }

    private Map<String, Object> snapshot(CaseExecInput in, String nodeId, String location, CaseSpec spec) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("nodeId", nodeId);
        snap.put("location", location);
        snap.put("commit", in.suiteCommit());
        EnvironmentEntity env = in.environment();
        if (env != null) {
            snap.put("environmentId", env.getId());
            snap.put("environment", env.getName());
            snap.put("productVersion", env.getProductVersion());
            snap.put("variables", maskedVars(environmentService.variablesOf(env)));
        }
        if (spec != null && spec.executor() != null && spec.executor().env() != null) {
            snap.put("caseEnv", maskedVars(spec.executor().env()));
        }
        snap.put("executedAt", Instant.now().toString());
        return snap;
    }

    private Map<String, String> maskedVars(Map<String, String> vars) {
        Map<String, String> out = new LinkedHashMap<>();
        if (vars != null) {
            vars.forEach((k, v) -> out.put(k, isSensitive(k) ? "******" : v));
        }
        return out;
    }

    private boolean isSensitive(String key) {
        return key != null && (SENSITIVE.matcher(key).matches()
                || key.toLowerCase(Locale.ROOT).endsWith("key"));
    }

    private List<String> filePaths(List<AssertionSpec> assertions) {
        List<String> out = new ArrayList<>();
        if (assertions == null) {
            return out;
        }
        for (AssertionSpec a : assertions) {
            if (a == null || a.selector() == null || a.selector().isBlank()) {
                continue;
            }
            String source = a.source() == null ? "" : a.source().strip().toUpperCase(Locale.ROOT);
            if ((AssertionSpec.FILE_EXISTS.equals(source) || AssertionSpec.FILE_SHA256.equals(source))
                    && !out.contains(a.selector().strip())) {
                out.add(a.selector().strip());
            }
        }
        return out;
    }

    private String failedMessages(List<AssertionResult> assertions) {
        List<String> failed = assertions.stream().filter(a -> !a.pass()).map(AssertionResult::message).toList();
        return failed.isEmpty() ? "断言未通过" : String.join("；", failed);
    }

    private int timeoutSec(CaseSpec spec, int fallback) {
        if (spec.executor() != null && spec.executor().timeoutSec() != null && spec.executor().timeoutSec() > 0) {
            return spec.executor().timeoutSec();
        }
        return fallback;
    }

    private String location(CaseSpec spec, String fallback) {
        if (spec.executor() == null || spec.executor().location() == null || spec.executor().location().isBlank()) {
            return fallback;
        }
        return spec.executor().location().strip().toUpperCase(Locale.ROOT);
    }

    private void addBounded(List<String> lines, String line) {
        addBounded(lines, line, 2000);
    }

    private void addBounded(List<String> lines, String line, int max) {
        if (lines.size() >= max) {
            return;
        }
        lines.add(line);
    }

    private String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
