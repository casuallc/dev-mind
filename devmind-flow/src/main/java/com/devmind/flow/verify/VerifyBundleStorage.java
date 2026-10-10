package com.devmind.flow.verify;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.verify.VerifyBundleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * CAP-73 FR-03 产物包暂存（{@code devmind.verify.bundle-dir}，默认 data/verify-bundles/<runId>/bundle.zip）：
 * 构建节点上传 → 环境节点拉取 → 成功即删；失败保留 {@code bundle-retention-days} 天定时清理。
 *
 * <p>run ↔ 节点绑定校验在此强制（上传=构建节点、下载=环境节点），与 CAP-56 拉包端点
 * 同一安全口径。</p>
 */
@Component
public class VerifyBundleStorage implements VerifyBundleStore {

    private static final Logger log = LoggerFactory.getLogger(VerifyBundleStorage.class);
    private static final String BUNDLE_NAME = "bundle.zip";

    private final VerifyRunRepository runRepo;
    private final VerifyProperties props;

    public VerifyBundleStorage(VerifyRunRepository runRepo, VerifyProperties props) {
        this.runRepo = runRepo;
        this.props = props;
    }

    @Override
    public void storeBundle(long runId, String nodeId, InputStream content) {
        VerifyRunEntity run = requireRun(runId);
        if (!nodeId.equals(run.getBuildNodeId())) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "该节点不是 run " + runId + " 的构建节点");
        }
        if (!VerifyRunEntity.STATUS_RUNNING.equals(run.getStatus())
                || !VerifyRunEntity.STAGE_BUILDING.equals(run.getStage())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "run " + runId + " 不在 BUILDING 阶段，拒绝接收产物包（status=" + run.getStatus() + "）");
        }
        long maxBytes = props.getBundleMaxMb() * 1024L * 1024L;
        Path dir = dirOf(runId);
        Path tmp = dir.resolve(BUNDLE_NAME + ".tmp");
        Path target = dir.resolve(BUNDLE_NAME);
        try {
            Files.createDirectories(dir);
            long written = 0;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = content.read(buf)) >= 0) {
                    written += n;
                    if (written > maxBytes) {
                        throw new DevMindException(ErrorCode.CONFLICT,
                                "VERIFY_BUNDLE_TOO_LARGE: 产物包超过上限 " + props.getBundleMaxMb()
                                        + "MB，请精简构建配置 output_dir（只保留部署必需文件）");
                    }
                    out.write(buf, 0, n);
                }
            } finally {
                try {
                    content.close();
                } catch (IOException ignored) {
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("CAP-73 产物包已暂存: run={} node={} size={}KB", runId, nodeId, written / 1024);
        } catch (DevMindException e) {
            deleteQuietly(tmp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new DevMindException(ErrorCode.INTERNAL, "产物包落盘失败: " + e.getMessage());
        }
    }

    @Override
    public InputStream openBundle(long runId, String nodeId) {
        VerifyRunEntity run = requireRun(runId);
        if (!nodeId.equals(run.getEnvNodeId())) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "该节点不是 run " + runId + " 的环境节点");
        }
        Path bundle = dirOf(runId).resolve(BUNDLE_NAME);
        if (!Files.isRegularFile(bundle)) {
            throw new DevMindException(ErrorCode.NOT_FOUND,
                    "run " + runId + " 的产物包不存在（构建未上传或已清理）");
        }
        try {
            return Files.newInputStream(bundle);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "产物包读取失败: " + e.getMessage());
        }
    }

    /** 编排器终态回调：成功即删，失败保留待巡检清理 */
    public void cleanupOnTerminal(VerifyRunEntity run) {
        if (run == null || run.getId() == null) {
            return;
        }
        if (VerifyRunEntity.STATUS_SUCCESS.equals(run.getStatus())) {
            deleteDirQuietly(dirOf(run.getId()));
        }
    }

    /** 过期包巡检（失败保留 bundleRetentionDays 天；僵尸目录按 mtime 兜底） */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void sweepExpired() {
        Path root = Path.of(props.getBundleDir());
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minusSeconds(props.getBundleRetentionDays() * 86400L);
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path bundle = dir.resolve(BUNDLE_NAME);
                try {
                    if (Files.isRegularFile(bundle)
                            && Files.getLastModifiedTime(bundle).toInstant().isBefore(cutoff)) {
                        deleteDirQuietly(dir);
                        log.info("CAP-73 过期产物包已清理: {}", dir);
                    }
                } catch (IOException e) {
                    log.warn("CAP-73 产物包巡检失败: {} err={}", dir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("CAP-73 产物包目录巡检失败: {}", e.getMessage());
        }
    }

    private VerifyRunEntity requireRun(long runId) {
        return runRepo.findById(runId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "verify run 不存在: " + runId));
    }

    private Path dirOf(long runId) {
        return Path.of(props.getBundleDir(), String.valueOf(runId));
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private void deleteDirQuietly(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("CAP-73 产物包目录删除失败: {} err={}", dir, e.getMessage());
        }
    }
}
