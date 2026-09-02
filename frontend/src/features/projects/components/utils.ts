// detail 子组件共享小工具。
/** 环境名 → Tag 颜色（服务器/环境两处表格共用） */
export function envColor(env: string): string {
  return env === 'prod' ? 'red' : env === 'staging' ? 'orange' : 'blue'
}
