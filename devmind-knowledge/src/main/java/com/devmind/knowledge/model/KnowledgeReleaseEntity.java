package com.devmind.knowledge.model;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="knowledge_releases", uniqueConstraints=@UniqueConstraint(columnNames={"entry_id","release_label"}))
public class KnowledgeReleaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="entry_id",nullable=false) private Long entryId;
    private Long kbId;
    @Column(name="release_label",nullable=false,length=128) private String releaseLabel;
    private int contentRevision;
    private String state;
    private String title;
    @Column(length=1024) private String sourceUri;
    private String sourceClass;
    private String docType;
    @Column(length=500) private String tags;
    public String getDocType(){return docType;} public void setDocType(String v){docType=v;}
    public String getTags(){return tags;} public void setTags(String v){tags=v;}
    public void setId(Long value){id=value;}
    private String contentHash;
    private String embeddingFingerprint;
    @Lob @JdbcTypeCode(SqlTypes.LONGVARCHAR) private String parseReportJson;
    public String getParseReportJson(){return parseReportJson;}public void setParseReportJson(String v){parseReportJson=v;}
    private Instant indexedAt;
    public String getEmbeddingFingerprint(){return embeddingFingerprint;}public void setEmbeddingFingerprint(String v){embeddingFingerprint=v;}
    public Instant getIndexedAt(){return indexedAt;}public void setIndexedAt(Instant v){indexedAt=v;}
    private String publishedBy;
    private Instant publishedAt;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    @Lob @JdbcTypeCode(SqlTypes.LONGVARCHAR) private String contentMd;
    @Lob @JdbcTypeCode(SqlTypes.LONGVARCHAR) private String applicabilityJson;
    @Lob @JdbcTypeCode(SqlTypes.LONGVARCHAR) private String sectionScopesJson;
    public String getSectionScopesJson(){return sectionScopesJson;}public void setSectionScopesJson(String value){sectionScopesJson=value;}
    @Lob @JdbcTypeCode(SqlTypes.LONGVARCHAR) private String supersededScopesJson;
    @Version private long revision;
    public Long getId(){return id;} public Long getEntryId(){return entryId;} public void setEntryId(Long v){entryId=v;}
    public Long getKbId(){return kbId;} public void setKbId(Long v){kbId=v;}
    public String getReleaseLabel(){return releaseLabel;} public void setReleaseLabel(String v){releaseLabel=v;}
    public int getContentRevision(){return contentRevision;} public void setContentRevision(int v){contentRevision=v;}
    public String getState(){return state;} public void setState(String v){state=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getSourceUri(){return sourceUri;} public void setSourceUri(String v){sourceUri=v;}
    public String getSourceClass(){return sourceClass;} public void setSourceClass(String v){sourceClass=v;}
    public String getContentHash(){return contentHash;} public void setContentHash(String v){contentHash=v;}
    public String getPublishedBy(){return publishedBy;} public void setPublishedBy(String v){publishedBy=v;}
    public Instant getPublishedAt(){return publishedAt;} public void setPublishedAt(Instant v){publishedAt=v;}
    public Instant getEffectiveFrom(){return effectiveFrom;} public void setEffectiveFrom(Instant v){effectiveFrom=v;}
    public Instant getEffectiveTo(){return effectiveTo;} public void setEffectiveTo(Instant v){effectiveTo=v;}
    public String getContentMd(){return contentMd;} public void setContentMd(String v){contentMd=v;}
    public String getApplicabilityJson(){return applicabilityJson;} public void setApplicabilityJson(String v){applicabilityJson=v;}
    public String getSupersededScopesJson(){return supersededScopesJson;} public void setSupersededScopesJson(String v){supersededScopesJson=v;}
    public long getRevision(){return revision;}
}
