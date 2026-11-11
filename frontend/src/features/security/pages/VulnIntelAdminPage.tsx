// 漏洞情报后台页（/admin/security/intel）：vuln_intel 全表浏览（CVE/描述关键字、KEV、EPSS 过滤 + 服务端分页排序）
// + 手工增删改 + 手动触发 EPSS/KEV/NVD 同步（异步跑，轮询 stats 的 running 标志判完成）。
// 元数据（描述/发布时间/最后修改/CVSS）来自 NVD 每日增量 + 展开行按需补拉（FR-10-5）。
// 布局遵循 docs/core/前端内容区布局约定.md：Card + extra 按钮 + FitTable 撑满。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  createIntel,
  deleteIntel,
  enrichIntel,
  intelStats,
  listIntel,
  syncIntelEpss,
  syncIntelKev,
  syncIntelNvd,
  updateIntel,
} from '../api'
import type { IntelUpsertInput } from '../api'
import type { VulnIntel, VulnIntelStats } from '../types'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { usePermissions } from '../../auth/usePermissions'

const SORT_OPTIONS = [
  { value: 'updatedAt', label: '最近更新' },
  { value: 'publishedAt', label: '发布时间' },
  { value: 'epssScore', label: 'EPSS 分数' },
  { value: 'epssPercentile', label: 'EPSS 百分位' },
  { value: 'cvssScore', label: 'CVSS 分数' },
  { value: 'cve', label: 'CVE 编号' },
]

const pct = (v: number | null) => (v == null ? '—' : `${(v * 100).toFixed(1)}%`)

const CVSS_COLORS: Record<string, string> = {
  CRITICAL: 'red',
  HIGH: 'volcano',
  MEDIUM: 'gold',
  LOW: 'green',
}

const cvssTag = (score: number | null, severity: string | null) => {
  if (score == null) return '—'
  const sev = (severity || '').toUpperCase()
  return (
    <Space size={4}>
      <span>{score.toFixed(1)}</span>
      {sev && <Tag color={CVSS_COLORS[sev] ?? 'default'}>{sev}</Tag>}
    </Space>
  )
}

