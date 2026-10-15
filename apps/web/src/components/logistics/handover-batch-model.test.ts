import { describe, expect, it } from 'vitest';
import type {
  HandoverBatchRow,
  HandoverCapabilities,
  HandoverCandidateRow,
  ManifestItemInput,
} from './handover-batch-model';
import {
  batchStatusLabel,
  batchStatusTone,
  canCancel,
  canDispatch,
  canRecordReceipt,
  carrierChannelLabel,
  dimensionText,
  manifestInputFromCandidate,
  receiptNeedsDetail,
  receiptStatusLabel,
  unavailableDimensions,
  validateBatch,
  waybillGapLabel,
  waybillText,
  weightText,
} from './handover-batch-model';

function candidate(over: Partial<HandoverCandidateRow> = {}): HandoverCandidateRow {
  return {
    id: 'c1',
    sourceUnitId: 'u1',
    sourcePackingOrderId: 'p1',
    warehouseRef: 'WH-SH-01',
    outboundOrderNo: 'OUT-1',
    salesOrderNo: 'SO-1',
    destinationCountry: 'US',
    carrierCode: 'DHL_EXPRESS',
    carrierChannel: 'THIRD_PARTY',
    waybillNo: 'WB-1',
    waybillGapReason: '',
    waybillGapCode: '',
    parcelCount: 2,
    totalWeightKg: 3,
    status: 'PENDING',
    batchId: null,
    packedAt: '2026-10-14T18:00:00Z',
    handedOverAt: '2026-10-15T07:00:00Z',
    syncedAt: '2026-10-15T08:00:00Z',
    ...over,
  };
}

function batch(over: Partial<HandoverBatchRow> = {}): HandoverBatchRow {
  return {
    id: 'b1',
    batchNo: 'HNB-20261015000001',
    carrierCode: 'DHL_EXPRESS',
    carrierChannel: 'THIRD_PARTY',
    handoverAt: '2026-10-15T09:30:00Z',
    handoverBucket: '2026-10-15',
    unitCount: 1,
    parcelCount: 2,
    totalWeightKg: 3,
    status: 'DRAFT',
    statusDetail: '',
    statusLabel: '已组批待交接',
    dispatchedByMembershipId: null,
    dispatchedAt: null,
    receiptRef: '',
    receiptStatus: '',
    receiptStatusLabel: '',
    receiptDetail: '',
    receiptRecordedAt: null,
    manifestCount: 2,
    version: 1,
    createdAt: '2026-10-15T09:00:00Z',
    ...over,
  };
}

