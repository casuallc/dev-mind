package com.devmind.test.dto;

import java.util.Map;

/**
 * CAP-69 触发脚本套件运行。三个字段全可选：agentNodeId 空=走路由链（套件默认→平台默认）；
 * env 覆盖仅本次生效（不落库）；command 空=用套件命令模板。
 */
public record ScriptSuiteRunRequest(String agentNodeId, Map<String, String> env, String command) {
}
