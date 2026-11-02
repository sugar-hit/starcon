import { describe, expect, it } from 'vitest';

import {
  conflictContext,
  conflictTitle,
  dimensionLabel,
  emptyPriorityRule,
  formatInstant,
  overlapText,
  priorityScopeLabel,
  priorityText,
  scopeRefPlaceholder,
  toPriorityRulesPayload,
  validatePriorityRules,
} from './price-list-priority-model';
import type { ConflictDetail, PriorityRuleInput } from './price-list-priority-model';

/**
 * 5.1.2.1 优先级与冲突检测前端模型的纯函数断言。
 *
 * <p>守的是三条界面口径：
 *
 * <ol>
 *   <li><b>数字越小 = 优先级越高</b>（1 最高），并列 ⇒ 不可唯一解析（前端不替用户挑）。</li>
 *   <li><b>每张价目表必须有一条全局规则</b>：没有它 ⇒ 它不参与解析（界面上必须拦）。</li>
 *   <li><b>「同 SKU」维度未覆盖</b>：界面不许显示成「没有冲突」。</li>
 * </ol>
 */

function rule(
  scopeType: PriorityRuleInput['scopeType'],
  scopeRef: string,
  priority: number,
): PriorityRuleInput {
  return { scopeType, scopeRef, priority, note: '' };
}

describe('优先级规则校验', () => {
  it('接受一套含全局规则的规则', () => {
    expect(validatePriorityRules([rule('GLOBAL', '*', 10), rule('SHOP', 'AMZ-US', 5)])).toBeNull();
  });

  it('一条规则都没有 ⇒ 报错', () => {
    const error = validatePriorityRules([]);
    expect(error?.message).toContain('至少要有一条优先级规则');
  });

  it('缺全局规则 ⇒ 报错（这张表会「在某些上下文里没有优先级」）', () => {
    const error = validatePriorityRules([rule('SHOP', 'AMZ-US', 5)]);
    expect(error?.message).toContain('GLOBAL');
  });

  it('两条全局规则 ⇒ 报错', () => {
    const error = validatePriorityRules([rule('GLOBAL', '*', 1), rule('GLOBAL', '*', 2)]);
    expect(error?.message).toContain('只能有一条');
  });

  it('同一上下文重复登记 ⇒ 报错（大小写无关）', () => {
    const error = validatePriorityRules([
      rule('GLOBAL', '*', 1),
      rule('SHOP', 'AMZ-US', 2),
      rule('SHOP', 'amz-us', 3),
    ]);
    expect(error?.message).toContain('重复');
  });

  it('上下文取值为空 ⇒ 报错', () => {
    const error = validatePriorityRules([rule('GLOBAL', '*', 1), rule('SITE', '  ', 2)]);
    expect(error?.message).toContain('必须给编码');
  });

  it('优先级越界 ⇒ 报错', () => {
    expect(validatePriorityRules([rule('GLOBAL', '*', -1)])?.message).toContain('0 到 9999');
    expect(validatePriorityRules([rule('GLOBAL', '*', 10000)])?.message).toContain('0 到 9999');
  });
});

describe('提交归一化', () => {
  it('全局规则的取值固定成星号，其余去空白', () => {
    const payload = toPriorityRulesPayload([
      { scopeType: 'GLOBAL', scopeRef: '  任意  ', priority: 3, note: ' 备注 ' },
      { scopeType: 'SHOP', scopeRef: '  AMZ-US ', priority: 5, note: '' },
    ]);
    expect(payload.rules[0]?.scopeRef).toBe('*');
    expect(payload.rules[0]?.note).toBe('备注');
    expect(payload.rules[1]?.scopeRef).toBe('AMZ-US');
  });
});

describe('显示口径', () => {
  it('数字越小优先级越高，未配置时明说「未配置优先级」', () => {
    expect(priorityText(1)).toBe('优先级 1');
    expect(priorityText(null)).toBe('未配置优先级');
  });

  it('上下文与维度的中文名', () => {
    expect(priorityScopeLabel('GLOBAL')).toBe('全局');
    expect(priorityScopeLabel('SITE')).toBe('站点');
    expect(dimensionLabel('SKU')).toBe('同 SKU');
    expect(dimensionLabel('TIME')).toBe('同时段生效');
  });

  it('占位提示告诉用户该填什么', () => {
    expect(scopeRefPlaceholder('GLOBAL')).toBe('*');
    expect(scopeRefPlaceholder('SHOP')).toBe('店铺编码');
  });

  it('空规则默认是全局 10', () => {
    expect(emptyPriorityRule()).toEqual({
      scopeType: 'GLOBAL',
      scopeRef: '*',
      priority: 10,
      note: '',
    });
  });

  it('重叠区间是半开区间，空终点显示「长期有效」', () => {
    expect(overlapText('2026-10-01T00:00:00Z', '2026-12-01T00:00:00Z')).toContain('2026-10-01');
    expect(overlapText('2026-10-01T00:00:00Z', '2026-12-01T00:00:00Z')).toContain(')');
    expect(overlapText('2026-10-01T00:00:00Z', null)).toContain('长期有效');
    expect(overlapText(null, null)).toBe('未知');
  });

  it('冲突标题与上下文可读', () => {
    const detail: ConflictDetail = {
      dimensions: ['SHOP', 'SITE', 'TIME', 'PRIORITY'],
      leftPriceListId: '1',
      leftPriceListCode: 'NA-RETAIL',
      leftPriceListName: '北美零售价',
      rightPriceListId: '2',
      rightPriceListCode: 'NA-BULK',
      rightPriceListName: '北美批量价',
      shopCode: 'AMZ-US',
      siteCode: 'US',
      legalEntityCode: 'US-HQ',
      overlapFrom: '2026-10-01T00:00:00Z',
      overlapTo: null,
      leftPriority: 10,
      rightPriority: 10,
      reason: '不可唯一解析',
      blocking: true,
    };
    expect(conflictTitle(detail)).toBe('NA-RETAIL ↔ NA-BULK');
    expect(conflictContext(detail)).toContain('店铺 AMZ-US');
    expect(conflictContext(detail)).toContain('站点 US');
    expect(conflictContext(detail)).toContain('法人 US-HQ');
  });

  it('时间格式化不会把空值渲染成 1970', () => {
    expect(formatInstant(null)).toBe('—');
    expect(formatInstant('2026-10-01T09:00:00Z')).toMatch(/^2026-10-01/);
  });
});
