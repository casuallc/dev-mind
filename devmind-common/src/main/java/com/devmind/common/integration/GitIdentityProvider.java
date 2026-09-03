package com.devmind.common.integration;

import java.util.Optional;

/**
 * CAP-24 Git 提交身份解析 SPI：按「用户名 + git 平台 host」解析提交署名（author/committer）。
 * 由 devmind-integration 实现（用户级 Git 凭证），消费方（如 devmind-session 拉起会话注入
 * GIT_AUTHOR_* env）以 {@code ObjectProvider<GitIdentityProvider>} 探测注入，未装配时回退
 * 现状（不注入身份）。
 *
 * <p>token 不出实现模块边界——本 SPI 只暴露署名，不含凭证。</p>
 */
public interface GitIdentityProvider {

    /**
     * 解析提交署名。优先级：用户在该 host 的个人凭证署名 → 用户 displayName/username（email 为 null，
     * 此时只注入 name，email 留给系统 git 配置）；用户不存在返回 empty。
     *
     * @param username 平台用户名（users.username，各表 created_by 口径）
     * @param repoHost 仓库远端 host（如 gitlab.example.com）；null/空 = 无远端，仅做用户级回退
     */
    Optional<GitAuthor> resolveAuthor(String username, String repoHost);

    /** 提交署名三元组（email 可空 = 不注入 GIT_AUTHOR_EMAIL/GIT_COMMITTER_EMAIL）。 */
    record GitAuthor(String name, String email) {}
}
