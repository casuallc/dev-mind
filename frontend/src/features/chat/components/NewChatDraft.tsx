// 新问答草稿态：输入框直接开问——首条消息即开场提问，发送即创建问答。
// 高级选项：知识库（CAP-46 单库 / CAP-84 模型执行体多库 + 知识模式与过滤）/场景（CAP-33）/
// 执行节点/模型/权限模式（无项目/仓库，与项目会话完全分开），收进 Popover。
import { useEffect, useMemo, useState } from 'react'
import { Alert, Badge, Button, Empty, Form, Input, InputNumber, Popover, Select, Space, Switch, message } from 'antd'
import { SendOutlined, SettingOutlined } from '@ant-design/icons'
import { createChat } from '../api'
import type { ChatKnowledgeOptions, ChatSummary } from '../types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { listScenarios } from '../../scenarios/api'
import type { Scenario } from '../../scenarios/types'
import { listBases } from '../../knowledge/api'
import type { KnowledgeBase } from '../../knowledge/types'
import { DOC_TYPE_OPTIONS } from '../../knowledge/types'
import { listModelEndpoints } from '../../model/api'
import type { ModelEndpoint } from '../../model/types'
import ModelEndpointSelect from './ModelEndpointSelect'
import { showError } from '../../../shared/utils/showError'

const DEFAULTS = { permissionMode: 'acceptEdits', executor: 'AGENT', knowledgeMode: 'ANSWER' }
const MAX_KB = 5

const TEMPLATE_OPTIONS = [
  { value: 'FAULT', label: '故障排查' },
  { value: 'DEPLOY', label: '部署变更' },
  { value: 'DEV', label: '开发实现' },
  { value: 'FAQ', label: 'FAQ 答复' },
]

