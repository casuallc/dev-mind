package com.devmind.integration.dto;

import java.util.List;

/**
 * CAP-47 FR-02 切换实例/项目后重拉的选项集合。任一连接器调用失败即整体抛出
 * （用户主动切换，须看到真实错误），不像 {@link JiraPushTargetsView} 那样降级为空表。
 */
public record JiraPushOptionsView(List<JiraOptionView> jiraProjects,
                                  List<JiraOptionView> issueTypes,
                                  List<JiraOptionView> priorities) {
}
