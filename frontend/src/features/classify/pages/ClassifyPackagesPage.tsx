// CAP-57 分类服务 · 安装包管理：三类包（边车程序/模型权重/语料数据）上传、按节点分发（拉取模式）、安装记录与重试。
// 上传走 multipart（上限 4GB）；分发后节点经 HTTP 拉包物化，进度在 installs 子表轮询。
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd'
import {
  CloudUploadOutlined,
  InboxOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons'
import FitTable from '../../../shared/components/FitTable'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import LayaViewSwitch from '../../laya/components/LayaViewSwitch'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import {
  deleteClassifyPackage,
  installClassifyPackage,
  listClassifyInstalls,
  listClassifyPackages,
  retryClassifyInstall,
  uploadClassifyPackage,
} from '../api'
import type { ClassifyPackage, ClassifyPackageInstall, ClassifyPackageKind } from '../types'

const KIND_META: Record<ClassifyPackageKind, string> = {
  SIDECAR_APP: '边车程序包',
  MODEL_WEIGHTS: '模型权重包',
  CORPUS: '语料/数据包',
}

const INSTALL_STATUS: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'processing', label: '分发中' },
  INSTALLED: { color: 'success', label: '已安装' },
  FAILED: { color: 'error', label: '失败' },
}

export default function ClassifyPackagesPage() {
  const [kind, setKind] = useState<ClassifyPackageKind>('SIDECAR_APP')
  const [rows, setRows] = useState<ClassifyPackage[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [loading, setLoading] = useState(false)
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [distributeFor, setDistributeFor] = useState<ClassifyPackage | null>(null)
  const [distributeNodeId, setDistributeNodeId] = useState<number | null>(null)
  const [distributing, setDistributing] = useState(false)
  const [installsFor, setInstallsFor] = useState<ClassifyPackage | null>(null)
  const [installs, setInstalls] = useState<ClassifyPackageInstall[]>([])
  const [installsLoading, setInstallsLoading] = useState(false)
  const [uploadForm] = Form.useForm<{ name: string; version: string; file: File | null }>()

  const nodeName = useMemo(() => {
    const m = new Map<number, string>()
    nodes.forEach((n) => m.set(n.id, n.name))
    return m
  }, [nodes])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [pkgs, nodeList] = await Promise.all([listClassifyPackages(kind), listAgentNodes()])
      setRows(pkgs)
      setNodes(nodeList)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [kind])

  useEffect(() => {
    void load()
  }, [load])

  const loadInstalls = useCallback(async (pkg: ClassifyPackage) => {
    setInstallsLoading(true)
    try {
      setInstalls(await listClassifyInstalls(pkg.id))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载安装记录失败')
    } finally {
      setInstallsLoading(false)
    }
  }, [])

  // 分发是异步任务（GB 级下载可达分钟级）：抽屉开着时每 5s 轮询一次进度
  useEffect(() => {
    if (!installsFor) return
    const timer = setInterval(() => void loadInstalls(installsFor), 5000)
    return () => clearInterval(timer)
  }, [installsFor, loadInstalls])

  const doUpload = async () => {
    const values = uploadForm.getFieldsValue(true)
    if (!values.file) {
      message.warning('请选择文件')
      return
    }
    setUploading(true)
    try {
      await uploadClassifyPackage(kind, values.name, values.version, values.file)
      message.success(`包「${values.name} ${values.version}」已上传`)
      setUploadOpen(false)
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '上传失败')
    } finally {
      setUploading(false)
    }
  }

  const doDistribute = async () => {
    if (!distributeFor || distributeNodeId == null) return
    setDistributing(true)
    try {
      await installClassifyPackage(distributeFor.id, distributeNodeId)
      message.success(`已下发分发指令（节点拉取安装，进度见「安装记录」）`)
      setDistributeFor(null)
      if (installsFor?.id === distributeFor.id) void loadInstalls(distributeFor)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '分发失败')
    } finally {
      setDistributing(false)
    }
  }

  const remove = async (row: ClassifyPackage) => {
    try {
      await deleteClassifyPackage(row.id)
      message.success(`包「${row.name} ${row.pkgVersion}」已删除`)
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const retry = async (inst: ClassifyPackageInstall) => {
    try {
      await retryClassifyInstall(inst.id)
      message.success('已重新下发')
      if (installsFor) void loadInstalls(installsFor)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重试失败')
    }
  }

  const columns = [
    { title: '名称', dataIndex: 'name', width: 200 },
    { title: '版本', dataIndex: 'pkgVersion', width: 110 },
    {
      title: '文件',
      dataIndex: 'originalFilename',
      render: (v: string, row: ClassifyPackage) => (
        <Space size={8}>
          <span>{v}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {fmtBytes(row.sizeBytes)}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: 'SHA-256',
      dataIndex: 'sha256',
      width: 150,
      render: (v: string) => (
        <Typography.Text copyable={{ text: v }} style={{ fontSize: 12 }}>
          {v.slice(0, 12)}…
        </Typography.Text>
      ),
    },
    {
      title: '上传',
      dataIndex: 'uploadedAt',
      width: 190,
      render: (t: string, row: ClassifyPackage) => `${row.uploadedBy ?? '-'} · ${fmtTime(t)}`,
    },
    {
      title: '操作',
      key: 'actions',
      width: 200,
      render: (_: unknown, row: ClassifyPackage) => (
        <Space>
          <a
            onClick={() => {
              setDistributeNodeId(null)
              setDistributeFor(row)
            }}
          >
            分发
          </a>
          <a
            onClick={() => {
              setInstallsFor(row)
              void loadInstalls(row)
            }}
          >
            安装记录
          </a>
          <Popconfirm title="删除该包？" description="已绑定为实例程序包时不可删；节点的安装目录不受影响。" onConfirm={() => remove(row)}>
            <a style={{ color: '#ff4d4f' }}>删除</a>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  const installColumns = [
    {
      title: '节点',
      dataIndex: 'nodeId',
      width: 140,
      render: (id: number) => nodeName.get(id) ?? `#${id}`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: string, inst: ClassifyPackageInstall) => {
        const meta = INSTALL_STATUS[s] ?? { color: 'default', label: s }
        return (
          <Space size={4}>
            <Tag color={meta.color}>{meta.label}</Tag>
            {inst.error ? (
              <Typography.Text type="danger" style={{ fontSize: 12 }} ellipsis={{ tooltip: inst.error }}>
                {inst.error}
              </Typography.Text>
            ) : null}
          </Space>
        )
      },
    },
    {
      title: '安装目录',
      dataIndex: 'installDir',
      render: (v?: string | null) =>
        v ? (
          <Typography.Text copyable style={{ fontSize: 12 }}>
            {v}
          </Typography.Text>
        ) : (
          '-'
        ),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 150, render: (t: string) => fmtTime(t) },
    {
      title: '操作',
      key: 'actions',
      width: 80,
      render: (_: unknown, inst: ClassifyPackageInstall) =>
        inst.status === 'FAILED' ? <a onClick={() => retry(inst)}>重试</a> : null,
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <LayaViewSwitch group="instances" value="packages">
          <Segmented
            value={kind}
            onChange={(v) => setKind(v as ClassifyPackageKind)}
            options={(Object.keys(KIND_META) as ClassifyPackageKind[]).map((k) => ({ value: k, label: KIND_META[k] }))}
          />
        </LayaViewSwitch>
      }
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              uploadForm.resetFields()
              setUploadOpen(true)
            }}
          >
            上传包
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        管理 laya 分类服务的安装包（程序 / 模型权重 / 语料数据），上传到服务端后按节点分发；实例 env 里的
        {' ${PKG_DIR:<packageId>} '} 会展开为对应包在该节点的安装目录。
      </Typography.Paragraph>
      <FitTable<ClassifyPackage>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        locale={{
          emptyText: (
            <Empty description={`还没有${KIND_META[kind]}`}>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadOpen(true)}>
                上传包
              </Button>
            </Empty>
          ),
        }}
      />

      <Modal
        title={`上传${KIND_META[kind]}`}
        open={uploadOpen}
        onOk={doUpload}
        onCancel={() => setUploadOpen(false)}
        confirmLoading={uploading}
        okText="上传"
        cancelText="取消"
        okButtonProps={{ icon: <CloudUploadOutlined /> }}
        destroyOnHidden={false}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="zip 格式；同名同版本不可重复上传。大文件（GB 级权重）上传期间请勿关闭页面。"
        />
        <Form form={uploadForm} layout="vertical" preserve>
          <Form.Item label="包名" name="name" rules={[{ required: true, message: '请输入包名' }]}>
            <Input placeholder={kind === 'SIDECAR_APP' ? '如 laya-sidecar' : '如 laya-typed-decisions'} maxLength={128} />
          </Form.Item>
          <Form.Item label="版本" name="version" rules={[{ required: true, message: '请输入版本' }]}>
            <Input placeholder="如 0.3.0" maxLength={64} />
          </Form.Item>
          <Form.Item label="文件" name="file" valuePropName="file" rules={[{ required: true, message: '请选择文件' }]}>
            <Upload.Dragger
              maxCount={1}
              beforeUpload={() => false}
              onChange={(info) => uploadForm.setFieldValue('file', info.fileList[0]?.originFileObj ?? null)}
              onRemove={() => uploadForm.setFieldValue('file', null)}
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">点击或拖拽 zip 文件到此</p>
            </Upload.Dragger>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={distributeFor ? `分发「${distributeFor.name} ${distributeFor.pkgVersion}」` : ''}
        open={!!distributeFor}
        onOk={doDistribute}
        onCancel={() => setDistributeFor(null)}
        confirmLoading={distributing}
        okText="下发分发"
        cancelText="取消"
        okButtonProps={{ disabled: distributeNodeId == null, icon: <SendOutlined /> }}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="分发为异步任务：节点经 HTTP 从服务端拉包、校验 SHA-256 后解压安装；GB 级包需数分钟，进度在「安装记录」查看。"
        />
        <Select
          style={{ width: '100%' }}
          placeholder="选择目标节点（需在线且协议 v15+）"
          value={distributeNodeId}
          onChange={setDistributeNodeId}
          options={nodes.map((n) => ({
            value: n.id,
            label: `${n.name}${n.status !== 'ONLINE' ? `（${n.status}）` : ''}${
              (n.protocolVersion ?? 1) < 15 ? ` · 协议 v${n.protocolVersion ?? 1} 需升级` : ''
            }`,
            disabled: n.status !== 'ONLINE',
          }))}
        />
      </Modal>

      <Drawer
        title={installsFor ? `「${installsFor.name} ${installsFor.pkgVersion}」安装记录` : ''}
        open={!!installsFor}
        onClose={() => setInstallsFor(null)}
        width={640}
      >
        <Table<ClassifyPackageInstall>
          rowKey="id"
          size="small"
          loading={installsLoading}
          columns={installColumns}
          dataSource={installs}
          pagination={false}
          locale={{ emptyText: '尚未分发到任何节点' }}
        />
      </Drawer>
    </Card>
  )
}
