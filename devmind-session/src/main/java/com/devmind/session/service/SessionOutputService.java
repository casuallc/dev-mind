package com.devmind.session.service;

import com.devmind.common.agent.SessionOutputSink;
import com.devmind.common.attachment.AttachmentStore;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionOutputEntity;
import com.devmind.session.repo.SessionOutputRepository;
import com.devmind.session.repo.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话产出存储（CAP-37 FR-01）：{@link SessionOutputSink} 实现，供 devmind-agent 上传端点
 * 经 ObjectProvider 探测调用；flow 引擎直接注入本服务按文件名读产出（不再依赖 worktree 路径）。
 * 同 sessionId+fileName 覆盖旧值（重发/重新分析幂等）。
 *
 * <p>CAP-83：二进制产出（xlsx 等）经 {@link #storeBinary} 进附件管理（attachments 表 +
 * 磁盘，归属 = 会话 createdBy 回退 workspaceOwner，PRIVATE + tags=session-output），
 * session_outputs 行只存 attachment_id 引用；文本 ↔ 附件同名互覆时清另一列并删旧附件。</p>
 */
@Service
public class SessionOutputService implements SessionOutputSink {

    private static final Logger log = LoggerFactory.getLogger(SessionOutputService.class);
    /** CAP-83 产出附件来源标签（附件管理页可筛） */
    static final String TAG_SESSION_OUTPUT = "session-output";

    private final SessionOutputRepository repo;
    private final SessionRepository sessionRepo;
    private final ObjectProvider<AttachmentStore> attachmentStore;

    public SessionOutputService(SessionOutputRepository repo, SessionRepository sessionRepo,
                                ObjectProvider<AttachmentStore> attachmentStore) {
        this.repo = repo;
        this.sessionRepo = sessionRepo;
        this.attachmentStore = attachmentStore;
    }

    /** 逐文件 upsert（controller 同步链路，save 自身事务即时提交，不加类级事务）。
     *  文本覆盖旧附件行时删旧附件（CAP-83 同名互覆语义）。 */
    @Override
    public void store(String sessionId, List<OutputFile> files) {
        for (OutputFile f : files) {
            SessionOutputEntity e = repo.findBySessionIdAndFileName(sessionId, f.fileName())
                    .orElseGet(() -> {
                        SessionOutputEntity n = new SessionOutputEntity();
                        n.setSessionId(sessionId);
                        n.setFileName(f.fileName());
                        return n;
                    });
            String oldAttachment = e.getAttachmentId();
            e.setContent(f.content());
            e.setAttachmentId(null);
            e.setCreatedAt(Instant.now());
            repo.save(e);
            deleteOldAttachment(oldAttachment);
        }
        log.info("会话产出已存储: session={} files={}", sessionId,
                files.stream().map(OutputFile::fileName).toList());
    }

    /**
     * CAP-83 二进制产出：存附件（PRIVATE + session-output 标签，归属 = 会话创建者）→
     * 引用行 upsert → 删旧附件。返回 attachmentId。
     */
    @Override
    public String storeBinary(String sessionId, String fileName, InputStream in,
                              long declaredSize, String contentType) {
        AttachmentStore attachments = attachmentStore.getIfAvailable();
        if (attachments == null) {
            throw new DevMindException(ErrorCode.INTERNAL, "附件存储服务未装配");
        }
        SessionEntity session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        String owner = session.getCreatedBy() != null && !session.getCreatedBy().isBlank()
                ? session.getCreatedBy() : session.getWorkspaceOwner();
        String attachmentId = attachments.store(in, declaredSize, fileName, contentType,
                AttachmentStore.SCOPE_PRIVATE, owner, TAG_SESSION_OUTPUT,
                "会话产出 " + sessionId + " / " + fileName);

        SessionOutputEntity e = repo.findBySessionIdAndFileName(sessionId, fileName)
                .orElseGet(() -> {
                    SessionOutputEntity n = new SessionOutputEntity();
                    n.setSessionId(sessionId);
                    n.setFileName(fileName);
                    return n;
                });
        String oldAttachment = e.getAttachmentId();
        e.setAttachmentId(attachmentId);
        e.setContent(null);
        e.setCreatedAt(Instant.now());
        repo.save(e);
        if (oldAttachment != null && !oldAttachment.equals(attachmentId)) {
            deleteOldAttachment(oldAttachment);
        }
        log.info("二进制会话产出已存储: session={} file={} attachment={}", sessionId, fileName, attachmentId);
        return attachmentId;
    }

    /** 覆盖删旧附件：失败仅 warn（引用已指向新附件，旧附件残留可由管理页/清理任务兜底）。 */
    private void deleteOldAttachment(String oldAttachmentId) {
        if (oldAttachmentId == null || oldAttachmentId.isBlank()) {
            return;
        }
        AttachmentStore attachments = attachmentStore.getIfAvailable();
        if (attachments == null) {
            return;
        }
        try {
            attachments.delete(oldAttachmentId);
        } catch (Exception e) {
            log.warn("旧产出附件删除失败（引用已更新）: attachment={} err={}", oldAttachmentId, e.getMessage());
        }
    }

    /** 按会话+文件名读产出文本内容（flow 引擎读取入口）；无记录或附件产出（CAP-83）返回 empty。 */
    public Optional<String> findContent(String sessionId, String fileName) {
        return repo.findBySessionIdAndFileName(sessionId, fileName).map(SessionOutputEntity::getContent);
    }

    /** 按会话+文件名读产出实体（CAP-83 附件判定/推送兜底闸用）；无记录返回 empty。 */
    public Optional<SessionOutputEntity> find(String sessionId, String fileName) {
        return repo.findBySessionIdAndFileName(sessionId, fileName);
    }

    /** 列出会话全部已回传产出（CAP-39 FR-02  outputs 列表端点）。 */
    public List<SessionOutputEntity> list(String sessionId) {
        return repo.findBySessionId(sessionId);
    }
}
