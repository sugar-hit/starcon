import { describe, expect, it } from 'vitest';
import {
  activeFilterCount,
  applyFilterChange,
  buildQuery,
  effectiveStateLabel,
  effectiveWindow,
  emptyFilters,
  formatQuantity,
  hasNextPage,
  hasPrevPage,
  pageSummary,
  quantityInvalidReason,
  quantityRange,
  resetFilters,
  sellableBanner,
  sellableText,
  unavailableDimensions,
  type BundleFilters,
  type ListingBundleBindingRow,
  type ListingBundleComponentSellable,
} from './bundle-component-model';

const binding = (over: Partial<ListingBundleBindingRow> = {}): ListingBundleBindingRow => ({
  id: 'b1',
  mappingId: 'm1',
  listingId: 'l1',
  listingVariantId: 'v1',
  shopCode: 'AMZ-1',
  externalItemId: 'ITEM-1',
  externalVariantId: 'V-1',
  bundleVersionId: 'bundle-version-1',
  effectiveFrom: '2026-09-01T10:00:00+08:00',
  effectiveTo: null,
  current: true,
  changeReason: '初次建立',
  actorId: null,
  componentCount: 2,
  totalQuantity: 3,
  createdAt: '2026-09-01T10:00:00+08:00',
  ...over,
});

const component = (
  over: Partial<ListingBundleComponentSellable> = {},
): ListingBundleComponentSellable => ({
  componentId: 'c1',
  componentSkuId: 'sku-1',
  quantity: 1,
  sellableQuantity: 10,
  setsAvailable: 10,
  sellable: true,
  reason: null,
  ...over,
});

describe('4.1.2.3 组件数量必须大于 0（蓝图加粗句）', () => {
  it('合法区间是 > 0 而不是「非负」', () => {
    const range = quantityRange();
    expect(range.exclusiveMin).toBe(true);
    // 0 本身是违法的：写成 [0, ∞) 会让「配了组件但不消耗」在表单上看起来合法
    expect(range.min).toBe(0);
  });

  it('0、负数、空与非数字都给出与后端同方向的原因', () => {
    expect(quantityInvalidReason('0')).toContain('组件数量必须大于 0');
    expect(quantityInvalidReason('-1')).toContain('组件数量必须大于 0');
    expect(quantityInvalidReason('-0.5')).toContain('组件数量必须大于 0');
    expect(quantityInvalidReason('  ')).toContain('必填');
    expect(quantityInvalidReason('abc')).toContain('必须是数字');
  });

  it('正数（含小数）合法 —— 0.5 米挂绳 + 2 个挂钩是真实场景', () => {
    expect(quantityInvalidReason('1')).toBeNull();
    expect(quantityInvalidReason('0.5')).toBeNull();
    expect(quantityInvalidReason('2.000001')).toBeNull();
  });
});

describe('4.1.2.3 缺任一组件时整个套装不可售', () => {
  it('尚未校验时不给出「可售」结论', () => {
    expect(sellableBanner(null).tone).toBe('unknown');
    expect(sellableBanner(null).text).not.toContain('可售');
  });

  it('组件数为 0 ⇒ 不可售，且说的是「缺组件」而不是「缺货」', () => {
    const banner = sellableBanner({
      bundleSellable: false,
      componentCount: 0,
      unsellableCount: 0,
      components: [],
      reason: '没有组件',
      available: true,
      bindingId: 'b',
      mappingId: 'm',
      bundleVersionId: 'v',
      warehouseId: 'w',
      note: '',
    });
    expect(banner.tone).toBe('bad');
    expect(banner.text).toContain('没有登记任何组件');
  });

  it('三个组件里一个不够 ⇒ 整套不可售（哪怕另外两个都还有货）', () => {
    const banner = sellableBanner({
      bundleSellable: false,
      componentCount: 3,
      unsellableCount: 1,
      components: [
        component({ setsAvailable: 5 }),
        component({ componentId: 'c2', setsAvailable: 3 }),
        component({ componentId: 'c3', sellable: false, setsAvailable: 0, reason: '可售量不足' }),
      ],
      reason: '缺任一组件',
      available: true,
      bindingId: 'b',
      mappingId: 'm',
      bundleVersionId: 'v',
      warehouseId: 'w',
      note: '',
    });
    expect(banner.tone).toBe('bad');
    expect(banner.text).toContain('整套不可售');
    expect(banner.text).toContain('1 个');
  });

  it('全部可售时给出「最多能成套卖几件」= 各组件的最小值', () => {
    const banner = sellableBanner({
      bundleSellable: true,
      componentCount: 3,
      unsellableCount: 0,
      components: [
        component({ setsAvailable: 9 }),
        component({ componentId: 'c2', setsAvailable: 4 }),
        component({ componentId: 'c3', setsAvailable: 7 }),
      ],
      reason: null,
      available: true,
      bindingId: 'b',
      mappingId: 'm',
      bundleVersionId: 'v',
      warehouseId: 'w',
      note: '',
    });
    expect(banner.tone).toBe('ok');
    expect(banner.text).toContain('4 件');
  });
});

