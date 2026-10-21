package com.devmind.task;

import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.model.AsyncTaskRunEntity;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-80 建表口径的跨库回归网（照 {@code devmind-decision-lab} 的 {@code DecisionLabSchemaTest}
 * 先例，只用 Hibernate 元数据导出 DDL，不起 Spring 上下文）。
 *
 * <p>要钉住的都是只在某个库上才炸的错误：裸 {@code @Lob} 在 PG 落成 oid 大对象
 *（auto-commit 读直接抛错）、在 MySQL 落成 tinytext；H2 保留字列名建表即失败。</p>
 */
class AsyncTaskSchemaTest {

    /** async_tasks 的长文本列 */
    private static final String[] TASK_LOB_COLUMNS = {"payload"};

    /** async_task_runs 的长文本列 */
    private static final String[] RUN_LOB_COLUMNS = {"logs"};

    private String exportDdl(Class<?> dialect) {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", dialect.getName())
                .build();
        Metadata metadata = new MetadataSources(registry)
                .addAnnotatedClass(AsyncTaskEntity.class)
                .addAnnotatedClass(AsyncTaskRunEntity.class)
                .buildMetadata();
        String ddl = String.join("\n", new SchemaCreatorImpl(registry)
                .generateCreationCommands(metadata, false)).toLowerCase();
        StandardServiceRegistryBuilder.destroy(registry);
        return ddl;
    }

    /** 取该列的 DDL 片段（从列名到下一个逗号；CREATE TABLE 输出成一行，不能按行切） */
    private static String columnDef(String ddl, String column) {
        int start = ddl.indexOf(" " + column + " ");
        if (start < 0) {
            return "";
        }
        int end = ddl.indexOf(',', start);
        return end < 0 ? ddl.substring(start) : ddl.substring(start, end);
    }

    @Test
    void pgLobColumnsAreInlineTextNotOid() {
        String ddl = exportDdl(PostgreSQLDialect.class);
        for (String column : lobColumns()) {
            assertTrue(columnDef(ddl, column).contains(" text"),
                    "PG 上 " + column + " 应为 text（@Lob 必须配 @JdbcTypeCode(LONGVARCHAR)）");
        }
        assertFalse(ddl.contains(" oid "), "PG 不应出现 oid 大对象列——那会让读写炸在 auto-commit 上");
    }

    @Test
    void mysqlLobColumnsAreLongtext() {
        String ddl = exportDdl(MySQLDialect.class);
        for (String column : lobColumns()) {
            assertTrue(columnDef(ddl, column).contains("longtext"),
                    "MySQL 上 " + column + " 应为 longtext（依赖 @Column(length = 16_777_216)）");
        }
    }

    @Test
    void tablesCreateOnAllThreeDialects() {
        // H2 保留字（version/commit/…）曾两度炸建表；三方言各导一遍即回归网
        for (Class<?> dialect : new Class<?>[] {H2Dialect.class, PostgreSQLDialect.class, MySQLDialect.class}) {
            String ddl = exportDdl(dialect);
            assertTrue(ddl.contains("create table async_tasks"),
                    dialect.getSimpleName() + " 应能建 async_tasks");
            assertTrue(ddl.contains("create table async_task_runs"),
                    dialect.getSimpleName() + " 应能建 async_task_runs");
        }
    }

    private static String[] lobColumns() {
        String[] all = new String[TASK_LOB_COLUMNS.length + RUN_LOB_COLUMNS.length];
        System.arraycopy(TASK_LOB_COLUMNS, 0, all, 0, TASK_LOB_COLUMNS.length);
        System.arraycopy(RUN_LOB_COLUMNS, 0, all, TASK_LOB_COLUMNS.length, RUN_LOB_COLUMNS.length);
        return all;
    }
}
