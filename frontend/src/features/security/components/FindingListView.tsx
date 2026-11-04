// 发现列表（FR-05）：扫描选择器 + 过滤条（引擎/状态/严重度/直接依赖/KEV/test scope/KEV 优先）
// + 全量加载 FitTable 客户端分页；行点击开详情抽屉。
import { Checkbox, Select, Space, Tag, Tooltip, Typography } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { listFindings, listScans } from '../api'
import type { FindingQuery } from '../api'
import type { SecurityFinding, SecurityScan } from '../types'
import FitTable, { noWrapColumns } from '../../../shared/components/FitTable'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { fmtTime } from '../../../shared/utils/format'
import { ENGINE_LABEL, FINDING_STATUS_COLOR, FINDING_STATUS_LABEL, SEVERITY_COLOR, parseDetail } from './common'
import FindingDrawer from './FindingDrawer'

interface Props {
  projectId: string
  refreshTick: number
  /** 从扫描任务「发现」按钮带过来的目标扫描 */
  scanId: number | null
  onScanConsumed: () => void
}

const STATUS_OPTIONS = [
  { value: 'NEW', label: '新增' },
  { value: 'EXISTING', label: '存量' },
  { value: 'SUPPRESSED', label: '已抑制' },
  { value: 'FIXED', label: '已修复' },
  { value: 'ALL', label: '全部' },
]

export default function FindingListView({ projectId, refreshTick, scanId, onScanConsumed }: Props) {
  const [scans, setScans] = useState<SecurityScan[]>([])
  const [currentScan, setCurrentScan] = useState<number | undefined>()
  const [engine, setEngine] = useState<string | undefined>()
  const [status, setStatus] = useState<string>('NEW')
  const [severity, setSeverity] = useState<string | undefined>()
  const [directOnly, setDirectOnly] = useState(false)
  const [kevOnly, setKevOnly] = useState(false)
  const [includeTestScope, setIncludeTestScope] = useState(false)
  const [kevFirst, setKevFirst] = useState(false)
  const [rows, setRows] = useState<SecurityFinding[]>([])
  const [loading, setLoading] = useState(false)
  const [openId, setOpenId] = useState<number | null>(null)

  // 加载本项目的成功扫描供选择（新→旧）
  useEffect(() => {
    listScans(projectId, 0, 50)
      .then((p) => {
        const ok = p.content.filter((s) => s.status === 'SUCCESS')
        setScans(ok)
        if (!scanId && ok.length > 0) setCurrentScan((cur) => cur ?? ok[0].id)
      })
      .catch(() => {})
  }, [projectId, refreshTick, scanId])

  // 外部（扫描任务行）指定扫描
  useEffect(() => {
    if (scanId) {
      setCurrentScan(scanId)
      onScanConsumed()
    }
  }, [scanId, onScanConsumed])

  const refresh = useCallback(() => {
    if (!currentScan) {
      setRows([])
      return
    }
    const q: FindingQuery = {
      engine,
      status,
      severity,
      directDep: directOnly || undefined,
      kev: kevOnly || undefined,
      includeTestScope: includeTestScope || undefined,
      kevFirst: kevFirst || undefined,
    }
    setLoading(true)
    listFindings(currentScan, q)
      .then(setRows)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [currentScan, engine, status, severity, directOnly, kevOnly, includeTestScope, kevFirst])

  useEffect(refresh, [refresh, refreshTick])

  const columns: ColumnsType<SecurityFinding> = noWrapColumns<SecurityFinding>([
    {
      title: '严重度',
      dataIndex: 'severity',
      width: 90,
      render: (v: SecurityFinding['severity']) => <Tag color={SEVERITY_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '引擎',
      dataIndex: 'engine',
      width: 110,
      render: (v: string) => ENGINE_LABEL[v] ?? v,
    },
    {
      title: '标题',
      dataIndex: 'title',
      ellipsis: true,
      render: (v: string, r) => {
        const dj = parseDetail(r.detailJson)
        return (
          <Space size={4}>
            <Typography.Text>{v}</Typography.Text>
            {dj.kev && (
              <Tooltip title="CISA KEV 在野利用">
                <Tag color="red">KEV</Tag>
              </Tooltip>
            )}
          </Space>
        )
      },
    },
    { title: '规则', dataIndex: 'ruleId', width: 180, ellipsis: true },
    {
      title: '位置/构件',
      width: 220,
      ellipsis: true,
      render: (_, r) => {
        const dj = parseDetail(r.detailJson)
        if (dj.groupId) return `${dj.groupId}:${dj.artifactId}${dj.depVersion ? `:${dj.depVersion}` : ''}`
        return r.filePath ? `${r.filePath}${r.lineNo ? `:${r.lineNo}` : ''}` : '-'
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: SecurityFinding['status']) => (
        <Tag color={FINDING_STATUS_COLOR[v]}>{FINDING_STATUS_LABEL[v]}</Tag>
      ),
    },
    { title: '发现时间', dataIndex: 'createdAt', width: 160, render: (v: string) => fmtTime(v) },
  ]) as ColumnsType<SecurityFinding>

  return (
    <>
      <Space size={12} wrap style={{ marginBottom: 12 }}>
        <Select
          style={{ minWidth: 260 }}
          placeholder="选择扫描"
          value={currentScan}
          onChange={setCurrentScan}
          options={scans.map((s) => ({
            value: s.id,
            label: `#${s.id} · ${s.branch} · ${fmtTime(s.createdAt)}（新增 ${s.newCount ?? 0}）`,
          }))}
        />
        <Select
          allowClear
          style={{ width: 140 }}
          placeholder="引擎"
          value={engine}
          onChange={setEngine}
          options={Object.entries(ENGINE_LABEL).map(([value, label]) => ({ value, label }))}
        />
        <Select style={{ width: 100 }} value={status} onChange={setStatus} options={STATUS_OPTIONS} />
        <Select
          allowClear
          style={{ width: 110 }}
          placeholder="严重度"
          value={severity}
          onChange={setSeverity}
          options={(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const).map((v) => ({ value: v, label: v }))}
        />
        <Checkbox checked={directOnly} onChange={(e) => setDirectOnly(e.target.checked)}>
          仅直接依赖
        </Checkbox>
        <Checkbox checked={kevOnly} onChange={(e) => setKevOnly(e.target.checked)}>
          仅 KEV
        </Checkbox>
        <Checkbox checked={includeTestScope} onChange={(e) => setIncludeTestScope(e.target.checked)}>
          含 test scope
        </Checkbox>
        <Checkbox checked={kevFirst} onChange={(e) => setKevFirst(e.target.checked)}>
          KEV 优先排序
        </Checkbox>
      </Space>
      <FitTable<SecurityFinding>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        loading={loading}
        pagination={LIST_PAGINATION}
        onRow={(r) => ({ onClick: () => setOpenId(r.id), style: { cursor: 'pointer' } })}
      />
      <FindingDrawer findingId={openId} onClose={() => setOpenId(null)} onChanged={refresh} />
    </>
  )
}
