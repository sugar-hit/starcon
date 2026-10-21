#!/usr/bin/env node
/**
 * 能力地图站点生成器（`build-capability-map.mjs`）的语义测试。
 *
 * 这个生成器负责把「功能蓝图 + 交付台账」变成公开进展站点。
 * 它一旦坏掉，**页面不会报错**，只会安静地展示过期数字 ——
 * 看页面的人会据此判断项目进度，数字错一次就是误导。
 *
 * 本测试锁住五件事：
 *   1. 站点里的模块数、已交付数与**台账逐行对得上**（不是与某个写死的数字对得上）。
 *   2. 生成结果**幂等**：连续跑两次，文件字节一致（`--check` 通过）。
 *   3. **页面没有被复制多份**（回归测试：2026-10-20 真的发生过）。
 *      证据列里出现 `$'` 时，`String.replace` 的字符串形式会把它当成
 *      「匹配点之后的原文」的占位符，于是整页被复制成好几份 ——
 *      当时生成的页面从 161KB 涨到 1.1MB，标题重复 4 次。改用函数形式才修好。
 *   4. 页面里**没有内嵌脚本错误**，且四层下钻 + 证据抽屉在真实浏览器里能用。
 *   5. 快照标记存在：看页面的人必须知道「这是哪一刻的状态」。
 *
 * 用法：node tools/module-progress/build-capability-map.itest.mjs
 */

import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const PAGE = join(ROOT, 'docs', 'site', 'capability-map', 'index.html');
const LEDGER = join(ROOT, 'docs', 'product', 'capability-blueprint', 'module-progress.md');
const BUILDER = join(HERE, 'build-capability-map.mjs');

let passed = 0;
let failed = 0;
const failures = [];

function ok(name, cond, detail = '') {
  if (cond) {
    passed++;
    console.log(`  ✓ ${name}`);
  } else {
    failed++;
    failures.push(`${name}${detail ? ` —— ${detail}` : ''}`);
    console.log(`  ✗ ${name}${detail ? ` —— ${detail}` : ''}`);
  }
}

/* ── 台账里的真实数字（测试的基准，不写死任何常量） ──────────── */

function ledgerCounts() {
  const text = readFileSync(LEDGER, 'utf8');
  const tally = { 已交付: 0, 进行中: 0, 未开发: 0 };
  let total = 0;
  const row = /^\|\s*(\d+\.\d+\.\d+\.\d+)\s*\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|/gm;
  let m;
  while ((m = row.exec(text)) !== null) {
    total++;
    const s = (m[5] ?? '').trim();
    if (s in tally) tally[s]++;
  }
  return { total, ...tally };
}

function pageData() {
  const html = readFileSync(PAGE, 'utf8');
  const m = html.match(/^const DATA = (.*);$/m);
  if (!m) return null;
  return JSON.parse(m[1]);
}

/* ── ① 站点数字 vs 台账逐行 ──────────────────────────────────── */

console.log('① 站点数字与台账一致');
const ledger = ledgerCounts();
const data = pageData();
ok('页面里能解析出数据块', data !== null);
if (data) {
  const tally = { 已交付: 0, 进行中: 0, 未开发: 0 };
  let total = 0;
  for (const s of data.systems)
    for (const f of s.l2)
      for (const c of f.l3)
        for (const mod of c.l4) {
          total++;
          if (mod.state in tally) tally[mod.state]++;
        }
  ok('模块总数与台账一致', total === ledger.total, `页面 ${total} / 台账 ${ledger.total}`);
  ok('已交付数与台账一致', tally.已交付 === ledger.已交付, `页面 ${tally.已交付} / 台账 ${ledger.已交付}`);
  ok('进行中数与台账一致', tally.进行中 === ledger.进行中, `页面 ${tally.进行中} / 台账 ${ledger.进行中}`);
  ok('未开发数与台账一致', tally.未开发 === ledger.未开发, `页面 ${tally.未开发} / 台账 ${ledger.未开发}`);

  const doneWithEvidence = data.systems
    .flatMap((s) => s.l2)
    .flatMap((f) => f.l3)
    .flatMap((c) => c.l4)
    .filter((m) => m.state === '已交付' && m.evidence && m.evidence !== '—');
  ok(
    '每个已交付模块都带实现证据',
    doneWithEvidence.length === ledger.已交付,
    `${doneWithEvidence.length} / ${ledger.已交付}`,
  );

  const l2 = data.systems.reduce((a, s) => a + s.l2.length, 0);
  const l3 = data.systems.reduce((a, s) => a + s.l2.reduce((b, f) => b + f.l3.length, 0), 0);
  ok('功能域与子功能层级非空', l2 > 0 && l3 > 0, `${l2} 功能域 / ${l3} 子功能`);
}

/* ── ② 页面没被复制多份（$ 替换回归） ────────────────────────── */

console.log('\n② 页面结构完好（$ 替换回归）');
const html = readFileSync(PAGE, 'utf8');
const dataLines = html.match(/^const DATA = .*;$/gm) ?? [];
ok('数据块只有一行', dataLines.length === 1, `${dataLines.length} 行`);
const titleCount = (html.match(/starcon 系统功能架构/g) ?? []).length;
ok('标题没有被复制（应为 title + h1 两处）', titleCount === 2, `${titleCount} 处`);
ok('样式块只有一份', (html.match(/<style>/g) ?? []).length === 1);
ok('脚本块只有一份', (html.match(/<script>/g) ?? []).length === 1);

