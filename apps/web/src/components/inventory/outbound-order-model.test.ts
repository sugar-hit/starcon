import { describe, expect, it } from 'vitest';
import type {
  OutboundBulkFailure,
  OutboundOrderCapabilities,
  OutboundOrderSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  canCancel,
  canRequestPicking,
  carrierWarning,
  errorText,
  failureList,
  isTerminal,
  nextStatuses,
  offsetIso,
  optionLabel,
  pageSummary,
  pieceText,
  reasonRequired,
  sourceText,
  transitionOf,
  volumeText,
} from './outbound-order-model';

/**
 * 8.3.1.1 出库单列表前端模型的展示口径测试。
 *
 * <p>这些函数全是「数字与文案怎么讲」的决定，讲错了比算错了更危险：
 * 把「已交接待拣货」显示成「已拣货」，作业员就会以为货已经离库位。
 */

function order(over: Partial<OutboundOrderSummary> = {}): OutboundOrderSummary {
  return {
    id: 'o1',
    orderCode: 'OB-20261007-ABCD1234',
    sourceType: 'SALES',
    sourceTypeLabel: '销售',
    sourceTransferOrderId: null,
    sourceTransferCode: null,
    sourceExternalRef: 'SO-1001',
    warehouseId: 'w1',
    warehouseCode: 'WH-ONE',
    warehouseName: '一仓',
    carrierCode: '',
    status: 'PENDING_PICKING',
    statusLabel: '待拣',
    skuLineCount: 2,
    totalPieceCount: 5.5,
    shippedAt: null,
    cancelledAt: null,
    cancelReason: null,
    createdByUserId: 'u1',
    createdAt: '2026-10-07T10:00:00Z',
    pickRequested: false,
    ...over,
  };
}

const capabilities: OutboundOrderCapabilities = {
  sourceTypes: [{ name: 'SALES', label: '销售', description: '' }],
  statuses: [],
  transitions: [
    {
      from: 'PENDING_RECHECK',
      fromLabel: '待复核',
      to: 'PICKING',
      toLabel: '拣货中',
      reasonRequired: true,
      requiresCarrier: false,
      note: '复核不通过退回拣货',
    },
    {
      from: 'PENDING_SHIPMENT',
      fromLabel: '待发货',
      to: 'SHIPPED',
      toLabel: '已发货',
      reasonRequired: true,
      requiresCarrier: true,
      note: '承运商已揽收',
    },
  ],
  filters: [],
  printDocuments: [],
  pickingHandoverNote: '',
  orderFulfillmentNote: '',
  dataScopeNote: '',
  printScopeNote: '',
};

describe('8.3.1.1 出库单列表 · 展示口径', () => {
  it('终态就是已发货与已取消', () => {
    expect(isTerminal(order({ status: 'SHIPPED' }))).toBe(true);
    expect(isTerminal(order({ status: 'CANCELLED' }))).toBe(true);
    expect(isTerminal(order({ status: 'PENDING_PACKING' }))).toBe(false);
  });

  it('生成拣货任务只在「待拣且没交接过」时可点', () => {
    expect(canRequestPicking(order())).toBe(true);
    // 已交接过：状态可能还停在待拣（并发），但不能再交一次。
    expect(canRequestPicking(order({ pickRequested: true }))).toBe(false);
    expect(canRequestPicking(order({ status: 'PICKING' }))).toBe(false);
    expect(canRequestPicking(order({ status: 'SHIPPED' }))).toBe(false);
  });

  it('终态不可取消，未发货的都可以取消', () => {
    expect(canCancel(order({ status: 'SHIPPED' }))).toBe(false);
    expect(canCancel(order({ status: 'CANCELLED' }))).toBe(false);
    expect(canCancel(order({ status: 'PENDING_SHIPMENT' }))).toBe(true);
  });

  it('件数去掉多余的尾随零但不改值', () => {
    expect(pieceText('5.500000')).toBe('5.5');
    expect(pieceText('3.000000')).toBe('3');
    expect(pieceText('2.500000')).toBe('2.5');
    expect(pieceText(null)).toBe('—');
    expect(pieceText(undefined)).toBe('—');
    expect(pieceText('')).toBe('—');
    // 全部是 0 时不能显示成空串。
    expect(pieceText('0.000000')).toBe('0');
  });

  it('行数与件数拼成一句话，不把两者混成一个数', () => {
    expect(volumeText(order())).toBe('2 个 SKU / 5.5 件');
  });

  it('调拨显示调拨单号，其余来源显示外部单号', () => {
    expect(
      sourceText(
        order({ sourceType: 'TRANSFER', sourceTransferOrderId: 't1', sourceTransferCode: 'TR-1' }),
      ),
    ).toBe('TR-1');
    expect(sourceText(order())).toBe('SO-1001');
    // 外部单号缺失时如实显示占位，不编一个单号出来。
    expect(sourceText(order({ sourceExternalRef: null }))).toBe('—');
  });

  it('状态跃迁按 (from,to) 查说明，查不到返回 undefined', () => {
    expect(transitionOf(capabilities, 'PENDING_RECHECK', 'PICKING')?.note).toBe(
      '复核不通过退回拣货',
    );
    expect(transitionOf(capabilities, 'PENDING_PICKING', 'SHIPPED')).toBeUndefined();
    expect(reasonRequired(capabilities, 'PENDING_RECHECK', 'PICKING')).toBe(true);
    expect(reasonRequired(capabilities, 'PENDING_PICKING', 'SHIPPED')).toBe(false);
    expect(nextStatuses(capabilities, 'PENDING_SHIPMENT')).toHaveLength(1);
    expect(nextStatuses(null, 'PENDING_SHIPMENT')).toEqual([]);
  });

  it('没有承运渠道时提前提示，而不是等提交后报错', () => {
    expect(carrierWarning(order())).toContain('承运渠道');
    expect(carrierWarning(order({ carrierCode: 'DHL' }))).toBeNull();
  });

  it('时间筛选一定带时区偏移', () => {
    const iso = offsetIso(new Date('2026-10-07T10:00:00Z'));
    expect(iso).toBe('2026-10-07T10:00:00.000Z');
    expect(offsetIso(undefined)).toBeUndefined();
  });

  it('批量操作的失败逐条可读，不合并成一句话', () => {
    const failures: OutboundBulkFailure[] = [
      { orderId: 'o1', orderCode: 'OB-1', reason: '只有待拣的出库单能生成拣货任务' },
      { orderId: 'o2', orderCode: null, reason: '出库单不存在或不属于当前租户' },
    ];
    expect(failureList(failures)).toEqual([
      'OB-1：只有待拣的出库单能生成拣货任务',
      '出库单不存在或不属于当前租户',
    ]);
  });

  it('分页摘要不把空页说成没有数据', () => {
    expect(pageSummary({ items: [], total: 0, page: 0, size: 20 })).toBe('共 0 条');
    expect(pageSummary({ items: [order()], total: 41, page: 1, size: 20 }).length).toBeGreaterThan(
      0,
    );
    expect(pageSummary({ items: [order()], total: 41, page: 1, size: 20 })).toContain('共 41 条');
  });

  it('选项标签查不到时回退成原值，不显示空白', () => {
    expect(optionLabel(capabilities.sourceTypes, 'SALES')).toBe('销售');
    expect(optionLabel(capabilities.sourceTypes, 'TRANSFER')).toBe('TRANSFER');
  });

  it('错误文案取得到就显示，取不到就如实回退', () => {
    expect(errorText(new Error('承运渠道不能为空'))).toBe('承运渠道不能为空');
    expect(errorText('something')).toContain('暂时不可用');
  });
});
