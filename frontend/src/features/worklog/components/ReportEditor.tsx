import { Button, Empty, Input, Modal, Space, Typography, message } from 'antd'
import { RobotOutlined, EditOutlined, ReloadOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { fmtTime } from '../../../shared/utils/format'
import MarkdownEditor, { type MarkdownEditorMode } from '../../../shared/components/MarkdownEditor'
import { showError } from '../../../shared/utils/showError'

interface Props {
  /** 报告是否已存在（CAP-74：无 DB id，exists=false 渲染空白引导） */
  exists?: boolean
  updatedAt?: string
  /** 编辑字段：日报单段 contentMd；周报两段 summaryMd/nextPlanMd */
  fields: { key: string; label: string; value: string }[]
  generating: boolean
  /** 手动创建空白稿（不经 AI），创建后进入编辑态 */
  onCreateManual: () => Promise<void>
  /** 触发 AI 生成（非 force 已存在由后端跳过；force=显式覆盖旧稿）；hint 为可选补充说明 */
  onGenerate: (force: boolean, hint?: string) => void
  /** 保存即生效落库（无确认态，保存后仍可随时再改） */
  onSave: (values: Record<string, string>) => Promise<void>
  /** 撑满父容器剩余高度（日报页要求编辑器底部与页面底部对齐） */
  fill?: boolean
  /** 多字段布局：vertical 上下（默认）；horizontal 左右等宽（周报「周总结 / 下周计划」） */
  layout?: 'vertical' | 'horizontal'
  /**
   * 确定性「重建」入口（不经 LLM，人工确认后填入编辑框，保存才落库）：
   * 周报传「从计划项重建」（CAP-90 FR-10，写入 nextPlanMd）；
   * 季度复盘传「从数据重建」（CAP-91 FR-10，写入 contentMd）。返回空 markdown = 无数据可重建。
   */
  rebuild?: {
    /** 拉取确定性 markdown 预览 */
    fetch: () => Promise<{ markdown: string } | null>
    /** 填入的字段 key（默认 nextPlanMd） */
    fieldKey?: string
    /** 按钮文案（默认「从计划项重建」） */
    label?: string
    /** 无数据时的提示文案 */
    emptyText?: string
    /** 确认弹窗标题（默认「重建「下周计划」？」） */
    confirmTitle?: string
  }
}

/**
 * CAP-28 报告编辑卡：空白手填 / AI 草稿 → 人工修订 → 保存。
 * CAP-74 去 DRAFT/CONFIRMED 状态机，CAP-89 起事实源回落数据库（保存即落库），
 * 不再有「确认定稿/只读」概念。编辑态复用共享 MarkdownEditor 的极简模式（showToolbar=false：
 * Qoder 文档式顶栏，字段标题通过 title 进入顶栏，只有源码/分栏/预览切换，默认预览；日报以读为主，
 * 不摆格式工具栏）。周报两字段用 layout=horizontal 左右等宽并排，与日报一样 fill 撑满并底部对齐。
 * 全部字段处于预览时不显示「保存修改」；保存需二次确认。
 */
export default function ReportEditor({
  exists,
  updatedAt,
  fields,
  generating,
  onCreateManual,
  onGenerate,
  onSave,
  fill,
  layout = 'vertical',
  rebuild,
}: Props) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [modes, setModes] = useState<Record<string, MarkdownEditorMode>>({})
  const [saving, setSaving] = useState(false)
  const [creating, setCreating] = useState(false)
  const [genOpen, setGenOpen] = useState(false)
  const [genForce, setGenForce] = useState(false)
  const [hint, setHint] = useState('')
  const [rebuilding, setRebuilding] = useState(false)

  // 确定性重建（不经 LLM）：拉取预览 → 人工确认 → 填入编辑框（保存才落库）
  const doRebuild = async () => {
    if (!rebuild) return
    const fieldKey = rebuild.fieldKey ?? 'nextPlanMd'
    setRebuilding(true)
    try {
      const preview = await rebuild.fetch()
      if (!preview || !preview.markdown) {
        message.info(rebuild.emptyText ?? '下周暂无计划项：可先在「计划」页签排下周计划')
        return
      }
      Modal.confirm({
        centered: true,
        title: rebuild.confirmTitle ?? '重建「下周计划」？',
        content: (
          <div>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
              将替换当前编辑值（未保存的旧文本不受影响；保存后才落库）：
            </Typography.Paragraph>
            <Typography.Paragraph>
              <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{preview.markdown}</pre>
            </Typography.Paragraph>
          </div>
        ),
        okText: '填入编辑框',
        cancelText: '取消',
        onOk: () => {
          setValues((v) => ({ ...v, [fieldKey]: preview.markdown }))
          // 切到编辑态让「保存修改」出现（全预览时保存按钮隐藏）
          setModes((m) => ({ ...m, [fieldKey]: 'split' }))
        },
      })
    } catch (e) {
      showError(e, '获取重建预览失败')
    } finally {
      setRebuilding(false)
    }
  }

  useEffect(() => {
    const next: Record<string, string> = {}
    fields.forEach((f) => {
      next[f.key] = f.value
    })
    setValues(next)
  }, [fields])

  const modeOf = (key: string) => modes[key] ?? 'preview'
  // 任一字段在编辑/分栏态才显示保存按钮（全预览=纯阅读，隐藏保存防误触）
  const anyEditing = fields.some((f) => modeOf(f.key) !== 'preview')
  const horizontal = layout === 'horizontal' && fields.length > 1
  const heightOf = (key: string) => {
    const rows = Math.max(6, (values[key] || '').split('\n').length + 1)
    return Math.max(220, rows * 22 + 12)
  }
  // 左右并排时两边同高，避免卡片底边参差
  const rowHeight = horizontal && fields.length ? Math.max(...fields.map((f) => heightOf(f.key))) : 220

  const doSave = async () => {
    setSaving(true)
    try {
      await onSave(values)
      message.success('已保存')
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const save = () => {
    Modal.confirm({
      title: '保存修改',
      content: '保存即落库生效（之后仍可随时再改），确定保存吗？',
      okText: '保存',
      cancelText: '取消',
      onOk: doSave,
    })
  }

  const openGenerate = (force: boolean) => {
    setGenForce(force)
    setHint('')
    setGenOpen(true)
  }
  const submitGenerate = () => {
    const h = hint.trim()
    setGenOpen(false)
    onGenerate(genForce, h || undefined)
  }

  // 补充说明只影响本次生成；素材里的影响与数字模型推不出来，由本人补最快
  const generateDialog = (
    <Modal
      open={genOpen}
      title={genForce ? '重新生成报告' : 'AI 生成报告'}
      okText="开始生成"
      cancelText="取消"
      confirmLoading={generating}
      onOk={submitGenerate}
      onCancel={() => setGenOpen(false)}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
        可选：补充今天/本周的重点、影响或数字。补充内容会作为高优先级事实参与生成，能明显提升报告质量。
      </Typography.Paragraph>
      <Input.TextArea
        value={hint}
        onChange={(e) => setHint(e.target.value)}
        placeholder="例如：登录改造已上线，覆盖 3 条业务线；本周还协助排查了 2 个线上问题……"
        maxLength={2000}
        showCount
        autoSize={{ minRows: 3, maxRows: 6 }}
      />
    </Modal>
  )

  if (!exists) {
    return (
      <>
        <Empty
          description="还没有报告：可「手动填写」直接写，或点「AI 生成」根据当天/当周工作条目与 git 提交生成草稿"
          style={{ padding: '48px 0' }}
        >
          <Space>
            <Button
              type="primary"
              icon={<EditOutlined />}
              loading={creating}
              onClick={async () => {
                setCreating(true)
                try {
                  await onCreateManual()
                } catch (e) {
                  showError(e, '创建失败')
                } finally {
                  setCreating(false)
                }
              }}
            >
              手动填写
            </Button>
            <Button icon={<RobotOutlined />} loading={generating} onClick={() => openGenerate(false)}>
              AI 生成
            </Button>
          </Space>
        </Empty>
        {generateDialog}
      </>
    )
  }

  return (
    <div
      style={fill ? { height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0 } : undefined}
    >
      <Space style={{ marginBottom: 12, flex: 'none' }} wrap>
        <Typography.Text type="secondary">更新于 {fmtTime(updatedAt)}</Typography.Text>
        <Button size="small" icon={<RobotOutlined />} loading={generating} onClick={() => openGenerate(true)}>
          重新生成
        </Button>
        {rebuild && (
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={rebuilding}
            onClick={doRebuild}
            title="以确定性数据为准重建文本（不经 LLM；数据是唯一事实源，文本只是其叙述投影）"
          >
            {rebuild.label ?? '从计划项重建'}
          </Button>
        )}
        {anyEditing && (
          <Button size="small" type="primary" loading={saving} onClick={save}>
            保存修改
          </Button>
        )}
      </Space>
      <div
        style={horizontal
          ? fill
            ? { flex: 1, minHeight: 0, display: 'flex', gap: 16, alignItems: 'stretch' }
            : { display: 'flex', gap: 16, alignItems: 'flex-start' }
          : fill
            ? { flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 16 }
            : undefined}
      >
        {fields.map((f) => {
          const content = values[f.key] || ''
          // 普通模式高度随内容行数伸缩（下限 220）；fill 模式由 flex 撑满页面剩余高度
          const boxHeight = horizontal ? rowHeight : heightOf(f.key)
          return (
            <div
              key={f.key}
              style={horizontal
                ? fill
                  ? { flex: '1 1 0', minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column' }
                  : { flex: '1 1 0', minWidth: 0 }
                : fill
                  ? { flex: 1, minHeight: 220, display: 'flex', flexDirection: 'column' }
                  : { marginBottom: 16 }}
            >
              <MarkdownEditor
                title={f.label}
                fill={fill}
                showToolbar={false}
                height={boxHeight}
                value={content}
                onChange={(v) => setValues({ ...values, [f.key]: v })}
                mode={modeOf(f.key)}
                onModeChange={(m) => setModes({ ...modes, [f.key]: m })}
              />
            </div>
          )
        })}
      </div>
      {generateDialog}
    </div>
  )
}
