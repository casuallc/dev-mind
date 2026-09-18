// CAP-47 FR-10 个人设置「Jira 推送模板」子 tab：一行 = 我 + 实例 + Jira 项目 + 任务类型。
// 推送需求到 Jira 时，弹窗选定同组合即自动带入该行的 优先级/经办人/标签/动态字段默认值。
//
// 只存**取值本身**（任务类型 id、优先级 name、动态字段的平台取值），不存字段清单与候选值——
// 那些随 Jira 配置变化，一律由 createmeta / options 实时拉取，缓存下来只会过期骗人。
//
// 编辑表单级联失效边界（与推送弹窗同）：切实例清空其后全部字段（任务类型 id/优先级词表/
// 经办人/动态字段取值都是实例内的值，跨实例沿用会静默配错）；切项目只清任务类型与其后的动态字段。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Flex,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import {
  deleteJiraPushTemplate,
  getJiraPushTemplateOptions,
  getJiraTemplateCreateFields,
  listIntegrations,
  listJiraPushTemplates,
  saveJiraPushTemplate,
  searchJiraTemplateAssignableUsers,
} from '../api'
import type {
  Integration,
  JiraAssignableUser,
  JiraCreateFields,
  JiraPushOptions,
  JiraPushTemplate,
  JiraPushTemplateInput,
} from '../types'
import JiraDynamicField, { fromJiraValue, toJiraValue } from './JiraDynamicField'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const EMPTY_OPTIONS: JiraPushOptions = { jiraProjects: [], issueTypes: [], priorities: [] }

/** 表单会填的固定字段（id 对齐 Jira createmeta）——该创建界面上没有的要隐藏，否则存了也推不出去 */
const FIXED_FIELD_LABEL: Record<string, string> = {
  priority: '优先级',
  assignee: '经办人',
  labels: '标签',
}

/** 表单值形态：extraFields 按字段 id 分组（与推送弹窗同构） */
type FormValues = Omit<JiraPushTemplateInput, 'extraFields'> & {
  extraFields?: Record<string, unknown>
}

