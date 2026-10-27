// 自适应高度表格：表头吸顶 + 分页条常驻，只有表体滚动（整页不出现纵向滚动条）。
// 做法：外层 flex 容器测高 → 交给 antd 的 scroll.y（antd 原生固定表头/固定分页），
// 避免 calc(100vh - Xpx) 这类魔法数——容器链已 flex 化，高度由 flex 决定（见 docs/core/前端内容区布局约定.md）。
// 用法：父容器须为 flex 列（如 Card body 用 pageCardBodyFlexStyle），与本组件合起来即为「表格区域内部滚动」。
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Table } from 'antd'
import type { TableProps } from 'antd'
import type { ColumnsType } from 'antd/es/table'

type AnyColumns = ColumnsType<never>

/**
 * 全站默认：单元格不换行、超长省略（ellipsis）。
 * 某列确需多行展示时显式 `ellipsis: false` 关闭；列分组递归处理。
 * 配合 FitTable 恒有的 scroll.y，antd 走 tableLayout: fixed，省略截断才真正生效。
 */
export function noWrapColumns<T extends object>(columns: TableProps<T>['columns']): TableProps<T>['columns'] {
  return columns?.map((c) => ({
    ...c,
    ellipsis: c.ellipsis ?? true,
    ...('children' in c && c.children
      ? { children: noWrapColumns(c.children as AnyColumns) }
      : {}),
  }))
}

interface FitTableProps<T> extends TableProps<T> {
  /** 表体最小高度（px）：容器被压得很矮时兜底，避免表头/分页重叠，默认 120 */
  minBodyHeight?: number
}

export default function FitTable<T extends object>({
  minBodyHeight = 120,
  scroll,
  columns,
  ...tableProps
}: FitTableProps<T>) {
  const boxRef = useRef<HTMLDivElement>(null)
  const [bodyHeight, setBodyHeight] = useState<number>()

  // 表体可用高度 = 容器高度 − 表头 − 分页条（含其 margin）。两者都在容器内实测，不猜常量。
  const measure = useCallback(() => {
    const box = boxRef.current
    if (!box) return
    const head = box.querySelector<HTMLElement>('.ant-table-thead')
    const pager = box.querySelector<HTMLElement>('.ant-table-pagination')
    let chrome = head?.offsetHeight ?? 0
    if (pager) {
      const cs = getComputedStyle(pager)
      chrome += pager.offsetHeight + parseFloat(cs.marginTop) + parseFloat(cs.marginBottom)
    }
    const next = Math.max(minBodyHeight, Math.floor(box.clientHeight - chrome))
    setBodyHeight((prev) => (prev === next ? prev : next))
  }, [minBodyHeight])

  // 首帧先量一次，避免固定表头晚一帧才生效造成的跳动
  useLayoutEffect(measure)
  // 容器与内容尺寸变化都重估（窗口缩放、页头/工具栏换行、列数随视图切换、分页条显隐）
  useEffect(() => {
    const box = boxRef.current
    if (!box) return
    const ro = new ResizeObserver(measure)
    ro.observe(box)
    if (box.firstElementChild) ro.observe(box.firstElementChild)
    return () => ro.disconnect()
  }, [measure])

  return (
    // overflow:hidden 保证表格撑不破容器（容器高度由 flex 决定，不受内容影响，测高不会自激）
    <div ref={boxRef} style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
      <Table<T> {...tableProps} columns={noWrapColumns(columns)} scroll={{ ...scroll, y: bodyHeight }} />
    </div>
  )
}
