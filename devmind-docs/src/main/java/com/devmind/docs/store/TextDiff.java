package com.devmind.docs.store;

import com.devmind.docs.dto.DiffView;
import java.util.ArrayList;
import java.util.List;

/**
 * 行级文本 diff（CAP-03 FR-02）：LCS 最长公共子序列，输出简化 unified 行（+/ -/空格）。
 * 大文件（行数乘积超阈值）退化为整块替换，避免 O(n*m) 内存爆掉。
 */
public final class TextDiff {

    private static final int LCS_LIMIT = 2_000_000;

    private TextDiff() {
    }

    public static DiffView diff(String oldText, String newText) {
        List<String> a = lines(oldText);
        List<String> b = lines(newText);
        if (a.size() * b.size() > LCS_LIMIT) {
            return wholeReplace(a, b);
        }
        int[][] dp = lcsTable(a, b);
        List<String> out = new ArrayList<>();
        int i = a.size(), j = b.size(), add = 0, del = 0;
        while (i > 0 && j > 0) {
            if (a.get(i - 1).equals(b.get(j - 1))) {
                out.add(0, " " + a.get(i - 1));
                i--; j--;
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                out.add(0, "-" + a.get(--i));
                del++;
            } else {
                out.add(0, "+" + b.get(--j));
                add++;
            }
        }
        while (i > 0) { out.add(0, "-" + a.get(--i)); del++; }
        while (j > 0) { out.add(0, "+" + b.get(--j)); add++; }
        return new DiffView(add + del > 0, out, add, del);
    }

    private static DiffView wholeReplace(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        for (String l : a) { out.add("-" + l); }
        if (!a.isEmpty() && !b.isEmpty()) { out.add("@@ 整体变更 @@"); }
        for (String l : b) { out.add("+" + l); }
        return new DiffView(!a.equals(b), out, b.size(), a.size());
    }

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            String ai = a.get(i - 1);
            for (int j = 1; j <= m; j++) {
                dp[i][j] = ai.equals(b.get(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp;
    }

    private static List<String> lines(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        // 保留行尾 \r（Windows 编辑差异），统一去掉
        return new ArrayList<>(List.of(s.replace("\r\n", "\n").split("\n", -1)));
    }
}
