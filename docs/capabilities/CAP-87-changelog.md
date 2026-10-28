# CAP-87 控制台更新日志（本地文件只读渲染）

> 能力 ID：CAP-87 ｜ 分类：管理/前端体验 ｜ 状态：**已实现** ｜ 日期：2026-10-25
> 上游能力：CAP-01（认证）、CAP-11（发版执行器，历史口径参考）
> 决策记录：
> 1. **对象是 Dev-Mind 自身，不是项目**：更新日志描述平台每个版本的变更，供运维确认
>    「当前部署版本改了什么」。不复用项目维度的 Release 记录，也不做全平台项目发版汇总。
> 2. **数据源是随安装包分发的本地文件**：内容由研发在发版前维护为 `CHANGELOG.md`，随
>    `devmind-<version>.tar.gz` 一起交付；控制台只读解析与渲染。**不做界面编辑、草稿、
>    发布审批与修订历史**——这些能力与「文件随包发版」的交付方式冲突，原需求中的
>    Release 自动草稿段落据此作废。
> 3. **未发布内容不对外可见**：`## [Unreleased]`（或「未发布」）段落永远不进入 API；
>    只有带版本号与发布日期的条目才对控制台可见。
> 4. **不做数据库存储**：文件是唯一事实源，避免「文件/DB 双写」分叉；解析结果按文件
>    mtime 做进程内缓存，文件更新后下一次请求自动生效，无需重启。

## 1. 目的

平台版本变更信息目前分散在 git 提交、发版记录、文档库与 IM 通知里。运维升级或排障时
需要回答两个问题：**线上跑的是哪个版本**、**这个版本相对上一版改了什么**。现状只能翻
记录拼凑，成本高且口径不一致。

本能力在控制台提供一个只读「更新日志」页面：按版本倒序展示已发布版本的版本号、发布
日期、变更类型（新增/优化/修复/破坏性变更）、变更说明（Markdown）与关联需求/工作项
编号；支持版本号、日期区间与关键词筛选；从「关于」弹框的当前部署版本可一键跳到对应
版本。

## 2. 功能需求

### M1 文件约定与解析

- **FR-01 文件定位**：新增配置 `devmind.changelog.path`（可空）。解析顺序：
  1. 显式配置的路径（相对路径按 `${user.dir}` 解析）；
  2. `${user.dir}/CHANGELOG.md`（开发态：仓库根）；
  3. `${user.dir}/config/CHANGELOG.md`（分发包：`config/` 外置文件）；
  4. classpath `CHANGELOG.md`（jar 内兜底，保证 IDE/测试可读）。
  文件不存在时接口返回空列表并给出 `sourcePath`/`available=false`，页面展示放行指引，
  不报 500。
- **FR-02 文件格式**：采用 Keep a Changelog 风格，版本标题
  `## [<version>] - <yyyy-MM-dd>`（方括号可省略）。版本内：
  - `### 新增 | 优化 | 修复 | 破坏性变更`（兼容 Added/Improved/Changed/Fixed/Breaking）
    映射为 `ADDED/IMPROVED/FIXED/BREAKING` 四种变更类型；
  - 可选的元信息行 `- 关联：CAP-87, REQ-12, WI-34`（兼容 `Refs:`）；
  - 其余内容按 Markdown 原样保留为正文。
- **FR-03 可见性**：版本号为空、`Unreleased`、`未发布` 的段落一律跳过；无法解析出
  发布日期的条目仍保留（日期字段为空），但不允许无版本号的自由段落进入列表。
- **FR-04 容错与缓存**：单个坏段落只跳过该段落并记 WARN，不影响全文；按文件
  `lastModified + size` 缓存解析结果，文件替换后自动失效重载；单文件上限
  `devmind.changelog.max-size-kb`（默认 2048），超限报 400 并提示拆分文件。

### M2 查询接口

