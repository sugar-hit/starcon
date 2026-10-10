import { describe, expect, it } from 'vitest';
import type {
  FactorySaved,
  ProcessingComponentLine,
  ProcessingOrderDetail,
  ProcessingOrderSaved,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  factoryKindLabels,
  fmt,
  inboundStatusLabel,
  isFullyIssued,
  isOverdue,
  issueProgressText,
  needsQuantity,
  outstandingCount,
  processingOrderQuery,
  processingStatusLabels,
  receiptGapText,
  statusActions,
  statusLabel,
} from './processing-order-model';

/**
 * 6.4.1.1 加工单前端模型。
 *
 * <p>这些断言钉的是**界面上不能搞错的判断**，不是文案：
 * 进度口径（R7 三元组）、终态不给动作、部分入库的差额提示、逾期口径。
 */

function component(overrides: Partial<ProcessingComponentLine> = {}): ProcessingComponentLine {
  return {
    id: 'c1',
    skuId: 's1',
    skuCode: 'FABRIC',
    unitQuantity: 0.5,
    scrapRate: 5,
    requiredQuantity: 5.25,
    issuedQuantity: 0,
    outstandingQuantity: 5.25,
    role: '',
    note: '',
    ...overrides,
  };
}

function order(overrides: Partial<ProcessingOrderSaved> = {}): ProcessingOrderSaved {
  return {
    id: 'o1',
    orderNo: 'PO-1',
    factoryId: 'f1',
    factoryCode: 'F-01',
    factoryName: '加工厂',
    outputSkuId: 'out1',
    outputSkuCode: 'OUT-1',
    bomId: 'bom1',
    bomVersionId: 'bv1',
    bomVersionNo: 'v1',
    plannedOutputQuantity: 10,
    completedQuantity: 0,
    dueOn: '2099-01-01',
    status: 'DRAFT',
    statusLabel: '草稿',
    componentCount: 1,
    issuedComponentCount: 0,
    totalRequiredQuantity: 5.25,
    totalIssuedQuantity: 0,
    note: '',
    version: 0,
    createdAt: '2099-01-01T00:00:00Z',
    releasedAt: null,
    completedAt: null,
    receivedAt: null,
    cancelReason: '',
    ...overrides,
  };
}

describe('进度口径：已领 / 需求（还欠）三个数自解释', () => {
  it('还欠 = 需求 − 已领', () => {
    const line = component({ issuedQuantity: 2, outstandingQuantity: 3.25 });
    expect(issueProgressText(line)).toBe('已领 2 / 需求 5.25（还欠 3.25）');
  });

  it('差额为 0 才算领满', () => {
    expect(isFullyIssued(component({ issuedQuantity: 5.25, outstandingQuantity: 0 }))).toBe(true);
    expect(isFullyIssued(component({ issuedQuantity: 5.2, outstandingQuantity: 0.05 }))).toBe(false);
  });

  it('未领满计数只数真正还欠的那些', () => {
    const lines = [
      component({ id: 'c1', issuedQuantity: 5.25, outstandingQuantity: 0 }),
      component({ id: 'c2', issuedQuantity: 1, outstandingQuantity: 4.25 }),
      component({ id: 'c3', issuedQuantity: 0, outstandingQuantity: 20 }),
    ];
    expect(outstandingCount(lines)).toBe(2);
  });

  it('数量保留 6 位小数的零头（组件常是「0.5 米面料 + 2 颗螺丝」）', () => {
    expect(fmt(5.25)).toBe('5.25');
    expect(fmt(5.250000)).toBe('5.25');
    expect(fmt(0.5)).toBe('0.5');
    expect(fmt(20)).toBe('20');
    expect(fmt(null)).toBe('—');
  });
});

describe('状态动作：终态一个都不给', () => {
  it('草稿只给下达与作废', () => {
    expect(statusActions('DRAFT').map((a) => a.status)).toEqual(['RELEASED', 'CANCELLED']);
  });

  it('已下达给开工与作废（不直接给完工：领料与加工是两件事）', () => {
    expect(statusActions('RELEASED').map((a) => a.status)).toEqual(['IN_PROCESS', 'CANCELLED']);
  });

  it('加工中给报完工与作废', () => {
    expect(statusActions('IN_PROCESS').map((a) => a.status)).toEqual(['COMPLETED', 'CANCELLED']);
  });

  it('已完工给登记入库', () => {
    expect(statusActions('COMPLETED').map((a) => a.status)).toEqual(['RECEIVED']);
  });

  it('已入库与已作废是终态：不给任何动作', () => {
    expect(statusActions('RECEIVED')).toEqual([]);
    expect(statusActions('CANCELLED')).toEqual([]);
  });

  it('报完工与登记入库要填数量，其余不需要', () => {
    expect(needsQuantity('COMPLETED')).toBe(true);
    expect(needsQuantity('RECEIVED')).toBe(true);
    expect(needsQuantity('RELEASED')).toBe(false);
    expect(needsQuantity('CANCELLED')).toBe(false);
  });
});

