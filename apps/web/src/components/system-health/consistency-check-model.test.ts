import { describe, expect, it } from 'vitest';
import {
  CONSISTENCY_CHECK_KEYS,
  CONSISTENCY_RULES,
  CONSISTENCY_SOURCE_SPECS,
  GREEN,
  MAPPING_GAP_STALE_DAYS,
  RED,
  UNDETERMINED,
  YELLOW,
  ageDays,
  buildAccuracyCheck,
  buildAmountParityCheck,
  buildAnomalyList,
  buildConclusion,
  buildDiffRateCheck,
  buildMappingGapCheck,
  buildReconciliationCheck,
  canRead,
  formatPercent,
  formatQuantity,
  latestRun,
  scopeModeOf,
  sourceAvailabilities,
  unavailable,
  type Access,
  type InventoryHealthGate,
  type MappingGapSummary,
  type ReconciliationRun,
  type ReconciliationRunPage,
} from './consistency-check-model';

/**
 * 1.5.1.2 数据一致性体检的模型单测。
 *
 * <p>这些用例守的是本模块**最容易做错**的几件事：
 *
 * <ol>
 *   <li>「判不了」永远不是绿、也不是 0；</li>
 *   <li>只要有一项判不了，<b>整体</b>就不能报「通过」；</li>
 *   <li>上游未交付 / 不可比的两项如实报出来，不拿 0 顶替；</li>
 *   <li>金额差异**不产出任何数字**（履约侧没有金额事实、两侧币种没有汇率）；</li>
 *   <li>闸门判定原样透传（不重算、不改写理由）。</li>
 * </ol>
 */

const NOW = new Date('2026-10-24T08:00:00.000Z');

// ================================================================ 夹具

function run(overrides: Partial<ReconciliationRun> = {}): ReconciliationRun {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    scopeType: 'ALL',
    scopeKey: null,
    scopeLabel: '全部仓库',
    windowFrom: '2026-10-20T00:00:00Z',
    windowTo: '2026-10-23T23:59:59Z',
    status: 'CONSISTENT',
    checkedBalanceCount: 120,
    checkedEntryCount: 3400,
    checkedTransactionCount: 210,
    openingOnHand: 1000,
    periodOnHand: -120,
    afterWindowOnHand: 880,
    expectedOnHand: 880,
    snapshotOnHand: 880,
    onHandDiff: 0,
    openingReserved: 40,
    periodReserved: -5,
    afterWindowReserved: 35,
    expectedReserved: 35,
    snapshotReserved: 35,
    reservedDiff: 0,
    discrepancyCount: 0,
    suspectCount: 0,
    firstSuspectAt: null,
    lastSuspectAt: null,
    verdictNote: '核对了 120 条余额行、3400 笔分录，账实一致。',
    triggeredBy: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-10-23T23:59:59Z',
    ...overrides,
  };
}

function runPage(runs: ReconciliationRun[]): ReconciliationRunPage {
  return { runs, total: runs.length, page: 0, size: 1 };
}

function gate(overrides: Partial<InventoryHealthGate> = {}): InventoryHealthGate {
  return {
    scopeType: 'TENANT',
    scopeKey: null,
    scopeLabel: '全部仓库',
    accuracyState: 'AVAILABLE',
    accuracyRate: 0.995,
    accuracyThreshold: 0.99,
    blocked: false,
    blockReason: '',
    basis: '核对口径（最近一次账实核对批次）',
    lastReconciledAt: '2026-10-23T23:59:59Z',
    lastStocktakenAt: '2026-10-01T00:00:00Z',
    evaluatedAt: '2026-10-24T07:00:00Z',
    issues: [],
    ...overrides,
  };
}

