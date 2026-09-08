// 场景管理（CAP-33 FR-06）：场景 = prompt 骨架 + 预装配上下文包（skills/docs/知识 tags/场景背景）。
// 成套操作收进「管理」Drawer；预览走 dryRun 装配（不 bumpHits）。
import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  createScenario,
  deleteScenario,
  listScenarios,
  previewScenario,
  updateScenario,
} from '../api'
import type { Scenario, ScenarioInput, ScenarioPreview } from '../types'
import { listSkills } from '../../skills/api'
import { listDocs } from '../../docs/api'
import { listProjects } from '../../projects/api'
import { listAgentNodes } from '../../agent/api'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardBodyScrollStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

const SOURCE_LABEL: Record<string, string> = {
  scenario: '场景绑定',
  'project-auto': '项目自动',
  request: '请求追加',
}
const KIND_LABEL: Record<string, string> = { knowledge: '知识', skill: 'Skill', doc: '文档' }

export default function ScenariosPage() {
  const [rows, setRows] = useState<Scenario[]>([])
  const [loading, setLoading] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editing, setEditing] = useState<Scenario | null>(null)
  const [saving, setSaving] = useState(false)
  const [preview, setPreview] = useState<ScenarioPreview | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  // 编辑器的资产备选项
  const [skillOptions, setSkillOptions] = useState<{ value: string; label: string }[]>([])
  const [docOptions, setDocOptions] = useState<{ value: number; label: string }[]>([])
  const [projectOptions, setProjectOptions] = useState<{ value: string; label: string }[]>([])
  const [nodeOptions, setNodeOptions] = useState<{ value: string; label: string }[]>([])
  const [form] = Form.useForm()
  const watchScope = Form.useWatch('scope', form)

  const load = async () => {
    setLoading(true)
    try {
      setRows(await listScenarios())
    } catch (e) {
      message.error(`加载场景失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const openEditor = (s: Scenario | null) => {
    setEditing(s)
    form.setFieldsValue(
      s ?? {
        code: '',
        name: '',
        scope: 'GLOBAL',
        skillIds: [],
        docIds: [],
        knowledgeTags: [],
        sortOrder: rows.length + 1,
        enabled: true,
      },
    )
    setDrawerOpen(true)
    // 资产备选项随抽屉加载（一次即可，失败不阻塞编辑）
    listSkills({ size: 200 })
      .then((p) =>
        setSkillOptions(p.items.map((sk) => ({
          value: sk.id,
          label: `${sk.name}（${sk.scope === 'GLOBAL' ? '全局' : '项目'}${sk.status !== 'ACTIVE' ? '·已停用' : ''}）`,
        }))),
      )
      .catch(() => undefined)
    listDocs({})
      .then((docs) => setDocOptions(docs.map((d) => ({ value: d.id, label: `#${d.id} ${d.title}` }))))
      .catch(() => undefined)
    listProjects()
      .then((ps) => setProjectOptions(ps.map((p) => ({ value: p.id, label: p.name }))))
      .catch(() => undefined)
    listAgentNodes()
      .then((ns) =>
        setNodeOptions(ns.map((n) => ({
          value: String(n.id),
          label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
        }))),
      )
      .catch(() => undefined)
  }

  const onSave = async () => {
    const values = (await form.validateFields()) as ScenarioInput & { docIds?: (number | string)[] }
    const input: ScenarioInput = {
      ...values,
      code: values.code?.trim(),
      docIds: (values.docIds ?? []).map(Number),
      // GLOBAL 场景不落 projectId（后端也会置空，前端先自清避免误读）
      projectId: values.scope === 'PROJECT' ? values.projectId : undefined,
    }
    setSaving(true)
    try {
      if (editing) {
        await updateScenario(editing.id, input)
        message.success('已更新')
      } else {
        await createScenario(input)
        message.success('已创建')
      }
      setDrawerOpen(false)
      load()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  const onDelete = () => {
    if (!editing) return
    Modal.confirm({
      centered: true,
      title: '删除该场景？',
      content: `Code: ${editing.code}（${editing.name}）。已创建会话的历史快照不受影响（重建时按无场景装配）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteScenario(editing.id)
          message.success('已删除')
          setDrawerOpen(false)
          load()
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }

  const onPreview = async () => {
    const values = form.getFieldsValue() as ScenarioInput
    const code = editing?.code ?? values.code
    if (!code) {
      message.warning('先填写 code 再预览')
      return
    }
    setPreviewLoading(true)
    try {
      // 未保存的编辑不生效——预览的是已落库场景的装配结果（dryRun）
      setPreview(
        await previewScenario(code, {
          projectId: values.scope === 'PROJECT' ? values.projectId : undefined,
          taskSpec: '示例任务：为登录页补充表单校验',
        }),
      )
    } catch (e) {
      message.error(`预览失败：${(e as Error).message}`)
    } finally {
      setPreviewLoading(false)
    }
  }

  const columns: ColumnsType<Scenario> = [
    { title: 'Code', dataIndex: 'code', width: 140, render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
    { title: '名称', dataIndex: 'name', width: 160 },
    {
      title: '范围',
      dataIndex: 'scope',
      width: 90,
      render: (v: string, r) =>
        v === 'PROJECT' ? <Tag color="blue">项目·{r.projectId}</Tag> : <Tag>全局</Tag>,
    },
    {
      title: '绑定资产',
      key: 'assets',
      width: 150,
      render: (_, r) => (
        <Space size={4} wrap>
          {r.skillIds.length > 0 && <Tag>{r.skillIds.length} skill</Tag>}
          {r.docIds.length > 0 && <Tag>{r.docIds.length} 文档</Tag>}
          {r.knowledgeTags.length > 0 && <Tag>{r.knowledgeTags.length} 知识标签</Tag>}
          {r.skillIds.length + r.docIds.length + r.knowledgeTags.length === 0 && '-'}
        </Space>
      ),
    },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 70,
      render: (v: boolean) => <Switch size="small" checked={v} disabled />,
    },
    { title: '排序', dataIndex: 'sortOrder', width: 60 },
    { title: '更新时间', dataIndex: 'updatedAt', width: 150, render: (t: string) => fmtTime(t) },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, r) => (
        <Button size="small" onClick={() => openEditor(r)}>
          管理
        </Button>
      ),
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title="场景"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor(null)}>
            新建场景
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        场景 = prompt 骨架 + 预装配上下文包（skills / 文档 / 知识标签 / 场景背景）。新建会话或问答时选择场景，
        骨架渲染为首条 prompt，绑定资产随上下文包注入沙箱。占位符：
        <Typography.Text code>{'{{task}}'}</Typography.Text>、
        <Typography.Text code>{'{{project}}'}</Typography.Text>、
        <Typography.Text code>{'{{branch}}'}</Typography.Text>、
        <Typography.Text code>{'{{requirement}}'}</Typography.Text>。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: '暂无场景。点击右上角「新建场景」创建第一个。' }}
      />

      <Drawer
        title={editing ? `场景：${editing.name}` : '新建场景'}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={560}
        footer={
          <Space style={{ display: 'flex', justifyContent: 'space-between' }}>
            <Space>
              {editing && (
                <Button danger onClick={onDelete}>
                  删除
                </Button>
              )}
              <Button loading={previewLoading} onClick={onPreview}>
                装配预览
              </Button>
            </Space>
            <Space>
              <Button onClick={() => setDrawerOpen(false)}>取消</Button>
              <Button type="primary" loading={saving} onClick={onSave}>
                保存
              </Button>
            </Space>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item label="Code" name="code" rules={[{ required: true, message: '请输入唯一 code' }]} style={{ flex: 1 }}>
              <Input placeholder="如 code-review" />
            </Form.Item>
            <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]} style={{ flex: 1 }}>
              <Input placeholder="如 代码评审" />
            </Form.Item>
          </Space>
          <Form.Item label="描述" name="description">
            <Input placeholder="（可选）一句话说明场景用途" />
          </Form.Item>
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item label="范围" name="scope" rules={[{ required: true }]} style={{ width: 140 }}>
              <Select
                options={[
                  { value: 'GLOBAL', label: '全局' },
                  { value: 'PROJECT', label: '项目' },
                ]}
              />
            </Form.Item>
            {watchScope === 'PROJECT' && (
              <Form.Item
                label="所属项目"
                name="projectId"
                rules={[{ required: true, message: 'PROJECT 场景必须指定项目' }]}
                style={{ flex: 1 }}
              >
                <Select options={projectOptions} placeholder="选择项目" showSearch optionFilterProp="label" />
              </Form.Item>
            )}
          </Space>
          <Form.Item
            label="Prompt 骨架"
            name="promptSkeleton"
            extra="会话/问答创建时以首条消息渲染 {{task}} 后作为初始 prompt"
          >
            <Input.TextArea rows={4} placeholder={'任务：{{task}}\n项目：{{project}}\n基线分支：{{branch}}'} />
          </Form.Item>
          <Form.Item
            label="场景背景"
            name="extraContextMd"
            extra="业务背景/口径约定（Markdown），进 CLAUDE.md「场景背景」节"
          >
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item label="绑定 Skills" name="skillIds" extra="物化为沙箱 .claude/skills/，Claude 自动发现">
            <Select mode="multiple" options={skillOptions} placeholder="（可选）" showSearch optionFilterProp="label" />
          </Form.Item>
          <Form.Item
            label="绑定文档"
            name="docIds"
            extra="摘要进 CLAUDE.md，全文物化为 .devmind/docs/<id>.md（需要时由 Read 查看）"
          >
            <Select mode="multiple" options={docOptions} placeholder="（可选）" showSearch optionFilterProp="label" />
          </Form.Item>
          <Form.Item label="绑定知识标签" name="knowledgeTags" extra="命中的知识条目进 CLAUDE.md（可输入新标签回车添加）">
            <Select mode="tags" placeholder="（可选）如 gitlab,review" open={false} />
          </Form.Item>
          <Space size="middle" style={{ display: 'flex' }} wrap>
            <Form.Item label="模型" name="model" style={{ width: 180 }}>
              <Input placeholder="留空 = 平台默认" allowClear />
            </Form.Item>
            <Form.Item label="权限模式" name="permissionMode" style={{ width: 200 }}>
              <Select
                allowClear
                placeholder="留空 = 平台默认"
                options={[
                  { value: 'acceptEdits', label: 'acceptEdits' },
                  { value: 'default', label: 'default' },
                  { value: 'bypassPermissions', label: 'bypassPermissions' },
                  { value: 'plan', label: 'plan' },
                ]}
              />
            </Form.Item>
            <Form.Item label="执行节点" name="agentNodeId" style={{ width: 200 }}>
              <Select allowClear options={nodeOptions} placeholder="留空 = 默认路由" />
            </Form.Item>
          </Space>
          <Space size="large">
            <Form.Item label="排序" name="sortOrder">
              <InputNumber min={0} style={{ width: 100 }} />
            </Form.Item>
            <Form.Item label="启用" name="enabled" valuePropName="checked" extra="停用后不出现在创建表单（旧 code 仍可解析）">
              <Switch />
            </Form.Item>
          </Space>
        </Form>
      </Drawer>

      <Modal
        title={`装配预览：${editing?.code ?? ''}`}
        open={preview != null}
        onCancel={() => setPreview(null)}
        footer={null}
        width={760}
      >
        {preview && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <div>
              <Typography.Text strong>渲染后 prompt</Typography.Text>
              <pre style={{ whiteSpace: 'pre-wrap', background: '#f6f6f6', padding: 8, borderRadius: 4 }}>
                {preview.renderedTaskSpec}
              </pre>
            </div>
            {!preview.hasContext ? (
              <Typography.Text type="secondary">装配为空——真实创建时按无上下文启动。</Typography.Text>
            ) : (
              <>
                <Table
                  rowKey={(r) => `${r.kind}:${r.ref}`}
                  size="small"
                  pagination={false}
                  dataSource={preview.items}
                  columns={[
                    { title: '类型', dataIndex: 'kind', width: 70, render: (k: string) => KIND_LABEL[k] ?? k },
                    { title: '名称', dataIndex: 'name', ellipsis: true },
                    { title: '范围', dataIndex: 'scope', width: 90, render: (s?: string) => s ?? '-' },
                    {
                      title: '来源',
                      dataIndex: 'source',
                      width: 90,
                      render: (s: string) => (
                        <Tag color={s === 'scenario' ? 'blue' : s === 'project-auto' ? 'green' : 'orange'}>
                          {SOURCE_LABEL[s] ?? s}
                        </Tag>
                      ),
                    },
                  ]}
                />
                <Typography.Text type="secondary">
                  上下文包：{preview.entries} 条目 / {preview.totalBytes} 字节
                </Typography.Text>
                <Typography.Text strong>CLAUDE.md（注入全文）</Typography.Text>
                <pre
                  style={{
                    whiteSpace: 'pre-wrap',
                    background: '#f6f6f6',
                    padding: 8,
                    borderRadius: 4,
                    maxHeight: 320,
                    overflow: 'auto',
                    fontSize: 12,
                  }}
                >
                  {preview.claudeMd}
                </pre>
              </>
            )}
          </Space>
        )}
      </Modal>
    </Card>
  )
}
