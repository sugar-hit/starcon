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
  // --- catalog layer: 4.2.2.2 description-templates appended to the SAME
  //     alternation as the pre-existing catalog routes (descriptions / titles /
  //     products / ...). This is the known trap: taking the wrong side of a
  //     merge silently 404s an ALREADY DELIVERED catalog route while
  //     typecheck / lint / vitest / Playwright all stay green, because the
  //     allowlist only takes effect in the browser.
  //
  //     So the pre-existing catalog paths are re-asserted below as regression
  //     guards, and the BLOCK list proves the regex still rejects instead of
  //     degenerating into "allow everything".
  const CATALOG_ALLOW = [
    // 4.2.2.2 (this lane)
    'description-templates/overview',
    'description-templates/options',
    'description-templates/templates',
    `description-templates/templates/${UUID}`,
    `description-templates/templates/${UUID}/sections`,
    `description-templates/templates/${UUID}/enabled`,
    'description-templates/renders',
    `description-templates/renders/${UUID}`,
    // pre-existing catalog baseline (must not regress)
    'descriptions/overview',
    'descriptions/templates',
    `descriptions/templates/${UUID}`,
    'descriptions/phrases',
    'descriptions/renders',
    `descriptions/renders/${UUID}/skus`,
    'descriptions/sections',
    'titles/overview',
    `titles/templates/${UUID}/enabled`,
    'attributes/overview',
    'media/overview',
  ];

  const CATALOG_BLOCK = [
    // one segment more than exists -> must not look delivered
    `description-templates/templates/${UUID}/bogus-shape`,
    `description-templates/renders/${UUID}/skus`,
    `description-templates/templates/${UUID}/translation-coverage`,
    // singular typo of an existing segment
    'description-template',
    `description-templates/renders/${UUID}/extra`,
  ];

  it.each(CATALOG_ALLOW)('CATALOG ALLOWS %s', async (path) => {
    const res = await callProxy(path, 'catalog');
    expect(res.status, `catalog path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(CATALOG_BLOCK)('CATALOG STILL BLOCKS %s', async (path) => {
    const res = await callProxy(path, 'catalog');
    expect(res.status, `catalog path ${path} should be blocked`).toBe(404);
  });

  // --- KNOWN PRE-EXISTING DEFECT, owned by 3.4.2.2 (not this lane) -----------
  //
  // The catalog allowlist literally contains the token
  // `translation-coveragetranslations` (a lost `|` from an earlier union), so
  // 3.4.2.2's own delivered route
  //   descriptions/templates/{uuid}/translations
  // is silently 404'd in the browser while typecheck / lint / vitest / Playwright
  // all stay green — the exact failure mode this file exists to catch.
  //
  // It is asserted as BLOCKED here so the defect is recorded in a test instead of
  // living only in someone's head. It is NOT fixed by 4.2.2.2: the `descriptions`
  // branch belongs to 3.4.2.2's lane. When that lane fixes the token, this test
  // goes red and the entry moves to CATALOG_ALLOW.
  const CATALOG_KNOWN_BROKEN = [
    `descriptions/templates/${UUID}/translations`,
  ];

  it.each(CATALOG_KNOWN_BROKEN)(
    'CATALOG KNOWN-BROKEN (3.4.2.2 owns the fix) %s',
    async (path) => {
      const res = await callProxy(path, 'catalog');
      expect(res.status, `${path} was expected to be blocked (known 3.4.2.2 defect)`).toBe(404);
    },
  );

  const WH_BLOCK = ['customs-declarationz', `transit-trackings/${UUID}/bogus-shape`];

  it.each(WH_ALLOW)('WAREHOUSE ALLOWS %s', async (path) => {
    const res = await callProxy(path, 'warehouse');
    expect(res.status, `warehouse path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(WH_BLOCK)('WAREHOUSE STILL BLOCKS %s', async (path) => {
    const res = await callProxy(path, 'warehouse');
    expect(res.status, `warehouse path ${path} should be blocked`).toBe(404);
  });

  // --- channel layer: 10.4.2.1 warehouse-providers appended to the SAME
  //     alternation as the pre-existing channel routes (platforms /
  //     connections / shops / sync-* / oauth). This is the known trap:
  //     a mistake here silently 404s an ALREADY DELIVERED channel route
  //     while typecheck/lint/vitest all stay green, so the pre-existing
  //     channel paths are re-asserted below as regression guards.
  const CH_ALLOW = [
    // 10.4.2.1 (this lane)
    'warehouse-providers',
    'warehouse-providers/connections',
    `warehouse-providers/connections/${UUID}`,
    `warehouse-providers/connections/${UUID}/accounts`,
    `warehouse-providers/connections/${UUID}/sync-paused`,
    'warehouse-providers/sync-runs',
    `warehouse-providers/accounts/${UUID}`,
    `warehouse-providers/accounts/${UUID}/inbound-stock-lines`,
    `warehouse-providers/accounts/${UUID}/sync`,
    `warehouse-providers/accounts/${UUID}/observations`,
    `warehouse-providers/accounts/${UUID}/observations/latest`,
    // pre-existing channel baseline (must not regress)
    'platforms',
    // NOTE: platform code is NOT a uuid — the allowlist uses [A-Za-z0-9_]+ here.
    'platforms/AMAZON/enums',
    `platforms/AMAZON/enums/${UUID}`,
    'connections',
    `connections/${UUID}`,
    `connections/${UUID}/health`,
    'shops',
    `shops/${UUID}/marketplaces`,
    'sync-cursors',
    `sync-cursors/${UUID}/trigger`,
    `sync-runs/${UUID}/complete`,
    'sync-failures/summary',
    `platform-operations/${UUID}/verify`,
    'oauth/sessions',
  ];

  const CH_BLOCK = [
    // one segment more than exists -> must not look delivered
    `warehouse-providers/accounts/${UUID}/observations/latest/extra`,
    `warehouse-providers/connections/${UUID}/accounts/bogus-shape`,
    // singular typo of an existing segment
    'warehouse-provider',
    `warehouse-providers/accountz/${UUID}`,
    // observations are append-only: there is deliberately NO delete surface
    `warehouse-providers/accounts/${UUID}/observations/${UUID}/delete`,
  ];

  it.each(CH_ALLOW)('CHANNEL ALLOWS %s', async (path) => {
    const res = await callProxy(path, 'channel');
    expect(res.status, `channel path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(CH_BLOCK)('CHANNEL STILL BLOCKS %s', async (path) => {
    const res = await callProxy(path, 'channel');
    expect(res.status, `channel path ${path} should be blocked`).toBe(404);
  });
});