import { describe, expect, it } from 'vitest';

import {
  ANOMALY_HINT,
  VERIFIED_SCOPE_HINT,
  availableActions,
  confirmedVerification,
  eventSummary,
  overdueText,
  releaseSummary,
  severityTone,
  thresholdSummary,
  thresholdText,
} from './fulfillment-monitor-model';
import type {
  FulfillmentMonitorCaseRow,
  FulfillmentMonitorReleaseRow,
  FulfillmentMonitorVerificationRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 9.3.3.2 履约流程监控的展示辅助。
 *
 * <p>重点测三件容易在界面上说错的事：
 *
 * <ol>
 *   <li><b>阈值三句话不能少「谁定的」</b> —— 租户默认 / 仓库覆盖 / 建单快照 / 内置兜底
 *       四种，改错了地方完全看不出来。</li>
 *   <li><b>「待回执」不能显示成「已释放」</b> —— 释放预占只是登记指令，
 *       warehouse 那边还没收到任何东西；显示成已释放就会有人以为货回池、于是又发一次。</li>
 *   <li><b>证伪的案例不能重试</b> —— {@link confirmedVerification} 只认 CONFIRMED。</li>
 * </ol>
 */

function caseRow(over: Partial<FulfillmentMonitorCaseRow> = {}): FulfillmentMonitorCaseRow {
  return {
    id: 'c1',
    caseNo: 'MC-abc',
    requestId: 'r1',
    requestCode: 'FR-1',
    orderRef: 'SO-1',
    skuRef: 'SKU-1',
    warehouseRef: 'WH-1',
    stepKind: 'RESERVE',
    stepKindLabel: '锁定预占',
    attempt: 1,
    anomalyKind: 'STEP_STUCK',
    anomalyKindLabel: '流程卡在某步',
    severity: 'WARNING',
    severityLabel: '警告',
    thresholdSeconds: 3600,
    thresholdSource: 'TENANT_DEFAULT',
    thresholdSourceLabel: '租户默认档',
    overdueSeconds: 7500,
    ruleEvidence: '已超 7500 秒',
    detectedAt: '2026-10-17T10:00:00+08:00',
    status: 'VERIFIED',
    statusLabel: '已查证',
    monitorVersion: 1,
    investigatingAt: '',
    verifiedAt: '',
    resolvedAt: '',
    resolutionNote: '',
    ...over,
  };
}

describe('overdueText / thresholdText', () => {
  it('秒级差值原样显示', () => {
    expect(overdueText(45)).toBe('45 秒');
  });

  it('分钟级带余秒', () => {
    expect(overdueText(125)).toBe('2 分 5 秒');
  });

  it('小时级带余分 —— 卡住的主指标不能只给一个裸秒数', () => {
    expect(overdueText(7500)).toBe('2 小时 5 分');
  });

  it('超过一天折算成天', () => {
    expect(overdueText(90000)).toBe('1 天 1 小时');
  });

  it('负值被夹成 0 而不是显示负耗时', () => {
    expect(overdueText(-3)).toBe('0 秒');
  });

  it('阈值与耗时用同一把尺子，两句话才能对比着读', () => {
    expect(thresholdText(3600)).toBe('1 小时');
  });
});

describe('thresholdSummary', () => {
  it('三句话齐全：卡了多久 / 阈值多久 / 谁定的', () => {
    const text = thresholdSummary(caseRow());
    expect(text).toContain('2 小时 5 分');
    expect(text).toContain('阈值 1 小时');
    expect(text).toContain('租户默认档');
  });

  it('来源换成仓库覆盖档也要照实显示', () => {
    const text = thresholdSummary(
      caseRow({
        thresholdSource: 'TENANT_OVERRIDE',
        thresholdSourceLabel: '仓库覆盖档',
      }),
    );
    expect(text).toContain('仓库覆盖档');
  });

  it('内置兜底也标出来 —— 不能让人误以为有人配过', () => {
    const text = thresholdSummary(
      caseRow({ thresholdSource: 'BUILTIN', thresholdSourceLabel: '内置兜底' }),
    );
    expect(text).toContain('内置兜底');
  });
});

describe('severityTone', () => {
  it('CRITICAL 走危险色，INFO 走弱化色', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warning');
    expect(severityTone('INFO')).toBe('muted');
  });
});

