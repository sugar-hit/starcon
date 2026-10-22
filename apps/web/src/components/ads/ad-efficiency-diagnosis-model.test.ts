import { describe, expect, it } from 'vitest';
import {
  actionAmountText,
  actionExecutable,
  actionModeLabel,
  actionTypeLabel,
  dateText,
  diagnosisStatusLabel,
  emptyAdDiagnosisRuleInput,
  emptyStateText,
  money,
  outcomeLabel,
  outcomeTone,
  quantity,
  ratioText,
  ruleTypeLabel,
  severityLabel,
  severityTone,
  timestampText,
  unavailableText,
  validateAdDiagnosisRule,
} from './ad-efficiency-diagnosis-model';
import type { AdDiagnosisActionRow, AdDiagnosisRuleInput } from './ad-efficiency-diagnosis-model';

/**
 * 14.3.2.2 效率诊断 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的三件事**：
 *
 * 1. **比率的 null 与 0 含义相反**。null = 不可比（分母为 0，比如销售额为 0 时 ACOS 算不出来），
 *    0 = 可比但真的是零。把「不可比」渲染成 0.00% 会让「烧了 210 块、零订单」看起来像效率极高。
 * 2. **「不可用」不是「失败」**。竞价类动作是能力声明（14.2.1.1 未交付），
 *    界面上要写「待 14.2.1.1 交付后开放」，不能给一个点了没用的按钮。
 * 3. **阈值是必填的**。「一条没有阈值的规则」永远判不出东西，留着只会让人以为它在生效。
 */

function action(overrides: Partial<AdDiagnosisActionRow> = {}): AdDiagnosisActionRow {
  return {
    id: '33333333-3333-3333-3333-333333333333',
    diagnosisId: '44444444-4444-4444-4444-444444444444',
    ruleId: '55555555-5555-5555-5555-555555555555',
    campaignId: '66666666-6666-6666-6666-666666666666',
    campaignName: '活动一',
    actionType: 'INCREASE_BUDGET',
    actionMode: 'WRITE',
    available: true,
    ownerModule: '',
    impact: '',
    targetModule: '',
    targetPath: '',
    actionPercent: 20,
    actionAmount: null,
    beforeValue: 100,
    afterValue: 120,
    outcome: 'NOT_APPLIED',
    message: '',
    guardrailNote: '',
    dryRun: true,
    requestedBy: null,
    appliedAt: null,
    createdAt: '2026-10-22T00:00:00+08:00',
    updatedAt: '2026-10-22T00:00:00+08:00',
    ...overrides,
  };
}

function ruleInput(overrides: Partial<AdDiagnosisRuleInput> = {}): AdDiagnosisRuleInput {
  return { ...emptyAdDiagnosisRuleInput, ...overrides };
}

describe('14.3.2.2 比率：null 是不可比，不是 0', () => {
  it('null 显示「不可比」，绝不显示 0.00%', () => {
    expect(ratioText(null)).toBe('不可比');
    expect(ratioText(null, false)).toBe('不可比');
    expect(ratioText(undefined)).toBe('不可比');
  });

  it('0 是「可比但真的是零」，与 null 区分开', () => {
    expect(ratioText(0)).toBe('0.00%');
    expect(ratioText(0, false)).toBe('0.0000');
    expect(ratioText(0)).not.toBe(ratioText(null));
  });

  it('普通比率原样展示，不做前端算术', () => {
    expect(ratioText(0.5)).toBe('50.00%');
    expect(ratioText(0.123456)).toBe('12.35%');
  });
});

describe('14.3.2.2 金额与数量：只原样展示', () => {
  it('null 显示破折号而不是 0', () => {
    expect(money(null)).toBe('—');
    expect(quantity(null)).toBe('—');
  });

  it('金额带币种，计数不��小数', () => {
    expect(money(120, 'USD')).toBe('120.00 USD');
    expect(quantity(1234.567)).toBe('1,234.57');
  });
});