- **FR-05 列表与筛选**：`GET /api/changelog` 支持 `keyword`（版本/正文/关联编号）、
  `version`（版本号包含匹配）、`from`/`to`（发布日期闭区间，`yyyy-MM-dd`）、
  `page`（从 0 起）/`size`（默认 20，上限 100）。返回 `PageView`，列表项只含摘要
  （不含完整正文），按版本倒序（数值段逐段比较，非数值版本回退发布日期倒序）。
- **FR-06 详情**：`GET /api/changelog/{version}` 返回完整 Markdown 正文、类型与关联
  编号；版本不存在返回 404。
- **FR-07 当前版本对照**：复用 `GET /api/about` 的 `version` 作为「当前部署版本」；
  前端按该版本号请求详情，命中则高亮/直达，未命中提示「当前版本暂无更新日志条目」。

### M3 控制台页面

- **FR-08 页面与路由**：新增 `/changelog`（登录后可达，非项目上下文），页面为单 Card
  列表：顶部当前部署版本提示条；筛选区（版本号、日期区间、关键词）；表格列为版本号、
  发布日期、变更类型标签、变更摘要、关联编号、操作「查看」；服务端分页。
- **FR-09 详情预览**：行内「查看」打开右侧 Drawer，用共享 `Markdown` 组件渲染正文，
  展示版本/日期/类型/关联编号；不做编辑、发布、删除入口。
- **FR-10 入口与跳转**：顶部用户下拉新增「更新日志」；「关于」弹框展示版本处新增
  「查看更新日志」按钮，携带 `?version=<当前部署版本>` 打开页面并自动套用版本筛选。
- **FR-11 性能与空态**：列表默认 20 条/页，首屏只传摘要；文件不存在、当前版本未匹配、
  筛选无结果分别给出明确空态与下一步提示。

## 3. 否决方案

| 方案 | 否决理由 |
|---|---|
| 从项目 Release 记录自动生成草稿 + 人工发布 | 更新日志对象是 Dev-Mind 自身而非某个项目；文件随包发版的交付方式下，控制台写库会产生「文件/DB 双写」与升级覆盖问题（2026-10-25 需求方确认） |
| 每个版本一个 JSON/YAML 文件 | 单文件 `CHANGELOG.md` 更符合研发维护习惯，与 git diff、代码评审天然对齐；结构化字段由标题/小节约定承载 |
| 全文塞进前端静态资源 | 分发包升级后前端构建产物不再可变；外置文件允许运维在不重打包 UI 的情况下替换日志 |
| 每次请求实时读盘解析 | 日志会增长，正文 Markdown 解析有成本；按 mtime 缓存可保证「文件更新即生效」且请求开销恒定 |
| 在页面内做编辑/发布 | 与「文件随安装包发版」冲突，且需要额外权限、审计与历史表；见决策 2 |

## 4. 架构与模块归属

- 后端新增 `devmind-changelog`（只依赖 `devmind-common` + Spring Web）：
  - `ChangelogProperties`：`path` / `max-size-kb`；
  - `ChangelogParser`：纯函数式 Markdown 段落解析，便于单测；
  - `ChangelogService`：文件定位、mtime 缓存、筛选/排序/分页；
  - `ChangelogController`：`GET /api/changelog`、`GET /api/changelog/{version}`。
- 前端新增 `frontend/src/features/changelog`：`api.ts` / `types.ts` /
  `pages/ChangelogPage.tsx`；路由注册在 `app/App.tsx`，入口在
  `features/auth/components/UserMenu.tsx` 与 `app/AppLayout.tsx` 无耦合。
- 分发放置：`devmind-dist` 把仓库根 `CHANGELOG.md` 复制到安装包 `config/CHANGELOG.md`；
  开发态直接读仓库根文件。
- 依赖方向：`devmind-changelog → devmind-common`，不依赖 release/deploy/project，
  不引入 JPA。

## 5. 数据模型

