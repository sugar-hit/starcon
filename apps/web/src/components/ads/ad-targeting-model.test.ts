import { describe, expect, it } from 'vitest';
import {
  bidFactorText,
  countText,
  daypartWindowText,
  dimensionLabel,
  emptyAdTargetingCreateInput,
  emptyAdTargetingInterpretInput,
  emptyAdTargetingMetricInput,
  interpretedRowText,
  isUuid,
  money,
  pendingWritebackText,
  percentText,
  ratioText,
  scopeLabel,
  suggestionActionLabel,
  suggestionRuleLabel,
  suggestionSeverityLabel,
  suggestionSeverityTone,
  targetingOperationLabel,
  targetingStatusLabel,
  timestampText,
  validateAdTargetingInterpret,
  validateAdTargetingMetric,
  validateTargetingCreate,
} from './ad-targeting-model';
import type { AdTargetingInterpretedRow } from './ad-targeting-model';

/**
 * 14.1.3.2 投放定位 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的五件事**：
 *
 * 1. **「不可比」与「真的是零」是两件事**。ACOS / ROAS / CTR 为 null 时必须显示「不可比」，
 *    渲染成 `0.00` 就是在说「这个定位的 ACOS 是 0」—— 那是假的（销售额为 0 时 ACOS
 *    是**算不出来**）。
 * 2. **跨币种不相加**。页面按币种分行展示，汇总金额按币种分列；前端不做金额算术。
 * 3. **建议必须有依据**。「别照做」（actionable=false）必须能被页面标出来。
 * 4. **「本地已改」与「平台侧已改」分得开**。
 * 5. **平台原文 ≠ 归一定位**：归一不出来的键显示「未识别」，不能渲染成「地域：FOO」。
 */
