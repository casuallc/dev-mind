// CAP-02 统一的新建/编辑项目表单：后台项目列表与项目设置页共用。
// CAP-23：仓库来源支持 本地路径 / 从 Git 克隆（GitLab/GitHub，异步克隆到服务端工作区）。
import { useEffect, useState } from 'react'
import { Form, Input, Modal, Radio, Select, Switch, message } from 'antd'
import { createProject, updateProject } from '../api'
import type { Project, ProjectInput } from '../types'
import { useGitIntegrations } from '../hooks/useGitIntegrations'

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
  const sourceType = Form.useWatch('sourceType', form) ?? 'LOCAL'
  const { options: integrationOptions } = useGitIntegrations()
  // 编辑存量项目时禁用来源切换（sourceType 创建后不可变，避免目录语义混乱）
  const editing = project != null

  useEffect(() => {
    if (!open) return
    form.setFieldsValue(
      project
        ? { ...project, sourceType: project.sourceType ?? 'LOCAL', integrationId: undefined }
        : {
            name: '',
            sourceType: 'LOCAL',
            path: '',
            remoteUrl: '',
            integrationId: undefined,
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
      // CLONE 模式不带 path（服务端计算）；LOCAL 不带克隆字段
      const payload: ProjectInput =
        values.sourceType === 'CLONE'
          ? {
              ...values,
              path: undefined,
              // 编辑时留空 = 保持不变（当前地址到 项目设置-仓库 查看/修改）
              remoteUrl: values.remoteUrl || undefined,
            }
          : { ...values, remoteUrl: undefined, integrationId: undefined }
      if (project) {
        await updateProject(project.id, payload)
        message.success('已更新')
      } else {
        await createProject(payload)
        message.success(
          payload.sourceType === 'CLONE' ? '已创建，后台开始克隆（进度见项目设置-仓库）' : '已创建',
        )
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
          label="仓库来源"
          name="sourceType"
          extra={editing ? '仓库来源创建后不可变更' : '克隆模式：服务端从 GitLab/GitHub 拉取到工作区（按项目分目录）'}
        >
          <Radio.Group disabled={editing} optionType="button" buttonStyle="solid">
            <Radio.Button value="LOCAL">本地路径</Radio.Button>
            <Radio.Button value="CLONE">从 Git 克隆</Radio.Button>
          </Radio.Group>
        </Form.Item>
        {sourceType === 'CLONE' ? (
          <>
            <Form.Item
              label="远端仓库地址"
              name="remoteUrl"
              rules={[{ required: !editing, message: '请输入 http/https 仓库地址' }]}
              extra={
                editing
                  ? '留空保持不变；修改后请到 项目设置-仓库 触发重新克隆'
                  : '仅支持 http/https（PAT 注入认证），不支持 ssh'
              }
            >
              <Input placeholder="https://gitlab.example.com/group/my-repo.git" />
            </Form.Item>
            <Form.Item
              label="集成实例（认证）"
              name="integrationId"
              extra="私有仓库选择对应 GitLab/GitHub 实例（PAT 克隆）；公开仓库可不选 = 匿名克隆"
            >
              <Select
                allowClear
                placeholder="不选 = 公开仓库匿名克隆"
                options={integrationOptions}
              />
            </Form.Item>
          </>
        ) : (
          <Form.Item
            label="本地仓库路径"
            name="path"
            rules={[{ required: true, message: '请输入本地 git 仓库绝对路径' }]}
            extra="必须是本地 git 仓库（含 .git），如 D:/code/my-repo"
          >
            <Input placeholder="D:/code/my-repo" />
          </Form.Item>
        )}
        <Form.Item
          label="默认分支"
          name="defaultBranch"
          extra={sourceType === 'CLONE' ? '留空则克隆成功后自动探测远端默认分支' : undefined}
        >
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
