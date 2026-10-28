package com.devmind.knowledge.access;

import com.devmind.common.knowledge.*;
import com.devmind.common.model.*;
import com.devmind.common.exception.*;
import com.devmind.knowledge.model.KnowledgeProcessingApprovalEntity;
import com.devmind.knowledge.repo.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeProcessingService implements KnowledgeProcessingPolicy {
    private final KnowledgeAccessService access;
    private final KnowledgeEntryRepository entries;
    private final KnowledgeProcessingApprovalRepository approvals;
    private final ObjectProvider<ModelEndpointProvider> endpoints;
    private final KnowledgeAuditRepository audit;
    public KnowledgeProcessingService(KnowledgeAccessService access,KnowledgeEntryRepository entries,
            KnowledgeProcessingApprovalRepository approvals,ObjectProvider<ModelEndpointProvider> endpoints,KnowledgeAuditRepository audit) {
        this.audit=audit;
        this.access=access;this.entries=entries;this.approvals=approvals;this.endpoints=endpoints;
    }
    public record Approval(Long endpointId,Purpose purpose) { }
    public List<KnowledgeProcessingApprovalEntity> list(String type,Long id) { authorize(type,id);return approvals.findByResourceTypeAndResourceId(type,id); }
    @Transactional public List<KnowledgeProcessingApprovalEntity> replace(String type,Long id,List<Approval> values) {
        authorize(type,id);
        if(values==null||values.size()>100) throw new DevMindException(ErrorCode.BAD_REQUEST);
        var keys=new HashSet<String>();var rows=new ArrayList<KnowledgeProcessingApprovalEntity>();
        for(var value:values) {
            if(value==null||value.purpose()==null||!keys.add(value.endpointId()+":"+value.purpose())) throw new DevMindException(ErrorCode.BAD_REQUEST);
            var r=new KnowledgeProcessingApprovalEntity();r.setResourceType(type);r.setResourceId(id);r.setEndpointId(value.endpointId());
            r.setPurpose(value.purpose().name());r.setEndpointFingerprint(fingerprint(endpoint(value.endpointId())));
            r.setApprovedBy(access.actor());r.setApprovedAt(Instant.now());rows.add(r);
        }
        approvals.deleteByResourceTypeAndResourceId(type,id);approvals.flush();
        audit.save(com.devmind.knowledge.model.KnowledgeAuditEntity.of(access.actor(),"CHANGE_PROCESSING",type,id,"APPROVAL_COUNT_"+rows.size()));
        return approvals.saveAllAndFlush(rows);
    }
    private void authorize(String type,Long id) {
        if("BASE".equals(type))access.requireBase(id,KnowledgeAccessPolicy.Action.MANAGE_ACL);
        else if("ENTRY".equals(type)){access.requireEntry(id,KnowledgeAccessPolicy.Action.READ);access.requireBase(entries.findById(id).orElseThrow().getKbId(),KnowledgeAccessPolicy.Action.MANAGE_ACL);}
        else throw new DevMindException(ErrorCode.BAD_REQUEST);
    }
    @Override public void requireProcessing(String actor,Collection<Long> kbIds,Collection<Long> entryIds,Long endpointId,Purpose purpose) {
        kbIds.forEach(id->access.requireBase(actor,id,KnowledgeAccessPolicy.Action.READ));access.requireEntries(actor,entryIds);
        String fingerprint=fingerprint(endpoint(endpointId));
        if(kbIds.stream().anyMatch(id->!allows("BASE",id,endpointId,purpose,fingerprint,false)))throw denied();
        for(Long id:entryIds) if(!allowsEntry(id,endpointId,purpose,fingerprint))throw denied();
    }
    /** Background indexing may use only explicitly approved endpoints; legacy global endpoints have no resource identity. */
    public boolean allowsEntry(Long id,Long endpointId,Purpose purpose) {
        if(endpointId==null)return false;
        try {
            access.requireEntry(access.actor(),id,KnowledgeAccessPolicy.Action.READ);
            return allowsEntry(id,endpointId,purpose,fingerprint(endpoint(endpointId)));
        }catch(DevMindException unavailable){return false;}
    }
    private boolean allowsEntry(Long id,Long endpointId,Purpose purpose,String fingerprint) {
        var entry=entries.findById(id).orElse(null);
        return entry!=null && allows("BASE",entry.getKbId(),endpointId,purpose,fingerprint,false)
                && allows("ENTRY",id,endpointId,purpose,fingerprint,true);
    }
    private boolean allows(String type,Long id,Long endpointId,Purpose purpose,String fingerprint,boolean inherit) {
        var rows=approvals.findByResourceTypeAndResourceId(type,id);
        if(inherit && rows.isEmpty()) return true;
        return rows.stream().anyMatch(r->endpointId.equals(r.getEndpointId())&&purpose.name().equals(r.getPurpose())&&fingerprint.equals(r.getEndpointFingerprint()));
    }
    private ModelEndpointView endpoint(Long id) {
        var provider=endpoints.getIfAvailable();
        if(provider==null||id==null)throw denied();
        return provider.activeEndpoint(id).orElseThrow(KnowledgeProcessingService::denied);
    }
    public static String fingerprint(ModelEndpointView e) {
        return KnowledgeDigests.sha256(e.id()+"\n"+e.provider()+"\n"+e.baseUrl()+"\n"+e.kind()+"\n"+e.model()+"\n"+e.dimensions());
    }
    private static DevMindException denied(){return new DevMindException(ErrorCode.FORBIDDEN,"此知识尚未批准交由所选模型端点处理，请由知识负责人配置处理范围");}
}
