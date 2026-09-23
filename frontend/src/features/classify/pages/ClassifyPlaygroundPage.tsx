// CAP-57 分类服务 · 在线试分类：三通道（受管实例直打 / DECISION 端点 / 平台默认决策链）发一次
// state+questions 给 laya 边车，展示逐题答案（概率条形）与路由/降级信息；每次运行落决策记录
// （capability=classify-playground，refId=pg-*），可从结果面板跳转查证。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Input,
  message,
  Progress,
  Row,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import { ClearOutlined, ExperimentOutlined, PlayCircleOutlined } from '@ant-design/icons'
import { pageCardBodyFlexStyle, pageCardStyle, pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import LayaViewSwitch from '../../laya/components/LayaViewSwitch'
import type { DecisionAnswer } from '../../decision/types'
import { listModelEndpoints } from '../../model/api'
import {
  getClassifyPlaygroundSample,
  listClassifyInstances,
  runClassifyPlayground,
} from '../api'
import type { ClassifyInstance, PlaygroundRunResult } from '../types'

type Channel = 'default' | 'instance' | 'endpoint'

/** 单题答案渲染：choice/score/noul 三原语只有对应字段有值；probabilities 画条形 */
function AnswerBlock({ qid, answer }: { qid: string; answer: DecisionAnswer }) {
  const probs = Object.entries(answer.probabilities ?? {}).sort((a, b) => b[1] - a[1])
  return (
    <Card size="small" style={{ marginBottom: 8 }}>
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        <Space size={8} wrap>
          <Typography.Text strong>{qid}</Typography.Text>
          <Tag>{answer.type ?? '-'}</Tag>
          {answer.choice != null ? <Tag color="blue">{answer.choice}</Tag> : null}
          {answer.score != null ? <Tag color="blue">score {answer.score}</Tag> : null}
          {answer.noul != null ? <Tag color="blue">noul {answer.noul}</Tag> : null}
          {answer.confidence != null ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              置信度 {(answer.confidence * 100).toFixed(1)}%
            </Typography.Text>
          ) : null}
        </Space>
        {probs.length > 0 ? (
          <div>
            {probs.map(([key, p]) => (
              <Space key={key} size={8} style={{ display: 'flex', marginBottom: 2 }}>
                <Typography.Text style={{ width: 120, fontSize: 12 }} ellipsis={{ tooltip: key }}>
                  {key}
                </Typography.Text>
                <Progress
                  percent={Math.round(p * 1000) / 10}
                  size="small"
                  style={{ flex: 1, minWidth: 0, marginBottom: 0 }}
                />
              </Space>
            ))}
          </div>
        ) : null}
      </Space>
    </Card>
  )
}

