import { describe, expect, it } from 'vitest';
import {
  buildHref,
  countHits,
  domainLabel,
  GLOBAL_SEARCH_TARGETS,
  hitSubtitle,
  mergeServiceResults,
  MIN_KEYWORD_LENGTH,
  narrowerScope,
  normalizeKeyword,
  scopeLabel,
  type GlobalSearchGroup,
  type GlobalSearchHit,
  type GlobalSearchResponse,
  type ServiceSearchOutcome,
} from './global-search-model';

function hit(overrides: Partial<GlobalSearchHit> = {}): GlobalSearchHit {
  return {
    domain: 'PRODUCT',
    kind: 'SKU',
    id: '11111111-1111-1111-1111-111111111111',
    primary: 'SKU-AX-001',
    secondary: '商品名称 · SPU SP-AX-100',
    ...overrides,
  };
}

function group(
  domain: string,
  hits: GlobalSearchHit[],
  scope = 'ALL',
  label?: string,
): GlobalSearchGroup {
  return {
    domain,
    label: label ?? domain,
    hits,
    dataScope: scope,
    dataScopeNote: `${domain} 的数据范围说明`,
  };
}

function response(overrides: Partial<GlobalSearchResponse> = {}): GlobalSearchResponse {
  return {
    keyword: 'SKU-AX',
    matchMode: 'PREFIX_OR_EXACT',
    minKeywordLength: MIN_KEYWORD_LENGTH,
    groups: [],
    unavailableDomains: [],
    suppressedDomains: [],
    ...overrides,
  };
}

function outcome(
  service: string,
  groups: GlobalSearchResponse['groups'],
  unavailable: string[] = [],
  suppressed: GlobalSearchResponse['suppressedDomains'] = [],
): ServiceSearchOutcome {
  return {
    service,
    response: response({ groups, unavailableDomains: unavailable, suppressedDomains: suppressed }),
    error: null,
  };
}

describe('normalizeKeyword', () => {
  it('少于 2 个字符不检索（蓝图写死的约束）', () => {
    expect(normalizeKeyword('S').ok).toBe(false);
    expect(normalizeKeyword('  ').ok).toBe(false);
    expect(normalizeKeyword('').ok).toBe(false);
    expect(normalizeKeyword('S').hint).toContain('2');
  });

  it('去首尾空白后达到 2 个字符即通过', () => {
    expect(normalizeKeyword('  ab  ')).toEqual({ ok: true, keyword: 'ab', hint: '' });
  });
});

describe('mergeServiceResults', () => {
  it('同名域的命中合成一组（商品域同时有 SKU 与 SPU）', () => {
    const merged = mergeServiceResults([
      outcome('catalog', [
        group('PRODUCT', [hit({ kind: 'SKU', id: 'a' })], 'ALL', '商品'),
        group('PRODUCT', [hit({ kind: 'SPU', id: 'b' })], 'ALL', '商品'),
      ]),
    ]);
    expect(merged.groups).toHaveLength(1);
    expect(merged.groups[0]?.hits.map((h) => h.kind)).toEqual(['SKU', 'SPU']);
  });

  it('同一条命中被两个来源同时命中时只留一条', () => {
    const merged = mergeServiceResults([
      outcome('catalog', [group('PRODUCT', [hit({ id: 'same' })], 'ALL', '商品')]),
      outcome('trade', [group('PRODUCT', [hit({ id: 'same' })], 'ALL', '商品')]),
    ]);
    expect(merged.groups[0]?.hits).toHaveLength(1);
  });

  it('组按蓝图的域次序排列，不按服务返回次序', () => {
    const merged = mergeServiceResults([
      outcome('warehouse', [group('LOGISTICS', [hit({ domain: 'LOGISTICS' })], 'TENANT', '物流')]),
      outcome('trade', [group('ORDER', [hit({ domain: 'ORDER' })], 'ALL', '订单')]),
      outcome('catalog', [group('PRODUCT', [hit()], 'ALL', '商品')]),
    ]);
    expect(merged.groups.map((g) => g.domain)).toEqual(['PRODUCT', 'ORDER', 'LOGISTICS']);
  });

  it('没权限的域与失败的服务必须分开报', () => {
    const merged = mergeServiceResults([
      outcome(
        'catalog',
        [group('PRODUCT', [hit()], 'ALL', '商品')],
        ['CUSTOMER'],
        [{ domain: 'CUSTOMER', label: '客户', group: 'CUSTOMER', groupLabel: '客户' }],
      ),
      { service: 'warehouse', response: null, error: '服务暂时不可用' },
    ]);
    expect(merged.unavailableDomains).toEqual(['CUSTOMER']);
    expect(merged.failedServices).toEqual(['warehouse']);
    expect(merged.groups).toHaveLength(1);
    expect(merged.suppressedDomains).toHaveLength(1);
  });

  it('一个服务失败不影响其余域返回', () => {
    const merged = mergeServiceResults([
      { service: 'catalog', response: null, error: 'boom' },
      outcome('trade', [group('ORDER', [hit({ domain: 'ORDER' })], 'ALL', '订单')]),
    ]);
    expect(merged.groups.map((g) => g.domain)).toEqual(['ORDER']);
  });

  it('被权限抑制的子域带归属业务域一起带回来（按业务域 + 子域去重）', () => {
    const merged = mergeServiceResults([
      outcome(
        'catalog',
        [group('PRODUCT', [hit()], 'ALL', '商品')],
        ['PRODUCT_SKU', 'PRODUCT_SPU'],
        [
          { domain: 'PRODUCT_SKU', label: 'SKU', group: 'PRODUCT', groupLabel: '商品' },
          { domain: 'PRODUCT_SPU', label: 'SPU', group: 'PRODUCT', groupLabel: '商品' },
        ],
      ),
      outcome(
        'catalog',
        [],
        ['PRODUCT_SKU'],
        [{ domain: 'PRODUCT_SKU', label: 'SKU', group: 'PRODUCT', groupLabel: '商品' }],
      ),
    ]);
    expect(merged.suppressedDomains.map((s) => s.domain)).toEqual(['PRODUCT_SKU', 'PRODUCT_SPU']);
  });

  it('同一域被多个服务返回时取更严的数据范围', () => {
    const merged = mergeServiceResults([
      outcome('catalog', [group('PRODUCT', [hit({ id: '1' })], 'ALL', '商品')]),
      outcome('trade', [group('PRODUCT', [hit({ id: '2' })], 'SELF', '商品')]),
    ]);
    expect(merged.groups[0]?.dataScope).toBe('SELF');
    expect(merged.groups[0]?.hits).toHaveLength(2);
  });
});

