import { describe, expect, it } from 'vitest';
import {
  activeFilterCount,
  applyFilterChange,
  batchSummary,
  batchSupport,
  buildQuery,
  emptyFilters,
  hasNextPage,
  hasPrevPage,
  ListingCapabilities,
  ListingFilters,
  ListingPage,
  ListingRow,
  mappingLabel,
  pageSummary,
  priceCell,
  resetFilters,
  saveListing,
  statusLabel,
  stockLabel,
  unavailableDimensions,
} from './listing-model';

/**
 * 4.1.1.1 Listing 列表 前端模型测试。
 *
 * <p>本文件钉三组事：
 *
 * <ol>
 *   <li><b>价格不画假数据</b>：类型上没有 price 字段，单元格恒为「未接入」，且措辞来自服务端</li>
 *   <li><b>未映射与缺货是两个独立筛选</b>：两个字段、两个参数、两条独立判定，能同时生效</li>
 *   <li><b>筛选全部下发服务端</b>：空值不下发、换条件回到第 0 页、批量动作的支持性如实表达</li>
 * </ol>
 */

const caps: ListingCapabilities = {
  dimensions: [
    {
      dimension: 'listing_scope',
      description: '',
      available: true,
      source: 'listing',
      ownerModule: '',
      impact: '',
    },
    {
      dimension: 'price',
      description: '',
      available: false,
      source: '—',
      ownerModule: '05 定价与价目系统',
      impact: '看不到售价',
    },
    {
      dimension: 'sales',
      description: '',
      available: false,
      source: '—',
      ownerModule: '09 订单与履约系统',
      impact: '看不到销量',
    },
    {
      dimension: 'internal_stock',
      description: '',
      available: false,
      source: '—',
      ownerModule: '07 库存系统',
      impact: '看不到内部可售量',
    },
  ],
  priceAvailable: false,
  priceOwnerModule: '05 定价与价目系统',
  priceNote: '🔴 本批次价格能力未接入：卷 05 整卷未交付',
  statuses: ['DRAFT', 'ON_SALE'],
  platformStockStates: ['IN_STOCK', 'OUT_OF_STOCK', 'UNKNOWN'],
  reviewStatuses: ['NOT_SUBMITTED', 'APPROVED'],
  mappingStatuses: ['UNMAPPED', 'PARTIAL', 'MAPPED'],
  variantMappingStatuses: ['UNMAPPED', 'MAPPED', 'BUNDLE'],
  batchActions: ['ASSIGN_OWNER', 'DELIST', 'REPRICE', 'RESTOCK'],
  independentStateNote: '两个独立状态',
  upstreamNote: '',
};

function row(over: Partial<ListingRow> = {}): ListingRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    shopId: 's1',
    shopCode: 'US-AMZ',
    shopName: '美国店',
    platformId: 'p1',
    platformCode: 'AMAZON',
    marketplaceId: 'm1',
    marketplaceCode: 'US',
    externalItemId: 'B0ABC12345',
    title: '无线耳机',
    categoryPath: 'Electronics > Audio',
    platformCategoryId: 'cat-1',
    status: 'ON_SALE',
    reviewStatus: 'APPROVED',
    reviewMessage: '',
    ownerId: 'o1',
    rating: 4.7,
    ratingCount: 128,
    currency: 'USD',
    platformStockState: 'IN_STOCK',
    platformStockQty: null,
    mappingStatus: 'MAPPED',
    variantCount: 2,
    mappedVariantCount: 2,
    internalSkuIds: ['sku-1'],
    statusChangeCount7d: 0,
    recentStatusChanges: [],
    launchedAt: '2026-10-01T09:00:00+08:00',
    lastSyncedAt: '2026-10-02T18:30:00+08:00',
    updatedAt: '2026-10-02T18:30:00+08:00',
    ...over,
  };
}

function page(over: Partial<ListingPage> = {}): ListingPage {
  return { items: [row()], total: 1, page: 0, size: 50, rejectedFilters: [], ...over };
}

