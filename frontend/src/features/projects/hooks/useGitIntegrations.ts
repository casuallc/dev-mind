// CAP-23：可用的 git 平台集成实例（GITLAB/GITHUB 且 ENABLED），供克隆认证选择
import { useEffect, useState } from 'react'
import { listIntegrations } from '../../integrations/api'
import type { Integration } from '../../integrations/types'

export function useGitIntegrations() {
  const [options, setOptions] = useState<{ value: number; label: string }[]>([])
  const [integrations, setIntegrations] = useState<Integration[]>([])

  useEffect(() => {
    listIntegrations()
      .then((list) => {
        const git = list.filter((i) => (i.type === 'GITLAB' || i.type === 'GITHUB') && i.status === 'ENABLED')
        setIntegrations(git)
        setOptions(git.map((i) => ({ value: i.id, label: `${i.name}（${i.type} · ${i.baseUrl}）` })))
      })
      .catch(() => {
        // 集成列表加载失败不阻断表单（匿名克隆仍可用）
      })
  }, [])

  return { options, integrations }
}
