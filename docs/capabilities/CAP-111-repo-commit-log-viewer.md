# CAP-111 代码库提交日志查看器（搜索/详情/分页，下线分支切换）

> 能力 ID：CAP-111 ｜ 分类：管理 ｜ 状态：已实现 ｜ 日期：2026-11-18
> 决策记录：搜索与分页一律**服务端**做（不做前端内存过滤，同 CAP-109 决策口径）——
> 无关键字时走 `git rev-list --count` + `git log --skip/-n` 真分页；有关键字时全量
> `git log` 拉取后内存过滤（subject 忽略大小写 contains / sha 忽略大小写前缀），
> 因为 git 原生 `--grep` 只匹配提交信息、盖不住 sha，两个维度要统一分页口径只能内存合并；
> 提交详情用 `git show --no-patch`（元信息+正文）+ `git show --name-status -z
> --first-parent`（变更文件，NUL 分隔机读，merge 提交按第一父 diff），sha 参数走
> `^[0-9a-fA-F]{4,40}$` 白名单防注入；分支切换能力整体下线（见否决方案）。

## 1. 目的

REQ-23 对 CAP-109 M2「代码库提交历史」的使用反馈与增强：

- 「切换到此分支」在后台管理场景不需要（有副作用：服务端克隆被会话 diff/工时扫描共用，
  CAP-109 R1 已记录在案），整体下线；
- 操作入口「提交」语义不清，改名「提交日志」；
- 提交历史需要按文本或哈希搜索过滤；
- 需要能看到单次提交的完整详情（完整提交信息 + 变更文件）；
- limit 截断（上限 200）看不到全部提交，改服务端分页可翻到最后。

## 2. 功能需求

### 提交日志查看器（M1）

- **FR-01 下线分支切换**：移除抽屉「切换到此分支」按钮、前端 `checkoutRepo`、
  后端 `POST /api/repos/{id}/checkout` 端点、`GitRepoOpsService.checkout`、
  `RepoCheckoutRequest` DTO 及对应单测。「抓取」保留。
- **FR-02 入口改名**：ReposView 操作列「提交」→「提交日志」，抽屉标题同步
  「提交日志：{仓库名}」。
- **FR-03 搜索过滤**：`GET /api/repos/{id}/commits` 增加可选 `keyword`，
  匹配提交信息（subject，忽略大小写包含）或提交哈希（sha，忽略大小写前缀）；
  抽屉顶部分支选择旁加 `Input.Search`。
- **FR-04 提交详情**：新增 `GET /api/repos/{id}/commits/{sha}` 返回
  `{sha, authorName, authorEmail, time, subject, body, files:[{status, path, oldPath?}]}`；
  sha 必须匹配 `^[0-9a-fA-F]{4,40}$` 否则 400。前端表格行可展开，展开时懒加载详情：
  完整哈希/作者/时间、完整提交信息（subject+body 原样保留换行）、变更文件列表
  （状态 A/M/D/R/C + 路径，改名带原路径）。
- **FR-05 服务端分页**：`GET /api/repos/{id}/commits` 出参由数组改为
  `PageView{items,total,page,size}`（common 既有通用分页视图，page 从 0 起）；
  size 默认 20、硬上限 100。无关键字：`git rev-list --count` 求总数 +
  `git log --skip=page*size -n size` 取页；有关键字：全量 log 内存过滤后切片。
  前端表格接服务端分页（current/pageSize/total/showSizeChanger）。

## 3. 否决方案

- **保留 checkout 仅前端隐藏按钮**：能力下线就前后端一起下，留一个无人调用的写端点
  是纯粹的安全暴露面（POST 改工作区），且 CAP-109 R1 的副作用提示不再有消费场景。
- **搜索走 `git log --grep`**：--grep 只匹配提交信息文本，盖不住 sha 维度；要支持
  「text or hash」统一过滤且分页总数正确，只能全量拉取内存过滤（内部仓库提交量级
  万级以内，30s 超时内可完成；超此量级的是 O1 开放问题）。
- **详情直接随列表返回 body/files**：列表页 99% 不看详情，全量带 body+文件清单会让
  每页请求膨胀一个数量级；懒加载独立端点更省。
- **前端内存分页**：与 CAP-109 否决「前端内存过滤」同理，口径易漂且看不到全部提交。

## 4. 架构与模块归属

```
devmind-integration（沿用 CAP-109 归属，无新模块）
  GitRepoOpsService    —— listCommits 改分页+keyword；新增 commitDetail；删除 checkout
  GitRepoOpsController —— GET /api/repos/{id}/commits（PageView）、
                          GET /api/repos/{id}/commits/{sha}；删除 POST /checkout
  dto/RepoCommitDetailView（新，含 FileChange）；dto/RepoCheckoutRequest（删除）

frontend features/repos
  api.ts    —— listRepoCommits 改分页签名、新增 getRepoCommit、删除 checkoutRepo
  types.ts  —— 新增 RepoCommitDetail/RepoCommitFile/PageViewResult
  components/RepoCommitsDrawer.tsx —— 搜索框 + 服务端分页 + 展开行详情；删切换按钮
  pages/ReposView.tsx —— 操作按钮「提交」→「提交日志」
```

## 5. 数据模型

无变更（无新表新列）。

## 6. API 概要

- `GET /api/repos/{id}/commits?branch=&keyword=&page=&size=` →
  `PageView<RepoCommitView>`（出参由数组改为分页视图，全认证用户可读）
- `GET /api/repos/{id}/commits/{sha}` → `RepoCommitDetailView`（新增，全认证用户可读）
- ~~`POST /api/repos/{id}/checkout`~~（下线）
- `POST /api/repos/{id}/fetch`（既有，ADMIN，不变）

## 7. 安全

- 分支名沿用实体 branches 列白名单校验；sha 参数 `^[0-9a-fA-F]{4,40}$` 白名单，
  GitCli 走 ProcessBuilder 参数数组不过 shell，双重防注入。
- checkout 写端点下线后，/api/repos 下不再有任何「改服务端克隆工作区」的管理面入口
  （fetch 只读远端对齐引用，不动检出分支）。
- commits/detail 均为本地只读 git 操作，不触碰凭证。

## 8. 里程碑

- **M1 提交日志查看器**（已交付）：FR-01~05。

## 9. 验收标准

- `mvn -q -pl devmind-integration -am test` 通过（GitRepoOpsServiceTest 覆盖：
  分页跨页切片与 total、keyword 文本/哈希过滤、详情解析含正文与 A/M/D/R 文件状态、
  非法 sha 400、缺 .git 400、分支白名单 400；checkout 用例全删）。
- `npx tsc -b` 通过。
- 起服务后代码库 tab 点「提交日志」：翻页可见全部提交、搜索文本/哈希生效、
  展开行显示完整提交信息与变更文件，无「切换到此分支」按钮。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | 有关键字时全量 git log + 内存过滤，超大仓库（十万级提交）可能逼近 30s 超时 | 接受（内部仓库量级远低于此）；真撞上再演进到增量索引 |
| R2 | 详情不显示逐文件增删行数（numstat 与 name-status 合并解析不可靠） | 接受，本期只给状态+路径 |
| O1 | 提交 diff 逐行查看 | 已由 CAP-113 交付（2026-11-23） |
| O2 | merge 提交文件清单按第一父 diff（--first-parent） | 与主线视角一致，接受 |
