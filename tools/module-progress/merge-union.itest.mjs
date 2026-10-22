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

import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'merge-driver-itest');
const DRIVER = join(ROOT, 'tools', 'module-progress', 'merge-union.mjs');
const JSON_FORMATTER = join(ROOT, 'tools', 'module-progress', 'json-format.py');

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
  check(
    '台账同一模块 ID 不出现重复行',
    dupIds.length === 0,
    `重复: ${[...new Set(dupIds)].join(' ')}`,
  );
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

// ── 契约字节稳定性：驱动写出的字节必须与 codegen 的输出逐字节一致 ─────────
/*
 * 前面那段走的是「真的 git merge」，验的是**会不会冲突、会不会丢内容**；
 * 这一段直接调驱动器（git 真实调用时就是 %O/%A/%B 三个路径），验的是**写出来的字节长什么样**。
 *
 * 为什么重要：CI 的契约闸是「重新生成后 git diff 必须为空」。
 * 驱动器一旦把横幅写到末行、或把类型排成三方求并的插入序，闸就红 —— 而且每并入一次契约文件都红。
 * 期望值不是「行集合相等」这种弱断言，而是**按生成器格式手工拼出的字节**，逐字符相等。
 */
const BANNER =
  '// Generated by tools/codegen/catalog-contracts.py from Spring OpenAPI. DO NOT EDIT.';

const bytesDir = join(SANDBOX, 'byte-stability');
mkdirSync(bytesDir, { recursive: true });

let driverCalls = 0;
/** 直接调驱动器：%O/%A/%B 三个路径，输出写回 %A。 */
const runDriver = (name, base, ours, theirs) => {
  const dir = join(bytesDir, `${name}-${driverCalls++}`);
  mkdirSync(dir, { recursive: true });
  const baseFile = join(dir, 'base');
  const oursFile = join(dir, 'ours');
  const theirsFile = join(dir, 'theirs');
  writeFileSync(baseFile, base);
  writeFileSync(oursFile, ours);
  writeFileSync(theirsFile, theirs);
  const res = spawnSync('node', [DRIVER, baseFile, oursFile, theirsFile], { encoding: 'utf8' });
  return {
    status: res.status,
    stderr: res.stderr ?? '',
    text: readFileSync(oursFile, 'utf8'),
    file: oursFile,
  };
};

// 用例 1：横幅归首行 + 类型升序 + 与「生成器格式」逐字节相等
// ours 故意打乱顺序且把横幅放在末行 —— 那是驱动器坏版本留下的形状。
const tsBase = `${BANNER}\nexport type Alpha = { a: string };\nexport type Shared = { v: 1 };\n`;
const tsOurs = `export type Shared = { v: 1 };\nexport type Alpha = { a: string };\n${BANNER}\n`;
const tsTheirs = `export type Shared = { v: 1 };\nexport type Alpha = { a: string };\nexport type FromB = { b: string };\n${BANNER}\n`;
const tsExpected = `${BANNER}\nexport type Alpha = { a: string };\nexport type FromB = { b: string };\nexport type Shared = { v: 1 };\n`;

const r1 = runDriver('ts', tsBase, tsOurs, tsTheirs);
const r1Lines = r1.text.split('\n');
check('契约 TS：横幅在第 1 行', r1Lines[0] === BANNER, `第 1 行 = ${JSON.stringify(r1Lines[0])}`);
check(
  '契约 TS：横幅只出现一次且不在末行',
  r1.text.split(BANNER).length === 2 && !r1Lines.slice(-2).includes(BANNER),
  JSON.stringify(r1.text),
);
const tsNames = r1Lines.filter((l) => l.startsWith('export type ')).map((l) => l.split(' ')[2]);
check(
  '契约 TS：类型按名字升序（输入是故意打乱的）',
  JSON.stringify(tsNames) === JSON.stringify([...tsNames].sort()),
  tsNames.join(' '),
);
check(
  '契约 TS：与「按生成器格式手工拼出的期望字节」严格相等',
  r1.text === tsExpected,
  `实际 ${JSON.stringify(r1.text)} 期望 ${JSON.stringify(tsExpected)}`,
);
check(
  '契约 TS：一个空行都不输出',
  r1.text
    .replace(/\n$/, '')
    .split('\n')
    .every((l) => l !== ''),
  JSON.stringify(r1.text),
);

// 用例 2：幂等 —— 输出再喂回驱动器、连续求并两次，结果都必须不变
const r2 = runDriver('ts-idem', tsBase, r1.text, r1.text);
check('契约 TS：输出再喂回驱动器后不变（幂等）', r2.text === r1.text, JSON.stringify(r2.text));
const r2b = runDriver('ts-idem2', tsBase, r1.text, tsTheirs);
check('契约 TS：连续求并两次逐字节相同', r2b.text === r1.text, JSON.stringify(r2b.text));

