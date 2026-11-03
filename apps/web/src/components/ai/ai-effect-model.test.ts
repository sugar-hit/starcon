import { describe, expect, it } from 'vitest';
import {
  adoptionDecisionLabel,
  adoptionDecisionTone,
  adoptionRateFacts,
  artifactTypeLabel,
  backtestStatusTone,
  decideSuggestion,
  feedbackReasonLabel,
  formatCount,
  formatInstant,
  formatRate,
  memberLabel,
  normalizeThresholdInput,
  suggestionFacts,
  suggestionSeverityTone,
  suggestionStatusLabel,
  suggestionStatusTone,
  suggestionTypeLabel,
  suggestionTypeTone,
  thresholdFacts,
  trimDecimal,
  usefulnessLabel,
  usefulnessTone,
  validateThresholdInput,
  verdictLabel,
  verdictTone,
} from './ai-effect-model';
import type { AiEffectThresholdRow } from '../../../../../packages/api-contracts/src/index';

/**
 * 18.4.3.2「效果评估」前端口径单元测试。
 *
 * <p>这些全是**纯函数**（不碰网络、不碰 DOM），测的是「口径对不对」——
 * 页面上的数字如果错了，运营照着它下线一个还在用的功能，后果比不做评估更糟，
 * 所以口径必须被钉住。
 *
 * <p>🔴 测得最狠的两条是 {@link formatRate} 与 {@link normalizeThresholdInput}：
 *
 * <ul>
 *   <li>比率必须区分**「没有分母」（null）**与**「真的是 0」**。把两者混成同一个 0
 *       就是说谎 —— 「没调用过」和「调用了没人用」要下的结论完全不同。</li>
 *   <li>阈值表单**清空一格 = 沿用当前值**（null），不是发空串下去。</li>
 * </ul>
 */

describe('trimDecimal —— 十进制文本裁剪，不转数值', () => {
  it('去掉多余的尾零，但不动数值本身', () => {
    expect(trimDecimal('3.000000')).toBe('3');
    expect(trimDecimal('0.500000')).toBe('0.5');
    expect(trimDecimal('1500.000000')).toBe('1500');
    expect(trimDecimal('0.002000')).toBe('0.002');
  });

  it('整数原样返回', () => {
    expect(trimDecimal('100')).toBe('100');
    expect(trimDecimal('0')).toBe('0');
  });

  it('空值显示成「—」而不是 0', () => {
    // 🔴 「没有值」与「零」是两件不同的事 —— 显示成 0 是在说谎。
    expect(trimDecimal(null)).toBe('—');
    expect(trimDecimal(undefined)).toBe('—');
    expect(trimDecimal('')).toBe('—');
  });

  it('不是十进制数就原样返回（不硬改）', () => {
    expect(trimDecimal('abc')).toBe('abc');
    expect(trimDecimal('1.2.3')).toBe('1.2.3');
  });
});

describe('formatRate —— 比率转百分比，字符串移位不碰浮点', () => {
  it('null 与 0 必须区分开', () => {
    // 🔴 null = **没有分母**（分母为 0）⇒ 显示「—」，不是 0%。
    expect(formatRate(null)).toBe('—');
    expect(formatRate(undefined)).toBe('—');
    expect(formatRate('')).toBe('—');
    // 真的是 0% 就显示 0%。
    expect(formatRate('0.000000')).toBe('0.00%');
  });

  it('常见比率按十进制移位算，不掉精度', () => {
    expect(formatRate('0.250000')).toBe('25.00%');
    expect(formatRate('1.000000')).toBe('100.00%');
    expect(formatRate('0.200000')).toBe('20.00%');
    expect(formatRate('0.070000')).toBe('7.00%');
  });

  it('小数位不足两位时补零，不四舍五入', () => {
    expect(formatRate('0.5')).toBe('50.00%');
    expect(formatRate('0.07')).toBe('7.00%');
    expect(formatRate('0')).toBe('0.00%');
    expect(formatRate('1')).toBe('100.00%');
  });

  it('不是比率就原样返回（不硬改）', () => {
    expect(formatRate('abc')).toBe('abc');
    expect(formatRate('-0.1')).toBe('-0.1');
  });
});

