// 服务器连接配置：结构化表单项（随接入类型/认证方式切换）+ 一段话智能识别解析。
// 与后端约定：accessConfig 存 JSON —— SSH {host, port, username, authType, password|privateKey, passphrase}
// HTTP {baseUrl, token, timeoutMs}；敏感字段由 CAP-07 加密落库，读取时解密回明文。
import { Form, Input, InputNumber, Select } from 'antd'

/** 表单字段名（平铺在服务器表单里，与 name/env/accessType 等并列） */
export const SSH_KEYS = ['host', 'port', 'username', 'authType', 'password', 'privateKey', 'passphrase'] as const
export const HTTP_KEYS = ['baseUrl', 'token', 'timeoutMs'] as const
const KNOWN_KEYS = new Set<string>([...SSH_KEYS, ...HTTP_KEYS])

/** 解析已存 accessConfig JSON → 表单字段值 + 未识别扩展键（编辑保存时原样保留） */
export function parseAccessConfig(json?: string): { values: Record<string, unknown>; extras: Record<string, unknown> } {
  const values: Record<string, unknown> = {}
  const extras: Record<string, unknown> = {}
  if (json) {
    try {
      const obj = JSON.parse(json) as Record<string, unknown>
      for (const [k, v] of Object.entries(obj)) {
        if (KNOWN_KEYS.has(k)) values[k] = v
        else extras[k] = v
      }
    } catch {
      // 历史脏数据不是 JSON：忽略，由用户重填
    }
  }
  return { values, extras }
}

/** 表单字段值 → accessConfig JSON 字符串（丢弃空值，合并扩展键） */
export function buildAccessConfig(
  accessType: string,
  allValues: Record<string, unknown>,
  extras: Record<string, unknown> = {},
): string {
  const keys = accessType === 'http' ? HTTP_KEYS : SSH_KEYS
  const cfg: Record<string, unknown> = { ...extras }
  for (const k of keys) {
    const v = allValues[k]
    if (v === undefined || v === null || v === '') continue
    cfg[k] = v
  }
  // authType 固定回写，缺省 password 与后端默认对齐
  if (accessType !== 'http' && !cfg.authType) cfg.authType = 'password'
  return Object.keys(cfg).length ? JSON.stringify(cfg) : ''
}

/** 列表「配置」列的摘要展示，避免直接甩一整段 JSON */
export function summarizeAccessConfig(accessType: string, json?: string): string {
  const { values } = parseAccessConfig(json)
  if (accessType === 'http') {
    return (values.baseUrl as string) || (json ?? '')
  }
  const host = values.host as string
  if (!host) return json ?? ''
  const port = values.port ? `:${values.port}` : ''
  const user = values.username ? `${values.username}@` : ''
  const auth = values.authType === 'key' ? '（密钥）' : ''
  return `${user}${host}${port}${auth}`
}

export interface SmartParseResult {
  accessType?: 'ssh' | 'http'
  values: Record<string, unknown>
  /** 识别命中的描述，用于提示用户 */
  hits: string[]
}

/**
 * 从一段自由文本里识别连接信息。支持示例：
 *   ssh root@172.20.140.156:22 密码 abc123
 *   用户名 ubuntu 主机 10.0.0.8 端口 2222 password: xxx
 *   https://ci.example.com token: abc.def
 *   含 -----BEGIN ... PRIVATE KEY----- 的整段 PEM → 密钥认证
 */
