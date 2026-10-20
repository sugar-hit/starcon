import { describe, expect, it } from 'vitest';
import {
  allocationStatusLabel,
  allocationStatusTone,
  basisLabel,
  componentLabel,
  componentTone,
  coverageTone,
  currencyRowsWithTotals,
  EMPTY_COST_FILTERS,
  formatAmount,
  formatQuantity,
  formatShare,
  isMissing,
  missingComponentLabels,
  NO_DATA,
  ruleStatusLabel,
  ruleStatusTone,
  sourceKindLabel,
  sourceKindTone,
  toIsoInstant,
  validateCostWindow,
} from './logistics-cost-analysis-model';
import type { CostComponentAmount, CostCurrencyRow } from './logistics-cost-analysis-model';

/**
 * 10.6.1.1 成本分析前端模型。
 *
 * <p>本文件钉住本模块界面上**最容易做假活**的那一处：把「取不到数」画成 0。
 *
 * <p>蓝图加粗句「物流成本常被低估：真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，
 * 报表必须全口径」在界面上的第一道闸就是 {@link formatAmount}：
 * {@code null} ⇒ 「暂无数据」，{@code 0} ⇒ 「0.00」。这两个在界面上必须长得不一样。
 */
describe('10.6.1.1 成本分析模型', () => {
  describe('🔴 null 与 0 是两件事', () => {
    it('null 显示「暂无数据」，0 显示「0.00」', () => {
      expect(formatAmount(null)).toBe(NO_DATA);
      expect(formatAmount(undefined)).toBe(NO_DATA);
      expect(formatAmount(0)).toBe('0.00');
      expect(formatAmount(0, 'USD')).toBe('0.00 USD');
    });

    it('金额带币种且不跨币种相加', () => {
      expect(formatAmount(1234.5, 'EUR')).toBe('1234.50 EUR');
    });

    it('占比与数量同样区分 null 与 0', () => {
      expect(formatShare(null)).toBe(NO_DATA);
      expect(formatShare(0)).toBe('0.00%');
      expect(formatShare(0.333333)).toBe('33.33%');
      expect(formatQuantity(null)).toBe(NO_DATA);
      expect(formatQuantity(0)).toBe('0.000');
      expect(formatQuantity(12.5, 'KG')).toBe('12.500 KG');
    });

    it('isMissing 只把 null / undefined 当缺项', () => {
      expect(isMissing(null)).toBe(true);
      expect(isMissing(undefined)).toBe(true);
      expect(isMissing(0)).toBe(false);
      expect(isMissing(-1)).toBe(false);
    });
  });

  describe('全口径：缺项要能一眼看全', () => {
    it('缺项逐条给出中文标签，界面上不用自己翻译枚举', () => {
      expect(missingComponentLabels(['PACKAGING', 'STORAGE_FEE'])).toEqual(['包材', '仓储费']);
    });

    it('合计为 null 的行不进入「有合计的币种」列表（真空态不是假数据）', () => {
      const rows: CostCurrencyRow[] = [
        {
          currency: 'USD',
          lastMileFreight: 100,
          firstMileAllocation: null,
          dutyAndTax: null,
          packaging: null,
          subtotal: 100,
          lastMileShare: 1,
          firstMileShare: null,
          dutyAndTaxShare: null,
          packagingShare: null,
          includedComponents: ['LAST_MILE_FREIGHT'],
          missingComponents: ['FIRST_MILE_ALLOCATION', 'PACKAGING', 'STORAGE_FEE'],
        },
        {
          currency: 'EUR',
          lastMileFreight: null,
          firstMileAllocation: null,
          dutyAndTax: null,
          packaging: null,
          subtotal: null,
          lastMileShare: null,
          firstMileShare: null,
          dutyAndTaxShare: null,
          packagingShare: null,
          includedComponents: [],
          missingComponents: [
            'LAST_MILE_FREIGHT',
            'FIRST_MILE_ALLOCATION',
            'DUTY_AND_TAX',
            'PACKAGING',
            'STORAGE_FEE',
          ],
        },
      ];
      expect(currencyRowsWithTotals(rows).map((row) => row.currency)).toEqual(['USD']);
    });

    it('覆盖不全时是 warning，不是 danger（缺项是常态，不是故障）', () => {
      expect(coverageTone(false)).toBe('warning');
      expect(coverageTone(true)).toBe('success');
    });
  });

  describe('来源类别：事实与参数必须分色', () => {
    it('参数是 warning，事实是 success', () => {
      expect(sourceKindLabel('PARAMETER')).toBe('参数');
      expect(sourceKindLabel('FACT')).toBe('事实');
      expect(sourceKindTone('PARAMETER')).toBe('warning');
      expect(sourceKindTone('FACT')).toBe('success');
    });

    it('不可用的分量是 danger（优先于来源类别）', () => {
      const unavailable: CostComponentAmount = {
        component: 'STORAGE_FEE',
        displayName: '仓储费',
        available: false,
        sourceKind: 'FACT',
        source: '',
        basis: '',
        ownerModule: '8 仓储作业',
        unavailableReason: '没有仓储费表',
        impact: '缺了这一位',
        byCurrency: [],
        estimatedByCurrency: [],
        currencyCount: 0,
      };
      expect(componentTone(unavailable)).toBe('danger');
    });
  });

  describe('词表', () => {
    it('五个成本分量都有中文标签（闭集五位）', () => {
      expect(componentLabel('LAST_MILE_FREIGHT')).toBe('尾程运费');
      expect(componentLabel('FIRST_MILE_ALLOCATION')).toBe('头程分摊');
      expect(componentLabel('PACKAGING')).toBe('包材');
      expect(componentLabel('DUTY_AND_TAX')).toBe('关税与增值税');
      expect(componentLabel('STORAGE_FEE')).toBe('仓储费');
    });

    it('分摊基准 / 分摊单状态 / 规则状态都有中文标签', () => {
      expect(basisLabel('BY_QUANTITY')).toBe('按发出数量');
      expect(basisLabel('BY_WEIGHT')).toBe('按毛重');
      expect(basisLabel('BY_DECLARED_VALUE')).toBe('按申报价值');
      expect(basisLabel('EQUAL')).toBe('均摊');
      expect(allocationStatusLabel('LOCKED')).toBe('已结账');
      expect(ruleStatusLabel('ACTIVE')).toBe('现行');
    });

    it('未知枚举回落到原值，不返回空串（界面上不该出现空白徽章）', () => {
      expect(componentLabel('WHATEVER')).toBe('WHATEVER');
      expect(basisLabel('WHATEVER')).toBe('WHATEVER');
    });

    it('状态色：已结账是凭据（success），已被取代是留档（neutral）', () => {
      expect(allocationStatusTone('LOCKED')).toBe('success');
      expect(allocationStatusTone('COMPUTED')).toBe('info');
      expect(allocationStatusTone('SUPERSEDED')).toBe('neutral');
      expect(ruleStatusTone('ACTIVE')).toBe('success');
      expect(ruleStatusTone('RETIRED')).toBe('neutral');
    });
  });

  describe('筛选与时间窗', () => {
    it('默认窗口是最近 30 天、倍数 1.5', () => {
      expect(EMPTY_COST_FILTERS.multiplier).toBe('1.5');
      expect(EMPTY_COST_FILTERS.dateFrom).toBe('');
      expect(EMPTY_COST_FILTERS.dateTo).toBe('');
    });

    it('开始晚于结束时报错（不把非法窗口丢给后端）', () => {
      expect(
        validateCostWindow({ ...EMPTY_COST_FILTERS, dateFrom: '2026-03-31', dateTo: '2026-03-01' }),
      ).toContain('时间窗的开始必须早于结束');
      expect(
        validateCostWindow({ ...EMPTY_COST_FILTERS, dateFrom: '2026-03-01', dateTo: '2026-03-31' }),
      ).toEqual([]);
    });

    it('倍数必须是大于 0 的数字', () => {
      expect(validateCostWindow({ ...EMPTY_COST_FILTERS, multiplier: '0' })).toContain(
        '超成本倍数必须是大于 0 的数字',
      );
      expect(validateCostWindow({ ...EMPTY_COST_FILTERS, multiplier: 'abc' })).toContain(
        '超成本倍数必须是大于 0 的数字',
      );
    });

    it('日期输入转成 ISO 时刻：起始是 00:00:00Z，结束是 23:59:59.999Z', () => {
      expect(toIsoInstant('2026-03-01')).toBe('2026-03-01T00:00:00.000Z');
      expect(toIsoInstant('2026-03-31', true)).toBe('2026-03-31T23:59:59.999Z');
      expect(toIsoInstant('')).toBeNull();
    });
  });
});
