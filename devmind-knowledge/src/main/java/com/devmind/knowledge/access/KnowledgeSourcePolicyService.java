package com.devmind.knowledge.access;
import com.devmind.common.knowledge.*;
import com.devmind.common.exception.*;
import com.devmind.knowledge.model.KnowledgeSourceBindingEntity;
import com.devmind.knowledge.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeSourcePolicyService {
    private final KnowledgeSourceBindingRepository bindings;
    private final KnowledgeEntryRepository entries;
    private final KnowledgeAccessService access;
    private final KnowledgeIdentityProvider identities;
    public KnowledgeSourcePolicyService(KnowledgeSourceBindingRepository bindings,KnowledgeEntryRepository entries,KnowledgeAccessService access,KnowledgeIdentityProvider identities){this.bindings=bindings;this.entries=entries;this.access=access;this.identities=identities;}
    public record Configure(long expectedRevision,String mode,int maxAgeSeconds) { }
    private void authorize(Long id) {
        var entry=entries.findById(id).orElseThrow(()->new DevMindException(ErrorCode.NOT_FOUND));
        access.requireBase(entry.getKbId(),KnowledgeAccessPolicy.Action.MANAGE_ACL);
        if(!"ADMIN".equals(identities.current().role()))throw new DevMindException(ErrorCode.FORBIDDEN,"源权限模式需要安全管理员批准");
    }
    public KnowledgeSourceBindingEntity get(Long id){authorize(id);return bindings.findById(id).orElse(null);}
    @Transactional public KnowledgeSourceBindingEntity configure(Long id,Configure r) {
        authorize(id);var entry=entries.findForAccessUpdate(id).orElseThrow();
        if(entry.getSource()==null||java.util.Set.of("manual","file").contains(entry.getSource()))throw new DevMindException(ErrorCode.BAD_REQUEST,"本地资料无需设置源端授权");
        if(r==null||!java.util.Set.of("LIVE","SNAPSHOT").contains(String.valueOf(r.mode()))||r.maxAgeSeconds()<1||r.maxAgeSeconds()>300)throw new DevMindException(ErrorCode.BAD_REQUEST);
        var b=bindings.findById(id).orElse(null);
        if((b==null?-1:b.getRevision())!=r.expectedRevision())throw new DevMindException(ErrorCode.CONFLICT);
        if(b==null){b=new KnowledgeSourceBindingEntity();b.setEntryId(id);}
        b.setMode(r.mode());b.setMaxAgeSeconds(r.maxAgeSeconds());b.setApprovedBy(access.actor());b.setRevoked(false);
        return bindings.saveAndFlush(b);
    }
}
