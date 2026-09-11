package com.devmind.agent.service;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.RunnerPackageView;
import com.devmind.agent.model.RunnerPackageEntity;
import com.devmind.agent.repo.RunnerPackageRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CAP-21 FR-09 runner 包托管：全局单份（固定 id=1 覆盖式 upsert），jar 落盘
 * {@code data/agent-runner/runner.jar}（原子 move 替换）。上传时强校验包内
 * runner-version.txt（版本来源）与 SelfUpdater.class（无自升级能力的旧包会把节点升死，拒收），
 * 并按构建时间戳防倒退：旧构建覆盖新构建 → 409（force=true 跳过，用于确认过的降级）。
 */
@Service
public class RunnerPackageService {

    private static final Logger log = LoggerFactory.getLogger(RunnerPackageService.class);

    /** 单行表固定主键 */
    public static final long PKG_ID = 1L;
    static final String JAR_NAME = "runner.jar";
    static final String VERSION_ENTRY = "runner-version.txt";
    static final String SELF_UPDATER_ENTRY = "com/devmind/agent/runner/SelfUpdater.class";

    /** 版本串尾部构建时间戳（pom 注入的 runner.version = ${project.version}-b<yyyyMMdd.HHmm>） */
    private static final Pattern BUILD_STAMP = Pattern.compile("-b(\\d{8}\\.\\d{4})$");

    private final RunnerPackageRepository repo;
    private final AgentProperties props;

    public RunnerPackageService(RunnerPackageRepository repo, AgentProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /**
     * 上传替换当前包：校验 → 版本防倒退 → 算 sha256 → 原子落盘 → upsert 元数据。
     * 防倒退：新包构建时间戳早于当前托管包时 409 拒收（旧构建误传会覆盖新构建）；
     * force=true 跳过该校验，用于确认过的降级回滚。任一侧版本串无构建时间戳
     * （历史 dev 构建）时不比对、直接放行。
     */
    public synchronized RunnerPackageView upload(MultipartFile file, String uploadedBy,
                                                 boolean force) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "上传文件为空");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".jar")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "仅支持 .jar 文件: " + filename);
        }
        byte[] bytes = file.getBytes();
        String version = inspectJar(bytes);
        rejectIfOlderBuild(version, force);

        Path dir = packageDir();
        Path tmp = dir.resolve(JAR_NAME + ".tmp");
        Path target = dir.resolve(JAR_NAME);
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 文件系统不支持 ATOMIC_MOVE 时降级普通替换
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        RunnerPackageEntity e = repo.findById(PKG_ID).orElseGet(RunnerPackageEntity::new);
        e.setId(PKG_ID);
        e.setVersion(version);
        e.setSha256(sha256Hex(bytes));
        e.setSizeBytes(bytes.length);
        e.setOriginalFilename(filename);
        e.setUploadedAt(Instant.now());
        e.setUploadedBy(uploadedBy);
        log.info("runner 包已更新: version={} size={} by={}", version, bytes.length, uploadedBy);
        return RunnerPackageView.from(repo.save(e));
    }

    /** 当前包元数据；未上传 → 404。 */
    public RunnerPackageView current() {
        return RunnerPackageView.from(currentOpt()
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "尚未上传 runner 包")));
    }

    public Optional<RunnerPackageEntity> currentOpt() {
        return repo.findById(PKG_ID)
                .filter(e -> Files.exists(packageDirQuietly().resolve(JAR_NAME)));
    }

    /** 下载流（FileSystemResource，由 MVC 写出）；文件缺失 → 404。 */
    public Resource openForDownload() {
        Path jar = packageDirQuietly().resolve(JAR_NAME);
        if (!Files.exists(jar)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "runner 包文件缺失，请重新上传");
        }
        return new FileSystemResource(jar);
    }

    private Path packageDir() {
        try {
            return Files.createDirectories(Path.of(props.getRunnerPackageDir()));
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "创建 runner 包目录失败: " + e.getMessage());
        }
    }

    private Path packageDirQuietly() {
        return Path.of(props.getRunnerPackageDir());
    }

    /** 版本防倒退：新包构建时间戳早于当前托管包 → 409（force 跳过；无时间戳可比对时放行）。 */
    private void rejectIfOlderBuild(String newVersion, boolean force) {
        if (force) {
            return;
        }
        Optional<RunnerPackageEntity> current = currentOpt();
        if (current.isEmpty()) {
            return;
        }
        String curVersion = current.get().getVersion();
        String curStamp = buildStamp(curVersion);
        String newStamp = buildStamp(newVersion);
        if (curStamp == null || newStamp == null) {
            log.warn("runner 包版本无构建时间戳，跳过防倒退比对: current={} new={}", curVersion, newVersion);
            return;
        }
        if (newStamp.compareTo(curStamp) < 0) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "上传包 " + newVersion + " 早于当前托管包 " + curVersion + "，已拒收（旧构建会覆盖新构建）");
        }
    }

    /** 提取版本串尾部的构建时间戳（-b yyyyMMdd.HHmm），无 → null。 */
    private static String buildStamp(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = BUILD_STAMP.matcher(version.strip());
        return m.find() ? m.group(1) : null;
    }

    /** 遍历 jar 条目：提取版本并强校验自升级能力。 */
    private static String inspectJar(byte[] bytes) {
        String version = null;
        boolean hasSelfUpdater = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (VERSION_ENTRY.equals(entry.getName())) {
                    version = new String(zip.readAllBytes(), StandardCharsets.UTF_8).strip();
                } else if (SELF_UPDATER_ENTRY.equals(entry.getName())) {
                    hasSelfUpdater = true;
                }
            }
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不是合法的 jar 包: " + e.getMessage());
        }
        if (version == null || version.isBlank() || version.startsWith("@")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "包内缺少有效的 runner-version.txt（疑似未过滤占位符），非 devmind-agent-runner 构建产物");
        }
        if (!hasSelfUpdater) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "包内缺少 SelfUpdater（无自升级能力的老版本），请用最新构建的 devmind-agent-runner.jar");
        }
        return version;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
