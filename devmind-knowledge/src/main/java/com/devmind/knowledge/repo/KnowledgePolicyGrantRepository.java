package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgePolicyGrantEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgePolicyGrantRepository extends JpaRepository<KnowledgePolicyGrantEntity, Long> {
    interface CurrentGrant {
        Long getPolicyId();String getAction();String getEffect();java.time.Instant getExpiresAt();
    }
    @org.springframework.data.jpa.repository.Query("select g.policyId as policyId,g.action as action,g.effect as effect,g.expiresAt as expiresAt from KnowledgePolicyGrantEntity g where g.principalId in :principals and g.policyId in :ids")
    List<CurrentGrant> findCurrentGrants(@org.springframework.data.repository.query.Param("principals") Collection<String> principals,@org.springframework.data.repository.query.Param("ids") Collection<Long> ids);
    List<KnowledgePolicyGrantEntity> findByPrincipalIdAndPolicyIdIn(String principalId, Collection<Long> ids);
    List<KnowledgePolicyGrantEntity> findByPrincipalIdInAndPolicyIdIn(Collection<String> principalIds,Collection<Long> ids);
    List<KnowledgePolicyGrantEntity> findByPolicyId(Long policyId);
    void deleteByPolicyId(Long policyId);
    void deleteByPolicyIdAndPrincipalId(Long policyId,String principalId);
}
