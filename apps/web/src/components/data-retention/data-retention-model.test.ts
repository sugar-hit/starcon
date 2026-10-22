import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  actorLabel,
  canApproveDeletion,
  canExecuteCleanup,
  canExecuteDeletion,
  cleanupStatusLabel,
  cleanupStatusTone,
  conflictKindLabel,
  conflictSummary,
  dateText,
  daysText,
  deferralText,
  deletionStatusLabel,
  deletionStatusTone,
  emptyState,
  resolutionLabel,
  resolutionTone,
  retentionClassLabel,
  transferStatusLabel,
  transferStatusTone,
  unavailableState,
  unavailableUpstreams,
  type DataGovernanceDeletionRequest,
  type DataGovernanceUpstreamState,
  type RetentionCleanupOrder,
  type RetentionConflict,
} from './data-retention-model';

/**
 * 16.6.2.1 数据出境与留存 前端模型的纯函数断言。
 *
 * 🔴 这个页面上最容易做丢的事，前端这一层再挡一道：
 * **裁决过程看得见**、**「推迟」与「驳回」分得开**、**「判不了」与「没冲突」分得开**、
 * **未交付上游说「读不到」而不是「没有数据」**、**四种状态文案各不相同**。
 */

function conflict(overrides: Partial<RetentionConflict> = {}): RetentionConflict {
  return {
    domainCode: 'ORDER',
    domainName: '9 订单',
    standardPolicyId: 'p-1',
    standardRetentionDays: 180,
    standardLegalBasis: '内部制度',
    financialPolicyId: 'p-2',
    financialRetentionDays: 3650,
    financialLegalBasis: '税法要求',
    conflictKind: 'FINANCIAL_LONGER',
    conflictKindLabel: '财务（法定）留存期更长',
    resolution: 'FINANCIAL_PREVAILS',
    resolutionLabel: '财务留存优先',
    effectiveRetentionDays: 3650,
    deletionBlockedUntil: '2036-01-01T00:00:00Z',
    deletionBlocked: true,
    rationale: '冲突对：普通留存期 180 天 vs 法定（财务）留存期 3650 天。裁决：财务留存优先。',
    lastAdjudicatedAt: '2026-10-23T02:00:00Z',
    ...overrides,
  };
}

function deletion(
  overrides: Partial<DataGovernanceDeletionRequest> = {},
): DataGovernanceDeletionRequest {
  return {
    requestId: 'r-1',
    requestNo: 'DR-20261023-ABCD1234',
    requestType: 'FORGETTING_RIGHT',
    requestTypeLabel: '被遗忘权（GDPR 第 17 条）',
    subjectKind: 'ORDER',
    subjectRef: 'SO-1001',
    domainCode: 'ORDER',
    domainName: '9 订单',
    reason: '买家要求删除个人数据',
    status: 'PENDING',
    statusLabel: '待审批',
    requestedBy: '11111111-2222-3333-4444-555555555555',
    requestedByName: '',
    requestedAt: '2026-10-23T02:00:00Z',
    approvedBy: null,
    approvedByName: null,
    approvedAt: null,
    decisionNote: '',
    executedBy: null,
    executedByName: null,
    executedAt: null,
    deferUntil: null,
    deferReason: '',
    cleanupWorkOrderId: null,
    cleanupOrderNo: null,
    createdAt: '2026-10-23T02:00:00Z',
    ...overrides,
  };
}

function cleanupOrder(overrides: Partial<RetentionCleanupOrder> = {}): RetentionCleanupOrder {
  return {
    orderId: 'o-1',
    orderNo: 'CL-20261023-ABCD1234',
    domainCode: 'ORDER',
    domainName: '9 订单',
    subjectRef: 'SO-2001',
    triggerKind: 'MANUAL',
    triggerKindLabel: '人工发起',
    retentionDays: 180,
    retentionClass: 'STANDARD',
    retentionClassLabel: '普通留存期',
    affectedRecordCount: 0,
    status: 'PENDING',
    statusLabel: '待处理',
    eligibleAt: '2027-01-01T00:00:00Z',
    dueAt: '2027-01-01T00:00:00Z',
    requestedBy: '11111111-2222-3333-4444-555555555555',
    requestedByName: '',
    createdAt: '2026-10-23T02:00:00Z',
    updatedAt: '2026-10-23T02:00:00Z',
    ...overrides,
  };
}

