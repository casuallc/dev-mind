# CAP-10 测试执行器（Test Executor）

> 能力 ID：CAP-10 ｜ 分类：执行器 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

部署后的自动化验证：**冒烟测试 + API 测试**。API 测试套件由项目 OpenAPI 文档生成，沉淀为项目资产，后续每次部署自动回归。测试结果形成报告，失败自动转缺陷线索（供流程层建缺陷单）。

## 2. 功能需求

- **FR-01 冒烟测试**：对部署目标做健康检查 + 关键端点可达性（读项目 healthCheck 配置）。
- **FR-02 API 套件生成**：由项目 `apiDocSource`（OpenAPI 文件）自动生成用例：正常流 + 边界 + 鉴权；生成后可人工编辑、增删用例。
- **FR-03 套件沉淀**：API 套件存 docs-repo 的 `api-suite/<project>/`（走 CAP-03），成为项目资产，可版本化。
- **FR-04 执行与报告**：执行后产出测试报告（用例级：通过/失败/跳过 + 请求/响应摘要），挂 test_run；报告留 docs-repo（kind=report）。
- **FR-05 回归开关**：项目配置 `autoRegressionOnDeploy`，开启后每次部署成功自动触发套件回归（流程层调用）。
- **FR-06 失败转缺陷**：失败用例汇总为缺陷线索（标题/请求/期望/实际），供流程层一键建缺陷单并派修复 Agent。

## 3. 插件化接口

- 用例生成 SPI：`ApiTestCaseGenerator`（默认=OpenAPI 解析器）；可扩展（Postman 导入等）。
- 执行 SPI：`TestRunner`（默认=pytest+requests 封装，或轻量内建执行器）。

## 4. 依赖关系

- 依赖：CAP-01、CAP-02（apiDocSource）、CAP-03（套件/报告读写）、CAP-09（部署完成后对目标执行）。
- 被依赖：流程层（自动化验证节点）。

## 5. 数据模型

```
test_suites(id, project_id, name, kind[api|smoke], source, created_at)
test_cases(id, suite_id, method, path, name, params, headers, body,
           expected, enabled, updated_at)
test_runs(id, deployment_id?, project_id, suite_ids, status,
          summary, report_ref, started_at, finished_at, triggered_by)
test_case_results(id, run_id, case_id, status[pass|fail|skip], request_summary,
                  response_summary, error, duration)
```

## 6. API 概要

```
GET    /projects/{id}/api-doc              读取/更新 apiDocSource
POST   /projects/{id}/test-suites/generate 从 OpenAPI 生成套件
CRUD   /test-suites/{id}/cases             用例管理
POST   /tests/runs                         {projectId, suiteIds, deploymentId?}
GET    /test-runs/{id}                     运行状态
GET    /test-runs/{id}/report             测试报告
POST   /test-runs/{id}/issues             失败一键转缺陷线索
```

## 7. 验收标准

- 从项目的 OpenAPI 文档生成一套可执行的 API 用例；
- 对部署目标执行冒烟 + API 测试，报告用例级明细；
- 失败用例能一键生成缺陷线索；
- 项目开启回归开关后，流程层部署完成会自动触发套件。

## 8. MVP 范围（暂不做）

UI E2E（Playwright，后置）、性能/并发测试、用例执行历史对比趋势。
