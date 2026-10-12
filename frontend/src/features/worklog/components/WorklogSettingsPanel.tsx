// 设置页「工作日志」视图（/settings/worklog）：工作日志自己的偏好——日报/周报自动生成、
// 定时从 Git 导入工作条目、每日工时目标、报表格式模板、远程仓库备份。
// 三个定时项均可配个人执行时间：留空 = 跟随全局规则（后端 globalXxxLabel 给出全局文案）；
// 周报需星期与时间同时设置才覆盖全局。
// 原为工作日志页 extra 的「设置」按钮 +「工时设置」Modal（与顶部一级导航「设置」重名），
// 现收口为设置页的第 4 个页内视图：表单进 body，操作按钮在页面 Card extra，故以 ref 暴露 reload/save。
import { Button, Form, Input, InputNumber, Modal, Select, Space, Spin, Switch, Tag, TimePicker, Typography, message } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useCallback, useEffect, useImperativeHandle, useState, type Ref } from 'react'
import {
  addRepoMember,
  getDefaultTemplates,
  getSettings,
  listMyRepos,
  listRepoMembers,
  removeRepoMember,
  updateSettings,
} from '../api'
import type { WorklogRepoInfo, WorklogRepoMember, WorklogSettings } from '../types'
import { showError } from '../../../shared/utils/showError'
import { getUserSnapshot } from '../../auth/authStore'

/** 页面壳（SettingsPage）工具栏在 Card extra，靠该句柄触发刷新 / 保存 */
export interface WorklogSettingsPanelHandle {
  reload: () => void
  /** 校验失败直接 resolve（antd 已高亮并滚到该字段），保存失败已在内部 toast */
  save: () => Promise<void>
}

/** 表单值形态：工时目标按小时编辑，提交时换算为分钟（与后端 dailyMinutesTarget 对齐）；
 *  三个执行时间为 TimePicker 的 Dayjs（null = 跟随全局），提交时格式化为 "HH:mm"。 */
interface FormValues {
  autoDaily: boolean
  autoWeekly: boolean
  autoGitImport: boolean
  dailyTime?: Dayjs | null
  weeklyDay?: number | null
  weeklyTime?: Dayjs | null
  gitImportTime?: Dayjs | null
  dailyHoursTarget?: number
  dailyTemplateMd?: string
  weeklyTemplateMd?: string
  remoteUrl?: string
  remoteBranch?: string
  /** CAP-74 M2：活动仓 repoKey；'' = 个人仓（提交空白串回个人仓） */
  activeRepoKey?: string
}

/** 后端 "HH:mm" → TimePicker 值（ISO 拼接解析，免 customParseFormat 插件） */
const parseTime = (t?: string | null): Dayjs | null => (t ? dayjs(`2000-01-01T${t}`) : null)

const WEEK_DAY_OPTIONS = [1, 2, 3, 4, 5, 6, 7].map((d) => ({
  value: d,
  label: `周${'一二三四五六日'[d - 1]}`,
}))

