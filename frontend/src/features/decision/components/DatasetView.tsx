// CAP-56 FR-02 评测集视图：列表 / 新建 / 手工标注样本（含对照组模板预填）/ 从决策记录收编 /
// 冻结（对照组缺一不可）/ 修订为新版本。
//
// 冻结是本视图的中心：冻结前一切可改；冻结后评测才能引用它（未冻结的集发起评测必 400），
// 而要再改就得「修订为新版本」——所以冻结集上的所有写操作按钮一律置灰并说明原因，
// 不让用户点了才发现 409。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  DatePicker,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ButtonProps } from 'antd'
import { EditOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import {
  createDataset,
  createDatasetItem,
  deleteDataset,
  deleteDatasetItem,
  freezeDataset,
  getDataset,
  getDatasetItem,
  listCaseTemplates,
  listDatasetItems,
  listDatasets,
  previewIntake,
  reviseDataset,
  runIntake,
  updateDatasetItem,
} from '../api'
import type {
  CaseGroupTemplate,
  DatasetDetail,
  DatasetItemRequest,
  DatasetItemView,
  DatasetView as DatasetRow,
  RecordsIntakeResult,
  RecordsPreview,
} from '../types'
import type { DecisionQuestion } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import FitTable from '../../../shared/components/FitTable'
import { CASE_GROUP_LABEL, CaseGroupTag, CONTROL_GROUPS, SKIP_REASON_LABEL, SOURCE_LABEL } from './labCommon'
import { JsonBlock } from './ReportBlocks'

const KIND_OPTIONS = [
  { value: 'BENCHMARK', label: '基准集·人工标注' },
  { value: 'REPLAY', label: '回流集·决策记录' },
]

const CASE_GROUP_OPTIONS = (['NORMAL', ...CONTROL_GROUPS] as const).map((g) => ({
  value: g,
  label: CASE_GROUP_LABEL[g],
}))

/** 带说明的按钮：**灰按钮的 Tooltip 必须套一层 span**——disabled 的 button 不派发鼠标事件，
 *  Tooltip 拿不到目标就不会弹（用户只能看到一个不知道为什么是灰的按钮）。 */
function TipButton({ tip, ...rest }: { tip?: string } & ButtonProps) {
  const btn = <Button {...rest} />
  if (!tip) return btn
  return (
    <Tooltip title={tip}>
      <span style={{ display: 'inline-block' }}>{btn}</span>
    </Tooltip>
  )
}

export default function DatasetView({ refreshTick = 0, createTick = 0 }: { refreshTick?: number; createTick?: number }) {
  const [rows, setRows] = useState<DatasetRow[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [kind, setKind] = useState<string>()
  const [loading, setLoading] = useState(false)
  const [openId, setOpenId] = useState<number | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm<{ name: string; kind: string; note?: string }>()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listDatasets({ kind, page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载评测集失败')
    } finally {
      setLoading(false)
    }
  }, [kind, page, size])

  useEffect(() => {
    load()
  }, [load, refreshTick])

  // 外壳 extra「新建评测集」经 createTick 触发；挂载首帧不弹（切回视图时不应自动弹窗）
  const firstCreate = useRef(true)
  useEffect(() => {
    if (firstCreate.current) {
      firstCreate.current = false
      return
    }
    if (createTick > 0) {
      form.resetFields()
      form.setFieldsValue({ kind: 'BENCHMARK' })
      setCreateOpen(true)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createTick])

  const onCreate = async () => {
    const v = await form.validateFields()
    try {
      const created = await createDataset(v)
      message.success(`已创建「${created.dataset.name} v${created.dataset.version}」`)
      setCreateOpen(false)
      await load()
      setOpenId(created.dataset.id)
    } catch (e) {
      showError(e, '创建评测集失败')
    }
  }

  const columns: ColumnsType<DatasetRow> = [
    { title: '名称', dataIndex: 'name', ellipsis: true },
    {
      title: '类型',
      dataIndex: 'kindLabel',
      width: 150,
      render: (v: string, r) => <Tag color={r.kind === 'BENCHMARK' ? 'blue' : 'purple'}>{v}</Tag>,
    },
    { title: '版本', dataIndex: 'version', width: 70, render: (v: number) => `v${v}` },
    {
      title: '状态',
      width: 130,
      render: (_, r) =>
        r.frozen ? (
          <Tooltip title={`${r.frozenBy ?? '—'} 于 ${fmtTime(r.frozenAt)}`}>
            <Tag color="green">已冻结</Tag>
          </Tooltip>
        ) : (
          <Tag>草稿</Tag>
        ),
    },
    { title: '样本', dataIndex: 'itemCount', width: 70 },
    {
      title: '题面版本',
      dataIndex: 'questionSetVersion',
      width: 170,
      render: (v: string | null) => v ?? <Typography.Text type="secondary">未冻结</Typography.Text>,
    },
    { title: '创建', dataIndex: 'createdAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 90,
      render: (_, r) => <Button type="link" onClick={() => setOpenId(r.id)}>打开</Button>,
    },
  ]

  return (
    <>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="类型：全部"
          style={{ width: 200 }}
          value={kind}
          onChange={(v) => {
            setKind(v)
            setPage(0)
          }}
          options={KIND_OPTIONS}
        />
      </Space>
      <FitTable
        rowKey="id"
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
          emptyText:
            '还没有评测集——「新建评测集」建一个基准集并手工标注样本，或建回流集把已裁决的决策记录收编进来。',
        }}
      />

      <Modal
        title="新建评测集"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={onCreate}
        okText="创建"
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="name"
            label="名称"
            rules={[{ required: true, message: '名称必填' }]}
            extra="名称是修订链的主键：建好后不可改名，要改内容请用「修订为新版本」"
          >
            <Input placeholder="如 分诊基准集-60" />
          </Form.Item>
          <Form.Item name="kind" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Radio.Group options={KIND_OPTIONS} />
          </Form.Item>
          <Form.Item name="note" label="备注">
            <Input.TextArea rows={2} placeholder="选填" />
          </Form.Item>
        </Form>
      </Modal>

      {openId != null && <DatasetDrawer id={openId} onClose={() => setOpenId(null)} onChanged={load} />}
    </>
  )
}

