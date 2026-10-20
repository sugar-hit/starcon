import { describe, expect, it } from 'vitest';
import {
  SCORE_GRADES,
  TIMELINESS_STATUSES,
  deviationText,
  errorText,
  hoursText,
  pageText,
  pivotCell,
  pivotMatrix,
  rateText,
  recentWindow,
  scoreGradeLabel,
  scoreText,
  shareText,
  timelinessStatusLabel,
  windowValid,
  type TimelinessMatrixRow,
} from './timeliness-model';

/**
 * 10.6.2.1 时效分析的前端模型断言。
 *
 * <p>🔴 本文件最要紧的两条：
 *
 * <ol>
 *   <li><b>不可比不是 0</b>：{@link rateText} / {@link scoreText} / {@link hoursText}
 *       在值为 null 时**不许**显示 0 —— 0 是「比过了，确实最差」，
 *       null 是「压根没资格比」。</li>
 *   <li><b>交叉维度不能被压扁</b>：{@link pivotMatrix} 必须把「渠道 × 国家」摆成
 *       真正的矩阵，而且**没有数据的格子不补 0**（补 0 会让「这个组合没有货」
 *       看起来像「这个组合超时率 0%」）。</li>
 * </ol>
 *
 * <p>蓝图加粗句「时效数据要按渠道和国家交叉分析，总体平均会掩盖某些渠道在某些国家的
 * 系统性延误」在浏览器里最容易退化的地方正是这两条。
 */
function row(
  channelCode: string,
  countryCode: string,
  overdueRate: number | null,
  score: number | null = null,
  sampleSufficient = true,
): TimelinessMatrixRow {
  return {
    channelId: `${channelCode}-id`,
    channelCode,
    channelName: `${channelCode} 名称`,
    countryCode,
    shippedCount: 10,
    deliveredCount: 10,
    comparableCount: 10,
    onTimeCount: 6,
    overdueCount: 4,
    noPromiseCount: 0,
    pendingCount: 0,
    returnedCount: 0,
    deliveryRate: 1,
    onTimeRate: 0.6,
    overdueRate,
    avgTransitHours: 80.8,
    avgDeviationHours: 16.8,
    stddevTransitHours: 37.18,
    p90TransitHours: 124,
    maxOverdueHours: 60,
    score,
    scoreGrade: sampleSufficient ? 'FAIR' : 'INSUFFICIENT',
    sampleSufficient,
  };
}

describe('10.6.2.1 不可比不是 0', () => {
  it('比率是 null ⇒ 显示「不可比」，绝不显示 0.00%', () => {
    expect(rateText(null)).toBe('不可比');
    expect(rateText(undefined)).toBe('不可比');
    expect(rateText(null)).not.toContain('0.00%');
  });

  it('有值时按百分比展示，scale 固定两位', () => {
    expect(rateText(0.6)).toBe('60.00%');
    expect(rateText(0)).toBe('0.00%');
    expect(rateText(1)).toBe('100.00%');
  });

  it('评分是 null ⇒ 显示「不可比」，绝不显示 0.00 分', () => {
    expect(scoreText(null)).toBe('不可比');
    expect(scoreText(null)).not.toContain('0.00');
    expect(scoreText(66)).toBe('66.00');
  });

  it('时长是 null ⇒ 显示「—」，不是 0 小时', () => {
    expect(hoursText(null)).toBe('—');
    expect(hoursText(80.8)).toBe('80.80 小时');
  });

  it('贡献率是 null ⇒ 「不可比」', () => {
    expect(shareText(null)).toBe('不可比');
    expect(shareText(0.4444)).toBe('44.44%');
  });
});

describe('10.6.2.1 时效偏差带符号', () => {
  it('正数（晚于承诺）显式带 + 号', () => {
    expect(deviationText(16.8)).toBe('+16.80 小时');
  });

  it('负数（早于承诺）保留负号', () => {
    expect(deviationText(-12)).toBe('-12.00 小时');
  });

  it('null ⇒ 「—」', () => {
    expect(deviationText(null)).toBe('—');
  });
});

