package com.devmind.docs;

import com.devmind.docs.dto.TemplateView;
import java.util.List;

/** 文档模板（FR-07）：按 kind 预置结构化模板，新建时一键选用。 */
public final class DocTemplates {

    private DocTemplates() {
    }

    public static List<TemplateView> all() {
        return List.of(new TemplateView("requirement", "需求文档模板", REQUIREMENT),
                new TemplateView("design", "技术方案模板", DESIGN),
                new TemplateView("api-suite", "API 测试套件模板", API_SUITE),
                new TemplateView("report", "测试/总结报告模板", REPORT));
    }

    public static TemplateView byKind(String kind) {
        return all().stream().filter(t -> t.kind().equals(kind)).findFirst().orElse(null);
    }

    static final String REQUIREMENT = """
            # 需求文档

            ## 背景与目标
            <!-- 为什么做这件事，期望达到什么结果 -->

            ## 需求描述
            <!-- 功能/非功能需求，分条列出 -->

            ## 验收标准
            <!-- 如何验证需求完成 -->

            ## 范围与边界
            <!-- 本期做/不做 -->

            ## 依赖与风险
            <!-- 依赖的模块/外部系统，已知风险 -->
            """;

    static final String DESIGN = """
            # 技术方案

            ## 背景
            <!-- 关联需求、现状与问题 -->

            ## 目标与非目标
            <!-- 方案要达到什么，明确排除什么 -->

            ## 总体设计
            <!-- 架构图/模块划分/数据流 -->

            ## 详细设计
            <!-- 关键模块实现要点、接口定义、数据模型 -->

            ## 兼容与迁移
            <!-- 变更影响面、兼容策略 -->

            ## 验证方案
            <!-- 测试策略、验收方式 -->
            """;

    static final String API_SUITE = """
            # API 测试套件

            ## 概览
            <!-- 被测接口清单与依赖环境 -->

            ## 前置条件
            <!-- 环境准备、鉴权方式 -->

            ## 用例
            ### 用例 1：正常路径
            - 接口：
            - 请求：
            - 期望：
            ### 用例 2：异常路径
            - 接口：
            - 请求：
            - 期望：

            ## 执行结果
            <!-- 本轮执行结论，关联报告 -->
            """;

    static final String REPORT = """
            # 测试/总结报告

            ## 概述
            <!-- 报告目的、范围、时间 -->

            ## 结果汇总
            | 项 | 结果 |
            | --- | --- |
            | 用例数 |  |
            | 通过 |  |
            | 失败 |  |

            ## 详细结果
            <!-- 逐条列出，含失败分析 -->

            ## 结论与建议
            <!-- 是否可发布、遗留问题 -->
            """;
}
