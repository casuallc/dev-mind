import { Button, Space } from 'antd'
import FitTable from '../../../shared/components/FitTable'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import type { BookmarkTagSummary } from '../types'

interface Props {
  rows: BookmarkTagSummary[]
  loading: boolean
  onRename: (t: BookmarkTagSummary) => void
  onDelete: (t: BookmarkTagSummary) => void
}

/** FR-03 标签管理：删除只解除关联（不动收藏），改名受 (owner, name) 唯一约束。 */
export default function TagsPane({ rows, loading, onRename, onDelete }: Props) {
  return (
    <FitTable<BookmarkTagSummary>
      rowKey="id"
      loading={loading}
      dataSource={rows}
      pagination={LIST_PAGINATION}
      locale={{ emptyText: '还没有标签，编辑收藏时在「标签」里输入回车即可新建' }}
      columns={[
        { title: '标签', dataIndex: 'name', ellipsis: true },
        {
          title: '引用收藏数',
          dataIndex: 'bookmarkCount',
          width: 120,
        },
        {
          title: '操作',
          width: 160,
          render: (_, t) => (
            <Space>
              <Button onClick={() => onRename(t)}>重命名</Button>
              <Button danger onClick={() => onDelete(t)}>
                删除
              </Button>
            </Space>
          ),
        },
      ]}
    />
  )
}
