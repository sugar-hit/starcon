import { describe, expect, it } from 'vitest';
import {
  afterSourceLabel,
  actionSummary,
  beforeDisplay,
  buildCreateRequest,
  CHANGE_TYPE_OPTIONS,
  dispatchSummary,
  emptyWritebacksMessage,
  fieldLabel,
  foreignChannels,
  outcomeLabel,
  progressSummary,
  taskStateLabel,
  taskStateTone,
  triggerStateLabel,
  unavailableDimensions,
  type ContentWritebackActionResult,
  type ContentWritebackCapabilities,
  type ContentWritebackChangeRow,
  type ContentWritebackDispatchResponse,
  type ContentWritebackRow,
} from './content-writeback-model';

/**
 * 4.3.2.1 内容回写 前端纯函数单测。
 *
 * 这些函数承担的是「**不把未交付的东西画成已交付**」的责任，所以断言重点不是格式，
 * 而是几个具体的不变量：页面上不会出现价格 / 库存选项、跳过不算成功、
 * 改前缺失不拿改后顶替、拒绝原因原样透传。
 */

const CAPS: ContentWritebackCapabilities = {
  dimensions: [
    {
      dimension: 'before_after_snapshot',
      description: '逐字段改前改后',
      available: true,
      source: 'V187',
      ownerModule: '',
      impact: '',
    },
    {
      dimension: 'platform_execution',
      description: '实际调平台 API',
      available: false,
      source: '',
      ownerModule: '02 平台适配器',
      impact: '任务停在在途，由 4.3.2.2 回报',
    },
  ],
  changeTypes: ['TITLE', 'BULLETS', 'IMAGES', 'DESCRIPTION', 'A_PLUS'],
  rejectedChangeTypes: ['PRICE', 'STOCK', 'QUANTITY'],
  channels: [
    { channel: 'CONTENT', label: '内容回写', ownerModule: '4.3.2.1', handledHere: true, note: '' },
    { channel: 'PRICE', label: '价格回写', ownerModule: '05 定价', handledHere: false, note: '改价走 UPDATE_PRICE' },
    { channel: 'STOCK', label: '库存回写', ownerModule: '07 库存', handledHere: false, note: '改库存走 UPDATE_STOCK' },
  ],
  triggerStates: ['RUNNING', 'COMPLETED', 'CANCELLED'],
  taskStates: ['PENDING', 'SKIPPED_NO_CHANGE', 'DISPATCHED', 'SUCCEEDED', 'FAILED'],
  snapshotNote: '改前来自平台侧当前快照，改后来自内部草稿或覆盖值',
  relationNote: '上游 4.2.1.2 草稿 + 4.1.1.2 快照；下游 2.5.1.1',
};

const WRITEBACK: ContentWritebackRow = {
  writebackId: 'w1',
  triggerKind: 'BULK_EVENT',
  changeTypes: ['TITLE'],
  listingIds: [],
  overrides: '{}',
  language: 'en-US',
  state: 'RUNNING',
  totalCount: 3,
  pendingCount: 1,
  skippedCount: 1,
  dispatchedCount: 0,
  succeededCount: 1,
  failedCount: 0,
  progressPercent: 67,
  actorId: null,
  note: '',
  createdAt: '2026-10-19T00:00:00Z',
  updatedAt: '2026-10-19T00:00:00Z',
  completedAt: null,
};

function change(overrides: Partial<ContentWritebackChangeRow> = {}): ContentWritebackChangeRow {
  return {
    changeId: 'c1',
    taskId: 't1',
    listingId: 'l1',
    field: 'TITLE',
    beforeValue: '旧标题',
    afterValue: '新标题',
    changed: true,
    outcome: 'APPLIED',
    beforeSource: 'PLATFORM_SNAPSHOT',
    afterSource: 'INTERNAL_DRAFT',
    reason: '',
    ...overrides,
  };
}