export default function JiraPushTemplatesPanel() {
  const [templates, setTemplates] = useState<JiraPushTemplate[]>([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [deletingId, setDeletingId] = useState<number | null>(null)

  const reload = useCallback(() => {
    setLoading(true)
    listJiraPushTemplates()
      .then(setTemplates)
      .catch((e) => showError(e, '加载模板失败'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(reload, [reload])

  const onDelete = (row: JiraPushTemplate) => {
    Modal.confirm({
      title: `删除模板「${row.jiraProjectKey} · ${row.issueTypeId}」？`,
      content: '删除后推送该组合的需求时不再自动带入这些默认值',
      centered: true,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setDeletingId(row.id)
        try {
          await deleteJiraPushTemplate(row.id)
          message.success('已删除')
          reload()
        } catch (e) {
          showError(e, '删除失败')
        } finally {
          setDeletingId(null)
        }
      },
    })
  }

  return (
    <>
      <Flex justify="space-between" align="flex-start" gap={16}>
        <Typography.Paragraph type="secondary">
          按「Jira 项目 + 任务类型」配置你常用的推送默认值：推送需求到 Jira 时选定同一组合，
          弹窗自动带入这些值（可临时改）。只保存取值本身，可选值随 Jira 实时拉取。
        </Typography.Paragraph>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditOpen(true)}>
            新建模板
          </Button>
        </Space>
      </Flex>
      <Table<JiraPushTemplate>
        rowKey="id"
        loading={loading}
        dataSource={templates}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '还没有推送模板——点右上「新建模板」，为你常推的 Jira 项目 + 任务类型配一套默认值' }}
        columns={[
          { title: 'Jira 实例', dataIndex: 'integrationName', render: (n: string) => n ?? '—' },
          { title: 'Jira 项目', dataIndex: 'jiraProjectKey', width: 120 },
          { title: '任务类型', dataIndex: 'issueTypeId', width: 110 },
          {
            title: '优先级',
            dataIndex: 'priorityName',
            width: 90,
            render: (v: string | null) => v ?? '—',
          },
          {
            title: '经办人',
            dataIndex: 'assigneeName',
            width: 110,
            render: (v: string | null) => v ?? '—',
          },
          {
            title: '标签',
            dataIndex: 'labels',
            render: (labels: string[] | null) =>
              labels?.length ? labels.map((l) => <Tag key={l}>{l}</Tag>) : '—',
          },
          {
            title: '动态字段',
            width: 90,
            render: (_, row) => Object.keys(row.extraFields ?? {}).length || '—',
          },
          {
            title: '更新时间',
            dataIndex: 'updatedAt',
            width: 160,
            render: (t: string | null) => (t ? fmtTime(t) : '—'),
          },
          {
            title: '操作',
            width: 120,
            render: (_, row) => (
              <Space size={4}>
                <TemplateEditButton template={row} onSaved={reload} />
                <Button size="small" danger loading={deletingId === row.id} onClick={() => onDelete(row)}>
                  删除
                </Button>
              </Space>
            ),
          },
        ]}
      />
      {/* 新建：组合键由表单填；编辑：组合键即主键，不可改（改了等于另存一行，用「新建」表达更直白） */}
      <TemplateEditModal open={editOpen} onClose={() => setEditOpen(false)} onSaved={reload} />
    </>
  )
}

/** 行内编辑入口：自带 Modal（每行一个，避免把「正在编辑谁」提到表格外维护） */
function TemplateEditButton({ template, onSaved }: { template: JiraPushTemplate; onSaved: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button size="small" onClick={() => setOpen(true)}>
        编辑
      </Button>
      <TemplateEditModal
        open={open}
        template={template}
        onClose={() => setOpen(false)}
        onSaved={onSaved}
      />
    </>
  )
}

function TemplateEditModal({ open, template, onClose, onSaved }: {
  open: boolean
  /** 为空即新建 */
  template?: JiraPushTemplate
  onClose: () => void
  onSaved: () => void
}) {
  const [form] = Form.useForm<FormValues>()
  const [instances, setInstances] = useState<Integration[]>([])
  const [saving, setSaving] = useState(false)
  const [options, setOptions] = useState<JiraPushOptions>(EMPTY_OPTIONS)
  const [optionsLoading, setOptionsLoading] = useState(false)
  const [createFields, setCreateFields] = useState<JiraCreateFields | null>(null)
  const [createFieldsLoading, setCreateFieldsLoading] = useState(false)
  // 经办人搜索不可用（无权限/老实例未开搜索）时退化为纯文本输入
  const [assigneeDegraded, setAssigneeDegraded] = useState(false)
  const [users, setUsers] = useState<JiraAssignableUser[]>([])
  const [searching, setSearching] = useState(false)
  const searchTimer = useRef<number | undefined>(undefined)
  // 编辑存量模板时的动态字段取值（Jira API 形态），等 createmeta 回来按 control 逆转换后消费
  const savedExtra = useRef<Record<string, unknown> | null>(null)

  const integrationId = Form.useWatch('integrationId', form)
  const jiraProjectKey = Form.useWatch('jiraProjectKey', form)
  const issueTypeId = Form.useWatch('issueTypeId', form)
  const editing = template != null

  /** 重拉选项（切实例/切项目）：失败即清空词表——沿用旧实例的候选值会配错 */
  const loadOptions = useCallback(async (id: number, projectKey?: string) => {
    setOptionsLoading(true)
    try {
      setOptions(await getJiraPushTemplateOptions(id, projectKey))
    } catch (e) {
      showError(e, '拉取 Jira 选项失败')
      setOptions(EMPTY_OPTIONS)
    } finally {
      setOptionsLoading(false)
    }
  }, [])

  // 打开弹窗：实例清单 + 编辑时回填表单。实例拉取失败不阻断——存量值照常回显，只是选项选不了
  useEffect(() => {
    if (!open) return
    listIntegrations()
      .then((list) => setInstances(list.filter((i) => i.type === 'JIRA' && i.status === 'ENABLED')))
      .catch(() => setInstances([]))
    setOptions(EMPTY_OPTIONS)
    setCreateFields(null)
    setAssigneeDegraded(false)
    setUsers([])
    savedExtra.current = template?.extraFields ?? null
    form.setFieldsValue(template
      ? {
          integrationId: template.integrationId,
          jiraProjectKey: template.jiraProjectKey,
          issueTypeId: template.issueTypeId,
          priorityName: template.priorityName ?? undefined,
          assigneeName: template.assigneeName ?? undefined,
          labels: template.labels ?? [],
          extraFields: undefined,
        }
      : {
          integrationId: undefined,
          jiraProjectKey: undefined,
          issueTypeId: undefined,
          priorityName: undefined,
          assigneeName: undefined,
          labels: [],
          extraFields: undefined,
        })
  }, [open, template, form])

  // 实例/项目就绪后拉项目与类型候选（与已保存值无关，仅用于渲染 Select 的 label）
  useEffect(() => {
    if (!open || integrationId == null) {
      return
    }
    loadOptions(integrationId, jiraProjectKey)
    // jiraProjectKey 变化只影响 issueTypes，但重拉一次代价很小且能顺带自愈词表
  }, [open, integrationId, jiraProjectKey, loadOptions])

  // 实例 + 项目 + 类型三者齐了才问 Jira「这个类型有哪些动态字段」——据此渲染默认值输入项
  useEffect(() => {
    if (!open || integrationId == null || !jiraProjectKey || !issueTypeId) {
      setCreateFields(null)
      return
    }
    let cancelled = false
    setCreateFieldsLoading(true)
    getJiraTemplateCreateFields(integrationId, jiraProjectKey, issueTypeId)
      .then((r) => {
        if (cancelled) return
        setCreateFields(r)
        // 回填来源：编辑存量模板 → 逆转换存量取值；新建/换过类型 → 清空重填
        const saved = savedExtra.current
        savedExtra.current = null
        const next: Record<string, unknown> = {}
        if (saved) {
          for (const f of r.fields) {
            const v = fromJiraValue(f, saved[f.id])
            if (v !== undefined) next[f.id] = v
          }
        }
        form.setFieldValue('extraFields', Object.keys(next).length ? next : undefined)
      })
      .catch((e) => {
        if (cancelled) return
        setCreateFields(null)
        showError(e, '加载必填字段失败')
      })
      .finally(() => {
        if (!cancelled) setCreateFieldsLoading(false)
      })
    return () => { cancelled = true }
  }, [open, integrationId, jiraProjectKey, issueTypeId, form])

  const onInstanceChange = () => {
    // 除实例本身外全部清空：下面每个字段的取值都是「该实例内」的值
    form.setFieldsValue({
      jiraProjectKey: undefined,
      issueTypeId: undefined,
      priorityName: undefined,
      assigneeName: undefined,
      labels: [],
      extraFields: undefined,
    })
    savedExtra.current = null
    setAssigneeDegraded(false)
    setUsers([])
    setCreateFields(null)
    // 不必手动 loadOptions：选项效应盯着 integrationId，变了自会重拉
  }

  const onProjectChange = () => {
    form.setFieldValue('issueTypeId', undefined)
    savedExtra.current = null
    setCreateFields(null)
  }

  const onIssueTypeChange = () => {
    // 类型换了字段集就换了，旧取值留着只会错位；新值由上面的 create-fields 效应清空后重填
    savedExtra.current = null
  }

  /** 经办人搜索（防抖 300ms）；失败只标记降级，不弹错 */
  const searchUsers = (q: string) => {
    if (integrationId == null) return
    window.clearTimeout(searchTimer.current)
    searchTimer.current = window.setTimeout(() => {
      setSearching(true)
      searchJiraTemplateAssignableUsers(integrationId, jiraProjectKey, q)
        .then((list) => {
          setUsers(list)
          setAssigneeDegraded(false)
        })
        .catch(() => setAssigneeDegraded(true))
        .finally(() => setSearching(false))
    }, 300)
  }

  useEffect(() => () => window.clearTimeout(searchTimer.current), [])

  const onSave = async (v: FormValues) => {
    if (v.integrationId == null || !v.jiraProjectKey || !v.issueTypeId) return
    // 动态字段按 control 组装成 Jira API 形态（与推送 payload 同）——推送时直接复用
    const extraFields: Record<string, unknown> = {}
    for (const f of createFields?.fields ?? []) {
      const value = toJiraValue(f, v.extraFields?.[f.id])
      if (value !== undefined) extraFields[f.id] = value
    }
    const input: JiraPushTemplateInput = {
      integrationId: v.integrationId,
      jiraProjectKey: v.jiraProjectKey,
      issueTypeId: v.issueTypeId,
      // 创建界面上没有的字段不存：存下来也只会让推送时多一个被 Jira 拒的字段
      priorityName: shows('priority') ? v.priorityName || undefined : undefined,
      assigneeName: shows('assignee') ? v.assigneeName?.trim() || undefined : undefined,
      labels: shows('labels') ? (v.labels ?? []).map((l) => l.trim()).filter(Boolean) : [],
      extraFields: Object.keys(extraFields).length ? extraFields : undefined,
    }
    setSaving(true)
    try {
      await saveJiraPushTemplate(input)
      message.success(editing ? '模板已更新' : '模板已创建，推送该组合的需求时会自动带入这些值')
      onClose()
      onSaved()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  // 元数据没拿到（error 或空 availableFields）时视作「未知」→ 固定字段一律照常显示
  const available = createFields && !createFields.error && createFields.availableFields.length
    ? new Set(createFields.availableFields)
    : null
  /** 该固定字段能不能写：Jira 的创建界面没有它（如某项目的「标签」），填了也会被拒 */
  const shows = (fieldId: string) => !available || available.has(fieldId)
  const hiddenFixed = Object.keys(FIXED_FIELD_LABEL).filter((id) => !shows(id))

  return (
    <Modal
      centered
      width={640}
      open={open}
      title={editing ? `编辑模板：${template.jiraProjectKey} · ${template.issueTypeId}` : '新建推送模板'}
      okText="保存"
      cancelText="取消"
      confirmLoading={saving}
      onOk={() => form.submit()}
      onCancel={onClose}
      destroyOnHidden
    >
      {instances.length === 0 ? (
        <Alert
          type="info"
          showIcon
          message="没有可用的 Jira 实例"
          description={<>请联系管理员在「后台管理 → <Link to="/admin/integrations">平台集成</Link>」新建「JIRA」类型实例并启用。</>}
        />
      ) : (
        <Form form={form} layout="vertical" onFinish={onSave} preserve={false}>
          <Form.Item label="Jira 实例" name="integrationId"
            rules={[{ required: true, message: '请选择实例' }]}>
            <Select
              placeholder="选择 Jira 实例"
              disabled={editing}
              onChange={onInstanceChange}
              options={instances.map((i) => ({ value: i.id, label: i.name, title: i.baseUrl }))}
            />
          </Form.Item>

          <Form.Item label="Jira 项目" name="jiraProjectKey"
            rules={[{ required: true, message: '请选择 Jira 项目' }]}
            tooltip="决定 issue key 前缀（如 PROJ-123）；模板按「项目 + 任务类型」命中">
            <Select
              showSearch
              loading={optionsLoading}
              placeholder="选择 Jira 项目"
              optionFilterProp="label"
              disabled={editing}
              onChange={onProjectChange}
              options={options.jiraProjects.map((p) => ({ value: p.id, label: p.name }))}
            />
          </Form.Item>

          <Form.Item label="任务类型" name="issueTypeId"
            rules={[{ required: true, message: '请选择任务类型' }]}
            tooltip="选定后自动带出该类型的动态字段，可在此为它们配置默认值">
            <Select
              loading={optionsLoading}
              placeholder="选择任务类型"
              optionFilterProp="label"
              disabled={editing}
              onChange={onIssueTypeChange}
              options={options.issueTypes.map((t) => ({ value: t.id, label: t.name }))}
            />
          </Form.Item>

          {options.issueTypes.length === 0 && jiraProjectKey && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="该项目下当前账号没有可创建的任务类型"
              description="通常是当前账号在该 Jira 项目上没有创建权限，或项目未配置可用类型。"
            />
          )}

          {shows('priority') && (
            <Form.Item label="优先级" name="priorityName"
              tooltip="按实例词表校验；留空则推送时用需求自身的优先级（命中实例词表才带）">
              <Select
                allowClear
                loading={optionsLoading}
                placeholder="可选"
                optionFilterProp="label"
                options={options.priorities.map((p) => ({ value: p.name, label: p.name }))}
              />
            </Form.Item>
          )}

          {shows('assignee') && (
            <Form.Item label="经办人" name="assigneeName"
              tooltip="Jira 的 assignee.name 要**登录名**（不是显示姓名）；搜索不可用时可直接输入用户名">
              {assigneeDegraded ? (
                <Input placeholder="请输入 Jira 用户名（该实例不支持搜索）" />
              ) : (
                <Select
                  showSearch
                  allowClear
                  // 本地过滤而非依赖 Jira 的关键字参数：实测该实例对 query 参数直接无视
                  // （搜一个不存在的人仍返回全部 102 个），只听服务端的搜索框等于没搜。
                  optionFilterProp="label"
                  loading={searching}
                  placeholder="输入用户名/姓名搜索（留空取默认列表）"
                  onSearch={searchUsers}
                  onOpenChange={(o) => { if (o) searchUsers('') }}
                  notFoundContent={searching ? <Spin size="small" /> : null}
                  options={users.map((u) => ({
                    value: u.name,
                    label: u.displayName && u.displayName !== u.name
                      ? `${u.displayName}（${u.name}）`
                      : u.name,
                  }))}
                />
              )}
            </Form.Item>
          )}

          {shows('labels') && (
            <Form.Item label="标签" name="labels"
              rules={[{
                validator: (_, v?: string[]) => {
                  const bad = (v ?? []).map((x) => x.trim()).find((x) => /[\s,]/.test(x))
                  return bad ? Promise.reject(new Error(`标签「${bad}」不能含空格或逗号`)) : Promise.resolve()
                },
              }]}
              tooltip="留空则推送时用需求自身的标签">
              <Select mode="tags" open={false} placeholder="回车添加" />
            </Form.Item>
          )}

          {hiddenFixed.length > 0 && (
            <div style={{ color: '#888', fontSize: 12, marginBottom: 16 }}>
              Jira 的该任务类型创建界面上没有
              {hiddenFixed.map((id) => `「${FIXED_FIELD_LABEL[id]}」`).join('')}
              ，已隐藏——存了也会在推送时被 Jira 拒绝。
            </div>
          )}

          {createFieldsLoading && <Spin size="small" />}
          {createFields?.error && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="没能取到该任务类型的动态字段"
              description={`${createFields.error}——仍可保存，但推送时这些字段不会有默认值。`}
            />
          )}
          {(createFields?.fields.length ?? 0) > 0 && (
            <>
              <div style={{ color: '#888', fontSize: 12, marginBottom: 8 }}>
                以下字段是该任务类型在 Jira 侧要求的，可在此配置默认值（留空则不预填）：
              </div>
              {createFields?.fields.map((f) => (
                <JiraDynamicField key={f.id} field={f} required={false} />
              ))}
            </>
          )}
          {(createFields?.unsupported.length ?? 0) > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={`该任务类型还要求 ${createFields?.unsupported.map((f) => f.name).join('、')}`}
              description="这些字段的取值方式平台暂不支持填写，无法在此配置默认值；推送时会提示改选任务类型。"
            />
          )}
        </Form>
      )}
    </Modal>
  )
}
