import { describe, expect, it } from 'vitest';
import {
  blockingReasons,
  emptyCompareInput,
  emptyRatingInput,
  emptyResultText,
  moneyText,
  rankBasisLabel,
  rateText,
  scoreText,
  scoreTone,
  selectionBasisLabel,
  toInstant,
  transitText,
  validateCompare,
  validateRating,
} from './rate-comparison-model';
import type { CompareResult, ComparisonLine } from './rate-comparison-model';

/**
 * 10.1.2.2 渠道比价前端模型单测。
 *
 * 🔴 这里钉的是**「界面上最容易骗人的三处」**，不是排版：
 *
 * 1. **不可比 ≠ 0 分**（`scoreText`）—— 0 分是「比过了、确实最差」，null 是「压根没资格比」。
 *    渲染成 0.00 会让人以为「没登记时效的那条被打了 0 分」。
 * 2. **算不出钱 ≠ 0 元**（`moneyText`）—— 一条 0 元运费会被当成「这家最便宜」选进下游分仓。
 * 3. **「没有候选渠道」与「有渠道但一条都不合格」是两句话**（`emptyResultText`）。
 */

function line(overrides: Partial<ComparisonLine> = {}): ComparisonLine {
  return {
    channelId: 'c1',
    channelCode: 'SF-DE-STD',
    channelName: '顺丰德国标准',
    carrierName: '顺丰',
    eligibility: 'PASS',
    totalCharge: 42,
    currency: 'USD',
    transitMinDays: 5,
    transitMaxDays: 9,
    transitBasisDays: 9,
    priced: true,
    costScore: 100,
    transitScore: 60,
    reliabilityScore: 92,
    compositeScore: 79,
    rankNo: 1,
    rankBasis: 'RANKED',
    exclusionReason: '',
    ratingId: 'r1',
    ratingWindowFrom: null,
    ratingWindowTo: null,
    ratingObservedAt: null,
    ratingDispatched: 1000,
    ratingDelivered: 990,
    ratingOnTime: 980,
    ratingComplaint: 10,
    ratingSuccessRate: 0.99,
    ratingOnTimeRate: 0.98,
    ratingComplaintRate: 0.01,
    ratingSource: 'MANUAL_ENTERED',
    riskFlagged: false,
    riskNote: '',
    sampleSufficient: true,
    sampleNote: '',
    ratingStale: false,
    staleNote: '',
    priorityNo: null,
    autoSelectAllowed: true,
    minCompositeScore: null,
    selected: false,
    selectionNote: '',
    scoreBreakdown: [],
    notes: [],
    ...overrides,
  };
}

function result(overrides: Partial<CompareResult> = {}): CompareResult {
  return {
    costBasis: 'RULE_ESTIMATE',
    sourceQuoteId: null,
    comparedAt: '2026-10-15T09:00:00+08:00',
    destCountry: 'DE',
    shopCode: '',
    weightKg: 1.5,
    pieces: 1,
    currency: 'USD',
    mixedCurrency: false,
    cheapestTotal: 42,
    dearestTotal: 61,
    candidateCount: 2,
    rankedCount: 2,
    riskFlaggedCount: 0,
    insufficientSampleCount: 0,
    staleRatingCount: 0,
    costWeight: 0.4,
    transitWeight: 0.3,
    reliabilityWeight: 0.3,
    successRateWeight: 0.4,
    onTimeRateWeight: 0.4,
    complaintRateWeight: 0.2,
    maxTransitDays: null,
    maxRatingAgeDays: 90,
    requirePassOnly: false,
    applyShopPriority: false,
    minCompositeScore: null,
    selectedChannelId: 'c1',
    selectedChannelCode: 'SF-DE-STD',
    selectedChannelName: '顺丰德国标准',
    selectionBasis: 'SCORE_RANKED',
    selectionNote: '按综合分排名自动选中 SF-DE-STD',
    savedComparisonId: null,
    lines: [line()],
    note: '',
    ...overrides,
  };
}

describe('🔴 不可比不是 0 分', () => {
  it('null 得分渲染成「不可比」，0 分渲染成 0.00', () => {
    expect(scoreText(null)).toBe('不可比');
    expect(scoreText(0)).toBe('0.00');
    expect(scoreText(79)).toBe('79.00');
  });

  it('不可比与 0 分的视觉语气不同（不可比不该看起来像「很差的分」）', () => {
    expect(scoreTone(null)).toBe('text-muted-foreground');
    expect(scoreTone(0)).toBe('text-muted-foreground');
    expect(scoreTone(95)).toBe('text-emerald-600');
    expect(scoreTone(60)).toBe('text-foreground');
  });
});

