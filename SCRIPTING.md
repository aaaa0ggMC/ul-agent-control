# ul-agent-control 脚本操控手册（自用）

> 目标：像 TAS 一样逐 tick 操控整个游戏，录成 20fps 视频（1 秒 = 20 游戏 tick）。
> 原则：**不绕过**——所有操控走游戏自己的输入层/事件/公开入口；工具做不到就扩展本工具。

## 时间模型

- **默认静止**：`begin` 开启步进模式（暂停菜单冻结世界），世界只在你放行时前进。
- `step N`（别名 `advance N`）= 放行 N 个游戏 tick 后重新冻结。
- `resume` = 恢复正常时间流。
- 录制与时间模型正交：`record start` 后**世界每推进 1 tick 存 1 帧**（步进/自由跑都精确）。

## 动作一览（node/node/cli.js --dir <gameDir> <action> '<json>'）

| 动作 | 参数 | 说明 |
| --- | --- | --- |
| `begin` / `freeze` | — | 冻结世界（开 step 模式） |
| `step N` / `advance N` | 简写数字 | 放行 N tick |
| `resume` | — | 恢复正常时间 |
| `watch` | 见下 | **条件等待**：推进时间直到条件满足或超时 |
| `record` | `{"op":"start\|stop","every":N}` | 逐 tick 录帧（every=1 每 tick 一帧） |
| `invoke` | `{"class","method"[,"arg":"player"]}` | 反射调任意模组静态方法；arg=player 传服务端玩家并**调度到服务端线程** |
| `command` | `{"line","wait_ms"}` | 执行游戏命令（冻结期排队，放行的 tick 里执行） |
| `move` | `{"forward","strafe","jump","sneak","sprint"}` | 合成移动输入（InputUpdateEvent，真实输入层） |
| `look` | `{"yaw","pitch"}` | 视角（pitch -90 看天=射线必 MISS） |
| `use` / `attack` | `{"hold":N}` | 右键/左键：KeyBinding + 反射 rightClickMouse（SRG func_147121_ag）+ 空手对空补发 RightClickEmpty 事件 |
| `hotbar` | `{"slot":0-8}` | 选快捷栏 |
| `join` | `{}` | 免 GUI 进测试世界 |
| `respawn` | `{}` | 复活 |
| `state` | `{}` | 玩家状态快照（坐标/血/饥饿/手持…） |
| `screenshot` | `{"name"}` | 单帧截图 |

## watch(条件, tick 速度, 超时)

```bash
node node/cli.js --dir $D watch '{"class":"aaaa0ggmc.ulmodpack.zombie.ZombieForms","method":"isTitan","arg":"player","expect":{"equals":"true"},"every":1,"timeout_ms":120000}'
```

- `class`/`method`/`arg`：与 invoke 同语义（任意公开静态方法 = 谓词）。
- `expect`：`{"equals":"true"}` 或 `{"min":1}` 或 `{"max":0}`（数值比较对返回值解析）。
- `every`：每评估一次放行多少 tick（= tick 速度节流）。
- `timeout_ms`：墙钟超时；返回 `{ok, satisfied, result, ticks_waited}`。
- 进入时若世界未冻结会**自动 begin**；结束后保持冻结（继续用 step/record）。

常用谓词（ul-modpack 公开静态方法）：

| 意图 | 调用 | expect |
| --- | --- | --- |
| 招募到随从 | `HordeManager.countFollowers(arg:player)` | min 1 |
| 尸潮进行中 | `HordeManager.isTideActive(arg:player)` | equals true |
| 通关数 | `HordeManager.tidesCleared(arg:player)` | min 1 |
| 变成泰坦 | `ZombieForms.isTitan(arg:player)` | equals true |
| 有尸群领导 | `ZombieForms.hasLeadership(arg:player)` | equals true |

## 录制 → 成片

```bash
bash dev/ul-agent-control/scripts/assemble.sh <framesDir> 20 out.mp4
# framesDir 在 gameDir/screenshots/ulac-rec-<ts>/；fps=20 即 1 秒=20 tick
```

## 环境铁律（踩过的坑）

1. **死亡屏/任何 GUI 开着 → 输入层整个失效**（runTickKeyboard 门禁）；操控前先 `respawn` + `command gamemode creative`（防被怪打死）。
2. 玩家形态会被 respawn 重置；操控前 `command ulpack status` 确认形态，必要时 `command ulpack infect`。
3. `time set midnight` 不存在，用 `time set 18000`；时间流逝，每次尝试前重设。
4. 空手右键=射线必须 MISS：`look pitch:-90` 看天；僵尸/村民别站脸上。
5. 服务端线程断言：morphext 玩家数据只能在服务端线程读写 → invoke arg=player 自动调度，别自己在客户端线程调。
6. 冻结期 command 会排队：先发命令再 step 放行；或用 watch（自动放行）。
7. 构建校验认 **BUILD SUCCESSFUL**（javac 报错是中文「错误:」，grep error: 抓不到）。
8. 实例 jar 更新：池子 cp + `./scripts/sync-mods.sh`；清单外本地文件 sync 拒绝覆盖（先 rm 实例副本）。

## 演示脚本骨架（不死者线）

```bash
D=.../shaping_the_world
cli join '{}'
cli respawn '{}'; cli command '{"line":"gamemode creative"}'; cli command '{"line":"time set 18000"}'
cli command '{"line":"tp @p -136 72 1688"}'; cli command '{"line":"kill @e[type=!player,r=30]"}'
cli command '{"line":"summon minecraft:zombie ~-2 ~ ~3"}'   # ×6，随从素材
cli command '{"line":"ulpack skill give Tester ul:horde_leadership"}'
cli record '{"op":"start","every":1}'
cli begin
cli move '{"forward":0.3,"sneak":true}'; cli step 20          # 走 1 秒
cli invoke '{"class":"aaaa0ggmc.ulmodpack.zombie.horde.HordeManager","method":"tryRecruit","arg":"player"}'
cli watch '{...countFollowers min 1...}'
cli command '{"line":"summon minecraft:villager ~ ~1 ~8"}'   # ×5 建"村"
cli invoke '{...HordeManager.onAirUse...}'                    # 发动屠村
cli watch '{...tidesCleared min 1...}'                        # 等通关（铁傀儡/掉腐肉都在其中）
cli command '{"line":"give Tester aaaa0ggmc_ul_modpack:zealous_flesh 1"}'
cli invoke '{...TitanFeast.transform...}'                     # 变泰坦
cli watch '{...isTitan true...}'
cli invoke '{...TitanFormHandler.slam...}'                    # 震地
cli record '{"op":"stop"}'; cli resume
bash scripts/assemble.sh <dir> 20 demo.mp4
```
