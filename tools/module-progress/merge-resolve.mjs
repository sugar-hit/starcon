#!/usr/bin/env node
/**
 * 冲突解法判定器：给每个冲突文件一个**有证据的**解法，而不是让人眼判断
 *
 * 背景（本仓库特有）：
 *   `main` 是用 **squash** 合并更新的，所以 `main` 的文件版本来自集成分支的**某个较早提交**，
 *   而不是集成分支的当前 tip。于是「main vs 集成分支」多数时候是**线性演进**，
 *   不是双向分叉 —— 这时候「两侧都求并」是**错的**（会把旧内容重新塞回去），
 *   正确解法是取集成分支侧。
 *
 * 判据（按顺序）：
 *   1. **祖先快照判定**：main 侧该文件的 blob，是否出现在集成分支该路径的历史里？
 *      - 是 ⇒ main 是旧版本，取集成分支侧（不丢内容）。
 *      - 否 ⇒ main 有集成分支从未有过的内容，**必须并入**，不许直接取一边。
 *   2. **累积型文件三件套**（白名单这类「只增不减」的文件）额外要求：
 *      - 集合包含：旧侧每一条都在新侧
 *      - 有序子序列：旧侧的顺序在新侧保持（first-match-wins 的顺序敏感）
 *      - 零删除：新旧之间没有删除行
 *      三件套同时成立 ⇒ 「取新侧」在结果上等同于完整求并。
 *
 * 用法：
 *   node tools/module-progress/merge-resolve.mjs --base upstream/main --head upstream/integration/module-dev
 *   node tools/module-progress/merge-resolve.mjs --base upstream/main --head upstream/integration/module-dev --json
 *
 * 退出码：0 = 所有冲突都有可机械执行的解法；1 = 存在必须人工并入的文件。
 */

import { execFileSync } from 'node:child_process';

/** 跑一条 git 命令 */
function git(args, { allowFail = false, cwd } = {}) {
  try {
    return execFileSync('git', args, {
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      ...(cwd ? { cwd } : {}),
    });
  } catch (err) {
    // ⚠️ `git merge-tree --write-tree` **有冲突时退出码是 1**，但 stdout 里正是我们要的冲突清单。
    // 所以失败时要把 stdout 带出来，不能当成空输出。
    if (allowFail) return err.stdout ?? '';
    throw new Error(`git ${args.join(' ')} 失败：${err.message?.split('\n')[0] ?? err}`);
  }
}

/**
 * 列出两个 ref 之间的冲突文件。
 *
 * `git merge-tree --write-tree --name-only` 的输出形态：
 *   第 1 行        merge 后的 tree hash
 *   第 2..N 行     冲突文件路径
 *   （空行）        分隔
 *   之后           `Auto-merging` / `CONFLICT (...)` 的明细说明
 *
 * ⚠️ 合并驱动（contract-union）会把它的提示打到同一个输出流里，
 * 所以不能假设「第一行就是 tree hash」—— 要按「空行或明细行出现即停」来切。
 */
export function mergeConflictFiles(base, head, cwd) {
  const out = git(['merge-tree', '--write-tree', '--name-only', base, head], {
    allowFail: true,
    cwd,
  });
  const lines = out.split('\n');
  const files = [];
  for (let i = 1; i < lines.length; i += 1) {
    const line = lines[i].trim();
    if (!line) break;
    if (line.startsWith('Auto-merging') || line.startsWith('CONFLICT')) break;
    files.push(line);
  }
  return files;
}

/** 取某 ref 上某文件的 blob hash */
export function blobOf(ref, path, cwd) {
  const out = git(['rev-parse', `${ref}:${path}`], { allowFail: true, cwd }).trim();
  return out && !out.includes('fatal') ? out : null;
}

/** 取某 ref 上某路径的全部历史 blob（新的在前） */
export function pathBlobHistory(ref, path, cwd) {
  const commits = git(['log', '--format=%H', ref, '--', path], { allowFail: true, cwd })
    .split('\n')
    .filter(Boolean);
  const seen = new Set();
  const out = [];
  for (const c of commits) {
    const b = blobOf(c, path, cwd);
    if (b && !seen.has(b)) {
      seen.add(b);
      out.push(b);
    }
  }
  return out;
}

/** 取出累积型文件里的条目序列（白名单的 pattern 条目） */
export function extractEntries(text) {
  const out = [];
  for (const line of text.split('\n')) {
    let s = line.trim();
    if (s.endsWith(',')) s = s.slice(0, -1).trimEnd();
    if (s.startsWith('/^') && s.endsWith('$/')) out.push(s);
  }
  return out;
}

/**
 * 累积型文件的三件套校验。
 * 返回 {containment, order, zeroDeletion, allOk}。
 */
export function threePartVerdict(baseText, headText) {
  const a = extractEntries(baseText);
  const b = extractEntries(headText);

  const setB = new Set(b);
  const missing = a.filter((x) => !setB.has(x));

  let i = 0;
  for (const x of b) {
    if (i < a.length && x === a[i]) i += 1;
  }
  const outOfOrder = a.length - i;

  // 零删除：按行比较，旧侧每一行都要在新侧里出现
  const linesB = new Set(headText.split('\n').map((l) => l.trimEnd()));
  const removed = baseText
    .split('\n')
    .map((l) => l.trimEnd())
    .filter((l) => l.trim() !== '' && !linesB.has(l));

  return {
    baseCount: a.length,
    headCount: b.length,
    containment: missing.length === 0,
    missingCount: missing.length,
    order: outOfOrder === 0,
    outOfOrderCount: outOfOrder,
    zeroDeletion: removed.length === 0,
    removedCount: removed.length,
    allOk: missing.length === 0 && outOfOrder === 0 && removed.length === 0,
  };
}

