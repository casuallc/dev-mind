import { Button, Checkbox, Collapse, Drawer, Empty, Input, InputNumber, Modal, Select, Space, Tag, Typography, message } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { adoptOkrDraft, discardOkrDraft, getGoalTree, getOkrDraft } from '../api'
import type { OkrDraftView, SuggestedObjective, WorklogGoal } from '../types'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'

interface Props {
  /** 目标周期 period（'2027' 或 '2026-Q4'）；null = 关闭 */
  period: string | null
  onCancel: () => void
  /** 采纳成功后回调（父组件刷新目标树） */
  onAdopted: () => void
}

/** 抽屉内可编辑行（在 SuggestedObjective 上加 checked；KR 行同构） */
interface DraftKr {
  checked: boolean
  title: string
  content?: string
  weight?: number
}
interface DraftObjective extends DraftKr {
  parentYearGoalId?: number
  krs: DraftKr[]
}

const toRows = (items: SuggestedObjective[]): DraftObjective[] =>
  (items ?? []).map((o) => ({
    checked: true,
    title: o.title,
    content: o.content,
    weight: o.weight,
    parentYearGoalId: o.parentYearGoalId,
    krs: (o.krs ?? []).map((k) => ({ checked: true, title: k.title, content: k.content, weight: k.weight })),
  }))

/**
 * CAP-93 FR-06 OKR 草稿抽屉：AI 生成结果进草稿，人工勾选/编辑（标题、权重、季度 O 挂年度 O）
 * 后「采纳」批量落库（复用目标校验链），或「丢弃」。采纳/丢弃后同 period 重生成会覆盖出新草稿。
 */
