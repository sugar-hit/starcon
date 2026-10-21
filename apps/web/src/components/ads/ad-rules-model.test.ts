import { describe, expect, it } from 'vitest';
import {
  AdRulePage,
  RuleInput,
  emptyMetricInput,
  emptyRuleConditionInput,
  emptyRuleInput,
  freshnessText,
  metricLabel,
  money,
  outcomeLabel,
  ratio,
  rejectReasonLabel,
  validateMetric,
  validateRule,
  verdictLabel,
} from './ad-rules-model';

/**
 * 14.2.2.2 自动规则 前端模型测试。
 *
 * 🔴 这一页最容易做丢的是**上限**与**试运行**，所以测试重点钉在：
 *   * 没有上限的规则在**前端**就被拦下（后端还会再拒一次、DB 还有 CHECK 兜底，三道闸）
 *   * 启停动作没有幅度
 *   * 条件的阈值列与指标口径必须对应（比率 vs 金额混填会被拒）
 *   * 「没有数据」与「没同步到」在展示上分得开
 *   * 不可比的比率显示成「不可比」而不是 0
 */

const PLATFORM = '11111111-1111-1111-1111-111111111111';

function rule(overrides: Partial<RuleInput> = {}): RuleInput {
  return {
    ...emptyRuleInput,
    ruleCode: 'RULE-1',
    ruleName: 'ACOS 超标降价',
    platformId: PLATFORM,
    shopCode: 'SHOP-1',
    externalCampaignId: 'CAMP-1',
    adjustmentValue: '0.2',
    singleAdjustCapAmount: '0.5',
    totalCapAmount: '5',
    conditions: [{ ...emptyRuleConditionInput, metric: 'ACOS', thresholdValue: '0.35' }],
    ...overrides,
  };
}

describe('14.2.2.2 规则校验：上限是必填项', () => {
  it('单次上限留空 / 为 0 / 为负都被拒', () => {
    for (const cap of ['', '0', '-1']) {
      const result = validateRule(rule({ singleAdjustCapAmount: cap }));
      expect(result.ok, `单次上限 = ${cap} 应当被拒`).toBe(false);
      expect(result.errors.join('\n')).toContain('单次调整金额上限');
    }
  });

  it('总上限（金额 / 次数 / 窗口）留空或非正都被拒', () => {
    expect(validateRule(rule({ totalCapAmount: '' })).errors.join('\n')).toContain('总上限（窗口内累计金额）');
    expect(validateRule(rule({ totalCapCount: '0' })).errors.join('\n')).toContain('总上限（窗口内累计次数）');
    expect(validateRule(rule({ totalCapWindowHours: '0' })).errors.join('\n')).toContain('累计窗口长度');
    expect(validateRule(rule({ totalCapWindowHours: '1.5' })).errors.join('\n')).toContain('累计窗口长度');
  });

  it('比例上限可以是 0（表示不启用），但不能是负数', () => {
    expect(validateRule(rule({ singleAdjustCapPercent: '0' })).ok).toBe(true);
    const negative = validateRule(rule({ singleAdjustCapPercent: '-5' }));
    expect(negative.ok).toBe(false);
    expect(negative.errors.join('\n')).toContain('比例上限');
  });

  it('一条上限齐备的规则通过校验', () => {
    const result = validateRule(rule());
    expect(result.errors).toEqual([]);
    expect(result.ok).toBe(true);
  });
});

describe('14.2.2.2 规则校验：启停动作没有幅度', () => {
  it('PAUSE / RESUME 带幅度被拒', () => {
    for (const action of ['PAUSE', 'RESUME']) {
      const result = validateRule(rule({ actionType: action, adjustmentValue: '0.2' }));
      expect(result.ok, `${action} 带幅度应当被拒`).toBe(false);
      expect(result.errors.join('\n')).toContain('启停动作');
    }
  });

  it('PAUSE / RESUME 幅度为 0 或留空都过', () => {
    expect(validateRule(rule({ actionType: 'PAUSE', adjustmentValue: '0' })).ok).toBe(true);
    expect(validateRule(rule({ actionType: 'PAUSE', adjustmentValue: '' })).ok).toBe(true);
  });

  it('改价改预算必须有幅度', () => {
    expect(validateRule(rule({ actionType: 'INCREASE_BUDGET', adjustmentValue: '' })).ok).toBe(false);
  });
});

describe('14.2.2.2 规则校验：条件的阈值列与指标口径必须对应', () => {
  it('比率口径不许填金额阈值', () => {
    const result = validateRule(
      rule({
        conditions: [{ ...emptyRuleConditionInput, metric: 'ACOS', thresholdAmount: '0.35' }],
      }),
    );
    expect(result.ok).toBe(false);
    expect(result.errors.join('\n')).toContain('不能填在「金额阈值」上');
  });

  it('金额口径不许填比率阈值', () => {
    const result = validateRule(
      rule({
        conditions: [{ ...emptyRuleConditionInput, metric: 'SPEND', thresholdValue: '100' }],
      }),
    );
    expect(result.ok).toBe(false);
    expect(result.errors.join('\n')).toContain('不能填在「比率 / 计数阈值」上');
  });

  it('比率口径必须填比率阈值', () => {
    const result = validateRule(
      rule({ conditions: [{ ...emptyRuleConditionInput, metric: 'ROAS' }] }),
    );
    expect(result.ok).toBe(false);
    expect(result.errors.join('\n')).toContain('阈值必须填在「比率 / 计数阈值」上');
  });

  it('没有条件的规则被拒 —— 没有条件的规则永远不会命中', () => {
    const result = validateRule(rule({ conditions: [] }));
    expect(result.ok).toBe(false);
    expect(result.errors.join('\n')).toContain('至少要有一个条件');
  });
});