describe('数据范围', () => {
  it('短标签按后端口径逐条对应', () => {
    expect(scopeLabel('ALL')).toBe('全范围');
    expect(scopeLabel('DEPARTMENT')).toBe('本部门');
    expect(scopeLabel('SELF')).toBe('仅本人');
    expect(scopeLabel('TENANT')).toBe('租户级');
  });

  it('未知口径原样回显，不假装它属于某个已知范围', () => {
    expect(scopeLabel('WHATEVER')).toBe('WHATEVER');
    expect(scopeLabel('')).toBe('范围未声明');
    expect(scopeLabel(null)).toBe('范围未声明');
  });

  it('取更严的一侧：SELF > DEPARTMENT > ALL / TENANT', () => {
    expect(narrowerScope('ALL', 'SELF')).toBe('SELF');
    expect(narrowerScope('DEPARTMENT', 'ALL')).toBe('DEPARTMENT');
    expect(narrowerScope('TENANT', 'DEPARTMENT')).toBe('DEPARTMENT');
    expect(narrowerScope('ALL', 'ALL')).toBe('ALL');
  });
});

describe('buildHref', () => {
  it('订单跳详情页（蓝图 9.1.1.2 的订单控制塔）', () => {
    expect(buildHref('ORDER', hit({ domain: 'ORDER', id: 'abc-123' }))).toBe('/orders/abc-123');
  });

  it('其余域跳列表页并把主标识回填到关键字筛选器', () => {
    const href = buildHref('PURCHASE', hit({ domain: 'PURCHASE', primary: 'PO-AB 001' }));
    expect(href).toBe('/purchase/orders?keyword=PO-AB%20001');
  });

  it('未知域不会拼出一个假链接', () => {
    expect(buildHref('NOT_A_DOMAIN', hit({ domain: 'NOT_A_DOMAIN' }))).toBe('/dashboard/search');
  });

  it('每个已知域都有落点', () => {
    for (const domain of Object.keys(GLOBAL_SEARCH_TARGETS)) {
      expect(buildHref(domain, hit({ domain }))).toContain(
        GLOBAL_SEARCH_TARGETS[domain]?.href ?? '',
      );
    }
  });
});

describe('展示辅助', () => {
  it('域的中文名来自目标表，未知域退回域码', () => {
    expect(domainLabel('PRODUCT')).toBe('商品');
    expect(domainLabel('WHAT')).toBe('WHAT');
  });

  it('副信息为空时退回 kind，不显示空白', () => {
    expect(hitSubtitle(hit({ secondary: '' }))).toBe('SKU');
    expect(hitSubtitle(hit())).toContain('SPU');
  });

  it('命中总数跨域累加', () => {
    const merged = mergeServiceResults([
      outcome('catalog', [
        group('PRODUCT', [hit({ id: '1' }), hit({ id: '2' })], 'ALL', '商品'),
      ]),
      outcome('trade', [group('ORDER', [hit({ domain: 'ORDER', id: '3' })], 'ALL', '订单')]),
    ]);
    expect(countHits(merged.groups)).toBe(3);
  });
});
