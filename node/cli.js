#!/usr/bin/env node
'use strict';
/**
 * ul-agent-control 命令行。
 *
 *   node cli.js [--dir <gameDir>] <action> [params-json]
 *
 * 例：
 *   node cli.js --dir ~/Games/HMCL/shaping_the_world state
 *   node cli.js tick '{"rate":2}'
 *   node cli.js move '{"forward":1,"sprint":true}'
 *   node cli.js look 180 0                （简写：数字直接当参数）
 *   node cli.js command '{"line":"ulpack status"}'
 *   node cli.js screenshot
 *
 * gameDir 也可用环境变量 ULAC_GAME_DIR；默认取当前目录。
 */
const Ulac = require('./ulac');

async function main() {
  const args = process.argv.slice(2);
  let gameDir = process.env.ULAC_GAME_DIR || process.cwd();
  if (args[0] === '--dir') {
    gameDir = args[1];
    args.splice(0, 2);
  }
  const action = args[0];
  if (!action) {
    console.error('用法: node cli.js [--dir gameDir] <action> [params]');
    process.exit(1);
  }
  let params = {};
  const raw = args.slice(1).join(' ');
  // 简写优先（step 20 的 "20" 会被 JSON.parse 成数字，必须先于 JSON 路径判断）
  if (action === 'step' || action === 'advance') {
    params = { op: 'step', ticks: Number(args[1] || 1) };
  } else if (action === 'begin' || action === 'freeze') {
    params = { op: 'begin' };
  } else if (action === 'resume' || action === 'end') {
    params = { op: 'end' };
  } else if (raw) {
    try {
      params = JSON.parse(raw);
    } catch (e) {
      // 简写：look 180 0 / tick 2 / hotbar 3 / command ulpack status
      if (action === 'look') {
        params = { yaw: Number(args[1]), pitch: Number(args[2]) };
      } else if (action === 'tick' || action === 'hotbar') {
        params = { rate: Number(args[1]), slot: Number(args[1]) };
      } else if (action === 'command') {
        params = { line: raw };
      } else if (action === 'chat') {
        params = { text: raw };
      } else if (action === 'move') {
        params = { forward: Number(args[1] || 1) };
      } else if (action === 'screenshot') {
        params = { name: raw };
      }
    }
  }
  const ulac = new Ulac(gameDir);
  const out = await ulac.cmd(action, params);
  console.log(JSON.stringify(out, null, 2));
  process.exit(out && out.ok === false ? 1 : 0);
}

main().catch((e) => {
  console.error('ERROR', e.message || e);
  process.exit(1);
});
