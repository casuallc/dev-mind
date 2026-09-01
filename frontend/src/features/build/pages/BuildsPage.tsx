// 构建记录页（/builds）：当前项目的构建历史。
import { Card } from 'antd'
import BuildCenterTab from '../components/BuildTab'
import { useCurrentProjectId } from '../../projects/hooks/useCurrentProject'

export default function BuildsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return (
    <Card size="small" title="构建记录">
      <BuildCenterTab id={projectId} />
    </Card>
  )
}
