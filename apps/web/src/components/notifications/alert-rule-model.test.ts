import { describe, expect, it } from 'vitest';
import type {
  AlertRuleEffective,
  AlertRuleVocabulary,
} from '../../../../../packages/api-contracts/src/index';
import {
  buildSaveInput,
  canDelete,
  canReset,
  draftOf,
  emptyDraft,
  isCustom,
  isPreset,
  isValidThresholdText,
  metricAvailable,
  metricName,
  resolutionLabel,
  scopePhrase,
  splitByOrigin,
  summarizeRules,
  thresholdPhrase,
  validateDraft,
  windowPhrase,
} from './alert-rule-model';

/**
 * 1.3.2.1 预警规则 —— 前端模型单测。
 *
 * <p>盯的是**形状与措辞**，不是网络：
 * 阈值文本的十进制形状、词表越界、对象范围与对象标识的自洽、
 * 「预置 / 自定义」的来源判定、以及「不可判定 ≠ 命中」这条措辞。
 */

const vocabulary: AlertRuleVocabulary = {
  metrics: [
    {
      code: 'SALES_AMOUNT',
      displayName: '销售额',
      unit: 'CURRENCY',
      unitLabel: '金额',
      ownerSystem: '13 指标',
      ownerModule: '13.1.1.1 经营总览',
      available: true,
      unavailableReason: null,
    },
    {
      code: 'STOCK_COVER_DAYS',
      displayName: '库存可覆盖天数',
      unit: 'DAYS',
      unitLabel: '天数',
      ownerSystem: '07 库存',
      ownerModule: '7.5.1.2 在途库存（库存风险投影）',
      available: false,
      unavailableReason: '覆盖天数口径尚未交付',
    },
  ],
  objectTypes: ['SKU', 'SHOP', 'LEGAL_ENTITY', 'SUPPLIER'],
  timeWindows: ['INSTANT', 'ROLLING_7D', 'ROLLING_30D', 'CALENDAR_MONTH'],
  comparisons: ['LT', 'LE', 'GT', 'GE'],
  changeBases: ['NONE', 'PERIOD_OVER_PERIOD', 'YEAR_OVER_YEAR'],
  objectScopes: ['ALL', 'SPECIFIC'],
};

function rule(patch: Partial<AlertRuleEffective> = {}): AlertRuleEffective {
  return {
    id: '3f9a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8',
    ruleCode: 'SKU_STOCK_COVER_DAYS_LOW',
    origin: 'PRESET',
    displayName: 'SKU 库存可覆盖天数偏低',
    description: '某个 SKU 的库存按当前出库速率还能卖不到 N 天时预警。',
    objectType: 'SKU',
    metricCode: 'STOCK_COVER_DAYS',
    threshold: '14',
    unit: 'DAYS',
    unitLabel: '天数',
    timeWindow: 'INSTANT',
    comparison: 'LT',
    changeBasis: 'NONE',
    objectScope: 'ALL',
    objectRef: null,
    timezone: 'Asia/Shanghai',
    enabled: true,
    customized: false,
    timezoneSource: 'PRESET_DEFAULT',
    upstreamSystem: '07 库存',
    upstreamModule: '7.5.1.2 在途库存（库存风险投影）',
    updatedAt: null,
    ...patch,
  };
}

describe('阈值文本（十进制精确，不碰浮点）', () => {
  it('接受十进制整数与最多六位小数', () => {
    expect(isValidThresholdText('14')).toBe(true);
    expect(isValidThresholdText('20.5')).toBe(true);
    expect(isValidThresholdText('0.000001')).toBe(true);
  });

  it('拒绝科学计数法、负数、多余小数位与空值', () => {
    expect(isValidThresholdText('1E2')).toBe(false);
    expect(isValidThresholdText('-1')).toBe(false);
    expect(isValidThresholdText('1.1234567')).toBe(false);
    expect(isValidThresholdText('')).toBe(false);
    expect(isValidThresholdText('abc')).toBe(false);
  });

  it('构造入参时阈值原样以字符串送出（不转成 number）', () => {
    const draft = { ...emptyDraft(), name: '精确线', threshold: '20.50' };
    const input = buildSaveInput(draft);
    expect(input.threshold).toBe('20.50');
    expect(typeof input.threshold).toBe('string');
  });
});

