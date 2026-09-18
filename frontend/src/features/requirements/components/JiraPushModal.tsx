// CAP-47：自建（source=LOCAL）需求「推送到 Jira」参数弹窗。
// 数据源一次给齐（push-targets，服务端不抛错，选项失败降级为空表 + optionsError，弹窗一定打得开）；
// 切实例时除标题/描述外全部重置、切项目时只重置任务类型——任务类型 id / 优先级词表 / 经办人 username
// 都是实例内的值，跨实例沿用会静默推到错误的对象上。
// 经办人搜索三段式降级（服务端 query → GDPR 退 username → 前端退纯文本输入），任何一段失败都不阻断提交。
// 推送失败保持弹窗打开、表单不清空（Jira 必填自定义字段等 400 需用户改参数重试），错误原文常驻弹窗：
// Jira 一次会给 8~9 条字段级错误（「模块是必需的。」…），message toast 几秒即散且读不全，故落成弹窗内
// 常驻 Alert（改参数期间一直在），仅带堆栈的调试态仍走 showError 的 Modal。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  DatePicker,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Typography,
  message,
} from 'antd'
import dayjs from 'dayjs'
import { Link } from 'react-router-dom'
import {
  getJiraPushOptions,
  getJiraPushTargets,
  pushRequirementToJira,
  searchJiraAssignableUsers,
} from '../api'
import type {
  JiraAssignableUser,
  JiraPushInput,
  JiraPushOptions,
  JiraPushResult,
  JiraPushTargets,
  Requirement,
} from '../types'
import { isAdmin } from '../../auth/authStore'
import { isApiRequestError } from '../../../shared/api/error'
import { showError } from '../../../shared/utils/showError'

/** 表单值形态：dueDate 在表单里是 dayjs，提交时转 'YYYY-MM-DD' */
type FormValues = Omit<JiraPushInput, 'integrationId' | 'dueDate'> & {
  integrationId?: number
  dueDate?: dayjs.Dayjs | null
}

const PUSH_WARNING = '推送后该需求转为 Jira 托管：标题/描述/类型/优先级/标签/经办人/截止日期/修复版本'
  + '此后由 Jira 维护、本地不可编辑。此操作不可撤销。'

const EMPTY_OPTIONS: JiraPushOptions = { jiraProjects: [], issueTypes: [], priorities: [] }

