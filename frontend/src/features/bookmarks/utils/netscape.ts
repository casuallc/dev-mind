// FR-09 浏览器书签文件解析：Netscape Bookmark 格式（Chrome / Edge / Firefox 导出通用）。
// 浏览器自己的导出格式交给浏览器引擎（DOMParser）解析最稳，服务端只收结构化 JSON 树。
import type { Bookmark, BookmarkGroup, ImportNode } from '../types'

/**
 * 解析书签 HTML 为导入树。不是浏览器导出格式（找不到 DL）时抛错，由调用方提示。
 * 结构约定：<DL> 的子节点是一串 <DT>/<DD>——<DT><H3> 是文件夹（其内部紧跟一个 <DL> 装子节点），
 * <DT><A> 是书签，紧随其后的兄弟 <DD> 是这条书签的备注；Firefox 的 TAGS 属性映射为标签。
 * 内嵌图标（ICON data URI）体量巨大且不导入（favicon 是 CAP-64 留后续项）。
 */
export function parseBookmarkFile(html: string): ImportNode[] {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const root = doc.querySelector('dl')
  if (!root) {
    throw new Error('未识别到书签内容——请选择浏览器书签管理器导出的 HTML 文件')
  }
  return parseList(root)
}

function parseList(dl: Element): ImportNode[] {
  const nodes: ImportNode[] = []
  let lastBookmark: ImportNode | null = null
  for (const el of Array.from(dl.children)) {
    if (el.tagName === 'DT') {
      const h3 = el.querySelector(':scope > h3')
      const a = el.querySelector(':scope > a')
      if (h3) {
        const sub = el.querySelector(':scope > dl')
        nodes.push({ type: 'folder', name: h3.textContent?.trim() ?? '', children: sub ? parseList(sub) : [] })
        lastBookmark = null
      } else if (a) {
        const tags = (a.getAttribute('tags') ?? '')
          .split(',')
          .map((t) => t.trim())
          .filter(Boolean)
        const node: ImportNode = {
          type: 'bookmark',
          title: a.textContent?.trim() ?? '',
          url: a.getAttribute('href') ?? '',
        }
        if (tags.length > 0) node.tags = tags
        nodes.push(node)
        lastBookmark = node
      }
    } else if (el.tagName === 'DD' && lastBookmark) {
      const note = el.textContent?.trim()
      if (note) lastBookmark.description = note
      lastBookmark = null
    }
  }
  return nodes
}

/** 导入预览统计（文件夹/书签条数），导入前给用户确认体量 */
export function importStats(nodes: ImportNode[]): { folders: number; bookmarks: number } {
  let folders = 0
  let bookmarks = 0
  const walk = (ns: ImportNode[]) => {
    for (const n of ns) {
      if (n.type === 'folder') {
        folders++
        walk(n.children ?? [])
      } else {
        bookmarks++
      }
    }
  }
  walk(nodes)
  return { folders, bookmarks }
}

// ---- 导出（FR-09 的逆操作）----

/** HTML 转义：文本与属性都走它（属性值统一双引号包裹） */
function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** ADD_DATE 是 Unix 秒；createdAt 缺失/解析失败时用当前时间 */
function addDate(iso?: string): number {
  const t = iso ? Date.parse(iso.replace(' ', 'T')) : NaN
  return Math.floor((Number.isNaN(t) ? Date.now() : t) / 1000)
}

/**
 * 生成 Netscape Bookmark 文件（Chrome「导入书签」直接认）。
 * 结构与 parseBookmarkFile 对称：分组→<DT><H3>+<DL>，收藏→<DT><A>+可选 <DD> 备注，
 * 标签写 TAGS 属性——Chrome 忽略它，但导回本平台时标签不丢（往返无损）。
 * 账号/密码属平台私有数据，不进导出文件。
 */
export function buildBookmarkFile(groups: BookmarkGroup[], bookmarks: Bookmark[]): string {
  const byGroup = new Map<number | null, Bookmark[]>()
  for (const b of [...bookmarks].sort((a, b2) => a.sortOrder - b2.sortOrder)) {
    const list = byGroup.get(b.groupId) ?? []
    list.push(b)
    byGroup.set(b.groupId, list)
  }

  const lines: string[] = []
  const emitBookmark = (b: Bookmark, indent: string) => {
    const tags = b.tags.length > 0 ? ` TAGS="${esc(b.tags.map((t) => t.name).join(','))}"` : ''
    lines.push(`${indent}<DT><A HREF="${esc(b.url)}" ADD_DATE="${addDate(b.createdAt)}"${tags}>${esc(b.title)}</A>`)
    if (b.description?.trim()) lines.push(`${indent}<DD>${esc(b.description.trim())}`)
  }
  const emitGroup = (g: BookmarkGroup, indent: string) => {
    lines.push(`${indent}<DT><H3 ADD_DATE="${addDate()}">${esc(g.name)}</H3>`)
    lines.push(`${indent}<DL><p>`)
    emitChildren(g, `${indent}    `)
    lines.push(`${indent}</DL><p>`)
  }
  const emitChildren = (g: BookmarkGroup | null, indent: string) => {
    for (const b of byGroup.get(g?.id ?? null) ?? []) emitBookmark(b, indent)
    const subs = (g ? g.children : groups).slice().sort((a, b) => a.sortOrder - b.sortOrder)
    for (const sub of subs) emitGroup(sub, indent)
  }

  emitChildren(null, '    ')
  return [
    '<!DOCTYPE NETSCAPE-Bookmark-file-1>',
    '<!-- This is an automatically generated file.',
    '     It will be read and overwritten.',
    '     DO NOT EDIT! -->',
    '<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">',
    '<TITLE>Bookmarks</TITLE>',
    '<H1>Bookmarks</H1>',
    '<DL><p>',
    ...lines,
    '</DL><p>',
    '',
  ].join('\n')
}
