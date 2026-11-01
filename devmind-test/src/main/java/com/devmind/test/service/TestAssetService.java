package com.devmind.test.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.program.ProgramContract;
import com.devmind.common.program.ProgramManifest;
import com.devmind.common.program.TestProgramBundleProvider;
import com.devmind.project.ProjectService;
import com.devmind.test.config.TestAssetProperties;
import com.devmind.test.dto.TestAssetGitRequest;
import com.devmind.test.dto.TestAssetReviewRequest;
import com.devmind.test.dto.TestAssetView;
import com.devmind.test.model.TestAssetEntity;
import com.devmind.test.repo.TestAssetRepository;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestSuiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CAP-104 测试资产库：GIT 源登记 / ZIP 与单文件上传、评审门禁、删除保护。
 * 未评审（DRAFT/REJECTED）资产不可被套件或用例引用；解包做了路径穿越与规模限制。
 */
@Service
public class TestAssetService implements TestProgramBundleProvider {

    private static final Logger log = LoggerFactory.getLogger(TestAssetService.class);
    private static final java.util.regex.Pattern COMMIT = java.util.regex.Pattern.compile("^[0-9a-fA-F]{7,64}$");

    private final TestAssetRepository repo;
    private final TestSuiteRepository suiteRepo;
    private final TestCaseRepository caseRepo;
    private final ProjectService projectService;
    private final IdentityService identityService;
    private final TestAssetProperties props;
    private final AuditService auditService;

    public TestAssetService(TestAssetRepository repo, TestSuiteRepository suiteRepo,
                            TestCaseRepository caseRepo, ProjectService projectService,
                            IdentityService identityService, TestAssetProperties props,
                            AuditService auditService) {
        this.repo = repo;
        this.suiteRepo = suiteRepo;
        this.caseRepo = caseRepo;
        this.projectService = projectService;
        this.identityService = identityService;
        this.props = props;
        this.auditService = auditService;
    }

    public List<TestAssetView> list(String projectId, String reviewStatus) {
        projectService.requireProject(projectId);
        List<TestAssetEntity> list = reviewStatus == null || reviewStatus.isBlank()
                ? repo.findByProjectIdOrderByCreatedAtDesc(projectId)
                : repo.findByProjectIdAndReviewStatusOrderByCreatedAtDesc(projectId,
                        reviewStatus.strip().toUpperCase(Locale.ROOT));
        return list.stream().map(this::toView).toList();
    }

