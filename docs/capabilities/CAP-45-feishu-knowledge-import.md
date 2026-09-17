# CAP-45 飞书文档对接（知识库导入）

> 状态：定稿（2026-09-17）｜依赖：CAP-44（知识库容器化与向量检索）
> 目标：把飞书文档（docx/wiki/旧版 doc）作为知识条目的外部来源，手动选择导入到指定知识库，
> 支持变更检测与手动重同步；条目经 CAP-44 摄入管线自动分块向量化，参与 RAG 检索。

## 背景

团队大量规范/方案沉淀在飞书。CAP-44 已把知识库做成「库容器 + 条目 + 向量索引」，
但条目只能手工维护（source=manual）。本 CAP 打通飞书文档 → 知识条目的导入链路。
**范围拍板：手动选文档导入，不做空间/目录自动同步。**

## 术语

| 词 | 含义 |
|---|---|
| 飞书集成 | integrations 表 TYPE_FEISHU 记录（自建应用 appId/appSecret，AES-GCM 密文落库） |
| node token | 飞书 wiki 节点 token（URL `/wiki/{token}`）；docx/doc 文档 token（URL `/docx/{token}`、`/docs/{token}`） |

## 功能需求

### FR-01 飞书集成配置

- `integrations` 增类型 `FEISHU`：baseUrl 默认 `https://open.feishu.cn`（私有部署可改）；
  authType 固定 BASIC 双行密文惯例：secretEnc = `"appId\nappSecret"`（表单 username=App ID、token=App Secret）。
- 连接测试 = 获取 `tenant_access_token` 成功（校验 appId/appSecret 有效）。
- 凭证红线不变：密文仅 IntegrationCipher 加解密，视图不回显、日志/异常消息不带 token。

### FR-02 文档拉取（integration 模块，经 common SPI 暴露）

- common 新增 `FeishuDocFetcher` SPI（防 knowledge→integration 反向依赖）：
  - `listIntegrations()` → 可用 FEISHU 集成清单（id/name）；
  - `fetch(integrationId, url)` → `FeishuDoc{externalId, title, contentMd, docType, sourceUrl}`。
- URL 解析：`/wiki/{nodeToken}`、`/docx/{docToken}`、`/docs/{docToken}`（去 query/fragment 归一化）。
- wiki：`GET /wiki/v2/spaces/get_node?token=` 解析出 objType/objToken；objType=docx → docx 拉取。
- docx：`GET /docx/v1/documents/{id}`（标题）+ `blocks` 分页拉全 → **blocks→markdown 转换器**：
  覆盖 page/heading1~9/text/bullet/ordered/code/quote/divider/table（pipe 表）/todo；
  text_run 支持 bold/italic/strikethrough/inline_code/link；未知块降级纯文本不炸。
- 旧版 doc：`GET /doc/v2/{docToken}/raw_content` → 纯文本。
- tenant_access_token 内存缓存（过期前 5 分钟刷新），不持久化。

### FR-03 导入到知识库

- `POST /api/knowledge/bases/{kbId}/import/feishu` `{integrationId, urls[]}`：
  逐条拉取转 markdown → 建/更新条目：
  - `source=feishu`、`externalId="{integrationId}:{docToken}"`（判重键）、`path`=归一化来源 URL；
  - 同库同 externalId 已存在 → `content_hash` 相同跳过（unchanged），不同则更新内容（updated）；
  - 新建/更新后走既有保存路径（contentHash 维护 + EntryContentChangedEvent）→ 自动重建索引；
  - 单条失败不阻断其他 URL，结果逐条返回 `{url, status: created|updated|unchanged|failed, entryId?, error?}`。

### FR-04 手动重同步

- `POST /api/knowledge/entries/{id}/resync`：source=feishu 条目按 path 存的来源 URL 重拉，
  内容变更才更新+重索引；未变返回 unchanged；飞书侧文档已删/无权限 → failed 并保留旧内容。

### FR-05 前端

- 平台集成页：类型选项加 `FEISHU`（表单 username→「App ID」、token→「App Secret」，baseUrl 预填官方地址）。
- 知识库详情页加「飞书导入」视图：选集成 + 粘贴 URL 列表（每行一条）→ 导入结果逐条展示
  （成功/未变更/失败原因）；条目表格 source=feishu 行操作加「重同步」。

## 非目标

- 飞书空间/目录级自动同步、webhook 变更推送（后续 CAP 再议）。
- 飞书文档内图片/附件下载（图片链接保留原文，不物化）。
- 多人协作评论/历史版本拉取。

## 数据与接口

- 复用 `knowledge_entries` 既有列：source/external_id/content_hash/path/index_status（CAP-44 已备），无 schema 变更。
- 新增 REST：
  - `GET  /api/knowledge/feishu/integrations` → [{id, name}]
  - `POST /api/knowledge/bases/{kbId}/import/feishu` `{integrationId, urls[]}` → 逐条结果
  - `POST /api/knowledge/entries/{id}/resync` → {status, entryId?}

## 验证

- 单测：blocks→markdown 转换器（标题/列表/代码/引用/表格/行内样式/未知块降级）；
  导入服务（判重跳过/变更更新+事件/失败逐条隔离）；resync（变更才更新、文档消失保留旧内容）。
- E2E（tests/fixtures/feishu-mock.js 假飞书端：token/wiki/docx blocks）：起 app（mock embedding）→
  建 FEISHU 集成（baseUrl 指 mock）→ 建库导入 → 断言条目 source/externalId/索引 ready/检索命中 →
  改 mock 文档内容重同步断言更新、同内容重同步断言 unchanged。
- 手测：真实飞书自建应用（凭证写 application-local.yml 或 UI 录入，禁提交）。