function mapping(overrides: Partial<MappingGapSummary> = {}): MappingGapSummary {
  return {
    byStatus: [{ key: 'PENDING', total: 3 }],
    byReason: [{ key: 'NEW_PLATFORM_VARIANT', total: 3 }],
    byShop: [{ shopCode: 'SHOP-1', shopName: '美国一店', platformCode: 'AMAZON', total: 3 }],
    pendingTotal: 3,
    oldestPendingAt: '2026-10-23T00:00:00Z',
    priceSnapshotCount: 0,
    orderSourceWired: true,
    ...overrides,
  };
}

function access(permissions: string[], departmentId: string | null = null): Access {
  return {
    userId: 'user-1',
    tenantId: 'tenant-1',
    tenantName: '测试租户',
    membershipId: 'member-1',
    departmentId,
    permissions,
    grants: [
      {
        roleId: 'role-1',
        roleCode: 'OPS',
        scopeMode: 'ALL',
        permissions,
      },
    ],
  };
}

// ================================================================ 分项：账实核对

describe('分项 · 库存账实核对', () => {
  it('一笔核对都没有 ⇒ 判不了，不是「账实一致」', () => {
    const check = buildReconciliationCheck({
      availability: 'AVAILABLE',
      value: runPage([]),
      failure: null,
    });
    expect(check.verdict).toBe(UNDETERMINED);
    expect(check.availability).toBe('NO_DATA');
    expect(check.reason).toContain('还没有跑过一次账实核对');
  });

  it('CONSISTENT ⇒ 绿', () => {
    const check = buildReconciliationCheck({
      availability: 'AVAILABLE',
      value: runPage([run({ status: 'CONSISTENT' })]),
      failure: null,
    });
    expect(check.verdict).toBe(GREEN);
    expect(check.reason).toContain('账实一致');
  });

  it('DISCREPANT ⇒ 红，并把差异条数与差额写进证据', () => {
    const check = buildReconciliationCheck({
      availability: 'AVAILABLE',
      value: runPage([
        run({
          status: 'DISCREPANT',
          discrepancyCount: 7,
          suspectCount: 3,
          onHandDiff: -12.5,
          reservedDiff: 2,
          firstSuspectAt: '2026-10-22T00:00:00Z',
          lastSuspectAt: '2026-10-23T00:00:00Z',
        }),
      ]),
      failure: null,
    });
    expect(check.verdict).toBe(RED);
    expect(check.reason).toContain('存在差异');
    expect(check.reason).toContain('7 条余额行');
    expect(check.evidence.join('\n')).toContain('差额 -12.5');
    expect(check.evidence.join('\n')).toContain('可疑区间');
  });

  it('无权限 ⇒ 判不了，且措辞不是「账实一致」', () => {
    const check = buildReconciliationCheck(
      unavailable<ReconciliationRunPage>('NOT_PERMITTED', '无权限 · 对你不开放'),
    );
    expect(check.verdict).toBe(UNDETERMINED);
    expect(check.availability).toBe('NOT_PERMITTED');
    expect(check.reason).toContain('没有去读');
  });

  it('服务读不到 ⇒ 判不了，不是「账实一致」', () => {
    const check = buildReconciliationCheck(
      unavailable<ReconciliationRunPage>('UNREACHABLE', '连接被拒'),
    );
    expect(check.verdict).toBe(UNDETERMINED);
    expect(check.availability).toBe('UNREACHABLE');
    expect(check.reason).toContain('连接被拒');
  });

  it('取最近一次批次按落库时刻倒序，不信列表顺序', () => {
    const older = run({ id: 'older', createdAt: '2026-10-20T00:00:00Z' });
    const newer = run({ id: 'newer', createdAt: '2026-10-23T00:00:00Z' });
    expect(latestRun([older, newer])?.id).toBe('newer');
    expect(latestRun([newer, older])?.id).toBe('newer');
    expect(latestRun([])).toBeNull();
    expect(latestRun(null)).toBeNull();
  });
});

// ================================================================ 分项：库存准确率