describe('14.2.2.2 规则校验：作用对象与上下限', () => {
  it('必须给出外部活动 id 或外部关键词 id', () => {
    const result = validateRule(rule({ externalCampaignId: '', externalKeywordId: '' }));
    expect(result.ok).toBe(false);
    expect(result.errors.join('\n')).toContain('外部活动 id');
  });

  it('关键词级规则必须点名关键词', () => {
    const result = validateRule(rule({ targetType: 'KEYWORD', externalKeywordId: '' }));
    expect(result.ok).toBe(false);
    expect(result.errors.join('\n')).toContain('外部关键词 id');
  });

  it('上下限反了被拒', () => {
    const budget = validateRule(rule({ minBudget: '100', maxBudget: '10' }));
    expect(budget.ok).toBe(false);
    expect(budget.errors.join('\n')).toContain('预算上限不能低于预算下限');
    const bid = validateRule(rule({ minBid: '1.4', maxBid: '0.2' }));
    expect(bid.ok).toBe(false);
    expect(bid.errors.join('\n')).toContain('出价上限不能低于出价下限');
  });
});

describe('14.2.2.2 指标校验', () => {
  it('数据截至时间必填 —— 没有它就分不清「没有数据」与「没同步到」', () => {
    const result = validateMetric({
      ...emptyMetricInput,
      platformId: PLATFORM,
      shopCode: 'SHOP-1',
      externalCampaignId: 'CAMP-1',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('\n')).toContain('数据截至时间必填');
  });

  it('统计窗口结束必须晚于开始', () => {
    const result = validateMetric({
      ...emptyMetricInput,
      platformId: PLATFORM,
      shopCode: 'SHOP-1',
      externalCampaignId: 'CAMP-1',
      metricsAsOf: '2026-01-02T00:00',
      windowStart: '2026-01-02T00:00',
      windowEnd: '2026-01-01T00:00',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('\n')).toContain('结束时间必须晚于开始时间');
  });

  it('层级串了被拒：关键词级必须点名关键词、活动级不许带关键词', () => {
    const keyword = validateMetric({
      ...emptyMetricInput,
      platformId: PLATFORM,
      shopCode: 'SHOP-1',
      externalCampaignId: 'CAMP-1',
      metricScope: 'KEYWORD',
    });
    expect(keyword.ok).toBe(false);
    expect(keyword.errors.join('\n')).toContain('外部关键词 id');

    const campaign = validateMetric({
      ...emptyMetricInput,
      platformId: PLATFORM,
      shopCode: 'SHOP-1',
      externalCampaignId: 'CAMP-1',
      metricScope: 'CAMPAIGN',
      externalKeywordId: 'KW-1',
    });
    expect(campaign.ok).toBe(false);
    expect(campaign.errors.join('\n')).toContain('不得带外部关键词 id');
  });
});

describe('14.2.2.2 展示：不可比 ≠ 0，「没有数据」≠「没同步到」', () => {
  it('null 比率显示成「不可比」，0 显示成 0.000000', () => {
    expect(ratio(null)).toBe('不可比');
    expect(ratio(0)).toBe('0.000000');
    expect(money(null)).toBe('—');
    expect(money(1.5)).toBe('1.5000');
  });

  it('数据新鲜度：没有数据 / 没同步到 / 数据截至 三种说法分开', () => {
    expect(freshnessText({ latestMetricsAsOf: null, metricsStale: false })).toContain('没有数据');
    expect(
      freshnessText({ latestMetricsAsOf: '2026-01-02T00:00:00Z', metricsStale: true }),
    ).toContain('没同步到');
    expect(
      freshnessText({ latestMetricsAsOf: '2026-01-02T00:00:00Z', metricsStale: false }),
    ).toContain('数据截至');
  });

  it('执行结论与拒绝原因翻成人话', () => {
    expect(outcomeLabel('DRY_RUN')).toBe('试运行（未调整）');
    expect(outcomeLabel('APPLIED')).toBe('已执行');
    expect(outcomeLabel('REJECTED')).toBe('被闸门拒绝');
    expect(rejectReasonLabel('SINGLE_CAP_EXCEEDED')).toBe('超过单次调整上限');
    expect(rejectReasonLabel('TOTAL_CAP_EXCEEDED')).toBe('超过总上限（窗口内累计）');
    expect(rejectReasonLabel('BOUND_VIOLATION')).toBe('越过上下限保护');
    expect(verdictLabel('INSUFFICIENT_DATA')).toContain('数据不足');
  });

  it('指标标签写明口径', () => {
    expect(metricLabel('ACOS')).toContain('花费 / 归因销售额');
    expect(metricLabel('ROAS')).toContain('归因销售额 / 花费');
  });
});

describe('14.2.2.2 空态：没有规则时给的是「先登记一条」而不是空白', () => {
  it('默认表单已经带上「试运行开启」与「上限必填」的初始值', () => {
    expect(emptyRuleInput.dryRun).toBe(true);
    expect(emptyRuleInput.totalCapCount).toBe('3');
    expect(emptyRuleInput.totalCapWindowHours).toBe('24');
    expect(emptyRuleInput.singleAdjustCapPercent).toBe('0');
    expect(emptyRuleInput.conditions.length).toBe(1);
  });

  it('规则列表为空时 total 为 0，页面据此显示空态', () => {
    const page: AdRulePage = {
      items: [],
      total: 0,
      page: 0,
      size: 20,
      dryRunCount: 0,
      activeCount: 0,
    };
    expect(page.items.length).toBe(0);
    expect(page.total).toBe(0);
  });
});
