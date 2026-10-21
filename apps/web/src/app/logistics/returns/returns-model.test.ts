import { describe, expect, it } from 'vitest';
import {
  DISPOSITION_METHODS,
  EMPTY_DISPOSITION_METHOD,
  blockingReasons,
  buildDecisionPayload,
  canSelectMethod,
  costTypeLabel,
  dispositionDistribution,
  dispositionMethodLabel,
  dispositionStateLabel,
  emptyDecisionForm,
  methodAllowed,
  moneyText,
  quantityText,
  restrictionBasisLabel,
  restrictionSubjectLabel,
  validateDecisionForm,
  warehouseTypeLabel,
  type ReturnDispositionEligibility,
  type ReturnDispositionOverview,
} from './returns-model';

/**
 * 10.7.1.1 退货接收与处置决策的前端模型断言。
 *
 * <p>🔴 本文件最要紧的一条是「<b>处置方式必须显式</b>」：表单初值必须是**空串**
 * 而不是 'RESALE'，校验必须在空串时拒，构建请求体必须在空串时返回 error 而不是
 * 悄悄塞一个去向。蓝图加粗句「默认按『可再售』处理会高估回收价值，转卖/弃置决策
 * 必须显式」在浏览器里最容易退化的地方正是这个下拉框。
 */
describe('10.7.1.1 处置方式必须显式给出', () => {
  it('表单初值是空串，不是 RESALE', () => {
    const form = emptyDecisionForm('reg-1');
    expect(form.dispositionMethod).toBe(EMPTY_DISPOSITION_METHOD);
    expect(form.dispositionMethod).not.toBe('RESALE');
    expect(form.registrationId).toBe('reg-1');
  });

  it('没选处置方式 ⇒ 校验拒，且理由写明「不替你默认」', () => {
    const form = { ...emptyDecisionForm('reg-1'), decidedQty: '3', decisionBasis: '看过了' };
    const error = validateDecisionForm(form);
    expect(error).toContain('必须显式选择处置方式');
    expect(error).toContain('不替你默认');
  });

  it('没选处置方式 ⇒ 请求体不成立（不会悄悄塞一个去向）', () => {
    const form = { ...emptyDecisionForm('reg-1'), decidedQty: '3', decisionBasis: '看过了' };
    const payload = buildDecisionPayload(form);
    expect('error' in payload).toBe(true);
    if ('error' in payload) expect(payload.error).toContain('处置方式');
  });

  it('显式选了去向 ⇒ 请求体成立，且带的是人给的那一个', () => {
    const form = {
      ...emptyDecisionForm('reg-1'),
      dispositionMethod: 'RETURN_TO_COUNTRY',
      decidedQty: '3',
      decisionBasis: '残次品，退运回国重新入库',
    };
    expect(validateDecisionForm(form)).toBe('');
    const payload = buildDecisionPayload(form);
    expect('error' in payload).toBe(false);
    if (!('error' in payload)) {
      expect(payload.dispositionMethod).toBe('RETURN_TO_COUNTRY');
      expect(payload.decidedQty).toBe(3);
      expect(payload.decisionBasis).toBe('残次品，退运回国重新入库');
    }
  });

  it('三个去向都在白名单里，且互不相同', () => {
    expect(DISPOSITION_METHODS).toEqual(['RESALE', 'RETURN_TO_COUNTRY', 'DISCARD']);
  });

  it('判定依据为空 ⇒ 拒（转卖/退运/弃置对利润影响巨大）', () => {
    const form = {
      ...emptyDecisionForm('reg-1'),
      dispositionMethod: 'RESALE',
      decidedQty: '1',
      decisionBasis: '   ',
    };
    expect(validateDecisionForm(form)).toContain('判定依据');
  });

  it('数量非法 ⇒ 拒', () => {
    for (const qty of ['', '0', '-1', 'abc']) {
      const form = {
        ...emptyDecisionForm('reg-1'),
        dispositionMethod: 'DISCARD',
        decidedQty: qty,
        decisionBasis: '销毁',
      };
      expect(validateDecisionForm(form), `数量「${qty}」必须被拒`).toContain('处置数量');
    }
  });
});

/** 构造一份服务端的可行性结论（页面的判据只有这一份，不自己比对）。 */
function eligibility(
  methods: { method: string; allowed: boolean; blockingReasons?: string[] }[],
): ReturnDispositionEligibility {
  return {
    registrationId: 'reg-1',
    skuCode: 'SKU-1',
    skuName: '样机',
    receivedQty: 5,
    qualityVerdict: 'DEFECTIVE',
    qualityVerdictLabel: '残次',
    qualityVerdictAt: '2026-03-01T02:00:00Z',
    qualityNote: '上游 11.2.3.1 的质检结论',
    resaleRestricted: false,
    resaleRestrictionNote: '没有命中任何限制',
    methods: methods.map((m) => ({
      method: m.method,
      methodLabel: dispositionMethodLabel(m.method),
      allowed: m.allowed,
      blockingReasons: m.blockingReasons ?? [],
      note: '',
    })),
    evaluatedAt: '2026-03-04T00:00:00Z',
    upstreamNote: '',
  };
}

