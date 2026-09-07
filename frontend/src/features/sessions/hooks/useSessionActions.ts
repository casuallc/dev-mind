// 会话操作 hook：结束/挂起/恢复/终止/Diff 的 handler 复用层，
// 从 SessionDetail 抽取，供 SessionsBoard 精简操作条与详情页共用。
// removeWorktree / 沉淀经验仅详情页需要，不在此抽取。
import { useCallback, useState } from 'react'
import { Modal, message } from 'antd'
import { finishSession, killSession, resumeSession, sessionDiff, suspendSession } from '../api'
import type { RepoDiffView, SessionSummary } from '../types'

export interface SessionActions {
  /** 结束会话：关 stdin，claude 自然退出 → DONE/FAILED */
  onFinish: () => Promise<void>
  onSuspend: () => Promise<void>
  onResume: () => Promise<void>
  /** 终止：弹确认后强杀进程 */
  onKill: () => void
  diff: {
    open: boolean
    loading: boolean
    /** CAP-31：按库分组的 diff 列表 */
    data: RepoDiffView[] | null
    show: () => Promise<void>
    close: () => void
  }
}

export function useSessionActions(
  id: string | undefined,
  onUpdated: (s?: SessionSummary) => void,
): SessionActions {
  const [diffOpen, setDiffOpen] = useState(false)
  const [diffLoading, setDiffLoading] = useState(false)
  const [diffData, setDiffData] = useState<RepoDiffView[] | null>(null)

  const doAction = useCallback(
    async (fn: () => Promise<SessionSummary>, successMsg: string) => {
      if (!id) return
      try {
        onUpdated(await fn())
        message.success(successMsg)
      } catch (e) {
        message.error(`操作失败：${(e as Error).message}`)
      }
    },
    [id, onUpdated],
  )

  const onFinish = useCallback(async () => {
    if (!id) return
    try {
      await finishSession(id)
      message.success('已结束会话，等待 claude 退出…')
      window.setTimeout(() => onUpdated(), 800)
    } catch (e) {
      message.error(`结束失败：${(e as Error).message}`)
    }
  }, [id, onUpdated])

  const onSuspend = useCallback(() => doAction(() => suspendSession(id!), '已挂起'), [doAction, id])
  const onResume = useCallback(() => doAction(() => resumeSession(id!), '已恢复'), [doAction, id])

  const onKill = useCallback(() => {
    if (!id) return
    Modal.confirm({
      centered: true,
      title: '终止该会话？',
      content: '将强制杀掉 claude 进程并标记为 TERMINATED。',
      okText: '终止',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => doAction(() => killSession(id), '已终止'),
    })
  }, [id, doAction])

  const showDiff = useCallback(async () => {
    if (!id) return
    setDiffLoading(true)
    try {
      setDiffData(await sessionDiff(id))
      setDiffOpen(true)
    } catch (e) {
      message.error(`获取 diff 失败：${(e as Error).message}`)
    } finally {
      setDiffLoading(false)
    }
  }, [id])

  return {
    onFinish,
    onSuspend,
    onResume,
    onKill,
    diff: { open: diffOpen, loading: diffLoading, data: diffData, show: showDiff, close: () => setDiffOpen(false) },
  }
}