/**
 * 判定单个冲突文件的解法。
 *
 * @returns {{path, kind, recommendation, reason, evidence}}
 */
export function classifyFile({ base, head, path, mergeBase, cwd }) {
  const baseBlob = blobOf(base, path, cwd);
  const headBlob = blobOf(head, path, cwd);
  const history = pathBlobHistory(head, path, cwd);

  // 冲突类型看的是**共同祖先**（merge base）上有没有这个文件：
  //   共同祖先里有 ⇒ content（两侧都改过同一处）
  //   共同祖先里没有 ⇒ add/add（两侧各自新增，git 没有可对齐的祖先）
  const baseAtMergeBase = mergeBase ? blobOf(mergeBase, path, cwd) : null;
  const kind =
    baseAtMergeBase === null
      ? 'add/add（两侧各自新增，无共同祖先）'
      : 'content（共同祖先里有这个文件，两侧都改过）';

  const isAncestorSnapshot = baseBlob !== null && history.includes(baseBlob);
  const index = isAncestorSnapshot ? history.indexOf(baseBlob) + 1 : -1;

  const baseText = baseBlob ? git(['show', `${base}:${path}`], { allowFail: true, cwd }) : '';
  const headText = headBlob ? git(['show', `${head}:${path}`], { allowFail: true, cwd }) : '';
  const cumulative = extractEntries(baseText).length + extractEntries(headText).length >= 6;
  const verdict = cumulative ? threePartVerdict(baseText, headText) : null;

  if (!isAncestorSnapshot) {
    return {
      path,
      kind,
      recommendation: '并入（人工）',
      reason:
        'main 侧的文件版本不在集成分支的历史里 —— main 有集成分支从未有过的内容，直接取一边会丢东西',
      evidence: {
        baseBlob,
        headBlob,
        ancestorSnapshot: false,
        cumulativeCheck: verdict,
      },
    };
  }

  if (verdict && !verdict.allOk) {
    return {
      path,
      kind,
      recommendation: '并入（人工）',
      reason: '累积型文件的三件套未全部成立（可能有条目丢失或顺序被打乱），必须人工求并',
      evidence: {
        baseBlob,
        headBlob,
        ancestorSnapshot: true,
        ancestorIndex: index,
        ancestorVersions: history.length,
        cumulativeCheck: verdict,
      },
    };
  }

  return {
    path,
    kind,
    recommendation: '取集成分支侧',
    reason: verdict
      ? 'main 侧是集成分支历史上的祖先快照，且累积型三件套全部成立 ⇒ 取新侧在结果上等同于完整求并'
      : 'main 侧是集成分支历史上的祖先快照 ⇒ 两侧是线性演进，取新侧不丢内容',
    evidence: {
      baseBlob,
      headBlob,
      ancestorSnapshot: true,
      ancestorIndex: index,
      ancestorVersions: history.length,
      cumulativeCheck: verdict,
    },
  };
}

/** 主流程 */
export function main(argv = process.argv.slice(2)) {
  const args = { base: 'upstream/main', head: 'upstream/integration/module-dev', json: false };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--base') args.base = argv[++i];
    else if (argv[i] === '--head') args.head = argv[++i];
    else if (argv[i] === '--json') args.json = true;
  }

  const cwd = process.cwd();
  const files = mergeConflictFiles(args.base, args.head, cwd);
  const mergeBase =
    git(['merge-base', args.base, args.head], { allowFail: true, cwd }).trim() || null;
  const results = files.map((path) =>
    classifyFile({ base: args.base, head: args.head, path, mergeBase, cwd }),
  );
  const needHuman = results.filter((r) => r.recommendation.startsWith('并入'));

  if (args.json) {
    console.log(
      JSON.stringify(
        { base: args.base, head: args.head, results, needHuman: needHuman.length },
        null,
        2,
      ),
    );
  } else {
    console.log(`冲突文件 ${results.length} 个（${args.base} → ${args.head}）`);
    console.log('');
    for (const r of results) {
      console.log(`[${r.recommendation}] ${r.path}`);
      console.log(`    冲突类型：${r.kind}`);
      console.log(`    依据：${r.reason}`);
      const c = r.evidence.cumulativeCheck;
      if (c) {
        console.log(
          `    累积型三件套：集合包含 ${c.containment ? '✓' : '✗'} · 有序子序列 ${c.order ? '✓' : '✗'} · 零删除 ${c.zeroDeletion ? '✓' : '✗'}` +
            `（条目 ${c.baseCount} → ${c.headCount}）`,
        );
      }
      console.log('');
    }
    console.log(
      needHuman.length
        ? `结论：${needHuman.length} 个文件必须人工并入，其余按上面标注机械执行。`
        : '结论：所有冲突都有可机械执行的解法，按上面标注执行即可。',
    );
  }

  process.exit(needHuman.length ? 1 : 0);
}

const invokedDirectly = process.argv[1] && new URL(import.meta.url).pathname === process.argv[1];
if (invokedDirectly) main();
