# CAP-64 收藏夹（网址收藏与账号速查）

> 能力 ID：CAP-64 ｜ 分类：组装层 ｜ 状态：需求定稿（2026-09-23），待实施。

## 1. 目的

研发日常散落在浏览器书签、聊天记录、笔记里的各类网址（Nexus、Jira、GitLab、监控台、
各环境控制台、内部系统入口……）缺乏统一归属：换浏览器/换机器即丢失，环境地址靠口口相传，
关联账号记在别处。平台需要一个**绑定到人的收藏夹能力**：

1. **集中收藏**：网址（标题 + URL + 描述）统一入库，分组组织、标签横切，随处可用；
2. **可维护**：编辑、删除、转移分组、批量整理，收藏可一键浏览器打开；
3. **活性可感知**：手动/批量探测收藏是否可用（HTTP 状态、耗时、最近探测时间），
   失效入口一眼识别；
4. **账号速查**：每条收藏可挂多条关联账号记录（如「测试环境控制台 → 测试账号」），
   密码密文存储，与收藏同生命周期；
5. **可分享**：单个收藏或整个分组可分享给平台内指定用户（只读），团队入口沉淀不靠转发链接。

**定位**：个人效率工具（与 CAP-28 工作日志、CAP-30 通用问答同属「keyed 到人」的个人数据），
归属与越权防护直接复用 CAP-62 FR-04 的 owner 强制口径；不承载项目资产，不进项目上下文。

### 非目标（v1 不做）

- 浏览器插件（浏览器内一键收藏到平台；书签**文件**导入已落地，见 FR-09）；
- 匿名公开分享链接（免登录访问）；
- 网页快照/内容存档（只存元数据，不抓正文）；
- 收藏内容进 RAG/知识库（与 CAP-04/44 无管道；后续如需经上下文装配另立 CAP）；
- 定时自动探测调度（v1 仅手动触发；调度器另评）；
-  favicon 自动抓取与缓存（v1 手动填或留空，前端用首字母占位）。

## 2. 功能需求

### FR-01 收藏条目 CRUD

- 收藏字段：`title`（必填）、`url`（必填，http/https 校验）、`description`（可选）、
  `group_id`（可空 = 未分组）、排序号；
- 创建/编辑/删除均由 owner 本人在「个人组」入口操作；列表按分组树 + 标签筛选 +
  关键词（标题/URL/描述）搜索；
- 同 URL 重复收藏不强制拦截（不同环境参数合法），保存时提示已存在的同 URL 条目；
- 前端布局遵循 `docs/core/前端内容区布局约定.md`（Card + 分组树侧栏 + 表格/卡片列表）。

### FR-02 分组与转移分组

- 分组为**树状**（`parent_id` 可空，深度不限制，前端建议两级内使用），归属 owner；
  分组字段：`name`、排序号；
- 分组 CRUD；删除分组二选一：收藏移到未分组（默认）/ 级联删除组内收藏（显式勾选确认）；
- 分组树侧栏支持折叠/展开（折叠集本地记忆，默认全展开）与拖拽更改父分组
  （禁拖入自己的子树；服务端 `PUT /bookmark-groups/{id}` 成环校验兜底，违例 400）；
- 收藏转移分组：单条改 `group_id` + 列表多选批量转移（目标含「未分组」）；
- 分组树按 owner 隔离，互相不可见（含同名分组）。

### FR-03 标签

- 标签归属 owner（`(owner_id, name)` 唯一），自由创建、改名、删除；
- 收藏 × 标签多对多；编辑收藏时多选挂标签；列表可按单/多标签过滤；
- 删除标签仅解除关联，不动收藏。

### FR-04 可用性探测

- 探测动作：单条「探测」按钮 + 当前筛选结果「批量探测」（后端异步逐条执行，上限一次
  200 条）；
- 实现：服务端出站 HTTP——先 `HEAD`，405/501 回落 `GET`（`Range: bytes=0-0`，不取正文）；
  超时 10s、最多跟随 3 次重定向、仅允许 http/https（拒绝 file/内网保留段提示由配置开关
  `devmind.bookmark.probe.allow-private` 控制，默认允许——内部系统恰是主要收藏对象）；
