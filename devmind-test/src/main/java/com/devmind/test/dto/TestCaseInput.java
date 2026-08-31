package com.devmind.test.dto;

import java.util.Map;

/**
 * 用例写入（新建/更新）。id 为空表示新建；saveCases 整体替换语义：不在列表中的现有用例被删除。
 * expected 示例：http → {"status":200,"contains":"…"}（status 可 "2XX"）；health → {"type":"http"/"command",…}。
 */
public record TestCaseInput(
        Long id,
        String name,
        String kind,
        String method,
        String path,
        Map<String, String> params,
        Map<String, String> headers,
        String body,
        Map<String, Object> expected,
        Boolean enabled) {
}
