// CAP-104 用例规范编辑器：HTTP / COMMAND / MANUAL 三类型共用一套 CaseSpec 表单。
// 断言按类型切换 source；MANUAL 无自动断言（人工判定）；保存即生成新版本（已通过/已废弃用例升版本回草稿）。
import {
  Alert,
  Button,
  Collapse,
  Drawer,
  Flex,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Typography,
} from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { useEffect, useMemo } from 'react'
import type { AgentNode } from '../../agent/types'
import { paramsToText, textToParams } from '../../../shared/utils/format'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import {
  ASSERTION_OP_OPTIONS,
  ASSERTION_SOURCE_OPTIONS,
  CASE_PRIORITY_OPTIONS,
  CASE_TYPE_OPTIONS,
} from '../constants'
import type { CaseSpec, CaseType, TestAsset, TestCase, TestSuite } from '../types'

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

const CASE_TYPE_HINT: Record<CaseType, string> = {
  HTTP: '由服务端/节点发起 HTTP 请求，断言状态码、响应头、JSON 字段或响应文本。',
  COMMAND: '经 exec 帧下发执行节点，断言退出码、stdout/stderr、产物文件；受 runner 白名单约束。',
  MANUAL: '人工执行并判定结果；运行后由有复核权限的人在运行详情里给出 PASS/FAIL 与备注。',
}

interface AssertionForm {
  source: string
  selector?: string
  op: string
  expected?: string
}

export interface CaseSpecFormValues {
  suiteId: number
  title: string
  caseKey?: string
  type: CaseType
  priority?: string
  tags?: string[]
  preconditionsText?: string
  stepsText?: string
  cleanupText?: string
  assertions?: AssertionForm[]
  location?: string
  nodeId?: string
  timeoutSec?: number | null
  envText?: string
  method?: string
  path?: string
  paramsText?: string
  headersText?: string
  body?: string
  command?: string
  workingDir?: string
  /** CAP-104 M1.5：'1' = 程序契约（执行资产包入口脚本）；空 = 普通命令 */
  contractVersion?: string
  instructions?: string
  sourceAssetId?: number | null
  changeNote?: string
}

function linesToArr(text?: string): string[] | undefined {
  const arr = (text ?? '').split('\n').map((s) => s.trim()).filter(Boolean)
  return arr.length ? arr : undefined
}

function arrToLines(arr?: string[] | null): string {
  return (arr ?? []).join('\n')
}

function defaultAssertions(type: CaseType): AssertionForm[] {
  if (type === 'HTTP') return [{ source: 'HTTP_STATUS', op: 'EQUALS', expected: '200' }]
  if (type === 'COMMAND') return [{ source: 'EXIT_CODE', op: 'EQUALS', expected: '0' }]
  return []
}

/** 存量 CAP-10 用例无 spec_json：按旧字段合成一份规范，首次保存即升级为规范用例 */
function legacyCaseSpec(c: TestCase): CaseSpec {
  const e = (c.expected ?? {}) as Record<string, unknown>
  const status = e.status != null ? String(e.status) : null
  if (c.kind === 'health' && e.type === 'command') {
    return {
      title: c.name,
      type: 'COMMAND',
      command: { command: String(e.command ?? '') },
      executor: { location: 'NODE' },
      assertions: [{ source: 'EXIT_CODE', op: 'EQUALS', expected: '0' }],
    }
  }
  const path = c.kind === 'health' && e.url ? String(e.url) : c.path
  return {
    title: c.name,
    type: 'HTTP',
    http: { method: c.method || 'GET', path, params: c.params, headers: c.headers, body: c.body },
    executor: { location: 'SERVER' },
    assertions: status ? [{ source: 'HTTP_STATUS', op: 'EQUALS', expected: status }] : [],
  }
}

