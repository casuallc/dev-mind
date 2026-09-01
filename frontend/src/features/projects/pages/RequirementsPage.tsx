// 需求列表页（/requirements）：当前项目的需求研发主线。
import { Card } from 'antd'
import RequirementListCard from '../components/RequirementListCard'
import { useCurrentProjectId } from '../hooks/useCurrentProject'

export default function RequirementsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return (
    <Card size="small" title="需求">
      <RequirementListCard projectId={projectId} />
    </Card>
  )
}
