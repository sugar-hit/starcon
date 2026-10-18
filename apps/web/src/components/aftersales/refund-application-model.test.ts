import { describe, expect, it } from 'vitest';
import type { RefundBalance, RefundDetail, RefundPolicyView } from '../../../../../packages/api-contracts/src/index';
import {
  actionButton,
  amount,
  availableText,
  balanceRows,
  boundaryRows,
  eventText,
  executionText,
  executionTone,
  methodText,
  policyText,
  reasonText,
  stateTone,
} from './refund-application-model';

/**
 * 11.3.1.1 退款申请展示口径的单测。
 *
 * <p>这些函数承载的是**会被误读的口径**，而误读的代价是钱：
 * 「可退 0」被当成「还剩 0」会让人少退一笔，「UNKNOWN」被标成红色会让人去点重试 ——
 * 后者是一次真实的重复退款。所以每一格都要单独断言。
 */

function balance(over: Partial<RefundBalance> = {}): RefundBalance {
  return {
    caseId: 'c',
    caseNo: 'AS1',
    caseType: 'RETURN_REFUND',
    caseStatus: 'AUTHORIZING',
    orderId: 'o',
    orderNo: 'SO1',
    currency: 'USD',
    baseAmount: 60,
    settledAmount: 0,
    dispatchedAmount: 0,
    registeredAmount: 0,
    pendingAmount: 0,
    disputedAmount: 0,
    occupiedAmount: 0,
    availableAmount: 60,
    refundable: true,
    blockingReasons: [],
    balanceSource: 'src',
    evaluatedAt: '2026-03-01T00:00:00Z',
    ...over,
  };
}

describe('可退余额那一格', () => {
  it('可退 0 与可退为负必须分开显示 —— 前者还能退，后者已经超了', () => {
    expect(availableText(balance({ availableAmount: 60 }))).toEqual({ value: '60.00', tone: 'success' });
    expect(availableText(balance({ availableAmount: 0 }))).toEqual({
      value: '额度已退光',
      tone: 'warning',
    });
    // ⚠️ 不截断成 0：截断之后「超了 50 块」与「正好退光」在界面上完全一样，
    // 而前者要查账、后者只要再退一笔。
    expect(availableText(balance({ availableAmount: -50 }))).toEqual({
      value: '已超 50.00',
      tone: 'danger',
    });
  });

  it('五个占用桶逐行显示而不是只给一个合计', () => {
    const rows = balanceRows(
      balance({
        baseAmount: 60,
        settledAmount: 10,
        dispatchedAmount: 5,
        registeredAmount: 3,
        pendingAmount: 7,
        disputedAmount: 2,
        occupiedAmount: 27,
        availableAmount: 33,
      }),
    );
    expect(rows).toHaveLength(7);
    expect(rows.map((r) => r.label)).toEqual([
      '可退基数（售后单申请金额）',
      '已退（成功执行记录）',
      '在途 · 已派发 / 结果未知',
      '在途 · 已登记待发起',
      '待审 / 已批占用',
      '对账争议（按宁可拦住也不能放过计入）',
      '可退余额',
    ]);
    expect(rows[1]?.value).toBe('−10.00');
    expect(rows[5]?.hint).toBeTruthy();
  });
});

describe('审批规则 fail-closed', () => {
  const policy = (over: Partial<RefundPolicyView> = {}): RefundPolicyView => ({
    currency: 'USD',
    policyStatus: 'MISSING',
    dualApprovalThreshold: 0,
    requiresDualApproval: true,
    policySource: 'DEFAULT_NO_POLICY',
    note: null,
    effectiveFrom: null,
    updatedBy: null,
    updatedAt: null,
    ...over,
  });

  it('没有配置时必须显示成「未配置 · 最严格档」，不能显示阈值 0', () => {
    // ⚠️ 显示「0」会被读成「0 元以下不用第二个人签」，而真相是「任何金额都要两个人」。
    const text = policyText(policy());
    expect(text.tone).toBe('warning');
    expect(text.label).toContain('未配置');
    expect(text.label).toContain('fail-closed');
  });

  it('配了阈值后按阈值说话', () => {
    expect(policyText(policy({ policySource: 'CONFIGURED', dualApprovalThreshold: 100 })).label)
      .toBe('金额 ≥ 100.00 USD 需两人签字，低于则单签');
    expect(policyText(policy({ policySource: 'CONFIGURED', dualApprovalThreshold: 0 })).label)
      .toBe('任何金额都要两人签字');
  });
});

