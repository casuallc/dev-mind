package com.devmind.agent.service;

import com.devmind.agent.dto.AgentHelloMeta;
import com.devmind.agent.dto.AgentNodeView;
import com.devmind.agent.dto.CreateAgentNodeRequest;
import com.devmind.agent.dto.IssuedNodeView;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.repo.AgentNodeRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
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
        return repo.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(AgentNodeView::from).toList();
    }

    public AgentNodeView setDisabled(Long id, boolean disabled) {
        AgentNodeEntity e = require(id);
        // 启用后状态回到 OFFLINE，等 runner 重连翻 ONLINE
        e.setStatus(disabled ? STATUS_DISABLED : STATUS_OFFLINE);
        if (disabled) {
            e.setDefault(false); // 禁用即摘除平台默认标记，避免会话调度落到不可用节点
        }
        return AgentNodeView.from(repo.save(e));
    }

    /**
     * 设为/取消平台默认执行节点（FR-03）：设定时清除其他节点标记，全平台至多一个。
     * 会话与项目均未指定节点时调度到平台默认节点；无平台默认则回落本机。
     */
    public AgentNodeView setDefault(Long id, boolean isDefault) {
        AgentNodeEntity e = require(id);
        if (isDefault && STATUS_DISABLED.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "节点已禁用，不能设为默认: " + e.getName());
        }
        if (isDefault) {
            for (AgentNodeEntity other : repo.findByIsDefaultTrue()) {
                if (!other.getId().equals(id)) {
                    other.setDefault(false);
                    repo.save(other);
                }
            }
        }
        e.setDefault(isDefault);
        return AgentNodeView.from(repo.save(e));
    }

    /** 平台默认执行节点 id（无默认 = null）：会话调度优先级的最终远程兜底。 */
    public String defaultNodeId() {
        return repo.findByIsDefaultTrue().stream().findFirst()
                .map(n -> String.valueOf(n.getId())).orElse(null);
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

    /** 连接建立：ONLINE + 心跳时间戳 + 远端地址（IP:端口）。 */
    public void markOnline(Long id, String remoteAddr) {
        repo.findById(id).ifPresent(e -> {
            e.setStatus(STATUS_ONLINE);
            e.setLastHeartbeatAt(Instant.now());
            if (remoteAddr != null && !remoteAddr.isBlank()) {
                e.setRemoteAddr(remoteAddr);
            }
            repo.save(e);
        });
    }

    /** hello 帧：更新节点元数据（meta 各字段可空 = 旧 runner 未上报，不动旧值）。 */
    public void updateMeta(Long id, AgentHelloMeta meta) {
        repo.findById(id).ifPresent(e -> {
            if (meta.os() != null && !meta.os().isBlank()) {
                e.setOs(meta.os());
            }
            if (meta.capabilities() != null) {
                e.setCapabilities(meta.capabilities());
            }
            if (meta.runnerVersion() != null) {
                e.setRunnerVersion(meta.runnerVersion());
            }
            if (meta.workspaceBytes() != null) {
                e.setWorkspaceBytes(meta.workspaceBytes());
            }
            if (meta.protocolVersion() != null) {
                e.setProtocolVersion(meta.protocolVersion());
            }
            // FR-07 D4：runner 配置 labels 非空时 hello 才带 → 带上即覆盖服务端编辑值；
            // runner 未配置（hello 无此字段 = null）不动服务端编辑值
            if (meta.labels() != null) {
                e.setLabels(meta.labels());
            }
            if (meta.toolchainJson() != null) {
                e.setToolchain(meta.toolchainJson());
            }
            e.setLastHeartbeatAt(Instant.now());
            repo.save(e);
        });
    }

    /** FR-05：心跳帧携带的工作区占用落库（心跳不带其他元数据）。 */
    public void updateWorkspaceBytes(Long id, Long workspaceBytes) {
        if (workspaceBytes == null) {
            return;
        }
        repo.findById(id).ifPresent(e -> {
            e.setWorkspaceBytes(workspaceBytes);
            repo.save(e);
        });
    }

    /** FR-07：服务端编辑节点标签（CSV；null/空白 = 清空）。runner 配置非空 labels 时 hello 会覆盖此值。 */
    public AgentNodeView updateLabels(Long id, String labels) {
        AgentNodeEntity e = require(id);
        String v = labels == null ? null : labels.strip();
        e.setLabels(v == null || v.isEmpty() ? null : v);
        return AgentNodeView.from(repo.save(e));
    }

    /** FR-07 标签调度：节点标签是否覆盖全部 required（required 空 = 恒 true；节点不存在 = false）。 */
    public boolean nodeMatchesLabels(String nodeId, List<String> requiredLabels) {
        if (requiredLabels == null || requiredLabels.isEmpty()) {
            return true;
        }
        try {
            return repo.findById(Long.parseLong(nodeId))
                    .map(e -> labelsCover(e.getLabels(), requiredLabels))
                    .orElse(false);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** FR-07 标签调度：在线且标签覆盖全部 required 的节点（按 id 升序，取第一个即可）。 */
    public List<AgentNodeEntity> onlineMatching(List<String> requiredLabels) {
        return repo.findAll().stream()
                .filter(e -> STATUS_ONLINE.equals(e.getStatus()))
                .filter(e -> labelsCover(e.getLabels(), requiredLabels))
                .sorted(java.util.Comparator.comparing(AgentNodeEntity::getId))
                .toList();
    }

    /** CSV 标签覆盖判定：required 中每个标签都在节点 CSV 标签集合内（大小写敏感，去空白）。 */
    static boolean labelsCover(String nodeLabelsCsv, List<String> requiredLabels) {
        if (requiredLabels == null || requiredLabels.isEmpty()) {
            return true;
        }
        if (nodeLabelsCsv == null || nodeLabelsCsv.isBlank()) {
            return false;
        }
        java.util.Set<String> have = java.util.Arrays.stream(nodeLabelsCsv.split(","))
                .map(String::strip).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        return requiredLabels.stream().map(String::strip).filter(s -> !s.isEmpty()).allMatch(have::contains);
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
