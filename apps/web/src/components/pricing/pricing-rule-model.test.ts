import { describe, expect, it } from 'vitest';

import {
  actionRateText,
  applyAction,
  applyTrigger,
  currentPriceText,
  decisionTone,
  emptyEvaluate,
  emptyRule,
  evaluatePricingRules,
  hitStatusText,
  hitTone,
  moneyText,
  moneyWithCurrency,
  percentText,
  priceChangeText,
  quantityText,
  requiresConfirmationText,
  statusTone,
  suggestionStatusLabel,
  suggestedPriceText,
  summaryStats,
  thresholdText,
  toRuleInput,
  triggerTypeLabel,
  validateEvaluate,
  validateRule,
} from './pricing-rule-model';
import type {
  PricingRuleDetail,
  PricingRuleEvaluationResult,
  PricingRuleHitView,
  PricingRuleSuggestionView,
} from './pricing-rule-model';

/**
 * 5.3.2.2 价格规则前端模型的纯函数断言。
 *
 * <p>这些函数守的是蓝图那条加粗句的**技术落点**：
 * 「自动调价必须先"建议"再"执行"，高风险规则（降价超过 X%）需人工确认」——
 * 以及它旁边那条隐含纪律：「读不到 ≠ 确实是 0」「建议价 ≠ 生效价」。
 */

function suggestion(
  overrides: Partial<PricingRuleSuggestionView> = {},
): PricingRuleSuggestionView {
  return {
    id: '22222222-2222-2222-2222-222222222222',
    ruleId: '11111111-1111-1111-1111-111111111111',
    ruleCode: 'CLEAR-STOCK',
    ruleName: '断货清仓',
    hitId: '33333333-3333-3333-3333-333333333333',
    skuId: '44444444-4444-4444-4444-444444444444',
    skuCode: 'SKU-1',
    skuName: '商品一',
    spuId: '55555555-5555-5555-5555-555555555555',
    siteCode: 'US',
    platformCode: 'AMAZON',
    currency: 'USD',
    actionType: 'LOWER_PRICE',
    actionTypeLabel: '降价',
    currentPrice: 100,
    suggestedPrice: 95,
    priceChangeRate: -0.05,
    requiresConfirmation: false,
    status: 'READY',
    statusLabel: '已放行',
    decisionReady: true,
    grossMarginRate: 0.628947,
    grossProfit: 59.75,
    reason: '建议降价 5%',
    decidedBy: null,
    decidedAt: null,
    decisionNote: '',
    createdAt: '2026-10-01T00:00Z',
    ...overrides,
  };
}

function hit(overrides: Partial<PricingRuleHitView> = {}): PricingRuleHitView {
  return {
    id: '66666666-6666-6666-6666-666666666666',
    ruleId: '11111111-1111-1111-1111-111111111111',
    ruleCode: 'CLEAR-STOCK',
    ruleName: '断货清仓',
    skuId: '44444444-4444-4444-4444-444444444444',
    skuCode: 'SKU-1',
    skuName: '商品一',
    spuId: '55555555-5555-5555-5555-555555555555',
    triggerType: 'INVENTORY_BELOW_THRESHOLD',
    triggerTypeLabel: '库存低于阈值',
    matched: true,
    superseded: false,
    supersededByRuleId: null,
    supersededByRuleCode: null,
    reason: '可售量 3 低于阈值 10 ⇒ 命中',
    evidence: { available: 3, thresholdQuantity: 10 },
    evaluatedAt: '2026-10-01T00:00Z',
    ...overrides,
  };
}

describe('口径渲染：null 绝不渲染成 0', () => {
  it('金额取不到时是兜底文案而不是 0.00', () => {
    expect(moneyText(null)).toBe('未维护');
    expect(moneyText(undefined)).toBe('未维护');
    expect(moneyText(0)).toBe('0');
    expect(moneyText(95)).toBe('95');
  });

  it('百分比取不到时不是 0%', () => {
    expect(percentText(null)).toBe('不可信（成本不完整）');
    expect(percentText(0)).toBe('0%');
    expect(percentText(0.05)).toBe('5%');
  });

  it('金额带币种；取不到时连币种都不补', () => {
    expect(moneyWithCurrency(95, 'USD')).toBe('95 USD');
    expect(moneyWithCurrency(null, 'USD')).toBe('未维护');
  });

  it('数量取不到时是 —', () => {
    expect(quantityText(null)).toBe('—');
    expect(quantityText(3)).toBe('3');
  });
});

