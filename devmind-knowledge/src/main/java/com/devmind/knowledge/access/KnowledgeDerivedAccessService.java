package com.devmind.knowledge.access;
import com.devmind.common.knowledge.*;
import com.devmind.common.exception.*;
import com.devmind.knowledge.model.*;
import com.devmind.knowledge.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class KnowledgeDerivedAccessService implements KnowledgeDerivedPolicy {
    private final KnowledgeDerivedArtifactRepository artifacts;
    private final KnowledgeDependencyRepository dependencies;
    private final KnowledgeAccessService access;
    private final KnowledgeIdentityProvider identities;
    public KnowledgeDerivedAccessService(KnowledgeDerivedArtifactRepository artifacts,KnowledgeDependencyRepository dependencies,KnowledgeAccessService access,KnowledgeIdentityProvider identities){this.artifacts=artifacts;this.dependencies=dependencies;this.access=access;this.identities=identities;}
    @Override public void requireRead(String actor,String type,Long id) {
        if(!Objects.equals(actor,identities.current().actor()))throw hidden();
        var artifact=artifacts.findByResourceTypeAndResourceId(type,id).orElseThrow(KnowledgeDerivedAccessService::hidden);
        if(!Objects.equals(actor,artifact.getOwnerId()) || !Set.of("COMPLETE","VERIFIED_EMPTY").contains(String.valueOf(artifact.getCompleteness())))throw hidden();
        var edges=dependencies.findByArtifactId(artifact.getId());
        if("COMPLETE".equals(artifact.getCompleteness())&&edges.isEmpty())throw hidden();
        // Independent-distribution approval is deliberately not inferred from an ordinary permission.
        if(edges.stream().anyMatch(e->!"ACCESS_AND_AUDIT".equals(e.getEdgeType())))throw hidden();
        access.requireEntries(actor,edges.stream().map(KnowledgeDependencyEntity::getSourceEntryId).toList());
    }
    @Transactional public void registerDocument(Long docId,String content,SolutionSourceProvider.SolutionSource source) {
        if(!"COMPLETE".equals(source.completeness())||source.dependencies()==null||source.dependencies().isEmpty())throw new DevMindException(ErrorCode.CONFLICT,"来源不完整的方案不能沉淀");
        var ids=source.dependencies().stream().map(SolutionSourceProvider.SolutionSource.Evidence::entryId).filter(Objects::nonNull).distinct().toList();
        access.requireEntries(access.actor(),ids);
        var artifact=new KnowledgeDerivedArtifactEntity();artifact.setResourceType("DOCUMENT");artifact.setResourceId(docId);artifact.setOwnerId(access.actor());
        artifact.setCompleteness("COMPLETE");artifact.setContentHash(KnowledgeDigests.sha256(content));artifact.setSourceChatId(source.chatId());artifact=artifacts.saveAndFlush(artifact);
        for(var evidence:source.dependencies()) {
            if(evidence.entryId()==null||evidence.releaseId()==null||evidence.contentHash()==null)throw new DevMindException(ErrorCode.CONFLICT,"历史证据尚未迁移到可追溯发布版");
            var edge=new KnowledgeDependencyEntity();edge.setArtifactId(artifact.getId());edge.setSourceEntryId(evidence.entryId());edge.setSourceReleaseId(evidence.releaseId());edge.setSourceHash(evidence.contentHash());dependencies.save(edge);
        }
    }
    private static DevMindException hidden(){return new DevMindException(ErrorCode.NOT_FOUND,"资源不存在或来源权限已失效");}
}
