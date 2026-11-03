#!/bin/bash
# 不死者线全流程演示：招募 → 尸潮 → 屠村 → 热忱的腐肉 → 泰坦 → 震地（逐 tick 录制）
# 用法: demo-zombie-tide.sh [gameDir] [out.mp4]
set -u
GAMEDIR="${1:-/home/aaaa0ggmc/Games/HMCL/shaping_the_world}"
OUT="${2:-/home/aaaa0ggmc/Games/HMCL/ul-zombie-tide-demo.mp4}"
ULAC=/home/aaaa0ggmc/Games/HMCL/dev/ul-agent-control
cli() { timeout "${CLITMO:-90}" node "$ULAC/node/cli.js" --dir "$GAMEDIR" "$@" 2>&1; }
say() { echo "== $*"; }
w() { # watch 便捷封装: w '<method类>' '<方法>' '<expect json>' <timeout_ms>
  cli watch "{\"class\":\"$1\",\"method\":\"$2\",\"arg\":\"player\",\"expect\":$3,\"every\":1,\"timeout_ms\":$4}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('   watch:', d.get('satisfied'), 'result=', d.get('result'), 'ticks=', d.get('ticks_waited'))"
}

H=aaaa0ggmc.ulmodpack.zombie.horde.HordeManager
Z=aaaa0ggmc.ulmodpack.zombie.ZombieForms
T=aaaa0ggmc.ulmodpack.zombie.titan

say "进世界 + 场景"
cli join '{}' | grep -o '"ok": true' | head -1
# 等世界真正加载完（RTG 世界可达数分钟；加载中客户端 tick 不跑、指令全超时）
for i in $(seq 1 60); do
  INGAME=$(cli state '{}' | python3 -c "import sys,json; print(json.load(sys.stdin).get('inGame', False))" 2>/dev/null)
  [ "$INGAME" = "True" ] && { echo "   inGame after ${i} polls"; break; }
  sleep 5
done
cli respawn '{}' > /dev/null; sleep 3
cli command '{"line":"gamemode creative"}' > /dev/null
cli command '{"line":"gamerule doDaylightCycle false"}' > /dev/null   # 锁夜晚：watch 的 tick 会把白天推回来
cli command '{"line":"time set 18000"}' > /dev/null
cli command '{"line":"tp @p -122 72 1702"}' > /dev/null
cli command '{"line":"kill @e[type=!player,r=40]"}' > /dev/null; sleep 1
for t in minecraft:villager minecraft:zombie minecraft:villager_golem minecraft:husk; do cli command "{\"line\":\"kill @e[type=$t]\"}" > /dev/null; done   # 历轮残留全球清（半径杀会漏跑远的）
cli command '{"line":"fill -132 72 1692 -112 74 1712 minecraft:air"}' > /dev/null   # 清空脚下杂物（门/石块会卡人）
for i in $(seq 1 20); do cli command "{\"line\":\"summon minecraft:zombie ~$((i*2-21)) ~ ~3\"}" > /dev/null; done
# 强度层调参演示：铁傀儡上限 9→4（覆盖 JSON 热重载，不重新构建）
cat > "$GAMEDIR/config/ul_strength_design/overrides.json" <<'JSON'
{ "_doc": "演示调参：铁傀儡上限降低，保证屠村能打完", "values": { "zombie_tide.horde.golem_max_alive": 4 }, "tiers": {} }
JSON
sleep 6   # 等 5 秒热重载
# 补光（夜里录视频要看得见）：四角海晶灯柱
for xy in "-14 6" "-14 -6" "14 6" "14 -6" "0 0"; do set -- $xy; cli command "{\"line\":\"setblock ~$1 ~ ~$2 minecraft:sea_lantern\"}" > /dev/null; cli command "{\"line\":\"setblock ~$1 ~1 ~$2 minecraft:sea_lantern\"}" > /dev/null; done
cli camera '{"mode":1}' > /dev/null
cli command '{"line":"ulpack skill give Tester ul:horde_leadership"}' > /dev/null
cli command '{"line":"ulpack tide stop"}' > /dev/null   # 清掉上一轮残留的尸潮（世界持久化）

say "开录 + 冻结"
cli record '{"op":"stop"}' > /dev/null
DIR=$(cli record '{"op":"start","every":1}' | python3 -c "import sys,json; print(json.load(sys.stdin).get('dir',''))")
cli begin | grep -o '"phase": "[A-Z]*"' | head -1

say "招募（移动中对空使用能力）"
cli look '{"yaw":-90,"pitch":0}' > /dev/null   # 朝西（空地），别撞进僵尸堆里走不动
cli move '{"forward":0.4,"sneak":true}' > /dev/null
cli step 25 > /dev/null                      # 走 1.2 秒（移动窗口要真的走起来）
cli step 5 > /dev/null                       # 预热：位移先进跟踪器，invoke 别落在首 tick
cli invoke "{\"class\":\"$H\",\"method\":\"tryRecruit\",\"arg\":\"player\"}" > /dev/null
CLITMO=150 w "$H" countFollowers '{"min":1}' 90000
cli move '{"forward":0,"sneak":false}' > /dev/null

say "建村（村民聚集即村庄）+ 发动尸潮"
# 村民圈进栅栏（村民遇袭会逃跑，跑了屠村就打不完）
cli command '{"line":"fill ~-4 ~ ~6 ~2 ~2 ~12 minecraft:oak_fence"}' > /dev/null
cli command '{"line":"fill ~-3 ~ ~7 ~1 ~1 ~11 minecraft:air"}' > /dev/null
for i in 1 2 3 4; do cli command "{\"line\":\"summon minecraft:villager ~$((i-2)) ~ ~8\"}" > /dev/null; done
cli step 40 > /dev/null                      # 让村民散开几步、AI 活动
cli invoke "{\"class\":\"$H\",\"method\":\"onAirUse\",\"arg\":\"player\"}" > /dev/null
CLITMO=60 w "$H" isTideActive '{"equals":"true"}' 30000

say "屠村演出（铁傀儡刷洗/随从战死/掉腐肉）"
for i in $(seq 1 12); do
  R=$(CLITMO=120 w "$H" tidesCleared '{"min":1}' 60000)
  echo "$R" | grep -q "satisfied': True" && break
done

say "热忱的腐肉 → 泰坦"
cli command '{"line":"give Tester aaaa0ggmc_ul_modpack:zealous_flesh 1"}' > /dev/null
cli step 10 > /dev/null
cli invoke "{\"class\":\"$T.TitanFeast\",\"method\":\"transform\",\"arg\":\"player\"}" > /dev/null
CLITMO=150 w "$Z" isTitan '{"equals":"true"}' 60000
cli look '{"yaw":-140,"pitch":-10}' > /dev/null
cli move '{"forward":0.3}' > /dev/null; cli step 30 > /dev/null; cli move '{"forward":0}' > /dev/null

say "震地"
cli invoke "{\"class\":\"$T.TitanFormHandler\",\"method\":\"slam\",\"arg\":\"player\"}" > /dev/null
cli step 40 > /dev/null

say "收尾压片"
cli record '{"op":"stop"}' | grep -o '"frames": [0-9]*'
cli resume > /dev/null
if [ -n "$DIR" ]; then
  bash "$ULAC/scripts/assemble.sh" "$DIR" 20 "$OUT" | tail -1
else
  echo "!! 没拿到帧目录"
fi
