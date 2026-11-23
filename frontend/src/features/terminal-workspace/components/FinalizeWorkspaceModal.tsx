// CAP-115 M3 终端工作区收口确认弹窗：confirmCode（=工作区键）服务端硬校验前的客户端同口径校验 +
// 丢弃改动/删远端分支选项 + 显式「ff-merge 并 push 到共享基线」后果说明。
// 脏工作区且未勾选丢弃时服务端 409（列前 3 个改动文件），错误透传不吞。
import { useState } from 'react'
import { Alert, Checkbox, Form, Input, Modal, Typography, message } from 'antd'
import { finalizeTerminalWorkspace } from '../api'
import { showError } from '../../../shared/utils/showError'
import { showFinalizeDetail } from './finalizeDetail'
import type { TerminalWorkspaceView } from '../types'

interface Props {
  ws: TerminalWorkspaceView
  open: boolean
  onCancel: () => void
  /** 收口成功后回调（父级刷新列表/详情） */
  onDone: () => void
}

export default function FinalizeWorkspaceModal({ ws, open, onCancel, onDone }: Props) {
  const [form] = Form.useForm<{ confirmCode: string }>()
  const [discardChanges, setDiscardChanges] = useState(false)
  const [deleteRemoteBranch, setDeleteRemoteBranch] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const onOk = async () => {
    const v = await form.validateFields()
    setSubmitting(true)
    try {
      const r = await finalizeTerminalWorkspace(ws.id, {
        confirmCode: v.confirmCode.trim(),
        discardChanges,
        deleteRemoteBranch,
      })
      message.success(`工作区 ${ws.workspaceKey} 已收口`)
      onDone()
      if (r.detail) showFinalizeDetail(r.detail, r.finalizedAt)
    } catch (e) {
      showError(e, '收口失败（工作区已保留，可处理后重试）')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title={`收口终端工作区 ${ws.workspaceKey}`}
      open={open}
      centered
      okText="确认收口"
      okButtonProps={{ danger: true }}
      cancelText="取消"
      confirmLoading={submitting}
      onOk={() => void onOk()}
      onCancel={onCancel}
      destroyOnHidden
    >
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message={
          <>
            将把工作区分支 <Typography.Text code>{ws.branch}</Typography.Text> 的改动 ff-merge 并 push
            到共享基线 <Typography.Text code>{ws.baseBranch || '项目默认基线'}</Typography.Text>
            ，成功后工作区置为「已收口」（工作树保留，改动已进入基线）。
          </>
        }
      />
      <Form form={form} layout="vertical">
        <Form.Item
          name="confirmCode"
          label={`请输入工作区键 ${ws.workspaceKey} 以确认收口`}
          rules={[
            { required: true, message: '请输入工作区键' },
            {
              validator: (_, value: string) =>
                !value || value.trim() === ws.workspaceKey
                  ? Promise.resolve()
                  : Promise.reject(new Error(`确认码不符，请输入 ${ws.workspaceKey}`)),
            },
          ]}
        >
          <Input placeholder={ws.workspaceKey} allowClear />
        </Form.Item>
      </Form>
      <Checkbox checked={discardChanges} onChange={(e) => setDiscardChanges(e.target.checked)}>
        丢弃未提交改动（runner 侧 reset --hard + clean -fd 后再合并）
      </Checkbox>
      <br />
      <Checkbox
        checked={deleteRemoteBranch}
        onChange={(e) => setDeleteRemoteBranch(e.target.checked)}
        style={{ marginTop: 8 }}
      >
        收口成功后删除远端分支 {ws.branch}
      </Checkbox>
    </Modal>
  )
}
