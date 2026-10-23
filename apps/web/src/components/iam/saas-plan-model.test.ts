import { describe, expect, it } from 'vitest';
import type { UsageView } from '../../../../../packages/api-contracts/src/index';
import {
  ALLOWED_TRANSITIONS,
  ENTITLEMENT_KIND_LABELS,
  ENTITLEMENT_STATE_LABELS,
  OVER_LIMIT_ACTION_LABELS,
  PERIOD_LABELS,
  PLAN_CODE_PATTERN,
  SUBSCRIPTION_STATUS_LABELS,
  capabilitySummary,
  describeEntitlementState,
  entitlementVerdict,
  eventSummary,
  nextStatuses,
  planSummary,
  subscriptionStatusText,
  usageSummary,
  validateEntitlementCode,
  validateLimit,
  validatePlanCode,
} from './saas-plan-model';

describe('套餐与权益编码校验', () => {
  it('接受大写字母开头的标识符', () => {
    for (const code of ['STARTER', 'A1', 'PLAN_2026', 'X_1']) {
      expect(validatePlanCode(code)).toBe('');
      expect(validateEntitlementCode(code)).toBe('');
    }
  });

  it('拒绝空编码、单字符编码、小写与横杠', () => {
    expect(validatePlanCode('')).toBe('套餐编码不能为空');
    expect(validateEntitlementCode('')).toBe('权益编码不能为空');
    expect(validatePlanCode('P')).toBe('编码需为大写字母开头的标识符（2–64 位字母、数字或下划线）');
    expect(validatePlanCode('plan')).toBe(
      '编码需为大写字母开头的标识符（2–64 位字母、数字或下划线）',
    );
    expect(validateEntitlementCode('plan-x')).toBe(
      '编码需为大写字母开头的标识符（2–64 位字母、数字或下划线）',
    );
  });

  it('编码正则与大写标识符口径一致', () => {
    expect(PLAN_CODE_PATTERN.test('STARTER')).toBe(true);
    expect(PLAN_CODE_PATTERN.test('starter')).toBe(false);
  });
});

describe('权益上限校验', () => {
  it('不限量时不校验上限', () => {
    expect(validateLimit('', true)).toBe('');
    expect(validateLimit('abc', true)).toBe('');
  });

  it('有限量时上限必填且不能为负', () => {
    expect(validateLimit('', false)).toBe('上限不能为空（不限量请勾选「不限量」）');
    expect(validateLimit('-1', false)).toBe('上限不能为负数');
    expect(validateLimit('abc', false)).toBe('上限必须是数字');
    expect(validateLimit('1000', false)).toBe('');
    expect(validateLimit('0', false)).toBe('');
  });
});

describe('权益用量口径', () => {
  it('未接通计量要说「判定不了」，不能说成「没超限」', () => {
    const text = describeEntitlementState({
      available: false,
      unlimited: false,
      usageValue: null,
      limitValue: 1000,
      period: 'MONTH',
      state: 'UNDETERMINED',
    });
    expect(text).toContain('判定不了');
    expect(text).not.toContain('在上限内');
  });

  it('不限量单独成句', () => {
    expect(
      describeEntitlementState({
        available: true,
        unlimited: true,
        usageValue: 99999,
        limitValue: null,
        period: 'TOTAL',
        state: 'UNLIMITED',
      }),
    ).toBe('不限量');
  });

  it('正常用量给出「当前 / 上限（周期）」', () => {
    expect(
      describeEntitlementState({
        available: true,
        unlimited: false,
        usageValue: 120,
        limitValue: 1000,
        period: 'MONTH',
        state: 'WITHIN_LIMIT',
      }),
    ).toBe('每月 120 / 1000');
  });

  it('总量型上限不拼「每总量」', () => {
    expect(
      describeEntitlementState({
        available: true,
        unlimited: false,
        usageValue: 1000,
        limitValue: 1000,
        period: 'TOTAL',
        state: 'WITHIN_LIMIT',
      }),
    ).toBe('总量 1000 / 1000');
  });

  it('六类权益维度与三种超限策略都有可读名', () => {
    expect(Object.keys(ENTITLEMENT_KIND_LABELS)).toHaveLength(6);
    expect(ENTITLEMENT_KIND_LABELS.SKU_COUNT).toBe('SKU 数');
    expect(ENTITLEMENT_KIND_LABELS.AI_USAGE).toBe('AI 用量');
    expect(OVER_LIMIT_ACTION_LABELS.BLOCK).toBe('阻断');
    expect(OVER_LIMIT_ACTION_LABELS.DEGRADE).toBe('降级');
    expect(OVER_LIMIT_ACTION_LABELS.ALLOW).toBe('放行');
    expect(PERIOD_LABELS.MONTH).toBe('月');
  });
});