describe('次数、时刻与主体成员', () => {
  it('次数是整数，千分位只是排版', () => {
    expect(formatCount(0)).toBe('0');
    expect(formatCount(1234)).toContain('1');
    expect(formatCount(null)).toBe('—');
  });

  it('时刻空值显示成「—」', () => {
    expect(formatInstant(null)).toBe('—');
    expect(formatInstant('')).toBe('—');
    expect(formatInstant('not-a-time')).toBe('not-a-time');
  });

  it('成员只展示前 8 位', () => {
    expect(memberLabel('abcdef01-2345-6789-abcd-ef0123456789')).toBe('abcdef01…');
    expect(memberLabel(null)).toBe('—');
  });
});

describe('人工处置三态', () => {
  it('中文说法', () => {
    expect(adoptionDecisionLabel('ACCEPTED')).toBe('直接采用');
    expect(adoptionDecisionLabel('MODIFIED')).toBe('修改后采用');
    expect(adoptionDecisionLabel('REJECTED')).toBe('拒绝');
  });

  it('语气：直接采用绿、修改后采用黄、拒绝红', () => {
    expect(adoptionDecisionTone('ACCEPTED')).toBe('ok');
    expect(adoptionDecisionTone('MODIFIED')).toBe('warn');
    expect(adoptionDecisionTone('REJECTED')).toBe('danger');
    expect(adoptionDecisionTone('UNKNOWN')).toBe('muted');
  });

  it('🔴 「修改后采用」不是「拒绝」—— 它计入采纳率分子，所以是黄不是红', () => {
    expect(adoptionDecisionTone('MODIFIED')).not.toBe('danger');
  });
});

describe('有用性三档', () => {
  it('中文说法与语气', () => {
    expect(usefulnessLabel('USEFUL')).toBe('有用');
    expect(usefulnessLabel('PARTIALLY_USEFUL')).toBe('部分有用');
    expect(usefulnessLabel('NOT_USEFUL')).toBe('无用');
    expect(usefulnessTone('USEFUL')).toBe('ok');
    expect(usefulnessTone('PARTIALLY_USEFUL')).toBe('warn');
    expect(usefulnessTone('NOT_USEFUL')).toBe('danger');
  });
});

describe('产物类型与反馈理由码', () => {
  it('产物类型', () => {
    expect(artifactTypeLabel('TRANSLATION')).toBe('译文');
    expect(artifactTypeLabel('FORECAST')).toBe('预测结果');
    expect(artifactTypeLabel('ASSISTANT')).toBe('助手答案');
    expect(artifactTypeLabel('OTHER')).toBe('其它 AI 产物');
  });

  it('理由码是受控词表', () => {
    expect(feedbackReasonLabel('INACCURATE')).toBe('内容不准');
    expect(feedbackReasonLabel('TOO_VERBOSE')).toBe('太啰嗦');
    expect(feedbackReasonLabel('MISSING_CONTEXT')).toBe('缺少上下文');
    expect(feedbackReasonLabel('DATA_STALE')).toBe('数据过时');
    // 未知码原样返回，不硬编。
    expect(feedbackReasonLabel('WHATEVER')).toBe('WHATEVER');
  });
});

describe('回测判定', () => {
  it('🔴 缺实际值来源不是失败 —— 它是「没有销量可对照」，如实显示', () => {
    expect(backtestStatusTone('COMPARED')).toBe('ok');
    expect(backtestStatusTone('NO_ACTUAL_SOURCE')).toBe('muted');
    expect(backtestStatusTone('NOT_COMPARABLE')).toBe('warn');
  });
});

