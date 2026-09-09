package com.devmind.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死全仓 @Lob 约定赖以成立的跨库 DDL（Hibernate 方言升级漂移的回归网）：
 *
 * <p>约定：{@code @Lob + @JdbcTypeCode(SqlTypes.LONGVARCHAR)}（byte[] 用 LONGVARBINARY）
 * + {@code @Column(length = 16_777_216)}。裸 @Lob（CLOB/BLOB）在 PG 落成 oid 大对象——
 * auto-commit 读抛 "Large Objects may not be used in auto-commit mode"、lower()/cast 渲染 bytea；
 * 在 MySQL 需 length 防 tinytext。本测试用真实 schema 导出验证 PG 出 text/bytea、MySQL 出 longtext/longblob。</p>
 */
class LobColumnTypeTest {

    @Entity
    @Table(name = "lob_probe")
    static class LobProbeEntity {
        @Id
        private Long id;

        @Lob
        @JdbcTypeCode(SqlTypes.LONGVARCHAR)
        @Column(name = "content_text", length = 16_777_216)
        private String contentText;

        @Lob
        @JdbcTypeCode(SqlTypes.LONGVARBINARY)
        @Column(name = "content_bytes", length = 16_777_216)
        private byte[] contentBytes;
    }

    private String exportDdl(Class<?> dialect) {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", dialect.getName())
                .build();
        Metadata metadata = new MetadataSources(registry)
                .addAnnotatedClass(LobProbeEntity.class)
                .buildMetadata();
        String ddl = String.join("\n", new SchemaCreatorImpl(registry)
                        .generateCreationCommands(metadata, false))
                .toLowerCase();
        StandardServiceRegistryBuilder.destroy(registry);
        return ddl;
    }

    @Test
    void pgLobColumnsAreInlineTextAndBytea() throws Exception {
        String ddl = exportDdl(PostgreSQLDialect.class);
        assertTrue(ddl.contains("content_text text"), "PG content_text 应为 text，DDL: " + ddl);
        assertTrue(ddl.contains("content_bytes bytea"), "PG content_bytes 应为 bytea，DDL: " + ddl);
        assertTrue(!ddl.contains("oid"), "PG 不应出现 oid 大对象列，DDL: " + ddl);
    }

    @Test
    void mysqlLobColumnsAreLongtextAndLongblob() throws Exception {
        String ddl = exportDdl(MySQLDialect.class);
        assertTrue(ddl.contains("content_text longtext"), "MySQL content_text 应为 longtext，DDL: " + ddl);
        assertTrue(ddl.contains("content_bytes longblob"), "MySQL content_bytes 应为 longblob，DDL: " + ddl);
    }
}
