import { useEffect, useState } from 'react'
import { Alert, Button, Card, Empty, Select, Typography } from 'antd'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { listAgentNodes } from '../api'
import type { AgentNode } from '../types'
import NodeFilesBrowser from '../components/NodeFilesBrowser'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

/**
 * CAP-65 节点文件浏览（全页）：选择在线且已配白名单根目录的节点后进入文件浏览器。
 * 支持 ?nodeId= 直达（节点详情页「文件浏览」按钮跳入）；节点状态变化不轮询，手动刷新节点列表。
 */
export default function NodeFilesPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [nodes, setNodes] = useState<AgentNode[] | null>(null) // null=加载中

  const reload = () => {
    listAgentNodes()
      .then(setNodes)
      .catch((e) => {
        setNodes([])
        showError(e, '加载节点失败')
      })
  }

  useEffect(reload, [])

  // 可浏览 = 在线且配置了文件访问根目录；离线/未配白名单的节点进选择器但置灰，避免「凭空消失」
  const eligible = (n: AgentNode) => n.status === 'ONLINE' && !!(n.fileRoots?.length)
  const nodeIdParam = Number(params.get('nodeId'))
  const selected = nodes?.find((n) => n.id === nodeIdParam && eligible(n)) ?? null
  // nodeId 参数存在但节点不可浏览（离线/未配白名单/已删除）时给出原因提示
  const invalidParam = nodes != null && params.get('nodeId') != null && !selected

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="节点文件"
      extra={
        <Select
          style={{ minWidth: 260 }}
          placeholder="选择节点（在线且已配文件根目录）"
          loading={nodes === null}
          value={selected?.id}
          options={(nodes ?? []).map((n) => ({
            value: n.id,
            label: `${n.name}${eligible(n) ? '' : n.status !== 'ONLINE' ? '（离线）' : '（未配根目录）'}`,
            disabled: !eligible(n),
          }))}
          onChange={(id) => setParams({ nodeId: String(id) })}
        />
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        浏览/编辑节点白名单根目录内的文件（CAP-65）。可选节点需在详情页配置「文件访问根目录」且保持在线。
      </Typography.Paragraph>
      {invalidParam && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="指定节点不可浏览：已离线、未配置文件访问根目录，或已被删除。请重新选择节点。"
        />
      )}
      {selected ? (
        <NodeFilesBrowser node={selected} />
      ) : (
        <Empty
          style={{ margin: 'auto' }}
          description={
            nodes === null
              ? '加载节点中…'
              : nodes.some(eligible)
                ? '从右上角选择节点开始浏览'
                : '暂无可浏览节点——需节点在线且配置了文件访问根目录'
          }
        >
          {nodes != null && !nodes.some(eligible) && (
            <Button type="primary" onClick={() => navigate('/admin/agent/nodes')}>
              去节点列表配置
            </Button>
          )}
        </Empty>
      )}
    </Card>
  )
}
