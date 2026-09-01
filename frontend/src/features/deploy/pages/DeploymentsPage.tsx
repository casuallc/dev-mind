// 部署记录页（/deployments）：当前项目的部署历史。
import { Card } from 'antd'
import DeployTab from '../components/DeployTab'
import { useCurrentProjectId } from '../../projects/hooks/useCurrentProject'

export default function DeploymentsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return (
    <Card size="small" title="部署记录">
      <DeployTab id={projectId} />
    </Card>
  )
}
