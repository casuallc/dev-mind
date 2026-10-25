package com.devmind.changelog.model;

/**
 * CAP-87 变更类型（对应 CHANGELOG.md 的二级小节）。
 * 序列化用枚举名，前端负责中文文案与颜色映射。
 */
public enum ChangelogType {
    ADDED,
    IMPROVED,
    FIXED,
    BREAKING
}
