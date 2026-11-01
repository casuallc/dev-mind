// CAP-104 测试资产库（/tests → 测试资产页签）：GIT 源登记、ZIP/单文件上传、评审门禁与删除保护。
// 未评审通过的资产不可被用例/套件引用；解包规模与路径安全由服务端校验。
import {
  Button,
  Drawer,
  Flex,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  Upload,
  message,
} from 'antd'
import type { UploadFile } from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { InboxOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createGitAsset,
  deleteAsset,
  listAssets,
  reviewAsset,
  uploadAsset,
} from '../api'
import type { TestAsset, TestAssetGitInput } from '../types'
import { usePermissions } from '../../auth/usePermissions'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import FitTable from '../../../shared/components/FitTable'
import { ASSET_KIND_COLOR, REVIEW_COLOR, REVIEW_LABEL } from '../constants'

const REVIEW_FILTER = [
  { value: 'DRAFT', label: '待评审' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]

export default function AssetLibraryPanel({ projectId }: { projectId: string }) {
  const { canAction } = usePermissions()
  const canManage = canAction('project.tests.action.manage-asset')
  const canReview = canAction('project.tests.action.review-case')

  const [rows, setRows] = useState<TestAsset[]>([])
  const [loading, setLoading] = useState(false)
  const [reviewFilter, setReviewFilter] = useState<string | undefined>()

  const [gitOpen, setGitOpen] = useState(false)
  const [gitSaving, setGitSaving] = useState(false)
  const [gitForm] = Form.useForm<TestAssetGitInput>()

  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [uploadName, setUploadName] = useState('')
  const [uploadNote, setUploadNote] = useState('')
  const [uploadRole, setUploadRole] = useState<'DATA' | 'PROGRAM'>('DATA')
  const [uploading, setUploading] = useState(false)

  const [reviewTarget, setReviewTarget] = useState<TestAsset | null>(null)
  const [reviewStatus, setReviewStatus] = useState<'APPROVED' | 'REJECTED'>('APPROVED')
  const [reviewNote, setReviewNote] = useState('')
  const [reviewing, setReviewing] = useState(false)

  const load = () => {
    setLoading(true)
    listAssets(projectId, reviewFilter)
      .then(setRows)
      .catch((e) => showError(e, '加载资产失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, reviewFilter])

  const saveGit = async () => {
    const v = await gitForm.validateFields()
    setGitSaving(true)
    try {
      await createGitAsset(projectId, {
        name: v.name,
        repoUrl: v.repoUrl,
        branch: v.branch,
        commitHash: v.commitHash?.trim() || undefined,
        note: v.note?.trim() || undefined,
      })
      message.success('已登记 git 资产（待评审）')
      setGitOpen(false)
      gitForm.resetFields()
      load()
    } catch (e) {
      showError(e, '登记失败')
    } finally {
      setGitSaving(false)
    }
  }

  const doUpload = async () => {
    if (!uploadFile) {
      message.warning('请选择文件')
      return
    }
    if (uploadRole === 'PROGRAM' && !/\.zip$/i.test(uploadFile.name)) {
      message.warning('程序包必须是 ZIP（内含 .devmind-program.json 与入口脚本）')
      return
    }
    setUploading(true)
    try {
      await uploadAsset(projectId, uploadFile, uploadName, uploadNote, uploadRole)
      message.success(uploadRole === 'PROGRAM' ? '程序包已上传（待评审，清单校验见列表）' : '已上传（待评审）')
      setUploadOpen(false)
      setUploadFile(null)
      setUploadName('')
      setUploadNote('')
      setUploadRole('DATA')
      load()
    } catch (e) {
      showError(e, '上传失败')
    } finally {
      setUploading(false)
    }
  }

  const openReview = (a: TestAsset, status: 'APPROVED' | 'REJECTED') => {
    setReviewTarget(a)
    setReviewStatus(status)
    setReviewNote('')
  }

  const doReview = async () => {
    if (!reviewTarget) return
    setReviewing(true)
    try {
      await reviewAsset(reviewTarget.id, reviewStatus, reviewNote.trim() || undefined)
      message.success(reviewStatus === 'APPROVED' ? '资产已通过评审' : '资产已驳回')
      setReviewTarget(null)
      load()
    } catch (e) {
      showError(e, '评审失败')
    } finally {
      setReviewing(false)
    }
  }

  const onDelete = (a: TestAsset) => {
    Modal.confirm({
      centered: true,
      title: `删除资产「${a.name}」？`,
      content: '被用例或套件引用的资产不可删除；删除后源文件一并清理，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteAsset(a.id)
        message.success('已删除')
        load()
      },
    })
  }

  const columns: ColumnsType<TestAsset> = [
    { title: 'ID', dataIndex: 'id', width: 64, render: (v: number) => `#${v}` },
    { title: '名称', dataIndex: 'name', width: 200, ellipsis: true },
    {
      title: '类型', dataIndex: 'kind', width: 80,
      render: (v: keyof typeof ASSET_KIND_COLOR) => <Tag color={ASSET_KIND_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '角色', dataIndex: 'assetRole', width: 90,
      render: (v: 'DATA' | 'PROGRAM' | null | undefined) => (v === 'PROGRAM'
        ? <Tag color="purple">程序包</Tag> : <Tag>数据/脚本</Tag>),
    },
    {
      title: '程序信息', key: 'program', width: 230,
      render: (_, a) => (a.assetRole === 'PROGRAM'
        ? (
          <Space direction="vertical" size={0}>
            <Typography.Text style={{ fontSize: 12 }}>
              {a.runtime ?? '-'}{a.manifest?.runtimeVersion ? ` ${a.manifest.runtimeVersion}` : ''}
              {' · '}{a.targetOs ?? 'ANY'}/{a.targetArch ?? 'ANY'}
            </Typography.Text>
            <Typography.Text code style={{ fontSize: 11 }}>{a.entrypoint ?? '-'}</Typography.Text>
          </Space>
        )
        : '-'),
    },
    { title: '来源', dataIndex: 'source', ellipsis: true, render: (v: string | null) => (v ? <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> : '-') },
    { title: '分支', dataIndex: 'branch', width: 110, render: (v: string | null) => v || '-' },
    { title: 'Commit', dataIndex: 'commitHash', width: 120, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '大小', dataIndex: 'sizeBytes', width: 90, render: (v: number | null) => (v != null ? fmtBytes(v) : '-') },
    { title: '文件数', dataIndex: 'fileCount', width: 80, render: (v: number | null) => v ?? '-' },
    {
      title: '评审', dataIndex: 'reviewStatus', width: 90,
      render: (v: keyof typeof REVIEW_COLOR) => <Tag color={REVIEW_COLOR[v]}>{REVIEW_LABEL[v]}</Tag>,
    },
    { title: '上传人', dataIndex: 'uploadedBy', width: 110, render: (v: string | null) => v || '-' },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      key: 'act',
      width: 170,
      render: (_, a) => (
        <Space size={4}>
          {canReview && a.reviewStatus !== 'APPROVED' && (
            <Button size="small" onClick={() => openReview(a, 'APPROVED')}>通过</Button>
          )}
          {canReview && a.reviewStatus !== 'REJECTED' && (
            <Button size="small" onClick={() => openReview(a, 'REJECTED')}>驳回</Button>
          )}
          {canManage && <Button size="small" danger onClick={() => onDelete(a)}>删除</Button>}
        </Space>
      ),
    },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        测试资产 = 脚本/数据/夹具的受管来源（git 仓库、ZIP 或单文件）。资产先评审通过才可被用例/套件引用；
        上传会校验规模并解包（防路径穿越），源文件保存在服务端。
      </Typography.Paragraph>
      <Flex justify="space-between" wrap gap={8} style={{ marginBottom: 12 }}>
        <Space>
          <Select<string>
            allowClear
            style={{ width: 160 }}
            placeholder="全部评审状态"
            value={reviewFilter}
            onChange={setReviewFilter}
            options={REVIEW_FILTER}
          />
        </Space>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {canManage && (
            <>
              <Button icon={<PlusOutlined />} onClick={() => setGitOpen(true)}>登记 git 源</Button>
              <Button type="primary" icon={<InboxOutlined />} onClick={() => setUploadOpen(true)}>上传资产</Button>
            </>
          )}
        </Space>
      </Flex>
      <FitTable<TestAsset>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: canManage ? '暂无资产：可登记 git 仓库或上传 ZIP/脚本包' : '暂无资产' }}
      />

      <Drawer
        title="登记 git 测试资产"
        width={DRAWER_DEFAULT}
        open={gitOpen}
        onClose={() => setGitOpen(false)}
        destroyOnHidden
        footer={
          <Flex justify="flex-end" gap={8}>
            <Button onClick={() => setGitOpen(false)}>取消</Button>
            <Button type="primary" loading={gitSaving} onClick={saveGit}>保存</Button>
          </Flex>
        }
      >
        <Form form={gitForm} layout="vertical" initialValues={{ branch: 'main' }}>
          <Form.Item label="资产名称" name="name" rules={[{ required: true, message: '请输入资产名称' }]}>
            <Input placeholder="如 E2E 脚本仓" />
          </Form.Item>
          <Form.Item label="仓库地址" name="repoUrl" rules={[{ required: true, message: '请输入仓库地址' }]}>
            <Input placeholder="https://git.example.com/qa/e2e.git" />
          </Form.Item>
          <Form.Item label="分支" name="branch" rules={[{ required: true, message: '请输入分支' }]}>
            <Input placeholder="main" />
          </Form.Item>
          <Form.Item label="固定 Commit（可选）" name="commitHash"
            tooltip="7~64 位十六进制；执行时检出该 commit 以保证可复现">
            <Input placeholder="a1b2c3d" />
          </Form.Item>
          <Form.Item label="备注" name="note">
            <Input.TextArea rows={2} placeholder="用途/负责人等" />
          </Form.Item>
        </Form>
      </Drawer>

      <Modal
        title="上传测试资产"
        open={uploadOpen}
        onCancel={() => setUploadOpen(false)}
        onOk={doUpload}
        okText="上传"
        confirmLoading={uploading}
        width={560}
        destroyOnHidden
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Select<'DATA' | 'PROGRAM'>
            style={{ width: '100%' }}
            value={uploadRole}
            onChange={setUploadRole}
            options={[
              { value: 'DATA', label: '数据/脚本资产（ZIP/单文件）' },
              { value: 'PROGRAM', label: '程序包 PROGRAM（ZIP，含 .devmind-program.json）' },
            ]}
          />
          <Upload.Dragger
            maxCount={1}
            accept={uploadRole === 'PROGRAM' ? '.zip' : undefined}
            fileList={uploadFile ? [{ uid: '1', name: uploadFile.name } as UploadFile] : []}
            beforeUpload={(f) => { setUploadFile(f); return false }}
            onRemove={() => { setUploadFile(null); return false }}
          >
            <p className="ant-upload-drag-icon"><InboxOutlined /></p>
            <p className="ant-upload-text">点击或拖拽 ZIP / 脚本文件到此处</p>
            <p className="ant-upload-hint">
              {uploadRole === 'PROGRAM'
                ? '程序包必须已构建完成：包内 .devmind-program.json 声明 runtime/entrypoint/targetOs；平台不会执行构建或装依赖。'
                : 'ZIP 会自动解包；单文件上限与解包规模由服务端配置校验'}
            </p>
          </Upload.Dragger>
          <Input placeholder="资产名称（可选，默认文件名）" value={uploadName}
            onChange={(e) => setUploadName(e.target.value)} />
          <Input.TextArea rows={2} placeholder="备注（可选）" value={uploadNote}
            onChange={(e) => setUploadNote(e.target.value)} />
        </Space>
      </Modal>

      <Modal
        centered
        title={reviewStatus === 'APPROVED' ? '通过资产评审' : '驳回资产'}
        open={!!reviewTarget}
        onCancel={() => setReviewTarget(null)}
        onOk={doReview}
        okText={reviewStatus === 'APPROVED' ? '通过' : '驳回'}
        okButtonProps={reviewStatus === 'REJECTED' ? { danger: true } : undefined}
        confirmLoading={reviewing}
        width={480}
        destroyOnHidden
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Typography.Text>
            {reviewTarget ? `#${reviewTarget.id} ${reviewTarget.name}` : ''}
          </Typography.Text>
          <Input.TextArea
            rows={3}
            placeholder={reviewStatus === 'APPROVED' ? '评审备注（可选）' : '驳回原因（建议填写）'}
            value={reviewNote}
            onChange={(e) => setReviewNote(e.target.value)}
          />
          <Tooltip title="未通过的资产不能被用例/套件引用或执行">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              评审门禁：仅「已通过」的资产可被引用。
            </Typography.Text>
          </Tooltip>
        </Space>
      </Modal>
    </div>
  )
}
