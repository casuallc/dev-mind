import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Space,
  Typography,
  Upload,
  message,
} from 'antd'
import { DownloadOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons'
import {
  downloadRunnerPackage,
  getRunnerPackage,
  uploadRunnerPackage,
} from '../api'
import type { RunnerPackage } from '../types'
import { fmtTime } from '../../../shared/utils/format'

/**
 * FR-09「Runner 包」页签：服务端托管的 devmind-agent-runner.jar（全局单份，上传即替换）。
 * 上传时后端强校验包内 runner-version.txt 与 SelfUpdater；节点升级走节点列表的「升级」按钮。
 */
export default function RunnerPackagePanel() {
  const [pkg, setPkg] = useState<RunnerPackage | null>(null)
  const [loading, setLoading] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)

  const reload = () => {
    setLoading(true)
    getRunnerPackage()
      .then(setPkg)
      .catch(() => setPkg(null)) // 404 = 尚未上传
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const onUpload = async () => {
    if (!file) return
    setUploading(true)
    try {
      const res = await uploadRunnerPackage(file)
      message.success(`已上传 runner 包 ${res.version}（旧包已替换）`)
      setFile(null)
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '上传失败')
    } finally {
      setUploading(false)
    }
  }

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="全局托管一份 devmind-agent-runner.jar，上传即替换。节点机上首次手工部署后，后续在「节点列表」点「升级」即可让节点自动下载、换包、重启。"
      />
      {pkg ? (
        <Descriptions
          bordered
          size="small"
          column={1}
          style={{ maxWidth: 720, marginBottom: 16 }}
          title="当前托管包"
        >
          <Descriptions.Item label="版本">{pkg.version}</Descriptions.Item>
          <Descriptions.Item label="SHA-256">
            <Typography.Text copyable={{ text: pkg.sha256 }} code>
              {pkg.sha256.slice(0, 16)}…
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="大小">
            {(pkg.sizeBytes / 1024 / 1024).toFixed(2)} MB
          </Descriptions.Item>
          <Descriptions.Item label="原文件名">{pkg.originalFilename || '-'}</Descriptions.Item>
          <Descriptions.Item label="上传时间">{fmtTime(pkg.uploadedAt)}</Descriptions.Item>
          <Descriptions.Item label="上传人">{pkg.uploadedBy || '-'}</Descriptions.Item>
        </Descriptions>
      ) : (
        <Empty
          style={{ margin: '24px 0' }}
          description={loading ? '加载中…' : '尚未上传 runner 包'}
        />
      )}
      <Space direction="vertical" style={{ width: 480 }}>
        <Upload.Dragger
          accept=".jar"
          maxCount={1}
          beforeUpload={(f) => {
            setFile(f)
            return false // 本地暂存，点「上传替换」才真正提交
          }}
          onRemove={() => setFile(null)}
          fileList={file ? [{ uid: '-1', name: file.name }] : []}
        >
          <p className="ant-upload-drag-icon"><InboxOutlined /></p>
          <p className="ant-upload-text">点击或拖拽 devmind-agent-runner.jar 到此</p>
        </Upload.Dragger>
        <Space>
          <Button
            type="primary"
            icon={<UploadOutlined />}
            disabled={!file}
            loading={uploading}
            onClick={onUpload}
          >
            上传替换
          </Button>
          <Button
            icon={<DownloadOutlined />}
            disabled={!pkg}
            onClick={() => downloadRunnerPackage().catch((e) => message.error(e.message))}
          >
            下载当前包
          </Button>
        </Space>
      </Space>
    </div>
  )
}
