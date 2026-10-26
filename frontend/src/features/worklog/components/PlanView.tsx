import { Alert, Button, Checkbox, Input, Modal, Select, Space, Tag, Typography, message } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useCallback, useImperativeHandle, useMemo, useState, useEffect, type Ref } from 'react'
import { carryPlans, createPlanItem, deletePlanItem, getWeekly, listPlans, suggestPlans, updatePlanItem } from '../api'
import type { PlanItem, PlanItemPayload } from '../types'
import { ENTRY_TYPES, PLAN_STATUSES } from '../types'
import FitTable from '../../../shared/components/FitTable'
import PlanItemFormDrawer from './PlanItemFormDrawer'
import RecentWeekStrip from './RecentWeekStrip'
import { showError } from '../../../shared/utils/showError'

const mondayOf = (d: Dayjs) => d.startOf('week').add(1, 'day') // dayjs 周日开头，+1 = 周一

/** 人工可设状态（CARRIED 由滚转写入，不在下拉里） */
const EDITABLE_STATUSES = ['TODO', 'DOING', 'DONE', 'DROPPED']

/** 页面壳（WorklogPage）工具栏在 Card extra，靠该句柄触发 本周/刷新/AI 建议/新建计划项 */
export interface PlanViewHandle {
  backToThisWeek: () => void
  reload: () => void
  openSuggest: () => void
  openCreate: () => void
}

/**
 * CAP-88 FR-06 + CAP-90 FR-06「计划」视图：WEEK 唯一粒度（日计划已并入条目——条目状态即计划态，
 * 今日清单在「条目」页签维护）。状态徽标 + 下拉改状态 + 预估工时 + 关联需求/Jira；
 * 上周未完成提示条一键滚转；下周计划项是周报「下周计划」节的唯一事实源（单向投影）。
 * 周切换复用周报的 7 周选择条（不可选未来周）；操作按钮在页面壳 Card extra（布局约定），经 ref 句柄触发。
 */
