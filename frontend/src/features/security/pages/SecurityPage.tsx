// 安全扫描中心（/security，CAP-66）：单 Card + Segmented 四视图
// 扫描任务 / 发现列表 / 抑制管理 / 扫描配置；extra 放刷新与「发起扫描」。
import { Button, Card, Segmented, Typography } from 'antd'
import { useCallback, useState } from 'react'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { usePermissions } from '../../auth/usePermissions'
import type { SecurityScan } from '../types'
import ScanListView from '../components/ScanListView'
import ScanDetailDrawer from '../components/ScanDetailDrawer'
import FindingListView from '../components/FindingListView'
import SuppressionView from '../components/SuppressionView'
import ConfigView from '../components/ConfigView'
import CreateScanModal from '../components/CreateScanModal'

export default function SecurityPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null
  return <SecurityCenter id={projectId} />
}

function SecurityCenter({ id }: { id: string }) {
  const { canAction } = usePermissions()
  const canScan = canAction('project.security.action.scan')
  const [view, setView] = useState<string>('scans')
  const [tick, setTick] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)
  const [detailScanId, setDetailScanId] = useState<number | null>(null)
  const [findingScanId, setFindingScanId] = useState<number | null>(null)

  const refresh = () => setTick((t) => t + 1)
  const onConsumeScan = useCallback(() => setFindingScanId(null), [])

  const openFindings = (s: SecurityScan) => {
    setFindingScanId(s.id)
    setView('findings')
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Segmented
          value={view}
          onChange={(v) => setView(v as string)}
          options={[
            { value: 'scans', label: '扫描任务' },
            { value: 'findings', label: '发现列表' },
            { value: 'suppressions', label: '抑制管理' },
            { value: 'config', label: '扫描配置' },
          ]}
        />
      }
      extra={
        <>
          <Button icon={<ReloadOutlined />} onClick={refresh}>
            刷新
          </Button>
          {canScan && (
            <Button type="primary" icon={<PlusOutlined />} style={{ marginLeft: 8 }} onClick={() => setCreateOpen(true)}>
              发起扫描
            </Button>
          )}
        </>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        多引擎安全扫描（SCA 依赖漏洞 / SAST 代码扫描 / 密钥泄漏），基线对比与误报治理，支持 EPSS·KEV 威胁情报与 AI 复核修复。
      </Typography.Paragraph>
      {view === 'scans' && (
        <ScanListView
          projectId={id}
          refreshTick={tick}
          onOpenDetail={setDetailScanId}
          onOpenFindings={openFindings}
        />
      )}
      {view === 'findings' && (
        <FindingListView projectId={id} refreshTick={tick} scanId={findingScanId} onScanConsumed={onConsumeScan} />
      )}
      {view === 'suppressions' && <SuppressionView projectId={id} refreshTick={tick} />}
      {view === 'config' && <ConfigView projectId={id} refreshTick={tick} />}

      <ScanDetailDrawer scanId={detailScanId} onClose={() => setDetailScanId(null)} />
      <CreateScanModal
        projectId={id}
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setView('scans')
          refresh()
        }}
      />
    </Card>
  )
}
