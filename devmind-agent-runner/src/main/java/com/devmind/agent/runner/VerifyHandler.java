package com.devmind.agent.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.devmind.common.agent.exec.RunnerWorkspace;
import com.devmind.common.agent.runtime.ProcessHelper;

/**
 * CAP-73 验证链 runner 侧 handler（协议 v22）：verify_push / verify_pack / verify_patch。
 *
 * <p><b>verify_push</b>（PUSHING，会话节点）：需求工作区 {@code worktrees/<key>} 本地分支
 * 推远端（{@link RunnerWorkspace#verifyPush}），token 仅内存。</p>
 *
 * <p><b>verify_pack</b>（BUILDING，构建节点）：verify 工作区
 * （{@code <root>/<projectId>/verify/<key>}，持久 clone，{@link RunnerWorkspace#prepareVerify}）
 * reset --hard 到 commitSha → 逐条跑 build_steps（命令与 exec 同一 execAllowlist 防线；
 * VERIFY_MODULES 等服务端装配的 env 原样透传）→ 产物树 output_dir 生成 sha256 清单 +
 * 打 zip（清单固定入口 {@link #MANIFEST_ENTRY}）→ HTTP 上传
 * {@code POST /api/agent/verify/runs/{runId}/bundle}（节点 token 认证，字节不走帧）。</p>
 *
 * <p><b>verify_patch</b>（PATCHING，环境节点）：pre_hook（失败即终止，不动现场）→
 * 下载包到 {@code patchDir/.devmind-verify-staging/} 解包并校验清单哈希 → 与本需求 manifest
 * （runner 状态目录 {@code <root>/_verify/manifests/<manifestKey>.json}，runner 是唯一读写者）
 * diff → 覆盖/新增/删除（只及本需求 manifest 范围，manifest 外文件永不触碰）→ 原子写新
 * manifest → post_hook。<b>恢复闭环</b>：pre_hook 成功后的任何失败仍尝试 post_hook 拉起服务，
 * 覆盖中途失败标注「现场可能为混合版本」。</p>
 *
 * <p>上行帧：进度行 {@code verify_log{requestId,chunk}}（所有输出先过
 * {@link RunnerWorkspace#sanitize} 脱敏 token）；收口 {@code verify_ack{requestId,ok,error?,
 * fileCount?,filesChanged?}}。三条入口都投虚拟线程，不阻塞 WS listener。</p>
 */
public class VerifyHandler {

    private static final Logger log = LoggerFactory.getLogger(VerifyHandler.class);

    /** 清单在 zip 内的固定入口名（pack 写入 / patch 读取，双边契约） */
    static final String MANIFEST_ENTRY = ".devmind-verify-manifest.json";
    /** patchDir 下的解包暂存目录名（同步完成即清理） */
    static final String STAGING_DIR = ".devmind-verify-staging";
    /** runner 侧 manifest 状态目录（<workspaceRoot>/_verify/manifests/；_ 前缀同 _chat 惯例，不参与 GC） */
    static final String STATE_DIR = "_verify";

    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(10);

    private final RunnerConfig config;
    private final RunnerWorkspace workspace;
    private final Consumer<Map<String, Object>> sender;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public VerifyHandler(RunnerConfig config, RunnerWorkspace workspace,
                         Consumer<Map<String, Object>> sender) {
        this.config = config;
        this.workspace = workspace;
        this.sender = sender;
    }

    /** WS listener 入口：verify_push（PUSHING）。 */
    public void handlePush(JsonNode frame) {
        Thread.ofVirtual().name("verify-push-" + frame.path("requestId").asText("?"))
                .start(() -> runPush(frame));
    }

    /** WS listener 入口：verify_pack（BUILDING）。 */
    public void handlePack(JsonNode frame) {
        Thread.ofVirtual().name("verify-pack-" + frame.path("requestId").asText("?"))
                .start(() -> runPack(frame));
    }

    /** WS listener 入口：verify_patch（PATCHING）。 */
    public void handlePatch(JsonNode frame) {
        Thread.ofVirtual().name("verify-patch-" + frame.path("requestId").asText("?"))
                .start(() -> runPatch(frame));
    }

    // ---------------- PUSHING ----------------

