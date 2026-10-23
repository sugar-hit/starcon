import { describe, expect, it } from 'vitest';
import {
  buildGroupedView,
  describeGroupedOutcome,
  emptyGroupedView,
  filterGroups,
  groupOptions,
  permissionNotice,
  suppressedPartsNotice,
  type MergedSearchResult,
} from './global-search-group-model';
import { mergeServiceResults, type GlobalSearchHit } from './global-search-model';

function hit(id: string, overrides: Partial<GlobalSearchHit> = {}): GlobalSearchHit {
  return {
    domain: 'PRODUCT',
    kind: 'SKU',
    id,
    primary: `SKU-${id}`,
    secondary: null,
    ...overrides,
  };
}

function merged(overrides: Partial<MergedSearchResult> = {}): MergedSearchResult {
  return {
    groups: [],
    unavailableDomains: [],
    failedServices: [],
    suppressedDomains: [],
    ...overrides,
  };
}

describe('buildGroupedView', () => {
  it('组计数与清单同源：就是这一组实际渲染的命中条数', () => {
    const view = buildGroupedView(
      merged({
        groups: [
          {
            domain: 'PRODUCT',
            label: '商品',
            hits: [hit('1'), hit('2')],
            dataScope: 'ALL',
            dataScopeNote: '商品档案在你的授权范围内全租户可见。',
          },
          {
            domain: 'ORDER',
            label: '订单',
            hits: [hit('3', { domain: 'ORDER' })],
            dataScope: 'SELF',
            dataScopeNote: '销售订单只返回本人经手过的。',
          },
        ],
      }),
    );

    expect(view.groups.map((g) => g.count)).toEqual([2, 1]);
    expect(view.totalHits).toBe(3);
  });

  it('每组带数据范围的短标签与说明文案', () => {
    const view = buildGroupedView(
      merged({
        groups: [
          {
            domain: 'ORDER',
            label: '订单',
            hits: [hit('3', { domain: 'ORDER' })],
            dataScope: 'SELF',
            dataScopeNote: '只返回本人经手过的订单。',
          },
        ],
      }),
    );
    expect(view.groups[0]?.dataScopeLabel).toBe('仅本人');
    expect(view.groups[0]?.dataScopeNote).toBe('只返回本人经手过的订单。');
  });

  it('被权限抑制的子域按归属业务域归并（报的是业务域数，不是子域数）', () => {
    const view = buildGroupedView(
      merged({
        groups: [
          {
            domain: 'PRODUCT',
            label: '商品',
            hits: [hit('1')],
            dataScope: 'ALL',
            dataScopeNote: '商品档案全租户可见。',
          },
        ],
        suppressedDomains: [
          { domain: 'PRODUCT_SKU', label: 'SKU', group: 'PRODUCT', groupLabel: '商品' },
          { domain: 'PRODUCT_SPU', label: 'SPU', group: 'PRODUCT', groupLabel: '商品' },
          { domain: 'ORDER', label: '订单', group: 'ORDER', groupLabel: '订单' },
        ],
      }),
    );

    expect(view.suppressedGroups).toHaveLength(2);
    expect(view.suppressedGroups[0]).toEqual({
      group: 'PRODUCT',
      label: '商品',
      parts: ['SKU', 'SPU'],
    });
    expect(view.suppressedGroups[1]?.group).toBe('ORDER');
    expect(view.suppressedDomainCount).toBe(3);
  });

  it('没有权限问题时不给任何提示', () => {
    const view = buildGroupedView(merged());
    expect(permissionNotice(view)).toBe('');
    expect(view.suppressedGroups).toEqual([]);
  });

  it('空视图就是空视图，不编造任何组', () => {
    const view = emptyGroupedView();
    expect(view.groups).toEqual([]);
    expect(view.totalHits).toBe(0);
    expect(describeGroupedOutcome(view)).toBe('没有匹配的记录');
  });
});

