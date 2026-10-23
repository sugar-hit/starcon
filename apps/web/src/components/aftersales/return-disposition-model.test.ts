import { describe, expect, it } from 'vitest';
import {
  canApprove,
  canCancel,
  canExecute,
  canSubmit,
  DISPOSITION_STATUS_OPTIONS,
  financePending,
  requiresTargetLocation,
  SCRAP_REASON_OPTIONS,
  scrapReady,
  statusLabel,
  TARGET_STATE_OPTIONS,
  targetStateLabel,
} from './return-disposition-model';

/**
 * 11.2.3.2 处置状态机的前端单元测试。
 *
 * <p>测的是蓝图加粗句「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理」
 * 在界面上是不是真的落成了一道道闸门：报废不能跳过审批、不能自己批自己、
 * 执行失败要能重试但不能改成「已执行」。
 *
 * <p>这一层与服务端的状态机是**镜像**：服务端用 DB 触发器钉死（V337 守卫②③④），
 * 前端必须有一份同样的判据 —— 否则界面会渲染一个注定被拒的动作，
 * 让操作员以为自己遇到的是偶发故障。
 */
describe('处置单状态机：每个状态只允许它该有的动作', () => {
  it('只有草稿能提交待审', () => {
    expect(canSubmit('DRAFT')).toBe(true);
    expect(canSubmit('PENDING_APPROVAL')).toBe(false);
    expect(canSubmit('APPROVED')).toBe(false);
    expect(canSubmit('EXECUTED')).toBe(false);
  });

  it('只有待审能审批', () => {
    expect(canApprove('PENDING_APPROVAL')).toBe(true);
    expect(canApprove('DRAFT')).toBe(false);
    expect(canApprove('APPROVED')).toBe(false);
    expect(canApprove('EXECUTED')).toBe(false);
  });

  it('已批可执行；执行失败可原样重试', () => {
    expect(canExecute('APPROVED')).toBe(true);
    expect(canExecute('EXECUTION_FAILED')).toBe(true);
    expect(canExecute('DRAFT')).toBe(false);
    expect(canExecute('PENDING_APPROVAL')).toBe(false);
  });

  it('终态（已执行 / 已驳回 / 已撤销）什么都不给做', () => {
    expect(canCancel('EXECUTED')).toBe(false);
    expect(canCancel('REJECTED')).toBe(false);
    expect(canCancel('CANCELLED')).toBe(false);
    expect(canCancel('APPROVED')).toBe(true);
    expect(canCancel('EXECUTION_FAILED')).toBe(true);
  });
});

describe('报废要原因码与说明', () => {
  it('原因码与说明都齐了才算就绪', () => {
    expect(scrapReady('DAMAGED_UNREPAIRABLE', '外箱压损不可修复')).toBe(true);
    expect(scrapReady('', '外箱压损不可修复')).toBe(false);
    expect(scrapReady('DAMAGED_UNREPAIRABLE', '  ')).toBe(false);
  });

  it('损耗原因码是受控词表，不是自由文本', () => {
    expect(SCRAP_REASON_OPTIONS.map((o) => o.code)).toEqual([
      'DAMAGED_UNREPAIRABLE',
      'MISSING_PARTS',
      'FAILED_FUNCTIONAL_TEST',
      'BEYOND_ECONOMIC_REPAIR',
      'OTHER',
    ]);
    // 「其它」必须有说明 —— 所以它不能是唯一允许的码。
    expect(SCRAP_REASON_OPTIONS.some((o) => o.code === 'OTHER')).toBe(true);
  });
});

describe('只有转可售 / 转残次需要目标库位', () => {
  it('报废没有目标库位（货被核销，不在任何库位上）', () => {
    expect(requiresTargetLocation('SCRAP')).toBe(false);
    expect(requiresTargetLocation('SELLABLE')).toBe(true);
    expect(requiresTargetLocation('DEFECTIVE')).toBe(true);
  });
});

describe('财务侧字段为空时显示「待确认」，不是 0', () => {
  it('损耗记录的财务金额为空 ⇒ 财务侧还没确认', () => {
    expect(financePending({ financeLossAmount: null, financeReference: null })).toBe(true);
    expect(financePending({ financeLossAmount: 12.5, financeReference: 'FV-1' })).toBe(false);
    expect(financePending(null)).toBe(false);
  });
});

describe('三值枚举与状态词表', () => {
  it('去向是三值（SELLABLE / DEFECTIVE / SCRAP），不是「可售 / 不可售」两分', () => {
    expect(TARGET_STATE_OPTIONS.map((o) => o.code)).toEqual(['SELLABLE', 'DEFECTIVE', 'SCRAP']);
    expect(new Set(TARGET_STATE_OPTIONS.map((o) => o.label)).size).toBe(3);
  });

  it('处置单状态词表覆盖全部七个状态（含三个终态）', () => {
    expect(DISPOSITION_STATUS_OPTIONS.map((o) => o.code)).toEqual([
      'DRAFT',
      'PENDING_APPROVAL',
      'APPROVED',
      'REJECTED',
      'EXECUTED',
      'EXECUTION_FAILED',
      'CANCELLED',
    ]);
    expect(statusLabel('EXECUTED')).toBe('已执行');
    expect(statusLabel('UNKNOWN')).toBe('UNKNOWN');
    expect(targetStateLabel('SCRAP')).toBe('报废');
  });
});