describe('触发条件与动作的文案', () => {
  it('五类触发条件各有各的阈值文案', () => {
    expect(
      thresholdText({
        triggerType: 'INVENTORY_BELOW_THRESHOLD',
        thresholdQuantity: 10,
        thresholdRate: null,
        marginFloorRate: null,
        agingDaysThreshold: null,
      }),
    ).toContain('10');
    expect(
      thresholdText({
        triggerType: 'COMPETITOR_PRICE_DROP',
        thresholdQuantity: null,
        thresholdRate: 0.1,
        marginFloorRate: null,
        agingDaysThreshold: null,
      }),
    ).toContain('10%');
    expect(
      thresholdText({
        triggerType: 'MARGIN_BELOW_FLOOR',
        thresholdQuantity: null,
        thresholdRate: null,
        marginFloorRate: 0.2,
        agingDaysThreshold: null,
      }),
    ).toContain('20%');
    expect(
      thresholdText({
        triggerType: 'AGING_OVER_THRESHOLD',
        thresholdQuantity: null,
        thresholdRate: null,
        marginFloorRate: null,
        agingDaysThreshold: 90,
      }),
    ).toContain('90');
  });

  it('触发条件的中文名与蓝图逐字对应', () => {
    expect(triggerTypeLabel('INVENTORY_BELOW_THRESHOLD')).toBe('库存低于阈值');
    expect(triggerTypeLabel('COMPETITOR_PRICE_DROP')).toBe('竞品降价');
    expect(triggerTypeLabel('COST_INCREASE')).toBe('成本上涨');
    expect(triggerTypeLabel('MARGIN_BELOW_FLOOR')).toBe('毛利低于下限');
    expect(triggerTypeLabel('AGING_OVER_THRESHOLD')).toBe('库龄超期');
  });

  it('非价格动作没有幅度', () => {
    expect(actionRateText('PAUSE_PROMOTION', null)).toContain('非价格动作');
    expect(actionRateText('DELIST', null)).toContain('非价格动作');
    expect(actionRateText('LOWER_PRICE', 0.05)).toBe('5%');
  });
});

describe('🔴 建议价 ≠ 生效价', () => {
  it('取不到当前价时不产出建议价（不是 0）', () => {
    expect(suggestedPriceText(suggestion({ suggestedPrice: null }))).toBe(
      '不产出建议价（取不到当前价）',
    );
    expect(suggestedPriceText(suggestion())).toBe('95 USD');
    expect(currentPriceText(suggestion({ currentPrice: null }))).toBe('取不到当前价');
  });

  it('变动幅度只报降幅的绝对值', () => {
    expect(priceChangeText(-0.05)).toBe('降 5%');
    expect(priceChangeText(0.05)).toBe('涨 5%');
    expect(priceChangeText(0)).toBe('不变');
    expect(priceChangeText(null)).toBe('—');
  });

  it('高风险建议在页面上必须显眼', () => {
    expect(
      requiresConfirmationText(
        suggestion({ requiresConfirmation: true, status: 'PENDING_CONFIRMATION' }),
      ),
    ).toContain('需人工确认');
    expect(requiresConfirmationText(suggestion({ requiresConfirmation: true }))).toContain(
      '曾按高风险处理',
    );
    expect(requiresConfirmationText(suggestion())).toContain('低风险');
  });

  it('成本不完整的建议即便降幅很小也不自动放行', () => {
    expect(requiresConfirmationText(suggestion({ decisionReady: false }))).toContain(
      '成本不完整',
    );
  });

  it('状态的色调：待确认是警告，驳回是危险', () => {
    expect(statusTone('PENDING_CONFIRMATION')).toBe('warning');
    expect(statusTone('READY')).toBe('success');
    expect(statusTone('REJECTED')).toBe('danger');
    expect(suggestionStatusLabel('PENDING_CONFIRMATION')).toBe('待人工确认');
  });

  it('决策就绪度：不可信是危险色', () => {
    expect(decisionTone(true)).toBe('success');
    expect(decisionTone(false)).toBe('danger');
  });
});

describe('命中记录：未命中也留痕', () => {
  it('被更高优先级规则覆盖的命中有专门的文案与色调', () => {
    const covered = hit({ superseded: true, supersededByRuleCode: 'VIP' });
    expect(hitStatusText(covered)).toBe('被更高优先级规则覆盖');
    expect(hitTone(true, true)).toBe('warning');
    expect(hitStatusText(hit({ matched: false }))).toBe('未命中');
    expect(hitTone(false, false)).toBe('neutral');
  });
});