describe('只看某一组', () => {
  const view = buildGroupedView(
    merged({
      groups: [
        {
          domain: 'PRODUCT',
          label: '商品',
          hits: [hit('1')],
          dataScope: 'ALL',
          dataScopeNote: '说明',
        },
        {
          domain: 'ORDER',
          label: '订单',
          hits: [hit('2', { domain: 'ORDER' })],
          dataScope: 'SELF',
          dataScopeNote: '说明',
        },
      ],
    }),
  );

  it('选某一组时只留那一组，其余的不出现（不是置灰）', () => {
    expect(filterGroups(view.groups, 'ORDER').map((g) => g.domain)).toEqual(['ORDER']);
  });

  it('不选就是全部', () => {
    expect(filterGroups(view.groups, null)).toHaveLength(2);
  });

  it('选了一个不存在的组时不留任何组（不回落成全部）', () => {
    expect(filterGroups(view.groups, 'NOT_THERE')).toEqual([]);
  });

  it('筛选条的计数与各组清单同源', () => {
    expect(groupOptions(view.groups)).toEqual([
      { value: 'ALL', label: '全部', count: 2 },
      { value: 'PRODUCT', label: '商品', count: 1 },
      { value: 'ORDER', label: '订单', count: 1 },
    ]);
  });
});

describe('提示文案', () => {
  it('状态行把命中、无权限、服务未响应分开说', () => {
    const view = buildGroupedView(
      merged({
        groups: [
          {
            domain: 'ORDER',
            label: '订单',
            hits: [hit('2', { domain: 'ORDER' })],
            dataScope: 'SELF',
            dataScopeNote: '说明',
          },
        ],
        suppressedDomains: [
          { domain: 'CUSTOMER', label: '客户', group: 'CUSTOMER', groupLabel: '客户' },
        ],
        failedServices: ['warehouse'],
      }),
    );

    const text = describeGroupedOutcome(view);
    expect(text).toContain('命中 1 条');
    expect(text).toContain('1 个业务域无权限');
    expect(text).toContain('客户');
    expect(text).toContain('warehouse 服务未响应');
  });

  it('无权限提示只报域数，并写明具体条数未返回', () => {
    const view = buildGroupedView(
      merged({
        suppressedDomains: [
          { domain: 'PRODUCT_SKU', label: 'SKU', group: 'PRODUCT', groupLabel: '商品' },
          { domain: 'ORDER', label: '订单', group: 'ORDER', groupLabel: '订单' },
        ],
      }),
    );

    const notice = permissionNotice(view);
    expect(notice).toContain('2 个业务域因权限未返回结果');
    expect(notice).toContain('商品');
    expect(notice).toContain('订单');
    expect(notice).toContain('命中的具体条数未返回');
    // 绝不能编一个被抑制的命中条数出来。
    expect(notice).not.toMatch(/\d+ 条/);
  });

  it('组内部分子域被抑制时给出组内提示', () => {
    const view = buildGroupedView(
      merged({
        groups: [
          {
            domain: 'PRODUCT',
            label: '商品',
            hits: [hit('1')],
            dataScope: 'ALL',
            dataScopeNote: '说明',
          },
        ],
        suppressedDomains: [
          { domain: 'PRODUCT_SKU', label: 'SKU', group: 'PRODUCT', groupLabel: '商品' },
        ],
      }),
    );
    expect(suppressedPartsNotice(view.groups[0]!)).toContain('SKU');
  });

  it('组内没有被抑制的子域时不提示', () => {
    const view = buildGroupedView(
      merged({
        groups: [
          {
            domain: 'PRODUCT',
            label: '商品',
            hits: [hit('1')],
            dataScope: 'ALL',
            dataScopeNote: '说明',
          },
        ],
      }),
    );
    expect(suppressedPartsNotice(view.groups[0]!)).toBe('');
  });
});

describe('与 1.2.1.1 的合并产物对接', () => {
  it('合并结果直接喂给分组视图，不用另写一套归并', () => {
    const mergedResult = mergeServiceResults([
      {
        service: 'catalog',
        response: {
          keyword: 'SKU-AX',
          matchMode: 'PREFIX_OR_EXACT',
          minKeywordLength: 2,
          groups: [
            {
              domain: 'PRODUCT',
              label: '商品',
              hits: [hit('1')],
              dataScope: 'ALL',
              dataScopeNote: '商品档案全租户可见。',
            },
          ],
          unavailableDomains: ['CUSTOMER'],
          suppressedDomains: [
            { domain: 'CUSTOMER', label: '客户', group: 'CUSTOMER', groupLabel: '客户' },
          ],
        },
        error: null,
      },
    ]);

    const view = buildGroupedView(mergedResult);
    expect(view.groups.map((g) => g.domain)).toEqual(['PRODUCT']);
    expect(view.totalHits).toBe(1);
    expect(view.suppressedGroups.map((g) => g.label)).toEqual(['客户']);
  });
});
