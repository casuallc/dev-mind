package com.devmind.classify.instance.dto;

import java.util.Map;

/**
 * CAP-57 实例创建/更新请求。布尔/数值用包装类型（Jackson 3 红线），校验在 service 层
 * （与 decision-lab 同口径，错误话术集中）。
 *
 * @param commandOverride 命令覆盖（空格分隔 argv，无 shell 语义）；空 = 默认 uvicorn 启动
 * @param env             环境变量（值支持 {@code ${PKG_DIR:<packageId>}} 占位符）
 */
public record ClassifyInstanceRequest(String name, String agentNodeId, Integer port, String baseUrl,
                                      Long appPackageId, String pythonBin, Map<String, String> env,
                                      String commandOverride) {
}
