package com.devmind.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * chat_knowledge_refs（CAP-84 FR-19）：每轮检索命中的证据快照。
 * 条目后续被改/删也不影响历史引用回溯。
 */
@Entity
@Table(name = "chat_knowledge_refs",
        indexes = @Index(name = "idx_chat_knowledge_ref", columnList = "chat_id, turn_seq"))
public class ChatKnowledgeRefEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", length = 32, nullable = false)
    private String chatId;

    /** 本轮 user 事件 seq（前端按事件 seq 关联引用） */
    @Column(name = "turn_seq", nullable = false)
    private Long turnSeq;

    /** [S1] 里的 1 */
    @Column(name = "citation_no", nullable = false)
    private Integer citationNo;

    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "kb_id")
    private Long kbId;

    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(name = "entry_name", length = 255)
    private String entryName;

    @Column(name = "source_uri", length = 1024)
    private String sourceUri;

    @Column(length = 2000)
    private String snippet;

    private Double score;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public Long getTurnSeq() { return turnSeq; }
    public void setTurnSeq(Long turnSeq) { this.turnSeq = turnSeq; }
    public Integer getCitationNo() { return citationNo; }
    public void setCitationNo(Integer citationNo) { this.citationNo = citationNo; }
    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public String getEntryName() { return entryName; }
    public void setEntryName(String entryName) { this.entryName = entryName; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
