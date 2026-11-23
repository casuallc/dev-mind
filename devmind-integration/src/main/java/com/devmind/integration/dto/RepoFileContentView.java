package com.devmind.integration.dto;

/**
 * CAP-113 FR-02 单文件原文视图。ref 为实际取内容的 git 引用（提交 sha 或其父提交 sha^）；
 * size 为文件字节数（git cat-file -s）；超 512KB 不拉内容 truncated=true（content 为空串）；
 * 前 8K 字符含 NUL 判 binary=true（content 置空串）。
 */
public record RepoFileContentView(String path, String ref, long size,
                                  boolean binary, boolean truncated, String content) {
}
