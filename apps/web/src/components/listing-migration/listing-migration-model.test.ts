import { describe, expect, it } from 'vitest';
import {
  batchStateLabel,
  buildExecuteRequest,
  buildFilterRequest,
  buildPreviewRequest,
  categoryOptionLabel,
  dimensionReport,
  EMPTY_DRAFT,
  itemStateLabel,
  itemStateTone,
  MAX_RISK_OPTIONS,
  parseDecimal,
  rangeIsValid,
  riskLevelLabel,
  riskLevelTone,
  riskScoreLabel,
  ruleLabel,
  similarityLabel,
  SORT_OPTIONS,
  targetDraftBadge,
  targetShopOptions,
  UNKNOWN_POLICY_OPTIONS,
  type ListingMigrationItemRow,
  type MigrationDraft,
  type ShopView,
} from './listing-migration-model';

/**
 * 4.4.2.2 批量搬迁 前端纯函数单测。
 *
 * <p>这些函数是页面上"最容易做丢的那几件事"的可执行版本：
 * 未知不是 0、标题差异化是流程的一部分、风险与已搬迁分开、幂等键由后端派生。
 */

function shop(id: string, code: string, status = 'ACTIVE'): ShopView {
  return {
    id,
    platformId: 'p-1',
    platformCode: 'AMAZON',
    platformName: 'Amazon',
    connectionId: 'c-1',
    connectionAccount: 'acct',
    legalEntityId: 'le-1',
    code,
    name: `店铺 ${code}`,
    platformShopId: 'shop-1',
    operatingMode: 'SELF_FULFILLED',
    ownerId: 'o-1',
    departmentId: 'd-1',
    status,
    version: 0,
    marketplaceCount: 1,
    marketplaces: [
      {
        marketplaceId: 'm-us',
        marketplaceCode: 'US',
        marketplaceName: '美国站',
        status: 'ACTIVE',
        enabledAt: '2026-01-01T00:00:00Z',
      },
    ],
  } as ShopView;
}

function draft(overrides: Partial<MigrationDraft> = {}): MigrationDraft {
  return { ...EMPTY_DRAFT, sourceShopId: 'shop-a', targetShopIds: ['shop-b'], ...overrides };
}

describe('4.4.2.2 未知值不是一个 0', () => {
  it('价格 / 销量输入：空 = 不参与过滤，非法 = 明确判为非法', () => {
    expect(parseDecimal('')).toBeNull();
    expect(parseDecimal('   ')).toBeNull();
    expect(parseDecimal('10')).toBe(10);
    expect(parseDecimal('10.25')).toBe(10.25);
    expect(parseDecimal('10.25678')).toBeUndefined();
    expect(parseDecimal('-5')).toBeUndefined();
    expect(parseDecimal('1e3')).toBeUndefined();
    expect(parseDecimal('abc')).toBeUndefined();
  });

  it('区间倒置判为非法，单边留空是合法的', () => {
    expect(rangeIsValid('', '')).toBe(true);
    expect(rangeIsValid('10', '')).toBe(true);
    expect(rangeIsValid('', '10')).toBe(true);
    expect(rangeIsValid('10', '20')).toBe(true);
    expect(rangeIsValid('20', '10')).toBe(false);
    expect(rangeIsValid('abc', '20')).toBe(false);
  });

  it('未知值策略的三个取值与后端一致', () => {
    expect(UNKNOWN_POLICY_OPTIONS.map((o) => o.value)).toEqual(['EXCLUDE', 'INCLUDE', 'ONLY']);
  });

  it('重复度分值为空时写"无法判定"，不写 0', () => {
    expect(riskScoreLabel(null, 'UNKNOWN')).toContain('不是 0 分');
    expect(riskScoreLabel(undefined, 'UNKNOWN')).toContain('不是 0 分');
    expect(riskScoreLabel(0.8712, 'CRITICAL')).toContain('87.1%');
  });

  it('分量为空时显示"未知"，不是 0%', () => {
    expect(similarityLabel(null)).toBe('未知');
    expect(similarityLabel(undefined)).toBe('未知');
    expect(similarityLabel(0.5)).toBe('50.0%');
  });

  it('UNKNOWN 等级的文案是"无法判定"，不是"低"', () => {
    expect(riskLevelLabel('UNKNOWN')).toContain('无法判定');
    expect(riskLevelTone('UNKNOWN')).toBe('neutral');
    expect(riskLevelTone('CRITICAL')).toBe('danger');
  });
});