describe('分项 · 库存准确率', () => {
  it('低于阈值 ⇒ 红，且闸门理由原样透传', () => {
    const check = buildAccuracyCheck({
      availability: 'AVAILABLE',
      value: gate({
        accuracyRate: 0.95,
        blocked: true,
        blockReason: '库存准确率 0.95 低于阈值 0.99，账目不可信，阻断结算与对账。',
      }),
      failure: null,
    });
    expect(check.verdict).toBe(RED);
    expect(check.reason).toContain('阻断结算与对账出结论');
    expect(check.reason).toContain('0.95 低于阈值 0.99');
  });

  it('达标 ⇒ 绿', () => {
    const check = buildAccuracyCheck({
      availability: 'AVAILABLE',
      value: gate({ accuracyRate: 0.995, blocked: false }),
      failure: null,
    });
    expect(check.verdict).toBe(GREEN);
    expect(check.reason).toContain('未触发阻断');
  });

  it('没有评估结论（NO_DATA）⇒ 判不了，不是「准确率 0」', () => {
    const check = buildAccuracyCheck({
      availability: 'AVAILABLE',
      value: gate({
        accuracyState: 'NO_DATA',
        accuracyRate: null,
        blocked: true,
        blockReason: '尚无库存健康评估结论，无法证明账目准确率。',
      }),
      failure: null,
    });
    expect(check.verdict).toBe(UNDETERMINED);
    expect(check.availability).toBe('NO_DATA');
    expect(check.reason).toContain('没有证据不等于准');
    expect(check.reason).not.toContain('准确率 0');
  });

  it('上游未交付（UNAVAILABLE）⇒ 判不了，且可得性是「上游未交付」', () => {
    const check = buildAccuracyCheck({
      availability: 'AVAILABLE',
      value: gate({ accuracyState: 'UNAVAILABLE', accuracyRate: null, blocked: true }),
      failure: null,
    });
    expect(check.verdict).toBe(UNDETERMINED);
    expect(check.availability).toBe('NOT_DELIVERED');
    expect(check.reason).toContain('上游未交付');
  });

  it('说「未触发阻断」但拿不到数值 ⇒ 判不了', () => {
    const check = buildAccuracyCheck({
      availability: 'AVAILABLE',
      value: gate({ accuracyRate: null, blocked: false }),
      failure: null,
    });
    expect(check.verdict).toBe(UNDETERMINED);
  });

  it('无权限 / 读不到 ⇒ 判不了', () => {
    expect(buildAccuracyCheck(unavailable<InventoryHealthGate>('NOT_PERMITTED')).verdict).toBe(
      UNDETERMINED,
    );
    expect(buildAccuracyCheck(unavailable<InventoryHealthGate>('UNREACHABLE')).verdict).toBe(
      UNDETERMINED,
    );
  });
});

// ================================================================ 分项：上游不可读的两项

describe('分项 · 上游不可读的两项', () => {
  it('12.3.2 对账差异未交付 ⇒ 判不了，绝不是 0 差异、也绝不是健康', () => {
    const check = buildDiffRateCheck();
    expect(check.verdict).toBe(UNDETERMINED);
    expect(check.availability).toBe('NOT_DELIVERED');
    expect(check.reason).toContain('未交付');
    expect(check.reason).not.toContain('0 差异');
    expect(check.missing).toContain('12.3.2.1');
    expect(check.action).toBeNull();
  });

  it('三方金额差异 ⇒ 不可比，不产出任何金额差异数字', () => {
    const check = buildAmountParityCheck();
    expect(check.verdict).toBe(UNDETERMINED);
    expect(check.availability).toBe('NOT_COMPARABLE');
    expect(check.evidence.join('\n')).toContain('无金额列');
    expect(check.reason).toContain('绝不能直接相减');
    expect(check.action).toBeNull();
  });

  it('不可比的两项不请求任何端点（端点与权限码都是空的）', () => {
    expect(CONSISTENCY_SOURCE_SPECS.DIFF_RATE.endpoint).toBeNull();
    expect(CONSISTENCY_SOURCE_SPECS.DIFF_RATE.permission).toBeNull();
    expect(CONSISTENCY_SOURCE_SPECS.AMOUNT_PARITY.endpoint).toBeNull();
    expect(CONSISTENCY_SOURCE_SPECS.AMOUNT_PARITY.permission).toBeNull();
  });
});

