# CAP-87 控制台更新日志（本地文件只读渲染）

> 能力 ID：CAP-87 ｜ 分类：管理/前端体验 ｜ 状态：**已实现** ｜ 日期：2026-10-25（M4 修订 2026-11-02）
> 上游能力：CAP-01（认证）、CAP-11（发版执行器，历史口径参考）
> 决策记录：
> 1. **对象是 Dev-Mind 自身，不是项目**：更新日志描述平台每个版本的变更，供运维确认
>    「当前部署版本改了什么」。不复用项目维度的 Release 记录，也不做全平台项目发版汇总。
> 2. **数据源是随安装包分发的本地文件**：内容交付为 `CHANGELOG.md`，随
>    `devmind-<version>.tar.gz` 一起分发；控制台只读解析与渲染。**不做界面编辑、草稿、
>    发布审批与修订历史**——这些能力与「文件随包发版」的交付方式冲突，原需求中的
>    Release 自动草稿段落据此作废。
> 3. **未发布内容不对外可见**：`## [Unreleased]`（或「未发布」）段落永远不进入 API；
>    只有带版本号与发布日期的条目才对控制台可见。
> 4. **不做数据库存储**：文件是唯一事实源，避免「文件/DB 双写」分叉；解析结果按文件
>    mtime 做进程内缓存，文件更新后下一次请求自动生效，无需重启。
> 5. **版本锚点是构建而不是语义化发版**（2026-11-02 M4 修订，推翻原「研发发版前人工
>    维护」假设）：平台实际是 SNAPSHOT 持续部署，没有发版仪式，pom 版本从不 bump，
>    导致页面上永远只有一条可见条目——「没有用起来」的根因是缺发版动作而非维护纪律
>    （R1 假设证伪，Unreleased 一直有人在写）。改为：`scripts/build-dist.sh` 打包时由
>    `scripts/changelog-gen.py` 自动生成发布段落（版本号 `<pom去SNAPSHOT>-yyyymmdd+<shortsha>`），
>    内容从上一锚点到 HEAD 的提交记录按提交规约归类，**废弃人工维护发版公告**；
>    `[Unreleased]` 保留为可选润色覆盖层（有内容时优先并入新条目并清空）。
> 6. **当前部署对照改用 commit 匹配**（2026-11-02 M4 修订，解决 R4）：pom 版本恒为
>    SNAPSHOT，按版本号字符串匹配必然落空。每个发布段落记录 `- 提交：<shortsha>`
>    元信息行（= 打包时 HEAD，打包完成后再自动提交 CHANGELOG 自身，不影响匹配），
>    前端以 `/api/about` 的 `commit` 与条目逐一比对定位「当前部署」，`?version=` 旧
>    入口保留为兜底。

## 1. 目的

平台版本变更信息目前分散在 git 提交、发版记录、文档库与 IM 通知里。运维升级或排障时
需要回答两个问题：**线上跑的是哪个版本**、**这个版本相对上一版改了什么**。现状只能翻
记录拼凑，成本高且口径不一致。

本能力在控制台提供一个只读「更新日志」页面：按版本倒序展示已发布版本的版本号、发布
日期、变更类型（新增/优化/修复/破坏性变更）、变更说明（Markdown）与关联需求/工作项
编号；支持版本号、日期区间与关键词筛选；从「关于」弹框可按当前部署 commit 一键定位
对应条目。

M1~M3 上线后暴露结构性问题：平台是 SNAPSHOT 持续部署、从不 bump 版本也没有发版仪式，
「只有已发布段落可见」的规则下页面恒只有一条 0.1.0 条目。M4 把条目生产改为打包时自动
生成（见决策 5/6），页面数据随每次部署自动增长，零维护成本。

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
  - 可选的元信息行 `- 提交：<shortsha>`（M4 新增，生成器写入，作为「当前部署」对照锚点；
    兼容 `Commit:`）；两种元信息行都从正文剥离、不进入渲染；
  - 其余内容按 Markdown 原样保留为正文。
- **FR-03 可见性**：版本号为空、`Unreleased`、`未发布` 的段落一律跳过；无法解析出
  发布日期的条目仍保留（日期字段为空），但不允许无版本号的自由段落进入列表。
- **FR-04 容错与缓存**：单个坏段落只跳过该段落并记 WARN，不影响全文；按文件
  `lastModified + size` 缓存解析结果，文件替换后自动失效重载；单文件上限
  `devmind.changelog.max-size-kb`（默认 2048），超限报 400 并提示拆分文件。

### M2 查询接口

