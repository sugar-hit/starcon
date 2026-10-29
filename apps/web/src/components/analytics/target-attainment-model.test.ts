import { describe, expect, it } from 'vitest';
import {
  actualStatusLabel,
  actualStatusTone,
  alertKindLabel,
  alertLevelLabel,
  alertTone,
  allRows,
  amountText,
  asOfText,
  bucketAttainmentText,
  bucketPeriodText,
  capabilityRules,
  emptyText,
  evaluatedAtText,
  notDeliveredLines,
  pageState,
  progressText,
  rateText,
  rankEntryText,
  rankText,
  scopeLabel,
  statusLabel,
  statusTone,
  summaryText,
  underAchievedRows,
  unavailableRows,
  upstreamModules,
} from './target-attainment-model';
import type {
  AttainmentBoard,
  AttainmentBucket,
  AttainmentCapabilities,
  AttainmentGroup,
  AttainmentRankEntry,
  AttainmentRow,
  AttainmentSnapshot,
  AttainmentSummary,
} from './target-attainment-model';

/**
 * 13.1.2.2 达成追踪 前端模型单测。
 *
 * <p>测的是**这一页最容易做丢的四件事**：
 * 「算不出来」≠「等于 0」、「没达标」≠「还没法判定」、
 * 概览里「算不出来」的那几类单独计数（不并进未达标）、前端不做业务算术。
 */

function row(overrides: Partial<AttainmentRow> = {}): AttainmentRow {
  return {
    targetId: '11111111-1111-1111-1111-111111111111',
    scopeType: 'SHOP',
    ownerId: 'SHOP-A',
    ownerCode: 'SHOP-A',
    ownerName: '店铺甲',
    metricCode: 'STORE_GMV_PLATFORM',
    metricDisplayName: '平台侧成交金额',
    unit: 'CURRENCY',
    currency: 'USD',
    periodStart: '2026-01-01',
    periodEnd: '2026-01-31',
    targetValue: 1000,
    currentValue: 500,
    attainmentRate: 0.5,
    forecastValue: 800,
    forecastRate: 0.8,
    elapsedRatio: 0.625,
    remainingDays: 12,
    actualStatus: 'AVAILABLE',
    status: 'ON_TRACK',
    statusNote: '按当前速度推算，期末可达 0.8。',
    underAchieved: false,
    rank: 1,
    rankTotal: 2,
    rankGroup: 'SHOP|STORE_GMV_PLATFORM|USD|2026-01-01|2026-01-31',
    buckets: [],
    ...overrides,
  };
}

function group(rows: AttainmentRow[]): AttainmentGroup {
  const first = rows[0] as AttainmentRow;
  return {
    scopeType: first.scopeType,
    metricCode: first.metricCode,
    currency: first.currency,
    periodStart: first.periodStart,
    periodEnd: first.periodEnd,
    groupKey: first.rankGroup,
    total: rows.length,
    available: rows.filter((r) => r.attainmentRate !== null).length,
    unavailable: rows.filter((r) => r.attainmentRate === null).length,
    rows,
  };
}

function summary(overrides: Partial<AttainmentSummary> = {}): AttainmentSummary {
  return {
    total: 4,
    achieved: 1,
    onTrack: 1,
    atRisk: 1,
    behind: 1,
    notStarted: 0,
    notComparable: 0,
    unavailable: 0,
    underAchieved: 2,
    ...overrides,
  };
}

function board(overrides: Partial<AttainmentBoard> = {}): AttainmentBoard {
  return {
    asOfDate: '2026-01-20',
    timezone: 'UTC',
    timezoneSource: 'PROJECTION',
    timezoneNote: '',
    evaluatedAt: '2026-01-20T03:00:00Z',
    page: 0,
    size: 50,
    total: 1,
    groups: [group([row()])],
    alerts: [],
    summary: summary(),
    rankNote: '排名位次在全量上计算。',
    note: '绝不跨币种相加。',
    ...overrides,
  };
}

function capabilities(overrides: Partial<AttainmentCapabilities> = {}): AttainmentCapabilities {
  return {
    upstreamConnected: true,
    boardAvailable: true,
    upstreamModules: ['13.1.2.1 目标设定（本服务 reporting_target）'],
    notDelivered: ['🔴 部门 / 人员维度的实际值不可得。'],
    rules: [{ ruleCode: 'STATUS', description: 'ACHIEVED 达成率 ≥ 100%。' }],
    ...overrides,
  };
}

