import { describe, expect, it } from 'vitest';
import {
  costVarianceBaselineStatusLabel,
  costVarianceBaselineTypeLabel,
  costVarianceComparabilityLabel,
  costVarianceDimensionLabel,
  costVarianceDirectionLabel,
  costVarianceDriverLabel,
  costVariancePeriodLabel,
  costVariancePrimaryDriverLabel,
  costVarianceReportStatusLabel,
  costVarianceSeverityLabel,
  formatCostVarianceMoney,
  formatCostVarianceQuantity,
  formatCostVarianceShare,
  sortCostVarianceDrivers,
  sortCostVarianceItems,
} from './cost-variance-model';
import type { FinanceCostVarianceDriverRow } from '../../../../../packages/api-contracts/src/index';

/**
 * 12.4.1.3 成本差异分析前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>算不出来 ≠ 0</b>：服务端给 {@code null} 的地方前端一律显示「—」，
 *       绝不显示 0.0000（那会被读成「差异是 0」）；</li>
 *   <li><b>无基线不可比 ≠ 差异为 0</b>：可比性的可读名必须写明「不可比 / 不可计算」，
 *       不可计算的类别**不参与排名**（没有名次就没有主因）；</li>
 *   <li><b>多币种绝不相加</b>：前端**不提供**「全部差异合计」，
 *       金额与占比都只在同一币种内成立；</li>
 *   <li>可读名覆盖**全部词表取值**，且不认识的原样返回（新增取值时页面不会显示 undefined）。</li>
 * </ol>
 */

