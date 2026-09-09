package com.devmind.execution.model;

/**
 * 执行步骤（触发时固化为 snapshot，JSON 字段名保持稳定以兼容历史快照）。
 * command 一律为渲染后的完整 shell 脚本串：LOCAL 由 LocalStepRunner 本机执行；
 * location 兼作能力域标签（build/deploy/release/test…）写入执行审计。
 */
public record StepSpec(String name, String command, String workingDir, String location) {

    public static final String LOCAL = "LOCAL";
}
