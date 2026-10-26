// CAP-86 v3 库设置抽屉：名称 / 描述 / 向量端点 / 状态 + 危险区删除。
// 设置已从库详情页移出，统一从库列表「设置」打开；保存与删除均居中 Modal.confirm。
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button, Divider, Drawer, Form, Input, message, Modal, Select, Space, Tag, Tooltip, Typography } from 'antd'
import { DeleteOutlined } from '@ant-design/icons'
import { deleteBase, updateBase } from '../api'
import { listModelEndpoints } from '../../model/api'
import type { ModelEndpoint } from '../../model/types'
import type { KnowledgeBase, KnowledgeBaseInput } from '../types'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

export default function KnowledgeBaseSettingsDrawer({
  open,
  base,
  onClose,
  onSaved,
  onDeleted,
}: {
  open: boolean
  base: KnowledgeBase | null
  onClose: () => void
  onSaved: (base: KnowledgeBase) => void
  onDeleted: (base: KnowledgeBase) => void
}) {
  const [form] = Form.useForm<KnowledgeBaseInput>()
  const [current, setCurrent] = useState<KnowledgeBase | null>(base)
  const [endpoints, setEndpoints] = useState<ModelEndpoint[]>([])
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    if (!open) return
    setCurrent(base)
    form.resetFields()
    if (base) {
      form.setFieldsValue({
        name: base.name,
        description: base.description ?? undefined,
        modelEndpointId: base.modelEndpointId ?? undefined,
        status: base.status,
      })
    }
  }, [open, base, form])

  useEffect(() => {
    if (!open) return
    listModelEndpoints()
      .then(setEndpoints)
      .catch(() => undefined)
  }, [open])

  const embeddingEndpoints = useMemo(
    () => endpoints.filter((e) => e.kind === 'EMBEDDING'),
    [endpoints],
  )
  const resolvedEndpoint =
    embeddingEndpoints.find((e) => e.id === current?.modelEndpointId) ??
    embeddingEndpoints.find((e) => e.isDefault && e.status === 'active') ??
    null

  const onSave = async () => {
    if (!current) return
    const values = await form.validateFields()
    const archiving = values.status === 'archived' && current.status !== 'archived'
    Modal.confirm({
      centered: true,
      title: `保存知识库「${current.name}」的设置？`,
      content: archiving
        ? '状态将改为「归档停用」：该库会退出检索，已入库文档保留。'
        : '保存后立即生效；向量端点变更后可能需要重建索引。',
      okText: '保存',
      cancelText: '取消',
      onOk: async () => {
        setSaving(true)
        try {
          const updated = await updateBase(current.id, values)
          message.success('已更新')
          onSaved(updated)
          onClose()
        } catch (e) {
          showError(e, '保存失败')
        } finally {
          setSaving(false)
        }
      },
    })
  }

  const onDelete = () => {
    if (!current) return
    Modal.confirm({
      centered: true,
      title: `删除知识库「${current.name}」？`,
      content:
        current.entryCount > 0
          ? `库内还有 ${current.entryCount} 个文档，确认后将连同条目与索引一并删除，无法恢复。`
          : '删除后无法恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setDeleting(true)
        try {
          await deleteBase(current.id, current.entryCount > 0)
          message.success('已删除')
          onDeleted(current)
        } catch (e) {
          showError(e, '删除失败')
        } finally {
          setDeleting(false)
        }
      },
    })
  }

  return (
    <>
      <Drawer
        title={current ? '编辑知识库' : '知识库设置'}
        open={open}
        onClose={onClose}
        width={DRAWER_DEFAULT}
        destroyOnHidden
        footer={
          <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" loading={saving} onClick={onSave} disabled={!current}>
              保存
            </Button>
          </Space>
        }
      >
        {!current ? (
          <Typography.Text type="secondary">加载中…</Typography.Text>
        ) : (
          <>
            <Form form={form} layout="vertical">
              <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
                <Input placeholder="如 在线商城后端经验库" />
              </Form.Item>
              <Form.Item name="description" label="描述">
                <Input.TextArea rows={2} placeholder="库的用途、覆盖范围（可选）" />
              </Form.Item>
              <Form.Item label="范围" extra="范围与所属项目创建后不可修改">
                <Space>
                  <Tag color={current.scope === 'global' ? 'blue' : undefined}>
                    {current.scope === 'global' ? '全局' : '项目'}
                  </Tag>
                  {current.scope === 'project' && (
                    <Typography.Text type="secondary">
                      {current.projectName ?? current.projectId}
                    </Typography.Text>
                  )}
                </Space>
              </Form.Item>
              <Form.Item
                name="modelEndpointId"
                label="向量端点"
                extra="留空 = 跟随平台默认端点；指定后本库索引与检索按该端点走（不同模型/维度不能混用同一份索引）"
              >
                <Select
                  allowClear
                  placeholder="跟随平台默认端点"
                  options={embeddingEndpoints.map((e) => ({
                    value: e.id,
                    label: `${e.name}（${e.model ?? e.provider}${e.dimensions ? ` · ${e.dimensions} 维` : ' · 未探测维度'}${e.status === 'disabled' ? ' · 已停用' : ''}）`,
                  }))}
                />
              </Form.Item>
              <Form.Item label="当前生效">
                <Space>
                  {current.modelEndpointName ? (
                    <Tag color="blue">{current.modelEndpointName}</Tag>
                  ) : (
                    <Tooltip title="无可用向量端点：索引停用、检索走关键词降级">
                      <Tag color="orange">未配置向量端点</Tag>
                    </Tooltip>
                  )}
                  <Typography.Text type="secondary">
                    {resolvedEndpoint?.dimensions
                      ? `维度 ${resolvedEndpoint.dimensions}`
                      : '维度未探测（可在「模型接入」对该端点做一次连接测试）'}
                  </Typography.Text>
                  <Link to="/admin/models">模型接入</Link>
                </Space>
              </Form.Item>
              <Form.Item name="status" label="状态">
                <Select
                  options={[
                    { value: 'active', label: '启用' },
                    { value: 'archived', label: '归档停用（退出检索）' },
                  ]}
                />
              </Form.Item>
            </Form>

            <Divider style={{ margin: '8px 0 16px' }} />
            <Typography.Text type="danger" strong>
              危险区
            </Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Space wrap>
                <Typography.Text type="secondary">
                  删除知识库会连同 {current.entryCount} 个文档与索引一并移除，无法恢复。
                </Typography.Text>
                <Button danger icon={<DeleteOutlined />} loading={deleting} onClick={onDelete}>
                  删除知识库
                </Button>
              </Space>
            </div>
          </>
        )}
      </Drawer>
    </>
  )
}
