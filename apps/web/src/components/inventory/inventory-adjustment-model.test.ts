import { describe, expect, it } from 'vitest';
import {
  actionBalanceNotes,
  actionLabels,
  allowedActions,
  approveProblem,
  attachmentSummary,
  auditActionLabels,
  createProblem,
  errorText,
  formatMoment,
  lineProblem,
  lineStateText,
  orderSummary,
  qty,
  rejectProblem,
  selectableReasons,
  signedQty,
  signedTone,
  statusLabels,
  statusTone,
} from './inventory-adjustment-model';
import { ApiError } from './api';

/**
 * 7.4.1.1 调整单管理前端纯函数单测。
 *
 * <p>测的都是「界面上最容易显示错」的地方：方向、超精度、null 显示成 0、
 * 不可用原因码、以及**每个动作到底改不改余额**。
 */
describe('库存调整单 · 数量与方向', () => {
  it('null 显示成破折号而不是 0', () => {
    expect(qty(null)).toBe('—');
    expect(qty(undefined)).toBe('—');
    expect(qty(0)).toBe('0');
  });

  it('带符号数量带方向', () => {
    expect(signedQty(3)).toBe('+3');
    expect(signedQty(-2)).toBe('−2');
    expect(signedQty(0)).toBe('0');
    expect(signedQty(null)).toBe('—');
  });

  it('正数是增调、负数是减调', () => {
    expect(signedTone(1)).toBe('ok');
    expect(signedTone(-1)).toBe('bad');
    expect(signedTone(0)).toBe('neutral');
    expect(signedTone(null)).toBe('neutral');
  });
});

describe('库存调整单 · 行校验', () => {
  it('方向写反被拦下', () => {
    expect(lineProblem('OVERAGE', '-3', 1)).toContain('正数');
    expect(lineProblem('SHORTAGE', '3', 1)).toContain('负数');
  });

  it('零与非法数字被拦下', () => {
    expect(lineProblem('DAMAGE', '0', 1)).toContain('不能是 0');
    expect(lineProblem('DAMAGE', 'abc', 1)).toContain('必须是数字');
    expect(lineProblem('DAMAGE', '', 1)).toContain('必须填写');
  });

  it('超过四位小数被拒而不是被静默四舍五入', () => {
    expect(lineProblem('DAMAGE', '-1.00005', 1)).toContain('4 位小数');
    expect(lineProblem('DAMAGE', '-1.0000', 1)).toBeNull();
  });

  it('缺 expected_version 被拦下', () => {
    expect(lineProblem('DAMAGE', '-1', null)).toContain('expected_version');
  });

  it('没选原因码被拦下', () => {
    expect(lineProblem('', '-1', 1)).toContain('原因');
  });
});

describe('库存调整单 · 单据校验', () => {
  it('零行被拒', () => {
    expect(createProblem('DAMAGE', 'x', [])).toContain('至少要有一行');
  });

  it('指出是第几行有问题', () => {
    const problem = createProblem('DAMAGE', 'x', [
      { reasonCode: 'DAMAGE', qty: '-1', expectedVersion: 1 },
      { reasonCode: 'OVERAGE', qty: '-1', expectedVersion: 1 },
    ]);
    expect(problem).toContain('第 2 行');
  });

  it('批准与驳回都必须写理由', () => {
    expect(approveProblem('  ')).toContain('批准必须写理由');
    expect(rejectProblem('')).toContain('驳回必须写意见');
    expect(approveProblem('现场核实无误')).toBeNull();
  });
});

