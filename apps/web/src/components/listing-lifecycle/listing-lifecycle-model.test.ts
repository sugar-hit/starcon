import { describe, expect, it } from 'vitest';
import {
  actionHint,
  actionLabel,
  actionSummary,
  divergenceLabel,
  divergenceTone,
  emptyDivergenceMessage,
  emptyListMessage,
  emptyTimelineMessage,
  eventHeadline,
  eventSubline,
  formatTime,
  pageSummary,
  platformEventLabel,
  receiptSummary,
  sourceLabel,
  sourceTone,
  statusLabel,
  statusTone,
  syncSummary,
  STATUS_OPTIONS,
  type LifecycleActionReceipt,
  type LifecycleSyncReceipt,
  type ListingLifecycleActionRow,
  type ListingLifecycleEventRow,
} from './listing-lifecycle-model';

/**
 * 4.1.1.3 生命周期与下架 的纯函数断言。
 *
 * <p>这个文件盯的是**文案层最容易做丢的五件事**：
 *
 * <ol>
 *   <li>「平台 / 人工」两个来源的文案与色调**必须不同** —— 这是本模块的核心价值：
 *       少了它，「平台把商品下架了」和「我们主动下架了」在界面上长得一模一样，
 *       而这两件事的处置方式完全相反。</li>
 *   <li>四类差异的文案与色调**必须不同** —— 「平台已下架内部还在售」是漏单风险（danger），
 *       其余三类是需跟进（warning），混成一句「有差异」就没人管了。</li>
 *   <li>回执里「有没有回写平台」**必须说清楚** —— 回写了平台侧也会下架，
 *       没回写平台可能还在卖。这两件事的后果完全相反。</li>
 *   <li>时间线上一条事件的标题**必须区分**「内部状态变更」与「平台事件原文」，
 *       而且判据来自服务端的 {@code internalStateChange}，前端不自己判断。</li>
 *   <li>空态文案**分得清**「还没有数据」与「筛选条件太窄」。</li>
 * </ol>
 */

function event(overrides: Partial<ListingLifecycleEventRow> = {}): ListingLifecycleEventRow {
  return {
    eventId: 'e1',
    listingId: 'l1',
    externalItemId: 'ITEM-1',
    title: '测试商品',
    shopCode: 'SHOP-1',
    platformCode: 'AMAZON',
    fromStatus: 'ON_SALE',
    toStatus: 'DELISTED_BY_PLATFORM',
    source: 'PLATFORM',
    eventType: 'DELISTED',
    platformStatus: 'INACTIVE',
    internalStatus: '',
    conflictDetected: false,
    conflictNote: '',
    resolution: 'NOT_REQUIRED',
    rejectionReason: '',
    note: '',
    internalStateChange: true,
    occurredAt: '2026-03-14T07:09:26Z',
    receivedAt: '2026-03-14T07:09:27Z',
    ...overrides,
  };
}

function action(overrides: Partial<ListingLifecycleActionRow> = {}): ListingLifecycleActionRow {
  return {
    actionId: 'a1',
    listingId: 'l1',
    action: 'DELIST',
    fromStatus: 'ON_SALE',
    toStatus: 'DELISTED_BY_US',
    source: 'MANUAL',
    platformEventType: '',
    platformStatus: '',
    rejectionReason: '',
    reason: '手动下架',
    writebackRequested: false,
    writebackBatchId: null,
    writebackBatchNo: '',
    writebackItemId: null,
    operationId: null,
    platformEventId: null,
    actorId: 'u1',
    createdAt: '2026-03-14T07:09:26Z',
    ...overrides,
  };
}

function receipt(overrides: Partial<LifecycleActionReceipt> = {}): LifecycleActionReceipt {
  return {
    actionId: 'a1',
    listingId: 'l1',
    action: 'DELIST',
    fromStatus: 'ON_SALE',
    toStatus: 'DELISTED_BY_US',
    source: 'MANUAL',
    writebackRequested: false,
    writebackBatchId: null,
    writebackBatchNo: '',
    operationId: null,
    statusEventId: 'e1',
    note: '状态已改',
    ...overrides,
  };
}

describe('4.1.1.3 来源标注是本模块的核心价值', () => {
  it('平台与人工的文案不同', () => {
    expect(sourceLabel('PLATFORM')).toBe('平台');
    expect(sourceLabel('MANUAL')).toBe('人工');
    expect(sourceLabel('PLATFORM')).not.toBe(sourceLabel('MANUAL'));
  });

  it('平台与人工的色调不同 —— 必须一眼能分开', () => {
    expect(sourceTone('PLATFORM')).toBe('info');
    expect(sourceTone('MANUAL')).toBe('neutral');
  });
});

describe('4.1.1.3 七态', () => {
  it('七个状态都有中文名，且互不相同', () => {
    expect(STATUS_OPTIONS).toHaveLength(7);
    const labels = STATUS_OPTIONS.map((o) => o.label);
    expect(new Set(labels).size).toBe(7);
    for (const option of STATUS_OPTIONS) {
      expect(statusLabel(option.value)).toBe(option.label);
    }
  });

  it('在售是 success，两个下架是 warning，封禁与驳回是 danger', () => {
    expect(statusTone('ON_SALE')).toBe('success');
    expect(statusTone('DELISTED_BY_US')).toBe('warning');
    expect(statusTone('DELISTED_BY_PLATFORM')).toBe('warning');
    expect(statusTone('BLOCKED')).toBe('danger');
    expect(statusTone('REJECTED')).toBe('danger');
    expect(statusTone('UNDER_REVIEW')).toBe('info');
  });
});

