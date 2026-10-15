import { describe, expect, it } from 'vitest';

import {
  EMPTY_MARKING_FORM,
  EMPTY_RULE_FORM,
  EMPTY_SIZE_FORM,
  bandText,
  flagKindLabel,
  hitKindLabel,
  optionalNumber,
  parseList,
  ruleReadinessMeta,
  severityMeta,
  serviceLevelLabel,
  toCutoffTime,
  toMarkingForm,
  toRuleForm,
  toSizeForm,
  transitDaysText,
  validateMarkingForm,
  validateRuleForm,
  validateSizeForm,
  verdictMeta,
} from './logistics-transit-model';
import type {
  MarkingRequirementRow,
  SizeLimitRow,
  TransitRuleRow,
} from './logistics-transit-model';

/**
 * 10.1.1.3 时效与限制前端模型测试。
 *
 * <p>只测**纯函数**：渲染与取数由 playwright / 集成测试兜。断言重点放在
 * 「错了会算错时效或放行不该发的货」的那几条上 —— 也就是重量段的半开区间、
 * 尺寸三维的完整性、标记要求的标识点名，它们与后端 DB CHECK / 触发器同款。
 *
 * <p>⚠️ 这里断言的每一条前端校验都**不是安全边界**：真正的硬拦截在后端
 * （V139 五个触发器 + POST precheck 判定端点）。前端只是提前告诉人，
 * 所以测试断言的是「提前报错」这个行为本身，而不是「只有前端挡得住」。
 */

function ruleForm(overrides: Partial<typeof EMPTY_RULE_FORM> = {}) {
  return { ...EMPTY_RULE_FORM, channelId: 'channel-1', ...overrides };
}

function sizeForm(overrides: Partial<typeof EMPTY_SIZE_FORM> = {}) {
  return { ...EMPTY_SIZE_FORM, channelId: 'channel-1', ...overrides };
}

function markingForm(overrides: Partial<typeof EMPTY_MARKING_FORM> = {}) {
  return { ...EMPTY_MARKING_FORM, channelId: 'channel-1', ...overrides };
}

describe('validateRuleForm · 重量段是半开区间 [下限, 上限)', () => {
  it('一份齐备的规则没有问题', () => {
    expect(validateRuleForm(ruleForm({ weightMinKg: 0.5, weightMaxKg: 30 }))).toEqual([]);
  });

  it('没选渠道要报 —— 时效承诺挂在渠道之下', () => {
    const problems = validateRuleForm(ruleForm({ channelId: null }));
    expect(problems.some((p) => p.includes('渠道'))).toBe(true);
  });

  it('国家必须是两位字母', () => {
    expect(
      validateRuleForm(ruleForm({ countryCode: 'DEU' })).some((p) => p.includes('两位字母')),
    ).toBe(true);
    expect(
      validateRuleForm(ruleForm({ countryCode: 'de' })).some((p) => p.includes('两位字母')),
    ).toBe(false);
  });

  it('上限 ≤ 下限要报：这个段永远命中不了', () => {
    expect(
      validateRuleForm(ruleForm({ weightMinKg: 30, weightMaxKg: 30 })).some((p) =>
        p.includes('永远命中不了'),
      ),
    ).toBe(true);
    expect(
      validateRuleForm(ruleForm({ weightMinKg: 30, weightMaxKg: 10 })).some((p) =>
        p.includes('必须大于下限'),
      ),
    ).toBe(true);
  });

  it('留空上限是合法的（最后一段「大于下限」），且不等于填一个等于下限的值', () => {
    expect(validateRuleForm(ruleForm({ weightMinKg: 30, weightMaxKg: null }))).toEqual([]);
    expect(
      validateRuleForm(ruleForm({ weightMinKg: 30, weightMaxKg: 30 })).some((p) =>
        p.includes('半开区间'),
      ),
    ).toBe(true);
  });

  it('下限为负要报', () => {
    expect(validateRuleForm(ruleForm({ weightMinKg: -1 })).some((p) => p.includes('不能为负'))).toBe(
      true,
    );
  });
});

