// 项目基本信息加载 hook：业务详情页与后台项目设置页共用。
import { useCallback, useEffect, useState } from 'react'
import { message } from 'antd'
import { getProject } from '../api'
import type { Project } from '../types'

export function useProject(id: string | undefined) {
  const [project, setProject] = useState<Project | null>(null)
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    if (!id) return
    try {
      setProject(await getProject(id))
    } catch (e) {
      message.error(`加载项目失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    setLoading(true)
    reload()
  }, [reload])

  return { project, setProject, loading, reload }
}
