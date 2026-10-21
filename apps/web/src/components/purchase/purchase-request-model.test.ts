import { describe, expect, it } from 'vitest';
import {
  NO_DATA_TEXT,
  approvalDecisionLabel,
  approvalSourceLabel,
  fmt,
  fmtAmount,
  fmtDate,
  hasApprovalSyncError,
  isConvertible,
  isEditable,
  isLineEditable,
  isRefreshable,
  isSubmittable,
  missingConvertInputs,
  priorityLabel,
  purchaseRequestDetailQuery,
  purchaseRequestLineQuery,
  purchaseRequestQuery,
  requestStatusLabel,
  requestStatusTone,
  sourceTypeLabel,
} from './purchase-request-model';

/**
 * 6.2.2.1 采购申请前端模型的纯函数测试。
 *
 * <p>覆盖的是**页面真的会依赖的口径**，不是覆盖率：
 *
 * <ul>
 *   <li>「答不出来」与「不需要」不折叠：null 一律给「无数据」，绝不显示 0。</li>
 *   <li>申请与采购单分离：转单事实的表述里**不出现**「采购单已创建」。</li>
 *   <li>审批同步失败必须能被页面一眼认出来。</li>
 *   <li>转单前缺供应商 / 交期要能逐行指出缺哪一条。</li>
 * </ul>
 */

describe('查询串拼装', () => {
  it('空参数不带问号', () => {
    expect(purchaseRequestQuery({})).toBe('');
    expect(purchaseRequestLineQuery({})).toBe('');
  });

  it('只拼给定的参数，顺序稳定', () => {
    expect(purchaseRequestQuery({ status: 'DRAFT', page: 0, size: 20 })).toBe(
      '?status=DRAFT&page=0&size=20',
    );
    expect(purchaseRequestLineQuery({ skuCode: 'SKU-1', page: 1 })).toBe('?skuCode=SKU-1&page=1');
  });

  it('关键字会去掉首尾空白，空串不拼', () => {
    expect(purchaseRequestQuery({ keyword: '  SKU-1  ' })).toBe('?keyword=SKU-1');
    expect(purchaseRequestQuery({ keyword: '   ' })).toBe('');
  });

  it('刷新审批结论的查询串只有开与关两种', () => {
    expect(purchaseRequestDetailQuery(true)).toBe('?refreshApproval=true');
    expect(purchaseRequestDetailQuery(false)).toBe('');
    expect(purchaseRequestDetailQuery()).toBe('');
  });
});

describe('状态与标签', () => {
  it('申请单状态的标签与后端枚举一一对应', () => {
    expect(requestStatusLabel('DRAFT')).toBe('草稿');
    expect(requestStatusLabel('PENDING_APPROVAL')).toBe('待审');
    expect(requestStatusLabel('APPROVED')).toBe('已批');
    expect(requestStatusLabel('CONVERTED')).toBe('已转单');
    expect(requestStatusLabel('REJECTED')).toBe('已驳回');
  });

  it('未知状态原样返回而不是空串', () => {
    expect(requestStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(sourceTypeLabel('MANUAL')).toBe('手工建单');
    expect(sourceTypeLabel('UNKNOWN')).toBe('UNKNOWN');
    expect(priorityLabel('CRITICAL')).toBe('紧急');
    expect(priorityLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('审批判定结论与来源的标签可读', () => {
    expect(approvalDecisionLabel('SUBMIT_FOR_APPROVAL')).toBe('需审批');
    expect(approvalDecisionLabel('EXEMPT')).toBe('免审');
    expect(approvalDecisionLabel('')).toBe('未判定');
    expect(approvalSourceLabel('UNCONTROLLED')).toBe('本租户未配规则');
    expect(approvalSourceLabel('')).toBe('未判定');
  });

  it('驳回是危险色、待审是警示色、已批与已转单是成功色', () => {
    expect(requestStatusTone('REJECTED')).toBe('danger');
    expect(requestStatusTone('PENDING_APPROVAL')).toBe('warning');
    expect(requestStatusTone('APPROVED')).toBe('success');
    expect(requestStatusTone('CONVERTED')).toBe('success');
    expect(requestStatusTone('DRAFT')).toBe('info');
  });
});

describe('可执行动作的判定', () => {
  it('只有草稿可改内容、可提交审批', () => {
    expect(isEditable('DRAFT')).toBe(true);
    expect(isEditable('PENDING_APPROVAL')).toBe(false);
    expect(isSubmittable('DRAFT')).toBe(true);
    expect(isSubmittable('APPROVED')).toBe(false);
    expect(isLineEditable('DRAFT')).toBe(true);
    expect(isLineEditable('PENDING_APPROVAL')).toBe(false);
  });

  it('只有待审可刷审批结论，只有已批能转单', () => {
    expect(isRefreshable('PENDING_APPROVAL')).toBe(true);
    expect(isRefreshable('APPROVED')).toBe(false);
    expect(isConvertible('APPROVED')).toBe(true);
    expect(isConvertible('DRAFT')).toBe(false);
    expect(isConvertible('CONVERTED')).toBe(false);
  });
});

describe('审批同步失败', () => {
  it('失败原因非空时能被认出来', () => {
    expect(hasApprovalSyncError({ approvalSyncError: '审批服务暂时不可达' })).toBe(true);
  });

  it('没有失败原因（空串 / null）时不算失败', () => {
    expect(hasApprovalSyncError({ approvalSyncError: '' })).toBe(false);
    expect(hasApprovalSyncError({ approvalSyncError: '   ' })).toBe(false);
    expect(hasApprovalSyncError({ approvalSyncError: null })).toBe(false);
  });
});

describe('数量与金额的展示', () => {
  it('null 一律给「无数据」，绝不显示 0', () => {
    expect(fmt(null)).toBe(NO_DATA_TEXT);
    expect(fmt(undefined)).toBe(NO_DATA_TEXT);
    expect(fmtAmount(null)).toBe(NO_DATA_TEXT);
  });

  it('数量最多六位小数', () => {
    expect(fmt(120)).toBe('120');
    expect(fmt(1.5)).toBe('1.5');
  });

  it('金额保留两位到四位小数', () => {
    expect(fmtAmount(162.5)).toBe('162.50');
    expect(fmtAmount(0.125)).toBe('0.125');
  });

  it('交期未定给「未定」而不是空串', () => {
    expect(fmtDate(null)).toBe('未定');
    expect(fmtDate(undefined)).toBe('未定');
    expect(fmtDate('2026-12-05')).toBe('2026-12-05');
  });
});

describe('转单前缺什么', () => {
  it('逐行指出缺供应商还是缺交期', () => {
    const missing = missingConvertInputs([
      { skuCode: 'SKU-1', supplierId: null, requestedArrivalDate: '2026-12-05' },
      { skuCode: 'SKU-2', supplierId: 'supplier-1', requestedArrivalDate: null },
    ]);
    expect(missing).toEqual(['SKU-1（还没选供应商）', 'SKU-2（还没有交期）']);
  });

  it('齐了就没有缺项', () => {
    expect(
      missingConvertInputs([
        { skuCode: 'SKU-1', supplierId: 'supplier-1', requestedArrivalDate: '2026-12-05' },
      ]),
    ).toEqual([]);
  });

  it('空清单没有缺项', () => {
    expect(missingConvertInputs([])).toEqual([]);
  });
});
