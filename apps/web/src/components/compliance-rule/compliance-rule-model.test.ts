import { describe, expect, it } from 'vitest';
import {
  BASE,
  RULE_TYPES,
  SEVERITIES,
  STATUSES,
  TERM_KINDS,
  WORD_RULE_TYPES,
  emptyState,
  emptyStateKind,
  isWordRuleType,
  ruleTypeLabel,
  scopeText,
  severityLabel,
  severityTone,
  statusLabel,
  termKindLabel,
  windowText,
  type ComplianceRuleScope,
} from './compliance-rule-model';

/**
 * 16.2.1.1 规则库 前端模型的展示口径。
 *
 * 🔴 这组用例守的是页面上最容易做丢的五件事：
 *
 * 1. **值域与后端逐字一致**（七种规则类型 / 三种严重度）。
 * 2. **只有三类规则带词条** —— 页面上非词条类规则**不出现**词条输入框。
 * 3. **「提示」不是绿色** —— 它是中性色；把它显示成绿色会让人以为这条规则可以无视。
 * 4. **生效窗口没有结束时刻时显示「一直有效」**，**不是**空白（空白会被读成「没定」）。
 * 5. **空态四态分开说** —— 「还没有店铺 / 还没有规则 / 规则还没有版本 / 筛选无结果」。
 */
describe('16.2.1.1 规则库前端模型', () => {
  it('白名单前缀是独立的一条，不是并进既有 channel 分支', () => {
    expect(BASE).toBe('channel/compliance-rules');
  });

  it('规则类型恰为七种，严重度恰为三种，生命周期两种', () => {
    expect(RULE_TYPES).toHaveLength(7);
    expect(SEVERITIES).toEqual(['BLOCKING', 'WARNING', 'INFO']);
    expect(STATUSES).toEqual(['ACTIVE', 'RETIRED']);
    expect(TERM_KINDS).toEqual(['GENERAL', 'BRAND', 'CATEGORY', 'SPELLING']);
  });

  it('只有违禁词 / 极限词 / 侵权词三类规则带词条', () => {
    expect(WORD_RULE_TYPES).toEqual([
      'PROHIBITED_WORD',
      'SUPERLATIVE_WORD',
      'INFRINGEMENT_WORD',
    ]);
    expect(isWordRuleType('PROHIBITED_WORD')).toBe(true);
    expect(isWordRuleType('SUPERLATIVE_WORD')).toBe(true);
    expect(isWordRuleType('INFRINGEMENT_WORD')).toBe(true);
    expect(isWordRuleType('CERTIFICATION_REQUIRED')).toBe(false);
    expect(isWordRuleType('RESTRICTED_SALE')).toBe(false);
    expect(isWordRuleType('LABEL_REQUIREMENT')).toBe(false);
    expect(isWordRuleType('MATERIAL_REQUIREMENT')).toBe(false);
    expect(isWordRuleType(null)).toBe(false);
  });

  it('严重度的色调：阻断红、警告黄、提示中性 —— 提示不是「好」', () => {
    expect(severityTone('BLOCKING')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warning');
    expect(severityTone('INFO')).toBe('neutral');
    // 🔴 提示**不与**「没事」共用绿色。
    expect(severityTone('INFO')).not.toBe('success');
    expect(severityTone(undefined)).toBe('neutral');
  });

  it('人话名齐全，未知编码回落到原值（不编造）', () => {
    expect(ruleTypeLabel('PROHIBITED_WORD')).toBe('违禁词');
    expect(ruleTypeLabel('MATERIAL_REQUIREMENT')).toBe('材质要求');
    expect(severityLabel('BLOCKING')).toBe('阻断');
    expect(statusLabel('RETIRED')).toBe('已停用');
    expect(termKindLabel('SPELLING')).toBe('拼写变体');
    expect(ruleTypeLabel('NOPE')).toBe('NOPE');
    expect(severityLabel(null)).toBe('未声明');
  });

  it('生效窗口没有结束时刻时显示「一直有效」，不是空白', () => {
    const from = '2026-10-01T00:00:00Z';
    expect(windowText(from, null)).toContain('一直有效');
    expect(windowText(from, null)).not.toBe('');
    expect(windowText(from, '2026-11-01T00:00:00Z')).toContain('止');
  });

  it('适用范围展示：NULL 站点与 NULL 店铺写成人话，不显示空白', () => {
    const scope: ComplianceRuleScope = {
      scopeId: 's1',
      ruleVersionId: 'v1',
      platformId: 'p1',
      platformCode: 'AMAZON',
      platformName: 'Amazon',
      marketplaceId: null,
      marketplaceCode: null,
      marketplaceName: null,
      shopId: null,
      shopCode: null,
      shopName: null,
      createdAt: '2026-10-01T00:00:00Z',
    };
    const text = scopeText(scope);
    expect(text).toContain('AMAZON');
    expect(text).toContain('全部站点');
    expect(text).toContain('全部店铺');
  });

  it('空态四态分开说', () => {
    expect(emptyStateKind(0, 0, false)).toBe('NO_SHOP');
    expect(emptyStateKind(3, 0, false)).toBe('NO_DATA');
    expect(emptyStateKind(3, 5, false, 0)).toBe('NO_VERSION');
    expect(emptyStateKind(3, 5, true, 2)).toBe('NO_MATCH');

    expect(emptyState('NO_SHOP').title).toBe('还没有店铺');
    expect(emptyState('NO_DATA').title).toBe('还没有任何规则');
    expect(emptyState('NO_VERSION').title).toBe('这条规则还没有版本');
    expect(emptyState('NO_MATCH').title).toBe('筛选条件下没有规则');
    // 四种空态的文案**各不相同** —— 混成一句「暂无数据」运营就不知道该干什么。
    const titles = new Set(
      (['NO_SHOP', 'NO_DATA', 'NO_VERSION', 'NO_MATCH'] as const).map(
        (kind) => emptyState(kind).description,
      ),
    );
    expect(titles.size).toBe(4);
  });
});
