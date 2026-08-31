package com.devmind.execution.model;

/** 单步骤执行结果：成功与否 + 退出码 + 错误摘要。 */
public record StepResult(boolean ok, int exitCode, String error) {

    public static StepResult failed(int exitCode, String error) {
        return new StepResult(false, exitCode, error);
    }
}
