package com.devmind.integration.repo;

import com.devmind.integration.model.JiraPushTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * CAP-47 FR-10：个人 Jira 推送模板（唯一键 = 用户 + 实例 + Jira 项目 + 任务类型）。
 */
public interface JiraPushTemplateRepository extends JpaRepository<JiraPushTemplateEntity, Long> {

    List<JiraPushTemplateEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<JiraPushTemplateEntity> findByUserIdAndIntegrationIdAndJiraProjectKeyAndIssueTypeId(
            String userId, Long integrationId, String jiraProjectKey, String issueTypeId);
}
