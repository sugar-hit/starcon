import { describe, expect, it } from 'vitest';
import type { ForecastAvailabilityLine, SpecViolation } from './stock-forecast-model';
import {
  AvailabilityState,
  availabilityState,
  availabilityText,
  forecastSourceLabel,
  forecastStatusLabel,
  quantityText,
  shipmentTaskStatusLabel,
  violationText,
  violationsFromError,
} from './stock-forecast-model';
import { ApiError } from '../inventory/api';

function availability(over: Partial<ForecastAvailabilityLine> = {}): ForecastAvailabilityLine {
  return {
    skuCode: 'SKU-1',
    requestedQuantity: 10,
    unit: 'PCS',
    sellable: 50,
    sellableKnown: true,
    inTransitQuantity: 20,
    inTransitKnown: true,
    coverable: true,
    note: '',
    ...over,
  };
}

describe('10.4.2.2 备货预报展示派生', () => {
  it('数量文本去掉多余的 0，但小数精度不丢', () => {
    expect(quantityText(12, 'PCS')).toBe('12 PCS');
    expect(quantityText(12.25, 'PCS')).toBe('12.25 PCS');
    expect(quantityText(0.000001, 'KG')).toBe('0.000001 KG');
  });

  it('状态中文名覆盖预报单与货件任务的全部状态', () => {
    expect(forecastStatusLabel('DRAFT')).toBe('草稿');
    expect(forecastStatusLabel('SHIPMENT_CREATED')).toBe('货件已创建');
    expect(forecastStatusLabel('REJECTED')).toBe('仓端已拒收');
    expect(forecastStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(shipmentTaskStatusLabel('FAILED')).toBe('失败（可重试）');
    expect(shipmentTaskStatusLabel('PENDING')).toBe('待推进');
  });

  it('预报来源中文名', () => {
    expect(forecastSourceLabel('FBA_SHIPMENT_PLAN')).toBe('FBA 发货计划');
    expect(forecastSourceLabel('REPLENISHMENT_SUGGESTION')).toBe('补货建议');
    expect(forecastSourceLabel('MANUAL')).toBe('人工登记');
  });

  it('规格违规要说清「期望什么 / 实际什么」，不能只有一句规格不符', () => {
    const violation: SpecViolation = {
      rule: 'MAX_UNITS_PER_CARTON_EXCEEDED',
      ruleLabel: '单箱件数超出上限',
      skuCode: 'SKU-1',
      message: 'SKU-1 单箱 20 件，超出上限 10',
      expected: '≤ 10',
      actual: '20',
    };
    const text = violationText(violation);
    expect(text).toContain('[SKU-1]');
    expect(text).toContain('期望 ≤ 10');
    expect(text).toContain('实际 20');
  });

  it('可用量不足与口径不可用是三件事，不能混为一谈', () => {
    expect(availabilityState(availability())).toBe(AvailabilityState.ENOUGH);
    expect(availabilityState(availability({ sellable: 3 }))).toBe(AvailabilityState.SHORT);
    // 上游没给这一行：不是「不足」，是「不可用」
    expect(
      availabilityState(availability({ sellable: null, sellableKnown: false })),
    ).toBe(AvailabilityState.UNAVAILABLE);
  });

  it('口径不可用时明说不可用，不用 0 顶（0 会被读成没有库存需求）', () => {
    const unknown = availability({ sellable: null, sellableKnown: false });
    expect(availabilityText(unknown)).toContain('不可用');
    expect(availabilityText(unknown)).not.toMatch(/可售 0/);
    // 在途也读不到时同样要如实说
    const noTransit = availability({ inTransitQuantity: null, inTransitKnown: false });
    expect(availabilityText(noTransit)).toContain('头程在途不可用');
  });

  it('从 422 的响应体里取出结构化违规清单', () => {
    const violations = [
      {
        rule: 'BOX_TYPE_NOT_ALLOWED',
        ruleLabel: '箱型不在目的仓允许范围内',
        skuCode: 'SKU-1',
        message: '箱型不符',
        expected: 'CARTON_STANDARD',
        actual: 'CARTON_TINY',
      },
    ];
    const error = new ApiError(422, '规格校验未通过', { violations });
    expect(violationsFromError(error)).toHaveLength(1);
    expect(violationsFromError(error)[0]?.rule).toBe('BOX_TYPE_NOT_ALLOWED');

    // 不是 422 或响应体里没有 violations 时退回空数组，界面改用 detail 文案
    expect(violationsFromError(new ApiError(500, 'boom', null))).toEqual([]);
    expect(violationsFromError(new Error('boom'))).toEqual([]);
  });
});