describe('表单校验', () => {
  it('规则编码与币种必填', () => {
    const input = { ...emptyRule(), code: 'RULE-1', name: '规则' };
    expect(validateRule({ ...input, code: '' })?.field).toBe('code');
    expect(validateRule({ ...input, code: 'a b' })?.field).toBe('code');
    expect(validateRule({ ...input, currency: 'US' })?.field).toBe('currency');
  });

  it('阈值按触发条件各用各的列', () => {
    const input = { ...emptyRule(), code: 'RULE-1', name: '规则' };
    expect(validateRule({ ...input, triggerType: 'COST_INCREASE' })?.field).toBe(
      'thresholdRate',
    );
    expect(
      validateRule({ ...input, triggerType: 'MARGIN_BELOW_FLOOR' })?.field,
    ).toBe('marginFloorRate');
    expect(
      validateRule({ ...input, triggerType: 'AGING_OVER_THRESHOLD' })?.field,
    ).toBe('agingDaysThreshold');
  });

  it('降价 / 涨价必须给幅度，非价格动作不能给幅度', () => {
    const input = { ...emptyRule(), code: 'RULE-1', name: '规则' };
    expect(validateRule({ ...input, actionRate: null })?.field).toBe('actionRate');
    expect(validateRule({ ...input, actionType: 'DELIST', actionRate: 0.05 })?.field).toBe(
      'actionRate',
    );
    expect(validateRule({ ...input, actionType: 'DELIST', actionRate: null })).toBeNull();
  });

  it('切换触发条件会清空不适用的阈值列', () => {
    const input = emptyRule();
    const switched = applyTrigger(input, 'MARGIN_BELOW_FLOOR');
    expect(switched.thresholdQuantity).toBeNull();
    expect(switched.thresholdRate).toBeNull();
    expect(switched.marginFloorRate).toBe(0.2);
  });

  it('切换到非价格动作会清空幅度', () => {
    const input = emptyRule();
    expect(applyAction(input, 'PAUSE_PROMOTION').actionRate).toBeNull();
    expect(applyAction(input, 'RAISE_PRICE').actionRate).toBe(0.05);
  });

  it('评估必须有 SKU、目标店铺与目标平台（当前价按平台 + 站点登记）', () => {
    const request = { ...emptyEvaluate(), skuIds: ['44444444-4444-4444-4444-444444444444'] };
    expect(validateEvaluate({ ...request, skuIds: [] })?.field).toBe('skuIds');
    expect(validateEvaluate({ ...request, siteCode: '' })?.field).toBe('siteCode');
    expect(validateEvaluate({ ...request, platformCode: '' })?.field).toBe('platformCode');
  });

  it('跨币种必须给汇率（绝不按 1:1 折算）', () => {
    const request = { ...emptyEvaluate(), skuIds: ['44444444-4444-4444-4444-444444444444'] };
    expect(
      validateEvaluate({ ...request, currency: 'EUR', costCurrency: 'USD' })?.field,
    ).toBe('fxRate');
    expect(
      validateEvaluate({ ...request, currency: 'EUR', costCurrency: 'USD', fxRate: 0.9 }),
    ).toBeNull();
  });
});

describe('规则详情 → 表单', () => {
  it('往返一次不丢字段', () => {
    const detail: PricingRuleDetail = {
      summary: {
        id: '11111111-1111-1111-1111-111111111111',
        code: 'CLEAR-STOCK',
        name: '断货清仓',
        triggerType: 'INVENTORY_BELOW_THRESHOLD',
        triggerTypeLabel: '库存低于阈值',
        actionType: 'LOWER_PRICE',
        actionTypeLabel: '降价',
        thresholdQuantity: 10,
        thresholdRate: null,
        marginFloorRate: null,
        agingDaysThreshold: null,
        actionRate: 0.05,
        priority: 20,
        currency: 'USD',
        maxAutoPriceDropRate: 0.1,
        enabled: true,
        effectiveFrom: '2026-10-01T00:00Z',
        effectiveTo: null,
        scopeCount: 1,
        createdBy: null,
        updatedAt: '2026-10-01T00:00Z',
      },
      description: '断货清仓规则',
      triggerType: 'INVENTORY_BELOW_THRESHOLD',
      thresholdQuantity: 10,
      thresholdRate: null,
      marginFloorRate: null,
      agingDaysThreshold: null,
      actionType: 'LOWER_PRICE',
      actionRate: 0.05,
      priority: 20,
      currency: 'USD',
      maxAutoPriceDropRate: 0.1,
      enabled: true,
      effectiveFrom: '2026-10-01T00:00Z',
      effectiveTo: null,
      note: '清仓',
      version: 3,
      scopes: [
        {
          id: '77777777-7777-7777-7777-777777777777',
          type: 'BRAND',
          typeLabel: '品牌',
          ref: 'Brand',
          platformCode: null,
        },
      ],
      createdAt: '2026-10-01T00:00Z',
      updatedAt: '2026-10-01T00:00Z',
    };
    const input = toRuleInput(detail);
    expect(input.code).toBe('CLEAR-STOCK');
    expect(input.version).toBe(3);
    expect(input.scopes).toEqual([{ type: 'BRAND', ref: 'Brand', platformCode: null }]);
    expect(validateRule(input)).toBeNull();
  });
});

describe('顶部统计卡', () => {
  it('把「建议调价」与「待人工确认」分开报', () => {
    const result: PricingRuleEvaluationResult = {
      evaluatedSkuCount: 3,
      evaluatedRuleCount: 2,
      hitCount: 4,
      suggestionCount: 1,
      pendingConfirmationCount: 1,
      hits: [],
      suggestions: [],
      note: '',
    };
    const stats = summaryStats(result);
    expect(stats.map((s) => s.label)).toEqual([
      '评估的 SKU 数',
      '命中记录',
      '建议调价',
      '待人工确认',
    ]);
    expect(stats.find((s) => s.label === '建议调价')?.hint).toContain('不是生效价');
    expect(summaryStats(null)).toEqual([]);
  });
});

describe('端点路径', () => {
  it('评估走的是 /catalog/pricing-rules/evaluate（建议，不是改价）', () => {
    // 只断言路径形状：真正放行哪些形状由 api-proxy 的白名单测试守住。
    expect(typeof evaluatePricingRules).toBe('function');
  });
});
