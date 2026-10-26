import { describe, expect, it } from 'vitest';

import type {
  CrmMessageRow,
  FinanceSupplierReconciliationRow,
  StockoutAlertRow,
  SyncFailureView,
  TimelinessOverdueRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  EMPTY_FILTERS,
  NOTIFICATION_TYPES,
  NOTIFICATION_TYPE_LABELS,
  applyReadState,
  filterBySubscription,
  filterNotifications,
  formatOccurredAt,
  formatSpan,
  mergeNotifications,
  messageSeverity,
  notificationKey,
  overdueSeverity,
  reconciliationSeverity,
  sortNotifications,
  summarizeNotifications,
  syncFailureSeverity,
  toMessageNotification,
  toOverdueNotification,
  toReconciliationNotification,
  toStockoutNotification,
  toSyncFailureNotification,
  unreadBadge,
  upstreamSeverity,
  type NotificationItem,
  type SourceResult,
} from './notification-inbox-model';

/**
 * 1.3.1.1 通知列表 —— 前端聚合模型的单测。
 *
 * <p>本模块最容易做错的几件事，这里逐条钉住：
 *
 * <ol>
 *   <li><b>「判不了」被当成「没有」</b>：上游没给严重度、没给时刻 ——
 *       这些都判不了，用 LOW / 「刚刚」兜底会让它们排到最后然后永远没人处理。</li>
 *   <li><b>已处置的事项仍被报成通知</b>：已解决的缺货预警、已重放的同步失败、
 *       没有未结差异的对账单、我们自己发出去的回信 ——
 *       报成通知就是在制造假通知。</li>
 *   <li><b>计数与清单不是同一个集合</b>：未读数必须能从清单里点出来。</li>
 *   <li><b>通知键对不上</b>：键是与 identity 之间唯一的接口，
 *       形状错了「标了已读」就落不到对应的行上。</li>
 * </ol>
 */

function stockoutRow(overrides: Partial<StockoutAlertRow> = {}): StockoutAlertRow {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    skuId: '22222222-2222-4222-8222-222222222222',
    skuCode: 'SKU-001',
    warehouseId: '33333333-3333-4333-8333-333333333333',
    warehouseCode: 'WH-01',
    warehouseName: '深圳仓',
    onHandQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: 0,
    networkOnHandQuantity: 0,
    networkReservedQuantity: 0,
    networkAvailableQuantity: 0,
    runId: null,
    runAt: null,
    runMethod: null,
    runAvailableQuantity: null,
    runAllocatableQuantity: null,
    runUnallocatedQuantity: null,
    runAllocatedTotal: null,
    demandUnits: 30,
    demandRegistered: true,
    demandWindowStart: null,
    demandWindowEnd: null,
    demandShopCount: 2,
    coverageRatio: 0,
    daysOfCover: 0,
    alertState: 'WAREHOUSE_STOCKED_OUT',
    severity: 'CRITICAL',
    demandState: 'KNOWN',
    note: '',
    recommendation: {} as StockoutAlertRow['recommendation'],
    lifecycleState: 'OPEN',
    acknowledgedByMembershipId: null,
    acknowledgedAt: null,
    resolvedAt: null,
    resolutionNote: null,
    recurrenceCount: 0,
    detectedAt: '2026-10-24T08:00:00.000Z',
    ...overrides,
  };
}

function syncFailureRow(overrides: Partial<SyncFailureView> = {}): SyncFailureView {
  return {
    id: '44444444-4444-4444-8444-444444444444',
    cursorId: '55555555-5555-4555-8555-555555555555',
    platformCode: 'AMAZON',
    marketplaceCode: 'ATVPDKIKX0DER',
    externalKey: 'SKU-001',
    resourceType: 'PRODUCT',
    reason: 'FIELD_PARSE_FAILED',
    detail: '缺少品牌字段',
    payloadDigest: 'abc',
    attempts: 2,
    status: 'OPEN',
    lastError: 'parse failed',
    occurredAt: '2026-10-24T09:00:00.000Z',
    ...overrides,
  };
}

