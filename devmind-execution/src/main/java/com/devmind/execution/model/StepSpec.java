package com.devmind.execution.model;

/**
 * 执行步骤（触发时固化为 snapshot，JSON 字段名保持稳定以兼容历史快照）。
 * 本地执行时 command 为 shell 脚本；远程执行时 command 为脚本模板 code（白名单）。
 */
public record StepSpec(String name, String command, String workingDir, String location) {

    public static final String LOCAL = "LOCAL";
    public static final String REMOTE = "REMOTE";
}
