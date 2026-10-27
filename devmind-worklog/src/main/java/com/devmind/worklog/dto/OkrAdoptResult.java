package com.devmind.worklog.dto;

import java.util.List;

/** CAP-93 FR-04 草稿采纳结果：created=创建的目标总数（O+KR），goals=新建目标视图。 */
public record OkrAdoptResult(int created, List<GoalView> goals) {
}
