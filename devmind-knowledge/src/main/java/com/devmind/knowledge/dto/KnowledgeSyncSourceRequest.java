package com.devmind.knowledge.dto;

import java.util.Map;

/**
 * CAP-85 FR-27：新建/更新同步源。kind 决定 config 字段：
 * GIT = {repoPath, ref, docsPath, docType, tags[]}；FEISHU = {integrationId, target, docType, tags[]}。
 */
public record KnowledgeSyncSourceRequest(
        Long kbId,
        String kind,
        String name,
        Map<String, Object> config,
        Boolean enabled) {
}
