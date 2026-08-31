package com.devmind.deploy.model;

import java.util.Map;

/**
 * 部署计划步骤（CAP-09 FR-01）：步骤渲染自项目部署配置 + 执行参数。
 * type 取值参考：artifact（拉取产物）/ backup（备份）/ deploy（部署）/ start（启动）/ health（健康检查）。
 */
public record DeployStep(String name, String type, String templateCode, Map<String, String> params) {
}
