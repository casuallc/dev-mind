package com.devmind.knowledge.model;

import jakarta.persistence.*;

@Entity
@Table(name = "knowledge_policy_grants", uniqueConstraints = @UniqueConstraint(
        columnNames = {"policy_id", "principal_id", "action"}))
public class KnowledgePolicyGrantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "policy_id", nullable = false) private Long policyId;
    @Column(name = "principal_id", nullable = false, length = 64) private String principalId;
    @Column(nullable = false, length = 16) private String action;
    @Column(nullable = false, length = 8) private String effect;
    private java.time.Instant expiresAt;
    public java.time.Instant getExpiresAt(){return expiresAt;}public void setExpiresAt(java.time.Instant v){expiresAt=v;}
    public Long getId() { return id; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long value) { policyId = value; }
    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String value) { principalId = value; }
    public String getAction() { return action; }
    public void setAction(String value) { action = value; }
    public String getEffect() { return effect; }
    public void setEffect(String value) { effect = value; }
}
