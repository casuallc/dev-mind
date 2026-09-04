// 需求列表页（/requirements）：当前项目的需求研发主线。
// 布局遵循 docs/core/前端内容区布局约定.md，Card 外壳在 RequirementListCard 内。
import RequirementListCard from '../components/RequirementListCard'
import { useCurrentProjectId } from '../../../app/useCurrentProject'

export default function RequirementsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return <RequirementListCard projectId={projectId} />
}
