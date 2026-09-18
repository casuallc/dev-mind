// 后台项目设置「Jira 推送」子页（CAP-47 FR-10）：薄壳，实现在 integrations 能力内。
import { useParams } from 'react-router-dom'
import JiraPushDefaultsTab from '../../../integrations/components/JiraPushDefaultsTab'

export default function JiraPushDefaultsPage() {
  const { id = '' } = useParams<{ id: string }>()
  return <JiraPushDefaultsTab projectId={id} />
}
