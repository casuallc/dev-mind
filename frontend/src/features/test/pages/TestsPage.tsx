// 测试记录页（/tests）：当前项目的测试运行历史。
import { Card } from 'antd'
import TestTab from '../components/TestTab'
import { useCurrentProjectId } from '../../projects/hooks/useCurrentProject'

export default function TestsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return (
    <Card size="small" title="测试记录">
      <TestTab id={projectId} />
    </Card>
  )
}
