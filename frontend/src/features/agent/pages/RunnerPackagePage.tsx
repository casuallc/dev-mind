import { Card } from 'antd'
import RunnerPackagePanel from '../components/RunnerPackagePanel'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'

/** Runner 包独立页：薄壳承载 RunnerPackagePanel（服务端托管的 devmind-agent-runner.jar，全局单份）。 */
export default function RunnerPackagePage() {
  return (
    <Card style={pageCardStyle} styles={{ body: pageCardBodyFlexStyle }} title="Runner 包">
      <RunnerPackagePanel />
    </Card>
  )
}
