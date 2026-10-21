import { describe, expect, it } from 'vitest';
import {
  NO_DATA_TEXT,
  adviceLabel,
  adviceTone,
  arrivalDateText,
  boxSpecText,
  dailySalesText,
  decisionLabel,
  estimatedCostText,
  fmt,
  growthRateText,
  isActionable,
  rawRequiredText,
  replenishmentAdviceLabels,
  replenishmentBatchQuery,
  replenishmentCoveragePolicyQuery,
  replenishmentSuggestionQuery,
  statusLabel,
  suggestedQuantityText,
} from './replenishment-suggestion-model';

/**
 * 6.2.1.2 补货建议前端模型。
 *
 * <p>这些断言钉的是**界面上不能搞错的判断**，不是文案：
 * 「答不出来」绝不能变成 0、建议量与采购单位不能混、箱规量不到不能显示成 1、
 * 只有「建议补货」能处置、查询串的拼装。
 */
describe('6.2.1.2 补货建议前端模型', () => {
  it('没有日均销量时显示「无数据」而不是 0', () => {
    // 0 日均销量会让目标库存变成 0、补货建议变成「不用补」——
    // 把「没登记数据」显示成 0 会被读成「库存充足」。
    expect(dailySalesText(null)).toBe(NO_DATA_TEXT);
    expect(dailySalesText(undefined)).toBe(NO_DATA_TEXT);
    expect(dailySalesText(0)).toBe('0');
    expect(dailySalesText(12.5)).toBe('12.5');
  });

  it('算不出净需求时显示「无数据」而不是 0', () => {
    expect(rawRequiredText(null)).toBe(NO_DATA_TEXT);
    expect(rawRequiredText(0)).toBe('0');
    expect(rawRequiredText(250)).toBe('250');
  });

  it('给不出建议量时显示「无数据」而不是 0', () => {
    // 缺报价 / 缺箱规 ⇒ 答不出可下单的数量。显示 0 会被读成「不用补」。
    expect(suggestedQuantityText(null)).toBe(NO_DATA_TEXT);
    expect(suggestedQuantityText(0)).toBe('0');
    expect(suggestedQuantityText(264)).toBe('264');
  });

  it('取不到价格时预计费用显示「无数据」而不是 0', () => {
    // 0 会被读成「这一批不要钱」。
    expect(estimatedCostText(null, 'USD')).toBe(NO_DATA_TEXT);
    expect(estimatedCostText(314.2854, 'USD')).toBe('314.2854 USD');
    expect(estimatedCostText(314.2854, null)).toBe('314.2854');
  });

  it('没有预计到货日时显示「无数据」而不是今天', () => {
    expect(arrivalDateText(null)).toBe(NO_DATA_TEXT);
    expect(arrivalDateText('')).toBe(NO_DATA_TEXT);
    expect(arrivalDateText('2026-01-30')).toBe('2026-01-30');
  });

  it('箱规量不到时显示「无数据」而不是 1', () => {
    // 猜成 1 会把订量算少一箱（1 箱 = 12 件时差 12 倍），而且页面上看不出异常。
    expect(boxSpecText(null, 'CTN')).toBe(NO_DATA_TEXT);
    expect(boxSpecText(12, 'CTN')).toBe('12 件/CTN');
  });

  it('五种建议类别各有各的名字不折叠', () => {
    expect(Object.keys(replenishmentAdviceLabels).sort()).toEqual([
      'HOLD',
      'NO_BOX_SPEC',
      'NO_QUOTE',
      'NO_SALES_DATA',
      'REPLENISH',
    ]);
    expect(adviceLabel('REPLENISH')).toBe('建议补货');
    expect(adviceLabel('HOLD')).toBe('无需补货');
    expect(adviceLabel('NO_SALES_DATA')).toBe('缺日均销量');
    expect(adviceLabel('NO_QUOTE')).toBe('缺有效报价');
    expect(adviceLabel('NO_BOX_SPEC')).toBe('缺箱规');
  });

  it('「答不出来」的三档用中性色而不是危险色', () => {
    // 它们不是「危险」，是「答不出来」；用红色会让人以为那是急单，
    // 把真正要下单的那几条淹掉。
    expect(adviceTone('REPLENISH')).toBe('warning');
    expect(adviceTone('HOLD')).toBe('info');
    expect(adviceTone('NO_SALES_DATA')).toBe('neutral');
    expect(adviceTone('NO_QUOTE')).toBe('neutral');
    expect(adviceTone('NO_BOX_SPEC')).toBe('neutral');
  });

  it('只有「建议补货」能处置', () => {
    // 给「答不出来」的行点忽略，会把一个数据缺口伪装成一次业务决定。
    expect(isActionable('REPLENISH')).toBe(true);
    expect(isActionable('HOLD')).toBe(false);
    expect(isActionable('NO_SALES_DATA')).toBe(false);
    expect(isActionable('NO_QUOTE')).toBe(false);
    expect(isActionable('NO_BOX_SPEC')).toBe(false);
  });

  it('处置状态与处置动作各有各的名字', () => {
    expect(statusLabel('SUGGESTED')).toBe('待处置');
    expect(statusLabel('ACCEPTED')).toBe('已接受');
    expect(statusLabel('ADJUSTED')).toBe('已调整');
    expect(statusLabel('IGNORED')).toBe('已忽略');
    expect(decisionLabel('ACCEPT')).toBe('接受');
    expect(decisionLabel('ADJUST')).toBe('调整');
    expect(decisionLabel('IGNORE')).toBe('忽略');
  });

  it('近期增长率带正负号且无版本时是「无数据」', () => {
    expect(growthRateText(null)).toBe(NO_DATA_TEXT);
    expect(growthRateText(0.25)).toBe('+25%');
    expect(growthRateText(-0.1)).toBe('-10%');
  });

  it('数量格式化去掉无意义的尾零且 null 给「无数据」', () => {
    expect(fmt(null)).toBe(NO_DATA_TEXT);
    expect(fmt(0)).toBe('0');
    expect(fmt(264)).toBe('264');
    expect(fmt(12.5)).toBe('12.5');
  });

  it('建议清单查询串按筛选项拼装', () => {
    expect(replenishmentSuggestionQuery({})).toBe('');
    expect(replenishmentSuggestionQuery({ advice: 'REPLENISH' })).toBe('?advice=REPLENISH');
    expect(replenishmentSuggestionQuery({ onlyActionable: true, page: 0, size: 20 })).toBe(
      '?onlyActionable=true&page=0&size=20',
    );
    expect(replenishmentSuggestionQuery({ keyword: ' SKU-1 ' })).toBe('?keyword=SKU-1');
  });

  it('批次与配置查询串按筛选项拼装', () => {
    expect(replenishmentBatchQuery({})).toBe('');
    expect(replenishmentBatchQuery({ page: 1, size: 10 })).toBe('?page=1&size=10');
    expect(replenishmentCoveragePolicyQuery({ scope: 'SKU', skuId: 'abc' })).toBe(
      '?scope=SKU&skuId=abc',
    );
    expect(replenishmentCoveragePolicyQuery({ effectiveOnly: true })).toBe('?effectiveOnly=true');
  });
});
