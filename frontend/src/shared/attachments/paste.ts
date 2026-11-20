// CAP-32 FR-10：剪贴板取文件公共助手（ChatPanel / NewSessionDraft / NewChatDraft 共用）。
// 截图粘贴出来的文件名是通用占位（Windows 截图 = image.png，部分来源干脆空名），直接上传
// 会在附件库里堆一堆同名 image.png——统一改名 pasted-yyyyMMdd-HHmmss-<seq>.<ext>，
// 一眼认出来源、同秒多张也不互相顶掉；用户自己复制的有名字的文件保持原名。

let pasteSeq = 0

/** 通用占位名判定：空名 / image / image.png / image.jpeg 这类浏览器默认名 */
function isGenericName(name: string): boolean {
  return !name || /^image(\.[a-z0-9]+)?$/i.test(name)
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function stampedName(f: File): string {
  if (!isGenericName(f.name)) return f.name
  const ext = f.name.includes('.')
    ? f.name.split('.').pop()!
    : (f.type.split('/')[1] || 'png')
  const d = new Date()
  pasteSeq = (pasteSeq + 1) % 1000
  return `pasted-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}-${pasteSeq}.${ext}`
}

/**
 * 从粘贴事件提取文件列表（无文件返回空数组，调用方据是否为空决定 preventDefault）。
 * 入参结构兼容 React.ClipboardEvent 与原生 ClipboardEvent。
 */
export function clipboardFiles(e: { clipboardData?: DataTransfer | null }): File[] {
  return Array.from(e.clipboardData?.files ?? []).map((f) => {
    const name = stampedName(f)
    return name === f.name ? f : new File([f], name, { type: f.type })
  })
}
