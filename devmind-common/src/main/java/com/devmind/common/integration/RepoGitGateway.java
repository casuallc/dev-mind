package com.devmind.common.integration;

import java.util.Optional;

/**
 * CAP-25/26 仓库 Git 网关 SPI：跨模块的 git 凭据解析与 fetch。
 * 由 devmind-integration 实现（凭据解密不出模块边界；resolveToken 的返回值随 CAP-25
 * launch 帧下发 runner 属设计内通道，接收方仅内存持有）。消费方以
 * {@code ObjectProvider<RepoGitGateway>} 探测注入，未装配时降级为纯本地库行为。
 */
public interface RepoGitGateway {

    /**
     * CAP-25：解析指定远端 host 的 git 凭据。优先级：actor 个人 PAT（host 匹配，CAP-24）→
     * 项目绑定 Integration token。两者皆无返回 empty（调用方应降级，不得匿名 push）。
     *
     * @param actor    操作人（users.username 口径，可空 = 跳过个人凭证）
     * @param repoHost 仓库远端 host（可空 = 跳过个人凭证）
     * @param projectId 项目 ID（绑定 Integration 反查用，可空 = 跳过绑定回退）
     */
    Optional<String> resolveToken(String actor, String repoHost, String projectId);

    /**
     * CAP-26：fetch 指定 ref 到 FETCH_HEAD（凭据按 resolveToken 同优先级解析，匿名兜底）。
     *
     * @param repoPath 本地仓库路径（据此反查 project_repos 得 remoteUrl 与项目绑定）
     * @param ref      分支名；空 = 不指定 refspec（fetch 全部）
     * @param actor    操作人（个人凭证匹配用，可空）
     * @return false = 该库无 remoteUrl（纯本地库，未执行任何操作）；true = fetch 成功
     * @throws com.devmind.common.exception.DevMindException fetch 失败（网络/凭据），
     *         调用方按「执行前同步失败 = 执行失败」处理
     */
    boolean fetch(String repoPath, String ref, String actor);
}