describe('报完工 ≠ 已入库：差额必须摆在界面上', () => {
  const detail = (receivedQuantity: number, outstandingQuantity: number) =>
    ({
      order: order({ status: 'COMPLETED' }),
      components: [],
      inboundRequest: {
        id: 'r1',
        orderId: 'o1',
        orderNo: 'PO-1',
        skuId: 'out1',
        skuCode: 'OUT-1',
        requestedQuantity: 10,
        receivedQuantity,
        outstandingQuantity,
        inventoryTransactionRef: '',
        status: outstandingQuantity > 0 ? 'PARTIAL' : 'RECEIVED',
        requestedAt: '2099-01-01T00:00:00Z',
        receivedAt: null,
        note: '',
      },
      events: [],
      nextStatuses: [],
      boundaryNotes: [],
    }) as ProcessingOrderDetail;

  it('还没入库（通知还没生成）时没有差额提示', () => {
    const empty = { ...detail(0, 10), inboundRequest: null } as ProcessingOrderDetail;
    expect(receiptGapText(empty.order, empty.inboundRequest)).toBeNull();
  });

  it('部分入库时明确写出「还差多少」，而不是合成一个「已完成」', () => {
    const d = detail(8, 2);
    expect(receiptGapText(d.order, d.inboundRequest)).toBe(
      '还有 2 件未入库（待入库 10，实收 8）',
    );
  });

  it('收满后不再提示差额', () => {
    const d = detail(10, 0);
    expect(receiptGapText(d.order, d.inboundRequest)).toBeNull();
  });
});

describe('逾期口径', () => {
  const yesterday = () => {
    const d = new Date();
    d.setDate(d.getDate() - 1);
    return d.toISOString().slice(0, 10);
  };

  it('交期已过且未入库 / 未作废 → 逾期', () => {
    expect(isOverdue(order({ dueOn: yesterday(), status: 'IN_PROCESS' }))).toBe(true);
  });

  it('已入库与已作废不算逾期（货到了 / 单子作废了，都不再是欠着）', () => {
    expect(isOverdue(order({ dueOn: yesterday(), status: 'RECEIVED' }))).toBe(false);
    expect(isOverdue(order({ dueOn: yesterday(), status: 'CANCELLED' }))).toBe(false);
  });

  it('交期未到不算逾期', () => {
    expect(isOverdue(order({ dueOn: '2099-01-01', status: 'DRAFT' }))).toBe(false);
  });
});

describe('查询串构造：筛选项真的进 URL', () => {
  it('空条件不带问号', () => {
    expect(processingOrderQuery({})).toBe('');
  });

  it('关键字要 trim 且转义', () => {
    expect(processingOrderQuery({ keyword: '  PO-1 ' })).toBe('?keyword=PO-1');
  });

  it('状态 / 逾期 / 分页逐项带上', () => {
    expect(processingOrderQuery({ status: 'RELEASED', overdueOnly: true, page: 2, size: 20 })).toBe(
      '?status=RELEASED&overdueOnly=true&page=2&size=20',
    );
  });

  it('overdueOnly 为 false 时不带（后端默认 false，不必显式传）', () => {
    expect(processingOrderQuery({ overdueOnly: false })).toBe('');
  });
});

describe('标签表覆盖后端词表', () => {
  it('加工单六个状态都有中文标签', () => {
    for (const key of Object.keys(processingStatusLabels)) {
      expect(statusLabel(key)).toBe(processingStatusLabels[key]);
    }
    expect(Object.keys(processingStatusLabels)).toEqual([
      'DRAFT',
      'RELEASED',
      'IN_PROCESS',
      'COMPLETED',
      'RECEIVED',
      'CANCELLED',
    ]);
  });

  it('未知状态如实显示原值，不显示成 undefined', () => {
    expect(statusLabel('WHATEVER')).toBe('WHATEVER');
    expect(inboundStatusLabel('WHATEVER')).toBe('WHATEVER');
  });

  it('厂别两种都有标签', () => {
    expect(factoryKindLabels.OUTSOURCED).toBe('委外工厂');
    expect(factoryKindLabels.IN_HOUSE).toBe('自有工厂');
  });
});

describe('加工厂数据形状', () => {
  it('列表项带齐展示所需的字段', () => {
    const factory: FactorySaved = {
      id: 'f1',
      code: 'F-01',
      name: '加工厂',
      factoryKind: 'OUTSOURCED',
      status: 'ACTIVE',
      note: '',
      version: 0,
      createdAt: '2099-01-01T00:00:00Z',
      updatedAt: '2099-01-01T00:00:00Z',
    };
    expect(factoryKindLabels[factory.factoryKind]).toBe('委外工厂');
  });
});
