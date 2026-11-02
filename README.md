# ul-agent-control —— 程式化游戏控制（测试工具）

给 AI/脚本用的“游戏手柄”：tick 调速、虚拟输入、视角、动作、执行命令并回传输出、
游戏内截图、状态回读。回环 HTTP API + node SDK/CLI，想要什么能力就往里加。

## 能力

| 动作 | 参数 | 说明 |
| --- | --- | --- |
| `tick` | `rate`（0.1–20） | 目标 TPS。服务端节流 + 客户端 Timer 同步，2 = 10 倍慢动作 |
| `move` | `forward` `strafe`(-1..1)、`jump` `sneak` `sprint`(bool) | 粘性虚拟输入，走正常玩家输入管道 |
| `look` / `lookoff` | `yaw` `pitch` | 粘性视角（绝对角度） |
| `attack` / `use` | — | 左键（挥臂+攻击注视目标）/ 右键使用 |
| `hotbar` | `slot`(0-8) | 切快捷栏 |
| `chat` | `text` | 发聊天 |
| `command` | `line`、`wait_ms?` | 以玩家身份执行服务端命令，**回传输出行** |
| `screenshot` | `name?` | 游戏内截图 → `screenshots/<name>.png`（Framebuffer 直取，不依赖 X11） |
| `reset` | — | 清空虚拟输入/视角（tick 速率保留） |
| `state` | — | 状态回读（同 GET /state） |

**GET /state** 返回：玩家（位置/朝向/血/饥饿/氧气/经验/模式/维度/群系/手持/快捷栏/盔甲/
副手/药水效果）、世界（时间/天气/人数）、附近实体（32 格内前 12）、注视目标（方块/实体）、
当前界面、FPS、tick 实测、最近 20 条聊天（命令输出都会进这里）、最近截图名。

## 使用

游戏启动后 `<gameDir>/ul-agent-control.json` 里有 `{host, port, token}`（只绑 127.0.0.1）。

```bash
# CLI
node node/cli.js --dir /path/to/gameDir state
node node/cli.js tick 2
node node/cli.js command "ulpack status"
node node/cli.js screenshot after-test

# node 代码
const Ulac = require('./node/ulac');
const ulac = new Ulac('/path/to/gameDir');
await ulac.tick(5);
await ulac.move({ forward: 1 });
await Ulac.sleep(1000);
await ulac.reset();
const s = await ulac.state();
```

curl 也行：`curl -H "X-ULAC-Token: <token>" -d '{"action":"tick","rate":2}' http://127.0.0.1:<port>/cmd`

## 设计说明

- **同步动作**：command/screenshot/attack/… 都在游戏线程执行完才回包（`wait_ms` 可调，
  慢速 tick 下记得调大）。
- **tick 节流**：服务端在每个 tick 开头把间隔“睡”到 1000/ms；客户端 Timer.tickLength
  同步放大。tick 只能放慢（≤20 TPS），不追求超速。
- **命令输出捕获**：以玩家身份执行（权限/目标选择器语义一致），输出文本回传 JSON。
- **截图**：走 `ScreenShotHelper` + Framebuffer，窗口遮挡/无窗口都不影响；比 X11 截图干净。
- 后续想加什么（背包点击、窗口事件订阅、录像、MCP 工具封装……）直接往里塞。

## 构建

```bash
cd dev/ul-agent-control && ./gradlew build
# 测试工具：直接丢进某个实例的 mods/（不进 _shared 池）
cp build/libs/ul-agent-control-1.0.0.jar ../../shaping_the_world/mods/
```

## 集成

- 测试脚本：`node/` 下的 SDK；shell 里 `node node/cli.js ...` 或 curl。
- MCP：SDK 已经是纯函数式的，包一层 MCP tool 即可（后续按需加）。
