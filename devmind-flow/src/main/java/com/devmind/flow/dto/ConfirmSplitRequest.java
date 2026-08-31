package com.devmind.flow.dto;

import java.util.List;

/**
 * 拆分确认请求（CAP-14 FR-07）：人编辑后的工作单元清单，固化时按 dependsOn 下标建 depends_on 边。
 */
public record ConfirmSplitRequest(List<SplitDraftItem> items) {
}
