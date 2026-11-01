package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;

/** CAP-104 环境预检报告：每项 expected/actual/message，失败给出具体原因。 */
public record PrecheckReport(boolean passed, Instant checkedAt, List<PrecheckItem> items) {

    public record PrecheckItem(String name, boolean pass, String expected, String actual, String message) {
    }
}