describe('草稿校验', () => {
  it('五项定义缺一不可', () => {
    expect(validateDraft(emptyDraft(), vocabulary)).toBe('规则名不能为空');
    const noType = { ...emptyDraft(), name: 'x' };
    expect(validateDraft(noType, vocabulary)).toBe('请选择对象类型');
    const noMetric = { ...noType, objectType: 'SKU' };
    expect(validateDraft(noMetric, vocabulary)).toBe('请选择度量');
    const noThreshold = { ...noMetric, metricCode: 'SALES_AMOUNT' };
    expect(validateDraft(noThreshold, vocabulary)).toBe('阈值不能为空');
    const noWindow = { ...noThreshold, threshold: '10' };
    expect(validateDraft(noWindow, vocabulary)).toBe('时间窗不在受控词表里');
    const noComparison = { ...noWindow, timeWindow: 'INSTANT' };
    expect(validateDraft(noComparison, vocabulary)).toBe('比较方向不在受控词表里');
  });

  it('词表外的对象类型与度量被拒', () => {
    const bad = {
      ...emptyDraft(),
      name: 'x',
      objectType: 'WAREHOUSE',
      metricCode: 'SALES_AMOUNT',
      threshold: '1',
    };
    expect(validateDraft(bad, vocabulary)).toBe('对象类型不在受控词表里');
  });

  it('对象范围与对象标识必须自洽', () => {
    const specific = {
      ...emptyDraft(),
      name: 'x',
      objectType: 'SKU',
      metricCode: 'SALES_AMOUNT',
      threshold: '1',
      objectScope: 'SPECIFIC',
    };
    expect(validateDraft(specific, vocabulary)).toBe(
      '对象范围是「具体某个对象」时必须给出对象标识',
    );
    const all = { ...specific, objectScope: 'ALL', objectRef: 'SKU-1' };
    expect(validateDraft(all, vocabulary)).toBe(
      '对象范围是「所有该类型对象」时不能给对象标识',
    );
  });

  it('合法草稿通过校验', () => {
    const draft = {
      ...emptyDraft(),
      name: '合法线',
      objectType: 'SKU',
      metricCode: 'SALES_AMOUNT',
      threshold: '10',
    };
    expect(validateDraft(draft, vocabulary)).toBeNull();
  });
});

describe('来源与动作可见性', () => {
  it('预置规则可恢复默认但不可删除', () => {
    const preset = rule();
    expect(isPreset(preset)).toBe(true);
    expect(isCustom(preset)).toBe(false);
    expect(canReset(preset)).toBe(true);
    expect(canDelete(preset)).toBe(false);
  });

  it('自定义规则可删除但没有默认值可恢复', () => {
    const custom = rule({ origin: 'CUSTOM', ruleCode: 'CUSTOM-1A2B3C4D5E6F' });
    expect(isCustom(custom)).toBe(true);
    expect(canDelete(custom)).toBe(true);
    expect(canReset(custom)).toBe(false);
  });

  it('把生效规则折回草稿时保持十进制文本', () => {
    const draft = draftOf(rule({ threshold: '20.50' }));
    expect(draft.threshold).toBe('20.50');
    expect(typeof draft.threshold).toBe('string');
    expect(draft.objectScope).toBe('ALL');
    expect(draft.objectRef).toBe('');
  });
});

describe('措辞', () => {
  it('阈值短语与时间窗短语说得清', () => {
    expect(thresholdPhrase(rule({ comparison: 'LT', threshold: '14', unitLabel: '天数' }))).toBe(
      '低于 14 天',
    );
    expect(
      windowPhrase(
        rule({ timeWindow: 'CALENDAR_MONTH', changeBasis: 'PERIOD_OVER_PERIOD' }),
      ),
    ).toBe('自然月 · 环比');
    expect(scopePhrase(rule({ objectScope: 'SPECIFIC', objectRef: 'SKU-1' }))).toBe(
      '具体对象 SKU-1',
    );
  });

  it('未接通的度量被如实标出来', () => {
    expect(metricAvailable(vocabulary, 'STOCK_COVER_DAYS')).toBe(false);
    expect(metricAvailable(vocabulary, 'SALES_AMOUNT')).toBe(true);
    expect(metricName(vocabulary, 'SALES_AMOUNT')).toBe('销售额');
  });

  it('不可判定与命中是两句话', () => {
    expect(resolutionLabel('HIT')).toBe('命中');
    expect(resolutionLabel('UNDECIDABLE')).toBe('不可判定');
  });
});

describe('汇总与分段', () => {
  it('统计预置 / 自定义 / 被改过 / 已关闭', () => {
    const rules = [
      rule(),
      rule({ id: 'a', ruleCode: 'B', customized: true }),
      rule({ id: 'b', ruleCode: 'C', enabled: false }),
      rule({ id: 'c', ruleCode: 'CUSTOM-1A2B3C4D5E6F', origin: 'CUSTOM' }),
    ];
    const summary = summarizeRules(rules);
    expect(summary.total).toBe(4);
    expect(summary.presetCount).toBe(3);
    expect(summary.customCount).toBe(1);
    expect(summary.customizedCount).toBe(1);
    expect(summary.disabledCount).toBe(1);
  });

  it('按来源分段且预置在前', () => {
    const { presets, customs } = splitByOrigin([
      rule({ id: 'x', origin: 'CUSTOM' }),
      rule({ id: 'y' }),
    ]);
    expect(presets).toHaveLength(1);
    expect(customs).toHaveLength(1);
    expect(presets[0]?.id).toBe('y');
  });
});
