package com.devmind.skill.dto;

/** 附件内容视图：元数据 + Base64 内容（文本文件内容同为 Base64 编码的 UTF-8 字节）。 */
public record SkillFileContentView(
        SkillFileView meta,
        String contentBase64) {
}
