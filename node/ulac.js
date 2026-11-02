'use strict';
/**
 * ul-agent-control 的 node SDK。
 *
 *   const Ulac = require('./ulac');
 *   const ulac = new Ulac('/path/to/gameDir');   // 读 ul-agent-control.json
 *   await ulac.state();                          // 状态回读
 *   await ulac.tick(2);                          // 2 TPS 慢动作
 *   await ulac.move({ forward: 1, sprint: true });
 *   await ulac.look(90, -10);
 *   await ulac.command('ulpack status');         // 执行命令并拿到输出
 *   await ulac.screenshot('after-infection');    // 游戏内截图（screenshots/ 目录）
 *
 * 也可用环境变量 ULAC_URL + ULAC_TOKEN 直连（不读文件）。
 */
const fs = require('fs');
const path = require('path');

class Ulac {
  constructor(gameDir) {
    if (process.env.ULAC_URL) {
      this.base = process.env.ULAC_URL.replace(/\/$/, '');
      this.token = process.env.ULAC_TOKEN || '';
      return;
    }
    const file = path.join(gameDir, 'ul-agent-control.json');
    this.info = JSON.parse(fs.readFileSync(file, 'utf8'));
    this.base = `http://${this.info.host}:${this.info.port}`;
    this.token = this.info.token;
  }

  async cmd(action, params = {}) {
    const res = await fetch(`${this.base}/cmd`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-ULAC-Token': this.token },
      body: JSON.stringify(Object.assign({ action }, params)),
    });
    return res.json();
  }

  state() { return this.cmd('state'); }
  tick(rate) { return this.cmd('tick', { rate }); }
  move(p) { return this.cmd('move', p); }
  look(yaw, pitch) { return this.cmd('look', { yaw, pitch }); }
  lookoff() { return this.cmd('lookoff'); }
  reset() { return this.cmd('reset'); }
  attack() { return this.cmd('attack'); }
  use() { return this.cmd('use'); }
  hotbar(slot) { return this.cmd('hotbar', { slot }); }
  chat(text) { return this.cmd('chat', { text }); }
  command(line, waitMs = 8000) { return this.cmd('command', { line, wait_ms: waitMs }); }
  respawn() { return this.cmd('respawn'); }
  screenshot(name) { return this.cmd('screenshot', name ? { name } : {}); }

  /** 便捷：等 n 毫秒（node 侧）。 */
  static sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }
}

module.exports = Ulac;