// ---------------- 评测集详情抽屉 ----------------

function DatasetDrawer({ id, onClose, onChanged }: { id: number; onClose: () => void; onChanged: () => void }) {
  const [detail, setDetail] = useState<DatasetDetail | null>(null)
  const [templates, setTemplates] = useState<CaseGroupTemplate[]>([])
  const [items, setItems] = useState<DatasetItemView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [group, setGroup] = useState<string>()
  const [loadingItems, setLoadingItems] = useState(false)
  const [editing, setEditing] = useState<{ itemId: number | null; caseGroup: string } | null>(null)
  const [intakeOpen, setIntakeOpen] = useState(false)

  const reload = useCallback(async () => {
    try {
      setDetail(await getDataset(id))
    } catch (e) {
      showError(e, '加载评测集失败')
    }
  }, [id])

  const loadItems = useCallback(async () => {
    setLoadingItems(true)
    try {
      const r = await listDatasetItems(id, { caseGroup: group, page, size })
      setItems(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载样本失败')
    } finally {
      setLoadingItems(false)
    }
  }, [id, group, page, size])

  useEffect(() => {
    reload()
    listCaseTemplates()
      .then(setTemplates)
      .catch(() => setTemplates([]))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  useEffect(() => {
    loadItems()
  }, [loadItems])

  const frozen = detail?.dataset.frozen ?? false
  const ds = detail?.dataset

  const refreshAll = async () => {
    await Promise.all([reload(), loadItems()])
    onChanged()
  }

  const onFreeze = async () => {
    try {
      const d = await freezeDataset(id)
      message.success(`已冻结「${d.dataset.name} v${d.dataset.version}」（题面版本 ${d.dataset.questionSetVersion}）`)
      await refreshAll()
    } catch (e) {
      showError(e, '冻结失败')
    }
  }

  const onRevise = async () => {
    try {
      const d = await reviseDataset(id)
      message.success(`已派生新版本 v${d.dataset.version}`)
      setPage(0)
      await refreshAll()
    } catch (e) {
      showError(e, '修订失败')
    }
  }

  const onDelete = async () => {
    try {
      await deleteDataset(id)
      message.success('已删除评测集')
      onChanged()
      onClose()
    } catch (e) {
      showError(e, '删除失败')
    }
  }

  const onDeleteItem = async (itemId: number) => {
    try {
      await deleteDatasetItem(id, itemId)
      message.success('已删除样本')
      await refreshAll()
    } catch (e) {
      showError(e, '删除样本失败')
    }
  }

  const frozenTip = frozen ? '评测集已冻结，不可修改——要改请用「修订为新版本」' : ''

  const itemColumns: ColumnsType<DatasetItemView> = [
    { title: '组', dataIndex: 'caseGroup', width: 140, render: (g: string) => <CaseGroupTag value={g} /> },
    {
      title: '来源',
      dataIndex: 'source',
      width: 100,
      render: (s: string, r) => (
        <Tooltip title={r.originRecordId ? `收编自决策记录 #${r.originRecordId}` : undefined}>
          {SOURCE_LABEL[s] ?? s}
        </Tooltip>
      ),
    },
    {
      title: '内容',
      dataIndex: 'title',
      ellipsis: true,
      render: (t: string, r) =>
        r.caseGroupIssue ? (
          <Space size={6}>
            <Tooltip title={r.caseGroupIssue}>
              <Tag color="red">标签与内容不符</Tag>
            </Tooltip>
            <span>{t}</span>
          </Space>
        ) : (
          t
        ),
    },
    {
      title: '已标题',
      dataIndex: 'annotatedQuestions',
      width: 180,
      render: (q: string[]) => (q?.length ? q.join('、') : <Typography.Text type="secondary">未标注</Typography.Text>),
    },
    { title: '添加', dataIndex: 'createdAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 120,
      render: (_, r) => (
        <Space size={0}>
          <TipButton
            type="link"
            tip={frozen ? frozenTip : undefined}
            disabled={frozen}
            onClick={() => setEditing({ itemId: r.id, caseGroup: r.caseGroup })}
          >
            打开
          </TipButton>
          <Popconfirm title="删除这条样本？" onConfirm={() => onDeleteItem(r.id)} disabled={frozen}>
            <TipButton type="link" danger tip={frozen ? frozenTip : undefined} disabled={frozen}>
              删除
            </TipButton>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <>
      <Drawer
        title={ds ? `评测集「${ds.name}」v${ds.version}` : '评测集'}
        width={1000}
        open
        onClose={onClose}
        extra={
          <Space>
            <TipButton
              icon={<EditOutlined />}
              tip={frozen ? frozenTip : undefined}
              disabled={frozen}
              onClick={() => setEditing({ itemId: null, caseGroup: 'NORMAL' })}
            >
              添加样本
            </TipButton>
            <TipButton
              tip={frozen ? frozenTip : ds?.kind !== 'REPLAY' ? '只有回流集（kind=REPLAY）能收编决策记录' : undefined}
              disabled={frozen || ds?.kind !== 'REPLAY'}
              onClick={() => setIntakeOpen(true)}
            >
              收编决策记录
            </TipButton>
            {ds && !frozen && (
              <Popconfirm
                title="冻结这个评测集？"
                description="冻结后不可修改，评测才能引用它。缺对照组、标签与内容不符、题面版本不一致都会被拒（并逐条给原因）。"
                onConfirm={onFreeze}
              >
                <Button type="primary">冻结</Button>
              </Popconfirm>
            )}
            {ds?.frozen && (
              <Popconfirm title="修订为新版本？" description="从当前冻结版本派生下一个草稿，冻结版本本身不动。" onConfirm={onRevise}>
                <Button type="primary">修订为新版本</Button>
              </Popconfirm>
            )}
            <Popconfirm
              title="删除这个评测集？"
              description="样本与冻结版本一并删除，已引用它的评测运行会失去数据集主体。"
              onConfirm={onDelete}
            >
              <TipButton danger tip={frozen ? frozenTip : undefined} disabled={frozen}>
                删除
              </TipButton>
            </Popconfirm>
          </Space>
        }
      >
        {ds && (
          <>
            <Descriptions size="small" column={3} style={{ marginBottom: 12 }}>
              <Descriptions.Item label="类型">
                <Tag color={ds.kind === 'BENCHMARK' ? 'blue' : 'purple'}>{ds.kindLabel}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="样本数">{ds.itemCount}</Descriptions.Item>
              <Descriptions.Item label="题面版本">{ds.questionSetVersion ?? '未冻结'}</Descriptions.Item>
              <Descriptions.Item label="创建">
                {ds.createdBy ?? '—'} · {fmtTime(ds.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="冻结" span={2}>
                {ds.frozen ? `${ds.frozenBy ?? '—'} · ${fmtTime(ds.frozenAt)}` : '草稿（可自由增删样本）'}
              </Descriptions.Item>
              {ds.note && <Descriptions.Item label="备注" span={3}>{ds.note}</Descriptions.Item>}
            </Descriptions>

            <Space size={8} wrap style={{ marginBottom: 12 }}>
              <Typography.Text strong>对照组</Typography.Text>
              {(['NORMAL', ...CONTROL_GROUPS] as const).map((g) => {
                const n = detail?.caseGroupCounts?.[g] ?? 0
                const missing = g !== 'NORMAL' && n === 0
                return (
                  <Tag key={g} color={missing ? 'red' : n > 0 ? 'green' : 'default'}>
                    {CASE_GROUP_LABEL[g]}：{missing ? '缺（冻结会被拒）' : n}
                  </Tag>
                )
              })}
            </Space>

            {detail?.warnings?.length ? (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 12 }}
                message="冻结前提醒"
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {detail.warnings.map((w) => (
                      <li key={w}>{w}</li>
                    ))}
                  </ul>
                }
              />
            ) : null}

            {detail?.coverage?.length ? (
              <div style={{ marginBottom: 12 }}>
                <Typography.Text strong>题面覆盖</Typography.Text>
                <Space size={12} wrap style={{ marginLeft: 12 }}>
                  {detail.coverage.map((c) => (
                    <Typography.Text key={c.questionId} type={c.annotated > 0 ? undefined : 'danger'}>
                      {c.questionId}（{c.type}）：{c.annotated} 条
                    </Typography.Text>
                  ))}
                </Space>
              </div>
            ) : null}

            <Space style={{ marginBottom: 8 }} wrap>
              <Select
                allowClear
                placeholder="分组：全部"
                style={{ width: 180 }}
                value={group}
                onChange={(v) => {
                  setGroup(v)
                  setPage(0)
                }}
                options={CASE_GROUP_OPTIONS}
              />
              <Button icon={<ReloadOutlined />} onClick={refreshAll}>
                刷新
              </Button>
            </Space>

            <Table<DatasetItemView>
              rowKey="id"
              size="small"
              loading={loadingItems}
              columns={itemColumns}
              dataSource={items}
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
                size: 'small',
              }}
              locale={{
                emptyText:
                  ds.kind === 'REPLAY'
                    ? '还没有样本——用「收编决策记录」把已裁决的记录转成样本（只收 gold 落得上题面的）。'
                    : '还没有样本——用「添加样本」按对照组模板逐条标注（三个对照组缺一不可）。',
              }}
            />

            {Object.keys(detail?.manifest ?? {}).length > 0 && (
              <>
                <Typography.Text strong>冻结快照（manifest）</Typography.Text>
                <JsonBlock value={detail?.manifest} />
              </>
            )}
          </>
        )}
      </Drawer>

      {editing && detail && (
        <ItemEditor
          datasetId={id}
          itemId={editing.itemId}
          defaultGroup={editing.caseGroup}
          templates={templates}
          frozen={frozen}
          onClose={() => setEditing(null)}
          onSaved={refreshAll}
        />
      )}

      {intakeOpen && (
        <IntakeModal
          datasetId={id}
          onClose={() => setIntakeOpen(false)}
          onDone={async () => {
            setIntakeOpen(false)
            setPage(0)
            await refreshAll()
          }}
        />
      )}
    </>
  )
}

// ---------------- 样本编辑（新增 / 修改） ----------------

/** 由题面派生 gold 骨架：choice 取第一个选项、score 取最高等级、noul 取 false（都要人工确认） */
function goldSkeleton(questions: Record<string, DecisionQuestion>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  Object.entries(questions ?? {}).forEach(([id, q]) => {
    const c = q?.criteria
    if (q?.type === 'choice' && c && !Array.isArray(c)) out[id] = Object.keys(c)[0]
    else if (q?.type === 'score' && Array.isArray(c)) out[id] = c.length - 1
    else if (q?.type === 'noul') out[id] = false
    else out[id] = null
  })
  return out
}

function ItemEditor({
  datasetId,
  itemId,
  defaultGroup,
  templates,
  frozen,
  onClose,
  onSaved,
}: {
  datasetId: number
  itemId: number | null
  defaultGroup: string
  templates: CaseGroupTemplate[]
  frozen: boolean
  onClose: () => void
  onSaved: () => Promise<void> | void
}) {
  const [caseGroup, setCaseGroup] = useState(defaultGroup)
  const [note, setNote] = useState('')
  const [stateText, setStateText] = useState('{}')
  const [questionsText, setQuestionsText] = useState('{}')
  const [goldText, setGoldText] = useState('{}')
  const [extra, setExtra] = useState<{ goldNotLanded: string[]; distributions: Record<string, unknown> } | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (itemId == null) return
    getDatasetItem(datasetId, itemId)
      .then((d) => {
        setCaseGroup(d.item.caseGroup)
        setNote(d.item.note ?? '')
        setStateText(JSON.stringify(d.state ?? {}, null, 2))
        setQuestionsText(JSON.stringify(d.questions ?? {}, null, 2))
        setGoldText(JSON.stringify(d.gold ?? {}, null, 2))
        setExtra({ goldNotLanded: d.goldNotLanded ?? [], distributions: d.distributions ?? {} })
      })
      .catch((e) => showError(e, '加载样本失败'))
  }, [datasetId, itemId])

  const applyTemplate = (g: string) => {
    setCaseGroup(g)
    const t = templates.find((x) => x.caseGroup === g)
    if (!t) return
    setStateText(JSON.stringify(t.state ?? {}, null, 2))
    setQuestionsText(JSON.stringify(t.questions ?? {}, null, 2))
    setGoldText(JSON.stringify(t.gold ?? {}, null, 2))
  }

  /** 只取标准题面 + gold 骨架（普通样本没有模板，题面仍须与标准题面一致，否则后端逐字拒绝） */
  const applyQuestionSkeleton = () => {
    const t = templates[0]
    if (!t) {
      message.warning('对照组模板没加载出来，无法取标准题面：请手工填写')
      return
    }
    setQuestionsText(JSON.stringify(t.questions ?? {}, null, 2))
    setGoldText(JSON.stringify(goldSkeleton(t.questions ?? {}), null, 2))
    message.info('已填入标准题面与 gold 骨架：请按本样本实际情况改 gold 值')
  }

  const parse = (text: string, what: string): Record<string, unknown> | null => {
    try {
      const v = JSON.parse(text || '{}')
      if (v == null || typeof v !== 'object' || Array.isArray(v)) {
        message.error(`${what} 必须是一个 JSON 对象`)
        return null
      }
      return v as Record<string, unknown>
    } catch (e) {
      message.error(`${what} 不是合法 JSON：${e instanceof Error ? e.message : String(e)}`)
      return null
    }
  }

  const onSave = async () => {
    const state = parse(stateText, 'state')
    const questions = parse(questionsText, 'questions')
    const gold = parse(goldText, 'gold')
    if (!state || !questions || !gold) return
    if (!Object.keys(state).length || !Object.keys(gold).length) {
      message.error('state 与 gold 都不能为空（gold 至少标一题，否则这条样本没有判分依据）')
      return
    }
    const body: DatasetItemRequest = { state, questions, gold, caseGroup, note: note || undefined }
    setSaving(true)
    try {
      if (itemId == null) await createDatasetItem(datasetId, body)
      else await updateDatasetItem(datasetId, itemId, body)
      message.success(itemId == null ? '已添加样本' : '已保存样本')
      await onSaved()
      onClose()
    } catch (e) {
      showError(e, '保存样本失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      title={itemId == null ? '添加样本' : `样本 #${itemId}`}
      open
      width={860}
      onCancel={onClose}
      onOk={onSave}
      okText="保存"
      confirmLoading={saving}
      okButtonProps={{ disabled: frozen }}
    >
      <Form layout="vertical">
        <Space size={12} wrap style={{ marginBottom: 4 }}>
          <Form.Item label="对照组" style={{ marginBottom: 8 }}>
            <Select
              style={{ width: 200 }}
              value={caseGroup}
              onChange={applyTemplate}
              options={CASE_GROUP_OPTIONS.map((o) => {
                const t = templates.find((x) => x.caseGroup === o.value)
                return { ...o, title: t?.hint }
              })}
            />
          </Form.Item>
          {templates.length > 0 && (
            <Button onClick={applyQuestionSkeleton}>取标准题面 + gold 骨架</Button>
          )}
        </Space>
        <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
          {templates.find((t) => t.caseGroup === caseGroup)?.hint ??
            '普通样本：题面必须与标准题面逐字一致（否则后端拒绝），gold 按本样本人工裁决填。'}
        </Typography.Paragraph>
        <Form.Item label="state（发给模型的输入快照）" style={{ marginBottom: 12 }}>
          <Input.TextArea rows={6} value={stateText} onChange={(e) => setStateText(e.target.value)} />
        </Form.Item>
        <Form.Item label="questions（题面）" style={{ marginBottom: 12 }}>
          <Input.TextArea rows={6} value={questionsText} onChange={(e) => setQuestionsText(e.target.value)} />
        </Form.Item>
        <Form.Item label="gold（人工裁决，判分依据）" style={{ marginBottom: 12 }}>
          <Input.TextArea rows={4} value={goldText} onChange={(e) => setGoldText(e.target.value)} />
        </Form.Item>
        <Form.Item label="备注" style={{ marginBottom: 0 }}>
          <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="选填" />
        </Form.Item>
        {extra?.goldNotLanded?.length ? (
          <Alert
            type="error"
            showIcon
            style={{ marginTop: 12 }}
            message={`gold 里这些题落不上题面（等于没标）：${extra.goldNotLanded.join('、')}`}
          />
        ) : null}
      </Form>
    </Modal>
  )
}

// ---------------- 从决策记录收编 ----------------

function IntakeModal({
  datasetId,
  onClose,
  onDone,
}: {
  datasetId: number
  onClose: () => void
  onDone: () => Promise<void> | void
}) {
  const [capability, setCapability] = useState('kb-proposal-triage')
  const [since, setSince] = useState('')
  const [sampleLimit, setSampleLimit] = useState(50)
  const [preview, setPreview] = useState<RecordsPreview | null>(null)
  const [result, setResult] = useState<RecordsIntakeResult | null>(null)
  const [busy, setBusy] = useState(false)

  const params = () => ({
    capability: capability.trim() || undefined,
    since: since || undefined,
  })

  const onPreview = async () => {
    setBusy(true)
    try {
      setPreview(await previewIntake(datasetId, { ...params(), sampleLimit }))
    } catch (e) {
      showError(e, '预览收编失败')
    } finally {
      setBusy(false)
    }
  }

  const onIntake = async () => {
    setBusy(true)
    try {
      const r = await runIntake(datasetId, params())
      setResult(r)
      message.success(`已收编 ${r.added} 条，跳过 ${r.skipped} 条`)
      await onDone()
    } catch (e) {
      showError(e, '收编失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title="从决策记录收编样本"
      open
      width={1000}
      onCancel={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>关闭</Button>
          <Button onClick={onPreview} loading={busy}>
            预览
          </Button>
          <Popconfirm
            title="确认收编？"
            description="可收编的记录会作为新样本写入本集（已收编过的不重复）。"
            onConfirm={onIntake}
            disabled={!preview || preview.collectable === 0}
          >
            <Button type="primary" loading={busy} disabled={!preview || preview.collectable === 0}>
              收编 {preview ? preview.collectable : 0} 条
            </Button>
          </Popconfirm>
        </Space>
      }
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <Input
          style={{ width: 240 }}
          placeholder="能力（如 kb-proposal-triage）"
          value={capability}
          onChange={(e) => setCapability(e.target.value)}
        />
        <DatePicker
          allowClear
          placeholder="裁决时间不早于"
          value={since ? dayjs(since) : null}
          onChange={(d) => setSince(d ? d.format('YYYY-MM-DD') : '')}
        />
        <InputNumber
          min={1}
          max={500}
          value={sampleLimit}
          onChange={(v) => setSampleLimit(v ?? 50)}
          addonBefore="预览条数"
        />
      </Space>

      {preview && (
        <>
          <Descriptions size="small" column={4} style={{ marginBottom: 8 }}>
            <Descriptions.Item label="命中记录">{preview.total}</Descriptions.Item>
            <Descriptions.Item label="已扫描">{preview.scanned}</Descriptions.Item>
            <Descriptions.Item label="可收编">{preview.collectable}</Descriptions.Item>
            <Descriptions.Item label="收编后分组">
              {Object.entries(preview.caseGroups ?? {})
                .map(([g, n]) => `${CASE_GROUP_LABEL[g] ?? g}×${n}`)
                .join('、') || '—'}
            </Descriptions.Item>
          </Descriptions>
          {preview.truncated && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 8 }}
              message="扫描到上限就停了：请按能力或时间收窄范围，否则一部分记录不会被看到"
            />
          )}
          <Table
            rowKey={(r) => `${r.outcome}-${r.recordId}`}
            size="small"
            style={{ marginBottom: 12 }}
            dataSource={preview.samples}
            pagination={LIST_PAGINATION}
            columns={[
              { title: '记录', dataIndex: 'recordId', width: 80 },
              { title: '内容', dataIndex: 'title', ellipsis: true },
              {
                title: '分组',
                dataIndex: 'caseGroup',
                width: 140,
                render: (g: string) => <CaseGroupTag value={g} />,
              },
              { title: '人工动作', dataIndex: 'humanAction', width: 110, render: (v: string | null) => v ?? '—' },
              {
                title: '结论',
                dataIndex: 'outcome',
                width: 260,
                render: (o: string, r) =>
                  o === 'COLLECT' ? (
                    <Tag color="green">可收编</Tag>
                  ) : (
                    <Tooltip title={r.detail}>
                      <Tag color="orange">
                        跳过：{r.reasonLabel ?? SKIP_REASON_LABEL[r.reasonCode ?? ''] ?? r.reasonCode}
                      </Tag>
                    </Tooltip>
                  ),
              },
            ]}
          />
          <Typography.Text strong>跳过原因汇总</Typography.Text>
          <Table
            rowKey="code"
            size="small"
            style={{ marginTop: 8 }}
            pagination={false}
            dataSource={Object.entries(preview.skipReasons ?? {}).map(([code, n]) => ({ code, n }))}
            columns={[
              { title: '原因', dataIndex: 'code', render: (c: string) => SKIP_REASON_LABEL[c] ?? c },
              { title: '条数', dataIndex: 'n', width: 100 },
            ]}
          />
        </>
      )}

      {result && (
        <Alert
          type="success"
          showIcon
          style={{ marginTop: 12 }}
          message={`已收编 ${result.added} 条（跳过 ${result.skipped} 条），本集现有 ${result.dataset.dataset.itemCount} 条`}
        />
      )}
    </Modal>
  )
}
