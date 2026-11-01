package com.devmind.test.spec;

/** CAP-104 断言结果（固定结构，报告/统计/前端共用）。 */
public record AssertionResult(String source, String selector, String op,
                              String expected, String actual, boolean pass, String message) {
}
