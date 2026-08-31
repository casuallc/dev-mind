package com.devmind.flow;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.flow.dto.SplitDraftItem;

import java.util.List;

/**
 * 拆分清单校验（CAP-14 FR-07）：dependsOn 下标越界与环检测。
 * 纯函数，供 confirmSplit 提交前调用；独立成类便于单测。
 */
public final class SplitPlanValidator {

    private SplitPlanValidator() {
    }

    /** 校验清单：下标引用合法且无环；非法时抛 BAD_REQUEST，消息指明问题项。 */
    public static void validate(List<SplitDraftItem> items) {
        if (items == null || items.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "拆分清单不能为空");
        }
        for (int i = 0; i < items.size(); i++) {
            SplitDraftItem it = items.get(i);
            if (it.title() == null || it.title().isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "第 " + (i + 1) + " 项缺少标题");
            }
            if (it.dependsOn() == null) {
                continue;
            }
            for (int dep : it.dependsOn()) {
                if (dep < 0 || dep >= items.size()) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "第 " + (i + 1) + " 项依赖了下标 " + dep + "，超出清单范围");
                }
                if (dep == i) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "第 " + (i + 1) + " 项不能依赖自身");
                }
            }
        }
        // DFS 三色标记环检测：边 i -> j 表示 i 依赖 j
        int[] mark = new int[items.size()]; // 0=未访问 1=访问中 2=已完成
        for (int i = 0; i < items.size(); i++) {
            if (mark[i] == 0) {
                dfs(items, i, mark);
            }
        }
    }

    private static void dfs(List<SplitDraftItem> items, int i, int[] mark) {
        mark[i] = 1;
        if (items.get(i).dependsOn() != null) {
            for (int dep : items.get(i).dependsOn()) {
                if (mark[dep] == 1) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "依赖存在环：第 " + (i + 1) + " 项与第 " + (dep + 1) + " 项相互（间接）依赖");
                }
                if (mark[dep] == 0) {
                    dfs(items, dep, mark);
                }
            }
        }
        mark[i] = 2;
    }
}