- 结果落库：`last_status`（`UNKNOWN`/`OK`/`FAIL`）、`last_status_code`、`last_latency_ms`、
  `last_checked_at`；2xx/3xx 判 OK，其余与连接异常判 FAIL（异常原因入 code 同义描述）；
- 列表展示状态点（绿/红/灰）+ 最近探测时间（`fmtTime`）；批量探测进行中前端轮询刷新。

### FR-05 关联账号

- 每条收藏可挂多条账号记录：`label`（如「管理员」「只读账号」）、`username`、
  `password`（可选，**密文存储**，复用 common `SecretCipher`）、`note`、排序号；
- 账号随收藏级联删除；接口出参密码恒脱敏（`******`），明文仅在「查看」动作单独端点
  按次返回（owner 本人，前端点击眼睛图标触发）；
- 编辑账号为整组提交（增删改一次保存），不单独建账号生命周期。

### FR-06 浏览器打开

- 列表每行「打开」动作 `window.open(url, '_blank', 'noopener')`；卡片视图整卡可点；
- 打开动作落 `last_visited_at`（排序「最近访问」可用；不另建访问日志表）。

### FR-07 分享

- 分享对象：单条收藏或整个分组；分享给**平台内指定用户**（username 选择）；
- 语义为**只读引用**：接收方「与我分享」区看到分享者的收藏/分组内容（含账号记录的
  label/username，**密码恒不分享**——出参直接剔除该字段）；
- 源数据变更（编辑/删收藏/改分组）实时反映给接收方；撤销分享立即不可见；
- 接收方不可改不可再分享；可把分享来的收藏**复制**为自己的收藏（深拷贝条目+标签名，
  不含账号密码，落自己分组）；
- 分组分享含组内全部收藏（含子分组），新增收藏自动进入分享范围。

### FR-08 归属与权限

- 全部实体 keyed `owner_id`（username 口径，同 CAP-62）；接口层 owner 强制：
  非 owner 直敲 URL 一律 404（防探测），ADMIN 可读（排障语义）不可写；
- 分享是 owner 强制的**唯一例外通道**：接收方仅经 `/api/bookmarks/shared-with-me`
  命名空间读取，收藏主端点不因分享放行；
- 菜单入口：顶部个人组「收藏夹」，菜单注册走 CAP-61 目录（默认全员可见）。

### FR-09 浏览器书签导入

- 入口：「我的收藏」工具条「导入」按钮，选择浏览器导出的书签 HTML
  （Chrome / Edge / Firefox 书签管理器的导出格式，均为 Netscape Bookmark）；
- **解析在前端**：Netscape HTML 由浏览器 DOMParser 解析成结构化树（文件夹 / 书签 / 备注 /
  标签），服务端只接收 JSON 树，不在 Java 侧写 HTML 解析器（浏览器最懂自己的导出格式）；
- 文件夹 → 同名分组（层级保留）：同父下同名分组**复用**不重建，重复导入同一文件幂等；
- 书签 → 收藏条目：同 owner 已存在完全相同 URL（含本批次内重复）**跳过**并计数，不覆盖
  既有条目的任何字段（与 FR-01「手工收藏不拦截同 URL」不冲突——导入是批量动作，幂等优先）；
  非 http/https 或缺主机名的地址跳过并计数；标题为空回落用 URL 充当；
- Firefox `TAGS` 属性按名 get-or-create 映射为标签（Chrome 导出无标签）；备注（`<DD>`）落
  `description`；内嵌图标（ICON data URI）不导入（favicon 抓取仍是留后续项）；
- 上限：单次 2000 条书签、文件夹 8 层，超限整体 400（单事务回滚，不残留半截导入）；
- 返回 `{createdGroups, createdBookmarks, skippedDuplicates, skippedInvalid}`，前端 toast 汇报；
- 归属口径同 FR-08：导入即逐条以 owner 身份创建，无新的权限面。

## 3. 插件化接口

| SPI（devmind-common） | 实现方 | 消费方 |
|---|---|---|
| 无（v1 不对外暴露 SPI） | — | — |

- 预留方向（不在 v1）：`BookmarkCatalog`（按用户列收藏）供 CAP-33 上下文装配/通用问答
  注入「我的常用入口」；届时再入 common，v1 不提前抽象。

## 4. 依赖关系