function reconciliationRow(
  overrides: Partial<FinanceSupplierReconciliationRow> = {},
): FinanceSupplierReconciliationRow {
  return {
    id: '66666666-6666-4666-8666-666666666666',
    reconNo: 'REC-001',
    upstreamReconId: '77777777-7777-4777-8777-777777777777',
    upstreamReconNo: 'UP-001',
    supplierRefId: '88888888-8888-4888-8888-888888888888',
    supplierCode: 'SUP-01',
    supplierName: '深圳供应商',
    periodType: 'MONTH',
    periodStart: '2026-09-01',
    periodEnd: '2026-09-30',
    statementCurrency: 'USD',
    status: 'DIFFERENCES',
    statusLabel: '有差异',
    upstreamStatus: 'DIFFERENCES',
    statementReceivedAmount: 1000,
    statementDeductionAmount: 0,
    statementClaimAmount: 0,
    statementSuggestedAmount: 1000,
    statementTotalQuantity: 10,
    financeConfirmedAmount: null,
    financeConfirmedQuantity: null,
    financeAdjustedAmount: 0,
    lineCount: 10,
    differenceCount: 3,
    openDifferenceCount: 3,
    matchedLineCount: 7,
    unmatchedLineCount: 3,
    upstreamSupplierConfirmedAt: null,
    upstreamSupplierReference: '',
    confirmedBy: null,
    confirmedAt: null,
    rejectedBy: null,
    rejectedAt: null,
    rejectReason: '',
    payableId: null,
    payableNo: '',
    note: '',
    createdAt: '2026-10-01T00:00:00.000Z',
    updatedAt: '2026-10-20T00:00:00.000Z',
    ...overrides,
  };
}

function messageRow(overrides: Partial<CrmMessageRow> = {}): CrmMessageRow {
  return {
    id: '99999999-9999-4999-8999-999999999999',
    messageNo: 'MSG-001',
    shopId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    shopName: '美国店',
    platformCode: 'AMAZON',
    buyerReference: 'B-1',
    orderReference: 'SO-1',
    subject: '买家问发货时间',
    body: '',
    direction: 'INBOUND',
    messageType: 'QUESTION',
    priority: 'HIGH',
    readStatus: 'UNREAD',
    readAt: null,
    readBy: null,
    platformTime: '2026-10-23T10:00:00.000Z',
    attachmentCount: 0,
    source: 'AMAZON',
    externalMessageId: 'X-1',
    createdBy: '',
    note: '',
    createdAt: '2026-10-23T10:00:01.000Z',
    updatedAt: '2026-10-23T10:00:01.000Z',
    ...overrides,
  };
}

function overdueRow(overrides: Partial<TimelinessOverdueRow> = {}): TimelinessOverdueRow {
  return {
    factId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    factNo: 'FACT-001',
    salesOrderNo: 'SO-001',
    shopRef: '美国店',
    warehouseRef: '深圳仓',
    carrierCode: 'SF',
    carrierName: '顺丰',
    handedOverAt: '2026-10-20T00:00:00.000Z',
    orderToHandoverSeconds: 100,
    overdueKind: 'SHIP',
    overdueSeconds: 7200,
    deadlineAt: '2026-10-23T20:00:00.000Z',
    promiseVsDeliveryState: 'LATE',
    ...overrides,
  };
}

function source<T>(rows: T[], error = ''): SourceResult<T> {
  return { rows, total: rows.length, error };
}

