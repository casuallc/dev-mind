import {
  Button,
  Card,
  Dropdown,
  Input,
  Modal,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { AppstoreOutlined, BarsOutlined, ExportOutlined, ImportOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  copyShared,
  createBookmark,
  createGroup,
  createShare,
  createTag,
  deleteBookmark,
  deleteGroup,
  deleteShare,
  deleteTag,
  getAccountSecret,
  importBookmarks,
  listBookmarks,
  listGroups,
  listShares,
  listTags,
  moveBookmarks,
  probeBookmark,
  probeBookmarks,
  renameTag,
  sharedWithMe,
  updateBookmark,
  updateGroup,
  visitBookmark,
} from '../api'
import type {
  Bookmark,
  BookmarkFilter,
  BookmarkGroup,
  BookmarkShare,
  BookmarkStatus,
  BookmarkTagSummary,
  ImportNode,
  SharedWithMe,
} from '../types'
import BookmarkCardGrid from '../components/BookmarkCardGrid'
import BookmarkDrawer, { type BookmarkFormValues } from '../components/BookmarkDrawer'
import GroupModal from '../components/GroupModal'
import GroupTree, { findGroup, type GroupSelection } from '../components/GroupTree'
import ImportModal from '../components/ImportModal'
import ShareModal, { type ShareTarget } from '../components/ShareModal'
import SharedWithMePane from '../components/SharedWithMePane'
import StatusDot from '../components/StatusDot'
import TagsPane from '../components/TagsPane'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { buildBookmarkFile } from '../utils/netscape'

type View = 'mine' | 'shared' | 'shares' | 'tags'
type ViewMode = 'table' | 'card'

const VIEW_MODE_KEY = 'bookmark.viewMode'

const VIEWS = [
  { value: 'mine', label: '我的收藏' },
  { value: 'shared', label: '与我分享' },
  { value: 'shares', label: '分享管理' },
  { value: 'tags', label: '标签' },
]

const STATUS_OPTIONS = [
  { value: 'ALL', label: '全部状态' },
  { value: 'OK', label: '正常' },
  { value: 'FAIL', label: '异常' },
  { value: 'UNKNOWN', label: '未探测' },
]

/** 分组下拉的可选项（缩进体现层级；默认分组用 null） */
function groupOptions(groups: BookmarkGroup[], depth = 0): { value: number; label: string }[] {
  return groups.flatMap((g) => [
    { value: g.id, label: `${'　'.repeat(depth)}${g.name}` },
    ...groupOptions(g.children, depth + 1),
  ])
}

function flatten(groups: BookmarkGroup[]): BookmarkGroup[] {
  return groups.flatMap((g) => [g, ...flatten(g.children)])
}

/**
 * CAP-64 收藏夹（个人级，owner 隔离）：我的收藏 / 与我分享 / 分享管理 / 标签 四个视图。
 * 归属校验全在服务端（越权得到的是 404），前端不做权限判断。
 */
export default function BookmarksPage() {
  const [view, setView] = useState<View>('mine')
  const [viewMode, setViewMode] = useState<ViewMode>(() =>
    localStorage.getItem(VIEW_MODE_KEY) === 'card' ? 'card' : 'table',
  )

  const [groups, setGroups] = useState<BookmarkGroup[]>([])
  const [tags, setTags] = useState<BookmarkTagSummary[]>([])
  const [rows, setRows] = useState<Bookmark[]>([])
  const [allRows, setAllRows] = useState<Bookmark[]>([])
  const [shared, setShared] = useState<SharedWithMe[]>([])
  const [shares, setShares] = useState<BookmarkShare[]>([])
  const [loading, setLoading] = useState(false)

  // 筛选（groupId 走服务端子树语义，前端侧栏计数用全量列表自己算）
  const [sel, setSel] = useState<GroupSelection>({ kind: 'all' })
  const [keyword, setKeyword] = useState('')
  const [keywordDraft, setKeywordDraft] = useState('')
  const [tagIds, setTagIds] = useState<number[]>([])
  const [status, setStatus] = useState<BookmarkStatus | 'ALL'>('ALL')
  const [selectedKeys, setSelectedKeys] = useState<string[]>([])

  // 弹层
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editing, setEditing] = useState<Bookmark | null>(null)
  const [saving, setSaving] = useState(false)
  const [groupModal, setGroupModal] = useState<{ editing: BookmarkGroup | null; parent: BookmarkGroup | null } | null>(null)
  const [tagModal, setTagModal] = useState<{ editing: BookmarkTagSummary | null } | null>(null)
  const [tagName, setTagName] = useState('')
  const [shareTarget, setShareTarget] = useState<ShareTarget | null>(null)
  const [shareSaving, setShareSaving] = useState(false)
  const [moveOpen, setMoveOpen] = useState(false)
  const [moveTarget, setMoveTarget] = useState<number | null>(null)
  const [accountsOf, setAccountsOf] = useState<Bookmark | null>(null)
  const [secrets, setSecrets] = useState<Record<string, string | null>>({})
  const [copying, setCopying] = useState<{ bookmark: Bookmark; owner: string } | null>(null)
  const [copyGroup, setCopyGroup] = useState<number | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [importSaving, setImportSaving] = useState(false)

  const dupAcknowledged = useRef(false)
  const [polling, setPolling] = useState(false)

  const filter = useMemo<BookmarkFilter>(
    () => ({
      groupId: sel.kind === 'group' ? sel.id : null,
      ungrouped: sel.kind === 'ungrouped',
      tagIds,
      keyword,
      status,
    }),
    [sel, tagIds, keyword, status],
  )
  const filterRef = useRef(filter)
  filterRef.current = filter

  const fetchMine = useCallback(async (showLoading: boolean) => {
    if (showLoading) setLoading(true)
    try {
      // 全量那份给侧栏计数与「同 URL 已存在」提示用
      const [filtered, all] = await Promise.all([listBookmarks(filterRef.current), listBookmarks({})])
      setRows(filtered)
      setAllRows(all)
    } catch (e) {
      if (showLoading) showError(e, '加载收藏失败')
    } finally {
      if (showLoading) setLoading(false)
    }
  }, [])

  const reloadMine = useCallback(() => fetchMine(true), [fetchMine])
  const reloadGroups = useCallback(
    () => listGroups().then(setGroups).catch((e) => showError(e, '加载分组失败')),
    [],
  )
  const reloadTags = useCallback(() => listTags().then(setTags).catch((e) => showError(e, '加载标签失败')), [])

  useEffect(() => {
    reloadGroups()
    reloadTags()
  }, [reloadGroups, reloadTags])

  useEffect(() => {
    reloadMine()
  }, [filter, reloadMine])

  // FR-04 批量探测进行中轮询（异步逐条执行，结果落 last_checked_at）；超时兜底停轮询
  useEffect(() => {
    if (!polling) return
    const t = setInterval(() => fetchMine(false), 3000)
    const stop = setTimeout(() => {
      setPolling(false)
      message.info('批量探测轮询已结束，可点「刷新」查看最新结果')
    }, 60000)
    return () => {
      clearInterval(t)
      clearTimeout(stop)
    }
  }, [polling, fetchMine])

  const loadShared = useCallback(
    () => sharedWithMe().then(setShared).catch((e) => showError(e, '加载分享失败')),
    [],
  )
  const loadShares = useCallback(() => listShares().then(setShares).catch((e) => showError(e, '加载分享失败')), [])

  const reloadCurrent = useCallback(() => {
    if (view === 'shared') loadShared()
    else if (view === 'shares') loadShares()
    else if (view === 'tags') {
      reloadTags()
      reloadGroups()
    } else reloadMine()
  }, [view, loadShared, loadShares, reloadMine, reloadGroups, reloadTags])

  useEffect(() => {
    if (view === 'shared') loadShared()
    else if (view === 'shares') loadShares()
  }, [view, loadShared, loadShares])

  // ---------------- FR-06 打开 ----------------

  const openBookmark = (b: Bookmark) => {
    window.open(b.url, '_blank', 'noopener')
    // 失败静默（记录最近访问只是排序辅助，不该弹错）
    visitBookmark(b.id)
      .then(() => fetchMine(false))
      .catch(() => {})
  }

  // ---------------- FR-01 保存 ----------------

  const resolveTagIds = async (names: string[]): Promise<number[]> => {
    const ids: number[] = []
    for (const name of names) {
      const known = tags.find((t) => t.name === name)
      if (known) {
        ids.push(known.id)
        continue
      }
      ids.push((await createTag(name)).id) // 同名幂等返回既有标签
    }
    return ids
  }

  const submitBookmark = async (v: BookmarkFormValues) => {
    const url = v.url.trim()
    const dup = allRows.find((r) => r.url.trim() === url && r.id !== editing?.id)
    if (dup && !dupAcknowledged.current) {
      Modal.confirm({
        title: '已存在同地址的收藏',
        content: `「${dup.title}」用的是同一个地址。不同环境参数属于合法场景，仍然保存吗？`,
        okText: '仍然保存',
        cancelText: '返回修改',
        onOk: () => {
          dupAcknowledged.current = true
          void submitBookmark(v)
        },
      })
      return
    }
    dupAcknowledged.current = false

    setSaving(true)
    try {
      const payload = {
        title: v.title.trim(),
        url,
        description: v.description?.trim() || undefined,
        groupId: v.groupId ?? null,
        tagIds: await resolveTagIds(v.tagNames ?? []),
        accounts: (v.accounts ?? [])
          .filter((a) => a.label?.trim())
          .map((a) => ({
            id: a.id || undefined,
            label: a.label.trim(),
            username: a.username?.trim() || undefined,
            password: a.password ? a.password : undefined,
            clearPassword: a.clearPassword === true,
            note: a.note?.trim() || undefined,
          })),
      }
      if (editing) await updateBookmark(editing.id, payload)
      else await createBookmark(payload)
      message.success(editing ? '已保存' : `已收藏「${payload.title}」`)
      setDrawerOpen(false)
      setEditing(null)
      await Promise.all([fetchMine(true), reloadGroups(), reloadTags()])
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const confirmDeleteBookmark = (b: Bookmark) =>
    Modal.confirm({
      centered: true,
      title: `删除收藏「${b.title}」？`,
      content: '挂着的账号与标签关联一并删除，已发出的分享同时失效。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () =>
        deleteBookmark(b.id)
          .then(() => {
            message.success('已删除')
            return Promise.all([fetchMine(true), reloadGroups()])
          })
          .catch((e) => showError(e, '删除失败')),
    })

  // ---------------- FR-09 导入 ----------------

  const submitImport = async (nodes: ImportNode[]) => {
    setImportSaving(true)
    try {
      const r = await importBookmarks(nodes)
      const skipped = [
        r.skippedDuplicates ? `跳过重复 ${r.skippedDuplicates} 条` : '',
        r.skippedInvalid ? `跳过无效地址 ${r.skippedInvalid} 条` : '',
      ]
        .filter(Boolean)
        .join('，')
      message.success(`导入完成：新增 ${r.createdBookmarks} 条收藏、${r.createdGroups} 个分组${skipped ? `，${skipped}` : ''}`)
      setImportOpen(false)
      await Promise.all([fetchMine(true), reloadGroups(), reloadTags()])
    } catch (e) {
      showError(e, '导入失败')
    } finally {
      setImportSaving(false)
    }
  }

  // ---------------- 导出（FR-09 逆操作：Chrome 可直接导入的 Netscape HTML） ----------------

  const exportBookmarks = () => {
    if (allRows.length === 0 && groups.length === 0) {
      message.info('还没有可导出的收藏')
      return
    }
    const html = buildBookmarkFile(groups, allRows)
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }))
    const a = document.createElement('a')
    const d = new Date()
    const stamp = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
    a.href = url
    a.download = `devmind-bookmarks-${stamp}.html`
    a.click()
    URL.revokeObjectURL(url)
    message.success(`已导出 ${allRows.length} 条收藏，Chrome「书签管理器 → 导入」选这个文件即可`)
  }

  // ---------------- FR-02 分组 ----------------
  const submitGroup = async (name: string) => {
    const editingGroup = groupModal?.editing ?? null
    setSaving(true)
    try {
      if (editingGroup) await updateGroup(editingGroup.id, { name, parentId: editingGroup.parentId })
      else await createGroup({ name, parentId: groupModal?.parent?.id ?? null })
      message.success(editingGroup ? '已重命名' : `已新建分组「${name}」`)
      setGroupModal(null)
      await Promise.all([reloadGroups(), fetchMine(true)])
    } catch (e) {
      showError(e, '保存分组失败')
    } finally {
      setSaving(false)
    }
  }

  const confirmDeleteGroup = (g: BookmarkGroup) => {
    let cascade = false
    Modal.confirm({
      centered: true,
      title: `删除分组「${g.name}」？`,
      content: (
        <Space direction="vertical" size={4}>
          <span>组内收藏默认移到「默认分组」，子分组上提一级。</span>
          <label>
            <input type="checkbox" onChange={(e) => (cascade = e.target.checked)} /> 连组内收藏一起删（含子分组，不可恢复）
          </label>
        </Space>
      ),
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () =>
        deleteGroup(g.id, cascade)
          .then(() => {
            message.success(cascade ? '已级联删除' : '已删除，组内收藏移入默认分组')
            if (sel.kind === 'group' && sel.id === g.id) setSel({ kind: 'all' })
            return Promise.all([reloadGroups(), fetchMine(true)])
          })
          .catch((e) => showError(e, '删除分组失败')),
    })
  }

  const moveGroup = async (g: BookmarkGroup, parentId: number | null) => {
    try {
      // 改名与换父共用一个 PUT：带上原名，只改 parentId；后端有成环校验（拖入自己子树 400）
      await updateGroup(g.id, { name: g.name, parentId })
      const parentName = parentId == null ? '顶级' : findGroup(groups, parentId)?.name
      message.success(parentName ? `已移动到「${parentName}」下` : '已移动到顶级')
      await Promise.all([reloadGroups(), fetchMine(true)])
    } catch (e) {
      showError(e, '移动分组失败')
    }
  }

  const submitMove = async () => {
    try {
      const { moved } = await moveBookmarks(selectedKeys, moveTarget)
      message.success(`已转移 ${moved} 条收藏`)
      setMoveOpen(false)
      setSelectedKeys([])
      await Promise.all([reloadGroups(), fetchMine(true)])
    } catch (e) {
      showError(e, '转移失败')
    }
  }

  // ---------------- FR-03 标签 ----------------

  const submitTag = async () => {
    const name = tagName.trim()
    if (!name) return
    setSaving(true)
    try {
      if (tagModal?.editing) await renameTag(tagModal.editing.id, name)
      else await createTag(name)
      message.success(tagModal?.editing ? '已重命名' : `已新建标签「${name}」`)
      setTagModal(null)
      await Promise.all([reloadTags(), fetchMine(true)])
    } catch (e) {
      showError(e, '保存标签失败')
    } finally {
      setSaving(false)
    }
  }

  const confirmDeleteTag = (t: BookmarkTagSummary) =>
    Modal.confirm({
      centered: true,
      title: `删除标签「${t.name}」？`,
      content: `引用它的 ${t.bookmarkCount} 条收藏不会被删除，只是不再带这个标签。`,
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () =>
        deleteTag(t.id)
          .then(() => {
            message.success('已删除')
            setTagIds((prev) => prev.filter((x) => x !== t.id))
            return Promise.all([reloadTags(), fetchMine(true)])
          })
          .catch((e) => showError(e, '删除标签失败')),
    })

  // ---------------- FR-04 探测 ----------------

  const probeOne = async (b: Bookmark) => {
    try {
      const r = await probeBookmark(b.id)
      if (r.status === 'OK') message.success(`「${b.title}」可达（${r.statusCode} · ${r.latencyMs}ms）`)
      else message.warning(`「${b.title}」不可达（${r.statusCode ?? r.status}）`)
      await fetchMine(false)
    } catch (e) {
      showError(e, '探测失败')
    }
  }

  const probeSelected = async () => {
    const ids = selectedKeys
    try {
      const { accepted } = await probeBookmarks(ids)
      message.success(`已受理 ${accepted} 条探测，结果会自动刷新`)
      setPolling(true)
      await fetchMine(false)
    } catch (e) {
      showError(e, '批量探测失败')
    }
  }

  // ---------------- FR-05 账号明文 ----------------

  const reveal = async (accountId: string) => {
    if (!accountsOf) return
    try {
      const { password } = await getAccountSecret(accountsOf.id, accountId)
      setSecrets((prev) => ({ ...prev, [accountId]: password }))
    } catch (e) {
      showError(e, '取密码失败')
    }
  }

  // ---------------- FR-07 分享 ----------------

  const submitShare = async (targetUser: string) => {
    if (!shareTarget) return
    setShareSaving(true)
    try {
      await createShare({
        bookmarkId: shareTarget.kind === 'bookmark' ? shareTarget.id : null,
        groupId: shareTarget.kind === 'group' ? shareTarget.id : null,
        targetUser,
      })
      message.success(`已分享给 ${targetUser}`)
      setShareTarget(null)
      if (view === 'shares') loadShares()
    } catch (e) {
      showError(e, '分享失败')
    } finally {
      setShareSaving(false)
    }
  }

  const revokeShare = (s: BookmarkShare) =>
    Modal.confirm({
      centered: true,
      title: `撤销对 ${s.targetUser} 的分享？`,
      content: '撤销后对方立刻看不到这条内容（复制过去的副本不受影响）。',
      okText: '撤销',
      okButtonProps: { danger: true },
      onOk: () =>
        deleteShare(s.id)
          .then(() => {
            message.success('已撤销')
            return loadShares()
          })
          .catch((e) => showError(e, '撤销失败')),
    })

  const submitCopy = async () => {
    if (!copying) return
    try {
      const created = await copyShared(copying.bookmark.id, copyGroup)
      message.success(`已复制为「${created.title}」`)
      setCopying(null)
      setCopyGroup(null)
      await Promise.all([fetchMine(true), reloadTags()])
    } catch (e) {
      showError(e, '复制失败')
    }
  }

  const ungroupedCount = allRows.filter((b) => b.groupId == null).length
  const canBatch = view === 'mine' && viewMode === 'table' && selectedKeys.length > 0

  const groupNameOf = (b: Bookmark) => flatten(groups).find((g) => g.id === b.groupId)?.name ?? '默认分组'
  const startCreate = () => {
    setEditing(null)
    setDrawerOpen(true)
  }

  const extra = (
    <Space>
      <Button icon={<ReloadOutlined />} onClick={reloadCurrent}>
        刷新
      </Button>
      {view === 'mine' && (
        <>
          <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
            导入
          </Button>
          <Button icon={<ExportOutlined />} onClick={exportBookmarks}>
            导出
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={startCreate}>
            新建收藏
          </Button>
        </>
      )}
      {view === 'tags' && (
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setTagName('')
            setTagModal({ editing: null })
          }}
        >
          新建标签
        </Button>
      )}
    </Space>
  )

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>收藏夹</span>
          <Segmented value={view} onChange={(v) => setView(v as View)} options={VIEWS} />
        </Space>
      }
      extra={extra}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        团队内网入口一处收纳：分组归类、标签筛选、可用性探测、账号加密备忘；支持只读分享（密码不外传）。
      </Typography.Paragraph>

      {view === 'mine' && (
        <div style={{ display: 'flex', gap: 12, flex: 1, minHeight: 0 }}>
          <GroupTree
            groups={groups}
            total={allRows.length}
            ungroupedCount={ungroupedCount}
            selected={sel}
            onSelect={setSel}
            onCreate={(parent) => setGroupModal({ editing: null, parent })}
            onRename={(g) => setGroupModal({ editing: g, parent: null })}
            onDelete={confirmDeleteGroup}
            onShare={(g) => setShareTarget({ kind: 'group', id: g.id, name: g.name })}
            onMove={moveGroup}
          />
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0, flexWrap: 'wrap' }}>
              <Input.Search
                allowClear
                placeholder="搜名称 / 地址 / 备注"
                style={{ flex: 1, minWidth: 180, maxWidth: 320 }}
                value={keywordDraft}
                onChange={(e) => setKeywordDraft(e.target.value)}
                onSearch={(v) => setKeyword(v)}
              />
              <Select
                allowClear
                mode="multiple"
                placeholder="按标签筛"
                style={{ minWidth: 160 }}
                value={tagIds}
                onChange={setTagIds}
                options={tags.map((t) => ({ value: t.id, label: t.name }))}
              />
              <Select
                style={{ width: 120 }}
                value={status}
                onChange={(v) => setStatus(v as BookmarkStatus | 'ALL')}
                options={STATUS_OPTIONS}
              />
              {canBatch && (
                <>
                  <Button onClick={probeSelected}>批量探测（{selectedKeys.length}）</Button>
                  <Button
                    onClick={() => {
                      setMoveTarget(null)
                      setMoveOpen(true)
                    }}
                  >
                    转移分组（{selectedKeys.length}）
                  </Button>
                </>
              )}
              <Segmented
                style={{ marginLeft: 'auto' }}
                value={viewMode}
                onChange={(v) => {
                  const mode = v as ViewMode
                  setViewMode(mode)
                  localStorage.setItem(VIEW_MODE_KEY, mode)
                }}
                options={[
                  { value: 'table', icon: <BarsOutlined />, title: '表格视图' },
                  { value: 'card', icon: <AppstoreOutlined />, title: '卡片视图' },
                ]}
              />
            </div>
            {viewMode === 'card' ? (
              <BookmarkCardGrid
                rows={rows}
                loading={loading}
                groupName={groupNameOf}
                onOpen={openBookmark}
                onProbe={probeOne}
                onEdit={(b) => {
                  setEditing(b)
                  setDrawerOpen(true)
                }}
                onAccounts={(b) => {
                  setSecrets({})
                  setAccountsOf(b)
                }}
                onShare={(b) => setShareTarget({ kind: 'bookmark', id: Number(b.id), name: b.title })}
                onMove={(b) => {
                  setSelectedKeys([b.id])
                  setMoveTarget(null)
                  setMoveOpen(true)
                }}
                onDelete={confirmDeleteBookmark}
                onCreate={startCreate}
              />
            ) : (
              <FitTable<Bookmark>
                rowKey="id"
                loading={loading}
                dataSource={rows}
                pagination={LIST_PAGINATION}
                rowSelection={{ selectedRowKeys: selectedKeys, onChange: (keys) => setSelectedKeys(keys as string[]) }}
                locale={{
                  emptyText: (
                    <Space direction="vertical">
                      <span>这里还没有收藏</span>
                      <Button type="primary" onClick={startCreate}>
                        新建收藏
                      </Button>
                    </Space>
                  ),
                }}
                columns={[
                  {
                    title: '名称',
                    dataIndex: 'title',
                    ellipsis: true,
                    render: (t: string, b) => (
                      <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                        <Space size={6} style={{ minWidth: 0 }}>
                          <StatusDot bookmark={b} />
                          <Tooltip title={b.description || undefined} mouseEnterDelay={0.4}>
                            <a onClick={() => openBookmark(b)}>{t}</a>
                          </Tooltip>
                        </Space>
                        <Typography.Text
                          type="secondary"
                          style={{ fontSize: 12, paddingLeft: 14 }}
                          ellipsis
                        >
                          {b.url}
                        </Typography.Text>
                      </div>
                    ),
                  },
                  {
                    title: '标签',
                    width: 180,
                    render: (_, b) =>
                      b.tags.length === 0 ? (
                        <Typography.Text type="secondary">-</Typography.Text>
                      ) : (
                        <>
                          {b.tags.slice(0, 3).map((t) => (
                            <Tag key={t.id}>{t.name}</Tag>
                          ))}
                          {b.tags.length > 3 && (
                            <Tooltip title={b.tags.slice(3).map((t) => t.name).join('、')}>
                              <Tag>+{b.tags.length - 3}</Tag>
                            </Tooltip>
                          )}
                        </>
                      ),
                  },
                  { title: '归属分组', width: 120, render: (_, b) => groupNameOf(b) },
                  {
                    title: '最近访问',
                    dataIndex: 'lastVisitedAt',
                    width: 170,
                    render: (t: string) => fmtTime(t),
                  },
                  {
                    title: '操作',
                    width: 200,
                    render: (_, b) => (
                      <Space>
                        <Button onClick={() => openBookmark(b)}>打开</Button>
                        <Button
                          onClick={() => {
                            setEditing(b)
                            setDrawerOpen(true)
                          }}
                        >
                          编辑
                        </Button>
                        <Dropdown
                          trigger={['click']}
                          menu={{
                            items: [
                              { key: 'probe', label: '探测' },
                              { key: 'accounts', label: `账号（${b.accounts.length}）` },
                              { key: 'share', label: '分享给同事' },
                              { key: 'move', label: '转移分组' },
                              { type: 'divider' },
                              { key: 'delete', label: '删除', danger: true },
                            ],
                            onClick: ({ key }) => {
                              if (key === 'probe') {
                                void probeOne(b)
                              } else if (key === 'accounts') {
                                setSecrets({})
                                setAccountsOf(b)
                              } else if (key === 'share') {
                                setShareTarget({ kind: 'bookmark', id: Number(b.id), name: b.title })
                              } else if (key === 'move') {
                                setSelectedKeys([b.id])
                                setMoveTarget(null)
                                setMoveOpen(true)
                              } else if (key === 'delete') {
                                confirmDeleteBookmark(b)
                              }
                            },
                          }}
                        >
                          <Button>管理</Button>
                        </Dropdown>
                      </Space>
                    ),
                  },
                ]}
              />
            )}
          </div>
        </div>
      )}

      {view === 'shared' && <SharedWithMePane rows={shared} onOpen={openBookmark} onCopy={(b, owner) => setCopying({ bookmark: b, owner })} />}

      {view === 'shares' && (
        <FitTable<BookmarkShare>
          rowKey="id"
          dataSource={shares}
          pagination={LIST_PAGINATION}
          locale={{ emptyText: '还没有对外分享过；在收藏行「管理 → 分享给同事」或分组树上分享整个分组' }}
          columns={[
            {
              title: '分享内容',
              render: (_, s) =>
                s.bookmarkId != null ? (
                  <Space>
                    <Tag color="blue">收藏</Tag>
                    {s.bookmarkTitle ?? `#${s.bookmarkId}`}
                  </Space>
                ) : (
                  <Space>
                    <Tag color="purple">分组</Tag>
                    {s.groupName ?? `#${s.groupId}`}
                  </Space>
                ),
            },
            { title: '接收人', dataIndex: 'targetUser', width: 160 },
            {
              title: '分享时间',
              dataIndex: 'createdAt',
              width: 180,
              render: (t: string) => fmtTime(t),
            },
            {
              title: '操作',
              width: 100,
              render: (_, s) => (
                <Button danger onClick={() => revokeShare(s)}>
                  撤销
                </Button>
              ),
            },
          ]}
        />
      )}

      {view === 'tags' && (
        <TagsPane
          rows={tags}
          loading={loading}
          onRename={(t) => {
            setTagName(t.name)
            setTagModal({ editing: t })
          }}
          onDelete={confirmDeleteTag}
        />
      )}

      <BookmarkDrawer
        open={drawerOpen}
        bookmark={editing}
        defaultGroupId={sel.kind === 'group' ? sel.id : null}
        groups={groups}
        tagNames={tags.map((t) => t.name)}
        saving={saving}
        onClose={() => {
          setDrawerOpen(false)
          setEditing(null)
        }}
        onSubmit={submitBookmark}
      />

      <GroupModal
        open={!!groupModal}
        editing={groupModal?.editing ?? null}
        parent={groupModal?.parent ?? null}
        saving={saving}
        onClose={() => setGroupModal(null)}
        onOk={submitGroup}
      />

      <ShareModal
        open={!!shareTarget}
        target={shareTarget}
        saving={shareSaving}
        onClose={() => setShareTarget(null)}
        onOk={submitShare}
      />

      <ImportModal open={importOpen} saving={importSaving} onClose={() => setImportOpen(false)} onOk={submitImport} />

      <Modal
        title={tagModal?.editing ? `重命名标签：${tagModal.editing.name}` : '新建标签'}
        open={!!tagModal}
        confirmLoading={saving}
        onCancel={() => setTagModal(null)}
        destroyOnHidden
        onOk={submitTag}
      >
        <Input
          placeholder="如：内网、运维、监控"
          value={tagName}
          maxLength={64}
          onChange={(e) => setTagName(e.target.value)}
          onPressEnter={submitTag}
        />
      </Modal>

      <Modal
        title={`转移 ${selectedKeys.length} 条收藏到`}
        open={moveOpen}
        onCancel={() => setMoveOpen(false)}
        onOk={submitMove}
      >
        <Select
          allowClear
          style={{ width: '100%' }}
          placeholder="默认分组"
          value={moveTarget}
          onChange={(v) => setMoveTarget(v ?? null)}
          options={groupOptions(groups)}
        />
      </Modal>

      <Modal
        title={copying ? `复制「${copying.bookmark.title}」为自己的` : '复制为我的'}
        open={!!copying}
        onCancel={() => {
          setCopying(null)
          setCopyGroup(null)
        }}
        onOk={submitCopy}
        okText="复制"
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            复制的是条目与标签名（我的空间没有同名标签会自动建），不含账号与密码。
          </Typography.Text>
          <Select
            allowClear
            style={{ width: '100%' }}
            placeholder="落到默认分组"
            value={copyGroup}
            onChange={(v) => setCopyGroup(v ?? null)}
            options={groupOptions(groups)}
          />
        </Space>
      </Modal>

      <Modal
        title={accountsOf ? `账号：${accountsOf.title}` : '账号'}
        open={!!accountsOf}
        onCancel={() => setAccountsOf(null)}
        footer={<Button onClick={() => setAccountsOf(null)}>关闭</Button>}
        destroyOnHidden
      >
        {accountsOf && accountsOf.accounts.length > 0 ? (
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={accountsOf.accounts}
            columns={[
              { title: '用途', dataIndex: 'label', width: 120 },
              { title: '用户名', dataIndex: 'username', render: (u: string) => u || '-' },
              {
                title: '密码',
                width: 200,
                render: (_, a) => {
                  if (!a.hasPassword) return '-'
                  const shown = secrets[a.id]
                  if (shown === undefined) return <Button onClick={() => reveal(a.id)}>查看</Button>
                  return <Typography.Text copyable>{shown ?? '（未设置）'}</Typography.Text>
                },
              },
              { title: '备注', dataIndex: 'note', render: (n: string) => n || '-' },
            ]}
          />
        ) : (
          <Typography.Text type="secondary">这条收藏没有登记账号。</Typography.Text>
        )}
      </Modal>
    </Card>
  )
}
