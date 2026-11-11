// 漏洞情报后台页（/admin/security/intel）：vuln_intel 全表浏览（CVE 关键字/KEV/EPSS 过滤 + 服务端分页排序）
// + 手工增删改 + 手动触发 EPSS/KEV 同步（异步跑，轮询 stats 的 running 标志判完成）。
// 布局遵循 docs/core/前端内容区布局约定.md：Card + extra 按钮 + FitTable 撑满。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Button,
  Card,
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
  intelStats,
  listIntel,
  syncIntelEpss,
  syncIntelKev,
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
  { value: 'epssScore', label: 'EPSS 分数' },
  { value: 'epssPercentile', label: 'EPSS 百分位' },
  { value: 'cve', label: 'CVE 编号' },
]

const pct = (v: number | null) => (v == null ? '—' : `${(v * 100).toFixed(1)}%`)

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
  const startSync = async (kind: 'epss' | 'kev') => {
    try {
      if (kind === 'epss') await syncIntelEpss()
      else await syncIntelKev()
      message.success(`${kind === 'epss' ? 'EPSS' : 'KEV'} 同步已启动（全量下载需分钟级，完成后自动刷新）`)
      const poll = async () => {
        try {
          const s = await intelStats()
          setStats(s)
          if (s.epssSyncRunning || s.kevSyncRunning) {
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

  const onDelete = (r: VulnIntel) => {
    Modal.confirm({
      title: '删除情报记录',
      content: `删除 ${r.cve}？下次 EPSS 同步若该 CVE 仍在榜会被重新写入（KEV 标记同理）。`,
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
      width: 190,
      render: (v: string) =>
        v.startsWith('CVE-') ? (
          <Typography.Link href={`https://nvd.nist.gov/vuln/detail/${v}`} target="_blank">
            {v}
          </Typography.Link>
        ) : (
          v
        ),
    },
    { title: 'EPSS 分数', dataIndex: 'epssScore', width: 110, render: pct },
    { title: 'EPSS 百分位', dataIndex: 'epssPercentile', width: 110, render: pct },
    {
      title: 'KEV',
      dataIndex: 'kev',
      width: 190,
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
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (t) => (t ? fmtTime(t) : '—') },
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
        EPSS（FIRST.org 每日全量快照）与 CISA KEV 在野利用目录的落库情报，供安全扫描富化（KEV 标/EPSS 分数/修复期限）。
        {stats && (
          <>
            {' '}当前共 <b>{stats.total}</b> 条，KEV 在榜 <b>{stats.kevCount}</b> 条
            {stats.lastUpdatedAt && <>，最近更新 {fmtTime(stats.lastUpdatedAt)}</>}。
          </>
        )}
        {' '}EPSS 每天 04:00、KEV 每天 04:30 自动同步（分数变化超阈值才落库），手工增改仅当日有效、会被次日同步覆写。
      </Typography.Paragraph>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minHeight: 0 }}>
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="CVE 编号关键字"
            style={{ width: 220 }}
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
            emptyText: '情报表为空。点右上「同步 EPSS」/「同步 KEV」立即拉取，或等每日凌晨自动同步。',
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
