package com.devmind.agent.service;

import com.devmind.agent.dto.AgentNodeView;
import com.devmind.agent.dto.CreateAgentNodeRequest;
import com.devmind.agent.dto.IssuedNodeView;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.repo.AgentNodeRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * CAP-21 节点管理：注册 token 签发（只显一次，库存 SHA-256）、启停删、在线状态与心跳落库。
 * 连接生命周期（WS 进出）由 AgentConnectionRegistry 回调本类。
 */
@Service
public class AgentNodeService {

    private static final Logger log = LoggerFactory.getLogger(AgentNodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_OFFLINE = "OFFLINE";
    public static final String STATUS_DISABLED = "DISABLED";

    private final AgentNodeRepository repo;

    public AgentNodeService(AgentNodeRepository repo) {
        this.repo = repo;
    }

    /** 创建节点并签发注册 token（明文仅此一次）。 */
    public IssuedNodeView create(CreateAgentNodeRequest req) {
        if (repo.existsByName(req.name().strip())) {
            throw new DevMindException(ErrorCode.CONFLICT, "节点名已存在: " + req.name());
        }
        AgentNodeEntity e = new AgentNodeEntity();
        e.setName(req.name().strip());
        e.setLabels(req.labels());
        e.setStatus(STATUS_OFFLINE);
        e.setCreatedAt(Instant.now());
        String token = generateToken();
        e.setTokenHash(hash(token));
        return new IssuedNodeView(AgentNodeView.from(repo.save(e)), token);
    }

    public List<AgentNodeView> list() {
        return repo.findAll().stream().map(AgentNodeView::from).toList();
    }

    public AgentNodeView setDisabled(Long id, boolean disabled) {
        AgentNodeEntity e = require(id);
        // 启用后状态回到 OFFLINE，等 runner 重连翻 ONLINE
        e.setStatus(disabled ? STATUS_DISABLED : STATUS_OFFLINE);
        return AgentNodeView.from(repo.save(e));
    }

    public void delete(Long id) {
        repo.delete(require(id));
    }

    /** runner 接入认证：token 解析节点；DISABLED 拒绝。 */
    public Optional<AgentNodeEntity> resolveByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return repo.findByTokenHash(hash(token.strip()))
                .filter(e -> !STATUS_DISABLED.equals(e.getStatus()));
    }

    public AgentNodeEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "节点不存在: " + id));
    }

    /** 连接建立：ONLINE + 心跳时间戳。 */
    public void markOnline(Long id) {
        repo.findById(id).ifPresent(e -> {
            e.setStatus(STATUS_ONLINE);
            e.setLastHeartbeatAt(Instant.now());
            repo.save(e);
        });
    }

    /** hello 帧：更新节点元数据（os/capabilities/runner 版本）。 */
    public void updateMeta(Long id, String os, String capabilities, String runnerVersion) {
        repo.findById(id).ifPresent(e -> {
            if (os != null && !os.isBlank()) {
                e.setOs(os);
            }
            if (capabilities != null) {
                e.setCapabilities(capabilities);
            }
            if (runnerVersion != null) {
                e.setRunnerVersion(runnerVersion);
            }
            e.setLastHeartbeatAt(Instant.now());
            repo.save(e);
        });
    }

    public void touchHeartbeat(Long id) {
        repo.findById(id).ifPresent(e -> {
            e.setLastHeartbeatAt(Instant.now());
            repo.save(e);
        });
    }

    public void markOffline(Long id) {
        repo.findById(id).ifPresent(e -> {
            if (!STATUS_DISABLED.equals(e.getStatus())) {
                e.setStatus(STATUS_OFFLINE);
                repo.save(e);
            }
        });
    }

    private static String generateToken() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return "dmag_" + HexFormat.of().formatHex(buf);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