// ================================================================ 分项：映射缺失

describe('分项 · 待处理映射缺失', () => {
  it('零缺口 ⇒ 绿', () => {
    const check = buildMappingGapCheck(
      {
        availability: 'AVAILABLE',
        value: mapping({ pendingTotal: 0, oldestPendingAt: '' }),
        failure: null,
      },
      NOW,
    );
    expect(check.verdict).toBe(GREEN);
    expect(check.reason).toContain('没有待处理的映射缺口');
  });

  it('有缺口但不够陈旧 ⇒ 黄', () => {
    const check = buildMappingGapCheck(
      {
        availability: 'AVAILABLE',
        value: mapping({ pendingTotal: 3, oldestPendingAt: '2026-10-23T00:00:00Z' }),
        failure: null,
      },
      NOW,
    );
    expect(check.verdict).toBe(YELLOW);
    expect(check.reason).toContain('3 条待处理映射缺口');
  });

  it('缺口压过阈值 ⇒ 红', () => {
    const check = buildMappingGapCheck(
      {
        availability: 'AVAILABLE',
        value: mapping({
          pendingTotal: 3,
          oldestPendingAt: '2026-10-10T00:00:00Z',
        }),
        failure: null,
      },
      NOW,
    );
    expect(check.verdict).toBe(RED);
    expect(check.reason).toContain(`${MAPPING_GAP_STALE_DAYS} 天`);
  });

  it('09 订单事件未接线 ⇒ 证据里写明待处理数是下限', () => {
    const check = buildMappingGapCheck(
      {
        availability: 'AVAILABLE',
        value: mapping({ orderSourceWired: false }),
        failure: null,
      },
      NOW,
    );
    expect(check.evidence.join('\n')).toContain('未接线');
    expect(check.evidence.join('\n')).toContain('下限');
  });

  it('无权限 / 读不到 ⇒ 判不了', () => {
    expect(buildMappingGapCheck(unavailable<MappingGapSummary>('NOT_PERMITTED'), NOW).verdict).toBe(
      UNDETERMINED,
    );
    expect(buildMappingGapCheck(unavailable<MappingGapSummary>('UNREACHABLE'), NOW).verdict).toBe(
      UNDETERMINED,
    );
  });
});

// ================================================================ 整体结论

