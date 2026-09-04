// CAP-23：克隆表单认证提示——remoteUrl 主机命中 ENABLED 的 Git 集成实例但未选择时，
// 警告"匿名克隆私有库必失败"（git 子进程无 TTY，报 could not read Username / exit=128，不点日志看不出原因）。
import { Typography } from 'antd'

/** 组件只依赖最小结构，避免跨 feature 引用 integrations 内部类型 */
export interface GitIntegrationRef {
  id: number
  name: string
  baseUrl: string
}

function hostOf(url?: string | null): string | null {
  if (!url || !url.trim()) return null
  try {
    return new URL(url.trim()).hostname.toLowerCase()
  } catch {
    return null
  }
}

interface Props {
  remoteUrl?: string
  integrationId?: number | null
  integrations: GitIntegrationRef[]
}

export default function CloneAuthHint({ remoteUrl, integrationId, integrations }: Props) {
  if (integrationId != null) return null
  const host = hostOf(remoteUrl)
  if (!host) return null
  const matched = integrations.find((i) => hostOf(i.baseUrl) === host)
  if (!matched) return null
  return (
    <Typography.Text type="warning" style={{ display: 'block', marginTop: -8, marginBottom: 16 }}>
      该地址与集成实例「{matched.name}」同主机：私有仓库不选实例将匿名克隆并失败（exit=128）
    </Typography.Text>
  )
}
