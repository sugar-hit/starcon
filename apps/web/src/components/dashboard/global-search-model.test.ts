import { describe, expect, it } from 'vitest';
import {
  buildHref,
  countHits,
  domainLabel,
  GLOBAL_SEARCH_TARGETS,
  hitSubtitle,
  mergeServiceResults,
  MIN_KEYWORD_LENGTH,
  normalizeKeyword,
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

function response(overrides: Partial<GlobalSearchResponse> = {}): GlobalSearchResponse {
  return {
    keyword: 'SKU-AX',
    matchMode: 'PREFIX_OR_EXACT',
    minKeywordLength: MIN_KEYWORD_LENGTH,
    groups: [],
    unavailableDomains: [],
    ...overrides,
  };
}

function outcome(
  service: string,
  groups: GlobalSearchResponse['groups'],
  unavailable: string[] = [],
): ServiceSearchOutcome {
  return { service, response: response({ groups, unavailableDomains: unavailable }), error: null };
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
        { domain: 'PRODUCT', label: '商品', hits: [hit({ kind: 'SKU', id: 'a' })] },
        { domain: 'PRODUCT', label: '商品', hits: [hit({ kind: 'SPU', id: 'b' })] },
      ]),
    ]);
    expect(merged.groups).toHaveLength(1);
    expect(merged.groups[0]?.hits.map((h) => h.kind)).toEqual(['SKU', 'SPU']);
  });

  it('同一条命中被两个来源同时命中时只留一条', () => {
    const merged = mergeServiceResults([
      outcome('catalog', [{ domain: 'PRODUCT', label: '商品', hits: [hit({ id: 'same' })] }]),
      outcome('trade', [{ domain: 'PRODUCT', label: '商品', hits: [hit({ id: 'same' })] }]),
    ]);
    expect(merged.groups[0]?.hits).toHaveLength(1);
  });

  it('组按蓝图的域次序排列，不按服务返回次序', () => {
    const merged = mergeServiceResults([
      outcome('warehouse', [{ domain: 'LOGISTICS', label: '物流', hits: [hit({ domain: 'LOGISTICS' })] }]),
      outcome('trade', [{ domain: 'ORDER', label: '订单', hits: [hit({ domain: 'ORDER' })] }]),
      outcome('catalog', [{ domain: 'PRODUCT', label: '商品', hits: [hit()] }]),
    ]);
    expect(merged.groups.map((g) => g.domain)).toEqual(['PRODUCT', 'ORDER', 'LOGISTICS']);
  });

  it('没权限的域与失败的服务必须分开报', () => {
    const merged = mergeServiceResults([
      outcome('catalog', [{ domain: 'PRODUCT', label: '商品', hits: [hit()] }], ['CUSTOMER']),
      { service: 'warehouse', response: null, error: '服务暂时不可用' },
    ]);
    expect(merged.unavailableDomains).toEqual(['CUSTOMER']);
    expect(merged.failedServices).toEqual(['warehouse']);
    expect(merged.groups).toHaveLength(1);
  });

  it('一个服务失败不影响其余域返回', () => {
    const merged = mergeServiceResults([
      { service: 'catalog', response: null, error: 'boom' },
      outcome('trade', [{ domain: 'ORDER', label: '订单', hits: [hit({ domain: 'ORDER' })] }]),
    ]);
    expect(merged.groups.map((g) => g.domain)).toEqual(['ORDER']);
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
        { domain: 'PRODUCT', label: '商品', hits: [hit({ id: '1' }), hit({ id: '2' })] },
      ]),
      outcome('trade', [{ domain: 'ORDER', label: '订单', hits: [hit({ domain: 'ORDER', id: '3' })] }]),
    ]);
    expect(countHits(merged.groups)).toBe(3);
  });
});
