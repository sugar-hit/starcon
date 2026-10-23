import { describe, expect, it } from 'vitest';
import type {
  FinanceSupplierReconciliationDifferenceRow,
  FinanceSupplierReconciliationRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  actorRoleLabel,
  confirmBlockReason,
  differenceHint,
  differenceStatusLabel,
  differenceStatusTone,
  differenceTypeLabel,
  formatSupplierMoney,
  formatSupplierQuantity,
  lineVarianceHint,
  reconciliationEventLabel,
  reconciliationStatusLabel,
  reconciliationStatusTone,
  reconciliationSummaryLabel,
  resolutionLabel,
  upstreamStatusLabel,
} from './supplier-reconciliation-model';

/**
 * 12.1.2.3 供应商对账前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>「<b>已导入 / 已核对 / 已确认</b>」是三件事，页面不许把它们渲染成同一个状态；</li>
 *   <li>「<b>采购侧口径</b>」与「<b>财务侧口径</b>」必须分开显示 —— 只显示一个数就答不出
 *       「差在哪、谁认这笔账」；</li>
 *   <li>「<b>没有质检记录</b>」≠「没有扣量」，页面不许把两者混成一句话；</li>
 *   <li>「<b>未裁决的差异存在时不能确认</b>」，且确认按钮禁用时必须说清为什么。</li>
 * </ol>
 */

const recon = (
  over: Partial<FinanceSupplierReconciliationRow>,
): FinanceSupplierReconciliationRow => ({
  id: '11111111-1111-1111-1111-111111111111',
  reconNo: 'SR-20261031-ABCD1234',
  upstreamReconId: '22222222-2222-2222-2222-222222222222',
  upstreamReconNo: 'PR-20261031-ABCD1234',
  supplierRefId: '33333333-3333-3333-3333-333333333333',
  supplierCode: 'SUP-001',
  supplierName: '供应商甲',
  periodType: 'MONTHLY',
  periodStart: '2026-10-01',
  periodEnd: '2026-10-31',
  statementCurrency: 'USD',
  status: 'MATCHED',
  statusLabel: '已核对',
  upstreamStatus: 'CONFIRMED',
  statementReceivedAmount: 2000,
  statementDeductionAmount: 0,
  statementClaimAmount: 0,
  statementSuggestedAmount: 2000,
  statementTotalQuantity: 150,
  financeConfirmedAmount: null,
  financeConfirmedQuantity: null,
  financeAdjustedAmount: 0,
  lineCount: 2,
  differenceCount: 3,
  openDifferenceCount: 0,
  matchedLineCount: 2,
  unmatchedLineCount: 0,
  upstreamSupplierConfirmedAt: '2026-11-01T02:00:00Z',
  upstreamSupplierReference: 'SUP-CONF-1',
  confirmedBy: null,
  confirmedAt: null,
  rejectedBy: null,
  rejectedAt: null,
  rejectReason: '',
  payableId: null,
  payableNo: '',
  note: '',
  createdAt: '2026-11-01T02:00:00Z',
  updatedAt: '2026-11-01T02:00:00Z',
  ...over,
});

const difference = (
  over: Partial<FinanceSupplierReconciliationDifferenceRow>,
): FinanceSupplierReconciliationDifferenceRow => ({
  id: '44444444-4444-4444-4444-444444444444',
  lineId: '55555555-5555-5555-5555-555555555555',
  lineNo: 1,
  differenceType: 'QUALITY_DEDUCTION_NOT_APPLIED',
  differenceTypeLabel: '质检扣量未扣除',
  status: 'OPEN',
  statusLabel: '未处理',
  statementValue: 100,
  financeValue: 90,
  varianceQuantity: -10,
  varianceAmount: -100,
  currency: 'USD',
  resolution: null,
  resolutionLabel: '',
  adjustmentAmount: 0,
  resolutionNote: '',
  resolvedBy: null,
  resolvedAt: null,
  explanation: '',
  explainedBy: null,
  explainedAt: null,
  createdAt: '2026-11-01T02:00:00Z',
  ...over,
});