export default function VulnIntelAdminPage() {
  const { canAction } = usePermissions()
  const canManage = canAction('admin.vuln-intel.action.manage')
  const canSync = canAction('admin.vuln-intel.action.sync')

  const [rows, setRows] = useState<VulnIntel[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [keyword, setKeyword] = useState<string>()
  const [kev, setKev] = useState<boolean>()
  const [epssMin, setEpssMin] = useState<number>()
  const [sort, setSort] = useState('updatedAt')
  const [loading, setLoading] = useState(false)
  const [stats, setStats] = useState<VulnIntelStats | null>(null)
  /** 正在按需补拉 NVD 元数据的 CVE 集（展开行自动触发 + 手动「从 NVD 刷新」共用；ref 做重入闸门） */
  const [enriching, setEnriching] = useState<ReadonlySet<string>>(new Set())
  const enrichingRef = useRef<Set<string>>(new Set())
  /** undefined=关闭；null=新增；否则编辑该行 */
  const [editing, setEditing] = useState<VulnIntel | null | undefined>(undefined)
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listIntel({ keyword, kev, epssMin, page, size, sort, dir: sort === 'cve' ? 'asc' : 'desc' })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      message.error(`加载情报列表失败：${e instanceof Error ? e.message : e}`)
    } finally {
      setLoading(false)
    }
  }, [keyword, kev, epssMin, page, size, sort])

  const loadStats = useCallback(() => {
    intelStats()
      .then(setStats)
      .catch(() => {})
  }, [])

  useEffect(() => { load() }, [load])
  useEffect(() => { loadStats() }, [loadStats])
  useEffect(() => () => { if (pollTimer.current) clearTimeout(pollTimer.current) }, [])

  /** 手动同步：启动后轮询 stats 的 running 标志，翻回 false 即完成 → 刷新列表与统计 */
  const startSync = async (kind: 'epss' | 'kev' | 'nvd') => {
    try {
      if (kind === 'epss') await syncIntelEpss()
      else if (kind === 'kev') await syncIntelKev()
      else await syncIntelNvd()
      const label = kind.toUpperCase()
      message.success(`${label} 同步已启动（需分钟级，完成后自动刷新）`)
      const poll = async () => {
        try {
          const s = await intelStats()
          setStats(s)
          if (s.epssSyncRunning || s.kevSyncRunning || s.nvdSyncRunning) {
            pollTimer.current = setTimeout(poll, 5000)
          } else {
            message.success('情报同步完成')
            load()
          }
        } catch {
          // 轮询失败（断网等）就停，用户可手动刷新
        }
      }
      pollTimer.current = setTimeout(poll, 3000)
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    }
  }

  /** 按需补拉/刷新单条 NVD 元数据；silent=展开行自动触发时静默失败（404=NVD 查无此 CVE） */
  const doEnrich = useCallback(async (r: VulnIntel, silent: boolean) => {
    if (enrichingRef.current.has(r.cve)) return
    enrichingRef.current.add(r.cve)
    setEnriching(new Set(enrichingRef.current))
    try {
      const updated = await enrichIntel(r.cve)
      setRows((prev) => prev.map((x) => (x.cve === updated.cve ? updated : x)))
      if (!silent) message.success(`已刷新 ${r.cve} 的 NVD 元数据`)
    } catch (e) {
      if (!silent) message.error(e instanceof Error ? e.message : String(e))
    } finally {
      enrichingRef.current.delete(r.cve)
      setEnriching(new Set(enrichingRef.current))
    }
  }, [])

  const onDelete = (r: VulnIntel) => {
    Modal.confirm({
      title: '删除情报记录',
      content: `删除 ${r.cve}？下次 EPSS 同步若该 CVE 仍在榜会被重新写入（KEV 标记同理，NVD 元数据随回灌补齐）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '再想想',
      onOk: async () => {
        await deleteIntel(r.cve)
        message.success('已删除')
        load()
        loadStats()
      },
    })
  }

  const columns: ColumnsType<VulnIntel> = [
    {
      title: 'CVE',
      dataIndex: 'cve',
      width: 170,
      render: (v: string) =>
        v.startsWith('CVE-') ? (
          <Typography.Link href={`https://nvd.nist.gov/vuln/detail/${v}`} target="_blank">
            {v}
          </Typography.Link>
        ) : (
          v
        ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: { showTitle: false },
      render: (v: string | null, r) =>
        v ? (
          <Typography.Text ellipsis={{ tooltip: v }} style={{ maxWidth: '100%' }}>
            {v}
          </Typography.Text>
        ) : enriching.has(r.cve) ? (
          <Typography.Text type="secondary">NVD 拉取中…</Typography.Text>
        ) : (
          '—'
        ),
    },
    { title: 'EPSS 分数', dataIndex: 'epssScore', width: 100, render: pct },
    { title: 'EPSS 百分位', dataIndex: 'epssPercentile', width: 100, render: pct },
    {
      title: 'CVSS',
      dataIndex: 'cvssScore',
      width: 110,
      render: (v: number | null, r) => cvssTag(v, r.cvssSeverity),
    },
    {
      title: 'KEV',
      dataIndex: 'kev',
      width: 170,
      render: (k: boolean, r) =>
        k ? (
          <Space size={4}>
            <Tooltip title="CISA 已知在野利用目录">
              <Tag color="red">KEV</Tag>
            </Tooltip>
            {r.kevDueDate && (
              <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                期限 {r.kevDueDate}
              </Typography.Text>
            )}
          </Space>
        ) : (
          '—'
        ),
    },
    { title: '发布时间', dataIndex: 'publishedAt', width: 160, render: (t) => (t ? fmtTime(t) : '—') },
    { title: '同步时间', dataIndex: 'updatedAt', width: 160, render: (t) => (t ? fmtTime(t) : '—') },
    ...(canManage
      ? [
          {
            title: '操作',
            key: 'ops',
            width: 110,
            render: (_: unknown, r: VulnIntel) => (
              <Space>
                <a onClick={() => setEditing(r)}>编辑</a>
                <a style={{ color: '#f5222d' }} onClick={() => onDelete(r)}>
                  删除
                </a>
              </Space>
            ),
          },
        ]
      : []),
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="漏洞情报"
      extra={
        <Space>
          {canSync && (
            <>
              <Button
                icon={<SyncOutlined spin={stats?.nvdSyncRunning} />}
                loading={stats?.nvdSyncRunning}
                onClick={() => startSync('nvd')}
              >
                同步 NVD
              </Button>
              <Button
                icon={<SyncOutlined spin={stats?.kevSyncRunning} />}
                loading={stats?.kevSyncRunning}
                onClick={() => startSync('kev')}
              >
                同步 KEV
              </Button>
              <Button
                icon={<SyncOutlined spin={stats?.epssSyncRunning} />}
                loading={stats?.epssSyncRunning}
                onClick={() => startSync('epss')}
              >
                同步 EPSS
              </Button>
            </>
          )}
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing(null)}>
              新增记录
            </Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={() => { load(); loadStats() }}>
            刷新
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        EPSS（FIRST.org 每日全量快照）、CISA KEV 在野利用目录与 NVD 元数据（描述/发布时间/最后修改/CVSS）
        的落库情报，供安全扫描富化（KEV 标/EPSS 分数/修复期限）。
        {stats && (
          <>
            {' '}当前共 <b>{stats.total}</b> 条，KEV 在榜 <b>{stats.kevCount}</b> 条
            {stats.lastUpdatedAt && <>，最近更新 {fmtTime(stats.lastUpdatedAt)}</>}。
          </>
        )}
        {' '}EPSS 每天 04:00、KEV 每天 04:30、NVD 每天 05:00 自动同步（NVD 走增量窗口 + 高分缺描述行回灌；
        展开行可按需即时补拉），手工增改的 EPSS/KEV 字段仅当日有效、会被次日同步覆写。
      </Typography.Paragraph>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minHeight: 0 }}>
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="CVE 编号 / 描述关键字"
            style={{ width: 240 }}
            onSearch={(v) => { setKeyword(v || undefined); setPage(0) }}
          />
          <Select
            allowClear
            placeholder="KEV"
            style={{ width: 120 }}
            value={kev === undefined ? undefined : kev ? 'yes' : 'no'}
            onChange={(v) => { setKev(v === undefined ? undefined : v === 'yes'); setPage(0) }}
            options={[
              { value: 'yes', label: '仅 KEV' },
              { value: 'no', label: '仅非 KEV' },
            ]}
          />
          <InputNumber
            placeholder="EPSS 最低分"
            min={0}
            max={1}
            step={0.1}
            style={{ width: 130 }}
            value={epssMin}
            onChange={(v) => { setEpssMin(v ?? undefined); setPage(0) }}
          />
          <Select
            value={sort}
            style={{ width: 130 }}
            onChange={(v) => { setSort(v); setPage(0) }}
            options={SORT_OPTIONS}
          />
        </Space>
        <FitTable
          rowKey="cve"
          loading={loading}
          columns={columns}
          dataSource={rows}
          expandable={{
            expandedRowRender: (r) => (
              <IntelDetail
                row={r}
                enriching={enriching.has(r.cve)}
                canSync={canSync}
                onEnrich={() => doEnrich(r, false)}
              />
            ),
            onExpand: (open, r) => {
              if (open && !r.description) void doEnrich(r, true)
            },
            rowExpandable: () => true,
          }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(s !== size ? 0 : p - 1)
              setSize(s)
            },
          }}
          locale={{
            emptyText: '情报表为空。点右上「同步 EPSS」/「同步 KEV」/「同步 NVD」立即拉取，或等每日凌晨自动同步。',
          }}
        />
      </div>
      <IntelEditDrawer
        editing={editing}
        onClose={(saved) => {
          setEditing(undefined)
          if (saved) {
            load()
            loadStats()
          }
        }}
      />
    </Card>
  )
}

