import { describe, expect, it } from 'vitest';
import { ApiError } from '../../lib/api';
import {
  NO_DATA_TEXT,
  actionHint,
  approvalCaseStatusLabel,
  approvalCaseStatusTone,
  approvalErrorText,
  approvalProgressText,
  approvalTierLabel,
  approverBandText,
  batchApprovalResultText,
  buyerLevelLabel,
  fmtAmount,
  payableStatusLabel,
  purchaseOrderApprovalQuery,
  releaseStatusLabel,
  ruleBandText,
  ruleMatchLabel,
} from './purchase-order-approval-model';

/**
 * 6.3.1.3 采购单审批前端模型的纯函数测试。
 *
 * <p>覆盖的是**页面真的会依赖的口径**，不是覆盖率：
 *
 * <ul>
 *   <li><b>null ≠ 0</b>：还没登记的应付金额显示「无数据」，绝不显示 0 ——
 *       0 会被读成「这笔应付是 0 元」。</li>
 *   <li><b>「没配规则」不是「不用审」</b>：DEFAULT 必须显示成默认档。</li>
 *   <li><b>不能批要说清为什么</b>：原因原样透出，不能被吞成「不可操作」。</li>
 *   <li><b>批量失败要逐条列出</b>：失败原因不能只给一个计数。</li>
 *   <li><b>金额区间是半开的</b>：[下限, 上限) —— 相邻规则既不重叠也不留缝。</li>
 * </ul>
 */
describe('金额的展示', () => {
  it('null 一律给「无数据」，绝不显示 0', () => {
    expect(fmtAmount(null)).toBe(NO_DATA_TEXT);
    expect(fmtAmount(undefined)).toBe(NO_DATA_TEXT);
  });

  it('0 是真值：显示 0 而不是「无数据」', () => {
    expect(fmtAmount(0)).toBe('0.00');
  });

  it('金额保留两位到四位小数', () => {
    expect(fmtAmount(1234.56789)).toBe('1,234.5679');
    expect(fmtAmount(250)).toBe('250.00');
  });
});

describe('审批进度的展示', () => {
  it('显示「已批 / 需要」的人数，而不是百分比', () => {
    expect(approvalProgressText(1, 2)).toBe('1 / 2 人');
    expect(approvalProgressText(2, 2)).toBe('2 / 2 人');
  });

  it('需要人数为 0 时给「无数据」而不是 0 / 0', () => {
    expect(approvalProgressText(0, 0)).toBe(NO_DATA_TEXT);
  });
});

describe('规则命中的展示', () => {
  it('「一条规则都没命中」显示成默认档，不是「无」', () => {
    expect(ruleMatchLabel('DEFAULT')).toBe('默认档（最低层级 · 1 人）');
    expect(ruleMatchLabel('RULE')).toBe('命中规则');
  });

  it('金额区间是半开的 [下限, 上限)', () => {
    expect(
      ruleBandText({
        buyerLevel: 'JUNIOR',
        currency: 'USD',
        minAmount: 1000,
        maxAmount: 5000,
      }),
    ).toBe('初级采购员 · USD · [1,000.00, 5,000.00)');
  });

  it('通配维度显示成「全部」而不是 ANY', () => {
    expect(
      ruleBandText({ buyerLevel: 'ANY', currency: 'ANY', minAmount: 0, maxAmount: null }),
    ).toBe('全部分级 · 全部币种 · [0.00, ∞)');
  });

  it('审批人的授权区间同样半开', () => {
    expect(approverBandText({ currency: 'ANY', minAmount: 0, maxAmount: 100 })).toBe(
      '全部币种 · [0.00, 100.00)',
    );
  });
});

