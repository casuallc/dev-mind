// 需求描述中的附件引用解析/改写，与 CAP-40 RequirementAttachmentProvider 的 ANY_REF 同一口径：
// 本地 Markdown 引用 ![alt](/api/attachments/{id}/raw) / [名](/api/attachments/{id}/raw)
// + Jira wiki 图 !name.png|attrs!。描述文本是附件绑定的唯一事实源——AI 会话投送（CAP-40）、
// 详情页渲染、附件 Tab 列表都按此扫描，不再走 relations 边。

/**
 * 本地附件引用：group1='!' 为图片（Markdown 图语法），group2=显示名，group3=附件 id。
 * `[^)\n]*` 容忍存量烤 token 的 query（早期写入姿势，渲染期会剥掉重拼）。
 */
const LOCAL_REF_RE = /(!?)\[([^\]\n]*)\]\(\/api\/attachments\/([0-9a-f]{32})\/raw[^)\n]*\)/gi

/** Jira wiki 图标记（与 JiraDescription 同款：只认图片扩展名，防 !重要! 这类纯文本被当图） */
const WIKI_IMAGE_RE = /!([^!\n|]+?\.(?:png|jpe?g|gif|bmp|webp))(\|[^!\n]*)?!/gi

export interface AttachmentRef {
  id: string
  name: string
  image: boolean
}

/** 描述中的本地附件引用（按出现顺序，同 id 去重） */
export function extractAttachmentRefs(description: string): AttachmentRef[] {
  const seen = new Set<string>()
  const refs: AttachmentRef[] = []
  for (const m of description.matchAll(LOCAL_REF_RE)) {
    if (seen.has(m[3])) continue
    seen.add(m[3])
    refs.push({ id: m[3], name: m[2] || m[3], image: m[1] === '!' })
  }
  return refs
}

/** 描述中的 Jira wiki 图文件名（按出现顺序去重） */
export function extractWikiImageNames(description: string): string[] {
  const seen = new Set<string>()
  const names: string[] = []
  for (const m of description.matchAll(WIKI_IMAGE_RE)) {
    const name = m[1].trim()
    if (!seen.has(name)) {
      seen.add(name)
      names.push(name)
    }
  }
  return names
}

export function buildRefLine(name: string, attachmentId: string, image: boolean): string {
  return `${image ? '!' : ''}[${name}](/api/attachments/${attachmentId}/raw)`
}

/** 文末追加引用行（与正文空行分隔；描述为空直接起步） */
export function appendRefLines(description: string | undefined, lines: string[]): string {
  const base = (description ?? '').trimEnd()
  return (base ? base + '\n\n' : '') + lines.join('\n')
}

/** 从描述中剔除指定附件的全部引用：整行只剩该引用时连行删，否则就地删片段（末尾空白一并收） */
export function stripAttachmentRef(description: string, attachmentId: string): string {
  const re = new RegExp(
    `!?\\[[^\\]\\n]*\\]\\(\\/api\\/attachments\\/${attachmentId}\\/raw[^)\\n]*\\)`,
    'gi',
  )
  return description
    .split('\n')
    .filter((line) => {
      const without = line.replace(re, '')
      return without === line || without.trim() !== ''
    })
    .map((line) => line.replace(re, '').replace(/[ \t]+$/, ''))
    .join('\n')
}