describe('16.6.2.1 数据出境与留存前端模型', () => {
  it('裁决摘要把冲突对、双方期限与裁决结果都说出来', () => {
    const summary = conflictSummary(conflict());
    expect(summary).toContain('9 订单');
    expect(summary).toContain('180');
    expect(summary).toContain('3650');
    expect(summary).toContain('财务留存优先');
    expect(summary).toContain('税法要求');
  });

  it('「没登记留存策略」与「没冲突」是两种文案', () => {
    expect(conflictKindLabel('NO_POLICY')).toContain('判不了');
    expect(conflictKindLabel('NO_CONFLICT')).toBe('没有冲突');
    expect(conflictKindLabel('FINANCIAL_LONGER')).toContain('财务');
    expect(conflictKindLabel('STANDARD_LONGER')).toContain('普通');
    expect(new Set([conflictKindLabel('NO_POLICY'), conflictKindLabel('NO_CONFLICT')]).size).toBe(
      2,
    );
  });

  it('没登记留存策略时，摘要如实说「判不了」，不假装有生效期', () => {
    const summary = conflictSummary(
      conflict({
        conflictKind: 'NO_POLICY',
        standardPolicyId: null,
        standardRetentionDays: null,
        financialPolicyId: null,
        financialRetentionDays: null,
        effectiveRetentionDays: null,
      }),
    );
    expect(summary).toContain('判不了');
    expect(summary).not.toContain('vs');
  });

  it('裁决结果的标签与色调分得开', () => {
    expect(resolutionLabel('FINANCIAL_PREVAILS')).toBe('财务留存优先');
    expect(resolutionLabel('STANDARD_APPLIES')).toBe('按普通留存期');
    expect(resolutionTone('FINANCIAL_PREVAILS')).toBe('warning');
    expect(resolutionTone('STANDARD_APPLIES')).toBe('success');
  });

  it('「推迟」与「驳回」是两种状态，不能都写成「没批」', () => {
    expect(deletionStatusLabel('DEFERRED')).toContain('推迟');
    expect(deletionStatusLabel('REJECTED')).toBe('已驳回');
    expect(deletionStatusTone('DEFERRED')).toBe('info');
    expect(deletionStatusTone('REJECTED')).toBe('danger');
    expect(deletionStatusLabel('DEFERRED')).not.toBe(deletionStatusLabel('REJECTED'));
  });

  it('推迟说明写清「推到哪天」和「为什么」', () => {
    const deferred = deletion({
      status: 'DEFERRED',
      deferUntil: '2036-01-01T00:00:00Z',
      deferReason: '财务（法定）留存期 3650 天，到 2036-01-01 才走完。',
    });
    const text = deferralText(deferred);
    expect(text).toContain('2036-01-01');
    expect(text).toContain('3650');
    // 不是「推迟」状态的请求没有推迟说明。
    expect(deferralText(deletion({ status: 'APPROVED' }))).toBe('');
  });

  it('能不能审批 / 执行，按状态判定', () => {
    expect(canApproveDeletion(deletion({ status: 'PENDING' }))).toBe(true);
    expect(canApproveDeletion(deletion({ status: 'DEFERRED' }))).toBe(false);
    expect(canExecuteDeletion(deletion({ status: 'APPROVED' }))).toBe(true);
    expect(canExecuteDeletion(deletion({ status: 'DEFERRED' }))).toBe(false);
    expect(canExecuteDeletion(deletion({ status: 'PENDING' }))).toBe(false);
  });

  it('已完成的清理工单不许再执行', () => {
    expect(canExecuteCleanup(cleanupOrder({ status: 'PENDING' }))).toBe(true);
    expect(canExecuteCleanup(cleanupOrder({ status: 'FAILED' }))).toBe(true);
    expect(canExecuteCleanup(cleanupOrder({ status: 'COMPLETED' }))).toBe(false);
    expect(canExecuteCleanup(cleanupOrder({ status: 'CANCELLED' }))).toBe(false);
  });

  it('天数没登记时写「未登记」，不写 0（0 会被读成「不留存」）', () => {
    expect(daysText(null)).toBe('未登记');
    expect(daysText(undefined)).toBe('未登记');
    expect(daysText(3650)).toBe('3650');
    expect(daysText(180)).toBe('180');
  });

  it('日期缺失时写「—」', () => {
    expect(dateText(null)).toBe('—');
    expect(dateText('2026-10-23T02:00:00Z')).toBe('2026-10-23');
  });

  it('留存期类别、传输状态、清理状态各有各的标签', () => {
    expect(retentionClassLabel('FINANCIAL')).toBe('法定（财务）留存期');
    expect(retentionClassLabel('STANDARD')).toBe('普通留存期');
    expect(transferStatusLabel('SUSPENDED')).toBe('已暂停');
    expect(transferStatusTone('ENDED')).toBe('danger');
    expect(cleanupStatusLabel('PARTIAL')).toBe('部分完成');
    expect(cleanupStatusTone('COMPLETED')).toBe('success');
  });

  it('成员标识用短码显示（拿不到显示名时也不编造）', () => {
    expect(actorLabel('11111111-2222-3333-4444-555555555555')).toBe('成员 11111111');
    expect(actorLabel('')).toBe('未知成员');
    expect(actorLabel(null)).toBe('未知成员');
  });

  it('未交付的上游说「读不到」并写明归属模块，不说「没有数据」', () => {
    const upstream: DataGovernanceUpstreamState = {
      sourceCode: 'FINANCE_RETENTION_DETAIL',
      sourceLabel: '12 财务 · 财务留存期明细与凭证（finance）',
      available: false,
      ownerModule: '12 财务（finance 服务）',
      note: 'finance 服务尚未落地。',
    };
    const state = unavailableState(upstream);
    expect(state.title).toContain('尚未交付');
    expect(state.description).toContain('12 财务');
    expect(state.description).toContain('不是「没有数据」');
  });

  it('读不到的上游被单独挑出来', () => {
    const upstreams: DataGovernanceUpstreamState[] = [
      { sourceCode: 'A', sourceLabel: 'A', available: true, ownerModule: '', note: '' },
      { sourceCode: 'B', sourceLabel: 'B', available: false, ownerModule: 'B 模块', note: '' },
    ];
    const missing = unavailableUpstreams({
      upstreams,
      transferSemantics: '',
      retentionSemantics: '',
      conflictSemantics: '',
      deletionSemantics: '',
      cleanupSemantics: '',
      auditSemantics: '',
      boundaryNote: '',
      downstreamNote: '',
    });
    expect(missing).toHaveLength(1);
    expect(missing[0]?.sourceCode).toBe('B');
    expect(unavailableUpstreams(null)).toEqual([]);
  });

  it('八种空态文案各不相同', () => {
    const kinds = [
      'NO_DOMAIN',
      'NO_TRANSFER',
      'NO_POLICY',
      'NO_CONFLICT',
      'NO_DELETION',
      'NO_CLEANUP',
      'NO_RECORD',
      'FILTER_EMPTY',
    ] as const;
    const titles = kinds.map((k) => emptyState(k).title);
    expect(new Set(titles).size).toBe(kinds.length);
    expect(emptyState('NO_POLICY').description).toContain('判不了');
    expect(emptyState('NO_CONFLICT').description).toContain('两回事');
    expect(emptyState('FILTER_EMPTY').description).toContain('放宽筛选');
  });
});

