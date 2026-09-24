import { Form, Input, Modal, Typography } from 'antd'
import { useEffect } from 'react'

export interface ShareTarget {
  kind: 'bookmark' | 'group'
  /** 收藏或分组的 id（后端按 kind 决定传 bookmarkId 还是 groupId） */
  id: number
  /** 展示名（收藏标题 / 分组名） */
  name: string
}

interface Props {
  open: boolean
  target: ShareTarget | null
  saving: boolean
  onClose: () => void
  onOk: (targetUser: string) => void
}

/**
 * FR-07 分享（单条收藏或整个分组 → 平台内指定用户，只读引用）。
 * 接收人用 username 文本输入：平台无「非 ADMIN 可读的用户清单」端点，
 * 后端会校验用户存在性并给出 404 提示，不依赖 /auth/users。
 */
export default function ShareModal({ open, target, saving, onClose, onOk }: Props) {
  const [form] = Form.useForm<{ targetUser: string }>()

  useEffect(() => {
    if (open) form.resetFields()
  }, [open, form])

  return (
    <Modal
      title={target?.kind === 'group' ? `分享分组：${target.name}` : `分享收藏：${target?.name ?? ''}`}
      open={open}
      confirmLoading={saving}
      okText="分享"
      onCancel={onClose}
      destroyOnHidden
      onOk={async () => {
        const v = await form.validateFields()
        onOk(v.targetUser.trim())
      }}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="targetUser"
          label="接收人（username）"
          rules={[{ required: true, message: '请输入接收人的 username' }]}
        >
          <Input placeholder="如：zhangsan" maxLength={64} />
        </Form.Item>
      </Form>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
        {target?.kind === 'group'
          ? '分组分享含组内全部收藏（含子分组），之后新增的收藏自动进入分享范围。'
          : '分享是只读引用：你改他跟着变，一撤销他立刻看不到。'}
        {' '}账号只带用途与用户名，密码不会分享出去。
      </Typography.Paragraph>
    </Modal>
  )
}