- **FR-05 列表与筛选**：`GET /api/changelog` 支持 `keyword`（版本/正文/关联编号）、
  `version`（版本号包含匹配）、`commit`（M4 新增，提交哈希包含匹配，用于「当前部署」
  定位）、`from`/`to`（发布日期闭区间，`yyyy-MM-dd`）、
  `page`（从 0 起）/`size`（默认 20，上限 100）。返回 `PageView`，列表项只含摘要
  （不含完整正文），按版本倒序（数值段逐段比较，非数值版本回退发布日期倒序）。
- **FR-06 详情**：`GET /api/changelog/{version}` 返回完整 Markdown 正文、类型、关联
  编号与提交哈希；版本不存在返回 404。
- **FR-07 当前部署对照**（M4 重写）：以 `GET /api/about` 的 `commit` 为锚，与条目
  `commit` 元信息前缀互含匹配，命中则高亮「当前部署」标签并可直开详情；未命中
  （手工部署、IDE 直跑无 git.properties）回退旧 version 字符串匹配，再落空提示
  「当前部署暂无更新日志条目」。

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

### M4 打包自动生成（构建即版本锚点）

- **FR-12 生成器**：`scripts/changelog-gen.py`（Python 3 标准库，无三方依赖）。
  - 锚点：最新已发布段落的 `- 提交：<sha>` 元信息行；无锚点即报错并提示人工补登记
    （一次性 bootstrap），不做日期猜测。
  - 内容：`git log --no-merges <anchor>..HEAD`，按提交规约 `<type>(<scope>): <描述>`
    归类——feat→新增、fix→修复、refactor/perf→优化；docs/style/test/chore/build/ci
    不进日志；`!` 后缀或含「破坏性/BREAKING」一律归入破坏性变更。条文本
    `- **scope**：描述（shortsha）`；从 subject/body 提取 CAP/REQ/WI 编号汇总为
    `- 关联：` 元信息行。
  - 覆盖层：`[Unreleased]` 有正文时以其为新条目内容（人工润色优先，不再附自动明细），
    生成后清空该段；留空则完全由提交记录生成。
  - 版本号：`<pom 去 -SNAPSHOT>-<yyyymmdd>+<shortsha>`（pom 版本正则读根 pom.xml，
    不调 mvn），保证同日多次打包唯一、详情路由可寻址。
  - 幂等：锚点..HEAD 无新提交时输出提示并 0 退出，不写文件；`--dry-run` 只打印不落盘。
- **FR-13 构建集成**：`scripts/build-dist.sh` 在 maven 打包前调用生成器（新条目随包
  分发，且 jar 内 git.properties 与条目记录的提交一致=打包时 HEAD），打包成功后
  再调用 `changelog-gen.py --commit` 将 CHANGELOG.md 自动提交（
  `docs(changelog): 发版 <version> 自动转正`，不 push）；`--skip-changelog` 跳过
  （hotfix 重打包同一代码时锚点..HEAD 为空，生成器自身也会空转跳过）。python 解释器
  按 `python3 → python → py -3` 探测，找不到则构建失败并提示（禁静默打出无新条目的包）。
- **FR-14 前端对照与入口**：更新日志页顶部提示条展示
  `当前部署 v<version> @ <commit>（提交时间）`；「当前部署」标签与「查看该版本变更」
  按 FR-07 的 commit 匹配；「关于」弹框「查看更新日志」改为携带 `?commit=<sha>`
  （无 commit 时回退 `?version=`），页面落地后套用 commit 筛选并直开命中条目详情。

## 3. 否决方案

| 方案 | 否决理由 |
|---|---|
| 从项目 Release 记录自动生成草稿 + 人工发布 | 更新日志对象是 Dev-Mind 自身而非某个项目；文件随包发版的交付方式下，控制台写库会产生「文件/DB 双写」与升级覆盖问题（2026-10-25 需求方确认） |
| 每个版本一个 JSON/YAML 文件 | 单文件 `CHANGELOG.md` 更符合研发维护习惯，与 git diff、代码评审天然对齐；结构化字段由标题/小节约定承载 |
| 全文塞进前端静态资源 | 分发包升级后前端构建产物不再可变；外置文件允许运维在不重打包 UI 的情况下替换日志 |
| 每次请求实时读盘解析 | 日志会增长，正文 Markdown 解析有成本；按 mtime 缓存可保证「文件更新即生效」且请求开销恒定 |
| 在页面内做编辑/发布 | 与「文件随安装包发版」冲突，且需要额外权限、审计与历史表；见决策 2 |
| 人工维护发版公告 + 发版 checklist 约束（M4 否决） | 平台是 SNAPSHOT 持续部署、无发版仪式，约束只会逼出复制粘贴的噪音条目；提交规约（`<type>(<scope)>: 中文描述`）已足够规整，生成质量有保障（2026-11-02 决策 5） |
| 每次部署 bump pom 语义化版本 | 持续部署节奏下每次换包都 bump 版本号噪音过大；构建锚点（日期+sha）已满足唯一性与可追溯（2026-11-02 决策 5） |

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
- 条目生产（M4）：`scripts/changelog-gen.py` 由 `scripts/build-dist.sh` 在打包前调用，
  改写仓库根 `CHANGELOG.md` 并在打包成功后自动提交；不属于任何 Maven 模块，纯构建期工具。
