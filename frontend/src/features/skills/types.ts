// Skill 管理（基础模块）的类型定义，与后端 devmind-skill 模块对齐

export type SkillScope = 'GLOBAL' | 'PROJECT'
export type SkillStatus = 'ACTIVE' | 'DISABLED'

/** Skill 列表项（对应后端 SkillView；GLOBAL 的 projectId 为 null） */
export interface Skill {
  id: string
  scope: SkillScope
  projectId?: string | null
  name: string
  description: string
  tags: string[]
  status: SkillStatus
  fileCount: number
  hitCount: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

/** 分页响应（对应后端 PageView） */
export interface SkillPage {
  items: Skill[]
  total: number
  page: number
  size: number
}

export interface SkillInput {
  scope: SkillScope
  projectId?: string
  name: string
  description: string
  contentMd?: string
  extraFrontmatter?: Record<string, string>
  tags?: string[]
  status?: SkillStatus
}

/** 附件元数据（列表不读内容） */
export interface SkillFileMeta {
  id: string
  path: string
  binary: boolean
  size: number
  contentType?: string
  createdAt: string
  updatedAt: string
}

/** Skill 详情：列表项 + SKILL.md 正文 + 其余 frontmatter 键 + 附件元数据 */
export interface SkillDetail {
  skill: Skill
  contentMd?: string
  extraFrontmatter: Record<string, string>
  files: SkillFileMeta[]
}

export interface SkillFileInput {
  path?: string
  contentBase64?: string
  contentType?: string
}

export interface SkillFileContent {
  meta: SkillFileMeta
  contentBase64: string
}

/** 导出（为注入 agent 工作目录预留，本期 UI 不暴露） */
export interface SkillPackage {
  items: {
    skillId: string
    name: string
    scope: SkillScope
    projectId?: string | null
    files: { path: string; binary: boolean; contentBase64: string }[]
  }[]
}
