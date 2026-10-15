import { describe, expect, it } from 'vitest';
import {
  buyerVisibleLabel,
  callbackGapNote,
  callbackSeverity,
  isTerminal,
  nextStatuses,
  percentText,
  platformCallbackLabel,
  shipmentRecordStatusLabel,
  slaVerdict,
  summaryTiles,
  trackingRateSeverity,
  trackingSeverity,
  trackingStatusLabel,
} from './shipment-records-model';
import type { ShipmentRecordSummary } from '../../../../../packages/api-contracts/src/index';

function summary(over: Partial<ShipmentRecordSummary> = {}): ShipmentRecordSummary {
  return {
    totalRecords: 3,
    shippedRecords: 2,
    inTransitRecords: 1,
    deliveredRecords: 0,
    returnedRecords: 0,
    voidRecords: 0,
    totalParcels: 5,
    unsetTrackingRecords: 3,
    exceptionTrackingRecords: 0,
    unsetCallbackRecords: 2,
    rejectedCallbackRecords: 0,
    skippedCallbackRecords: 1,
    unsetVisibilityRecords: 3,
    noSlaRecords: 1,
    ...over,
  };
}

describe('9.4.1.2 发货记录 · 呈现口径', () => {
  it('状态标签覆盖全部枚举值且未知值原样返回', () => {
    expect(shipmentRecordStatusLabel('SHIPPED')).toBe('已发货');
    expect(shipmentRecordStatusLabel('IN_TRANSIT')).toBe('在途');
    expect(shipmentRecordStatusLabel('DELIVERED')).toBe('已妥投');
    expect(shipmentRecordStatusLabel('RETURNED')).toBe('已退回');
    expect(shipmentRecordStatusLabel('VOID')).toBe('已作废');
    expect(shipmentRecordStatusLabel(undefined)).toBe('未知');
    expect(shipmentRecordStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('UNSET 与 NO_TRACE 在标签与配色上都是两件事', () => {
    expect(trackingStatusLabel('UNSET')).toBe('未登记轨迹');
    expect(trackingStatusLabel('NO_TRACE')).toBe('承运商无轨迹');
    expect(trackingStatusLabel(undefined)).toBe('未登记轨迹');
    // 未登记是中性灰，不是「正常」绿 —— 它不是好消息，是「还没有数据源」
    expect(trackingSeverity('UNSET')).toBe('muted');
    expect(trackingSeverity(undefined)).toBe('muted');
    // 登记过但承运商说没有 = 提醒色，且与 UNSET 不同
    expect(trackingSeverity('NO_TRACE')).toBe('warn');
    expect(trackingSeverity('EXCEPTION')).toBe('danger');
    expect(trackingSeverity('RETURNED')).toBe('danger');
    expect(trackingSeverity('IN_TRANSIT')).toBe('normal');
  });

  it('未登记不冒充正常：UNSET 永远不是 normal 绿', () => {
    for (const status of ['UNSET', 'NO_TRACE', 'EXCEPTION']) {
      expect(trackingSeverity(status)).not.toBe('normal');
    }
  });

  it('平台回传的跳过与漏了是两个桶且配色不同', () => {
    expect(platformCallbackLabel('UNSET')).toBe('未登记');
    expect(platformCallbackLabel('SKIPPED')).toBe('决定不回传');
    // 「漏了」是提醒色，「有意跳过」是中性 —— 合并就再也分不出这两种情况
    expect(callbackSeverity('UNSET')).toBe('warn');
    expect(callbackSeverity('SKIPPED')).toBe('muted');
    expect(callbackSeverity('UNSET')).not.toBe(callbackSeverity('SKIPPED'));
    expect(callbackSeverity('REJECTED')).toBe('danger');
    expect(callbackSeverity('ACKNOWLEDGED')).toBe('normal');
    expect(platformCallbackLabel(undefined)).toBe('未登记');
  });

  it('callbackGapNote 对漏了与跳过给出不同措辞', () => {
    const missed = callbackGapNote('UNSET', undefined);
    expect(missed).toContain('漏了');
    expect(missed).toContain('9.4.2.1');

    const skipped = callbackGapNote('SKIPPED', '店铺已关闭订单');
    expect(skipped).toContain('决定不回传');
    expect(skipped).toContain('店铺已关闭订单');

    // 已回传 / 待回传不需要额外措辞
    expect(callbackGapNote('ACKNOWLEDGED', undefined)).toBeNull();
    expect(callbackGapNote('SUBMITTED', undefined)).toBeNull();
  });

  it('买家可见未登记不被画成「买家不可见」', () => {
    expect(buyerVisibleLabel('UNSET')).toBe('未登记');
    expect(buyerVisibleLabel('VISIBLE')).toBe('买家可见');
    expect(buyerVisibleLabel('HIDDEN')).toBe('对买家隐藏');
    expect(buyerVisibleLabel('PUBLISHED')).toBe('已向买家发布');
    expect(buyerVisibleLabel(undefined)).toBe('未登记');
  });

  it('没登记时限时不给「准时」标签', () => {
    expect(slaVerdict(undefined, false)).toEqual({ label: '未登记时限 · 无从判定', severity: 'muted' });
    // 关键：有时限且未迟发才算准时；没时限永远不是准时
    expect(slaVerdict(undefined, true).label).toContain('无从判定');
    expect(slaVerdict('2030-01-01T00:00:00+08:00', false)).toEqual({ label: '准时', severity: 'normal' });
    expect(slaVerdict('2030-01-01T00:00:00+08:00', true)).toEqual({ label: '迟发', severity: 'danger' });
  });

  it('绩效比例如实呈现两位小数，分母为 0 时不显示占位符', () => {
    expect(percentText(0.5)).toBe('50.00%');
    expect(percentText(0.3333)).toBe('33.33%');
    expect(percentText(0)).toBe('0.00%');
    expect(percentText(undefined)).toBe('—');
  });

  it('有效追踪率的盯盘阈值', () => {
    expect(trackingRateSeverity(0.3)).toBe('danger');
    expect(trackingRateSeverity(0.7)).toBe('warn');
    expect(trackingRateSeverity(0.95)).toBe('normal');
    expect(trackingRateSeverity(undefined)).toBe('normal');
  });

  it('状态推进只给合法的下一步，终态没有下一步', () => {
    expect(nextStatuses('SHIPPED')).toEqual(['IN_TRANSIT', 'RETURNED', 'VOID']);
    expect(nextStatuses('IN_TRANSIT')).toEqual(['DELIVERED', 'RETURNED', 'VOID']);
    expect(nextStatuses('DELIVERED')).toEqual([]);
    expect(nextStatuses('RETURNED')).toEqual([]);
    expect(nextStatuses('VOID')).toEqual([]);
    expect(nextStatuses(undefined)).toEqual([]);
  });

  it('终态识别', () => {
    expect(isTerminal('DELIVERED')).toBe(true);
    expect(isTerminal('RETURNED')).toBe(true);
    expect(isTerminal('VOID')).toBe(true);
    expect(isTerminal('SHIPPED')).toBe(false);
    expect(isTerminal('IN_TRANSIT')).toBe(false);
    expect(isTerminal(undefined)).toBe(false);
  });
});

describe('9.4.1.2 发货记录 · 汇总卡', () => {
  it('三个「未登记」各成一卡，不并成一个「正常」', () => {
    const tiles = summaryTiles(summary());
    const labels = tiles.map((t) => t.label);
    expect(labels).toContain('未登记轨迹');
    expect(labels).toContain('未登记回传');
    expect(labels).toContain('未登记买家可见');
    expect(labels).toContain('决定不回传');
    expect(labels).toContain('未登记发货时限');
    // 没有一张卡叫「正常」，也没有把三列并成一张
    expect(labels).not.toContain('正常');
    expect(new Set(labels).size).toBe(labels.length);
  });

  it('每张卡都带出处说明（为什么是提醒色）', () => {
    for (const tile of summaryTiles(summary())) {
      expect(tile.hint.length).toBeGreaterThan(0);
      expect(tile.value).not.toBe('');
    }
  });

  it('计数为零时是 normal，不是一片警告色', () => {
    const tiles = summaryTiles(
      summary({
        unsetTrackingRecords: 0,
        unsetCallbackRecords: 0,
        unsetVisibilityRecords: 0,
        noSlaRecords: 0,
        exceptionTrackingRecords: 0,
        rejectedCallbackRecords: 0,
      }),
    );
    const byLabel = new Map(tiles.map((t) => [t.label, t.severity]));
    expect(byLabel.get('未登记轨迹')).toBe('normal');
    expect(byLabel.get('未登记回传')).toBe('normal');
    expect(byLabel.get('轨迹异常')).toBe('normal');
  });

  it('有异常与有漏登记时分别升级为 danger 与 warn', () => {
    const tiles = summaryTiles(
      summary({ exceptionTrackingRecords: 2, rejectedCallbackRecords: 1, unsetCallbackRecords: 4 }),
    );
    const byLabel = new Map(tiles.map((t) => [t.label, t.severity]));
    expect(byLabel.get('轨迹异常')).toBe('danger');
    expect(byLabel.get('平台拒绝')).toBe('danger');
    expect(byLabel.get('未登记回传')).toBe('warn');
  });
});