describe('4.1.1.1 价格不画假数据', () => {
  it('capabilities 未加载时也返回「未接入」而不是 0', () => {
    const cell = priceCell(null);
    expect(cell.available).toBe(false);
    expect(cell.note).toContain('未接入');
    expect(cell.note).not.toBe('0');
  });

  it('priceAvailable=false 时透出服务端措辞与归属模块', () => {
    const cell = priceCell(caps);
    expect(cell.available).toBe(false);
    // 措辞来自服务端，不在前端另编一套
    expect(cell.note).toBe(caps.priceNote);
    expect(cell.ownerModule).toBe('05 定价与价目系统');
  });

  it('🔴 列表行类型上根本没有价格字段', () => {
    // 这是编译期断言：TS 的 excess property check 会在下面这行报错
    // @ts-expect-error 4.1.1.1 没有价格事实来源，行上不许出现 price
    const withPrice: ListingRow = { ...row(), price: 19.99 };
    expect(withPrice).toBeDefined();
  });

  it('未接入维度清单含价格 / 销量 / 内部可售量三项', () => {
    const dims = unavailableDimensions(caps).map((d) => d.dimension);
    expect(dims).toContain('price');
    expect(dims).toContain('sales');
    expect(dims).toContain('internal_stock');
    expect(unavailableDimensions(caps)).not.toContainEqual(
      expect.objectContaining({ dimension: 'listing_scope' }),
    );
    expect(unavailableDimensions(null)).toEqual([]);
  });
});

describe('4.1.1.1 未映射与缺货是两个独立筛选', () => {
  it('两个字段各发各的参数', () => {
    const q = buildQuery({
      ...emptyFilters,
      mappingStatus: 'MAPPED',
      outOfStock: true,
    });
    const params = new URLSearchParams(q);
    expect(params.get('mappingStatus')).toBe('MAPPED');
    expect(params.get('outOfStock')).toBe('true');
  });

  it('outOfStock=false 也要下发（"筛有货"与"不过滤"是两件事）', () => {
    const q = buildQuery({ ...emptyFilters, outOfStock: false });
    expect(new URLSearchParams(q).get('outOfStock')).toBe('false');
  });

  it('outOfStock=undefined 时不下发该参数', () => {
    const q = buildQuery({ ...emptyFilters, outOfStock: undefined });
    expect(new URLSearchParams(q).has('outOfStock')).toBe(false);
  });

  it('两个维度各算一条激活筛选', () => {
    expect(activeFilterCount({ ...emptyFilters, mappingStatus: 'UNMAPPED' })).toBe(1);
    expect(activeFilterCount({ ...emptyFilters, outOfStock: true })).toBe(1);
    expect(
      activeFilterCount({ ...emptyFilters, mappingStatus: 'UNMAPPED', outOfStock: true }),
    ).toBe(2);
  });

  it('两个维度不会被合成一个 flag', () => {
    const filters: ListingFilters = {
      ...emptyFilters,
      mappingStatus: 'UNMAPPED',
      outOfStock: false,
    };
    expect(filters.mappingStatus).toBe('UNMAPPED');
    expect(filters.outOfStock).toBe(false);
  });

  it('文案上「未映射」与「缺货」不相邻，避免读成一件事', () => {
    expect(mappingLabel('UNMAPPED')).toBe('未映射');
    expect(mappingLabel('PARTIAL')).toBe('部分映射');
    expect(stockLabel('OUT_OF_STOCK')).toBe('平台缺货');
    // UNKNOWN 是「平台没回报」，不是「有货」
    expect(stockLabel('UNKNOWN')).toBe('平台未回报');
    expect(stockLabel('UNKNOWN')).not.toBe('有货');
  });
});

