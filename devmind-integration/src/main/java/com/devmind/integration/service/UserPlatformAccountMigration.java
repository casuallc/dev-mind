package com.devmind.integration.service;

import com.devmind.integration.model.IntegrationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CAP-35 FR-05 存量迁移：user_git_credentials（CAP-24）→ user_platform_accounts。
 * 按凭证 base_url host 匹配 Integration；无匹配的 host 自动登记一条实例
 * （type 按 host 含 "github" 判 GITHUB、否则 GITLAB；name=host；无机器人凭证；ENABLED）再绑定。
 * 幂等：(user_id, integration_id) 已存在即跳过；旧表不删（ddl-auto 不管删表），迁移后旧代码已移除。
 * 任何异常只记日志不阻断启动。
 */
@Component
public class UserPlatformAccountMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserPlatformAccountMigration.class);

    private final JdbcTemplate jdbc;

    public UserPlatformAccountMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("user_git_credentials 迁移失败（不阻断启动，可重启重试）: {}", e.getMessage());
        }
    }

    private void migrate() {
        if (!tableExists("user_git_credentials")) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, label, base_url, secret_enc, git_author_name, git_author_email"
                        + " FROM user_git_credentials");
        if (rows.isEmpty()) {
            return;
        }
        int migrated = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            String userId = (String) row.get("user_id");
            String baseUrl = (String) row.get("base_url");
            String host = UserPlatformAccountService.hostOf(baseUrl);
            if (userId == null || host == null) {
                skipped++;
                continue;
            }
            Long integrationId = findOrRegisterIntegration(host, baseUrl);
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_platform_accounts WHERE user_id = ? AND integration_id = ?",
                    Integer.class, userId, integrationId);
            if (exists != null && exists > 0) {
                skipped++;
                continue;
            }
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("INSERT INTO user_platform_accounts"
                            + " (user_id, integration_id, auth_type, secret_enc,"
                            + "  git_author_name, git_author_email, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    userId, integrationId, IntegrationEntity.AUTH_PAT, row.get("secret_enc"),
                    row.get("git_author_name"), row.get("git_author_email"), now, now);
            migrated++;
        }
        log.info("CAP-35 存量迁移完成: user_git_credentials → user_platform_accounts，迁移 {} 条，跳过 {} 条",
                migrated, skipped);
    }

    /** host 匹配 ENABLED 实例；无匹配自动登记一条（§2.5） */
    private Long findOrRegisterIntegration(String host, String baseUrl) {
        List<Map<String, Object>> integrations = jdbc.queryForList(
                "SELECT id, base_url, status FROM integrations");
        for (Map<String, Object> i : integrations) {
            String h = UserPlatformAccountService.hostOf((String) i.get("base_url"));
            if (host.equalsIgnoreCase(h)) {
                return ((Number) i.get("id")).longValue();
            }
        }
        String type = host.contains("github") ? IntegrationEntity.TYPE_GITHUB : IntegrationEntity.TYPE_GITLAB;
        String normalizedBase = normalizeBaseUrl(baseUrl, host);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO integrations (type, name, base_url, auth_type, status, created_by,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                type, host, normalizedBase, IntegrationEntity.AUTH_PAT,
                IntegrationEntity.STATUS_ENABLED, "cap35-migration", now, now);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM integrations WHERE base_url = ?",
                Long.class, normalizedBase);
        log.info("CAP-35 迁移自动登记平台实例: #{} {} {}", id, type, normalizedBase);
        return id;
    }

    private static String normalizeBaseUrl(String baseUrl, String host) {
        String url = baseUrl.trim().replaceAll("/+$", "");
        // 只保留 scheme://host[:port]，丢弃路径（实例地址语义）
        try {
            java.net.URI uri = java.net.URI.create(url);
            StringBuilder sb = new StringBuilder();
            sb.append(uri.getScheme() == null ? "https" : uri.getScheme()).append("://").append(host);
            if (uri.getPort() > 0) {
                sb.append(':').append(uri.getPort());
            }
            return sb.toString();
        } catch (Exception e) {
            return url;
        }
    }

    private boolean tableExists(String table) {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE 1=0", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
