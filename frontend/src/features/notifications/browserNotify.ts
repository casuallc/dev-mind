// 浏览器通知（FR-02）：P0 前台也弹；P1 仅后台弹；P2 静默进中心。P0/P1 配提示音。
import type { AppNotification } from './types'

export function requestNotificationPermission() {
  if ('Notification' in window && Notification.permission === 'default') {
    void Notification.requestPermission()
  }
}

export function showBrowserNotification(n: AppNotification) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return
  if (n.level === 'P2') return
  if (n.level === 'P1' && document.visibilityState !== 'hidden') return
  try {
    const notif = new Notification(n.title, {
      body: n.body || `事件：${n.eventType}`,
      tag: `devmind-${n.id}`,
    })
    notif.onclick = () => {
      window.focus()
      if (n.entityType === 'SESSION' && n.entityId) {
        window.location.href = `/sessions/${n.entityId}`
      }
      notif.close()
    }
    playBeep()
  } catch {
    /* 通知不可用则忽略 */
  }
}

/** 短提示音（Web Audio 生成，无需音频资源）。 */
function playBeep() {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    const ctx = new Ctx()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.type = 'sine'
    osc.frequency.value = 880
    gain.gain.setValueAtTime(0.12, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.25)
    osc.start()
    osc.stop(ctx.currentTime + 0.25)
  } catch {
    /* 音频不可用则忽略 */
  }
}
