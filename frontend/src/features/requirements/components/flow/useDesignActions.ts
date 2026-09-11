// CAP-37 FR-04：方案确认/废弃/删除/预览的共享逻辑（DesignsTab 与 FlowTab 两处复用，
// 避免状态操作与文档预览代码两份维护）。
import { useState } from 'react'
import { Modal, message } from 'antd'
import { deleteDesign, updateDesignStatus } from '../../api'
import { getDoc } from '../../../docs/api'
import type { Design, DesignStatus } from '../../types'
import { showError } from '../../../../shared/utils/showError'

export interface DocPreview {
  title: string
  content: string
}

export function designStatusColor(s: DesignStatus): string {
  switch (s) {
    case 'DRAFT': return 'gold'
    case 'CONFIRMED': return 'green'
    case 'DISCARDED': return 'default'
    default: return 'default'
  }
}

export function useDesignActions(projectId: string, requirementId: string, onChanged: () => void) {
  const [preview, setPreview] = useState<DocPreview | null>(null)

  const setStatus = async (d: Design, status: DesignStatus) => {
    try {
      await updateDesignStatus(projectId, requirementId, d.id, status)
      message.success(`方案 v${d.version} → ${status}`)
      onChanged()
    } catch (e) {
      showError(e)
    }
  }

  const remove = (d: Design) => {
    Modal.confirm({
      centered: true,
      title: `删除方案 v${d.version}？`,
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        await deleteDesign(projectId, requirementId, d.id)
        message.success('已删除')
        onChanged()
      },
    })
  }

  /** 预览方案文档内容；docId 为空时提示 */
  const previewDesign = async (d: Design) => {
    if (!d.docId) {
      message.info('该方案未关联文档')
      return
    }
    try {
      const doc = await getDoc(d.docId)
      setPreview({ title: `方案 v${d.version} · ${doc.title}`, content: doc.contentMd || '（空）' })
    } catch (e) {
      showError(e, '读取方案文档失败')
    }
  }

  /** 预览任意文档（流程 Tab 的「查看分析」用） */
  const previewDoc = async (docId: number, title: string) => {
    try {
      const doc = await getDoc(docId)
      setPreview({ title, content: doc.contentMd || '（空）' })
    } catch (e) {
      showError(e, '读取文档失败')
    }
  }

  return { preview, closePreview: () => setPreview(null), setStatus, remove, previewDesign, previewDoc }
}