describe('驱动因素与可比性的可读名', () => {
  it('蓝图点名的五类差异各有可读名', () => {
    expect(costVarianceDriverLabel('STANDARD_COST')).toBe('实际 vs 预算/标准成本');
    expect(costVarianceDriverLabel('PURCHASE_PRICE')).toBe('采购价波动');
    expect(costVarianceDriverLabel('FIRST_MILE')).toBe('头程成本波动');
    expect(costVarianceDriverLabel('SHRINKAGE')).toBe('损耗差异');
    expect(costVarianceDriverLabel('FX')).toBe('汇率影响');
    expect(costVarianceDriverLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('可比性写明「不可比 / 不可计算」，不假装算得出来', () => {
    expect(costVarianceComparabilityLabel('COMPARABLE')).toBe('可比');
    expect(costVarianceComparabilityLabel('NO_BASELINE')).toBe('无基线，不可比');
    expect(costVarianceComparabilityLabel('CURRENCY_MISMATCH')).toBe('币种不一致，不可比');
    expect(costVarianceComparabilityLabel('UPSTREAM_UNAVAILABLE')).toBe('上游不可得，不可计算');
    expect(costVarianceComparabilityLabel('NOT_APPLICABLE')).toBe('不适用');
    expect(costVarianceComparabilityLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('基线类型、维度、状态与报表状态各有可读名', () => {
    expect(costVarianceBaselineTypeLabel('STANDARD_COST')).toBe('标准成本');
    expect(costVarianceBaselineTypeLabel('BUDGET')).toBe('预算');
    expect(costVarianceDimensionLabel('SKU')).toBe('按 SKU');
    expect(costVarianceDimensionLabel('CATEGORY')).toBe('按类目');
    expect(costVarianceBaselineStatusLabel('ACTIVE')).toBe('生效中');
    expect(costVarianceBaselineStatusLabel('SUPERSEDED')).toBe('已被接替');
    expect(costVarianceBaselineStatusLabel('ARCHIVED')).toBe('已撤下');
    expect(costVarianceReportStatusLabel('COMPLETED')).toBe('全部可算');
    expect(costVarianceReportStatusLabel('PARTIAL')).toBe('部分不可算');
    expect(costVarianceSeverityLabel('INFO')).toBe('提示');
    expect(costVarianceSeverityLabel('WARNING')).toBe('警告');
    expect(costVarianceSeverityLabel('CRITICAL')).toBe('严重');
  });
});

describe('算不出来不是 0', () => {
  it('金额为空时显示破折号，不显示 0.0000', () => {
    expect(formatCostVarianceMoney(null)).toBe('—');
    expect(formatCostVarianceMoney(undefined)).toBe('—');
    expect(formatCostVarianceMoney(null)).not.toContain('0');
  });

  it('有金额时按四位小数原样展示', () => {
    expect(formatCostVarianceMoney(1120)).toBe('1120.0000');
    expect(formatCostVarianceMoney(9.5)).toBe('9.5000');
  });

  it('数量按六位小数展示', () => {
    expect(formatCostVarianceQuantity(null)).toBe('—');
    expect(formatCostVarianceQuantity(110)).toBe('110.000000');
  });

  it('占比按百分比展示，空值是破折号', () => {
    expect(formatCostVarianceShare(null)).toBe('—');
    expect(formatCostVarianceShare(0.5)).toBe('50.00%');
  });
});

describe('差异的措辞', () => {
  it('正数说「成本更高」，负数说「成本更低」，零说「持平」', () => {
    expect(costVarianceDirectionLabel(90)).toBe('成本更高 90.0000');
    expect(costVarianceDirectionLabel(-175)).toBe('成本更低 175.0000');
    expect(costVarianceDirectionLabel(0)).toBe('与基线持平');
  });

  it('不可计算时明说「不可计算」，不说 0', () => {
    expect(costVarianceDirectionLabel(null)).toBe('不可计算');
    expect(costVarianceDirectionLabel(undefined)).toBe('不可计算');
  });
});

describe('期间的展示', () => {
  it('期间是左闭右开的，措辞要说清「止不含」', () => {
    expect(costVariancePeriodLabel('2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z')).toBe(
      '2026-01-01 起 ~ 2026-02-01 前',
    );
  });

  it('缺一端时显示破折号', () => {
    expect(costVariancePeriodLabel(null, null)).toBe('— 起 ~ — 前');
  });
});

describe('驱动因素的排序与主因', () => {
  function driver(
    driverName: string,
    rank: number | null,
    isPrimary: boolean,
    amount: number | null,
  ): FinanceCostVarianceDriverRow {
    return {
      id: `${driverName}-${rank ?? 'x'}`,
      reportId: 'r',
      currency: 'USD',
      driver: driverName,
      driverLabel: costVarianceDriverLabel(driverName),
      amount,
      quantity: null,
      actualCostAmount: null,
      standardCostAmount: null,
      actualQuantity: null,
      standardQuantity: null,
      comparability: rank === null ? 'UPSTREAM_UNAVAILABLE' : 'COMPARABLE',
      comparabilityLabel: '',
      comparableReason: '',
      rank,
      isPrimary,
      contributionShare: null,
      basis: '',
    };
  }

  it('可比的按名次在前，不可计算的（没有名次）排最后', () => {
    const drivers = [
      driver('PURCHASE_PRICE', null, false, null),
      driver('FIRST_MILE', 1, true, 100),
      driver('SHRINKAGE', 2, false, 20),
    ];
    expect(sortCostVarianceDrivers(drivers).map((d) => d.driver)).toEqual([
      'FIRST_MILE',
      'SHRINKAGE',
      'PURCHASE_PRICE',
    ]);
  });

  it('主因是 rank 1 的那一类，并写明它的金额', () => {
    const drivers = [
      driver('FIRST_MILE', 1, true, 100),
      driver('SHRINKAGE', 2, false, 20),
    ];
    expect(costVariancePrimaryDriverLabel(drivers)).toBe('头程成本波动（100.0000）');
  });

  it('没有主因时返回空串，不硬凑一个', () => {
    const drivers = [driver('PURCHASE_PRICE', null, false, null)];
    expect(costVariancePrimaryDriverLabel(drivers)).toBe('');
  });
});

describe('明细的排序', () => {
  it('不可比的排在最后', () => {
    const items = [
      { comparability: 'NO_BASELINE', driver: 'STANDARD_COST', skuCode: 'SKU-B' },
      { comparability: 'COMPARABLE', driver: 'STANDARD_COST', skuCode: 'SKU-A' },
    ];
    expect(sortCostVarianceItems(items).map((i) => i.skuCode)).toEqual(['SKU-A', 'SKU-B']);
  });

  it('不改动原数组', () => {
    const items = [
      { comparability: 'NO_BASELINE', driver: 'STANDARD_COST', skuCode: 'SKU-B' },
      { comparability: 'COMPARABLE', driver: 'STANDARD_COST', skuCode: 'SKU-A' },
    ];
    const sorted = sortCostVarianceItems(items);
    expect(items[0]?.comparability).toBe('NO_BASELINE');
    expect(sorted[0]?.comparability).toBe('COMPARABLE');
  });
});