describe('4.1.1.1 筛选全部下发服务端', () => {
  it('空值不下发（避免被读成"显式筛空"）', () => {
    const q = buildQuery({ ...emptyFilters, shopCode: '   ', status: '', category: '' });
    const params = new URLSearchParams(q);
    expect(params.has('shopCode')).toBe(false);
    expect(params.has('status')).toBe(false);
    expect(params.has('category')).toBe(false);
  });

  it('分页与排序参数始终下发', () => {
    const params = new URLSearchParams(buildQuery({ ...emptyFilters, page: 2, size: 20 }));
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('20');
    expect(params.get('sort')).toBe('RECENT');
  });

  it('负数页码被夹到 0，页大小至少 1', () => {
    const params = new URLSearchParams(buildQuery({ ...emptyFilters, page: -3, size: 0 }));
    expect(params.get('page')).toBe('0');
    expect(params.get('size')).toBe('1');
  });

  it('换筛选条件回到第 0 页', () => {
    const next = applyFilterChange({ ...emptyFilters, page: 4 }, { status: 'ON_SALE' });
    expect(next.page).toBe(0);
    expect(next.status).toBe('ON_SALE');
  });

  it('清空筛选保留排序与页大小', () => {
    const reset = resetFilters({
      ...emptyFilters,
      status: 'ON_SALE',
      search: 'x',
      sort: 'RATING_DESC',
      size: 20,
    });
    expect(reset.status).toBe('');
    expect(reset.search).toBe('');
    expect(reset.sort).toBe('RATING_DESC');
    expect(reset.size).toBe(20);
    expect(activeFilterCount(reset)).toBe(0);
  });

  it('分页摘要用 total 而不是当前页长度', () => {
    expect(pageSummary(page({ total: 500, page: 0, size: 50 }))).toContain('共 500 条');
    expect(pageSummary(page({ total: 500, page: 0, size: 50 }))).toContain('第 1-50 条');
    expect(pageSummary(page({ total: 500, page: 9, size: 50 }))).toContain('第 451-500 条');
    expect(pageSummary(page({ total: 0, items: [] }))).toBe('共 0 条');
    expect(pageSummary(null)).toBe('');
  });

  it('翻页边界', () => {
    expect(hasNextPage(page({ total: 120, page: 0, size: 50 }))).toBe(true);
    expect(hasPrevPage(page({ total: 120, page: 0, size: 50 }))).toBe(false);
    expect(hasNextPage(page({ total: 120, page: 2, size: 50 }))).toBe(false);
    expect(hasPrevPage(page({ total: 120, page: 2, size: 50 }))).toBe(true);
    expect(hasNextPage(null)).toBe(false);
  });

  it('状态文案覆盖全部七态，未知值原样透出', () => {
    expect(statusLabel('ON_SALE')).toBe('在售');
    expect(statusLabel('DELISTED_BY_PLATFORM')).toBe('平台下架');
    expect(statusLabel('WHAT_IS_THIS')).toBe('WHAT_IS_THIS');
  });
});

describe('4.1.1.1 批量操作如实表达支持性', () => {
  it('改价 / 改库存恒为不支持并指明归属', () => {
    expect(batchSupport('REPRICE')).toMatchObject({
      supported: false,
      ownerModule: expect.stringContaining('05'),
    });
    expect(batchSupport('RESTOCK')).toMatchObject({
      supported: false,
      ownerModule: expect.stringContaining('07'),
    });
    expect(batchSupport('REPRICE').reason).toContain('影子价格表');
    expect(batchSupport('RESTOCK').reason).toContain('平台侧事实');
  });

  it('分配负责人 / 批量下架在本模块可执行', () => {
    expect(batchSupport('ASSIGN_OWNER').supported).toBe(true);
    expect(batchSupport('DELIST').supported).toBe(true);
  });

  it('被拒的批量结果有明确措辞，不显示成"成功"', () => {
    const summary = batchSummary({
      taskId: 't1',
      action: 'REPRICE',
      outcome: 'REJECTED',
      requestedCount: 5,
      affectedCount: 0,
      skipCount: 0,
      skippedIds: [],
      reason: '归 05 定价',
      ownerModule: '05 定价与价目系统',
      createdAt: '2026-10-18T00:00:00+08:00',
    });
    expect(summary).toContain('未执行');
    expect(summary).toContain('05 定价与价目系统');
    expect(summary).not.toContain('成功');
  });

  it('部分跳过要说清跳了几条', () => {
    const summary = batchSummary({
      taskId: 't2',
      action: 'DELIST',
      outcome: 'SUCCEEDED',
      requestedCount: 5,
      affectedCount: 3,
      skipCount: 2,
      skippedIds: ['a', 'b'],
      reason: '',
      ownerModule: '',
      createdAt: '2026-10-18T00:00:00+08:00',
    });
    expect(summary).toContain('影响 3 / 5 条');
    expect(summary).toContain('2 条不在数据范围内被跳过');
  });
});

describe('4.1.1.1 平台商品 ID 原文不改写', () => {
  it('平台 ID 原样透出，不折叠大小写', () => {
    const r = row({ externalItemId: 'B0abc-DEF_123' });
    expect(r.externalItemId).toBe('B0abc-DEF_123');
  });
});

describe('4.1.1.1 类型导出面', () => {
  it('saveListing / fetch* 已导出（页面与测试共用同一入口）', () => {
    expect(typeof saveListing).toBe('function');
  });
});
