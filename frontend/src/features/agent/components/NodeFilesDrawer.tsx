// CAP-65 节点文件浏览抽屉：白名单根目录内的一层列表（目录优先排序、面包屑下钻），
// 文本文件预览/编辑一体弹窗（等宽字体，CAP-60 字体栈），改名/删除/上传/下载。
// 安全边界在服务端+runner 双重校验（白名单外/逃逸/超限一律 409/400），此处只做体验层预检。
import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Breadcrumb,
  Button,
  Drawer,
  Input,
  Modal,
  Select,
  Space,
  Tooltip,
  Typography,
  Upload,
  message,
} from 'antd'
import {
  DownloadOutlined,
  FileAddOutlined,
  FolderOutlined,
  ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import type { AgentNode, NodeFileEntry } from '../types'
import {
  deleteNodeFile,
  downloadNodeFile,
  listNodeFiles,
  readNodeFile,
  renameNodeFile,
  uploadNodeFile,
  writeNodeFile,
} from '../api'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

/** 预览/编辑等宽字体栈（CAP-60 终端同款） */
const MONO_FONT = "'JetBrains Mono', 'Cascadia Code', 'Cascadia Mono', Consolas, Menlo, 'Courier New', monospace"
const TRANSFER_CAP = 100 * 1024 * 1024

const joinRel = (dir: string, name: string) => (dir ? `${dir}/${name}` : name)

/** newName 前端校验（服务端/runner 仍终判）：非空、不含 / \ :、非点名 */
const badName = (n: string) => !n.trim() || /[/\\:]/.test(n) || n === '.' || n === '..'

export default function NodeFilesDrawer({ node, onClose }: { node: AgentNode; onClose: () => void }) {
  const roots = node.fileRoots ?? []
  const [root, setRoot] = useState(roots[0] ?? '')
  const [dir, setDir] = useState('')
  const [entries, setEntries] = useState<NodeFileEntry[] | null>(null) // null=加载中
  const [truncated, setTruncated] = useState(false)
  const [busy, setBusy] = useState(false)

  // 预览/编辑一体弹窗：dirty 才放行保存
  const [preview, setPreview] = useState<{ entry: NodeFileEntry; orig: string; draft: string } | null>(null)
  const [renaming, setRenaming] = useState<NodeFileEntry | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [newFileOpen, setNewFileOpen] = useState(false)
  const [newFileName, setNewFileName] = useState('')

  const reload = useCallback(async () => {
    setEntries(null)
    try {
      const res = await listNodeFiles(node.id, root, dir)
      setEntries(res.entries)
      setTruncated(res.truncated)
    } catch (e) {
      setEntries([])
      showError(e, '目录加载失败')
    }
  }, [node.id, root, dir])

  useEffect(() => {
    void reload()
  }, [reload])

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      showError(e, '操作失败')
    } finally {
      setBusy(false)
    }
  }

  /** 文件点击：读成功开编辑弹窗；二进制/超限（409）降级为「下载查看」引导 */
  const openFile = (entry: NodeFileEntry) =>
    run(async () => {
      const rel = joinRel(dir, entry.name)
      try {
        const res = await readNodeFile(node.id, root, rel)
        setPreview({ entry, orig: res.content, draft: res.content })
      } catch (e) {
        Modal.confirm({
          centered: true,
          title: entry.name,
          content: `${(e as Error).message}——可下载到本地查看。`,
          okText: '下载查看',
          cancelText: '关闭',
          onOk: () => downloadNodeFile(node.id, root, rel, entry.name),
        })
      }
    })

  const savePreview = () =>
    run(async () => {
      if (!preview) return
      await writeNodeFile(node.id, root, joinRel(dir, preview.entry.name), preview.draft)
      message.success(`已保存 ${preview.entry.name}`)
      setPreview(null)
      await reload()
    })

  const doRename = () =>
    run(async () => {
      if (!renaming) return
      const name = renameDraft.trim()
      if (badName(name)) {
        message.warning('文件名不能为空，且不得含 / \\ :')
        return
      }
      await renameNodeFile(node.id, root, joinRel(dir, renaming.name), name)
      message.success('已改名')
      setRenaming(null)
      await reload()
    })

  const onDelete = (entry: NodeFileEntry) =>
    Modal.confirm({
      centered: true,
      title: entry.dir ? `删除目录「${entry.name}」？` : `删除文件「${entry.name}」？`,
      content: entry.dir ? '将递归删除其下全部内容，不可恢复。' : '删除后不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () =>
        run(async () => {
          await deleteNodeFile(node.id, root, joinRel(dir, entry.name), entry.dir)
          message.success('已删除')
          await reload()
        }),
    })

  const doCreateFile = () =>
    run(async () => {
      const name = newFileName.trim()
      if (badName(name)) {
        message.warning('文件名不能为空，且不得含 / \\ :')
        return
      }
      await writeNodeFile(node.id, root, joinRel(dir, name), '')
      message.success(`已创建 ${name}`)
      setNewFileOpen(false)
      setNewFileName('')
      await reload()
    })

  const doUpload = (file: File) => {
    if (file.size > TRANSFER_CAP) {
      message.error(`文件超过 100MB 上限（${fmtBytes(file.size)}），不支持上传`)
      return
    }
    void run(async () => {
      const key = `upload-${file.name}`
      message.loading({ key, content: `上传 ${file.name}… 0%`, duration: 0 })
      try {
        await uploadNodeFile(node.id, root, dir, file, (p) =>
          message.loading({ key, content: `上传 ${file.name}… ${p}%`, duration: 0 }),
        )
        message.success({ key, content: `已上传 ${file.name}` })
        await reload()
      } catch (e) {
        message.destroy(key)
        throw e
      }
    })
  }

  const segments = dir ? dir.split('/') : []
  const crumbItems = [
    {
      title: (
        <a onClick={() => setDir('')}>
          <FolderOutlined /> 根目录
        </a>
      ),
    },
    ...segments.map((seg, i) => ({
      title:
        i === segments.length - 1 ? (
          <span>{seg}</span>
        ) : (
          <a onClick={() => setDir(segments.slice(0, i + 1).join('/'))}>{seg}</a>
        ),
    })),
  ]

  return (
    <Drawer
      title={`文件浏览 · ${node.name}`}
      open
      onClose={onClose}
      width={960}
      styles={{ body: { display: 'flex', flexDirection: 'column', gap: 12 } }}
    >
      <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
        <Space wrap>
          {roots.length > 1 ? (
            <Select
              style={{ minWidth: 280 }}
              value={root}
              options={roots.map((r) => ({ value: r, label: r }))}
              onChange={(r) => {
                setRoot(r)
                setDir('')
              }}
            />
          ) : (
            <Tooltip title="文件访问根目录（白名单）">
              <Typography.Text code>{root}</Typography.Text>
            </Tooltip>
          )}
          <Breadcrumb items={crumbItems} />
        </Space>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void reload()}>
            刷新
          </Button>
          <Button icon={<FileAddOutlined />} onClick={() => setNewFileOpen(true)}>
            新建文本文件
          </Button>
          <Upload showUploadList={false} beforeUpload={(f) => (doUpload(f), false)}>
            <Button icon={<UploadOutlined />}>上传</Button>
          </Upload>
        </Space>
      </Space>

      {truncated && (
        <Alert type="info" showIcon message="目录条目超过 1000，仅展示前 1000 条（按名称序）。" />
      )}

      <FitTable<NodeFileEntry>
        rowKey="name"
        size="small"
        loading={entries === null}
        dataSource={entries ?? []}
        pagination={LIST_PAGINATION}
        columns={[
          {
            title: '名称',
            dataIndex: 'name',
            render: (name: string, e) =>
              e.dir ? (
                <a onClick={() => setDir(joinRel(dir, name))}>
                  <FolderOutlined /> {name}
                </a>
              ) : (
                <a onClick={() => openFile(e)}>{name}</a>
              ),
          },
          {
            title: '大小',
            dataIndex: 'size',
            width: 100,
            render: (size: number | undefined, e) => (e.dir ? '-' : fmtBytes(size)),
          },
          {
            title: '修改时间',
            dataIndex: 'mtime',
            width: 170,
            render: (mtime: string | undefined) => fmtTime(mtime),
          },
          {
            title: '操作',
            width: 210,
            render: (_, e) => (
              <Space size={4}>
                {!e.dir && (
                  <>
                    <Button size="small" type="link" onClick={() => openFile(e)}>
                      预览/编辑
                    </Button>
                    <Button
                      size="small"
                      type="link"
                      icon={<DownloadOutlined />}
                      onClick={() =>
                        run(() => downloadNodeFile(node.id, root, joinRel(dir, e.name), e.name))
                      }
                    >
                      下载
                    </Button>
                  </>
                )}
                <Button
                  size="small"
                  type="link"
                  onClick={() => {
                    setRenaming(e)
                    setRenameDraft(e.name)
                  }}
                >
                  改名
                </Button>
                <Button size="small" type="link" danger onClick={() => onDelete(e)}>
                  删除
                </Button>
              </Space>
            ),
          },
        ]}
      />

      {/* 预览/编辑一体弹窗：等宽 TextArea，内容未改时保存按钮置灰 */}
      <Modal
        centered
        width={820}
        title={preview ? `预览/编辑 · ${preview.entry.name}` : ''}
        open={!!preview}
        onCancel={() => setPreview(null)}
        okText="保存"
        cancelText="关闭"
        confirmLoading={busy}
        okButtonProps={{ disabled: !preview || preview.draft === preview.orig }}
        onOk={savePreview}
      >
        {preview && (
          <Input.TextArea
            style={{ fontFamily: MONO_FONT, fontSize: 12 }}
            autoSize={{ minRows: 12, maxRows: 24 }}
            value={preview.draft}
            onChange={(e) => setPreview({ ...preview, draft: e.target.value })}
          />
        )}
      </Modal>

      {/* 改名弹窗 */}
      <Modal
        centered
        title={`改名 · ${renaming?.name ?? ''}`}
        open={!!renaming}
        onCancel={() => setRenaming(null)}
        okText="改名"
        cancelText="取消"
        confirmLoading={busy}
        onOk={doRename}
      >
        <Input
          value={renameDraft}
          onChange={(e) => setRenameDraft(e.target.value)}
          placeholder="新名称（同目录，不得含 / \ :）"
          onPressEnter={doRename}
        />
      </Modal>

      {/* 新建文本文件弹窗 */}
      <Modal
        centered
        title="新建文本文件"
        open={newFileOpen}
        onCancel={() => setNewFileOpen(false)}
        okText="创建"
        cancelText="取消"
        confirmLoading={busy}
        onOk={doCreateFile}
      >
        <Input
          value={newFileName}
          onChange={(e) => setNewFileName(e.target.value)}
          placeholder={`在 ${dir || '根目录'} 下创建（UTF-8 空文件，如 notes.md）`}
          onPressEnter={doCreateFile}
        />
      </Modal>
    </Drawer>
  )
}