describe('整体结论', () => {
  /** 三个**可读**分项全绿（不含两个上游不可读的分项）。 */
  const readableGreen = [
    buildReconciliationCheck({ availability: 'AVAILABLE', value: runPage([run()]), failure: null }),
    buildAccuracyCheck({ availability: 'AVAILABLE', value: gate(), failure: null }),
    buildMappingGapCheck(
      { availability: 'AVAILABLE', value: mapping({ pendingTotal: 0 }), failure: null },
      NOW,
    ),
  ];

  it('全部可读分项都绿 ⇒ 整体通过', () => {
    const conclusion = buildConclusion(readableGreen, gate());
    expect(conclusion.verdict).toBe(GREEN);
    expect(conclusion.counts.green).toBe(3);
    expect(conclusion.counts.undetermined).toBe(0);
  });

  it('🔴 只要有一项判不了，整体就不能报「通过」', () => {
    // 三个可读分项都绿 + 一个上游未交付 —— 这**不是**「全绿」。
    const conclusion = buildConclusion([...readableGreen, buildDiffRateCheck()], gate());
    expect(conclusion.counts.undetermined).toBe(1);
    expect(conclusion.verdict).toBe(UNDETERMINED);
    expect(conclusion.title).toContain('判不出整体结论');
    expect(conclusion.detail).toContain('判不了**不是**「没问题」');
  });

  it('🔴 本仓今天整体结论**不可能是绿** —— 五项里两项上游不可读', () => {
    const conclusion = buildConclusion(
      [...readableGreen, buildDiffRateCheck(), buildAmountParityCheck()],
      gate(),
    );
    expect(conclusion.counts.undetermined).toBe(2);
    expect(conclusion.verdict).toBe(UNDETERMINED);
  });

  it('一项黄 ⇒ 整体黄', () => {
    const checks = [
      readableGreen[0]!,
      readableGreen[1]!,
      buildMappingGapCheck(
        { availability: 'AVAILABLE', value: mapping({ pendingTotal: 2 }), failure: null },
        NOW,
      ),
    ];
    const conclusion = buildConclusion(checks, gate());
    expect(conclusion.verdict).toBe(YELLOW);
    expect(conclusion.counts.yellow).toBe(1);
  });

  it('一项红 ⇒ 整体红', () => {
    const checks = [
      buildReconciliationCheck({
        availability: 'AVAILABLE',
        value: runPage([run({ status: 'DISCREPANT' })]),
        failure: null,
      }),
      readableGreen[1]!,
      readableGreen[2]!,
    ];
    const conclusion = buildConclusion(checks, gate());
    expect(conclusion.verdict).toBe(RED);
    expect(conclusion.counts.red).toBe(1);
  });

  it('闸门已阻断 ⇒ 整体红，且理由原样引用归属服务', () => {
    const conclusion = buildConclusion(
      readableGreen,
      gate({
        blocked: true,
        accuracyRate: 0.9,
        blockReason: '库存准确率 0.9 低于阈值 0.99，账目不可信，阻断结算与对账。',
      }),
    );
    expect(conclusion.verdict).toBe(RED);
    expect(conclusion.blocked).toBe(true);
    expect(conclusion.blockReason).toBe(
      '库存准确率 0.9 低于阈值 0.99，账目不可信，阻断结算与对账。',
    );
    expect(conclusion.title).toContain('低于阈值');
  });

  it('闸门因「没有证据」而阻断 ⇒ 整体红，但标题说的是没有证据，不是账坏了', () => {
    const conclusion = buildConclusion(
      readableGreen,
      gate({
        accuracyState: 'NO_DATA',
        accuracyRate: null,
        blocked: true,
        blockReason: '尚无库存健康评估结论，无法证明账目准确率。',
      }),
    );
    expect(conclusion.verdict).toBe(RED);
    expect(conclusion.title).toContain('没有证据');
    expect(conclusion.title).not.toContain('低于阈值');
  });
});

// ================================================================ 异常清单

describe('异常清单', () => {
  it('只收非绿项，红排在最前', () => {
    const checks = [
      buildReconciliationCheck({
        availability: 'AVAILABLE',
        value: runPage([run({ status: 'DISCREPANT' })]),
        failure: null,
      }),
      buildAccuracyCheck({ availability: 'AVAILABLE', value: gate(), failure: null }),
      buildDiffRateCheck(),
    ];
    const anomalies = buildAnomalyList(checks);
    expect(anomalies.length).toBe(2);
    expect(anomalies[0]?.verdict).toBe(RED);
    expect(anomalies[1]?.verdict).toBe(UNDETERMINED);
  });
});

// ================================================================ 数据范围

