package com.devmind.flow.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 手工导入 wi-plan.json 创建工作单元（CAP-52 补救措施）。
 *
 * @param content wi-plan.json 文件内容（JSON 数组字符串），允许被 ```json 代码围栏包裹
 */
public record ImportWiPlanRequest(@NotBlank String content) {
}
