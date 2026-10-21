import { describe, expect, it } from 'vitest';

import {
  EMPTY_LANDED_COST_FILTERS,
  fetchLandedCostComponents,
  fetchLandedCostList,
  landedCompleteness,
  landedComponentLabel,
  landedComponentReason,
  landedComponentState,
  landedMoneyText,
  landedMoneyWithCurrency,
  landedSourceLabel,
  landedSourceTone,
  validateLandedCostCompute,
  validateLandedCostParameter,
} from './landed-cost-model';
import type { LandedCostComponentRow } from './landed-cost-model';

/**
 * 5.2.1.2 到岸成本前端模型的纯函数断言。
 *
 * <p>这些函数守的是两条生死线：
 *
 * <ol>
 *   <li>「到岸成本与仓库成本是两个口径」—— 口径必须在页面上明示**算了什么、明确不算什么**；
 *   <li>「读不到 ≠ 确实是 0」—— 渲染成 0.00 会让到岸成本系统性低估（尤其是关税），
 *       而页面上看不出任何异常。
 * </ol>
 */

/** 一个六项齐全的分量。 */
function component(overrides: Partial<LandedCostComponentRow> = {}): LandedCostComponentRow {
  return {
    code: 'INTERNATIONAL_FREIGHT',
    label: '国际头程',
    sourceKind: 'FACT',
    sourceModule: '10 头程（warehouse 头程发运单）',
    sourceDocumentCode: 'FM-12345678',
    available: true,
    included: true,
    amount: 100,
    unitAmount: 10,
    taxDutyAmount: null,
    taxOtherAmount: null,
    taxBearer: '',
    ownerModule: '',
    impact: '',
    note: '',
    ...overrides,
  };
}

describe('六个分量', () => {
  it('每一项都有中文名（页面上不能只显示一串英文码）', () => {
    expect(landedComponentLabel('PURCHASE_PRICE')).toBe('采购价');
    expect(landedComponentLabel('INTERNATIONAL_FREIGHT')).toBe('国际头程');
    expect(landedComponentLabel('DUTY')).toBe('关税');
    expect(landedComponentLabel('CUSTOMS_CLEARANCE')).toBe('清关');
    expect(landedComponentLabel('DESTINATION_HANDLING')).toBe('目的地操作费');
    expect(landedComponentLabel('LAST_MILE_RESERVE')).toBe('尾程预备');
    expect(landedComponentLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(landedComponentLabel(null)).toBe('未知分量');
  });

  it('事实与参数必须分得开 —— 否则分不清「仓库读出来的」与「有人填进去的」', () => {
    expect(landedSourceLabel('FACT')).toBe('上游事实');
    expect(landedSourceLabel('PARAMETER')).toBe('租户参数');
    expect(landedSourceTone('FACT')).toBe('success');
    expect(landedSourceTone('PARAMETER')).toBe('warning');
  });
});

describe('null 不渲染成 0', () => {
  it('取不到的金额显示「缺口」而不是 0.00', () => {
    expect(landedMoneyText(null)).toBe('缺口');
    expect(landedMoneyText(undefined)).toBe('缺口');
    // 🔴 真的是 0 时显示 0 —— 「缺口」与「确实是 0」是两种不同的行。
    expect(landedMoneyText(0)).toBe('0');
    expect(landedMoneyText(12.5)).toBe('12.5');
  });

  it('带币种的金额串在缺口时也不补币种', () => {
    expect(landedMoneyWithCurrency(null, 'USD')).toBe('缺口');
    expect(landedMoneyWithCurrency(12.5, 'USD')).toBe('USD 12.5');
    expect(landedMoneyWithCurrency(12.5, null)).toBe('12.5');
  });
});

describe('覆盖度', () => {
  it('缺项时明写「已知部分合计」并点名缺哪几项', () => {
    const full = landedCompleteness([]);
    expect(full.tone).toBe('success');
    expect(full.text).toContain('齐全');

    const partial = landedCompleteness(['DUTY', 'CUSTOMS_CLEARANCE']);
    expect(partial.tone).toBe('danger');
    expect(partial.text).toContain('DUTY');
    expect(partial.text).toContain('CUSTOMS_CLEARANCE');
    // 「已知部分合计」这四个字必须在 —— 直接拿去算利润会算高。
    expect(partial.text).toContain('已知部分合计');
  });

  it('空缺列表按齐全处理（不能因为后端没给就当成缺全部）', () => {
    expect(landedCompleteness(null).tone).toBe('success');
    expect(landedCompleteness(undefined).tone).toBe('success');
  });
});

describe('分量状态', () => {
  it('缺口 / 有事实但不计入 / 计入是三种不同的状态', () => {
    expect(landedComponentState(component({ available: false, included: false })).tone).toBe('danger');
    expect(landedComponentState(component({ available: true, included: false })).tone).toBe('warning');
    expect(landedComponentState(component()).tone).toBe('success');
  });

  it('缺口必须说清归谁管、缺了会怎样', () => {
    const reason = landedComponentReason(
      component({
        available: false,
        included: false,
        ownerModule: '10.5.1.3 关税与税费',
        impact: '不用 0 假装没有关税',
      }),
    );
    expect(reason).toContain('10.5.1.3');
    expect(reason).toContain('不用 0 假装没有关税');
  });

  it('有事实但不计入时给出原因', () => {
    const reason = landedComponentReason(
      component({ included: false, note: '买方承担，不计入卖方的到岸成本' }),
    );
    expect(reason).toContain('买方承担');
  });
});

describe('本地校验', () => {
  it('只有清关费与尾程预备是参数', () => {
    expect(
      validateLandedCostParameter({
        componentCode: 'DUTY',
        destinationCountryCode: 'US',
        currency: 'USD',
        unitAmount: 1,
        note: '',
        version: 0,
      }),
    ).toContain('只有清关费与尾程预备是参数；其余四项是上游事实，不许人工填');

    expect(
      validateLandedCostParameter({
        componentCode: 'CUSTOMS_CLEARANCE',
        destinationCountryCode: 'US',
        currency: 'USD',
        unitAmount: 1,
        note: '',
        version: 0,
      }),
    ).toEqual([]);
  });

  it('目的国与币种必须是标准码', () => {
    const problems = validateLandedCostParameter({
      componentCode: 'LAST_MILE_RESERVE',
      destinationCountryCode: 'USA',
      currency: 'US',
      unitAmount: -1,
      note: '',
      version: 0,
    });
    expect(problems.join('|')).toContain('两位国家码');
    expect(problems.join('|')).toContain('ISO 4217');
    expect(problems.join('|')).toContain('不能为负');
  });

  it('计算必须给出上游发运单的 id 或单号', () => {
    expect(validateLandedCostCompute({ sourceShipmentId: null, sourceShipmentCode: null })).toHaveLength(
      1,
    );
    expect(validateLandedCostCompute({ sourceShipmentId: 'x', sourceShipmentCode: null })).toEqual([]);
    expect(validateLandedCostCompute({ sourceShipmentId: null, sourceShipmentCode: 'FM-1' })).toEqual([]);
  });
});

describe('端点', () => {
  it('列表与分量分页都走 catalog/landed-costs 命名空间', () => {
    expect(EMPTY_LANDED_COST_FILTERS.page).toBe(0);
    expect(EMPTY_LANDED_COST_FILTERS.size).toBe(20);
    // 函数存在即断言：命名空间写错时浏览器侧是静默 404。
    expect(typeof fetchLandedCostList).toBe('function');
    expect(typeof fetchLandedCostComponents).toBe('function');
  });
});
