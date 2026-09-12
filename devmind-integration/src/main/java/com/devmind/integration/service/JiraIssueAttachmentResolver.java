package com.devmind.integration.service;

import com.devmind.common.attachment.IssueAttachmentResolver;
import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.project.RequirementService;
import com.devmind.project.model.RequirementEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * CAP-40 {@link IssueAttachmentResolver} 实现：需求关联 Jira issue 的内嵌附件按文件名解析为
 * 字节（复用 CAP-19 FR-09 下载链：元数据定位 contentUrl + 集成凭据拉流）。非 Jira 来源需求/
 * 附件不存在/远程拉取失败一律 empty——附件投送是增强项，不阻断上下文装配。
 */
@Component
public class JiraIssueAttachmentResolver implements IssueAttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(JiraIssueAttachmentResolver.class);

    private final JiraIssueActionService actionService;
    private final RequirementService requirementService;
    private final ExternalLinkRepository linkRepo;

    public JiraIssueAttachmentResolver(JiraIssueActionService actionService,
                                       RequirementService requirementService,
                                       ExternalLinkRepository linkRepo) {
        this.actionService = actionService;
        this.requirementService = requirementService;
        this.linkRepo = linkRepo;
    }

    @Override
    public Optional<IssueAttachment> resolve(String requirementId, String filename) {
        try {
            RequirementEntity req = requirementService.requireById(requirementId);
            if (req == null || !RequirementEntity.SOURCE_JIRA.equals(req.getSource())) {
                return Optional.empty();
            }
            String issueKey = linkRepo.findByProjectIdAndInternalTypeAndInternalId(
                            req.getProjectId(), ExternalLinkEntity.INTERNAL_REQUIREMENT, requirementId)
                    .stream()
                    .filter(l -> ExternalLinkEntity.EXTERNAL_ISSUE.equals(l.getExternalType()))
                    .map(ExternalLinkEntity::getExternalKey)
                    .findFirst()
                    .orElse(null);
            if (issueKey == null) {
                return Optional.empty();
            }
            var att = actionService.loadAttachment(req.getProjectId(), requirementId, filename);
            return Optional.of(new IssueAttachment(filename, att.mimeType(), att.content(), issueKey));
        } catch (Exception e) {
            log.warn("Jira 附件解析失败（降级跳过）: requirement={} file={} err={}",
                    requirementId, filename, e.getMessage());
            return Optional.empty();
        }
    }
}