export default function JiraPushModal({ requirement, open, onClose, onPushed }: {
  requirement: Requirement
  open: boolean
  onClose: () => void
  onPushed: (r: JiraPushResult) => void
}) {
  const [form] = Form.useForm<FormValues>()
  const [targets, setTargets] = useState<JiraPushTargets | null>(null)
  const [loading, setLoading] = useState(false)
  const [options, setOptions] = useState<JiraPushOptions>(EMPTY_OPTIONS)
  const [optionsLoading, setOptionsLoading] = useState(false)
  // 经办人搜索不可用（无权限/老实例未开搜索）时退化为纯文本输入
  const [assigneeDegraded, setAssigneeDegraded] = useState(false)
  const [users, setUsers] = useState<JiraAssignableUser[]>([])
  const [searching, setSearching] = useState(false)
  const [busy, setBusy] = useState(false)
  // 推送失败原文：常驻弹窗内（toast 会散、读不全 Jira 的逐字段错误）
  const [pushError, setPushError] = useState<string | null>(null)
  const searchTimer = useRef<number | undefined>(undefined)
  // 已选 Jira 项目 key（响应式读表单值：任务类型是否为空要结合「有没有选项目」判断）
  const jiraProjectKey = Form.useWatch('jiraProjectKey', form)
  const pid = requirement.projectId
  const rid = requirement.id
  // 回链只拼 URL（origin 只有浏览器知道），文案格式由服务端统一拼
  const backlinkUrl = `${window.location.origin}/projects/${pid}/requirements/${rid}`

  useEffect(() => {
    if (!open) return
    setLoading(true)
    setTargets(null)
    setOptions(EMPTY_OPTIONS)
    setAssigneeDegraded(false)
    setUsers([])
    setPushError(null)
    getJiraPushTargets(pid, rid)
      .then((t) => {
        setTargets(t)
        setOptions({ jiraProjects: t.jiraProjects, issueTypes: t.issueTypes, priorities: t.priorities })
      })
      .catch((e) => showError(e, '加载推送目标失败'))
      .finally(() => setLoading(false))
  }, [open, pid, rid])

  // 默认值以「targets 到达」为触发点填表（不是 fetch 回调里）：弹窗 destroyOnHidden，
  // 表单随弹窗重建，等 Form 挂载完再写值才不会丢（也能避开 antd 未连接告警）
  useEffect(() => {
    if (!open || !targets || targets.instances.length === 0) return
    form.setFieldsValue({
      integrationId: targets.defaultIntegrationId ?? targets.instances[0]?.id,
      jiraProjectKey: targets.defaultJiraProjectKey,
      issueTypeId: undefined,
      summary: targets.defaults.title || requirement.title,
      description: targets.defaults.description ?? '',
      priorityName: targets.defaults.priority,
      labels: targets.defaults.labels ?? [],
      dueDate: targets.defaults.dueDate ? dayjs(targets.defaults.dueDate) : null,
    })
  }, [open, targets, form, requirement.title])

  /** 重拉选项（切实例/切项目）：失败即抛出原文提示，并把词表清空避免沿用旧实例的值 */
  const loadOptions = useCallback(async (integrationId: number, jiraProjectKey?: string) => {
    setOptionsLoading(true)
    try {
      setOptions(await getJiraPushOptions(pid, rid, integrationId, jiraProjectKey))
    } catch (e) {
      showError(e, '拉取 Jira 选项失败')
      setOptions(EMPTY_OPTIONS)
    } finally {
      setOptionsLoading(false)
    }
  }, [pid, rid])

  const onInstanceChange = (integrationId: number) => {
    // 除标题/描述外全部重置：项目 key 与任务类型 id 属于实例内项目、优先级词表与经办人 username 也随实例变
    form.setFieldsValue({
      jiraProjectKey: undefined,
      issueTypeId: undefined,
      priorityName: undefined,
      assigneeName: undefined,
      labels: [],
      dueDate: null,
    })
    setAssigneeDegraded(false)
    setUsers([])
    loadOptions(integrationId)
  }

  const onProjectChange = (jiraProjectKey: string) => {
    form.setFieldValue('issueTypeId', undefined)
    const integrationId = form.getFieldValue('integrationId')
    if (integrationId != null) loadOptions(integrationId, jiraProjectKey)
  }

  /** 经办人搜索（防抖 300ms）；失败只标记降级，不弹错也不阻断提交 */
  const searchUsers = (q: string) => {
    const integrationId = form.getFieldValue('integrationId')
    if (integrationId == null) return
    const jiraProjectKey = form.getFieldValue('jiraProjectKey')
    window.clearTimeout(searchTimer.current)
    searchTimer.current = window.setTimeout(() => {
      setSearching(true)
      searchJiraAssignableUsers(pid, rid, integrationId, jiraProjectKey, q)
        .then((list) => {
          setUsers(list)
          setAssigneeDegraded(false)
        })
        .catch(() => setAssigneeDegraded(true))
        .finally(() => setSearching(false))
    }, 300)
  }

  useEffect(() => () => window.clearTimeout(searchTimer.current), [])

  const submit = async (v: FormValues) => {
    if (v.integrationId == null) return
    const input: JiraPushInput = {
      integrationId: v.integrationId,
      jiraProjectKey: v.jiraProjectKey,
      issueTypeId: v.issueTypeId,
      summary: v.summary.trim(),
      description: v.description?.trim() || undefined,
      backlinkUrl,
      priorityName: v.priorityName || undefined,
      assigneeName: v.assigneeName?.trim() || undefined,
      labels: (v.labels ?? []).map((l) => l.trim()).filter(Boolean),
      dueDate: v.dueDate ? v.dueDate.format('YYYY-MM-DD') : undefined,
    }
    setBusy(true)
    setPushError(null)
    try {
      const r = await pushRequirementToJira(pid, rid, input)
      message.success(`已创建 Jira issue ${r.externalKey}${r.remoteStatus ? `（${r.remoteStatus}）` : ''}`)
      onClose()
      onPushed(r)
    } catch (e) {
      // 弹窗保持打开、表单不清空，用户可改参数重试；Jira 的逐字段错误落常驻 Alert
      if (isApiRequestError(e) && e.body?.stackTrace) {
        showError(e, '推送失败') // 本地排错模式：完整堆栈走 Modal
      } else {
        setPushError(e instanceof Error ? e.message : String(e))
      }
    } finally {
      setBusy(false)
    }
  }

  const noInstance = !!targets && targets.instances.length === 0
  const noIdentity = targets?.identitySource === 'NONE'
  const blocked = !targets || noInstance || noIdentity

  return (
    <Modal
      centered
      width={680}
      open={open}
      title={`推送到 Jira · ${requirement.code}`}
      okText="推送并关联"
      cancelText="返回"
      confirmLoading={busy}
      okButtonProps={{ disabled: blocked }}
      onOk={() => form.submit()}
      onCancel={onClose}
      destroyOnHidden
    >
      {loading && <Spin style={{ display: 'block', marginBottom: 12 }} />}
      {!loading && noInstance ? (
        <Empty description="没有可用的 Jira 实例">
          <Typography.Text type="secondary">
            {isAdmin() ? (
              <>请先到 <Link to="/admin/integrations">平台集成</Link> 新建「JIRA」类型实例并启用。</>
            ) : (
              <>请联系管理员在「后台管理 → 平台集成」新建「JIRA」类型实例并启用。</>
            )}
          </Typography.Text>
        </Empty>
      ) : (
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          {pushError && (
            <Alert
              type="error"
              showIcon
              message="推送失败"
              description={(
                <>
                  <div style={{ whiteSpace: 'pre-wrap' }}>{pushError}</div>
                  {/* 只读必填字段这一种（本弹窗不推自定义字段）：给出可操作的两条出路，别让用户只看到报错 */}
                  {/(必需的|required)/i.test(pushError) && (
                    <div style={{ marginTop: 4 }}>
                      若提示某字段「是必需的」，说明该任务类型在 Jira 项目里配了必填字段（本弹窗不推自定义字段）：
                      可换一个任务类型，或先在 Jira 侧把该字段配成默认值。
                    </div>
                  )}
                </>
              )}
            />
          )}
          {targets && (
            <>
              {noIdentity ? (
                <Alert
                  type="error"
                  showIcon
                  message="该实例没有可用凭据，无法创建 issue"
                  description="实例既未配置机器人凭证，当前账号也没绑定该实例的个人账号。请到「我的 → 第三方账号」绑定后重试。"
                />
              ) : (
                <Alert
                  type="info"
                  showIcon
                  message={targets.identitySource === 'PERSONAL'
                    ? '将以你的个人账号创建 issue'
                    : '将以实例机器人凭证创建 issue'}
                  description={targets.syncCovered
                    ? undefined
                    : '该项目在此实例上没有启用的 Jira 同步配置，托管字段不会自动刷新，推送后可用「从 Jira 刷新」手动拉取。'}
                />
              )}
              <Alert type="warning" showIcon message={PUSH_WARNING} />
            </>
          )}

          <Form form={form} layout="vertical" onFinish={submit} preserve={false}>
            <Form.Item label="Jira 实例" name="integrationId"
              rules={[{ required: true, message: '请选择实例' }]}>
              <Select
                placeholder="选择 Jira 实例"
                onChange={onInstanceChange}
                options={(targets?.instances ?? []).map((i) => ({
                  value: i.id,
                  label: i.name,
                  title: i.baseUrl,
                }))}
              />
            </Form.Item>

            <Form.Item label="Jira 项目" name="jiraProjectKey"
              rules={[{ required: true, message: '请选择 Jira 项目' }]}
              tooltip="决定 issue key 前缀（如 PROJ-123）；无同步配置时也可自由选择">
              <Select
                showSearch
                loading={optionsLoading}
                placeholder="选择 Jira 项目"
                optionFilterProp="label"
                onChange={onProjectChange}
                options={options.jiraProjects.map((p) => ({ value: p.id, label: p.name }))}
              />
            </Form.Item>

            <Form.Item label="任务类型" name="issueTypeId"
              rules={[{ required: true, message: '请选择任务类型' }]}
              tooltip="按当前账号在目标项目下可创建的类型动态拉取（子任务已过滤）">
              <Select
                loading={optionsLoading}
                placeholder="选择任务类型"
                optionFilterProp="label"
                options={options.issueTypes.map((t) => ({ value: t.id, label: t.name }))}
              />
            </Form.Item>

            {options.issueTypes.length === 0 && (targets?.optionsError || jiraProjectKey) && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 16 }}
                message={targets?.optionsError
                  ? `选项拉取失败：${targets.optionsError}`
                  : '该项目下当前账号没有可创建的任务类型'}
                description={targets?.optionsError
                  ? '可更换实例或项目重试；任务类型为空时无法推送。'
                  : '通常是当前账号在该 Jira 项目上没有创建权限，或项目未配置可用类型。'}
              />
            )}

            <Form.Item label="标题" name="summary"
              rules={[
                { required: true, message: '请输入标题' },
                { max: 255, message: '标题超长（≤255 字符）' },
              ]}>
              <Input placeholder="Jira issue 标题" />
            </Form.Item>

            <Form.Item label="描述" name="description"
              tooltip="按纯文本推送；若含 !name.png! 这类内容，Jira 侧会按 wiki 图片语法解析">
              <Input.TextArea rows={4} placeholder="可选；留空则只推送下方回链" />
            </Form.Item>

            <Form.Item label="优先级" name="priorityName"
              tooltip="按实例词表校验；词表不可用时服务端跳过校验">
              <Select
                allowClear
                loading={optionsLoading}
                placeholder="可选"
                optionFilterProp="label"
                options={options.priorities.map((p) => ({ value: p.name, label: p.name }))}
              />
            </Form.Item>

            <Form.Item label="标签" name="labels"
              rules={[{
                validator: (_, v?: string[]) => {
                  const bad = (v ?? []).map((x) => x.trim()).find((x) => /[\s,]/.test(x))
                  return bad ? Promise.reject(new Error(`标签「${bad}」不能含空格或逗号`)) : Promise.resolve()
                },
              }]}
              tooltip="回车添加；不打回 Jira 侧已有标签">
              {/* 不设 tokenSeparators：标签按整串存储（后端逗号拼接），防止逗号被拆 */}
              <Select mode="tags" open={false} placeholder="回车添加" />
            </Form.Item>

            <Form.Item label="经办人" name="assigneeName"
              tooltip="搜索不可用时可直接输入 Jira 用户名">
              {assigneeDegraded ? (
                <Input placeholder="请输入 Jira 用户名（该实例不支持搜索）" />
              ) : (
                <Select
                  showSearch
                  allowClear
                  filterOption={false}
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

            <Form.Item label="截止日期" name="dueDate">
              <DatePicker style={{ width: '100%' }} placeholder="可选" />
            </Form.Item>

            <div style={{ color: '#888', fontSize: 12, wordBreak: 'break-all' }}>
              描述尾部将自动追加平台回链：<Typography.Text code>{requirement.code} · {backlinkUrl}</Typography.Text>
            </div>
          </Form>
        </Space>
      )}
    </Modal>
  )
}
