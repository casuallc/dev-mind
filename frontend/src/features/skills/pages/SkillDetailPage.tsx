// Skill 详情页：列表「管理」落地页（页面模式，交互对齐文档管理 /admin/docs/:id）。
// 展示 SKILL.md 正文渲染 + 附件入口；编辑/附件仍走原抽屉组件，启停/删除在页内完成，删除后返回列表。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Empty, message, Modal, Space, Spin, Tag, Typography } from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  FolderOpenOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import { canWrite } from '../../auth/authStore'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { deleteSkill, getSkill, updateSkillStatus } from '../api'
import type { SkillDetail } from '../types'
import SkillFormDrawer from '../components/SkillFormDrawer'
import SkillFilesDrawer from '../components/SkillFilesDrawer'
import Markdown from '../../../shared/components/Markdown'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardBodyFlexStyle, pageCardStyle, pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

const scopeTag = (s: string) =>
  s === 'GLOBAL' ? <Tag color="blue">全局</Tag> : <Tag color="purple">项目</Tag>
const statusTag = (s: string) =>
  s === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>

export default function SkillDetailPage() {
  const { id = '' } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [detail, setDetail] = useState<SkillDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [projects, setProjects] = useState<Project[]>([])
  const [formOpen, setFormOpen] = useState(false)
  const [filesOpen, setFilesOpen] = useState(false)

  const load = useCallback(async () => {
    if (!id) return
    try {
      setDetail(await getSkill(id))
    } catch (e) {
      showError(e, '加载 skill 失败')
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    setLoading(true)
    load()
    listProjects().then(setProjects).catch(() => undefined)
  }, [load])

  const skill = detail?.skill
  const projectName = skill?.projectId
    ? (projects.find((p) => p.id === skill.projectId)?.name ?? skill.projectId)
    : null

  const onToggleStatus = async () => {
    if (!skill) return
    try {
      await updateSkillStatus(skill.id, skill.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
      message.success(skill.status === 'ACTIVE' ? '已停用' : '已启用')
      load()
    } catch (e) {
      showError(e, '操作失败')
    }
  }

  const onDelete = () => {
    if (!skill) return
    Modal.confirm({
      centered: true,
      title: `删除 skill「${skill.name}」？`,
      content: '将同时删除其全部附件文件，删除后无法恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteSkill(skill.id)
          message.success('已删除')
          navigate('/admin/skills')
        } catch (e) {
          showError(e, '删除失败')
        }
      },
    })
  }

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
      {loading ? (
        <Card>
          <Spin />
        </Card>
      ) : !skill ? (
        <Card>
          <Empty description="Skill 不存在" />
        </Card>
      ) : (
        <Card
          style={pageCardStyle}
          styles={{ body: pageCardBodyFlexStyle }}
          title={
            <Space wrap size={8}>
              <Typography.Text code strong>{skill.name}</Typography.Text>
              {scopeTag(skill.scope)}
              {statusTag(skill.status)}
              {skill.tags.map((t) => <Tag key={t}>{t}</Tag>)}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                项目：{projectName ?? '全局可用'} · 附件 {skill.fileCount} 个 · 更新于 {fmtTime(skill.updatedAt)}
              </Typography.Text>
            </Space>
          }
          extra={
            <Space wrap>
              {canWrite() && (
                <Button icon={<EditOutlined />} onClick={() => setFormOpen(true)}>
                  编辑
                </Button>
              )}
              <Button icon={<FolderOpenOutlined />} onClick={() => setFilesOpen(true)}>
                附件
              </Button>
              {canWrite() && (
                <>
                  <Button onClick={onToggleStatus}>
                    {skill.status === 'ACTIVE' ? '停用' : '启用'}
                  </Button>
                  <Button danger icon={<DeleteOutlined />} onClick={onDelete}>
                    删除
                  </Button>
                </>
              )}
              <Button onClick={() => navigate('/admin/skills')}>返回列表</Button>
            </Space>
          }
        >
          <div style={pagePaneScrollStyle}>
            <Typography.Paragraph type="secondary">
              {skill.description || '（无描述）'}
            </Typography.Paragraph>
            {Object.keys(detail?.extraFrontmatter ?? {}).length > 0 && (
              <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                frontmatter：
                {Object.entries(detail!.extraFrontmatter).map(([k, v]) => (
                  <Typography.Text code key={k} style={{ marginRight: 8 }}>{k}: {v}</Typography.Text>
                ))}
              </Typography.Paragraph>
            )}
            <Markdown content={detail?.contentMd ?? ''} />
          </div>
        </Card>
      )}

      <SkillFormDrawer
        open={formOpen}
        editing={detail}
        projects={projects}
        onClose={() => setFormOpen(false)}
        onSaved={load}
      />
      <SkillFilesDrawer
        open={filesOpen}
        skill={skill ?? null}
        onClose={() => {
          setFilesOpen(false)
          load()
        }}
      />
    </div>
  )
}
