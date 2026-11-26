import { Alert, Card, Space, Tag, Typography } from 'antd'
import type { AgentNode } from '../types'

const { Paragraph, Text } = Typography

/** 命令块：等宽 + 可复制 + 保留换行 */
function CmdBlock({ children }: { children: string }) {
  return (
    <Paragraph
      code
      copyable={{ text: children }}
      type="secondary"
      style={{ marginBottom: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}
    >
      {children}
    </Paragraph>
  )
}

export function serviceModeLabel(mode?: string): string {
  if (mode === 'windows-service') return 'Windows 服务'
  if (mode === 'systemd') return 'systemd 服务'
  if (mode === 'process') return '前台/后台进程'
  return '未知（老 runner 未上报）'
}

/**
 * 运行身份（执行用户）展示与「修改执行用户」引导卡。
 * 一键修改不可行：改执行用户必须节点机上持有管理员/root 权限与目标账户凭据（Windows 服务
 * 登录账户要密码，systemd 改 User= 要 root），平台无法远程代办——故这里生成按节点实际
 * 服务名/工作目录预填的命令，操作者到节点机执行，runner 重连后身份自动刷新。
 */
export default function RunIdentityCard({ node }: { node: AgentNode }) {
  const svc = node.serviceName || '<服务名>'
  const dir = node.workDir || '<runner 安装目录>'
  const isWindows = (node.os ?? '').toLowerCase().includes('windows')

  if (!node.runUser && !node.serviceMode) {
    return (
      <Card size="small" title="运行身份（执行用户）">
        <Alert
          type="info"
          showIcon
          message="该节点 runner 未上报运行身份（版本过旧）——先升级 runner，之后此处展示执行用户与修改引导。"
        />
      </Card>
    )
  }

  return (
    <Card size="small" title="运行身份（执行用户）">
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Space size={8} wrap>
          <Text>当前执行用户：</Text>
          <Text code>{node.runUser || '-'}</Text>
          <Tag>{serviceModeLabel(node.serviceMode)}</Tag>
          {node.serviceName && <Tag color="blue">{node.serviceName}</Tag>}
          {node.workDir && <Text type="secondary" style={{ fontSize: 12 }}>{node.workDir}</Text>}
        </Space>
        <Text type="secondary">
          会话内所有 shell/构建命令都继承 runner 进程身份。改执行用户只能在节点机上操作
          （需管理员/root 权限与目标账户凭据，平台无法一键代办），按下面引导执行后 runner
          自动重连，本卡身份即刷新；可在会话里跑 <Text code>whoami</Text> 复核。
        </Text>

        {isWindows && node.serviceMode === 'windows-service' && (
          <>
            <Alert
              type="warning"
              showIcon
              message="前提：目标账户必须设过密码（无密码/纯 PIN 账户无法用于服务登录）；目标用户日后改密码需重跑第 1 条；不要重跑一键安装脚本（会重写服务配置丢掉该设置）。"
            />
            <CmdBlock>{`:: 管理员 PowerShell，把 <目标用户>/<密码> 替换为实际值：
sc.exe config ${svc} obj= ".\\<目标用户>" password= "<密码>"
takeown /F "${dir}" /R /D Y
icacls "${dir}" /grant <目标用户>:F /T
sc.exe stop ${svc}
sc.exe start ${svc}`}</CmdBlock>
            <Text type="secondary" style={{ fontSize: 12 }}>
              后两条是把存量工作区/日志文件属主从 SYSTEM 收回给目标用户，否则新身份读写旧目录会失败。
              也可改用 WinSW 配置：编辑 {dir}\bin\{svc}.xml 加 &lt;serviceaccount&gt; 块后
              依次 stop / uninstall / install / start（效果等价）。
            </Text>
          </>
        )}

        {isWindows && node.serviceMode !== 'windows-service' && (
          <>
            <Text>裸进程模式：停掉当前 runner 进程，以目标用户登录该机器后重新拉起：</Text>
            <CmdBlock>{`# 在目标用户的会话里执行（安装目录 ${dir}）：
java -jar devmind-agent-runner.jar agent.properties`}</CmdBlock>
          </>
        )}

        {!isWindows && node.serviceMode === 'systemd' && (
          <>
            <CmdBlock>{`# root/sudo 执行，把 <目标用户> 替换为实际值：
sudo systemctl edit ${svc}
# 写入：
# [Service]
# User=<目标用户>
sudo chown -R <目标用户>:<目标用户> "${dir}"
sudo systemctl daemon-reload
sudo systemctl restart ${svc}`}</CmdBlock>
            <Text type="secondary" style={{ fontSize: 12 }}>
              chown 的组名若与用户名不同请相应调整；改执行用户后存量工作区属主必须一并收回，否则新身份读写旧目录会失败。
            </Text>
          </>
        )}

        {!isWindows && node.serviceMode !== 'systemd' && (
          <>
            <Text>裸进程模式：停掉当前 runner 进程，以目标用户重新拉起：</Text>
            <CmdBlock>{`# 在目标用户的 shell 里执行（安装目录 ${dir}）：
nohup java -jar devmind-agent-runner.jar agent.properties >> runner.log 2>&1 &`}</CmdBlock>
          </>
        )}
      </Space>
    </Card>
  )
}