describe('confirmedVerification', () => {
  const confirmed: FulfillmentMonitorVerificationRow = {
    id: 'v1',
    verificationNo: 'VER-1',
    conclusion: 'CONFIRMED',
    conclusionLabel: '坐实了异常',
    verifiedScope: 'DOWNSTREAM_ACK',
    verifiedScopeLabel: '已向下游查证回执',
    evidenceRef: 'RSV-1',
    finding: '下游确实没动',
    verifiedAt: '2026-10-17T10:00:00+08:00',
    verifiedByMembershipId: 'u1',
  };
  const disproven: FulfillmentMonitorVerificationRow = {
    ...confirmed,
    id: 'v2',
    conclusion: 'NOT_CONFIRMED',
    conclusionLabel: '证伪，不是异常',
  };

  it('认坐实的那条', () => {
    expect(confirmedVerification([disproven, confirmed])?.id).toBe('v1');
  });

  it('只有证伪记录时返回 null —— 证伪的异常绝不许重试', () => {
    expect(confirmedVerification([disproven])).toBeNull();
  });

  it('空列表返回 null', () => {
    expect(confirmedVerification([])).toBeNull();
  });
});

describe('releaseSummary', () => {
  const base: FulfillmentMonitorReleaseRow = {
    id: 'rel1',
    releaseNo: 'REL-1',
    caseId: 'c1',
    requestId: 'r1',
    dispositionId: 'd1',
    reservationId: 'rsv1',
    sourceKind: 'TENANT_MAINTAINED',
    sourceKindLabel: '租户自行登记',
    capturedAt: '',
    status: 'REQUESTED',
    statusLabel: '待回执',
    releaseRef: '',
    releasedQty: 0,
    rejectReason: '',
    requestedAt: '',
    acknowledgedAt: '',
    idempotencyKey: 'k1',
  };

  it('待回执必须说清货尚未释放', () => {
    const text = releaseSummary(base);
    expect(text).toContain('待 7.2 回执');
    expect(text).toContain('尚未');
  });

  it('真的拿到回执才显示已释放，并带出凭据号', () => {
    const text = releaseSummary({
      ...base,
      status: 'RELEASED',
      statusLabel: '已释放',
      releaseRef: 'REL-DOWNSTREAM',
    });
    expect(text).toContain('已释放');
    expect(text).toContain('REL-DOWNSTREAM');
  });

  it('被拒要显示理由', () => {
    const text = releaseSummary({
      ...base,
      status: 'REJECTED',
      statusLabel: '已被拒',
      rejectReason: '库存已被别的单占用',
    });
    expect(text).toContain('库存已被别的单占用');
  });
});

describe('eventSummary', () => {
  it('有前后状态时用箭头串起来', () => {
    const text = eventSummary({
      id: 'e1',
      eventKind: 'VERIFIED',
      eventKindLabel: '查证坐实',
      fromStatus: 'INVESTIGATING',
      toStatus: 'VERIFIED',
      monitorVersion: 2,
      detail: '已向 7.2 查证',
      actorId: 'u1',
      occurredAt: '',
    });
    expect(text).toContain('查证坐实');
    expect(text).toContain('INVESTIGATING → VERIFIED');
    expect(text).toContain('已向 7.2 查证');
  });

  it('detail 为 null 时不拼出「· null」', () => {
    const text = eventSummary({
      id: 'e2',
      eventKind: 'DETECTED',
      eventKindLabel: '已检出异常',
      fromStatus: '',
      toStatus: 'OPEN',
      monitorVersion: 0,
      detail: '' as unknown as string,
      actorId: '',
      occurredAt: '',
    });
    expect(text).not.toContain('null');
  });
});

describe('availableActions', () => {
  it('未结案给四类处置入口', () => {
    expect(availableActions('OPEN')).toEqual([
      'RETRY',
      'SWITCH_PATH',
      'MANUAL_INTERVENTION',
      'RELEASE_RESERVATION',
    ]);
    expect(availableActions('VERIFIED')).toHaveLength(4);
  });

  it('已处置 / 已证伪不再给处置入口', () => {
    expect(availableActions('DISPOSED')).toEqual([]);
    expect(availableActions('DISMISSED')).toEqual([]);
  });
});

describe('四类异常各有处置提示', () => {
  it('四类都配了提示，且都强调先查证', () => {
    expect(Object.keys(ANOMALY_HINT)).toEqual([
      'RESERVATION_FAILED',
      'INSUFFICIENT_STOCK',
      'STEP_STUCK',
      'SHIPMENT_TIMEOUT',
    ]);
    // 「重试会重复发货」这句必须在超时两类里出现 —— 那正是加粗句要防的事。
    expect(ANOMALY_HINT.STEP_STUCK).toContain('重试会重复发货');
    expect(ANOMALY_HINT.SHIPMENT_TIMEOUT).toContain('重试会造成重复发货');
  });
});

describe('查证范围提示', () => {
  it('「只看本地状态」明确标出不足以支持重试', () => {
    expect(VERIFIED_SCOPE_HINT.LOCAL_STATE_ONLY).toContain('不足以');
  });

  it('四类范围都有说明', () => {
    expect(Object.keys(VERIFIED_SCOPE_HINT)).toHaveLength(4);
  });
});
