#!/usr/bin/env node
/**
 * merge-union 驱动器的真实 git 集成测试
 *
 * 为什么不用临时文件直接调函数：真实场景是 git 以 %O/%A/%B 三个路径调用驱动器，
 * 且这三个路径在仓库根下、可能涉及同一目录。直接调函数验证不了「git 会不会真的
 * 用这个驱动器、有没有静默失败」。所以这里起一个真的临时仓库，走真的 git merge。
 *
 * 输出全绿才算通过；任何一条断言失败就退出码 1。
 */

import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'merge-driver-itest');
const DRIVER = join(ROOT, 'tools', 'module-progress', 'merge-union.mjs');

const g = (args, cwd) => execFileSync('git', args, { cwd, encoding: 'utf8' });

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail && !ok ? `\n    ${detail}` : ''}`);
  if (!ok) failures += 1;
};

if (existsSync(SANDBOX)) rmSync(SANDBOX, { recursive: true });
mkdirSync(SANDBOX, { recursive: true });

g(['init', '-q', '-b', 'main'], SANDBOX);
g(['config', 'user.email', 'itest@local'], SANDBOX);
g(['config', 'user.name', 'itest'], SANDBOX);
g(['config', 'merge.contract-union.driver', `node ${DRIVER} %O %A %B`], SANDBOX);

// 声明三份生成物走并集驱动器
writeFileSync(
  join(SANDBOX, '.gitattributes'),
  'packages/api-contracts/src/*.ts merge=contract-union\n' +
    'packages/api-contracts/src/*.openapi.json merge=contract-union\n' +
    'docs/product/capability-blueprint/module-progress.md merge=contract-union\n',
);

const ledger = (id, name, status) => `| ${id} | ${name} | /x | D1–D2 | ${status} | 证据 |\n`;
const LEDGER_ROW = /^\| (\d+\.\d+\.\d+\.\d+) \|/;

mkdirSync(join(SANDBOX, 'packages/api-contracts/src'), { recursive: true });
mkdirSync(join(SANDBOX, 'docs/product/capability-blueprint'), { recursive: true });

writeFileSync(
  join(SANDBOX, 'packages/api-contracts/src/catalog.ts'),
  'export type Alpha = { a: string };\nexport type Shared = { v: 1 };\n',
);
writeFileSync(
  join(SANDBOX, 'packages/api-contracts/src/catalog.openapi.json'),
  `${JSON.stringify({ paths: { '/alpha': { get: {} } }, components: { schemas: { Alpha: {} } } }, null, 2)}\n`,
);
writeFileSync(
  join(SANDBOX, 'docs/product/capability-blueprint/module-progress.md'),
  ledger('3.5.2.2', 'BOM 展开', '未开发') + ledger('3.6.1.1', '往来单位档案', '未开发'),
);
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', '基线'], SANDBOX);

// 车道 A：加自己的类型 / 端点 / 改自己那一行台账
g(['checkout', '-qb', 'lane-a'], SANDBOX);
writeFileSync(
  join(SANDBOX, 'packages/api-contracts/src/catalog.ts'),
  'export type Alpha = { a: string };\nexport type Shared = { v: 1 };\nexport type FromA = { a: string };\n',
);
const specA = JSON.parse(
  readFileSync(join(SANDBOX, 'packages/api-contracts/src/catalog.openapi.json'), 'utf8'),
);
specA.paths['/from-a'] = { get: {} };
specA.components.schemas.FromA = {};
writeFileSync(
  join(SANDBOX, 'packages/api-contracts/src/catalog.openapi.json'),
  `${JSON.stringify(specA, null, 2)}\n`,
);
writeFileSync(
  join(SANDBOX, 'docs/product/capability-blueprint/module-progress.md'),
  ledger('3.5.2.2', 'BOM 展开', '已交付') + ledger('3.6.1.1', '往来单位档案', '未开发'),
);
g(['commit', '-qam', '车道 A 交付 3.5.2.2'], SANDBOX);

// 车道 B：从同一起点切出，加自己的类型 / 端点 / 改另一行台账
g(['checkout', '-q', 'main'], SANDBOX);
g(['checkout', '-qb', 'lane-b'], SANDBOX);
writeFileSync(
  join(SANDBOX, 'packages/api-contracts/src/catalog.ts'),
  'export type Alpha = { a: string };\nexport type Shared = { v: 2 };\nexport type FromB = { b: string };\n',
);
const specB = JSON.parse(
  readFileSync(join(SANDBOX, 'packages/api-contracts/src/catalog.openapi.json'), 'utf8'),
);
specB.paths['/from-b'] = { get: {} };
specB.components.schemas.FromB = {};
writeFileSync(
  join(SANDBOX, 'packages/api-contracts/src/catalog.openapi.json'),
  `${JSON.stringify(specB, null, 2)}\n`,
);
writeFileSync(
  join(SANDBOX, 'docs/product/capability-blueprint/module-progress.md'),
  ledger('3.5.2.2', 'BOM 展开', '未开发') + ledger('3.6.1.1', '往来单位档案', '已交付'),
);
g(['commit', '-qam', '车道 B 交付 3.6.1.1'], SANDBOX);

// 关键一仗：两条车道改的是同一批生成物，合到一起必须零冲突且两边成果都在
g(['checkout', '-q', 'main'], SANDBOX);
let mergeFailed = false;
try {
  g(['merge', '--no-ff', '-m', 'merge lane-a', 'lane-a'], SANDBOX);
  g(['merge', '--no-ff', '-m', 'merge lane-b', 'lane-b'], SANDBOX);
} catch {
  mergeFailed = true;
}
check('两条车道改同一批生成物，git merge 零冲突', !mergeFailed);

if (!mergeFailed) {
  const ts = readFileSync(join(SANDBOX, 'packages/api-contracts/src/catalog.ts'), 'utf8');
  check('契约 TS 同时保住 FromA', /^export type FromA =/m.test(ts), ts);
  check('契约 TS 同时保住 FromB', /^export type FromB =/m.test(ts), ts);
  check('同名类型 Shared 以较新的 lane-b 为准', /Shared = \{ v: 2 \}/.test(ts), ts);
  check('同名类型不重复定义', (ts.match(/export type Shared/g) ?? []).length === 1, ts);

  const spec = JSON.parse(
    readFileSync(join(SANDBOX, 'packages/api-contracts/src/catalog.openapi.json'), 'utf8'),
  );
  check(
    'OpenAPI paths 同时保住 /from-a',
    Boolean(spec.paths['/from-a']),
    JSON.stringify(spec.paths),
  );
  check(
    'OpenAPI paths 同时保住 /from-b',
    Boolean(spec.paths['/from-b']),
    JSON.stringify(spec.paths),
  );
  check(
    'OpenAPI schemas 同时保住 Alpha/FromA/FromB',
    Boolean(
      spec.components.schemas.Alpha &&
      spec.components.schemas.FromA &&
      spec.components.schemas.FromB,
    ),
    JSON.stringify(spec.components.schemas),
  );

  const led = readFileSync(
    join(SANDBOX, 'docs/product/capability-blueprint/module-progress.md'),
    'utf8',
  );
  check(
    '台账保住 3.5.2.2 已交付（lane A 的改动）',
    /\| 3\.5\.2\.2 \| BOM 展开 \| \/x \| D1–D2 \| 已交付 \|/.test(led),
    led,
  );
  check(
    '台账保住 3.6.1.1 已交付（lane B 的改动）',
    /\| 3\.6\.1\.1 \| 往来单位档案 \| \/x \| D1–D2 \| 已交付 \|/.test(led),
    led,
  );
  // 回归：按整行去重会让同 ID 的两行都留下，sync.mjs 后写覆盖会把
  // 已交付读成未开发 → plan 重派 → 撞已占用的 Flyway 版本号。
  // 这三条断言 2026-10-07 加：原断言只验「好行在不在」，从不验「坏行没了没」。
  const rowIds = led
    .split('\n')
    .filter((l) => LEDGER_ROW.test(l))
    .map((l) => l.split('|')[1].trim());
  const dupIds = rowIds.filter((id, i) => rowIds.indexOf(id) !== i);
  check('台账同一模块 ID 不出现重复行', dupIds.length === 0, `重复: ${[...new Set(dupIds)].join(' ')}`);
  check(
    '台账不残留车道侧的未开发占位行（3.5.2.2 / 3.6.1.1）',
    !/\| 3\.5\.2\.2 \| BOM 展开 \| \/x \| D1–D2 \| 未开发 \|/.test(led) &&
      !/\| 3\.6\.1\.1 \| 往来单位档案 \| \/x \| D1–D2 \| 未开发 \|/.test(led),
    led,
  );
  check('台账行数等于模块数（无多余行）', rowIds.length === 2, `实际 ${rowIds.length} 行`);
}

// 关键二仗：纯业务文件（不走驱动器）必须正常冲突，不能被并集吃掉
writeFileSync(join(SANDBOX, 'app.ts'), 'line1\n');
g(['add', '-A'], SANDBOX);
g(['commit', '-qm', '业务文件基线'], SANDBOX);
g(['checkout', '-qb', 'lane-c', 'main'], SANDBOX);
writeFileSync(join(SANDBOX, 'app.ts'), 'line1\nlineC\n');
g(['commit', '-qam', 'C 改 app.ts'], SANDBOX);
g(['checkout', '-q', 'main'], SANDBOX);
writeFileSync(join(SANDBOX, 'app.ts'), 'line1\nlineMain\n');
g(['commit', '-qam', 'main 改 app.ts'], SANDBOX);
let businessConflict = false;
try {
  g(['merge', '-m', 'merge lane-c', 'lane-c'], SANDBOX);
} catch {
  businessConflict = true;
}
const conflicted = businessConflict ? g(['diff', '--name-only', '--diff-filter=U'], SANDBOX) : '';
check(
  '普通业务文件仍然正常报冲突（不被并集误吞）',
  businessConflict && conflicted.includes('app.ts'),
  `conflicted=${conflicted}`,
);

console.log(failures === 0 ? '\n全部通过' : `\n${failures} 条断言失败`);
process.exit(failures === 0 ? 0 : 1);
