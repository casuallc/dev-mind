// Skill 新建/编辑抽屉：scope/projectId 仅创建时可改；contentMd 为 SKILL.md 正文，支持 Markdown 预览。
import { useEffect } from 'react'
import { Button, Drawer, Form, Input, Select, Space, Tabs, message } from 'antd'
import { createSkill, updateSkill } from '../api'
import type { SkillDetail, SkillInput } from '../types'
import type { Project } from '../../projects/types'
import Markdown from '../../docs/components/Markdown'

const NAME_RULE = /^[a-z0-9]+(-[a-z0-9]+)*$/

export default function SkillFormDrawer({ open, editing, projects, onClose, onSaved }: {
  open: boolean
  editing: SkillDetail | null
  projects: Project[]
  onClose: () => void
  onSaved: () => void
}) {
  const [form] = Form.useForm<SkillInput>()
  const contentMd = Form.useWatch('contentMd', form)

  useEffect(() => {
    if (open) {
      if (editing) {
        form.setFieldsValue({
          scope: editing.skill.scope,
          projectId: editing.skill.projectId ?? undefined,
          name: editing.skill.name,
          description: editing.skill.description,
          contentMd: editing.contentMd ?? '',
          tags: editing.skill.tags,
          status: editing.skill.status,
        })
      } else {
        form.setFieldsValue({
          scope: 'GLOBAL', projectId: undefined, name: '', description: '',
          contentMd: '', tags: [], status: 'ACTIVE',
        })
      }
    }
  }, [open, editing, form])

  const onSave = async (v: SkillInput) => {
    try {
      if (editing) {
        await updateSkill(editing.skill.id, v)
      } else {
        await createSkill(v)
      }
      message.success('已保存')
      onClose()
      onSaved()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  return (
    <Drawer
      title={editing ? `编辑 Skill「${editing.skill.name}」` : '新增 Skill'}
      open={open}
      onClose={onClose}
      width={720}
      destroyOnHidden
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={() => form.submit()}>
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" onFinish={onSave} preserve={false}>
        <Form.Item label="范围" name="scope" rules={[{ required: true }]}
          tooltip="全局 skill 跨项目共享；项目 skill 仅所属项目可用。创建后不可变更">
          <Select
            disabled={editing != null}
            options={[
              { value: 'GLOBAL', label: '全局（所有项目可用）' },
              { value: 'PROJECT', label: '项目（仅所选项目可用）' },
            ]}
          />
        </Form.Item>
        <Form.Item noStyle shouldUpdate>
          {({ getFieldValue }) =>
            getFieldValue('scope') === 'PROJECT' && (
              <Form.Item label="项目" name="projectId" rules={[{ required: true, message: '请选择项目' }]}>
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="选择项目"
                  disabled={editing != null}
                  options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))}
                />
              </Form.Item>
            )
          }
        </Form.Item>
        <Form.Item
          label="名称"
          name="name"
          rules={[
            { required: true, message: '请输入名称' },
            { pattern: NAME_RULE, message: '须为 kebab-case（小写字母/数字/中划线）' },
          ]}
          tooltip="将作为 .claude/skills/<name>/ 目录名与 SKILL.md frontmatter 的 name"
        >
          <Input placeholder="如 api-design" maxLength={64} />
        </Form.Item>
        <Form.Item
          label="描述"
          name="description"
          rules={[
            { required: true, message: '请输入描述' },
            { max: 500, message: '描述过长（≤500）' },
          ]}
          tooltip="SKILL.md frontmatter 的 description——agent 据此判断何时使用该 skill，请写清适用场景"
        >
          <Input.TextArea rows={2} placeholder="这个 skill 做什么、什么时候该用它" />
        </Form.Item>
        <Form.Item label="标签" name="tags">
          <Select mode="tags" open={false} placeholder="回车添加（为按项目匹配注入预留）" />
        </Form.Item>
        <Form.Item label="SKILL.md 正文">
          <Tabs
            size="small"
            items={[
              {
                key: 'edit',
                label: '编辑',
                children: (
                  <Form.Item name="contentMd" noStyle>
                    <Input.TextArea
                      rows={12}
                      placeholder="Markdown 正文（frontmatter 由上面的名称/描述自动生成）"
                    />
                  </Form.Item>
                ),
              },
              { key: 'preview', label: '预览', children: <Markdown content={contentMd ?? ''} /> },
            ]}
          />
        </Form.Item>
      </Form>
    </Drawer>
  )
}
