import { describe, expect, it } from 'vitest';

import {
  ADJUSTMENT_SECTION,
  adjustModeLabel,
  deltaText,
  emptyFilters,
  emptyRequest,
  hasShop,
  itemStatusLabel,
  itemTone,
  lossText,
  moneyText,
  moneyWithCurrency,
  percentText,
  previewStats,
  priceSourceLabel,
  priceText,
  scopeSummary,
  toggleShop,
  validateRequest,
} from './batch-price-adjustment-model';
import type {
  BatchPriceAdjustmentItem,
  BatchPriceAdjustmentPreview,
  BatchPriceAdjustmentRequest,
} from './batch-price-adjustment-model';

/**
 * 5.4.1.2 批量调价前端模型的纯函数断言。
 *
 * <p>这些函数守着蓝图那句「**必须先看预览再执行**，预览要包含"改完后预计亏损的商品数"
 * 这类反向指标」的技术落点，以及它旁边那条隐含的纪律：
 * **「没测准」与「测出来是亏的」是两件事**。
 *
 * <p>🔴 本文件**不断言任何「未交付」字面量**：兄弟模块一旦交付，那个字面量就会消失，
 * 写死会让别的车道一交付就红（本仓 2026-10-20 踩过这个坑，害得整批 L2 返工一轮）。
 * 分区的标题与字段改为**从分区描述符本身断言** —— 组件也从同一个描述符渲染。
 */

function item(overrides: Partial<BatchPriceAdjustmentItem> = {}): BatchPriceAdjustmentItem {
  return {
    id: null,
    adjustmentId: null,
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    skuName: '商品一',
    platformCode: 'AMAZON',
    siteCode: 'US',
    currency: 'USD',
    status: 'WILL_ADJUST',
    skipReason: '',
    oldPrice: 100,
    newPrice: 110,
    priceDelta: 10,
    priceSource: 'INTERNAL_PRICE',
    priceObservedAt: '2026-10-21T00:00Z',
    grossProfitBefore: 64,
    grossMarginBefore: 0.64,
    grossProfitAfter: 74.5,
    grossMarginAfter: 0.677273,
    decisionReady: true,
    lossBefore: false,
    lossAfter: false,
    ...overrides,
  };
}

function preview(overrides: Partial<BatchPriceAdjustmentPreview> = {}): BatchPriceAdjustmentPreview {
  return {
    previewToken: 'token-1',
    adjustMode: 'PERCENT',
    adjustModeLabel: '百分比',
    adjustAmount: null,
    adjustRate: 0.1,
    templateId: null,
    templateCode: null,
    templateName: null,
    currency: 'USD',
    costCurrency: 'USD',
    fxRate: null,
    shopCount: 2,
    shops: ['AMAZON:CA', 'AMAZON:US'],
    skuCount: 3,
    listingCount: 6,
    willAdjustCount: 5,
    skippedCount: 1,
    lossBeforeCount: 0,
    lossAfterCount: 1,
    lossAfterSkuCount: 1,
    profitBeforeTotal: 120,
    profitAfterTotal: 90,
    profitDeltaTotal: -30,
    avgMarginBefore: 0.4,
    avgMarginAfter: 0.3,
    items: [item()],
    note: '',
    ...overrides,
  };
}

describe('null 绝不能渲染成 0', () => {
  it('取不到与确实是 0 是两件事', () => {
    expect(moneyText(null)).toBe('未计入');
    expect(moneyText(0)).toBe('0');
    expect(moneyText(undefined)).toBe('未计入');
    expect(moneyWithCurrency(null, 'USD')).toBe('未计入');
    expect(moneyWithCurrency(12.5, 'USD')).toBe('12.5 USD');
    expect(priceText(null)).toBe('未计入');
    expect(priceText(0)).toBe('0');
    expect(priceText(109.989)).toBe('109.99');
    expect(percentText(null)).toBe('不可信（成本不完整）');
    expect(percentText(0)).toBe('0%');
    expect(percentText(0.6412)).toBe('64.12%');
  });

  it('亏损标记为 null 时是「不可信」而不是「不亏」', () => {
    expect(lossText(null)).toBe('不可信');
    expect(lossText(true)).toBe('亏损');
    expect(lossText(false)).toBe('不亏');
  });

  it('跳过的行不是 danger —— 它是「调不了」，不是「有问题」', () => {
    expect(itemTone(item({ status: 'SKIPPED' }))).toBe('neutral');
    expect(itemTone(item({ lossAfter: true }))).toBe('danger');
    expect(itemTone(item({ lossAfter: null }))).toBe('info');
    expect(itemTone(item())).toBe('success');
  });

  it('价差正负号看得见', () => {
    expect(deltaText(10)).toBe('+10');
    expect(deltaText(-20)).toBe('-20');
    expect(deltaText(0)).toBe('0');
    expect(deltaText(null)).toBe('—');
  });
});

describe('词表', () => {
  it('调价方式与状态有名字', () => {
    expect(adjustModeLabel('FIXED_AMOUNT')).toBe('固定金额');
    expect(adjustModeLabel('PERCENT')).toBe('百分比');
    expect(adjustModeLabel('TEMPLATE')).toBe('按模板');
    expect(adjustModeLabel(null)).toBe('—');
    expect(itemStatusLabel('WILL_ADJUST')).toBe('将调价');
    expect(itemStatusLabel('SKIPPED')).toBe('已跳过');
    expect(priceSourceLabel('INTERNAL_PRICE')).toBe('我方价');
    expect(priceSourceLabel('PLATFORM_PRICE')).toBe('平台在售价');
  });
});

