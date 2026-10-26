import { describe, expect, it } from 'vitest';
import {
  allocationBatchStatusLabel,
  allocationBasisLabel,
  allocationBasisSourceLabel,
  allocationDimensionLabel,
  allocationUnavailableText,
  formatAllocationAmount,
  formatAllocationMoney,
  formatAllocationPeriod,
  formatAllocationQuantity,
  formatAllocationRatio,
  isPoolAllocated,
  roundingPolicyLabel,
} from './cost-allocation-model';
import type { FinanceCostAllocationResult } from '../../../../../packages/api-contracts/src/index';

/**
 * 12.4.2.2 分摊规则前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>🔴 Σ结果 = 被分摊总额</b>：页面把「被分摊总额 / 分摊总额 / 舍入差」并排显示，
 *       所以金额必须**原样透传**（前端不做任何业务算术），否则那条等式在页面上就不可查；</li>
 *   <li><b>「没分到」≠「摊到 0」</b>：基数不可得的对象必须显示成
 *       「基数不可得，未参与分摊」，而不是一个安静的 0.0000；</li>
 *   <li><b>「算不出」与「0」必须能分开</b>（服务端给 null，前端显示「—」）；</li>
 *   <li>可读名覆盖**全部词表取值**，且不认识的原样返回（新增取值时页面不会显示 undefined）。</li>
 * </ol>
 */

describe('分摊维度的可读名', () => {
  it('蓝图点名的五个维度各有可读名', () => {
    expect(allocationDimensionLabel('SKU')).toBe('按 SKU');
    expect(allocationDimensionLabel('SPU')).toBe('按 SPU');
    expect(allocationDimensionLabel('SHOP')).toBe('按店铺');
    expect(allocationDimensionLabel('ORDER')).toBe('按订单');
    expect(allocationDimensionLabel('CATEGORY')).toBe('按类目');
    expect(allocationDimensionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('措辞是「摊给谁」而不是「已摊给谁」—— 页面不越界下结论', () => {
    for (const dimension of ['SKU', 'SPU', 'SHOP', 'ORDER', 'CATEGORY']) {
      expect(allocationDimensionLabel(dimension)).not.toContain('已');
    }
  });
});

describe('分摊基数的可读名', () => {
  it('蓝图点名的五种基数各有可读名', () => {
    expect(allocationBasisLabel('QUANTITY')).toBe('按数量');
    expect(allocationBasisLabel('AMOUNT')).toBe('按金额');
    expect(allocationBasisLabel('WEIGHT')).toBe('按重量');
    expect(allocationBasisLabel('VOLUME')).toBe('按体积');
    expect(allocationBasisLabel('EQUAL')).toBe('等额');
    expect(allocationBasisLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('舍入差处理的可读名', () => {
  it('五种策略各有可读名', () => {
    expect(roundingPolicyLabel('LARGEST_REMAINDER')).toBe('最大余数优先');
    expect(roundingPolicyLabel('SMALLEST_REMAINDER')).toBe('最小余数优先');
    expect(roundingPolicyLabel('FIRST_TARGET')).toBe('第一个对象');
    expect(roundingPolicyLabel('LAST_TARGET')).toBe('最后一个对象');
    expect(roundingPolicyLabel('SPECIFIED')).toBe('指定对象');
    expect(roundingPolicyLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('批次状态与对象来源的可读名', () => {
  it('批次状态各有可读名', () => {
    expect(allocationBatchStatusLabel('ALLOCATED')).toBe('已分摊');
    expect(allocationBatchStatusLabel('REVERTED')).toBe('已撤销');
    expect(allocationBatchStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('对象来源说明这份基数是从哪个上游事实来的', () => {
    expect(allocationBasisSourceLabel('TRADE_PLATFORM_ORDER')).toBe('平台订单');
    expect(allocationBasisSourceLabel('TRADE_SALES_ORDER_LINE')).toBe('销售订单行');
    expect(allocationBasisSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('金额与数量的可读形态', () => {
  it('原样透传（只做显示格式化），不做任何业务算术', () => {
    expect(formatAllocationAmount(1234.5)).toBe('1234.5000');
    expect(formatAllocationAmount(0)).toBe('0.0000');
    expect(formatAllocationAmount(0.0001)).toBe('0.0001');
    expect(formatAllocationMoney(1234.5, 'USD')).toBe('1234.5000 USD');
    expect(formatAllocationMoney(1234.5, null)).toBe('1234.5000');
    expect(formatAllocationQuantity(3)).toBe('3.000000');
    expect(formatAllocationRatio(0.25)).toBe('25.0000%');
  });

  it('「算不出」显示为破折号，不显示 0', () => {
    expect(formatAllocationAmount(null)).toBe('—');
    expect(formatAllocationAmount(undefined)).toBe('—');
    expect(formatAllocationMoney(null, 'USD')).toBe('—');
    expect(formatAllocationQuantity(null)).toBe('—');
    expect(formatAllocationRatio(null)).toBe('—');
  });
});

describe('归属期间的可读形态', () => {
  it('只拼接服务端给的日历区间，不自己算日期差', () => {
    expect(formatAllocationPeriod('2026-03-01', '2026-03-31')).toBe('2026-03-01 ~ 2026-03-31');
    expect(formatAllocationPeriod(null, '2026-03-31')).toBe('—');
    expect(formatAllocationPeriod('2026-03-01', undefined)).toBe('—');
  });
});

describe('「没分到」的可读形态', () => {
  function result(overrides: Partial<FinanceCostAllocationResult>): FinanceCostAllocationResult {
    return {
      id: 'r1',
      batchId: 'b1',
      targetDimension: 'SKU',
      targetCode: 'SKU-A',
      targetName: '商品 A',
      basisValue: 0,
      basisAvailable: false,
      shareRatio: 0,
      exactAmount: 0,
      allocatedAmount: 0,
      roundingAdjustment: 0,
      isRoundingTarget: false,
      basisNote: '',
      ...overrides,
    } as FinanceCostAllocationResult;
  }

  it('基数不可得 ⇒ 说清「没分到」，不是安静的 0', () => {
    expect(allocationUnavailableText(result({}))).toBe('基数不可得，未参与分摊');
    expect(
      allocationUnavailableText(result({ basisNote: 'catalog 没维护这个 SKU 的包装重量' })),
    ).toContain('catalog 没维护');
  });

  it('基数可得 ⇒ 没有额外说明（不是每一行都要挂一句话）', () => {
    expect(
      allocationUnavailableText(
        result({ basisAvailable: true, allocatedAmount: 12.5, basisValue: 3 }),
      ),
    ).toBe('');
  });
});

describe('池是否已分摊', () => {
  it('读上游 12.4.2.1 留下的那一列（语义：本模块交付后 = 已分摊）', () => {
    expect(isPoolAllocated({ allocationAvailable: true } as never)).toBe(true);
    expect(isPoolAllocated({ allocationAvailable: false } as never)).toBe(false);
  });
});
