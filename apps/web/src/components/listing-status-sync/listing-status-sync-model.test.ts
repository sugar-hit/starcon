import { describe, expect, it } from 'vitest';
import {
  conflictSummary,
  emptyConflictsMessage,
  emptyEventsMessage,
  emptyQueueMessage,
  eventTypeLabel,
  EVENT_TYPE_OPTIONS,
  ingestChannelLabel,
  operationStatusLabel,
  operationStatusTone,
  pageSummary,
  queueKindLabel,
  queueStateLabel,
  queueStateTone,
  republishSummary,
  syncDelayLabel,
  type ListingStatusEventRow,
  type RepublishReceipt,
} from './listing-status-sync-model';

/**
 * 4.3.2.2 平台状态回流 的纯函数断言。
 *
 * <p>这个文件盯的是**文案层最容易做丢的五件事**：
 *
 * <ol>
 *   <li>「在途 / 结果未知 / 失败」三态的文案与色调**必须不同** ——
 *       把"结果未知"显示成"已处理"是谎报，显示成"失败"会让运营以为要重来一遍。
 *   <li>冲突摘要里**必须出现「以平台为准」** —— 那是蓝图的加粗句，也是这条告警的行动指令。
 *   <li>「一键处理」的回执里，**没发出去的条目也要逐条出现** ——
 *       静默丢弃会让运营以为全发出去了。
 *   <li>空态文案**三态各不相同**：「还没有记录」与「事件源尚未接入」是两回事。
 *   <li>真分页的页码摘要用服务端给的 {@code total}，不是前端自己猜的。
 * </ol>
 */

function event(overrides: Partial<ListingStatusEventRow> = {}): ListingStatusEventRow {
  return {
    eventId: 'e1',
    listingId: 'l1',
    externalItemId: 'ITEM-1',
    title: '测试商品',
    shopCode: 'SHOP-1',
    platformCode: 'AMAZON',
    eventType: 'DELISTED',
    source: 'PLATFORM',
    ingestChannel: 'WEBHOOK',
    fromStatus: 'ACTIVE',
    toStatus: 'INACTIVE',
    platformStatus: 'INACTIVE',
    internalStatus: 'ON_SALE',
    conflictDetected: true,
    conflictNote: '内部状态 ON_SALE，平台状态 DELISTED：以平台为准',
    resolution: 'PLATFORM_WINS',
    rejectionReason: '',
    detail: '{}',
    note: '',
    occurredAt: '2026-03-14T07:00:00Z',
    receivedAt: '2026-03-14T07:00:30Z',
    ...overrides,
  };
}

describe('4.3.2.2 事件类型', () => {
  it('七种类型齐全且与后端取值域逐字一致', () => {
    expect(EVENT_TYPE_OPTIONS.map((o) => o.value)).toEqual([
      'APPROVED',
      'REJECTED',
      'DELISTED',
      'THROTTLED',
      'SUPPRESSED',
      'CATEGORY_REVIEW',
      'PRICE_ALERT',
    ]);
  });

  it('每种类型都有中文名', () => {
    for (const option of EVENT_TYPE_OPTIONS) {
      expect(eventTypeLabel(option.value)).toBe(option.label);
      expect(option.label).not.toBe(option.value);
    }
  });

  it('未知类型原样返回（不编一个最像的）', () => {
    expect(eventTypeLabel('NOT_A_REAL_TYPE')).toBe('NOT_A_REAL_TYPE');
  });
});

describe('4.3.2.2 队列项类型与状态', () => {
  it('三种待办的文案各不相同', () => {
    const labels = ['REPUBLISH', 'REVIEW', 'CONFLICT_ALERT'].map(queueKindLabel);
    expect(new Set(labels).size).toBe(3);
    expect(labels[0]).toContain('重发');
    expect(labels[2]).toContain('冲突');
  });

  it('「已派发」不等于「已处理完」', () => {
    expect(queueStateLabel('DISPATCHED')).not.toBe(queueStateLabel('RESOLVED'));
    expect(queueStateLabel('DISPATCHED')).toContain('在途');
  });

  it('状态色调：处理完=成功、已作废=危险、在途=信息', () => {
    expect(queueStateTone('RESOLVED')).toBe('success');
    expect(queueStateTone('CANCELLED')).toBe('danger');
    expect(queueStateTone('DISPATCHED')).toBe('info');
  });
});

