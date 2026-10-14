import { describe, expect, it } from 'vitest';
import type {
  PendingShipmentUnitRow,
  ShipmentBatchTaskItemRow,
  ShipmentBatchTaskRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  batchStatusSeverity,
  batchTaskFinished,
  carrierChannelLabel,
  carrierMissing,
  errorText,
  failedItems,
  groupViews,
  needsAttention,
  nextActionText,
  pendingShipmentStatusLabel,
  shipmentBatchStatusLabel,
  shipmentBatchTaskTypeLabel,
  shipmentSlaBucketLabel,
  shipmentSlaSourceLabel,
  shipmentTaskItemStatusLabel,
  shipmentWaybillStatusLabel,
  slaBucketSeverity,
  summaryText,
  taskEligibility,
  waybillText,
  weightText,
} from './pending-shipment-model';

/**
 * 9.4.1.1 待发货前端模型的判定口径。
 *
 * <p>这些判定对应蓝图里那几句**加粗**的话（按承运商分组是大促期效率关键 /
 * 混在一起会导致交接混乱），所以逐条钉住 —— 判定写错在页面上是「看起来正常、
 * 现场照着错的口径分拣」。
 */

const unit = (over: Partial<PendingShipmentUnitRow> = {}): PendingShipmentUnitRow =>
  ({
    id: 'u1',
    sourcePackingOrderId: 'p1',
    sourceOutboundOrderId: 'o1',
    warehouseRef: 'WH-SH-01',
    warehouseId: 'w1',
    outboundOrderNo: 'OB-1',
    salesOrderId: 's1',
    salesOrderNo: 'SO-1',
    shopRef: '',
    buyerName: '',
    destinationCountry: '',
    carrierChannel: 'THIRD_PARTY',
    carrierCode: 'SF',
    slaBucket: 'DUE_TODAY',
    slaSource: 'MANUAL_ATTESTATION',
    parcelCount: 2,
    totalWeightKg: 3.5,
    status: 'PENDING',
    waybillNo: '',
    labelPrinted: false,
    packedAt: '2026-10-14T10:00:00+08:00',
    slaDeadline: '2026-10-14T20:00:00+08:00',
    handedOverAt: null,
    syncedAt: '2026-10-14T11:00:00+08:00',
    nextActions: '批量打单 → 批量交接',
    ...over,
  }) as PendingShipmentUnitRow;

const task = (over: Partial<ShipmentBatchTaskRow> = {}): ShipmentBatchTaskRow =>
  ({
    id: 't1',
    taskNo: 'SBT-20261014000001',
    taskType: 'HANDOVER',
    taskTypeLabel: '批量交接',
    warehouseId: 'w1',
    warehouseRef: 'WH-SH-01',
    carrierChannel: 'THIRD_PARTY',
    carrierCode: 'SF',
    status: 'PENDING',
    statusLabel: '待执行',
    unitCount: 2,
    succeededCount: 0,
    failedCount: 0,
    skippedCount: 0,
    failureReason: '',
    note: '大促交接',
    createdByMembershipId: 'm1',
    createdAt: '2026-10-14T11:00:00+08:00',
    startedAt: null,
    finishedAt: null,
    ...over,
  }) as ShipmentBatchTaskRow;

const item = (over: Partial<ShipmentBatchTaskItemRow> = {}): ShipmentBatchTaskItemRow =>
  ({
    id: 'i1',
    unitId: 'u1',
    outboundOrderNo: 'OB-1',
    salesOrderNo: 'SO-1',
    warehouseRef: 'WH-SH-01',
    carrierCode: 'SF',
    carrierChannel: 'THIRD_PARTY',
    parcelId: null,
    parcelNo: 0,
    status: 'SUCCEEDED',
    statusLabel: '成功',
    detail: '',
    processedAt: '2026-10-14T12:00:00+08:00',
    ...over,
  }) as ShipmentBatchTaskItemRow;