describe('库存调整单 · 状态机', () => {
  it('每个状态只给合法的动作', () => {
    expect(allowedActions('DRAFT')).toEqual(['submit', 'cancel']);
    expect(allowedActions('PENDING_APPROVAL')).toEqual(['approve', 'reject', 'cancel']);
    expect(allowedActions('APPROVED')).toEqual(['post', 'cancel']);
    // 已过账与已作废是终态：不给任何按钮
    expect(allowedActions('POSTED')).toEqual([]);
    expect(allowedActions('CANCELLED')).toEqual([]);
  });

  it('只有过账改余额', () => {
    expect(actionBalanceNotes.post).toContain('改余额');
    for (const action of ['submit', 'approve', 'reject', 'cancel']) {
      expect(actionBalanceNotes[action]).toContain('不改余额');
    }
    expect(Object.keys(actionLabels)).toEqual(expect.arrayContaining(['submit', 'post']));
  });

  it('状态色区分中间态与终态', () => {
    expect(statusTone('PENDING_APPROVAL')).toBe('warn');
    expect(statusTone('APPROVED')).toBe('warn');
    expect(statusTone('REJECTED')).toBe('bad');
    expect(statusTone('CANCELLED')).toBe('bad');
    expect(statusTone('DRAFT')).toBe('ok');
    expect(statusTone('POSTED')).toBe('ok');
    expect(statusLabels.POSTED).toBe('已过账');
  });
});

describe('库存调整单 · 行状态', () => {
  it('已过账的行标成已过账', () => {
    expect(lineStateText({ postedAt: '2026-10-21T10:00:00+08:00' })).toBe('已过账');
  });

  it('库位明细又动过的行标成已过期', () => {
    expect(lineStateText({ expectedVersion: 3, currentBucketVersion: 5 })).toContain('已过期');
  });

  it('正常的待过账行不报警', () => {
    expect(lineStateText({ expectedVersion: 3, currentBucketVersion: 3 })).toBe('待过账');
  });
});

describe('库存调整单 · 不可用的原因码', () => {
  it('不可用的码不进入可选列表', () => {
    const options = [
      { value: 'DAMAGE', label: '损坏', description: '', available: true, ownerModule: '', note: '' },
      {
        value: 'PLATFORM_VARIANCE',
        label: '平台仓差异',
        description: '',
        available: false,
        ownerModule: '10.1.1.1',
        note: '平台仓是观测值',
      },
    ];
    expect(selectableReasons(options).map((o) => o.value)).toEqual(['DAMAGE']);
  });
});

describe('库存调整单 · 文案', () => {
  it('统一错误文案区分 409/403/422/503', () => {
    expect(errorText(new ApiError(403, 'x'))).toContain('warehouse.inventory.update');
    expect(errorText(new ApiError(409, '库存已变动'))).toContain('库存已变动');
    expect(errorText(new ApiError(422, '该原因码不可用'))).toContain('不可用');
    expect(errorText(new ApiError(503, 'trade 不可达'))).toContain('不可用');
    expect(errorText(new Error('普通错误'))).toBe('普通错误');
  });

  it('单据概要与附件摘要可读', () => {
    expect(
      orderSummary({
        id: '1',
        code: 'ADJ-INV-20261021-ABCD1234',
        status: 'DRAFT',
        reasonCode: 'DAMAGE',
        reasonLabel: '损坏',
        note: '',
        sourceType: null,
        sourceOrderId: null,
        sourceInstructionId: null,
        sourceReference: '',
        lineCount: 2,
        gainLineCount: 0,
        lossLineCount: 2,
        gainQty: 0,
        lossQty: 5,
        netQty: -5,
        submittedByUserId: null,
        submittedAt: null,
        decidedByUserId: null,
        decidedAt: null,
        decisionNote: null,
        postedByUserId: null,
        postedAt: null,
        cancelledByUserId: null,
        cancelledAt: null,
        createdByUserId: 'u',
        createdAt: '2026-10-21T10:00:00+08:00',
      }),
    ).toContain('ADJ-INV-20261021-ABCD1234');
    expect(
      attachmentSummary({
        id: 'a',
        orderId: 'o',
        mediaId: 'm',
        fileName: '破损.jpg',
        mediaType: 'image/jpeg',
        sizeBytes: 20480,
        mountedAt: '2026-10-21T10:00:00+08:00',
        mountedByUserId: 'u',
      }),
    ).toContain('破损.jpg');
  });

  it('时间缺失显示破折号', () => {
    expect(formatMoment(null)).toBe('—');
    expect(formatMoment('2026-10-21T10:00:00+08:00')).toContain('2026');
  });

  it('审计动作有中文名', () => {
    expect(auditActionLabels.POSTED).toBe('过账');
    expect(auditActionLabels.CREATED).toBe('建单');
  });
});
