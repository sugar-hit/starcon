import { describe, expect, it } from 'vitest';
import {
  PRICE_ADJUSTMENT_SECTION,
  approvalDecisionLabel,
  approvalSourceLabel,
  deltaText,
  emptyPriceAdjustmentFilters,
  emptyPriceAdjustmentRequest,
  lossText,
  moneyText,
  percentText,
  priceAdjustmentSummary,
  priceSourceLabel,
  priceText,
  statusLabel,
  statusTone,
  validatePriceAdjustmentRequest,
} from './price-adjustment-model';
import type { PriceAdjustmentDetail } from './price-adjustment-model';

/**
 * 5.4.1.1 调价单前端**纯函数**测试。
 *
 * <p>🔴 断言写在字面量上，兄弟模块一旦交付就会红。断言「本分区渲染出了它自己的标题与字段」
 * 这件事本身是稳的 —— 它只依赖本模块自己的描述符。
 */
describe('5.4.1.1 调价单', () => {
  it('分区描述符写着自己的标题与字段', () => {
    expect(PRICE_ADJUSTMENT_SECTION.key).toBe('price-adjustment');
    expect(PRICE_ADJUSTMENT_SECTION.title).toBe('调价单');
    expect(PRICE_ADJUSTMENT_SECTION.description).toContain('变更前后值');
    const ids = PRICE_ADJUSTMENT_SECTION.fields.map((f) => f.id);
    expect(ids).toEqual(
      expect.arrayContaining([
        'skuId',
        'platformCode',
        'siteCode',
        'currency',
        'newPrice',
        'reason',
      ]),
    );
    // 新价与原因必填 —— 蓝图那句「输入新旧价、说明原因」。
    expect(PRICE_ADJUSTMENT_SECTION.fields.find((f) => f.id === 'newPrice')?.required).toBe(true);
    expect(PRICE_ADJUSTMENT_SECTION.fields.find((f) => f.id === 'reason')?.required).toBe(true);
  });

  it('null 绝不渲染成 0 —— 「取不到」与「确实是 0」是两件事', () => {
    expect(moneyText(null)).toBe('未计入');
    expect(moneyText(undefined)).toBe('未计入');
    expect(moneyText(0)).toBe('0');
    expect(priceText(null)).toBe('未计入');
    expect(priceText(0)).toBe('0');
    expect(priceText(12.345)).toBe('12.35');
    expect(percentText(null)).toBe('不可信（成本不完整）');
    expect(percentText(0)).toBe('0%');
    expect(percentText(0.25)).toBe('25%');
    expect(lossText(null)).toBe('不可信');
    expect(lossText(false)).toBe('不亏');
    expect(lossText(true)).toBe('亏损');
  });

  it('价差带方向', () => {
    expect(deltaText(20)).toBe('+20');
    expect(deltaText(-20)).toBe('-20');
    expect(deltaText(0)).toBe('0');
    expect(deltaText(null)).toBe('—');
  });

  it('状态与审批口径的中文名', () => {
    expect(statusLabel('DRAFT')).toBe('草稿');
    expect(statusLabel('PENDING_APPROVAL')).toBe('待审');
    expect(statusLabel('APPROVED')).toBe('已批');
    expect(statusLabel('EXECUTED')).toBe('已执行');
    expect(statusLabel('REJECTED')).toBe('已驳回');
    // 待审是「在等审批」，不是「出错了」。
    expect(statusTone('PENDING_APPROVAL')).toBe('info');
    expect(statusTone('REJECTED')).toBe('danger');
    expect(statusTone('EXECUTED')).toBe('success');
    expect(approvalDecisionLabel('SUBMIT_FOR_APPROVAL')).toBe('需审批');
    expect(approvalDecisionLabel('EXEMPT')).toBe('免审');
    expect(approvalDecisionLabel(null)).toBe('未提交');
    expect(approvalSourceLabel('UNCONTROLLED')).toContain('默认放行');
    expect(priceSourceLabel('PRICE_OBSERVATION')).toBe('价格观测台账');
    expect(priceSourceLabel('BATCH_ADJUSTMENT')).toBe('批量调价新价');
  });

  it('校验拦住的每一条都有说清原因', () => {
    const base = emptyPriceAdjustmentRequest();
    expect(validatePriceAdjustmentRequest(base)?.field).toBe('skuId');

    const noSite = { ...base, skuId: '11111111-1111-1111-1111-111111111111' };
    expect(validatePriceAdjustmentRequest(noSite)?.field).toBe('platformCode');

    const noCurrency = {
      ...base,
      skuId: 'x',
      platformCode: 'AMAZON',
      siteCode: 'US',
      currency: 'US',
    };
    expect(validatePriceAdjustmentRequest(noCurrency)?.field).toBe('currency');

    const zeroPrice = {
      ...base,
      skuId: 'x',
      platformCode: 'AMAZON',
      siteCode: 'US',
      newPrice: 0,
      reason: '跟价',
    };
    expect(validatePriceAdjustmentRequest(zeroPrice)?.field).toBe('newPrice');

    const noReason = {
      ...base,
      skuId: 'x',
      platformCode: 'AMAZON',
      siteCode: 'US',
      newPrice: 10,
    };
    expect(validatePriceAdjustmentRequest(noReason)?.field).toBe('reason');

    // 🔴 跨币种没给汇率 ⇒ 拒绝，绝不按 1:1 折算。
    const crossCurrency = {
      ...base,
      skuId: 'x',
      platformCode: 'AMAZON',
      siteCode: 'US',
      currency: 'EUR',
      costCurrency: 'USD',
      newPrice: 10,
      reason: '跟价',
    };
    const fxProblem = validatePriceAdjustmentRequest(crossCurrency);
    expect(fxProblem?.field).toBe('fxRate');
    expect(fxProblem?.message).toContain('绝不按 1:1 折算');

    const ok = { ...crossCurrency, fxRate: 7.2 };
    expect(validatePriceAdjustmentRequest(ok)).toBeNull();
  });

  it('空表单与空筛选', () => {
    expect(emptyPriceAdjustmentRequest().newPrice).toBe(0);
    expect(emptyPriceAdjustmentFilters().status).toBe('');
    expect(emptyPriceAdjustmentFilters().sort).toBe('NEWEST');
  });

  it('人话摘要带变更前后值', () => {
    const detail = {
      id: '1',
      skuCode: 'SKU-1',
      platformCode: 'AMAZON',
      siteCode: 'US',
      oldPrice: 100,
      newPrice: 120,
      currency: 'USD',
      reason: '竞品降价跟价',
    } as PriceAdjustmentDetail;
    const summary = priceAdjustmentSummary(detail);
    expect(summary).toContain('SKU-1');
    expect(summary).toContain('AMAZON/US');
    expect(summary).toContain('100 → 120 USD');
    expect(summary).toContain('竞品降价跟价');
  });
});
