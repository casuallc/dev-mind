// CAP-47 FR-10 项目「Jira 推送」默认值配置卡（后台项目设置 Tab）：
// 每个项目一套模板，打开需求推送弹窗时自动带入，省去每次重填模块/缺陷类型这类固定字段。
//
// 只存**取值本身**（任务类型 id、优先级 name、动态字段的平台取值），不存字段清单与候选值——
// 那些随 Jira 配置变化，一律由 createmeta / options 实时拉取，缓存下来只会过期骗人。
//
// 级联失效有明确边界：切实例清空其后全部字段（任务类型 id/优先级词表/经办人/动态字段取值都是
// 实例内的值，跨实例沿用会静默配错）；切项目只清任务类型与其后的动态字段（优先级词表随实例走）。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Form,
  Input,
  Select,
  Space,
  Spin,
  Typography,
  message,
} from 'antd'
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import {
  getJiraCreateFieldsForProject,
  getJiraPushDefaults,
  getJiraPushOptionsForProject,
  listIntegrations,
  saveJiraPushDefaults,
  searchJiraAssignableUsersForProject,
} from '../api'
import type {
  Integration,
  JiraAssignableUser,
  JiraCreateFields,
  JiraPushDefaultsInput,
  JiraPushOptions,
} from '../types'
import JiraDynamicField, { fromJiraValue, toJiraValue } from './JiraDynamicField'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

interface Props {
  projectId: string
}

const EMPTY_OPTIONS: JiraPushOptions = { jiraProjects: [], issueTypes: [], priorities: [] }

/** 本页会填的固定字段（id 对齐 Jira createmeta）——该创建界面上没有的要隐藏，否则存了也推不出去 */
const FIXED_FIELD_LABEL: Record<string, string> = {
  priority: '优先级',
  assignee: '经办人',
  labels: '标签',
}

