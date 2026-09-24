import { Button, Checkbox, Divider, Drawer, Form, Input, Select, Space, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { useEffect } from 'react'
import type { Bookmark, BookmarkGroup } from '../types'

/** FR-01/05 表单口径：账号整组提交（带 id 更新 / 无 id 新建 / 未出现即删除），密码留空 = 不修改 */
export interface BookmarkFormValues {
  title: string
  url: string
  description?: string
  groupId?: number | null
  /** 标签用**名字**提交（服务端同名幂等），省掉「先建标签再挂」两步 */
  tagNames?: string[]
  accounts?: Array<{
    id?: string
    hasPassword?: boolean
    label: string
    username?: string
    password?: string
    clearPassword?: boolean
    note?: string
  }>
}

interface Props {
  open: boolean
  /** null = 新建 */
  bookmark: Bookmark | null
  /** 新建时的默认落点分组（跟随侧栏当前选中） */
  defaultGroupId: number | null
  groups: BookmarkGroup[]
  tagNames: string[]
  saving: boolean
  onClose: () => void
  onSubmit: (values: BookmarkFormValues) => void
}

/** 分组下拉的可选项（缩进体现层级，未分组单列） */
function groupOptions(groups: BookmarkGroup[], depth = 0): { value: number; label: string }[] {
  return groups.flatMap((g) => [
    { value: g.id, label: `${'　'.repeat(depth)}${g.name}` },
    ...groupOptions(g.children, depth + 1),
  ])
}

/**
 * 收藏新建/编辑抽屉（FR-01 + FR-02 归组 + FR-03 标签 + FR-05 账号）。
 * 打开时用 destroyOnHidden 重建表单，故取值一律走 getFieldsValue(true)（字段可能未挂载）。
 */
export default function BookmarkDrawer({
  open,
  bookmark,
  defaultGroupId,
  groups,
  tagNames,
  saving,
  onClose,
  onSubmit,
}: Props) {
  const [form] = Form.useForm<BookmarkFormValues>()

  useEffect(() => {
    if (!open) return
    form.resetFields()
    if (bookmark) {
      form.setFieldsValue({
        title: bookmark.title,
        url: bookmark.url,
        description: bookmark.description,
        groupId: bookmark.groupId ?? null,
        tagNames: bookmark.tags.map((t) => t.name),
        accounts: bookmark.accounts.map((a) => ({
          id: a.id,
          hasPassword: a.hasPassword,
          label: a.label,
          username: a.username,
          password: undefined,
          clearPassword: false,
          note: a.note,
        })),
      })
    } else {
      form.setFieldsValue({ groupId: defaultGroupId, accounts: [] })
    }
  }, [open, bookmark, defaultGroupId, form])

  const submit = async () => {
    await form.validateFields()
    onSubmit(form.getFieldsValue(true))
  }

  return (
    <Drawer
      title={bookmark ? `编辑收藏：${bookmark.title}` : '新建收藏'}
      width={620}
      open={open}
      onClose={onClose}
      destroyOnHidden
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={submit}>
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" initialValues={{ accounts: [] }}>
        <Form.Item name="title" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="如：Nexus 私服" maxLength={256} />
        </Form.Item>
        <Form.Item
          name="url"
          label="地址"
          rules={[
            { required: true, message: '请输入地址' },
            {
              validator: (_, v: string) =>
                !v || /^https?:\/\//i.test(v.trim())
                  ? Promise.resolve()
                  : Promise.reject(new Error('只支持 http/https 地址')),
            },
          ]}
          extra="同地址重复收藏不拦截（不同环境参数是合法场景），保存时会提示"
        >
          <Input placeholder="https://nexus.example.com/repository/maven-public/" />
        </Form.Item>
        <Form.Item name="groupId" label="分组">
          <Select
            allowClear
            placeholder="未分组"
            options={groupOptions(groups)}
            style={{ maxWidth: 320 }}
          />
        </Form.Item>
        <Form.Item
          name="tagNames"
          label="标签"
          extra="输入回车即新建并挂上（同名标签自动复用）"
        >
          <Select
            mode="tags"
            placeholder="如：内网、运维"
            options={tagNames.map((n) => ({ value: n, label: n }))}
            style={{ maxWidth: 420 }}
          />
        </Form.Item>
        <Form.Item name="description" label="备注">
          <Input.TextArea rows={2} maxLength={1024} placeholder="这条入口是干什么的" />
        </Form.Item>

        <Divider orientation="left" style={{ marginTop: 0 }}>
          关联账号
        </Divider>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          密码加密存储，列表只显示掩码；明文可在表格里点眼睛图标按次查看。编辑时密码留空 = 保持原密码。
        </Typography.Paragraph>
        <Form.List name="accounts">
          {(fields, { add, remove }) => (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {fields.map((field) => (
                <div
                  key={field.key}
                  style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}
                >
                  <Form.Item name={[field.name, 'id']} hidden>
                    <Input />
                  </Form.Item>
                  <Form.Item name={[field.name, 'hasPassword']} hidden>
                    <Input />
                  </Form.Item>
                  <Form.Item
                    name={[field.name, 'label']}
                    rules={[{ required: true, message: '填写用途' }]}
                    style={{ marginBottom: 0, width: 120 }}
                  >
                    <Input placeholder="用途，如管理员" maxLength={128} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'username']} style={{ marginBottom: 0, width: 150 }}>
                    <Input placeholder="用户名" maxLength={256} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'password']} style={{ marginBottom: 0, width: 160 }}>
                    <Input.Password placeholder="密码（留空不改）" autoComplete="new-password" />
                  </Form.Item>
                  <Form.Item name={[field.name, 'note']} style={{ marginBottom: 0, flex: 1, minWidth: 100 }}>
                    <Input placeholder="备注" maxLength={512} />
                  </Form.Item>
                  <Form.Item
                    noStyle
                    shouldUpdate={(prev, cur) =>
                      prev.accounts?.[field.name]?.hasPassword !== cur.accounts?.[field.name]?.hasPassword
                    }
                  >
                    {() =>
                      form.getFieldValue(['accounts', field.name, 'hasPassword']) ? (
                        <Form.Item name={[field.name, 'clearPassword']} valuePropName="checked" noStyle>
                          <Checkbox>清空</Checkbox>
                        </Form.Item>
                      ) : null
                    }
                  </Form.Item>
                  <Button type="text" danger icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
                </div>
              ))}
              <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ label: '', clearPassword: false })}>
                添加账号
              </Button>
            </div>
          )}
        </Form.List>
      </Form>
    </Drawer>
  )
}
