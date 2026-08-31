// deploy 模块共享常量。
import type { DeployStatus } from './types'

export const STATUS_COLOR: Record<DeployStatus, string> = {
  PLANNED: 'blue',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  ROLLED_BACK: 'orange',
}
