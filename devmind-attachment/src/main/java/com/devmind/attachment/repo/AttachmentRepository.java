package com.devmind.attachment.repo;

import com.devmind.attachment.model.AttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** CAP-32 附件元数据仓库。 */
public interface AttachmentRepository extends JpaRepository<AttachmentEntity, String> {

    /** 普通用户可见集合：本人全部 + 他人 SHARED。 */
    List<AttachmentEntity> findByUploadedByOrScopeOrderByCreatedAtDesc(String uploadedBy, String scope);
}
