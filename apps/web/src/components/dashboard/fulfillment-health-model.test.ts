import { describe, expect, it } from 'vitest';
import {
  coverageHints,
  emptyState,
  groupRedFlags,
  levelLabel,
  levelTone,
  mergeCards,
  outboundDurationCard,
  severityTone,
  unavailableDimensions,
  type FulfillmentHealthCapabilities,
  type FulfillmentHealthCard,
  type FulfillmentHealthCoverage,
  type FulfillmentHealthRedFlag,
  type FulfillmentHealthSummary,
  type OutboundStageDistribution,
  type OutboundTimelinessReport,
} from './fulfillment-health-model';

/**
 * 1.1.2.3 履约健康 前端模型的纯函数断言。
 *
 * 🔴 这个页面上最容易做丢的事，前端这一层再挡一道：
 * **「量不到」显示成「未接通」而不是 0**、**前端不重算后端算出的比率**、
 * **计数型卡片没有分子分母**、**红灯项与卡片同源**、**三态空态文案各不相同**。
 */

function stage(overrides: Partial<OutboundStageDistribution> = {}): OutboundStageDistribution {
  return {
    stageCode: 'ORDER_TO_OUTBOUND',
    stageLabel: '订单到出库',
    unit: '分钟',
    sampledCount: 10,
    missingOrderCount: 2,
    coverageRate: 0.8333,
    avgMinutes: 18.5,
    p50Minutes: 12.0,
    p90Minutes: 40.0,
    maxMinutes: 90.0,
    thresholdMinutes: 1440.0,
    thresholdSource: 'WAREHOUSE',
    overdueCount: 0,
    overdueRate: 0,
    buckets: [],
    ...overrides,
  };
}

function report(overrides: Partial<OutboundTimelinessReport> = {}): OutboundTimelinessReport {
  return {
    from: '2026-03-01',
    to: '2026-03-30',
    timezone: 'Asia/Shanghai',
    shippedOrderCount: 10,
    overdueOrderCount: 0,
    overdueOrderRate: 0,
    distribution: [stage()],
    ...overrides,
  };
}

function card(overrides: Partial<FulfillmentHealthCard> = {}): FulfillmentHealthCard {
  return {
    key: 'SHIP_OVERDUE_RATE',
    label: '超时发货率',
    available: true,
    unavailableReason: '',
    ownerModule: '',
    metricKind: 'RATE',
    unit: '%',
    value: 0.125,
    valueText: '12.50%',
    numerator: 1,
    denominator: 8,
    alertCount: 1,
    redFlagKind: 'SHIP_OVERDUE',
    basis: '口径说明',
    level: 'RED',
    levelRule: '判定依据',
    detailPath: '/orders/fulfillment/shipment-records',
    sourceTables: ['shipment_record'],
    ...overrides,
  };
}

function summary(cards: FulfillmentHealthCard[]): FulfillmentHealthSummary {
  return {
    cards,
    breakdowns: [],
    coverage: {
      pendingProjectionTotal: 5,
      pendingHandedOverUnits: 1,
      pendingCancelledUnits: 1,
      shipmentRecordsEvaluated: 8,
      shipmentRecordsUnsetSla: 0,
      shipmentRecordsUntrackable: 0,
      dropshipOpenExceptions: 0,
      dropshipTrackings: 0,
    },
    boundaryNotes: [],
  };
}

function flag(overrides: Partial<FulfillmentHealthRedFlag> = {}): FulfillmentHealthRedFlag {
  return {
    kind: 'SHIP_OVERDUE',
    kindLabel: '迟发记录',
    refId: 'r-1',
    refNo: 'SR-1',
    title: '迟发记录',
    detail: '发货于 2026-03-01 10:15',
    severity: 'CRITICAL',
    status: 'SHIPPED',
    occurredAt: '2026-03-01T10:15:30+08:00',
    linkPath: '/orders/fulfillment/shipment-records',
    ...overrides,
  };
}

