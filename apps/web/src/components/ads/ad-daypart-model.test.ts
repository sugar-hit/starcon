import { describe, expect, it } from 'vitest';
import {
  activationBlockers,
  daypartActionLabel,
  daypartActionTone,
  daypartStatusLabel,
  daypartStatusTone,
  editableCurve,
  effectiveHourCount,
  guardrailLabel,
  hourLabel,
  money,
  multiplierText,
  ratioText,
  taskResultLabel,
  taskResultTone,
  taskStatusLabel,
  timezoneStatusLabel,
  timezoneStatusTone,
  upstreamStatusLabel,
  validateDaypartCurve,
} from './ad-daypart-model';
import type { AdDaypartHourInput } from './ad-daypart-model';

/**
 * 14.2.1.2 分时策略 前端模型测试。
 *
 * 🔴 这里钉住的是**最容易在界面上做丢**的几件事：
 *   1. 🔴 **时区归属三态**（定得了 / 定不了 / 与上游不一致）必须分开显示 ——
 *      把它们都显示成「正常」就是蓝图加粗句说的「用错时区会完全失效」；
 *   2. 🔴 **不可比 ≠ 零**：占比为 null 时显示「不可比」而不是 0%；
 *   3. 🔴 **「停投」必须把预算系数压到 0**（自相矛盾的配置服务端会拒）；
 *   4. 🔴 **曲线必须覆盖 0..23 全部 24 个小时**（缺一个小时 ⇒ 那个小时落进空洞）；
 *   5. 🔴 **被护栏拒掉的行**要显眼，且写清被哪道闸拦下；
 *   6. 🔴 **试运行 ≠ 已生效**（「只算不调」不能显示成「已改成」）。
 */
describe('14.2.1.2 分时策略 · 展示辅助', () => {
  it('🔴 时区归属三态分开显示（定不了 ≠ 正常）', () => {
    expect(timezoneStatusLabel('RESOLVED')).toBe('时区已定');
    expect(timezoneStatusLabel('UNRESOLVED')).toBe('时区定不了');
    expect(timezoneStatusLabel('CONFLICT')).toBe('时区与上游不一致');

    expect(timezoneStatusTone('RESOLVED')).toBe('good');
    expect(timezoneStatusTone('UNRESOLVED')).toBe('bad');
    expect(timezoneStatusTone('CONFLICT')).toBe('warn');
  });

  it('🔴 动作四档各自有名字与语气（停投 ≠ 降价）', () => {
    expect(daypartActionLabel('KEEP')).toBe('不动');
    expect(daypartActionLabel('BOOST')).toBe('高峰提价提预算');
    expect(daypartActionLabel('REDUCE')).toBe('低谷降价');
    expect(daypartActionLabel('PAUSE')).toBe('低谷停投');

    expect(daypartActionTone('BOOST')).toBe('good');
    expect(daypartActionTone('REDUCE')).toBe('warn');
    expect(daypartActionTone('PAUSE')).toBe('bad');
    expect(daypartActionTone('KEEP')).toBe('muted');
  });

  it('🔴 占比为 null 时显示「不可比」而不是 0%', () => {
    expect(ratioText(null)).toBe('不可比');
    expect(ratioText(0)).toBe('0.00%');
    expect(ratioText(0.041667)).toBe('4.17%');
  });

  it('金额与系数原样展示（不做任何前端算术）', () => {
    expect(money(150)).toBe('150.0000');
    expect(money(null)).toBe('不可比');
    expect(multiplierText(1.5)).toBe('1.500000');
    expect(multiplierText(0)).toBe('0.000000');
  });

  it('本地小时标签补零', () => {
    expect(hourLabel(0)).toBe('00:00');
    expect(hourLabel(9)).toBe('09:00');
    expect(hourLabel(23)).toBe('23:00');
  });

  it('🔴 逐条结果区分「已生效 / 被拒 / 跳过 / 试运行」', () => {
    expect(taskResultLabel('APPLIED')).toBe('已生效');
    expect(taskResultLabel('REJECTED')).toBe('被拒');
    expect(taskResultLabel('SKIPPED')).toBe('已跳过');
    expect(taskResultLabel('DRY_RUN')).toBe('试运行（只算不调）');

    expect(taskResultTone('APPLIED')).toBe('good');
    expect(taskResultTone('REJECTED')).toBe('bad');
    expect(taskResultTone('DRY_RUN')).toBe('warn');
  });

  it('🔴 被哪道闸拦下的中文名（空串 = 没过闸，是业务原因）', () => {
    expect(guardrailLabel('SINGLE_AMOUNT')).toBe('单次上限（金额）');
    expect(guardrailLabel('SINGLE_PERCENT')).toBe('单次上限（比例）');
    expect(guardrailLabel('TOTAL_AMOUNT')).toBe('总额上限');
    expect(guardrailLabel('TOTAL_COUNT')).toBe('次数上限');
    expect(guardrailLabel('CURRENCY')).toBe('币种不一致');
    expect(guardrailLabel('')).toBe('');
  });

  it('上游结论与策略状态各有各的说法', () => {
    expect(upstreamStatusLabel('OK')).toBe('已产出曲线');
    expect(upstreamStatusLabel('SHOP_UNRESOLVED')).toBe('店铺时区定不了');
    expect(upstreamStatusLabel('CONFLICT')).toBe('两侧时区不一致');
    expect(upstreamStatusLabel('NO_FACT')).toBe('上游窗口内没有事实');

    expect(daypartStatusLabel('DRAFT')).toBe('草稿');
    expect(daypartStatusLabel('ACTIVE')).toBe('生效中');
    expect(daypartStatusTone('ACTIVE')).toBe('good');
    expect(taskStatusLabel('PARTIALLY_APPLIED')).toBe('部分生效');
  });
});