describe('能不能操作', () => {
  it('能批时说「可以审批」', () => {
    expect(
      actionHint({ caseStatus: 'PENDING', canApprove: true, blockedReason: '' }),
    ).toBe('可以审批');
  });

  it('不能批时把原因原样透出', () => {
    expect(
      actionHint({
        caseStatus: 'PENDING',
        canApprove: false,
        blockedReason: '你的审批层级低于这张单要求的层级（要求 L2）',
      }),
    ).toBe('你的审批层级低于这张单要求的层级（要求 L2）');
  });

  it('已经审批完的单说「已经审批通过」，而不是拿权限说事', () => {
    expect(
      actionHint({
        caseStatus: 'APPROVED',
        canApprove: false,
        blockedReason: '你已经在这张采购单上批过了',
      }),
    ).toBe('这一单已经审批通过');
    expect(actionHint({ caseStatus: 'REJECTED', canApprove: false, blockedReason: '' })).toBe(
      '这一单已被驳回',
    );
  });
});

describe('状态标签', () => {
  it('审批单状态有中文标签与视觉分级', () => {
    expect(approvalCaseStatusLabel('PENDING')).toBe('待审批');
    expect(approvalCaseStatusLabel('APPROVED')).toBe('已通过');
    expect(approvalCaseStatusLabel('REJECTED')).toBe('已驳回');
    expect(approvalCaseStatusTone('REJECTED')).toBe('danger');
    expect(approvalCaseStatusTone('PENDING')).toBe('warning');
    expect(approvalCaseStatusTone('APPROVED')).toBe('success');
  });

  it('「还没登记」与「还没放行」是**状态**，不是空', () => {
    expect(payableStatusLabel('NONE')).toBe('尚未登记');
    expect(payableStatusLabel('PENDING_FINANCE_CONFIRM')).toBe('待财务确认');
    expect(releaseStatusLabel('NONE')).toBe('尚未放行');
    expect(releaseStatusLabel('RELEASED')).toBe('已放行');
  });

  it('层级与分级有中文标签', () => {
    expect(approvalTierLabel('L2')).toBe('L2 采购经理');
    expect(buyerLevelLabel('JUNIOR')).toBe('初级采购员');
    expect(buyerLevelLabel('UNREGISTERED')).toBe('未登记分级');
  });
});

describe('批量结果的展示', () => {
  it('全成功时说清处理了几张', () => {
    expect(
      batchApprovalResultText({
        items: [{ orderId: 'a', orderNo: 'PO-1', succeeded: true, message: '已处理' }],
        succeededCount: 1,
        failedCount: 0,
        note: '批量审批',
      }),
    ).toBe('已处理 1 张采购单');
  });

  it('有失败时逐条列出失败原因，不吞', () => {
    const text = batchApprovalResultText({
      items: [
        { orderId: 'a', orderNo: 'PO-1', succeeded: true, message: '已处理' },
        { orderId: 'b', orderNo: 'PO-2', succeeded: false, message: '金额是 0，登记不出应付' },
      ],
      succeededCount: 1,
      failedCount: 1,
      note: '批量审批',
    });
    expect(text).toContain('成功 1 张');
    expect(text).toContain('失败 1 张');
    expect(text).toContain('PO-2');
    expect(text).toContain('金额是 0');
  });
});

describe('查询串', () => {
  it('只拼有值的筛选条件', () => {
    expect(purchaseOrderApprovalQuery({})).toBe('');
    expect(purchaseOrderApprovalQuery({ caseStatus: 'PENDING' })).toBe('?caseStatus=PENDING');
  });

  it('关键字去空白并编码', () => {
    expect(purchaseOrderApprovalQuery({ keyword: '  PO-1 ' })).toBe('?keyword=PO-1');
    expect(purchaseOrderApprovalQuery({ keyword: 'a&b' })).toBe('?keyword=a%26b');
  });

  it('分页参数原样带上', () => {
    expect(purchaseOrderApprovalQuery({ page: 2, size: 20 })).toBe('?page=2&size=20');
  });
});

describe('错误文案', () => {
  it('403 要点明是哪一个权限码', () => {
    expect(approvalErrorText(new ApiError(403, 'forbidden'))).toContain('trade.purchase.approve');
  });

  it('409 / 400 用后端给的原因原文', () => {
    expect(approvalErrorText(new ApiError(409, '不能审批自己提交的采购单'))).toContain(
      '不能审批自己提交的采购单',
    );
    expect(approvalErrorText(new ApiError(400, '驳回必须附意见'))).toContain('驳回必须附意见');
  });
});