    private void runPush(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        String token = frame.path("token").asText(null);
        try {
            RunnerWorkspace.PushOutcome r = workspace.verifyPush(
                    frame.path("projectId").asText(""),
                    frame.path("workspaceOwner").asText(""),
                    frame.path("workspaceKey").asText(""),
                    frame.path("remoteUrl").asText(""),
                    frame.path("branch").asText(""),
                    token, frame.path("timeoutSec").asLong(300));
            if (r.output() != null && !r.output().isBlank()) {
                emit(requestId, r.output().strip(), token);
            }
            if (r.exit() == 0) {
                emit(requestId, "[runner] 需求分支已推送: " + frame.path("branch").asText(""), token);
                ack(requestId, true, null, null, null);
            } else {
                ack(requestId, false, tail(r.output(), "git push 失败"), null, null);
            }
        } catch (Exception e) {
            log.warn("verify_push 失败: requestId={} err={}", requestId, e.getMessage());
            ack(requestId, false, String.valueOf(e.getMessage()), null, null);
        }
    }

    // ---------------- BUILDING ----------------

    private void runPack(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        long runId = frame.path("runId").asLong(0);
        String token = frame.path("token").asText(null);
        Path tmpZip = null;
        try {
            String projectId = frame.path("projectId").asText("");
            String workspaceKey = frame.path("workspaceKey").asText("");
            String remoteUrl = frame.path("remoteUrl").asText("");
            String branch = frame.path("branch").asText("");
            String commitSha = frame.path("commitSha").asText("");
            String outputDir = frame.path("outputDir").asText("");
            long timeoutSec = frame.path("timeoutSec").asLong(1800);
            long bundleMaxBytes = frame.path("bundleMaxBytes").asLong(512L * 1024 * 1024);
            if (outputDir.isBlank()) {
                throw new IllegalStateException("verify_pack 缺 outputDir（构建配置未配产物目录）");
            }
            List<Step> steps = new ArrayList<>();
            for (JsonNode s : frame.path("steps")) {
                steps.add(new Step(s.path("name").asText("步骤"),
                        s.path("command").asText(""), s.path("workingDir").asText(null)));
            }
            if (steps.isEmpty()) {
                throw new IllegalStateException("verify_pack 无构建步骤（项目未配 build_steps）");
            }
            Map<String, String> env = new LinkedHashMap<>();
            JsonNode envNode = frame.path("env");
            if (envNode.isObject()) {
                envNode.properties().forEach(e -> env.put(e.getKey(), e.getValue().asText("")));
            }
            // 命令与 exec 同一白名单防线（服务端只下发项目预定义 build_steps，这里是本机兜底）
            for (Step s : steps) {
                ExecHandler.checkAllowlist(config.execAllowlist(), s.command());
            }

            Path dir = workspace.prepareVerify(projectId, workspaceKey, remoteUrl, branch, commitSha, token);
            emit(requestId, "[runner] verify 工作区就绪: " + dir, token);

            long deadlineMs = System.currentTimeMillis() + timeoutSec * 1000;
            for (Step s : steps) {
                long leftSec = (deadlineMs - System.currentTimeMillis()) / 1000;
                if (leftSec <= 0) {
                    throw new IllegalStateException("构建总超时（>" + timeoutSec + "s），整链终止");
                }
                emit(requestId, "[runner] 步骤开始: " + s.name(), token);
                int code = runShell(s.command(), ExecHandler.resolveCwd(dir, s.workingDir()),
                        env, leftSec, token, requestId);
                if (code != 0) {
                    throw new IllegalStateException("步骤「" + s.name() + "」退出码 " + code);
                }
            }

            Path outRoot = dir.resolve(outputDir).normalize();
            if (!outRoot.startsWith(dir)) {
                throw new IllegalStateException("outputDir 越界（.. 逃逸防护）: " + outputDir);
            }
            if (!Files.isDirectory(outRoot)) {
                throw new IllegalStateException("产物目录不存在（构建步骤未产出 " + outputDir + "）: " + outRoot);
            }
            List<ManifestEntry> manifest = manifestOf(outRoot);
            if (manifest.isEmpty()) {
                throw new IllegalStateException("产物目录为空（output_dir 必须含完整部署物树）: " + outRoot);
            }
            tmpZip = Files.createTempFile("devmind-verify-", ".zip");
            zipBundle(outRoot, manifest, tmpZip);
            long zipBytes = Files.size(tmpZip);
            if (zipBytes > bundleMaxBytes) {
                throw new IllegalStateException("VERIFY_BUNDLE_TOO_LARGE: 产物包 " + zipBytes / 1_048_576
                        + "MB 超上限 " + bundleMaxBytes / 1_048_576 + "MB，请精简 output_dir");
            }
            emit(requestId, "[runner] 产物打包完成: " + manifest.size() + " 个文件, "
                    + zipBytes / 1024 + "KB，上传中", token);
            uploadBundle(runId, tmpZip);
            emit(requestId, "[runner] 产物包已上传", token);
            ack(requestId, true, null, manifest.size(), null);
        } catch (Exception e) {
            log.warn("verify_pack 失败: requestId={} err={}", requestId, e.getMessage());
            emit(requestId, "[runner] " + e.getMessage(), token);
            ack(requestId, false, String.valueOf(e.getMessage()), null, null);
        } finally {
            deleteQuietly(tmpZip);
        }
    }