describe('4.3.2.1 🔴 页面上不出现价格与库存的入口', () => {
  it('可选项只有五个内容字段', () => {
    const values = CHANGE_TYPE_OPTIONS.map((o) => o.value);
    expect(values).toEqual(['TITLE', 'BULLETS', 'IMAGES', 'DESCRIPTION', 'A_PLUS']);
    expect(values).not.toContain('PRICE');
    expect(values).not.toContain('STOCK');
  });

  it('buildCreateRequest 丢弃不在内容通道里的类型（前端这道闸独立于服务端）', () => {
    const body = buildCreateRequest({
      changeTypes: ['TITLE', 'price', 'STOCK', 'IMAGES'],
      listingIds: [],
      shopCode: '',
      status: '',
      overrides: {},
      limit: 50,
      note: '',
    });
    // 拼错的大小写归一后可用；price / stock 直接被丢掉，绝不下发
    expect(body?.changeTypes).toEqual(['TITLE', 'IMAGES']);
  });

  it('buildCreateRequest 同时丢掉 overrides 里的非内容键', () => {
    const body = buildCreateRequest({
      changeTypes: ['DESCRIPTION'],
      listingIds: [],
      shopCode: '',
      status: '',
      overrides: { DESCRIPTION: '新描述', PRICE: '99.99' },
      limit: 50,
      note: '',
    });
    expect(body?.overrides).toEqual({ DESCRIPTION: '新描述' });
  });

  it('一个字段都没选时返回 null（提交按钮据此禁用，而不是发空请求）', () => {
    expect(
      buildCreateRequest({
        changeTypes: [],
        listingIds: ['l1'],
        shopCode: '',
        status: '',
        overrides: {},
        limit: 50,
        note: '',
      }),
    ).toBeNull();
    expect(
      buildCreateRequest({
        changeTypes: ['   '],
        listingIds: [],
        shopCode: '',
        status: '',
        overrides: {},
        limit: 50,
        note: '',
      }),
    ).toBeNull();
  });

  it('空值不下发（不下发空串去覆盖服务端默认）', () => {
    const body = buildCreateRequest({
      changeTypes: ['TITLE'],
      listingIds: [],
      shopCode: '',
      status: '',
      overrides: { TITLE: '   ' },
      limit: 20,
      note: '',
    });
    expect(body).toEqual({ changeTypes: ['TITLE'], limit: 20 });
    expect(body).not.toHaveProperty('shopCode');
    expect(body).not.toHaveProperty('status');
    expect(body).not.toHaveProperty('overrides');
    expect(body).not.toHaveProperty('note');
  });

  it('通道归属：不属于本模块的通道带上 ownerModule（页面据此显示「改价请去 05 定价」）', () => {
    expect(foreignChannels(CAPS).map((c) => c.ownerModule)).toEqual(['05 定价', '07 库存']);
  });
});

describe('4.3.2.1 🔴 跳过不算成功，在途也不算成功', () => {
  it('SKIPPED_NO_CHANGE 有独立文案与 warning 色调', () => {
    expect(taskStateLabel('SKIPPED_NO_CHANGE')).toBe('已跳过（无变更）');
    expect(taskStateLabel('SKIPPED_NO_CHANGE')).not.toContain('已回写');
    expect(taskStateTone('SKIPPED_NO_CHANGE')).toBe('warning');
    expect(taskStateTone('SUCCEEDED')).toBe('success');
    expect(taskStateTone('FAILED')).toBe('danger');
  });

  it('在途文案明确是「等平台回报」，不是「已回写」', () => {
    expect(taskStateLabel('DISPATCHED')).toBe('在途（等平台回报）');
    expect(taskStateLabel('DISPATCHED')).not.toContain('已回写');
    expect(taskStateLabel('SUCCEEDED')).toBe('已回写');
  });

  it('进度摘要把跳过单独成句，不并进成功', () => {
    const line = progressSummary(WRITEBACK);
    expect(line).toContain('已跳过 1');
    expect(line).toContain('已回写 1');
    // 跳过与回写是同一个数字也不能合并显示
    expect(line).not.toMatch(/成功 \d+ · 失败 \d+（/);
  });

  it('触发状态文案三态', () => {
    expect(triggerStateLabel('RUNNING')).toBe('进行中');
    expect(triggerStateLabel('COMPLETED')).toBe('已完成');
    expect(triggerStateLabel('CANCELLED')).toBe('已中止');
  });
});

