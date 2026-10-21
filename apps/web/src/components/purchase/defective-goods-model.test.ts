import { describe, expect, it } from 'vitest';
import {
  CONCESSION_HINT,
  DISPOSITION_TYPE_OPTIONS,
  claimActions,
  concessionRequired,
  defectiveQuery,
  dispositionTypeLabel,
  emptyLine,
  exportUrl,
  fmtAmount,
  fmtQuantity,
  inventoryStatusText,
  isReleasing,
  isTerminal,
  inspectionOptionText,
  lineDisplay,
  releaseQuantityFor,
  toLineInput,
  totalQuantity,
  totalReleased,
  withinDisposalLimit,
  type DefectiveClaimRow,
  type DefectiveInspectionOption,
} from './defective-goods-model';

describe('6.4.2.2 不良品处理前端模型', () => {
  it('处置类型词表覆盖蓝图点名的五个去向', () => {
    expect(DISPOSITION_TYPE_OPTIONS.map((o) => o.value)).toEqual([
      'RETURN_EXCHANGE',
      'REPAIR',
      'CONCESSION',
      'SCRAP',
      'PROMOTION',
    ]);
  });

  it('放行量只由处置类型决定', () => {
    // 让步接收与转为促销品整行回可售池。
    expect(releaseQuantityFor('CONCESSION', 30)).toBe(30);
    expect(releaseQuantityFor('PROMOTION', 12.5)).toBe(12.5);
    // 报废 / 退换货 / 返修的货离仓或不可售。
    expect(releaseQuantityFor('SCRAP', 30)).toBe(0);
    expect(releaseQuantityFor('RETURN_EXCHANGE', 30)).toBe(0);
    expect(releaseQuantityFor('REPAIR', 30)).toBe(0);
    expect(isReleasing('CONCESSION')).toBe(true);
    expect(isReleasing('SCRAP')).toBe(false);
  });

  it('让步接收才需要原因与权限码', () => {
    expect(concessionRequired('CONCESSION')).toBe(true);
    expect(concessionRequired('SCRAP')).toBe(false);
    expect(CONCESSION_HINT).toContain('trade.defective-disposition.concession');
  });

  it('提交前清掉调用方自报的放行量与让步栏', () => {
    const line = { ...emptyLine(), dispositionType: 'SCRAP' as const, quantity: 20 };
    line.releasedQuantity = 999;
    const cleaned = toLineInput(line);
    expect(cleaned.releasedQuantity).toBe(0);
    expect(cleaned.concessionReason).toBeNull();
    expect(cleaned.permissionCode).toBeNull();

    const concession = toLineInput({
      ...emptyLine(),
      dispositionType: 'CONCESSION',
      quantity: 5,
      concessionReason: '  外观瑕疵，客户接受  ',
      permissionCode: ' trade.defective-disposition.concession ',
    });
    expect(concession.releasedQuantity).toBe(5);
    expect(concession.concessionReason).toBe('外观瑕疵，客户接受');
    expect(concession.permissionCode).toBe('trade.defective-disposition.concession');
  });

  it('处置量超限的判据是「还能处置多少」', () => {
    expect(withinDisposalLimit(20, 20)).toBe(true);
    expect(withinDisposalLimit(12, 20)).toBe(true);
    expect(withinDisposalLimit(21, 20)).toBe(false);
  });

  it('数量与金额格式化不丢精度', () => {
    expect(fmtQuantity(20)).toBe('20');
    expect(fmtQuantity(12.5)).toBe('12.5');
    expect(fmtQuantity(null)).toBe('—');
    expect(fmtQuantity(undefined)).toBe('—');
    expect(fmtAmount(500)).toBe('500');
    expect(fmtAmount(450.1234)).toBe('450.1234');
    expect(fmtAmount(null)).toBe('—');
  });

  it('处置类型中文优先用后端给的标签', () => {
    expect(dispositionTypeLabel('SCRAP')).toBe('报废');
    expect(dispositionTypeLabel('SCRAP', '服务端标签')).toBe('服务端标签');
    expect(dispositionTypeLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('库存状态照实显示 —— 绝不把「没派发」说成「已登记」', () => {
    expect(inventoryStatusText({ inventoryStatus: 'DISPATCHED', inventoryStatusLabel: '已登记' })).toBe(
      '库存指令已登记到 warehouse',
    );
    expect(
      inventoryStatusText({ inventoryStatus: 'UNAVAILABLE', inventoryStatusLabel: '上游无写端点' }),
    ).toContain('上游无写端点');
    expect(
      inventoryStatusText({ inventoryStatus: 'NOT_DISPATCHED', inventoryStatusLabel: '未派发' }),
    ).toContain('待派发');
    expect(inventoryStatusText({ inventoryStatus: 'FAILED', inventoryStatusLabel: '失败' })).toContain(
      '失败',
    );
  });

  it('终态不再给动作', () => {
    expect(isTerminal('COMPLETED')).toBe(true);
    expect(isTerminal('CANCELLED')).toBe(true);
    expect(isTerminal('DRAFT')).toBe(false);
    expect(isTerminal('DECIDED')).toBe(false);
  });

  it('索赔动作按状态给出，不给点了 404 的按钮', () => {
    const base = { status: 'DRAFT' } as DefectiveClaimRow;
    expect(claimActions(base)).toEqual(['SUBMIT', 'WITHDRAW']);
    expect(claimActions({ ...base, status: 'SUBMITTED' })).toEqual([
      'AGREE',
      'REJECT',
      'WITHDRAW',
    ]);
    expect(claimActions({ ...base, status: 'AGREED' })).toEqual([]);
    expect(claimActions({ ...base, status: 'REJECTED' })).toEqual([]);
  });

  it('明细展示的放行量取自库里的值', () => {
    const display = lineDisplay({
      id: 'l1',
      dispositionType: 'CONCESSION',
      dispositionTypeLabel: '让步接收',
      quantity: 5,
      releasedQuantity: 5,
      concessionReason: '客户接受',
      permissionCode: 'trade.defective-disposition.concession',
      expectedReturnDate: null,
      repairVendor: null,
      promotionPlan: null,
      note: '',
      createdAt: '2026-10-21T00:00:00Z',
    });
    expect(display.type).toBe('让步接收');
    expect(display.released).toBe(5);
    expect(display.needsConcession).toBe(true);
  });

  it('明细合计与放行合计按 6 位小数收敛', () => {
    expect(
      totalQuantity([
        { quantity: 12.5 },
        { quantity: 7.5 },
      ]),
    ).toBe(20);
    expect(
      totalReleased([
        { releasedQuantity: 5 },
        { releasedQuantity: 2.5 },
      ]),
    ).toBe(7.5);
  });

  it('可处置质检单的下拉文案带上限', () => {
    const option: DefectiveInspectionOption = {
      id: 'i1',
      inspectionNo: 'QI-2026-0001',
      result: 'PARTIAL',
      skuId: null,
      skuCode: 'SKU-A',
      skuName: '商品 A',
      supplierId: null,
      supplierName: '供应商 A',
      lotSize: 100,
      defectiveQuantity: 20,
      qualifiedQuantity: 70,
      concessionQuantity: 10,
      sellableCeiling: 80,
      grossDeductedQuantity: 30,
      disposedQuantity: 5,
      remainingQuantity: 15,
      completedAt: null,
    };
    const text = inspectionOptionText(option);
    expect(text).toContain('QI-2026-0001');
    expect(text).toContain('不合格 20');
    expect(text).toContain('还能处置 15');
  });

  it('查询串不拼空值', () => {
    expect(defectiveQuery({ page: 0, size: 20 })).toBe('?page=0&size=20');
    expect(defectiveQuery({ keyword: '  ', status: '' })).toBe('');
    expect(defectiveQuery({ dispositionType: 'SCRAP' })).toBe('?dispositionType=SCRAP');
  });

  it('导出地址带 /api/ 前缀且不重复 trade/', () => {
    expect(exportUrl('abc')).toBe('/api/trade/defective-dispositions/abc/export');
    expect(exportUrl('abc')).not.toContain('/api/trade/trade/');
  });
});