describe('4.1.2.3 筛选与分页', () => {
  it('空筛选不产生 query 噪声，排序与分页始终送出', () => {
    const q = new URLSearchParams(buildQuery(emptyFilters()));
    expect(q.get('sort')).toBe('RECENT');
    expect(q.get('page')).toBe('0');
    expect(q.get('size')).toBe('50');
    expect(q.get('search')).toBeNull();
  });

  it('空白值不送出，前后空格被裁掉', () => {
    const filters: BundleFilters = { ...emptyFilters(), search: '   ', shopCode: ' AMZ-1 ' };
    const q = new URLSearchParams(buildQuery(filters));
    expect(q.get('search')).toBeNull();
    expect(q.get('shopCode')).toBe('AMZ-1');
    expect(activeFilterCount(filters)).toBe(1);
  });

  it('改筛选回到第 0 页 —— 停在第 3 页再看筛选结果永远是空的', () => {
    const filters: BundleFilters = { ...emptyFilters(), page: 3 };
    expect(applyFilterChange(filters, { shopCode: 'X' }).page).toBe(0);
  });

  it('清空筛选保留页大小', () => {
    const filters: BundleFilters = { ...emptyFilters(), shopCode: 'X', search: 'Y', size: 20 };
    const cleared = resetFilters(filters);
    expect(cleared.shopCode).toBe('');
    expect(cleared.size).toBe(20);
    expect(activeFilterCount(cleared)).toBe(0);
  });

  it('分页边界按服务端返回的 total 判定', () => {
    const page = { items: [], total: 120, page: 0, size: 50, currentInPage: 1 };
    expect(hasNextPage(page)).toBe(true);
    expect(hasPrevPage(page)).toBe(false);
    expect(hasNextPage({ ...page, page: 2 })).toBe(false);
    expect(hasPrevPage({ ...page, page: 2 })).toBe(true);
    expect(hasNextPage(null)).toBe(false);
  });

  it('总数为 0 时摘要就说 0，不显示「第 1-0 条」', () => {
    expect(pageSummary({ items: [], total: 0, page: 0, size: 50, currentInPage: 0 })).toBe('共 0 条');
    expect(pageSummary(null)).toBe('共 0 条');
  });
});

describe('4.1.2.3 展示', () => {
  it('数量去掉尾随零：NUMERIC(19,6) 不该显示成一串零', () => {
    expect(formatQuantity(2.0)).toBe('2');
    expect(formatQuantity(0.5)).toBe('0.5');
    expect(formatQuantity(null)).toBe('—');
  });

  it('生效区间：终点空 = 当前生效', () => {
    expect(effectiveWindow(binding())).toContain('当前生效');
    expect(effectiveWindow(binding({ effectiveTo: '2026-10-01T10:00:00+08:00' }))).toContain(
      '已退役',
    );
  });

  it('时刻不做时区换算，原样带偏移显示', () => {
    expect(effectiveWindow(binding()).startsWith('2026-09-01 10:00:00')).toBe(true);
  });

  it('未交付维度如实挑出（不画空列表）', () => {
    const caps = {
      dimensions: [
        { dimension: 'component_maintenance', available: true },
        { dimension: 'component_snapshot', available: false },
        { dimension: 'inventory_reservation', available: false },
      ],
      sorts: [],
      effectiveStates: [],
      gapNote: '',
      dependencyNote: '',
    } as unknown as Parameters<typeof unavailableDimensions>[0];
    expect(unavailableDimensions(caps).map((d) => d.dimension)).toEqual([
      'component_snapshot',
      'inventory_reservation',
    ]);
    expect(unavailableDimensions(null)).toEqual([]);
  });

  it('生效状态与排序有中文标签', () => {
    expect(effectiveStateLabel('CURRENT')).toBe('当前生效');
    expect(effectiveStateLabel('RETIRED')).toBe('已退役');
  });

  it('可售量展示带上「每件用量」，缺货行的原因原样透出', () => {
    expect(sellableText(component({ sellableQuantity: 5, quantity: 2 }))).toBe('5（每件需 2）');
    expect(sellableText(component({ sellableQuantity: 0, reason: '查不到库存行' }))).toContain('0');
  });
});