describe('用量视图 → 摘要', () => {
  /** 一条真实的契约行：字段齐备，避免测试用「半个对象」绕过类型检查。 */
  const ORDER_USAGE: UsageView = {
    id: '11111111-1111-4111-8111-111111111111',
    subscriptionId: '22222222-2222-4222-8222-222222222222',
    entitlementCode: 'ORDER',
    entitlementKind: 'ORDER_VOLUME',
    limitValue: 3000,
    unlimited: false,
    overLimitAction: 'DEGRADE',
    period: 'MONTH',
    usageValue: 120,
    available: true,
    ownerModule: '17.7.1.2',
    impact: '',
    sourceSystem: 'trade',
    measuredAt: '2026-10-23T10:00:00+08:00',
    periodStart: '2026-10-01T00:00:00+08:00',
    periodEnd: '2026-11-01T00:00:00+08:00',
    note: '',
    state: 'WITHIN_LIMIT',
    updatedAt: '2026-10-23T10:00:00+08:00',
  };

  it('周期来自权益定义，不能被写成 null', () => {
    expect(usageSummary(ORDER_USAGE)).toBe('每月 120 / 3000');
  });

  it('总量型权益显示「总量」而不是「每总量」', () => {
    expect(
      usageSummary({
        ...ORDER_USAGE,
        entitlementCode: 'SKU',
        entitlementKind: 'SKU_COUNT',
        limitValue: 1000,
        usageValue: 1000,
        period: 'TOTAL',
      }),
    ).toBe('总量 1000 / 1000');
  });

  it('未接通计量的维度不冒充「没超限」', () => {
    expect(
      usageSummary({
        ...ORDER_USAGE,
        available: false,
        usageValue: null,
        state: 'UNDETERMINED',
      }),
    ).toContain('判定不了');
  });
});

describe('超限判定结论', () => {
  it('六种判定各有不同文案', () => {
    expect(entitlementVerdict('WITHIN_LIMIT')).toBe('用量在权益上限内');
    expect(entitlementVerdict('OVER_LIMIT_BLOCKED')).toContain('阻断');
    expect(entitlementVerdict('OVER_LIMIT_DEGRADED')).toContain('降级');
    expect(entitlementVerdict('OVER_LIMIT_ALLOWED')).toContain('只记录');
    expect(entitlementVerdict('UNDETERMINED')).toContain('判定不了');
    expect(entitlementVerdict('NO_SUBSCRIPTION')).toContain('没有订阅');
    expect(entitlementVerdict('UNLIMITED')).toBe('该权益不设上限');
  });

  it('判定状态都有可读名', () => {
    for (const state of Object.keys(ENTITLEMENT_STATE_LABELS)) {
      expect(ENTITLEMENT_STATE_LABELS[state]).toBeTruthy();
    }
  });
});

describe('订阅状态流转白名单', () => {
  it('取消是终态', () => {
    expect(nextStatuses('CANCELLED')).toEqual([]);
    expect(ALLOWED_TRANSITIONS.CANCELLED).toHaveLength(0);
  });

  it('试用可以转正式、到期与取消', () => {
    expect(nextStatuses('TRIAL')).toEqual(['ACTIVE', 'EXPIRED', 'CANCELLED', 'SUSPENDED']);
  });

  it('生效中不能直接回到试用', () => {
    expect(nextStatuses('ACTIVE')).not.toContain('TRIAL');
  });

  it('到期后可以续费', () => {
    expect(nextStatuses('EXPIRED')).toContain('ACTIVE');
  });

  it('没有订阅时没有可流转状态', () => {
    expect(nextStatuses(null)).toEqual([]);
  });
});

describe('订阅状态文案', () => {
  it('未开通单独成句', () => {
    expect(subscriptionStatusText(null, 'NONE')).toBe('未开通');
  });

  it('存储状态与推导状态不同时要说清是按时间判定', () => {
    expect(subscriptionStatusText('TRIAL', 'EXPIRED')).toContain('已按时间判定为');
    expect(subscriptionStatusText('TRIAL', 'EXPIRED')).toContain('试用中');
  });

  it('两者相同时只说一次', () => {
    expect(subscriptionStatusText('ACTIVE', 'ACTIVE')).toBe('生效中');
    expect(SUBSCRIPTION_STATUS_LABELS.EXPIRED).toBe('已到期');
  });
});

describe('变更留痕摘要', () => {
  it('状态迁移带前后状态', () => {
    expect(
      eventSummary({
        action: 'STATUS_CHANGED',
        fromStatus: 'TRIAL',
        toStatus: 'ACTIVE',
        reason: '',
      }),
    ).toBe('状态变更（试用中 → 生效中）');
  });

  it('带原因时把原因说出来', () => {
    expect(
      eventSummary({
        action: 'CANCELLED',
        fromStatus: 'ACTIVE',
        toStatus: 'CANCELLED',
        reason: '客户不续费',
      }),
    ).toContain('客户不续费');
  });
});

describe('套餐摘要与能力声明', () => {
  it('摘要要同时给出权益项数与阻断型数量', () => {
    const text = planSummary({
      name: '标准版',
      code: 'STARTER',
      status: 'ACTIVE',
      trialDays: 7,
      entitlementCount: 6,
      blockedActionCount: 2,
    });
    expect(text).toContain('标准版（STARTER）');
    expect(text).toContain('试用 7 天');
    expect(text).toContain('权益 6 项');
    expect(text).toContain('阻断型 2 项');
  });

  it('未交付维度必须点名归属模块与影响面', () => {
    const text = capabilitySummary({
      dimension: '权益用量自动计量',
      description: '分散在各服务',
      available: false,
      source: '各来源系统登记',
      ownerModule: '17.7.1.2',
      impact: '未登记的维度判定不了',
    });
    expect(text).toContain('归属 17.7.1.2');
    expect(text).toContain('判定不了');
  });

  it('已交付维度直接说清做了什么', () => {
    const text = capabilitySummary({
      dimension: '套餐定义与权益项',
      description: '套餐与六个维度上限',
      available: true,
      source: 'identity 服务',
      ownerModule: '',
      impact: '',
    });
    expect(text).toBe('套餐定义与权益项：套餐与六个维度上限');
  });
});
