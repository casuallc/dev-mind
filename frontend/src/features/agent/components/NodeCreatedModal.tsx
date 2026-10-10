import { Alert, Button, Modal, Steps, Tabs, Typography } from 'antd'
import { CodeOutlined, WindowsOutlined } from '@ant-design/icons'
import type { IssuedNode } from '../types'
import { installOneLiner, installScriptUrl, type InstallOs } from '../utils/installScript'

interface Props {
  /** 新建节点签发的 token（仅此一次可见）；null = 不展示 */
  issued: IssuedNode | null
  /** 尚未上传 runner 包时提示（脚本下载步骤会失败） */
  pkgMissing: boolean
  onClose: () => void
}

const serverUrl = () => `${location.origin.replace(/^http/, 'ws')}/ws/agent`

/**
 * CAP-21「节点已创建」弹窗：主操作是目标机一条命令（脚本由服务端按 ?token= 下发，已内嵌 token）；
 * 备用路径为下载脚本文件手工拷贝。token 复制块仅作留档（命令/脚本已内嵌，无需单独使用）。
 */
export default function NodeCreatedModal({ issued, pkgMissing, onClose }: Props) {
  const platformTab = (os: InstallOs) => {
    if (!issued) return null
    const isWin = os === 'windows'
    return (
      <>
        <Steps
          direction="vertical"
          size="small"
          current={-1}
          items={[
            {
              title: isWin ? '在目标机的管理员 PowerShell 中粘贴执行' : '在目标机终端粘贴执行',
              description: (
                <Typography.Paragraph
                  code
                  copyable
                  style={{ marginBottom: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}
                >
                  {installOneLiner(issued.node.id, os, issued.token)}
                </Typography.Paragraph>
              ),
            },
            {
              title: '回到本页确认节点已 ONLINE',
              description: (
                <Typography.Text type="secondary">
                  命令会下载安装脚本并执行：检查 JDK 21+、下载 runner 包、写 agent.properties，并默认注册
                  {isWin ? ' Windows 服务（WinSW）' : ' systemd 服务（devmind-agent）'}
                  ——开机自启、崩溃自拉起、自升级由服务管理器接管；无{isWin ? '管理员' : ' root '}
                  权限时自动降级为后台运行（{isWin ? '-NoService' : '--no-service'} 可强制后台模式）。
                </Typography.Text>
              ),
            },
          ]}
        />
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          无法联网直链时：
          <Typography.Link href={installScriptUrl(issued.node.id, os, issued.token)} download>
            {isWin ? <WindowsOutlined /> : <CodeOutlined />} 下载脚本文件
          </Typography.Link>
          手工拷贝到目标机执行。
        </Typography.Text>
      </>
    )
  }

  return (
    <Modal
      title="节点已创建（token 仅此一次展示）"
      open={!!issued}
      width={680}
      footer={
        <Button type="primary" onClick={onClose}>
          我已保存
        </Button>
      }
      onCancel={onClose}
    >
      {issued && (
        <>
          <Alert
            type="warning"
            showIcon
            message="下方安装命令与脚本已内嵌 token，关闭后无法再次查看（只能重建节点）；需要留档可复制保存。"
            style={{ marginBottom: 12 }}
          />
          {pkgMissing && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message="尚未上传 runner 包——安装过程中的下载步骤会失败，请切到「Runner 包」页签上传。"
            />
          )}
          <Tabs
            items={[
              { key: 'windows', label: 'Windows', children: platformTab('windows') },
              { key: 'linux', label: 'Linux', children: platformTab('linux') },
            ]}
          />
          <Typography.Paragraph
            code
            copyable={{ text: `serverUrl=${serverUrl()}\ntoken=${issued.token}\n` }}
            style={{ whiteSpace: 'pre-wrap', marginTop: 8, marginBottom: 0 }}
          >
            {`serverUrl=${serverUrl()}\ntoken=${issued.token}`}
          </Typography.Paragraph>
        </>
      )}
    </Modal>
  )
}
