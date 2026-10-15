import { describe, expect, it } from 'vitest';
import type {
  WaybillBatchResult,
  WaybillCapabilities,
  WaybillCarrierRow,
  WaybillQueueRow,
  WaybillRequestRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  batchSummary,
  canRetry,
  declarationCountText,
  declarationWarning,
  declaredValueText,
  noWaybillHint,
  queuePositionText,
  requestStatusLabel,
  retryBlockedReason,
  statusSeverity,
  timeText,
  unavailableDimensions,
} from './waybill-model';

/**
 * 10.2.1.2 运单申请前端模型的纯函数单测。
 *
 * <p>这些断言守的是**界面上最容易做错**的几处：把「还没有运单号」画成空白、
 * 把「已入队」画成「提交中」、给不可重试的失败一个必然失败的按钮、
 * 把批量里的「跳过」与「失败」合成一个红数字。
 */

function request(overrides: Partial<WaybillRequestRow> = {}): WaybillRequestRow {
  return {
    id: 'r1',
    requestNo: 'WAR-20261015-000001',
    idempotencyKey: 'IDEM-1',
    pendingUnitId: 'u1',
    pendingParcelId: 'p1',
    outboundOrderNo: 'OB-1',
    salesOrderNo: 'SO-1',
    warehouseRef: 'US-WEST',
    carrierCode: 'DHL',
    carrierChannel: 'THIRD_PARTY',
    carrierChannelLabel: '第三方物流',
    shipFromName: '深圳发货仓',
    shipFromCountry: 'CN',
    shipToName: 'Jane Buyer',
    shipToCountry: 'US',
    shipToAddressLine: '1 Market St',
    shipToCity: 'San Francisco',
    shipToPostalCode: '94105',
    grossWeightKg: 1.5,
    lengthMm: 300,
    widthMm: 200,
    heightMm: 100,
    declaredCurrency: 'USD',
    declaredValueTotal: 20,
    declaredItemCount: 1,
    customsSource: '3.7.1.1',
    labelTemplateRef: '',
    status: 'QUEUED',
    statusLabel: '已入队',
    waybillNo: '',
    labelFormat: '',
    labelUri: '',
    failureCode: '',
    failureReason: '',
    cancelReason: '',
    attemptCount: 0,
    retryCount: 0,
    notBefore: '',
    issuedAt: '',
    createdAt: '2026-10-15T08:00:00Z',
    nextAction: '',
    ...overrides,
  };
}

describe('状态标签与分色', () => {
  it('已知状态给中文标签，未知状态回落到枚举名而不是静默吞掉', () => {
    expect(requestStatusLabel(request({ status: 'ISSUED' }))).toBe('已出号');
    expect(requestStatusLabel(request({ status: 'WAT' as never }))).toBe('WAT');
  });

  it('作废与失败是两种不同的红：一个是正常的，一个是待处理的', () => {
    expect(statusSeverity(request({ status: 'CANCELLED' }))).toBe('muted');
    expect(statusSeverity(request({ status: 'FAILED' }))).toBe('danger');
    expect(statusSeverity(request({ status: 'ISSUED' }))).toBe('ok');
    expect(statusSeverity(request({ status: 'QUEUED' }))).toBe('warn');
  });
});

describe('「没有运单号」必须说清是哪一种没有', () => {
  it('已出号时显示运单号', () => {
    const row = request({ status: 'ISSUED', waybillNo: 'WB123' });
    expect(noWaybillHint(row)).toBe('运单号 WB123');
  });

  it('已入队时明确说「还没向承运商提交」', () => {
    // 关键：不能让运营以为已经在提交了 —— 那会导致重复点提交
    expect(noWaybillHint(request({ status: 'QUEUED' }))).toContain('还没向承运商提交');
  });

  it('失败时显示承运商/渠道给出的原因，而不是一句「暂无」', () => {
    const row = request({
      status: 'FAILED',
      failureCode: 'ADAPTER_MISSING',
      failureReason: '承运商 DHL 尚未接通：10.1 物流渠道全部未交付',
    });
    expect(noWaybillHint(row)).toContain('10.1 物流渠道全部未交付');
  });

  it('已作废时说明不产生运单号', () => {
    expect(noWaybillHint(request({ status: 'CANCELLED' }))).toBe('已作废，不产生运单号');
  });
});

