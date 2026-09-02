// Skill 附件管理抽屉：附件列表 + 文本文件新增/编辑（Base64 传输）+ 二进制上传 + 下载/删除。
// SKILL.md 本体不在此管理（走 SkillFormDrawer 正文）。
import { useCallback, useEffect, useState } from 'react'
import { Button, Drawer, Form, Input, message, Modal, Space, Table, Typography, Upload } from 'antd'
import { DeleteOutlined, DownloadOutlined, EditOutlined, FileAddOutlined, UploadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { canWrite } from '../../auth/authStore'
import {
  createSkillFile,
  deleteSkillFile,
  getSkillFile,
  listSkillFiles,
  updateSkillFile,
} from '../api'
import type { Skill, SkillFileMeta } from '../types'
import { fmtTime } from '../../../shared/utils/format'

// UTF-8 安全的 Base64 编解码（内容可能含中文，不能直接用 btoa/atob）
const b64encode = (text: string) => {
  const bytes = new TextEncoder().encode(text)
  let bin = ''
  for (let i = 0; i < bytes.length; i += 0x8000) {
    bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  }
  return btoa(bin)
}
const b64decode = (b64: string) =>
  new TextDecoder().decode(Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)))

const fmtSize = (n: number) => (n < 1024 ? `${n} B` : `${(n / 1024).toFixed(1)} KB`)

/** 按扩展名猜 contentType（文本类型服务端才按 UTF-8 文本存储） */
const guessContentType = (path: string) => {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  const map: Record<string, string> = {
    md: 'text/markdown', txt: 'text/plain', sh: 'text/x-sh', py: 'text/x-python',
    js: 'text/javascript', ts: 'text/typescript', json: 'application/json',
    yml: 'application/yaml', yaml: 'application/yaml', xml: 'application/xml',
  }
  return map[ext] ?? 'text/plain'
}

type FileFormValues = { path: string; content: string }

export default function SkillFilesDrawer({ open, skill, onClose }: {
  open: boolean
  skill: Skill | null
  onClose: () => void
}) {
  const [files, setFiles] = useState<SkillFileMeta[]>([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editingFile, setEditingFile] = useState<SkillFileMeta | null>(null)
  const [form] = Form.useForm<FileFormValues>()

  const load = useCallback(async () => {
    if (!skill) return
    setLoading(true)
    try {
      setFiles(await listSkillFiles(skill.id))
    } catch (e) {
      message.error(`加载附件失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [skill])

  useEffect(() => {
    if (open) load()
  }, [open, load])

  const openCreate = () => {
    setEditingFile(null)
    form.setFieldsValue({ path: '', content: '' })
    setEditOpen(true)
  }

  const openEdit = async (f: SkillFileMeta) => {
    if (!skill) return
    try {
      const detail = await getSkillFile(skill.id, f.id)
      setEditingFile(f)
      form.setFieldsValue({ path: f.path, content: b64decode(detail.contentBase64) })
      setEditOpen(true)
    } catch (e) {
      message.error(`读取附件失败：${(e as Error).message}`)
    }
  }

  const onSave = async (v: FileFormValues) => {
    if (!skill) return
    const input = { path: v.path, contentBase64: b64encode(v.content ?? ''), contentType: guessContentType(v.path) }
    try {
      if (editingFile) {
        await updateSkillFile(skill.id, editingFile.id, input)
      } else {
        await createSkillFile(skill.id, input)
      }
      message.success('已保存')
      setEditOpen(false)
      load()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const onDelete = (f: SkillFileMeta) => {
    if (!skill) return
    Modal.confirm({
      centered: true,
      title: `删除附件「${f.path}」？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteSkillFile(skill.id, f.id)
          message.success('已删除')
          load()
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }

  const onDownload = async (f: SkillFileMeta) => {
    if (!skill) return
    try {
      const detail = await getSkillFile(skill.id, f.id)
      const bytes = Uint8Array.from(atob(detail.contentBase64), (c) => c.charCodeAt(0))
      const url = URL.createObjectURL(new Blob([bytes]))
      const a = document.createElement('a')
      a.href = url
      a.download = f.path.split('/').pop() ?? f.path
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      message.error(`下载失败：${(e as Error).message}`)
    }
  }

  const columns: ColumnsType<SkillFileMeta> = [
    { title: '路径', dataIndex: 'path', ellipsis: true, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '类型', dataIndex: 'contentType', width: 140, ellipsis: true, render: (v) => v ?? '-' },
    { title: '大小', dataIndex: 'size', width: 90, render: fmtSize },
    { title: '更新时间', dataIndex: 'updatedAt', width: 165, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 170,
      render: (_, r) => (
        <Space size={4}>
          {canWrite() && !r.binary && (
            <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} />
          )}
          <Button size="small" icon={<DownloadOutlined />} onClick={() => onDownload(r)} />
          {canWrite() && (
            <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDelete(r)} />
          )}
        </Space>
      ),
    },
  ]

  return (
    <Drawer
      title={skill ? `附件文件 — ${skill.name}` : '附件文件'}
      open={open}
      onClose={onClose}
      width={760}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          SKILL.md 本体在「编辑」表单中维护；这里管理 references/scripts 等附属文件（单文件 ≤512KB，合计 ≤2MB）。
        </Typography.Text>
        {canWrite() && (
          <Space>
            <Button type="primary" icon={<FileAddOutlined />} onClick={openCreate}>
              新增文本文件
            </Button>
            <Upload
              showUploadList={false}
              beforeUpload={(file) => {
                if (!skill) return false
                const reader = new FileReader()
                reader.onload = async () => {
                  const dataUrl = reader.result as string
                  try {
                    await createSkillFile(skill.id, {
                      path: file.name,
                      contentBase64: dataUrl.slice(dataUrl.indexOf(',') + 1),
                      contentType: file.type || 'application/octet-stream',
                    })
                    message.success(`已上传 ${file.name}`)
                    load()
                  } catch (e) {
                    message.error(`上传失败：${(e as Error).message}`)
                  }
                }
                reader.readAsDataURL(file)
                return false
              }}
            >
              <Button icon={<UploadOutlined />}>上传二进制文件</Button>
            </Upload>
          </Space>
        )}
        <Table
          size="small"
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={files}
          pagination={false}
        />
      </Space>

      <Modal
        title={editingFile ? `编辑附件「${editingFile.path}」` : '新增文本文件'}
        open={editOpen}
        onCancel={() => setEditOpen(false)}
        onOk={() => form.submit()}
        okText="保存"
        width={720}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={onSave} preserve={false}>
          <Form.Item
            label="路径"
            name="path"
            rules={[
              { required: true, message: '请输入路径' },
              { pattern: /^[^/\\][^\\]*$/, message: '包内相对路径，用 "/" 分隔' },
            ]}
            tooltip="如 references/xxx.md、scripts/run.sh；SKILL.md 为保留名"
          >
            <Input placeholder="references/xxx.md" maxLength={255} />
          </Form.Item>
          <Form.Item label="内容" name="content">
            <Input.TextArea rows={14} />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  )
}