describe('14.3.2.2 动作可用性：不可用不是失败', () => {
  it('WRITE 模式可执行', () => {
    expect(actionExecutable(action())).toBe(true);
  });

  it('UNAVAILABLE 不可执行，且界面写明责任模块', () => {
    const bid = action({
      actionType: 'INCREASE_BID',
      actionMode: 'UNAVAILABLE',
      available: false,
      ownerModule: '14.2.1.1 竞价管理',
      impact: '14.2.1.1 未交付，本模块不跳过它直接写平台。',
      outcome: 'UNAVAILABLE',
    });
    expect(actionExecutable(bid)).toBe(false);
    expect(unavailableText(bid)).toBe('待 14.2.1.1 竞价管理 交付后开放');
    expect(actionModeLabel(bid.actionMode)).toBe('暂不可用');
  });

  it('NAVIGATE 去已交付模块处理，不可执行但要指路', () => {
    const keywords = action({
      actionType: 'OPTIMIZE_KEYWORDS',
      actionMode: 'NAVIGATE',
      available: true,
      targetModule: '14.1.3.1 关键词管理',
      targetPath: '/ads/keywords',
    });
    expect(actionExecutable(keywords)).toBe(false);
    expect(unavailableText(keywords)).toBe('请到 14.1.3.1 关键词管理 处理');
  });
});

describe('14.3.2.2 执行结论六种分开', () => {
  it('每种结论有自己的文案与色调', () => {
    expect(outcomeLabel('NOT_APPLIED')).toBe('待执行');
    expect(outcomeLabel('DRY_RUN')).toBe('试运行（只算不调）');
    expect(outcomeLabel('APPLIED')).toBe('已生效（本地）');
    expect(outcomeLabel('REJECTED')).toBe('被护栏拒绝');
    expect(outcomeLabel('SKIPPED')).toBe('已跳过');
    expect(outcomeLabel('UNAVAILABLE')).toBe('不可用');
    expect(outcomeTone('APPLIED')).toBe('text-emerald-600');
    expect(outcomeTone('REJECTED')).toBe('text-destructive');
    expect(outcomeTone('DRY_RUN')).toBe('text-sky-600');
  });
});

describe('14.3.2.2 判定口径与严重度', () => {
  it('五种问题各有中文名', () => {
    expect(ruleTypeLabel('HIGH_SPEND_NO_CONVERSION')).toBe('高花费零转化');
    expect(ruleTypeLabel('ACOS_OVER_TARGET')).toBe('ACOS 长期超标');
    expect(ruleTypeLabel('BUDGET_CAPPED')).toBe('预算受限');
    expect(ruleTypeLabel('LOW_DELIVERY')).toBe('竞价过低曝光不足');
    expect(ruleTypeLabel('CREATIVE_DECAY')).toBe('创意衰退');
  });

  it('严重度有色调', () => {
    expect(severityLabel('CRITICAL')).toBe('严重');
    expect(severityTone('CRITICAL')).toBe('text-destructive');
    expect(severityTone('LOW')).toBe('text-muted-foreground');
  });

  it('动作类型有中文名', () => {
    expect(actionTypeLabel('PAUSE_CAMPAIGN')).toBe('暂停活动');
    expect(actionTypeLabel('INCREASE_BUDGET')).toBe('提高预算');
    expect(actionTypeLabel('DECREASE_BUDGET')).toBe('降低预算');
    expect(actionTypeLabel('INCREASE_BID')).toBe('提高竞价');
    expect(actionTypeLabel('OPTIMIZE_KEYWORDS')).toBe('关键词层优化');
  });
});

describe('14.3.2.2 空态：四种空分开说', () => {
  it('没规则 / 没数据 / 没发现 / 被筛掉各说各的', () => {
    expect(emptyStateText('NO_RULES', '')).toContain('还没有诊断规则');
    expect(emptyStateText('NO_DATA', '')).toContain('还没有广告每日指标');
    expect(emptyStateText('NO_FINDINGS', '')).toContain('没有发现效率问题');
    expect(emptyStateText('FILTERED_EMPTY', '')).toContain('被当前的筛选条件全部排除');
    expect(emptyStateText(null, '兜底文案')).toBe('兜底文案');
  });

  it('「没发现」是一个结论，不是「没数据」', () => {
    expect(emptyStateText('NO_FINDINGS', '')).not.toBe(emptyStateText('NO_DATA', ''));
  });
});

describe('14.3.2.2 建议幅度：比例优先', () => {
  it('有比例就用比例，没有才看绝对额', () => {
    expect(actionAmountText(20, null)).toBe('20.00%');
    expect(actionAmountText(null, 12.5)).toBe('12.50');
    expect(actionAmountText(null, null)).toBe('—');
  });
});

