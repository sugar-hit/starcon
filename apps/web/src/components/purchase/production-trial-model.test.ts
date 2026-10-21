import { describe, expect, it } from 'vitest';
import type { ProductionTrialCostComparisonRow } from '../../../../../packages/api-contracts/src/index';
import {
  describeComparison,
  formatMoney,
  formatQuantity,
  formatVarianceRate,
  formatYieldRate,
  productionTrialBomQuery,
  productionTrialOrderQuery,
} from './production-trial-model';

/**
 * 6.6.2.1 试产单前端模型的展示口径断言。
 *
 * <p>这些口径是「蓝图加粗句」在界面上的落点，断言它们是为了让口径改坏时**先红在这里**，
 * 而不是等到页面上没人看出来。
 *
 * <p>🔴 最要紧的三条：
 *
 * <ol>
 *   <li><b>良率是比例</b>：0.95 就是 95%，显示的百分数只是同一个值的显示格式；
 *       「取不到」显示「—」而**不是 0.00%**。</li>
 *   <li><b>成本不跨币种相加</b>：不可比时显示「不可比」而**不是 0** ——
 *       「量产目标成本取不到」与「量产目标成本是 0」是两件事。</li>
 *   <li><b>金额显示不吃掉整数位的零</b>：{@code 100.0000} 不能显示成 {@code 1}。</li>
 * </ol>
 */

describe('良率口径', () => {
  it('比例显示成百分数（同一个值，不是第二次计算）', () => {
    expect(formatYieldRate(0.95)).toBe('95.00%');
    expect(formatYieldRate(0.98)).toBe('98.00%');
    expect(formatYieldRate(1)).toBe('100.00%');
    expect(formatYieldRate(0)).toBe('0.00%');
  });

  it('取不到时显示破折号，而不是 0.00%', () => {
    expect(formatYieldRate(null)).toBe('—');
    expect(formatYieldRate(undefined)).toBe('—');
  });
});

describe('成本偏差率口径', () => {
  it('比例显示成百分数', () => {
    expect(formatVarianceRate(0.2)).toBe('20.00%');
    expect(formatVarianceRate(-0.05)).toBe('-5.00%');
  });

  it('取不到时显示「不可比」，不是 0.00%', () => {
    expect(formatVarianceRate(null)).toBe('不可比');
    expect(formatVarianceRate(undefined)).toBe('不可比');
  });
});

describe('金额与数量显示', () => {
  it('去掉尾随零但不吃掉整数位的零', () => {
    expect(formatMoney(150, 'USD')).toBe('150 USD');
    expect(formatMoney(100, 'USD')).toBe('100 USD');
    expect(formatMoney(20.5, 'USD')).toBe('20.5 USD');
    expect(formatMoney(0.2, 'USD')).toBe('0.2 USD');
    expect(formatMoney(1000, 'CNY')).toBe('1000 CNY');
  });

  it('取不到时显示破折号，不是 0', () => {
    expect(formatMoney(null, 'USD')).toBe('—');
    expect(formatQuantity(null)).toBe('—');
    expect(formatQuantity(210)).toBe('210');
  });
});

describe('成本对比行', () => {
  const comparable: ProductionTrialCostComparisonRow = {
    currency: 'USD',
    trialCostTotal: 120,
    massTargetCost: 100,
    varianceAmount: 20,
    varianceRate: 0.2,
    comparable: true,
    note: '同一币种内可比',
  };

  it('可比时给出差额与偏差率', () => {
    const described = describeComparison(comparable);
    expect(described.headline).toBe('20 USD（20.00%）');
    expect(described.detail).toBe('同一币种内可比');
  });

  it('不可比时说「不可比」，并保留原因，不显示 0', () => {
    const described = describeComparison({
      currency: 'CNY',
      trialCostTotal: 80,
      massTargetCost: null,
      varianceAmount: null,
      varianceRate: null,
      comparable: false,
      note: '这个币种下没有任何一张试产单填了量产目标成本',
    });
    expect(described.headline).toBe('不可比');
    expect(described.headline).not.toContain('0');
    expect(described.detail).toContain('量产目标成本');
  });
});

describe('查询串', () => {
  it('只拼真正给了的筛选条件', () => {
    expect(productionTrialOrderQuery({})).toBe('');
    expect(productionTrialOrderQuery({ status: 'IN_PROGRESS' })).toBe('?status=IN_PROGRESS');
    expect(productionTrialOrderQuery({ keyword: ' TP-1 ' })).toBe('?keyword=TP-1');
    expect(productionTrialOrderQuery({ page: 2, size: 20 })).toBe('?page=2&size=20');
    expect(
      productionTrialOrderQuery({ status: 'COMPLETED', bomVersionId: 'bom-1', page: 0 }),
    ).toBe('?status=COMPLETED&bomVersionId=bom-1&page=0');
  });

  it('空白关键词不进查询串（不是「搜了个空」）', () => {
    expect(productionTrialOrderQuery({ keyword: '   ' })).toBe('');
    expect(productionTrialBomQuery('  ')).toBe('');
    expect(productionTrialBomQuery('v1')).toBe('?keyword=v1');
  });
});