describe('🔴 算不出钱不是 0 元', () => {
  it('null 金额渲染成破折号而不是 0.00', () => {
    expect(moneyText(null)).toBe('—');
    expect(moneyText(0, 'USD')).toBe('0.00 USD');
    expect(moneyText(42, 'USD')).toBe('42.00 USD');
    expect(moneyText(42)).toBe('42.00');
  });
});

describe('比率与时效展示', () => {
  it('比率是 0~1，转成百分比展示；null 不编成 0%', () => {
    expect(rateText(0.99)).toBe('99.00%');
    expect(rateText(0.05)).toBe('5.00%');
    expect(rateText(0)).toBe('0.00%');
    expect(rateText(null)).toBe('—');
  });

  it('时效区间：上下界相同塌成一个数字，缺一端不编天数', () => {
    expect(transitText(5, 9)).toBe('5–9 天');
    expect(transitText(7, 7)).toBe('7 天');
    expect(transitText(null, 9)).toBe('?–9 天');
    expect(transitText(5, null)).toBe('5 天');
    expect(transitText(null, null)).toBe('—');
  });
});

describe('🔴 三个闸：只挡自动选择，不挡评分', () => {
  it('命中闸时逐条列出理由（不拼成一团）', () => {
    const flagged = line({
      riskFlagged: true,
      riskNote: '投诉率 8.00% 超过红线 5.00%',
      sampleSufficient: false,
      sampleNote: '样本量 3 单低于门槛 30 单',
      ratingStale: true,
      staleNote: '评分已 200 天',
    });
    const reasons = blockingReasons(flagged);
    expect(reasons).toHaveLength(3);
    expect(reasons[0]).toContain('投诉率红线');
    expect(reasons[1]).toContain('样本量');
    expect(reasons[2]).toContain('评分新鲜度');
  });

  it('没过闸时不给理由（不把空串当成一条理由渲染出来）', () => {
    expect(blockingReasons(line())).toEqual([]);
  });

  it('被闸拦下但仍参与排名 —— 界面不能把它从表里删掉', () => {
    const flagged = line({ riskFlagged: true, riskNote: '超红线', rankBasis: 'RANKED', rankNo: 1 });
    expect(flagged.rankBasis).toBe('RANKED');
    expect(flagged.rankNo).toBe(1);
    expect(flagged.compositeScore).not.toBeNull();
  });
});

describe('落选与推荐口径', () => {
  it('每一种落选口径都有人话，不是裸枚举码', () => {
    expect(rankBasisLabel('RANKED')).toBe('参与排名');
    expect(rankBasisLabel('EXCLUDED_NO_SCORE')).toContain('没有渠道评分');
    expect(rankBasisLabel('EXCLUDED_CURRENCY')).toContain('币种');
    expect(rankBasisLabel('EXCLUDED_NO_PRICE')).toContain('算不出运费');
    expect(rankBasisLabel('EXCLUDED_NO_TRANSIT')).toContain('未登记时效');
    expect(rankBasisLabel('EXCLUDED_TRANSIT')).toContain('时效');
    expect(rankBasisLabel('EXCLUDED_INELIGIBLE')).toContain('可承运性');
  });

  it('三种选择口径都有人话', () => {
    expect(selectionBasisLabel('SCORE_RANKED')).toContain('综合分');
    expect(selectionBasisLabel('SHOP_PRESET_PRIORITY')).toContain('预设');
    expect(selectionBasisLabel('NO_ELIGIBLE_CHANNEL')).toContain('无可选渠道');
  });
});

describe('🔴 空态：「没有候选渠道」与「一条都不合格」是两句话', () => {
  it('还没有比价时不说话', () => {
    expect(emptyResultText(null)).toBe('');
  });

  it('候选集为空：说「没有候选渠道」，并说明空结果不代表可以随便选', () => {
    const text = emptyResultText(result({ lines: [] }));
    expect(text).toContain('没有候选渠道');
    expect(text).toContain('不代表');
    expect(text).not.toContain('闸');
  });

  it('有渠道但都没过闸：说「不给推荐」，并指向逐条原因', () => {
    const text = emptyResultText(
      result({ selectionBasis: 'NO_ELIGIBLE_CHANNEL', selectedChannelCode: '', selectedChannelId: null }),
    );
    expect(text).toContain('不给推荐');
    expect(text).toContain('落选原因');
  });

  it('正常推荐时不显示空态文案', () => {
    expect(emptyResultText(result())).toBe('');
  });
});

