import { describe, expect, it } from 'vitest';
import {
  CASE_STATUS_LABELS,
  canRevert,
  caseSummary,
  effectiveDate,
  effectiveMoment,
  isActionable,
  pendingTotal,
  settledTotal,
  successorLabel,
  type CaseCounts,
} from './offboarding-model';

const counts = (over: Partial<CaseCounts> = {}): CaseCounts => ({
  status: 'OPEN',
  responsibilityTotal: 0,
  responsibilityPending: 0,
  reclaimTotal: 0,
  reclaimPending: 0,
  ...over,
});

describe('17.2.2.3 离职与停用展示口径', () => {
  it('未结与已结条目数按两张台账分别累加', () => {
    const c = counts({
      responsibilityTotal: 5,
      responsibilityPending: 2,
      reclaimTotal: 3,
      reclaimPending: 1,
    });
    expect(pendingTotal(c)).toBe(3);
    // 已结 = (5-2) 责任 + (3-1) 回收 = 5
    expect(settledTotal(c)).toBe(5);
  });

  it('处理中的案卷一句话说清还剩多少事', () => {
    expect(
      caseSummary(
        counts({
          responsibilityTotal: 5,
          responsibilityPending: 2,
          reclaimTotal: 3,
          reclaimPending: 1,
        }),
      ),
    ).toBe('成员已停用，还有 3 项未结（已结 5 项）');
  });

  it('全部结清与没有任何条目是两种结论，不能混成同一句', () => {
    expect(caseSummary(counts({ status: 'COMPLETED' }))).toBe(
      '成员已停用，没有登记待转移或待回收的条目',
    );
    expect(
      caseSummary(counts({ status: 'COMPLETED', responsibilityTotal: 4, reclaimTotal: 2 })),
    ).toBe('成员已停用，6 项责任与待办已全部结清');
  });

  it('撤销态优先说已撤销，不被 pending=0 误报成已结清', () => {
    const reverted = counts({ status: 'REVERTED', responsibilityTotal: 2, reclaimTotal: 1 });
    expect(caseSummary(reverted)).toBe('已撤销离职，成员已恢复启用');
    expect(CASE_STATUS_LABELS.REVERTED).toBe('已撤销');
  });

  it('已结清的案卷仍可撤销，已撤销的不能二次撤销', () => {
    expect(canRevert('OPEN')).toBe(true);
    expect(canRevert('COMPLETED')).toBe(true);
    expect(canRevert('REVERTED')).toBe(false);
  });

  it('台账条目只有 PENDING 才可再动', () => {
    expect(isActionable('PENDING')).toBe(true);
    expect(isActionable('RECLAIMED')).toBe(false);
    expect(isActionable('TRANSFERRED')).toBe(false);
    expect(isActionable('REASSIGNED')).toBe(false);
  });

  it('接手人未指定时显示占位符而不是留空', () => {
    expect(successorLabel('alice')).toBe('alice');
    expect(successorLabel(null)).toBe('未指定接手人');
    expect(successorLabel('')).toBe('未指定接手人');
  });

  it('生效时刻按 UTC 切日，跨时区看到同一个日期', () => {
    // 09:30 +08:00 == 01:30Z，两地打开都应是 2026-01-01
    expect(effectiveDate('2026-01-01T09:30:00+08:00')).toBe('2026-01-01');
    expect(effectiveMoment('2026-01-01T09:30:00+08:00')).toBe('2026-01-01 01:30 UTC');
  });

  it('缺失或坏时间戳不渲染成 NaN', () => {
    expect(effectiveDate(null)).toBe('—');
    expect(effectiveDate('not-a-date')).toBe('—');
    expect(effectiveMoment(undefined)).toBe('—');
    expect(effectiveMoment('not-a-date')).toBe('—');
  });
});
