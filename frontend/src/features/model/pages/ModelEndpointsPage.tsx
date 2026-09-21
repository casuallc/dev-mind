// CAP-48 模型接入管理页（仅 ADMIN）：端点登记 + 连接测试 + 平台默认 + 启停/删除。
// 类型分两种：向量化（Embedding，知识库索引/检索用）与通用模型（CHAT，本期只登记 + 连接测试）。
// 维度是连接测试的产物而非人工输入——手填维度正是「换模型后检索静默全空」的成因，
// 所以表单里没有这一项，只有测试结果里能看到它；对话端点没有维度这回事。
import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Collapse,
  Descriptions,
  Divider,
  Drawer,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { ApiOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  changeEndpointStatus,
  createModelEndpoint,
  deleteModelEndpoint,
  listModelEndpoints,
  setDefaultEndpoint,
  testModelEndpoint,
  testModelEndpointDraft,
  updateModelEndpoint,
} from '../api'
import type { EndpointTestResult, ModelEndpoint, ModelEndpointInput } from '../types'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { fmtTime } from '../../../shared/utils/format'

const KIND_OPTIONS = [
  { value: 'EMBEDDING', label: 'Embedding（向量化，知识库检索用）' },
  { value: 'CHAT', label: '通用模型（对话，Chat Completions）' },
]

/** 提供方选项按类型走：同一个 openai-compatible，向量打 /embeddings，对话打 /chat/completions */
const PROVIDER_OPTIONS: Record<string, { value: string; label: string }[]> = {
  EMBEDDING: [
    { value: 'openai-compatible', label: 'OpenAI 兼容 /embeddings' },
    { value: 'mock', label: 'Mock（确定性哈希向量，测试用）' },
  ],
  CHAT: [
    { value: 'openai-compatible', label: 'OpenAI 兼容 /chat/completions' },
    { value: 'mock', label: 'Mock（假回复，测试用）' },
  ],
}

const KIND_META: Record<string, { label: string; color: string }> = {
  EMBEDDING: { label: '向量化', color: 'blue' },
  CHAT: { label: '通用模型', color: 'purple' },
}

const kindMeta = (kind: string) => KIND_META[kind] ?? { label: kind, color: 'default' }

const PROVIDER_COLOR: Record<string, string> = {
  'openai-compatible': 'blue',
  mock: 'default',
}

const isChatKind = (kind?: string | null) => kind === 'CHAT'

/** 测试成功 toast：只有向量端点才有「探测维度」可说 */
const okToast = (r: EndpointTestResult) =>
  `连接成功（${r.latencyMs} ms${r.dimensions != null ? `，探测维度 ${r.dimensions}` : ''}）`

/** 测试结果提示条：成功展示延迟与实测维度，维度变化单独告警（已有索引需重建） */
function TestResultAlert({ result }: { result: EndpointTestResult }) {
  const changed = result.dimensionChanged
  return (
    <>
      <Alert
        type={result.ok ? 'success' : 'error'}
        showIcon
        message={`${result.ok ? '连接成功' : '连接失败'}：${result.message}`}
        description={
          result.ok ? (
            <Space size={16} wrap>
              <span>耗时 {result.latencyMs} ms</span>
              {result.model && <span>模型 {result.model}</span>}
              {/* 对话端点没有维度这回事：dimensions 为 null 时干脆不显示，别写成"未知" */}
              {result.dimensions != null && (
                <span>
                  探测维度 <Typography.Text strong>{result.dimensions}</Typography.Text>
                </span>
              )}
            </Space>
          ) : undefined
        }
      />
      {changed && (
        <Alert
          style={{ marginTop: 8 }}
          type="warning"
          showIcon
          message={`维度已变化：${changed.from ?? '未探测'} → ${changed.to ?? '未知'}`}
          description="该端点上已建索引的知识库向量维度不再匹配，需到知识库详情页执行「重建全库索引」，否则检索会返回维度失配告警。"
        />
      )}
    </>
  )
}