无数据库表。文件模型如下：

```markdown
# Dev-Mind 更新日志

## [Unreleased]
### 新增
- 开发中的变更（不进入 API）

## [0.1.0] - 2026-10-25
- 关联：CAP-86, CAP-85, CAP-84

### 新增
- 知识库管理台菜单组化 ...

### 修复
- 重启迁移不再覆盖较新的 AI 周报 ...
```

解析后的内存模型：`version`、`releasedAt`（LocalDate，可空）、`types`（枚举集合）、
`refs`（字符串集合）、`summary`（去 Markdown 标记后截断）、`contentMd`（原正文）。

## 6. API 概要

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/changelog?keyword=&version=&from=&to=&page=&size=` | 已发布条目分页列表（摘要） |
| GET | `/api/changelog/{version}` | 单版本完整正文 |
| GET | `/api/about` | 复用：当前部署版本/构建时间 |

列表响应附 `sourcePath`、`available` 两个元字段（`PageView` 外层扩展），便于页面在
文件缺失时给出可执行提示。

## 7. 安全

- 两个端点均为登录后只读 GET，沿用 `SecurityConfig` 的 `GET /api/**` 认证规则；
  不新增写方法，天然无越权写入面。
- 文件路径来自服务端配置，不接受请求参数控制；对外只暴露解析后的文本，不暴露宿主机
  绝对路径以外的文件内容。
- Markdown 渲染复用共享组件的既有安全口径（不启用原始 HTML），无 XSS 注入面。

## 8. 里程碑

- **M1**：FR-01~FR-04（文件定位、解析、可见性、缓存与容错）+ 解析单测。
- **M2**：FR-05~FR-07（列表/详情 API、筛选分页、当前版本对照）+ 接口测试。
- **M3**：FR-08~FR-11（页面、详情抽屉、用户菜单与关于弹框入口、空态与性能）+
  `tsc -b` / `vite build`、`tests/legacy/api/cap87_changelog_e2e.py`。

## 9. 验收标准

- `mvn -pl devmind-changelog test` 通过；全量 `mvn -q test` 不回归。
- `cd frontend && npx tsc -b && npm run build` 通过。
- `tests/legacy/api/cap87_changelog_e2e.py` 覆盖：
  1. 默认列表只含已发布版本且按版本倒序，`[Unreleased]` 不出现；
  2. 版本号、日期区间、关键词三类筛选各自命中/落空；
  3. 详情返回完整 Markdown，未知版本 404；
  4. 文件缺失时返回空列表且 `available=false`（不 500）；
  5. 文件被替换后不重启即可看到新条目（mtime 缓存失效）。
- 手工验收：用户下拉「更新日志」可进入；「关于」弹框「查看更新日志」带当前版本号
  落地并套用筛选；详情抽屉 Markdown 正常渲染（含表格/代码块）。

## 10. 风险与开放问题

| ID | 风险/问题 | 口径 |
|---|---|---|
| R1 | 研发忘记更新 `CHANGELOG.md` | 纳入发版 checklist；页面不伪造数据，缺失即空态 |
| R2 | 文件格式漂移导致条目解析不到 | 解析器兼容中英文小节与有无方括号；单测钉死格式；坏段落跳过并 WARN |
| R3 | 安装包升级覆盖运维手工修改 | 文件随包分发是既定交付方式；运维本地修改应在下次升级前回灌仓库，风险接受 |
| R4 | `about.version`（Maven 版本）与日志版本口径不一致 | 页面按字符串精确匹配；未命中时提示而非静默；后续可在发版脚本统一版本号 |
| O1 | Release 成功自动生成草稿 | 明确不做（决策 2）；若未来恢复，应以「生成/更新 CHANGELOG.md 并走代码评审」实现，而非控制台写库 |
| O2 | 多文件/按年份拆分 | 当前单文件上限 2MB 足够；超过后再演进为目录扫描，API 不变 |
