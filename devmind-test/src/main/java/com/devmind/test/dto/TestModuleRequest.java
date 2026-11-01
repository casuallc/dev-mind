package com.devmind.test.dto;

/** CAP-104 功能模块写入。 */
public record TestModuleRequest(String name, String description, String tags, String owner, Integer sortNo) {
}
