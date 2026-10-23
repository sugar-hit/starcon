import { describe, expect, it } from 'vitest';
import type {
  UsageBillingRow,
  UsageCapacityRow,
  UsageCoverageRow,
  UsageReportRow,
  UsageTrendPoint,
} from '../../../../../packages/api-contracts/src/index';
import {
  BILLING_STATE_LABELS,
  COVERAGE_STATE_LABELS,
  PERIOD_TYPES,
  countBillingStates,
  describeBillingState,
  describeCapacity,
  describeCoverage,
  describeReportRow,
  formatPeriodRange,
  formatQuantity,
  formatUtilization,
  groupReportByTenant,
  overCapacityKinds,
  summarizeTrend,
  unwiredKinds,
  validatePeriodType,
} from './usage-stat-model';

/**
 * 17.7.1.2 用量统计的展示口径。
 *
 * <p>重点是守住三条最容易写错的语义：
 *
 * <ol>
 *   <li>**判定不了 ≠ 没超限**：`UNDETERMINED` 与 `WITHIN_LIMIT` 的文案必须完全不同；
 *   <li>**口径跟着套餐定义走**：流量维度的桶粒度固定、存量维度跟随统计周期，
 *       文案要把「哪一档口径」写出来；
 *   <li>**不落金额**：所有文案里只有数量，没有单价与金额。
 * </ol>
 */

const billingRow = (over: Partial<UsageBillingRow>): UsageBillingRow => ({
  tenantId: '11111111-1111-1111-1111-111111111111',
  tenantCode: 'T1',
  tenantName: '租户一',
  planCode: 'STARTER',
  planName: '标准版',
  subscriptionStatus: 'ACTIVE',
  entitlementCode: 'SKU',
  entitlementKind: 'SKU_COUNT',
  entitlementLimit: 1000,
  entitlementUnlimited: false,
  overLimitAction: 'BLOCK',
  entitlementUnit: '个',
  entitlementPeriod: 'TOTAL',
  periodType: null,
  periodStart: null,
  periodEnd: null,
  quantity: null,
  sourceSystem: '',
  measuredAt: null,
  collectedAt: null,
  utilization: null,
  overLimit: false,
  state: 'UNDETERMINED',
  stateNote: '',
  ...over,
});

const reportRow = (over: Partial<UsageReportRow>): UsageReportRow => ({
  tenantId: '11111111-1111-1111-1111-111111111111',
  tenantCode: 'T1',
  tenantName: '租户一',
  subscriptionId: '22222222-2222-2222-2222-222222222222',
  planCode: 'STARTER',
  planName: '标准版',
  subscriptionStatus: 'ACTIVE',
  entitlementCode: 'ORDER',
  entitlementKind: 'ORDER_VOLUME',
  entitlementLimit: 3000,
  entitlementUnlimited: false,
  overLimitAction: 'DEGRADE',
  entitlementUnit: '单',
  entitlementPeriod: 'MONTH',
  periodType: 'MONTH',
  periodStart: '2026-03-01T00:00:00Z',
  periodEnd: '2026-04-01T00:00:00Z',
  quantity: 2500,
  sourceSystem: 'trade',
  measuredAt: '2026-03-20T00:00:00Z',
  collectedAt: '2026-03-20T01:00:00Z',
  utilization: 0.833333,
  overLimit: false,
  ...over,
});

const trendPoint = (over: Partial<UsageTrendPoint>): UsageTrendPoint => ({
  tenantId: '11111111-1111-1111-1111-111111111111',
  tenantCode: 'T1',
  tenantName: '租户一',
  entitlementKind: 'ORDER_VOLUME',
  periodType: 'MONTH',
  periodStart: '2026-01-01T00:00:00Z',
  periodEnd: '2026-02-01T00:00:00Z',
  quantity: 100,
  sourceSystems: 'trade',
  collectedAt: '2026-01-20T00:00:00Z',
  ...over,
});

