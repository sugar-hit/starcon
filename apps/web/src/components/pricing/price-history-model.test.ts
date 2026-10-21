import { describe, expect, it } from 'vitest';

import {
  CHANGE_SOURCES,
  DIRECTIONS,
  EMPTY_CHANGE_FILTERS,
  EMPTY_COMPARISON_FILTERS,
  REASON_CATEGORIES,
  SORT_OPTIONS,
  changeSourceLabel,
  directionLabel,
  directionTone,
  effectiveText,
  listingText,
  moneyText,
  moneyWithCurrency,
  priceMoveText,
  profitDeltaText,
  profitText,
  ratioText,
  salesDeltaText,
  salesText,
  signedMoneyText,
  signedRatioText,
  timeText,
  validateChange,
  validateReason,
} from './price-history-model';
import type { ChangeInput } from './price-history-model';

/**
 * 5.5.1.1 价格历史前端模型测试。
 *
 * <p>🔴 本文件最要紧的四组断言，都是「页面上说错话比不显示更糟」的地方：
 *
 * <ol>
 *   <li><b>null 不渲染成 0</b>：销量未接线显示「未接线」、利润没测准显示「未测准」、
 *       没有旧价显示「首次定价」—— 三者都**不是** 0。
 *   <li><b>「降价有没有用」不能被一个凭空造出来的销量 0 骗了</b>：
 *       销量不可用时，差额也必须是「未接线」。
 *   <li><b>首次定价不是「从 0 涨到新价」</b>：页面上不能出现 100% 的假涨幅。
 *   <li><b>方向与语气要一眼可辨</b>：涨价 / 降价 / 首次定价三者的语气不能一样。
 * </ol>
 */
