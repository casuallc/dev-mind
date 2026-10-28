package com.devmind.knowledge.access;
import com.devmind.common.knowledge.*;
import com.devmind.common.exception.*;
import com.devmind.knowledge.repo.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class KnowledgeTaskPolicyImpl implements KnowledgeTaskPolicy {
    private final KnowledgeAccessService access;
    private final KnowledgeIdentityProvider identities;
    private final KnowledgeImportBatchRepository batches;
    private final KnowledgeImportFileRepository files;
    private final KnowledgeEntryRepository entries;
    private final KnowledgeBaseRepository bases;
    private final ObjectMapper mapper;
    public KnowledgeTaskPolicyImpl(KnowledgeAccessService access,KnowledgeIdentityProvider identities,
            KnowledgeImportBatchRepository batches,KnowledgeEntryRepository entries,KnowledgeBaseRepository bases,ObjectMapper mapper,
            KnowledgeImportFileRepository files){this.access=access;this.identities=identities;this.batches=batches;this.entries=entries;this.bases=bases;this.mapper=mapper;this.files=files;}
    @Override public void require(String actor,String owner,String type,String payload,Use use) {
        if(!Objects.equals(identities.current().actor(),actor)||!Objects.equals(actor,owner))throw hidden();
        var action=use==Use.READ?KnowledgeAccessPolicy.Action.READ:KnowledgeAccessPolicy.Action.EDIT;
        var p=mapper.readTree(payload==null||payload.isBlank()?"{}":payload);
        if("knowledge.ingest".equals(type)) {
            var batch=batches.findById(p.path("batchId").asText()).orElseThrow(KnowledgeTaskPolicyImpl::hidden);
            if(!Objects.equals(actor,batch.getExecutionActor()))throw hidden();
            access.requireBase(actor,batch.getKbId(),action);
            for(var file:files.findByBatchIdOrderByRelativePathAsc(batch.getId()))if(file.getEntryId()!=null)access.requireEntry(actor,file.getEntryId(),action);
            return;
        }
        if("knowledge.sync".equals(type))throw new DevMindException(ErrorCode.CONFLICT,"此同步连接器尚未完成源权限适配，暂不开放执行");
        if(!Set.of("knowledge.rebuild","knowledge.enrich").contains(type))throw hidden();
        if(p.path("entryId").isIntegralNumber()){access.requireEntry(actor,p.path("entryId").asLong(),action);return;}
        List<Long> kbIds=p.path("kbId").isIntegralNumber()?List.of(p.path("kbId").asLong()):bases.findAll().stream().map(e->e.getId()).toList();
        for(Long id:kbIds) {
            access.requireBase(actor,id,action);
            for(var ref:entries.findAccessRefsByKbIdIn(List.of(id)))access.requireEntry(actor,ref.getId(),action);
        }
    }
    private static DevMindException hidden(){return new DevMindException(ErrorCode.NOT_FOUND,"任务不存在或知识来源权限已失效");}
}
