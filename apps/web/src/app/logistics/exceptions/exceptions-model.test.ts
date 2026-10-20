import { describe, expect, it } from 'vitest';
import {
  ACTION_TYPES,
  CASE_STATUSES,
  CLAIM_TYPES,
  DOWNSTREAM_MODULES,
  EMPTY_EXCEPTION_TYPE,
  EXCEPTION_TYPES,
  SEVERITIES,
  actionTypeLabel,
  buildCasePayload,
  buildClaimPayload,
  buildDispositionPayload,
  buildRulePayload,
  caseStatusLabel,
  claimTotalsText,
  downstreamModuleLabel,
  downstreamStatusLabel,
  durationText,
  emptyCaseForm,
  emptyClaimForm,
  emptyDispositionForm,
  emptyRuleForm,
  exceptionTypeLabel,
  moneyText,
  quantityText,
  severityLabel,
  sourceLabel,
  totalPages,
  validateCaseForm,
  validateClaimForm,
  validateDispositionForm,
  validateRuleForm,
} from './exceptions-model';

/**
 * 10.3.1.2 异常件的前端模型断言。
 *
 * <p>🔴 本文件最要紧的三条：
 *
 * <ol>
 *   <li><b>异常类型必须显式选</b>：表单初值必须是**空串**而不是 'TRACKING_STALLED'，
 *       校验必须在空串时拒，构建请求体必须在空串时返回 error 而不是悄悄塞一个类型。
 *       承运商的异常码是自由文本，页面替人猜就是「地址问题」被记成「妥投失败」。</li>
 *   <li><b>下游只到「待通知」为止</b>：模型里**不存在**「标记已通知 / 已受理」的构造，
 *       下游模块的展示词表里也只暴露这一种可推进的状态。</li>
 *   <li><b>结案必须写理由</b>：没有理由的结案请求体不成立 —— 异常件「负责到底」
 *       的最后一步不能是无痕的。</li>
 * </ol>
 */
describe('10.3.1.2 异常类型必须显式给出', () => {
  it('表单初值是空串，不是某个默认类型', () => {
    const form = emptyCaseForm();
    expect(form.exceptionType).toBe(EMPTY_EXCEPTION_TYPE);
    expect(form.exceptionType).not.toBe('TRACKING_STALLED');
    expect(form.exceptionType).not.toBe('BUYER_COMPLAINT');
  });

  it('没选类型 ⇒ 校验拒，且理由写明「不替你猜」', () => {
    const error = validateCaseForm(emptyCaseForm());
    expect(error).toContain('必须显式选择异常类型');
    expect(error).toContain('不替你猜');
  });

  it('没选类型 ⇒ 请求体不成立（不会悄悄塞一个类型）', () => {
    const built = buildCasePayload(emptyCaseForm());
    expect('error' in built).toBe(true);
    if ('error' in built) expect(built.error).toContain('异常类型');
  });

  it('显式选了类型 ⇒ 请求体成立，且带的是人给的那一个', () => {
    const form = { ...emptyCaseForm(), exceptionType: 'CUSTOMS_HOLD', waybillNo: ' WB-1 ' };
    const built = buildCasePayload(form);
    expect('error' in built).toBe(false);
    if (!('error' in built)) {
      expect(built.payload.exceptionType).toBe('CUSTOMS_HOLD');
      expect(built.payload.waybillNo).toBe('WB-1');
    }
  });

  it('六个异常类型与白名单逐字一致', () => {
    expect(EXCEPTION_TYPES).toEqual([
      'TRACKING_STALLED',
      'DELIVERY_FAILED',
      'ADDRESS_ISSUE',
      'CUSTOMS_HOLD',
      'DAMAGED_OR_LOST',
      'BUYER_COMPLAINT',
    ]);
    for (const t of EXCEPTION_TYPES) {
      expect(exceptionTypeLabel(t)).not.toBe('未分类');
    }
  });
});