describe('统计周期校验', () => {
  it('只接受套餐定义认得的四个周期码', () => {
    expect(PERIOD_TYPES).toEqual(['DAY', 'MONTH', 'YEAR', 'TOTAL']);
    for (const period of PERIOD_TYPES) expect(validatePeriodType(period)).toBe('');
    expect(validatePeriodType('month')).toBe('');
    expect(validatePeriodType('WEEK')).toContain('统计周期只能是');
    expect(validatePeriodType('')).toBe('统计周期不能为空');
  });
});

describe('数量与利用率', () => {
  it('整数不带小数点，空值显示占位符', () => {
    expect(formatQuantity(3000)).toBe('3000');
    expect(formatQuantity(0)).toBe('0');
    expect(formatQuantity(null)).toBe('—');
    expect(formatQuantity(undefined)).toBe('—');
  });

  it('利用率保留一位小数，空值返回空串', () => {
    expect(formatUtilization(0.8)).toBe('80.0%');
    expect(formatUtilization(1.5)).toBe('150.0%');
    expect(formatUtilization(null)).toBe('');
  });
});

describe('计费依据：判定不了 ≠ 没超限', () => {
  it('没接通计量的维度写「判定不了」，绝不写成「没超限」', () => {
    const text = describeBillingState(billingRow({ state: 'UNDETERMINED', quantity: null }));
    expect(text).toContain('判定不了');
    // 「不等于『没超限』」是这句话的一部分：判定不了恰恰不是「没超限」。
    expect(text).toContain('不等于「没超限」');
    expect(text).not.toContain('在上限内');
    expect(text).not.toContain('用量 0');
  });

  it('量到了且在上限内，文案给出用量与上限', () => {
    const text = describeBillingState(
      billingRow({ state: 'WITHIN_LIMIT', quantity: 800, entitlementLimit: 1000 }),
    );
    expect(text).toContain('800');
    expect(text).toContain('1000');
    expect(text).not.toContain('判定不了');
  });

  it('超限时带出超限策略', () => {
    const text = describeBillingState(
      billingRow({ state: 'OVER_LIMIT', quantity: 1500, entitlementLimit: 1000 }),
    );
    expect(text).toContain('已超出上限');
    expect(text).toContain('BLOCK');
  });

  it('不限量单独一种说法', () => {
    const text = describeBillingState(
      billingRow({ state: 'UNLIMITED', entitlementUnlimited: true, quantity: 999 }),
    );
    expect(text).toBe('不限量');
  });

  it('状态词表里判定不了与在上限内是两条不同的词', () => {
    expect(BILLING_STATE_LABELS.UNDETERMINED).not.toBe(BILLING_STATE_LABELS.WITHIN_LIMIT);
    expect(BILLING_STATE_LABELS.UNDETERMINED).toBe('判定不了');
  });
});

describe('用量报表摘要', () => {
  it('给出维度、用量、上限与来源系统', () => {
    const text = describeReportRow(reportRow({}));
    expect(text).toContain('订单量');
    expect(text).toContain('2500');
    expect(text).toContain('3000');
    expect(text).toContain('trade');
  });

  it('不限量时上限显示为「不限量」而不是 0', () => {
    const text = describeReportRow(
      reportRow({ entitlementUnlimited: true, entitlementLimit: 0, quantity: 42 }),
    );
    expect(text).toContain('不限量');
    expect(text).not.toContain('0 单');
  });

  it('周期区间文案带上粒度与起止', () => {
    expect(formatPeriodRange('MONTH', '2026-03-01T00:00:00Z', '2026-04-01T00:00:00Z')).toContain(
      '2026-03-01',
    );
    expect(formatPeriodRange('MONTH', '2026-03-01T00:00:00Z', '2026-04-01T00:00:00Z')).toContain(
      '2026-04-01',
    );
    expect(formatPeriodRange('MONTH', null, null)).toBe('月');
  });
});

