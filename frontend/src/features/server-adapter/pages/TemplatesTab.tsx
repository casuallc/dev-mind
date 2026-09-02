// CAP-07 FR-05 命令模板白名单管理
import { useCallback, useEffect, useState } from 'react'
import { Button, Checkbox, Drawer, Form, Input, message, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { Project } from '../../projects/types'
import { listProjects } from '../../projects/api'
import { createTemplate, deleteTemplate, listTemplates, updateTemplate } from '../api'
import type { TemplateInput, TemplateView } from '../types'

const CAPABILITIES = ['build', 'deploy', 'release', 'test', 'logs', 'exec']

export default function TemplatesTab() {
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useState<string>('')
  const [templates, setTemplates] = useState<TemplateView[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<TemplateView | null>(null)
  const [form] = Form.useForm<TemplateInput>()

  const loadProjects = useCallback(async () => {
    try {
      const list = await listProjects()
      setProjects(list)
      if (list.length > 0 && !projectId) setProjectId(list[0].id)
    } catch (e) {
      message.error(`加载项目失败：${(e as Error).message}`)
    }
  }, [projectId])

  const load = useCallback(async () => {
    if (!projectId) return
    setLoading(true)
    try {
      setTemplates(await listTemplates(projectId))
    } catch (e) {
      message.error(`加载模板失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => { loadProjects() }, [loadProjects])
  useEffect(() => { load() }, [load])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ projectId, allowed: [] })
    setOpen(true)
  }

  const openEdit = (t: TemplateView) => {
    setEditing(t)
    form.setFieldsValue({
      projectId: t.projectId,
      code: t.code,
      name: t.name,
      templateText: t.templateText,
      params: t.params.map((p) => ({ ...p })),
      allowed: t.allowed,
    })
    setOpen(true)
  }

  const onSave = async () => {
    const v = await form.validateFields()
    try {
      if (editing) {
        await updateTemplate(editing.id, v)
        message.success('模板已更新')
      } else {
        await createTemplate(v)
        message.success('模板已创建')
      }
      setOpen(false)
      await load()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const onDelete = async (id: number) => {
    try {
      await deleteTemplate(id)
      message.success('已删除')
      await load()
    } catch (e) {
      message.error(`删除失败：${(e as Error).message}`)
    }
  }

  const columns: ColumnsType<TemplateView> = [
    { title: '编码', dataIndex: 'code', width: 120, render: (c) => <Typography.Text code>{c}</Typography.Text> },
    { title: '名称', dataIndex: 'name', width: 160 },
    {
      title: '允许能力',
      dataIndex: 'allowed',
      render: (a: string[]) =>
        a && a.length > 0 ? a.map((x) => <Tag key={x} color="blue">{x}</Tag>) : <Typography.Text type="secondary">全部</Typography.Text>,
    },
    {
      title: '参数',
      dataIndex: 'params',
      width: 140,
      render: (p: TemplateView['params']) =>
        p && p.length > 0 ? p.map((x) => <Tag key={x.name}>{x.name}{x.required ? '*' : ''}</Tag>) : '-',
    },
    {
      title: '操作',
      width: 140,
      render: (_, r) => (
        <Space>
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          <Popconfirm title="删除该模板？" okText="删除" okButtonProps={{ danger: true }} onConfirm={() => onDelete(r.id)}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <Space>
        <Select
          style={{ width: 220 }}
          placeholder="选择项目"
          value={projectId || undefined}
          onChange={setProjectId}
          options={projects.map((p) => ({ label: `${p.name} (${p.id})`, value: p.id }))}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建模板</Button>
      </Space>
      <Table
        size="small"
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={templates}
        pagination={false}
        locale={{ emptyText: '无模板。远程只能执行白名单内的模板（FR-05）。' }}
      />

      <Drawer
        title={editing ? `编辑模板 ${editing.code}` : '新建命令模板'}
        open={open}
        onClose={() => setOpen(false)}
        width={720}
        destroyOnHidden
        footer={
          <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button onClick={() => setOpen(false)}>取消</Button>
            <Button type="primary" onClick={onSave}>保存</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Space style={{ width: '100%' }} size={12}>
            <Form.Item name="projectId" label="项目" rules={[{ required: true }]} style={{ width: 200 }}>
              <Select options={projects.map((p) => ({ label: `${p.name} (${p.id})`, value: p.id }))} />
            </Form.Item>
            <Form.Item name="code" label="模板编码" rules={[{ required: true, message: '必填' }]} style={{ width: 180 }}>
              <Input placeholder="如 deploy" />
            </Form.Item>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '必填' }]} style={{ flex: 1 }}>
              <Input placeholder="部署脚本" />
            </Form.Item>
          </Space>
          <Form.Item
            name="templateText"
            label="模板正文（shell，用 ${参数名} 占位）"
            rules={[{ required: true, message: '必填' }]}
          >
            <Input.TextArea rows={7} style={{ fontFamily: 'ui-monospace, Consolas, monospace' }} placeholder={'#!/bin/sh\necho "deploy ${artifact} to ${env}"'} />
          </Form.Item>
          <Form.Item name="allowed" label="允许使用的能力（留空=不限）">
            <Checkbox.Group options={CAPABILITIES} />
          </Form.Item>
          <Form.Item name="params" label="参数 schema（可选）">
            <Space direction="vertical" style={{ width: '100%' }}>
              <Form.List name="params">
                {(fields, { add, remove }) => (
                  <>
                    {fields.map(({ key, name }) => (
                      <Space key={key} align="baseline">
                        <Form.Item name={[name, 'name']} rules={[{ required: true, message: '参数名必填' }]}>
                          <Input placeholder="参数名 (env)" style={{ width: 130 }} />
                        </Form.Item>
                        <Form.Item name={[name, 'label']}>
                          <Input placeholder="标签（环境）" style={{ width: 140 }} />
                        </Form.Item>
                        <Form.Item name={[name, 'defaultValue']}>
                          <Input placeholder="默认值" style={{ width: 140 }} />
                        </Form.Item>
                        <Form.Item name={[name, 'required']} valuePropName="checked">
                          <Checkbox>必填</Checkbox>
                        </Form.Item>
                        <Button type="text" danger onClick={() => remove(name)}>删</Button>
                      </Space>
                    ))}
                    <Button type="dashed" onClick={() => add({ required: false })}>+ 添加参数</Button>
                  </>
                )}
              </Form.List>
            </Space>
          </Form.Item>
        </Form>
      </Drawer>
    </Space>
  )
}