- CAP-01（DevMindPrincipal/登录态）；CAP-61（菜单注册）；CAP-62（个人数据 owner 强制
  口径与 404 语义，照搬先例，无新切面）；
- CAP-48 抽取的 common `SecretCipher`（账号密码加密，域分隔串 `bookmark-account`，
  与 integration 域互不通用）；
- 新模块 `devmind-bookmark`（仅依赖 common + auth 链）+ `frontend/src/features/bookmarks`
  自包含；不依赖 project/session/execution 任何模块，不发起 DomainEvent（v1 无通知场景）。

## 5. 数据模型

```
bookmark_groups(id, owner_id(64), parent_id null, name(128), sort_order, created_at, updated_at,
                索引(owner_id, parent_id))
bookmarks(id, owner_id(64), group_id null, title(256), url(2048), description(1024),
          favicon_url(2048) null, sort_order,
          last_status(16, @ColumnDefault("'UNKNOWN'")), last_status_code(8),
          last_latency_ms, last_checked_at null, last_visited_at null,
          created_at, updated_at, 索引(owner_id, group_id))
bookmark_tags(id, owner_id(64), name(64), created_at, 唯一键(owner_id, name))
bookmark_tag_rel(bookmark_id, tag_id, 唯一键(bookmark_id, tag_id))
bookmark_accounts(id, bookmark_id, label(128), username(256), password_enc(1024) null,
                  note(512), sort_order)
bookmark_shares(id, owner_id(64), bookmark_id null, group_id null, target_user(64), created_at,
                唯一键(owner_id, target_user, bookmark_id, group_id))
```

- 密码列 `password_enc` 为 `SecretCipher` 密文串（非 @Lob，长度上限 1024 足够）；
- `bookmark_shares` 的 `bookmark_id`/`group_id` 恰好一个非空（服务层校验，CK 依赖 DB 差异
  不做）；
- 时间序列化统一 `JacksonConfig`，前端渲染统一 `fmtTime`。

## 6. API 概要

```
# 收藏
GET    /api/bookmarks                       列表（?groupId=&tagIds=&keyword=&status=，owner 过滤）
POST   /api/bookmarks                       {title,url,description,groupId,tagIds,accounts[]}
GET    /api/bookmarks/{id}                  （非 owner 404）
PUT    /api/bookmarks/{id}                  全量编辑（含 accounts 整组与 tagIds）
DELETE /api/bookmarks/{id}
PUT    /api/bookmarks/move                  {ids[], groupId|null}（批量转移分组）
POST   /api/bookmarks/{id}/visit            记录 last_visited_at（前端打开同时调用，失败静默）

# 探测
POST   /api/bookmarks/{id}/probe            单条探测（同步返回结果）
POST   /api/bookmarks/probe-batch           {ids[]}（异步，202；上限 200）
POST   /api/bookmarks/import                {nodes[]}（FR-09 结构化书签树；单事务，上限 2000 条/8 层）

# 分组
GET    /api/bookmark-groups                 我的分组树
POST   /api/bookmark-groups                 {name, parentId}
PUT    /api/bookmark-groups/{id}            改名/移动父级/排序
DELETE /api/bookmark-groups/{id}?cascade=   默认收藏移未分组，cascade=true 级联删

# 标签
GET    /api/bookmark-tags                   我的标签（带引用计数）
POST   /api/bookmark-tags                   {name}
PUT    /api/bookmark-tags/{id}              改名
DELETE /api/bookmark-tags/{id}

# 账号明文（按次）
GET    /api/bookmarks/{id}/accounts/{aid}/secret   返回 {password}（owner only）

# 分享
GET    /api/bookmark-shares                 我发出的分享
POST   /api/bookmark-shares                 {bookmarkId|groupId, targetUser}
DELETE /api/bookmark-shares/{id}
GET    /api/bookmarks/shared-with-me        我收到的分享（分组树+收藏，密码剔除）
POST   /api/bookmarks/shared-with-me/copy   {bookmarkId, groupId|null}（复制为我的收藏）
```

## 7. 验收标准

- 归属：A 的收藏/分组/标签/账号，B 直敲各端点一律 404；ADMIN 可读不可写；
- 探测：可通 URL 探测后 OK + 状态码 + 耗时落库；不可达/4xx/5xx 判 FAIL；批量探测 200 条
  上限拦截；
