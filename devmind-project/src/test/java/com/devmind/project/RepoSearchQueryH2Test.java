package com.devmind.project;

import com.devmind.project.model.GitRepositoryEntity;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import com.devmind.project.repo.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-109 FR-01/FR-02 关键字搜索 JPQL 的 H2 内存库回归：大小写不敏感、部分匹配、
 * 空 keyword 返回全部、status 过滤叠加。执行的正是 Repository 接口上 @Query 引用的同一串
 * JPQL 常量（防实现与回归网漂移）。
 *
 * <p>不起 Spring 上下文（全仓无 JPA 切片基建，见 DatasetServiceTest 先例注记）：
 * 直接用 Hibernate 元数据在 H2 内存库建表，照 AsyncTaskSchemaTest 的裸 Hibernate 范式。</p>
 */
class RepoSearchQueryH2Test {

    private StandardServiceRegistry registry;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void setUp() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:cap109;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .build();
        emf = new MetadataSources(registry)
                .addAnnotatedClass(ProjectEntity.class)
                .addAnnotatedClass(GitRepositoryEntity.class)
                .buildMetadata()
                .buildSessionFactory();
        em = emf.createEntityManager();
        em.getTransaction().begin();
        seedProjects();
        seedRepos();
        em.getTransaction().commit();
    }

    @AfterEach
    void tearDown() {
        if (em != null && em.isOpen()) {
            em.close();
        }
        if (emf != null) {
            emf.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private void seedProjects() {
        persist(project("p1", "DevMind 平台", "D:/apusic/dev-mind", "积木式研发能力平台", "ACTIVE",
                Instant.parse("2026-11-01T00:00:00Z")));
        persist(project("p2", "CTYunManager", "D:/apusic/ctyunmanager", "云管平台", "ACTIVE",
                Instant.parse("2026-11-02T00:00:00Z")));
        persist(project("p3", "LegacyPortal", "D:/old/portal", null, "ARCHIVED",
                Instant.parse("2026-11-03T00:00:00Z")));
    }

    private void seedRepos() {
        persist(repo("dev-mind", "D:/apusic/dev-mind", "https://github.com/org/dev-mind.git",
                GitRepositoryEntity.STATUS_ACTIVE, Instant.parse("2026-11-01T00:00:00Z")));
        persist(repo("ctyunmanager", "D:/apusic/ctyunmanager", null,
                GitRepositoryEntity.STATUS_ACTIVE, Instant.parse("2026-11-02T00:00:00Z")));
        persist(repo("old-portal", "D:/old/portal", "https://gitlab.internal/legacy/Old-Portal.git",
                GitRepositoryEntity.STATUS_DISABLED, Instant.parse("2026-11-03T00:00:00Z")));
    }

    private static ProjectEntity project(String id, String name, String path, String description,
                                         String status, Instant createdAt) {
        ProjectEntity e = new ProjectEntity();
        e.setId(id);
        e.setName(name);
        e.setPath(path);
        e.setDescription(description);
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(createdAt);
        return e;
    }

    private static GitRepositoryEntity repo(String name, String localPath, String remoteUrl,
                                            String status, Instant createdAt) {
        GitRepositoryEntity e = new GitRepositoryEntity();
        e.setName(name);
        e.setLocalPath(localPath);
        e.setRemoteUrl(remoteUrl);
        e.setSourceType(GitRepositoryEntity.SOURCE_LOCAL);
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(createdAt);
        return e;
    }

    private void persist(Object entity) {
        em.persist(entity);
    }

    private List<ProjectEntity> projectSearch(String status, String kw) {
        return em.createQuery(ProjectRepository.SEARCH_JPQL, ProjectEntity.class)
                .setParameter("status", status)
                .setParameter("kw", kw)
                .getResultList();
    }

    private List<GitRepositoryEntity> repoSearch(String status, String kw) {
        return em.createQuery(GitRepositoryRepository.SEARCH_JPQL, GitRepositoryEntity.class)
                .setParameter("status", status)
                .setParameter("kw", kw)
                .getResultList();
    }

    private static List<String> projectIds(List<ProjectEntity> rows) {
        return rows.stream().map(ProjectEntity::getId).toList();
    }

    private static List<String> repoNames(List<GitRepositoryEntity> rows) {
        return rows.stream().map(GitRepositoryEntity::getName).toList();
    }

    // ---------------- 项目搜索 ----------------

    @Test
    void projectKeywordIsCaseInsensitive() {
        assertEquals(List.of("p1"), projectIds(projectSearch(null, "devmind")));
        assertEquals(List.of("p1"), projectIds(projectSearch(null, "DEVMIND")));
    }

    @Test
    void projectKeywordPartialMatchesPathAndDescription() {
        // path 部分匹配
        assertEquals(List.of("p2"), projectIds(projectSearch(null, "ctyunmanager")));
        // description（CLOB，查询里 cast）部分匹配
        assertEquals(List.of("p2"), projectIds(projectSearch(null, "云管")));
        // path 片段命中两行（按 createdAt 倒序）
        assertEquals(List.of("p2", "p1"), projectIds(projectSearch(null, "apusic")));
    }

    @Test
    void projectBlankKeywordReturnsAll() {
        assertEquals(List.of("p3", "p2", "p1"), projectIds(projectSearch(null, null)));
        assertEquals(List.of("p3", "p2", "p1"), projectIds(projectSearch(null, "")));
    }

    @Test
    void projectKeywordCombinesWithStatus() {
        assertEquals(List.of("p2", "p1"), projectIds(projectSearch("ACTIVE", "apusic")));
        assertTrue(projectSearch("ARCHIVED", "apusic").isEmpty());
        assertEquals(List.of("p3"), projectIds(projectSearch("ARCHIVED", "portal")));
    }

    // ---------------- 代码库搜索 ----------------

    @Test
    void repoKeywordMatchesNameRemoteUrlAndLocalPath() {
        assertEquals(List.of("dev-mind"), repoNames(repoSearch(null, "dev-mind")));
        // remoteUrl 大小写不敏感（库里 Old-Portal 大写）
        assertEquals(List.of("old-portal"), repoNames(repoSearch(null, "old-portal.git")));
        assertEquals(List.of("old-portal"), repoNames(repoSearch(null, "GITLAB.INTERNAL")));
        // localPath 片段
        assertEquals(List.of("ctyunmanager"), repoNames(repoSearch(null, "ctyunmanager")));
    }

    @Test
    void repoStatusFilterAndBlankKeyword() {
        assertEquals(List.of("ctyunmanager", "dev-mind"),
                repoNames(repoSearch(GitRepositoryEntity.STATUS_ACTIVE, null)));
        assertEquals(List.of("old-portal"),
                repoNames(repoSearch(GitRepositoryEntity.STATUS_DISABLED, null)));
        assertEquals(List.of("old-portal", "ctyunmanager", "dev-mind"), repoNames(repoSearch(null, null)));
        // keyword + status 叠加
        assertTrue(repoSearch(GitRepositoryEntity.STATUS_ACTIVE, "old-portal").isEmpty());
    }
}