describe('对账单状态：导入 / 核对 / 确认是三件事', () => {
  it('三个状态各有各的文案与色调，不许被渲染成同一件事', () => {
    expect(reconciliationStatusLabel('IMPORTED')).toBe('已导入（待核对）');
    expect(reconciliationStatusLabel('MATCHED')).toBe('已核对（待确认）');
    expect(reconciliationStatusLabel('CONFIRMED')).toBe('财务已确认（已生成应付）');
    expect(reconciliationStatusTone('IMPORTED')).toBe('neutral');
    expect(reconciliationStatusTone('MATCHED')).toBe('neutral');
    expect(reconciliationStatusTone('DIFFERENCES')).toBe('warning');
    expect(reconciliationStatusTone('CONFIRMED')).toBe('success');
    expect(reconciliationStatusTone('REJECTED')).toBe('danger');
    expect(
      new Set([
        reconciliationStatusTone('IMPORTED'),
        reconciliationStatusTone('MATCHED'),
        reconciliationStatusTone('CONFIRMED'),
      ]).size,
    ).toBeGreaterThan(1);
  });

  it('不认识的取值原样返回，不猜', () => {
    expect(reconciliationStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(upstreamStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(differenceTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(differenceStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('差异：两侧口径必须都看得见', () => {
  it('差异一行同时给出采购侧值、财务侧值与差额', () => {
    const hint = differenceHint(difference({}));
    expect(hint).toContain('采购侧');
    expect(hint).toContain('财务侧');
    expect(hint).toContain('差额');
    expect(hint).toContain('-100.0000 USD');
  });

  it('裁决方式分开摆：按财务口径调整 / 沿用采购侧口径 / 未裁决', () => {
    expect(resolutionLabel('APPLIED')).toBe('按财务口径调整');
    expect(resolutionLabel('KEPT')).toBe('沿用采购侧口径');
    expect(resolutionLabel(null)).toBe('未裁决');
  });

  it('差异状态：未处理 / 已说明待裁决 / 已裁决是三件事', () => {
    expect(differenceStatusLabel('OPEN')).toBe('未处理');
    expect(differenceStatusLabel('EXPLAINED')).toBe('已说明待裁决');
    expect(differenceStatusLabel('RESOLVED')).toBe('已裁决');
    expect(differenceStatusTone('OPEN')).toBe('danger');
    expect(differenceStatusTone('EXPLAINED')).toBe('warning');
    expect(differenceStatusTone('RESOLVED')).toBe('success');
  });
});

describe('核对结论：缺证据与「没有差异」不是一回事', () => {
  it('到货依据缺失时明说「留空不是 0」', () => {
    const hint = lineVarianceHint({
      receiptMatched: false,
      receiptArrivedQty: null,
      quantity: 100,
      qualityStatus: 'NOT_INSPECTED',
      qualityDeductedQty: null,
      financeQuantity: null,
      varianceQuantity: 0,
      varianceAmount: 0,
      currency: 'USD',
    });
    expect(hint).toContain('到货依据缺失');
    expect(hint).toContain('不是 0');
  });

  it('没有质检记录时明说「不等于没有扣量」', () => {
    const hint = lineVarianceHint({
      receiptMatched: true,
      receiptArrivedQty: 100,
      quantity: 100,
      qualityStatus: 'NOT_INSPECTED',
      qualityDeductedQty: null,
      financeQuantity: 100,
      varianceQuantity: 0,
      varianceAmount: 0,
      currency: 'USD',
    });
    expect(hint).toContain('无质检记录');
    expect(hint).toContain('≠ 没有扣量');
  });

  it('有质检记录时把扣量显示出来', () => {
    const hint = lineVarianceHint({
      receiptMatched: true,
      receiptArrivedQty: 100,
      quantity: 100,
      qualityStatus: 'INSPECTED',
      qualityDeductedQty: 10,
      financeQuantity: 90,
      varianceQuantity: -10,
      varianceAmount: -100,
      currency: 'USD',
    });
    expect(hint).toContain('质检扣量 10.000000');
    expect(hint).toContain('-100.0000 USD');
  });
});

describe('确认闸门：为什么不能点必须说清楚', () => {
  it('还有未裁决差异时给出原因', () => {
    const reason = confirmBlockReason(recon({ status: 'DIFFERENCES', openDifferenceCount: 2 }));
    expect(reason).toContain('2 条差异未裁决');
  });

  it('还没核对时给出原因', () => {
    expect(confirmBlockReason(recon({ status: 'IMPORTED' }))).toContain('还没核对');
  });

  it('已确认 / 已驳回都是终态，不能再确认', () => {
    expect(confirmBlockReason(recon({ status: 'CONFIRMED' }))).toContain('已冻结');
    expect(confirmBlockReason(recon({ status: 'REJECTED' }))).toContain('已冻结');
  });

  it('差异都裁决完了才可以确认 —— 原因串为空', () => {
    expect(confirmBlockReason(recon({ status: 'MATCHED', openDifferenceCount: 0 }))).toBe('');
  });
});

describe('展示口径：金额原样显示，不做前端算术', () => {
  it('金额按服务端给的数原样显示（四位小数 + 币种）', () => {
    expect(formatSupplierMoney(1800, 'USD')).toBe('1800.0000 USD');
    expect(formatSupplierMoney(-100, 'USD')).toBe('-100.0000 USD');
    expect(formatSupplierMoney(null, 'USD')).toBe('—');
  });

  it('数量按六位小数原样显示', () => {
    expect(formatSupplierQuantity(90)).toBe('90.000000');
    expect(formatSupplierQuantity(null)).toBe('—');
  });

  it('汇总按币种分开，且未确认时明说「尚未确认」', () => {
    const label = reconciliationSummaryLabel({
      currency: 'USD',
      statementAmount: 2000,
      financeAmount: null,
      adjustmentAmount: 0,
      reconCount: 2,
      openDifferenceCount: 1,
    });
    expect(label).toContain('USD');
    expect(label).toContain('尚未确认');
    expect(label).toContain('未裁决差异 1 条');
  });
});

describe('留痕：采购侧动作与财务侧动作分开显示', () => {
  it('动作方分得开', () => {
    expect(actorRoleLabel('PURCHASE')).toBe('采购侧');
    expect(actorRoleLabel('FINANCE')).toBe('财务侧');
  });

  it('事件类型各有可读名', () => {
    expect(reconciliationEventLabel('IMPORTED')).toBe('导入采购侧对账单');
    expect(reconciliationEventLabel('DIFFERENCE_RESOLVED')).toBe('裁决差异');
    expect(reconciliationEventLabel('CONFIRMED')).toBe('财务确认');
    expect(reconciliationEventLabel('PAYABLE_GENERATED')).toBe('生成应付依据');
  });
});
