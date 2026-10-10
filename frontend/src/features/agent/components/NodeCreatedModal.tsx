import { Alert, Button, Modal, Steps, Tabs, Typography } from 'antd'
import { CodeOutlined, WindowsOutlined } from '@ant-design/icons'
import type { IssuedNode } from '../types'
import { downloadInstallScripts } from '../utils/installScript'

interface Props {
  /** 新建节点签发的 token（仅此一次可见）；null = 不展示 */
  issued: IssuedNode | null
  /** 尚未上传 runner 包时提示（脚本下载步骤会失败） */
  pkgMissing: boolean
  onClose: () => void
}

const serverUrl = () => `${location.origin.replace(/^http/, 'ws')}/ws/agent`

/**
 * CAP-21「节点已创建」弹窗：token 一次性展示 + 分平台步骤化安装引导。
 * 一键脚本默认注册系统服务（Windows 服务 / systemd），无权限自动降级后台运行。
 */
export default function NodeCreatedModal({ issued, pkgMissing, onClose }: Props) {
  const scripts = issued ? downloadInstallScripts(issued.token) : null

  const platformTab = (os: 'windows' | 'linux') => {
    const isWin = os === 'windows'
    const cmd = isWin
      ? 'powershell -ExecutionPolicy Bypass -File install-runner.ps1'
      : 'sudo bash install-runner.sh'
    return (
      <Steps
        direction="vertical"
        size="small"
        current={-1}
        items={[
          {
            title: '下载安装脚本（已内嵌 token）',
            description: (
              <Button
                icon={isWin ? <WindowsOutlined /> : <CodeOutlined />}
                onClick={isWin ? scripts?.windows : scripts?.linux}
              >
                {isWin ? 'install-runner.ps1' : 'install-runner.sh'}
              </Button>
            ),
          },
          {
            title: isWin ? '在目标机的管理员 PowerShell 中执行' : '在目标机执行（注册服务需 root）',
            description: (
              <Typography.Paragraph code copyable style={{ marginBottom: 0 }}>
                {cmd}
              </Typography.Paragraph>
            ),
          },
          {
            title: '回到本页确认节点已 ONLINE',
            description: (
              <Typography.Text type="secondary">
                脚本会检查 JDK 21+、下载 runner 包、写 agent.properties，并默认注册
                {isWin ? ' Windows 服务（WinSW）' : ' systemd 服务（devmind-agent）'}
                ——开机自启、崩溃自拉起、自升级由服务管理器接管；无{isWin ? '管理员' : ' root '}
                权限时自动降级为后台运行，{isWin ? '-NoService' : '--no-service'} 可强制后台模式。
              </Typography.Text>
            ),
          },
        ]}
      />
    )
  }

  return (
    <Modal
      title="节点已创建（token 仅此一次展示）"
      open={!!issued}
      width={640}
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
            message="请立即复制保存 token——关闭后无法再次查看，只能重建节点。"
            style={{ marginBottom: 12 }}
          />
          <Typography.Paragraph
            code
            copyable={{ text: `serverUrl=${serverUrl()}\ntoken=${issued.token}\n` }}
            style={{ whiteSpace: 'pre-wrap' }}
          >
            {`serverUrl=${serverUrl()}\ntoken=${issued.token}`}
          </Typography.Paragraph>
          {pkgMissing && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message="尚未上传 runner 包——脚本中的下载步骤会失败，请切到「Runner 包」页签上传。"
            />
          )}
          <Tabs
            items={[
              { key: 'windows', label: 'Windows', children: platformTab('windows') },
              { key: 'linux', label: 'Linux', children: platformTab('linux') },
            ]}
          />
        </>
      )}
    </Modal>
  )
}