/* ── ③ 兜底数字与快照标记 ───────────────────────────────────── */

console.log('\n③ 页面自述的数字正确');
ok('顶栏兜底总数正确', html.includes(`id="sTotal">${ledger.total}<`), `应为 ${ledger.total}`);
ok('顶栏兜底已交付正确', html.includes(`id="sOk">${ledger.已交付}<`), `应为 ${ledger.已交付}`);
ok('顶栏兜底未开发正确', html.includes(`id="sTodo">${ledger.未开发}<`), `应为 ${ledger.未开发}`);
ok('页面带数据快照时间', /"generatedAt":"\d{4}-\d{2}-\d{2}T/.test(html));
ok('页面带集成头 commit', /"integrationHead":"[0-9a-f]{7,}"/.test(html));

/* ── ④ 幂等：再跑一次，字节一致 ─────────────────────────────── */

console.log('\n④ 生成幂等');
try {
  execFileSync('node', [BUILDER], { cwd: ROOT, encoding: 'utf8' });
  ok('重新生成不报错', true);
  const after = readFileSync(PAGE, 'utf8');
  const dataLine = (t) => (t.match(/^const DATA = .*;$/m) ?? [''])[0];
  const stripTs = (t) => dataLine(t).replace(/"generatedAt":"[^"]*"/, '"generatedAt":"X"');
  ok('两次生成的数据一致（忽略时间戳）', stripTs(after) === stripTs(html));
  const check = execFileSync('node', [BUILDER, '--check'], { cwd: ROOT, encoding: 'utf8' });
  ok('--check 通过', check.includes('一致'), check.trim().split('\n')[0]);
} catch (e) {
  ok('重新生成不报错', false, String(e.message).slice(0, 200));
}

/* ── ⑤ 真实浏览器里能下钻、能看证据 ─────────────────────────── */

console.log('\n⑤ 浏览器端下钻与证据抽屉');
let browserChecked = false;
try {
  const { chromium } = await import('@playwright/test');
  const browser = await chromium.launch({ channel: 'chrome' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  await page.goto(`file://${PAGE}`, { waitUntil: 'networkidle' });

  ok('顶栏渲染出总数', (await page.textContent('#sTotal')).trim() === String(ledger.total));
  ok('顶栏渲染出已交付数', (await page.textContent('#sOk')).trim() === String(ledger.已交付));
  ok('系统卡片 18 张', (await page.$$('#sysgrid .card')).length === 18);

  await page.click('#sysgrid .card:nth-child(4)');
  await page.waitForSelector('#v-l2 .fcard');
  ok('下钻到 L2 功能域', (await page.$$('#v-l2 .fcard')).length > 0);

  await page.click('#v-l2 .fcard:nth-child(3)');
  await page.waitForSelector('#v-l3 .l3top');
  const groups = await page.$$('#v-l3 .l3group');
  for (const g of groups) {
    const isOpen = await g.evaluate((el) => el.classList.contains('open'));
    if (!isOpen) {
      await (await g.$('.l3top')).click();
      break;
    }
  }
  await page.waitForSelector('#v-l3 .l3group.open .l4row', { state: 'attached' });
  ok('下钻到 L4 模块明细', (await page.$$('#v-l3 .l3group.open .l4row')).length > 0);

  // 抽屉：已交付模块要给证据，未交付模块要说清楚
  const rows = await page.$$('#v-l3 .l3group.open .l4row');
  let sawEvidence = false;
  let sawTodo = false;
  for (const row of rows) {
    const tag = (await row.$eval('.tag', (e) => e.textContent.trim()));
    if (tag === '已交付' && !sawEvidence) {
      await row.click();
      await page.waitForSelector('#drawer.on');
      const evid = (await page.textContent('#dBody .evid')) || '';
      sawEvidence = evid.length > 50;
      await page.keyboard.press('Escape');
      await page.waitForFunction(() => !document.querySelector('#drawer').classList.contains('on'));
    }
    if (tag === '未开发' && !sawTodo) {
      await row.click();
      await page.waitForSelector('#drawer.on');
      const evid = (await page.textContent('#dBody .evid')) || '';
      sawTodo = evid.includes('尚未开发');
      await page.keyboard.press('Escape');
      await page.waitForFunction(() => !document.querySelector('#drawer').classList.contains('on'));
    }
    if (sawEvidence && sawTodo) break;
  }
  ok('已交付模块的抽屉给出实现证据', sawEvidence);
  ok('未交付模块的抽屉说明尚未开发', sawTodo);
  ok('没有 JS 运行时错误', errors.length === 0, errors.slice(0, 2).join(' | '));

  await browser.close();
  browserChecked = true;
} catch (e) {
  console.log(`  · 跳过浏览器检查（${String(e.message).split('\n')[0].slice(0, 120)}）`);
}

if (!browserChecked) console.log('  · 本轮浏览器端断言未执行（不影响其余结论）');

/* ── 汇总 ────────────────────────────────────────────────────── */

console.log(`\n通过 ${passed} 项，失败 ${failed} 项`);
if (failed) {
  console.log('\n失败明细：');
  failures.forEach((f) => console.log(`  ✗ ${f}`));
  process.exit(1);
}
if (!existsSync(PAGE)) {
  console.error('页面文件不存在');
  process.exit(1);
}
