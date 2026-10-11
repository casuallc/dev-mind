// 弹层宽度分档约定（Modal/Drawer 通用）：
// 内容较多的弹层（多字段表单/表格/详情/预览）一律用 Drawer，宽度按下述档位取，勿散落魔法数。
// 简单确认、单字段、≤4 字段小表单继续用 Modal。
export const DRAWER_SM = 640 // 轻量表单 / 简要信息（Descriptions 等）
export const DRAWER_FORM = 720 // 常规表单抽屉
export const DRAWER_WIDE = 960 // 大表单（富文本/动态字段/多区块）/ 代码·Markdown 预览
export const DRAWER_FULL = 1080 // 表格+详情 / 双栏浏览 / 日志
