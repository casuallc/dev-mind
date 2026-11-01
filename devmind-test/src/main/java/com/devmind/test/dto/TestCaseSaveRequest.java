package com.devmind.test.dto;

import com.devmind.test.spec.CaseSpec;

/** CAP-104 用例新建/更新：spec 为唯一事实来源，changeNote 记录版本变更原因。 */
public record TestCaseSaveRequest(String changeNote, CaseSpec spec) {
}
