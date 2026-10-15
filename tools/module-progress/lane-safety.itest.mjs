#!/usr/bin/env node
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const scratch = join(here, '../../.tmp');
mkdirSync(scratch, { recursive: true });
const root = mkdtempSync(join(scratch, 'lane-safety-'));
const git = (...args) =>
  execFileSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
const write = (path, content) => {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), content);
};
const run = (...args) =>
  spawnSync(process.execPath, [join(here, 'lane.mjs'), ...args], {
    cwd: root,
    env: { ...process.env, LANE_ROOT: root },
    encoding: 'utf8',
  });
try {
  git('init', '-q', '-b', 'main');
  git('config', 'user.name', 'Workflow test');
  git('config', 'user.email', 'test@starcon.local');
  write('.gitignore', '.dev-agent/\n');
  write('backend/services/catalog/pom.xml', '<project/>\n');
  write(
    'docs/product/capability-blueprint/README.md',
    '| 03 | catalog | core | test | catalog | link |\n',
  );
  write('docs/product/capability-blueprint/03-catalog.md', '# Catalog\n');
  write(
    'docs/product/capability-blueprint/module-progress.md',
    [
      '| 3.1.1.2 | shared L3 | /a | D3 | 未开发 | — |',
      '| 3.2.1.1 | independent | /b | D3 | 未开发 | — |',
      '',
    ].join('\n'),
  );
  git('add', '.');
  git('commit', '-qm', 'base');
  const base = git('rev-parse', 'HEAD');
  // Failed dry run must not manufacture an integration branch.
  assert.notEqual(run('plan', '--lanes', '1', '--dry-run').status, 0);
  assert.equal(git('branch', '--list', 'integration/module-dev'), '');
  git('branch', 'integration/module-dev');
  const statePath = '.dev-agent/lanes.json';
  const state = {
    integrationBranch: 'integration/module-dev',
    lanes: {
      '3.1.1.1': {
        id: '3.1.1.1',
        state: 'running',
        heartbeatAt: new Date().toISOString(),
        migrationVersion: 'V7',
      },
      old: { id: '3.9.1.1', state: 'merged', migrationVersion: 'V99' },
    },
  };
  write(statePath, JSON.stringify(state));
  const before = readFileSync(join(root, statePath), 'utf8');
  for (const status of ['running', 'ready', 'conflict']) {
    state.lanes['3.1.1.1'].state = status;
    write(statePath, JSON.stringify(state));
    const result = run('plan', '--lanes', '2', '--dry-run');
    assert.equal(result.status, 0, result.stderr);
    const output = JSON.parse(result.stdout);
    assert.deepEqual(output.newlyPlanned, ['3.2.1.1'], `${status} L3 must remain exclusive`);
    assert.equal(
      output.toDispatch[0].migrationVersion,
      'V100',
      'never recycle a recorded migration number',
    );
    assert.equal(
      readFileSync(join(root, statePath), 'utf8'),
      JSON.stringify(state),
      'dry run must not write state',
    );
  }
  assert.equal(git('branch', '--list', 'feat/*'), '', 'dry run must not create worker branches');
  write(statePath, '{broken');
  assert.notEqual(run('plan', '--lanes', '1', '--dry-run').status, 0);
  assert.equal(
    readFileSync(join(root, statePath), 'utf8'),
    '{broken',
    'corrupt state must not be silently replaced',
  );
  write(statePath, before);
  for (const invalid of ['-1', 'NaN', '1.5', 'Infinity']) {
    assert.notEqual(run('plan', '--lanes', invalid, '--dry-run').status, 0, invalid);
  }
  // Legacy short base SHA, clean worktree, no commit: this used to pass land.
  state.lanes = {
    '3.1.1.1': { id: '3.1.1.1', state: 'running', worktree: root, baseSha: base.slice(0, 7) },
  };
  write(statePath, JSON.stringify(state));
  assert.notEqual(run('land', '--lane', '3.1.1.1').status, 0);
  assert.equal(JSON.parse(readFileSync(join(root, statePath))).lanes['3.1.1.1'].state, 'running');
  write('behavior.txt', 'new behavior\n');
  git('add', '.');
  git('commit', '-qm', 'behavior');
  const landed = run('land', '--lane', '3.1.1.1');
  assert.equal(landed.status, 0, landed.stderr);
  assert.equal(JSON.parse(readFileSync(join(root, statePath))).lanes['3.1.1.1'].state, 'ready');
  const wt = join(root, '.worktrees', '3.1.1.1');
  git('worktree', 'add', '-q', wt, '-b', 'feature');
  state.lanes['3.1.1.1'].state = 'abandoned';
  write(statePath, JSON.stringify(state));
  assert.deepEqual(JSON.parse(run('gc').stdout).removed, [], 'abandoned work must survive gc');
  state.lanes['3.1.1.1'].state = 'merged';
  write(statePath, JSON.stringify(state));
  assert.deepEqual(
    JSON.parse(run('gc').stdout).skipped,
    ['3.1.1.1'],
    'unmerged HEAD must survive gc',
  );
  git('update-ref', 'refs/heads/integration/module-dev', git('rev-parse', 'HEAD'));
  writeFileSync(join(wt, 'uncommitted.txt'), 'keep me');
  assert.deepEqual(
    JSON.parse(run('gc').stdout).skipped,
    ['3.1.1.1'],
    'dirty worktree must survive gc',
  );
  rmSync(join(wt, 'uncommitted.txt'));
  assert.deepEqual(
    JSON.parse(run('gc').stdout).removed,
    ['3.1.1.1'],
    'clean integrated worktree may be collected',
  );
  console.log(
    'PASS: L3 ownership, migration reservation, dry-run safety, input validation, and real-commit land',
  );
} finally {
  rmSync(root, { recursive: true, force: true });
}
