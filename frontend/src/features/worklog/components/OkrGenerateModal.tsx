import { Checkbox, Input, Modal, Segmented, Select, Space, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { getOkrDraft, suggestOkr } from '../api'
import { showError } from '../../../shared/utils/showError'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

interface Props {
  open: boolean
  /** 打开时的默认 scope（年度区= YEAR，季度区= QUARTER） */
  defaultScope: 'YEAR' | 'QUARTER'
  /** 当前查看的年（周期选项围绕它展开） */
  viewedYear: number
  /** 当前查看的季度（1-4；年度区传 undefined） */
  viewedQuarter?: number
  /** 草稿生成完成（轮询到草稿更新）→ 父组件打开草稿抽屉 */
  onReady: (period: string) => void
  onCancel: () => void
}

/** 下一周期：季度滚下一季（Q4 跨年），年度滚下一年 */
const nextPeriod = (scope: 'YEAR' | 'QUARTER', year: number, quarter?: number) =>
  scope === 'YEAR'
    ? String(year + 1)
    : quarter === 4
      ? `${year + 1}-Q1`
      : `${year}-Q${(quarter ?? 4) + 1}`

/**
 * CAP-93 FR-05「AI 生成 OKR」弹窗：选周期（年度/某一季度）+ 补充说明 + 强制重生成，
 * 受理后轮询草稿（updatedAt 变化判定，同季度复盘模式），生成完成交父组件开草稿抽屉。
 */
export default function OkrGenerateModal({ open, defaultScope, viewedYear, viewedQuarter, onReady, onCancel }: Props) {
  const [scope, setScope] = useState<'YEAR' | 'QUARTER'>(defaultScope)
  const [period, setPeriod] = useState('')
  const [hint, setHint] = useState('')
  const [force, setForce] = useState(false)
  const [running, setRunning] = useState(false)

  // 每次打开重置：默认生成「当前查看周期的下一周期」（如正在看 Q4 → 默认下一年 Q1）
  useEffect(() => {
    if (!open) return
    setScope(defaultScope)
    setPeriod(nextPeriod(defaultScope, viewedYear, viewedQuarter))
    setHint('')
    setForce(false)
  }, [open, defaultScope, viewedYear, viewedQuarter])

  const yearOptions = [viewedYear - 1, viewedYear, viewedYear + 1, viewedYear + 2].map((y) => ({
    value: String(y),
    label: `${y} 年度`,
  }))
  const quarterOptions = [viewedYear - 1, viewedYear, viewedYear + 1].flatMap((y) =>
    [1, 2, 3, 4].map((q) => ({ value: `${y}-Q${q}`, label: `${y}-Q${q}` })),
  )

  const onScopeChange = (v: 'YEAR' | 'QUARTER') => {
    setScope(v)
    setPeriod(nextPeriod(v, viewedYear, viewedQuarter))
  }

  const onOk = async () => {
    if (!period) return
    setRunning(true)
    const prev = await getOkrDraft(period).catch(() => undefined)
    try {
      const ack = await suggestOkr(scope, period, force, hint.trim() || undefined)
      if (ack.reused) {
        message.info(`${period} 已有目标，未重复生成（如需重生成请勾选「强制重新生成」）`)
        return
      }
      message.info(`生成会话已创建${ack.sessionId ? `（${ack.sessionId}）` : ''}，完成后自动打开草稿…`)
      for (let i = 0; i < 150; i++) {
        await sleep(2000)
        const d = await getOkrDraft(period).catch(() => undefined)
        if (d?.exists && d.updatedAt !== prev?.updatedAt) {
          message.success('生成完成，请确认草稿')
          onReady(period)
          return
        }
      }
      message.warning('生成超时或失败，失败原因与结果请查看通知中心')
    } catch (e) {
      showError(e, '生成失败')
    } finally {
      setRunning(false)
    }
  }

  return (
    <Modal
      centered
      title="AI 生成 OKR"
      open={open}
      okText={running ? '生成中…' : '开始生成'}
      confirmLoading={running}
      okButtonProps={{ disabled: !period }}
      onOk={onOk}
      onCancel={() => !running && onCancel()}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            目标周期
          </Typography.Text>
          <Space style={{ display: 'flex', marginTop: 4 }}>
            <Segmented
              value={scope}
              onChange={(v) => onScopeChange(v as 'YEAR' | 'QUARTER')}
              options={[
                { label: '年度 OKR', value: 'YEAR' },
                { label: '季度 OKR', value: 'QUARTER' },
              ]}
            />
            <Select
              style={{ minWidth: 140 }}
              value={period || undefined}
              options={scope === 'YEAR' ? yearOptions : quarterOptions}
              onChange={setPeriod}
            />
          </Space>
        </div>
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            补充说明（可选，作为高优先级事实进入分析；如「明年重点是平台化」）
          </Typography.Text>
          <Input.TextArea
            style={{ marginTop: 4 }}
            rows={3}
            maxLength={500}
            value={hint}
            onChange={(e) => setHint(e.target.value)}
          />
        </div>
        <Checkbox checked={force} onChange={(e) => setForce(e.target.checked)}>
          强制重新生成（目标周期已有目标时仍会生成草稿；采纳草稿不影响已有目标）
        </Checkbox>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          生成依据：{scope === 'QUARTER' ? '上一季度的 OKR 进展、周报摘要与条目类型聚合' : '上一年度目标树、各季进展与季度复盘摘要'}。
          结果进草稿抽屉，人工确认采纳后才落库。
        </Typography.Text>
      </Space>
    </Modal>
  )
}
