export interface AuthUser {
  id: string
  username: string
  displayName: string
  role: string
  status: string
  createdAt?: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  user: AuthUser
}

/** CAP-35 我的第三方账号视图：一行 = 一个 ENABLED 平台实例 + 我的绑定状态（不含 secret 明文） */
export interface PlatformAccount {
  integrationId: number
  integrationType: 'GITLAB' | 'GITHUB' | 'JIRA' | string
  integrationName: string
  baseUrl: string
  bound: boolean
  authType?: string | null
  /** 仅 Jira BASIC 回显（登录名非敏感） */
  username?: string | null
  hasSecret: boolean
  gitAuthorName?: string | null
  gitAuthorEmail?: string | null
  updatedAt?: string | null
}
