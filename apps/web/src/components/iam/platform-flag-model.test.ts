import { describe, expect, it } from 'vitest';
import {
  announcementWindowState,
  describeEvaluation,
  formatPercent,
  fromLocalInputValue,
  groupByKind,
  platformSummary,
  reasonLabel,
  rolloutExplanation,
  toLocalInputValue,
  validateExceptionInput,
  validateSettingInput,
  type EvaluationLike,
  type SettingDraft,
} from './platform-flag-model';

/**
 * 17.7.2.1 平台参数与开关的前端口径。
 *
 * <p>重点是三条纪律在前端也成立：
 *
 * <ol>
 *   <li><b>三层裁决看得见</b>：摘要里必须同时出现平台全局值、租户例外与最终生效值；
 *   <li><b>限流阈值不给默认值</b>：前端不能替平台填一个数字；
 *   <li><b>灰度分桶可核对</b>：分桶号与放量阈值都要露出来。
 * </ol>
 */

function draft(overrides: Partial<SettingDraft> = {}): SettingDraft {
  return {
    enabled: true,
    rolloutPercent: '10.00',
    rateLimitPerMinute: '',
    announcementTitle: '',
    announcementBody: '',
    announcementFrom: '',
    announcementTo: '',
    note: '',
    ...overrides,
  };
}

function evaluation(overrides: Partial<EvaluationLike> = {}): EvaluationLike {
  return {
    flagCode: 'MODULE_AI_ASSISTANT',
    flagName: 'AI 助手',
    flagKind: 'MODULE',
    tenantId: '11111111-1111-1111-1111-111111111111',
    platformLayer: {
      configured: true,
      enabled: true,
      rolloutPercent: 10,
      rateLimitPerMinute: null,
      announcementTitle: null,
      announcementFrom: null,
      announcementTo: null,
    },
    tenantLayer: {
      applicable: true,
      present: false,
      effect: null,
      reason: null,
    },
    effectiveLayer: {
      enabled: true,
      reason: 'ROLLOUT_INCLUDED',
      rolloutBucket: 1338,
      rolloutBasisPoints: 1000,
      rateLimitPerMinute: null,
      announcementActive: null,
    },
    ...overrides,
  };
}

describe('formatPercent', () => {
  it('统一显示两位小数', () => {
    expect(formatPercent(10)).toBe('10.00%');
    expect(formatPercent('12.5')).toBe('12.50%');
  });

  it('读不到值时显示破折号而不是 0', () => {
    // 显示 0% 会让「没配」看起来像「配成 0」—— 这两件事的处置完全不同。
    expect(formatPercent(null)).toBe('—');
    expect(formatPercent(undefined)).toBe('—');
    expect(formatPercent('')).toBe('—');
  });
});

describe('reasonLabel', () => {
  it('已知码给人话，未知码原样透出', () => {
    expect(reasonLabel('TENANT_DISABLED')).toBe('平台对本租户单独禁用');
    expect(reasonLabel('SOME_NEW_CODE')).toBe('SOME_NEW_CODE');
    expect(reasonLabel(null)).toBe('—');
  });
});

describe('platformSummary', () => {
  it('功能开关显示开关状态与放量比例', () => {
    expect(
      platformSummary({
        flagKind: 'MODULE',
        configured: true,
        enabled: true,
        rolloutPercent: 10,
        rateLimitPerMinute: null,
        announcementTitle: null,
        announcementFrom: null,
        announcementTo: null,
      }),
    ).toBe('开启 · 放量 10.00%');
  });

  it('限流阈值显示每分钟次数', () => {
    expect(
      platformSummary({
        flagKind: 'RATE_LIMIT',
        configured: true,
        enabled: true,
        rolloutPercent: 0,
        rateLimitPerMinute: 120,
        announcementTitle: null,
        announcementFrom: null,
        announcementTo: null,
      }),
    ).toBe('每分钟 120 次');
  });

  it('没有配置行时如实说尚未配置', () => {
    expect(
      platformSummary({
        flagKind: 'RATE_LIMIT',
        configured: false,
        enabled: false,
        rolloutPercent: 0,
        rateLimitPerMinute: null,
        announcementTitle: null,
        announcementFrom: null,
        announcementTo: null,
      }),
    ).toBe('平台尚未配置');
  });
});

describe('describeEvaluation', () => {
  it('三层都要出现：平台值 / 租户例外 / 最终生效值', () => {
    const text = describeEvaluation(evaluation());
    expect(text).toContain('放量 10.00%');
    expect(text).toContain('本租户无例外');
    expect(text).toContain('最终可用');
    expect(text).toContain('在灰度放量范围内');
  });

  it('租户被单独禁用时，例外原因必须出现在摘要里', () => {
    const text = describeEvaluation(
      evaluation({
        tenantLayer: {
          applicable: true,
          present: true,
          effect: 'DISABLED',
          reason: '风控观察期',
        },
        effectiveLayer: {
          enabled: false,
          reason: 'TENANT_DISABLED',
          rolloutBucket: 1338,
          rolloutBasisPoints: 1000,
          rateLimitPerMinute: null,
          announcementActive: null,
        },
      }),
    );
    expect(text).toContain('本租户被单独禁用（风控观察期）');
    expect(text).toContain('最终不可用');
  });

  it('平台关掉时不能说成「本租户无例外所以可用」', () => {
    const text = describeEvaluation(
      evaluation({
        platformLayer: {
          configured: true,
          enabled: false,
          rolloutPercent: 0,
          rateLimitPerMinute: null,
          announcementTitle: null,
          announcementFrom: null,
          announcementTo: null,
        },
        effectiveLayer: {
          enabled: false,
          reason: 'PLATFORM_DISABLED',
          rolloutBucket: 1338,
          rolloutBasisPoints: 0,
          rateLimitPerMinute: null,
          announcementActive: null,
        },
      }),
    );
    expect(text).toContain('关闭 · 放量 0.00%');
    expect(text).toContain('最终不可用');
    expect(text).toContain('平台已关闭');
  });

  it('限流阈值与公告不参与按租户禁用，要说清楚', () => {
    const text = describeEvaluation(
      evaluation({
        flagKind: 'RATE_LIMIT',
        tenantLayer: { applicable: false, present: false, effect: null, reason: null },
      }),
    );
    expect(text).toContain('本类不接受按租户禁用');
  });
});

