import { describe, expect, it } from 'vitest';
import type {
  WaybillDisplayStatusOption,
  WaybillListRow,
  WaybillListSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  bulkSummaryLine,
  displayStatusTone,
  freightCell,
  issuedDisclaimer,
  printable,
  summaryLine,
  timeText,
  trackingGapWarning,
  unavailableStatuses,
  weightText,
} from './waybill-listing-model';

/**
 * 10.2.1.1 运单列表的**纯函数**单测。
 *
 * <p>这些派生函数决定界面上「这句话会不会被读错」，所以逐条钉住，重点是三个容易
 * 做错的判断：已取号 ≠ 在途中、未登记 ≠ 0 运费、批量里「跳过」与「失败」要分开。
 */

const UUID = '11111111-1111-1111-1111-111111111111';

function row(overrides: Partial<WaybillListRow> = {}): WaybillListRow {
  return {
    id: UUID,
    requestNo: 'WAR-0001',
    waybillNo: '',
    salesOrderNo: 'SO-1',
    outboundOrderNo: 'OB-1',
    pendingParcelId: UUID,
    warehouseRef: 'US-WEST',
    carrierCode: 'DHL',
    carrierChannel: 'THIRD_PARTY',
    carrierChannelLabel: '三方物流',
    destinationCountry: 'US',
    displayStatus: 'PENDING_NUMBER',
    displayStatusLabel: '待取号',
    statusSource: 'INTERNAL_REQUEST',
    statusNote: '',
    upstreamStatus: 'QUEUED',
    upstreamStatusLabel: '已入队',
    grossWeightKg: 2.5,
    freightAmount: 0,
    freightCurrency: '',
    freightGapCode: 'NOT_REGISTERED',
    freightGapLabel: '未登记',
    freightSource: '',
    labelAvailability: 'NO_REFERENCE',
    labelAvailabilityLabel: '未登记面单凭据',
    labelFormat: '',
    labelUri: '',
    createdAt: '2026-10-15T02:00:00Z',
    issuedAt: '',
    notBefore: '',
    nextAction: '',
    ...overrides,
  } as WaybillListRow;
}

function summary(overrides: Partial<WaybillListSummary> = {}): WaybillListSummary {
  return {
    total: 0,
    pendingNumber: 0,
    numberIssued: 0,
    applicationFailed: 0,
    cancelled: 0,
    issuedWithoutCarrierTracking: 0,
    withoutWaybillNo: 0,
    freightRegistered: 0,
    freightNotRegistered: 0,
    freightByCurrency: [],
    printableCount: 0,
    withoutLabelReference: 0,
    distinctCarriers: 0,
    distinctDestinationCountries: 0,
    notes: [],
    ...overrides,
  } as WaybillListSummary;
}

