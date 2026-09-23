package com.devmind.classify.pkg;

import com.devmind.auth.IdentityService;
import com.devmind.classify.config.ClassifyProperties;
import com.devmind.classify.instance.repo.ClassifyInstanceRepository;
import com.devmind.classify.pkg.dto.ClassifyPackageInstallView;
import com.devmind.classify.pkg.dto.ClassifyPackageView;
import com.devmind.classify.pkg.model.ClassifyPackageEntity;
import com.devmind.classify.pkg.model.ClassifyPackageInstallEntity;
import com.devmind.classify.pkg.repo.ClassifyPackageInstallRepository;
import com.devmind.classify.pkg.repo.ClassifyPackageRepository;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentPkgCommand;
import com.devmind.common.agent.AgentPkgResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.execution.runner.AgentNodeRouter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * CAP-57 FR-03 安装包管理与节点分发。
 *
 * <p><b>上传全程流式</b>（GB 级模型权重包红线）：{@code transferTo} 落 {@code .upload} 临时文件 →
 * {@link DigestInputStream} 流式算 sha256 → 建表拿 id → 原子 move 成 {@code pkg-<id>.zip}。
 * 任何一步失败删临时文件，不留半成品；全程不过内存（禁 getBytes）。</p>
 *
 * <p><b>分发 = 节点拉取</b>：install 只是把 installs 行置 PENDING 并下发 pkg 帧（协议 v15，
 * {@link AgentNodeRouter#requirePkgCapable} 触发阶段门控），runner 凭节点 token 走 HTTP 拉包、
 * sha 校验、解 zip、原子换版后回 pkg_ack；虚拟线程等 future 收口 INSTALLED/FAILED。
 * 失败可 {@link #retryInstall(long)} 重试（复用同一行，重发新 requestId）。</p>
 */
@Service
public class ClassifyPackageService {

    private static final Logger log = LoggerFactory.getLogger(ClassifyPackageService.class);

    /** 等 pkg_ack 的上限（GB 下载以分钟计，30min 封顶；runner 侧 HTTP 自身有超时） */
    private static final long INSTALL_WAIT_MINUTES = 30;

    private static final List<String> KINDS = List.of(ClassifyPackageEntity.KIND_SIDECAR_APP,
            ClassifyPackageEntity.KIND_MODEL_WEIGHTS, ClassifyPackageEntity.KIND_CORPUS);

    private final ClassifyPackageRepository repo;
    private final ClassifyPackageInstallRepository installRepo;
    private final ClassifyInstanceRepository instanceRepo;
    private final ClassifyProperties props;
    private final AgentNodeRouter nodeRouter;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final IdentityService identityService;

    public ClassifyPackageService(ClassifyPackageRepository repo,
                                  ClassifyPackageInstallRepository installRepo,
                                  ClassifyInstanceRepository instanceRepo,
                                  ClassifyProperties props,
                                  AgentNodeRouter nodeRouter,
                                  ObjectProvider<AgentNodeConnector> connectorProvider,
                                  IdentityService identityService) {
        this.repo = repo;
        this.installRepo = installRepo;
        this.instanceRepo = instanceRepo;
        this.props = props;
        this.nodeRouter = nodeRouter;
        this.connectorProvider = connectorProvider;
        this.identityService = identityService;
    }

    // ---------------- 包管理 ----------------

    public List<ClassifyPackageView> list(String kind) {
        List<ClassifyPackageEntity> all = repo.findAll(
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "id"));
        return all.stream()
                .filter(e -> kind == null || kind.isBlank() || kind.equals(e.getKind()))
                .map(ClassifyPackageService::viewOf).toList();
    }

    public ClassifyPackageView get(long id) {
        return viewOf(require(id));
    }

    /**
     * 上传：流式落盘 → 流式 sha256 → 建行 → 原子 move。{@code (kind,name,pkgVersion)} 唯一，
     * 撞了 409（同版本重传请先删旧行——删行会同时清理文件与安装记录）。
     */
    public ClassifyPackageView upload(String kind, String name, String pkgVersion,
                                      MultipartFile file) {
        String k = requireKind(kind);
        String n = requireText(name, "包名必填", 128);
        String v = requireText(pkgVersion, "版本必填", 128);
        if (file == null || file.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "包文件为空");
        }
        if (repo.findByKindAndNameAndPkgVersion(k, n, v).isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "已存在同 kind+名称+版本的包: " + k + " / " + n + " / " + v);
        }
        Path dir = storageDir();
        Path tmp = dir.resolve("upload-" + UUID.randomUUID() + ".tmp");
        try {
            file.transferTo(tmp);
            String sha256 = sha256(tmp);
            ClassifyPackageEntity e = new ClassifyPackageEntity();
            e.setKind(k);
            e.setName(n);
            e.setPkgVersion(v);
            e.setSha256(sha256);
            e.setSizeBytes(Files.size(tmp));
            e.setOriginalFilename(trimTo(file.getOriginalFilename(), 512));
            e.setStoredPath(""); // 先建行拿 id，随即原子 move 后回填
            e.setUploadedBy(identityService.currentActor());
            e.setUploadedAt(Instant.now());
            ClassifyPackageEntity saved = repo.save(e);
            Path target = packagePath(saved.getId());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            saved.setStoredPath(target.toAbsolutePath().toString());
            saved = repo.save(saved);
            log.info("分类安装包上传: id={} kind={} name={} v={} size={} sha={} by={}",
                    saved.getId(), k, n, v, saved.getSizeBytes(), sha256, saved.getUploadedBy());
            return viewOf(saved);
        } catch (DevMindException e) {
            deleteQuietly(tmp);
            throw e;
        } catch (Exception e) {
            deleteQuietly(tmp);
            throw new DevMindException(ErrorCode.CONFLICT, "安装包上传失败: " + e.getMessage(), e);
        }
    }

    /** 删除：清文件 + 安装记录；有实例拿它当应用包时 409（env 里的 ${PKG_DIR:id} 引用不扫描，运维自负） */
    public void delete(long id) {
        ClassifyPackageEntity e = require(id);
        var referrers = instanceRepo.findByAppPackageId(id);
        if (!referrers.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "实例 " + referrers.stream().map(i -> "「" + i.getName() + "」")
                            .collect(Collectors.joining("、"))
                    + " 正把它当应用包，不能删除（先改实例绑定）");
        }
        installRepo.deleteAll(installRepo.findByPackageIdOrderByIdDesc(id));
        deleteQuietly(Path.of(e.getStoredPath()));
        repo.delete(e);
        log.info("分类安装包删除: id={} kind={} name={} v={}", id, e.getKind(), e.getName(), e.getPkgVersion());
    }

    // ---------------- 节点分发 ----------------

    /**
     * 分发到节点（202 语义）：installs 行置 PENDING 立即返回，pkg 帧已下发；虚拟线程等
     * pkg_ack 收口 INSTALLED/FAILED。<b>禁 @Transactional</b>（红线：异步线程要看 PENDING 行）。
     */
    public ClassifyPackageInstallView install(long packageId, String nodeId) {
        ClassifyPackageEntity pkg = require(packageId);
        String node = requireText(nodeId, "目标节点必填", 64);
        nodeRouter.requirePkgCapable(node);
        if (!Files.isRegularFile(Path.of(pkg.getStoredPath()))) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "包文件缺失（可能被清理或移动）: id=" + packageId);
        }
        Instant now = Instant.now();
        ClassifyPackageInstallEntity install = installRepo.findByPackageIdAndNodeId(packageId, node)
                .orElseGet(() -> {
                    ClassifyPackageInstallEntity i = new ClassifyPackageInstallEntity();
                    i.setPackageId(packageId);
                    i.setNodeId(node);
                    i.setCreatedAt(now);
                    return i;
                });
        String requestId = "pkg-" + packageId + "-" + UUID.randomUUID().toString().substring(0, 8);
        install.setStatus(ClassifyPackageInstallEntity.STATUS_PENDING);
        install.setRequestId(requestId);
        install.setError(null);
        install.setUpdatedAt(now);
        ClassifyPackageInstallEntity saved = installRepo.save(install);

        AgentPkgCommand cmd = new AgentPkgCommand(requestId, packageId, pkg.getSha256(),
                pkg.getSizeBytes(), pkg.getOriginalFilename(), "packages/pkg-" + packageId);
        CompletableFuture<AgentPkgResult> future;
        try {
            future = connector().pkgInstallAsync(node, cmd);
        } catch (Exception e) {
            markFailed(saved.getId(), e.getMessage());
            throw e;
        }
        long installId = saved.getId();
        Thread.ofVirtual().name("pkg-install-wait-" + installId).start(() -> {
            try {
                AgentPkgResult r = future.get(INSTALL_WAIT_MINUTES, TimeUnit.MINUTES);
                settle(installId, r);
            } catch (Exception e) {
                markFailed(installId, "等待节点分发结果超时/异常: " + e.getMessage());
            }
        });
        log.info("分类安装包分发已下发: install={} package={} node={} request={}",
                installId, packageId, node, requestId);
        return installViewOf(saved, pkg.getName());
    }

    /** 失败重试：复用同一行（packageId+nodeId 唯一），走完整 install 流程 */
    public ClassifyPackageInstallView retryInstall(long installId) {
        ClassifyPackageInstallEntity e = installRepo.findById(installId).orElseThrow(
                () -> new DevMindException(ErrorCode.NOT_FOUND, "安装记录不存在: id=" + installId));
        return install(e.getPackageId(), e.getNodeId());
    }

    public List<ClassifyPackageInstallView> listInstalls(Long packageId, String nodeId) {
        Map<Long, String> names = repo.findAll().stream()
                .collect(Collectors.toMap(ClassifyPackageEntity::getId, ClassifyPackageEntity::getName));
        List<ClassifyPackageInstallEntity> rows;
        if (packageId != null) {
            rows = installRepo.findByPackageIdOrderByIdDesc(packageId);
        } else if (nodeId != null && !nodeId.isBlank()) {
            rows = installRepo.findByNodeIdOrderByIdDesc(nodeId.strip());
        } else {
            rows = installRepo.findAll();
        }
        return rows.stream().map(i -> installViewOf(i, names.get(i.getPackageId()))).toList();
    }

    /** pkg_ack 收口（虚拟线程）：INSTALLED 记节点侧 installDir；FAILED 记原因 */
    private void settle(long installId, AgentPkgResult r) {
        installRepo.findById(installId).ifPresent(i -> {
            i.setUpdatedAt(Instant.now());
            if (r.ok()) {
                i.setStatus(ClassifyPackageInstallEntity.STATUS_INSTALLED);
                i.setInstallDir(r.installDir());
                i.setError(null);
                log.info("分类安装包已装到节点: install={} node={} dir={}",
                        installId, i.getNodeId(), r.installDir());
            } else {
                i.setStatus(ClassifyPackageInstallEntity.STATUS_FAILED);
                i.setError(trimTo(r.error(), 1024));
                log.warn("分类安装包分发失败: install={} node={} err={}",
                        installId, i.getNodeId(), r.error());
            }
            installRepo.save(i);
        });
    }

    private void markFailed(long installId, String error) {
        installRepo.findById(installId).ifPresent(i -> {
            i.setStatus(ClassifyPackageInstallEntity.STATUS_FAILED);
            i.setError(trimTo(error, 1024));
            i.setUpdatedAt(Instant.now());
            installRepo.save(i);
        });
    }

    // ---------------- internals ----------------

    private Path storageDir() {
        try {
            Path dir = Path.of(props.getStorageDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "安装包存储目录不可用（devmind.classify.storage-dir）: " + e.getMessage(), e);
        }
    }

    /** 存储按 id 命名（原始文件名仅展示，防路径注入与重名互相覆盖） */
    private Path packagePath(long id) {
        return storageDir().resolve("pkg-" + id + ".zip");
    }

    private ClassifyPackageEntity require(long id) {
        return repo.findById(id).orElseThrow(
                () -> new DevMindException(ErrorCode.NOT_FOUND, "安装包不存在: id=" + id));
    }

    private AgentNodeConnector connector() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无法下发安装指令");
        }
        return connector;
    }

    private static String requireKind(String kind) {
        if (kind == null || !KINDS.contains(kind.strip())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "未知包类型: " + kind + "（可用: " + String.join(" / ", KINDS) + "）");
        }
        return kind.strip();
    }

    private static String requireText(String value, String what, int max) {
        if (value == null || value.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, what);
        }
        return trimTo(value, max);
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** 流式 sha256（GB 文件不过内存） */
    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            in.transferTo(OutputStreamNull.INSTANCE);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            log.warn("临时文件清理失败（可手工删除）: {} {}", p, e.getMessage());
        }
    }

    private static ClassifyPackageView viewOf(ClassifyPackageEntity e) {
        return new ClassifyPackageView(e.getId(), e.getKind(), e.getName(), e.getPkgVersion(),
                e.getSha256(), e.getSizeBytes(), e.getOriginalFilename(), e.getUploadedBy(),
                e.getUploadedAt());
    }

    private static ClassifyPackageInstallView installViewOf(ClassifyPackageInstallEntity i,
                                                            String packageName) {
        return new ClassifyPackageInstallView(i.getId(), i.getPackageId(), packageName, i.getNodeId(),
                i.getInstallDir(), i.getStatus(), i.getRequestId(), i.getError(),
                i.getCreatedAt(), i.getUpdatedAt());
    }

    /** transferTo 的接收端：/dev/null 语义（DigestInputStream 只为过一遍流算摘要） */
    private static final class OutputStreamNull extends java.io.OutputStream {
        private static final OutputStreamNull INSTANCE = new OutputStreamNull();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