describe('10.3.1.2 处置动作：结案必须有理由，转派必须有目标', () => {
  it('动作初值是空串', () => {
    expect(emptyDispositionForm().actionType).toBe('');
  });

  it('没选动作 ⇒ 校验拒', () => {
    expect(validateDispositionForm(emptyDispositionForm())).toContain('必须选择处置动作');
  });

  it('结案 / 作废没有理由 ⇒ 请求体不成立', () => {
    for (const action of ['RESOLVE', 'VOID']) {
      const form = { ...emptyDispositionForm(), actionType: action };
      expect(validateDispositionForm(form)).toContain('必须写明');
      const built = buildDispositionPayload(form);
      expect('error' in built).toBe(true);
    }
  });

  it('转派没有目标责任人 ⇒ 请求体不成立（没有目标人的转派等于没转派）', () => {
    const form = { ...emptyDispositionForm(), actionType: 'ASSIGN', detail: '转给同事' };
    expect(validateDispositionForm(form)).toContain('目标责任人');
    const built = buildDispositionPayload(form);
    expect('error' in built).toBe(true);
  });

  it('金额大于 0 但没有币种 ⇒ 请求体不成立', () => {
    const form = {
      ...emptyDispositionForm(),
      actionType: 'CONTACT_CARRIER',
      amount: '15',
      currency: '',
    };
    expect(validateDispositionForm(form)).toContain('币种');
    const built = buildDispositionPayload(form);
    expect('error' in built).toBe(true);
  });

  it('金额带币种 ⇒ 请求体成立，币种被大写归一', () => {
    const form = {
      ...emptyDispositionForm(),
      actionType: 'CONTACT_CARRIER',
      amount: '15.50',
      currency: 'usd',
    };
    const built = buildDispositionPayload(form);
    expect('error' in built).toBe(false);
    if (!('error' in built)) {
      expect(built.payload.amount).toBe(15.5);
      expect(built.payload.currency).toBe('USD');
    }
  });

  it('十种处置动作都有中文名', () => {
    expect(ACTION_TYPES).toHaveLength(10);
    for (const a of ACTION_TYPES) {
      expect(actionTypeLabel(a)).not.toBe('未知动作');
    }
  });
});

describe('10.3.1.2 单据：理赔要有金额与币种，补发要有数量', () => {
  it('单据类型初值是空串', () => {
    expect(emptyClaimForm().claimType).toBe('');
  });

  it('没选类型 ⇒ 校验拒', () => {
    expect(validateClaimForm(emptyClaimForm())).toContain('单据类型');
  });

  it('理赔单没有金额 ⇒ 请求体不成立（0 元理赔单是假单）', () => {
    const form = { ...emptyClaimForm(), claimType: 'CARRIER_CLAIM', currency: 'USD' };
    expect(validateClaimForm(form)).toContain('理赔金额');
    const built = buildClaimPayload(form);
    expect('error' in built).toBe(true);
  });

  it('理赔单有金额但币种不合法 ⇒ 请求体不成立', () => {
    const form = { ...emptyClaimForm(), claimType: 'CARRIER_CLAIM', amount: '12.34', currency: 'US' };
    expect(validateClaimForm(form)).toContain('币种');
  });

  it('理赔单完整 ⇒ 请求体成立', () => {
    const form = { ...emptyClaimForm(), claimType: 'CARRIER_CLAIM', amount: '12.34', currency: 'usd' };
    const built = buildClaimPayload(form);
    expect('error' in built).toBe(false);
    if (!('error' in built)) {
      expect(built.payload.amount).toBe(12.34);
      expect(built.payload.currency).toBe('USD');
      expect(built.payload.qty).toBeUndefined();
    }
  });

  it('补发单没有数量 ⇒ 请求体不成立', () => {
    const form = { ...emptyClaimForm(), claimType: 'RESHIP' };
    expect(validateClaimForm(form)).toContain('补发数量');
  });

  it('补发单有数量 ⇒ 请求体成立且不带金额', () => {
    const form = { ...emptyClaimForm(), claimType: 'RESHIP', qty: '2' };
    const built = buildClaimPayload(form);
    expect('error' in built).toBe(false);
    if (!('error' in built)) {
      expect(built.payload.qty).toBe(2);
      expect(built.payload.amount).toBeUndefined();
    }
  });

  it('两种单据类型都有中文名', () => {
    expect(CLAIM_TYPES).toEqual(['CARRIER_CLAIM', 'RESHIP']);
  });
});