// 用例 3：三方语义没被破坏（base 才是「谁真的改了它」的裁判）
const semBase = `${BANNER}\nexport type Shared = { v: 1 };\nexport type Only = { o: 1 };\n`;
const semOurs = `${BANNER}\nexport type Shared = { v: 2 };\nexport type Only = { o: 1 };\n`;
const semTheirs = `${BANNER}\nexport type Shared = { v: 1 };\nexport type Only = { o: 1 };\n`;
const r3 = runDriver('sem-ours', semBase, semOurs, semTheirs);
check('三方：只有 ours 相对 base 改动 → 取 ours', r3.text.includes('Shared = { v: 2 }'), r3.text);
check('三方：没动过的键保持 base 的值', r3.text.includes('Only = { o: 1 }'), r3.text);
const r4 = runDriver('sem-theirs', semBase, semTheirs, semOurs);
check(
  '三方：只有 theirs 相对 base 改动 → 取 theirs',
  r4.text.includes('Shared = { v: 2 }'),
  r4.text,
);
const r5 = runDriver('sem-conflict', semBase, semOurs, semOurs.replace('v: 2', 'v: 3'));
check('三方：两边都改同一个键 → 取 theirs', r5.text.includes('Shared = { v: 3 }'), r5.text);
check(
  '三方：两边都改同一个键 → CONFLICT_KEYS 报出来',
  /CONFLICT_KEYS 契约:Shared/.test(r5.stderr),
  r5.stderr,
);

// 用例 4：OpenAPI json —— 键序字典序 + 浮点 Python repr + 末尾换行 + 与 json-format.py 逐字节相同
const jsonBase = {
  openapi: '3.0.1',
  paths: { '/a': { get: { operationId: 'get_a' } } },
  components: {
    schemas: {
      Alpha: { type: 'object', properties: { amount: { type: 'number', minimum: 1e-6 } } },
    },
  },
};
const jsonOurs = {
  openapi: '3.0.1',
  paths: { '/a': { get: { operationId: 'get_a' } }, '/z': { get: { operationId: 'get_z' } } },
  components: {
    schemas: {
      Alpha: { type: 'object', properties: { amount: { type: 'number', minimum: 1e-6 } } },
      Zeta: { type: 'object' },
    },
  },
};
const jsonTheirs = {
  openapi: '3.0.1',
  paths: { '/a': { get: { operationId: 'get_a' } }, '/b': { get: { operationId: 'get_b' } } },
  components: {
    schemas: {
      Alpha: { type: 'object', properties: { amount: { type: 'number', minimum: 1e-6 } } },
      Beta: { type: 'object' },
    },
  },
};
// 期望的合并结果（并集语义）：paths 与 components.schemas 各取两边的键。
const jsonMerged = {
  openapi: '3.0.1',
  paths: {
    '/a': { get: { operationId: 'get_a' } },
    '/b': { get: { operationId: 'get_b' } },
    '/z': { get: { operationId: 'get_z' } },
  },
  components: {
    schemas: {
      Alpha: { type: 'object', properties: { amount: { type: 'number', minimum: 1e-6 } } },
      Beta: { type: 'object' },
      Zeta: { type: 'object' },
    },
  },
};
// 生成器写快照用的就是这一个 json.dumps 调用（见 catalog-contracts.py 的 snapshot.write_text），
// json-format.py 与它同款 ⇒ 「与 json-format.py 逐字节相同」= 「与生成器逐字节相同」。
const fmt = spawnSync('python3', [JSON_FORMATTER], {
  input: JSON.stringify(jsonMerged),
  encoding: 'utf8',
});
const jsonExpected = fmt.stdout;

const r6 = runDriver(
  'json',
  `${JSON.stringify(jsonBase, null, 2)}\n`,
  `${JSON.stringify(jsonOurs, null, 2)}\n`,
  `${JSON.stringify(jsonTheirs, null, 2)}\n`,
);
check(
  'OpenAPI json：与 json-format.py 的输出逐字节相同',
  r6.text === jsonExpected,
  `实际 ${JSON.stringify(r6.text.slice(0, 160))}`,
);
check('OpenAPI json：浮点写成 Python repr（1e-06）', r6.text.includes('"minimum": 1e-06'), r6.text);
check('OpenAPI json：不出现 JS 的 0.000001', !r6.text.includes('0.000001'), r6.text);
check('OpenAPI json：末尾有换行', r6.text.endsWith('\n'), JSON.stringify(r6.text.slice(-20)));
const r6Parsed = JSON.parse(r6.text);
const r6Paths = Object.keys(r6Parsed.paths);
const r6Schemas = Object.keys(r6Parsed.components.schemas);
check(
  'OpenAPI json：paths 键序是字典序',
  JSON.stringify(r6Paths) === JSON.stringify([...r6Paths].sort()),
  r6Paths.join(' '),
);
check(
  'OpenAPI json：components.schemas 键序是字典序',
  JSON.stringify(r6Schemas) === JSON.stringify([...r6Schemas].sort()),
  r6Schemas.join(' '),
);