export default function ClassifyPlaygroundPage() {
  const [channel, setChannel] = useState<Channel>('default')
  const [instanceId, setInstanceId] = useState<number | null>(null)
  const [endpointId, setEndpointId] = useState<number | null>(null)
  const [instances, setInstances] = useState<ClassifyInstance[]>([])
  const [endpoints, setEndpoints] = useState<{ id: number; name: string; baseUrl?: string | null }[]>([])
  const [stateText, setStateText] = useState('')
  const [questionsText, setQuestionsText] = useState('')
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<PlaygroundRunResult | null>(null)

  useEffect(() => {
    listClassifyInstances().then(setInstances).catch(() => undefined)
    listModelEndpoints()
      .then((eps) => setEndpoints(eps.filter((e) => e.kind === 'DECISION').map((e) => ({ id: e.id, name: e.name, baseUrl: e.baseUrl }))))
      .catch(() => undefined)
  }, [])

  const fillSample = useCallback(async () => {
    try {
      const sample = await getClassifyPlaygroundSample()
      setStateText(JSON.stringify(sample.state, null, 2))
      setQuestionsText(JSON.stringify(sample.questions, null, 2))
      message.success('已填入边车中文样例')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '样例加载失败')
    }
  }, [])

  const run = useCallback(async () => {
    let state: Record<string, unknown>
    let questions: Record<string, unknown>
    try {
      state = stateText.trim() ? (JSON.parse(stateText) as Record<string, unknown>) : {}
    } catch {
      message.error('state 不是合法 JSON')
      return
    }
    try {
      questions = JSON.parse(questionsText) as Record<string, unknown>
    } catch {
      message.error('questions 不是合法 JSON')
      return
    }
    setRunning(true)
    setResult(null)
    try {
      const r = await runClassifyPlayground({
        instanceId: channel === 'instance' ? instanceId : null,
        endpointId: channel === 'endpoint' ? endpointId : null,
        state,
        questions,
      })
      setResult(r)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '试分类失败')
    } finally {
      setRunning(false)
    }
  }, [channel, instanceId, endpointId, stateText, questionsText])

  const channelOptions = useMemo(
    () => [
      { value: 'default' as const, label: '平台默认决策链' },
      { value: 'instance' as const, label: '受管实例直打' },
      { value: 'endpoint' as const, label: 'DECISION 端点' },
    ],
    [],
  )

  const canRun =
    questionsText.trim().length > 0 &&
    (channel === 'default' || (channel === 'instance' && instanceId != null) || (channel === 'endpoint' && endpointId != null))

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <LayaViewSwitch group="records" value="playground">
          <Select
            style={{ width: 320 }}
            value={channel === 'instance' ? `i-${instanceId ?? ''}` : channel === 'endpoint' ? `e-${endpointId ?? ''}` : 'default'}
            onChange={(v: string) => {
              if (v === 'default') setChannel('default')
              else if (v.startsWith('i-')) {
                setChannel('instance')
                const id = Number(v.slice(2))
                if (!Number.isNaN(id) && id > 0) setInstanceId(id)
              } else {
                setChannel('endpoint')
                const id = Number(v.slice(2))
                if (!Number.isNaN(id) && id > 0) setEndpointId(id)
              }
            }}
            options={[
              ...channelOptions.slice(0, 1),
              {
                label: '受管实例',
                options: instances.map((i) => ({ value: `i-${i.id}`, label: `${i.name}（${i.baseUrl}）` })),
              },
              {
                label: 'DECISION 端点',
                options: endpoints.map((e) => ({ value: `e-${e.id}`, label: `${e.name}${e.baseUrl ? `（${e.baseUrl}）` : ''}` })),
              },
            ]}
          />
        </LayaViewSwitch>
      }
      extra={
        <Space>
          <Button icon={<ExperimentOutlined />} onClick={fillSample}>
            填入样例
          </Button>
          <Button
            icon={<ClearOutlined />}
            onClick={() => {
              setStateText('')
              setQuestionsText('')
              setResult(null)
            }}
          >
            清空
          </Button>
          <Button type="primary" icon={<PlayCircleOutlined />} loading={running} disabled={!canRun} onClick={run}>
            运行
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        给 laya 边车发一次 state + questions（JSON），查看逐题答案与路由/降级信息。每次运行都会写入决策记录（capability=classify-playground）。
      </Typography.Paragraph>
      <Row gutter={12} style={{ flex: 1, minHeight: 0 }}>
        <Col span={12} style={{ ...pagePaneScrollStyle, paddingRight: 4 }}>
          <Typography.Text strong>state（JSON，可空）</Typography.Text>
          <Input.TextArea
            style={{ marginTop: 4, marginBottom: 12, fontFamily: 'monospace', fontSize: 12 }}
            rows={10}
            value={stateText}
            onChange={(e) => setStateText(e.target.value)}
            placeholder='{"proposal": "……"}'
          />
          <Typography.Text strong>questions（JSON，必填，至少一题）</Typography.Text>
          <Input.TextArea
            style={{ marginTop: 4, fontFamily: 'monospace', fontSize: 12 }}
            rows={12}
            value={questionsText}
            onChange={(e) => setQuestionsText(e.target.value)}
            placeholder='{"q1": {"type": "choice", "instructions": "……", "criteria": {"keep": "……", "discard": "……"}}}'
          />
        </Col>
        <Col span={12} style={pagePaneScrollStyle}>
          {result ? (
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Space size={8} wrap>
                <Typography.Text strong>目标：{result.target}</Typography.Text>
                <Tag>{result.latencyMs}ms</Tag>
                {result.degraded ? <Tag color="warning">降级：{result.degradedReason ?? '-'}</Tag> : null}
              </Space>
              <Typography.Paragraph type="secondary" style={{ marginBottom: 4, fontSize: 12 }}>
                路由：{result.routingModel ?? '-'}
                {result.routingReason ? `（${result.routingReason}）` : ''} · 记录：
                <Link to={`/admin/laya/records?capability=classify-playground`}>{result.recordRefId}</Link>
                （到决策记录页按 refId 查证）
              </Typography.Paragraph>
              {Object.entries(result.answers).map(([qid, ans]) => (
                <AnswerBlock key={qid} qid={qid} answer={ans} />
              ))}
            </Space>
          ) : (
            <Empty style={{ marginTop: 80 }} description={running ? '推理中……' : '左侧填好 questions 后点「运行」'} />
          )}
          {result && Object.keys(result.answers).length === 0 ? (
            <Alert type="warning" showIcon message="边车返回了空 answers——检查 questions 结构是否符合三原语协议。" />
          ) : null}
        </Col>
      </Row>
    </Card>
  )
}