    /** HTTP 上传产物包（节点 token 认证；服务端校验 run↔构建节点绑定）。 */
    private void uploadBundle(long runId, Path zip) throws IOException, InterruptedException {
        String url = RunnerUpgrader.serverHttpBase(config) + "/api/agent/verify/runs/" + runId
                + "/bundle?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);
        HttpResponse<Void> resp = RunnerHttp.newClient(config).send(
                HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT)
                        .header("Content-Type", "application/zip")
                        .POST(HttpRequest.BodyPublishers.ofFile(zip)).build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("产物包上传失败: HTTP " + resp.statusCode());
        }
    }

    // ---------------- PATCHING ----------------

    private void runPatch(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        long runId = frame.path("runId").asLong(0);
        String patchDir = frame.path("patchDir").asText("");
        String preHook = frame.path("preHook").asText(null);
        String postHook = frame.path("postHook").asText(null);
        String manifestKey = frame.path("manifestKey").asText("");
        long timeoutSec = frame.path("timeoutSec").asLong(600);
        Path staging = null;
        boolean preDone = false;   // pre_hook 一旦成功登记：此后任何失败都必须尝试 post_hook
        boolean postAttempted = false;
        boolean copyStarted = false;
        try {
            if (patchDir.isBlank()) {
                throw new IllegalStateException("verify_patch 缺 patchDir");
            }
            Path target = Path.of(patchDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(target)) {
                throw new IllegalStateException("补丁目录不存在: " + target);
            }
            if (manifestKey.isBlank() || !manifestKey.matches("[a-zA-Z0-9._-]+")) {
                throw new IllegalStateException("非法 manifestKey: " + manifestKey);
            }
            long deadlineMs = System.currentTimeMillis() + timeoutSec * 1000;

            if (preHook != null && !preHook.isBlank()) {
                emit(requestId, "[runner] 执行 pre_hook", null);
                ExecHandler.checkAllowlist(config.execAllowlist(), preHook);
                int code = runShell(preHook, target, Map.of(),
                        Math.max(30, (deadlineMs - System.currentTimeMillis()) / 1000), null, requestId);
                if (code != 0) {
                    throw new IllegalStateException("pre_hook 退出码 " + code + "（现场未动，仍是旧版本）");
                }
                preDone = true;
            }

            staging = target.resolve(STAGING_DIR).normalize();
            deleteQuietly(staging);
            Files.createDirectories(staging);
            Path zip = staging.resolve("bundle.zip");
            emit(requestId, "[runner] 下载产物包 run=" + runId, null);
            downloadBundle(runId, zip);
            Path tree = staging.resolve("tree");
            List<ManifestEntry> newManifest = unzipBundle(zip, tree);
            verifyHashes(tree, newManifest);
            emit(requestId, "[runner] 包校验通过: " + newManifest.size() + " 个文件", null);

            List<ManifestEntry> oldManifest = readLocalManifest(manifestKey);
            copyStarted = true;
            int changed = applyDiff(tree, target, newManifest, oldManifest,
                    line -> emit(requestId, line, null));
            writeLocalManifest(manifestKey, newManifest);
            deleteQuietly(staging);
            staging = null;

            if (postHook != null && !postHook.isBlank()) {
                postAttempted = true;
                emit(requestId, "[runner] 执行 post_hook", null);
                ExecHandler.checkAllowlist(config.execAllowlist(), postHook);
                int code = runShell(postHook, target, Map.of(),
                        Math.max(30, (deadlineMs - System.currentTimeMillis()) / 1000), null, requestId);
                if (code != 0) {
                    throw new IllegalStateException("post_hook 退出码 " + code + "（文件已同步，服务恢复需人工确认）");
                }
            }
            ack(requestId, true, null, null, changed);
        } catch (Exception e) {
            log.warn("verify_patch 失败: requestId={} err={}", requestId, e.getMessage());
            StringBuilder err = new StringBuilder(String.valueOf(e.getMessage()));
            if (copyStarted) {
                err.append("；现场可能为混合版本");
            }
            // 恢复闭环：pre_hook 成功后的任何失败仍尝试 post_hook 拉起服务
            if (preDone && !postAttempted && postHook != null && !postHook.isBlank()) {
                try {
                    emit(requestId, "[runner] 失败恢复：尝试 post_hook", null);
                    int code = runShell(postHook, Path.of(patchDir), Map.of(), 300, null, requestId);
                    err.append(code == 0 ? "；post_hook 恢复已执行" : "；post_hook 恢复失败（退出码 " + code + "）");
                } catch (Exception ex) {
                    err.append("；post_hook 恢复异常: ").append(ex.getMessage());
                }
            }
            ack(requestId, false, err.toString(), null, null);
        } finally {
            deleteQuietly(staging);
        }
    }

    /** HTTP 下载产物包（节点 token 认证；服务端校验 run↔环境节点绑定）。 */
    private void downloadBundle(long runId, Path dest) throws IOException, InterruptedException {
        String url = RunnerUpgrader.serverHttpBase(config) + "/api/agent/verify/runs/" + runId
                + "/bundle?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);
        HttpResponse<Path> resp = RunnerHttp.newClient(config).send(
                HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("产物包下载失败: HTTP " + resp.statusCode());
        }
    }

    // ---------------- 清单与 zip（静态，供单测直接驱动） ----------------

    /** 产物清单条目（zip 内 MANIFEST_ENTRY 与 runner 侧状态文件共用 schema） */
    record ManifestEntry(String path, String sha256, long size) {
    }

    /** 对目录树生成清单：相对路径（/ 分隔）+ sha256 + size，按路径排序（稳定序列化）。 */
    static List<ManifestEntry> manifestOf(Path root) throws IOException {
        List<ManifestEntry> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                out.add(new ManifestEntry(rel, sha256(p), Files.size(p)));
            }
        }
        out.sort(Comparator.comparing(ManifestEntry::path));
        return out;
    }

    /** 产物树 + 清单打 zip（清单固定为第一个入口，patch 侧同名读取）。 */
    static void zipBundle(Path root, List<ManifestEntry> manifest, Path zipOut) throws IOException {
        try (OutputStream fos = Files.newOutputStream(zipOut);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zos.write(manifestJson(manifest).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (ManifestEntry e : manifest) {
                zos.putNextEntry(new ZipEntry(e.path()));
                Files.copy(root.resolve(e.path()), zos);
                zos.closeEntry();
            }
        }
    }

    /** 解包到 tree 目录并返回包内清单（缺清单 = 包非法）。zip 入口越界（zip slip）防护。 */
    static List<ManifestEntry> unzipBundle(Path zip, Path tree) throws IOException {
        Files.createDirectories(tree);
        List<ManifestEntry> manifest = null;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                if (MANIFEST_ENTRY.equals(e.getName())) {
                    manifest = parseManifest(new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                    continue;
                }
                Path dest = safeResolve(tree, e.getName());
                Files.createDirectories(dest.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (manifest == null) {
            throw new IllegalStateException("产物包缺清单入口 " + MANIFEST_ENTRY);
        }
        return manifest;
    }

    /** 解包文件逐一对清单校验 sha256/size（包在传输/暂存中被篡改或截断的最后一道防线）。 */
    static void verifyHashes(Path tree, List<ManifestEntry> manifest) throws IOException {
        for (ManifestEntry e : manifest) {
            Path f = safeResolve(tree, e.path());
            if (!Files.isRegularFile(f)) {
                throw new IllegalStateException("包内缺文件: " + e.path());
            }
            if (Files.size(f) != e.size() || !sha256(f).equals(e.sha256())) {
                throw new IllegalStateException("包内文件哈希校验失败: " + e.path());
            }
        }
    }

    /**
     * manifest diff 同步：新增+变更（哈希不同或盘上缺失）从 staging 覆盖进 target；
     * 旧 manifest 有、新 manifest 无的删除。<b>只及 manifest 范围</b>——target 中从未出现在
     * 本需求 manifest 的文件（环境本地配置/logs/data/其他需求的文件）永不触碰。
     *
     * @return 变更文件数（新增+覆盖+删除）
     */
    static int applyDiff(Path tree, Path target, List<ManifestEntry> newManifest,
                         List<ManifestEntry> oldManifest, Consumer<String> logSink) throws IOException {
        Map<String, ManifestEntry> oldByPath = new LinkedHashMap<>();
        for (ManifestEntry e : oldManifest) {
            oldByPath.put(e.path(), e);
        }
        Map<String, ManifestEntry> newByPath = new LinkedHashMap<>();
        for (ManifestEntry e : newManifest) {
            newByPath.put(e.path(), e);
        }
        int changed = 0;
        for (ManifestEntry e : newManifest) {
            ManifestEntry old = oldByPath.get(e.path());
            Path dest = safeResolve(target, e.path());
            boolean diskMissing = !Files.isRegularFile(dest);
            boolean skip;
            if (old != null) {
                skip = old.sha256().equals(e.sha256()) && !diskMissing;
            } else {
                // 首验/无旧 manifest：盘上已有同哈希文件也跳过（不盲盖）
                skip = !diskMissing && Files.size(dest) == e.size() && sha256(dest).equals(e.sha256());
            }
            if (skip) {
                continue;
            }
            Files.createDirectories(dest.getParent());
            Files.copy(tree.resolve(e.path()), dest, StandardCopyOption.REPLACE_EXISTING);
            changed++;
            if (logSink != null) {
                logSink.accept("[runner] " + (old == null && diskMissing ? "新增 " : "覆盖 ") + e.path());
            }
        }
        for (ManifestEntry e : oldManifest) {
            if (newByPath.containsKey(e.path())) {
                continue;
            }
            Path dest = safeResolve(target, e.path());
            if (Files.deleteIfExists(dest)) {
                changed++;
                if (logSink != null) {
                    logSink.accept("[runner] 删除 " + e.path());
                }
            }
        }
        return changed;
    }

    /** 清单路径越界防护（../ 逃逸 / 绝对路径一律拒绝——清单来自产物包，按不可信输入处理）。 */
    private static Path safeResolve(Path base, String rel) {
        if (rel == null || rel.isBlank() || rel.startsWith("/") || rel.contains(":")) {
            throw new IllegalStateException("清单含非法路径: " + rel);
        }
        Path p = base.resolve(rel).normalize();
        if (!p.startsWith(base)) {
            throw new IllegalStateException("清单路径越界（.. 逃逸）: " + rel);
        }
        return p;
    }

    /** runner 侧 manifest 状态文件（<workspaceRoot>/_verify/manifests/<key>.json）。 */
    Path manifestFile(String manifestKey) {
        return config.workspaceRoot().toAbsolutePath().normalize()
                .resolve(STATE_DIR).resolve("manifests").resolve(manifestKey + ".json").normalize();
    }

    /** 读本需求既有 manifest；缺失（首验/节点重建）= 空清单 → 退化为全量同步一次。 */
    List<ManifestEntry> readLocalManifest(String manifestKey) {
        Path f = manifestFile(manifestKey);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            return parseManifest(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("本地 manifest 读取失败（按全量同步处理）: {} err={}", f, e.getMessage());
            return List.of();
        }
    }

    /** 原子写新 manifest（tmp + move 防半截清单——它决定下次 diff 的删除语义，半截会误删）。 */
    void writeLocalManifest(String manifestKey, List<ManifestEntry> manifest) throws IOException {
        Path f = manifestFile(manifestKey);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, manifestJson(manifest), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------- 清单 JSON（手写序列化/解析，schema 稳定免 Jackson 绑定） ----------------

    static String manifestJson(List<ManifestEntry> manifest) {
        StringBuilder sb = new StringBuilder("{\"files\":[");
        for (int i = 0; i < manifest.size(); i++) {
            ManifestEntry e = manifest.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"path\":\"").append(escape(e.path())).append("\",\"sha256\":\"")
                    .append(e.sha256()).append("\",\"size\":").append(e.size()).append('}');
        }
        return sb.append("]}").toString();
    }

    static List<ManifestEntry> parseManifest(String json) {
        JsonNode root = JsonMapper.builder().build().readTree(json);
        List<ManifestEntry> out = new ArrayList<>();
        for (JsonNode f : root.path("files")) {
            out.add(new ManifestEntry(f.path("path").asText(""),
                    f.path("sha256").asText(""), f.path("size").asLong(0)));
        }
        return out;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------- 脚本执行与上行帧 ----------------

    private record Step(String name, String command, String workingDir) {
    }

    /**
     * 跑一段 shell 脚本（写临时 .sh 以 execShell 拉起，同 ExecHandler 姿势），输出行流式
     * 回 verify_log（脱敏后）。返回退出码；超时整树杀返回 -1。
     */
    private int runShell(String script, Path cwd, Map<String, String> env, long timeoutSec,
                         String token, String requestId) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("devmind-verify-", ".sh");
        Process proc = null;
        try {
            Files.writeString(tmp, script, StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(config.execShell(), tmp.toAbsolutePath().toString());
            pb.directory(cwd.toFile());
            env.forEach((k, v) -> pb.environment().put(k, v == null ? "" : v));
            // CAP-43：exec scope 代理注入（帧 env 优先，同 ExecHandler）
            String proxy = com.devmind.common.agent.exec.NodeProxy.urlFor("exec");
            if (proxy != null) {
                for (String key : new String[]{"HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"}) {
                    pb.environment().putIfAbsent(key, proxy);
                }
            }
            proc = pb.start();
            Process p = proc;
            Thread outT = Thread.ofVirtual().name("verify-out")
                    .start(() -> pump(p.getInputStream(), requestId, token));
            Thread errT = Thread.ofVirtual().name("verify-err")
                    .start(() -> pump(p.getErrorStream(), requestId, token));
            boolean timedOut = !proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (timedOut) {
                emit(requestId, "[runner] 超时（>" + timeoutSec + "s），整树终止", token);
                ProcessHelper.killTree(proc);
                proc.waitFor(10, TimeUnit.SECONDS);
            }
            outT.join(5000);
            errT.join(5000);
            return timedOut ? -1 : proc.exitValue();
        } finally {
            if (proc != null && proc.isAlive()) {
                ProcessHelper.killTree(proc);
            }
            deleteQuietly(tmp);
        }
    }

    private void pump(InputStream in, String requestId, String token) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                emit(requestId, line, token);
            }
        } catch (IOException ignored) {
        }
    }

    /** verify_log 上行（chunk 先脱敏）。发送失败只记 debug——收口靠 verify_ack。 */
    private void emit(String requestId, String chunk, String token) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "verify_log");
        frame.put("requestId", requestId);
        frame.put("chunk", RunnerWorkspace.sanitize(chunk, token));
        try {
            sender.accept(frame);
        } catch (Exception e) {
            log.debug("verify_log 发送失败: {}", e.getMessage());
        }
    }

    /** verify_ack 收口（fileCount=pack 专属，filesChanged=patch 专属）。 */
    private void ack(String requestId, boolean ok, String error, Integer fileCount, Integer filesChanged) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "verify_ack");
        frame.put("requestId", requestId);
        frame.put("ok", ok);
        if (error != null) {
            frame.put("error", error);
        }
        if (fileCount != null) {
            frame.put("fileCount", fileCount);
        }
        if (filesChanged != null) {
            frame.put("filesChanged", filesChanged);
        }
        sender.accept(frame);
    }

    private static String sha256(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tail(String s, String fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        String t = s.strip();
        return t.length() <= 300 ? t : "…" + t.substring(t.length() - 300);
    }

    private static void deleteQuietly(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        } catch (Exception e) {
            log.debug("清理失败（可人工删除 {}）: {}", p, e.getMessage());
        }
    }
}
