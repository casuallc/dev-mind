// CAP-86 FR-04 召回验证：跨库/单库检索召回调试（管理员的验证手段，消费入口在 AI 问答）。
// ?kbId= 预选单库，替代原库内「检索测试」页签。
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Card, Typography } from 'antd'
import { listBases } from '../api'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import type { KnowledgeBase, KnowledgeSearchV2Hit } from '../types'
import KnowledgeSearchView from '../components/KnowledgeSearchView'
import KnowledgeEntryDrawer from '../components/KnowledgeEntryDrawer'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

export default function KnowledgeSearchPage() {
  const [searchParams] = useSearchParams()
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [entryDetail, setEntryDetail] = useState<{
    entryId: number
    hit?: KnowledgeSearchV2Hit
    focusChunkId?: number
  } | null>(null)

  useEffect(() => {
    listBases()
      .then(setBases)
      .catch((e) => showError(e, '加载知识库失败'))
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
  }, [])

  const rawKbId = Number(searchParams.get('kbId'))
  const presetKbId = Number.isFinite(rawKbId) && rawKbId > 0 ? rawKbId : undefined

  return (
    <Card style={pageCardStyle} styles={{ body: pageCardBodyFlexStyle }} title="召回验证">
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        验证「这个问题能不能问出来」：跨库/单库检索调试，看命中文档与高亮片段。日常问知识请用 AI 问答（绑定知识库）。
      </Typography.Paragraph>
      <KnowledgeSearchView
        bases={bases}
        projects={projects}
        presetKbId={presetKbId}
        onOpenEntry={(entryId, hit, focusChunkId) => setEntryDetail({ entryId, hit, focusChunkId })}
      />
      <KnowledgeEntryDrawer
        entryId={entryDetail?.entryId}
        hit={entryDetail?.hit}
        focusChunkId={entryDetail?.focusChunkId}
        kbName={entryDetail?.hit?.kbName}
        onClose={() => setEntryDetail(null)}
      />
    </Card>
  )
}
