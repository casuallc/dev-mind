// 发版配置页（/admin/projects/:id/release）：薄壳，实现归 deploy 模块（发版归属交付域）。
// 这里只管配置；发版创建与历史在工作台 /releases。
import { useParams } from 'react-router-dom'
import ReleaseConfigForm from '../../../deploy/components/ReleaseConfigForm'

export default function ReleaseConfigPage() {
  const { id = '' } = useParams<{ id: string }>()
  return <ReleaseConfigForm id={id} />
}