describe('4.1.1.3 差异区', () => {
  it('四类差异的文案互不相同', () => {
    const kinds = [
      'PLATFORM_DELISTED_INTERNAL_ON_SALE',
      'INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE',
      'INTERNAL_DELISTED_NOT_WRITEBACK',
      'INTERNAL_DELISTED_NO_WRITEBACK_RECORD',
    ];
    const labels = kinds.map(divergenceLabel);
    expect(new Set(labels).size).toBe(4);
  });

  it('漏单风险是 danger，其余是 warning', () => {
    expect(divergenceTone('PLATFORM_DELISTED_INTERNAL_ON_SALE')).toBe('danger');
    expect(divergenceTone('INTERNAL_DELISTED_NOT_WRITEBACK')).toBe('warning');
    expect(divergenceTone('INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE')).toBe('warning');
  });

  it('空态文案要说清「没有差异」是什么含义', () => {
    expect(emptyDivergenceMessage()).toContain('权威来源');
  });
});

describe('4.1.1.3 回执必须说清有没有回写平台', () => {
  it('回写了：回执里要出现批次与队列任务', () => {
    const lines = receiptSummary(
      receipt({
        writebackRequested: true,
        writebackBatchId: 'b1',
        writebackBatchNo: 'WB-20260314-000001',
        operationId: 'op1',
      }),
    );
    expect(lines.join('\n')).toContain('WB-20260314-000001');
    expect(lines.join('\n')).toContain('op1');
  });

  it('没回写：回执里必须写明「平台侧不知情」，不能让人以为已经下架到平台', () => {
    const lines = receiptSummary(receipt({ writebackRequested: false }));
    const text = lines.join('\n');
    expect(text).toContain('未回写平台');
    expect(text).toContain('平台侧不知情');
    expect(text).not.toContain('已生成平台操作任务');
  });

  it('批量同步的回执逐条列出联动与未联动', () => {
    const lines = syncSummary({
      appliedCount: 1,
      skippedCount: 1,
      applied: ['商品 X：ON_SALE → DELISTED_BY_PLATFORM'],
      skipped: ['事件 Y：内部状态已经是 DELISTED_BY_PLATFORM，状态没有变化'],
      note: '本次联动 1 条',
    });
    const text = lines.join('\n');
    expect(text).toContain('联动 1 条');
    expect(text).toContain('商品 X');
    expect(text).toContain('事件 Y');
  });
});

describe('4.1.1.3 时间线', () => {
  it('内部状态变更与平台事件原文的标题不同', () => {
    const internal = eventHeadline(event({ internalStateChange: true }));
    const platform = eventHeadline(
      event({
        internalStateChange: false,
        fromStatus: 'ACTIVE',
        toStatus: 'INACTIVE',
        eventType: 'DELISTED',
        platformStatus: 'INACTIVE',
      }),
    );
    expect(internal).toContain('内部状态');
    expect(platform).toContain('平台事件');
    expect(internal).not.toBe(platform);
  });

  it('副标题里必须出现来源', () => {
    expect(eventSubline(event({ source: 'PLATFORM' }))).toContain('来源：平台');
    expect(eventSubline(event({ source: 'MANUAL' }))).toContain('来源：人工');
  });

  it('驳回原因要出现在副标题里', () => {
    const sub = eventSubline(event({ rejectionReason: '标题过长' }));
    expect(sub).toContain('标题过长');
  });

  it('空态文案按来源分得清', () => {
    expect(emptyTimelineMessage('PLATFORM')).toContain('平台来源');
    expect(emptyTimelineMessage('MANUAL')).toContain('人工来源');
    expect(emptyTimelineMessage('')).toContain('任何状态变更记录');
  });
});

describe('4.1.1.3 动作留痕', () => {
  it('下架且没回写要写明「未回写平台」', () => {
    const summary = actionSummary(action({ writebackRequested: false }));
    expect(summary).toContain('未回写平台');
    expect(summary).toContain('来源：人工');
  });

  it('下架且回写了要写明批次', () => {
    const summary = actionSummary(
      action({ writebackRequested: true, writebackBatchNo: 'WB-20260314-000002' }),
    );
    expect(summary).toContain('WB-20260314-000002');
  });

  it('每个动作都有中文名与说明', () => {
    for (const action_ of ['DELIST', 'REPUBLISH', 'SUBMIT_REVIEW', 'MARK_BLOCKED']) {
      expect(actionLabel(action_)).not.toBe(action_);
      expect(actionHint(action_)).not.toBe('');
    }
  });
});

describe('4.1.1.3 其它', () => {
  it('分页摘要用服务端给的 total', () => {
    expect(pageSummary({ page: 0, size: 20, total: 0 })).toBe('第 0-0 条 / 共 0 条');
    expect(pageSummary({ page: 1, size: 20, total: 45 })).toBe('第 21-40 条 / 共 45 条');
  });

  it('空态文案区分「没有数据」与「筛选太窄」', () => {
    expect(emptyListMessage(false)).toContain('还没有登记任何商品');
    expect(emptyListMessage(true)).toContain('筛选条件');
    expect(emptyListMessage(false)).not.toBe(emptyListMessage(true));
  });

  it('平台事件类型都有中文名', () => {
    expect(platformEventLabel('DELISTED')).toBe('平台下架');
    expect(platformEventLabel('REJECTED')).toBe('平台驳回');
    expect(platformEventLabel('THROTTLED')).toBe('平台限流');
  });

  it('空时刻显示为「—」而不是 Invalid Date', () => {
    expect(formatTime(null)).toBe('—');
    expect(formatTime('not-a-date')).toBe('—');
    expect(formatTime('2026-03-14T07:09:26Z')).not.toBe('—');
  });
});