describe('「算不出来」≠「等于 0」', () => {
  it('达成率为 null 时显示「不可得」，绝不显示 0.00%', () => {
    expect(rateText(null)).toBe('不可得');
    expect(rateText(null)).not.toContain('0');
    expect(rateText(0)).toBe('0.00%');
  });

  it('当前值为 null 时显示「不可得」，绝不显示 0', () => {
    expect(amountText(null, 'USD')).toBe('不可得');
    expect(amountText(null, 'USD')).not.toBe('0.00 USD');
    expect(amountText(0, 'USD')).toBe('0.00 USD');
  });

  it('时间进度为 null 时也显示「不可得」', () => {
    expect(progressText(null)).toBe('不可得');
    expect(progressText(0.5)).toBe('50.00%');
  });

  it('「取不到」与「取到但是 0」用不同措辞', () => {
    expect(actualStatusLabel('AVAILABLE')).toBe('已取到实际值');
    expect(actualStatusLabel('NO_DATA')).toBe('还没有实际值事实');
    expect(actualStatusLabel('NOT_AVAILABLE')).toBe('该维度没有实际值来源');
    expect(actualStatusTone('AVAILABLE')).toBe('success');
    expect(actualStatusTone('NO_DATA')).toBe('warning');
    expect(actualStatusTone('NOT_AVAILABLE')).toBe('neutral');
  });
});

describe('「没达标」≠「还没法判定」', () => {
  it('未达标只认服务端判定的 underAchieved', () => {
    const behind = row({ underAchieved: true, status: 'BEHIND' });
    const notStarted = row({ underAchieved: false, status: 'NOT_STARTED', attainmentRate: null });
    const unavailable = row({ underAchieved: false, status: 'UNAVAILABLE', attainmentRate: null });
    const ok = row({ underAchieved: false, status: 'ACHIEVED' });

    const rows = underAchievedRows([behind, notStarted, unavailable, ok]);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.status).toBe('BEHIND');
  });

  it('算不出达成率的行单独归类', () => {
    const rows = [
      row({ attainmentRate: 0.5 }),
      row({ targetId: '2', attainmentRate: null, status: 'UNAVAILABLE' }),
    ];
    expect(unavailableRows(rows)).toHaveLength(1);
    expect(allRows(board({ groups: [group(rows)] }))).toHaveLength(2);
  });

  it('三种「算不出来」的状态各有各的标签', () => {
    expect(statusLabel('NOT_STARTED')).toBe('周期尚未开始');
    expect(statusLabel('NOT_COMPARABLE')).toBe('不可比（目标值不是正数）');
    expect(statusLabel('UNAVAILABLE')).toBe('实际值不可得');
    expect(statusTone('NOT_STARTED')).toBe('neutral');
    expect(statusTone('AT_RISK')).toBe('warning');
    expect(statusTone('BEHIND')).toBe('danger');
    expect(statusTone('ACHIEVED')).toBe('success');
  });
});

describe('概览计数', () => {
  it('「算不出来」的那几类单独计数，不并进未达标', () => {
    const text = summaryText(summary({ total: 6, behind: 1, notStarted: 1, unavailable: 1 }));
    expect(text).toContain('目标 6');
    expect(text).toContain('周期尚未开始 1');
    expect(text).toContain('实际值不可得 1');
    expect(text).toContain('未达标合计 2');
  });

  it('概览为 null 时不编数字', () => {
    expect(summaryText(null)).toBe('—');
  });
});

describe('排名', () => {
  it('未参与排名的显示「未参与排名」，不给它一个名次', () => {
    expect(rankText(0, 3)).toBe('未参与排名');
    expect(rankText(1, 3)).toBe('第 1 / 3 名');
  });

  it('排名条目一行文本带状态与两个率', () => {
    const entry: AttainmentRankEntry = {
      rank: 2,
      rankTotal: 4,
      groupKey: 'SHOP|X|USD|2026-01-01|2026-01-31',
      scopeType: 'SHOP',
      ownerId: 'SHOP-A',
      ownerCode: 'SHOP-A',
      ownerName: '店铺甲',
      metricCode: 'STORE_GMV_PLATFORM',
      currency: 'USD',
      targetValue: 1000,
      currentValue: 500,
      attainmentRate: 0.5,
      forecastRate: 0.8,
      status: 'ON_TRACK',
    };
    const text = rankEntryText(entry);
    expect(text).toContain('店铺甲');
    expect(text).toContain('50.00%');
    expect(text).toContain('80.00%');
  });
});