- 账号：密码入库为密文（DB 直查不可读）；列表接口恒脱敏；`/secret` 端点仅 owner 可取明文；
  分享视图中密码字段不存在；
- 分享：分组分享后接收方可见组内收藏（含后续新增）；撤销即不可见；接收方写操作 404；
  复制后成为接收方自有数据、源删除不影响；
- 分组：删除分组默认收藏落未分组；级联删除二次确认生效；
- 回归：`mvn -q test` + `npx tsc -b` 通过；E2E 覆盖「建收藏→探测→挂账号→分享→接收方
  可见→撤销→404」闭环。

## 8. MVP 范围（v1 确认做 / 留后续）

- v1：FR-01~09 全做；探测仅手动触发；分享仅平台内用户只读；
- 留后续：定时探测与失效通知（CAP-06）、favicon 抓取缓存、
  匿名分享链接、收藏进上下文装配（BookmarkCatalog SPI）、访问频次统计排序。

## 9. 落地状态

- **M1 —— 需求定稿**：本文档。
- **M2 —— 实现 + E2E 通过（2026-09-24）**：新模块 `devmind-bookmark`（仅依赖 common + auth）
  + `frontend/src/features/bookmarks`（顶部一级导航「收藏夹」）。单测 47 项（含真实网络探测、
  DNS/CONNECT 分类回归）`mvn test` 全绿；`npx tsc -b` 通过；`tests/cap64_e2e.py` 51 项断言
  闭环通过；全站布局巡检 `--only bookmarks` 四视图（1366×768）全过。
- **规格未明确处的实现口径**（后续若要改口径，改这里 + 同步代码）：
  1. 分组过滤含子树——选中父分组即含全部后代条目；
  2. 未分组另设 `ungrouped=true` 布尔参数（`groupId=null` 在查询串无法表达 SQL NULL）；
  3. 多标签过滤取 AND（需同时具备所选标签）；
  4. 删除分组默认模式：子分组上提到被删分组的父级（不是拍平到根），组内条目转未分组；
  5. 探测异常归一为 8 字符内同义码（DNS/CONNECT/SSL/TIMEOUT/IO/ERROR）落 `last_status_code`；
     DNS 判定必须先于 CONNECT——JDK HttpClient 把域名解析失败包成
     `ConnectException → UnresolvedAddressException`，只认 ConnectException 会误判；
  6. 分享目标用户以用户名文本录入（平台暂无普通用户列表端点，CAP-61 菜单/用户体系就绪后可换选择器）；
  7. 前端将 `group_id` 为空的集合统称「默认分组」（虚拟分组，非实体分组行，不可重命名/分享/删除）；
     存储与 API 口径不变（`group_id IS NULL` / `ungrouped=true`）。

- **M3 —— FR-09 浏览器书签导入（2026-10-02）**：`POST /api/bookmarks/import` 收结构化书签树
  单事务落库；前端「我的收藏」工具条「导入」弹窗用 DOMParser 解析 Netscape HTML（书签管理器
  导出文件），解析预览（文件夹/书签条数）后确认导入，toast 汇报新增/跳过计数。
- **M3 实现口径**（同上，改口径先改这里）：
  1. 导入分组复用键 = (owner, parentId, name)——重复导入同一文件幂等；手工建同名分组不受影响；
  2. 导入去重 = owner 内完全相同 URL（normalize 后精确匹配，含批次内重复）跳过并计数；
  3. 非法地址（非 http/https、缺主机名）跳过并计数，不阻塞整批；>64 字符的标签名同样跳过；
  4. 标题/描述/组名超列宽（256/1024/128）截断而不报错——导入宁可截断不失败；
  5. 书签 HTML 解析只在浏览器（DOMParser），服务端契约是结构化 JSON 树，E2E 直接打 JSON 契约。

- **M4 —— 分组树折叠 + 拖拽改父级（2026-10-03）**：侧栏分组改用 antd Tree 渲染，
  折叠集存 localStorage（记「收起的」，默认全展开，新建分组不会被藏住）；拖拽换父走既有
  `PUT /bookmark-groups/{id}`（带原名只改 parentId），前端 `allowDrop` 禁拖入自己子树，
  原地松手不发请求；「全部收藏/默认分组」两个虚拟行不进 Tree（不可拖、无折叠箭头）。
