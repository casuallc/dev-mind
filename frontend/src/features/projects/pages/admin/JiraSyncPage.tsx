// Jira 同步页（/admin/projects/:id/jira）：薄壳，实现归 integrations 模块。
import { useParams } from 'react-router-dom'
import JiraSyncTab from '../../../integrations/components/JiraSyncTab'

export default function JiraSyncPage() {
  const { id = '' } = useParams<{ id: string }>()
  return <JiraSyncTab projectId={id} />
}
