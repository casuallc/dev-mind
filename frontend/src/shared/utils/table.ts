// 主列表统一客户端分页配置：默认每页 10 条，可换页大小、显示总数。
// 仅用于全量加载的主列表/管理页表格；服务端真分页（审计/部署历史/需求列表等）与抽屉/弹窗/表单内嵌小表不用它。
import type { TablePaginationConfig } from 'antd/es/table'

export const LIST_PAGINATION: TablePaginationConfig = {
  defaultPageSize: 10,
  showSizeChanger: true,
  pageSizeOptions: [10, 20, 50, 100],
  showTotal: (t) => `共 ${t} 条`,
}