    public TestAssetEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "测试资产不存在: " + id));
    }

    public TestAssetView get(Long id) {
        return toView(require(id));
    }

    public TestAssetView createGit(String projectId, TestAssetGitRequest req) {
        projectService.requireProject(projectId);
        if (req == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "请求体为空");
        }
        String name = requireText(req.name(), "资产名称");
        String repoUrl = requireText(req.repoUrl(), "git 仓库地址");
        String branch = requireText(req.branch(), "分支");
        if (req.commitHash() != null && !req.commitHash().isBlank()
                && !COMMIT.matcher(req.commitHash().strip()).matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "commitHash 只能是 7~64 位十六进制");
        }
        TestAssetEntity e = new TestAssetEntity();
        e.setProjectId(projectId);
        e.setKind(TestAssetEntity.GIT);
        e.setName(truncate(name, 128));
        e.setSource(repoUrl);
        e.setBranch(branch);
        e.setCommitHash(blankToNull(req.commitHash()));
        e.setReviewStatus(TestAssetEntity.DRAFT);
        e.setUploadedBy(identityService.currentActor());
        e.setNote(truncate(blankToNull(req.note()), 512));
        e.setCreatedAt(Instant.now());
        TestAssetEntity saved = repo.save(e);
        auditService.record("test", "asset.create", saved.getUploadedBy(), projectId, true,
                "资产 #" + saved.getId() + " " + saved.getName() + " kind=GIT");
        return toView(saved);
    }

    /** ZIP 解包 / 单文件落盘；先建行拿 id 再写文件，失败时回滚行与目录。 */
    public TestAssetView upload(String projectId, MultipartFile file, String name, String note) {
        return upload(projectId, file, name, note, null);
    }

    /**
     * M1.5：assetRole=PROGRAM 时解析 {@code .devmind-program.json} 并校验入口；
     * assetRole 为空时自动识别含清单的 ZIP（前端不传也能正确归角色）。
     */
    public TestAssetView upload(String projectId, MultipartFile file, String name, String note,
                                String assetRole) {
        projectService.requireProject(projectId);
        if (file == null || file.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "上传文件为空");
        }
        if (file.getSize() > props.getMaxUploadBytes()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "上传文件超限（" + file.getSize() + " > " + props.getMaxUploadBytes() + " 字节）");
        }
        String original = safeFilename(file.getOriginalFilename());
        boolean zip = original.toLowerCase(Locale.ROOT).endsWith(".zip");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "读取上传文件失败: " + e.getMessage());
        }

        String requestedRole = assetRole == null || assetRole.isBlank()
                ? null : assetRole.strip().toUpperCase(Locale.ROOT);
        if (requestedRole != null && !TestAssetEntity.ROLE_PROGRAM.equals(requestedRole)
                && !TestAssetEntity.ROLE_DATA.equals(requestedRole)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "assetRole 只能是 PROGRAM / DATA");
        }
        if (TestAssetEntity.ROLE_PROGRAM.equals(requestedRole) && !zip) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "程序资产必须是 ZIP 包");
        }

        TestAssetEntity e = new TestAssetEntity();
        e.setProjectId(projectId);
        e.setKind(zip ? TestAssetEntity.ZIP : TestAssetEntity.FILE);
        e.setName(truncate(blankToNull(name) == null ? original : name.strip(), 128));
        e.setSource(original);
        e.setSha256(EvidenceService.sha256(bytes));
        e.setSizeBytes((long) bytes.length);
        e.setAssetRole(TestAssetEntity.ROLE_DATA);
        e.setReviewStatus(TestAssetEntity.DRAFT);
        e.setUploadedBy(identityService.currentActor());
        e.setNote(truncate(blankToNull(note), 512));
        e.setCreatedAt(Instant.now());
        TestAssetEntity saved = repo.save(e);

        Path dir = assetDir(projectId, saved.getId());
        try {
            Files.createDirectories(dir.resolve("source"));
            Files.write(dir.resolve("source").resolve(original), bytes);
            if (zip) {
                Path filesDir = dir.resolve("files");
                int count = extractZip(bytes, filesDir);
                saved.setFileCount(count);
                saved.setStorageRef(projectId + "/" + saved.getId() + "/files");
                applyProgramManifest(saved, filesDir, requestedRole);
            } else {
                if (TestAssetEntity.ROLE_PROGRAM.equals(requestedRole)) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "程序资产必须是 ZIP 包");
                }
                saved.setFileCount(1);
                saved.setStorageRef(projectId + "/" + saved.getId() + "/source/" + original);
            }
            TestAssetEntity stored = repo.save(saved);
            auditService.record("test", "asset.upload", stored.getUploadedBy(), projectId, true,
                    "资产 #" + stored.getId() + " " + stored.getName() + " role="
                            + stored.getAssetRole() + " " + stored.getSizeBytes() + " 字节");
            return toView(stored);
        } catch (Exception ex) {
            try {
                deleteRecursively(dir);
            } catch (Exception ignored) {
                // 清理失败不掩盖原始异常
            }
            repo.delete(saved);
            if (ex instanceof DevMindException de) {
                throw de;
            }
            throw new DevMindException(ErrorCode.INTERNAL, "资产入库失败: " + ex.getMessage());
        }
    }

    public TestAssetView review(Long id, TestAssetReviewRequest req) {
        TestAssetEntity e = require(id);
        String status = req == null || req.reviewStatus() == null ? null
                : req.reviewStatus().strip().toUpperCase(Locale.ROOT);
        if (!TestAssetEntity.APPROVED.equals(status) && !TestAssetEntity.REJECTED.equals(status)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "评审状态只能是 APPROVED / REJECTED");
        }
        e.setReviewStatus(status);
        e.setReviewedBy(identityService.currentActor());
        e.setReviewedAt(Instant.now());
        if (req.note() != null && !req.note().isBlank()) {
            e.setNote(truncate(req.note().strip(), 512));
        }
        TestAssetEntity saved = repo.save(e);
        auditService.record("test", "asset.review", saved.getReviewedBy(), saved.getProjectId(),
                TestAssetEntity.APPROVED.equals(status),
                "资产 #" + saved.getId() + " → " + status);
        return toView(saved);
    }

    @Transactional
    public void delete(Long id) {
        TestAssetEntity e = require(id);
        long suites = suiteRepo.countBySourceAssetId(id);
        long cases = caseRepo.countBySourceAssetId(id);
        if (suites > 0 || cases > 0) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "资产仍被 " + suites + " 个套件 / " + cases + " 个用例引用，不可删除");
        }
        repo.delete(e);
        auditService.record("test", "asset.delete", identityService.currentActor(), e.getProjectId(), true,
                "资产 #" + id + " " + e.getName());
        try {
            deleteRecursively(assetDir(e.getProjectId(), id));
        } catch (IOException ignored) {
            log.warn("资产目录清理失败: id={}", id);
        }
    }

    public TestAssetView toView(TestAssetEntity e) {
        ProgramManifest manifest = null;
        if (e.getManifestJson() != null && !e.getManifestJson().isBlank()) {
            try {
                manifest = ProgramManifest.parse(e.getManifestJson());
            } catch (IllegalArgumentException ignored) {
                // 历史数据或手工改库：视图降级只回原始字段，不让列表整体失败
            }
        }
        return new TestAssetView(e.getId(), e.getProjectId(), e.getKind(), e.getName(), e.getSource(),
                e.getBranch(), e.getCommitHash(), e.getSha256(), e.getSizeBytes(), e.getFileCount(),
                e.getReviewStatus(), e.getUploadedBy(), e.getReviewedBy(), e.getReviewedAt(), e.getNote(),
                e.getCreatedAt(),
                e.getAssetRole(), e.getRuntime(), e.getEntrypoint(), e.getTargetOs(), e.getTargetArch(),
                manifest);
    }

    /** 程序包清单原文（未解析）；非程序资产返回 404。 */
    public String manifestJson(Long id) {
        TestAssetEntity e = require(id);
        if (!TestAssetEntity.ROLE_PROGRAM.equals(e.getAssetRole())
                || e.getManifestJson() == null || e.getManifestJson().isBlank()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "资产不是程序包或缺少清单: " + id);
        }
        return e.getManifestJson();
    }

    // ---------------- 程序包下发（runner 拉取） ----------------

    @Override
    public Optional<TestProgramBundleProvider.Bundle> packageBytes(long assetId) {
        return repo.findById(assetId).flatMap(e -> {
            if (!TestAssetEntity.ROLE_PROGRAM.equals(e.getAssetRole())
                    || !TestAssetEntity.ZIP.equals(e.getKind())
                    || !TestAssetEntity.APPROVED.equals(e.getReviewStatus())) {
                return Optional.empty();
            }
            Path src = assetDir(e.getProjectId(), e.getId()).resolve("source").resolve(safeFilename(e.getSource()));
            if (!Files.isRegularFile(src)) {
                log.warn("程序资产源包缺失: id={}, path={}", e.getId(), src);
                return Optional.empty();
            }
            try {
                byte[] bytes = Files.readAllBytes(src);
                String sha = EvidenceService.sha256(bytes);
                if (e.getSha256() != null && !e.getSha256().equalsIgnoreCase(sha)) {
                    log.warn("程序资产 sha256 不一致，拒绝下发: id={}, expected={}, actual={}",
                            e.getId(), e.getSha256(), sha);
                    return Optional.empty();
                }
                return Optional.of(new TestProgramBundleProvider.Bundle(
                        bytes, safeFilename(e.getSource()), sha));
            } catch (IOException ex) {
                log.warn("程序资产读取失败: id={}, err={}", e.getId(), ex.getMessage());
                return Optional.empty();
            }
        });
    }

    // ---------------- 程序包清单 ----------------

    /**
     * M1.5：显式 PROGRAM 必须带清单；未显式声明时含清单的 ZIP 自动识别为 PROGRAM，
     * 否则保持 DATA（M1 行为不变）。入口必须是包内普通文件且不含构建命令。
     */
    private void applyProgramManifest(TestAssetEntity saved, Path filesDir, String requestedRole) {
        saved.setAssetRole(TestAssetEntity.ROLE_DATA);
        if (TestAssetEntity.ROLE_DATA.equals(requestedRole)) {
            return;
        }
        Path manifestFile = filesDir.resolve(ProgramContract.MANIFEST_NAME);
        boolean hasManifest = Files.isRegularFile(manifestFile);
        if (!hasManifest) {
            if (TestAssetEntity.ROLE_PROGRAM.equals(requestedRole)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "程序包缺少 " + ProgramContract.MANIFEST_NAME + " 清单");
            }
            return;
        }
        String raw;
        ProgramManifest manifest;
        try {
            raw = Files.readString(manifestFile, java.nio.charset.StandardCharsets.UTF_8);
            manifest = ProgramManifest.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "程序清单不合法: " + ex.getMessage());
        } catch (IOException ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "读取程序清单失败: " + ex.getMessage());
        }
        Path entry = filesDir.resolve(manifest.entrypoint()).normalize();
        if (!entry.startsWith(filesDir) || !Files.isRegularFile(entry)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "程序入口不存在或逃逸包根: " + manifest.entrypoint());
        }
        try {
            String script = Files.readString(entry, java.nio.charset.StandardCharsets.UTF_8);
            ProgramManifest.rejectBuildCommands(manifest.entrypoint(), script);
        } catch (IOException ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "读取程序入口失败: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
        saved.setAssetRole(TestAssetEntity.ROLE_PROGRAM);
        saved.setRuntime(manifest.runtime());
        saved.setEntrypoint(manifest.entrypoint());
        saved.setTargetOs(manifest.targetOs());
        saved.setTargetArch(manifest.targetArch());
        saved.setManifestJson(raw);
    }

    // ---------------- 文件系统 ----------------

    private int extractZip(byte[] bytes, Path target) throws IOException {
        Files.createDirectories(target);
        int count = 0;
        long total = 0;
        try (InputStream in = new ByteArrayInputStream(bytes); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                count++;
                if (count > props.getMaxFiles()) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "ZIP 文件数超限（>" + props.getMaxFiles() + "）");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("..") || name.contains(":")) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "ZIP 含非法路径: " + name);
                }
                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "ZIP 路径穿越: " + name);
                }
                byte[] content = readBounded(zin, props.getMaxFileBytes());
                total += content.length;
                if (total > props.getMaxUncompressedBytes()) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "ZIP 解包总量超限（>" + props.getMaxUncompressedBytes() + "）");
                }
                Files.createDirectories(out.getParent());
                Files.write(out, content);
            }
        }
        return count;
    }

    private byte[] readBounded(InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "ZIP 单文件超限（>" + max + " 字节）");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private Path root() {
        String dir = props.getRootDir();
        Path base = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("user.dir"), "data", "test-assets")
                : Path.of(dir);
        return base.toAbsolutePath().normalize();
    }

    private Path assetDir(String projectId, Long assetId) {
        return root().resolve(safeSegment(projectId)).resolve(String.valueOf(assetId));
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("资产文件删除失败: {}", p);
                }
            });
        }
    }

    private String safeSegment(String s) {
        return s == null ? "_" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String safeFilename(String name) {
        String n = name == null || name.isBlank() ? "asset.bin" : name.strip();
        n = n.replace('\\', '_').replace('/', '_');
        if (n.contains("..") || n.length() > 180) {
            n = "asset.bin";
        }
        return n;
    }

    private String requireText(String s, String label) {
        if (s == null || s.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, label + "必填");
        }
        return s.strip();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
