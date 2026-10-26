import { Drawer, message } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import {
  createQuarterly,
  generateQuarterly,
  getQuarterly,
  reviewPreview,
  updateQuarterly,
} from '../api'
import type { QuarterlyReport } from '../types'
import ReportEditor from './ReportEditor'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

interface Props {
  /** 季度 period（'2026-Q1'）；null = 关闭 */
  period: string | null
  onCancel: () => void
}

/**
 * CAP-91 M2 季度复盘抽屉：复用 ReportEditor（单字段 contentMd）。
 * AI 生成走 worklog 会话链路（受理后轮询落库行）；「从数据重建」为确定性预览（不经 LLM，
 * 人工确认后填入编辑框，保存才落库，同 CAP-90 FR-10 哲学）。
 */
export default function QuarterlyReviewDrawer({ period, onCancel }: Props) {
  const [report, setReport] = useState<QuarterlyReport | undefined>()
  const [generating, setGenerating] = useState(false)

  const load = useCallback(() => {
    if (!period) return
    getQuarterly(period)
      .then(setReport)
      .catch((e) => showError(e, '加载季度复盘失败'))
  }, [period])
  useEffect(load, [load])

  const onGenerate = async (force: boolean, hint?: string) => {
    if (!period) return
    setGenerating(true)
    const prevUpdatedAt = report?.updatedAt
    try {
      const ack = await generateQuarterly(period, force, hint)
      if (ack.reused) {
        message.info('已存在该季度复盘，未重复生成（如需重生成请使用强制重新生成）')
        return
      }
      message.info(`生成会话已创建${ack.sessionId ? `（${ack.sessionId}）` : ''}，完成后自动刷新…`)
      for (let i = 0; i < 150; i++) {
        await sleep(2000)
        const r = await getQuarterly(period)
        if (r && (force ? r.updatedAt !== prevUpdatedAt : true)) {
          setReport(r)
          message.success('生成完成')
          return
        }
      }
      message.warning('生成超时或失败，失败原因与结果请查看通知中心')
    } catch (e) {
      showError(e, '生成失败')
    } finally {
      setGenerating(false)
      load()
    }
  }

  return (
    <Drawer
      title={`季度复盘（${period ?? ''}）`}
      open={period != null}
      onClose={onCancel}
      width={DRAWER_SCREEN}
      destroyOnHidden
    >
      {period && (
        <ReportEditor
          key={period}
          exists={report?.exists}
          updatedAt={report?.updatedAt}
          generating={generating}
          fields={[{ key: 'contentMd', label: `复盘内容（${period}，Markdown）`, value: report?.contentMd ?? '' }]}
          onCreateManual={async () => {
            setReport(await createQuarterly(period))
          }}
          onGenerate={onGenerate}
          rebuild={{
            fetch: () => reviewPreview(period),
            fieldKey: 'contentMd',
            label: '从数据重建',
            emptyText: '当季暂无目标与完成条目：无可重建数据',
            confirmTitle: `用 OKR 对照与计划外聚合重建「${period} 复盘」？`,
          }}
          onSave={async (values) => {
            if (report?.exists) {
              setReport(await updateQuarterly(period, { contentMd: values.contentMd }))
            }
          }}
        />
      )}
    </Drawer>
  )
}
