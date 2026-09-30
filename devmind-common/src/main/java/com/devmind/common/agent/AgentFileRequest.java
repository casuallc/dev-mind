package com.devmind.common.agent;

import java.util.List;

/**
 * CAP-65 file 帧请求：节点白名单根目录内的文件操作指令。
 *
 * <p>op 语义见 {@link #OP_LIST} 等常量；各 op 使用字段：</p>
 * <ul>
 *   <li>list：root/path（path="" = 根本身）</li>
 *   <li>read：root/path（≤512KB 文本，NUL 嗅探拒二进制）</li>
 *   <li>write：root/path/content（UTF-8 ≤512KB，原子写）</li>
 *   <li>rename：root/path/newName（同目录改名，newName 禁含 / \ :）</li>
 *   <li>delete：root/path/recursive（非空目录须 recursive=true）</li>
 *   <li>upload：root/path/transferId/size/sha256（runner 主动 HTTP 拉中转文件）</li>
 *   <li>download：root/path/transferId（runner 主动 HTTP 推字节到中转端点）</li>
 * </ul>
 */
public record AgentFileRequest(String op, String root, String path, String newName,
                               String content, Boolean recursive, String transferId,
                               Long size, String sha256) {

    /** 单层目录列表 */
    public static final String OP_LIST = "list";
    /** 文本读取 */
    public static final String OP_READ = "read";
    /** UTF-8 文本保存（原子写） */
    public static final String OP_WRITE = "write";
    /** 同目录改名 */
    public static final String OP_RENAME = "rename";
    /** 删文件/目录（非空目录须 recursive=true） */
    public static final String OP_DELETE = "delete";
    /** 大文件写（HTTP 中转拉取） */
    public static final String OP_UPLOAD = "upload";
    /** 大文件读（HTTP 中转推送） */
    public static final String OP_DOWNLOAD = "download";

    /** 全部 op（服务端组帧前校验用） */
    public static final List<String> ALL_OPS = List.of(OP_LIST, OP_READ, OP_WRITE, OP_RENAME,
            OP_DELETE, OP_UPLOAD, OP_DOWNLOAD);

    public static AgentFileRequest list(String root, String path) {
        return new AgentFileRequest(OP_LIST, root, path, null, null, null, null, null, null);
    }

    public static AgentFileRequest read(String root, String path) {
        return new AgentFileRequest(OP_READ, root, path, null, null, null, null, null, null);
    }

    public static AgentFileRequest write(String root, String path, String content) {
        return new AgentFileRequest(OP_WRITE, root, path, null, content, null, null, null, null);
    }

    public static AgentFileRequest rename(String root, String path, String newName) {
        return new AgentFileRequest(OP_RENAME, root, path, newName, null, null, null, null, null);
    }

    public static AgentFileRequest delete(String root, String path, boolean recursive) {
        return new AgentFileRequest(OP_DELETE, root, path, null, null, recursive, null, null, null);
    }

    public static AgentFileRequest upload(String root, String path, String transferId,
                                          long size, String sha256) {
        return new AgentFileRequest(OP_UPLOAD, root, path, null, null, null, transferId, size, sha256);
    }

    public static AgentFileRequest download(String root, String path, String transferId) {
        return new AgentFileRequest(OP_DOWNLOAD, root, path, null, null, null, transferId, null, null);
    }
}
