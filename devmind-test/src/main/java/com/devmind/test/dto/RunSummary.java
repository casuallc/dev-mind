package com.devmind.test.dto;

/** 运行汇总；blocked/error 为 CAP-104 新增，旧构造器保持兼容。 */
public record RunSummary(int total, int passed, int failed, int skipped, int blocked, int error) {

    public RunSummary(int total, int passed, int failed, int skipped) {
        this(total, passed, failed, skipped, 0, 0);
    }
}
