// CAP-02 统一的新建/编辑项目表单：后台项目列表与项目设置页共用。
import { useEffect, useState } from 'react'
import { Form, Input, Modal, Select, Switch, message } from 'antd'
import { createProject, updateProject } from '../api'
import type { Project, ProjectInput } from '../types'

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'ACTIVE' },
  { value: 'ARCHIVED', label: 'ARCHIVED' },
]

interface Props {
  open: boolean
  /** null 表示新建 */
  project: Project | null
  onCancel: () => void
  onSaved: () => void
}

export default function ProjectFormModal({ open, project, onCancel, onSaved }: Props) {
  const [form] = Form.useForm<ProjectInput>()
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!open) return
    form.setFieldsValue(
      project ?? {
        name: '',
        path: '',
        defaultBranch: 'master',
        tags: [],
        description: '',
        status: 'ACTIVE',
        apiDocSource: '',
        autoRegressionOnDeploy: false,
      },
    )
  }, [open, project, form])

  const onSave = async (values: ProjectInput) => {
    setSaving(true)
    try {
      if (project) {
        await updateProject(project.id, values)
        message.success('已更新')
      } else {
        await createProject(values)
        message.success('已创建')
      }
      onSaved()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      title={project ? '编辑项目' : '新建项目'}
      open={open}
      onCancel={onCancel}
      onOk={() => form.submit()}
      confirmLoading={saving}
      okText="保存"
      width={560}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" onFinish={onSave}>
        <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="如 在线商城后端" />
        </Form.Item>
        <Form.Item
          label="本地仓库路径"
          name="path"
          rules={[{ required: true, message: '请输入本地 git 仓库绝对路径' }]}
          extra="必须是本地 git 仓库（含 .git），如 D:/code/my-repo"
        >
          <Input placeholder="D:/code/my-repo" />
        </Form.Item>
        <Form.Item label="默认分支" name="defaultBranch">
          <Input placeholder="master / main" />
        </Form.Item>
        <Form.Item label="标签" name="tags" extra="如 java/spring/frontend，供知识库注入筛选">
          <Select mode="tags" placeholder="输入后回车添加" open={false} suffixIcon={null} />
        </Form.Item>
        <Form.Item label="描述" name="description">
          <Input.TextArea rows={2} placeholder="项目职责、目标（可选）" />
        </Form.Item>
        <Form.Item label="状态" name="status">
          <Select options={STATUS_OPTIONS} />
        </Form.Item>
        <Form.Item label="API 文档源" name="apiDocSource" extra="OpenAPI 文件路径，供测试套件生成（可选）">
          <Input placeholder="如 docs/openapi.yaml" />
        </Form.Item>
        <Form.Item
          label="部署成功后自动回归"
          name="autoRegressionOnDeploy"
          valuePropName="checked"
          extra="CAP-10 FR-05：部署单成功后自动对该项目全部套件跑一次回归"
        >
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  )
}
