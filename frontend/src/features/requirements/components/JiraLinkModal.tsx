// CAP-112：把 LOCAL 需求手动关联到**已存在**的 Jira issue（区别于 CAP-47 推送：issue 不是本次创建的）。
// 实例下拉复用推送弹窗的 push-targets 数据源（服务端不抛错，弹窗一定打得开）；
// issueKey 前端按同一正则即时校验，服务端还会再校验一次（trim + 大写归一化）。
// 关联即托管：标题/描述/类型/优先级等托管字段立即以 Jira 值刷新、本地只读——弹窗显著警示，不可撤销。
// 表单仅 2 个字段，按布局约定用 Modal（≤4 字段）。
import { useEffect, useState } from 'react'
import { Alert, Empty, Form, Input, Modal, Select, Spin, Typography, message } from 'antd'
import { Link } from 'react-router-dom'
import { getJiraPushTargets, linkJiraIssue } from '../api'
import type { JiraPushResult, JiraPushTargets, Requirement } from '../types'
import { isAdmin } from '../../auth/authStore'
import { showError } from '../../../shared/utils/showError'

/** 与服务端 JiraPushService.ISSUE_KEY_PATTERN 同口径（项目 key-数字，如 PROJ-123；大小写不敏感，提交前归一） */
const ISSUE_KEY_PATTERN = /^[A-Z][A-Z0-9_]*-\d+$/i

const LINK_WARNING = '关联后需求转为 Jira 托管：标题/描述/类型/优先级等字段将以 Jira 为准并立即刷新，'
  + '本地变为只读。此操作不可撤销。'

type FormValues = {
  integrationId?: number
  issueKey: string
}

export default function JiraLinkModal({ requirement, open, onClose, onLinked }: {
  requirement: Requirement
  open: boolean
  onClose: () => void
  onLinked: (r: JiraPushResult) => void
}) {
  const [form] = Form.useForm<FormValues>()
  const [targets, setTargets] = useState<JiraPushTargets | null>(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const pid = requirement.projectId
  const rid = requirement.id
  const noInstance = targets != null && targets.instances.length === 0

  useEffect(() => {
    if (!open) return
    setLoading(true)
    setTargets(null)
    getJiraPushTargets(pid, rid)
      .then((t) => {
        setTargets(t)
        // 默认实例带入（与推送弹窗同口径：同步配置的实例优先，否则候选首条）
        if (t.defaultIntegrationId != null) {
          form.setFieldValue('integrationId', t.defaultIntegrationId)
        }
      })
      .catch((e) => showError(e, '加载 Jira 实例失败'))
      .finally(() => setLoading(false))
  }, [open, pid, rid, form])

  const submit = async (values: FormValues) => {
    if (values.integrationId == null) return
    setBusy(true)
    try {
      const result = await linkJiraIssue(pid, rid, {
        integrationId: values.integrationId,
        issueKey: values.issueKey.trim(),
      })
      message.success(`已关联 Jira issue ${result.externalKey}，需求转为 Jira 托管`)
      onClose()
      onLinked(result)
    } catch (e) {
      // 失败保持弹窗打开（key 打错/冲突都需用户改了重试）
      showError(e, '关联 Jira issue 失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title="关联 Jira issue"
      open={open}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText="关联"
      okButtonProps={{ loading: busy, disabled: noInstance }}
      cancelButtonProps={{ disabled: busy }}
      maskClosable={!busy}
      destroyOnHidden
      width={520}
    >
      {loading && <Spin style={{ display: 'block', marginBottom: 12 }} />}
      {!loading && noInstance ? (
        <Empty description="没有可用的 Jira 实例">
          <Typography.Text type="secondary">
            {isAdmin() ? (
              <>请先到 <Link to="/admin/integrations">平台集成</Link> 新建「JIRA」类型实例并启用。</>
            ) : (
              <>请联系管理员在「后台管理 → 平台集成」新建「JIRA」类型实例并启用。</>
            )}
          </Typography.Text>
        </Empty>
      ) : (
        <>
          {targets && <Alert type="warning" showIcon message={LINK_WARNING} style={{ marginBottom: 12 }} />}
          <Form form={form} layout="vertical" onFinish={submit} preserve={false}>
            <Form.Item label="Jira 实例" name="integrationId"
              rules={[{ required: true, message: '请选择实例' }]}>
              <Select
                placeholder="选择 Jira 实例"
                options={(targets?.instances ?? []).map((i) => ({
                  value: i.id,
                  label: i.name,
                  title: i.baseUrl,
                }))}
              />
            </Form.Item>
            <Form.Item label="Issue Key" name="issueKey"
              extra="已存在的 Jira issue 编号，如 PROJ-123"
              rules={[
                { required: true, message: '请输入 issue key' },
                { pattern: ISSUE_KEY_PATTERN, message: '格式为 项目key-数字，如 PROJ-123' },
              ]}>
              <Input placeholder="PROJ-123" style={{ width: 240 }} maxLength={64} />
            </Form.Item>
          </Form>
        </>
      )}
    </Modal>
  )
}