describe('4.4.2.2 筛选规则', () => {
  it('没选源店铺时不产生请求', () => {
    expect(buildFilterRequest(draft({ sourceShopId: '' }))).toBeNull();
  });

  it('价格区间倒置时不产生请求', () => {
    expect(buildFilterRequest(draft({ priceMin: '20', priceMax: '10' }))).toBeNull();
  });

  it('销量区间倒置时不产生请求', () => {
    expect(buildFilterRequest(draft({ salesMin: '20', salesMax: '10' }))).toBeNull();
  });

  it('合法时把区间、类目、排序与策略如实下发', () => {
    const request = buildFilterRequest(
      draft({
        categoryPaths: ['Electronics > Audio'],
        priceMin: '5',
        priceMax: '20',
        unknownPolicy: 'INCLUDE',
        sortBy: 'TITLE',
        sortOrder: 'ASC',
      }),
    );
    expect(request).not.toBeNull();
    expect(request!.sourceShopId).toBe('shop-a');
    expect(request!.categoryPaths).toEqual(['Electronics > Audio']);
    expect(request!.priceMin).toBe(5);
    expect(request!.priceMax).toBe(20);
    expect(request!.salesMin).toBeNull();
    expect(request!.unknownPolicy).toBe('INCLUDE');
    expect(request!.sortBy).toBe('TITLE');
    expect(request!.sortOrder).toBe('ASC');
  });

  it('排序维度的取值域与后端一致', () => {
    expect(SORT_OPTIONS.map((o) => o.value)).toEqual([
      'UPDATED_AT',
      'CREATED_AT',
      'TITLE',
      'PRICE',
      'SALES',
    ]);
  });

  it('类目选项的显示名：路径优先，没有路径时退回平台类目 ID', () => {
    expect(categoryOptionLabel({ categoryPath: 'A > B', platformCategoryId: 'c-1', listingCount: 2 })).toBe(
      'A > B',
    );
    expect(categoryOptionLabel({ categoryPath: '', platformCategoryId: 'c-1', listingCount: 2 })).toBe(
      'c-1',
    );
    expect(categoryOptionLabel({ categoryPath: '', platformCategoryId: '', listingCount: 2 })).toBe(
      '（未登记类目）',
    );
  });
});

describe('4.4.2.2 预览与搬迁请求', () => {
  it('没有目标店铺时不能预览也不能搬迁', () => {
    expect(buildPreviewRequest(draft({ targetShopIds: [] }))).toBeNull();
    expect(buildExecuteRequest(draft({ targetShopIds: [] }))).toBeNull();
  });

  it('预览请求带筛选与目标店铺', () => {
    const request = buildPreviewRequest(draft());
    expect(request).not.toBeNull();
    expect(request!.targets).toEqual([{ shopId: 'shop-b', marketplaceId: null }]);
  });

  it('搬迁请求带上重复度控制参数', () => {
    const request = buildExecuteRequest(draft({ maxRiskLevel: 'MEDIUM', onUnknown: 'SKIP' }));
    expect(request).not.toBeNull();
    expect(request!.duplicatePolicy?.maxRiskLevel).toBe('MEDIUM');
    expect(request!.duplicatePolicy?.onUnknown).toBe('SKIP');
  });

  it('标题模板留空时下发 null（后端按默认规则处理）', () => {
    expect(buildExecuteRequest(draft())!.duplicatePolicy?.titleTemplate).toBeNull();
    expect(
      buildExecuteRequest(draft({ titleTemplate: '  {storeName} - {title}  ' })!)!.duplicatePolicy
        ?.titleTemplate,
    ).toBe('{storeName} - {title}');
  });

  it('幂等键由后端派生，页面上不自带（避免"换个键就多搬一次"）', () => {
    expect(buildExecuteRequest(draft())!.idempotencyKey).toBeNull();
  });

  it('重复度控制参数的取值域与后端一致', () => {
    expect(MAX_RISK_OPTIONS.map((o) => o.value)).toEqual([
      'NONE',
      'LOW',
      'MEDIUM',
      'HIGH',
      'CRITICAL',
    ]);
  });
});

