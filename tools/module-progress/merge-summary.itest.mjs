#!/usr/bin/env node
/**
 * merge-summary 摘要器的集成测试
 *
 * 覆盖两件事：
 *   1. 台账解析在**畸形行**下不会伤及邻行（这是 sync.mjs 踩过的真实坑：
 *      某行少写一个结尾竖线，它那一格会吃到下一行，把邻行整个吞掉）。
 *   2. 状态跃迁识别与候选标题规则符合协议。
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  parseLedger,
  diffLedger,
  groupByVolume,
  suggestTitle,
  readLedgerAt,
  DONE,
  WIP,
  TODO,
} from './merge-summary.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

const row = (id, name, status, evidence = '证据') =>
  `| ${id} | ${name} | /x | D1–D2 | ${status} | ${evidence} |\n`;

// ── 1. 正常解析 ───────────────────────────────────────────────
{
  const text = `# 台账\n\n${row('1.1.1.1', '我的待办', DONE)}${row('1.1.1.2', '异常订单队列', TODO)}`;
  const map = parseLedger(text);
  check('解析出行数', map.size === 2, `实际 ${map.size}`);
  check('解析出功能名', map.get('1.1.1.1')?.name === '我的待办');
  check('解析出状态', map.get('1.1.1.2')?.status === TODO);
}

// ── 2. 畸形行（漏结尾竖线）不得吃掉邻行 ───────────────────────
{
  const text =
    `| 11.2.1.1 | 退货授权单 | /x | D4 | 已交付 | 证据 |\n` +
    `| 11.2.1.2 | 退货签收 | /x | D4 | 已交付 |\n` +
    row('11.2.1.3', '退货质检', TODO);
  const map = parseLedger(text);
  check(
    '畸形行自己不产生脏条目',
    map.get('11.2.1.2') === undefined,
    `实际 ${JSON.stringify(map.get('11.2.1.2'))}`,
  );
  check(
    '畸形行之后的行仍能解析出功能名',
    map.get('11.2.1.3')?.name === '退货质检',
    `实际 ${map.get('11.2.1.3')?.name}`,
  );
  check(
    '畸形行之后的行仍能解析出状态',
    map.get('11.2.1.3')?.status === TODO,
    `实际 ${map.get('11.2.1.3')?.status}`,
  );
  check('畸形行不改变总条目数', map.size === 2, `实际 ${map.size}`);
}

// ── 3. 状态跃迁识别 ───────────────────────────────────────────
{
  const base = parseLedger(
    row('9.2.1.1', '订单变更取消', TODO) + row('11.2.1.1', '退货授权单', WIP),
  );
  const head = parseLedger(
    row('9.2.1.1', '订单变更取消', DONE) + row('11.2.1.1', '退货授权单', DONE),
  );
  const { delivered, started } = diffLedger(base, head);
  check(
    '未开发 → 已交付 = 新增交付',
    delivered.some((d) => d.id === '9.2.1.1'),
  );
  check(
    '进行中 → 已交付 = 新增交付',
    delivered.some((d) => d.id === '11.2.1.1'),
  );
  check('两种跃迁都计入交付', delivered.length === 2, `实际 ${delivered.length}`);
  check('无跃迁不算推进', started.length === 0);

  const base2 = parseLedger(row('9.2.1.1', '订单变更取消', TODO));
  const head2 = parseLedger(row('9.2.1.1', '订单变更取消', WIP));
  const d2 = diffLedger(base2, head2);
  check('未开发 → 进行中 = 新增推进', d2.started.length === 1 && d2.delivered.length === 0);

  const same = diffLedger(head, head);
  check('无变化时两侧都为空', same.delivered.length === 0 && same.started.length === 0);
}

// ── 4. 按卷分组 ───────────────────────────────────────────────
{
  const items = [
    { id: '9.2.1.1', name: '订单变更取消' },
    { id: '9.3.1.1', name: '发货拦截' },
    { id: '11.2.1.1', name: '退货授权单' },
  ];
  const names = new Map([
    ['09', '订单与履约系统'],
    ['11', '售后与退货系统'],
  ]);
  const groups = groupByVolume(items, names);
  check('分成两个卷', groups.length === 2, `实际 ${groups.length}`);
  check(
    '卷号升序',
    groups[0].volume === '9' && groups[1].volume === '11',
    `${groups[0].volume} / ${groups[1].volume}`,
  );
  check('卷名带出', groups[0].name === '订单与履约系统');
  check('组内成员齐全', groups[0].list.length === 2);
}

// ── 5. 候选标题规则（只讲功能，不讲工程） ─────────────────────
{
  const one = suggestTitle({ delivered: [{ id: '1.1.1.1', name: '退货授权单' }], started: [] });
  check('单项交付标题', one === 'feat: 退货授权单交付', one);

  const two = suggestTitle({
    delivered: [
      { id: '9.2.1.1', name: '订单变更取消' },
      { id: '11.2.1.1', name: '退货授权单' },
    ],
    started: [],
  });
  check('两项交付标题', two === 'feat: 订单变更取消与退货授权单交付', two);

  const many = suggestTitle({
    delivered: [
      { id: '1.1.1.1', name: '甲' },
      { id: '2.1.1.1', name: '乙' },
      { id: '3.1.1.1', name: '丙' },
    ],
    started: [],
  });
  check('多项交付标题带数量', many === 'feat: 甲、乙等 3 项能力交付', many);

  const empty = suggestTitle({ delivered: [], started: [] });
  check('无增量标题退化为 chore', empty.startsWith('chore:'), empty);

  const forbidden = ['提交', '文件', 'commit', 'branch', 'merge-union', 'sync.mjs'];
  for (const t of [one, two, many, empty]) {
    const bad = forbidden.filter((w) => t.includes(w));
    check(`标题不含工程词：${t}`, bad.length === 0, `命中 ${bad.join(',')}`);
  }
}

// ── 6. 读不到台账必须响亮失败（不许静默当成「无增量」） ──────────
// 背景：台账含证据列，实测已到 1.2MB，超过 Node 默认 1MB 缓冲时会抛 ENOBUFS；
// 若把读失败吞成空表，「读不到 head」就会退化成「head 里没有新交付」。
{
  let threw = false;
  try {
    readLedgerAt('refs/heads/definitely-not-a-real-ref-xyz');
  } catch {
    threw = true;
  }
  check('ref 不存在时抛错而不是静默返回空表', threw);
}

// ── 7. 真实仓库跑一遍（本仓库的真实台账与真实 ref） ───────────
{
  // 台账恒为 465 行。这条断言同时守住 ENOBUFS 回归：
  // 台账已超过 1MB（Node 默认缓冲），一旦 maxBuffer 被调小，这里会直接读空/抛错。
  try {
    const head = readLedgerAt('upstream/integration/module-dev');
    check('真实 ref 的台账解析出 465 行', head.size === 465, `实际 ${head.size}`);
  } catch (err) {
    check('真实 ref 的台账解析出 465 行', false, String(err.message ?? err));
  }

  try {
    const out = execFileSync(
      'node',
      [
        join(ROOT, 'tools', 'module-progress', 'merge-summary.mjs'),
        '--base',
        'upstream/main',
        '--head',
        'upstream/integration/module-dev',
      ],
      { cwd: ROOT, encoding: 'utf8' },
    );
    check('真实运行退出码为 0', true);
    check('输出含增量标题', out.includes('建议标题：'), out.slice(0, 200));
    check('输出含增量分区', out.includes('=== 功能增量'), out.slice(0, 200));
  } catch (err) {
    check('真实运行退出码为 0', false, String(err.stderr ?? err));
  }
}

if (failures > 0) {
  console.log(`\n✗ ${failures} 条断言失败`);
  process.exit(1);
}
console.log('\n✓ 全部通过');