describe('通知键', () => {
  it('键的形状是 <类型>:<上游主键>', () => {
    expect(notificationKey('STOCKOUT', 'abc')).toBe('STOCKOUT:abc');
    expect(notificationKey('TIMELINESS_OVERDUE', 'abc:SHIP')).toBe('TIMELINESS_OVERDUE:abc:SHIP');
  });

  it('上游主键原样进键，不被改写', () => {
    expect(toStockoutNotification(stockoutRow()).key).toBe(
      'STOCKOUT:11111111-1111-4111-8111-111111111111',
    );
    expect(toSyncFailureNotification(syncFailureRow()).key).toBe(
      'SYNC_FAILURE:44444444-4444-4444-8444-444444444444',
    );
    expect(toReconciliationNotification(reconciliationRow()).key).toBe(
      'RECONCILIATION_DIFF:66666666-6666-4666-8666-666666666666',
    );
    expect(toMessageNotification(messageRow()).key).toBe(
      'MESSAGE:99999999-9999-4999-8999-999999999999',
    );
    expect(toOverdueNotification(overdueRow()).key).toBe(
      'TIMELINESS_OVERDUE:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb:SHIP',
    );
  });

  it('五种类型都有中文标签', () => {
    for (const type of NOTIFICATION_TYPES) {
      expect(NOTIFICATION_TYPE_LABELS[type]).toBeTruthy();
    }
  });
});

describe('严重度按各自判据推', () => {
  it('缺货预警用上游自己的严重度并做词表对齐', () => {
    expect(upstreamSeverity('CRITICAL')).toBe('CRITICAL');
    expect(upstreamSeverity('WARNING')).toBe('HIGH');
    expect(upstreamSeverity('INFO')).toBe('LOW');
    expect(upstreamSeverity('NOPE')).toBe('UNKNOWN');
    expect(upstreamSeverity(null)).toBe('UNKNOWN');
  });

  it('同步失败按失败原因分档', () => {
    expect(syncFailureSeverity('FIELD_PARSE_FAILED')).toBe('HIGH');
    expect(syncFailureSeverity('MAPPING_MISSING')).toBe('HIGH');
    expect(syncFailureSeverity('RATE_LIMITED')).toBe('MEDIUM');
    expect(syncFailureSeverity('SOMETHING_NEW')).toBe('UNKNOWN');
  });

  it('站内信按优先级映射', () => {
    expect(messageSeverity('URGENT')).toBe('CRITICAL');
    expect(messageSeverity('HIGH')).toBe('HIGH');
    expect(messageSeverity('NORMAL')).toBe('MEDIUM');
    expect(messageSeverity('LOW')).toBe('LOW');
    expect(messageSeverity(undefined)).toBe('UNKNOWN');
  });

  it('对账差异按未结差异条数分档', () => {
    expect(reconciliationSeverity(12)).toBe('HIGH');
    expect(reconciliationSeverity(2)).toBe('MEDIUM');
    expect(reconciliationSeverity(null)).toBe('UNKNOWN');
  });

  it('履约超时按超期时长分档，判不了的落在 UNKNOWN', () => {
    expect(overdueSeverity(25 * 3600)).toBe('CRITICAL');
    expect(overdueSeverity(5 * 3600)).toBe('HIGH');
    expect(overdueSeverity(60)).toBe('MEDIUM');
    expect(overdueSeverity(null)).toBe('UNKNOWN');
  });
});

