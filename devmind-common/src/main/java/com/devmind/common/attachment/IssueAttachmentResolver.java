package com.devmind.common.attachment;

import java.util.Optional;

/**
 * CAP-40 外部平台（Jira）附件解析 SPI（common 定义、devmind-integration 实现）：
 * 把「需求关联 issue 的内嵌附件」按文件名解析为字节，供上下文装配投送到 worktree。
 * 消费方（devmind-flow 的需求附件 provider）以 {@code ObjectProvider<IssueAttachmentResolver>}
 * 探测注入，未装配 = 无外部附件源（降级跳过，不报错）。
 */
public interface IssueAttachmentResolver {

    /**
     * 按需求关联的外部 issue + 文件名读附件字节；需求无外部关联/附件不存在/拉取失败返回 empty
     * （远程拉取失败不应阻断装配，由调用方在清单标注「不可用」）。
     */
    Optional<IssueAttachment> resolve(String requirementId, String filename);

    /**
     * @param filename    附件文件名；@param contentType 原始 mime（可空）；
     * @param bytes       原始字节；@param issueKey 来源 issue key（清单标注用）
     */
    record IssueAttachment(String filename, String contentType, byte[] bytes, String issueKey) {
    }
}