export default function PlanView({ ref }: { ref?: Ref<PlanViewHandle> }) {
  const [week, setWeek] = useState<Dayjs>(() => mondayOf(dayjs()))
  // 周条窗口回退周数（0=最右为本周），与周报视图同口径
  const [weeksBack, setWeeksBack] = useState(0)
  const [items, setItems] = useState<PlanItem[]>([])
  const [carryover, setCarryover] = useState<PlanItem[]>([])
  const [carryoverFrom, setCarryoverFrom] = useState('')
  // CAP-91 FR-05：krId→标题映射（KR 列展示；后端随列表响应带全）
  const [krTitles, setKrTitles] = useState<Record<number, string>>({})
  const [loading, setLoading] = useState(false)
  const [carrying, setCarrying] = useState(false)
  const [editTarget, setEditTarget] = useState<PlanItem | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  // 上一周周报的「下周计划」文本：命中的本周计划项打「已写入周报」徽标（单向投影闭环的可视化）
  const [reportPlanMd, setReportPlanMd] = useState('')
  // CAP-88 M3「AI 建议」弹层（受控取值，不用 Form——弹层关闭即卸载会丢值）
  const [suggestOpen, setSuggestOpen] = useState(false)
  const [suggestHint, setSuggestHint] = useState('')
  const [suggestForce, setSuggestForce] = useState(false)
  const [suggesting, setSuggesting] = useState(false)

  const currentDateStr = week.format('YYYY-MM-DD')

  const load = useCallback(() => {
    setLoading(true)
    listPlans(currentDateStr)
      .then((r) => {
        setItems(r.items)
        setCarryover(r.carryoverItems)
        setCarryoverFrom(r.carryoverFrom)
        setKrTitles(r.krTitles ?? {})
      })
      .catch((e) => showError(e, '加载计划失败'))
      .finally(() => setLoading(false))
    // 本周计划是否已被上周周报叙述（标题命中即视为已投影——文本可人工润色，宽松匹配）
    getWeekly(mondayOf(dayjs(currentDateStr)).subtract(1, 'week').format('YYYY-MM-DD'))
      .then((r) => setReportPlanMd(r?.nextPlanMd ?? ''))
      .catch(() => setReportPlanMd(''))
  }, [currentDateStr])

  useEffect(load, [load])

  useImperativeHandle(ref, () => ({
    backToThisWeek: () => {
      setWeeksBack(0)
      setWeek(mondayOf(dayjs()))
    },
    reload: load,
    openSuggest: () => setSuggestOpen(true),
    openCreate: () => {
      setEditTarget(null)
      setEditOpen(true)
    },
  }))

  const onSave = async (payload: PlanItemPayload) => {
    setSaving(true)
    try {
      if (editTarget) await updatePlanItem(editTarget.id, payload)
      else await createPlanItem(payload)
      message.success('已保存')
      setEditOpen(false)
      load()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onStatusChange = (item: PlanItem, status: string) => {
    updatePlanItem(item.id, {
      scope: item.scope,
      date: item.planDate,
      title: item.title,
      content: item.content,
      planType: item.planType,
      hours: item.estimateHours,
      requirementId: item.requirementId,
      jiraIssueKey: item.jiraIssueKey,
      // CAP-91：krId 必须回传——缺省会被后端当作解除对齐
      krId: item.krId,
      status,
    })
      .then(() => load())
      .catch((e) => showError(e, '状态更新失败'))
  }

  const onDelete = (item: PlanItem) => {
    Modal.confirm({
      centered: true,
      title: '删除该计划项？',
      content: '关联条目的计划关联将被解除。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () =>
        deletePlanItem(item.id)
          .then(() => {
            message.success('已删除')
            load()
          })
          .catch((e) => showError(e, '删除失败')),
    })
  }

  const onCarryAll = () => {
    setCarrying(true)
    carryPlans(currentDateStr, carryover.map((c) => c.id))
      .then((r) => {
        message.success(`已滚转 ${r.carried} 项${r.skipped > 0 ? `，跳过 ${r.skipped} 项` : ''}`)
        load()
      })
      .catch((e) => showError(e, '滚转失败'))
      .finally(() => setCarrying(false))
  }

  /** CAP-88 M3：AI 建议当前浏览周（目标周已有项且未勾 force → 后端幂等跳过） */
  const onSuggest = () => {
    setSuggesting(true)
    suggestPlans(week.format('YYYY-MM-DD'), suggestForce, suggestHint || undefined)
      .then((r) => {
        if (r.reused) {
          message.info('该周已有计划项，已跳过生成（勾选「强制重新生成」可追加建议）')
        } else {
          message.success('建议生成会话已启动：完成后建议项自动落入该周（带 AI 徽标，可逐条删改）')
        }
        setSuggestOpen(false)
        setSuggestHint('')
        setSuggestForce(false)
      })
      .catch((e) => showError(e, '建议计划启动失败'))
      .finally(() => setSuggesting(false))
  }

  /** 完成度：DONE / 非 DROPPED 总数（已放弃不计入分母） */
  const ratio = useMemo(() => {
    const active = items.filter((i) => i.status !== 'DROPPED' && i.status !== 'CARRIED')
    const done = active.filter((i) => i.status === 'DONE').length
    return { done, total: active.length }
  }, [items])

  return (
    <>
      <RecentWeekStrip
        selected={currentDateStr}
        onSelect={setWeek}
        weeksBack={weeksBack}
        onShiftWindow={(p) => setWeeksBack((b) => Math.max(0, b + p))}
      />
      {carryover.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={`上一周（${carryoverFrom}）有 ${carryover.length} 项计划未完成`}
          description={
            <Space direction="vertical" size={4}>
              {carryover.map((c) => (
                <Typography.Text key={c.id} style={{ fontSize: 12 }}>
                  · {c.title}（{PLAN_STATUSES[c.status]?.label ?? c.status}）
                </Typography.Text>
              ))}
            </Space>
          }
          action={
            <Button size="small" type="primary" loading={carrying} onClick={onCarryAll}>
              一键滚转到本周
            </Button>
          }
        />
      )}
      {ratio.total > 0 && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8, fontSize: 12 }}>
          本周完成度：{ratio.done}/{ratio.total}
        </Typography.Paragraph>
      )}
      <FitTable
        rowKey="id"
        loading={loading}
        dataSource={items}
        pagination={false}
        locale={{ emptyText: '本周暂无计划项：点「新建计划项」开始规划（日计划已并入条目——在「条目」页签建计划态条目）' }}
        columns={[
          {
            title: '标题',
            dataIndex: 'title',
            ellipsis: true,
            render: (t: string, i: PlanItem) => (
              <Space size={4}>
                {i.source === 'AI_SUGGEST' && <Tag color="geekblue">AI</Tag>}
                {i.carriedFromId != null && <Tag color="purple">滚转</Tag>}
                {reportPlanMd.includes(t) && (
                  <Tag color="cyan" title="上一周周报的「下周计划」已叙述该项（单向投影，文本可再润色）">
                    已写入周报
                  </Tag>
                )}
                <span>{t}</span>
              </Space>
            ),
          },
          {
            title: '类型',
            dataIndex: 'planType',
            width: 90,
            render: (t: string) => <Tag>{ENTRY_TYPES[t] ?? t}</Tag>,
          },
          {
            title: '预估工时',
            dataIndex: 'estimateHours',
            width: 100,
            render: (h?: number) => (h == null ? '-' : `${h}h`),
          },
          {
            // CAP-91 FR-05：KR 对齐列（krTitles 由列表响应带全；已删/失效 KR 兜底显示 id）
            title: 'KR 对齐',
            dataIndex: 'krId',
            width: 150,
            render: (krId?: number) =>
              krId == null ? (
                '-'
              ) : (
                <Tag color="geekblue" title={krTitles[krId] ?? undefined}>
                  {krTitles[krId] ?? `KR#${krId}`}
                </Tag>
              ),
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 150,
            render: (s: string, i: PlanItem) =>
              s === 'CARRIED' ? (
                <Tag color={PLAN_STATUSES.CARRIED.color}>{PLAN_STATUSES.CARRIED.label}</Tag>
              ) : (
                <Select
                  size="small"
                  value={s}
                  style={{ width: 110 }}
                  onChange={(v) => onStatusChange(i, v)}
                  options={EDITABLE_STATUSES.map((v) => ({
                    value: v,
                    label: (
                      <Tag color={PLAN_STATUSES[v].color} style={{ marginInlineEnd: 0 }}>
                        {PLAN_STATUSES[v].label}
                      </Tag>
                    ),
                  }))}
                  labelRender={(p) => PLAN_STATUSES[p.value as string]?.label ?? p.value}
                />
              ),
          },
          {
            title: '关联',
            width: 140,
            render: (_, i) =>
              i.jiraIssueKey ? (
                <Tag color="blue">{i.jiraIssueKey}</Tag>
              ) : i.requirementId ? (
                <Tag>{i.requirementId}</Tag>
              ) : (
                '-'
              ),
          },
          {
            title: '操作',
            width: 150,
            render: (_, i) => (
              <Space>
                <Button
                  size="small"
                  disabled={i.status === 'CARRIED'}
                  onClick={() => {
                    setEditTarget(i)
                    setEditOpen(true)
                  }}
                >
                  编辑
                </Button>
                <Button size="small" danger onClick={() => onDelete(i)}>
                  删除
                </Button>
              </Space>
            ),
          },
        ]}
      />
      <PlanItemFormDrawer
        open={editOpen}
        target={editTarget}
        defaultDate={currentDateStr}
        krTitles={krTitles}
        saving={saving}
        onCancel={() => setEditOpen(false)}
        onSave={onSave}
      />
      <Modal
        centered
        title={`AI 建议 ${week.format('YYYY-MM-DD')} 周计划`}
        open={suggestOpen}
        confirmLoading={suggesting}
        okText="生成建议"
        onOk={onSuggest}
        onCancel={() => setSuggestOpen(false)}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          以「上周未完成/滚转项 + 当前在办需求」为素材，经 worklog 会话生成 3~6 条建议，
          逐条以 AI 徽标落入该周计划（可编辑/删除即确认）。目标周已有计划项时默认跳过。
        </Typography.Paragraph>
        <Input.TextArea
          rows={3}
          placeholder="补充说明（可选）：如「下周优先收尾评审」"
          value={suggestHint}
          onChange={(e) => setSuggestHint(e.target.value)}
          maxLength={500}
        />
        <Checkbox
          style={{ marginTop: 8 }}
          checked={suggestForce}
          onChange={(e) => setSuggestForce(e.target.checked)}
        >
          强制重新生成（已有计划项仍追加建议，不覆盖）
        </Checkbox>
      </Modal>
    </>
  )
}