describe('重试入口只在有意义的场景出现', () => {
  it('限流与承运商不可达可重试', () => {
    expect(canRetry(request({ status: 'FAILED', failureCode: 'RATE_LIMITED' }))).toBe(true);
    expect(canRetry(request({ status: 'FAILED', failureCode: 'CARRIER_UNAVAILABLE' }))).toBe(true);
  });

  it('承运商拒收不可重试：原样重发只会再被拒一次并多烧一次配额', () => {
    const row = request({ status: 'FAILED', failureCode: 'CARRIER_REJECTED' });
    expect(canRetry(row)).toBe(false);
    expect(retryBlockedReason(row)).toContain('改地址或改包裹尺寸');
  });

  it('渠道未接通时重试按钮禁用，并指向 ownerModule', () => {
    const row = request({ status: 'FAILED', failureCode: 'ADAPTER_MISSING' });
    expect(canRetry(row)).toBe(false);
    expect(retryBlockedReason(row)).toContain('10.1');
  });

  it('已出号与已作废都不能重试', () => {
    expect(canRetry(request({ status: 'ISSUED', waybillNo: 'WB1' }))).toBe(false);
    expect(canRetry(request({ status: 'CANCELLED' }))).toBe(false);
  });

  it('还没失败时给的重试说明是「当前状态不能重试」而不是空串', () => {
    expect(retryBlockedReason(request({ status: 'CANCELLED' }))).not.toBe('');
  });
});

describe('队列位次是可见事实', () => {
  const entry = (overrides: Partial<WaybillQueueRow> = {}): WaybillQueueRow => ({
    id: 'q1',
    requestId: 'r1',
    requestNo: 'WAR-1',
    carrierCode: 'DHL',
    status: 'WAITING',
    statusLabel: 'WAITING',
    scheduledAt: '2026-10-15T09:00:00Z',
    dispatchedAt: '',
    attemptCount: 0,
    rescheduleCount: 0,
    lastFailureReason: '',
    positionHint: 3,
    ...overrides,
  });

  it('等待中的条目显示「第几位 + 预计何时放行」', () => {
    const text = queuePositionText(entry());
    expect(text).toContain('第 3 位');
    expect(text).toContain('放行');
  });

  it('终态条目显示终态而不是「第 0 位」', () => {
    expect(queuePositionText(entry({ status: 'DONE', positionHint: 1 }))).toBe('已完成');
    expect(queuePositionText(entry({ status: 'FAILED', positionHint: 1 }))).toBe('已失败');
    expect(queuePositionText(entry({ status: 'DISPATCHING', positionHint: 1 }))).toBe('正在提交');
  });
});

describe('批量结果：跳过与失败分色', () => {
  const result = (submitted: number, skipped: number, failed: number): WaybillBatchResult =>
    ({
      carrierCode: 'DHL',
      submitted,
      skipped,
      failed,
      entries: [],
      notes: [],
    }) as WaybillBatchResult;

  it('全成功是 ok', () => {
    expect(batchSummary(result(3, 0, 0)).tone).toBe('ok');
  });

  it('有跳过是 warn：重复提交不是问题，但现场要知道有票没新建', () => {
    const summary = batchSummary(result(2, 1, 0));
    expect(summary.tone).toBe('warn');
    expect(summary.skippedText).toContain('幂等命中');
  });

  it('有失败是 danger，且文案说清「需要处理」', () => {
    const summary = batchSummary(result(1, 1, 1));
    expect(summary.tone).toBe('danger');
    expect(summary.failedText).toContain('需要处理');
  });
});