describe('5.5.1.1 价格历史前端模型', () => {
  describe('文案与语气', () => {
    it('变更来源各有中文名', () => {
      expect(changeSourceLabel('MANUAL_ADJUSTMENT')).toBe('人工调价');
      expect(changeSourceLabel('BATCH_ADJUSTMENT')).toBe('批量调价');
      expect(changeSourceLabel('PRICE_RULE')).toBe('价格规则自动调价');
      expect(changeSourceLabel('PRICE_LIST')).toBe('价目表改价');
      expect(changeSourceLabel('PLATFORM_FEEDBACK')).toBe('平台价格回流');
      expect(changeSourceLabel('IMPORT')).toBe('批量导入');
      expect(changeSourceLabel('OTHER')).toBe('其它来源');
      expect(changeSourceLabel('')).toBe('未标注');
    });

    it('涨价 / 降价的语气必须一眼可辨', () => {
      expect(directionTone('UP')).toBe('danger');
      expect(directionTone('DOWN')).toBe('info');
      expect(directionTone('INITIAL')).toBe('neutral');
      expect(directionTone('UNCHANGED')).toBe('neutral');
    });

    it('方向各有中文名', () => {
      expect(directionLabel('INITIAL')).toBe('首次定价');
      expect(directionLabel('UP')).toBe('涨价');
      expect(directionLabel('DOWN')).toBe('降价');
      expect(directionLabel('UNCHANGED')).toBe('价格未变');
    });

    it('词表与后端枚举逐字对齐', () => {
      expect(CHANGE_SOURCES.map((s) => s.code)).toEqual([
        'MANUAL_ADJUSTMENT',
        'BATCH_ADJUSTMENT',
        'PRICE_RULE',
        'PRICE_LIST',
        'PLATFORM_FEEDBACK',
        'IMPORT',
        'OTHER',
      ]);
      expect(DIRECTIONS.map((d) => d.code)).toEqual(['INITIAL', 'UP', 'DOWN', 'UNCHANGED']);
      expect(REASON_CATEGORIES.map((c) => c.code)).toEqual([
        'COMPETITION',
        'COST',
        'STOCK',
        'LIFECYCLE',
        'PROMOTION',
        'SEASONAL',
        'MANUAL',
        'OTHER',
      ]);
      expect(SORT_OPTIONS.map((s) => s.code)).toContain('EFFECTIVE_AT_DESC');
      expect(SORT_OPTIONS.map((s) => s.code)).toContain('EFFECTIVE_AT_ASC');
    });
  });

  describe('🔴 null 绝不渲染成 0', () => {
    it('没登记的价显示「未登记」而不是 0.00', () => {
      expect(moneyText(null)).toBe('未登记');
      expect(moneyText('')).toBe('未登记');
      expect(moneyText(undefined)).toBe('未登记');
      expect(moneyText('10.0000')).toBe('10.0000');
    });

    it('带币种的金额在取不到时也不显示「币种 0」', () => {
      expect(moneyWithCurrency(null, 'USD')).toBe('未登记');
      expect(moneyWithCurrency('19.99', 'USD')).toBe('USD 19.99');
    });

    it('🔴 销量未接线显示「未接线」，绝不是 0', () => {
      expect(salesText(null, false)).toBe('未接线');
      expect(salesText('0', false)).toBe('未接线');
      expect(salesText('12', true)).toBe('12');
      // 已接线但上游确实没有销量 ⇒ 仍然不是 0，而是「未接线」。
      expect(salesText(null, true)).toBe('未接线');
    });

    it('🔴 利润没测准显示「未测准」，与「毛利刚好 0」分得开', () => {
      expect(profitText(null, false, '成本构成不全')).toBe('未测准（成本构成不全）');
      expect(profitText(null, false, '')).toBe('未测准');
      expect(profitText('0.0000', true, '')).toBe('0.0000');
      expect(profitText('12.3400', true, '')).toBe('12.3400');
    });

    it('🔴 利润差额在没测准时也是「未测准」', () => {
      expect(profitDeltaText(null, false)).toBe('未测准');
      expect(profitDeltaText(null, true)).toBe('未测准');
      expect(profitDeltaText('-2.0000', true)).toBe('-2.0000');
      expect(profitDeltaText('2.0000', true)).toBe('+2.0000');
    });

    it('🔴 销量差额在未接线时也是「未接线」', () => {
      expect(salesDeltaText(null, false)).toBe('未接线');
      expect(salesDeltaText('2.000000', true)).toBe('+2.000000');
    });
  });

  describe('首次定价不是「从 0 涨到新价」', () => {
    it('没有旧价时只显示新价', () => {
      expect(priceMoveText({ oldPrice: null, newPrice: '19.99', currency: 'USD' })).toBe(
        '首次定价 USD 19.99',
      );
    });

    it('有旧价时显示「旧价 → 新价」', () => {
      expect(priceMoveText({ oldPrice: '100.00', newPrice: '90.00', currency: 'USD' })).toBe(
        'USD 100.00 → USD 90.00',
      );
    });
  });

  describe('价差与幅度', () => {
    it('价差带符号', () => {
      expect(signedMoneyText('-10.0000')).toBe('-10.0000');
      expect(signedMoneyText('5.0000')).toBe('+5.0000');
      expect(signedMoneyText(null)).toBe('—');
    });

    it('幅度带符号并是百分比', () => {
      expect(signedRatioText('-0.100000')).toBe('-10.00%');
      expect(signedRatioText('0.050000')).toBe('+5.00%');
      expect(signedRatioText(null)).toBe('—');
      expect(ratioText('0.250000')).toBe('25.00%');
    });
  });

  describe('listing 与时间', () => {
    it('listing 是 SKU × 店铺', () => {
      expect(listingText({ skuCode: 'SKU-1', platformCode: 'AMAZON', siteCode: 'US' })).toBe(
        'SKU-1 @ AMAZON:US',
      );
      expect(listingText({ skuCode: null, platformCode: 'AMAZON', siteCode: 'US' })).toContain(
        'AMAZON:US',
      );
    });

    it('生效时刻与入账时刻分得开（补录的调价单：生效在上周、入账在今天）', () => {
      expect(
        effectiveText({ effectiveAt: '2026-03-01T02:15:30Z', recordedAt: '2026-03-05T00:00:00Z' }),
      ).toContain('入账');
      expect(
        effectiveText({ effectiveAt: '2026-03-01T02:15:30Z', recordedAt: '2026-03-01T02:15:30Z' }),
      ).not.toContain('入账');
      expect(timeText(null)).toBe('—');
    });
  });

  describe('登记校验', () => {
    const base: ChangeInput = {
      skuId: 'sku-1',
      platformCode: 'AMAZON',
      siteCode: 'US',
      currency: 'USD',
      oldPrice: '100.00',
      newPrice: '90.00',
      changeSource: 'MANUAL_ADJUSTMENT',
      reasonCode: 'COMPETE',
      reasonText: '跟竞品',
      effectiveAt: '2026-03-01T10:15',
      quantity: null,
      costCurrency: null,
      fxRate: null,
      platformFeeRate: null,
      platformFeeFixed: null,
      logisticsCostPerUnit: null,
      monthlyFixedCost: null,
      costWindowFrom: null,
      costWindowTo: null,
      note: '',
    };

    it('合法输入没有毛病', () => {
      expect(validateChange(base)).toEqual([]);
    });

    it('旧价留空是合法的（首次定价）', () => {
      expect(validateChange({ ...base, oldPrice: null })).toEqual([]);
    });

    it('🔴 新价必须是正数 —— 0 价不是价', () => {
      expect(validateChange({ ...base, newPrice: '0' }).join()).toContain('新价必须大于 0');
      expect(validateChange({ ...base, newPrice: '' }).join()).toContain('必须给出新价');
      expect(validateChange({ ...base, newPrice: '-1' }).join()).toContain('新价必须大于 0');
    });

    it('🔴 原因必须写 —— 蓝图要求记「原因」', () => {
      expect(validateChange({ ...base, reasonText: '  ' }).join()).toContain('必须写明变更原因');
    });

    it('SKU / 平台 / 站点 / 生效时刻都要给', () => {
      expect(validateChange({ ...base, skuId: '' }).join()).toContain('必须指定 SKU');
      expect(validateChange({ ...base, platformCode: '' }).join()).toContain('必须指定平台码');
      expect(validateChange({ ...base, siteCode: '' }).join()).toContain('必须指定站点码');
      expect(validateChange({ ...base, effectiveAt: '' }).join()).toContain('必须给出变更生效时刻');
      expect(validateChange({ ...base, changeSource: '' }).join()).toContain('必须选择变更来源');
    });

    it('币种必须是三位字母', () => {
      expect(validateChange({ ...base, currency: 'US' }).join()).toContain(
        '币种必须是三位大写字母',
      );
      expect(validateChange({ ...base, currency: 'USDD' }).join()).toContain(
        '币种必须是三位大写字母',
      );
    });

    it('旧价不能为负', () => {
      expect(validateChange({ ...base, oldPrice: '-1' }).join()).toContain('旧价不能为负');
    });
  });

  describe('原因字典校验', () => {
    it('编码 / 名称 / 分类都要给', () => {
      expect(
        validateReason({
          code: '',
          label: 'x',
          category: 'COST',
          enabled: true,
          sortOrder: 0,
          note: '',
        }).join(),
      ).toContain('变更原因编码不能为空');
      expect(
        validateReason({
          code: 'A',
          label: '',
          category: 'COST',
          enabled: true,
          sortOrder: 0,
          note: '',
        }).join(),
      ).toContain('变更原因名称不能为空');
      expect(
        validateReason({
          code: 'A',
          label: 'x',
          category: '',
          enabled: true,
          sortOrder: 0,
          note: '',
        }).join(),
      ).toContain('必须选择变更原因分类');
      expect(
        validateReason({
          code: 'A',
          label: 'x',
          category: 'COST',
          enabled: true,
          sortOrder: 0,
          note: '',
        }),
      ).toEqual([]);
    });
  });

  describe('默认筛选', () => {
    it('默认按生效时刻倒序，且第一页', () => {
      expect(EMPTY_CHANGE_FILTERS.sort).toBe('EFFECTIVE_AT_DESC');
      expect(EMPTY_CHANGE_FILTERS.page).toBe(0);
      expect(EMPTY_CHANGE_FILTERS.size).toBe(20);
    });

    it('对比视图默认销量窗口 14 天', () => {
      expect(EMPTY_COMPARISON_FILTERS.salesWindowDays).toBe(14);
      expect(EMPTY_COMPARISON_FILTERS.page).toBe(0);
    });
  });
});