describe('14.1.3.2 投放定位 前端模型', () => {
  it('null 比率显示「不可比」，不是 0', () => {
    expect(ratioText(null)).toBe('不可比');
    expect(ratioText(undefined)).toBe('不可比');
    expect(percentText(null)).toBe('不可比');
    expect(ratioText(null, 4)).toBe('不可比');
    expect(ratioText(0.2, 4)).toBe('0.2000');
    expect(percentText(0.355)).toBe('35.5%');
  });

  it('金额 null 显示「—」，不是 0', () => {
    expect(money(null)).toBe('—');
    expect(money(null, 'USD')).toBe('—');
    expect(money(12.5, 'USD')).toBe('12.50 USD');
    expect(money(12.5)).toBe('12.50');
  });

  it('出价系数带「1 = 不调整」的口径', () => {
    expect(bidFactorText(1)).toBe('1.000000（1 = 不调整）');
    expect(bidFactorText(0.8)).toBe('0.800000（1 = 不调整）');
    expect(bidFactorText(null)).toBe('—');
  });

  it('计数原样展示', () => {
    expect(countText(1000)).toBe('1,000');
    expect(countText(0)).toBe('0');
  });

  it('维度 / 状态 / 归属 / 动作 / 规则 的中文名', () => {
    expect(dimensionLabel('GEO')).toBe('地域');
    expect(dimensionLabel('AUDIENCE')).toBe('人群');
    expect(dimensionLabel('DEVICE')).toBe('设备');
    expect(dimensionLabel('DAYPART')).toBe('时段');
    expect(dimensionLabel('MATCH_TYPE')).toBe('匹配类型');
    // 🔴 UNMAPPED 是「未识别」，不是兜底
    expect(dimensionLabel('UNMAPPED')).toBe('未识别');

    expect(targetingStatusLabel('ACTIVE')).toBe('在投');
    expect(targetingStatusLabel('PAUSED')).toBe('已收窄（停投这条定位）');
    expect(targetingStatusLabel('ARCHIVED')).toBe('已归档');

    expect(scopeLabel('CAMPAIGN')).toBe('活动级');
    expect(scopeLabel('GROUP')).toBe('组级');

    expect(suggestionActionLabel('NARROW')).toBe('收窄投放');
    expect(suggestionActionLabel('REDUCE_BID')).toBe('降价');
    expect(suggestionActionLabel('INCREASE_BID')).toBe('加价放量');

    expect(suggestionRuleLabel('WASTED_TARGETING')).toBe('无效定位');
    expect(suggestionRuleLabel('DIMENSION_SPREAD')).toBe('维度内差异过大');

    expect(suggestionSeverityLabel('HIGH')).toBe('严重');
    expect(suggestionSeverityTone('HIGH')).toBe('text-destructive');
    expect(suggestionSeverityTone('MEDIUM')).toBe('text-amber-600');
    expect(suggestionSeverityTone('LOW')).toBe('text-muted-foreground');

    expect(targetingOperationLabel('NARROW_TARGETING')).toBe('收窄投放');
    expect(targetingOperationLabel('INTERPRET_TARGETING')).toBe('解读平台原文');
  });

  it('时段窗口：解析不出来就写「未解析」，不编窗口', () => {
    expect(daypartWindowText(null, null, null, null)).toBe('时段窗口未解析');
    expect(daypartWindowText(22, 2, 4, true)).toBe('22:00 ~ 2:00（跨零点） · 4.000000 小时');
    expect(daypartWindowText(9, 17, 8, false)).toBe('9:00 ~ 17:00 · 8.000000 小时');
  });

  it('解读结果：认不出来的键显示「未识别」，并保留平台原文键', () => {
    const row: AdTargetingInterpretedRow = {
      dimension: 'UNMAPPED',
      dimensionValue: 'ZZZ',
      platformRawKey: 'weird',
      platformRawValue: 'zzz',
      recognized: false,
      daypartStartHour: null,
      daypartEndHour: null,
      daypartHours: null,
      daypartCrossesMidnight: null,
      note: '归一不出来',
    };
    expect(interpretedRowText(row)).toContain('未识别');
    expect(interpretedRowText(row)).toContain('weird');
  });

  it('本地已改 ≠ 平台侧已改', () => {
    expect(pendingWritebackText(true)).toBe('本地已改、尚未回写平台');
    expect(pendingWritebackText(false)).toBe('未标记待回写');
  });

  it('UUID 形状校验', () => {
    expect(isUuid('11111111-1111-1111-1111-111111111111')).toBe(true);
    expect(isUuid('capabilities')).toBe(false);
    expect(isUuid('')).toBe(false);
  });

  it('时间戳空值显示「—」', () => {
    expect(timestampText(null)).toBe('—');
    expect(timestampText('')).toBe('—');
    expect(timestampText('not-a-date')).toBe('not-a-date');
  });

  it('新建定位设置：必填校验 + 系数非正被拒', () => {
    const empty = validateTargetingCreate(emptyAdTargetingCreateInput);
    expect(empty.ok).toBe(false);
    expect(empty.errors).toContain('所属活动必填');
    expect(empty.errors).toContain('定位取值必填');

    const ok = validateTargetingCreate({
      ...emptyAdTargetingCreateInput,
      campaignId: '11111111-1111-1111-1111-111111111111',
      dimensionValue: 'US',
    });
    expect(ok.ok).toBe(true);

    const bad = validateTargetingCreate({
      ...emptyAdTargetingCreateInput,
      campaignId: '11111111-1111-1111-1111-111111111111',
      dimensionValue: 'US',
      bidFactor: '0',
    });
    expect(bad.ok).toBe(false);
    expect(bad.errors).toContain('出价系数必须大于 0');

    const nan = validateTargetingCreate({
      ...emptyAdTargetingCreateInput,
      campaignId: '11111111-1111-1111-1111-111111111111',
      dimensionValue: 'US',
      bidFactor: 'abc',
    });
    expect(nan.errors).toContain('出价系数必须是数字');
  });

  it('解读：原文与广告组至少给一个', () => {
    const empty = validateAdTargetingInterpret(emptyAdTargetingInterpretInput);
    expect(empty.ok).toBe(false);
    expect(empty.errors).toContain('所属活动必填');
    expect(empty.errors.some((e) => e.includes('至少给一个'))).toBe(true);

    const withGroup = validateAdTargetingInterpret({
      ...emptyAdTargetingInterpretInput,
      campaignId: '11111111-1111-1111-1111-111111111111',
      groupId: '22222222-2222-2222-2222-222222222222',
    });
    expect(withGroup.ok).toBe(true);

    const withRaw = validateAdTargetingInterpret({
      ...emptyAdTargetingInterpretInput,
      campaignId: '11111111-1111-1111-1111-111111111111',
      targetingSettings: 'geo=US',
    });
    expect(withRaw.ok).toBe(true);
  });

  it('同步效果数据：点击数不能大于展示数；金额不能为负', () => {
    const base = {
      ...emptyAdTargetingMetricInput,
      settingId: '11111111-1111-1111-1111-111111111111',
      bucketDate: '2026-10-01',
      currency: 'USD',
    };
    const ok = validateAdTargetingMetric({ ...base, impressions: '100', clicks: '10', conversions: '1' });
    expect(ok.ok).toBe(true);

    const bad = validateAdTargetingMetric({ ...base, impressions: '10', clicks: '20' });
    expect(bad.ok).toBe(false);
    expect(bad.errors).toContain('点击数不能大于展示数');

    const negative = validateAdTargetingMetric({ ...base, impressions: '10', spend: '-1' });
    expect(negative.ok).toBe(false);
    expect(negative.errors).toContain('花费不能为负');

    const noCurrency = validateAdTargetingMetric({ ...base, currency: '' });
    expect(noCurrency.errors).toContain('指标币种必填');
  });
});
