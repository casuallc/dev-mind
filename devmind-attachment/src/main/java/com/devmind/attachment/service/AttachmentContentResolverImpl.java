package com.devmind.attachment.service;

import com.devmind.attachment.model.AttachmentEntity;
import com.devmind.attachment.repo.AttachmentRepository;
import com.devmind.common.attachment.AttachmentContentResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * CAP-32 {@link AttachmentContentResolver} 实现：供 chat 等运行时把 attachmentId 解析为
 * 下发 claude 的图片素材。仅图片类附件返回字节（非图片 empty）；权限由调用方负责
 * （chat 内已鉴权会话归属，附件本体可见性在上传/引用时判定）。
 */
@Component
public class AttachmentContentResolverImpl implements AttachmentContentResolver {

    private static final Logger log = LoggerFactory.getLogger(AttachmentContentResolverImpl.class);

    private final AttachmentRepository repo;
    private final AttachmentService attachmentService;

    public AttachmentContentResolverImpl(AttachmentRepository repo, AttachmentService attachmentService) {
        this.repo = repo;
        this.attachmentService = attachmentService;
    }

    @Override
    public Optional<ResolvedAttachment> resolve(String attachmentId) {
        return repo.findById(attachmentId)
                .filter(AttachmentEntity::isImage)
                .flatMap(this::readBytes);
    }

    /** CAP-40：不限 mime（需求附件投送：pdf/text 等一并给 agent）；盘文件读取失败同样 empty。 */
    @Override
    public Optional<ResolvedAttachment> resolveAny(String attachmentId) {
        return repo.findById(attachmentId).flatMap(this::readBytes);
    }

    private Optional<ResolvedAttachment> readBytes(AttachmentEntity ent) {
        Path path = attachmentService.rootDir().resolve(ent.getStoragePath()).normalize();
        try {
            return Optional.of(new ResolvedAttachment(ent.getId(), ent.getContentType(),
                    Files.readAllBytes(path)));
        } catch (IOException e) {
            log.warn("附件盘文件读取失败: id={} path={} err={}", ent.getId(), path, e.getMessage());
            return Optional.empty();
        }
    }
}
