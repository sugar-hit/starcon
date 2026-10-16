import { describe, expect, it } from 'vitest';

import {
  UNKNOWN_NEXT_STEP,
  shipmentWritebackActions,
  shipmentWritebackBatchSummary,
  shipmentWritebackBlockedHint,
  shipmentWritebackStatusLabel,
  shipmentWritebackStatusLabels,
  shipmentWritebackSummaryView,
  shipmentWritebackTone,
  shipmentWritebackVerificationLabel,
} from './shipment-writeback-model';

describe('9.4.2.1 平台回传 · 呈现口径', () => {
  it('三态在标签与视觉分档上各自独立，不退化成两态', () => {
    // 「结果未知」既不是 ok 也不是 bad：这是本模块最容易被做错的一处。
    expect(shipmentWritebackTone('PENDING_VERIFICATION')).toBe('unknown');
    expect(shipmentWritebackTone('REJECTED')).toBe('bad');
    expect(shipmentWritebackTone('CONFIRMED')).toBe('ok');

    expect(shipmentWritebackStatusLabel('PENDING_VERIFICATION')).toBe('结果未知·待查证');
    // 「决定不回传」与「漏了回传」是两件事，标签不得合并。
    expect(shipmentWritebackStatusLabels.SKIPPED).toBe('决定不回传');
    expect(shipmentWritebackStatusLabel('SUPERSEDED')).toBe('已被修正版取代');
  });

  it('未知的状态缺省显示「未知」而不是留空', () => {
    expect(shipmentWritebackStatusLabel(undefined)).toBe('未知');
    expect(shipmentWritebackStatusLabel('')).toBe('未知');
    // 未知枚举值原样透出，不吞成「未知」——否则新增状态会被静默隐藏。
    expect(shipmentWritebackStatusLabel('BRAND_NEW')).toBe('BRAND_NEW');
  });

  it('查证结论缺省是「无需查证」而不是空白', () => {
    expect(shipmentWritebackVerificationLabel(undefined)).toBe('无需查证');
    expect(shipmentWritebackVerificationLabel('STILL_UNKNOWN')).toBe('查证后仍不确定');
  });
});

describe('9.4.2.1 平台回传 · 动作可用性', () => {
  it('结果未知态唯一可执行的动作是查证，重试必须禁用', () => {
    const a = shipmentWritebackActions('PENDING_VERIFICATION', 'NOT_REQUIRED');
    expect(a.canVerify).toBe(true);
    // 蓝图加粗约束在按钮上的落点：未知直接重发 = 平台侧重复发货。
    expect(a.canRetry).toBe(false);
    expect(a.canSubmit).toBe(false);
    expect(a.canRegisterOutcome).toBe(false);
  });

  it('查证结论仍是 STILL_UNKNOWN 时，即便状态可重试也不放行', () => {
    const a = shipmentWritebackActions('REJECTED', 'STILL_UNKNOWN');
    expect(a.canRetry).toBe(false);
    // 已经查过一次仍不确定：允许换个途径再查，但不允许重发。
    expect(a.canVerify).toBe(true);
  });

  it('查证确认未应用后，重试重新可用', () => {
    const a = shipmentWritebackActions('REJECTED', 'CONFIRMED_NOT_APPLIED');
    expect(a.canRetry).toBe(true);
    expect(a.canVerify).toBe(false);
  });

  it('已提交结果未回时拒绝重复提交与修正', () => {
    const a = shipmentWritebackActions('SUBMITTED', 'NOT_REQUIRED');
    expect(a.canRegisterOutcome).toBe(true);
    expect(a.canSubmit).toBe(false);
    expect(a.canCorrect).toBe(false);
  });

  it('终态既不能重试，也不能任意改写已生效的内容', () => {
    // CONFIRMED / SUPERSEDED：内容已生效或已被新版取代，改写等于抹掉事实。
    for (const s of ['CONFIRMED', 'SUPERSEDED']) {
      const a = shipmentWritebackActions(s, 'NOT_REQUIRED');
      expect(a.canCorrect).toBe(false);
      expect(a.canRetry).toBe(false);
    }
    // SKIPPED 仍可修正：它回答的是「当时为什么决定不回传」，后端保留那一版作证据，
    // 修正走**新建一版**而不是改掉它（见后端 correct() 与幂等键不可就地改写的触发器）。
    const skipped = shipmentWritebackActions('SKIPPED', 'NOT_REQUIRED');
    expect(skipped.canRetry).toBe(false);
    expect(skipped.canCorrect).toBe(true);
  });
});