describe('预览报告的反向指标', () => {
  it('「改价后预计亏损商品数」单独成卡，不被塞进利润变化里', () => {
    const stats = previewStats(preview());
    const reverse = stats.find((stat) => stat.label === '改价后预计亏损商品数');
    expect(reverse?.value).toBe('1');
    expect(reverse?.tone).toBe('danger');
    expect(reverse?.hint).toContain('反向指标');
    // 店铺与商品数也在。
    expect(stats.find((stat) => stat.label === '涉及店铺')?.value).toBe('2');
    expect(stats.find((stat) => stat.label === '涉及商品')?.value).toBe('3');
  });

  it('预计利润变化为 null 时写「不可信」，不是 0', () => {
    const stats = previewStats(preview({ profitDeltaTotal: null }));
    const delta = stats.find((stat) => stat.label === '预计利润变化');
    expect(delta?.value).toBe('不可信');
    expect(delta?.hint).toContain('没测准');
  });

  it('没有预览时返回空数组而不是占位', () => {
    expect(previewStats(null)).toEqual([]);
  });
});

describe('输入校验', () => {
  const base: BatchPriceAdjustmentRequest = {
    ...emptyRequest(),
    shops: [{ platformCode: 'AMAZON', siteCode: 'US' }],
    adjustRate: 0.1,
  };

  it('必须指定目标店铺', () => {
    expect(validateRequest({ ...base, shops: [] })?.field).toBe('shops');
  });

  it('百分比是小数口径，且不能把价格变成负数', () => {
    expect(validateRequest({ ...base, adjustRate: null })?.field).toBe('adjustRate');
    expect(validateRequest({ ...base, adjustRate: -1.5 })?.field).toBe('adjustRate');
    expect(validateRequest({ ...base, adjustRate: 0 })?.field).toBe('adjustRate');
  });

  it('固定金额可以是负数（降价），但不能是 0', () => {
    expect(validateRequest({ ...base, adjustMode: 'FIXED_AMOUNT', adjustAmount: -5, adjustRate: null }))
      .toBeNull();
    expect(
      validateRequest({ ...base, adjustMode: 'FIXED_AMOUNT', adjustAmount: 0, adjustRate: null })
        ?.field,
    ).toBe('adjustAmount');
  });

  it('按模板方式必须选模板', () => {
    expect(validateRequest({ ...base, adjustMode: 'TEMPLATE', adjustRate: null })?.field).toBe(
      'templateId',
    );
  });

  it('跨币种没给汇率时拒绝提交', () => {
    const error = validateRequest({ ...base, currency: 'EUR', costCurrency: 'USD' });
    expect(error?.field).toBe('fxRate');
    expect(error?.message).toContain('绝不按 1:1 折算');
  });

  it('合法的输入通过校验', () => {
    expect(validateRequest(base)).toBeNull();
  });
});

describe('目标店铺', () => {
  it('增删一个店铺', () => {
    const one = toggleShop([], 'AMAZON', 'US');
    expect(hasShop(one, 'AMAZON', 'US')).toBe(true);
    expect(hasShop(one, 'AMAZON', 'CA')).toBe(false);
    const two = toggleShop(one, 'AMAZON', 'CA');
    expect(two).toHaveLength(2);
    const back = toggleShop(two, 'AMAZON', 'US');
    expect(back).toHaveLength(1);
    expect(hasShop(back, 'AMAZON', 'CA')).toBe(true);
  });

  it('目标集合摘要说得清当前在看哪一批', () => {
    const summaryText = scopeSummary({
      ...emptyRequest(),
      shops: [{ platformCode: 'AMAZON', siteCode: 'US' }],
      category: '家居',
      brand: 'Acme',
      adjustMode: 'PERCENT',
      adjustRate: 0.1,
    });
    expect(summaryText).toContain('AMAZON/US');
    expect(summaryText).toContain('家居');
    expect(summaryText).toContain('Acme');
    expect(summaryText).toContain('10%');
  });
});

describe('分区描述符同源断言', () => {
  it('分区标题与字段都来自描述符本身', () => {
    expect(ADJUSTMENT_SECTION.key).toBe('batch-price-adjustment');
    expect(ADJUSTMENT_SECTION.title).toBe('批量调价');
    expect(ADJUSTMENT_SECTION.description).toContain('先预览影响范围');
    const ids = ADJUSTMENT_SECTION.fields.map((field) => field.id);
    expect(ids).toContain('adjustMode');
    expect(ids).toContain('shops');
    expect(ids).toContain('category');
    expect(ids).toContain('brand');
    expect(ids).toContain('adjustAmount');
    expect(ids).toContain('adjustRate');
    // 必填项：调价方式与目标店铺。
    expect(ADJUSTMENT_SECTION.fields.find((f) => f.id === 'adjustMode')?.required).toBe(true);
    expect(ADJUSTMENT_SECTION.fields.find((f) => f.id === 'shops')?.required).toBe(true);
  });

  it('空表单与空筛选是初始状态而不是 undefined', () => {
    expect(emptyRequest().adjustMode).toBe('PERCENT');
    expect(emptyRequest().shops).toEqual([]);
    expect(emptyFilters()).toEqual({ status: '', loss: '', keyword: '', sort: 'DELTA_ASC' });
  });
});
