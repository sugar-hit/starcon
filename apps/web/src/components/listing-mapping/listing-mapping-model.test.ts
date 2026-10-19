import { describe, expect, it } from 'vitest';
import {
  activeFilterCount,
  applyFilterChange,
  buildQuery,
  decisionLabel,
  emptyFilters,
  hasNextPage,
  hasPrevPage,
  isPending,
  mappingStateLabel,
  mappingStatusLabel,
  pageSummary,
  relationLabel,
  resetFilters,
  scoreText,
  sortLabel,
  unavailableDimensions,
  unmappedSummary,
  type MappingCandidate,
  type MappingCapabilities,
  type MappingFilters,
  type MappingPage,
  type UnmappedPage,
} from './listing-mapping-model';

/**
 * 4.1.2.1 映射管理 前端模型测试。
 *
 * <p>本文件钉四组事：
 *
 * <ol>
 *   <li><b>筛选全部下发服务端</b>：空值不下发、换条件回到第 0 页、每个维度各算一条</li>
 *   <li><b>候选不是映射</b>：只有 PENDING 的候选可以裁决；标题相似度恒带「需人工确认」</li>
 *   <li><b>分数不被前端改写</b>：精确命中与相似度分数分开表达，不换算成百分比</li>
 *   <li><b>未交付段如实呈现</b>：capabilities 里 available=false 的维度逐条列出</li>
 * </ol>
 */

const caps: MappingCapabilities = {
  dimensions: [
    {
      dimension: 'mapping_crud',
      description: '映射的建立 / 修改 / 解除',
      available: true,
      source: 'listing_mapping',
      ownerModule: '',
      impact: '',
    },
    {
      dimension: 'affected_orders',
      description: '按版本回溯受影响订单范围',
      available: false,
      source: '—',
      ownerModule: '4.1.2.2 映射版本',
      impact: '无法回答「改这条映射会波及哪些已产生的订单」',
    },
    {
      dimension: 'unmapped_backflow',
      description: '未映射清单回流 02 映射缺失队列',
      available: false,
      source: 'listing_variant',
      ownerModule: '2.4.2.3 映射缺失队列',
      impact: '未映射的变体不会自动进入运营的待办队列',
    },
  ],
  relationTypes: ['MANY_TO_ONE', 'ONE_TO_MANY', 'ONE_TO_ONE'],
  mappingStatuses: ['BUNDLE', 'MAPPED'],
  matchRules: ['BARCODE', 'SKU_CODE', 'TITLE_SIMILARITY'],
  candidateDecisions: ['CONFIRMED', 'PENDING', 'REJECTED'],
  resolveStatuses: ['PENDING_MAPPING', 'RESOLVED'],
  maxVariantsPerBatch: 200,
  maxCandidateSkus: 500,
  similarityThreshold: 0.25,
  gapNote: '🔴 本批次未交付段：按版本回溯受影响订单（4.1.2.2）需要 09 订单的真实来源',
  dependencyNote: '上游：4.1.1.1 的 listing / listing_variant',
};

function candidate(over: Partial<MappingCandidate> = {}): MappingCandidate {
  return {
    id: 'c-1',
    batchTaskId: 'b-1',
    listingId: 'l-1',
    listingVariantId: 'v-1',
    externalVariantId: 'V-1',
    rule: 'TITLE_SIMILARITY',
    internalSkuId: 'sku-1',
    internalSkuCode: 'SKU-001',
    internalSkuName: '无线蓝牙耳机',
    matchedText: '无线蓝牙耳机',
    score: 0.6234,
    requiresConfirmation: true,
    decision: 'PENDING',
    decidedBy: null,
    decidedAt: null,
    decisionReason: '',
    createdAt: '2026-10-05T10:00:00+08:00',
    ...over,
  };
}

describe('4.1.2.1 筛选全部下发服务端', () => {
  it('空值不下发：避免后端把 ?status= 读成「显式筛选空状态」', () => {
    const query = buildQuery(emptyFilters);
    expect(query).not.toContain('relationType=');
    expect(query).not.toContain('status=');
    expect(query).not.toContain('search=');
    expect(query).toContain('sort=RECENT');
    expect(query).toContain('page=0');
    expect(query).toContain('size=50');
  });

  it('每个筛选维度各发各的参数', () => {
    const filters: MappingFilters = {
      ...emptyFilters,
      relationType: 'ONE_TO_MANY',
      mappingStatus: 'BUNDLE',
      status: 'ACTIVE',
      internalSkuId: 'sku-1',
      shopCode: 'AMZ-1',
      search: '耳机',
    };
    const query = buildQuery(filters);
    expect(query).toContain('relationType=ONE_TO_MANY');
    expect(query).toContain('mappingStatus=BUNDLE');
    expect(query).toContain('status=ACTIVE');
    expect(query).toContain('internalSkuId=sku-1');
    expect(query).toContain('shopCode=AMZ-1');
    expect(query).toContain('search=%E8%80%B3%E6%9C%BA');
    // 六个维度各算一条
    expect(activeFilterCount(filters)).toBe(6);
  });

  it('换筛选条件回到第 0 页：停在第 3 页看新结果永远是空的', () => {
    const filters: MappingFilters = { ...emptyFilters, page: 3 };
    expect(applyFilterChange(filters, { shopCode: 'X' }).page).toBe(0);
  });

  it('清空筛选保留排序与分页大小', () => {
    const filters: MappingFilters = { ...emptyFilters, sort: 'VERSION', size: 20, page: 2 };
    const cleared = resetFilters(filters);
    expect(cleared.sort).toBe('VERSION');
    expect(cleared.size).toBe(20);
    expect(cleared.page).toBe(0);
    expect(activeFilterCount(cleared)).toBe(0);
  });
});