describe('未接入维度逐条点名 ownerModule', () => {
  it('available=false 的维度全部带 ownerModule 与影响，不静默漏掉', () => {
    const capabilities = {
      dimensions: [
        { dimension: 'PENDING_PARCEL', description: '待发货包裹', available: true, source: 'x', ownerModule: null, impact: '' },
        { dimension: 'CARRIER_CONNECTION', description: '承运商连接', available: false, source: 'x', ownerModule: '10.1.1.1', impact: '未接通时如实失败' },
        { dimension: 'RATE_LIMIT_CONFIG', description: '限流配额', available: false, source: 'x', ownerModule: '10.1.1.3', impact: '用登记值兜底' },
      ],
      requestStatuses: [],
      queueStatuses: [],
      attemptOutcomes: [],
      carrierChannels: [],
      rateLimitNote: '',
      idempotencyNote: '',
      boundaryNote: '',
    } as WaybillCapabilities;

    const gaps = unavailableDimensions(capabilities);
    expect(gaps).toHaveLength(2);
    expect(gaps.map((g) => g.ownerModule)).toEqual(['10.1.1.1', '10.1.1.3']);
    expect(gaps.every((g) => g.impact.length > 0)).toBe(true);
  });

  it('ownerModule 缺失时显示「未标注」而不是空白格子', () => {
    const capabilities = {
      dimensions: [{ dimension: 'X', description: '某维度', available: false, source: 'x', ownerModule: null, impact: 'y' }],
    } as unknown as WaybillCapabilities;
    const gaps = unavailableDimensions(capabilities);
    expect(gaps).toHaveLength(1);
    // noUncheckedIndexedAccess：索引结果可能 undefined，所以先断言长度再取值
    expect(gaps[0]?.ownerModule).toBe('未标注');
  });
});

describe('格式化：空值与金额口径', () => {
  it('时间空值显示「—」而不是空串（空串在表格里看不出含义）', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText(undefined)).toBe('—');
    expect(timeText('')).toBe('—');
    expect(timeText('not-a-date')).toBe('not-a-date');
    expect(timeText('2026-10-15T08:00:00Z')).not.toBe('—');
  });

  it('申报额必须带币种：裸数字在多币种场景里没有意义', () => {
    expect(declaredValueText(request())).toBe('20 USD');
  });

  it('申报明细 0 条显示成「0 条」而不是「—」—— 空明细是要拦的', () => {
    expect(declarationCountText(request({ declaredItemCount: 0 }))).toBe('0 条');
  });

  it('申报明细为空时给出可执行的警告而不是静默', () => {
    expect(declarationWarning(request({ declaredItemCount: 0 }))).toContain('海关都会拒收');
    expect(declarationWarning(request({ declaredItemCount: 1 }))).toBe('');
  });
});

describe('承运商行', () => {
  const carrier = (overrides: Partial<WaybillCarrierRow> = {}): WaybillCarrierRow => ({
    id: 'c1',
    code: 'DHL',
    displayName: 'DHL 承运商',
    carrierChannel: 'THIRD_PARTY',
    carrierChannelLabel: '第三方物流',
    adapterKey: 'dhl',
    connected: false,
    unavailableReason: '10.1 未交付',
    rateLimitPerWindow: 20,
    rateWindowSeconds: 60,
    maxRetryAttempts: 3,
    remark: '',
    adapterAvailable: false,
    submitBlockReason: '10.1 物流渠道未交付',
    ...overrides,
  });

  it('未通通的承运商带可执行的阻断原因', () => {
    expect(carrier().submitBlockReason).toContain('10.1');
  });

  it('登记为接通但没有 vendor 适配器时也要说清（两道口径都要）', () => {
    // 登记口径（connected）与运行时口径（adapterAvailable）是**两件事**：
    // 只看登记会把「登记过」显示成「能下单」。
    const row = carrier({ connected: true, adapterAvailable: false, submitBlockReason: '没有 vendor 适配器' });
    expect(row.connected).toBe(true);
    expect(row.adapterAvailable).toBe(false);
    expect(row.submitBlockReason).not.toBe('');
  });
});
