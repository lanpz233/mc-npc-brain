#!/usr/bin/env node
/* 面板真实数据冒烟：用仓库内置的真实数据文件（791 条配方等）跑一遍 panel.html 的
 * loadState 全流程（假 DOM），断言状态为已连接且各编辑器行数达标。
 * 历史教训：v0.1.5~v0.2.0 的编辑器引用了三个未定义的辅助函数（detailsRow /
 * labeledInput / textInput），假数据测试绕开了空循环、真实数据一进来就整页
 * “连接失败”。改面板后请跑：node scripts/panel-dom-smoke.js
 */
const fs = require('fs');
const path = require('path');
const RES = path.join(__dirname, '..', 'src', 'main', 'resources');
const html = fs.readFileSync(path.join(RES, 'webpanel', 'panel.html'), 'utf-8');
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

class El {
  constructor(tag) {
    this.tagName = tag; this.children = []; this.style = {}; this.dataset = {};
    this.classList = { add() {}, remove() {}, toggle() {} };
    this.listeners = {}; this.value = ''; this.checked = false; this._text = ''; this.className = '';
  }
  set textContent(v) { this._text = v; }
  get textContent() { return this._text + this.children.map(c => c.textContent).join(''); }
  set innerHTML(v) { this._html = v; this.children = []; }
  get innerHTML() { return this._html || ''; }
  appendChild(c) { this.children.push(c); return c; }
  append(...cs) { cs.forEach(c => this.children.push(c)); }
  addEventListener(t, f) { (this.listeners[t] ||= []).push(f); }
  querySelector() { return new El('div'); }
  querySelectorAll() { return []; }
  click() {}
  remove() {}
}
const byId = new Map();
global.document = {
  createElement: t => new El(t),
  querySelector(sel) {
    if (!byId.has(sel)) byId.set(sel, new El('div'));
    return byId.get(sel);
  },
  querySelectorAll() { return []; }
};
global.window = { location: { origin: 'http://127.0.0.1:8767' } };


const fs2 = require('fs');
const R = path.join(__dirname, '..', 'src', 'main', 'resources') + '/';
const fileEntry = (name, label) => ({ name, label, description: '', content: fs2.readFileSync(R + name, 'utf-8') });
const fakeState = {
  ok: true, version: '0.2.0', fields: [], values: {},
  files: [
    fileEntry('recipes.json', '配方表'),
    fileEntry('personality_tags.json', '性格标签'),
    fileEntry('surnames.json', '姓氏库'),
    fileEntry('given_names.json', '名字用字库'),
    fileEntry('prices.json', '定价表'),
    fileEntry('profession-pools.json', '职业货池'),
    fileEntry('prompts.json', '提示词')
  ],
  info: { recipes: 791, tags: 49, surnames: 97, givenChars: 125 }
};
global.fetch = async () => ({ ok: true, status: 200, json: async () => fakeState });

process.on('unhandledRejection', (e) => { console.log('UNHANDLED REJECTION:', e && e.message); process.exit(2); });
try {
  eval(script);
} catch (e) {
  console.log('TOP-LEVEL THROW:', e.message);
  process.exit(1);
}

setTimeout(() => {
  const counts = {};
  for (const [sel, el] of byId) {
    let n = 0;
    (function walk(x) { n++; for (const c of x.children) walk(c); })(el);
    counts[sel] = n;
  }
  // console.log('rendered element counts:', JSON.stringify(counts));
  const tst = byId.get('#toast');
  console.log('toast text:', JSON.stringify(tst && tst.textContent));
  const st = byId.get('#status');
  console.log('status text:', JSON.stringify(st && st.textContent));
  for (const sel of ['#recipeRows', '#priceRows', '#poolRows', '#promptRows']) {
    const el = byId.get(sel);
    console.log(sel, 'direct children:', el ? el.children.length : 'MISSING');
  }
  const fail = (msg) => { console.log('FAIL:', msg); process.exit(1); };
  if ((st && st.textContent || '').includes('连接失败')) fail('loadState reported 连接失败, toast=' + (tst && tst.textContent));
  const expect = { '#recipeRows': 700, '#priceRows': 50, '#poolRows': 16, '#promptRows': 11 };
  for (const [sel, min] of Object.entries(expect)) {
    const el = byId.get(sel);
    if (!el) fail(sel + ' never rendered');
    if (el.children.length < min) fail(sel + ' children ' + el.children.length + ' < ' + min);
  }
  console.log('PASS: panel renders with real bundled data (recipes/prices/pools/prompts all populated)');
}, 800);