export function specToForm(suiteId: number, spec: CaseSpec, fallback?: TestCase | null): CaseSpecFormValues {
  const type: CaseType = spec.type ?? 'HTTP'
  return {
    suiteId,
    title: spec.title ?? fallback?.name ?? '',
    caseKey: spec.caseKey ?? fallback?.caseKey ?? undefined,
    type,
    priority: spec.priority ?? 'P2',
    tags: spec.tags ?? [],
    preconditionsText: arrToLines(spec.preconditions),
    stepsText: arrToLines(spec.steps),
    cleanupText: arrToLines(spec.cleanup),
    assertions: (spec.assertions ?? []).map((a) => ({
      source: a.source,
      selector: a.selector ?? '',
      op: a.op,
      expected: a.expected ?? '',
    })),
    location: spec.executor?.location ?? (type === 'COMMAND' ? 'NODE' : 'SERVER'),
    nodeId: spec.executor?.nodeId ?? undefined,
    timeoutSec: spec.executor?.timeoutSec ?? undefined,
    envText: paramsToText(spec.executor?.env ?? undefined),
    method: spec.http?.method ?? fallback?.method ?? 'GET',
    path: spec.http?.path ?? fallback?.path ?? '',
    paramsText: paramsToText(spec.http?.params ?? undefined),
    headersText: paramsToText(spec.http?.headers ?? undefined),
    body: spec.http?.body ?? undefined,
    command: spec.command?.command ?? undefined,
    workingDir: spec.command?.workingDir ?? undefined,
    contractVersion: spec.command?.contractVersion ?? undefined,
    instructions: spec.manual?.instructions ?? undefined,
    sourceAssetId: spec.source?.assetId ?? undefined,
    changeNote: '',
  }
}

export function formToSpec(v: CaseSpecFormValues): CaseSpec {
  const type = v.type
  const assertions = type === 'MANUAL'
    ? undefined
    : (v.assertions ?? [])
        .filter((a) => a && a.source && a.op)
        .map((a) => ({
          source: a.source,
          selector: a.selector?.trim() ? a.selector.trim() : null,
          op: a.op,
          expected: a.op === 'EXISTS' || a.op === 'NOT_EXISTS'
            ? null
            : a.expected?.trim()
              ? a.expected.trim()
              : null,
        }))
  const location = type === 'COMMAND' ? 'NODE' : (v.location || 'SERVER')
  return {
    caseKey: v.caseKey?.trim() || null,
    title: v.title.trim(),
    type,
    priority: v.priority ?? null,
    tags: v.tags?.length ? v.tags : undefined,
    preconditions: linesToArr(v.preconditionsText),
    steps: linesToArr(v.stepsText),
    assertions: assertions?.length ? assertions : undefined,
    cleanup: linesToArr(v.cleanupText),
    executor: {
      location,
      nodeId: location === 'NODE' ? v.nodeId || null : null,
      timeoutSec: v.timeoutSec ?? null,
      env: type === 'COMMAND' && v.envText?.trim() ? textToParams(v.envText) : null,
    },
    http: type === 'HTTP'
      ? {
          method: v.method || 'GET',
          path: v.path?.trim() ?? '',
          params: v.paramsText?.trim() ? textToParams(v.paramsText) : null,
          headers: v.headersText?.trim() ? textToParams(v.headersText) : null,
          body: v.body?.trim() ? v.body : null,
        }
      : null,
    command: type === 'COMMAND'
      ? {
          command: v.command?.trim() ?? '',
          workingDir: v.workingDir?.trim() || null,
          contractVersion: v.contractVersion?.trim() || null,
        }
      : null,
    manual: type === 'MANUAL' ? { instructions: v.instructions?.trim() || null } : null,
    source: v.sourceAssetId
      ? { kind: 'ASSET', assetId: v.sourceAssetId }
      : { kind: 'MANUAL' },
  }
}

