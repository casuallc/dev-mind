package com.devmind.knowledge.model;
import jakarta.persistence.*;
@Entity @Table(name="knowledge_derived_artifacts",uniqueConstraints=@UniqueConstraint(columnNames={"resource_type","resource_id"}))
public class KnowledgeDerivedArtifactEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String resourceType;
    private Long resourceId;
    private String ownerId;
    private String completeness;
    private String contentHash;
    private String state="DRAFT";
    private String sourceChatId;
    public Long getId(){return id;}
    public String getResourceType(){return resourceType;}public void setResourceType(String v){resourceType=v;}
    public Long getResourceId(){return resourceId;}public void setResourceId(Long v){resourceId=v;}
    public String getOwnerId(){return ownerId;}public void setOwnerId(String v){ownerId=v;}
    public String getCompleteness(){return completeness;}public void setCompleteness(String v){completeness=v;}
    public String getContentHash(){return contentHash;}public void setContentHash(String v){contentHash=v;}
    public String getState(){return state;}public void setState(String v){state=v;}
    public String getSourceChatId(){return sourceChatId;}public void setSourceChatId(String v){sourceChatId=v;}
}