describe('状态色调', () => {
  it('在途与终态一眼分得开', () => {
    expect(stateTone('PENDING_APPROVAL')).toBe('warning');
    expect(stateTone('SECOND_APPROVAL_PENDING')).toBe('warning');
    expect(stateTone('APPROVED')).toBe('info');
    expect(stateTone('SECOND_APPROVED')).toBe('info');
    expect(stateTone('SUCCEEDED')).toBe('success');
    expect(stateTone('FAILED')).toBe('danger');
    expect(stateTone('REJECTED')).toBe('danger');
    expect(stateTone('DRAFT')).toBe('neutral');
    expect(stateTone('CANCELLED')).toBe('neutral');
  });

  it('UNKNOWN 是 warning 而不是 danger —— 外部超时不等于失败', () => {
    // ⚠️ 标成红色会让人把它当成「退失败了」去点重试 —— 重试一次退款的代价是真金白银。
    expect(executionTone('UNKNOWN')).toBe('warning');
    expect(executionTone('DISCREPANCY')).toBe('warning');
    expect(executionTone('IN_FLIGHT')).toBe('info');
    expect(executionTone('NOT_INITIATED')).toBe('info');
    expect(executionTone('SUCCEEDED')).toBe('success');
    expect(executionTone('FAILED')).toBe('danger');
    expect(executionTone(null)).toBe('neutral');
  });
});

describe('执行状态提示原样透出后端口径', () => {
  it('不自己重写一句「渠道处理中」', () => {
    const detail = {
      executionState: 'UNKNOWN',
      executionStateNote: '⚠️ **外部超时，结果未知** —— 这**不是失败**',
    } as Pick<RefundDetail, 'executionState' | 'executionStateNote'>;
    // 后端说「这不是失败」就得原样显示 —— 前端重写会丢掉这句。
    expect(executionText(detail)).toBe(detail.executionStateNote);
    expect(executionText(detail)).toContain('不是失败');
  });

  it('还没派发时也要说清为什么没有执行结果', () => {
    const detail = {
      executionState: null,
      executionStateNote: '本申请尚未派发：还没有挂上任何退款执行记录',
    } as Pick<RefundDetail, 'executionState' | 'executionStateNote'>;
    expect(executionText(detail)).toContain('尚未派发');
  });
});

describe('其它展示口径', () => {
  it('金额、空值与枚举标签', () => {
    expect(amount(1234.5)).toBe('1,234.50');
    expect(amount(null)).toBe('—');
    expect(amount('')).toBe('—');
    expect(methodText('ORIGINAL')).toBe('原路退回');
    expect(methodText('PLATFORM')).toBe('平台侧退款');
    expect(methodText('BALANCE')).toBe('退回余额');
    expect(reasonText('RETURN_REFUND')).toBe('退货退款');
    expect(reasonText('OTHER')).toContain('必须写说明');
  });

  it('动作按钮不可用时把理由带出来（按钮变灰而不给理由会让人以为系统坏了）', () => {
    expect(actionButton({ label: '交财务执行', allowed: false, blockingReason: '只有已批可派发', unavailableReason: null }))
      .toEqual({ label: '交财务执行', disabled: true, reason: '只有已批可派发' });
    expect(actionButton({ label: '真实付款执行', allowed: false, blockingReason: null, unavailableReason: '12 财务未交付' }))
      .toEqual({ label: '真实付款执行', disabled: true, reason: '12 财务未交付' });
    expect(actionButton(null).disabled).toBe(true);
  });

  it('留痕行带上前置状态与金额', () => {
    expect(
      eventText({
        eventTypeLabel: '复审通过',
        fromStatus: 'SECOND_APPROVAL_PENDING',
        toStatus: 'SECOND_APPROVED',
        amountSnapshot: 15,
        occurredAt: '2026-03-01T00:00:00Z',
      }),
    ).toBe('复审通过（SECOND_APPROVAL_PENDING → SECOND_APPROVED）· 15.00');
  });

  it('「刻意不做的事」逐条透出归属模块号', () => {
    const rows = boundaryRows({
      inputs: [],
      sections: [],
      relatedModules: [],
      boundaries: [
        {
          boundary: '真实付款执行与原收款关联',
          ownerModule: '12.2.2.2 付款执行',
          reason: '12 财务整卷未交付。',
        },
      ],
      stateMachine: '',
      balanceRule: '',
      approvalRule: '',
      executionNote: '',
    });
    expect(rows).toEqual([
      { scope: '真实付款执行与原收款关联', owner: '12.2.2.2 付款执行', reason: '12 财务整卷未交付。' },
    ]);
  });
});
