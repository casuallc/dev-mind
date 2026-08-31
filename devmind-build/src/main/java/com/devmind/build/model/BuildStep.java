package com.devmind.build.model;

/**
 * 构建步骤（从 CAP-02 build_steps 映射，触发时固化为 snapshot）。
 * 本地执行时 command 为 shell 脚本；远程执行时 command 为脚本模板 code（白名单）。
 */
public record BuildStep(String name, String command, String workingDir, String location) {
}
