import { Button, Card, Checkbox, Empty, Modal, Progress, Space, Tag, Tooltip, Typography, message } from 'antd'
import {
  FileTextOutlined,
  LeftOutlined,
  PlusOutlined,
  ReloadOutlined,
  RightOutlined,
  SwapRightOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useCallback, useEffect, useState } from 'react'
import { carryGoals, createGoal, deleteGoal, getGoalTree, updateGoal } from '../api'
import type { GoalNode, GoalPayload, GoalTreeView, WorklogGoal } from '../types'
import { GOAL_STATUSES } from '../types'
import GoalFormDrawer, { type GoalPreset } from './GoalFormDrawer'
import QuarterlyReviewDrawer from './QuarterlyReviewDrawer'
import { showError } from '../../../shared/utils/showError'

/** 季度 period → 日期范围展示（'2026-Q1' → '01-01 ~ 03-31'） */
const quarterRange = (period: string) => {
  const m = period.match(/^(\d{4})-Q([1-4])$/)
  if (!m) return ''
  const startMonth = (Number(m[2]) - 1) * 3 + 1
  const end = dayjs(`${m[1]}-${String(startMonth + 2).padStart(2, '0')}-01`).endOf('month')
  return `${String(startMonth).padStart(2, '0')}-01 ~ ${end.format('MM-DD')}`
}

/** 下一周期：季度滚下一季（Q4 跨年），年度滚下一年 */
const nextPeriodOf = (period: string, year: number) => {
  const m = period.match(/^(\d{4})-Q([1-4])$/)
  if (!m) return String(year + 1)
  const q = Number(m[2])
  return q < 4 ? `${m[1]}-Q${q + 1}` : `${Number(m[1]) + 1}-Q1`
}

const statusTag = (s: string) => {
  const meta = GOAL_STATUSES[s]
  return meta ? <Tag color={meta.color}>{meta.label}</Tag> : <Tag>{s}</Tag>
}

/** 滚转弹层状态：sourceLabel 展示用；candidates = 可滚转的 ACTIVE 目标节点 */
interface CarryState {
  sourceLabel: string
  toPeriod: string
  candidates: GoalNode[]
  selected: Set<number>
}

/**
 * CAP-91「目标」视图：年度目标区 + Q1~Q4 季度 OKR 区（O→KR 树）。
 * KR 行展示人工进度 + 派生建议进度（关联计划完成度/工时，只展示不回写）；
 * 每个季度区头部有「季度复盘」入口（M2）与跨周期滚转（FR-04：KR 需与父 O 同批滚转）。
 */