describe('4.1.2.1 候选不是映射', () => {
  it('只有 PENDING 的候选可以裁决 —— 已裁决的重复裁决会被服务端拒（409）', () => {
    expect(isPending(candidate())).toBe(true);
    expect(isPending(candidate({ decision: 'CONFIRMED' }))).toBe(false);
    expect(isPending(candidate({ decision: 'REJECTED' }))).toBe(false);
  });

  it('标题相似度恒带「需人工确认」，精确命中不带', () => {
    expect(candidate().requiresConfirmation).toBe(true);
    expect(
      candidate({ rule: 'SKU_CODE', requiresConfirmation: false }).requiresConfirmation,
    ).toBe(false);
  });

  it('裁决状态有中文文案', () => {
    expect(decisionLabel('PENDING')).toBe('待确认');
    expect(decisionLabel('CONFIRMED')).toBe('已确认');
    expect(decisionLabel('REJECTED')).toBe('已否决');
  });
});

describe('4.1.2.1 分数不被前端改写', () => {
  it('精确匹配显示「精确命中」，相似度保留 PG 真算值的小数位', () => {
    expect(scoreText(candidate({ rule: 'SKU_CODE' }))).toBe('精确命中');
    expect(scoreText(candidate({ rule: 'BARCODE' }))).toBe('精确命中');
    // 🔴 不换算成百分比：当时算出来的那个数必须能复核
    expect(scoreText(candidate())).toBe('0.6234');
  });
});

describe('4.1.2.1 未交付段如实呈现', () => {
  it('available=false 的维度逐条列出（归属模块与影响都不为空）', () => {
    const missing = unavailableDimensions(caps);
    expect(missing.map((d) => d.dimension)).toEqual(['affected_orders', 'unmapped_backflow']);
    for (const d of missing) {
      expect(d.ownerModule).not.toBe('');
      expect(d.impact).not.toBe('');
    }
  });

  it('能力声明为空时返回空清单而不是编一条出来', () => {
    expect(unavailableDimensions(null)).toEqual([]);
  });
});

describe('4.1.2.1 文案与分页摘要', () => {
  it('映射基数 / 状态 / 存续都有中文文案', () => {
    expect(relationLabel('ONE_TO_ONE')).toBe('一对一');
    expect(relationLabel('ONE_TO_MANY')).toBe('一对多（套装）');
    expect(relationLabel('MANY_TO_ONE')).toBe('多对一');
    expect(mappingStatusLabel('MAPPED')).toBe('已映射');
    expect(mappingStatusLabel('BUNDLE')).toBe('套装映射');
    expect(mappingStatusLabel('UNMAPPED')).toBe('未映射');
    expect(mappingStateLabel('ACTIVE')).toBe('生效中');
    expect(mappingStateLabel('REMOVED')).toBe('已解除');
    expect(sortLabel('RECENT')).toBe('最近更新');
  });

  it('分页摘要用 total，不用本页条数', () => {
    const page: MappingPage = {
      items: [],
      total: 120,
      page: 1,
      size: 50,
      removedInPage: 0,
    };
    expect(pageSummary(page)).toBe('共 120 条，当前显示第 51-100 条');
    expect(hasNextPage(page)).toBe(true);
    expect(hasPrevPage(page)).toBe(true);
    expect(pageSummary(null)).toBe('');
  });

  it('未映射清单摘要用 total，0 条是真空状态', () => {
    const empty: UnmappedPage = {
      items: [],
      total: 0,
      page: 0,
      size: 50,
      backflowDelivered: false,
      backflowOwnerModule: '2.4.2.3 映射缺失队列',
      backflowNote: '',
    };
    expect(unmappedSummary(empty)).toBe('没有未映射的平台变体');
    expect(unmappedSummary({ ...empty, total: 7 })).toBe('共 7 个未映射的平台变体');
    expect(unmappedSummary(null)).toBe('');
  });
});