describe('displayStatusTone', () => {
  it('四个可兑现的状态各有各的色调', () => {
    expect(displayStatusTone(row({ displayStatus: 'PENDING_NUMBER' }))).toBe('wait');
    expect(displayStatusTone(row({ displayStatus: 'NUMBER_ISSUED' }))).toBe('ok');
    expect(displayStatusTone(row({ displayStatus: 'APPLICATION_FAILED' }))).toBe('bad');
    expect(displayStatusTone(row({ displayStatus: 'CANCELLED' }))).toBe('muted');
  });

  it('「申请失败」与「已取消」不是同一个色（一个要处理，一个正常）', () => {
    expect(displayStatusTone(row({ displayStatus: 'APPLICATION_FAILED' }))).not.toBe(
      displayStatusTone(row({ displayStatus: 'CANCELLED' })),
    );
  });

  it('10.3 未交付的四个态若真出现，必须与「正常」区分开', () => {
    for (const s of ['PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'EXCEPTION'] as const) {
      expect(displayStatusTone(row({ displayStatus: s }))).toBe('warn');
    }
  });
});

describe('issuedDisclaimer', () => {
  it('已取号必须附上「不代表在途中」', () => {
    const text = issuedDisclaimer(row({ displayStatus: 'NUMBER_ISSUED' }));
    expect(text).toContain('已取号');
    expect(text).toContain('在途');
    expect(text).toContain('10.3');
  });

  it('其它状态不显示这句（否则每行都在喊同一个警告）', () => {
    expect(issuedDisclaimer(row({ displayStatus: 'PENDING_NUMBER' }))).toBe('');
  });
});

describe('freightCell', () => {
  it('未登记显示「未登记」而不是 0.00', () => {
    const cell = freightCell(row({ freightGapCode: 'NOT_REGISTERED', freightAmount: 0 }));
    expect(cell.text).toBe('未登记');
    expect(cell.text).not.toContain('0.00');
  });

  it('登记为 0 也要显示成 0.00 + 币种（合法零 ≠ 没登记）', () => {
    const cell = freightCell(
      row({ freightGapCode: 'REGISTERED', freightAmount: 0, freightCurrency: 'USD' }),
    );
    expect(cell.text).toBe('0.00 USD');
  });

  it('正常金额带币种', () => {
    const cell = freightCell(
      row({ freightGapCode: 'REGISTERED', freightAmount: 23.75, freightCurrency: 'USD' }),
    );
    expect(cell.text).toBe('23.75 USD');
  });
});

describe('printable', () => {
  it('有面单凭据才可打印', () => {
    expect(printable(row({ labelAvailability: 'RECORDED_REFERENCE' }))).toBe(true);
    expect(printable(row({ labelAvailability: 'NO_REFERENCE' }))).toBe(false);
  });
});

describe('unavailableStatuses', () => {
  const statuses: WaybillDisplayStatusOption[] = [
    { status: 'PENDING_NUMBER', label: '待取号', available: true, source: 'x', ownerModule: '', note: '' },
    { status: 'PICKED_UP', label: '已揽收', available: false, source: 'y', ownerModule: '10.3.1.1', note: 'n' },
    { status: 'IN_TRANSIT', label: '运输中', available: false, source: 'y', ownerModule: '10.3.1.1', note: 'n' },
  ];

  it('只挑出 available=false 的（界面上要禁用它们）', () => {
    expect(unavailableStatuses(statuses).map((s) => s.status)).toEqual([
      'PICKED_UP',
      'IN_TRANSIT',
    ]);
  });

  it('每个不可用态都带 ownerModule（禁用时要说清找谁）', () => {
    for (const s of unavailableStatuses(statuses)) expect(s.ownerModule).toBeTruthy();
  });
});

describe('bulkSummaryLine', () => {
  const entry = (applied: boolean, code: string) => ({
    requestId: UUID,
    requestNo: 'R',
    waybillNo: '',
    action: 'APPLY',
    applied,
    resultCode: code,
    resultMessage: '',
    currentStatus: '',
    currentStatusLabel: '',
  });

  it('成功/跳过/失败三个数字分开说', () => {
    const line = bulkSummaryLine({
      action: 'APPLY',
      selected: 3,
      applied: 1,
      skipped: 1,
      failed: 1,
      batchNo: '',
      entries: [entry(true, 'REQUEUED'), entry(false, 'NOT_APPLICABLE'), entry(false, 'FAILED')],
      notes: [],
    });
    expect(line).toContain('共 3 张');
    expect(line).toContain('成功 1');
    expect(line).toContain('跳过 1');
    expect(line).toContain('失败 1');
  });
});

describe('summaryLine 与 trackingGapWarning', () => {
  it('汇总行强调「筛选结果」而不是「本页」', () => {
    const line = summaryLine(summary({ total: 380, numberIssued: 12 }));
    expect(line).toContain('380');
    expect(line).toContain('筛选结果');
  });

  it('已取号但无轨迹时必须给出告警', () => {
    expect(trackingGapWarning(summary({ issuedWithoutCarrierTracking: 12 }))).toContain('≠');
  });

  it('没有已取号的票时不喊', () => {
    expect(trackingGapWarning(summary({ issuedWithoutCarrierTracking: 0 }))).toBe('');
  });
});

describe('timeText / weightText', () => {
  it('空值显示「—」而不是 1970', () => {
    expect(timeText('')).toBe('—');
    expect(timeText(null)).toBe('—');
  });

  it('非法时间原样返回而不是 Invalid Date', () => {
    expect(timeText('not-a-date')).toBe('not-a-date');
  });

  it('重量保留 3 位并带单位', () => {
    expect(weightText(row({ grossWeightKg: 2.5 }))).toBe('2.500 kg');
  });
});
