package com.devmind.test.assertion;

import java.util.Map;

/** COMMAND 用例观测结果（断言引擎输入）。files 只含断言涉及的路径。 */
public record CommandObservation(Integer exitCode, String stdout, String stderr, Map<String, FileState> files) {

    public record FileState(boolean exists, long size, String sha256) {
    }
}
