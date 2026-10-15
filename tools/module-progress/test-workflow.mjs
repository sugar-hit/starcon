#!/usr/bin/env node
// Run fixture repositories sequentially: each test owns its sandbox and exits nonzero on failure.
import { readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const here = dirname(fileURLToPath(import.meta.url));
const tests = readdirSync(here)
  .filter((name) => name.endsWith('.itest.mjs'))
  .sort();
if (!tests.length) throw new Error('No workflow regression tests found');
for (const name of tests) {
  console.log(`\n=== ${name} ===`);
  const result = spawnSync(process.execPath, [join(here, name)], {
    stdio: 'inherit',
    timeout: 120_000,
  });
  if (result.error) console.error(result.error.message);
  if (result.status !== 0) process.exit(1);
}
console.log(`\nWorkflow suites passed: ${tests.length}`);
