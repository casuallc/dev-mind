package com.devmind.knowledge.access;

import com.devmind.common.knowledge.*;
import com.devmind.knowledge.repo.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeSourceAuthorizer {
    private final KnowledgeSourceBindingRepository bindings;
    private final KnowledgeEntryRepository entries;
    private final ObjectProvider<KnowledgeSourcePermissionProvider> providers;
    private record Key(Long entry,String actor,long revision) { }
    private final Map<Key,KnowledgeSourcePermissionProvider.Decision> snapshots=new ConcurrentHashMap<>();
    public KnowledgeSourceAuthorizer(KnowledgeSourceBindingRepository bindings,KnowledgeEntryRepository entries,
            ObjectProvider<KnowledgeSourcePermissionProvider> providers){this.bindings=bindings;this.entries=entries;this.providers=providers;}
    public boolean allows(String actor,Long entryId,String source) {
        if(source==null||Set.of("manual","file").contains(source))return true;
        var binding=bindings.findCurrentById(entryId).orElse(null);
        if(binding==null || binding.isRevoked() || !Set.of("LIVE","SNAPSHOT").contains(binding.getMode())
                || binding.getMaxAgeSeconds()<1 || binding.getMaxAgeSeconds()>300 || binding.getApprovedBy()==null)return false;
        var entry=entries.findById(entryId).orElse(null);if(entry==null||entry.getExternalId()==null)return false;
        var adapter=providers.orderedStream().filter(p->source.equals(p.source())).findFirst().orElse(null);if(adapter==null)return false;
        var key=new Key(entryId,actor,binding.getRevision());
        var decision="SNAPSHOT".equals(binding.getMode())?snapshots.get(key):null;
        if(!valid(decision,actor,binding.getMaxAgeSeconds())) {
            snapshots.remove(key);
            try { decision=adapter.verify(actor,entry.getExternalId()); }
            catch(Exception unavailable){return false;}
            if(!valid(decision,actor,binding.getMaxAgeSeconds()))return false;
            if("SNAPSHOT".equals(binding.getMode())) {
                if(snapshots.size()>10_000)snapshots.clear();snapshots.put(key,decision);
            }
        }
        return decision.allowed();
    }
    private boolean valid(KnowledgeSourcePermissionProvider.Decision d,String actor,int maxAge) {
        Instant now=Instant.now();return d!=null && actor.equals(d.actor()) && d.allowed()
                && d.verifiedAt()!=null && !d.verifiedAt().isAfter(now) && d.expiresAt()!=null && now.isBefore(d.expiresAt())
                && !d.expiresAt().isAfter(d.verifiedAt().plusSeconds(maxAge)) && now.isBefore(d.verifiedAt().plusSeconds(maxAge))
                && d.sourceRevision()!=null && d.mappingRevision()!=null;
    }
    /** Trusted source-event adapter hook. A received revocation invalidates all principals immediately. */
    @org.springframework.transaction.annotation.Transactional public void invalidate(Long entryId) {
        bindings.findById(entryId).ifPresent(b->{b.setRevoked(true);bindings.saveAndFlush(b);});
        snapshots.keySet().removeIf(k->k.entry().equals(entryId));
    }
}