describe('4.4.2.2 目标店铺与状态文案', () => {
  it('目标店铺排除源店铺与已停用的店铺', () => {
    const shops = [shop('shop-a', 'A'), shop('shop-b', 'B'), shop('shop-c', 'C', 'SUSPENDED')];
    const options = targetShopOptions(shops, 'shop-a');
    expect(options.map((s) => s.id)).toEqual(['shop-b']);
  });

  it('搬迁结果状态与批次状态的中文', () => {
    expect(itemStateLabel('MIGRATED')).toBe('已搬迁');
    expect(itemStateLabel('SKIPPED')).toBe('已跳过');
    expect(itemStateLabel('FAILED')).toBe('失败');
    expect(itemStateTone('MIGRATED')).toBe('success');
    expect(itemStateTone('SKIPPED')).toBe('warning');
    expect(itemStateTone('FAILED')).toBe('danger');
    expect(batchStateLabel('EXECUTED')).toBe('全部搬迁');
    expect(batchStateLabel('PARTIAL')).toContain('部分搬迁');
    expect(batchStateLabel('FAILED')).toBe('全部失败');
  });

  it('没有目标草稿时不写"草稿 · 尚未过平台审核"', () => {
    const base = {
      itemId: 'i-1',
      batchId: 'b-1',
      sourceListingId: 'l-1',
      sourceTitle: '源',
      sourceExternalItemId: 'e-1',
      sourceShopId: 's-1',
      shopCode: 'S1',
      targetShopId: 's-2',
      shopName: '目标店铺',
      targetMarketplaceId: 'm-1',
      targetMarketplaceCode: 'US',
      targetListingId: null,
      targetExternalItemId: '',
      originalTitle: '源',
      differentiatedTitle: '',
      titleDifferentiated: false,
      state: 'SKIPPED',
      skipReason: 'ALREADY_MIGRATED',
      failureReason: null,
      duplicateLevel: 'LOW',
      duplicateScore: 0.1,
      duplicateBasis: null,
      publishTaskId: null,
      actorId: 'u-1',
      createdAt: '2026-10-20T00:00:00Z',
      diffs: [],
    } as unknown as ListingMigrationItemRow;
    expect(targetDraftBadge(base)).toBe('未产生目标草稿');
    expect(targetDraftBadge({ ...base, targetListingId: 'l-2' })).toContain('尚未过平台审核');
  });

  it('差异化规则的中文', () => {
    expect(ruleLabel('STORE_MARKER')).toBe('店铺标识');
    expect(ruleLabel('COLLISION_RESOLVE')).toBe('同名消歧');
    expect(ruleLabel('BULLET_STORE_MARKER')).toBe('卖点差异化');
  });
});

describe('4.4.2.2 筛选说明', () => {
  it('取某个维度的报告', () => {
    const explanation = {
      baseCount: 3,
      matchedCount: 1,
      dimensions: [
        {
          dimension: 'PRICE',
          applied: true,
          sourceAvailable: true,
          source: 'writeback_target.current_price',
          ownerModule: '',
          unknownPolicy: 'EXCLUDE',
          matchedCount: 1,
          unknownCount: 2,
          note: '按价格区间过滤',
        },
      ],
      note: '说明',
    };
    expect(dimensionReport(explanation, 'PRICE')?.unknownCount).toBe(2);
    expect(dimensionReport(explanation, 'SALES')).toBeNull();
    expect(dimensionReport(null, 'PRICE')).toBeNull();
  });
});
