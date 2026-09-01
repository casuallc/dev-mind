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
