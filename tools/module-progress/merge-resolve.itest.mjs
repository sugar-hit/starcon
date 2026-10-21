#!/usr/bin/env node
/**
 * merge-resolve 判定器的集成测试
 *
 * 在真实的小仓库里造出三种冲突形态，验证判据本身：
 *   A. main 侧是集成分支的祖先快照（squash 合入后的常态）⇒ 判「取集成分支侧」
 *   B. main 侧是集成分支从未有过的内容（真分叉）⇒ 判「并入（人工）」
 *   C. 累积型文件的三件套：包含 / 保序 / 零删除，任一不成立都要拦下来
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  threePartVerdict,
  extractEntries,
  mergeConflictFiles,
  classifyFile,
} from './merge-resolve.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'merge-resolve-itest');

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

const g = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8' });

/** 造一个仓库：base 提交 + 两个分支 */
function makeRepo(dir, { mainContent, integrationContent }) {
  if (existsSync(dir)) rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  g(['init', '-q', '-b', 'main'], dir);
  g(['config', 'user.email', 'itest@local'], dir);
  g(['config', 'user.name', 'itest'], dir);

  writeFileSync(join(dir, 'shared.txt'), 'base\n');
  g(['add', '-A'], dir);
  g(['commit', '-q', '-m', 'base'], dir);

  g(['checkout', '-q', '-b', 'integration/module-dev'], dir);
  writeFileSync(join(dir, 'shared.txt'), integrationContent);
  g(['add', '-A'], dir);
  g(['commit', '-q', '-m', 'integration changes shared'], dir);

  g(['checkout', '-q', 'main'], dir);
  writeFileSync(join(dir, 'shared.txt'), mainContent);
  g(['add', '-A'], dir);
  g(['commit', '-q', '-m', 'main changes shared'], dir);
}

// ── 纯函数：三件套 ─────────────────────────────────────────────
{
  const base = ['/^a$/', '/^b$/', '/^c$/'].join('\n');
  const head = ['/^a$/', '/^b$/', '/^c$/', '/^d$/'].join('\n');
  const v = threePartVerdict(base, head);
  check('集合包含成立', v.containment);
  check('有序子序列成立', v.order);
  check('零删除成立', v.zeroDeletion);
  check('三件套全成立', v.allOk);
  check('条目数统计正确', v.baseCount === 3 && v.headCount === 4, `${v.baseCount}/${v.headCount}`);

  const reordered = ['/^b$/', '/^a$/', '/^c$/', '/^d$/'].join('\n');
  const v2 = threePartVerdict(base, reordered);
  check('顺序被打乱时判不成立', !v2.order, `outOfOrder=${v2.outOfOrderCount}`);
  check('顺序乱时三件套整体不成立', !v2.allOk);

  const dropped = ['/^a$/', '/^c$/', '/^d$/'].join('\n');
  const v3 = threePartVerdict(base, dropped);
  check('条目丢失时集合包含不成立', !v3.containment, `missing=${v3.missingCount}`);
  check('条目丢失时三件套整体不成立', !v3.allOk);

  check('累积型条目抽取正确', extractEntries(extra(4)).length === 4);
  check('带结尾逗号的真实写法也能抽取', extractEntries('      /^price-history$/,\n').length === 1);
}

function extra(n) {
  return Array.from({ length: n }, (_, i) => `/^x${i}$/`).join('\n');
}

// ── 形态 A：main 侧是集成分支的祖先快照（squash 合入后的常态）──
{
  const dir = join(SANDBOX, 'ancestor');
  // 集成分支上 shared.txt 演进 v1 → v2；main 侧停留在 v1（v1 是集成分支历史里的 blob）
  makeRepo(dir, { mainContent: 'v1\n', integrationContent: 'v2\n' });
  // 让 main 的 blob 等于集成分支历史上的某个 blob：集成分支先提交 v1 再提交 v2
  g(['checkout', '-q', 'integration/module-dev'], dir);
  writeFileSync(join(dir, 'shared.txt'), 'v1\n');
  g(['add', '-A'], dir);
  g(['commit', '-q', '-m', 'v1'], dir);
  writeFileSync(join(dir, 'shared.txt'), 'v2\n');
  g(['add', '-A'], dir);
  g(['commit', '-q', '-m', 'v2'], dir);

  const base = 'main';
  const head = 'integration/module-dev';
  const mergeBase = g(['merge-base', base, head], dir).trim();

  const files = mergeConflictFiles(base, head, dir);
  check('形态 A：冲突文件被列出来', files.includes('shared.txt'), JSON.stringify(files));

  const shared = classifyFile({ base, head, path: 'shared.txt', mergeBase, cwd: dir });
  check(
    '形态 A：共同祖先里有该文件 ⇒ 判为 content',
    shared.kind.startsWith('content'),
    shared.kind,
  );
  check('形态 A：main 侧是祖先快照', shared.evidence.ancestorSnapshot === true);
  check('形态 A：判取集成分支侧', shared.recommendation === '取集成分支侧', shared.recommendation);
}

// ── 形态 B：main 侧有集成分支从未有过的内容（真分叉）──────────
{
  const dir = join(SANDBOX, 'diverged');
  makeRepo(dir, { mainContent: 'main 独有内容\n', integrationContent: 'integration 独有内容\n' });

  const base = 'main';
  const head = 'integration/module-dev';
  const mergeBase = g(['merge-base', base, head], dir).trim();

  const shared = classifyFile({ base, head, path: 'shared.txt', mergeBase, cwd: dir });
  check('形态 B：main 侧不是祖先快照', shared.evidence.ancestorSnapshot === false);
  check(
    '形态 B：判为必须人工并入',
    shared.recommendation.startsWith('并入'),
    shared.recommendation,
  );
  check('形态 B：给出理由', shared.reason.includes('从未有过'), shared.reason);
}

if (failures > 0) {
  console.log(`\n✗ ${failures} 条断言失败`);
  process.exit(1);
}
console.log('\n✓ 全部通过');