describe('合并：已处置的事项不再被报成通知', () => {
  const merged = mergeNotifications({
    stockout: source([
      stockoutRow(),
      stockoutRow({ id: 'healthy', alertState: 'HEALTHY' }),
      stockoutRow({ id: 'resolved', lifecycleState: 'RESOLVED' }),
    ]),
    syncFailure: source([
      syncFailureRow(),
      syncFailureRow({ id: 'replayed', status: 'RESOLVED' }),
    ]),
    reconciliationDiff: source([
      reconciliationRow(),
      reconciliationRow({ id: 'clean', openDifferenceCount: 0, differenceCount: 0 }),
    ]),
    message: source([
      messageRow(),
      messageRow({ id: 'outbound', direction: 'OUTBOUND' }),
    ]),
    timelinessOverdue: source([
      overdueRow(),
      overdueRow({ factId: 'ontime', overdueSeconds: 0 }),
    ]),
  });

  it('五类各留下一条真实通知', () => {
    expect(merged.items).toHaveLength(5);
    expect(merged.items.map((item) => item.type).sort()).toEqual([
      'MESSAGE',
      'RECONCILIATION_DIFF',
      'STOCKOUT',
      'SYNC_FAILURE',
      'TIMELINESS_OVERDUE',
    ]);
  });

  it('读不到的来源逐类单列，不混成 0 条', () => {
    const broken = mergeNotifications({
      stockout: source([], '服务不可用'),
      syncFailure: source([]),
      reconciliationDiff: source([]),
      message: source([]),
      timelinessOverdue: source([]),
    });
    expect(broken.unreadable).toHaveLength(1);
    expect(broken.unreadable[0]).toEqual({ type: 'STOCKOUT', reason: '服务不可用' });
    expect(broken.items).toHaveLength(0);
  });
});

describe('排序', () => {
  const item = (overrides: Partial<NotificationItem>): NotificationItem => ({
    key: 'STOCKOUT:x',
    type: 'STOCKOUT',
    reference: 'R',
    referenceLabel: '',
    severity: 'MEDIUM',
    occurredAt: '2026-10-24T00:00:00.000Z',
    href: '/x',
    actionLabel: '去',
    detail: '',
    scopeLabel: '',
    read: false,
    ...overrides,
  });

  it('时间新的在前', () => {
    const sorted = sortNotifications([
      item({ key: 'a', occurredAt: '2026-10-01T00:00:00.000Z' }),
      item({ key: 'b', occurredAt: '2026-10-24T00:00:00.000Z' }),
    ]);
    expect(sorted.map((i) => i.key)).toEqual(['b', 'a']);
  });

  it('时刻判不了的排在有时刻的最后（不是当最新）', () => {
    const sorted = sortNotifications([
      item({ key: 'unknown', occurredAt: null }),
      item({ key: 'known', occurredAt: '2026-10-01T00:00:00.000Z' }),
    ]);
    expect(sorted.map((i) => i.key)).toEqual(['known', 'unknown']);
  });

  it('同一时刻按严重度降序', () => {
    const sorted = sortNotifications([
      item({ key: 'low', severity: 'LOW' }),
      item({ key: 'critical', severity: 'CRITICAL' }),
    ]);
    expect(sorted.map((i) => i.key)).toEqual(['critical', 'low']);
  });

  it('同一批数据两次排序结果一致', () => {
    const rows = [
      item({ key: 'a', reference: 'A' }),
      item({ key: 'b', reference: 'B' }),
      item({ key: 'c', reference: 'C' }),
    ];
    expect(sortNotifications(rows).map((i) => i.key)).toEqual(
      sortNotifications([...rows].reverse()).map((i) => i.key),
    );
  });
});

describe('订阅与筛选', () => {
  const items: NotificationItem[] = [
    {
      key: 'STOCKOUT:1',
      type: 'STOCKOUT',
      reference: 'SKU-001',
      referenceLabel: '深圳仓',
      severity: 'CRITICAL',
      occurredAt: '2026-10-24T00:00:00.000Z',
      href: '/a',
      actionLabel: '去',
      detail: '可用 0',
      scopeLabel: '仓库 WH-01',
      read: false,
    },
    {
      key: 'MESSAGE:1',
      type: 'MESSAGE',
      reference: 'MSG-001',
      referenceLabel: '美国店',
      severity: 'LOW',
      occurredAt: '2026-10-23T00:00:00.000Z',
      href: '/b',
      actionLabel: '去',
      detail: '买家来信',
      scopeLabel: '店铺 美国店',
      read: true,
    },
  ];

  it('订阅集合之外的类型不进收件箱', () => {
    expect(filterBySubscription(items, ['MESSAGE'])).toHaveLength(1);
    expect(filterBySubscription(items, ['STOCKOUT', 'MESSAGE'])).toHaveLength(2);
    expect(filterBySubscription(items, [])).toHaveLength(0);
  });

  it('按类型 / 严重度 / 已读状态 / 关键字筛选', () => {
    expect(filterNotifications(items, { ...EMPTY_FILTERS, type: 'MESSAGE' })).toHaveLength(1);
    expect(filterNotifications(items, { ...EMPTY_FILTERS, severity: 'CRITICAL' })).toHaveLength(1);
    expect(filterNotifications(items, { ...EMPTY_FILTERS, readState: 'UNREAD' })).toHaveLength(1);
    expect(filterNotifications(items, { ...EMPTY_FILTERS, readState: 'READ' })).toHaveLength(1);
    expect(filterNotifications(items, { ...EMPTY_FILTERS, keyword: 'MSG' })).toHaveLength(1);
    expect(filterNotifications(items, { ...EMPTY_FILTERS, keyword: '不存在' })).toHaveLength(0);
  });
});

