// CAP-18/19/35 平台集成管理页（仅 ADMIN）：平台实例登记 + 可选机器人凭证 + 连接测试 + 启停。
// 机器人凭证仅供自动化（克隆、Jira 轮询、打 tag/Release）；人触发的写操作优先用操作人在
// 「设置 → 第三方账号」绑定的个人账号（CAP-35）。
import { useCallback, useEffect, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { ApiOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  changeIntegrationStatus,
  createIntegration,
  listIntegrations,
  testIntegration,
  testIntegrationDraft,
  updateIntegration,
} from '../api'
import type { Integration, IntegrationInput, IntegrationTestResult } from '../types'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

const TYPE_OPTIONS = [
  { value: 'GITLAB', label: 'GitLab（代码平台）' },
  { value: 'GITHUB', label: 'GitHub（代码平台，含 GHE）' },
  { value: 'JIRA', label: 'Jira（任务/Bug 同步）' },
  { value: 'FEISHU', label: '飞书（知识库导入 + 双向机器人）' },
]

const AUTH_OPTIONS = [
  { value: 'PAT', label: '个人访问令牌 PAT（Jira 8.14+）' },
  { value: 'BASIC', label: '用户名 + 密码（Jira 8.13 及更早）' },
]

const TYPE_COLOR: Record<string, string> = {
  GITLAB: 'orange',
  GITHUB: 'default',
  JIRA: 'blue',
  FEISHU: 'geekblue',
}

/** 飞书自建应用默认开放平台地址（CAP-45） */
const FEISHU_DEFAULT_BASE_URL = 'https://open.feishu.cn'

/** 表单值 = 集成字段 + 飞书机器人槽（botEnabled/botDefaultActor 仅表单态，保存时折进 configJson） */
type IntegrationFormValues = IntegrationInput & {
  botEnabled?: boolean
  botDefaultActor?: string
}

/** 从 configJson 解析飞书 bot 槽（口径同 FeishuBotGateway.botEnabled：槽存在即启用，enabled:false 显式关闭） */
function parseFeishuBot(configJson?: string | null): { enabled: boolean; defaultActor?: string } {
  if (!configJson) return { enabled: false }
  try {
    const bot = JSON.parse(configJson)?.bot
    if (!bot || typeof bot !== 'object') return { enabled: false }
    return {
      enabled: bot.enabled !== false,
      defaultActor: typeof bot.defaultActor === 'string' ? bot.defaultActor : undefined,
    }
  } catch {
    return { enabled: false }
  }
}

/** 组装飞书 configJson：保留既有扩展槽，覆写 bot 槽；未启用且无既有 bot 槽时不动 configJson */
function buildFeishuConfigJson(
  existing: string | null | undefined,
  botEnabled?: boolean,
  botDefaultActor?: string,
): string | undefined {
  let cfg: Record<string, unknown> = {}
  if (existing) {
    try {
      const parsed = JSON.parse(existing)
      if (parsed && typeof parsed === 'object') cfg = parsed
    } catch {
      /* 既有 configJson 非法不阻断保存，覆写 bot 槽即可 */
    }
  }
  const hadBot = cfg.bot !== null && typeof cfg.bot === 'object'
  if (botEnabled) {
    cfg.bot = {
      ...(hadBot ? (cfg.bot as object) : {}),
      enabled: true,
      ...(botDefaultActor?.trim() ? { defaultActor: botDefaultActor.trim() } : {}),
    }
  } else if (hadBot) {
    cfg.bot = { ...(cfg.bot as object), enabled: false }
  } else {
    return undefined
  }
  return JSON.stringify(cfg)
}

/** 列表连通性实时探测结果（逐行异步更新） */
type ConnState =
  | { phase: 'probing' }
  | { phase: 'done'; ok: boolean; message: string; detail?: string | null }

export default function IntegrationsPage() {
  const [items, setItems] = useState<Integration[]>([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<Integration | null>(null)
  const [testingId, setTestingId] = useState<number | null>(null)
  const [testingForm, setTestingForm] = useState(false)
  const [saving, setSaving] = useState(false)
  const [conn, setConn] = useState<Record<number, ConnState>>({})
  const [form] = Form.useForm<IntegrationFormValues>()
  const formType = Form.useWatch('type', form)
  const formAuthType = Form.useWatch('authType', form)
  const formToken = Form.useWatch('token', form)
  const isJira = formType === 'JIRA'
  const isGitHub = formType === 'GITHUB'
  const isFeishu = formType === 'FEISHU'
  const isBasic = (isJira && formAuthType === 'BASIC') || isFeishu

  /** 列表加载后逐行实时探测连通性（结果回填「连通性」列） */
  const probeAll = useCallback((list: Integration[]) => {
    setConn(Object.fromEntries(list.map(i => [i.id, { phase: 'probing' as const }])))
    list.forEach(i => {
      testIntegration(i.id)
        .then(r =>
          setConn(prev => ({
            ...prev,
            [i.id]: { phase: 'done', ok: r.ok, message: r.message, detail: r.detail },
          })),
        )
        .catch(e =>
          setConn(prev => ({
            ...prev,
            [i.id]: { phase: 'done', ok: false, message: (e as Error).message },
          })),
        )
    })
  }, [])

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const list = await listIntegrations()
      setItems(list)
      probeAll(list)
    } catch (e) {
      showError(e, '加载集成失败')
    } finally {
      setLoading(false)
    }
  }, [probeAll])

  useEffect(() => {
    reload()
  }, [reload])

  useEffect(() => {
    if (!editOpen) return
    const bot = editing?.type === 'FEISHU' ? parseFeishuBot(editing.configJson) : undefined
    form.setFieldsValue(
      editing
        ? {
            type: editing.type,
            name: editing.name,
            baseUrl: editing.baseUrl,
            authType: editing.authType ?? 'PAT',
            username: '',
            token: '',
            botEnabled: bot?.enabled ?? false,
            botDefaultActor: bot?.defaultActor,
          }
        : { type: 'JIRA', name: '', baseUrl: '', authType: 'PAT', username: '', token: '', botEnabled: false },
    )
  }, [editOpen, editing, form])

  const onSave = async (values: IntegrationFormValues) => {
    setSaving(true)
    try {
      // GitLab/GitHub 仅 PAT，不下发 authType；飞书固定 BASIC 双行（username=App ID, token=App Secret）
      const { botEnabled, botDefaultActor, ...rest } = values
      const payload: IntegrationInput = {
        ...rest,
        authType: isJira ? values.authType : isFeishu ? 'BASIC' : undefined,
        username: isBasic && values.username ? values.username : undefined,
        // 飞书机器人槽折进 configJson（bot 槽 = 机器人启用开关，多飞书应用并存时网关只挑带槽的）
        configJson: isFeishu
          ? buildFeishuConfigJson(editing?.configJson, botEnabled, botDefaultActor)
          : undefined,
      }
      if (editing) {
        // token 留空 = 保持不变
        await updateIntegration(editing.id, { ...payload, token: values.token || undefined })
        message.success('已更新')
      } else {
        await createIntegration(payload)
        message.success('已创建，建议先点「测试」验证连通性')
      }
      setEditOpen(false)
      reload()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onTest = async (row: Integration) => {
    setTestingId(row.id)
    setConn(prev => ({ ...prev, [row.id]: { phase: 'probing' } }))
    try {
      const r = await testIntegration(row.id)
      setConn(prev => ({
        ...prev,
        [row.id]: { phase: 'done', ok: r.ok, message: r.message, detail: r.detail },
      }))
      if (r.ok) {
        message.success(`${row.name}：${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      } else {
        message.error(`${row.name}：${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      }
    } catch (e) {
      setConn(prev => ({
        ...prev,
        [row.id]: { phase: 'done', ok: false, message: (e as Error).message },
      }))
      showError(e, '测试失败')
    } finally {
      setTestingId(null)
    }
  }

  /** 表单内「测试连接」：新建走未保存试连；编辑且 token 留空（凭据未改）时测已保存实例 */
  const onTestForm = async () => {
    let values: IntegrationInput
    try {
      values = await form.validateFields(['type', 'baseUrl', 'authType', 'username', 'token'])
    } catch {
      return // 校验未过，错误已标红
    }
    setTestingForm(true)
    try {
      if (!editing && !values.token) {
        message.warning('未填写平台凭证（纯实例登记），无需测试连接')
        return
      }
      const r: IntegrationTestResult =
        editing && !values.token
          ? await testIntegration(editing.id)
          : await testIntegrationDraft({
              ...values,
              name: values.name ?? '',
              authType: isJira ? values.authType : isFeishu ? 'BASIC' : undefined,
              username: isBasic && values.username ? values.username : undefined,
            })
      if (editing) {
        setConn(prev => ({
          ...prev,
          [editing.id]: { phase: 'done', ok: r.ok, message: r.message, detail: r.detail },
        }))
      }
      if (r.ok) {
        message.success(`${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      } else {
        message.error(`${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      }
    } catch (e) {
      showError(e, '测试失败')
    } finally {
      setTestingForm(false)
    }
  }

  const onToggle = async (row: Integration) => {
    try {
      await changeIntegrationStatus(row.id, row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')
      message.success(row.status === 'ENABLED' ? '已停用' : '已启用')
      reload()
    } catch (e) {
      showError(e, '操作失败')
    }
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="平台集成"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditing(null)
              setEditOpen(true)
            }}
          >
            新建集成
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        登记 GitLab/GitHub/Jira/飞书 平台实例。平台凭证（机器人）选填：供克隆、Jira 轮询同步、打 tag/Release、
        飞书文档导入知识库、飞书双向机器人（CAP-75，同一集成填 App ID/App Secret 即启用）等自动化使用；人触发的写操作（推送分支、建 MR/PR、Jira 状态/工时回写）优先使用操作人在
        「设置 → 第三方账号」绑定的个人账号。实例启用后，用户即可在「设置」中绑定自己的账号。
      </Typography.Paragraph>
      <FitTable<Integration>
        rowKey="id"
        loading={loading}
        dataSource={items}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '还没有集成实例，点右上角「新建集成」接入 GitLab / GitHub / Jira' }}
        columns={[
          {
            title: '类型',
            dataIndex: 'type',
            width: 100,
            render: (t: string) => <Tag color={TYPE_COLOR[t] ?? 'default'}>{t}</Tag>,
          },
          { title: '名称', dataIndex: 'name' },
          {
            title: '地址',
            dataIndex: 'baseUrl',
            render: (u: string) => (
              <Typography.Text copyable style={{ fontSize: 12 }}>{u}</Typography.Text>
            ),
          },
          {
            title: '凭据',
            dataIndex: 'hasToken',
            width: 120,
            render: (has: boolean, row) =>
              has ? (
                <Tag color="green">{row.authType === 'BASIC' ? '账号密码' : 'PAT'}</Tag>
              ) : (
                <Tag>未配置</Tag>
              ),
          },
          {
            title: '连通性',
            key: 'conn',
            width: 100,
            render: (_, row) => {
              const c = conn[row.id]
              if (!c || c.phase === 'probing') {
                return <Badge status="processing" text="探测中" />
              }
              return (
                <Tooltip title={`${c.message}${c.detail ? `（${c.detail}）` : ''}`}>
                  {c.ok ? (
                    <Badge status="success" text="正常" />
                  ) : (
                    <Badge status="error" text="异常" />
                  )}
                </Tooltip>
              )
            },
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (s: string) => (
              <Tag color={s === 'ENABLED' ? 'green' : 'default'}>{s === 'ENABLED' ? '启用' : '停用'}</Tag>
            ),
          },
          {
            title: '操作',
            width: 220,
            render: (_, row) => (
              <Space size={4}>
                <Tooltip title="验证地址可达 + 凭据有效">
                  <Button
                    size="small"
                    loading={testingId === row.id}
                    onClick={() => onTest(row)}
                  >
                    测试
                  </Button>
                </Tooltip>
                <Button
                  size="small"
                  onClick={() => {
                    setEditing(row)
                    setEditOpen(true)
                  }}
                >
                  编辑
                </Button>
                <Button size="small" onClick={() => onToggle(row)}>
                  {row.status === 'ENABLED' ? '停用' : '启用'}
                </Button>
              </Space>
            ),
          },
        ]}
      />

      <Drawer
        title={editing ? `编辑集成「${editing.name}」` : '新建集成'}
        open={editOpen}
        onClose={() => setEditOpen(false)}
        width={DRAWER_DEFAULT}
        destroyOnHidden
        footer={
          <Space style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
            <Tooltip
              title={
                editing && !form.getFieldValue('token')
                  ? '凭据未修改，将测试已保存的配置'
                  : '按当前表单内容试连，凭据不会保存'
              }
            >
              <Button icon={<ApiOutlined />} loading={testingForm} onClick={onTestForm}>
                测试连接
              </Button>
            </Tooltip>
            <Space>
              <Button onClick={() => setEditOpen(false)}>取消</Button>
              <Button type="primary" loading={saving} onClick={() => form.submit()}>
                保存
              </Button>
            </Space>
          </Space>
        }
      >
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="平台类型" name="type" rules={[{ required: true, message: '请选择平台类型' }]}>
            <Select
              options={TYPE_OPTIONS}
              disabled={!!editing}
              onChange={t => {
                if (t === 'FEISHU' && !form.getFieldValue('baseUrl')) {
                  form.setFieldValue('baseUrl', FEISHU_DEFAULT_BASE_URL)
                }
              }}
            />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 公司 Jira / 研发 GitLab" />
          </Form.Item>
          <Form.Item
            label="实例地址"
            name="baseUrl"
            rules={[{ required: true, message: '请输入实例地址' }]}
            extra={
              isFeishu
                ? '默认 https://open.feishu.cn；飞书私有部署填私有地址'
                : isGitHub
                  ? 'github.com 填 https://github.com（API 自动走 api.github.com）；GHE 填实例地址（自动拼 /api/v3）'
                  : '仅 http/https，如 https://jira.example.com'
            }
          >
            <Input
              placeholder={
                isFeishu ? FEISHU_DEFAULT_BASE_URL : isGitHub ? 'https://github.com' : 'https://jira.example.com'
              }
            />
          </Form.Item>
          {isJira && (
            <Form.Item
              label="认证方式"
              name="authType"
              rules={[{ required: true, message: '请选择认证方式' }]}
              extra={
                editing
                  ? '认证方式创建后不可切换，换方式请新建集成'
                  : 'Jira Server/DC 8.13 及更早没有 PAT，选「用户名 + 密码」'
              }
            >
              <Select options={AUTH_OPTIONS} disabled={!!editing} />
            </Form.Item>
          )}
          {isBasic && (
            <Form.Item
              label={isFeishu ? 'App ID' : '用户名'}
              name="username"
              rules={
                editing || !formToken
                  ? []
                  : [
                      {
                        required: true,
                        message: isFeishu ? '飞书自建应用需要填写 App ID' : 'Basic Auth 需要填写 Jira 登录用户名',
                      },
                    ]
              }
              extra={
                editing
                  ? '留空表示沿用原' + (isFeishu ? ' App ID' : '用户名')
                  : isFeishu
                    ? '飞书开放平台自建应用的 App ID（配置凭证时必填）'
                    : '配置平台凭证时必填'
              }
            >
              <Input
                placeholder={isFeishu ? 'cli_xxxxxxxxxxxxxxxx' : 'Jira 登录用户名'}
                autoComplete="off"
              />
            </Form.Item>
          )}
          <Form.Item
            label={isFeishu ? 'App Secret（平台凭证）' : isBasic ? '密码（平台凭证）' : '访问令牌 PAT（平台凭证）'}
            name="token"
            extra={
              editing
                ? '留空表示保持现有凭据不变'
                : isFeishu
                  ? '飞书自建应用的 App Secret（加密存储）。知识库导入需「云文档」读取权限并把文档分享给应用；双向机器人需机器人能力 + im:message / im:message:send_as_bot 权限 + im.message.receive_v1 长连接订阅（配好后重启应用生效）。留空 = 纯实例登记'
                  : isBasic
                    ? 'Jira 登录密码（加密存储）。留空 = 纯实例登记，自动化（轮询同步等）将不可用'
                    : isGitHub
                      ? 'GitHub Personal Access Token（classic 需 repo scope；fine-grained 按仓库授权 Contents/Pull requests 读写）。留空 = 纯实例登记，自动化（克隆/Release 等）将不可用'
                      : 'Jira Server/DC 8.14+：个人访问令牌；GitLab：Personal Access Token（api scope）。留空 = 纯实例登记，自动化（克隆/轮询/tag/Release 等）将不可用'
            }
          >
            <Input.Password
              placeholder={editing ? '（不修改请留空）' : isBasic ? (isFeishu ? '粘贴 App Secret' : '输入密码（可留空不配置）') : '粘贴 token（可留空不配置）'}
              autoComplete="off"
            />
          </Form.Item>
          {isFeishu && (
            <>
              <Form.Item
                label="双向机器人（CAP-75）"
                name="botEnabled"
                valuePropName="checked"
                extra="启用后此集成作为机器人网关凭据（启动时长连接收事件，需重启应用生效）。多个飞书应用并存时，只有勾选此项的集成会被机器人选中；纯文档导入的集成不要勾"
              >
                <Switch checkedChildren="启用" unCheckedChildren="关闭" />
              </Form.Item>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.botEnabled !== cur.botEnabled}
              >
                {({ getFieldValue }) =>
                  getFieldValue('botEnabled') ? (
                    <Form.Item
                      label="机器人默认操作身份"
                      name="botDefaultActor"
                      extra="机器人在群内执行问答/构建查询时使用的平台账号，权限 = 该账号既有权限；留空回落平台默认（devmind.bot.default-actor）"
                    >
                      <Input placeholder="平台用户名，如 liuchangqing（可留空）" autoComplete="off" />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
            </>
          )}
        </Form>
      </Drawer>
    </Card>
  )
}