describe('validateRuleForm · 时效与截单', () => {
  it('时效天数至少 1 天：为 0 时承诺不出送达天数', () => {
    expect(
      validateRuleForm(ruleForm({ transitDaysMin: 0 })).some((p) => p.includes('至少 1 天')),
    ).toBe(true);
  });

  it('上限不得小于下限', () => {
    expect(
      validateRuleForm(ruleForm({ transitDaysMin: 8, transitDaysMax: 5 })).some((p) =>
        p.includes('不得小于下限'),
      ),
    ).toBe(true);
  });

  it('截单时间必须是 HH:mm', () => {
    expect(validateRuleForm(ruleForm({ cutoffTime: '18' })).some((p) => p.includes('HH:mm'))).toBe(
      true,
    );
    expect(validateRuleForm(ruleForm({ cutoffTime: '18:00' }))).toEqual([]);
  });

  it('生效区间要么都给要么都不给', () => {
    const from = '2026-01-01T00:00:00Z';
    const to = '2026-12-31T00:00:00Z';
    expect(
      validateRuleForm(ruleForm({ effectiveFrom: from, effectiveTo: null })).some((p) =>
        p.includes('要么都给要么都不给'),
      ),
    ).toBe(true);
    expect(
      validateRuleForm(ruleForm({ effectiveFrom: from, effectiveTo: to })).some((p) =>
        p.includes('早于终点'),
      ),
    ).toBe(false);
    expect(
      validateRuleForm(ruleForm({ effectiveFrom: to, effectiveTo: from })).some((p) =>
        p.includes('早于终点'),
      ),
    ).toBe(true);
  });
});

describe('validateSizeForm · 尺寸三维要么都给要么都不给', () => {
  it('一项都没给要报：那是一行「没写限制」', () => {
    expect(validateSizeForm(sizeForm()).some((p) => p.includes('至少要给一项'))).toBe(true);
  });

  it('只给两维要报', () => {
    expect(
      validateSizeForm(sizeForm({ maxLengthCm: 60, maxWidthCm: 40 })).some((p) =>
        p.includes('要么都给要么都不给'),
      ),
    ).toBe(true);
  });

  it('三维齐备或只给重量都合法', () => {
    expect(validateSizeForm(sizeForm({ maxLengthCm: 60, maxWidthCm: 40, maxHeightCm: 30 }))).toEqual(
      [],
    );
    expect(validateSizeForm(sizeForm({ maxWeightKg: 30 }))).toEqual([]);
  });

  it('只给周长和也合法（部分承运商卡的是周长而不是单边长）', () => {
    expect(validateSizeForm(sizeForm({ maxGirthCm: 120 }))).toEqual([]);
  });

  it('上限必须大于 0；国家级写法要两位字母', () => {
    expect(validateSizeForm(sizeForm({ maxWeightKg: 0 })).some((p) => p.includes('大于 0'))).toBe(
      true,
    );
    expect(validateSizeForm(sizeForm({ countryCode: 'DEU' })).some((p) => p.includes('两位字母'))).toBe(
      true,
    );
    expect(validateSizeForm(sizeForm({ countryCode: '' })).some((p) => p.includes('两位字母'))).toBe(
      false,
    );
  });
});

describe('validateMarkingForm · 要求贴标识就必须点名贴什么', () => {
  it('LABEL_REQUIRED 没有 markingCode 要报（漏打标识会在清关出问题）', () => {
    expect(
      validateMarkingForm(markingForm({ requirementMode: 'LABEL_REQUIRED', markingCode: '' })).some(
        (p) => p.includes('必须点名贴什么'),
      ),
    ).toBe(true);
  });

  it('点名了就合法；BLOCK / MANUAL_REVIEW / ALLOWED 不要求点名', () => {
    expect(
      validateMarkingForm(markingForm({ requirementMode: 'LABEL_REQUIRED', markingCode: 'UN38.3' })),
    ).toEqual([]);
    for (const mode of ['BLOCK', 'MANUAL_REVIEW', 'ALLOWED']) {
      expect(validateMarkingForm(markingForm({ requirementMode: mode, markingCode: '' }))).toEqual([]);
    }
  });
});

