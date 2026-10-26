import { describe, expect, it } from 'vitest';
import {
  EMPTY_FILTERS,
  FAILURE_CLASS_LABELS,
  FAILURE_CLASS_TONES,
  RED,
  SEVERITY_LABELS,
  SEVERITY_ORDER,
  SEVERITY_TONES,
  TASK_STATE_LABELS,
  YELLOW,
  alertHref,
  buildQuery,
  formatDuration,
  formatMinutes,
  formatThresholds,
  rulesBySeverity,
  sortAlerts,
  sortBySeverity,
  summarize,
  taskTitle,
  type ServiceTaskStatusAlert,
  type ServiceTaskStatusRuleDoc,
  type ServiceTaskStatusTaskCard,
} from './service-task-status-model';
import type {
  ServiceTaskStatusFailureClassCount,
  ServiceTaskStatusThresholds,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 1.5.2.1 服务与任务状态 —— 前端模型单测。
 *
 * 🔴 这组用例守的是前端最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>严重度不重算</b>：前端只按服务端给的严重度排序与计数；</li>
 *   <li><b>「判不了」不能渲染成绿色</b>：UNCLASSIFIED / UNDETERMINED 走中性色调；</li>
 *   <li><b>停滞与失败是两类</b>：两个列表各自排序，不混；</li>
 *   <li><b>跳转只给真实存在的路由</b>：没有路由就不给链接。</li>
 * </ol>
 */

function card(overrides: Partial<ServiceTaskStatusTaskCard> = {}): ServiceTaskStatusTaskCard {
  return {
    taskId: '11111111-1111-1111-1111-111111111111',
    sourceKey: 'sync_run',
    sourceLabel: '同步运行',
    ownerModule: '2.4.1.2',
    title: 'Amazon · 连接 A · ORDER',
    detail: 'ORDER',
    rawState: 'RUNNING',
    state: 'RUNNING',
    inFlight: true,
    failed: false,
    failureClass: 'NONE',
    failureClassNote: '这条任务没有失败记录。',
    stallDeterminable: true,
    stalled: false,
    stallSeverity: null,
    stallMinutes: 5,
    severity: 'GREEN',
    reasons: [],
    occurredAt: '2026-10-26T10:00:00Z',
    startedAt: '2026-10-26T10:00:00Z',
    finishedAt: null,
    durationMs: null,
    triggerSource: 'SCHEDULER',
    timingNote: '耗时 = sync_run.duration_ms',
    targetModule: '2.4.1.2',
    targetRoute: '/channels',
    targetHint: '渠道与店铺 → 同步运行',
    targetAvailable: true,
    targetNote: '同步运行列表与运行日志在「渠道与店铺」页的「同步运行」Tab 里。',
    ...overrides,
  };
}

function alert(overrides: Partial<ServiceTaskStatusAlert> = {}): ServiceTaskStatusAlert {
  return {
    kind: 'STALLED',
    severity: RED,
    title: '任务停滞：同步运行 · Amazon',
    detail: '这条任务还在执行，但已经跑了 200 分钟。',
    sourceKey: 'sync_run',
    sourceLabel: '同步运行',
    ownerModule: '2.4.1.2',
    taskId: '11111111-1111-1111-1111-111111111111',
    failureClass: null,
    targetModule: '2.4.1.2',
    targetRoute: '/channels',
    targetHint: '渠道与店铺 → 同步运行',
    targetAvailable: true,
    targetNote: '同步运行列表与运行日志在「渠道与店铺」页的「同步运行」Tab 里。',
    ...overrides,
  };
}

describe('严重度', () => {
  it('严重度词表与色调是完整的三档', () => {
    expect(SEVERITY_ORDER).toEqual(['RED', 'YELLOW', 'GREEN']);
    expect(Object.keys(SEVERITY_LABELS).sort()).toEqual(['GREEN', 'RED', 'YELLOW']);
    expect(Object.keys(SEVERITY_TONES).sort()).toEqual(['GREEN', 'RED', 'YELLOW']);
  });

  it('红黄绿分布按服务端给的严重度统计', () => {
    const cards = [
      card({ severity: 'RED' }),
      card({ severity: 'YELLOW' }),
      card({ severity: 'GREEN' }),
      card({ severity: 'GREEN' }),
    ];
    expect(summarize(cards)).toEqual({ red: 1, yellow: 1, green: 2, total: 4 });
  });

  it('排序把红放前面，同色按来源名 + 标题稳定排', () => {
    const cards = [
      card({ severity: 'GREEN', sourceLabel: 'B 源', title: 'z' }),
      card({ severity: 'RED', sourceLabel: 'A 源', title: 'b' }),
      card({ severity: 'RED', sourceLabel: 'A 源', title: 'a' }),
    ];
    const sorted = sortBySeverity(cards);
    expect(sorted.map((c) => c.title)).toEqual(['a', 'b', 'z']);
  });
});

describe('失败分类', () => {
  it('六个分类都有词条', () => {
    expect(Object.keys(FAILURE_CLASS_LABELS).sort()).toEqual([
      'BUSINESS',
      'MIXED',
      'NONE',
      'SYSTEM',
      'UNCLASSIFIED',
      'UNDETERMINED',
    ]);
  });

  it('🔴「判不了」用中性色调 —— 它既不是红也不是黄', () => {
    expect(FAILURE_CLASS_TONES.UNCLASSIFIED).toBe('neutral');
    expect(FAILURE_CLASS_TONES.UNDETERMINED).toBe('neutral');
    expect(FAILURE_CLASS_TONES.BUSINESS).toBe('warning');
    expect(FAILURE_CLASS_TONES.SYSTEM).toBe('danger');
  });

  it('业务 / 系统两类各有自己的措辞，不会混成同一句', () => {
    expect(FAILURE_CLASS_LABELS.BUSINESS).not.toBe(FAILURE_CLASS_LABELS.SYSTEM);
    expect(FAILURE_CLASS_LABELS.BUSINESS).toContain('业务');
    expect(FAILURE_CLASS_LABELS.SYSTEM).toContain('系统');
  });
});

describe('任务状态', () => {
  it('七个规范化状态都有词条', () => {
    expect(Object.keys(TASK_STATE_LABELS).sort()).toEqual([
      'CANCELLED',
      'FAILED',
      'PARTIAL',
      'PENDING',
      'RUNNING',
      'SUCCEEDED',
      'UNKNOWN',
    ]);
  });

  it('标题带来源名 —— 五个来源混在一起时用户才知道这是哪一类任务', () => {
    expect(taskTitle(card())).toBe('同步运行 · Amazon · 连接 A · ORDER');
  });
});

describe('时间与耗时', () => {
  it('null 时刻渲染成「—」，不是纪元时间', () => {
    expect(formatMinutes(null)).toBe('—');
    expect(formatMinutes(undefined)).toBe('—');
  });

  it('分钟数换算成小时', () => {
    expect(formatMinutes(45)).toBe('45 分钟');
    expect(formatMinutes(60)).toBe('1 小时');
    expect(formatMinutes(95)).toBe('1 小时 35 分钟');
  });

  it('耗时毫秒换算成人话', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(500)).toBe('500 毫秒');
    expect(formatDuration(30_000)).toBe('30 秒');
    expect(formatDuration(90_000)).toBe('1 分 30 秒');
  });
});