export default function ModelEndpointsPage() {
  const [items, setItems] = useState<ModelEndpoint[]>([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<ModelEndpoint | null>(null)
  const [managing, setManaging] = useState<ModelEndpoint | null>(null)
  const [saving, setSaving] = useState(false)
  const [testingForm, setTestingForm] = useState(false)
  const [formResult, setFormResult] = useState<EndpointTestResult | null>(null)
  const [testingId, setTestingId] = useState<number | null>(null)
  const [manageResult, setManageResult] = useState<EndpointTestResult | null>(null)
  const [form] = Form.useForm<ModelEndpointInput>()
  const formProvider = Form.useWatch('provider', form)
  const formKind = Form.useWatch('kind', form)
  const isMock = formProvider === 'mock'
  const isChat = isChatKind(formKind)

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setItems(await listModelEndpoints())
    } catch (e) {
      showError(e, '加载模型端点失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  useEffect(() => {
    if (!editOpen) return
    setFormResult(null)
    form.setFieldsValue(
      editing
        ? {
            kind: editing.kind,
            name: editing.name,
            provider: editing.provider,
            baseUrl: editing.baseUrl ?? '',
            apiKey: '',
            model: editing.model ?? '',
            timeoutSeconds: editing.timeoutSeconds,
            batchSize: editing.batchSize,
            topK: editing.topK ?? undefined,
            threshold: editing.threshold ?? undefined,
          }
        : {
            kind: 'EMBEDDING',
            name: '',
            provider: 'openai-compatible',
            baseUrl: '',
            apiKey: '',
            model: '',
            timeoutSeconds: 30,
            batchSize: 32,
          },
    )
  }, [editOpen, editing, form])

  const onSave = async (values: ModelEndpointInput) => {
    setSaving(true)
    try {
      const payload: ModelEndpointInput = {
        ...values,
        // mock 端点不需要地址/模型/凭据，留空串会被服务端当"已填"（校验只判 blank，两者等价，这里清干净）
        baseUrl: isMock ? undefined : values.baseUrl,
        model: isMock ? undefined : values.model,
        apiKey: values.apiKey || undefined,
      }
      if (isChat) {
        // 对话端点不吃向量语义：表单里被隐藏的字段可能还留着切换类型前的值，别带上去
        payload.batchSize = undefined
        payload.topK = undefined
        payload.threshold = undefined
      }
      if (editing) {
        // apiKey 留空 = 保持现有凭据不变
        await updateModelEndpoint(editing.id, payload)
        message.success('已更新')
      } else {
        await createModelEndpoint(payload)
        message.success(isChat ? '已创建，建议先点「测试」发一条探针消息' : '已创建，建议先点「测试」探测维度')
      }
      setEditOpen(false)
      reload()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  /** 表单内「测试连接」：新建/改了凭据走未保存预检；编辑且凭据未改时测已保存实例（探测结果会落库） */
  const onTestForm = async () => {
    try {
      // 只校验探针非填不可的四项：名称之类的留给「保存」，不该挡住试连
      await form.validateFields(['kind', 'provider', 'baseUrl', 'model'])
    } catch {
      return // 校验未过，错误已标红
    }
    // 取值必须取整个表单：validateFields(nameList) 只回 nameList 那几个字段
    //（rc-field-form：`getFieldsValue(namePathList)`），拿它的返回值当表单值用，
    // apiKey 与 timeoutSeconds 会被静默丢掉——表现是服务端不发 Authorization、上游回 401。
    const values = form.getFieldsValue()
    setTestingForm(true)
    try {
      const base: ModelEndpointInput = {
        ...values,
        kind: values.kind ?? editing?.kind,
        name: values.name || editing?.name || '（未命名端点）',
        baseUrl: isMock ? undefined : values.baseUrl,
        model: isMock ? undefined : values.model,
      }
      if (isChat) {
        // 与 onSave 一致：对话端点不吃向量语义，别把切类型前残留的检索参数发出去
        base.batchSize = undefined
        base.topK = undefined
        base.threshold = undefined
      }
      const r =
        editing && !values.apiKey
          ? await testModelEndpoint(editing.id)
          : await testModelEndpointDraft(base)
      setFormResult(r)
      if (r.ok) {
        message.success(okToast(r))
      } else {
        message.error(`连接失败：${r.message}`)
      }
    } catch (e) {
      showError(e, '测试失败')
    } finally {
      setTestingForm(false)
    }
  }

  /** 管理抽屉内「测试连接」：已存端点，回写 last_test_* 与实测维度 */
  const onTest = async (row: ModelEndpoint) => {
    setTestingId(row.id)
    setManageResult(null)
    try {
      const r = await testModelEndpoint(row.id)
      setManageResult(r)
      if (r.ok) {
        message.success(okToast(r))
      } else {
        message.error(`连接失败：${r.message}`)
      }
      reload()
    } catch (e) {
      showError(e, '测试失败')
    } finally {
      setTestingId(null)
    }
  }

  const onSetDefault = async (row: ModelEndpoint) => {
    try {
      await setDefaultEndpoint(row.id)
      message.success(`「${row.name}」已设为该类型的平台默认端点`)
      reload()
    } catch (e) {
      showError(e, '设置默认失败')
    }
  }

  const onToggle = async (row: ModelEndpoint) => {
    try {
      await changeEndpointStatus(row.id, row.status === 'active' ? 'disabled' : 'active')
      message.success(
        row.status === 'active'
          ? isChatKind(row.kind)
            ? '已停用'
            : '已停用（引用它的知识库自动回落到平台默认端点）'
          : '已启用',
      )
      reload()
    } catch (e) {
      showError(e, '操作失败')
    }
  }

  const onDelete = async (row: ModelEndpoint) => {
    try {
      await deleteModelEndpoint(row.id)
      message.success('已删除')
      setManaging(null)
      reload()
    } catch (e) {
      showError(e, '删除失败')
    }
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="模型接入"
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
            新建端点
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        登记模型服务端点。<b>向量化（Embedding）</b>端点供知识库索引与检索使用，解析链为：知识库指定的端点 →
        平台默认端点 → 皆无则降级（索引停用、检索退化为关键词匹配）；向量维度由「测试连接」实测探测并落库，
        不可人工填写——维度填错会让检索静默搜不到东西。<b>通用模型（对话）</b>端点本期只支持登记与连接测试
        （测试会实调一次 /chat/completions 并回显模型回复），暂无消费方。密钥加密存储，不回显。
      </Typography.Paragraph>
      <FitTable<ModelEndpoint>
        rowKey="id"
        loading={loading}
        dataSource={items}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '还没有端点，点右上角「新建端点」接入 Embedding 或对话模型服务' }}
        columns={[
          {
            title: '名称',
            dataIndex: 'name',
            render: (name: string, row) => (
              <Space size={6}>
                <span>{name}</span>
                {row.isDefault && <Tag color="gold">默认</Tag>}
              </Space>
            ),
          },
          {
            title: '类型',
            dataIndex: 'kind',
            width: 110,
            render: (k: string) => <Tag color={kindMeta(k).color}>{kindMeta(k).label}</Tag>,
          },
          {
            title: '模型',
            key: 'model',
            render: (_, row) => (
              <Space size={6}>
                <Tag color={PROVIDER_COLOR[row.provider] ?? 'default'}>{row.provider}</Tag>
                <Typography.Text style={{ fontSize: 12 }}>{row.model ?? '—'}</Typography.Text>
              </Space>
            ),
          },
          {
            title: '维度',
            dataIndex: 'dimensions',
            width: 90,
            render: (d: number | null, row) => {
              if (isChatKind(row.kind)) {
                return (
                  <Tooltip title="对话端点没有维度这回事">
                    <Typography.Text type="secondary">—</Typography.Text>
                  </Tooltip>
                )
              }
              return d == null ? (
                <Tooltip title="尚未探测：点「管理 → 测试连接」实测一次">
                  <Tag>未探测</Tag>
                </Tooltip>
              ) : (
                d
              )
            },
          },
          {
            title: '超时 · 批量',
            key: 'limits',
            width: 110,
            render: (_, row) =>
              isChatKind(row.kind) ? `${row.timeoutSeconds}s` : `${row.timeoutSeconds}s · ${row.batchSize}`,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (s: string) => (
              <Tag color={s === 'active' ? 'green' : 'default'}>{s === 'active' ? '启用' : '停用'}</Tag>
            ),
          },
          {
            title: '最近测试',
            key: 'lastTest',
            width: 170,
            render: (_, row) => {
              if (!row.lastTestAt) {
                return <Typography.Text type="secondary">未测试</Typography.Text>
              }
              return (
                <Tooltip title={row.lastTestMessage ?? ''}>
                  <Space size={6}>
                    <Badge status={row.lastTestOk ? 'success' : 'error'} />
                    <span style={{ fontSize: 12 }}>{fmtTime(row.lastTestAt)}</span>
                  </Space>
                </Tooltip>
              )
            },
          },
          {
            title: '操作',
            width: 140,
            render: (_, row) => (
              <Space>
                <Button
                  size="small"
                  loading={testingId === row.id}
                  onClick={() => onTest(row)}
                >
                  测试
                </Button>
                <Button
                  size="small"
                  onClick={() => {
                    setManaging(row)
                    setManageResult(null)
                  }}
                >
                  管理
                </Button>
              </Space>
            ),
          },
        ]}
      />

      {/* 新建/编辑抽屉 */}
      <Drawer
        title={editing ? `编辑端点「${editing.name}」` : '新建端点'}
        open={editOpen}
        onClose={() => setEditOpen(false)}
        width={620}
        destroyOnHidden
        footer={
          <Space style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
            <Tooltip
              title={
                editing && !form.getFieldValue('apiKey')
                  ? '凭据未修改，将测试已保存的端点'
                  : '按当前表单内容试连一次，凭据不会保存'
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
          {formResult && (
            <>
              <TestResultAlert result={formResult} />
              <Divider />
            </>
          )}
          <Form.Item
            label="类型"
            name="kind"
            rules={[{ required: true, message: '请选择类型' }]}
            extra={
              editing
                ? '类型创建后不可变更，需要另一种类型请新建端点'
                : '向量化端点用于知识库检索；通用模型端点本期只做登记与连接测试，暂无消费方'
            }
          >
            <Select options={KIND_OPTIONS} disabled={!!editing} />
          </Form.Item>
          <Form.Item label="提供方" name="provider" rules={[{ required: true, message: '请选择提供方' }]}>
            <Select options={PROVIDER_OPTIONS[formKind ?? 'EMBEDDING'] ?? PROVIDER_OPTIONS.EMBEDDING} />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder={isChat ? '如 公司通用模型 / 阿里云 qwen-plus' : '如 公司 BGE-M3 / 阿里云 text-embedding-v3'} />
          </Form.Item>
          {!isMock && (
            <>
              <Form.Item
                label="服务地址"
                name="baseUrl"
                rules={[{ required: true, message: '请输入服务地址' }]}
                extra={
                  isChat
                    ? 'OpenAI 兼容服务根地址，调用时自动拼 /chat/completions，如 https://api.openai.com/v1'
                    : 'OpenAI 兼容服务根地址，调用时自动拼 /embeddings，如 https://api.openai.com/v1'
                }
              >
                <Input placeholder="https://api.openai.com/v1" />
              </Form.Item>
              <Form.Item
                label="API Key"
                name="apiKey"
                extra={
                  editing
                    ? '留空表示保持现有密钥不变'
                    : '加密存储，不回显；本地服务无需鉴权时可留空'
                }
              >
                <Input.Password placeholder={editing ? '（不修改请留空）' : '粘贴 API Key（可留空）'} autoComplete="off" />
              </Form.Item>
              <Form.Item
                label="模型名"
                name="model"
                rules={[{ required: true, message: '请输入模型名' }]}
                extra={
                  isChat
                    ? '传给 /chat/completions 的 model 字段，如 gpt-4o-mini / qwen-plus'
                    : '传给 /embeddings 的 model 字段，如 text-embedding-3-small / bge-m3'
                }
              >
                <Input placeholder={isChat ? 'gpt-4o-mini' : 'bge-m3'} />
              </Form.Item>
            </>
          )}
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              label="超时（秒）"
              name="timeoutSeconds"
              extra="单次请求超时 1~600"
              style={{ flex: 1 }}
            >
              <InputNumber min={1} max={600} style={{ width: '100%' }} />
            </Form.Item>
            {/* 对话端点没有分批/检索参数这回事，整块收敛掉，别让必填项看起来非填不可 */}
            {!isChat && (
              <Form.Item
                label="单批条数"
                name="batchSize"
                extra="长文档分批调用，1~256"
                style={{ flex: 1 }}
              >
                <InputNumber min={1} max={256} style={{ width: '100%' }} />
              </Form.Item>
            )}
          </Space>
          {!isChat && (
            <Collapse
              size="small"
              items={[
                {
                  key: 'advanced',
                  label: '高级：检索参数覆盖（留空 = 用平台默认）',
                  children: (
                    <>
                      <Form.Item
                        label="检索条数 topK"
                        name="topK"
                        extra="不同模型的合理取值差异大，一般留空即可"
                      >
                        <InputNumber min={1} max={100} style={{ width: '100%' }} placeholder="平台默认" />
                      </Form.Item>
                      <Form.Item
                        label="余弦阈值 threshold"
                        name="threshold"
                        extra="0~1；哈希类模型的相似度分布与真实 embedding 不是一个量级，必要时才覆盖"
                      >
                        <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} placeholder="平台默认" />
                      </Form.Item>
                    </>
                  ),
                },
              ]}
            />
          )}
        </Form>
      </Drawer>

      {/* 管理抽屉：测试/设为默认/启停/删除（行内只留最常用的「测试」） */}
      <Drawer
        title={managing ? `端点「${managing.name}」` : ''}
        open={!!managing}
        onClose={() => setManaging(null)}
        width={560}
        destroyOnHidden
      >
        {managing && (
          <>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="类型">
                <Tag color={kindMeta(managing.kind).color}>{kindMeta(managing.kind).label}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="提供方">{managing.provider}</Descriptions.Item>
              <Descriptions.Item label="服务地址">{managing.baseUrl ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="模型">{managing.model ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="凭据">
                {managing.hasApiKey ? <Tag color="green">已配置</Tag> : <Tag>未配置</Tag>}
              </Descriptions.Item>
              {!isChatKind(managing.kind) && (
                <>
                  <Descriptions.Item label="向量维度">
                    {managing.dimensions == null ? '未探测（点下方「测试连接」实测）' : managing.dimensions}
                  </Descriptions.Item>
                  <Descriptions.Item label="检索参数覆盖">
                    topK {managing.topK ?? '平台默认'} · threshold {managing.threshold ?? '平台默认'}
                  </Descriptions.Item>
                </>
              )}
              <Descriptions.Item label={isChatKind(managing.kind) ? '超时' : '超时 · 批量'}>
                {managing.timeoutSeconds}s
                {isChatKind(managing.kind) ? '' : ` · ${managing.batchSize}`}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={managing.status === 'active' ? 'green' : 'default'}>
                  {managing.status === 'active' ? '启用' : '停用'}
                </Tag>
                {managing.isDefault && <Tag color="gold">平台默认</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="最近测试">
                {managing.lastTestAt ? (
                  // 时间与结果消息分上下两行：同行 Space 里长消息会把时间挤成竖排折行
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <Space size={6}>
                      <Badge status={managing.lastTestOk ? 'success' : 'error'} />
                      <span style={{ whiteSpace: 'nowrap' }}>{fmtTime(managing.lastTestAt)}</span>
                    </Space>
                    {managing.lastTestMessage && (
                      <Typography.Paragraph
                        type="secondary"
                        style={{ fontSize: 12, marginBottom: 0, wordBreak: 'break-all' }}
                        ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}
                      >
                        {managing.lastTestMessage}
                      </Typography.Paragraph>
                    )}
                  </Space>
                ) : (
                  '未测试'
                )}
              </Descriptions.Item>
            </Descriptions>

            {manageResult && (
              <div style={{ marginTop: 12 }}>
                <TestResultAlert result={manageResult} />
              </div>
            )}

            <Divider />
            <Space wrap>
              <Button
                icon={<ApiOutlined />}
                loading={testingId === managing.id}
                onClick={() => onTest(managing)}
              >
                测试连接
              </Button>
              <Button
                icon={<EditOutlined />}
                onClick={() => {
                  setEditing(managing)
                  setManageResult(null)
                  setEditOpen(true)
                }}
              >
                编辑
              </Button>
              <Tooltip
                title={
                  isChatKind(managing.kind)
                    ? '设为默认后，将来接入的对话类能力在「未指定端点」时回落到它（本期还没有消费方）'
                    : '调用解析链在「未指定端点」的知识库上回落到它'
                }
              >
                <Button disabled={managing.isDefault} onClick={() => onSetDefault(managing)}>
                  设为平台默认
                </Button>
              </Tooltip>
              <Button onClick={() => onToggle(managing)}>
                {managing.status === 'active' ? '停用' : '启用'}
              </Button>
              <Popconfirm
                title="删除该端点？"
                description={
                  isChatKind(managing.kind)
                    ? '被知识库等资源引用时会被拒绝并列出引用方。'
                    : '被知识库引用时会被拒绝并列出引用方；索引维度不会被回写，引用它的库需重建索引。'
                }
                okText="删除"
                okButtonProps={{ danger: true }}
                onConfirm={() => onDelete(managing)}
              >
                <Button danger>删除</Button>
              </Popconfirm>
            </Space>
          </>
        )}
      </Drawer>
    </Card>
  )
}