export default function NewChatDraft({
  onCreated,
  onCancel,
  presetKbId,
}: {
  onCreated: (c: ChatSummary) => void
  /** 有可选问答时提供「取消」返回选中态 */
  onCancel?: () => void
  /** CAP-46 FR-04：从知识库详情「发起会话」跳入时预选该库 */
  presetKbId?: number
}) {
  const [form] = Form.useForm()
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [endpoints, setEndpoints] = useState<ModelEndpoint[]>([])
  const [text, setText] = useState('')
  const [creating, setCreating] = useState(false)
  const [optionsOpen, setOptionsOpen] = useState(false)
  // preserve:true 必带：高级选项的 Form 住在 destroyOnHidden 的 Popover 里，弹层一关字段就卸载，
  // 默认口径（只取已注册字段）会看着像"什么都没选"——徽标点会灭、条件字段会闪回默认视图
  const values = Form.useWatch([], { form, preserve: true }) ?? {}
  const isModel = values.executor === 'MODEL'
  const selectedKbIds: number[] = values.knowledgeBaseIds ?? []
  const hasKnowledge = isModel ? selectedKbIds.length > 0 : Boolean(values.knowledgeBaseId)
  const isSolution = hasKnowledge && values.knowledgeMode === 'SOLUTION'

  // 只有 active 的 CHAT 端点能用于问答；一个都没有就不让选模型执行体（选了指望着 409 更糟）
  const chatEndpoints = useMemo(
    () => endpoints.filter((e) => e.kind === 'CHAT' && e.status === 'active'),
    [endpoints],
  )
  const hasDefaultChat = chatEndpoints.some((e) => e.isDefault)

  useEffect(() => {
    listAgentNodes()
      .then(setAgentNodes)
      .catch(() => undefined)
    listScenarios(true)
      .then(setScenarios)
      .catch(() => undefined)
    listBases()
      .then(setKnowledgeBases)
      .catch(() => undefined)
    listModelEndpoints()
      .then(setEndpoints)
      .catch(() => undefined)
  }, [])

  // 预选平台默认对话端点：创建时会把它钉住（与留空走默认等价，但用户看得见用的是哪个）
  useEffect(() => {
    if (chatEndpoints.length === 0) return
    const def = chatEndpoints.find((e) => e.isDefault)
    if (def) form.setFieldValue('modelEndpointId', def.id)
  }, [chatEndpoints, form])

  // 预选知识库（库详情「用此库问答」跳入）：有可用对话端点时直接进入知识会话（MODEL + 多库）；
  // 没有端点则退回 CAP-46 的 Agent 单库注入，至少让人能先问起来。
  useEffect(() => {
    if (presetKbId == null || knowledgeBases.length === 0) return
    if (!knowledgeBases.some((b) => b.id === presetKbId && b.status === 'active')) return
    if (chatEndpoints.length > 0) {
      form.setFieldValue('executor', 'MODEL')
      form.setFieldValue('knowledgeBaseIds', [presetKbId])
      form.setFieldValue('knowledgeMode', 'ANSWER')
    } else {
      form.setFieldValue('knowledgeBaseId', presetKbId)
    }
  }, [presetKbId, knowledgeBases, chatEndpoints.length, form])

  // 切执行体时把单库/多库互相带上（目标字段为空才带，避免覆盖用户已经改过的选择）
  useEffect(() => {
    if (isModel) {
      const single = form.getFieldValue('knowledgeBaseId') as number | undefined
      const multi = form.getFieldValue('knowledgeBaseIds') as number[] | undefined
      if (single && (!multi || multi.length === 0)) form.setFieldValue('knowledgeBaseIds', [single])
    } else {
      const multi = form.getFieldValue('knowledgeBaseIds') as number[] | undefined
      if (!form.getFieldValue('knowledgeBaseId') && multi?.length === 1) {
        form.setFieldValue('knowledgeBaseId', multi[0])
      }
    }
  }, [isModel, form])

  // 有任意非默认选项时给入口加徽标点
  const hasCustomOptions = useMemo(
    () =>
      Boolean(
        values.model ||
          values.scenarioCode ||
          values.agentNodeId ||
          values.knowledgeBaseId ||
          values.knowledgeBaseIds?.length ||
          values.knowledgeMode === 'SOLUTION' ||
          values.knowledgeTemplate ||
          values.knowledgeTopK ||
          values.knowledgeDocTypes?.length ||
          values.knowledgeTags?.length ||
          values.knowledgeRequireCitation ||
          values.executor === 'MODEL' ||
          (values.permissionMode && values.permissionMode !== DEFAULTS.permissionMode),
      ),
    [values],
  )

  const onSend = async () => {
    const t = text.trim()
    if (!t || creating) return
    // 模型执行体下 Agent 专有字段一个都不发：后端对这些字段是"有值即 400"（不静默忽略），
    // 界面上也藏起来了——别让隐藏字段把创建打回
    if (isModel && chatEndpoints.length === 0) {
      message.warning('暂无可用对话端点：请先在「后台 → 模型接入」登记 kind=CHAT 的端点')
      return
    }
    setCreating(true)
    try {
      // 取值必须 getFieldsValue(true)（取整个 store，含已卸载字段）：高级选项的 Form 住在
      // destroyOnHidden 的 Popover 里，用户点一下输入框弹层就关、字段随之卸载——无参
      // getFieldsValue 只回"已注册字段"，这时会把执行体/知识库/节点等选项静默丢光，
      // 表现就是"选了模型执行体，建的还是 runner 问答"（无值即回落后端默认）。
      const v = form.getFieldsValue(true)
      const model = v.executor === 'MODEL'
      // CAP-84：知识会话 = MODEL + 多库；知识模式/过滤只在绑了库时下发（无库的普通模型问答保持纯聊）
      const kbIds: number[] = model && Array.isArray(v.knowledgeBaseIds)
        ? (v.knowledgeBaseIds as number[]).slice(0, MAX_KB)
        : []
      const knowledgeMode = kbIds.length > 0
        ? (v.knowledgeMode === 'SOLUTION' ? 'SOLUTION' : 'ANSWER')
        : undefined
      const options: ChatKnowledgeOptions = {}
      if (v.knowledgeTopK) options.topK = v.knowledgeTopK as number
      if (v.knowledgeDocTypes?.length) options.docTypes = v.knowledgeDocTypes as string[]
      if (v.knowledgeTags?.length) options.tags = v.knowledgeTags as string[]
      if (v.knowledgeRequireCitation) options.requireCitation = true
      if (knowledgeMode === 'SOLUTION' && v.knowledgeTemplate) options.template = v.knowledgeTemplate as string
      const c = await createChat({
        message: t,
        executor: model ? 'MODEL' : undefined,
        modelEndpointId: model ? v.modelEndpointId || undefined : undefined,
        model: model ? undefined : v.model || undefined,
        permissionMode: model ? undefined : v.permissionMode || undefined,
        agentNodeId: model ? undefined : v.agentNodeId || undefined,
        scenarioCode: model ? undefined : v.scenarioCode || undefined,
        knowledgeBaseId: model ? undefined : v.knowledgeBaseId || undefined,
        knowledgeBaseIds: kbIds.length > 0 ? kbIds : undefined,
        knowledgeMode,
        knowledgeOptions: Object.keys(options).length > 0 ? options : undefined,
      })
      message.success(`问答已创建：${c.id}`)
      setText('')
      form.resetFields()
      onCreated(c)
    } catch (e) {
      showError(e, '创建失败')
    } finally {
      setCreating(false)
    }
  }

  const optionsForm = (
    <div style={{ width: 380, maxHeight: '70vh', overflowY: 'auto', paddingRight: 4 }}>
      <Form form={form} layout="vertical" size="small" initialValues={DEFAULTS}>
        <Form.Item
          label="执行体"
          name="executor"
          extra={
            isModel
              ? '服务端直连对话端点（本平台已接入的模型）：免 runner 节点、流式输出可中断；无工具/文件能力'
              : '在 runner 节点上跑 claude CLI：可用工具、读写文件、跑命令'
          }
          style={{ marginBottom: 12 }}
        >
          <Select
            options={[
              { value: 'AGENT', label: '智能体（Agent · claude CLI）' },
              {
                value: 'MODEL',
                label: '模型（直连对话端点）',
                disabled: chatEndpoints.length === 0,
              },
            ]}
          />
        </Form.Item>
        {isModel && (
          <>
            {chatEndpoints.length === 0 && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 12 }}
                message="暂无可用对话端点"
                description="请先在「后台 → 模型接入」登记 kind=CHAT 的端点（并设为平台默认），或改用智能体执行体。"
              />
            )}
            <Form.Item
              label="对话端点"
              name="modelEndpointId"
              extra={
                hasDefaultChat
                  ? '留空 = 平台默认对话端点；创建时钉住该端点，日后改默认不影响这个问答'
                  : '平台尚未设置默认对话端点：必须显式选一个（后台 → 模型接入 里可设为默认）'
              }
              style={{ marginBottom: 12 }}
            >
              <ModelEndpointSelect endpoints={chatEndpoints} />
            </Form.Item>
          </>
        )}
        {isModel ? (
          <>
            <Form.Item
              label={`知识库（最多 ${MAX_KB} 个）`}
              name="knowledgeBaseIds"
              extra="绑定后进入知识会话：每轮检索这些库并注入证据；不选 = 普通模型问答。知识会话仅支持模型执行体。"
              style={{ marginBottom: 12 }}
            >
              <Select
                mode="multiple"
                allowClear
                maxCount={MAX_KB}
                placeholder="（可选）选择一个或多个知识库"
                options={knowledgeBases
                  .filter((b) => b.status === 'active')
                  .map((b) => ({
                    value: b.id,
                    label: `${b.name}（${b.injectMode} · ${b.entryCount} 条）`,
                  }))}
                notFoundContent="暂无可用知识库（知识库 → 新建）"
                maxTagCount="responsive"
              />
            </Form.Item>
            {hasKnowledge && (
              <>
                <Form.Item
                  label="知识模式"
                  name="knowledgeMode"
                  extra={
                    isSolution
                      ? '生成固定九章结构方案：问题理解 → 结论 → 前提 → 操作步骤 → 参数 → 风险 → 回滚 → 待确认 → 参考来源'
                      : '逐轮检索证据回答；资料不足时明确说明，不编造'
                  }
                  style={{ marginBottom: 12 }}
                >
                  <Select
                    options={[
                      { value: 'ANSWER', label: 'ANSWER（检索问答）' },
                      { value: 'SOLUTION', label: 'SOLUTION（结构化方案）' },
                    ]}
                  />
                </Form.Item>
                {isSolution && (
                  <Form.Item
                    label="方案模板"
                    name="knowledgeTemplate"
                    extra="留空按问题关键词自动判别（默认故障排查）；模板只约束章节侧重"
                    style={{ marginBottom: 12 }}
                  >
                    <Select allowClear placeholder="自动判别" options={TEMPLATE_OPTIONS} />
                  </Form.Item>
                )}
                <Form.Item
                  label="每轮检索条数"
                  name="knowledgeTopK"
                  extra="注入的证据块上限；留空用平台默认"
                  style={{ marginBottom: 12 }}
                >
                  <InputNumber min={1} max={30} style={{ width: '100%' }} placeholder="默认 8" />
                </Form.Item>
                <Form.Item
                  label="文档类型过滤"
                  name="knowledgeDocTypes"
                  extra="只在这些类型里检索；留空不限"
                  style={{ marginBottom: 12 }}
                >
                  <Select
                    mode="multiple"
                    allowClear
                    placeholder="不限"
                    options={DOC_TYPE_OPTIONS}
                    maxTagCount="responsive"
                  />
                </Form.Item>
                <Form.Item
                  label="标签过滤"
                  name="knowledgeTags"
                  extra="多标签为 OR（命中任一即可）"
                  style={{ marginBottom: 12 }}
                >
                  <Select mode="tags" open={false} placeholder="回车添加标签" />
                </Form.Item>
                <Form.Item
                  label="强制引用"
                  name="knowledgeRequireCitation"
                  valuePropName="checked"
                  extra="要求回答标注 [S#] 并在末尾列出参考来源"
                  style={{ marginBottom: 12 }}
                >
                  <Switch />
                </Form.Item>
              </>
            )}
          </>
        ) : (
          <Form.Item
            label="知识库"
            name="knowledgeBaseId"
            extra="绑定后：启动注入库概览，每轮提问自动检索库内相关内容注入（CAP-46 单库）；检索过滤/方案模式请改用模型执行体"
            style={{ marginBottom: 12 }}
          >
            <Select
              allowClear
              placeholder="（可选）绑定知识库"
              options={knowledgeBases
                .filter((b) => b.status === 'active')
                .map((b) => ({
                  value: b.id,
                  label: `${b.name}（${b.injectMode} · ${b.entryCount} 条）`,
                }))}
              notFoundContent="暂无可用知识库（知识库 → 新建）"
            />
          </Form.Item>
        )}
        {!isModel && (
          <>
            <Form.Item
              label="场景"
              name="scenarioCode"
              extra="场景骨架包裹首条提问，并注入其绑定的知识/skills/文档；PROJECT 场景按该项目身份装配"
              style={{ marginBottom: 12 }}
            >
              <Select
                allowClear
                placeholder="（可选）选择场景"
                options={scenarios.map((s) => ({
                  value: s.code,
                  label: `${s.name}${s.scope === 'PROJECT' ? '（项目）' : ''}`,
                }))}
                notFoundContent="暂无可用场景（后台 → 场景 创建）"
              />
            </Form.Item>
            <Form.Item
              label="执行节点"
              name="agentNodeId"
              extra="留空 = 平台默认节点；无默认则创建失败"
              style={{ marginBottom: 12 }}
            >
              <Select
                placeholder="跟随默认（平台默认节点）"
                allowClear
                options={agentNodes
                  .filter((n) => n.status === 'ONLINE')
                  .map((n) => ({
                    value: String(n.id),
                    label: `${n.name} (${n.os ?? '远程节点'})${n.isDefault ? ' · 平台默认' : ''}`,
                  }))}
                notFoundContent="暂无在线节点（后台 → Agent 节点 注册）"
              />
            </Form.Item>
            <Form.Item
              label="CLI 模型名"
              name="model"
              extra="传给 claude 的模型名；留空使用全局默认"
              style={{ marginBottom: 12 }}
            >
              <Input placeholder="留空使用全局默认模型" />
            </Form.Item>
            <Form.Item label="权限模式" name="permissionMode" style={{ marginBottom: 0 }}>
              <Select
                options={[
                  { value: 'acceptEdits', label: 'acceptEdits（默认）' },
                  { value: 'default', label: 'default（需要授权）' },
                  { value: 'bypassPermissions', label: 'bypassPermissions（全放）' },
                  { value: 'plan', label: 'plan（只读规划）' },
                ]}
              />
            </Form.Item>
          </>
        )}
      </Form>
    </div>
  )

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 420 }}>
      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty
          description={
            <>
              新问答——在下方输入问题，发送即开始对话。
              <br />
              纯问答不关联项目/仓库；默认由 runner 节点上的 claude 回答，
              在「高级选项」里可改选已接入的对话模型（免节点、流式输出）。
            </>
          }
        />
      </div>
      <div style={{ border: '1px solid #d9d9d9', borderRadius: 8, padding: 12 }}>
        <Input.TextArea
          autoSize={{ minRows: 3, maxRows: 8 }}
          variant="borderless"
          value={text}
          placeholder="例如：解释一下 Spring 虚拟线程的调度模型。Enter 发送 / Shift+Enter 换行…"
          onChange={(e) => setText(e.target.value)}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault()
              onSend()
            }
          }}
        />
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 4 }}>
          <Space>
            <Popover
              content={optionsForm}
              trigger="click"
              open={optionsOpen}
              onOpenChange={setOptionsOpen}
              placement="topLeft"
              destroyOnHidden
            >
              <Badge dot={hasCustomOptions}>
                <Button size="small" icon={<SettingOutlined />}>
                  高级选项
                </Button>
              </Badge>
            </Popover>
            {onCancel && (
              <Button size="small" type="text" onClick={onCancel}>
                取消
              </Button>
            )}
          </Space>
          <Button
            type="primary"
            icon={<SendOutlined />}
            loading={creating}
            disabled={!text.trim()}
            onClick={onSend}
          >
            发送
          </Button>
        </div>
      </div>
    </div>
  )
}