export default function JiraPushDefaultsTab({ projectId }: Props) {
  const [form] = Form.useForm<FormValues>()
  const [instances, setInstances] = useState<Integration[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [updatedAt, setUpdatedAt] = useState<string | null>(null)
  const [options, setOptions] = useState<JiraPushOptions>(EMPTY_OPTIONS)
  const [optionsLoading, setOptionsLoading] = useState(false)
  const [createFields, setCreateFields] = useState<JiraCreateFields | null>(null)
  const [createFieldsLoading, setCreateFieldsLoading] = useState(false)
  // 经办人搜索不可用（无权限/老实例未开搜索）时退化为纯文本输入
  const [assigneeDegraded, setAssigneeDegraded] = useState(false)
  const [users, setUsers] = useState<JiraAssignableUser[]>([])
  const [searching, setSearching] = useState(false)
  const searchTimer = useRef<number | undefined>(undefined)
  // 已保存的默认值里的动态字段取值（Jira API 形态），等 createmeta 回来按 control 逆转换后消费
  const savedExtra = useRef<Record<string, unknown> | null>(null)

  const integrationId = Form.useWatch('integrationId', form)
  const jiraProjectKey = Form.useWatch('jiraProjectKey', form)
  const issueTypeId = Form.useWatch('issueTypeId', form)

  /** 重拉选项（切实例/切项目）：失败即清空词表——沿用旧实例的候选值会配错 */
  const loadOptions = useCallback(async (id: number, projectKey?: string) => {
    setOptionsLoading(true)
    try {
      setOptions(await getJiraPushOptionsForProject(projectId, id, projectKey))
    } catch (e) {
      showError(e, '拉取 Jira 选项失败')
      setOptions(EMPTY_OPTIONS)
    } finally {
      setOptionsLoading(false)
    }
  }, [projectId])

  // 载入：实例清单 + 已保存的默认值。实例拉取失败不阻断——默认值照常回显，只是选项选不了
  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const [list, d] = await Promise.all([
        listIntegrations().catch(() => [] as Integration[]),
        getJiraPushDefaults(projectId).catch(() => null),
      ])
      setInstances(list.filter((i) => i.type === 'JIRA' && i.status === 'ENABLED'))
      savedExtra.current = d?.extraFields ?? null
      setUpdatedAt(d?.updatedAt ?? null)
      // 整表重置：重新加载要回到「服务端存的那一份」，而不是把当前编辑内容留在屏上
      form.resetFields()
      if (d) {
        form.setFieldsValue({
          integrationId: d.integrationId,
          jiraProjectKey: d.jiraProjectKey ?? undefined,
          issueTypeId: d.issueTypeId ?? undefined,
          priorityName: d.priorityName ?? undefined,
          assigneeName: d.assigneeName ?? undefined,
          labels: d.labels ?? [],
        })
      }
    } finally {
      setLoading(false)
    }
  }, [projectId, form])

  useEffect(() => {
    reload()
  }, [reload])

  // 实例/项目就绪后拉项目与类型候选（与已保存值无关，仅用于渲染 Select 的 label）
  useEffect(() => {
    if (integrationId == null) {
      setOptions(EMPTY_OPTIONS)
      return
    }
    loadOptions(integrationId, jiraProjectKey)
    // jiraProjectKey 变化只影响 issueTypes，但重拉一次代价很小且能顺带自愈词表
  }, [integrationId, jiraProjectKey, loadOptions])

  // 实例 + 项目 + 类型三者齐了才问 Jira「这个类型有哪些动态字段」——配置页据此渲染默认值输入项
  useEffect(() => {
    if (integrationId == null || !jiraProjectKey || !issueTypeId) {
      setCreateFields(null)
      return
    }
    let cancelled = false
    setCreateFieldsLoading(true)
    getJiraCreateFieldsForProject(projectId, integrationId, jiraProjectKey, issueTypeId)
      .then((r) => {
        if (cancelled) return
        setCreateFields(r)
        // 回填来源：首次（载入已保存配置）→ 逆转换存量取值；用户换过类型 → 清空重填
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
  }, [projectId, integrationId, jiraProjectKey, issueTypeId, form])

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
    // 不必手动 loadOptions：下面的选项效应盯着 integrationId，变了自会重拉
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
      searchJiraAssignableUsersForProject(projectId, integrationId, jiraProjectKey, q)
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
    if (v.integrationId == null) return
    // 动态字段按 control 组装成 Jira API 形态（与推送 payload 同）——推送时直接复用
    const extraFields: Record<string, unknown> = {}
    for (const f of createFields?.fields ?? []) {
      const value = toJiraValue(f, v.extraFields?.[f.id])
      if (value !== undefined) extraFields[f.id] = value
    }
    const input: JiraPushDefaultsInput = {
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
      const saved = await saveJiraPushDefaults(projectId, input)
      setUpdatedAt(saved.updatedAt ?? null)
      message.success('已保存，新建需求推送时会自动带入这些值')
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
  /** 该固定字段能不能写：Jira 的创建界面没有它（如某项目的「标签」），填/store 了也会被拒 */
  const shows = (fieldId: string) => !available || available.has(fieldId)
  const hiddenFixed = Object.keys(FIXED_FIELD_LABEL).filter((id) => !shows(id))

  if (loading) return <Spin />

  if (instances.length === 0) {
    return (
      <Alert
        type="info"
        showIcon
        message="没有可用的 Jira 实例"
        description={<>请先到 <Link to="/admin/integrations">平台集成</Link> 新建「JIRA」类型实例并启用。</>}
      />
    )
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        为项目配置一套 Jira 推送默认值，之后推送需求到 Jira 时自动带入，可在弹窗内临时修改。
        这里只保存取值本身，可选值随 Jira 实时拉取。
      </Typography.Paragraph>

      <Form form={form} layout="vertical" onFinish={onSave} style={{ maxWidth: 560 }}>
        <Form.Item label="Jira 实例" name="integrationId"
          rules={[{ required: true, message: '请选择实例' }]}>
          <Select
            placeholder="选择 Jira 实例"
            onChange={() => onInstanceChange()}
            options={instances.map((i) => ({ value: i.id, label: i.name, title: i.baseUrl }))}
          />
        </Form.Item>

        <Form.Item label="Jira 项目" name="jiraProjectKey"
          tooltip="决定 issue key 前缀（如 PROJ-123）。推送目标的唯一来源：下面这些默认值都按这个项目取值，
            故它优先于项目里的 Jira 同步配置（同步配置只管「从哪个项目拉 issue」）">
          <Select
            showSearch
            allowClear
            loading={optionsLoading}
            placeholder="选择 Jira 项目"
            optionFilterProp="label"
            onChange={onProjectChange}
            options={options.jiraProjects.map((p) => ({ value: p.id, label: p.name }))}
          />
        </Form.Item>

        <Form.Item label="任务类型" name="issueTypeId"
          tooltip="选定后自动带出该类型的动态字段，可在此为它们配置默认值">
          <Select
            allowClear
            loading={optionsLoading}
            placeholder="选择任务类型"
            optionFilterProp="label"
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
            tooltip="按实例词表校验；留空则用需求自身的优先级">
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
            tooltip="留空则用需求自身的标签">
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

        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>
              保存
            </Button>
            <Button icon={<ReloadOutlined />} onClick={reload}>
              重新加载
            </Button>
            {updatedAt && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                上次保存：{fmtTime(updatedAt)}
              </Typography.Text>
            )}
          </Space>
        </Form.Item>
      </Form>
    </Space>
  )
}

/** 表单值形态：extraFields 按字段 id 分组（与推送弹窗同构） */
type FormValues = Omit<JiraPushDefaultsInput, 'extraFields'> & {
  extraFields?: Record<string, unknown>
}
