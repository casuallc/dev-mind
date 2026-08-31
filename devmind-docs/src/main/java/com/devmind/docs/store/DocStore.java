package com.devmind.docs.store;

/**
 * 文档存储 SPI（CAP-03 §3）。默认实现=git 仓库（docs-repo）；可换 DB/对象存储。
 */
public interface DocStore {

    /** 写文件并提交，返回 commit sha（无变更时返回当前 HEAD sha，可为空串）。 */
    String write(String relativePath, String content, String message);

    /** 删除文件并提交，返回 commit sha。 */
    String delete(String relativePath, String message);

    /** 读文件内容（不存在返回空串）。 */
    String read(String relativePath);

    /** 当前 HEAD sha（无提交返回空串）。 */
    String headSha();

    /** push 远端备份（best-effort），返回执行结果描述。 */
    String push();
}
