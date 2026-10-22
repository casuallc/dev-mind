// 注入内容预览面板：选择项目与任务说明，预览会话启动时实际注入的知识内容。
// 内嵌在经验提案页（ProposalsView 顶部折叠面板），不再是独立 tab。
import { useEffect, useState } from 'react'
import { Button, Form, Input, Select, Space, Tag, Typography } from 'antd'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { previewInjection } from '../api'
import type { PreviewResult } from '../types'
import { showError } from '../../../shared/utils/showError'

export default function InjectionPreview() {
  const [projects, setProjects] = useState<Project[]>([])
  const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null)
  const [previewForm] = Form.useForm()

  useEffect(() => {
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
  }, [])

  const onPreview = async () => {
    const v = await previewForm.validateFields()
    try {
      setPreviewResult(await previewInjection(v.projectId, v.taskSpec))
    } catch (e) {
      showError(e, '预览失败')
    }
  }

  const projectOptions = projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Text type="secondary">
        选择项目与任务说明，预览会话启动时实际注入的知识内容（FULL 库条目）。
      </Typography.Text>
      <Form form={previewForm} layout="inline">
        <Form.Item name="projectId" label="项目">
          <Select allowClear placeholder="选择项目" style={{ width: 240 }} options={projectOptions} />
        </Form.Item>
        <Form.Item name="taskSpec" label="任务说明" style={{ flex: 1, minWidth: 240 }}>
          <Input placeholder="本次会话要做什么？（将写入 ## 当前任务）" />
        </Form.Item>
        <Button type="primary" onClick={onPreview}>
          预览注入内容
        </Button>
      </Form>
      {previewResult && (
        <>
          <Typography.Text>
            命中条目（{previewResult.entriesUsed.length}）：
            {previewResult.entriesUsed.length
              ? previewResult.entriesUsed.map((e) => (
                  <Tag key={e.id} color={e.scope === 'global' ? 'blue' : undefined}>
                    {e.name}
                  </Tag>
                ))
              : '无'}
          </Typography.Text>
          <pre
            style={{
              whiteSpace: 'pre-wrap',
              background: '#f6f6f6',
              padding: 12,
              borderRadius: 4,
              fontSize: 12,
              maxHeight: 320,
              overflow: 'auto',
              margin: 0,
            }}
          >
            {previewResult.content || '(空)'}
          </pre>
        </>
      )}
    </Space>
  )
}
