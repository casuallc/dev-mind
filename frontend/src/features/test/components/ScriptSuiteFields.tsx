// script 脚本套件字段组：新建抽屉（SuiteFormDrawer）与内层编辑页（SuiteDetailPage）共用，
// 保证两处字段/校验/文案一致。toScriptSuiteInput 按表单值组装提交体（空值转 undefined）。
import { Flex, Form, Input, InputNumber, Select } from 'antd'
import type { AgentNode } from '../../agent/types'
import type { ScriptSuiteInput } from '../types'
import EnvEditor from './EnvEditor'

interface ScriptFormValues {
  name: string
  repoUrl: string
  branch: string
  workSubdir?: string
  command: string
  junitPath: string
  timeoutSec?: number
  agentNodeId?: string
  workspaceKey?: string
  env?: { key?: string; value?: string | null; secret?: boolean }[]
}

export function toScriptSuiteInput(projectId: string, v: ScriptFormValues): ScriptSuiteInput {
  return {
    projectId,
    name: v.name,
    repoUrl: v.repoUrl,
    branch: v.branch,
    workSubdir: v.workSubdir?.trim() || undefined,
    command: v.command,
    junitPath: v.junitPath,
    env: (v.env ?? []).filter((e) => e.key?.trim()) as ScriptSuiteInput['env'],
    agentNodeId: v.agentNodeId || undefined,
    timeoutSec: v.timeoutSec ?? undefined,
    workspaceKey: v.workspaceKey?.trim() || undefined,
  }
}

/** script 套件除「名称」外的全部字段（名称由各容器自管：新建抽屉与类型选择同行、编辑页独立） */
export default function ScriptSuiteFields({ nodes }: { nodes: AgentNode[] }) {
  const nodeOptions = nodes.map((n) => ({
    value: String(n.id),
    label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
  }))
  return (
    <>
      <Form.Item label="git 仓库地址" name="repoUrl" rules={[{ required: true, message: '请输入仓库地址' }]}
        extra="节点凭自身 git 凭据 clone/pull（内网匿名读或节点 credential helper）">
        <Input placeholder="http://git.local/group/repo.git" />
      </Form.Item>
      <Flex gap={12} align="start">
        <Form.Item label="分支" name="branch" rules={[{ required: true, message: '请输入分支' }]} style={{ flex: 1 }}>
          <Input placeholder="master" />
        </Form.Item>
        <Form.Item label="工作子目录（可选）" name="workSubdir" style={{ flex: 1 }}
          extra="命令在仓库内该子目录执行，如 e2e">
          <Input placeholder="e2e" />
        </Form.Item>
      </Flex>
      <Form.Item label="执行命令" name="command" rules={[{ required: true, message: '请输入执行命令' }]}
        extra="runner execAllowlist 逐行校验首词，npm/npx/git 等需先放行">
        <Input.TextArea rows={4} placeholder={'npm ci\nnpx playwright test'} style={{ fontFamily: 'monospace' }} />
      </Form.Item>
      <Form.Item label="JUnit 产出路径" name="junitPath" rules={[{ required: true, message: '请输入 JUnit 产出路径' }]}
        extra="相对工作子目录；缺失时不判失败，仅在备注中注记">
        <Input placeholder="test-results/junit.xml" />
      </Form.Item>
      <Flex gap={12} align="start">
        <Form.Item label="超时（秒）" name="timeoutSec" style={{ flex: 1 }}>
          <InputNumber min={60} max={86400} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="默认节点" name="agentNodeId" style={{ flex: 1 }}
          extra="留空 = 项目默认节点 → 平台默认">
          <Select allowClear placeholder="项目默认 → 平台默认" options={nodeOptions} />
        </Form.Item>
      </Flex>
      <Form.Item label="工作区 key（可选）" name="workspaceKey"
        extra="默认 = 套件 id；多套件共享同一 key 即共享节点工作区目录（套件间状态文件跨运行保留）">
        <Input placeholder="如 admq-e2e" />
      </Form.Item>
      <Form.Item label="环境变量" name="env"
        extra="注入执行进程；脱敏项保存后恒显示掩码，掩码原样回传表示该条不变">
        <EnvEditor />
      </Form.Item>
    </>
  )
}
