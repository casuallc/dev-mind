package com.devmind.knowledge.model;

import jakarta.persistence.*;

/** A missing policy on a legacy base is deliberately quarantined. */
@Entity
@Table(name = "knowledge_policies")
public class KnowledgePolicyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64) private String ownerId;
    @Column(nullable = false, length = 16) private String visibility = "PRIVATE";
    @Version private long revision;
    private java.time.Instant updatedAt = java.time.Instant.now();
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public void touch() { updatedAt = java.time.Instant.now(); }
}