describe('10.3.1.2 规则：阈值与责任时限都必须是正整数分钟', () => {
  it('规则编码为空 ⇒ 校验拒', () => {
    expect(validateRuleForm(emptyRuleForm())).toContain('规则编码');
  });

  it('没选异常类型 ⇒ 校验拒', () => {
    const form = { ...emptyRuleForm(), ruleCode: 'R-1' };
    expect(validateRuleForm(form)).toContain('异常类型');
  });

  it('阈值不是正整数 ⇒ 校验拒', () => {
    const form = {
      ...emptyRuleForm(),
      ruleCode: 'R-1',
      ruleType: 'TRACKING_STALLED',
      thresholdMinutes: '0',
    };
    expect(validateRuleForm(form)).toContain('时长阈值');
  });

  it('规则完整 ⇒ 请求体成立，阈值是数字而不是字符串', () => {
    const form = {
      ...emptyRuleForm(),
      ruleCode: 'STALL-7D',
      ruleType: 'TRACKING_STALLED',
      thresholdMinutes: '10080',
      resolveSlaMinutes: '2880',
    };
    const built = buildRulePayload(form);
    expect('error' in built).toBe(false);
    if (!('error' in built)) {
      expect(built.payload.thresholdMinutes).toBe(10080);
      expect(built.payload.resolveSlaMinutes).toBe(2880);
    }
  });
});

describe('10.3.1.2 展示口径：时长、金额、币种、下游状态', () => {
  it('时长以整数分钟为主口径，小时只是括注', () => {
    expect(durationText(30)).toBe('30 分钟');
    expect(durationText(120)).toContain('120 分钟');
    expect(durationText(120)).toContain('2.0 小时');
    expect(durationText(0)).toBe('0 分钟');
    expect(durationText(null)).toBe('—');
  });

  it('金额带币种；数量保留六位小数的可能', () => {
    expect(moneyText(12.34, 'USD')).toBe('12.34 USD');
    expect(moneyText(null, 'USD')).toBe('—');
    expect(quantityText(2)).toBe('2');
    expect(quantityText(1.5)).toBe('1.500000');
  });

  it('跨币种分行，绝不相加', () => {
    const text = claimTotalsText([
      { currency: 'EUR', amount: 20, claimCount: 1 },
      { currency: 'USD', amount: 12.34, claimCount: 2 },
    ]);
    expect(text).toContain('20.00 EUR');
    expect(text).toContain('12.34 USD');
    // 不给总额
    expect(text).not.toContain('32.34');
    expect(claimTotalsText([])).toBe('暂无登记金额');
  });

  it('下游模块与状态都有中文名', () => {
    expect(DOWNSTREAM_MODULES).toEqual(['7', '11', '15', '10.2.1.3']);
    for (const m of DOWNSTREAM_MODULES) {
      expect(downstreamModuleLabel(m)).not.toBe('未知下游');
    }
    expect(downstreamStatusLabel('PENDING_NOTIFY')).toBe('待通知');
    expect(downstreamStatusLabel('NOTIFIED')).toBe('已通知');
    expect(downstreamStatusLabel('ACKNOWLEDGED')).toBe('已受理');
  });

  it('状态与来源都有中文名', () => {
    for (const s of CASE_STATUSES) {
      expect(caseStatusLabel(s)).not.toBe('未知');
    }
    for (const s of SEVERITIES) {
      expect(severityLabel(s)).not.toBe('未知');
    }
    expect(sourceLabel('TRACKING_FLAG')).toBe('被动异常标记');
    expect(sourceLabel('RULE_SCAN')).toBe('轨迹异常规则');
    expect(sourceLabel('CUSTOMER_FEEDBACK')).toBe('客服反馈');
    expect(sourceLabel('MANUAL')).toBe('人工登记');
  });

  it('分页总页数是真分页算出来的', () => {
    expect(totalPages(0, 20)).toBe(1);
    expect(totalPages(20, 20)).toBe(1);
    expect(totalPages(21, 20)).toBe(2);
    expect(totalPages(41, 20)).toBe(3);
  });
});
