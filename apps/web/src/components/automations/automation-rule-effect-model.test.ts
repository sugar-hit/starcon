import { describe, expect, it } from 'vitest';
import {
  adviceSourceLabel,
  describeAdviseReason,
  describeBasis,
  describeDuration,
  describeOutcome,
  falseTriggerTypeLabel,
  feedbackStatusTone,
  formatDecimal,
  formatRate,
  formatSavedMinutes,
  outcomeLabel,
  outcomeTone,
  ruleArchiveRef,
  severityLabel,
  severityTone,
} from './automation-rule-effect-model';

/**
 * 18.3.2.2「规则效果」前端模型的**纯函数**断言。
 *
 * <p>🔴 本文件盯住页面上最容易做错的五件事：
 *
 * <ol>
 *   <li><b>「没有样本」不许被说成「0%」</b>：成功率的分子为 0 与分母为 0 是两件事
 *       —— 分母为 0 必须显示「—」。</li>
 *   <li><b>「没有口径」不许被说成「0 分钟」</b>：没登记人工基线时，
 *       节省工时必须显示「—（未登记口径）」。</li>
 *   <li><b>「未执行」不许被说成「成功」</b>：它是独立的第五档，
 *       既不算成功也不算失败。</li>
 *   <li><b>耗时全程整数毫秒</b>：前端不碰浮点。</li>
 *   <li><b>「关闭动作归 18.3.1.1」这句话必须在</b>：读的人要知道
 *       「建议关闭」不等于「这里能关」。</li>
 * </ol>
 */

describe('成功率：没有样本 ≠ 成功率是零', () => {
  it('分母为 0 时显示破折号而不是 0%', () => {
    expect(formatRate(null)).toBe('—');
    expect(formatRate(undefined)).toBe('—');
    expect(formatRate(Number.NaN)).toBe('—');
  });

  it('有样本时按百分比显示', () => {
    expect(formatRate(0.5)).toBe('50.0%');
    expect(formatRate(1)).toBe('100.0%');
    expect(formatRate(0)).toBe('0.0%');
    expect(formatRate(0.2)).toBe('20.0%');
  });
});

describe('节省工时：没有口径 ≠ 省了 0 分钟', () => {
  it('没有口径时显示「未登记口径」', () => {
    expect(formatSavedMinutes(null, false)).toBe('—（未登记口径）');
    expect(formatSavedMinutes(0, false)).toBe('—（未登记口径）');
  });

  it('有口径但没省下时间时显示真的 0', () => {
    // 有口径 + 0 次成功 ⇒ 「0 分钟」是真的 0，不是「没评估过」。
    expect(formatSavedMinutes(0, true)).toBe('0 分钟');
  });

  it('有口径时显示分钟数', () => {
    expect(formatSavedMinutes(8, true)).toBe('8 分钟');
    expect(formatSavedMinutes(12.5, true)).toBe('12.5 分钟');
  });
});

describe('数字显示：不做浮点记账', () => {
  it('最多两位小数并去掉多余的 0', () => {
    expect(formatDecimal(4)).toBe('4');
    expect(formatDecimal(4.5)).toBe('4.5');
    expect(formatDecimal(4.25)).toBe('4.25');
    expect(formatDecimal(4.256)).toBe('4.26');
    expect(formatDecimal(null)).toBe('—');
  });
});

describe('耗时：整数毫秒', () => {
  it('按毫秒 / 秒 / 分 / 小时显示', () => {
    expect(describeDuration(0)).toBe('0 毫秒');
    expect(describeDuration(999)).toBe('999 毫秒');
    expect(describeDuration(1000)).toBe('1 秒');
    expect(describeDuration(3125)).toBe('3 秒');
    expect(describeDuration(65000)).toBe('1 分 5 秒');
    expect(describeDuration(3600000)).toBe('1 小时');
  });

  it('负数与非法值显示破折号', () => {
    expect(describeDuration(-1)).toBe('—');
    expect(describeDuration(Number.NaN)).toBe('—');
  });
});