/** 展开行详情：描述全文 + NVD 生命周期时间 + CVSS + 外链；描述缺失时可手动补拉 */
function IntelDetail({
  row,
  enriching,
  canSync,
  onEnrich,
}: {
  row: VulnIntel
  enriching: boolean
  canSync: boolean
  onEnrich: () => void
}) {
  return (
    <div style={{ padding: '4px 8px', maxWidth: 1100 }}>
      <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}>
        {row.description ?? (enriching ? '正在从 NVD 拉取元数据…' : '暂无描述。')}
      </Typography.Paragraph>
      <Descriptions
        size="small"
        column={4}
        items={[
          { key: 'pub', label: '发布时间', children: row.publishedAt ? fmtTime(row.publishedAt) : '—' },
          { key: 'mod', label: '最后修改', children: row.lastModifiedAt ? fmtTime(row.lastModifiedAt) : '—' },
          { key: 'cvss', label: 'CVSS', children: cvssTag(row.cvssScore, row.cvssSeverity) },
          {
            key: 'links',
            label: '外链',
            children: (
              <Space size={12} wrap>
                <Typography.Link href={`https://nvd.nist.gov/vuln/detail/${row.cve}`} target="_blank">
                  NVD
                </Typography.Link>
                <Typography.Link href={`https://www.cve.org/CVERecord?id=${row.cve}`} target="_blank">
                  CVE.org
                </Typography.Link>
                {row.kev && (
                  <Typography.Link
                    href="https://www.cisa.gov/known-exploited-vulnerabilities-catalog"
                    target="_blank"
                  >
                    CISA KEV
                  </Typography.Link>
                )}
                {canSync && (
                  <Typography.Link disabled={enriching} onClick={onEnrich}>
                    {enriching ? '拉取中…' : '从 NVD 刷新元数据'}
                  </Typography.Link>
                )}
              </Space>
            ),
          },
        ]}
      />
    </div>
  )
}