/**
 * 🔴 **api-proxy 白名单回归** —— 白名单**只在浏览器端生效**
 * （`pnpm test` 直连服务层，永远测不出代理 404）。
 * 漏登记一条 = 整个页面在浏览器里 404，而 typecheck / lint / vitest / Playwright **全部全绿**。
 *
 * <p>所以这里把「模型里**真实写的**调用路径」与「api-proxy 里的正则」放在一起对撞：
 * 路径不是手抄一份清单，而是**从模型源码里反向抽取** —— 路径改了这里就会红。
 */
describe('16.6.2.1 api-proxy 白名单', () => {
  const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

  /** 从 api-proxy.ts 里抽出本模块那条 elements 的白名单正则。 */
  function allowPattern(): RegExp {
    const source = readFileSync(join(__dirname, '..', '..', 'lib', 'api-proxy.ts'), 'utf8');
    const starts = [...source.matchAll(/^\s*prefix: '([^']+)'/gm)].map((m) => ({
      prefix: m[1] ?? '',
      at: m.index ?? 0,
    }));
    for (const [i, element] of starts.entries()) {
      if (element.prefix !== 'channel/') continue;
      const end = starts[i + 1]?.at ?? source.lastIndexOf('];');
      const block = source.slice(element.at, end);
      if (!block.includes('data-governance')) continue;
      const start = block.indexOf('/^');
      if (start < 0) throw new Error('未能定位本模块的白名单正则起点');
      let literal = '';
      for (let j = start + 1; j < block.length; j++) {
        const ch = block[j];
        if (ch === '/' && block[j - 1] !== '\\') break;
        literal += ch;
      }
      return new RegExp(literal);
    }
    throw new Error('api-proxy.ts 里找不到本模块的 services[] 元素');
  }

  /** 从模型源码里反向抽取**真实**的调用路径。 */
  function realCallPaths(): string[] {
    const source = readFileSync(join(__dirname, 'data-retention-model.ts'), 'utf8');
    const paths = new Set<string>();
    // 匹配 `${BASE}/xxx` 与 `${BASE}/xxx/${id}` 两种模板串。
    for (const m of source.matchAll(/\$\{BASE\}([^`'\n]*)/g)) {
      const raw = (m[1] ?? '')
        .replace(/\$\{[^}]+\}/g, UUID)
        .split('?')[0]!
        .trim();
      if (!raw) continue;
      paths.add(`data-governance${raw}`);
    }
    return [...paths];
  }

  it('模型里真实写的每条调用路径都在白名单里（漏登记 = 浏览器里 404）', () => {
    const pattern = allowPattern();
    const paths = realCallPaths();
    expect(paths.length).toBeGreaterThan(10);
    for (const path of paths) {
      expect(pattern.test(path), `路径 ${path} 没有登记进 api-proxy 白名单`).toBe(true);
    }
  });

  it('白名单不放行不存在的形状', () => {
    const pattern = allowPattern();
    const blocked = [
      // 删除请求没有「直接删除」端点 —— 物理删除跨服务做不了。
      `data-governance/deletion-requests/${UUID}/delete`,
      // 留存策略不可原地改写（改策略 = 登记新版本）。
      `data-governance/retention-policies/${UUID}`,
      // 清理执行记录是 append-only 凭据。
      `data-governance/cleanup-records/${UUID}`,
      `data-governance/cleanup-records/${UUID}/delete`,
      // 非 UUID 形状一律 404。
      'data-governance/transfers/not-a-uuid',
      'data-governance/transfers/batch/suspend',
      `data-governance/cleanup-orders/${UUID}/cancel`,
    ];
    for (const path of blocked) {
      expect(pattern.test(path), `路径 ${path} 不该被放行`).toBe(false);
    }
  });
});
