package com.devmind.knowledge;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.KnowledgeVersionView;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeEntryVersionEntity;
import com.devmind.knowledge.repo.KnowledgeEntryVersionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * CAP-84 FR-06 文档版本落库：正文快照 + 保留最近 N 版（默认 20）。
 * 调用方保证 entry.contentVersion 已递增。
 */
@Service
public class KnowledgeVersionService {

    private final KnowledgeEntryVersionRepository versionRepo;
    private final KnowledgeProperties props;

    public KnowledgeVersionService(KnowledgeEntryVersionRepository versionRepo, KnowledgeProperties props) {
        this.versionRepo = versionRepo;
        this.props = props;
    }

    public void record(KnowledgeEntryEntity entry, String actor, String note) {
        KnowledgeEntryVersionEntity v = new KnowledgeEntryVersionEntity();
        v.setEntryId(entry.getId());
        v.setContentVersion(entry.getContentVersion());
        v.setContentMd(entry.getContentMd());
        v.setContentHash(entry.getContentHash());
        v.setSourceUri(entry.getSourceUri());
        v.setChangeNote(note == null ? "" : truncate(note, 500));
        v.setCreatedBy(actor);
        v.setCreatedAt(Instant.now());
        versionRepo.save(v);
        prune(entry.getId());
    }

    public List<KnowledgeEntryVersionEntity> list(Long entryId) {
        return versionRepo.findByEntryIdOrderByContentVersionDesc(entryId);
    }

    public List<KnowledgeVersionView> listViews(Long entryId) {
        return list(entryId).stream().map(KnowledgeVersionService::view).toList();
    }

    public KnowledgeVersionView.Detail get(Long entryId, int contentVersion) {
        KnowledgeEntryVersionEntity v = versionRepo.findByEntryIdAndContentVersion(entryId, contentVersion)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "版本不存在: entry=" + entryId + " version=" + contentVersion));
        return new KnowledgeVersionView.Detail(v.getId(), v.getEntryId(), v.getContentVersion(),
                v.getContentHash(), v.getSourceUri(), v.getChangeNote(), v.getCreatedBy(),
                v.getCreatedAt(), v.getContentMd());
    }

    private static KnowledgeVersionView view(KnowledgeEntryVersionEntity v) {
        return new KnowledgeVersionView(v.getId(), v.getEntryId(), v.getContentVersion(),
                v.getContentHash(), v.getSourceUri(), v.getChangeNote(), v.getCreatedBy(), v.getCreatedAt());
    }

    public void deleteAll(Long entryId) {
        versionRepo.deleteByEntryId(entryId);
    }

    private void prune(Long entryId) {
        int keep = Math.max(1, props.getUpload().getVersionRetention());
        List<KnowledgeEntryVersionEntity> versions = versionRepo.findByEntryIdOrderByContentVersionDesc(entryId);
        for (int i = keep; i < versions.size(); i++) {
            versionRepo.delete(versions.get(i));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
