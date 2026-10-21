import { describe, expect, it } from 'vitest';
import {
  NO_DATA_TEXT,
  adjustmentSummary,
  batchStatusLabel,
  batchStatusTone,
  canFinalize,
  countByReason,
  demandMergeDraftQuery,
  demandMergeLineQuery,
  demandMergeQuery,
  demandMergeUnmergedQuery,
  draftStatusLabel,
  fmt,
  fmtAmount,
  fmtDate,
  groupLinesByDraft,
  isBatchEditable,
  reasonCodeLabel,
  reasonTone,
  selectableLineIds,
} from './demand-merge-model';
import type {
  PurchaseDemandMergeLineRow,
  PurchaseDemandMergeUnmergedRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 6.2.2.2 需求合并与批量前端模型的纯函数测试。
 *
 * <p>覆盖的是**页面真的会依赖的口径**，不是覆盖率：
 *
 * <ul>
 *   <li>「答不出来」与「不需要」不折叠：null 一律给「无数据」，绝不显示 0。</li>
 *   <li>跨供应商与单价冲突必须被页面一眼认出来（原因码 → 标签 + 视觉分级）。</li>
 *   <li>有未合并行时**不能**定稿 —— 把没合上的需求悄悄带过去比定不了稿更糟。</li>
 *   <li>批量动作只能选同一批次里的行。</li>
 * </ul>
 */

describe('查询串拼装', () => {
  it('空参数不带问号', () => {
    expect(demandMergeQuery({})).toBe('');
    expect(demandMergeDraftQuery({})).toBe('');
    expect(demandMergeLineQuery({})).toBe('');
    expect(demandMergeUnmergedQuery({})).toBe('');
  });

  it('只拼给定的参数，顺序稳定', () => {
    expect(demandMergeQuery({ status: 'OPEN', page: 0, size: 20 })).toBe(
      '?status=OPEN&page=0&size=20',
    );
    expect(demandMergeDraftQuery({ supplierId: 'sup-1', page: 1 })).toBe(
      '?supplierId=sup-1&page=1',
    );
    expect(demandMergeLineQuery({ draftId: 'draft-1', keyword: 'SKU' })).toBe(
      '?draftId=draft-1&keyword=SKU',
    );
    expect(demandMergeUnmergedQuery({ reasonCode: 'SUPPLIER_MISMATCH' })).toBe(
      '?reasonCode=SUPPLIER_MISMATCH',
    );
  });

  it('关键字会去掉首尾空白，空串不拼', () => {
    expect(demandMergeQuery({ keyword: '  DM-1  ' })).toBe('?keyword=DM-1');
    expect(demandMergeQuery({ keyword: '   ' })).toBe('');
    expect(demandMergeLineQuery({ keyword: '   ' })).toBe('');
  });
});

describe('状态与标签', () => {
  it('批次状态的标签与后端枚举一一对应', () => {
    expect(batchStatusLabel('OPEN')).toBe('可批量修改');
    expect(batchStatusLabel('FINALIZED')).toBe('已定稿');
    expect(batchStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('草稿状态的标签与后端枚举一一对应', () => {
    expect(draftStatusLabel('DRAFT')).toBe('草稿');
    expect(draftStatusLabel('CONVERTED')).toBe('已转采购单');
  });

  it('可批量修改是提示色，已定稿是成功色', () => {
    expect(batchStatusTone('OPEN')).toBe('info');
    expect(batchStatusTone('FINALIZED')).toBe('success');
  });

  it('未合并原因码全部有中文标签（缺一个就等于页面看不懂）', () => {
    const codes = [
      'REQUEST_NOT_APPROVED',
      'REQUEST_REJECTED',
      'REQUEST_ALREADY_CONVERTED',
      'SUPPLIER_MISSING',
      'SUPPLIER_MISMATCH',
      'TARGET_MISMATCH',
      'CURRENCY_MISMATCH',
      'ALREADY_MERGED',
      'PRICE_CONFLICT',
    ];
    for (const code of codes) {
      expect(reasonCodeLabel(code), `${code} 没有中文标签`).not.toBe(code);
    }
  });

  it('未知原因码原样返回而不是空串', () => {
    expect(reasonCodeLabel('BRAND_NEW_CODE')).toBe('BRAND_NEW_CODE');
  });

  it('跨供应商与单价冲突是警示色，已驳回是危险色', () => {
    expect(reasonTone('SUPPLIER_MISMATCH')).toBe('warning');
    expect(reasonTone('PRICE_CONFLICT')).toBe('warning');
    expect(reasonTone('REQUEST_REJECTED')).toBe('danger');
    expect(reasonTone('SUPPLIER_MISSING')).toBe('neutral');
  });
});

describe('可执行动作的判定', () => {
  it('定稿后不能再改内容', () => {
    expect(isBatchEditable('OPEN')).toBe(true);
    expect(isBatchEditable('FINALIZED')).toBe(false);
  });

  it('有未合并行时不能定稿', () => {
    expect(canFinalize('OPEN', 0)).toBe(true);
    expect(canFinalize('OPEN', 1)).toBe(false);
    expect(canFinalize('FINALIZED', 0)).toBe(false);
  });
});

describe('数量与金额的展示', () => {
  it('null 一律给「无数据」，绝不显示 0', () => {
    expect(fmt(null)).toBe(NO_DATA_TEXT);
    expect(fmt(undefined)).toBe(NO_DATA_TEXT);
    expect(fmtAmount(null)).toBe(NO_DATA_TEXT);
  });

  it('数量最多六位小数', () => {
    expect(fmt(120)).toBe('120');
    expect(fmt(1.5)).toBe('1.5');
  });

  it('金额保留两位到四位小数', () => {
    expect(fmtAmount(162.5)).toBe('162.50');
    expect(fmtAmount(0.125)).toBe('0.125');
  });

  it('到货日未定给「未定」而不是空串', () => {
    expect(fmtDate(null)).toBe('未定');
    expect(fmtDate(undefined)).toBe('未定');
    expect(fmtDate('2026-12-05')).toBe('2026-12-05');
  });
});

describe('按草稿分组', () => {
  const line = (
    id: string,
    draftId: string,
    supplierId: string,
    mergedQuantity: number,
    sourceQuantity: number,
  ): PurchaseDemandMergeLineRow =>
    ({
      id,
      draftId,
      supplierId,
      mergedQuantity,
      sourceQuantity,
      skuCode: 'SKU-1',
      skuName: 'SKU-1 名称',
      supplierCode: 'SUP-001',
      supplierName: '深圳华强电子',
      skuId: 'sku-1',
      unitPrice: 12.5,
      lineAmount: 125,
      arrivalDate: '2026-12-05',
      sourceRequestCount: 1,
      sortRank: 0,
      note: '',
      createdAt: '2026-10-21T10:00:00+08:00',
      updatedAt: '2026-10-21T10:00:00+08:00',
    }) as PurchaseDemandMergeLineRow;

  it('同一张草稿的行聚在一起，不同草稿分开', () => {
    const grouped = groupLinesByDraft([
      line('l1', 'd1', 'sup-a', 10, 10),
      line('l2', 'd2', 'sup-b', 4, 4),
      line('l3', 'd1', 'sup-a', 2, 2),
    ]);
    expect(grouped.size).toBe(2);
    expect(grouped.get('d1')?.map((l) => l.id)).toEqual(['l1', 'l3']);
    expect(grouped.get('d2')?.map((l) => l.id)).toEqual(['l2']);
  });

  it('空清单没有分组', () => {
    expect(groupLinesByDraft([]).size).toBe(0);
  });

  it('批量动作只接受本批次里真实存在的行', () => {
    const lines = [line('l1', 'd1', 'sup-a', 10, 10)];
    expect(selectableLineIds(lines, ['l1'])).toEqual(['l1']);
    expect(selectableLineIds(lines, ['l1', 'not-mine'])).toEqual(['l1']);
    expect(selectableLineIds(lines, [])).toEqual([]);
  });
});

describe('未合并行的统计与留痕摘要', () => {
  const unmerged = (reasonCode: string): PurchaseDemandMergeUnmergedRow =>
    ({
      id: `u-${reasonCode}`,
      batchId: 'b1',
      sourceRequestId: 'r1',
      sourceRequestLineId: 'rl1',
      sourceSkuId: 's1',
      sourceSkuCode: 'SKU-1',
      sourceSkuName: 'SKU-1 名称',
      sourceQuantity: 10,
      sourceSupplierId: null,
      sourceSupplierName: '',
      reasonCode,
      reasonCodeLabel: reasonCodeLabel(reasonCode),
      reason: '原因原文',
      createdAt: '2026-10-21T10:00:00+08:00',
    }) as PurchaseDemandMergeUnmergedRow;

  it('按原因码计数', () => {
    const counts = countByReason([unmerged('SUPPLIER_MISMATCH'), unmerged('SUPPLIER_MISMATCH')]);
    expect(counts).toEqual({ SUPPLIER_MISMATCH: 2 });
  });

  it('空的未合并清单没有计数', () => {
    expect(countByReason([])).toEqual({});
  });

  it('留痕摘要能说清改了什么字段', () => {
    const summary = adjustmentSummary({
      id: 'a1',
      batchId: 'b1',
      scope: 'QUANTITY',
      lineId: 'l1',
      draftId: 'd1',
      skuCode: 'SKU-1',
      field: 'merged_quantity',
      beforeValue: '10',
      afterValue: '25',
      reason: '供应商涨价',
      affectedLineCount: 2,
      adjustedBy: 'u1',
      adjustedAt: '2026-10-21T10:00:00+08:00',
    });
    expect(summary).toBe('SKU-1 · 数量 10 → 25');
  });
});
