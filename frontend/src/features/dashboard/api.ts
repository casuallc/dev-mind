import { api } from '../../shared/api/client'
import type { DashboardView } from './types'

// CAP-16 指挥中心：全局聚合视图
export const getDashboard = () => api.get<DashboardView>('/dashboard')