describe('10.7.1.1 转销售的两道否决由服务端给，页面只负责显示', () => {
  it('质检判成残次 ⇒ 转销售被挡住，挡住的理由原样带出来', () => {
    const e = eligibility([
      { method: 'RESALE', allowed: false, blockingReasons: ['上游 11.2.3.1 判定为「残次」：不允许转销售'] },
      { method: 'RETURN_TO_COUNTRY', allowed: true },
      { method: 'DISCARD', allowed: true },
    ]);
    expect(methodAllowed(e, 'RESALE')).toBe(false);
    expect(canSelectMethod(e, 'RESALE')).toBe(false);
    expect(blockingReasons(e, 'RESALE')).toEqual([
      '上游 11.2.3.1 判定为「残次」：不允许转销售',
    ]);
    expect(methodAllowed(e, 'RETURN_TO_COUNTRY')).toBe(true);
  });

  it('命中再销售限制 ⇒ 转销售被挡住', () => {
    const e = eligibility([
      { method: 'RESALE', allowed: false, blockingReasons: ['命中本租户再销售限制：BRAND BRAND-A'] },
      { method: 'RETURN_TO_COUNTRY', allowed: true },
      { method: 'DISCARD', allowed: true },
    ]);
    expect(canSelectMethod(e, 'RESALE')).toBe(false);
    expect(blockingReasons(e, 'RESALE')[0]).toContain('BRAND-A');
  });

  it('还没有服务端结论 ⇒ 三个去向都不许选（拿不准不是「大概率能卖」）', () => {
    expect(canSelectMethod(null, 'RESALE')).toBe(false);
    expect(canSelectMethod(null, 'RETURN_TO_COUNTRY')).toBe(false);
    expect(canSelectMethod(null, 'DISCARD')).toBe(false);
  });
});

describe('10.7.1.1 展示口径', () => {
  it('去向 / 仓型 / 成本类型 / 限制口径都有中文名', () => {
    expect(dispositionMethodLabel('RESALE')).toBe('转销售（可再售）');
    expect(dispositionMethodLabel('RETURN_TO_COUNTRY')).toBe('退运回国');
    expect(dispositionMethodLabel('DISCARD')).toBe('弃置销毁');
    expect(dispositionMethodLabel(null)).toBe('未决定');
    expect(warehouseTypeLabel('LOCAL')).toBe('本地仓');
    expect(warehouseTypeLabel('OVERSEAS')).toBe('海外仓');
    expect(warehouseTypeLabel('PLATFORM')).toBe('平台仓');
    expect(costTypeLabel('RETURN_FREIGHT')).toBe('退货运费');
    expect(costTypeLabel('DESTROY_FEE')).toBe('销毁费');
    expect(costTypeLabel('PLATFORM_HANDLING_FEE')).toBe('平台处理费');
    expect(restrictionSubjectLabel('BRAND')).toBe('品牌方要求');
    expect(restrictionSubjectLabel('PLATFORM')).toBe('平台政策');
    expect(restrictionBasisLabel('BRAND_REQUIREMENT')).toBe('品牌方要求');
    expect(restrictionBasisLabel('PLATFORM_POLICY')).toBe('平台政策');
  });

  it('处置状态（判定进度）与处置方式是两回事，页面上不能混', () => {
    expect(dispositionStateLabel('PENDING_DECISION')).toBe('待人工决策');
    expect(dispositionStateLabel('DECIDED')).toBe('已决策待下发');
    expect(dispositionStateLabel('ORDERED')).toBe('已下发处置单');
    // 待人工决策**不是**转销售
    expect(dispositionStateLabel('PENDING_DECISION')).not.toContain('转销售');
  });

  it('金额原样带币种，**不做任何汇率换算**', () => {
    expect(moneyText(12.5, 'USD')).toBe('12.50 USD');
    expect(moneyText(120, 'EUR')).toBe('120.00 EUR');
    expect(moneyText(null, 'USD')).toBe('—');
  });

  it('数量原样显示', () => {
    expect(quantityText(5)).toBe('5');
    expect(quantityText(1.5)).toBe('1.500000');
    expect(quantityText(null)).toBe('—');
  });

  it('概览的三个去向分布缺项补 0', () => {
    const overview = {
      registrationCount: 3,
      pendingDecisionCount: 1,
      issuedOrderCount: 1,
      cancelledOrderCount: 0,
      resaleCount: 2,
      repatriationCount: 0,
      discardCount: 0,
      restrictedRegistrationCount: 0,
      costTotals: [],
      salvageTotals: [],
      evaluatedAt: '2026-03-04T00:00:00Z',
      note: '',
      currencyNote: '',
    } as ReturnDispositionOverview;
    const dist = dispositionDistribution(overview);
    expect(dist.map((d) => d.method)).toEqual(['RESALE', 'RETURN_TO_COUNTRY', 'DISCARD']);
    expect(dist.map((d) => d.count)).toEqual([2, 0, 0]);
  });
});