describe('低价值判定', () => {
  it('中文说法与语气', () => {
    expect(verdictLabel('OK')).toBe('正常');
    expect(verdictLabel('LOW_VALUE')).toBe('低价值');
    expect(verdictLabel('INSUFFICIENT_DATA')).toBe('数据不足');
    expect(verdictTone('OK')).toBe('ok');
    expect(verdictTone('LOW_VALUE')).toBe('danger');
    expect(verdictTone('INSUFFICIENT_DATA')).toBe('warn');
  });
});

describe('改进建议', () => {
  it('类型 / 严重度 / 状态的说法与语气', () => {
    expect(suggestionTypeLabel('OFFLINE_CANDIDATE')).toBe('低价值，建议下线');
    expect(suggestionTypeLabel('DATA_GAP')).toBe('数据不足');
    expect(suggestionTypeTone('OFFLINE_CANDIDATE')).toBe('danger');
    // 🔴 数据不足**不是低价值** —— 语气不能是红。
    expect(suggestionTypeTone('DATA_GAP')).toBe('muted');
    expect(suggestionSeverityTone('HIGH')).toBe('danger');
    expect(suggestionSeverityTone('MEDIUM')).toBe('warn');
    expect(suggestionSeverityTone('LOW')).toBe('muted');
    expect(suggestionStatusLabel('OPEN')).toBe('待决策');
    expect(suggestionStatusTone('OPEN')).toBe('warn');
    expect(suggestionStatusTone('ACCEPTED')).toBe('ok');
    expect(suggestionStatusTone('RESOLVED')).toBe('muted');
  });

  it('四件事一个都不能少', () => {
    const facts = suggestionFacts({
      capabilityCode: 'TEXT_TRANSLATION',
      featureCode: 'BATCH_TRANSLATE',
      currentMetric: '采纳率 8.00%',
      recommendation: '建议下线该批量翻译功能',
      evidence: '窗口内调用 120 次，采纳 9 次',
    });
    const labels = facts.map((f) => f.label);
    expect(labels).toContain('针对功能');
    expect(labels).toContain('当前指标');
    expect(labels).toContain('建议做什么');
    expect(labels).toContain('依据');
    expect(facts.find((f) => f.label === '针对功能')?.value).toContain('TEXT_TRANSLATION');
  });
});

describe('采纳率报表的四件套', () => {
  const row = {
    calls: 120,
    adoptedCount: 9,
    acceptedCount: 5,
    modifiedCount: 4,
    rejectedCount: 6,
    linkedAdoptedCount: 7,
    unlinkedAdoptedCount: 2,
    adoptionRate: '0.075000',
    denominatorSource: 'platform_ai_usage_record（18.4.3.1 用量台账 · 调用次数）',
    numeratorSource: 'platform_ai_effect_adoption（ACTIVE · ACCEPTED + MODIFIED）',
  };

  it('分子 / 分母 / 比率 / 口径四件齐全', () => {
    const facts = adoptionRateFacts(row);
    const labels = facts.map((f) => f.label);
    expect(labels).toContain('分母（AI 调用次数）');
    expect(labels).toContain('分子（被采纳次数）');
    expect(labels).toContain('采纳率');
    expect(labels).toContain('分母来源');
    expect(labels).toContain('分子来源');
  });

  it('「修改后采用」与「拒绝」分开列，不混成一个数', () => {
    const split = adoptionRateFacts(row).find((f) => f.label.startsWith('其中'));
    expect(split?.value).toBe('5 / 4 / 6');
  });

  it('已关联 / 未关联调用记录分开列（两者单位可能不同）', () => {
    const linked = adoptionRateFacts(row).find((f) => f.label.includes('已关联'));
    expect(linked?.value).toBe('7 / 2');
  });

  it('分母为 0 时比率显示「—」，不是 0.00%', () => {
    const facts = adoptionRateFacts({ ...row, calls: 0, adoptionRate: null });
    expect(facts.find((f) => f.label === '采纳率')?.value).toBe('—');
  });
});

