// CAP-104 套件资产属性：功能模块、负责人、标签、固定 commit、来源资产（引用已通过评审的资产），
// 以及套件评审（APPROVED 后才允许作为回归资产执行）。属性与评审分别走不同接口。
import { Alert, Button, Drawer, Divider, Flex, Form, Input, Modal, Select, Space, Tag, message } from 'antd'
import { useEffect, useState } from 'react'
import {
  listAssets,
  listModules,
  reviewSuite,
  updateSuiteMeta,
} from '../api'
import type { SuiteMetaInput, TestAsset, TestModule, TestSuite } from '../types'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'
import { REVIEW_COLOR, REVIEW_LABEL } from '../constants'

interface FormValues {
  moduleId?: number | null
  owner?: string
  tags?: string
  commitHash?: string
  sourceAssetId?: number | null
}

export default function SuiteAssetDrawer({ open, suite, projectId, canEdit, canReview, onClose, onSaved }: {
  open: boolean
  suite: TestSuite | null
  projectId: string
  canEdit: boolean
  canReview: boolean
  onClose: () => void
  onSaved: (s: TestSuite) => void
}) {
  const [modules, setModules] = useState<TestModule[]>([])
  const [assets, setAssets] = useState<TestAsset[]>([])
  const [saving, setSaving] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  const [form] = Form.useForm<FormValues>()

  useEffect(() => {
    if (!open || !suite) return
    setModules([])
    setAssets([])
    listModules(projectId).then(setModules).catch(() => {})
    listAssets(projectId, 'APPROVED').then(setAssets).catch(() => {})
    form.setFieldsValue({
      moduleId: suite.moduleId,
      owner: suite.owner ?? '',
      tags: suite.tags ?? '',
      commitHash: suite.commitHash ?? '',
      sourceAssetId: suite.sourceAssetId,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, suite?.id])

  const save = async () => {
    if (!suite) return
    const v = await form.validateFields()
    const input: SuiteMetaInput = {
      moduleId: v.moduleId ?? null,
      owner: v.owner?.trim() || undefined,
      tags: v.tags?.trim() || undefined,
      commitHash: v.commitHash?.trim() || undefined,
      sourceAssetId: v.sourceAssetId ?? null,
    }
    setSaving(true)
    try {
      const updated = await updateSuiteMeta(suite.id, input)
      message.success('套件资产属性已保存')
      onSaved(updated)
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const review = (status: 'APPROVED' | 'DEPRECATED') => {
    if (!suite) return
    Modal.confirm({
      centered: true,
      title: status === 'APPROVED' ? `评审通过「${suite.name}」？` : `废弃「${suite.name}」？`,
      content: status === 'APPROVED'
        ? '通过后该套件可作为回归资产被方案引用执行。'
        : '废弃后不可被方案新增引用，历史运行记录保留。',
      okText: status === 'APPROVED' ? '通过' : '废弃',
      okButtonProps: status === 'DEPRECATED' ? { danger: true } : undefined,
      cancelText: '取消',
      onOk: async () => {
        setReviewing(true)
        try {
          const updated = await reviewSuite(suite.id, status)
          message.success(status === 'APPROVED' ? '已通过评审' : '已废弃')
          onSaved(updated)
        } catch (e) {
          showError(e, '评审失败')
        } finally {
          setReviewing(false)
        }
      },
    })
  }

  return (
    <Drawer
      title={suite ? `套件资产属性 · ${suite.name}` : '套件资产属性'}
      width={DRAWER_DEFAULT}
      open={open}
      onClose={onClose}
      destroyOnHidden
      footer={
        <Flex justify="flex-end" gap={8}>
          <Button onClick={onClose}>关闭</Button>
          {canEdit && <Button type="primary" loading={saving} onClick={save}>保存属性</Button>}
        </Flex>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="模块用于功能目录归组（未选 = 未分类）；来源资产必须是本项目中已评审通过的资产，固定 commit 保证可复现。"
      />
      <Form form={form} layout="vertical" disabled={!canEdit}>
        <Form.Item label="功能模块" name="moduleId">
          <Select
            allowClear
            placeholder="未分类"
            options={modules.map((m) => ({ value: m.id, label: m.name }))}
          />
        </Form.Item>
        <Flex gap={8} align="start">
          <Form.Item label="负责人" name="owner" style={{ flex: 1 }}>
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item label="标签" name="tags" style={{ flex: 1 }}>
            <Input placeholder="逗号分隔，可选" />
          </Form.Item>
        </Flex>
        <Flex gap={8} align="start">
          <Form.Item label="固定 Commit（可选）" name="commitHash" style={{ flex: 1 }}
            tooltip="7~64 位十六进制；来源资产的检出点">
            <Input placeholder="a1b2c3d" />
          </Form.Item>
          <Form.Item label="来源资产（可选）" name="sourceAssetId" style={{ flex: 1 }}>
            <Select
              allowClear
              placeholder="不引用资产"
              options={assets.map((a) => ({ value: a.id, label: `#${a.id} ${a.name}` }))}
            />
          </Form.Item>
        </Flex>
      </Form>

      <Divider style={{ margin: '8px 0 16px' }} />
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space>
          <span>套件评审：</span>
          {suite?.reviewStatus
            ? <Tag color={REVIEW_COLOR[suite.reviewStatus]}>{REVIEW_LABEL[suite.reviewStatus]}</Tag>
            : <Tag>未设置</Tag>}
        </Space>
        {canReview && (
          <Space>
            {suite?.reviewStatus !== 'APPROVED' && (
              <Button size="small" loading={reviewing} onClick={() => review('APPROVED')}>评审通过</Button>
            )}
            {suite?.reviewStatus !== 'DEPRECATED' && (
              <Button size="small" danger loading={reviewing} onClick={() => review('DEPRECATED')}>废弃</Button>
            )}
          </Space>
        )}
      </Space>
    </Drawer>
  )
}
