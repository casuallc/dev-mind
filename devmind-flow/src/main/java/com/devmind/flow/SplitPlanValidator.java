package com.devmind.flow;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.flow.dto.SplitDraftItem;

import java.util.List;

/**
 * 拆分清单校验（CAP-14 FR-07）：dependsOn 下标越界与环检测。
 * 纯函数，自动固化（handleSplitOutput）前调用；独立成类便于单测。
 */
public final class SplitPlanValidator {

    /**
     * CAP-52 FR-03 工作单元条数硬上限。prompt 的目标是 1~3 条，这里只是护栏：拆得越细，
     * 清单本身越长、验收与构建的触发次数越多，而「一个会话做完整个需求」的收益会被摊薄。
     * 超限不截断（截断等于把模型没打算合并的东西硬合），走降级通知让人决定。
     */
    public static final int MAX_ITEMS = 5;

    private SplitPlanValidator() {
    }

    /** 校验清单：条数 + 下标引用合法且无环；非法时抛 BAD_REQUEST，消息指明问题项。 */
    public static void validate(List<SplitDraftItem> items) {
        if (items == null || items.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "拆分清单不能为空");
        }
        if (items.size() > MAX_ITEMS) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "工作单元 " + items.size() + " 条，超过上限 " + MAX_ITEMS
                            + "（拆分粒度过细：一个工作单元应为一次可独立验收的改动）");
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
