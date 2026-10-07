import { describe, expect, it } from 'vitest';

import {
  auditLabel,
  dispositionWarning,
  errorText,
  formatAmount,
  formatQty,
  formatRate,
  isAgreementActive,
  isSettlementLocked,
  payableBasisNote,
  soldRate,
  unsettleable,
  zeroRemainingLines,
} from './consignment-model';
import type { ConsignmentLine, ConsignmentStockView } from '../../../../../packages/api-contracts/src/catalog';

/**
 * 7.7.1.2 前端纯函数单测。
 *
 * <p>重点覆盖三件**界面上最容易说错**的事：
 * 1. null 口径不能画成 0（自有货值、售罄率分母为 0）；
 * 2. 未售出不产生应付，转采购是唯一例外 —— 两者的语气必须不同；
 * 3. 已确认/已关闭的单据不再给可改的暗示。
 */

function line(over: Partial<ConsignmentLine> = {}): ConsignmentLine {
  return {
    id: 'l1',
    agreementId: 'a1',
    skuId: 's1',
    skuCode: 'SKU-1',
    skuName: '商品一',
    warehouseId: 'w1',
    warehouseCode: 'WH-1',
    consignedQuantity: 100,
    settlementPrice: 12.5,
    soldQuantity: 30,
    returnedQuantity: 0,
    convertedQuantity: 0,
    remainingQuantity: 70,
    stockOwnerType: 'SUPPLIER',
    countsAsOwnedAsset: false,
    salesInPeriod: 30,
    settledQuantity: 0,
    unsettleableQuantity: 30,
    ...over,
  } as ConsignmentLine;
}

function view(over: Partial<ConsignmentStockView> = {}): ConsignmentStockView {
  return {
    agreementId: 'a1',
    agreementCode: 'CG-1',
    mode: 'CONSIGNMENT',
    currency: 'CNY',
    settlementCycle: 'MONTHLY',
    endTreatment: 'RETURN_TO_SUPPLIER',
    status: 'ACTIVE',
    totalConsigned: 140,
    totalSold: 30,
    totalReturned: 0,
    totalConverted: 0,
    totalRemaining: 110,
    unsettledQuantity: 30,
    unsettledAmount: 375,
    lineCount: 2,
    zeroRemainingLineCount: 0,
    stockOwnerType: 'SUPPLIER',
    countsAsOwnedAsset: false,
    ownedAssetValue: null,
    ownershipNote: '寄售货主恒为供应商',
    payableBasis: '应付只有一个来源',
    ...over,
  } as ConsignmentStockView;
}

describe('formatQty', () => {
  it('去掉尾随 0 但保留有效小数', () => {
    expect(formatQty(100)).toBe('100');
    expect(formatQty('100.000000')).toBe('100');
    expect(formatQty('12.500000')).toBe('12.5');
  });

  it('空值显示破折号而不是 0', () => {
    expect(formatQty(null)).toBe('—');
    expect(formatQty(undefined)).toBe('—');
  });
});

describe('formatAmount', () => {
  it('币种必须跟着数字出现', () => {
    expect(formatAmount('375', 'CNY')).toBe('375.00 CNY');
  });

  it('null 口径显示「不适用」而不是 0 —— 0 会被读成「这些货值 0 元」', () => {
    expect(formatAmount(null, 'CNY')).toBe('不适用');
    expect(formatAmount(0, 'CNY')).toBe('0.00 CNY');
  });
});

describe('soldRate', () => {
  it('正常口径按已售 ÷ 寄售量', () => {
    expect(soldRate(line({ soldQuantity: 30, consignedQuantity: 120 }))).toBeCloseTo(0.25);
  });

  it('寄售量为 0 时返回 null —— 画成 0% 会被读成「一件都没卖出去」', () => {
    expect(soldRate(line({ consignedQuantity: 0, soldQuantity: 0 }))).toBeNull();
    expect(formatRate(soldRate(line({ consignedQuantity: 0, soldQuantity: 0 })))).toBe('—');
  });
});

describe('unsettleable', () => {
  it('已售减已结算，且不会出现负数', () => {
    expect(unsettleable(line({ unsettleableQuantity: 12.5 }))).toBe(12.5);
    expect(unsettleable(line({ unsettleableQuantity: -3 }))).toBe(0);
  });
});

describe('payableBasisNote', () => {
  it('有待结算量时说明应付金额与未售出不产生应付', () => {
    const text = payableBasisNote(view());
    expect(text).toContain('已售未结算');
    expect(text).toContain('375.00 CNY');
    expect(text).toContain('不产生应付');
  });

  it('没有任何待结算量时如实说明「这期没卖出」，而不是显示成错误', () => {
    const text = payableBasisNote(
      view({ unsettledQuantity: 0, unsettledAmount: 0, totalConsigned: 140 }),
    );
    expect(text).toContain('没有待结算的已售数量');
    expect(text).toContain('未售出不产生应付');
  });
});

describe('dispositionWarning', () => {
  it('退回供应商是静默口径：不产生应付', () => {
    const w = dispositionWarning('RETURN_TO_SUPPLIER', 0, 'CNY');
    expect(w.tone).toBe('muted');
    expect(w.text).toContain('不产生应付');
  });

  it('转采购必须显眼警示：它真的产生应付', () => {
    const w = dispositionWarning('CONVERT_TO_PURCHASE', '312.5', 'CNY');
    expect(w.tone).toBe('warn');
    expect(w.text).toContain('312.50 CNY');
    expect(w.text).toContain('唯一例外');
  });
});

describe('状态可用性', () => {
  it('已确认/已作废的结算单被冻结', () => {
    expect(isSettlementLocked({ status: 'OPEN' })).toBe(false);
    expect(isSettlementLocked({ status: 'CONFIRMED' })).toBe(true);
    expect(isSettlementLocked({ status: 'CANCELLED' })).toBe(true);
  });

  it('只有生效中的协议能记销售与出结算单', () => {
    expect(isAgreementActive({ status: 'ACTIVE' })).toBe(true);
    expect(isAgreementActive({ status: 'DRAFT' })).toBe(false);
    expect(isAgreementActive({ status: 'SUSPENDED' })).toBe(false);
    expect(isAgreementActive({ status: 'CLOSED' })).toBe(false);
  });
});

describe('zeroRemainingLines', () => {
  it('提示卖空的行数', () => {
    expect(zeroRemainingLines(view({ zeroRemainingLineCount: 2 }))).toBe(2);
  });
});

describe('auditLabel', () => {
  it('已知事件给中文标签，未知事件原样返回（不吞掉新事件）', () => {
    expect(auditLabel('SALE_RECORDED')).toBe('记录售出');
    expect(auditLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('errorText', () => {
  it('把异常收敛成一句能展示的话', () => {
    expect(errorText(new Error('boom'))).toBe('boom');
    expect(errorText('nope')).toBe('操作失败，请重试。');
  });
});
