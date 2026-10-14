/**
 * 回归测试：merge-ready 遇到冲突时必须 abort 合并，不能把脏树留给下一个 tick。
 *
 * 背景（2026-10-07 一晚实际发生两次）：
 * merge-ready 在 `git merge` 返回非零时只把车道标成 conflict 就 continue，
 * 不做 `git merge --abort`。后果：
 *   MERGE_HEAD 残留 + 主工作区脏 ⇒ 下一个 tick 的 merge-ready 命中
 *   「主工作区不干净，拒绝切分支」直接 exit(1)，并入通道彻底堵死；
 *   同时本循环里紧接着的那条车道会在同一棵冲突树上再 merge，
 *   报出与它自身无关的级联冲突 —— 看起来像两条车道撞了同一个文件，
 *   实际只有第一条真冲突。
 *
 * 断言两条不变式：
 *   ① 合并失败后没有 MERGE_HEAD、没有冲突标记、工作区干净；
 *   ② 下一次 merge-ready 仍能正常推进（不被脏树挡住），且能把新并入的车道标 merged。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const LANE = new URL('./lane.mjs', import.meta.url).pathname;
const git = (cwd, args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
const gitOut = (cwd, args) =>
  spawnSync('git', args, { cwd, encoding: 'utf8' }).stdout.trim();

/** 一个最小但结构完整的仓库：集成分支 + 一条冲突车道 + 一条干净车道 */
function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'lane-abort-'));
  git(dir, ['init', '-q', '-b', 'integration/module-dev']);
  git(dir, ['config', 'user.email', 'test@starcon.local']);
  git(dir, ['config', 'user.name', 'test']);
  // 编排器状态目录本身是 gitignored 的，否则它会污染「工作区是否干净」这个判据
  writeFileSync(join(dir, '.gitignore'), '.dev-agent/\n');

  writeFileSync(join(dir, 'shared.txt'), 'base\n');
  git(dir, ['add', '-A']);
  git(dir, ['commit', '-qm', 'base']);

  // 冲突车道：改了 shared.txt 的同一行
  git(dir, ['checkout', '-q', '-b', 'feat/9.9.9.9']);
  writeFileSync(join(dir, 'shared.txt'), 'lane side\n');
  git(dir, ['add', '-A']);
  git(dir, ['commit', '-qm', 'feat(9.9.9.9)']);

  // 干净车道：只加新文件，不碰 shared.txt
  git(dir, ['checkout', '-q', '-b', 'feat/8.8.8.8', 'integration/module-dev']);
  writeFileSync(join(dir, 'clean-side.txt'), 'ok\n');
  git(dir, ['add', '-A']);
  git(dir, ['commit', '-qm', 'feat(8.8.8.8)']);

  // 集成头也改了 shared.txt ⇒ 与 9.9.9.9 真冲突，但与 8.8.8.8 无关
  git(dir, ['checkout', '-q', 'integration/module-dev']);
  writeFileSync(join(dir, 'shared.txt'), 'integration side\n');
  git(dir, ['add', '-A']);
  git(dir, ['commit', '-qm', 'integration moves on']);

  const lane = (id, branch) => ({
    id,
    name: id,
    stage: 'D4',
    route: '—',
    l3: id.split('.').slice(0, 3).join('.'),
    blueprint: 'docs/product/capability-blueprint/08-warehouse-operations.md',
    branch,
    worktree: join(dir, `.worktrees/${id}`),
    migrationVersion: null,
    state: 'ready',
    headSha: null,
    baseSha: null,
    sessionId: null,
  });
  const state = {
    integrationBranch: 'integration/module-dev',
    lanes: { '9.9.9.9': lane('9.9.9.9', 'feat/9.9.9.9'), '8.8.8.8': lane('8.8.8.8', 'feat/8.8.8.8') },
  };
  mkdirSync(join(dir, '.dev-agent'), { recursive: true });
  writeFileSync(join(dir, '.dev-agent/lanes.json'), JSON.stringify(state, null, 2));
  return dir;
}

const readLanes = (dir) =>
  JSON.parse(execFileSync('cat', [join(dir, '.dev-agent/lanes.json')], { encoding: 'utf8' }));

test('冲突后不留 MERGE_HEAD / 冲突标记 / 脏树', () => {
  const dir = fixture();
  try {
    spawnSync('node', [LANE, 'merge-ready'], { cwd: dir, encoding: 'utf8', env: { ...process.env, LANE_ROOT: dir } });

    assert.equal(gitOut(dir, ['rev-parse', '-q', '--verify', 'MERGE_HEAD']), '', 'MERGE_HEAD 残留');
    assert.equal(git(dir, ['status', '--porcelain']), '', '合并失败后工作区仍脏');
    // git grep 无匹配时 stdout 为空（不是 "0"）
    assert.equal(
      gitOut(dir, ['grep', '-c', '^<<<<<<<', '--', 'shared.txt']),
      '',
      'shared.txt 里留下冲突标记',
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('冲突的那条标 conflict、干净的那条仍能并入 merged（不被脏树挡住、不产生级联假冲突）', () => {
  const dir = fixture();
  try {
    spawnSync('node', [LANE, 'merge-ready'], { cwd: dir, encoding: 'utf8', env: { ...process.env, LANE_ROOT: dir } });

    const lanes = readLanes(dir).lanes;
    assert.equal(lanes['9.9.9.9'].state, 'conflict', '真冲突的车道应标 conflict');
    assert.ok(lanes['9.9.9.9'].conflicts.includes('shared.txt'));
    assert.equal(
      lanes['8.8.8.8'].state,
      'merged',
      '不相关的车道应正常并入 —— 若为 ready/未推进，说明被脏树或残留合并挡住了',
    );
    assert.equal(git(dir, ['status', '--porcelain']), '', '并入后工作区应干净');
    assert.ok(
      git(dir, ['cat-file', '-e', 'integration/module-dev:clean-side.txt']) === '',
      '干净车道的产物应真的进了集成头',
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