export default function OkrSuggestDrawer({ period, onCancel, onAdopted }: Props) {
  const [draft, setDraft] = useState<OkrDraftView | undefined>()
  const [rows, setRows] = useState<DraftObjective[]>([])
  const [yearOs, setYearOs] = useState<WorklogGoal[]>([])
  const [loading, setLoading] = useState(false)
  const [acting, setActing] = useState(false)

  const load = useCallback(() => {
    if (!period) return
    setLoading(true)
    getOkrDraft(period)
      .then((d) => {
        setDraft(d)
        setRows(toRows(d.objectives))
        // 季度草稿的「挂年度 O」选项取草稿所属年的年度 O（可能与当前查看年不同）
        if (d.exists && d.scope === 'QUARTER') {
          getGoalTree(Number(period.slice(0, 4)))
            .then((t) => setYearOs(t.yearObjectives.map((n) => n.goal)))
            .catch(() => setYearOs([]))
        } else {
          setYearOs([])
        }
      })
      .catch((e) => showError(e, '加载 OKR 草稿失败'))
      .finally(() => setLoading(false))
  }, [period])
  useEffect(load, [load])

  const patchRow = (i: number, patch: Partial<DraftObjective>) =>
    setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const patchKr = (i: number, k: number, patch: Partial<DraftKr>) =>
    setRows((rs) =>
      rs.map((r, j) =>
        j === i ? { ...r, krs: r.krs.map((kr, m) => (m === k ? { ...kr, ...patch } : kr)) } : r,
      ),
    )

  const adoptable = rows.filter((r) => r.checked)
  const adoptCount = adoptable.reduce((n, r) => n + 1 + r.krs.filter((k) => k.checked).length, 0)
  const invalid = adoptable.some((r) => !r.title.trim() || r.krs.some((k) => k.checked && !k.title.trim()))

  const onAdopt = () => {
    if (!draft?.id) return
    Modal.confirm({
      centered: true,
      title: `采纳 ${adoptCount} 项到 ${period}？`,
      content: '将按勾选逐项创建目标（O → KR），创建后状态为「进行中」，可在目标树里继续维护。',
      okText: '采纳',
      onOk: async () => {
        setActing(true)
        try {
          const body: SuggestedObjective[] = adoptable.map((r) => ({
            title: r.title.trim(),
            content: r.content,
            weight: r.weight,
            parentYearGoalId: draft.scope === 'QUARTER' ? r.parentYearGoalId : undefined,
            krs: r.krs
              .filter((k) => k.checked)
              .map((k) => ({ title: k.title.trim(), content: k.content, weight: k.weight })),
          }))
          const r = await adoptOkrDraft(draft.id!, body)
          message.success(`已采纳，创建 ${r.created} 项目标`)
          onAdopted()
          onCancel()
        } catch (e) {
          showError(e, '采纳失败')
        } finally {
          setActing(false)
        }
      },
    })
  }

  const onDiscard = () => {
    if (!draft?.id) return
    Modal.confirm({
      centered: true,
      title: `丢弃 ${period} 的 OKR 草稿？`,
      content: '草稿内容将被标记为已丢弃（重新生成同周期会覆盖出新草稿）。',
      okText: '丢弃',
      okButtonProps: { danger: true },
      onOk: async () => {
        setActing(true)
        try {
          await discardOkrDraft(draft.id!)
          message.success('已丢弃')
          onCancel()
        } catch (e) {
          showError(e, '丢弃失败')
        } finally {
          setActing(false)
        }
      },
    })
  }

  return (
    <Drawer
      title={`OKR 草稿（${period ?? ''}）`}
      open={period != null}
      onClose={onCancel}
      width={DRAWER_SCREEN}
      loading={loading}
      extra={
        draft?.exists && (
          <Space size={8}>
            <Button danger disabled={acting} onClick={onDiscard}>
              丢弃
            </Button>
            <Button
              type="primary"
              disabled={acting || adoptCount === 0 || invalid}
              loading={acting}
              onClick={onAdopt}
            >
              采纳 {adoptCount} 项
            </Button>
          </Space>
        )
      }
    >
      {!draft?.exists ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="该周期暂无草稿：在目标树卡片右上角点「AI 生成」发起分析"
        />
      ) : (
        <>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
            AI 建议仅供参考：取消勾选不需要的行、改标题与权重后再采纳。
            {draft.scope === 'QUARTER' && ' 季度 O 可选挂同年的年度 O。'}
            {invalid && <Tag color="warning">有未填标题的行，无法采纳</Tag>}
          </Typography.Paragraph>
          {rows.map((r, i) => (
            <div
              key={i}
              style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '8px 12px', marginBottom: 8 }}
            >
              <Space size={8} wrap style={{ width: '100%' }}>
                <Checkbox
                  checked={r.checked}
                  onChange={(e) =>
                    patchRow(i, {
                      checked: e.target.checked,
                      krs: r.krs.map((k) => ({ ...k, checked: e.target.checked })),
                    })
                  }
                />
                <Tag color="blue">O</Tag>
                <Input
                  style={{ flex: 1, minWidth: 240 }}
                  value={r.title}
                  maxLength={256}
                  onChange={(e) => patchRow(i, { title: e.target.value })}
                />
                <InputNumber
                  size="middle"
                  min={0}
                  max={100}
                  placeholder="权重%"
                  value={r.weight}
                  onChange={(v) => patchRow(i, { weight: v ?? undefined })}
                />
                {draft.scope === 'QUARTER' && (
                  <Select
                    allowClear
                    style={{ minWidth: 200 }}
                    placeholder="挂年度 O（可选）"
                    value={r.parentYearGoalId}
                    options={yearOs.map((g) => ({ value: g.id, label: g.title }))}
                    onChange={(v) => patchRow(i, { parentYearGoalId: v })}
                  />
                )}
                <Button
                  size="small"
                  danger
                  onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}
                >
                  删除
                </Button>
              </Space>
              {r.content && (
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: '4px 0 0 24px' }}>
                  {r.content}
                </Typography.Paragraph>
              )}
              {r.krs.map((k, m) => (
                <div
                  key={m}
                  style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0 0 24px' }}
                >
                  <Checkbox
                    checked={k.checked}
                    disabled={!r.checked}
                    onChange={(e) => patchKr(i, m, { checked: e.target.checked })}
                  />
                  <Tag>KR</Tag>
                  <Input
                    style={{ flex: 1, minWidth: 200 }}
                    size="small"
                    value={k.title}
                    maxLength={256}
                    onChange={(e) => patchKr(i, m, { title: e.target.value })}
                  />
                  <InputNumber
                    size="small"
                    min={0}
                    max={100}
                    placeholder="权重%"
                    value={k.weight}
                    onChange={(v) => patchKr(i, m, { weight: v ?? undefined })}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    onClick={() =>
                      patchRow(i, { krs: r.krs.filter((_, x) => x !== m) })
                    }
                  >
                    删除
                  </Button>
                </div>
              ))}
            </div>
          ))}
          {draft.contentMd && (
            <Collapse
              style={{ marginTop: 12 }}
              items={[
                {
                  key: 'raw',
                  label: '查看 AI 原始产出',
                  children: (
                    <Typography.Paragraph>
                      <pre style={{ whiteSpace: 'pre-wrap', fontSize: 12, margin: 0 }}>{draft.contentMd}</pre>
                    </Typography.Paragraph>
                  ),
                },
              ]}
            />
          )}
        </>
      )}
    </Drawer>
  )
}