/* ── 用例 5：数值字面量保真（2026-10-23 回归，锁住 batch-68 的契约漂移） ──── */
/*
 * 背景：batch-68 的 L2 契约闸红在「6 个 channel schema 的 minimum 从 0.0 塌成 0」。
 * 根因：驱动器的求并经过 JS，JS 的 JSON 分不清 int 与 float ——
 *   JSON.parse("0.0") 得到的 double，再 JSON.stringify 就写成 "0"；
 *   JSON.parse("1e-06") 再 stringify 就写成 "0.000001"。
 * 于是字面量在 JS 序列化那一步就被抹掉了，Python 再怎么 json.dumps 也救不回来
 * （它拿到的已经是 int 0）。
 *
 * 这个用例的夹具**故意**用裸文本写出 0.0 / 1e-06 / 3.0（不能用 JSON.stringify 生成，
 * 那会把它们提前毁掉），断言驱动器的输出与「同样内容经 codegen 同款 Python 序列化」
 * 的输出**逐字节相同**。
 */

/** 造一份 OpenAPI 文档的**裸文本**（数字字面量原样保留，不经过 JSON.stringify）。 */
const numDoc = (extraPaths, extraSchemas) => {
  const paths = [
    `"/a": {
      "get": {
        "operationId": "getA",
        "parameters": [
          {
            "name": "floor",
            "in": "query",
            "schema": { "type": "number", "exclusiveMinimum": false, "minimum": 0.0 }
          }
        ]
      }
    }`,
    ...extraPaths,
  ];
  const schemas = [
    `"Alpha": {
        "type": "object",
        "properties": {
          "amount": { "type": "number", "minimum": 0.0 },
          "tiny": { "type": "number", "minimum": 1e-06 },
          "whole": { "type": "number", "minimum": 3.0 },
          "count": { "type": "integer", "minimum": 0 }
        }
      }`,
    ...extraSchemas,
  ];
  return `{
  "openapi": "3.0.1",
  "paths": {
    ${paths.join(',\n    ')}
  },
  "components": {
    "schemas": {
      ${schemas.join(',\n      ')}
    }
  }
}`;
};

const numBase = numDoc([], []);
const numOurs = numDoc(
  ['"/z": { "get": { "operationId": "getZ" } }'],
  ['"Zeta": { "type": "object" }'],
);
const numTheirs = numDoc(
  ['"/b": { "get": { "operationId": "getB" } }'],
  ['"Beta": { "type": "object" }'],
);
// 期望的并集结果（paths 与 components.schemas 各取两边的键）。
const numExpectedRaw = numDoc(
  ['"/b": { "get": { "operationId": "getB" } }', '"/z": { "get": { "operationId": "getZ" } }'],
  ['"Beta": { "type": "object" }', '"Zeta": { "type": "object" }'],
);
// 生成器写快照用的就是这一个 json.dumps 调用（见 catalog-contracts.py 的 snapshot.write_text），
// json-format.py 与它同款 ⇒ 「与 json-format.py 逐字节相同」= 「与生成器逐字节相同」。
const numExpected = spawnSync('python3', [JSON_FORMATTER], {
  input: numExpectedRaw,
  encoding: 'utf8',
  maxBuffer: 256 * 1024 * 1024,
}).stdout;

const r7 = runDriver('json-numbers', numBase, numOurs, numTheirs);
check(
  '数值保真：与 codegen 同款 Python 序列化的输出逐字节相同',
  r7.text === numExpected,
  `实际 ${JSON.stringify(r7.text.slice(0, 200))}\n期望 ${JSON.stringify(numExpected.slice(0, 200))}`,
);
check('数值保真：float 0.0 没被塌成 0', r7.text.includes('"minimum": 0.0'), r7.text.slice(0, 400));
check(
  '数值保真：指数记法 1e-06 原样保留',
  r7.text.includes('"minimum": 1e-06'),
  r7.text.slice(0, 400),
);
check('数值保真：float 3.0 没被塌成 3', r7.text.includes('"minimum": 3.0'), r7.text.slice(0, 400));
check('数值保真：int 0 仍然是 0（int/float 没被混为一谈）', r7.text.includes('"minimum": 0,'));
check('数值保真：没出现 JS 的 0.000001', !r7.text.includes('0.000001'), r7.text.slice(0, 400));

