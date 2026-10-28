package com.devmind.knowledge.model;

import jakarta.persistence.*;

/** Explicit per-product ordering; codes are deliberately not parsed as SemVer. */
@Entity
@Table(name="knowledge_product_versions",uniqueConstraints={
    @UniqueConstraint(columnNames={"product_id","code"}),
    @UniqueConstraint(columnNames={"product_id","sort_order"})})
public class KnowledgeProductVersionEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private String productId;
    @Column(nullable=false) private String productName;
    @Column(nullable=false) private String code;
    @Column(nullable=false) private int sortOrder;
    public Long getId(){return id;}
    public String getProductId(){return productId;} public void setProductId(String v){productId=v;}
    public String getProductName(){return productName;} public void setProductName(String v){productName=v;}
    public String getCode(){return code;} public void setCode(String v){code=v;}
    public int getSortOrder(){return sortOrder;} public void setSortOrder(int v){sortOrder=v;}
}
