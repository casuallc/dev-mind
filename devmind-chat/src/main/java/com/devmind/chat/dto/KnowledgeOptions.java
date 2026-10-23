package com.devmind.chat.dto;

import java.util.List;

/**
 * CAP-84 FR-16 知识会话检索选项（缺省项按服务端默认）。
 *
 * @param topK            每轮检索块数（1~30；空 = 8）
 * @param docTypes        只检索指定文档类型 TECH/MAINT/FAQ/ARTIFACT（空 = 不限）
 * @param tags            标签过滤（空 = 不限）
 * @param requireCitation 是否要求回答必须标注 [S#]（空 = true；SOLUTION 恒 true）
 * @param template        SOLUTION 方案模板 FAULT/DEPLOY/DEV/FAQ（空 = 按问题关键词自动判别；ANSWER 忽略）
 */
public record KnowledgeOptions(Integer topK, List<String> docTypes, List<String> tags,
                               Boolean requireCitation, String template) {
}