// 反向断言：把根因钉在用例里 —— 走 JS 一遍字面量就没了。
// （这两条断言在「修复前」的驱动上会红，正是它们把回归挡住的原因。）
const jsLossyFloat = JSON.stringify(JSON.parse('{"minimum": 0.0}'));
check(
  '反向断言：JS 的 parse+stringify 确实把 0.0 抹成 0（驱动绕开 JS 才能保住）',
  jsLossyFloat === '{"minimum":0}',
  jsLossyFloat,
);
const jsLossyExp = JSON.stringify(JSON.parse('{"minimum": 1e-06}'));
check(
  '反向断言：JS 的 parse+stringify 确实把 1e-06 写成 0.000001',
  jsLossyExp === '{"minimum":0.000001}',
  jsLossyExp,
);

// 类型层面的判定：JS 的 JSON.parse 分不出 int/float，用 Python 判（Python 分得清）。
const jsonTypeOf = (file, dottedPath) => {
  const res = spawnSync(
    'python3',
    [
      '-c',
      'import json,sys\n' +
        'd=json.load(open(sys.argv[1],encoding="utf-8"))\n' +
        'for k in sys.argv[2].split("."): d=d[k]\n' +
        'print(type(d).__name__)',
      file,
      dottedPath,
    ],
    { encoding: 'utf8' },
  );
  return (res.stdout ?? '').trim();
};
const r7File = r7.file;
check(
  '数值保真：amount.minimum 的类型仍是 float（不是 int）',
  jsonTypeOf(r7File, 'components.schemas.Alpha.properties.amount.minimum') === 'float',
  jsonTypeOf(r7File, 'components.schemas.Alpha.properties.amount.minimum'),
);
check(
  '数值保真：count.minimum 的类型仍是 int（没被浮点化）',
  jsonTypeOf(r7File, 'components.schemas.Alpha.properties.count.minimum') === 'int',
  jsonTypeOf(r7File, 'components.schemas.Alpha.properties.count.minimum'),
);
check(
  '数值保真：tiny.minimum 的类型仍是 float',
  jsonTypeOf(r7File, 'components.schemas.Alpha.properties.tiny.minimum') === 'float',
  jsonTypeOf(r7File, 'components.schemas.Alpha.properties.tiny.minimum'),
);

// 三方语义在 int/float 上必须分得清：base 是 0.0，只有 ours 改成 0 ⇒ 取 ours。
const r8 = runDriver(
  'json-num-threeway',
  numDoc('', ''),
  numDoc('', '').replaceAll('"minimum": 0.0', '"minimum": 0'),
  numDoc('', ''),
);
check(
  '数值保真：只有 ours 相对 base 改了 minimum（0.0 → 0）⇒ 取 ours',
  r8.text.includes('"minimum": 0,') && !r8.text.includes('"minimum": 0.0'),
  r8.text.slice(0, 400),
);

/* ── 用例 6：真实契约产物（packages/api-contracts/src/*.openapi.json） ────── */
/*
 * 夹具能证明逻辑对，真实产物能证明「这条路径真的在跑」——
 * channel.openapi.json 有 781 个 path / 1487 个 schema，是驱动真正的使用场景。
 * 断言：驱动处理真实产物后，**所有 minimum 字面量一个都不许变**，
 * 且输出与 codegen 同款序列化的输出逐字节相同。
 */
const REAL_SPEC = join(ROOT, 'packages', 'api-contracts', 'src', 'channel.openapi.json');
if (existsSync(REAL_SPEC)) {
  const realText = readFileSync(REAL_SPEC, 'utf8');
  const realExpected = spawnSync('python3', [JSON_FORMATTER], {
    input: realText,
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
  }).stdout;
  const r9 = runDriver('json-real', realText, realText, realText);
  check(
    '真实契约产物：驱动输出与 codegen 同款序列化逐字节相同',
    r9.text === realExpected,
    `实际长度 ${r9.text.length} 期望长度 ${realExpected.length}`,
  );
  const minLiterals = (text) => text.match(/"minimum": [^,\n]+/g) ?? [];
  const before = minLiterals(realText);
  const after = minLiterals(r9.text);
  check(
    '真实契约产物：所有 minimum 字面量一个都没变',
    JSON.stringify(before) === JSON.stringify(after),
    `变了的：${before
      .filter((v, i) => v !== after[i])
      .slice(0, 5)
      .join(' ')}`,
  );
  check(
    '真实契约产物：float 型 minimum 仍然是 float（不是塌成 int）',
    r9.text.includes('"minimum": 0.0001,') && r9.text.includes('"minimum": 1e-06,'),
    '没找到 0.0001 / 1e-06 字面量',
  );
} else {
  check('真实契约产物：channel.openapi.json 存在（否则本用例无法跑）', false, REAL_SPEC);
}

console.log(failures === 0 ? '\n全部通过' : `\n${failures} 条断言失败`);
process.exit(failures === 0 ? 0 : 1);
