import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import FitTable from '../../../shared/components/FitTable'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { fmtTime } from '../../../shared/utils/format'
import {
  createEgressRule,
  deleteEgressRule,
  listAgentNodes,
  listEgressRules,
  updateEgressRule,
} from '../api'
import type { AgentNode, EgressRule, EgressRuleBody } from '../types'

/** 隧道能力协议版本（AgentProtocol.EGRESS_TUNNEL）；低于此版本 runner 无隧道连接 */
const TUNNEL_PROTOCOL = 21

/**
 * CAP-70 FR-08 出口规则管理：host glob 命中即经指定节点的反向隧道出访（服务端内嵌 SOCKS5
 * 只绑 127.0.0.1，git 走 -c http.<url>.proxy=socks5h://…，连接器挂规则驱动 ProxySelector）。
 * 未命中任何规则 = 直连零行为变化。协议 <v21 的节点保存时警告（老 runner 无隧道，引用即失败）。
 */
export default function EgressRulesPage() {
  const { message, modal } = AntApp.useApp()
  const [rows, setRows] = useState<EgressRule[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<EgressRule | 'new' | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<EgressRuleBody>()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [rules, nodeList] = await Promise.all([listEgressRules(), listAgentNodes()])
      setRows(rules)
      setNodes(nodeList)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void load()
  }, [load])

  const nodeById = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes])

  const openCreate = () => {
    form.resetFields()
    form.setFieldsValue({ hostPattern: '', nodeId: undefined, enabled: true, sort: 0, remark: '' })
    setEditing('new')
  }

  const openEdit = (r: EgressRule) => {
    form.resetFields()
    form.setFieldsValue({
      hostPattern: r.hostPattern,
      nodeId: r.nodeId,
      enabled: r.enabled,
      sort: r.sort,
      remark: r.remark ?? '',
    })
    setEditing(r)
  }

  const save = async () => {
    // 全量取值（destroyOnHidden 关闭时已卸载字段会被 nameList 口径丢掉——此处直接校验全量）
    const body = await form.validateFields()
    const node = nodeById.get(body.nodeId)
    const protocol = node?.protocolVersion ?? 1
    const doSave = async () => {
      setSaving(true)
      try {
        if (editing === 'new') {
          await createEgressRule(body)
          message.success('已创建')
        } else if (editing) {
          await updateEgressRule(editing.id, body)
          message.success('已保存')
        }
        setEditing(null)
        void load()
      } catch (e) {
        message.error(e instanceof Error ? e.message : '保存失败')
      } finally {
        setSaving(false)
      }
    }
    // FR-08：协议 <v21 的 runner 无隧道能力，引用它出访必然失败——保存前显式警告
    if (protocol < TUNNEL_PROTOCOL) {
      modal.confirm({
        title: '节点协议版本过低',
        content: `节点「${node?.name ?? body.nodeId}」协议版本 v${protocol} < v${TUNNEL_PROTOCOL}，`
          + 'runner 无隧道能力，命中此规则的出访会失败。请升级 runner 后再用，确认仍要保存？',
        okText: '仍要保存',
        cancelText: '取消',
        onOk: doSave,
      })
      return
    }
    await doSave()
  }

  const remove = async (r: EgressRule) => {
    try {
      await deleteEgressRule(r.id)
      message.success('已删除')
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const tunnelBadge = (r: EgressRule) => {
    if (r.tunnelOnline == null) {
      return <Tag>无隧道</Tag>
    }
    return r.tunnelOnline ? <Tag color="success">隧道在线</Tag> : <Tag color="error">隧道离线</Tag>
  }

  const columns = [
    { title: '主机规则', dataIndex: 'hostPattern', width: 220, render: (v: string) => <code>{v}</code> },
    { title: '出口节点', dataIndex: 'nodeName', width: 160, render: (v: string | undefined, r: EgressRule) => v ?? `#${r.nodeId}` },
    {
      title: '隧道',
      key: 'tunnel',
      width: 100,
      render: (_: unknown, r: EgressRule) => tunnelBadge(r),
    },
    {
      title: '协议',
      key: 'protocol',
      width: 90,
      render: (_: unknown, r: EgressRule) => {
        const v = r.nodeProtocolVersion
        if (v == null) return '—'
        return v >= TUNNEL_PROTOCOL ? `v${v}` : <Typography.Text type="danger">v{v} 过低</Typography.Text>
      },
    },
    { title: '启用', dataIndex: 'enabled', width: 70, render: (v: boolean) => (v ? '是' : '否') },
    { title: '顺序', dataIndex: 'sort', width: 70 },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '更新时间', dataIndex: 'updatedAt', width: 160, render: (v?: string) => fmtTime(v) },
    {
      title: '操作',
      key: 'ops',
      width: 130,
      render: (_: unknown, r: EgressRule) => (
        <Space>
          <Button onClick={() => openEdit(r)}>编辑</Button>
          <Popconfirm title={`删除规则「${r.hostPattern}」？`} onConfirm={() => void remove(r)}>
            <Button danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="出口规则"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建规则</Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        服务端在外网、研发系统在内网时，命中主机规则的出访（git 克隆/抓取/diff、集成 API、书签探测）
        经指定节点的反向隧道出访；未命中任何规则保持直连。规则按顺序先命中先生效。
      </Typography.Paragraph>
      <FitTable
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '暂无出口规则（全部直连），点右上角「新建规则」开始配置' }}
      />
      <Modal
        open={editing !== null}
        title={editing === 'new' ? '新建出口规则' : '编辑出口规则'}
        onOk={() => void save()}
        onCancel={() => setEditing(null)}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="主机规则支持精确域名（git.corp.com）或后缀通配（*.corp.com）；命中但节点隧道离线时出访快速失败，不会静默回落直连。"
        />
        <Form form={form} layout="vertical">
          <Form.Item
            name="hostPattern"
            label="主机规则"
            rules={[{ required: true, message: '请输入主机规则' }]}
          >
            <Input placeholder="git.corp.com 或 *.corp.com" />
          </Form.Item>
          <Form.Item name="nodeId" label="出口节点" rules={[{ required: true, message: '请选择出口节点' }]}>
            <Select
              placeholder="选择隧道出口节点（runner 须 ≥ v21）"
              options={nodes.map((n) => ({
                value: n.id,
                label: `${n.name}（${n.status}${(n.protocolVersion ?? 1) < TUNNEL_PROTOCOL ? `，v${n.protocolVersion ?? 1} 无隧道` : ''}）`,
                disabled: n.status !== 'ONLINE',
              }))}
            />
          </Form.Item>
          <Space size={16}>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="sort" label="顺序（小先命中）">
              <InputNumber min={0} max={9999} />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