export function smartParseAccess(text: string): SmartParseResult {
  const values: Record<string, unknown> = {}
  const hits: string[] = []
  // 已匹配片段从剩余文本中剔除，避免被后续规则误吞（如密码被当成主机）
  let rest = ` ${text} `
  const eat = (m: RegExpMatchArray | null, apply: (m: RegExpMatchArray) => void) => {
    if (!m) return false
    apply(m)
    rest = rest.replace(m[0], ' ')
    return true
  }

  // 1. PEM 私钥整段
  eat(rest.match(/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/), (m) => {
    values.privateKey = m[0].trim()
    values.authType = 'key'
    hits.push('私钥（密钥认证）')
  })

  // 2. URL → HTTP 接入
  eat(rest.match(/https?:\/\/[^\s，,。；;"']+/), (m) => {
    values.baseUrl = m[0].replace(/\/+$/, '')
    hits.push(`baseUrl=${values.baseUrl}`)
  })

  // 3. 关键字字段（先于 user@host：否则「密码 p@ss」里的 @ 会被误当 user@host）
  const kwPatterns: Array<{ re: RegExp; key: string; label: string; numeric?: boolean; mask?: boolean }> = [
    { re: /(?:用户名|用户|账号|username|user)\s*[:：=]?\s*([A-Za-z_][\w-]*)/i, key: 'username', label: '用户名' },
    { re: /(?:密码|口令|password|passwd|pwd)\s*[:：=]?\s*([^\s，,。；;"']+)/i, key: 'password', label: '密码', mask: true },
    { re: /(?:token|令牌|api[-_ ]?key)\s*[:：=]?\s*([^\s，,。；;"']+)/i, key: 'token', label: 'token', mask: true },
    { re: /(?:主机|地址|host)\s*[:：=]?\s*([A-Za-z0-9][\w.-]*)/i, key: 'host', label: '主机' },
    { re: /(?:端口|port)\s*[:：=]?\s*(\d{1,5})/i, key: 'port', label: '端口', numeric: true },
    { re: /(?:超时|timeout)\s*[:：=]?\s*(\d{2,7})\s*(?:ms|毫秒)?/i, key: 'timeoutMs', label: '超时', numeric: true },
  ]
  for (const { re, key, label, numeric, mask } of kwPatterns) {
    if (values[key] !== undefined) continue
    eat(rest.match(re), (m) => {
      values[key] = numeric ? Number(m[1]) : m[1]
      hits.push(mask ? label : `${label}=${m[1]}`)
    })
  }

  // 4. user@host[:port]（SSH 最常见写法；已识别字段不覆盖）
  eat(rest.match(/([A-Za-z_][\w-]*)@([A-Za-z0-9][\w.-]*?)(?::(\d{1,5}))?(?![\w.-])/), (m) => {
    if (values.username === undefined) {
      values.username = m[1]
      hits.push(`用户名=${m[1]}`)
    }
    if (values.host === undefined) {
      values.host = m[2]
      hits.push(`主机=${m[2]}`)
    }
    if (m[3] && values.port === undefined) {
      values.port = Number(m[3])
      hits.push(`端口=${m[3]}`)
    }
  })

  // 5. 兜底：裸 IP[:port] 当主机
  if (values.host === undefined) {
    eat(rest.match(/\b(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?\b/), (m) => {
      values.host = m[1]
      hits.push(`主机=${m[1]}`)
      if (m[2]) {
        values.port = Number(m[2])
        hits.push(`端口=${m[2]}`)
      }
    })
  }

  // 6. 推断接入类型
  let accessType: 'ssh' | 'http' | undefined
  if (values.baseUrl !== undefined) accessType = 'http'
  else if (values.host !== undefined || values.privateKey !== undefined || values.username !== undefined) accessType = 'ssh'

  if (accessType === 'ssh' && values.port === undefined) values.port = 22
  if (accessType === 'ssh' && values.authType === undefined) values.authType = values.privateKey !== undefined ? 'key' : 'password'
  return { accessType, values, hits }
}

/** 连接配置结构化表单项：随 accessType（ssh/http）与 authType（password/key）切换 */
export function ServerAccessFormItems({ accessType, authType }: { accessType?: string; authType?: string }) {
  if (accessType === 'http') {
    return (
      <>
        <Form.Item label="Base URL" name="baseUrl" rules={[{ required: true, message: '请输入 Base URL' }]}>
          <Input placeholder="如 https://ci.example.com" />
        </Form.Item>
        <Form.Item label="Token" name="token" extra="Bearer 令牌，保存时加密落库">
          <Input.Password placeholder="访问令牌" autoComplete="new-password" />
        </Form.Item>
        <Form.Item label="超时时间" name="timeoutMs">
          <InputNumber min={1000} max={600000} step={1000} addonAfter="ms" placeholder="默认 30000" style={{ width: 200 }} />
        </Form.Item>
      </>
    )
  }
  return (
    <>
      <Form.Item label="主机" name="host" rules={[{ required: true, message: '请输入主机地址' }]}>
        <Input placeholder="IP 或域名，如 172.20.140.156" />
      </Form.Item>
      <Form.Item label="端口" name="port" rules={[{ required: true, message: '请输入端口' }]}>
        <InputNumber min={1} max={65535} placeholder="22" style={{ width: 200 }} />
      </Form.Item>
      <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
        <Input placeholder="如 root / ubuntu" autoComplete="off" />
      </Form.Item>
      <Form.Item label="认证方式" name="authType" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'password', label: '密码' },
            { value: 'key', label: '私钥' },
          ]}
          style={{ width: 200 }}
        />
      </Form.Item>
      {authType === 'key' ? (
        <>
          <Form.Item label="私钥" name="privateKey" rules={[{ required: true, message: '请粘贴 PEM 私钥' }]}
            extra="粘贴完整 PEM（含 BEGIN/END 行），保存时加密落库">
            <Input.TextArea rows={5} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item label="私钥口令" name="passphrase" extra="私钥有 passphrase 时填写，可留空">
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </>
      ) : (
        <Form.Item label="密码" name="password" extra="保存时加密落库">
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      )}
    </>
  )
}