describe('9.4.2.1 平台回传 · 未知态的处置提示', () => {
  it('未知态必须指向查证，且说清为什么不能直接重发', () => {
    const hint = shipmentWritebackBlockedHint('PENDING_VERIFICATION', 'NOT_REQUIRED');
    expect(hint).toBe(UNKNOWN_NEXT_STEP);
    expect(hint).toContain('必须先查证');
    expect(hint).toContain('重复发货');
  });

  it('已查证仍不确定时，提示换途径而不是重复同样的查询', () => {
    const hint = shipmentWritebackBlockedHint('PENDING_VERIFICATION', 'STILL_UNKNOWN');
    expect(hint).toContain('换一个查证途径');
    expect(hint).not.toBe(UNKNOWN_NEXT_STEP);
  });

  it('非未知态不给未知态提示', () => {
    expect(shipmentWritebackBlockedHint('REJECTED', 'NOT_REQUIRED')).toBe('');
    expect(shipmentWritebackBlockedHint(undefined, undefined)).toBe('');
  });
});

describe('9.4.2.1 平台回传 · 汇总与批量重试', () => {
  it('汇总只做搬运，不在页内自算', () => {
    const v = shipmentWritebackSummaryView({
      totalTasks: 10,
      pendingTasks: 3,
      submittedTasks: 1,
      confirmedTasks: 2,
      rejectedTasks: 2,
      unknownTasks: 2,
      dueTasks: 4,
      resendAllowedTasks: 3,
      atCapTasks: 1,
    });
    expect(v.total).toBe(10);
    expect(v.unknown).toBe(2);
    // 退避中的不计入 due —— 否则「该发的」和「到点的」会混成一个数。
    expect(v.due).toBe(4);
    expect(v.resendAllowed).toBe(3);
  });

  it('批量重试逐条说清谁被重投、谁被哪道闸门拦下', () => {
    const lines = shipmentWritebackBatchSummary({
      submitted: 3,
      rescheduled: 1,
      blockedUnknown: 2,
      blockedAtCap: 1,
      blockedBackoff: 0,
      blockedTerminal: 0,
    });
    expect(lines).toHaveLength(4);
    expect(lines[0]).toContain('已重投 3 条');
    // 退避未到点不算失败 —— 改排而不是跳过。
    expect(lines[1]).toContain('不算失败');
    expect(lines[2]).toContain('结果未知待查证');
    expect(lines[3]).toContain('重试上限');
  });

  it('全被闸门拦下时也要有话说，不返回空列表', () => {
    const lines = shipmentWritebackBatchSummary({
      submitted: 0,
      rescheduled: 0,
      blockedUnknown: 5,
      blockedAtCap: 0,
      blockedBackoff: 0,
      blockedTerminal: 0,
    });
    expect(lines).toEqual(['5 条结果未知待查证，按规矩不放行']);
  });

  it('空结果也给一句人话', () => {
    expect(
      shipmentWritebackBatchSummary({
        submitted: 0,
        rescheduled: 0,
        blockedUnknown: 0,
        blockedAtCap: 0,
        blockedBackoff: 0,
        blockedTerminal: 0,
      }),
    ).toEqual(['没有可重投的任务']);
  });
});