describe('状态与枚举口径', () => {
  it('状态标签全中文，未知值原样返回而不是空白', () => {
    expect(pendingShipmentStatusLabel('HANDED_OVER')).toBe('已交接');
    expect(pendingShipmentStatusLabel('PENDING')).toBe('待发货');
    expect(pendingShipmentStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(pendingShipmentStatusLabel(undefined)).toBe('未知');
    expect(shipmentBatchTaskTypeLabel('WAYBILL_REQUEST')).toBe('批量申请运单号');
    expect(shipmentBatchTaskTypeLabel('LABEL_PRINT')).toBe('批量打单');
    expect(shipmentBatchStatusLabel('PARTIAL')).toBe('部分成功');
    expect(shipmentTaskItemStatusLabel('SKIPPED')).toBe('跳过');
    expect(shipmentWaybillStatusLabel('ISSUED')).toBe('已签发');
    expect(carrierChannelLabel('FREIGHT_FORWARDER')).toBe('货代');
    expect(carrierChannelLabel(undefined)).toBe('—');
  });

  /**
   * 本模块**没有**「已发货」这个状态：最远是已交接。
   * 这条钉住「界面上不许出现已发货」这条边界，防止有人顺手加进映射表。
   */
  it('没有「已发货」标签：已发货属下游 8.3.4.1', () => {
    expect(pendingShipmentStatusLabel('SHIPPED')).toBe('SHIPPED');
    expect(Object.keys(pendingShipmentStatusLabelAll())).not.toContain('SHIPPED');
  });
});

function pendingShipmentStatusLabelAll(): Record<string, string> {
  return {
    PENDING: '待发货',
    WAYBILL_REQUESTED: '已申请运单号',
    LABEL_PRINTED: '已打单',
    HANDED_OVER: '已交接',
    CANCELLED: '已作废',
  };
}

describe('时限桶', () => {
  it('未登记时限独立成桶，不并进「今天到期」', () => {
    expect(shipmentSlaBucketLabel('UNSET')).toBe('未登记时限');
    expect(shipmentSlaBucketLabel('OVERDUE')).toBe('已逾期');
    expect(shipmentSlaBucketLabel(undefined)).toBe('未登记时限');
  });

  it('严重度：逾期与今天到期最红，48 小时次之，未登记只是提醒', () => {
    expect(slaBucketSeverity('OVERDUE')).toBe('danger');
    expect(slaBucketSeverity('DUE_TODAY')).toBe('danger');
    expect(slaBucketSeverity('DUE_48H')).toBe('warn');
    // 关键：未登记时限**不是**红色 —— 它是「还不知道」，不是「已经晚了」
    expect(slaBucketSeverity('UNSET')).toBe('normal');
    expect(slaBucketSeverity(undefined)).toBe('normal');
  });

  it('登记来源如实显示，未登记不冒充「平台回传」', () => {
    expect(shipmentSlaSourceLabel('MANUAL_ATTESTATION')).toBe('人工登记');
    expect(shipmentSlaSourceLabel('PLATFORM_FEED')).toBe('平台回传');
    expect(shipmentSlaSourceLabel('')).toBe('未登记');
    expect(shipmentSlaSourceLabel(undefined)).toBe('未登记');
  });
});

describe('入任务资格', () => {
  it('正常单元可入任务且无理由', () => {
    expect(taskEligibility(unit())).toEqual({ eligible: true, reason: '' });
  });

  /**
   * 承运商为空 = 还没走 9.3.x 路由。这类单元**上板但建不了任务** ——
   * 交接必须知道交给谁，所以按钮禁用并写明原因。
   */
  it('还没选承运商的单元不可入任务，并说清原因', () => {
    expect(carrierMissing(unit({ carrierCode: '' }))).toBe(true);
    const verdict = taskEligibility(unit({ carrierCode: '  ' }));
    expect(verdict.eligible).toBe(false);
    expect(verdict.reason).toContain('承运商');
  });

  it('已交接 / 已作废的单元不可入任务', () => {
    expect(taskEligibility(unit({ status: 'HANDED_OVER' })).eligible).toBe(false);
    expect(taskEligibility(unit({ status: 'HANDED_OVER' })).reason).toContain('同一批货发两次');
    expect(taskEligibility(unit({ status: 'CANCELLED' })).eligible).toBe(false);
  });
});

describe('批量任务状态', () => {
  it('终态不可重复执行', () => {
    expect(batchTaskFinished(task({ status: 'PENDING' }))).toBe(false);
    expect(batchTaskFinished(task({ status: 'RUNNING' }))).toBe(false);
    for (const status of ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'] as const) {
      expect(batchTaskFinished(task({ status })), `${status} 应是终态`).toBe(true);
    }
  });

  /**
   * 部分成功与全部成功必须**不同色**。
   * 这一屏是交接现场的看板 —— 「部分成功」显示成和「全部成功」一样的绿，
   * 等于把漏发的票藏起来。
   */
  it('PARTIAL 与 SUCCEEDED 分色，FAILED 最重', () => {
    expect(batchStatusSeverity('SUCCEEDED')).toBe('ok');
    expect(batchStatusSeverity('PARTIAL')).toBe('warn');
    expect(batchStatusSeverity('FAILED')).toBe('danger');
    expect(batchStatusSeverity('PENDING')).toBe('warn');
    expect(batchStatusSeverity('CANCELLED')).toBe('normal');
  });

  it('需要盯的任务 = 部分成功 + 全部失败', () => {
    expect(needsAttention(task({ status: 'PARTIAL' }))).toBe(true);
    expect(needsAttention(task({ status: 'FAILED' }))).toBe(true);
    expect(needsAttention(task({ status: 'SUCCEEDED' }))).toBe(false);
  });

  it('失败与跳过都要被挑出来（跳过 ≠ 成功）', () => {
    const items = [
      item({ id: 'a', status: 'SUCCEEDED' }),
      item({ id: 'b', status: 'SKIPPED', detail: '8.3.4.2 未交付' }),
      item({ id: 'c', status: 'FAILED', detail: '包裹已不存在' }),
    ];
    expect(failedItems(items).map((i) => i.id)).toEqual(['b', 'c']);
  });
});

describe('分组看板派生', () => {
  it('逾期或今天到期的组被标成需要盯', () => {
    const views = groupViews([
      {
        groupKey: 'SF',
        groupLabel: 'SF',
        unitCount: 5,
        parcelCount: 5,
        totalWeightKg: 5,
        overdueCount: 2,
        dueTodayCount: 0,
        unsetSlaCount: 0,
      },
      {
        groupKey: 'DHL',
        groupLabel: 'DHL',
        unitCount: 1,
        parcelCount: 1,
        totalWeightKg: 1,
        overdueCount: 0,
        dueTodayCount: 0,
        unsetSlaCount: 1,
      },
    ]);
    expect(views.find((v) => v.groupKey === 'SF')?.attention).toBe(true);
    expect(views.find((v) => v.groupKey === 'DHL')?.attention).toBe(false);
  });

  it('汇总文本把逾期 / 今天到期 / 未登记时限都报出来', () => {
    const text = summaryText({
      totalUnits: 10,
      pendingUnits: 6,
      waybillRequestedUnits: 1,
      labelPrintedUnits: 1,
      handedOverUnits: 1,
      cancelledUnits: 1,
      totalParcels: 12,
      totalWeightKg: 30,
      overdueUnits: 2,
      dueTodayUnits: 3,
      unsetSlaUnits: 4,
      openBatchTasks: 1,
    });
    expect(text).toContain('待发货 6');
    expect(text).toContain('逾期 2');
    expect(text).toContain('今天到期 3');
    expect(text).toContain('未登记时限 4');
    expect(summaryText(undefined)).toBe('');
  });
});

describe('呈现工具', () => {
  it('重量保留三位小数，缺失显示破折号而不是 0 kg', () => {
    expect(weightText(3.5)).toBe('3.500 kg');
    expect(weightText(0)).toBe('0.000 kg');
    expect(weightText(undefined)).toBe('—');
    expect(weightText(null)).toBe('—');
    // 0 与「没测过」不等价：显示成 0.000 kg 会让人以为确实没重量
    expect(weightText('')).toBe('—');
  });

  it('运单号未签发时写「待承运商回执」，不显示成空白', () => {
    expect(waybillText({ status: 'ISSUED', waybillNo: 'SF123' })).toBe('SF123');
    expect(waybillText({ status: 'REQUESTED', waybillNo: '' })).toBe('待承运商回执');
    expect(waybillText({ status: 'FAILED', waybillNo: '' })).toContain('被拒');
  });

  it('下一步原样透传后端判定，前端不自己猜状态机', () => {
    expect(nextActionText(unit({ nextActions: '先走 9.3.x 路由选定承运商' }))).toBe(
      '先走 9.3.x 路由选定承运商',
    );
    expect(nextActionText(unit({ nextActions: '' }))).toBe('—');
  });

  it('错误文案把异常翻成能读的一句话', () => {
    expect(errorText(new Error('仓库服务暂时不可达'))).toBe('仓库服务暂时不可达');
    expect(errorText('字符串异常')).toBe('未知错误');
  });
});
