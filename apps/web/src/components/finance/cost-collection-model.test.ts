import { describe, expect, it } from 'vitest';
import {
  costAllocationStatusLabel,
  costBasisTypeLabel,
  costDimensionLabel,
  costFeeCategoryLabel,
  costFeeSourceLabel,
  costPeriodBasisLabel,
  formatCostAmount,
  formatCostMoney,
  formatCostPeriod,
} from './cost-collection-model';

/**
 * 12.4.2.1 费用归集前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>归集 ≠ 分摊</b>：维度的可读名必须写成「按…分摊」而不是「已按…分摊」——
 *       本模块只声明下游要按什么维度分摊，分摊结果根本不存在；</li>
 *   <li>金额**原样展示**，前端不做任何业务算术；</li>
 *   <li>「算不出」与「0」必须能分开（服务端给 null，前端显示「—」）；</li>
 *   <li>可读名覆盖**全部词表取值**，且不认识的原样返回（新增取值时页面不会显示 undefined）。</li>
 * </ol>
 */

describe('费用来源的可读名', () => {
  it('七个来源各自有可读名，不认识的原样返回', () => {
    expect(costFeeSourceLabel('PLATFORM_SETTLEMENT')).toBe('平台结算单');
    expect(costFeeSourceLabel('AD_SPEND')).toBe('广告花费');
    expect(costFeeSourceLabel('SUBSCRIPTION')).toBe('订阅');
    expect(costFeeSourceLabel('LOGISTICS_WAYBILL')).toBe('运单运费');
    expect(costFeeSourceLabel('HEAD_MILE_SHIPMENT')).toBe('头程发运单');
    expect(costFeeSourceLabel('PERSONNEL')).toBe('人员费用');
    expect(costFeeSourceLabel('OFFICE')).toBe('办公费用');
    expect(costFeeSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('费用项的可读名', () => {
  it('七个费用项各自有可读名，不认识的原样返回', () => {
    expect(costFeeCategoryLabel('PLATFORM_FEE')).toBe('平台费');
    expect(costFeeCategoryLabel('AD_FEE')).toBe('广告费');
    expect(costFeeCategoryLabel('SUBSCRIPTION_FEE')).toBe('订阅费');
    expect(costFeeCategoryLabel('LOGISTICS_FEE')).toBe('物流费');
    expect(costFeeCategoryLabel('HEAD_MILE_FEE')).toBe('头程费');
    expect(costFeeCategoryLabel('PERSONNEL_COST')).toBe('人员费用');
    expect(costFeeCategoryLabel('OFFICE_COST')).toBe('办公费用');
    expect(costFeeCategoryLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('分摊目标维度的可读名', () => {
  it('四类口径各有可读名', () => {
    expect(costDimensionLabel('MONTH')).toBe('按月分摊');
    expect(costDimensionLabel('ORDER')).toBe('按单分摊');
    expect(costDimensionLabel('SKU')).toBe('按 SKU 分摊');
    expect(costDimensionLabel('PERIOD')).toBe('按期间分摊');
    expect(costDimensionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('措辞是「目标」而不是「已分摊」—— 归集与分摊要分开', () => {
    for (const dimension of ['MONTH', 'ORDER', 'SKU', 'PERIOD']) {
      expect(costDimensionLabel(dimension)).not.toContain('已');
      expect(costDimensionLabel(dimension)).toContain('分摊');
    }
  });
});

describe('归属期间口径与依据类型的可读名', () => {
  it('两档期间口径各有可读名', () => {
    expect(costPeriodBasisLabel('CALENDAR_MONTH')).toBe('自然月');
    expect(costPeriodBasisLabel('ACCOUNTING_PERIOD')).toBe('会计期间');
    expect(costPeriodBasisLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('五种依据类型各有可读名', () => {
    expect(costBasisTypeLabel('SETTLEMENT_LINE')).toBe('结算明细行');
    expect(costBasisTypeLabel('AD_REPORT_ROW')).toBe('广告报表行');
    expect(costBasisTypeLabel('WAYBILL_FREIGHT')).toBe('运单运费登记');
    expect(costBasisTypeLabel('FIRST_MILE_SHIPMENT')).toBe('头程发运单');
    expect(costBasisTypeLabel('MANUAL_DOCUMENT')).toBe('人工单据');
    expect(costBasisTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('金额的可读形态', () => {
  it('原样透传（只做显示格式化），不做任何业务算术', () => {
    expect(formatCostAmount(1234.5)).toBe('1234.5000');
    expect(formatCostAmount(0)).toBe('0.0000');
    expect(formatCostMoney(1234.5, 'USD')).toBe('1234.5000 USD');
    expect(formatCostMoney(1234.5, null)).toBe('1234.5000');
  });

  it('「算不出」显示为破折号，不显示 0', () => {
    expect(formatCostAmount(null)).toBe('—');
    expect(formatCostAmount(undefined)).toBe('—');
    expect(formatCostMoney(null, 'USD')).toBe('—');
    expect(formatCostMoney(undefined, 'USD')).toBe('—');
  });
});

describe('归属期间的可读形态', () => {
  it('只拼接服务端给的日历区间，不自己算日期差', () => {
    expect(formatCostPeriod('2026-03-01', '2026-03-31')).toBe('2026-03-01 ~ 2026-03-31');
    expect(formatCostPeriod(null, '2026-03-31')).toBe('—');
    expect(formatCostPeriod('2026-03-01', undefined)).toBe('—');
  });
});

describe('分摊状态的可读形态', () => {
  it('本模块恒为「未分摊」', () => {
    expect(costAllocationStatusLabel('UNALLOCATED')).toBe('未分摊');
    expect(costAllocationStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});