- 依赖方向：`devmind-changelog → devmind-common`，不依赖 release/deploy/project，
  不引入 JPA。

## 5. 数据模型

无数据库表。文件模型如下：

```markdown
# Dev-Mind 更新日志

## [Unreleased]
### 新增
- 人工润色草稿（可选；打包时被转正为新条目内容并清空，不进入 API）

## [0.1.0-20261102+a1b2c3d] - 2026-11-02
- 提交：a1b2c3d
- 关联：CAP-87, CAP-102

### 新增
- **changelog**：打包自动生成发布段落（a1b2c3d）

### 修复
- **test**：用例编辑表格类型列截断（e4f5g6h）

## [0.1.0] - 2026-10-25
- 提交：f1852db
- 关联：CAP-86, CAP-85, CAP-84
...
```

解析后的内存模型：`version`、`releasedAt`（LocalDate，可空）、`commit`（可空）、
`types`（枚举集合）、`refs`（字符串集合）、`summary`（去 Markdown 标记后截断）、
`contentMd`（原正文）。

## 6. API 概要

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/changelog?keyword=&version=&commit=&from=&to=&page=&size=` | 已发布条目分页列表（摘要，含条目 commit） |
| GET | `/api/changelog/{version}` | 单版本完整正文 |
| GET | `/api/about` | 复用：当前部署版本/commit/构建时间 |

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
- **M4**：FR-12~FR-14（打包自动生成、build-dist 集成与自动提交、前端 commit 对照）
  + 生成器单测（`tests/unit/test_changelog_gen.py`）+ 解析器/服务单测补 commit 维度
  + 既有 `[0.1.0]` 条目补登锚点 `- 提交：f1852db`（一次性 bootstrap）。

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
- M4：
  - `python -m unittest discover tests/unit -v`（生成器单测）通过；
  - `scripts/changelog-gen.py --dry-run` 在当前仓库输出与 `git log` 一致的归类预览，
    不写文件；
  - `scripts/build-dist.sh` 打包后安装包 `config/CHANGELOG.md` 含新条目且
    `- 提交：` 等于包内 `git.properties` 的 `git.commit.id.abbrev`；
  - 连续两次打包（中间无新提交）第二次空转跳过、CHANGELOG 无变化；
  - 部署后「更新日志」页命中条目显示「当前部署」标签，「关于」弹框跳转直开详情。

## 10. 风险与开放问题

| ID | 风险/问题 | 口径 |
|---|---|---|
| R1 | ~~研发忘记更新 `CHANGELOG.md`~~（M4 关闭） | 已被决策 5 证伪并根除：条目由打包自动生成，无人工维护环节 |
| R2 | 文件格式漂移导致条目解析不到 | 解析器兼容中英文小节与有无方括号；单测钉死格式；坏段落跳过并 WARN；M4 起格式由生成器产出，漂移面进一步收窄 |
| R3 | 安装包升级覆盖运维手工修改 | 文件随包分发是既定交付方式；运维本地修改应在下次升级前回灌仓库，风险接受 |
| R4 | ~~`about.version`（Maven 版本）与日志版本口径不一致~~（M4 关闭） | 已被决策 6 根除：对照改用 commit 匹配，版本号仅作展示 |
| R5 | 提交信息质量决定日志质量 | 提交规约已是 MUST 且有中文描述；个别提交描述含糊属可接受损耗，重要版本可用 Unreleased 覆盖层人工润色 |
| R6 | 生成器自动提交与用户本地未提交改动混杂 | 只 `git add CHANGELOG.md` 单文件提交，不触碰其他改动；自动提交不 push，推送节奏不变 |
| O1 | Release 成功自动生成草稿 | 明确不做（决策 2）；若未来恢复，应以「生成/更新 CHANGELOG.md 并走代码评审」实现，而非控制台写库 |
| O2 | 多文件/按年份拆分 | 当前单文件上限 2MB 足够；超过后再演进为目录扫描，API 不变 |
