package com.devmind.common.agent;

import java.util.List;

/**
 * CAP-59 terminal_complete 应答：runner 对 Tab 补全请求的候选回执。
 *
 * @param ok         补全是否成功执行（会话不在本节点/cwd 越界等 → false + error）
 * @param word       被补全的词（前端用它定位替换区间：输入行尾部等于 word 的片段）
 * @param candidates 候选列表（去重排序，上限 100；目录候选带 "/" 后缀）
 * @param error      失败原因（用户可读）
 */
public record TerminalCompleteResult(boolean ok, String word, List<String> candidates, String error) {

    public static TerminalCompleteResult of(String word, List<String> candidates) {
        return new TerminalCompleteResult(true, word, candidates, null);
    }

    public static TerminalCompleteResult failed(String error) {
        return new TerminalCompleteResult(false, "", List.of(), error);
    }
}
