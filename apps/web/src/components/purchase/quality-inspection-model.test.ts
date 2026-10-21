import { describe, expect, it } from 'vitest';
import type {
  IncomingConcessionRow,
  IncomingOrderDetail,
  IncomingOrderItemRow,
  IncomingOrderRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  CONCESSION_REQUIRED_HINT,
  ITEM_OPTIONS,
  balanceIsBalanced,
  balanceText,
  canComplete,
  dispositionText,
  fmtQuantity,
  isTerminal,
  itemLabel,
  itemResultLabel,
  missingItemsText,
  qualityInspectionQuery,
  samplingText,
  totalConcession,
  totalDeducted,
} from './quality-inspection-model';

/**
 * 6.4.2.1 质检单前端模型。
 *
 * <p>这些断言钉的是**界面上不能搞错的判断**，不是文案：
 * 三量自洽、抽样缺口、终态不给动作、缺项不能完结、分流状态不得说成「已分流」。
 */

function row(overrides: Partial<IncomingOrderRow> = {}): IncomingOrderRow {
  return {
    id: 'q1',
    inspectionNo: 'QI-20261021-abcdef12',
    inspectionType: 'INCOMING',
    inspectionTypeLabel: '来料质检',
    status: 'INSPECTING',
    statusLabel: '检验中',
    result: null,
    resultLabel: null,
    templateId: 't1',
    templateCode: 'TPL-STD',
    templateName: '标准模板',
    categoryId: null,
    categoryName: null,
    skuId: 's1',
    skuCode: 'SKU-A',
    skuName: '商品 A',
    supplierId: null,
    supplierName: null,
    receiptCode: 'GR-1',
    sourceRefCode: null,
    batchNo: null,
    arrivedQuantity: 100,
    lotSize: 100,
    sampleQuantity: 10,
    sampledQuantity: 10,
    qualifiedQuantity: 80,
    defectiveQuantity: 14,
    concessionQuantity: 6,
    grossDeductedQuantity: 20,
    sellableQuantity: 86,
    nonSellableQuantity: 14,
    disposition: 'PENDING_INSPECTION',
    dispositionLabel: '待检',
    dispositionStatus: 'UNAVAILABLE',
    dispositionStatusLabel: '上游无写端点',
    currencyCode: 'USD',
    unitPrice: 2.5,
    deductedAmount: 50,
    itemCount: 2,
    concludedItemCount: 2,
    concessionCount: 1,
    criticalFailure: false,
    createdAt: '2026-10-21T00:00:00Z',
    completedAt: null,
    ...overrides,
  };
}

function item(overrides: Partial<IncomingOrderItemRow> = {}): IncomingOrderItemRow {
  return {
    id: 'i1',
    templateItemId: 'ti1',
    itemCode: 'APPEARANCE',
    itemCodeLabel: '外观',
    itemName: '外观检查',
    critical: false,
    result: 'FAIL',
    resultLabel: '不合格',
    failedSampleQuantity: 2,
    deductedQuantity: 12,
    defectType: null,
    defectDescription: '有划痕',
    inspectedAt: null,
    createdAt: '2026-10-21T00:00:00Z',
    ...overrides,
  };
}

function concession(overrides: Partial<IncomingConcessionRow> = {}): IncomingConcessionRow {
  return {
    id: 'c1',
    itemCode: null,
    itemCodeLabel: null,
    quantity: 6,
    reason: '轻微划痕，客户接受',
    permissionCode: 'trade.quality-inspection.concession',
    approvedBy: 'u1',
    createdAt: '2026-10-21T00:00:00Z',
    ...overrides,
  };
}

describe('6.4.2.1 质检单前端模型', () => {
  it('三量之和必须等于本次质检覆盖的批量', () => {
    expect(balanceIsBalanced(row())).toBe(true);
    // 少一个数就不自洽 —— 页面必须显红，而不是照样显示三个数。
    expect(balanceIsBalanced(row({ qualifiedQuantity: 70 }))).toBe(false);
    expect(balanceIsBalanced(row({ lotSize: 200 }))).toBe(false);
  });

  it('三量等式直接把算式写在页面上', () => {
    expect(balanceText(row())).toBe('合格 80 + 不合格 14 + 让步接收 6 = 100');
  });

  it('抽样缺口如实显示', () => {
    expect(samplingText(row())).toBe('应抽 10，实抽 10');
    expect(samplingText(row({ sampledQuantity: 8 }))).toBe('应抽 10，实抽 8（还差 2）');
  });

  it('终态不再给动作', () => {
    expect(isTerminal('COMPLETED')).toBe(true);
    expect(isTerminal('CANCELLED')).toBe(true);
    expect(isTerminal('DRAFT')).toBe(false);
    expect(isTerminal('INSPECTING')).toBe(false);
  });

  it('还有检验项没结论就不能完结', () => {
    const detail = {
      missingItemCodes: ['PACKAGING'],
      missingItemLabels: ['包装'],
    } as unknown as IncomingOrderDetail;
    expect(canComplete(detail)).toBe(false);
    expect(missingItemsText(detail)).toBe('还有检验项没有结论：包装（PACKAGING）');
    const done = {
      missingItemCodes: [],
      missingItemLabels: [],
    } as unknown as IncomingOrderDetail;
    expect(canComplete(done)).toBe(true);
    expect(missingItemsText(done)).toBe('');
  });

  it('分流状态照实显示，绝不说成「已分流」', () => {
    const text = dispositionText(row());
    expect(text).toContain('待检');
    expect(text).toContain('上游无写端点');
    expect(text).not.toContain('已登记分流');
  });

  it('数量格式化保留整数形态', () => {
    expect(fmtQuantity(100)).toBe('100');
    expect(fmtQuantity(12.5)).toBe('12.5');
    expect(fmtQuantity(null)).toBe('—');
    expect(fmtQuantity(undefined)).toBe('—');
  });

  it('检验项与结论的中文标签优先用后端给的', () => {
    expect(itemLabel('APPEARANCE', '外观')).toBe('外观');
    expect(itemLabel('APPEARANCE')).toBe('外观');
    expect(itemLabel('UNKNOWN_CODE')).toBe('UNKNOWN_CODE');
    expect(itemResultLabel('FAIL', '不合格')).toBe('不合格');
    expect(itemResultLabel('FAIL')).toBe('不合格');
    // 蓝图点名的五项检验项一个都不能少。
    expect(ITEM_OPTIONS.map((o) => o.value)).toEqual([
      'APPEARANCE',
      'FUNCTION',
      'SPECIFICATION',
      'PACKAGING',
      'CERTIFICATION',
    ]);
  });

  it('逐项扣量与让步量分别汇总', () => {
    expect(totalDeducted([item(), item({ deductedQuantity: 8 })])).toBe(20);
    expect(totalConcession([concession(), concession({ quantity: 4 })])).toBe(10);
  });

  it('查询串里的空值不拼进去', () => {
    expect(qualityInspectionQuery({})).toBe('');
    expect(qualityInspectionQuery({ keyword: '  ' })).toBe('');
    expect(qualityInspectionQuery({ keyword: ' SKU-A ', page: 1, size: 20 })).toBe(
      '?keyword=SKU-A&page=1&size=20',
    );
  });

  it('让步接收的必填提示写明了原因与权限码', () => {
    expect(CONCESSION_REQUIRED_HINT).toContain('原因');
    expect(CONCESSION_REQUIRED_HINT).toContain('权限码');
  });
});
