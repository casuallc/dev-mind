package com.devmind.knowledge.model;
import jakarta.persistence.*;
@Entity @Table(name="knowledge_dependencies")
public class KnowledgeDependencyEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long artifactId;
    private Long sourceEntryId;
    private Long sourceReleaseId;
    private String sourceHash;
    private String edgeType="ACCESS_AND_AUDIT";
    private Long approvalId;
    public Long getArtifactId(){return artifactId;}public void setArtifactId(Long v){artifactId=v;}
    public Long getSourceEntryId(){return sourceEntryId;}public void setSourceEntryId(Long v){sourceEntryId=v;}
    public Long getSourceReleaseId(){return sourceReleaseId;}public void setSourceReleaseId(Long v){sourceReleaseId=v;}
    public String getSourceHash(){return sourceHash;}public void setSourceHash(String v){sourceHash=v;}
    public String getEdgeType(){return edgeType;}public void setEdgeType(String v){edgeType=v;}
    public Long getApprovalId(){return approvalId;}public void setApprovalId(Long v){approvalId=v;}
}
