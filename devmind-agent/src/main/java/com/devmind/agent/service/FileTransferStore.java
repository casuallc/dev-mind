package com.devmind.agent.service;

import com.devmind.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAP-65 文件中转登记：大文件（≤100MB）不经 WS，runner 凭 transferId 走 HTTP 主动拉/推
 * （CAP-34 上下文包 / CAP-57 模型包同模式）。本类负责临时文件登记与生命周期：
 * 用后即删（REST 链路收口时 complete）+ 过期 GC（默认 10min）+ 节点断连作废其未完成传输。
 *
 * <p>临时文件落 {@code devmind.agent.file-transfer-dir}（默认 data/file-transfers），
 * 文件名即 transferId（UUID，无目录分隔符注入风险）。</p>
 */
@Component
public class FileTransferStore {

    private static final Logger log = LoggerFactory.getLogger(FileTransferStore.class);

    /** 单条中转登记。upload=true：浏览器→服务端已暂存，等 runner GET 拉取；
     * upload=false：等 runner POST 推送，size/sha256 由收流后 markReceived 回填。 */
    public record Transfer(String id, String nodeId, boolean upload, Path file, String fileName,
                           Long size, String sha256, Instant expiresAt) {
    }

    private final AgentProperties props;
    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private volatile Path dir;

    public FileTransferStore(AgentProperties props) {
        this.props = props;
    }

    /** 登记一条上传中转（浏览器字节已落到 file）。 */
    public Transfer registerUpload(String nodeId, String fileName, long size, String sha256) {
        String id = UUID.randomUUID().toString();
        Transfer t = new Transfer(id, nodeId, true, ensureDir().resolve(id), fileName,
                size, sha256, Instant.now().plusMillis(props.getFileTransferExpireMs()));
        transfers.put(id, t);
        return t;
    }

    /** 登记一条下载中转（等 runner POST 推字节到 file）。 */
    public Transfer registerDownload(String nodeId, String fileName) {
        String id = UUID.randomUUID().toString();
        Transfer t = new Transfer(id, nodeId, false, ensureDir().resolve(id), fileName,
                null, null, Instant.now().plusMillis(props.getFileTransferExpireMs()));
        transfers.put(id, t);
        return t;
    }

    public Optional<Transfer> get(String id) {
        return Optional.ofNullable(transfers.get(id));
    }

    /** 下载收流完成：回填实际大小与 sha256（供 REST 回浏览器时的 contentLength/校验留痕）。 */
    public void markReceived(String id, long size, String sha256) {
        transfers.computeIfPresent(id, (k, t) -> new Transfer(t.id(), t.nodeId(), t.upload(),
                t.file(), t.fileName(), size, sha256, t.expiresAt()));
    }

    /** 用后即删：移除登记并删临时文件（幂等）。 */
    public void complete(String id) {
        Transfer t = transfers.remove(id);
        if (t != null) {
            deleteQuietly(t.file());
        }
    }

    /** 节点断连：作废其全部未完成传输（ack 不会再来，REST 等待侧由 registry 断连清理收口）。 */
    public void onNodeDisconnect(String nodeId) {
        for (Map.Entry<String, Transfer> e : transfers.entrySet()) {
            if (e.getValue().nodeId().equals(nodeId) && transfers.remove(e.getKey(), e.getValue())) {
                deleteQuietly(e.getValue().file());
            }
        }
    }

    /** 过期 GC：每分钟扫一遍，清掉超时未收口的传输（异常中断的兜底）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void gc() {
        Instant now = Instant.now();
        for (Map.Entry<String, Transfer> e : transfers.entrySet()) {
            if (e.getValue().expiresAt().isBefore(now) && transfers.remove(e.getKey(), e.getValue())) {
                log.info("文件中转过期清理: transferId={} nodeId={}", e.getKey(), e.getValue().nodeId());
                deleteQuietly(e.getValue().file());
            }
        }
    }

    private Path ensureDir() {
        Path d = dir;
        if (d == null) {
            synchronized (this) {
                if (dir == null) {
                    try {
                        dir = Files.createDirectories(Path.of(props.getFileTransferDir()));
                    } catch (IOException e) {
                        throw new IllegalStateException("文件中转目录创建失败: " + props.getFileTransferDir(), e);
                    }
                }
                d = dir;
            }
        }
        return d;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            log.debug("中转临时文件删除失败（GC 兜底）: {} err={}", p, e.getMessage());
        }
    }
}