describe('容量规划', () => {
  const capacity = (over: Partial<UsageCapacityRow>): UsageCapacityRow => ({
    entitlementKind: 'SKU_COUNT',
    subscribedTenantCount: 3,
    measuredTenantCount: 2,
    unmeasuredTenantCount: 1,
    unlimitedTenantCount: 0,
    usedTotal: 800,
    licensedTotal: 1000,
    headroom: 200,
    latestPeriodStart: '2026-03-01T00:00:00Z',
    ...over,
  });

  it('余量为负时明说「已超容量」，不装作正常', () => {
    const text = describeCapacity(capacity({ headroom: -50 }));
    expect(text).toContain('已超容量');
    expect(overCapacityKinds([capacity({ headroom: -50 })])).toEqual(['SKU_COUNT']);
    expect(overCapacityKinds([capacity({})])).toEqual([]);
  });

  it('未接通租户数与不限量租户数都要出现在摘要里', () => {
    const text = describeCapacity(capacity({ unlimitedTenantCount: 2, unmeasuredTenantCount: 1 }));
    expect(text).toContain('不设上限租户 2 个');
    expect(text).toContain('未接通租户 1 个');
  });
});

describe('维度覆盖', () => {
  const coverage = (over: Partial<UsageCoverageRow>): UsageCoverageRow => ({
    entitlementKind: 'AI_USAGE',
    subscribedTenantCount: 2,
    registeredTenantCount: 0,
    measuredTenantCount: 0,
    wiredSourceSystems: '',
    state: 'NOT_WIRED',
    stateNote: '',
    ...over,
  });

  it('没接通的维度如实报「未接通」，不写成用量 0', () => {
    const text = describeCoverage(coverage({}));
    expect(text).toContain('未接通');
    // 计数为 0 是事实（没有租户登记过），但**不能**把这个维度说成「用量 0」。
    expect(text).not.toContain('用量 0');
    expect(text).not.toContain('用量为 0');
    expect(unwiredKinds([coverage({})])).toEqual(['AI_USAGE']);
    expect(COVERAGE_STATE_LABELS.NOT_WIRED).toBe('未接通');
  });

  it('已接通的维度列出来源系统', () => {
    const text = describeCoverage(
      coverage({
        state: 'WIRED',
        registeredTenantCount: 2,
        measuredTenantCount: 2,
        wiredSourceSystems: 'catalog',
      }),
    );
    expect(text).toContain('已接通');
    expect(text).toContain('catalog');
    expect(unwiredKinds([coverage({ state: 'WIRED' })])).toEqual([]);
  });
});

describe('趋势分组', () => {
  it('同一租户同一维度归成一条序列，并按周期升序', () => {
    const groups = summarizeTrend([
      trendPoint({ periodStart: '2026-02-01T00:00:00Z', quantity: 200 }),
      trendPoint({ periodStart: '2026-01-01T00:00:00Z', quantity: 100 }),
      trendPoint({ tenantCode: 'T2', tenantName: '租户二', quantity: 9 }),
    ]);
    expect(groups).toHaveLength(2);
    const first = groups[0];
    expect(first?.tenantCode).toBe('T1');
    expect(first?.points.map((p) => p.quantity)).toEqual([100, 200]);
    expect(first?.points[0]?.label).toContain('2026-01-01');
  });

  it('没有趋势点时返回空数组（不是 null）', () => {
    expect(summarizeTrend([])).toEqual([]);
  });
});

describe('报表分组与状态计数', () => {
  it('按租户分组', () => {
    const groups = groupReportByTenant([
      reportRow({}),
      reportRow({ tenantCode: 'T2', tenantName: '租户二' }),
    ]);
    expect(groups).toHaveLength(2);
    expect(groups[0]?.rows).toHaveLength(1);
  });

  it('计费依据按状态计数', () => {
    const counts = countBillingStates([
      billingRow({ state: 'UNDETERMINED' }),
      billingRow({ state: 'UNDETERMINED' }),
      billingRow({ state: 'WITHIN_LIMIT' }),
    ]);
    expect(counts.UNDETERMINED).toBe(2);
    expect(counts.WITHIN_LIMIT).toBe(1);
  });
});
