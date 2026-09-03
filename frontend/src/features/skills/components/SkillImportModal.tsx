// Skill 导入弹窗（短表单）：上传 zip 压缩包（根目录或单层目录包裹 SKILL.md），
// 同名默认报错，可勾选覆盖已存在同名 skill。
import { useEffect, useState } from 'react'
import { Form, Modal, Select, Switch, Upload, message } from 'antd'
import type { UploadFile } from 'antd'
import { InboxOutlined } from '@ant-design/icons'
import { importSkillPackage } from '../api'
import type { SkillScope } from '../types'
import type { Project } from '../../projects/types'

interface ImportForm {
  scope: SkillScope
  projectId?: string
  overwrite: boolean
}

export default function SkillImportModal({ open, projects, onClose, onSaved }: {
  open: boolean
  projects: Project[]
  onClose: () => void
  onSaved: () => void
}) {
  const [form] = Form.useForm<ImportForm>()
  const scope = Form.useWatch('scope', form)
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [importing, setImporting] = useState(false)

  useEffect(() => {
    if (open) {
      form.setFieldsValue({ scope: 'GLOBAL', projectId: undefined, overwrite: false })
      setFileList([])
    }
  }, [open, form])

  const onOk = async () => {
    const v = await form.validateFields()
    const file = fileList[0]?.originFileObj
    if (!file) {
      message.warning('请选择 zip 压缩包')
      return
    }
    setImporting(true)
    try {
      const s = await importSkillPackage({
        scope: v.scope,
        projectId: v.scope === 'PROJECT' ? v.projectId : undefined,
        overwrite: v.overwrite,
        file,
      })
      message.success(`已导入 skill「${s.name}」（${s.fileCount} 个附件）`)
      onClose()
      onSaved()
    } catch (e) {
      message.error(`导入失败：${(e as Error).message}`)
    } finally {
      setImporting(false)
    }
  }

  return (
    <Modal
      title="导入 Skill 压缩包"
      open={open}
      onCancel={onClose}
      onOk={onOk}
      okText="导入"
      cancelText="取消"
      confirmLoading={importing}
      destroyOnHidden
      centered
    >
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item label="范围" name="scope" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'GLOBAL', label: '全局（所有项目可用）' },
              { value: 'PROJECT', label: '项目（仅所选项目可用）' },
            ]}
          />
        </Form.Item>
        {scope === 'PROJECT' && (
          <Form.Item label="项目" name="projectId" rules={[{ required: true, message: '请选择项目' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择项目"
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))}
            />
          </Form.Item>
        )}
        <Form.Item
          label="覆盖已存在的同名 skill"
          name="overwrite"
          valuePropName="checked"
          tooltip="不勾选时同名 skill 导入会报错；勾选后替换其正文与全部附件（标签/状态保留）"
        >
          <Switch />
        </Form.Item>
        <Form.Item label="压缩包" required>
          <Upload.Dragger
            accept=".zip"
            maxCount={1}
            fileList={fileList}
            beforeUpload={() => false}
            onChange={({ fileList: fl }) => setFileList(fl.slice(-1))}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽 zip 到此处</p>
            <p className="ant-upload-hint">
              包内需含 SKILL.md（根目录或单层目录包裹均可），其余文件作为附件导入
            </p>
          </Upload.Dragger>
        </Form.Item>
      </Form>
    </Modal>
  )
}
