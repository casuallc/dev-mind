#!/usr/bin/env bash
# bin/dev-mind rotate-key E2E（tmp/，gitignored；无需起服务）：
# 在隔离 APP_HOME 里放一个带密钥的 data/，逐条断言：
#   1) 交互输入非 yes → 中止且密钥不变
#   2) 服务运行中（pidfile 指向活进程）→ 拒绝轮换
#   3) 交互 yes → auth.key 与已存在的 *-crypto.key 全部重生成，旧密钥进 data/key-backup-*/
#   4) --force 免交互可用
#   5) auth.key 缺失 → 直接新建 32 字节随机密钥
# 运行：bash tests/e2e-rotate-key.sh
set -uo pipefail
cd "$(dirname "$0")/.."
E2E=tmp/rotate-key-e2e
HOME_DIR="$PWD/$E2E/home"
rm -rf "$E2E" && mkdir -p "$HOME_DIR/bin" "$HOME_DIR/data" "$HOME_DIR/config" "$HOME_DIR/logs"
cp devmind-dist/src/main/dist/bin/dev-mind "$HOME_DIR/bin/"

DM="$HOME_DIR/bin/dev-mind"
FAIL=0
ok()  { echo "PASS: $1"; }
bad() { echo "FAIL: $1"; FAIL=1; }

seed_keys() {
  head -c 32 /dev/urandom >"$HOME_DIR/data/auth.key"
  head -c 32 /dev/urandom >"$HOME_DIR/data/integration-crypto.key"
  cp "$HOME_DIR/data/auth.key" "$E2E/auth.orig"
  cp "$HOME_DIR/data/integration-crypto.key" "$E2E/integration.orig"
}
seed_keys

echo "== 1. 交互输入 no → 中止、密钥不变 =="
out=$(echo no | bash "$DM" rotate-key 2>&1)
[[ "$out" == *aborted* ]] && ok "中止提示" || { bad "无中止提示: $out"; }
cmp -s "$HOME_DIR/data/auth.key" "$E2E/auth.orig" && ok "auth.key 未变" || bad "auth.key 被改动"

echo "== 2. 服务运行中 → 拒绝轮换 =="
echo $$ >"$HOME_DIR/data/dev-mind.pid"   # 本脚本进程还活着，kill -0 必中
out=$(bash "$DM" rotate-key --force 2>&1)
rc=$?
rm -f "$HOME_DIR/data/dev-mind.pid"
[[ $rc -ne 0 && "$out" == *"is running"* ]] && ok "运行中拒绝" || bad "未拒绝运行中轮换(rc=$rc): $out"
cmp -s "$HOME_DIR/data/auth.key" "$E2E/auth.orig" && ok "密钥仍未变" || bad "运行中密钥被改动"

echo "== 3. 交互 yes → 全部轮换 + 备份 =="
out=$(echo yes | bash "$DM" rotate-key 2>&1)
[[ "$out" == *"rotated: data/auth.key"* && "$out" == *"rotated: data/integration-crypto.key"* ]] \
  && ok "两个密钥均轮换" || bad "轮换输出异常: $out"
cmp -s "$HOME_DIR/data/auth.key" "$E2E/auth.orig" && bad "auth.key 未换" || ok "auth.key 已换"
cmp -s "$HOME_DIR/data/integration-crypto.key" "$E2E/integration.orig" && bad "integration-crypto.key 未换" || ok "integration-crypto.key 已换"
[[ $(stat -c%s "$HOME_DIR/data/auth.key") == 32 ]] && ok "新 auth.key 32 字节" || bad "新 auth.key 长度异常"
BK=$(ls -d "$HOME_DIR"/data/key-backup-* 2>/dev/null | head -1)
[[ -n "$BK" && -f "$BK/auth.key" && -f "$BK/integration-crypto.key" ]] && ok "旧密钥已备份 $BK" || bad "备份缺失"
[[ -n "$BK" ]] && cmp -s "$BK/auth.key" "$E2E/auth.orig" && ok "备份内容与旧密钥一致" || bad "备份内容不符"

echo "== 4. --force 免交互 =="
cp "$HOME_DIR/data/auth.key" "$E2E/auth.round2"
out=$(bash "$DM" rotate-key --force 2>&1 </dev/null)
[[ "$out" == *"rotated: data/auth.key"* ]] && ok "--force 轮换成功" || bad "--force 失败: $out"
cmp -s "$HOME_DIR/data/auth.key" "$E2E/auth.round2" && bad "--force 后密钥未换" || ok "--force 后密钥已换"

echo "== 5. auth.key 缺失 → 新建 =="
rm -f "$HOME_DIR/data/auth.key"
out=$(bash "$DM" rotate-key --force 2>&1)
[[ -f "$HOME_DIR/data/auth.key" && $(stat -c%s "$HOME_DIR/data/auth.key") == 32 ]] \
  && ok "缺失时新建 32 字节密钥" || bad "缺失时未新建: $out"

if [[ $FAIL -eq 0 ]]; then
  rm -rf "$E2E"
  echo "ALL PASS"
else
  echo "FAILED（现场保留在 $E2E）"
  exit 1
fi
