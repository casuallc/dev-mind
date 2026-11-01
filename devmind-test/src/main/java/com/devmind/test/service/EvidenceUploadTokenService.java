package com.devmind.test.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.test.config.TestEvidenceProperties;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CAP-104 FR-24 一次性证据上传 token：绑定运行/结果/节点/类型，短有效期，单次使用。
 * 仅内存保存，服务重启后失效；token 不写日志、不落证据。
 */
@Service
public class EvidenceUploadTokenService {

    public record TokenInfo(String projectId, Long runId, Long resultId, String nodeId, String kind,
                            Instant expiresAt) {
    }

    public record Issued(String token, Instant expiresAt, long maxBytes) {
    }

    private final TestEvidenceProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    public EvidenceUploadTokenService(TestEvidenceProperties props) {
        this.props = props;
    }

    public Issued issue(String projectId, Long runId, Long resultId, String nodeId, String kind) {
        purgeExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        long ttl = Math.max(60, props.getUploadTokenTtlSeconds());
        Instant expiresAt = Instant.now().plusSeconds(ttl);
        tokens.put(token, new Entry(new TokenInfo(projectId, runId, resultId, nodeId,
                kind == null || kind.isBlank() ? "ATTACHMENT" : kind.strip().toUpperCase(), expiresAt)));
        return new Issued(token, expiresAt, props.getMaxUploadBytes());
    }

    /** 消费 token：运行必须匹配、未过期、未使用。 */
    public TokenInfo consume(String token, Long runId) {
        if (token == null || token.isBlank()) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "缺少证据上传 token");
        }
        Entry entry = tokens.get(token.strip());
        if (entry == null) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "证据上传 token 无效或已使用");
        }
        if (entry.info.expiresAt().isBefore(Instant.now())) {
            tokens.remove(token.strip());
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "证据上传 token 已过期");
        }
        if (!entry.info.runId().equals(runId)) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "证据上传 token 不属于该运行");
        }
        if (!entry.used.compareAndSet(false, true)) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "证据上传 token 已使用");
        }
        tokens.remove(token.strip());
        return entry.info;
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(e -> e.getValue().info.expiresAt().isBefore(now));
    }

    private static final class Entry {
        final TokenInfo info;
        final AtomicBoolean used = new AtomicBoolean(false);

        Entry(TokenInfo info) {
            this.info = info;
        }
    }
}
