package com.devmind.knowledge.access;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.knowledge.KnowledgeAccessPolicy;
import com.devmind.common.knowledge.KnowledgeIdentityProvider;
import com.devmind.common.knowledge.KnowledgeIdentityProvider.Subject;
import com.devmind.knowledge.dto.KnowledgeAccessView;
import com.devmind.knowledge.model.*;
import com.devmind.knowledge.repo.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** No admin read bypass, no anonymous fallback, no authorization cache. */
@Service
public class KnowledgeAccessService implements KnowledgeAccessPolicy {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeAccessService.class);
    private final KnowledgeIdentityProvider identities;
    private final KnowledgePolicyRepository policies;
    private final KnowledgePolicyGrantRepository grants;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeEntryRepository entries;
    private final KnowledgeSourceAuthorizer sources;
    private final KnowledgeAuditRepository audit;
    private final org.springframework.beans.factory.ObjectProvider<com.devmind.common.knowledge.KnowledgeGroupDirectory> groups;

    public KnowledgeAccessService(KnowledgeIdentityProvider identities, KnowledgePolicyRepository policies,
            KnowledgePolicyGrantRepository grants, KnowledgeBaseRepository bases, KnowledgeEntryRepository entries,
            KnowledgeSourceAuthorizer sources,
            org.springframework.beans.factory.ObjectProvider<com.devmind.common.knowledge.KnowledgeGroupDirectory> groups,
            KnowledgeAuditRepository audit) {
        this.audit=audit;
        this.sources=sources;this.groups=groups;
        this.identities = identities; this.policies = policies; this.grants = grants;
        this.bases = bases; this.entries = entries;
    }
    public String actor() { return identities.current().actor(); }

    /** Existing global governance endpoints are usable only when every included resource is readable. */
    public void requireAllReadable() {
        String actor = actor();
        var all = bases.findAll();
        if (readableBaseIds(actor, null).size() != all.size()) throw denied();
        var ids = all.stream().map(KnowledgeBaseEntity::getId).toList();
        if (!ids.isEmpty() && readableEntryIds(actor, ids).size() != entries.count()) throw denied();
    }

    public List<Action> effectiveActions(Long id) {
        var kb = bases.findById(id).orElseThrow(KnowledgeAccessService::hidden);
        var current = identities.current();
        var snapshot = snapshot(current, Collections.singletonList(kb.getPolicyId()));
        return Arrays.stream(Action.values()).filter(a -> snapshot.allows(kb.getPolicyId(), a)).toList();
    }

    @Transactional
    public void initializeBase(KnowledgeBaseEntity base) {
        var p = new KnowledgePolicyEntity();
        p.setOwnerId(actor());
        base.setPolicyId(policies.save(p).getId());
    }

    public void requireBase(Long id, Action action) { requireBase(actor(), id, action); }
    @Override public void requireBase(String actor, Long id, Action action) {
        var subject = identities.resolve(actor);
        if (id == null) throw hidden();
        var kb = bases.findById(id).orElseThrow(KnowledgeAccessService::hidden);
        var snapshot = snapshot(subject, Collections.singletonList(kb.getPolicyId()));
        if (!snapshot.allows(kb.getPolicyId(), Action.READ)) throw hidden();
        if (!snapshot.allows(kb.getPolicyId(), action)) throw denied();
    }

    @Override public List<Long> readableBaseIds(String actor, Collection<Long> requested) {
        var subject = identities.resolve(actor);
        if (requested != null && requested.isEmpty()) return List.of();
        var kbs = requested == null ? bases.findAll() : bases.findAllById(requested);
        var snapshot = snapshot(subject, kbs.stream().map(KnowledgeBaseEntity::getPolicyId).toList());
        return kbs.stream().filter(k -> snapshot.allows(k.getPolicyId(), Action.READ))
                .map(KnowledgeBaseEntity::getId).toList();
    }

    public List<Long> readableBaseIds(Collection<Long> requested) { return readableBaseIds(actor(), requested); }

    /** Access projections exclude document text; all policy/grant lookups are batched. */
    public List<Long> readableEntryIds(String actor, Collection<Long> kbIds) {
        var subject = identities.resolve(actor);
        var allowedBases = readableBaseIds(actor, kbIds);
        if (allowedBases.isEmpty()) return List.of();
        var refs = entries.findAccessRefsByKbIdIn(allowedBases);
        var snapshot = snapshot(subject, refs.stream().map(KnowledgeEntryRepository.AccessRef::getPolicyId).toList());
        return refs.stream().filter(e -> sources.allows(actor,e.getId(),e.getSource()))
                .filter(e -> e.getPolicyId() == null || snapshot.allows(e.getPolicyId(), Action.READ))
                .map(KnowledgeEntryRepository.AccessRef::getId).toList();
    }
    public List<Long> readableEntryIds(Collection<Long> kbIds) { return readableEntryIds(actor(), kbIds); }

    public void requireEntry(Long id, Action action) {
        requireEntry(actor(), id, action);
    }

    public void requireEntry(String actor, Long id, Action action) {
        var subject = identities.resolve(actor);
        var entry = entries.findById(id).orElseThrow(KnowledgeAccessService::hidden);
        requireBase(subject.actor(), entry.getKbId(), action);
        if (!sources.allows(actor,entry.getId(),entry.getSource())) throw hidden();
        if (entry.getPolicyId() != null) {
            var snapshot = snapshot(subject, List.of(entry.getPolicyId()));
            if (!snapshot.allows(entry.getPolicyId(), Action.READ)) throw hidden();
            if (!snapshot.allows(entry.getPolicyId(), action)) throw denied();
        }
    }

    /** Current runners have no verified OS isolation and revocation protocol, including for INTERNAL data. */
    public boolean canMaterializeForRunner(Collection<Long> entryIds) {
        requireEntries(actor(),entryIds);
        return entryIds.isEmpty();
    }

    public boolean canMaterializeBases(String actor,Collection<Long> ids) {
        identities.resolve(actor);
        if(ids==null||ids.isEmpty())return true;
        ids.forEach(id->requireBase(actor,id,Action.READ));
        return false;
    }

    @Override public void requireEntries(String actor, Collection<Long> ids) {
        identities.resolve(actor);
        if (ids == null || ids.isEmpty()) return;
        var refs = entries.findAccessRefsByIdIn(ids);
        var kbIds = refs.stream().map(KnowledgeEntryRepository.AccessRef::getKbId).distinct().toList();
        var readable = new HashSet<>(readableEntryIds(actor, kbIds));
        if (!readable.containsAll(ids)) throw hidden();
    }

    /** External-source ACL adapters are not delivered yet: quarantine rather than publish globally. */
    private boolean sourceReadable(String source) {
        return source == null || Set.of("manual", "file").contains(source);
    }

    public KnowledgeAccessView baseAccess(Long id) {
        requireBase(id, Action.MANAGE_ACL);
        return view(bases.findById(id).orElseThrow().getPolicyId());
    }

    @Transactional
    public KnowledgeAccessView updateBaseAccess(Long id, KnowledgeAccessView.Update update) {
        requireBase(id, Action.MANAGE_ACL);
        return updatePolicy(bases.findById(id).orElseThrow().getPolicyId(), update);
    }

    @Transactional
    public KnowledgeAccessView restrictEntry(Long id, KnowledgeAccessView.Update update) {
        if (update == null) throw new DevMindException(ErrorCode.BAD_REQUEST, "请提供授权设置");
        requireEntry(id, Action.READ);
        var entry = entries.findForAccessUpdate(id).orElseThrow();
        requireBase(entry.getKbId(), Action.MANAGE_ACL);
        if ("INHERIT".equals(update.visibility())) {
            long expected = entry.getPolicyId() == null ? -1
                    : policies.findById(entry.getPolicyId()).orElseThrow(KnowledgeAccessService::hidden).getRevision();
            if (expected != update.expectedRevision()) throw conflict();
            entry.setPolicyId(null);
            entries.saveAndFlush(entry);
            log.info("Knowledge entry access restored to inheritance: actor={} entry={}", actor(), id);
            return entryAccess(id);
        }
        if (entry.getPolicyId() == null) {
            if (update.expectedRevision() != -1) throw conflict();
            var p = new KnowledgePolicyEntity();
            p.setOwnerId(actor());
            entry.setPolicyId(policies.save(p).getId());
            entries.save(entry);
            update = new KnowledgeAccessView.Update(0, update.visibility(), update.grants());
        }
        return updatePolicy(entry.getPolicyId(), update);
    }

    public KnowledgeAccessView entryAccess(Long id) {
        requireEntry(id, Action.READ);
        var entry = entries.findById(id).orElseThrow();
        requireBase(entry.getKbId(), Action.MANAGE_ACL);
        return entry.getPolicyId() == null ? new KnowledgeAccessView(null, "INHERIT", -1, List.of(), List.of())
                : view(entry.getPolicyId());
    }

    @Transactional
    protected KnowledgeAccessView updatePolicy(Long policyId, KnowledgeAccessView.Update update) {
        if (update == null || !Set.of("PRIVATE", "INTERNAL").contains(String.valueOf(update.visibility()))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "可见性仅支持 PRIVATE/INTERNAL");
        }
        var policy = policies.findById(policyId).orElseThrow(KnowledgeAccessService::hidden);
        if (policy.getRevision() != update.expectedRevision()) throw conflict();
        var requested = update.grants() == null ? List.<KnowledgeAccessView.Grant>of() : update.grants();
        if (requested.size() > 200) throw new DevMindException(ErrorCode.BAD_REQUEST, "授权条目过多");
        var keys = new HashSet<String>();
        for (var grant : requested) {
            if (grant == null || grant.action() == null || grant.username() == null
                    || !Set.of("ALLOW", "DENY").contains(String.valueOf(grant.effect()))
                    || grant.username().length()>64 || grant.username().equals(policy.getOwnerId())
                    || grant.expiresAt()!=null && !grant.expiresAt().isAfter(java.time.Instant.now())
                    || !keys.add(grant.username() + ":" + grant.action())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "授权包含重复、负责人或非法条目");
            }
            if(grant.username().startsWith("group:")) {
                var directory=groups.getIfAvailable();
                if(directory==null || !directory.knownGroup(grant.username().substring(6)))throw new DevMindException(ErrorCode.BAD_REQUEST,"组织目录未接入或用户组不存在");
            } else grantSubject(grant.username());
        }
        grants.deleteByPolicyId(policyId);
        grants.flush();
        for (var grant : requested) {
            var row = new KnowledgePolicyGrantEntity();
            row.setPolicyId(policyId); row.setPrincipalId(grant.username());
            row.setAction(grant.action().name()); row.setEffect(grant.effect()); grants.save(row);
            row.setExpiresAt(grant.expiresAt());
        }
        policy.setVisibility(update.visibility());
        // Grants-only changes must also advance the optimistic lock version.
        policy.touch();
        policies.saveAndFlush(policy);
        audit.save(KnowledgeAuditEntity.of(actor(),"CHANGE_ACCESS","POLICY",policyId,"POLICY_REVISION_"+policy.getRevision()));
        log.info("Knowledge access changed: actor={} policy={} revision={}", actor(), policyId, policy.getRevision());
        return view(policyId);
    }

    public List<KnowledgeAccessView.PendingBase> pendingBases() {
        requireAdmin();
        return bases.findAll().stream().filter(k -> k.getPolicyId() == null)
                .map(k -> new KnowledgeAccessView.PendingBase(k.getId(), k.getName())).toList();
    }

    public KnowledgeAccessView policyControl(Long id) {
        requireAdmin();var base=bases.findById(id).orElseThrow(KnowledgeAccessService::hidden);
        if(base.getPolicyId()==null)throw new DevMindException(ErrorCode.CONFLICT,"请先完成知识库负责人迁移");
        return view(base.getPolicyId());
    }
    public record Transfer(long expectedRevision,String owner,String reason) { }
    @Transactional public KnowledgeAccessView transferOwner(Long id,Transfer request) {
        requireAdmin();requireReason(request==null?null:request.reason());
        if(!Set.of("ADMIN","DEVELOPER").contains(grantSubject(request.owner()).role()))throw new DevMindException(ErrorCode.BAD_REQUEST,"负责人必须是有效业务管理账号");
        var base=bases.findForAccessUpdate(id).orElseThrow(KnowledgeAccessService::hidden);
        if(base.getPolicyId()==null)throw new DevMindException(ErrorCode.CONFLICT,"请先完成知识库负责人迁移");
        var policy=policies.findById(base.getPolicyId()).orElseThrow(KnowledgeAccessService::hidden);
        if(policy.getRevision()!=request.expectedRevision())throw conflict();
        grants.deleteByPolicyIdAndPrincipalId(policy.getId(),request.owner());grants.flush();
        policy.setOwnerId(request.owner());policy.touch();policies.saveAndFlush(policy);
        audit.save(KnowledgeAuditEntity.of(actor(),"TRANSFER_OWNER","BASE",id,request.reason()));return view(policy.getId());
    }
    public record DebugRead(long expectedRevision,int minutes,List<Long> entryIds,String reason) { }
    @Transactional public KnowledgeAccessView debugRead(Long id,DebugRead request) {
        requireAdmin();requireReason(request==null?null:request.reason());
        if(request.minutes()<1||request.minutes()>15||request.entryIds()!=null&&request.entryIds().size()>30)throw new DevMindException(ErrorCode.BAD_REQUEST,"排障授权限时 1 至 15 分钟，最多指定 30 篇文档");
        var base=bases.findForAccessUpdate(id).orElseThrow(KnowledgeAccessService::hidden);
        var policy=policies.findById(base.getPolicyId()).orElseThrow(KnowledgeAccessService::hidden);
        if(policy.getRevision()!=request.expectedRevision())throw conflict();
        var policyIds=new HashSet<Long>();policyIds.add(policy.getId());
        if(request.entryIds()!=null)for(Long entryId:request.entryIds()) {
            var entry=entries.findById(entryId).orElseThrow(KnowledgeAccessService::hidden);
            if(!id.equals(entry.getKbId()))throw hidden();if(entry.getPolicyId()!=null)policyIds.add(entry.getPolicyId());
        }
        var deadline=java.time.Instant.now().plusSeconds(request.minutes()*60L);
        for(Long policyId:policyIds) {
            var p=policies.findById(policyId).orElseThrow(KnowledgeAccessService::hidden);
            if(actor().equals(p.getOwnerId()))continue;
            var existing=grants.findByPolicyId(policyId).stream().filter(g->actor().equals(g.getPrincipalId()) && "READ".equals(g.getAction())).findFirst().orElse(null);
            if(existing!=null && "DENY".equals(existing.getEffect()))throw new DevMindException(ErrorCode.FORBIDDEN,"排障读取不能覆盖显式拒绝规则");
            if(existing==null) {
                var g=new KnowledgePolicyGrantEntity();g.setPolicyId(policyId);g.setPrincipalId(actor());g.setAction("READ");g.setEffect("ALLOW");g.setExpiresAt(deadline);grants.save(g);
            } else if(existing.getExpiresAt()!=null){existing.setExpiresAt(deadline);grants.save(existing);}
            p.touch();policies.saveAndFlush(p);
        }
        audit.save(KnowledgeAuditEntity.of(actor(),"DEBUG_READ","BASE",id,request.reason()+";EXPIRES="+deadline));
        return view(policy.getId());
    }
    private void requireReason(String reason) {
        if(reason==null||reason.strip().length()<5||reason.length()>800)throw new DevMindException(ErrorCode.BAD_REQUEST,"请填写 5 至 800 字的操作原因");
    }

    @Transactional
    public KnowledgeAccessView assignLegacyOwner(Long id, String owner) {
        requireAdmin();
        if (!Set.of("ADMIN", "DEVELOPER").contains(grantSubject(owner).role())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "负责人需具有平台业务管理角色");
        }
        var base = bases.findForAccessUpdate(id).orElseThrow(KnowledgeAccessService::hidden);
        if (base.getPolicyId() != null) throw conflict();
        var policy = new KnowledgePolicyEntity(); policy.setOwnerId(owner);
        base.setPolicyId(policies.save(policy).getId()); bases.save(base);
        log.info("Knowledge legacy owner assigned: actor={} base={} owner={}", actor(), id, owner);
        return new KnowledgeAccessView(owner, "PRIVATE", 0, List.of(), List.of());
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(identities.current().role())) throw denied();
    }

    private Subject grantSubject(String username) {
        try { return identities.resolve(username); }
        catch (DevMindException invalid) {
            if (invalid.getErrorCode() != ErrorCode.UNAUTHORIZED) throw invalid;
            throw new DevMindException(ErrorCode.BAD_REQUEST, "目标账号不存在或不可用");
        }
    }

    private KnowledgeAccessView view(Long id) {
        var subject = identities.current();
        var policy = policies.findById(id).orElseThrow(KnowledgeAccessService::hidden);
        var snapshot = snapshot(subject, List.of(id));
        return new KnowledgeAccessView(policy.getOwnerId(), policy.getVisibility(), policy.getRevision(),
                Arrays.stream(Action.values()).filter(a -> snapshot.allows(id, a)).toList(),
                grants.findByPolicyId(id).stream().map(g -> new KnowledgeAccessView.Grant(
                        g.getPrincipalId(), Action.valueOf(g.getAction()), g.getEffect(),g.getExpiresAt())).toList());
    }

    private Snapshot snapshot(Subject subject, Collection<Long> ids) {
        var unique = ids.stream().filter(Objects::nonNull).distinct().toList();
        var map = unique.isEmpty() ? Map.<Long, KnowledgePolicyRepository.PolicyRef>of()
                : policies.findAccessSnapshots(unique).stream().collect(Collectors.toMap(KnowledgePolicyRepository.PolicyRef::getId, Function.identity()));
        var principals=new HashSet<String>();principals.add(subject.actor());
        var directory=groups.getIfAvailable();
        if(directory!=null)directory.resolve(subject.actor()).groups().forEach(g->principals.add("group:"+g));
        var rows = unique.isEmpty() ? List.<KnowledgePolicyGrantRepository.CurrentGrant>of()
                : grants.findCurrentGrants(principals, unique);
        return new Snapshot(subject, map, rows);
    }

    private record Snapshot(Subject subject, Map<Long, KnowledgePolicyRepository.PolicyRef> policies,
                            List<KnowledgePolicyGrantRepository.CurrentGrant> grants) {
        boolean allows(Long id, Action action) {
            if (id == null) return false;
            var p = policies.get(id);
            if (p == null) return false;
            if (action != Action.READ && !Set.of("ADMIN", "DEVELOPER").contains(subject.role())) return false;
            if (action != Action.READ && !allows(id, Action.READ)) return false;
            var relevant = grants.stream().filter(g -> id.equals(g.getPolicyId()) && action.name().equals(g.getAction()))
                    .filter(g->g.getExpiresAt()==null||java.time.Instant.now().isBefore(g.getExpiresAt())).toList();
            if (relevant.stream().anyMatch(g -> "DENY".equals(g.getEffect()))) return false;
            return subject.actor().equals(p.getOwnerId())
                    || (action == Action.READ && "INTERNAL".equals(p.getVisibility()))
                    || relevant.stream().anyMatch(g -> "ALLOW".equals(g.getEffect()));
        }
    }
    private static DevMindException hidden() { return new DevMindException(ErrorCode.NOT_FOUND, "资源不存在或无权访问"); }
    private static DevMindException denied() { return new DevMindException(ErrorCode.FORBIDDEN, "无权执行该知识操作"); }
    private static DevMindException conflict() { return new DevMindException(ErrorCode.CONFLICT, "授权已变更，请刷新后重试"); }
}
