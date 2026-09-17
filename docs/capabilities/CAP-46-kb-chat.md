# CAP-46 知识库 AI 会话（KB 绑定的通用问答）

> 状态：定稿（2026-09-17）｜依赖：CAP-44（知识库容器化与向量检索）
> 目标：通用问答（/chats）可绑定一个知识库——启动时注入库概览，之后每轮提问前
> 按提问内容检索库内分块，以 `<knowledge-context>` 前缀注入发给 claude 的输入，
> 实现「基于知识库的 AI 会话」。

## 背景

CAP-44 已提供知识库容器 + 向量检索（`KnowledgeRetriever` SPI）。会话侧目前只有
FULL 库的全量 CLAUDE.md 注入（经验库语义），缺少「挂一个 RAG 库做专题问答」的入口。
本 CAP 把检索注入接到通用问答的创建与每轮输入链路上。

## 功能需求

### FR-01 问答绑定知识库

- `CreateChatRequest` 增 `knowledgeBaseId`（Long 包装类型，可空）；`chat_sessions`
  增同名列（ddl-auto 演进，无迁移）。
- 创建时库不存在 → 400；`ChatView` 回传 `knowledgeBaseId` 供前端展示。

### FR-02 启动注入：库概览

- 创建时若绑库，把库概览节（库名/描述/注入模式/条目名清单，截断防爆）拼进
  launch prompt 前缀，让 claude 首轮即知「本会话挂了哪个库、库里有什么」。
- 概览经 `KnowledgeRetriever` 所在 knowledge 模块的 SPI 提供（common 增接口方法），
  chat 以 ObjectProvider 探测；未装配 knowledge 模块时绑库创建报 409。

### FR-03 每轮检索注入

- `ChatManagerService.input`：会话绑库时，先发往 runtime 的 text 前经
  `KnowledgeRetriever.retrieve([kbId], text, topK)` 取命中块，非空则包成
  `<knowledge-context>…（来源：条目名）…</knowledge-context>\n\n` 前缀拼进用户消息；
  无命中则原样发送（不注入空壳）。
- input 路径是 REST 线程（非 WS 事件链），同步远程 embedding 调用安全。
- 检索异常按无命中降级（SPI 实现已保证不抛），会话主链路不受知识侧故障影响。
- embedding 未配置时 SPI 内部降级 LIKE 检索（CAP-44 语义），会话侧无分叉。

### FR-04 前端

- 新问答「高级选项」Popover 加知识库选择器（列出 active 库，标注 injectMode）。
- 知识库详情页加「发起会话」按钮：跳转 /chats 并预选该库。
- 问答视图（已绑库）标题区显示库名标签。

## 非目标

- 多库绑定（单库起步；检索 SPI 本身支持多 kbIds，后续扩展）。
- 会话内动态换绑/解绑。
- 项目会话（/sessions）侧的 RAG 注入——项目会话注入语义仍是 FULL 经验库。

## 数据与接口

- `chat_sessions` 增 `knowledge_base_id BIGINT`（可空）。
- common 的 `KnowledgeRetriever` SPI 增 `Optional<KbOverview> overview(long kbId)`
  （record KbOverview(name, description, injectMode, entryNames)）。
- REST 不变更：`POST /chats` 请求体加 `knowledgeBaseId`；`ChatView` 加同名字段。

## 验证

- 单测：注入包装（有命中拼前缀/无命中原样/检索异常降级原样/未绑库不调用检索）。
- E2E（tests/cap46_verify.py）：独立实例（18090+独立 H2+mock embedding）+ fake runner
  节点 → 建库建条目等索引 ready → 绑库起问答 → 断言事件流出现 `<knowledge-context>`
  与库概览 → 再发一轮输入断言二次注入；不绑库问答断言无注入。
