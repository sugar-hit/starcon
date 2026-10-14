import { describe, expect, it } from 'vitest';
import type {
  WarehouseAllocationCandidateRow,
  WarehouseAllocationRuleRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  allocationStatusLabel,
  blockReasonLabel,
  canAdvance,
  candidateChosen,
  crossLegalEntityWarning,
  crossesLegalEntity,
  dimensionUnavailableReason,
  fallbackPolicyLabel,
  holdMentions,
  holdReasonLabel,
  isPartial,
  moneyText,
  qtyText,
  reasonCodeLabel,
  scoreBreakdown,
  timeText,
  weightsSumToOne,
} from './warehouse-allocation-model';

/**
 * 9.3.2.1 分仓规则前端模型的展示层断言。
 *
 * <p>重点钉两件最容易做错的事：
 *
 * <ul>
 *   <li><b>货权必须显形</b>：跨法人发货意味着收入记在别的法人名下，
 *       模型必须能独立把它算出来并给出一句人话 —— 不能只靠「选中仓那列的法人不一样」
 *       让用户自己看出来。</li>
 *   <li><b>「没有估算」不等于「为 0」</b>：0 是确定结论，null 是缺口，
 *       两者画成同一个 0 会让人以为这单确实没货。</li>
 * </ul>
 */

function candidate(
  overrides: Partial<WarehouseAllocationCandidateRow> = {},
): WarehouseAllocationCandidateRow {
  return {
    warehouseRef: 'WH-CN-SH',
    warehouseName: '上海仓',
    legalEntityCode: 'CN-ENT-A',
    regionCode: 'CN-EAST',
    warehousePriority: 10,
    availableQty: 200,
    shippingCost: 18,
    leadTimeDays: 5,
    scoreStock: 1,
    scoreCost: 1,
    scoreLeadTime: 1,
    scoreLegalEntity: 1,
    scoreRegion: 1,
    totalScore: 1,
    legalEntityMatch: true,
    feasible: true,
    infeasibleReasonCode: '',
    infeasibleReason: '',
    chosen: false,
    allocatedQty: null,
    ...overrides,
  } as WarehouseAllocationCandidateRow;
}

function rule(overrides: Partial<WarehouseAllocationRuleRow> = {}): WarehouseAllocationRuleRow {
  return {
    id: 'r1',
    ruleCode: 'RULE-DEFAULT',
    ruleName: '默认',
    priority: 10,
    scopePlatformCode: '*',
    scopeDestinationCountryCode: '*',
    selectionPolicy: 'HIGHEST_SCORE',
    fallbackPolicy: 'SUSPEND',
    weightStock: 0.25,
    weightCost: 0.25,
    weightLeadTime: 0.15,
    weightLegalEntity: 0.25,
    weightRegion: 0.1,
    requireLegalEntityMatch: true,
    legalEntityPolicy: 'ORDER_OWNER_FIRST',
    preferredLegalEntityCode: '',
    maxLeadTimeDays: null,
    currencyCode: 'USD',
    excludedWarehouseRefs: '',
    effectiveFrom: '2026-01-01T00:00:00Z',
    effectiveTo: null,
    notes: '',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  } as WarehouseAllocationRuleRow;
}

describe('货权参与判定（蓝图加粗句）', () => {
  it('法人与订单主体不同时判定为跨法人', () => {
    expect(
      crossesLegalEntity({
        crossLegalEntity: true,
        chosenLegalEntityCode: 'US-ENT-B',
        orderLegalEntityCode: 'CN-ENT-A',
      }),
    ).toBe(true);
  });

  it('标记缺失但两个法人不同也算跨法人（不靠单一字段）', () => {
    expect(
      crossesLegalEntity({
        crossLegalEntity: false,
        chosenLegalEntityCode: 'US-ENT-B',
        orderLegalEntityCode: 'CN-ENT-A',
      }),
    ).toBe(true);
  });

  it('同法人且未标记时不判为跨法人', () => {
    expect(
      crossesLegalEntity({
        crossLegalEntity: false,
        chosenLegalEntityCode: 'CN-ENT-A',
        orderLegalEntityCode: 'CN-ENT-A',
      }),
    ).toBe(false);
  });

  it('跨法人时给出「收入记在谁名下」的人话', () => {
    const warning = crossLegalEntityWarning({
      chosenLegalEntityCode: 'US-ENT-B',
      orderLegalEntityCode: 'CN-ENT-A',
    });
    expect(warning).toContain('US-ENT-B');
    expect(warning).toContain('CN-ENT-A');
    expect(warning).toContain('收入');
  });

  it('同法人或挂起（无发货仓）时不报警', () => {
    expect(
      crossLegalEntityWarning({
        chosenLegalEntityCode: 'CN-ENT-A',
        orderLegalEntityCode: 'CN-ENT-A',
      }),
    ).toBeNull();
    expect(
      crossLegalEntityWarning({ chosenLegalEntityCode: null, orderLegalEntityCode: 'CN-ENT-A' }),
    ).toBeNull();
  });
});