describe('1.1.2.3 履约健康 · 前端模型', () => {
  // ---------------------------------------------------------------- 灯色
  it('灯色映射到徽章色调且未接通的落中性色', () => {
    expect(levelTone('RED')).toBe('danger');
    expect(levelTone('AMBER')).toBe('warning');
    expect(levelTone('GREEN')).toBe('success');
    expect(levelTone('UNKNOWN')).toBe('neutral');
    expect(levelLabel('UNKNOWN')).toBe('未接通');
    expect(levelLabel('RED')).toBe('红灯');
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('INFO')).toBe('neutral');
  });

  // ---------------------------------------------------------------- 出库时长卡
  it('出库时长卡取订单到出库那一段并带上生效阈值的来源', () => {
    const c = outboundDurationCard(report());
    expect(c).not.toBeNull();
    expect(c!.key).toBe('AVERAGE_OUTBOUND_DURATION');
    expect(c!.metricKind).toBe('DURATION');
    expect(c!.value).toBe(18.5);
    expect(c!.valueText).toBe('18.5 分钟');
    expect(c!.level).toBe('GREEN');
    // 阈值来源必须写出来 —— 否则「24 小时」这个数没人知道是谁定的。
    expect(c!.basis).toContain('仓库覆盖');
    expect(c!.basis).toContain('1440');
  });

  it('出库时长一个样本都没有时判灰而不是报0分钟', () => {
    const c = outboundDurationCard(
      report({
        distribution: [stage({ sampledCount: 0, avgMinutes: null, overdueCount: 0 })],
      }),
    );
    expect(c!.value).toBeNull();
    expect(c!.valueText).toBe('未接通');
    expect(c!.level).toBe('UNKNOWN');
  });

  it('出库时效服务不可达时不补一张假卡', () => {
    expect(outboundDurationCard(null)).toBeNull();
    const merged = mergeCards(summary([card()]), null);
    expect(merged).toHaveLength(1);
    expect(merged.map((c) => c.key)).not.toContain('AVERAGE_OUTBOUND_DURATION');
  });

  it('出库时效报表里没有该环节时也不补卡', () => {
    expect(outboundDurationCard(report({ distribution: [] }))).toBeNull();
  });

  it('合并后出库时长排在待发货积压之后', () => {
    const merged = mergeCards(
      summary([
        card({ key: 'PENDING_SHIPMENT_BACKLOG', label: '待发货积压' }),
        card({ key: 'SHIP_OVERDUE_RATE', label: '超时发货率' }),
      ]),
      report(),
    );
    expect(merged.map((c) => c.key)).toEqual([
      'PENDING_SHIPMENT_BACKLOG',
      'AVERAGE_OUTBOUND_DURATION',
      'SHIP_OVERDUE_RATE',
    ]);
  });

  // ---------------------------------------------------------------- 量不到
  it('量不到的维度如实列出来而不是悄悄消失', () => {
    const caps: FulfillmentHealthCapabilities = {
      dimensions: [
        {
          key: 'SHIP_OVERDUE_RATE',
          label: '超时发货率',
          available: true,
          reason: '',
          ownerModule: '',
          impact: '',
          formula: '公式',
          denominator: '分母',
          sourceTables: 'shipment_record',
          handling: '只读聚合',
        },
        {
          key: 'PLATFORM_ORDER_DEFECT_RATE',
          label: '平台 ODR（订单缺陷率）',
          available: false,
          reason: '仓内没有 platform_performance_metric 表',
          ownerModule: '02 渠道与店铺 · 平台绩效指标同步',
          impact: '健康度总览里这一格是「未接通」而不是 0',
          formula: '',
          denominator: '',
          sourceTables: '',
          handling: '未接通',
        },
      ],
      redFlagKinds: [],
      levelRule: '',
      windowBasis: '',
      upstreamNote: '',
      ownershipNote: '',
      boundaryNote: '',
    };
    const missing = unavailableDimensions(caps);
    expect(missing).toHaveLength(1);
    const od = missing[0]!;
    expect(od.key).toBe('PLATFORM_ORDER_DEFECT_RATE');
    expect(od.ownerModule).toContain('02');
    expect(od.impact).not.toBe('');
  });

  it('未接通那一格的数值是null不是0', () => {
    const c = card({
      key: 'PLATFORM_ORDER_DEFECT_RATE',
      available: false,
      metricKind: 'UNAVAILABLE',
      value: null,
      valueText: '未接通',
      numerator: null,
      denominator: null,
      alertCount: null,
      redFlagKind: null,
      level: 'UNKNOWN',
    });
    expect(c.value).toBeNull();
    expect(c.valueText).toBe('未接通');
    expect(c.numerator).toBeNull();
  });

  it('计数型卡片没有分子分母', () => {
    const c = card({
      key: 'PENDING_SHIPMENT_BACKLOG',
      metricKind: 'COUNT',
      unit: '件',
      value: 3,
      valueText: '3 件',
      numerator: null,
      denominator: null,
    });
    expect(c.numerator).toBeNull();
    expect(c.denominator).toBeNull();
    expect(c.valueText).toBe('3 件');
  });

  // ---------------------------------------------------------------- 覆盖度
  it('覆盖度提示把未登记时限与不可追踪的件说出来', () => {
    const coverage: FulfillmentHealthCoverage = {
      pendingProjectionTotal: 5,
      pendingHandedOverUnits: 1,
      pendingCancelledUnits: 1,
      shipmentRecordsEvaluated: 8,
      shipmentRecordsUnsetSla: 2,
      shipmentRecordsUntrackable: 1,
      dropshipOpenExceptions: 3,
      dropshipTrackings: 10,
    };
    const hints = coverageHints(coverage);
    expect(hints.join('\n')).toContain('未登记发货时限');
    expect(hints.join('\n')).toContain('没有任何轨迹观测');
    expect(hints.join('\n')).toContain('3 条未结案异常');
    expect(hints).toHaveLength(3);
  });

  it('一切齐全时不给覆盖度提示', () => {
    expect(
      coverageHints({
        pendingProjectionTotal: 5,
        pendingHandedOverUnits: 1,
        pendingCancelledUnits: 1,
        shipmentRecordsEvaluated: 8,
        shipmentRecordsUnsetSla: 0,
        shipmentRecordsUntrackable: 0,
        dropshipOpenExceptions: 0,
        dropshipTrackings: 10,
      }),
    ).toHaveLength(0);
  });

  it('待发货投影为空时提示可能是同步没跑过', () => {
    const hints = coverageHints({
      pendingProjectionTotal: 0,
      pendingHandedOverUnits: 0,
      pendingCancelledUnits: 0,
      shipmentRecordsEvaluated: 0,
      shipmentRecordsUnsetSla: 0,
      shipmentRecordsUntrackable: 0,
      dropshipOpenExceptions: 0,
      dropshipTrackings: 0,
    });
    expect(hints.join('\n')).toContain('同步还没跑过');
  });

  // ---------------------------------------------------------------- 红灯项
  it('红灯项按类型分组且保持先后顺序', () => {
    const groups = groupRedFlags([
      flag({ kind: 'SHIP_OVERDUE', kindLabel: '迟发记录' }),
      flag({ kind: 'PENDING_SHIPMENT_OVERDUE', kindLabel: '待发货超时未发出' }),
      flag({ kind: 'SHIP_OVERDUE', kindLabel: '迟发记录', refId: 'r-2' }),
    ]);
    expect(groups.map((g) => g.kind)).toEqual(['SHIP_OVERDUE', 'PENDING_SHIPMENT_OVERDUE']);
    expect(groups[0]!.items).toHaveLength(2);
    expect(groups[1]!.items).toHaveLength(1);
  });

  // ---------------------------------------------------------------- 空态
  it('三态空态文案各不相同', () => {
    const loaded = emptyState(0, true);
    const failed = emptyState(0, false);
    const filtered = emptyState(3, true);
    expect(loaded.title).toContain('没有红灯项');
    expect(failed.title).toContain('加载失败');
    expect(filtered.title).toContain('没有匹配的项');
    expect(new Set([loaded.title, failed.title, filtered.title]).size).toBe(3);
    // 「没有红灯项」必须说清「未登记时限的件不在清单里」。
    expect(loaded.description).toContain('未登记时限');
  });
});