describe('评估阈值', () => {
  const row: AiEffectThresholdRow = {
    id: 'threshold-1',
    minCalls: 20,
    lowAdoptionRate: '0.200000',
    minFeedbackCount: 5,
    lowUsefulRate: '0.500000',
    maxMape: '0.300000',
    configured: true,
    basis: '租户已在库里登记这一行 ⇒ 以下阈值是该租户自己定的',
    updatedAt: '2026-11-02T00:00:00Z',
  };

  it('五个门槛都要有说法', () => {
    const facts = thresholdFacts(row);
    expect(facts.map((f) => f.label)).toHaveLength(5);
    expect(facts.find((f) => f.label.includes('调用次数'))?.value).toBe('20');
    expect(facts.find((f) => f.label.includes('采纳率'))?.value).toBe('20.00%');
    expect(facts.find((f) => f.label.includes('MAPE'))?.value).toBe('30.00%');
  });

  it('🔴 清空一格 = 沿用当前值（null），不是发空串下去', () => {
    const input = normalizeThresholdInput({
      minCalls: '',
      lowAdoptionRate: '0.2',
      minFeedbackCount: '',
      lowUsefulRate: '',
      maxMape: '',
    });
    expect(input.minCalls).toBeNull();
    expect(input.minFeedbackCount).toBeNull();
    expect(input.lowUsefulRate).toBeNull();
    expect(input.maxMape).toBeNull();
    expect(input.lowAdoptionRate).toBe('0.2');
  });

  it('非法输入本地就拦下（口径与后端一致）', () => {
    const base = {
      minCalls: '20',
      lowAdoptionRate: '0.2',
      minFeedbackCount: '5',
      lowUsefulRate: '0.5',
      maxMape: '0.3',
    };
    expect(validateThresholdInput(base)).toBeNull();
    expect(validateThresholdInput({ ...base, minCalls: '-1' })).toContain('调用次数门槛');
    expect(validateThresholdInput({ ...base, minCalls: '1.5' })).toContain('调用次数门槛');
    expect(validateThresholdInput({ ...base, lowAdoptionRate: '0.2x' })).toContain(
      '低价值采纳率阈值',
    );
    expect(validateThresholdInput({ ...base, lowUsefulRate: '1.5' })).toContain('低有用性阈值');
    expect(validateThresholdInput({ ...base, maxMape: '2' })).toContain('MAPE 阈值');
  });

  it('空着不校验（空 = 沿用当前值）', () => {
    expect(
      validateThresholdInput({
        minCalls: '',
        lowAdoptionRate: '',
        minFeedbackCount: '',
        lowUsefulRate: '',
        maxMape: '',
      }),
    ).toBeNull();
  });
});

describe('建议决策的查询参数', () => {
  it('decision 走查询参数而不是请求体', async () => {
    // 只验证 URL 形状：请求体是 { note }，decision 必须落在 query 上。
    const calls: { path: string; method: string; data?: unknown }[] = [];
    const original = globalThis.fetch;
    globalThis.fetch = ((input: string, init?: RequestInit) => {
      calls.push({
        path: String(input),
        method: init?.method ?? 'GET',
        data: init?.body ? JSON.parse(String(init.body)) : undefined,
      });
      return Promise.resolve(new Response('{}', { status: 200 }));
    }) as typeof fetch;
    try {
      await decideSuggestion('00000000-0000-4000-8000-000000000000', 'ACCEPTED', { note: '照办' });
    } finally {
      globalThis.fetch = original;
    }
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.path).toContain(
      '/api/platform/platform/ai/effects/suggestions/00000000-0000-4000-8000-000000000000/decision',
    );
    expect(calls[0]?.path).toContain('decision=ACCEPTED');
    expect(calls[0]?.data).toEqual({ note: '照办' });
  });
});
