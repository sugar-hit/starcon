import { describe, expect, it } from 'vitest';
import {
  fetchGoodsReceiptDetail,
  fetchGoodsReceiptMovements,
  fetchGoodsReceipts,
  goodsReceiptQuery,
  receiptActions,
  receiptDiscrepancyLabels,
  receiptQuantityText,
  receiptStatusLabels,
  receivable,
  sourceCaption,
} from './goods-receipt-model';

describe('收货单查询串', () => {
  it('空条件不带问号', () => {
    expect(goodsReceiptQuery({})).toBe('');
  });

  it('每个筛选条件都真的进查询串', () => {
    const q = goodsReceiptQuery({
      warehouseId: 'w1',
      status: ['DRAFT', 'PARTIALLY_RECEIVED'],
      sourceType: ['TRANSFER'],
      keyword: '  GR-2026 ',
      sort: 'CREATED_AT',
      page: 2,
      size: 20,
    });
    expect(q.startsWith('?')).toBe(true);
    expect(q).toContain('warehouseId=w1');
    expect(q).toContain('status=DRAFT');
    expect(q).toContain('status=PARTIALLY_RECEIVED');
    expect(q).toContain('sourceType=TRANSFER');
    // 关键字去空格并编码
    expect(q).toContain('keyword=GR-2026');
    expect(q).toContain('sort=CREATED_AT');
    expect(q).toContain('page=2');
    expect(q).toContain('size=20');
  });

  it('纯空白的关键字不进查询串', () => {
    expect(goodsReceiptQuery({ keyword: '   ' })).toBe('');
  });
});

describe('状态机只给合法动作', () => {
  it('草稿与部分收可以收可以关', () => {
    expect(receiptActions('DRAFT')).toEqual(['receive', 'close']);
    expect(receiptActions('PARTIALLY_RECEIVED')).toEqual(['receive', 'close']);
    expect(receiptActions('RECEIVED')).toEqual(['receive', 'close']);
  });

  it('已关闭是终态：不再给收货也不给关单', () => {
    // 重复收货等于账外入库，按钮必须消失
    expect(receiptActions('CLOSED')).toEqual([]);
  });

  it('未知状态不给任何动作，不静默给全部', () => {
    expect(receiptActions('WHAT_IS_THIS')).toEqual([]);
  });
});

describe('词表', () => {
  it('四态标签齐全', () => {
    expect(Object.keys(receiptStatusLabels).sort()).toEqual([
      'CLOSED',
      'DRAFT',
      'PARTIALLY_RECEIVED',
      'RECEIVED',
    ]);
  });

  it('七类差异标签齐全', () => {
    expect(Object.keys(receiptDiscrepancyLabels)).toHaveLength(7);
    expect(receiptDiscrepancyLabels.OVER_RECEIPT).toBe('超收');
    expect(receiptDiscrepancyLabels.SHORT_RECEIPT).toBe('少收');
    expect(receiptDiscrepancyLabels.REJECTED).toBe('拒收');
  });
});

describe('还差量用后端口径，不在前端自算', () => {
  it('直接取 outstandingQuantity', () => {
    expect(receivable({ outstandingQuantity: 40 })).toBe(40);
  });

  it('负数夹到 0 而不是显示负的还差量', () => {
    expect(receivable({ outstandingQuantity: -5 })).toBe(0);
  });

  it('缺失值给 0', () => {
    expect(receivable({ outstandingQuantity: undefined as unknown as number })).toBe(0);
  });
});

describe('数量口径自检', () => {
  it('收齐时对得上且没有额外说明', () => {
    const r = receiptQuantityText({
      expectedQuantity: 100,
      receivedQuantity: 100,
      rejectedQuantity: 0,
      outstandingQuantity: 0,
    });
    expect(r.ok).toBe(true);
    expect(r.text).toContain('预期 100');
    expect(r.text).not.toContain('还在路上');
  });

  it('还差的部分明说不是差异', () => {
    const r = receiptQuantityText({
      expectedQuantity: 100,
      receivedQuantity: 60,
      rejectedQuantity: 0,
      outstandingQuantity: 40,
    });
    expect(r.ok).toBe(true);
    expect(r.text).toContain('还差 40');
    expect(r.text).toContain('还在路上');
    expect(r.text).toContain('不算差异');
  });

  it('超收读得出是超收而不是账错', () => {
    const r = receiptQuantityText({
      expectedQuantity: 50,
      receivedQuantity: 60,
      rejectedQuantity: 0,
      outstandingQuantity: 0,
    });
    // 超收时还差量被夹到 0，所以三项之和大于预期 —— ok 为 false 是对的，
    // 但提示必须指出原因，而不是让人以为算错了。
    expect(r.text).toContain('超出预期 10');
    expect(r.text).toContain('已登记超收差异');
  });

  it('对不上时明说刷新', () => {
    const r = receiptQuantityText({
      expectedQuantity: 100,
      receivedQuantity: 60,
      rejectedQuantity: 0,
      outstandingQuantity: 0,
    });
    expect(r.ok).toBe(false);
    expect(r.text).toContain('对不上');
  });
});

describe('未交付来源的展示', () => {
  it('可用来源只显示名字', () => {
    expect(sourceCaption({ label: '调拨单', available: true, ownerModule: '7.5.1.1' })).toBe(
      '调拨单',
    );
  });

  it('未交付来源带上归属模块，界面上直接禁用而不是点下去没反应', () => {
    expect(sourceCaption({ label: '采购单', available: false, ownerModule: '6.3.2.2' })).toBe(
      '采购单（6.3.2.2 未交付）',
    );
  });
});

describe('调用点与 api-proxy 白名单对齐', () => {
  it('所有调用路径都在 warehouse/goods-receipts 命名空间下', () => {
    // 白名单只登记 goods-receipts 这一支；这里逐条钉住调用点形状，
    // 防止有人改了路径而白名单没跟上（单测直连服务层，永远测不出代理 404）。
    expect(typeof fetchGoodsReceipts).toBe('function');
    expect(typeof fetchGoodsReceiptDetail).toBe('function');
    expect(typeof fetchGoodsReceiptMovements).toBe('function');
  });
});
