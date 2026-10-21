import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import {
  amountText,
  approvalCaseStatusLabel,
  approvalCaseSummary,
  approvalCaseTone,
  approvalErrorText,
  approvalRecordActionLabel,
  approverProblem,
  approveProblem,
  batchItemText,
  batchSummary,
  qtyText,
  rejectProblem,
  ruleMatchLabel,
  ruleProblem,
  submitProblem,
  tierLabel,
  valuationStatusLabel,
} from './inventory-adjustment-approval-model';

/**
 * 7.4.1.2 调整审批前端模型的**纯函数**回归。
 *
 * <p>重点是那些「界面上说错一句就会误导人」的口径：
 * 申报金额必填、金额不静默四舍五入、审批人数要显示出来、规则命中要说清是规则还是默认档。
 */
describe('7.4.1.2 调整审批 · 前端模型', () => {
  describe('提交待审的校验', () => {
    it('金额必填：不申报就不能提交（否则能躲开金额档）', () => {
      expect(submitProblem('', 'USD')).toContain('必须申报调整的估值金额');
    });

    it('金额必须大于 0', () => {
      expect(submitProblem('0', 'USD')).toContain('必须大于 0');
      expect(submitProblem('-1', 'USD')).toContain('必须大于 0');
    });

    it('金额不是数字就直说，不静默纠正', () => {
      expect(submitProblem('abc', 'USD')).toContain('必须是数字');
    });

    it('金额超过 4 位小数要拒（不静默四舍五入）', () => {
      expect(submitProblem('1.00001', 'USD')).toContain('最多 4 位小数');
      expect(submitProblem('1.0000', 'USD')).toBeNull();
    });

    it('币种必须是三个大写字母', () => {
      expect(submitProblem('10', '人民币')).toContain('三个大写字母');
      expect(submitProblem('10', 'usd')).toBeNull();
      expect(submitProblem('10', 'USD')).toBeNull();
    });
  });

  describe('阈值规则的校验', () => {
    const base = {
      ruleNo: 'R-1',
      ruleName: '规则',
      currency: 'ANY',
      minAmount: '0',
      maxAmount: '1000',
      minQty: '0',
      maxQty: '',
      requiredTier: 'L1',
      requiredApprovals: '1',
    };

    it('规则号与规则名必填', () => {
      expect(ruleProblem({ ...base, ruleNo: '  ' })).toContain('规则号不能为空');
      expect(ruleProblem({ ...base, ruleName: '' })).toContain('规则名不能为空');
    });

    it('币种只能是三个大写字母或 ANY', () => {
      expect(ruleProblem({ ...base, currency: '人民币' })).toContain('三个大写字母');
      expect(ruleProblem({ ...base, currency: 'RM' })).toContain('三个大写字母');
      expect(ruleProblem({ ...base, currency: 'ANY' })).toBeNull();
    });

    it('金额区间必须 下限 < 上限（留空 = 无上限）', () => {
      expect(ruleProblem({ ...base, minAmount: '1000', maxAmount: '1000' })).toContain('下限 < 上限');
      expect(ruleProblem({ ...base, maxAmount: '' })).toBeNull();
    });

    it('数量区间必须 下限 < 上限（留空 = 无上限）', () => {
      expect(ruleProblem({ ...base, minQty: '10', maxQty: '5' })).toContain('下限 < 上限');
      expect(ruleProblem({ ...base, maxQty: '' })).toBeNull();
    });

    it('审批人数只能在 1~5 之间', () => {
      expect(ruleProblem({ ...base, requiredApprovals: '0' })).toContain('1~5');
      expect(ruleProblem({ ...base, requiredApprovals: '6' })).toContain('1~5');
      expect(ruleProblem({ ...base, requiredApprovals: '2' })).toBeNull();
    });

    it('审批层级只能是 L1 / L2 / L3', () => {
      expect(ruleProblem({ ...base, requiredTier: 'L4' })).toContain('L1 / L2 / L3');
    });
  });

  describe('审批人授权的校验', () => {
    it('必须指定审批人', () => {
      expect(approverProblem({ userId: '', approvalTier: 'L1', currency: 'ANY', minAmount: '0', maxAmount: '', maxQty: '' })).toContain(
        '必须指定审批人',
      );
    });

    it('金额授权区间必须 下限 < 上限', () => {
      expect(
        approverProblem({
          userId: 'u1',
          approvalTier: 'L2',
          currency: 'ANY',
          minAmount: '1000',
          maxAmount: '1000',
          maxQty: '',
        }),
      ).toContain('下限 < 上限');
    });

    it('数量授权上限不能为负', () => {
      expect(
        approverProblem({
          userId: 'u1',
          approvalTier: 'L2',
          currency: 'ANY',
          minAmount: '0',
          maxAmount: '',
          maxQty: '-1',
        }),
      ).toContain('不能为负');
    });
  });

  describe('审批意见', () => {
    it('批准必须写理由', () => {
      expect(approveProblem('   ')).toContain('批准必须写理由');
      expect(approveProblem('证据充分')).toBeNull();
    });

    it('驳回必须写意见', () => {
      expect(rejectProblem('')).toContain('驳回必须写意见');
      expect(rejectProblem('凭证不足')).toBeNull();
    });

    it('意见超长要拦', () => {
      expect(approveProblem('x'.repeat(501))).toContain('超过 500 字');
      expect(rejectProblem('x'.repeat(501))).toContain('超过 500 字');
    });
  });

  describe('展示口径', () => {
    it('命中规则与默认档要分得清', () => {
      expect(ruleMatchLabel({ ruleMatch: 'DEFAULT', ruleName: '' })).toContain('默认档');
      expect(ruleMatchLabel({ ruleMatch: 'RULE', ruleName: '大额档' })).toContain('大额档');
    });

    it('审批单状态与颜色档一一对应', () => {
      expect(approvalCaseStatusLabel('PENDING')).toBe('待审批');
      expect(approvalCaseStatusLabel('APPROVED')).toBe('已批');
      expect(approvalCaseStatusLabel('REJECTED')).toBe('已驳回');
      expect(approvalCaseTone('APPROVED')).toBe('success');
      expect(approvalCaseTone('REJECTED')).toBe('danger');
      expect(approvalCaseTone('PENDING')).toBe('warning');
    });

    it('审批链路动作有中文名', () => {
      expect(approvalRecordActionLabel('SUBMITTED')).toBe('提交待审');
      expect(approvalRecordActionLabel('APPROVED')).toBe('批准');
      expect(approvalRecordActionLabel('REJECTED')).toBe('驳回');
    });

    it('会计影响：登记 ≠ 生效', () => {
      expect(valuationStatusLabel('REGISTERED')).toContain('未入账');
      expect(valuationStatusLabel('EFFECTIVE')).toBe('已生效');
    });

    it('层级与金额/数量的显示精度固定（4 位小数）', () => {
      expect(tierLabel('L2')).toContain('L2');
      expect(tierLabel(null)).toBe('—');
      expect(amountText(123.4)).toBe('123.4000');
      expect(amountText(null)).toBe('—');
      expect(qtyText(2)).toBe('2');
      expect(qtyText(2.5)).toBe('2.5');
    });

    it('审批单摘要里必须看得到「已批 N/M 人」', () => {
      const summary = approvalCaseSummary({
        id: 'c1',
        orderId: 'o1',
        orderCode: 'ADJ-1',
        caseNo: 'ADJ-APP-1',
        attemptNo: 1,
        ruleId: null,
        ruleName: '',
        ruleMatch: 'DEFAULT',
        reasonCode: 'DAMAGE',
        orderAmount: 100,
        currency: 'USD',
        orderQty: 2,
        requiredTier: 'L2',
        requiredApprovals: 2,
        approvedCount: 1,
        status: 'PENDING',
        submittedBy: 'u1',
        submittedAt: '2026-10-21T00:00:00Z',
        rejectedBy: null,
        rejectedAt: null,
        rejectReason: '',
      });
      expect(summary).toContain('已批 1/2 人');
      expect(summary).toContain('L2');
    });
  });

  describe('批量结果', () => {
    it('成功与失败都要逐条说清', () => {
      expect(batchItemText({ orderId: 'o1', orderCode: 'ADJ-1', success: true, message: '已处理' })).toBe(
        'ADJ-1：已处理',
      );
      expect(
        batchItemText({ orderId: 'o2', orderCode: '', success: false, message: '找不到该对象' }),
      ).toContain('找不到该对象');
    });

    it('摘要要同时给出成功与失败条数', () => {
      expect(batchSummary({ successCount: 3, failureCount: 0 })).toBe('全部处理成功（3 张）');
      expect(batchSummary({ successCount: 2, failureCount: 1 })).toContain('失败 1 张');
    });
  });

  describe('错误文案', () => {
    it('普通异常原样透出', () => {
      expect(approvalErrorText(new Error('x'))).toBe('x');
    });

    it('403 / 404 / 409 分开说，不能都塌成「未知错误」', () => {
      expect(approvalErrorText(new ApiError(403, '没有权限'))).toContain('没有此操作权限');
      expect(approvalErrorText(new ApiError(404, '没有这个对象'))).toContain('不属于当前租户');
      expect(approvalErrorText(new ApiError(409, '人数没凑齐'))).toContain('人数没凑齐');
      expect(approvalErrorText(new ApiError(400, '参数不对'))).toContain('参数不对');
    });
  });
});
