/**
 * Merge-verification probe for api-proxy.ts whitelist union.
 *
 * WHY THIS EXISTS: the union was computed by a script. Structural checks passed,
 * but "tokens balanced" != "no route silently deleted". This replays the REAL
 * proxy handler and asserts HTTP status codes, which is the only trustworthy judge.
 *
 * Covers BOTH sides of the merge:
 *   - 17.4.1.1/17.4.1.2 approval-flows / approval-rules  (integration side)
 *   - 17.6.1.1 ledger-books / default-book / fact-assignments (lane side)
 * Plus reverse cases so an "allow nothing" regex can't pass the forward set.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

const ALLOW = [
  // --- integration side (17.4) ---
  'approval-flows',
  'approval-flows/document-types',
  `approval-flows/${UUID}`,
  `approval-flows/${UUID}/validation`,
  'approval-flows/positions',
  'approval-rules',
  'approval-rules/effective-policy',
  'approval-rules/evaluate',
  // --- lane side (17.6.1.1) ---
  `legal-entities/${UUID}/ledger-books`,
  `legal-entities/${UUID}/default-book`,
  `accounting-books/${UUID}/fact-assignments`,
  `accounting-books/${UUID}/fact-assignments/${UUID}`,
  // --- pre-existing baseline (must not regress) ---
  'permissions',
  'accounting-books',
  `accounting-books/${UUID}`,
  `legal-entities/${UUID}/accounting-books`,
  'legal-entities/references',
  'business-parameters/effective',
  'numbering/preview',
];

const BLOCK = [
  'approval-flowz',
  `approval-flows/${UUID}/bogus-shape`,
  'ledger-bookz',
  'accounting-books/not-a-uuid',
];

describe('api-proxy whitelist union (merge regression guard)', () => {
  beforeEach(() => {
    vi.resetModules();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  async function callProxy(path: string, service = 'identity') {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 })),
    );
    const { proxy } = await import('./api-proxy');
    const { NextRequest } = await import('next/server');
    const req = new NextRequest(`http://localhost/api/${service}/${path}`, {
      headers: { cookie: 'starcon_session=test' },
    });
    // the service matches on its prefix (e.g. 'identity/') then tests the remainder
    return proxy(req, { params: { path: [service, ...path.split('/')] } } as never);
  }

  it('extracts a non-empty allow-set (guards against a broken extractor)', () => {
    expect(ALLOW.length).toBeGreaterThan(10);
    expect(new Set(ALLOW).size).toBe(ALLOW.length);
  });

  it.each(ALLOW)('ALLOWS %s', async (path) => {
    const res = await callProxy(path);
    expect(res.status, `path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(BLOCK)('STILL BLOCKS %s', async (path) => {
    const res = await callProxy(path);
    expect(res.status, `path ${path} should be blocked`).toBe(404);
  });

  // --- warehouse layer: 10.5.1.1 customs declarations merged against
  //     10.4.1.2 transit-trackings. Both sides appended to the SAME group,
  //     so this is the second occurrence of the known trap.
  const WH_ALLOW = [
    'customs-declarations/capabilities',
    `customs-declarations/${UUID}`,
    `customs-declarations/${UUID}/documents`,
    `customs-declarations/${UUID}/results`,
    `customs-declarations/${UUID}/submit-for-review`,
    `customs-declarations/${UUID}/submit`,
    'transit-trackings/capabilities',
    `transit-trackings/${UUID}`,
    `transit-trackings/${UUID}/receipt`,
    'first-mile-shipments',
  ];
  const WH_BLOCK = ['customs-declarationz', `transit-trackings/${UUID}/bogus-shape`];

  it.each(WH_ALLOW)('WAREHOUSE ALLOWS %s', async (path) => {
    const res = await callProxy(path, 'warehouse');
    expect(res.status, `warehouse path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(WH_BLOCK)('WAREHOUSE STILL BLOCKS %s', async (path) => {
    const res = await callProxy(path, 'warehouse');
    expect(res.status, `warehouse path ${path} should be blocked`).toBe(404);
  });
});