describe('挂起清单', () => {
  it('排除原因码有中文名，未知码原样返回而不是空白', () => {
    expect(blockReasonLabel('LEGAL_ENTITY_MISMATCH')).toBe('货权法人不符');
    expect(blockReasonLabel('NOT_A_CODE')).toBe('NOT_A_CODE');
    expect(blockReasonLabel('')).toBe('');
  });

  it('挂起原因有中文名', () => {
    expect(holdReasonLabel('BLOCKED_BY_LEGAL_ENTITY')).toBe('全部仓货权法人不符');
    expect(holdReasonLabel('PARTIAL_SHORTFALL')).toBe('部分分配差额未满足');
  });

  it('排除清单能按仓号对上（仓A=…）', () => {
    const detail = 'WH-CN-SH=货权法人不符；WH-US-LAX=货权法人不符';
    expect(holdMentions(detail, 'WH-CN-SH')).toBe(true);
    expect(holdMentions(detail, 'WH-CN-BJ')).toBe(false);
    expect(holdMentions(null, 'WH-CN-SH')).toBe(false);
  });

  it('兜底策略的默认项写明「不静默兜底」而不是「失败」', () => {
    expect(fallbackPolicyLabel('SUSPEND')).toContain('不静默兜底');
    expect(fallbackPolicyLabel('ALLOW_PARTIAL')).toContain('部分分配');
    expect(fallbackPolicyLabel('CROSS_LEGAL_ENTITY')).toContain('跨法人');
  });
});

describe('「没有估算」不等于「为 0」', () => {
  it('null 显示成破折号而不是 0', () => {
    expect(qtyText(null)).toBe('—');
    expect(qtyText(undefined)).toBe('—');
    expect(qtyText(0)).toBe('0');
    expect(qtyText(1234.5)).toContain('1,234.5');
  });

  it('金额 null 同样显示成破折号，且不在前端二次取整', () => {
    expect(moneyText(null)).toBe('—');
    // 12.345 已在后端按 HALF_UP 到分落成 12.35；这里若再 round 会变成 12.34/12.35 抖动。
    expect(moneyText(12.35)).toBe('12.35 USD');
  });

  it('形状不符合预期的时间返回原串，而不是错位的时间', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText('not-a-date')).toBe('not-a-date');
    expect(timeText('2026-10-14T08:30:00+08:00')).toContain('2026-10-14');
  });
});

describe('五维得分与规则权重', () => {
  it('综合分等于五维加权之和', () => {
    const r = rule();
    const c = candidate({
      scoreStock: 1,
      scoreCost: 0.5,
      scoreLeadTime: 0.4,
      scoreLegalEntity: 1,
      scoreRegion: 0.5,
      totalScore: 0,
    });
    const breakdown = scoreBreakdown(c, r);
    expect(breakdown.stock).toBeCloseTo(0.25, 4);
    expect(breakdown.cost).toBeCloseTo(0.125, 4);
    expect(breakdown.leadTime).toBeCloseTo(0.06, 4);
    expect(breakdown.legalEntity).toBeCloseTo(0.25, 4);
    expect(breakdown.region).toBeCloseTo(0.05, 4);
    expect(breakdown.total).toBeCloseTo(0.735, 4);
  });

  it('权重合计不为 1 时前端也拦一道', () => {
    expect(weightsSumToOne([0.25, 0.25, 0.15, 0.25, 0.1])).toBe(true);
    expect(weightsSumToOne([0.5, 0.5, 0.5, 0, 0])).toBe(false);
  });
});

describe('状态与选中', () => {
  it('挂起与已下发是终态，不能推进', () => {
    expect(canAdvance('ALLOCATED')).toBe(true);
    expect(canAdvance('PARTIAL')).toBe(true);
    expect(canAdvance('CONFIRMED')).toBe(false);
    expect(canAdvance('SUSPENDED')).toBe(false);
    expect(canAdvance('RELEASED')).toBe(false);
  });

  it('部分分配单独识别', () => {
    expect(isPartial('PARTIAL')).toBe(true);
    expect(isPartial('ALLOCATED')).toBe(false);
  });

  it('选中判定只认 chosen===true', () => {
    expect(candidateChosen(candidate({ chosen: true }))).toBe(true);
    expect(candidateChosen(candidate({ chosen: false }))).toBe(false);
  });

  it('状态与原因码都有中文名，未知值原样返回', () => {
    expect(allocationStatusLabel('SUSPENDED')).toBe('已挂起');
    expect(allocationStatusLabel('WHAT')).toBe('WHAT');
    expect(reasonCodeLabel('NO_ELIGIBLE_WAREHOUSE')).toBe('无可行仓，已挂起');
  });
});

describe('能力维度', () => {
  it('不可用维度返回原因，可用维度返回 null', () => {
    const dims = [
      { code: 'A', available: true, unavailableReason: null },
      { code: 'B', available: false, unavailableReason: '上游未交付' },
    ];
    expect(dimensionUnavailableReason(dims, 'A')).toBeNull();
    expect(dimensionUnavailableReason(dims, 'B')).toBe('上游未交付');
    expect(dimensionUnavailableReason(dims, 'C')).toBeNull();
  });
});
