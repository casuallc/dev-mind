// CAP-55 FR-07「查看依据」抽屉：徽标只给结论，这里给结论的来路——
// 模型选了谁、为什么选它（routing.reason）、重复判定是跟哪些条目撞的、以及 laya 应答原文。
import { Descriptions, Drawer, Space, Tag, Typography } from 'antd'
import type { KnowledgeProposal, TriageView } from '../types'
import { fmtTime } from '../../../shared/utils/format'

const pct = (v: number | null | undefined) => (v == null ? '-' : `${Math.round(v * 100)}%`)

export default function TriageEvidenceDrawer({
  proposal,
  onClose,
}: {
  proposal: KnowledgeProposal | null
  onClose: () => void
}) {
  const triage = proposal?.triage ?? null
  return (
    <Drawer
      title={proposal ? `分诊依据 · ${proposal.title}` : ''}
      open={proposal != null}
      onClose={onClose}
      width={640}
    >
      {triage && <Body triage={triage} />}
    </Drawer>
  )
}

function Body({ triage }: { triage: TriageView }) {
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Descriptions size="small" column={2}>
        <Descriptions.Item label="分诊时间">{fmtTime(triage.at)}</Descriptions.Item>
        <Descriptions.Item label="耗时">{triage.latencyMs} ms</Descriptions.Item>
        <Descriptions.Item label="模型" span={2}>
          {triage.model ?? '—'}
        </Descriptions.Item>
      </Descriptions>

      {triage.degraded ? (
        <Typography.Text type="warning">
          本次分诊降级：{triage.degradedReason || '未拿到建议'}
        </Typography.Text>
      ) : (
        <>
          <Typography.Text strong>模型选它的理由（routing.reason）</Typography.Text>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            {triage.routingReason || '（边车未给理由）'}
          </Typography.Paragraph>

          {triage.adoptLayer && (
            <>
              <Typography.Text strong>采纳层级</Typography.Text>
              <div>
                <Tag color="blue">{triage.adoptLayer.label}</Tag>
                <Typography.Text type="secondary">
                  （{triage.adoptLayer.value} · 置信度 {pct(triage.adoptLayer.confidence)}）
                </Typography.Text>
              </div>
              <ProbabilityRow probabilities={triage.adoptLayer.probabilities} />
            </>
          )}

          {triage.quality && (
            <>
              <Typography.Text strong>质量分</Typography.Text>
              <div>
                <Tag color="green">{triage.quality.label}</Tag>
                <Typography.Text type="secondary">
                  （等级 {triage.quality.level ?? '-'} · 置信度 {pct(triage.quality.confidence)}）
                </Typography.Text>
              </div>
              <ProbabilityRow probabilities={triage.quality.probabilities} />
            </>
          )}

          {triage.duplicate && (
            <>
              <Typography.Text strong>重复判定</Typography.Text>
              <div>
                {triage.duplicate.duplicate ? (
                  <Tag color="red">判为重复</Tag>
                ) : (
                  <Tag>未判重复</Tag>
                )}
                <Typography.Text type="secondary">
                  （"是"的概率 {pct(triage.duplicate.probability)}）
                </Typography.Text>
              </div>
              {triage.duplicate.similar?.length ? (
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Typography.Text type="secondary">
                    召回比对物（判定依据，{triage.duplicate.note || '按向量相似度召回'}）：
                  </Typography.Text>
                  {triage.duplicate.similar.map((s, i) => (
                    <Typography.Text key={`${s.entryId ?? 'x'}-${i}`}>
                      · {s.entryName}
                      <Typography.Text type="secondary">
                        {s.score ? `（相似度 ${s.score.toFixed(3)}）` : ''}
                        {s.entryId != null ? ` #${s.entryId}` : ''}
                      </Typography.Text>
                    </Typography.Text>
                  ))}
                </Space>
              ) : (
                <Typography.Text type="secondary">未召回到相似条目。</Typography.Text>
              )}
            </>
          )}
        </>
      )}

      <Typography.Text strong>laya 应答原文</Typography.Text>
      <pre style={preStyle}>{JSON.stringify(triage.answers ?? {}, null, 2)}</pre>
    </Space>
  )
}

/** 概率分布（key 是机器值/等级下标）：模型给了分布就摊开，别只留一个置信度 */
function ProbabilityRow({ probabilities }: { probabilities: Record<string, number> | null }) {
  const items = Object.entries(probabilities ?? {})
  if (!items.length) return null
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {items.map(([k, v]) => `${k} ${pct(v)}`).join(' · ')}
    </Typography.Text>
  )
}

const preStyle = {
  whiteSpace: 'pre-wrap' as const,
  background: '#f6f6f6',
  padding: 12,
  borderRadius: 4,
  fontSize: 12,
  maxHeight: 320,
  overflow: 'auto',
  margin: 0,
}