describe('10.6.2.1 交叉矩阵不能被压扁', () => {
  const rows = [row('CH-A', 'US', 0.4), row('CH-A', 'DE', 1), row('CH-B', 'US', 0.1111)];

  it('行是渠道、列是国家', () => {
    const pivot = pivotMatrix(rows);
    expect(pivot.channels).toEqual(['CH-A', 'CH-B']);
    expect(pivot.countries).toEqual(['DE', 'US']);
    expect(pivot.observed).toBe(3);
  });

  it('一格 = 一个「渠道 × 国家」', () => {
    const pivot = pivotMatrix(rows);
    expect(pivotCell(pivot, 'CH-A', 'DE')?.overdueRate).toBe(1);
    expect(pivotCell(pivot, 'CH-A', 'US')?.overdueRate).toBe(0.4);
    expect(pivotCell(pivot, 'CH-B', 'US')?.overdueRate).toBe(0.1111);
  });

  it('🔴 没有数据的「渠道 × 国家」组合是 undefined，不是 0 值格', () => {
    const pivot = pivotMatrix(rows);
    expect(pivotCell(pivot, 'CH-B', 'DE')).toBeUndefined();
    expect(pivotCell(pivot, 'CH-A', 'FR')).toBeUndefined();
  });

  it('🔴 坐标轴只含观察到的渠道与国家（不凭空补出空组合）', () => {
    const pivot = pivotMatrix(rows);
    expect(pivot.countries).not.toContain('FR');
    expect(pivot.channels).not.toContain('CH-C');
  });

  it('空表 ⇒ 空矩阵，不是全 0 的矩阵', () => {
    const pivot = pivotMatrix([]);
    expect(pivot.channels).toEqual([]);
    expect(pivot.countries).toEqual([]);
    expect(pivot.observed).toBe(0);
  });
});

describe('10.6.2.1 词表与文案', () => {
  it('时效状态与评分等级的展示顺序固定', () => {
    expect(TIMELINESS_STATUSES[0]).toBe('OVERDUE');
    expect(SCORE_GRADES).toContain('INSUFFICIENT');
  });

  it('状态与等级都有中文文案', () => {
    expect(timelinessStatusLabel('OVERDUE')).toBe('超时送达');
    expect(timelinessStatusLabel('NO_PROMISE')).toBe('无承诺可比');
    expect(timelinessStatusLabel('IN_TRANSIT')).toBe('在途未签收');
    expect(timelinessStatusLabel('ON_TIME')).toBe('准点送达');
    expect(scoreGradeLabel('EXCELLENT')).toBe('优秀');
    expect(scoreGradeLabel('INSUFFICIENT')).toBe('样本不足');
  });

  it('未知取值给兜底文案而不是空白', () => {
    expect(timelinessStatusLabel('WHAT')).toBe('未知状态');
    expect(scoreGradeLabel(null)).toBe('未评级');
  });
});

describe('10.6.2.1 窗口与分页', () => {
  it('最近 N 天给出闭区间日期（UTC 自然日）', () => {
    const window = recentWindow(90, new Date('2026-09-30T10:00:00Z'));
    expect(window.to).toBe('2026-09-30');
    expect(window.from).toBe('2026-07-03');
  });

  it('窗口起止倒置时判为非法', () => {
    expect(windowValid('2026-09-30', '2026-09-01')).toBe(false);
    expect(windowValid('2026-09-01', '2026-09-30')).toBe(true);
    expect(windowValid('', '')).toBe(true);
  });

  it('分页文案在空集合上不越界', () => {
    expect(pageText(0, 50, 0)).toBe('共 0 条');
    expect(pageText(0, 50, 120)).toBe('第 1-50 条 / 共 120 条');
    expect(pageText(2, 50, 120)).toBe('第 101-120 条 / 共 120 条');
  });
});

describe('10.6.2.1 错误文案', () => {
  it('未知错误有兜底文案', () => {
    expect(errorText(new Error('boom'))).toBe('加载失败，请稍后重试');
  });
});
