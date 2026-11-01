package com.devmind.test.dto;

import java.util.List;

/** CAP-104 保存结果：同签名疑似重复用例只提示，不自动合并。 */
public record CaseSaveResult(TestCaseView testCase, List<TestCaseView> duplicates) {
}