describe('4.3.2.2 🔴 02 队列状态：在途 / 结果未知 / 失败三态不同', () => {
  it('结果未知有专门文案（不是「已处理」，也不是「失败」）', () => {
    const unknown = operationStatusLabel('UNKNOWN', 'STILL_UNKNOWN');
    expect(unknown).toContain('结果未知');
    expect(unknown).toContain('核验');
    expect(unknown).not.toBe(operationStatusLabel('SUCCEEDED', 'NOT_REQUIRED'));
    expect(unknown).not.toBe(operationStatusLabel('FAILED', 'NOT_REQUIRED'));
  });

  it('结果未知的色调是 warning（既不是成功也不是失败）', () => {
    expect(operationStatusTone('UNKNOWN')).toBe('warning');
    expect(operationStatusTone('SUCCEEDED')).toBe('success');
    expect(operationStatusTone('FAILED')).toBe('danger');
  });

  it('尚未发起就说尚未发起', () => {
    expect(operationStatusLabel('', '')).toBe('尚未发起');
  });
});

describe('4.3.2.2 🔴 冲突摘要', () => {
  it('必须写出「以平台为准」与两边状态', () => {
    const summary = conflictSummary(event());
    expect(summary).toContain('以平台为准');
    expect(summary).toContain('ON_SALE');
    expect(summary).toContain('INACTIVE');
  });

  it('没有冲突就不出摘要（不滥报）', () => {
    expect(conflictSummary(event({ conflictDetected: false }))).toBe('');
  });
});

describe('4.3.2.2 🔴 一键处理回执', () => {
  const receipt: RepublishReceipt = {
    matchedCount: 2,
    dispatchedCount: 1,
    skippedCount: 1,
    dispatched: [
      {
        queueId: 'q1',
        eventId: 'e1',
        listingId: 'l1',
        externalItemId: 'ITEM-1',
        shopCode: 'SHOP-1',
        eventType: 'REJECTED',
        rejectionReason: '标题过长',
        operationId: 'op1',
        idempotencyKey: 'LSR-q1',
        operationStatus: 'PENDING',
        operationVerification: 'NOT_REQUIRED',
      },
    ],
    rejected: ['q2：状态为 DISPATCHED，不再重复发起修正重发'],
    note: '修正重发已入 02 队列',
  };

  it('逐条写明发起了什么（含 02 队列任务 id）', () => {
    const lines = republishSummary(receipt);
    expect(lines.join('\n')).toContain('ITEM-1');
    expect(lines.join('\n')).toContain('op1');
    expect(lines.join('\n')).toContain('标题过长');
  });

  it('🔴 没发出去的条目也要出现（不静默丢弃）', () => {
    const lines = republishSummary(receipt);
    expect(lines.join('\n')).toContain('不再重复发起修正重发');
    expect(lines.join('\n')).toContain('命中 2 条');
  });
});

describe('4.3.2.2 真分页摘要', () => {
  it('用服务端给的 total，不用前端自己算', () => {
    expect(pageSummary({ page: 0, size: 20, total: 55 })).toBe('第 1-20 条 / 共 55 条');
    expect(pageSummary({ page: 2, size: 20, total: 55 })).toBe('第 41-55 条 / 共 55 条');
  });

  it('空集不会显示成「第 1-0 条」', () => {
    expect(pageSummary({ page: 0, size: 20, total: 0 })).toBe('第 0-0 条 / 共 0 条');
  });
});

describe('4.3.2.2 同步延迟', () => {
  it('用两个真实时刻的差值', () => {
    expect(syncDelayLabel('2026-03-14T07:00:00Z', '2026-03-14T07:00:30Z')).toBe('30 秒');
    expect(syncDelayLabel('2026-03-14T07:00:00Z', '2026-03-14T07:05:00Z')).toBe('5 分钟');
  });

  it('时间不可解析时显示「—」而不是 NaN', () => {
    expect(syncDelayLabel('not-a-date', '2026-03-14T07:00:00Z')).toBe('—');
  });
});

describe('4.3.2.2 🔴 三态文案各不相同', () => {
  it('「还没有记录」与「事件源尚未接入」是两句话', () => {
    const normal = emptyEventsMessage(true);
    const notIngested = emptyEventsMessage(false);
    expect(normal).not.toBe(notIngested);
    expect(normal).toContain('还没有平台状态回流记录');
    expect(notIngested).toContain('尚未接入');
  });

  it('队列空态与冲突空态也各是一句', () => {
    expect(emptyQueueMessage()).not.toBe(emptyConflictsMessage());
    expect(emptyConflictsMessage()).toContain('以平台为准');
  });
});

describe('4.3.2.2 事件来源通道', () => {
  it('三条通道 + 内部人工改状态', () => {
    expect(ingestChannelLabel('WEBHOOK')).toBe('平台推送');
    expect(ingestChannelLabel('POLL')).toBe('轮询补充');
    expect(ingestChannelLabel('API')).toBe('接口录入');
    expect(ingestChannelLabel('MANUAL')).toBe('内部人工改状态');
  });
});