describe('14.3.2.2 规则表单：阈值是必填的', () => {
  it('高花费零转化缺金额门槛 ⇒ 拒', () => {
    const result = validateAdDiagnosisRule(
      ruleInput({ ruleCode: 'R1', ruleType: 'HIGH_SPEND_NO_CONVERSION', actionType: 'PAUSE_CAMPAIGN' }),
    );
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('金额门槛');
  });

  it('ACOS 超标缺目标或缺超标天数 ⇒ 拒', () => {
    const noTarget = validateAdDiagnosisRule(
      ruleInput({
        ruleCode: 'R2',
        ruleType: 'ACOS_OVER_TARGET',
        actionType: 'DECREASE_BUDGET',
        actionPercent: '20',
      }),
    );
    expect(noTarget.ok).toBe(false);
    expect(noTarget.errors.join()).toContain('目标 ACOS');
    expect(noTarget.errors.join()).toContain('超标天数');

    const noDays = validateAdDiagnosisRule(
      ruleInput({
        ruleCode: 'R2',
        ruleType: 'ACOS_OVER_TARGET',
        actionType: 'DECREASE_BUDGET',
        actionPercent: '20',
        thresholdRatio: '0.3',
      }),
    );
    expect(noDays.ok).toBe(false);
    expect(noDays.errors.join()).toContain('超标天数');
  });

  it('预算受限缺利用率下限 ⇒ 拒', () => {
    const result = validateAdDiagnosisRule(
      ruleInput({
        ruleCode: 'R3',
        ruleType: 'BUDGET_CAPPED',
        actionType: 'INCREASE_BUDGET',
        actionPercent: '20',
      }),
    );
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('预算利用率下限');
  });

  it('曝光不足缺相对下限 ⇒ 拒', () => {
    const result = validateAdDiagnosisRule(
      ruleInput({ ruleCode: 'R4', ruleType: 'LOW_DELIVERY', actionType: 'INCREASE_BID' }),
    );
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('曝光相对下限');
  });

  it('创意衰退不许标成「数据源已就绪」', () => {
    const result = validateAdDiagnosisRule(
      ruleInput({
        ruleCode: 'R5',
        ruleType: 'CREATIVE_DECAY',
        actionType: 'NONE',
        dataSourceReady: true,
      }),
    );
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('数据源尚未就绪');
  });

  it('调预算必须给幅度；暂停不许给幅度', () => {
    const noAmount = validateAdDiagnosisRule(
      ruleInput({
        ruleCode: 'R6',
        ruleType: 'BUDGET_CAPPED',
        actionType: 'INCREASE_BUDGET',
        budgetUtilizationRatio: '0.95',
      }),
    );
    expect(noAmount.ok).toBe(false);
    expect(noAmount.errors.join()).toContain('建议幅度');

    const pauseWithAmount = validateAdDiagnosisRule(
      ruleInput({
        ruleCode: 'R7',
        ruleType: 'HIGH_SPEND_NO_CONVERSION',
        actionType: 'PAUSE_CAMPAIGN',
        thresholdAmount: '100',
        actionPercent: '10',
      }),
    );
    expect(pauseWithAmount.ok).toBe(false);
    expect(pauseWithAmount.errors.join()).toContain('不携带建议幅度');
  });

  it('最少天数不能大于观察窗口', () => {
    const result = validateAdDiagnosisRule(
      ruleInput({
        ruleCode: 'R8',
        ruleType: 'HIGH_SPEND_NO_CONVERSION',
        actionType: 'PAUSE_CAMPAIGN',
        thresholdAmount: '100',
        lookbackDays: '5',
        minMetricDays: '7',
      }),
    );
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('不能大于观察窗口');
  });

  it('一条完整的规则通过校验', () => {
    const result = validateAdDiagnosisRule(
      ruleInput({
        ruleCode: 'OK-1',
        ruleType: 'ACOS_OVER_TARGET',
        actionType: 'DECREASE_BUDGET',
        actionPercent: '20',
        thresholdRatio: '0.3',
        minOverTargetDays: '3',
      }),
    );
    expect(result.errors).toEqual([]);
    expect(result.ok).toBe(true);
  });
});

describe('14.3.2.2 时间与日期', () => {
  it('空时间显示破折号，非法时间原样返回', () => {
    expect(timestampText(null)).toBe('—');
    expect(timestampText('不是时间')).toBe('不是时间');
    expect(dateText(null)).toBe('—');
    expect(dateText('2026-10-07')).toBe('2026-10-07');
  });
});
