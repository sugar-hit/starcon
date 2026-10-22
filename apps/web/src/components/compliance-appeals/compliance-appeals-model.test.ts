import { describe, expect, it } from 'vitest';
import {
  APPEAL_TYPES,
  BASE,
  EVIDENCE_TYPES,
  OUTCOMES,
  STATUSES,
  appealTypeLabel,
  emptyState,
  emptyStateKind,
  evidenceTypeLabel,
  impactLabel,
  impactTone,
  outcomeLabel,
  quotaText,
  remainingText,
  statusLabel,
  statusTone,
} from './compliance-appeals-model';

/**
 * 16.1.2.2 申诉管理 前端模型的单测。
 *
 * 🔴 这组用例守的是六件最容易做丢的事：
 *
 * 1. **配额永远显示得出来**：上限 / 已用 / 剩几次三样都在。
 * 2. **「没配配额」≠「无限」** —— 显示成「未配置」，**不是 0**。
 * 3. **「尚无处罚措施」≠「没问题」** —— 色调与文案都单独一份。
 * 4. **值域与后端一致**：申诉类型 6 码 / 状态 5 态 / 结果 2 码。
 * 5. **影响档位的人话与服务端同口径**（上游 16.1.2.1 派生）。
 * 6. **三态空态文案必须各不相同**（同一个空态换个图标不算三态）。
 */
describe('申诉管理的受控值域（与后端 CHECK 一致）', () => {
  it('申诉类型恰好六个，状态五个，结果两个', () => {
    expect(APPEAL_TYPES).toEqual([
      'POLICY_MISINTERPRETATION',
      'EVIDENCE_PROVIDED',
      'PROCESS_ERROR',
      'DUPLICATE_PENALTY',
      'RESTORATION_REQUEST',
      'OTHER',
    ]);
    expect(STATUSES).toEqual(['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN']);
    expect(OUTCOMES).toEqual(['APPROVED', 'REJECTED']);
    expect(EVIDENCE_TYPES).toEqual([
      'DOCUMENT',
      'IMAGE',
      'VIDEO',
      'LINK',
      'NOTE',
      'INVOICE',
      'CERTIFICATE',
    ]);
  });

  it('值域都有中文人话', () => {
    expect(appealTypeLabel('POLICY_MISINTERPRETATION')).toBe('政策误判');
    expect(appealTypeLabel('EVIDENCE_PROVIDED')).toBe('补充证据');
    expect(appealTypeLabel('OTHER')).toBe('其他');
    expect(statusLabel('DRAFT')).toBe('草稿（未提交）');
    expect(statusLabel('SUBMITTED')).toBe('已提交（等平台裁定）');
    expect(outcomeLabel('APPROVED')).toBe('通过');
    expect(outcomeLabel('REJECTED')).toBe('驳回');
    expect(evidenceTypeLabel('CERTIFICATE')).toBe('资质证书');
  });

  it('api-proxy 白名单前缀是独立的一条路径', () => {
    expect(BASE).toBe('channel/platform-appeals');
  });
});

describe('配额口径（蓝图加粗句「申诉配额有限」）', () => {
  it('配了配额 ⇒ 上限 / 已用 / 剩余三样都显示得出来', () => {
    const text = quotaText({
      shopId: 's1',
      shopCode: 'SHOP-1',
      shopName: '测试店铺',
      periodStart: '2026-10-01T00:00:00Z',
      periodEnd: '2026-12-31T23:59:59Z',
      quotaLimit: 5,
      usedCount: 2,
      remaining: 3,
      configured: true,
      note: '',
    });
    expect(text).toContain('5 次');
    expect(text).toContain('已用 2 次');
    expect(text).toContain('剩 3 次');
  });

  it('🔴 没配配额 ⇒ 显示成「未配置」，**不是 0**（没配 ≠ 无限）', () => {
    const text = quotaText({
      shopId: 's1',
      shopCode: 'SHOP-1',
      shopName: '测试店铺',
      periodStart: null,
      periodEnd: null,
      quotaLimit: 0,
      usedCount: 0,
      remaining: 0,
      configured: false,
      note: '',
    });
    expect(text).toContain('未配置');
    expect(text).not.toContain('剩 0 次');
    expect(quotaText(null)).toContain('未配置');
  });

  it('剩余次数：没配 ≠ 用完，两件事分开说', () => {
    expect(remainingText(false, -1)).toBe('未配置配额');
    expect(remainingText(true, 0)).toBe('配额已用尽');
    expect(remainingText(true, 3)).toBe('还剩 3 次');
  });
});

describe('影响档位（派生，不落库）', () => {
  it('档位的人话与服务端同口径（含上游的「尚无处罚措施」）', () => {
    expect(impactLabel('CRITICAL')).toBe('致命（停号级）');
    expect(impactLabel('HIGH')).toBe('严重（下架级）');
    expect(impactLabel('MEDIUM')).toBe('中等（限制销售 / 扣款级）');
    expect(impactLabel('LOW')).toBe('轻微（警告级）');
    expect(impactLabel('NONE')).toBe('尚无处罚措施');
  });

  it('🔴 「尚无处罚措施」用中性色，不与「轻微（警告级）」共用绿色', () => {
    expect(impactTone('NONE')).not.toBe(impactTone('LOW'));
    expect(impactTone('CRITICAL')).toBe('danger');
    expect(impactTone('HIGH')).toBe('warning');
  });
});

describe('申诉状态的色调', () => {
  it('通过是绿的、驳回是红的、在办是蓝的、终态是中性', () => {
    expect(statusTone('APPROVED')).toBe('success');
    expect(statusTone('REJECTED')).toBe('danger');
    expect(statusTone('SUBMITTED')).toBe('info');
    expect(statusTone('WITHDRAWN')).toBe('neutral');
  });
});

describe('空态三态', () => {
  it('没数据与筛不出是两回事，文案必须不同', () => {
    expect(emptyStateKind(false, 0)).toBe('no-data');
    expect(emptyStateKind(true, 0)).toBe('no-match');
    expect(emptyStateKind(false, 3)).toBe('no-match');
    expect(emptyState('no-data')).not.toBe(emptyState('no-match'));
  });
});
