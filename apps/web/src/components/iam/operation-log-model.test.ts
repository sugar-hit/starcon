import { describe, expect, it } from 'vitest';
import type {
  OperationLogTrace,
  OperationLogView,
} from '../../../../../packages/api-contracts/src/index';
import {
  emptyFilters,
  eventTypeLabel,
  formatDuration,
  formatTime,
  levelLabel,
  operationLogExportUrl,
  operationLogQuery,
  shortRequestId,
  traceSteps,
} from './operation-log-model';

/**
 * 17.5.3.2 操作日志前端模型。
 *
 * 重点覆盖三处容易出错、且错了会让人误判排障结论的地方：
 * 筛选参数的拼装、耗时的呈现精度、链路为空时的呈现。
 */

function view(overrides: Partial<OperationLogView> = {}): OperationLogView {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    eventType: 'API_CALL',
    level: 'INFO',
    requestId: 'req-abc',
    traceId: null,
    actorId: null,
    actorName: null,
    summary: 'POST /identity/departments → 200',
    httpMethod: 'POST',
    path: '/identity/departments',
    statusCode: 200,
    durationMs: 12.5,
    taskName: null,
    taskStatus: null,
    errorClass: null,
    occurredAt: '2026-10-07T02:00:00Z',
    ...overrides,
  };
}

function trace(overrides: Partial<OperationLogTrace> = {}): OperationLogTrace {
  return {
    requestId: 'req-abc',
    entries: [view()],
    entryCount: 1,
    errorCount: 0,
    warningCount: 0,
    totalDurationMs: 12.5,
    spanMs: 12.5,
    firstAt: '2026-10-07T02:00:00Z',
    lastAt: '2026-10-07T02:00:00Z',
    ...overrides,
  };
}

describe('operationLogQuery', () => {
  it('只带非空筛选条件', () => {
    expect(operationLogQuery(emptyFilters, 0, 50)).toBe('page=0&size=50');
  });

  it('带上全部维度并做 URL 编码', () => {
    const query = operationLogQuery(
      {
        actorId: 'a b',
        eventType: 'API_CALL',
        level: 'ERROR',
        requestId: 'req/1',
        from: '2026-10-07T02:00:00Z',
        to: '2026-10-07T03:00:00Z',
      },
      2,
      20,
    );
    expect(query).toContain('page=2&size=20');
    expect(query).toContain('actorId=a%20b');
    expect(query).toContain('eventType=API_CALL');
    expect(query).toContain('level=ERROR');
    expect(query).toContain('requestId=req%2F1');
    // 时间转成 ISO-8601，后端据此按 timestamptz 检索
    expect(query).toContain(
      `from=${encodeURIComponent(new Date('2026-10-07T02:00:00Z').toISOString())}`,
    );
  });

  it('导出链接复用同一套条件', () => {
    expect(operationLogExportUrl(emptyFilters)).toBe(
      '/api/iam/identity/operation-log/export?page=0&size=1',
    );
  });
});

describe('标签', () => {
  it('事件类型与级别都有中文，未知值回落到原值而不是空白', () => {
    expect(eventTypeLabel('LOGIN')).toBe('登录');
    expect(eventTypeLabel('TASK_RUN')).toBe('任务执行');
    expect(eventTypeLabel('ERROR')).toBe('错误');
    expect(eventTypeLabel('WHATEVER')).toBe('WHATEVER');
    expect(levelLabel('WARNING')).toBe('告警');
    expect(levelLabel('FATAL')).toBe('FATAL');
  });
});

describe('formatDuration', () => {
  it('亚毫秒精度不被抹成 0', () => {
    // 排障要看抖动，整数毫秒会把偶发卡顿压成 0
    expect(formatDuration(0.125)).toBe('0.125 ms');
    expect(formatDuration(12.5)).toBe('12.5 ms');
  });

  it('超过 1 秒换算成秒', () => {
    expect(formatDuration(1500)).toBe('1.500 s');
  });

  it('未观测到耗时显示为 —，而不是 0', () => {
    // 0 与「没测到」是两件事：前者是真的零耗时，后者是没数据
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(undefined)).toBe('—');
    expect(formatDuration(0)).toBe('0 ms');
  });
});

describe('traceSteps', () => {
  it('按服务端返回的真实时序展开链路', () => {
    const steps = traceSteps(
      trace({
        entries: [
          view({ id: '1', eventType: 'LOGIN', summary: '登录成功', httpMethod: null }),
          view({ id: '2', eventType: 'API_CALL', summary: 'GET /identity/members → 200' }),
          view({ id: '3', eventType: 'ERROR', level: 'ERROR', summary: '处理失败' }),
        ],
        entryCount: 3,
        errorCount: 1,
      }),
    );
    expect(steps.map((s) => s.eventType)).toEqual(['LOGIN', 'API_CALL', 'ERROR']);
    expect(steps[0]?.eventLabel).toBe('登录');
    expect(steps[2]?.level).toBe('ERROR');
  });

  it('链路为空时返回空数组而不是抛错', () => {
    expect(
      traceSteps(trace({ entries: [], entryCount: 0, totalDurationMs: 0, spanMs: 0 })),
    ).toEqual([]);
  });
});

describe('shortRequestId', () => {
  it('短 ID 原样返回，长 ID 保留尾部（区分不同调用的正是尾部）', () => {
    expect(shortRequestId('req-1')).toBe('req-1');
    expect(shortRequestId('0123456789abcdefghij')).toBe('…89abcdefghij');
  });
});

describe('formatTime', () => {
  it('无法解析时原样返回，不显示 Invalid Date', () => {
    expect(formatTime('not-a-date')).toBe('not-a-date');
    expect(formatTime('2026-10-07T02:00:00Z')).not.toContain('Invalid');
  });
});