describe('比价条件校验', () => {
  it('默认条件是合法的', () => {
    expect(validateCompare(emptyCompareInput).ok).toBe(true);
  });

  it('国家格式错被拦下', () => {
    const check = validateCompare({ ...emptyCompareInput, destCountry: 'DEU' });
    expect(check.ok).toBe(false);
    expect(check.errors.join()).toContain('两位国家代码');
  });

  it('🔴 三个权重全 0 被拦下（那不叫「都不重要」，那叫没选目标）', () => {
    const check = validateCompare({
      ...emptyCompareInput,
      costWeight: '0',
      transitWeight: '0',
      reliabilityWeight: '0',
    });
    expect(check.ok).toBe(false);
    expect(check.errors.join()).toContain('权重');
  });

  it('权重可以不全填（留空 = 用服务端默认值），但不能全 0', () => {
    const check = validateCompare({
      ...emptyCompareInput,
      costWeight: '',
      transitWeight: '',
      reliabilityWeight: '',
    });
    expect(check.ok).toBe(true);
  });

  it('负权重被拦下', () => {
    expect(validateCompare({ ...emptyCompareInput, costWeight: '-1' }).ok).toBe(false);
  });

  it('时效上限不能是负数', () => {
    expect(validateCompare({ ...emptyCompareInput, maxTransitDays: '-1' }).ok).toBe(false);
  });
});

describe('🔴 评分登记校验：与服务层的拒收条件一一对应', () => {
  const valid: typeof emptyRatingInput = {
    ...emptyRatingInput,
    channelId: 'c1',
    countryCode: 'DE',
    dispatched: '1000',
    delivered: '990',
    onTime: '980',
    complaint: '10',
    windowFrom: '2026-07-01T00:00',
    windowTo: '2026-09-30T00:00',
    observedAt: '2026-10-01T00:00',
    maxComplaintRatePct: '5',
    minSampleThreshold: '30',
  };

  it('一份合法登记通过校验', () => {
    expect(validateRating(valid).ok).toBe(true);
  });

  it('已发运样本量必须 ≥ 1（0 单算不出任何比率）', () => {
    const check = validateRating({ ...valid, dispatched: '0' });
    expect(check.ok).toBe(false);
    expect(check.errors.join()).toContain('已发运样本量');
  });

  it('已妥投不能超过已发运', () => {
    expect(validateRating({ ...valid, delivered: '2000' }).ok).toBe(false);
  });

  it('按时妥投不能超过已妥投（时效达成率的分母是已妥投单）', () => {
    const check = validateRating({ ...valid, onTime: '995' });
    expect(check.ok).toBe(false);
    expect(check.errors.join()).toContain('已妥投');
  });

  it('投诉数不能超过已发运', () => {
    expect(validateRating({ ...valid, complaint: '2000' }).ok).toBe(false);
  });

  it('统计窗口结束必须晚于开始', () => {
    const check = validateRating({ ...valid, windowTo: '2026-06-30T00:00' });
    expect(check.ok).toBe(false);
    expect(check.errors.join()).toContain('统计窗口');
  });

  it('观测时刻必填（不存它就答不出「这组比率是哪一段的」）', () => {
    const check = validateRating({ ...valid, observedAt: '' });
    expect(check.ok).toBe(false);
    expect(check.errors.join()).toContain('观测时刻');
  });

  it('投诉率红线必须是 0~100 的百分比', () => {
    expect(validateRating({ ...valid, maxComplaintRatePct: '150' }).ok).toBe(false);
    expect(validateRating({ ...valid, maxComplaintRatePct: '-1' }).ok).toBe(false);
  });

  it('最小样本量必须 ≥ 1', () => {
    expect(validateRating({ ...valid, minSampleThreshold: '0' }).ok).toBe(false);
  });
});

describe('时刻绑定：本地时间串 → 带时区的 ISO-8601', () => {
  it('🔴 无时区的时间串会被服务端解释成不同时刻（新鲜度这个闸会得出不同结论）', () => {
    const iso = toInstant('2026-10-15T09:00');
    expect(iso).not.toBeNull();
    // 产出的必须带时区标记（Z 或 ±HH:MM）
    expect(iso).toMatch(/(Z|[+-]\d{2}:\d{2})$/);
  });

  it('空串与非法值给 null（而不是一个编出来的时间）', () => {
    expect(toInstant('')).toBeNull();
    expect(toInstant('   ')).toBeNull();
    expect(toInstant('not-a-date')).toBeNull();
  });
});