export default function GoalBoard() {
  const [year, setYear] = useState(() => dayjs().year())
  const [tree, setTree] = useState<GoalTreeView | null>(null)
  const [loading, setLoading] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<WorklogGoal | null>(null)
  const [preset, setPreset] = useState<GoalPreset | null>(null)
  const [saving, setSaving] = useState(false)
  const [carry, setCarry] = useState<CarryState | null>(null)
  const [carrying, setCarrying] = useState(false)
  const [reviewPeriod, setReviewPeriod] = useState<string | null>(null)

  const isThisYear = year === dayjs().year()

  const load = useCallback(() => {
    setLoading(true)
    getGoalTree(year)
      .then(setTree)
      .catch((e) => showError(e, '加载目标失败'))
      .finally(() => setLoading(false))
  }, [year])
  useEffect(load, [load])

  const openCreate = (p: GoalPreset) => {
    setEditTarget(null)
    setPreset(p)
    setDrawerOpen(true)
  }
  const openEdit = (g: WorklogGoal) => {
    setEditTarget(g)
    setPreset(null)
    setDrawerOpen(true)
  }

  const onSave = async (payload: GoalPayload) => {
    setSaving(true)
    try {
      if (editTarget) await updateGoal(editTarget.id, payload)
      else await createGoal(payload)
      message.success('已保存')
      setDrawerOpen(false)
      load()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onDelete = (g: WorklogGoal) => {
    Modal.confirm({
      centered: true,
      title: `删除「${g.title}」？`,
      content:
        g.kind === 'OBJECTIVE'
          ? 'O 下仍有 KR 时无法删除（需先删除或滚转其子项）。'
          : '删除后，关联该 KR 的周计划项将解除对齐。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () =>
        deleteGoal(g.id)
          .then(() => {
            message.success('已删除')
            load()
          })
          .catch((e) => showError(e, '删除失败')),
    })
  }

  /** 打开滚转弹层：候选 = 本区 ACTIVE 目标（O+KR 两层；KR 需父 O 同批勾选） */
  const openCarry = (sourceLabel: string, toPeriod: string, nodes: GoalNode[]) => {
    const candidates = nodes
      .map((n) => ({
        goal: n.goal,
        krs: n.krs.filter((k) => k.status === 'ACTIVE'),
      }))
      .filter((n) => n.goal.status === 'ACTIVE' || n.krs.length > 0)
    if (candidates.length === 0) {
      message.info('本区没有进行中的目标可滚转')
      return
    }
    setCarry({ sourceLabel, toPeriod, candidates, selected: new Set() })
  }

  const toggleCarry = (g: WorklogGoal, krs: WorklogGoal[], checked: boolean) => {
    setCarry((c) => {
      if (!c) return c
      const selected = new Set(c.selected)
      if (g.kind === 'OBJECTIVE') {
        // 勾选 O 联动其全部 KR；取消 O 联动取消（KR 不能脱离父 O 单独滚转）
        if (checked) selected.add(g.id)
        else selected.delete(g.id)
        krs.forEach((k) => (checked ? selected.add(k.id) : selected.delete(k.id)))
      } else if (checked) selected.add(g.id)
      else selected.delete(g.id)
      return { ...c, selected }
    })
  }

  const onCarry = () => {
    if (!carry || carry.selected.size === 0) return
    setCarrying(true)
    carryGoals(carry.toPeriod, [...carry.selected])
      .then((r) => {
        message.success(`已滚转 ${r.carried} 项${r.skipped > 0 ? `，跳过 ${r.skipped} 项` : ''}到 ${carry.toPeriod}`)
        setCarry(null)
        load()
      })
      .catch((e) => showError(e, '滚转失败'))
      .finally(() => setCarrying(false))
  }

  const renderKr = (kr: WorklogGoal) => (
    <div
      key={kr.id}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '6px 0 6px 24px',
        borderTop: '1px dashed #f0f0f0',
        flexWrap: 'wrap',
      }}
    >
      <Typography.Text code>KR#{kr.id}</Typography.Text>
      {statusTag(kr.status)}
      <Typography.Text style={{ flex: 1, minWidth: 200 }} ellipsis={{ tooltip: kr.title }}>
        {kr.title}
      </Typography.Text>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        人工 {kr.progress ?? 0}%
      </Typography.Text>
      {kr.suggestedProgress != null && (
        <Tooltip
          title={`建议进度由关联周计划项派生（${kr.linkedPlanDone ?? 0}/${kr.linkedPlanTotal ?? 0} 完成 · 工时 ${kr.linkedHours ?? 0}h），只展示不回写`}
        >
          <Progress type="circle" size={28} percent={kr.suggestedProgress} />
        </Tooltip>
      )}
      {kr.suggestedProgress != null && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          计划 {kr.linkedPlanDone ?? 0}/{kr.linkedPlanTotal ?? 0} · {kr.linkedHours ?? 0}h
        </Typography.Text>
      )}
      <Space size={4}>
        <Button size="small" onClick={() => openEdit(kr)}>
          编辑
        </Button>
        <Button size="small" danger onClick={() => onDelete(kr)}>
          删除
        </Button>
      </Space>
    </div>
  )

  const renderNode = (node: GoalNode, scope: 'YEAR' | 'QUARTER', period: string) => (
    <Card
      key={node.goal.id}
      size="small"
      style={{ marginBottom: 8 }}
      title={
        <Space size={8} wrap>
          {statusTag(node.goal.status)}
          <Typography.Text strong>O：{node.goal.title}</Typography.Text>
          {node.goal.carriedFromId != null && <Tag color="purple">滚转</Tag>}
          {node.goal.parentTitle && <Tag>↑ {node.goal.parentTitle}</Tag>}
          {node.goal.weight != null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              权重 {node.goal.weight}%
            </Typography.Text>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            进度 {node.goal.progress ?? 0}%
          </Typography.Text>
        </Space>
      }
      extra={
        <Space size={4}>
          <Button
            size="small"
            icon={<PlusOutlined />}
            disabled={node.goal.status !== 'ACTIVE'}
            onClick={() =>
              openCreate({ scope, period, kind: 'KEY_RESULT', parentId: node.goal.id, parentTitle: node.goal.title })
            }
          >
            新建 KR
          </Button>
          <Button size="small" onClick={() => openEdit(node.goal)}>
            编辑
          </Button>
          <Button size="small" danger onClick={() => onDelete(node.goal)}>
            删除
          </Button>
        </Space>
      }
    >
      {node.goal.content && (
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
          {node.goal.content}
        </Typography.Paragraph>
      )}
      {node.krs.length === 0 ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          暂无 KR：点右上「新建 KR」拆解关键结果
        </Typography.Text>
      ) : (
        node.krs.map(renderKr)
      )}
    </Card>
  )

  const yearOs = tree?.yearObjectives ?? []

  return (
    <>
      <Space style={{ marginBottom: 12 }} wrap>
        <Button size="small" icon={<LeftOutlined />} onClick={() => setYear((y) => y - 1)} />
        <Typography.Text strong>{year} 年{isThisYear && '（今年）'}</Typography.Text>
        <Button size="small" icon={<RightOutlined />} onClick={() => setYear((y) => y + 1)} />
        <Button size="small" disabled={isThisYear} onClick={() => setYear(dayjs().year())}>
          今年
        </Button>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
          刷新
        </Button>
      </Space>

      <Card
        size="small"
        title={`${year} 年度目标`}
        style={{ marginBottom: 16 }}
        extra={
          <Space size={4}>
            <Button
              size="small"
              icon={<SwapRightOutlined />}
              onClick={() => openCarry(`${year} 年度`, String(year + 1), yearOs)}
            >
              滚转到明年
            </Button>
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => openCreate({ scope: 'YEAR', period: String(year), kind: 'OBJECTIVE' })}
            >
              新建年度目标
            </Button>
          </Space>
        }
      >
        {yearOs.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="本年度暂无目标：点右上「新建年度目标」定全年方向"
          />
        ) : (
          yearOs.map((n) => renderNode(n, 'YEAR', String(year)))
        )}
      </Card>

      {(tree?.quarters ?? []).map((q) => (
        <Card
          key={q.period}
          size="small"
          title={
            <Space size={8}>
              <span>{q.period} 季度 OKR</span>
              <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
                {quarterRange(q.period)}
              </Typography.Text>
            </Space>
          }
          style={{ marginBottom: 16 }}
          extra={
            <Space size={4}>
              <Button size="small" icon={<FileTextOutlined />} onClick={() => setReviewPeriod(q.period)}>
                季度复盘
              </Button>
              <Button
                size="small"
                icon={<SwapRightOutlined />}
                onClick={() => openCarry(q.period, nextPeriodOf(q.period, year), q.objectives)}
              >
                滚转
              </Button>
              <Button
                size="small"
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => openCreate({ scope: 'QUARTER', period: q.period, kind: 'OBJECTIVE' })}
              >
                新建目标
              </Button>
            </Space>
          }
        >
          {q.objectives.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="本季度暂无 OKR：点右上「新建目标」制定季度 O，再逐个拆 KR"
            />
          ) : (
            q.objectives.map((n) => renderNode(n, 'QUARTER', q.period))
          )}
        </Card>
      ))}

      <GoalFormDrawer
        open={drawerOpen}
        target={editTarget}
        preset={preset}
        yearObjectives={yearOs.map((n) => n.goal)}
        saving={saving}
        onCancel={() => setDrawerOpen(false)}
        onSave={onSave}
      />
      <QuarterlyReviewDrawer period={reviewPeriod} onCancel={() => setReviewPeriod(null)} />
      <Modal
        centered
        title={`滚转目标：${carry?.sourceLabel ?? ''} → ${carry?.toPeriod ?? ''}`}
        open={carry != null}
        okText={`滚转 ${carry?.selected.size ?? 0} 项`}
        okButtonProps={{ disabled: (carry?.selected.size ?? 0) === 0 }}
        confirmLoading={carrying}
        onOk={onCarry}
        onCancel={() => setCarry(null)}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          勾选的目标将以「已滚转」结案并在 {carry?.toPeriod} 生成进行中副本（幂等：已滚转/已完结跳过）。
          KR 需随父 O 同批滚转，勾选 O 自动带上其 KR。
        </Typography.Paragraph>
        {(carry?.candidates ?? []).map((n) => (
          <div key={n.goal.id} style={{ marginBottom: 4 }}>
            {n.goal.status === 'ACTIVE' && (
              <Checkbox
                checked={carry?.selected.has(n.goal.id)}
                onChange={(e) => toggleCarry(n.goal, n.krs, e.target.checked)}
              >
                <b>O：{n.goal.title}</b>
              </Checkbox>
            )}
            {n.krs.map((k) => (
              <div key={k.id} style={{ paddingLeft: 24 }}>
                <Checkbox
                  checked={carry?.selected.has(k.id)}
                  disabled={!carry?.selected.has(n.goal.id)}
                  onChange={(e) => toggleCarry(k, [], e.target.checked)}
                >
                  KR#{k.id} {k.title}
                </Checkbox>
              </div>
            ))}
          </div>
        ))}
      </Modal>
    </>
  )
}
