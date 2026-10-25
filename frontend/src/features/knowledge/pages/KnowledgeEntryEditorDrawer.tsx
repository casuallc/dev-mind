// CAP-86 条目编辑抽屉：新建/编辑 Markdown 条目；编辑时按 id 拉完整正文（列表摘要不含 contentMd）。
import { useEffect, useState } from 'react'
import { Button, Drawer, Form, Input, message, Modal, Select, Space, Spin } from 'antd'
import { createEntry, getEntry, updateEntry } from '../api'
import type { DocType } from '../types'
import { DOC_TYPE_OPTIONS } from '../types'
import MarkdownEditor from '../../../shared/components/MarkdownEditor'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

interface EntryFormValues {
  name: string
  docType?: DocType
  summary?: string
  owner?: string
  tags?: string[]
  contentMd: string
  status: 'active' | 'deprecated'
}

export default function KnowledgeEntryEditorDrawer({
  open,
  baseId,
  entryId,
  onClose,
  onSaved,
}: {
  open: boolean
  baseId: number
  /** 传了 = 编辑；不传 = 新建 */
  entryId?: number | null
  onClose: () => void
  onSaved: () => void
}) {
  const [form] = Form.useForm<EntryFormValues>()
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [loadedEntryId, setLoadedEntryId] = useState<number | null>(null)

  useEffect(() => {
    if (!open) return
    form.resetFields()
    setLoadedEntryId(null)
    if (!entryId) {
      form.setFieldsValue({ status: 'active', tags: [] })
      return
    }
    let cancelled = false
    setLoading(true)
    getEntry(entryId)
      .then((entry) => {
        if (cancelled) return
        form.setFieldsValue({
          name: entry.name,
          contentMd: entry.contentMd,
          tags: entry.tags,
          status: entry.status,
          docType: entry.docType ?? undefined,
          summary: entry.summary ?? undefined,
          owner: entry.owner ?? undefined,
        })
        setLoadedEntryId(entry.id)
      })
      .catch((e) => {
        if (!cancelled) showError(e, '加载条目失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [open, entryId, form])

  const onSave = async () => {
    const values = await form.validateFields()
    const isUpdate = !!entryId && loadedEntryId === entryId
    const commit = async () => {
      setSaving(true)
      try {
        if (isUpdate) {
          await updateEntry(entryId as number, values)
          message.success('条目已更新（自动重建索引）')
        } else {
          await createEntry({ ...values, kbId: baseId })
          message.success('条目已创建（自动建立索引）')
        }
        onSaved()
        onClose()
      } catch (e) {
        showError(e, '保存失败')
      } finally {
        setSaving(false)
      }
    }
    if (!isUpdate) {
      await commit()
      return
    }
    Modal.confirm({
      centered: true,
      title: `保存「${values.name}」的修改？`,
      content: '保存后会更新正文并自动重建索引，旧内容保留为历史版本。',
      okText: '保存',
      cancelText: '取消',
      onOk: commit,
    })
  }

  return (
    <Drawer
      title={entryId ? '编辑条目' : '新增条目'}
      open={open}
      onClose={onClose}
      width={DRAWER_DEFAULT}
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={onSave} disabled={loading}>
            保存
          </Button>
        </Space>
      }
    >
      <Spin spinning={loading}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
            <Input placeholder="如：AntD 表格固定列写法" />
          </Form.Item>
          <Form.Item
            name="docType"
            label="文档类型"
            extra="手工改动分类/摘要/标签会关闭该条目的自动增强，避免模型覆盖人工修正"
          >
            <Select allowClear placeholder="未指定" options={DOC_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="summary" label="摘要">
            <Input.TextArea rows={2} placeholder="留空则按正文自动截取摘要" />
          </Form.Item>
          <Form.Item name="owner" label="负责人">
            <Input placeholder="文档维护责任人" />
          </Form.Item>
          <Form.Item name="tags" label="标签">
            <Select mode="tags" placeholder="回车添加标签" open={false} />
          </Form.Item>
          <Form.Item name="contentMd" label="内容（Markdown）" rules={[{ required: true, message: '请填写内容' }]}>
            <MarkdownEditor height={420} placeholder="Markdown 内容；保存后自动分块并建立索引" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 'active', label: 'active（启用）' },
                { value: 'deprecated', label: 'deprecated（停用）' },
              ]}
            />
          </Form.Item>
        </Form>
      </Spin>
    </Drawer>
  )
}