describe('执行结论：五档不许压扁', () => {
  it('「未执行」既不是成功也不是失败', () => {
    expect(outcomeLabel('NOT_ATTEMPTED')).toBe('未执行');
    expect(outcomeTone('NOT_ATTEMPTED')).toBe('warn');
    expect(outcomeTone('SUCCEEDED')).toBe('ok');
    expect(outcomeTone('FAILED')).toBe('danger');
    expect(outcomeTone('SKIPPED')).toBe('muted');
  });

  it('描述里必须写清「没真的跑」', () => {
    expect(describeOutcome({ outcome: 'NOT_ATTEMPTED', dryRun: false })).toContain('没有真的跑');
    expect(describeOutcome({ outcome: 'SUCCEEDED', dryRun: false })).toBe('执行成功');
    expect(describeOutcome({ outcome: 'SUCCEEDED', dryRun: true })).toContain('试运行');
  });
});

describe('误触发分类与反馈状态', () => {
  it('分类有人话', () => {
    expect(falseTriggerTypeLabel('WRONG_CONDITION')).toBe('条件写错了');
    expect(falseTriggerTypeLabel('TOO_FREQUENT')).toBe('触发太频繁');
    expect(falseTriggerTypeLabel('OTHER')).toBe('其它');
    expect(falseTriggerTypeLabel('UNKNOWN', '兜底')).toBe('兜底');
  });

  it('待处理反馈是警告色，已采纳是成功色', () => {
    expect(feedbackStatusTone('OPEN')).toBe('warn');
    expect(feedbackStatusTone('ACCEPTED')).toBe('ok');
    expect(feedbackStatusTone('DISMISSED')).toBe('muted');
  });
});

describe('建议的严重度与来源', () => {
  it('紧急是危险色，提示是中性色', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warn');
    expect(severityTone('INFO')).toBe('muted');
    expect(severityLabel('CRITICAL')).toBe('紧急');
  });

  it('来源区分系统与人工', () => {
    expect(adviceSourceLabel('SYSTEM')).toBe('系统生成');
    expect(adviceSourceLabel('MANUAL')).toBe('人工登记');
  });
});

describe('「建议关闭」的理由必须写清动作归属', () => {
  it('建议关闭时给出理由并点名 18.3.1.1', () => {
    const reason = describeAdviseReason({
      adviseDisable: true,
      falseTriggerRuns: 3,
      falseTriggerRate: 0.5,
    });
    expect(reason).toContain('3 次');
    expect(reason).toContain('50.0%');
    // 🔴 关闭动作归 18.3.1.1 —— 这句话必须在，否则读的人会以为这里能直接关掉规则。
    expect(reason).toContain('18.3.1.1');
    expect(reason).toContain('本模块不做');
  });

  it('没建议关闭时不编理由', () => {
    expect(
      describeAdviseReason({
        adviseDisable: false,
        falseTriggerRuns: 0,
        falseTriggerRate: null,
      }),
    ).toBe('');
  });
});

describe('口径说明随数字一起给', () => {
  it('口径为空时返回空串', () => {
    expect(describeBasis(null)).toBe('');
    expect(describeBasis(undefined)).toBe('');
  });

  it('口径里必须含表、字段、公式与排除项', () => {
    const text = describeBasis({
      sourceTable: 'platform_automation_rule_effect_baseline',
      sourceField: 'manual_minutes_per_run',
      formula: '节省工时（分钟） = 该规则的**成功执行次数** × 该规则登记的 manual_minutes_per_run',
      excluded: '失败 / 跳过 / 超时 / 未执行**都不计**',
      unit: '分钟',
      note: '口径由租户自己登记',
    });
    expect(text).toContain('platform_automation_rule_effect_baseline');
    expect(text).toContain('manual_minutes_per_run');
    expect(text).toContain('成功执行次数');
    expect(text).toContain('都不计');
  });
});

describe('规则档案定位引用', () => {
  it('指向规则档案那一页并带上规则 id', () => {
    const ref = ruleArchiveRef('3f9a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8');
    expect(ref).toContain('/automations/rules');
    expect(ref).toContain('3f9a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8');
  });

  it('id 里的特殊字符被转义', () => {
    expect(ruleArchiveRef('a b/c')).toContain('a%20b%2Fc');
  });
});
