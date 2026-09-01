package com.devmind.project.dto;

import java.util.List;

/**
 * 通用分页视图（page 从 0 起）。
 */
public record PageView<T>(
        List<T> items,
        long total,
        int page,
        int size) {
}
