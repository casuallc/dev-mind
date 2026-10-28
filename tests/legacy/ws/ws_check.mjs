const ws = new WebSocket('ws://localhost:8080/ws/notifications/stream')
let got = null
ws.onopen = async () => {
  await fetch('http://localhost:8080/api/notifications/emit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ eventType: 'SESSION_DONE', title: 'WS push 测试', body: 'check', level: 'P1', entityType: 'TEST', entityId: 'ws-check-' + Date.now() }),
  })
}
ws.onmessage = (ev) => {
  const f = JSON.parse(ev.data)
  if (f.type === 'snapshot') { console.log('snapshot', f.notifications.length, 'notifications') }
  else if (f.type === 'notification') { got = f; console.log('RECEIVED PUSH:', f.notification.title, f.notification.level); ws.close() }
}
setTimeout(() => { console.log(got ? 'PASS' : 'FAIL', 'ws realtime push'); process.exit(got ? 0 : 1) }, 8000)