export default function CaseSpecDrawer({ open, suites, nodes, assets, editing, defaultSuiteId, saving, onClose, onSave }: {
  open: boolean
  suites: TestSuite[]
  nodes: AgentNode[]
  assets: TestAsset[]
  editing: TestCase | null
  defaultSuiteId?: number
  saving: boolean
  onClose: () => void
  onSave: (v: CaseSpecFormValues) => void
}) {
  const [form] = Form.useForm<CaseSpecFormValues>()
  const type = Form.useWatch('type', form) ?? 'HTTP'
  const location = Form.useWatch('location', form) ?? 'SERVER'
  const contractVersion = Form.useWatch('contractVersion', form)
  const isProgram = type === 'COMMAND' && contractVersion === '1'
  const programAssets = useMemo(() => assets.filter((a) => a.assetRole === 'PROGRAM'), [assets])
  const dataAssets = useMemo(() => assets.filter((a) => a.assetRole !== 'PROGRAM'), [assets])

  useEffect(() => {
    if (!open) return
    form.resetFields()
    if (editing?.spec) {
      form.setFieldsValue(specToForm(editing.suiteId, editing.spec, editing))
    } else if (editing) {
      form.setFieldsValue(specToForm(editing.suiteId, legacyCaseSpec(editing), editing))
    } else {
      form.setFieldsValue({
        suiteId: defaultSuiteId ?? suites[0]?.id,
        type: 'HTTP',
        priority: 'P2',
        tags: [],
        location: 'SERVER',
        method: 'GET',
        assertions: defaultAssertions('HTTP'),
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, editing, defaultSuiteId])

  const nodeOptions = useMemo(
    () => nodes.map((n) => ({
      value: String(n.id),
      label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
    })),
    [nodes],
  )

  const onTypeChange = (t: CaseType) => {
    form.setFieldValue('location', t === 'COMMAND' ? 'NODE' : 'SERVER')
    form.setFieldValue('assertions', defaultAssertions(t))
    if (t !== 'COMMAND') form.setFieldValue('contractVersion', undefined)
  }

  const sourceAssetOptions = (isProgram ? programAssets : dataAssets).map((a) => ({
    value: a.id,
    label: isProgram
      ? `#${a.id} ${a.name}（${a.runtime ?? '?'} · ${a.targetOs ?? 'ANY'}/${a.targetArch ?? 'ANY'}）`
      : `#${a.id} ${a.name}`,
  }))

  const onSourceAssetChange = (id: number | undefined) => {
    if (!isProgram) return
    const asset = programAssets.find((a) => a.id === id)
    if (asset?.entrypoint) {
      form.setFieldValue('command', `sh ${asset.entrypoint}`)
    }
  }

  return (
    <Drawer
      title={editing ? `编辑用例 · ${editing.caseKey ?? editing.name}` : '新建用例'}
      width={DRAWER_DEFAULT}
      open={open}
      onClose={onClose}
      destroyOnHidden
      footer={
        <Flex justify="flex-end" gap={8}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={() => form.submit()}>保存</Button>
        </Flex>
      }
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={onSave}
        onValuesChange={(changed) => {
          if (changed.type) onTypeChange(changed.type as CaseType)
        }}
      >
        <Flex gap={8} align="start">
          <Form.Item label="所属套件" name="suiteId" rules={[{ required: true, message: '请选择套件' }]}
            style={{ flex: 1 }}>
            <Select
              disabled={!!editing}
              options={suites.filter((s) => s.kind !== 'script').map((s) => ({ value: s.id, label: s.name }))}
              placeholder="选择套件（用例归属项目由套件决定）"
            />
          </Form.Item>
          <Form.Item label="用例类型" name="type" style={{ width: 180 }}>
            <Select options={CASE_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item label="优先级" name="priority" style={{ width: 100 }}>
            <Select options={CASE_PRIORITY_OPTIONS} />
          </Form.Item>
        </Flex>
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message={CASE_TYPE_HINT[type]} />
        <Flex gap={8} align="start">
          <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入用例标题' }]}
            style={{ flex: 1 }}>
            <Input placeholder="如 登录接口返回 200 且带 token" />
          </Form.Item>
          <Form.Item label="用例标识（可选）" name="caseKey"
            tooltip="项目内唯一；创建后不可修改，留空自动生成" style={{ width: 220 }}>
            <Input placeholder="LOGIN-001" disabled={!!editing} />
          </Form.Item>
        </Flex>
        <Flex gap={8} align="start">
          <Form.Item label="标签" name="tags" style={{ flex: 1 }}>
            <Select mode="tags" placeholder="回车添加，如 smoke/regression" />
          </Form.Item>
          <Form.Item
            label={isProgram ? '程序包（必选）' : '来源资产（可选）'}
            name="sourceAssetId"
            style={{ width: 300 }}
            rules={isProgram ? [{ required: true, message: '请选择已评审通过的程序包' }] : undefined}
            tooltip={isProgram
              ? '仅列出 APPROVED 的 PROGRAM 程序包；执行时 runner 只运行清单声明的入口脚本'
              : '仅列出已评审通过的资产；引用后套件也可绑定同一资产'}
          >
            <Select
              allowClear
              placeholder={isProgram ? '选择程序包' : '手工编写'}
              options={sourceAssetOptions}
              onChange={onSourceAssetChange}
            />
          </Form.Item>
        </Flex>

        {type === 'HTTP' && (
          <>
            <Flex gap={8} align="start">
              <Form.Item label="方法" name="method" style={{ width: 110 }}>
                <Select options={METHODS.map((m) => ({ value: m, label: m }))} />
              </Form.Item>
              <Form.Item label="路径" name="path" rules={[{ required: true, message: '请输入请求路径' }]}
                style={{ flex: 1 }}>
                <Input placeholder="如 /api/users/{id}" />
              </Form.Item>
            </Flex>
            <Flex gap={8} align="start">
              <Form.Item label="Query 参数（每行 k=v）" name="paramsText" style={{ flex: 1 }}>
                <Input.TextArea rows={2} placeholder="name=test" />
              </Form.Item>
              <Form.Item label="Header（每行 k=v）" name="headersText" style={{ flex: 1 }}>
                <Input.TextArea rows={2} placeholder="X-Api-Key=xxx" />
              </Form.Item>
            </Flex>
            <Form.Item label="请求体（JSON）" name="body">
              <Input.TextArea rows={2} placeholder='{"name":"carol"}' />
            </Form.Item>
          </>
        )}

        {type === 'COMMAND' && (
          <>
            <Flex gap={8} align="start">
              <Form.Item label="执行方式" name="contractVersion" style={{ width: 300 }}
                tooltip="普通命令由 runner 直接执行；程序契约 v1 会下发已评审的程序包，按清单 entrypoint 运行">
                <Select
                  allowClear
                  placeholder="普通命令（runner 直接执行）"
                  options={[{ value: '1', label: '程序契约 v1（预构建程序包）' }]}
                  onChange={(v) => {
                    const cur = form.getFieldValue('sourceAssetId') as number | undefined
                    const curIsProgram = programAssets.some((a) => a.id === cur)
                    if (v === '1') {
                      form.setFieldValue('assertions', [{ source: 'EXIT_CODE', op: 'EQUALS', expected: '0' }])
                      if (cur != null && !curIsProgram) form.setFieldValue('sourceAssetId', undefined)
                    } else if (curIsProgram) {
                      form.setFieldValue('sourceAssetId', undefined)
                    }
                  }}
                />
              </Form.Item>
            </Flex>
            {isProgram && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 12 }}
                message="程序型用例：平台注入 input.json/result.json/artifacts 路径，程序输出按契约 v1 映射；退出码非 0 优先于 result.json 的 status。"
              />
            )}
            <Form.Item label={isProgram ? '入口命令（调用包内入口脚本）' : '命令'} name="command"
              rules={[{ required: true, message: '请输入命令' }]}>
              <Input.TextArea rows={3} placeholder={isProgram
                ? '如 sh run.sh（入口须在程序清单 entrypoint 内且为包内相对路径）'
                : '如 ./gradlew test 或 bash scripts/e2e.sh'} />
            </Form.Item>
            <Form.Item label="工作目录（相对程序包根目录/工作区，可选）" name="workingDir">
              <Input placeholder="如 tests/e2e" />
            </Form.Item>
            <Form.Item label="环境变量（每行 k=v，可选）" name="envText">
              <Input.TextArea autoSize={{ minRows: 2 }} placeholder="TARGET_URL=http://…" />
            </Form.Item>
          </>
        )}

        {type === 'MANUAL' && (
          <Form.Item label="人工执行说明" name="instructions">
            <Input.TextArea rows={4} placeholder="列出人工操作步骤与判定标准" />
          </Form.Item>
        )}

        {type !== 'MANUAL' && (
          <Flex gap={8} align="start">
            <Form.Item label={type === 'COMMAND' ? '执行位置（固定节点）' : '执行位置'} name="location"
              style={{ width: 170 }}>
              <Select
                disabled={type === 'COMMAND'}
                options={[
                  { value: 'SERVER', label: '服务端 SERVER' },
                  { value: 'NODE', label: '执行节点 NODE' },
                ]}
              />
            </Form.Item>
            {location === 'NODE' && (
              <Form.Item label="指定节点（可选，留空用默认节点）" name="nodeId" style={{ width: 260 }}>
                <Select allowClear options={nodeOptions} placeholder="平台默认节点" />
              </Form.Item>
            )}
            <Form.Item label="超时（秒）" name="timeoutSec" style={{ width: 130 }}>
              <InputNumber min={1} max={86400} style={{ width: '100%' }} placeholder="默认" />
            </Form.Item>
          </Flex>
        )}

        {type !== 'MANUAL' && (
          <Form.Item label="断言" style={{ marginBottom: 8 }}>
            <Form.List name="assertions">
              {(fields, { add, remove }) => (
                <Space direction="vertical" style={{ width: '100%' }} size={8}>
                  {fields.map((field) => (
                    <Flex key={field.key} gap={8} align="start">
                      <Form.Item name={[field.name, 'source']} noStyle>
                        <Select style={{ width: 200 }} options={ASSERTION_SOURCE_OPTIONS[type]} placeholder="断言来源" />
                      </Form.Item>
                      <Form.Item name={[field.name, 'selector']} noStyle>
                        <Input style={{ width: 160 }} placeholder="selector（如 $.data.id）" />
                      </Form.Item>
                      <Form.Item name={[field.name, 'op']} noStyle>
                        <Select style={{ width: 160 }} options={ASSERTION_OP_OPTIONS} placeholder="操作符" />
                      </Form.Item>
                      <Form.Item name={[field.name, 'expected']} noStyle>
                        <Input style={{ flex: 1 }} placeholder="期望值（EXISTS 可留空）" />
                      </Form.Item>
                      <Button type="text" danger icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
                    </Flex>
                  ))}
                  <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ source: ASSERTION_SOURCE_OPTIONS[type][0]?.value, op: 'EQUALS' })}>
                    添加断言
                  </Button>
                </Space>
              )}
            </Form.List>
          </Form.Item>
        )}

        <Collapse
          ghost
          size="small"
          style={{ marginTop: 8 }}
          items={[
            {
              key: 'steps',
              label: '前置条件 / 执行步骤 / 清理',
              children: (
                <>
                  <Form.Item label="前置条件（每行一条）" name="preconditionsText">
                    <Input.TextArea rows={2} placeholder="如 环境已部署、测试账号存在" />
                  </Form.Item>
                  <Form.Item label="执行步骤（每行一条）" name="stepsText">
                    <Input.TextArea rows={3} placeholder="如 1. 调用登录接口" />
                  </Form.Item>
                  <Form.Item label="清理步骤（每行一条）" name="cleanupText">
                    <Input.TextArea rows={2} placeholder="如 删除测试数据" />
                  </Form.Item>
                </>
              ),
            },
          ]}
        />

        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 8 }}>
          已通过/已废弃的用例再次保存会生成新版本并回到草稿，需重新评审；草稿可直接单次运行验证。
        </Typography.Paragraph>
        <Form.Item label="版本说明" name="changeNote">
          <Input placeholder="本次修改原因（写入版本记录）" />
        </Form.Item>
      </Form>
    </Drawer>
  )
}