describe('10.3.2.1 交运批次 · 运单号缺口必须如实显示，绝不拿别的号顶上', () => {
  it('有运单号时原样显示，不标缺口', () => {
    expect(waybillText('WB-1', '', '')).toEqual({ text: 'WB-1', missing: false });
  });

  it('无运单号时显示缺口原因而不是空白（空白与「确实没有单号」无法区分）', () => {
    const r = waybillText(
      '',
      'UPSTREAM_NOT_ISSUED',
      '上游尚未签发运单号（10.2.1.2 运单申请未交付）',
    );
    expect(r.missing).toBe(true);
    expect(r.text).toContain('上游尚未签发运单号');
    expect(r.text).not.toBe('');
  });

  it('缺口原因缺失时也必须给出可读文案，不显示空白', () => {
    const r = waybillText('', '', '');
    expect(r.missing).toBe(true);
    expect(r.text).toBe('未说明');
  });

  it('运单号**绝不**用出库单号冒充：清单行的运单号不能等于出库单号', () => {
    const c = candidate({ waybillNo: '', waybillGapCode: 'UPSTREAM_NOT_ISSUED' });
    const input = manifestInputFromCandidate(c, 1, 1.5);
    expect(input.waybillNo).toBe('');
    expect(input.waybillNo).not.toBe(c.outboundOrderNo);
    expect(input.waybillGapCode).toBe('UPSTREAM_NOT_ISSUED');
    expect(input.waybillGapReason).not.toBe('');
  });

  it('候选自带缺口原因时优先用它，不覆盖成通用文案', () => {
    const c = candidate({
      waybillNo: '',
      waybillGapCode: 'UPSTREAM_UNREADABLE',
      waybillGapReason: '上游运单通道 503',
    });
    const input = manifestInputFromCandidate(c, 1, 1);
    expect(input.waybillGapReason).toBe('上游运单通道 503');
    expect(input.waybillGapCode).toBe('UPSTREAM_UNREADABLE');
  });

  it('有运单号时不带任何缺口原因（两件事互斥）', () => {
    const input = manifestInputFromCandidate(candidate(), 1, 1);
    expect(input.waybillGapCode).toBe('');
    expect(input.waybillGapReason).toBe('');
  });

  it('未知缺口码回落成原值，不静默吞掉', () => {
    expect(waybillGapLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(waybillGapLabel('')).toBe('未说明');
  });
});

describe('10.3.2.1 组批校验：逐条说清原因，不是一句「请检查输入」', () => {
  const one = manifestInputFromCandidate(candidate(), 1, 1.5);
  const two = manifestInputFromCandidate(candidate(), 2, 1.5);
  const good: ManifestItemInput[] = [one, two];

  it('合法输入没有错误', () => {
    expect(validateBatch('DHL_EXPRESS', '2026-10-15T09:30:00Z', good)).toEqual([]);
  });

  it('承运商为空被拒（一条批次不许跨承运商）', () => {
    expect(validateBatch('', '2026-10-15T09:30:00Z', good)).toContain(
      '承运商必填：一条交运批次不许跨承运商',
    );
  });

  it('交运时间为空被拒', () => {
    expect(validateBatch('DHL_EXPRESS', '', good)).toContain('交运时间必填');
  });

  it('清单为空被拒并说明后果（没有清单就没有留证）', () => {
    const problems = validateBatch('DHL_EXPRESS', '2026-10-15T09:30:00Z', []);
    expect(problems).toContain('交接清单不能为空：没有逐件清单就没有留证');
  });

  it('同一件包裹重复出现被逐条点名', () => {
    const dup: ManifestItemInput[] = [good[0]!, { ...good[0]! }];
    const problems = validateBatch('DHL_EXPRESS', '2026-10-15T09:30:00Z', dup);
    expect(problems.some((p) => p.includes('出现了两次'))).toBe(true);
  });

  it('运单号与缺口原因都空时点名那一件（而不是笼统报错）', () => {
    const bad: ManifestItemInput[] = [
      { ...good[0]!, waybillNo: '', waybillGapReason: '', waybillGapCode: '' },
    ];
    const problems = validateBatch('DHL_EXPRESS', '2026-10-15T09:30:00Z', bad);
    expect(problems.some((p) => p.includes('绝不出库单号冒充运单号'))).toBe(true);
  });

  it('负重量被逐条点名', () => {
    const bad: ManifestItemInput[] = [{ ...good[0]!, grossWeightKg: -1 }];
    expect(
      validateBatch('DHL_EXPRESS', '2026-10-15T09:30:00Z', bad).some((p) =>
        p.includes('重量不能为负'),
      ),
    ).toBe(true);
  });
});

describe('10.3.2.1 交接清单是凭据：可写动作受限', () => {
  it('清单为空时不允许交接（没有留证就没有追责依据）', () => {
    expect(canDispatch(batch({ manifestCount: 0 }))).toBe(false);
    expect(canDispatch(batch({ manifestCount: 2 }))).toBe(true);
  });

  it('只有 DRAFT 可登记交接', () => {
    expect(canDispatch(batch({ status: 'DISPATCH_PENDING' }))).toBe(false);
    expect(canDispatch(batch({ status: 'HANDED_OVER' }))).toBe(false);
    expect(canDispatch(batch({ status: 'CANCELLED' }))).toBe(false);
  });

  it('已登记交接或已交接都可带回回执', () => {
    expect(canRecordReceipt(batch({ status: 'DISPATCH_PENDING' }))).toBe(true);
    expect(canRecordReceipt(batch({ status: 'HANDED_OVER' }))).toBe(true);
    expect(canRecordReceipt(batch({ status: 'DRAFT' }))).toBe(false);
  });

  it('已交接是终态：不可作废（否则追责凭据会被自己抹掉）', () => {
    expect(canCancel(batch({ status: 'HANDED_OVER' }))).toBe(false);
    expect(canCancel(batch({ status: 'DISPATCH_PENDING' }))).toBe(true);
    expect(canCancel(batch({ status: 'DRAFT' }))).toBe(true);
  });

  it('有差异 / 拒收必须写明原因，否则无法发起理赔', () => {
    expect(receiptNeedsDetail('ACCEPTED')).toBe(false);
    expect(receiptNeedsDetail('ACCEPTED_WITH_DISCREPANCY')).toBe(true);
    expect(receiptNeedsDetail('REJECTED')).toBe(true);
  });
});

describe('10.3.2.1 展示词表：未知枚举不静默吞掉', () => {
  it('批次状态四档', () => {
    expect(batchStatusLabel('DRAFT')).toBe('已组批待交接');
    expect(batchStatusLabel('DISPATCH_PENDING')).toBe('已登记交接 · 待承运商接通');
    expect(batchStatusLabel('HANDED_OVER')).toBe('已交接 · 责任在承运商');
    expect(batchStatusLabel('CANCELLED')).toBe('已作废');
    expect(batchStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(batchStatusLabel('')).toBe('（空）');
  });

  it('已交接是成功色，待接通是警示色', () => {
    expect(batchStatusTone('HANDED_OVER')).toBe('success');
    expect(batchStatusTone('DISPATCH_PENDING')).toBe('warning');
    expect(batchStatusTone('DRAFT')).toBe('info');
  });

  it('承运渠道与回执状态', () => {
    expect(carrierChannelLabel('THIRD_PARTY')).toBe('第三方物流');
    expect(carrierChannelLabel('PLATFORM_INTEGRATED')).toBe('平台面单');
    expect(carrierChannelLabel('')).toBe('（未指定）');
    expect(receiptStatusLabel('REJECTED')).toBe('拒收');
    expect(receiptStatusLabel('')).toBe('');
  });

  it('重量与尺寸：未量到显示 —，不显示 0（0 会被读成「量出来是 0 公斤」）', () => {
    expect(weightText(3.5)).toBe('3.500 kg');
    expect(weightText(0)).toBe('0.000 kg');
    expect(weightText(Number.NaN)).toBe('—');
    expect(dimensionText(200, 150, 80)).toBe('200×150×80 mm');
    expect(dimensionText(0, 150, 80)).toBe('—');
  });
});

describe('10.3.2.1 未交付能力如实标出（不用空列表冒充「没有数据」）', () => {
  const caps: HandoverCapabilities = {
    dimensions: [
      {
        dimension: 'handover_manifest',
        description: '',
        available: true,
        source: '',
        ownerModule: '',
        impact: '',
      },
      {
        dimension: 'carrier_dispatch',
        description: '',
        available: false,
        source: '',
        ownerModule: '10.1.1.1',
        impact: '停在待接通',
      },
      {
        dimension: 'waybill_number',
        description: '',
        available: false,
        source: '',
        ownerModule: '10.2.1.2',
        impact: '可能无单号',
      },
    ],
    batchStatuses: [],
    carrierChannels: [],
    waybillGapCodes: [],
    receiptStatuses: [],
    manifestActions: [],
    carrierSourceNote: '',
    upstreamNote: '',
    boundaryNote: '',
  };

  it('只列 available=false 的维度', () => {
    const gaps = unavailableDimensions(caps);
    expect(gaps.map((d) => d.dimension)).toEqual(['carrier_dispatch', 'waybill_number']);
  });

  it('能力声明还没加载时返回空数组而不是崩', () => {
    expect(unavailableDimensions(null)).toEqual([]);
  });
});
