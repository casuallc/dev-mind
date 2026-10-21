// 抽屉宽度标准（全平台统一，勿散落魔法数）：
// - DRAWER_DEFAULT：普通抽屉（表单 / 详情 / 列表操作），以「智能体运维 → 对话审计 → 轮次详情」抽屉宽度为基准。
// - DRAWER_SCREEN：大内容阅读/编辑抽屉——查看日志、终端、查看文档详情/编辑、版本差异等，占屏幕宽度 70%。
// - DRAWER_NESTED：嵌套在 DRAWER_SCREEN 抽屉内的二级抽屉（必须小于外层 70%）。
// 简单确认、单字段、≤4 字段小表单继续用 Modal，不用 Drawer。
export const DRAWER_DEFAULT = 1080
export const DRAWER_SCREEN = '70%'
export const DRAWER_NESTED = 960