export default function WorklogSettingsPanel({ ref }: { ref?: Ref<WorklogSettingsPanelHandle> }) {
  const [form] = Form.useForm<FormValues>()
  const [loading, setLoading] = useState(false)
  // 全局兜底规则文案（"每天 18:30" 等），来自后端 globalXxxLabel
  const [globalLabels, setGlobalLabels] = useState({ daily: '…', weekly: '…', gitImport: '…' })
  // CAP-74 M2：我所属的仓（活动仓 Select 数据源）+ 我名下仓的成员列表（成员管理）
  const me = getUserSnapshot()?.username
  const [myRepos, setMyRepos] = useState<WorklogRepoInfo[]>([])
  const [membersByRepo, setMembersByRepo] = useState<Record<string, WorklogRepoMember[]>>({})
  const [memberInput, setMemberInput] = useState<Record<string, string>>({})
  const [memberBusy, setMemberBusy] = useState<Record<string, boolean>>({})

  const reload = useCallback(() => {
    setLoading(true)
    getSettings()
      .then((s: WorklogSettings) => {
        setGlobalLabels({
          daily: s.globalDailyLabel ?? '…',
          weekly: s.globalWeeklyLabel ?? '…',
          gitImport: s.globalGitImportLabel ?? '…',
        })
        form.setFieldsValue({
          autoDaily: s.autoDaily,
          autoWeekly: s.autoWeekly,
          autoGitImport: s.autoGitImport ?? false,
          dailyTime: parseTime(s.dailyTime),
          weeklyDay: s.weeklyDay ?? null,
          weeklyTime: parseTime(s.weeklyTime),
          gitImportTime: parseTime(s.gitImportTime),
          dailyHoursTarget: s.dailyMinutesTarget != null ? s.dailyMinutesTarget / 60 : undefined,
          dailyTemplateMd: s.dailyTemplateMd ?? '',
          weeklyTemplateMd: s.weeklyTemplateMd ?? '',
          remoteUrl: s.remoteUrl ?? '',
          remoteBranch: s.remoteBranch ?? '',
          activeRepoKey: s.activeRepoKey ?? '',
        })
      })
      .catch((e) => showError(e, '加载设置失败'))
      .finally(() => setLoading(false))
    // 仓列表失败不阻塞设置主表单（活动仓 Select 退化为只有「个人仓」）
    listMyRepos()
      .then((repos) => {
        setMyRepos(repos)
        const mine = getUserSnapshot()?.username
        repos
          .filter((r) => r.createdBy === mine)
          .forEach((r) => {
            listRepoMembers(r.repoKey)
              .then((members) => setMembersByRepo((m) => ({ ...m, [r.repoKey]: members })))
              .catch(() => undefined)
          })
      })
      .catch(() => setMyRepos([]))
  }, [form])

  useEffect(reload, [reload])

  // 模板留空 = 回退内置默认；「填入默认」拉取内置模板进编辑器便于在其基础上改
  const fillDefaultTemplate = async (field: 'dailyTemplateMd' | 'weeklyTemplateMd') => {
    try {
      const d = await getDefaultTemplates()
      form.setFieldsValue({ [field]: field === 'dailyTemplateMd' ? d.dailyTemplateMd : d.weeklyTemplateMd })
    } catch (e) {
      showError(e, '获取默认模板失败')
    }
  }

  const save = async () => {
    let v: FormValues
    try {
      v = await form.validateFields()
    } catch {
      return // 校验失败：字段已高亮提示
    }
    try {
      await updateSettings({
        autoDaily: v.autoDaily,
        autoWeekly: v.autoWeekly,
        autoGitImport: v.autoGitImport,
        // 执行时间：有值发 "HH:mm"，清空发 ""（后端语义 = 清除个人设置跟随全局）
        dailyTime: v.dailyTime ? v.dailyTime.format('HH:mm') : '',
        weeklyDay: v.weeklyDay ?? 0, // 0 = 清除
        weeklyTime: v.weeklyTime ? v.weeklyTime.format('HH:mm') : '',
        gitImportTime: v.gitImportTime ? v.gitImportTime.format('HH:mm') : '',
        dailyMinutesTarget: v.dailyHoursTarget != null ? Math.round(v.dailyHoursTarget * 60) : undefined,
        dailyTemplateMd: v.dailyTemplateMd,
        weeklyTemplateMd: v.weeklyTemplateMd,
        remoteUrl: v.remoteUrl ?? '',
        remoteBranch: v.remoteBranch ?? '',
        // '' = 回个人仓（后端空白串语义）；null = 不变，这里表单全量提交不走 null
        activeRepoKey: v.activeRepoKey ?? '',
      })
      message.success('设置已保存')
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  useImperativeHandle(ref, () => ({ reload, save }))

  // ---- CAP-74 M2：成员管理（仅仓主可见操作；接口返回更新后的成员列表直接回填） ----
  const onAddMember = async (repoKey: string) => {
    const username = (memberInput[repoKey] ?? '').trim()
    if (!username) return
    setMemberBusy((b) => ({ ...b, [repoKey]: true }))
    try {
      const members = await addRepoMember(repoKey, username)
      setMembersByRepo((m) => ({ ...m, [repoKey]: members }))
      setMemberInput((m) => ({ ...m, [repoKey]: '' }))
      message.success(`已加入成员 ${username}`)
    } catch (e) {
      showError(e, '添加成员失败')
    } finally {
      setMemberBusy((b) => ({ ...b, [repoKey]: false }))
    }
  }

  const onRemoveMember = (repoKey: string, username: string) => {
    Modal.confirm({
      centered: true,
      title: `移除成员 ${username}？`,
      content: '仅移除成员关系，仓内该成员的历史文件保留。',
      okText: '移除',
      okButtonProps: { danger: true },
      onOk: () =>
        removeRepoMember(repoKey, username)
          .then((members) => {
            setMembersByRepo((m) => ({ ...m, [repoKey]: members }))
            message.success('已移除')
          })
          .catch((e) => showError(e, '移除成员失败')),
    })
  }

  return (
    <div style={{ maxWidth: 640 }}>
      <Typography.Paragraph type="secondary">
        日报/周报自动生成、定时从 Git 导入工作条目、每日工时目标、报表格式模板与工作日志空间的远程备份。
        三个定时项的执行时间可按个人覆盖全局规则，留空 = 跟随全局；
        改动即时生效：自动生成与定时导入由服务端定时任务触发，模板用于生成会话，远程备份供工作日志页的「推送远端」使用。
      </Typography.Paragraph>
      <Spin spinning={loading}>
        <Form form={form} layout="vertical">
          <Form.Item
            label="每天自动生成日报草稿"
            extra={`执行时间留空 = 跟随全局（${globalLabels.daily}）`}
          >
            <Space wrap>
              <Form.Item name="autoDaily" valuePropName="checked" noStyle>
                <Switch />
              </Form.Item>
              <Form.Item name="dailyTime" noStyle>
                <TimePicker format="HH:mm" allowClear placeholder="跟随全局" changeOnBlur />
              </Form.Item>
            </Space>
          </Form.Item>
          <Form.Item
            label="每周定时生成上周周报草稿"
            extra={`星期与时间都设置才覆盖全局（留空 = 跟随全局：${globalLabels.weekly}）`}
          >
            <Space wrap>
              <Form.Item name="autoWeekly" valuePropName="checked" noStyle>
                <Switch />
              </Form.Item>
              <Form.Item name="weeklyDay" noStyle>
                <Select allowClear placeholder="跟随全局" style={{ width: 110 }} options={WEEK_DAY_OPTIONS} />
              </Form.Item>
              <Form.Item name="weeklyTime" noStyle>
                <TimePicker format="HH:mm" allowClear placeholder="跟随全局" changeOnBlur />
              </Form.Item>
            </Space>
          </Form.Item>
          <Form.Item
            label="每天定时从 Git 导入工作条目"
            extra={`执行时间留空 = 跟随全局（${globalLabels.gitImport}，全局默认早于日报生成，当日条目先落库再进日报素材）。扫描本人勾选仓库的当日提交，自动导入为新条目（工时 0，事后再补）；未能解析提交署名的仓库会跳过，防混入他人提交；已导入过的提交自动去重，与手动「从 Git 导入」不冲突。`}
          >
            <Space wrap>
              <Form.Item name="autoGitImport" valuePropName="checked" noStyle>
                <Switch />
              </Form.Item>
              <Form.Item name="gitImportTime" noStyle>
                <TimePicker format="HH:mm" allowClear placeholder="跟随全局" changeOnBlur />
              </Form.Item>
            </Space>
          </Form.Item>
          <Form.Item name="dailyHoursTarget" label="每日工时目标（小时）">
            <InputNumber min={0} max={24} step={0.5} style={{ width: '100%' }} placeholder="如 8" />
          </Form.Item>
          <Form.Item
            name="dailyTemplateMd"
            label="日报格式模板"
            extra={
              <>
                占位符：{'{{date}}'} / {'{{entries}}'} / {'{{commits}}'}；留空 = 内置默认。
                <Button type="link" size="small" onClick={() => fillDefaultTemplate('dailyTemplateMd')}>
                  填入内置默认
                </Button>
              </>
            }
          >
            <Input.TextArea rows={7} placeholder="留空使用内置默认模板" style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item
            name="weeklyTemplateMd"
            label="周报格式模板"
            extra={
              <>
                占位符：{'{{weekRange}}'} / {'{{entries}}'} / {'{{commits}}'}；留空 = 内置默认。
                <Button type="link" size="small" onClick={() => fillDefaultTemplate('weeklyTemplateMd')}>
                  填入内置默认
                </Button>
              </>
            }
          >
            <Input.TextArea rows={8} placeholder="留空使用内置默认模板" style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item
            name="remoteUrl"
            label="远程仓库备份（URL）"
            extra="把日报/周报所在的工作日志仓备份到该远端（点工作日志页空间条上的「推送远端」由服务端直接 push，无需 runner 在线）。http/https 凭证按 URL host 匹配「设置 → 第三方账号」的个人访问令牌；file:// 免凭证。留空 = 解绑。"
          >
            <Input placeholder="https://git.example.com/<你>/worklog.git" allowClear />
          </Form.Item>
          <Form.Item name="remoteBranch" label="备份分支">
            <Input placeholder="留空默认 main" allowClear />
          </Form.Item>
          <Form.Item
            name="activeRepoKey"
            label="活动仓"
            extra="团队仓成员各写各的 <username>/ 子目录，互不覆盖；推送/冲突解决由仓主在远端配置权限。选「个人仓」即回到自己的默认仓。"
          >
            <Select
              style={{ maxWidth: 360 }}
              options={[
                { value: '', label: '个人仓（默认）' },
                ...myRepos
                  .filter((r) => !r.personal)
                  .map((r) => ({ value: r.repoKey, label: `${r.repoKey}（${r.createdBy} 的仓）` })),
              ]}
            />
          </Form.Item>
        </Form>
      </Spin>
      {myRepos
        .filter((r) => !r.personal && r.createdBy === me)
        .map((r) => (
          <div key={r.repoKey} style={{ marginTop: 8 }}>
            <Typography.Title level={5}>仓成员管理：{r.repoKey}</Typography.Title>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
              成员把活动仓切到该仓后，各自的日报/周报写入各自的子目录；移除成员不删历史文件。
            </Typography.Paragraph>
            <Space direction="vertical" style={{ width: '100%' }} size={4}>
              {(membersByRepo[r.repoKey] ?? []).map((m) => (
                <Space key={m.username}>
                  <Typography.Text>{m.username}</Typography.Text>
                  {m.owner ? (
                    <Tag color="blue">仓主</Tag>
                  ) : (
                    <Button size="small" danger type="link" onClick={() => onRemoveMember(r.repoKey, m.username)}>
                      移除
                    </Button>
                  )}
                </Space>
              ))}
              <Space.Compact style={{ maxWidth: 360 }}>
                <Input
                  placeholder="按用户名添加成员"
                  value={memberInput[r.repoKey] ?? ''}
                  onChange={(e) => setMemberInput((m) => ({ ...m, [r.repoKey]: e.target.value }))}
                  onPressEnter={() => onAddMember(r.repoKey)}
                />
                <Button
                  type="primary"
                  loading={memberBusy[r.repoKey]}
                  onClick={() => onAddMember(r.repoKey)}
                >
                  添加
                </Button>
              </Space.Compact>
            </Space>
          </div>
        ))}
    </div>
  )
}
