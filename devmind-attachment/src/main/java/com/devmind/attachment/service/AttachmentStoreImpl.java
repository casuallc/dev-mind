package com.devmind.attachment.service;

import com.devmind.common.attachment.AttachmentStore;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * CAP-83 {@link AttachmentStore} 实现：系统链路（无 SecurityContext）的附件写路径，
 * 委托 {@link AttachmentService} 的系统入口。独立于 AttachmentService 直接实现 SPI，
 * 避免与用户态 delete（owner/ADMIN 校验）签名冲突，系统写/删路径显式可辨。
 */
@Component
public class AttachmentStoreImpl implements AttachmentStore {

    private final AttachmentService service;

    public AttachmentStoreImpl(AttachmentService service) {
        this.service = service;
    }

    @Override
    public String store(InputStream in, long size, String originalName, String contentType,
                        String scope, String uploadedBy, String tags, String description) {
        return service.storeForSystem(in, size, originalName, contentType, scope,
                uploadedBy, tags, description);
    }

    @Override
    public void delete(String attachmentId) {
        service.deleteForSystem(attachmentId);
    }
}
