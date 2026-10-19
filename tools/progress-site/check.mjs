#!/usr/bin/env node
/**
 * starcon 交付进度看板 —— 数据自检
 *
 * 目的：拦住「构建成功但数据是错的」这种最糟的失败。
 * 判据不是「解析没报错」，而是「构建产物与台账自己声明的汇总数字一致」。
 *
 * 依据：
 *   - 台账 module-progress.md 顶部的「## 总览」是人工维护的声明值
 *   - 台账底部的「## 模块明细」是逐行维护的状态
 *   - 两者若对不上，说明台账自身漂了 —— 此时绝不能发布，否则站点数字与仓库文档互相矛盾
 *
 * 用法：node tools/progress-site/check.mjs
 * 退出码 0 = 通过；1 = 失败（调用方必须放弃发布）
 */

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..', '..');
const BP = join(ROOT, 'docs', 'product', 'capability-blueprint');

const failures = [];
const notes = [];

const fail = (m) => failures.push(m);

/* ── 1. 读台账的「总览」声明值 ─────────────────────────── */

const ledger = readFileSync(join(BP, 'module-progress.md'), 'utf8');
const overviewStart = ledger.indexOf('## 总览');
const declared = {};
if (overviewStart === -1) {
  fail('台账里找不到「## 总览」小节');
} else {
  const slice = ledger.slice(overviewStart, ledger.indexOf('##', overviewStart + 10));
  const pick = (label) => {
    const m = slice.match(
      new RegExp('\\|\\s*\\*?\\*?' + label + '\\*?\\*?\\s*\\|\\s*([^|]+?)\\s*\\|'),
    );
    return m ? m[1].trim() : null;
  };
  declared.total = pick('模块总数');
  declared.delivered = pick('已交付');
  declared.inProgress = pick('进行中');
  declared.notStarted = pick('未开发');
  declared.rate = pick('完成率');
  for (const [k, v] of Object.entries(declared)) {
    if (v == null) fail(`台账总览里读不到「${k}」`);
  }
}

/* ── 2. 读构建产物 ─────────────────────────────────────── */

let data;
try {
  data = JSON.parse(readFileSync(join(__dirname, 'dist', 'data.json'), 'utf8'));
} catch (e) {
  console.error('CHECK_FAILED: 无法读取或解析 dist/data.json — ' + e.message);
  process.exit(1);
}

const s = data.summary;

/* ── 3. 产物 vs 台账声明值 ──────────────────────────────── */

const num = (v) => Number(String(v ?? '').replace(/[^\d.]/g, ''));
const cmp = (label, got, want) => {
  if (num(got) !== num(want)) fail(`${label}：构建产物 ${got} ≠ 台账总览声明 ${want}`);
};

cmp('模块总数', s.total, declared.total);
cmp('已交付', s.delivered, declared.delivered);
cmp('进行中', s.inProgress, declared.inProgress);
cmp('未开发', s.notStarted, declared.notStarted);
if (declared.rate != null) {
  const want = Number(String(declared.rate).replace('%', '').trim());
  if (Math.abs(want - s.rate) > 0.05) fail(`完成率：构建产物 ${s.rate}% ≠ 台账总览声明 ${want}%`);
}

/* ── 4. 内部一致性 ─────────────────────────────────────── */

const systems = data.systems || [];
const modules = systems.flatMap((x) => x.modules || []);

if (modules.length !== s.total) fail(`模块计数不一致：明细 ${modules.length} ≠ 汇总 ${s.total}`);
if (systems.length !== s.systemCount)
  fail(`系统计数不一致：明细 ${systems.length} ≠ 汇总 ${s.systemCount}`);

const recomputed = [
  ['delivered', '已交付', '已交付'],
  ['inProgress', '进行中', '进行中'],
  ['notStarted', '未开发', '未开发'],
].map(([key, label, status]) => [key, label, modules.filter((m) => m.status === status).length]);
for (const [key, label, v] of recomputed) {
  if (v !== s[key]) fail(`${label} 计数不一致：逐行统计 ${v} ≠ 汇总 ${s[key]}`);
}

const badStatus = modules.filter((m) => !['已交付', '进行中', '未开发'].includes(m.status));
if (badStatus.length) {
  fail(
    `状态词表被污染：${badStatus.length} 个模块的状态不在「已交付/进行中/未开发」内，例如 ${badStatus[0].id}=${badStatus[0].status}`,
  );
}

const noDuty = modules.filter((m) => !m.duty);
if (noDuty.length) {
  notes.push(`${noDuty.length} 个模块没有职责描述（不影响发布，页面上该字段留空）`);
}

const deliveredNoEvidence = modules.filter((m) => m.status === '已交付' && !m.evidence);
if (deliveredNoEvidence.length) {
  fail(
    `${deliveredNoEvidence.length} 个模块标为「已交付」却没有交付证据，例如 ${deliveredNoEvidence[0].id}`,
  );
}

const emptySystem = systems.filter((x) => !x.modules || x.modules.length === 0);
if (emptySystem.length) fail(`${emptySystem.length} 个系统没有任何模块`);

/* ── 5. 输出 ────────────────────────────────────────────── */

console.log('── 自检明细 ──');
console.log(`  模块总数      ${s.total}`);
console.log(`  已交付        ${s.delivered}`);
console.log(`  进行中        ${s.inProgress}`);
console.log(`  未开发        ${s.notStarted}`);
console.log(`  完成率        ${s.rate}%`);
console.log(`  系统 / 二级功能 ${s.systemCount} / ${s.featureCount}`);
console.log(`  有职责描述    ${modules.length - noDuty.length}/${modules.length}`);
console.log(
  `  已交付有证据  ${modules.filter((m) => m.status === '已交付' && m.evidence).length}/${recomputed[0][2]}`,
);

for (const n of notes) console.log(`  提示：${n}`);

if (failures.length) {
  console.error('── 自检失败 ──');
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}

console.log('  ✓ 全部通过');
