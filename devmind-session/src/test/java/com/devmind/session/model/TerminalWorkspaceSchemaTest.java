package com.devmind.session.model;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-115 终端工作区实体 schema 回归（LobColumnTypeTest 同手法，真实 schema 导出）：
 * repos_json/finalize_detail 必须按 @Lob+LONGVARCHAR+length=16M 约定落列——
 * PG 出 text（禁 oid 大对象），MySQL 出 longtext。
 */
class TerminalWorkspaceSchemaTest {

    private String exportDdl(Class<?> dialect) {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", dialect.getName())
                .build();
        Metadata metadata = new MetadataSources(registry)
                .addAnnotatedClass(TerminalWorkspaceEntity.class)
                .addAnnotatedClass(TerminalSessionEntity.class)
                .buildMetadata();
        String ddl = String.join("\n", new SchemaCreatorImpl(registry)
                        .generateCreationCommands(metadata, false))
                .toLowerCase();
        StandardServiceRegistryBuilder.destroy(registry);
        return ddl;
    }

    @Test
    void pgLobColumnsAreInlineText() {
        String ddl = exportDdl(PostgreSQLDialect.class);
        assertTrue(ddl.contains("repos_json text"), "PG repos_json 应为 text，DDL: " + ddl);
        assertTrue(ddl.contains("finalize_detail text"), "PG finalize_detail 应为 text，DDL: " + ddl);
        assertTrue(!ddl.contains(" oid"), "PG 不应出现 oid 大对象列，DDL: " + ddl);
    }

    @Test
    void mysqlLobColumnsAreLongtext() {
        String ddl = exportDdl(MySQLDialect.class);
        assertTrue(ddl.contains("repos_json longtext"), "MySQL repos_json 应为 longtext，DDL: " + ddl);
        assertTrue(ddl.contains("finalize_detail longtext"), "MySQL finalize_detail 应为 longtext，DDL: " + ddl);
    }
}