describe('预警', () => {
  it('级别与类别各有标签', () => {
    expect(alertLevelLabel('CRITICAL')).toBe('严重');
    expect(alertLevelLabel('WARNING')).toBe('提醒');
    expect(alertTone('CRITICAL')).toBe('danger');
    expect(alertTone('WARNING')).toBe('warning');
    expect(alertKindLabel('FORECAST_BELOW_TARGET')).toBe('按当前速度达不到');
    expect(alertKindLabel('NO_ACTUALS')).toBe('算不出达成率');
    expect(alertKindLabel('TARGET_VALUE_INVALID')).toBe('目标值不可比');
  });
});

describe('页面状态三态齐备', () => {
  it('加载态 / 错误态 / 空态 / 就绪', () => {
    expect(pageState(null, '', true)).toBe('loading');
    expect(pageState(null, 'boom', false)).toBe('error');
    expect(pageState(board({ total: 0, groups: [] }), '', false)).toBe('empty');
    expect(pageState(board(), '', false)).toBe('ready');
  });

  it('「没有目标」与「目标算不出达成率」分开说', () => {
    expect(emptyText(board({ total: 0, groups: [] }))).toContain('没有目标');
    expect(emptyText(board())).toContain('有目标');
    expect(emptyText(null)).toContain('还没有读取到');
  });

  it('评估日与时区必须显式给出', () => {
    expect(asOfText(board())).toContain('2026-01-20');
    expect(asOfText(board())).toContain('UTC');
    expect(asOfText(board({ timezoneSource: 'FALLBACK_UTC' }))).toContain('暂按 UTC');
    expect(evaluatedAtText(board())).toContain('2026-01-20T03:00:00Z');
  });
});

describe('分解计划与能力声明', () => {
  it('分解项文本带目标、当前、达成率与预测', () => {
    const bucket: AttainmentBucket = {
      bucketStart: '2026-01-01',
      bucketEnd: '2026-01-31',
      granularity: 'MONTHLY',
      currency: 'USD',
      targetValue: 1000,
      currentValue: 250,
      attainmentRate: 0.25,
      elapsedRatio: 1,
      forecastValue: 250,
      forecastRate: 0.25,
      actualStatus: 'AVAILABLE',
      status: 'BEHIND',
      note: '按当前速度推算，期末只能到 0.25。',
    };
    expect(bucketPeriodText(bucket)).toBe('2026-01-01 ~ 2026-01-31');
    const text = bucketAttainmentText(bucket);
    expect(text).toContain('1,000.00 USD');
    expect(text).toContain('250.00 USD');
    expect(text).toContain('25.00%');
  });

  it('能力声明：拿不到的部分与判定规则都原样给出', () => {
    expect(notDeliveredLines(capabilities())).toHaveLength(1);
    expect(capabilityRules(capabilities())[0]).toContain('STATUS');
    expect(upstreamModules(capabilities())).toHaveLength(1);
    expect(notDeliveredLines(null)).toEqual([]);
  });

  it('归属维度标签', () => {
    expect(scopeLabel('DEPARTMENT')).toBe('部门');
    expect(scopeLabel('MEMBER')).toBe('人员');
    expect(scopeLabel('SHOP')).toBe('店铺');
    expect(scopeLabel('PRODUCT')).toBe('产品');
  });
});

describe('快照', () => {
  it('快照类型可被赋值（结构与契约一致）', () => {
    const snapshot: AttainmentSnapshot = {
      id: '11111111-1111-1111-1111-111111111111',
      targetId: '22222222-2222-2222-2222-222222222222',
      evaluatedOn: '2026-01-20',
      scopeType: 'SHOP',
      ownerId: 'SHOP-A',
      ownerCode: 'SHOP-A',
      ownerName: '店铺甲',
      metricCode: 'STORE_GMV_PLATFORM',
      currency: 'USD',
      periodStart: '2026-01-01',
      periodEnd: '2026-01-31',
      targetValue: 1000,
      currentValue: null,
      attainmentRate: null,
      forecastValue: null,
      forecastRate: null,
      elapsedRatio: 0.625,
      actualStatus: 'NO_DATA',
      status: 'UNAVAILABLE',
      actualNote: '还没有任何实际值事实。',
      forecastNote: '预测算不出来。',
      rank: 0,
      rankTotal: 0,
      rankGroup: 'SHOP|STORE_GMV_PLATFORM|USD|2026-01-01|2026-01-31',
      evaluatedAt: '2026-01-20T03:00:00Z',
    };
    expect(snapshot.attainmentRate).toBeNull();
    expect(snapshot.currentValue).toBeNull();
  });
});
