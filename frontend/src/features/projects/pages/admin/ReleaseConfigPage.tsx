// 发版配置页（/admin/projects/:id/release）：薄壳，实现归 deploy 模块（发版归属交付域）。
import { useParams } from 'react-router-dom'
import ReleaseTab from '../../../deploy/components/ReleaseTab'

export default function ReleaseConfigPage() {
  const { id = '' } = useParams<{ id: string }>()
  return <ReleaseTab id={id} />
}
