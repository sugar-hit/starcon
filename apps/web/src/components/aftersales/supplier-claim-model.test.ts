import { describe, expect, it } from 'vitest';
import {
  actionBlockedReason,
  CLAIMABLE_STAGES,
  CLAIM_REASON_OPTIONS,
  CLAIM_STATUS_OPTIONS,
  formatAmount,
  isSettlementPending,
  NEGOTIATION_TYPE_OPTIONS,
  OFFSET_STATUS_OPTIONS,
  SETTLEMENT_METHOD_OPTIONS,
  settlementEffectiveHint,
  stageBlockedReason,
} from './supplier-claim-model';

/**
 * 11.2.3.3 供应商索赔的前端单元测试。
 *
 * <p>测的是**蓝图那句加粗句**「<b>索赔不是自动到账</b>，系统只跟踪流程与金额，
 * 冲减应付需财务确认」在界面上是不是真的看得见：
 * 登记了赔付不等于冲减生效 —— 这件事服务端用两列（登记金额 / 生效金额）
 * 与表级 CHECK 钉死了，前端也必须有一份镜像，否则运营会以为登记完就等于钱到账了。
 *
 * <p>同理，责任环节的可索赔判定服务端也判了一次（V188 守卫②），
 * 前端不自己再编一套 —— 但**要能把「为什么不能索赔」说清楚**，
 * 否则运营只会看到「加不进去」，不知道该先去归因里改什么。
 */

describe('责任环节：只有生产与描述不符能向供应商索赔', () => {
  it('可索赔环节只有两个', () => {
    expect([...CLAIMABLE_STAGES]).toEqual(['PRODUCTION', 'DESCRIPTION']);
  });

  it('可索赔环节不给出禁用理由', () => {
    expect(stageBlockedReason('PRODUCTION')).toBeNull();
    expect(stageBlockedReason('DESCRIPTION')).toBeNull();
  });

  it('运输与仓储的责任不在供应商的产品上', () => {
    expect(stageBlockedReason('TRANSPORT')).toContain('承运商');
    expect(stageBlockedReason('WAREHOUSE')).toContain('我们自己');
  });

  it('买家因素与责任待定不能索赔', () => {
    expect(stageBlockedReason('BUYER_FACTOR')).toContain('买家因素');
    expect(stageBlockedReason('UNDETERMINED')).toContain('责任待定');
  });

  it('未知环节也要说清楚，不能默默放行', () => {
    expect(stageBlockedReason('WHAT_IS_THIS')).toContain('未知责任环节');
    expect(stageBlockedReason(null)).toBeNull();
  });
});

describe('赔付分录：登记不等于生效', () => {
  it('待财务确认时明说「尚未生效」，且生效金额是 0', () => {
    const hint = settlementEffectiveHint({
      requestedAmount: 8,
      effectiveAmount: 0,
      settlementStatus: 'PENDING_FINANCE_CONFIRM',
    });
    expect(hint).toContain('尚未生效');
    expect(hint).toContain('生效 0');
    expect(hint).toContain('等待财务确认');
  });

  it('已确认时显示生效金额', () => {
    const hint = settlementEffectiveHint({
      requestedAmount: 8,
      effectiveAmount: 8,
      settlementStatus: 'CONFIRMED',
    });
    expect(hint).toContain('已生效');
    expect(hint).toContain('8');
  });

  it('被财务驳回时生效金额仍是 0', () => {
    const hint = settlementEffectiveHint({
      requestedAmount: 8,
      effectiveAmount: 0,
      settlementStatus: 'REJECTED',
    });
    expect(hint).toContain('驳回');
    expect(hint).toContain('生效金额 0');
  });

  it('只有待确认状态算「待办」', () => {
    expect(isSettlementPending({ settlementStatus: 'PENDING_FINANCE_CONFIRM' })).toBe(true);
    expect(isSettlementPending({ settlementStatus: 'CONFIRMED' })).toBe(false);
    expect(isSettlementPending({ settlementStatus: 'REJECTED' })).toBe(false);
  });
});

describe('动作禁用理由由后端给，前端只转述', () => {
  const actions = [
    { code: 'SUBMIT', allowed: true, blockedReason: null },
    { code: 'CONFIRM_SETTLEMENT', allowed: false, blockedReason: '没有待财务确认的赔付分录' },
  ];

  it('可执行的动作没有禁用理由', () => {
    expect(actionBlockedReason(actions, 'SUBMIT')).toBeNull();
  });

  it('不可执行的动作把后端的理由原样带出来', () => {
    expect(actionBlockedReason(actions, 'CONFIRM_SETTLEMENT')).toBe('没有待财务确认的赔付分录');
  });

  it('动作不存在时也要有话说，而不是静默放行', () => {
    expect(actionBlockedReason(actions, 'NOT_A_REAL_ACTION')).toContain('不可用');
    expect(actionBlockedReason(undefined, 'SUBMIT')).toContain('不可用');
  });
});

describe('选项集合', () => {
  it('索赔单状态七个且顺序 = 状态机推进顺序', () => {
    expect(CLAIM_STATUS_OPTIONS.map((o) => o.code)).toEqual([
      'DRAFT',
      'SUBMITTED',
      'NEGOTIATING',
      'AGREED',
      'SETTLED',
      'REJECTED',
      'WITHDRAWN',
    ]);
  });

  it('冲减状态四个，「冲减已生效」只有一个', () => {
    expect(OFFSET_STATUS_OPTIONS.map((o) => o.code)).toEqual([
      'NOT_APPLIED',
      'PENDING_FINANCE_CONFIRM',
      'APPLIED',
      'FINANCE_REJECTED',
    ]);
    expect(OFFSET_STATUS_OPTIONS.filter((o) => o.code === 'APPLIED')).toHaveLength(1);
  });

  it('赔付方式两种（冲减应付 / 供应商退款）', () => {
    expect(SETTLEMENT_METHOD_OPTIONS.map((o) => o.code)).toEqual([
      'OFFSET_PAYABLE',
      'SUPPLIER_REFUND',
    ]);
  });

  it('只有「达成一致」带达成金额', () => {
    expect(NEGOTIATION_TYPE_OPTIONS.filter((o) => o.code === 'AGREEMENT')).toHaveLength(1);
  });

  it('索赔原因是质量原因的细分，五种', () => {
    expect(CLAIM_REASON_OPTIONS).toHaveLength(5);
  });
});

describe('展示格式化', () => {
  it('金额带币种且保留两位', () => {
    expect(formatAmount(8, 'USD')).toBe('8.00 USD');
    expect(formatAmount(7.1234, 'USD')).toBe('7.12 USD');
  });

  it('空金额显示破折号而不是 0', () => {
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
  });
});