describe('rolloutExplanation', () => {
  it('把分桶号与放量阈值露出来，便于核对口径', () => {
    const text = rolloutExplanation({ rolloutBucket: 1338, rolloutBasisPoints: 1000 });
    expect(text).toContain('分桶 1338 / 10000');
    expect(text).toContain('放量阈值 1000');
    expect(text).toContain('未命中');
  });

  it('分桶小于阈值即命中', () => {
    expect(rolloutExplanation({ rolloutBucket: 10, rolloutBasisPoints: 1000 })).toContain('命中');
  });

  it('没有分桶信息时返回空串', () => {
    expect(rolloutExplanation({ rolloutBucket: null, rolloutBasisPoints: null })).toBe('');
  });
});

describe('announcementWindowState', () => {
  const now = new Date('2026-10-23T00:00:00Z');

  it('窗口内是生效中', () => {
    expect(
      announcementWindowState('2026-10-01T00:00:00Z', '2026-11-01T00:00:00Z', now),
    ).toBe('生效中');
  });

  it('还没到开始时刻是未生效，过了结束时刻是已过期', () => {
    // 这两件事的处置不同：前者等，后者要改窗口。
    expect(
      announcementWindowState('2026-11-01T00:00:00Z', '2026-12-01T00:00:00Z', now),
    ).toBe('未生效');
    expect(
      announcementWindowState('2026-09-01T00:00:00Z', '2026-10-01T00:00:00Z', now),
    ).toBe('已过期');
  });

  it('没配窗口就是未配置', () => {
    expect(announcementWindowState(null, null, now)).toBe('未配置');
  });
});

describe('validateSettingInput', () => {
  it('放量比例必须在 0 ~ 100 之间', () => {
    expect(validateSettingInput('MODULE', draft({ rolloutPercent: '-1' }))).toContain('0.00');
    expect(validateSettingInput('MODULE', draft({ rolloutPercent: '101' }))).toContain('0.00');
    expect(validateSettingInput('MODULE', draft({ rolloutPercent: 'abc' }))).toContain('数值');
  });

  it('限流阈值必须显式给出：不给默认值', () => {
    // 这是 17.7.2.1 的硬要求之一：前端也不能替平台填一个数字。
    expect(validateSettingInput('RATE_LIMIT', draft())).toContain('显式给出');
    expect(validateSettingInput('RATE_LIMIT', draft({ rateLimitPerMinute: '120' }))).toBe('');
    expect(validateSettingInput('RATE_LIMIT', draft({ rateLimitPerMinute: '0' }))).toContain(
      '正整数',
    );
  });

  it('公告必须带标题与一个非空生效期', () => {
    expect(validateSettingInput('ANNOUNCEMENT', draft())).toContain('标题');
    expect(
      validateSettingInput(
        'ANNOUNCEMENT',
        draft({
          announcementTitle: '维护通知',
          announcementFrom: '2026-10-01T00:00',
          announcementTo: '2026-10-01T00:00',
        }),
      ),
    ).toContain('晚于');
    expect(
      validateSettingInput(
        'ANNOUNCEMENT',
        draft({
          announcementTitle: '维护通知',
          announcementFrom: '2026-10-01T00:00',
          announcementTo: '2026-11-01T00:00',
        }),
      ),
    ).toBe('');
  });

  it('功能开关不接受限流与公告字段', () => {
    expect(validateSettingInput('MODULE', draft({ rateLimitPerMinute: '10' }))).toContain(
      '不接受',
    );
  });
});

describe('validateExceptionInput', () => {
  it('只有功能开关可以按租户禁用', () => {
    expect(validateExceptionInput('RATE_LIMIT', '想放开限流')).toContain('只有功能开关');
    expect(validateExceptionInput('ANNOUNCEMENT', '想屏蔽公告')).toContain('只有功能开关');
  });

  it('必须写明原因', () => {
    expect(validateExceptionInput('MODULE', '  ')).toContain('原因');
    expect(validateExceptionInput('MODULE', '风控观察期')).toBe('');
  });
});

describe('时间输入往返', () => {
  it('datetime-local 与 ISO 互转保留时刻', () => {
    const iso = '2026-10-23T08:30:00.000Z';
    const local = toLocalInputValue(iso);
    expect(local).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    const back = fromLocalInputValue(local);
    expect(back).not.toBeNull();
    // 往返后必须还是同一个时刻（本地时区换算，不该偏 8 小时）。
    expect(new Date(back as string).getTime()).toBe(new Date(iso).getTime());
  });

  it('空值往返成 null', () => {
    expect(toLocalInputValue(null)).toBe('');
    expect(fromLocalInputValue('')).toBeNull();
  });
});

describe('groupByKind', () => {
  it('按固定顺序分组，空组不出现', () => {
    const groups = groupByKind([
      { flagKind: 'ANNOUNCEMENT', code: 'A' },
      { flagKind: 'MODULE', code: 'B' },
      { flagKind: 'MODULE', code: 'C' },
    ]);
    expect(groups.map((g) => g.kind)).toEqual(['MODULE', 'ANNOUNCEMENT']);
    expect(groups[0]?.rows).toHaveLength(2);
  });
});