describe('14.2.1.2 分时策略 · 曲线校验', () => {
  const full = (patch: Partial<AdDaypartHourInput> = {}): AdDaypartHourInput[] =>
    Array.from({ length: 24 }, (_, hour) => ({
      localHour: hour,
      action: patch.action ?? 'KEEP',
      bidMultiplier: patch.bidMultiplier ?? 1,
      budgetMultiplier: patch.budgetMultiplier ?? 1,
      basisNote: patch.basisNote ?? '',
    }));

  it('完整 24 小时的曲线校验通过', () => {
    expect(validateDaypartCurve(full())).toEqual([]);
  });

  it('🔴 缺一个小时就要被拦下（那个小时会落进「没有配置」的空洞）', () => {
    const hours = full().slice(0, 23);
    const problems = validateDaypartCurve(hours);
    expect(problems.length).toBeGreaterThan(0);
    expect(problems.join('|')).toContain('24 个小时');
  });

  it('🔴 空曲线被拦下', () => {
    expect(validateDaypartCurve([]).join('|')).toContain('不能为空');
  });

  it('🔴 「停投」必须把预算系数压到 0', () => {
    const hours = full({ action: 'PAUSE', budgetMultiplier: 1 });
    expect(validateDaypartCurve(hours).join('|')).toContain('预算系数必须是 0');
  });

  it('🔴 竞价系数必须大于 0（0 或负数都不许）', () => {
    expect(validateDaypartCurve(full({ bidMultiplier: 0 })).join('|')).toContain('竞价系数必须大于 0');
    expect(validateDaypartCurve(full({ bidMultiplier: -1 })).join('|')).toContain('竞价系数必须大于 0');
  });

  it('🔴 预算系数不能为负', () => {
    expect(validateDaypartCurve(full({ budgetMultiplier: -1 })).join('|')).toContain('预算系数不能为负');
  });

  it('小时重复被拦下', () => {
    const hours = full().map((row, index) => (index === 5 ? { ...row, localHour: 4 } : row));
    expect(validateDaypartCurve(hours).join('|')).toContain('重复');
  });

  it('小时超出 0..23 被拦下', () => {
    const hours = full().map((row, index) => (index === 3 ? { ...row, localHour: 24 } : row));
    expect(validateDaypartCurve(hours).join('|')).toContain('0..23');
  });

  it('🔴 在动的小时数：KEEP×1 不算在动', () => {
    expect(effectiveHourCount(full())).toBe(0);
    const hours = full().map((row, index) =>
      index === 10 ? { ...row, action: 'BOOST', bidMultiplier: 1.5, budgetMultiplier: 1.5 } : row,
    );
    expect(effectiveHourCount(hours)).toBe(1);
  });
});

describe('14.2.1.2 分时策略 · 激活闸', () => {
  const boost = Array.from({ length: 24 }, (_, hour) => ({
    localHour: hour,
    action: 'BOOST',
    bidMultiplier: 1.5,
    budgetMultiplier: 1.5,
    basisNote: '',
  }));

  it('🔴 时区定不了 ⇒ 激活不了（用错时区会完全失效）', () => {
    const problems = activationBlockers('UNRESOLVED', boost);
    expect(problems.join('|')).toContain('时区定不了');
  });

  it('🔴 与上游时区不一致 ⇒ 激活不了', () => {
    expect(activationBlockers('CONFLICT', boost).join('|')).toContain('时区定不了');
  });

  it('🔴 曲线全是「不动」⇒ 激活不了（激活它等于什么都没配）', () => {
    const keep = Array.from({ length: 24 }, (_, hour) => ({
      localHour: hour,
      action: 'KEEP',
      bidMultiplier: 1,
      budgetMultiplier: 1,
      basisNote: '',
    }));
    expect(activationBlockers('RESOLVED', keep).join('|')).toContain('什么都没配');
  });

  it('时区已定 + 曲线在动 ⇒ 没有阻碍', () => {
    expect(activationBlockers('RESOLVED', boost)).toEqual([]);
  });
});

describe('14.2.1.2 分时策略 · 曲线派生', () => {
  it('缺的小时补成 KEEP×1（页面上永远是 24 行）', () => {
    const editable = editableCurve([
      { localHour: 3, action: 'BOOST', bidMultiplier: 1.5, budgetMultiplier: 1.5, source: 'MANUAL', basisNote: 'x' },
    ]);
    expect(editable).toHaveLength(24);
    expect(editable[3]).toMatchObject({ action: 'BOOST', bidMultiplier: 1.5, budgetMultiplier: 1.5 });
    expect(editable[4]).toMatchObject({ action: 'KEEP', bidMultiplier: 1, budgetMultiplier: 1 });
  });
});