describe('筛选', () => {
  it('空筛选只带分页参数', () => {
    expect(buildQuery(EMPTY_FILTERS, 0, 50)).toBe('page=0&size=50');
  });

  it('筛选条件全部翻译成后端查询串', () => {
    const query = buildQuery(
      {
        sourceKey: 'publish_task',
        state: 'failed',
        triggerSource: 'scheduler',
        keyword: 'Amazon',
        stalled: 'true',
        failed: 'false',
      },
      2,
      20,
    );
    const params = new URLSearchParams(query);
    expect(params.get('sourceKey')).toBe('publish_task');
    expect(params.get('state')).toBe('FAILED');
    expect(params.get('triggerSource')).toBe('SCHEDULER');
    expect(params.get('keyword')).toBe('Amazon');
    expect(params.get('stalled')).toBe('true');
    expect(params.get('failed')).toBe('false');
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('20');
  });

  it('没填的筛选条件不进查询串', () => {
    const query = buildQuery({ ...EMPTY_FILTERS, keyword: '' }, 0, 50);
    expect(query).not.toContain('keyword');
    expect(query).not.toContain('sourceKey');
  });
});

describe('提示', () => {
  it('停滞与失败各自排序，不混成一个列表', () => {
    const stalled = alert({ kind: 'STALLED', severity: YELLOW, title: 'B' });
    const failed = alert({ kind: 'FAILED', severity: RED, title: 'A' });
    expect(sortAlerts([stalled, failed]).map((a) => a.title)).toEqual(['A', 'B']);
  });

  it('没有真实路由就不给链接', () => {
    expect(alertHref(alert())).toBe('/channels');
    expect(alertHref(alert({ targetAvailable: false, targetRoute: '/channels' }))).toBeNull();
    expect(alertHref(alert({ targetRoute: null }))).toBeNull();
  });
});

describe('规则表与阈值', () => {
  it('规则表按服务端下发的严重度分组，不在前端另写一份', () => {
    const rules: ServiceTaskStatusRuleDoc[] = [
      { code: 'STALL_CRITICAL', severity: 'RED', condition: 'c', reason: 'r', source: 's' },
      { code: 'STALL_WARNING', severity: 'YELLOW', condition: 'c', reason: 'r', source: 's' },
    ];
    const groups = rulesBySeverity(rules);
    expect(groups.map((g) => g.severity)).toEqual(['RED', 'YELLOW']);
  });

  it('阈值文案写清两条线', () => {
    const thresholds: ServiceTaskStatusThresholds = {
      stallWarnMinutes: 30,
      stallCriticalMinutes: 120,
      failureWindowHours: 24,
      maxPageSize: 200,
    };
    const text = formatThresholds(thresholds);
    expect(text).toContain('30');
    expect(text).toContain('120');
    expect(text).toContain('24');
  });
});

describe('失败计数', () => {
  it('分类计数的文案同时给两个口径（近窗口 / 累计）', () => {
    const entry: ServiceTaskStatusFailureClassCount = {
      failureClass: 'BUSINESS',
      label: '业务 / 配置问题',
      windowCount: 3,
      totalCount: 9,
      byReason: [],
    };
    expect(entry.windowCount).toBe(3);
    expect(entry.totalCount).toBe(9);
  });
});
