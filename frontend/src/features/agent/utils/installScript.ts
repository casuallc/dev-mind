/**
 * CAP-21 一键安装脚本地址拼装。脚本由服务端单一来源生成
 * （GET /api/agent-nodes/{id}/install-script，见后端 AgentInstallScripts），
 * 端点支持 ?token= 节点 token 自证——浏览器下载与目标机 curl/irm 直链通用。
 */

export type InstallOs = 'windows' | 'linux'

/** 脚本下载地址。token 非空 → 内嵌 token 版；空 → 参数化版（需登录态，仅页面内 fetch 用）。 */
export const installScriptUrl = (nodeId: number, os: InstallOs, token?: string | null): string =>
  `${location.origin}/api/agent-nodes/${nodeId}/install-script?os=${os}${token ? `&token=${token}` : ''}`

/** 目标机「下载即装」一行命令（token 内嵌在 URL 里；token 传 null 用 dmag_xxx 占位，用户自行替换）。 */
export const installOneLiner = (nodeId: number, os: InstallOs, token: string | null): string => {
  const url = installScriptUrl(nodeId, os, token ?? 'dmag_xxx')
  return os === 'linux'
    ? `curl -fsSL '${url}' | sudo bash`
    : `powershell -ExecutionPolicy Bypass -Command "irm '${url}' -OutFile install-runner.ps1; .\\install-runner.ps1"`
}
