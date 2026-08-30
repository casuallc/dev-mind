# CAP-07 服务器适配器（Server Adapter）

> 能力 ID：CAP-07 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

提供**插件化的服务器控制抽象**：构建（远程执行）、部署、发版、健康检查统一走一套接口。首期两种实现：**SSH**、**HTTP API**（目标机部署轻量 daemon）。安全上以**命令模板白名单**为核心，杜绝 Agent 自由拼接远程命令。

## 2. 功能需求

- **FR-01 Server 模型**：name、env（test/staging/prod）、accessType（`ssh` | `http`）、连接配置（加密存储）、capabilities（deploy/restart/logs/exec/health）。
- **FR-02 统一 SPI**：
  - `connectTest()`：连通性测试；
  - `execute(scriptTemplate, params)`：执行脚本模板（白名单内）；
  - `upload(localFile, remotePath)` / `download(remotePath)`：文件传输；
  - `healthCheck(checkConfig)`：健康检查（URL 或命令）。
- **FR-03 SSH 实现**：封装 SSH 客户端，密钥/口令认证，命令执行、文件传输、端口等。
- **FR-04 HTTP 实现（Server Agent）**：目标机运行轻量 daemon，暴露 REST API（执行/日志/健康/上传），HTTPS + token 鉴权；平台侧实现同一 SPI，对上层透明。
- **FR-05 命令模板白名单**：远程执行的命令必须是**项目预定义的脚本模板**（如 `deploy.sh.tpl`、`nexus-push.tpl`），模板内占位符参数化；Agent 不能自由拼接任意 shell 命令。
- **FR-06 执行审计**：所有经适配器执行的命令与输出全量留痕（audit_logs），可追溯。
- **FR-07 凭证管理**：SSH 私钥 / Server Agent token 加密存储（对称加密，密钥在配置中管理），运行时注入，不落日志；支持按 env 分级使用。

## 3. 插件化接口

```
interface ServerAdapter {
  String           connectTest(Server s);
  ExecResult       execute(Server s, ScriptTemplate tpl, Map<String,String> params);
  void             upload(Server s, String localPath, String remotePath);
  String           download(Server s, String remotePath);
  HealthResult     healthCheck(Server s, HealthCheckConfig cfg);
}
```
- 实现注册：`ServerAdapterRegistry` 按 `accessType` 路由；新增实现（K8s、CI Runner 等）即注册一个 bean。
- 上层（构建/部署/发版/测试）只依赖 `ServerAdapter` 接口与 `ScriptTemplate` 模型。

## 4. 依赖关系

- 依赖：CAP-01（鉴权）、CAP-02（服务器挂在项目下）。
- 被依赖：CAP-08（远程构建）、CAP-09（部署）、CAP-10（对部署目标做健康检查/拉日志）、CAP-11（远程发版）。

## 5. 数据模型

```
servers(id, project_id, name, env, access_type, host, port, username,
        key_ref, http_base_url, http_token_ref, capabilities(json),
        status, created_at)
script_templates(id, project_id, code, name, template_text,
                 params_schema(json), allowed[build|deploy|release|test], updated_at)
```

## 6. API 概要

```
CRUD   /servers
POST   /servers/{id}/test          连通性测试
POST   /servers/{id}/execute       执行模板 {templateCode, params}
GET    /servers/{id}/logs?cmd=…    拉取日志（capability=logs）
POST   /servers/{id}/health        健康检查
CRUD   /script-templates?projectId=  命令模板白名单管理
```

## 7. 验收标准

- 注册一台 SSH 服务器和一台 HTTP(Server Agent) 服务器，连通性测试均通过；
- 两种服务器都能通过项目预定义模板执行部署类命令，输出正确；
- Agent 尝试执行白名单外命令被拒绝；
- 所有执行有审计记录；凭证存储为密文。

## 8. MVP 范围（暂不做）

堡垒机/跳板、SFTP 完整管理界面、多服务器批量操作编排。
