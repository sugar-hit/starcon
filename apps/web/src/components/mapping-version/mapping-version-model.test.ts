import { describe, expect, it } from 'vitest';

import {
  activeFilterCount,
  affectedOrdersText,
  applyFilterChange,
  buildQuery,
  changedFieldLabel,
  changeSourceLabel,
  effectiveWindow,
  emptyFilters,
  formatMoment,
  hasNextPage,
  hasPrevPage,
  pageSummary,
  relationLabel,
  resetFilters,
  unavailableDimensions,
  type VersionAffectedOrders,
  type VersionPage,
  type VersionRow,
} from './mapping-version-model';

/**
 * 4.1.2.2 映射版本 前端模型的单测。
 *
 * <p>判据集中在四件最容易做丢的事上：筛选全在服务端、未交付段不画 0、
 * 商品范围快照与平台当前值分得开、时间只做展示不做算术。
 */

/** 一条最小可用的版本行。 */
function version(over: Partial<VersionRow> = {}): VersionRow {
  return {
    id: 'v1',
    mappingId: 'm1',
    versionNo: 1,
    relationType: 'ONE_TO_ONE',
    mappingStatus: 'MAPPED',
    internalSkuId: 'sku-1',
    componentQuantity: null,
    effectiveFrom: '2026-09-01T10:00:00+08:00',
    effectiveTo: null,
    current: true,
    changeReason: '初次建立',
    changeSource: 'MANUAL',
    actorId: 'u1',
    listingId: 'l1',
    externalItemId: 'B0001',
    titleSnapshot: '改版前的标题',
    listingVariantId: 'lv1',
    externalVariantId: 'V-1',
    shopCode: 'AMZ-1',
    capturedAt: '2026-09-01T10:00:00+08:00',
    createdAt: '2026-09-01T10:00:00+08:00',
    ...over,
  };
}

function pageOf(total: number, items: VersionRow[], pageNo = 0, size = 50): VersionPage {
  return { items, total, page: pageNo, size, currentInPage: items.filter((v) => v.current).length };
}

describe('buildQuery', () => {
  it('空筛选只带排序与分页', () => {
    const q = new URLSearchParams(buildQuery(emptyFilters()));
    expect(q.get('sort')).toBe('RECENT');
    expect(q.get('page')).toBe('0');
    expect(q.get('size')).toBe('50');
    expect([...q.keys()].sort()).toEqual(['page', 'size', 'sort']);
  });

  it('每个筛选维度都原样送服务端（不做本地过滤）', () => {
    const filters = applyFilterChange(emptyFilters(), {
      mappingId: 'm1',
      internalSkuId: 'sku-1',
      shopCode: ' AMZ-1 ',
      effectiveState: 'CURRENT',
      windowFrom: '2026-09-01T00:00:00+08:00',
      windowTo: '2026-09-30T00:00:00+08:00',
      search: ' 供应商 ',
    });
    const q = new URLSearchParams(buildQuery(filters));
    expect(q.get('mappingId')).toBe('m1');
    expect(q.get('internalSkuId')).toBe('sku-1');
    // 首尾空白被裁掉，但值本身原样送出
    expect(q.get('shopCode')).toBe('AMZ-1');
    expect(q.get('search')).toBe('供应商');
    expect(q.get('effectiveState')).toBe('CURRENT');
    // 生效区间的两个端点透传：重叠语义由服务端裁决
    expect(q.get('windowFrom')).toBe('2026-09-01T00:00:00+08:00');
    expect(q.get('windowTo')).toBe('2026-09-30T00:00:00+08:00');
  });
});

describe('筛选计数与翻页', () => {
  it('只数有值的筛选维度（排序/分页不算筛选）', () => {
    let filters = emptyFilters();
    expect(activeFilterCount(filters)).toBe(0);
    filters = applyFilterChange(filters, { shopCode: 'AMZ-1', search: '耳机' });
    expect(activeFilterCount(filters)).toBe(2);
  });

  it('改任一筛选条件都回到第 0 页（停在第 3 页看筛选结果永远是空的）', () => {
    const filters = applyFilterChange({ ...emptyFilters(), page: 3 }, { shopCode: 'AMZ-1' });
    expect(filters.page).toBe(0);
    expect(filters.shopCode).toBe('AMZ-1');
  });

  it('重置保留每页条数，清掉其余筛选', () => {
    const filters = resetFilters({ ...emptyFilters(), size: 20, shopCode: 'AMZ-1', search: 'x' });
    expect(filters.size).toBe(20);
    expect(activeFilterCount(filters)).toBe(0);
  });

  it('翻页按钮的边界跟着 total 走，不是本页条数', () => {
    expect(hasPrevPage(pageOf(3, [version()], 0, 2))).toBe(false);
    expect(hasNextPage(pageOf(3, [version()], 0, 2))).toBe(true);
    expect(hasPrevPage(pageOf(3, [version()], 1, 2))).toBe(true);
    expect(hasNextPage(pageOf(3, [version()], 1, 2))).toBe(false);
    // 空页不该让人以为还能往前翻
    expect(hasNextPage(null)).toBe(false);
  });

  it('摘要用 total，不用本页条数', () => {
    // 第一页真的装了 2 条（本页条数 = 2），但 total 是 3
    expect(pageSummary(pageOf(3, [version(), version({ id: 'v2', versionNo: 2 })], 0, 2))).toBe(
      '共 3 条，当前显示第 1-2 条',
    );
    // 末页只有 1 条：摘要按实际条数收尾，但总条数仍然是全集
    expect(pageSummary(pageOf(3, [version()], 1, 2))).toBe('共 3 条，当前显示第 3-3 条');
    expect(pageSummary(pageOf(0, []))).toBe('共 0 条');
    expect(pageSummary(null)).toBe('共 0 条');
  });
});

