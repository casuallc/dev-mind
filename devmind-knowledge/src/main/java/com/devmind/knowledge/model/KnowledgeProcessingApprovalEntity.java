package com.devmind.knowledge.model;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="knowledge_processing_approvals",uniqueConstraints=@UniqueConstraint(columnNames={"resource_type","resource_id","endpoint_id","purpose"}))
public class KnowledgeProcessingApprovalEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String resourceType;
    private Long resourceId;
    private Long endpointId;
    private String endpointFingerprint;
    private String purpose;
    private String approvedBy;
    private Instant approvedAt;
    public Long getId(){return id;}
    public String getResourceType(){return resourceType;}public void setResourceType(String v){resourceType=v;}
    public Long getResourceId(){return resourceId;}public void setResourceId(Long v){resourceId=v;}
    public Long getEndpointId(){return endpointId;}public void setEndpointId(Long v){endpointId=v;}
    public String getEndpointFingerprint(){return endpointFingerprint;}public void setEndpointFingerprint(String v){endpointFingerprint=v;}
    public String getPurpose(){return purpose;}public void setPurpose(String v){purpose=v;}
    public String getApprovedBy(){return approvedBy;}public void setApprovedBy(String v){approvedBy=v;}
    public Instant getApprovedAt(){return approvedAt;}public void setApprovedAt(Instant v){approvedAt=v;}
}
