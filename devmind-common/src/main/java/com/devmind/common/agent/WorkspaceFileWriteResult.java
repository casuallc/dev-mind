package com.devmind.common.agent;

/**
 * CAP-115 M2 workspace_file_write 应答：runner 对终端工作区做单文件直写的回执。
 *
 * @param ok        写入是否成功（路径越界/目录创建失败/IO 错误 → false + error）
 * @param path      实际写入的工作区相对 POSIX 路径（成功时非空）
 * @param sha       写入后文件内容的 sha256（供前端下一轮 expectedSha 乐观锁续用）
 * @param conflict  是否 sha 乐观锁冲突（expectedSha 与现内容不符；服务端据此转 409）
 * @param error     失败原因（用户可读）
 */
public record WorkspaceFileWriteResult(boolean ok, String path, String sha,
                                       boolean conflict, String error) {

    public static WorkspaceFileWriteResult ok(String path, String sha) {
        return new WorkspaceFileWriteResult(true, path, sha, false, null);
    }

    public static WorkspaceFileWriteResult conflict(String currentSha) {
        return new WorkspaceFileWriteResult(false, null, currentSha, true,
                "文件已被他人/其他途径修改，请刷新后重试");
    }

    public static WorkspaceFileWriteResult failed(String error) {
        return new WorkspaceFileWriteResult(false, null, null, false, error);
    }
}
