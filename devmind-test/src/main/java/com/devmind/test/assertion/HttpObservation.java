package com.devmind.test.assertion;

import java.util.List;
import java.util.Map;

/** HTTP 用例观测结果（断言引擎输入）。header 名统一小写。 */
public record HttpObservation(int status, Map<String, List<String>> headers, String body) {
}