describe('4.3.2.1 🔴 改前值缺失就说缺失，不拿改后顶替', () => {
  it('MISSING 时返回「不可知」而不是改后值', () => {
    const display = beforeDisplay(change({ beforeValue: '', beforeSource: 'MISSING' }));
    expect(display.known).toBe(false);
    expect(display.text).toContain('不可知');
    expect(display.text).not.toBe('新标题');
  });

  it('有基线时原样返回；空串显示成「（空）」而不是空白', () => {
    expect(beforeDisplay(change({ beforeValue: '旧标题' }))).toEqual({
      known: true,
      text: '旧标题',
    });
    expect(beforeDisplay(change({ beforeValue: '' })).text).toBe('（空）');
  });

  it('改后来源三态各有说法', () => {
    expect(afterSourceLabel('INTERNAL_DRAFT')).toBe('内部草稿');
    expect(afterSourceLabel('OVERRIDE')).toBe('本次显式给值');
    expect(afterSourceLabel('MISSING')).toBe('无来源');
  });

  it('结局三态各有说法', () => {
    expect(outcomeLabel('APPLIED')).toBe('有变更');
    expect(outcomeLabel('NO_CHANGE')).toBe('与线上相同（不派发）');
    expect(outcomeLabel('SKIPPED_NO_SOURCE')).toBe('内部无可推送内容');
  });

  it('字段名可读，未知字段原样返回而不是空串', () => {
    expect(fieldLabel('TITLE')).toBe('标题');
    expect(fieldLabel('A_PLUS')).toBe('A+');
    expect(fieldLabel('WHATEVER')).toBe('WHATEVER');
  });
});

describe('4.3.2.1 🔴 派发摘要逐限流行 + 队列凭据', () => {
  const dispatch = (overrides: Partial<ContentWritebackDispatchResponse> = {}) =>
    ({
      writebackId: 'w1',
      triggerState: 'RUNNING',
      totalDispatched: 0,
      totalSkippedByRateLimit: 0,
      queuedOperationIds: [],
      lanes: [],
      writeback: WRITEBACK,
      ...overrides,
    }) as ContentWritebackDispatchResponse;

  it('未配置限流时写明「未配置限流」，不并成一个看起来正常的总数', () => {
    const lines = dispatchSummary(
      dispatch({
        lanes: [
          {
            platformCode: 'AMAZON',
            shopCode: null,
            rateLimitId: null,
            maxInFlight: 0,
            inFlightBefore: 0,
            requestsPerWindow: 0,
            windowSeconds: 0,
            windowCountBefore: 0,
            dispatched: 0,
            skippedByRateLimit: 3,
            note: '该平台未配置限流参数',
          },
        ],
      }),
    );
    expect(lines.join('\n')).toContain('未配置限流');
    expect(lines.join('\n')).toContain('3 条待派');
  });

  it('两个平台各走各的账，不合并', () => {
    const lane = (code: string, dispatched: number, inFlightBefore: number) => ({
      platformCode: code,
      shopCode: null,
      rateLimitId: 'r1',
      maxInFlight: 4,
      inFlightBefore,
      requestsPerWindow: 60,
      windowSeconds: 60,
      windowCountBefore: 0,
      dispatched,
      skippedByRateLimit: 0,
      note: '',
    });
    const lines = dispatchSummary(
      dispatch({ lanes: [lane('AMAZON', 1, 0), lane('TIKTOK_SHOP', 3, 0)] }),
    );
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('AMAZON');
    expect(lines[1]).toContain('TIKTOK_SHOP');
  });

  it('队列凭据单独成句 —— 「经 02 队列」是可查事实', () => {
    const lines = dispatchSummary(dispatch({ queuedOperationIds: ['op1', 'op2'] }));
    expect(lines.join('\n')).toContain('02 队列 2 条');
  });

  it('中止与「没有可派发任务」分开说', () => {
    expect(dispatchSummary(dispatch({ triggerState: 'CANCELLED' }))[0]).toContain('已中止');
    expect(dispatchSummary(dispatch({ triggerState: 'RUNNING' }))[0]).toBe('本轮没有可派发的任务');
  });
});

describe('4.3.2.1 动作结果与能力表', () => {
  it('被拒原因原样透传，不吞掉', () => {
    const result: ContentWritebackActionResult = {
      writebackId: 'w1',
      state: 'RUNNING',
      changedTaskIds: ['t2'],
      rejected: ['t1: 状态为 SUCCEEDED，只有 FAILED 可重试'],
      writeback: WRITEBACK,
    };
    const line = actionSummary(result, '重试');
    expect(line).toContain('重试了 1 条');
    expect(line).toContain('SUCCEEDED');
    expect(line).toContain('1 条被拒绝');
  });

  it('未交付维度逐条带 ownerModule 与 impact', () => {
    const missing = unavailableDimensions(CAPS);
    expect(missing).toHaveLength(1);
    expect(missing[0]!.dimension).toBe('platform_execution');
    expect(missing[0]!.ownerModule).toBe('02 平台适配器');
    expect(missing[0]!.impact).not.toBe('');
  });

  it('空态文案说清下一步动作', () => {
    expect(emptyWritebacksMessage()).toContain('创建内容回写');
  });
});
