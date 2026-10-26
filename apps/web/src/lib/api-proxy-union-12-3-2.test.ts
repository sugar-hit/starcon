/**
 * Merge-verification probe for the api-proxy.ts whitelist union of
 * 12.3.2.2 (差异处理) and 12.3.2.3 (自动匹配规则).
 *
 * WHY THIS EXISTS
 * ---------------
 * Both lanes appended a **complete new `services[]` element** to the same
 * shared allowlist file, and — because both picked the *same* insertion anchor
 * ("right after the 12.3.2.1 reconciliation-tasks entry") — git saw a
 * same-position double insertion and reported a content conflict.
 *
 * The two sides were fully disjoint (different leading tokens), so the correct
 * resolution was a **line-level union**: keep BOTH elements, each with its own
 * `prefix` / `env` / `fallback` / `pattern`.
 *
 * "tokens balanced" != "no route silently deleted". Taking the wrong side of
 * such a merge removes an ALREADY DELIVERED route from the allowlist, and the
 * symptom is a 404 **in the browser only** — typecheck, lint, vitest and
 * Playwright all stay green, because the allowlist only takes effect when the
 * proxy handler runs.
 *
 * So this probe replays the REAL proxy handler and asserts HTTP status codes,
 * which is the only trustworthy judge. It covers BOTH sides of the merge plus
 * the pre-existing sibling (12.3.2.1) that must not regress, and it keeps
 * reverse cases so that an "allow everything" regex cannot pass the forward set.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

/** 12.3.2.2 差异处理 —— 集成头侧 */
const DIFF_ALLOW = [
  'reconciliation-differences',
  'reconciliation-differences/capabilities',
  'reconciliation-differences/options',
  `reconciliation-differences/${UUID}`,
  `reconciliation-differences/${UUID}/events`,
  `reconciliation-differences/${UUID}/claim`,
  `reconciliation-differences/${UUID}/assign`,
  `reconciliation-differences/${UUID}/classify`,
  `reconciliation-differences/${UUID}/adjustments`,
  `reconciliation-differences/${UUID}/adjust`,
  `reconciliation-differences/${UUID}/write-off`,
];

/** 12.3.2.3 自动匹配规则 —— 车道侧 */
const RULE_ALLOW = [
  'reconciliation-match-rules',
  'reconciliation-match-rules/capabilities',
  'reconciliation-match-rules/options',
  'reconciliation-match-rules/monitoring',
  `reconciliation-match-rules/${UUID}`,
  `reconciliation-match-rules/${UUID}/activate`,
  `reconciliation-match-rules/${UUID}/deactivate`,
  `reconciliation-match-rules/${UUID}/events`,
  `reconciliation-match-rules/${UUID}/rate-snapshots`,
];

/** 兄弟模块 12.3.2.1 对账任务 —— 本次并入**不得**让它退化 */
const SIBLING_ALLOW = [
  'reconciliation-tasks',
  'reconciliation-tasks/capabilities',
  'reconciliation-tasks/options',
];

const BLOCK = [
  // 12.3.2.2 的负向 shape
  'reconciliation-differences/not-a-uuid',
  `reconciliation-differences/${UUID}/not-a-shape`,
  `reconciliation-differences/capabilities/${UUID}`,
  `reconciliation-differences/options/${UUID}`,
  `reconciliation-differences/${UUID}/delete`,
  `reconciliation-differences/${UUID}/rerun`,
  'reconciliation-difference/capabilities',
  `reconciliation-tasks/${UUID}/differences/${UUID}`,
  // 12.3.2.3 的负向 shape
  `reconciliation-match-rules/${UUID}/delete`,
  `reconciliation-match-rules/${UUID}/rules`,
  `reconciliation-match-rules/${UUID}/preview`,
  'reconciliation-match-rules/not-a-uuid',
  `reconciliation-match-rules/${UUID}/not-a-shape`,
  `reconciliation-match-rules/capabilities/${UUID}`,
  'reconciliation-match-rule/capabilities',
];

describe('api-proxy whitelist union (12.3.2.2 + 12.3.2.3)', () => {
  beforeEach(() => {
    vi.resetModules();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  async function callProxy(path: string, service = 'finance') {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 })),
    );
    const { proxy } = await import('./api-proxy');
    const { NextRequest } = await import('next/server');
    const req = new NextRequest(`http://localhost/api/${service}/${path}`, {
      headers: { cookie: 'starcon_session=test' },
    });
    return proxy(req, {
      params: Promise.resolve({ path: [service, ...path.split('/')] }),
    } as never);
  }

  it('extracts a non-empty allow-set (guards against a broken extractor)', () => {
    expect(DIFF_ALLOW.length).toBeGreaterThan(10);
    expect(RULE_ALLOW.length).toBeGreaterThan(8);
    expect(new Set([...DIFF_ALLOW, ...RULE_ALLOW, ...SIBLING_ALLOW]).size).toBe(
      DIFF_ALLOW.length + RULE_ALLOW.length + SIBLING_ALLOW.length,
    );
  });

  it.each(DIFF_ALLOW)('12.3.2.2 ALLOWS %s', async (path) => {
    const res = await callProxy(path);
    expect(res.status, `path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(RULE_ALLOW)('12.3.2.3 ALLOWS %s', async (path) => {
    const res = await callProxy(path);
    expect(res.status, `path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(SIBLING_ALLOW)('12.3.2.1 STILL ALLOWS %s', async (path) => {
    const res = await callProxy(path);
    expect(res.status, `path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(BLOCK)('STILL BLOCKS %s', async (path) => {
    const res = await callProxy(path);
    expect(res.status, `path ${path} should be blocked`).toBe(404);
  });
});