/** 新增/编辑抽屉：>4 字段按约定走 Drawer；手工改动次日同步会被覆写（KEV 退榜清标记、EPSS 全量覆盖） */
function IntelEditDrawer({
  editing,
  onClose,
}: {
  editing: VulnIntel | null | undefined
  onClose: (saved: boolean) => void
}) {
  const [form] = Form.useForm<IntelUpsertInput>()
  const [saving, setSaving] = useState(false)

  const save = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const input: IntelUpsertInput = {
        cve: values.cve,
        epssScore: values.epssScore ?? null,
        epssPercentile: values.epssPercentile ?? null,
        kev: !!values.kev,
        kevDueDate: values.kevDueDate || null,
      }
      if (editing) {
        await updateIntel(editing.cve, input)
        message.success('已保存')
      } else {
        await createIntel(input)
        message.success('已新增')
      }
      onClose(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Drawer
      title={editing ? `编辑情报：${editing.cve}` : '新增情报记录'}
      open={editing !== undefined}
      onClose={() => onClose(false)}
      width={DRAWER_DEFAULT}
      destroyOnHidden
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={() => onClose(false)}>取消</Button>
          <Button type="primary" loading={saving} onClick={save}>
            {editing ? '保存' : '新增'}
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        手工维护的记录会在次日自动同步时被覆写（EPSS 全量覆盖分数、KEV 退榜清标记），仅适合当日临时补录。
        NVD 元数据（描述/发布时间/CVSS）不由手工维护，展开行可即时补拉。
      </Typography.Paragraph>
      <Form
        form={form}
        layout="vertical"
        initialValues={
          editing
            ? {
                cve: editing.cve,
                epssScore: editing.epssScore,
                epssPercentile: editing.epssPercentile,
                kev: editing.kev,
                kevDueDate: editing.kevDueDate,
              }
            : { kev: false }
        }
      >
        <Form.Item
          name="cve"
          label="CVE 编号"
          rules={[{ required: true, message: '必填，如 CVE-2024-3094' }]}
        >
          <Input placeholder="CVE-2024-3094" disabled={!!editing} maxLength={32} />
        </Form.Item>
        <Form.Item name="epssScore" label="EPSS 分数（0~1）">
          <InputNumber min={0} max={1} step={0.001} style={{ width: 200 }} />
        </Form.Item>
        <Form.Item name="epssPercentile" label="EPSS 百分位（0~1）">
          <InputNumber min={0} max={1} step={0.001} style={{ width: 200 }} />
        </Form.Item>
        <Form.Item name="kev" label="CISA KEV 在野利用" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item name="kevDueDate" label="KEV 修复期限">
          <Input placeholder="YYYY-MM-DD" maxLength={16} style={{ width: 200 }} />
        </Form.Item>
      </Form>
    </Drawer>
  )
}
