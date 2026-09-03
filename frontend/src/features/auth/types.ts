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

/** CAP-24 我的 Git 凭证视图（不含 secret 明文） */
export interface GitCredential {
  id: number
  label: string
  baseUrl: string
  gitAuthorName: string
  gitAuthorEmail: string
  hasSecret: boolean
  createdAt?: string
  updatedAt?: string
}
