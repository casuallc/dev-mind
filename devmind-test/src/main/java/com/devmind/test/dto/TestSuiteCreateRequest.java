package com.devmind.test.dto;

/** CAP-104：模块/负责人/标签可选；kind 限 api/smoke（script 走 ScriptSuiteRequest）。 */
public record TestSuiteCreateRequest(String name, String kind, Long moduleId, String owner, String tags) {
}
