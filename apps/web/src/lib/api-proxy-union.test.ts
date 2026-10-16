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
    return proxy(req, {
      params: Promise.resolve({ path: [service, ...path.split('/')] }),
    } as never);
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
    // 3.7.1.2 (this lane) — 目的地报关版本。This lane appended to the SAME
    // alternation as 4.2.2.2 and as the 3.7.1.1 `customs-attributes` branch
    // that was already here, so all three had to survive the union.
    'customs-destination-versions/effective',
    'customs-destination-versions/requirements',
    `customs-destination-versions/skus/${UUID}`,
    `customs-destination-versions/${UUID}/publish`,
    'customs-declaration/resolve',
    // 3.7.1.1 (this lane) — must not regress across the union either.
    'customs-attributes/profiles',
    `customs-attributes/skus/${UUID}`,
    `customs-attributes/skus/${UUID}/submit`,
    `customs-attributes/skus/${UUID}/review`,
    'customs-attributes/declaration-ready',
  ];

  const CATALOG_BLOCK = [
    // one segment more than exists -> must not look delivered
    `description-templates/templates/${UUID}/bogus-shape`,
    `description-templates/renders/${UUID}/skus`,
    `description-templates/templates/${UUID}/translation-coverage`,
    // singular typo of an existing segment
    'description-template',
    `description-templates/renders/${UUID}/extra`,
    // 3.7.1.2 negative cases: the wildcard destination must NOT become a
    // routable path (the DB rejects '*', so the proxy must reject it too),
    // and one segment more than the controller declares must not pass.
    'customs-destination-versions',
    'customs-destination-versions/*',
    `customs-destination-versions/skus/${UUID}/publish`,
    `customs-destination-versions/${UUID}/withdraw`,
    'customs-declaration',
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
  const CATALOG_KNOWN_BROKEN = [`descriptions/templates/${UUID}/translations`];

  it.each(CATALOG_KNOWN_BROKEN)('CATALOG KNOWN-BROKEN (3.4.2.2 owns the fix) %s', async (path) => {
    const res = await callProxy(path, 'catalog');
    expect(res.status, `${path} was expected to be blocked (known 3.4.2.2 defect)`).toBe(404);
  });

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
    // 10.3.2.1 交运批次 (this lane) — appended to the SAME alternation,
    // not a sibling group, so the channel baseline above stays asserted.
    'handover-batches',
    'handover-batches/capabilities',
    'handover-batches/group-preview',
    'handover-batches/candidates',
    'handover-batches/candidates/sync',
    `handover-batches/${UUID}`,
    `handover-batches/${UUID}/manifest`,
    `handover-batches/${UUID}/timeline`,
    `handover-batches/${UUID}/dispatch`,
    `handover-batches/${UUID}/receipt`,
    `handover-batches/${UUID}/cancel`,
    // 10.1.1.1 承运商列表 (this lane) — appended to the SAME alternation as a
    // sibling branch, so the channel baseline above stays asserted.
    'carriers',
    'carriers/capabilities',
    'carriers/overview',
    'carriers/sync',
    `carriers/${UUID}`,
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
    // --- 10.3.2.1 reverse assertions -------------------------------------
    // Literal sub-paths must NOT be swallowed by the [0-9a-f-]+ id segment,
    // and must not carry any optional tail: a shape that does not exist yet
    // has to 404, otherwise the UI reports a delivered feature that isn't.
    `handover-batches/${UUID}/manifest/extra`,
    `handover-batches/${UUID}/dispatch/extra`,
    `handover-batches/capabilities/extra`,
    'handover-batches/group-preview/extra',
    `handover-batches/candidates/${UUID}`,
    'handover-batches/candidates/extra',
    // handover-batches/candidates/sync is TWO segments; sync must not hang
    // off the bare candidates read shape as an optional action.
    'handover-batches/candidates/sync/extra',
    // singular typo of an existing segment
    'handover-batch',
    'handover-batches/capabilitie',
    // the handover manifest is a legal record: there is deliberately NO
    // delete surface, so a DELETE-shaped path must stay blocked.
    `handover-batches/${UUID}/delete`,
    // 10.3.2.2 (揽收与签收确认) is NOT delivered — must not look routed
    `handover-batches/${UUID}/pickup`,
    `handover-batches/${UUID}/delivery`,
    // --- 10.1.1.1 reverse assertions --------------------------------------
    // 字面子路径不得被 [0-9a-f-]+ id 段吞掉，也不得带任何可选尾巴：
    // 不存在的形状必须 404，否则界面会报出一个其实没交付的功能。
    'carriers/capabilities/extra',
    'carriers/overview/extra',
    'carriers/sync/extra',
    `carriers/${UUID}/extra`,
    // 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制都**未交付** —— 不得看起来已路由
    `carriers/${UUID}/channels`,
    `carriers/${UUID}/transit-times`,
    `carriers/${UUID}/limits`,
    // 单数拼写错误
    'carrier',
    'carriers/capabilitie',
  ];

  it.each(CH_ALLOW)('CHANNEL ALLOWS %s', async (path) => {
    const res = await callProxy(path, 'channel');
    expect(res.status, `channel path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(CH_BLOCK)('CHANNEL STILL BLOCKS %s', async (path) => {
    const res = await callProxy(path, 'channel');
    expect(res.status, `channel path ${path} should be blocked`).toBe(404);
  });

  // --- batch-10 channel union: 10.1.2.1 运费计算器 (integration side) and
  //     10.2.2.2 面单模板 (lane side). Both lanes appended a SEPARATE
  //     services[] element with the same `channel/` prefix, anchored at the
  //     same place (immediately before `prefix: 'trade/'`), so git collides
  //     even though the union is purely additive.
  //
  //     `resolve()` returns on the FIRST matching element, and it iterates the
  //     whole array — so sibling same-prefix elements are independent and a
  //     dropped element 404s its routes while typecheck/lint stay green. That
  //     is what these cases catch.
  //
  //     Paths below are taken verbatim from the generated contract
  //     packages/api-contracts/src/channel.openapi.json, keys
  //     /channel/freight/* and /channel/label-templates/*, with the
  //     `channel/` service prefix stripped (that is what resolve() does).
  const CH10_ALLOW = [
    // 10.1.2.1 运费计算器 — contract keys /channel/freight/*
    // NOTE: `freight/quotes/{uuid}` is in the contract (GET /channel/freight/quotes/{id})
    // but is a KNOWN-BROKEN case below — the regex nests the UUID as a sibling of
    // `quotes`, so it cannot match. Pre-existing, owned by 10.1.2.1.
    'freight/capabilities',
    'freight/estimate',
    'freight/quotes',
    'freight/rate-cards',
    'freight/country-rules',
    // 10.2.2.2 面单模板 — contract keys /channel/label-templates/*
    'label-templates',
    'label-templates/capabilities',
    'label-templates/overview',
    'label-templates/binding',
    `label-templates/${UUID}`,
    `label-templates/${UUID}/status`,
    `label-templates/${UUID}/preview`,
    `label-templates/${UUID}/renders`,
  ];

  const CH10_BLOCK = [
    // 10.1.2.1 explicitly does not route these (own comment block):
    //   freight/compare                      — 10.1.2.2 渠道比价未交付
    //   freight/quotes/{uuid}/settlement      — 结算口径不在本模块
    'freight/compare',
    `freight/quotes/${UUID}/settlement`,
    // 10.2.2.2 explicitly does not route these (own comment block):
    //   label-templates/{uuid}/print   — 8.3.4.2 owns printing
    //   label-templates/{uuid}/deploy  — same
    //   label-templates/resolve        — 运单 ↔ 模板自动选版未做
    `label-templates/${UUID}/print`,
    `label-templates/${UUID}/deploy`,
    'label-templates/resolve',
    // trailing slash: backend has no .../label-templates/ mapping
    'label-templates/',
    // literal sub-paths must not be swallowed by the uuid segment, and must
    // not carry an optional tail
    'label-templates/capabilities/extra',
    'label-templates/binding/extra',
    `label-templates/${UUID}/status/extra`,
    `label-templates/${UUID}/renders/${UUID}`,
    // single-typo of an existing segment
    'label-template',
    'freightz',
    'freight/quotes/not-a-uuid',
  ];

  // --- KNOWN PRE-EXISTING DEFECTS, owned by 10.1.2.1 --------------------------
  //
  // Both were proven PRE-EXISTING by A/B: the identical probe run against
  // `git show integration/module-dev:apps/web/src/lib/api-proxy.ts` fails these
  // two in exactly the same way. They are NOT introduced by this merge, and
  // they are NOT fixed by it either — the `freight` element is reproduced
  // verbatim from the integration head.
  //
  // (a) `freight/quotes/{uuid}` is DELIVERED (contract key
  //     /channel/freight/quotes/{id}, GET) but the regex reads
  //       ^freight(?:\/(?:capabilities|estimate|quotes|rate-cards|...|UUID))?$
  //     where the UUID is a SIBLING of `quotes`, not nested under it, so
  //     `freight/quotes/<uuid>` cannot match. Asserted BLOCKED to record the
  //     defect in a test rather than only in someone's head.
  // (b) bare `freight` is claimed NOT routed by the element's own trailing
  //     comment, but the trailing `?` on the whole group makes it optional,
  //     so `freight` alone IS routed. Asserted ALLOWED to pin actual
  //     behaviour; fixing either is 10.1.2.1's call, not this lane's.
  const CH10_FREIGHT_KNOWN_BROKEN = [`freight/quotes/${UUID}`];

  it.each(CH10_FREIGHT_KNOWN_BROKEN)(
    'CH10 KNOWN-BROKEN (10.1.2.1 owns the fix) %s',
    async (path) => {
      const res = await callProxy(path, 'channel');
      expect(res.status, `${path} was expected to be blocked (pre-existing 10.1.2.1 defect)`).toBe(
        404,
      );
    },
  );

  it('CH10 bare `freight` IS routed (contradicts 10.1.2.1 comment; pinned as-is)', async () => {
    const res = await callProxy('freight', 'channel');
    expect(res.status, 'bare freight routing changed; re-check the 10.1.2.1 regex').not.toBe(404);
  });

  it('CH10_ALLOW is non-empty and unique (guards against a broken list)', () => {
    expect(CH10_ALLOW.length).toBe(13);
    expect(new Set(CH10_ALLOW).size).toBe(CH10_ALLOW.length);
  });

  it.each(CH10_ALLOW)('CH10 CHANNEL ALLOWS %s', async (path) => {
    const res = await callProxy(path, 'channel');
    expect(res.status, `channel path ${path} was blocked (${res.status})`).not.toBe(404);
  });

  it.each(CH10_BLOCK)('CH10 CHANNEL STILL BLOCKS %s', async (path) => {
    const res = await callProxy(path, 'channel');
    expect(res.status, `channel path ${path} should be blocked`).toBe(404);
  });
});
