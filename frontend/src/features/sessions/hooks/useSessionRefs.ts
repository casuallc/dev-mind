// 会话关联研发主线信息解析：SessionSummary 只带 requirementId/workItemId（无 code/标题快照），
// 需求表一次加载建 id→Requirement 映射；工作单元按会话涉及的需求惰性拉取并缓存。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { listRequirements, listWorkItems } from '../../requirements/api'
import type { Requirement, WorkItem } from '../../requirements/types'

export interface SessionRefs {
  requirementOf: (id?: string) => Requirement | undefined
  workItemOf: (id?: string) => WorkItem | undefined
}

export function useSessionRefs(
  projectId: string | null | undefined,
  sessions: { requirementId?: string }[],
): SessionRefs {
  const [reqMap, setReqMap] = useState<Map<string, Requirement>>(new Map())
  const [wiMap, setWiMap] = useState<Map<string, WorkItem>>(new Map())
  // 已拉过工作单元的需求（含空结果），防 3s 轮询反复请求
  const loadedReqs = useRef<Set<string>>(new Set())

  useEffect(() => {
    setReqMap(new Map())
    setWiMap(new Map())
    loadedReqs.current.clear()
    if (!projectId) return
    // 全量映射（不按状态过滤：会话可关联已完结需求）；超出 size 的落到 id 原文兜底
    listRequirements(projectId, { size: 500 })
      .then((d) => setReqMap(new Map(d.items.map((r) => [r.id, r]))))
      .catch(() => undefined)
  }, [projectId])

  // 会话涉及的需求集合变化时，补拉未缓存的工作单元
  const reqIdsKey = sessions.map((s) => s.requirementId ?? '').sort().join(',')
  useEffect(() => {
    if (!projectId) return
    for (const rid of new Set(sessions.map((s) => s.requirementId).filter(Boolean) as string[])) {
      if (loadedReqs.current.has(rid)) continue
      loadedReqs.current.add(rid)
      listWorkItems(projectId, rid)
        .then((ws) =>
          setWiMap((prev) => {
            const next = new Map(prev)
            ws.forEach((w) => next.set(w.id, w))
            return next
          }),
        )
        .catch(() => loadedReqs.current.delete(rid))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, reqIdsKey])

  const requirementOf = useCallback((id?: string) => (id ? reqMap.get(id) : undefined), [reqMap])
  const workItemOf = useCallback((id?: string) => (id ? wiMap.get(id) : undefined), [wiMap])
  return useMemo(() => ({ requirementOf, workItemOf }), [requirementOf, workItemOf])
}
