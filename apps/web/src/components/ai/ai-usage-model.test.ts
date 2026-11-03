import { describe, expect, it } from 'vitest';
import {
  evaluationModeLabel,
  formatCount,
  formatInstant,
  formatMoney,
  memberLabel,
  quotaSubjectLabel,
  quotaTone,
  quotaUsageFacts,
  sensitiveActionLabel,
  sensitiveActionTone,
  trimDecimal,
  usageUnitLabel,
  violationFacts,
  violationTone,
} from './ai-usage-model';
import type { AiQuotaRow, AiQuotaViolation } from '../../../../../packages/api-contracts/src/index';

/**
 * 18.4.3.1「使用与配额」前端口径单元测试。
 *
 * <p>这些全是**纯函数**（不碰网络、不碰 DOM），测的是「口径对不对」——
 * 页面上的数字如果错了，运营照着它做决策就完了，所以口径必须被钉住。
 *
 * <p>🔴 测得最狠的一条是 {@link trimDecimal}：金额与用量全程是**十进制文本**，
 * 转成 `number` 就掉精度（`0.1 + 0.2 !== 0.3`），而账单上「差一分钱」是查不出来的。
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

describe('金额与次数的展示', () => {
  it('金额带币种', () => {
    expect(formatMoney('3.000000', 'USD')).toBe('3 USD');
    expect(formatMoney('0.500000', 'EUR')).toBe('0.5 EUR');
  });

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
});

describe('量纲', () => {
  it('量纲的中文说法', () => {
    expect(usageUnitLabel('REQUEST')).toBe('次');
    expect(usageUnitLabel('TOKEN')).toBe('Token');
    expect(usageUnitLabel('THOUSAND_TOKEN')).toBe('千 Token');
    expect(usageUnitLabel('CHARACTER')).toBe('字符');
  });
});

describe('敏感数据动作', () => {
  it('禁传 = 红、脱敏 = 黄、允许 = 绿', () => {
    expect(sensitiveActionTone('BLOCK')).toBe('danger');
    expect(sensitiveActionTone('MASK')).toBe('warn');
    expect(sensitiveActionTone('ALLOW')).toBe('ok');
    expect(sensitiveActionTone('UNKNOWN')).toBe('muted');
  });

  it('中文说法', () => {
    expect(sensitiveActionLabel('BLOCK')).toBe('禁传');
    expect(sensitiveActionLabel('MASK')).toBe('脱敏');
    expect(sensitiveActionLabel('ALLOW')).toBe('允许');
  });
});

describe('配额', () => {
  const quota: AiQuotaRow = {
    id: 'quota-1',
    scope: 'TENANT',
    scopeLabel: '租户级',
    membershipId: null,
    capabilityCode: 'TEXT_TRANSLATION',
    countConfigured: true,
    limitCount: 100,
    costConfigured: true,
    limitCost: '10.000000',
    currency: 'USD',
    period: 'DAY',
    periodLabel: '日',
    enabled: true,
    note: '',
    usedCount: 12,
    usedCost: '3.500000',
    exceeded: false,
    periodStart: '2026-11-02T00:00:00Z',
    periodEnd: '2026-11-03T00:00:00Z',
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  };

  it('两个上限各自成行，且成本那行带自己的币种', () => {
    const facts = quotaUsageFacts(quota);
    const count = facts.find((f) => f.label.startsWith('次数上限'));
    const cost = facts.find((f) => f.label.startsWith('成本上限'));
    expect(count?.value).toContain('12');
    expect(count?.value).toContain('100');
    // 🔴 成本那行必须带币种 —— 不同币种的钱不可比。
    expect(cost?.label).toContain('USD');
    expect(cost?.value).toContain('3.5');
    expect(cost?.value).toContain('10');
    expect(facts.some((f) => f.label === '重置时刻')).toBe(true);
  });

  it('只配一个上限时，那一行不出现', () => {
    const onlyCount: AiQuotaRow = { ...quota, costConfigured: false, limitCost: '0.000000' };
    const facts = quotaUsageFacts(onlyCount);
    expect(facts.some((f) => f.label.startsWith('成本上限'))).toBe(false);
    expect(facts.some((f) => f.label.startsWith('次数上限'))).toBe(true);
  });

  it('越线的语气', () => {
    expect(quotaTone(true, true)).toBe('danger');
    expect(quotaTone(false, true)).toBe('ok');
    // 🔴 停用的配额不是「正常」也不是「越线」 —— 它是「不参与判定」。
    expect(quotaTone(false, false)).toBe('muted');
    expect(quotaTone(true, false)).toBe('muted');
  });

  it('主体说明：租户级 / 用户级 + 能力', () => {
    expect(quotaSubjectLabel(quota)).toContain('租户级');
    expect(quotaSubjectLabel(quota)).toContain('TEXT_TRANSLATION');
    const memberQuota: AiQuotaRow = {
      ...quota,
      scope: 'USER',
      scopeLabel: '用户级',
      membershipId: 'abcdef01-2345-6789-abcd-ef0123456789',
    };
    expect(quotaSubjectLabel(memberQuota)).toContain('用户级');
    expect(quotaSubjectLabel(memberQuota)).toContain('abcdef01');
  });

  it('成员只展示前 8 位', () => {
    expect(memberLabel('abcdef01-2345-6789-abcd-ef0123456789')).toBe('abcdef01…');
    expect(memberLabel(null)).toBe('—');
  });
});

describe('超限说明必须带齐五要素', () => {
  const violation: AiQuotaViolation = {
    quotaId: 'quota-1',
    scope: 'TENANT',
    scopeLabel: '租户级',
    membershipId: null,
    capabilityCode: 'TEXT_TRANSLATION',
    limitKind: 'COUNT',
    limitValue: '2',
    currentUsage: '3',
    period: 'DAY',
    periodLabel: '日',
    periodStart: '2026-11-02T00:00:00Z',
    periodEnd: '2026-11-03T00:00:00Z',
    resetAt: '2026-11-03T00:00:00Z',
    message: '超出租户级配额（TEXT_TRANSLATION）：次数上限 2，当前累计 3，周期 日',
  };

  it('五要素一个都不能少', () => {
    const facts = violationFacts(violation);
    const labels = facts.map((f) => f.label);
    expect(labels).toContain('超了哪一条');
    expect(labels).toContain('当前用量');
    expect(labels).toContain('阈值');
    expect(labels).toContain('周期');
    expect(labels).toContain('什么时候重置');
  });

  it('阈值按次数 / 成本分别说清', () => {
    const count = violationFacts(violation).find((f) => f.label === '阈值');
    expect(count?.value).toContain('次数上限');
    const cost = violationFacts({ ...violation, limitKind: 'COST', limitValue: '1.000000' }).find(
      (f) => f.label === '阈值',
    );
    expect(cost?.value).toContain('成本上限');
  });

  it('重置时刻 = 周期结束时刻', () => {
    expect(violation.resetAt).toBe(violation.periodEnd);
  });

  it('超限的语气：次数是黄、成本是红', () => {
    expect(violationTone(violation)).toBe('warn');
    expect(violationTone({ ...violation, limitKind: 'COST' })).toBe('danger');
  });
});

describe('判定口径', () => {
  it('权威判定与预检要说清区别', () => {
    expect(evaluationModeLabel('POST_RECORD')).toContain('权威判定');
    expect(evaluationModeLabel('PREFLIGHT')).toContain('预检');
    expect(evaluationModeLabel('PREFLIGHT')).toContain('不占额度');
  });
});
