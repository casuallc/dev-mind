// CAP-03 文档编辑抽屉：类型/标题/标签元数据 + 正文（Markdown）一窗维护。
// 元数据走 PUT /documents/{id}；正文变更走保存新版本（frozen 状态必填变更说明），两者可同次提交。
import { useEffect } from 'react'
import { Button, Drawer, Form, Input, Select, Space, message } from 'antd'
import { saveDocVersion, updateDoc } from '../api'
import { KIND_LABEL } from '../types'
import type { DocDetail, DocKind } from '../types'
import MarkdownEditor from '../../../shared/components/MarkdownEditor'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'

interface DocEditForm {
  kind: DocKind
  title: string
  tags: string[]
  contentMd: string
  changeNote?: string
}

export default function DocEditDrawer({ open, doc, onClose, onSaved }: {
  open: boolean
  doc: DocDetail
  onClose: () => void
  onSaved: () => void
}) {
  const [form] = Form.useForm<DocEditForm>()
  const frozen = doc.status === 'frozen'

  useEffect(() => {
    if (open) {
      form.setFieldsValue({
        kind: doc.kind,
        title: doc.title,
        tags: doc.tags,
        contentMd: doc.contentMd,
        changeNote: undefined,
      })
    }
  }, [open, doc, form])

  const onSave = async (v: DocEditForm) => {
    const metaChanged =
      v.kind !== doc.kind ||
      v.title !== doc.title ||
      JSON.stringify(v.tags ?? []) !== JSON.stringify(doc.tags)
    const contentChanged = v.contentMd !== doc.contentMd
    if (!metaChanged && !contentChanged) {
      message.info('内容未变更')
      return
    }
    try {
      if (metaChanged) {
        await updateDoc(doc.id, { kind: v.kind, title: v.title, tags: v.tags ?? [] })
      }
      if (contentChanged) {
        const updated = await saveDocVersion(doc.id, { contentMd: v.contentMd, changeNote: v.changeNote })
        message.success(`已保存为 v${updated.versionNo}`)
      } else {
        message.success('已保存')
      }
      onClose()
      onSaved()
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  return (
    <Drawer
      title={`编辑文档「${doc.title}」`}
      open={open}
      onClose={onClose}
      width={DRAWER_SCREEN}
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
        <Form.Item label="类型" name="kind" rules={[{ required: true }]}>
          <Select options={Object.entries(KIND_LABEL).map(([value, label]) => ({ value, label }))} />
        </Form.Item>
        <Form.Item label="标题" name="title" rules={[{ required: true, message: '请填写标题' }]}>
          <Input placeholder="如：登录改造需求文档" />
        </Form.Item>
        <Form.Item label="标签" name="tags">
          <Select mode="tags" open={false} placeholder="回车添加标签" />
        </Form.Item>
        <Form.Item label="内容" name="contentMd" rules={[{ required: true, message: '请填写内容' }]}>
          <MarkdownEditor height={460} attachmentUpload={{}} placeholder="Markdown…（可直接粘贴/拖拽图片插入）" />
        </Form.Item>
        <Form.Item
          label="变更说明"
          name="changeNote"
          extra={frozen ? '文档已冻结（FR-04），正文变更必须填写说明' : '仅正文变更时随新版本记录，可选'}
          rules={[
            {
              validator: (_, value) =>
                frozen &&
                form.getFieldValue('contentMd') !== doc.contentMd &&
                (value == null || !String(value).trim())
                  ? Promise.reject(new Error('文档已冻结，必须填写变更说明'))
                  : Promise.resolve(),
            },
          ]}
        >
          <Input.TextArea rows={2} placeholder="本次改了什么" />
        </Form.Item>
      </Form>
    </Drawer>
  )
}