describe('数据范围（服务端判定，前端只用来决定要不要去读）', () => {
  it('动作权限必须与页面 .read 权限同在一条授权里（与后端 GrantPermissions.permits 同式）', () => {
    // 「能写不能读」的组合不能被静默放行：写权限要连带页面 .read 权限。
    expect(
      canRead(access(['warehouse.inventory.write']).grants, 'warehouse.inventory.write', false),
    ).toBe(false);
    expect(
      canRead(
        access(['warehouse.inventory.write', 'warehouse.inventory.read']).grants,
        'warehouse.inventory.write',
        false,
      ),
    ).toBe(true);
  });

  it('没有授权 ⇒ 无读权限', () => {
    expect(canRead(null, 'warehouse.inventory.read', false)).toBe(false);
    expect(canRead([], 'warehouse.inventory.read', false)).toBe(false);
  });

  it('取最宽的一条授权', () => {
    const grants = [
      {
        roleId: 'r1',
        roleCode: 'A',
        scopeMode: 'SELF',
        permissions: ['warehouse.inventory.read', 'warehouse.inventory'],
      },
      {
        roleId: 'r2',
        roleCode: 'B',
        scopeMode: 'ALL',
        permissions: ['warehouse.inventory.read', 'warehouse.inventory'],
      },
    ];
    expect(scopeModeOf(grants, 'warehouse.inventory.read', false)).toBe('ALL');
  });

  it('DEPARTMENT 但会话没有部门 ⇒ 往更严的一侧退化成 SELF', () => {
    const grants = [
      {
        roleId: 'r1',
        roleCode: 'A',
        scopeMode: 'DEPARTMENT',
        permissions: ['warehouse.inventory.read', 'warehouse.inventory'],
      },
    ];
    expect(scopeModeOf(grants, 'warehouse.inventory.read', false)).toBe('SELF');
    expect(scopeModeOf(grants, 'warehouse.inventory.read', true)).toBe('DEPARTMENT');
  });

  it('上游不可读的两项没有权限码 ⇒ 不去读', () => {
    const avail = sourceAvailabilities(
      access(['warehouse.inventory.read', 'warehouse.inventory', 'channel.operation.read']),
    );
    expect(avail.DIFF_RATE.permitted).toBe(false);
    expect(avail.AMOUNT_PARITY.permitted).toBe(false);
    expect(avail.RECONCILIATION.permitted).toBe(true);
    expect(avail.ACCURACY.permitted).toBe(true);
    expect(avail.MAPPING_GAPS.permitted).toBe(true);
  });
});

// ================================================================ 小工具

describe('小工具', () => {
  it('ageDays 解析不了时返回 null，不是 0', () => {
    expect(ageDays(null, NOW)).toBeNull();
    expect(ageDays('不是时间', NOW)).toBeNull();
    expect(ageDays('2026-10-20T00:00:00Z', NOW)).toBe(4);
  });

  it('格式化时 null 显示「—」，不是 0', () => {
    expect(formatPercent(null)).toBe('—');
    expect(formatQuantity(null)).toBe('—');
    expect(formatPercent(0.99)).toBe('99.00%');
    expect(formatQuantity(12.5)).toBe('12.5');
  });
});

// ================================================================ 规则表覆盖

describe('规则表', () => {
  it('五个分项在规则表里都有对应规则', () => {
    const codes = CONSISTENCY_RULES.map((rule) => rule.code);
    expect(codes.some((code) => code.startsWith('RECONCILIATION.'))).toBe(true);
    expect(codes.some((code) => code.startsWith('ACCURACY.'))).toBe(true);
    expect(codes.some((code) => code.startsWith('MAPPING_GAP.'))).toBe(true);
    expect(codes.some((code) => code.startsWith('DIFF_RATE.'))).toBe(true);
    expect(codes.some((code) => code.startsWith('AMOUNT_PARITY.'))).toBe(true);
  });

  it('每个分项都有归属、权限码或明确说明为什么读不了', () => {
    for (const key of CONSISTENCY_CHECK_KEYS) {
      const spec = CONSISTENCY_SOURCE_SPECS[key];
      expect(spec.owner.length).toBeGreaterThan(0);
      expect(spec.ownerModule.length).toBeGreaterThan(0);
      if (spec.state === 'READABLE') {
        expect(spec.endpoint).not.toBeNull();
        expect(spec.permission).not.toBeNull();
      } else {
        expect(spec.missing).not.toBeNull();
      }
    }
  });
});