describe('汇总：计数与清单同源', () => {
  const items: NotificationItem[] = [
    {
      key: 'STOCKOUT:1',
      type: 'STOCKOUT',
      reference: 'A',
      referenceLabel: '',
      severity: 'CRITICAL',
      occurredAt: '2026-10-24T00:00:00.000Z',
      href: '/a',
      actionLabel: '去',
      detail: '',
      scopeLabel: '',
      read: false,
    },
    {
      key: 'MESSAGE:1',
      type: 'MESSAGE',
      reference: 'B',
      referenceLabel: '',
      severity: 'UNKNOWN',
      occurredAt: null,
      href: '/b',
      actionLabel: '去',
      detail: '',
      scopeLabel: '',
      read: true,
    },
  ];

  it('未读数就是清单里没读过的那几条', () => {
    const summary = summarizeNotifications(items);
    expect(summary.total).toBe(2);
    expect(summary.unreadCount).toBe(1);
    expect(summary.readCount).toBe(1);
    expect(summary.byType.STOCKOUT).toBe(1);
    expect(summary.byType.MESSAGE).toBe(1);
  });

  it('判不了严重度与判不了时刻各只算一次', () => {
    const summary = summarizeNotifications(items);
    expect(summary.unknownSeverityCount).toBe(1);
    expect(summary.unknownTimeCount).toBe(1);
  });

  it('角标在未读为 0 时不显示（不写个 0）', () => {
    expect(unreadBadge({ unreadCount: 0 })).toBe('');
    expect(unreadBadge({ unreadCount: 3 })).toBe('3');
  });
});

describe('已读状态贴回通知流', () => {
  it('按通知键判定已读，不是按上游字段', () => {
    const items: NotificationItem[] = [
      {
        key: 'STOCKOUT:1',
        type: 'STOCKOUT',
        reference: 'A',
        referenceLabel: '',
        severity: 'LOW',
        occurredAt: '2026-10-24T00:00:00.000Z',
        href: '/a',
        actionLabel: '去',
        detail: '',
        scopeLabel: '',
        read: false,
      },
    ];
    const applied = applyReadState(items, new Set(['STOCKOUT:1']));
    expect(applied[0]?.read).toBe(true);
    expect(applyReadState(items, new Set())[0]?.read).toBe(false);
  });
});

describe('文案', () => {
  it('时刻缺失显示「时刻判不了」，不是「刚刚」', () => {
    expect(formatOccurredAt(null)).toBe('时刻判不了');
    expect(formatOccurredAt('不是时间')).toBe('时刻判不了');
    expect(formatOccurredAt('2026-10-24T08:00:00.000Z')).not.toBe('时刻判不了');
  });

  it('时长判不了显示「判不了」', () => {
    expect(formatSpan(null)).toBe('判不了');
    expect(formatSpan(30)).toBe('不到 1 分钟');
    expect(formatSpan(3600)).toBe('1 小时');
  });
});
