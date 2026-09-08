// 页面内容区高度约定（参考实现 = chat 内容区 ChatsBoard）：
// AppLayout/AdminLayout 的 Content 是 100vh 内的 flex 列容器，页面根节点必须撑满该高度（flex:1 + minHeight:0），
// 滚动一律发生在页面内部，不让整页（Content）出现纵向滚动条。
import type { CSSProperties } from 'react'

/** 页面根 Card：撑满内容区高度，自身成 flex 列（头部固定、body 弹性伸缩） */
export const pageCardStyle: CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
}

/** 页面根 Card 的 body（表格/长表单类页面）：内容在 body 内部滚动，Card 头部固定不动 */
export const pageCardBodyScrollStyle: CSSProperties = { flex: 1, minHeight: 0, overflow: 'auto' }

/** 页面根 Card 的 body（左右分栏工作台类页面）：body 为 flex 列容器，滚动由内部子面板各自负责（如 AI 问答） */
export const pageCardBodyFlexStyle: CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
}

/** 多区块页面（概览/详情等根节点为 Space/div 叠多张 Card）的根容器：撑满高度并内部滚动 */
export const pageRootScrollStyle: CSSProperties = { flex: 1, minHeight: 0, overflow: 'auto' }