describe('展示文案', () => {
  it('bandText：半开区间，上限留空显示为「≥X kg」', () => {
    expect(bandText(0.5, 30)).toBe('0.5 ~ 30 kg（不含上限）');
    expect(bandText(30, null)).toBe('≥ 30 kg');
  });

  it('bandText 去掉 NUMERIC(19,6) 的尾零：界面上不该出现 0.500000', () => {
    expect(bandText(0.500000, 30.000000)).toBe('0.5 ~ 30 kg（不含上限）');
  });

  it('transitDaysText：同一天数不重复写两遍', () => {
    expect(transitDaysText(8, 8)).toBe('8 天');
    expect(transitDaysText(8, 12)).toBe('8 ~ 12 天');
  });

  it('toCutoffTime：HH:mm 补成 HH:mm:ss；其余原样返回交给后端报错', () => {
    expect(toCutoffTime('18:00')).toBe('18:00:00');
    expect(toCutoffTime('9:30')).toBe('9:30:00');
    expect(toCutoffTime('乱填')).toBe('乱填');
  });

  it('标签函数覆盖全部枚举值，未知值原样返回而不是 undefined', () => {
    expect(serviceLevelLabel('EXPRESS')).toBe('特快');
    expect(flagKindLabel('DANGEROUS_GOODS')).toBe('危险品');
    expect(hitKindLabel('TRANSIT_MISSING')).toBe('缺时效承诺');
    expect(hitKindLabel('WEIGHT')).toBe('重量超限');
    expect(flagKindLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('verdictMeta / severityMeta 三档一一对应', () => {
    expect(verdictMeta('PASS').tone).toBe('success');
    expect(verdictMeta('REVIEW').tone).toBe('warning');
    expect(verdictMeta('BLOCK').tone).toBe('danger');
    expect(severityMeta('BLOCK').tone).toBe('danger');
    expect(severityMeta('REVIEW').tone).toBe('warning');
  });

  it('ruleReadinessMeta 把「还没配齐」与「已停用」分开', () => {
    expect(ruleReadinessMeta('NO_TRANSIT').tone).toBe('neutral');
    expect(ruleReadinessMeta('DISABLED').tone).toBe('warning');
    expect(ruleReadinessMeta('READY').tone).toBe('success');
  });
});

describe('工具', () => {
  it('optionalNumber：空串是「未填」而不是 0', () => {
    expect(optionalNumber('')).toBeNull();
    expect(optionalNumber('   ')).toBeNull();
    expect(optionalNumber('0')).toBe(0);
    expect(optionalNumber('30.5')).toBe(30.5);
    expect(optionalNumber('abc')).toBeNull();
  });

  it('parseList：去重、转大写、丢掉空项', () => {
    expect(parseList('home_goods, COSMETICS home_goods')).toEqual(['HOME_GOODS', 'COSMETICS']);
    expect(parseList('')).toEqual([]);
  });
});

describe('表单初值回填', () => {
  const row = {
    id: 'rule-1',
    channelId: 'channel-1',
    channelCode: 'SF-DE-STD',
    channelName: '顺丰德国标准',
    countryCode: 'DE',
    serviceLevel: 'STANDARD',
    weightMinKg: 0.5,
    weightMaxKg: null,
    transitDaysMin: 8,
    transitDaysMax: 12,
    cutoffTime: '18:00',
    effectiveFrom: '2026-01-01T00:00:00Z',
    effectiveTo: '2026-12-31T00:00:00Z',
    status: 'ACTIVE',
    readiness: 'READY',
    readinessMessage: '',
    effectiveNow: true,
    note: '',
    version: 3,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  } as TransitRuleRow;

  it('toRuleForm 回填出可通过校验的表单（空上限仍然是合法的最后一段）', () => {
    const form = toRuleForm(row);
    expect(form.transitRuleId).toBe('rule-1');
    expect(form.version).toBe(3);
    expect(form.weightMaxKg).toBeNull();
    expect(validateRuleForm(form)).toEqual([]);
  });

  it('toSizeForm / toMarkingForm 回填字段不串位', () => {
    const size = {
      id: 'size-1',
      channelId: 'channel-1',
      channelCode: 'SF-DE-STD',
      channelName: '顺丰德国标准',
      countryCode: null,
      maxLengthCm: 60,
      maxWidthCm: 40,
      maxHeightCm: 30,
      maxGirthCm: null,
      maxWeightKg: 30,
      scopeLabel: '渠道级',
      note: '',
      version: 0,
      createdAt: '',
      updatedAt: '',
    } as SizeLimitRow;
    const sizeForm2 = toSizeForm(size);
    expect(sizeForm2.countryCode).toBe('');
    expect(sizeForm2.maxLengthCm).toBe(60);
    expect(validateSizeForm(sizeForm2)).toEqual([]);

    const marking = {
      id: 'mark-1',
      channelId: 'channel-1',
      channelCode: 'SF-DE-STD',
      channelName: '顺丰德国标准',
      flagKind: 'BATTERY',
      requirementMode: 'LABEL_REQUIRED',
      markingCode: 'UN38.3',
      carrierReason: '',
      upstreamRestrictionMode: null,
      consistentWithUpstream: true,
      note: '',
      version: 0,
      createdAt: '',
      updatedAt: '',
    } as MarkingRequirementRow;
    const markingForm2 = toMarkingForm(marking);
    expect(markingForm2.flagKind).toBe('BATTERY');
    expect(markingForm2.markingCode).toBe('UN38.3');
    expect(validateMarkingForm(markingForm2)).toEqual([]);
  });
});