describe('未交付段：不画 0', () => {
  const unavailable: VersionAffectedOrders = {
    versionId: 'v2',
    versionNo: 2,
    available: false,
    ownerModule: '09 订单履约（trade 服务 · order_line_component 冻结）',
    note: 'trade 侧没有可供 channel 查询订单的已交付端点',
    prerequisite: '订单行冻结本版本 id 并提供查询端点',
  };

  it('available=false 时给出归属模块与原因', () => {
    const text = affectedOrdersText(unavailable);
    expect(text).toContain('未接入');
    expect(text).toContain('09 订单履约');
    expect(text).toContain('没有可供 channel 查询订单的已交付端点');
  });

  it('🔴 绝不出现「0 条」这种会被读成「确实没有受影响订单」的措辞', () => {
    const text = affectedOrdersText(unavailable) ?? '';
    expect(text).not.toContain('0 条');
    expect(text).not.toContain('没有受影响');
  });

  it('可用时返回 null（界面上不画这一段）', () => {
    expect(affectedOrdersText({ ...unavailable, available: true })).toBeNull();
    expect(affectedOrdersText(null)).toBeNull();
    expect(affectedOrdersText(undefined)).toBeNull();
  });

  it('能力声明里 available=false 的维度被逐条挑出来', () => {
    const missing = unavailableDimensions({
      dimensions: [
        { dimension: 'version_list', description: '', available: true, source: '', ownerModule: '', impact: '' },
        {
          dimension: 'affected_orders',
          description: '',
          available: false,
          source: '',
          ownerModule: '09 订单',
          impact: '回答不了波及哪些订单',
        },
      ],
      sorts: [],
      changeSources: [],
      relationTypes: [],
      gapNote: '',
      dependencyNote: '',
    });
    expect(missing.map((d) => d.dimension)).toEqual(['affected_orders']);
    expect(missing[0]?.ownerModule).toBe('09 订单');
    expect(unavailableDimensions(null)).toEqual([]);
  });
});

describe('时间：只展示，不做算术', () => {
  it('原样带偏移显示，不换算到浏览器本地时区', () => {
    expect(formatMoment('2026-09-01T10:00:00+08:00')).toBe('2026-09-01 10:00:00 +08:00');
    expect(formatMoment('2026-09-01T02:00:00Z')).toBe('2026-09-01 02:00:00 Z');
  });

  it('空值显示为破折号而不是 Invalid Date', () => {
    expect(formatMoment(null)).toBe('—');
    expect(formatMoment(undefined)).toBe('—');
    expect(formatMoment('')).toBe('—');
  });

  it('生效区间：已关闭与当前生效是两种可读形态', () => {
    expect(effectiveWindow(version())).toContain('当前生效');
    const closed = version({
      current: false,
      effectiveTo: '2026-09-08T10:00:00+08:00',
    });
    expect(effectiveWindow(closed)).toContain('已关闭');
    expect(effectiveWindow(closed)).toContain('2026-09-08 10:00:00 +08:00');
  });
});

describe('标签', () => {
  it('基数与来源有中文名，未知值原样透出而不是渲染空白', () => {
    expect(relationLabel('ONE_TO_ONE')).toBe('一对一');
    expect(relationLabel('ONE_TO_MANY')).toContain('套装');
    expect(relationLabel('MANY_TO_ONE')).toBe('多对一');
    expect(relationLabel('WHAT_IS_THIS')).toBe('WHAT_IS_THIS');
    expect(changeSourceLabel('BATCH_CONFIRMED')).toBe('批量候选人工确认');
    expect(changedFieldLabel('INTERNAL_SKU_ID')).toBe('内部 SKU');
    expect(changedFieldLabel('NOPE')).toBe('NOPE');
  });
});
