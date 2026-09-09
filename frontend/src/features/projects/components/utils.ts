// detail 子组件共享小工具。
/** 环境名 → Tag 颜色（环境/部署等表格共用） */
export function envColor(env: string): string {
  return env === 'prod' ? 'red' : env === 'staging' ? 'orange' : 'blue'
}
