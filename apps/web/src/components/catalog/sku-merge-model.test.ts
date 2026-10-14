import { describe, expect, it } from 'vitest';
import type {
  SkuMergePreview,
  SkuMergeReferenceRow,
  SkuMergeReport,
  SkuMergeTrace,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionableReferences,
  blockReason,
  canExecuteMerge,
  dimensionHint,
  dimensionLabel,
  DIMENSION_HINTS,
  referenceOutcome,
  remainingSpuNote,
  reportNote,
  reportSummary,
  timeText,
  traceSummary,
  validateNote,
  versionCheckLabel,
  versionCheckTone,
} from './sku-merge-model';

function ref(over: Partial<SkuMergeReferenceRow> = {}): SkuMergeReferenceRow {
  return {
    dimension: 'QUOTE',
    label: '供应商报价',
    action: 'MOVE',
    actionLabel: '已改指主记录',
    sourceCount: 0,
    migratedCount: 0,
    summedCount: 0,
    deduplicatedCount: 0,
    conflictCount: 0,
    frozenCount: 0,
    blocked: false,
    note: '',
    ...over,
  };
}

function report(
  references: SkuMergeReferenceRow[],
  over: Partial<SkuMergeReport> = {},
): SkuMergeReport {
  const sum = (k: keyof SkuMergeReferenceRow) =>
    references.reduce((n, r) => n + (r[k] as number), 0);
  return {
    references,
    totalMigrated: sum('migratedCount'),
    totalSummed: sum('summedCount'),
    totalDeduplicated: sum('deduplicatedCount'),
    totalConflict: sum('conflictCount'),
    totalFrozen: sum('frozenCount'),
    blockedDimensions: references.filter((r) => r.blocked).length,
    note: '历史事实一律不搬',
    ...over,
  };
}

function preview(over: Partial<SkuMergePreview> = {}): SkuMergePreview {
  return {
    candidateId: 'c1',
    decisionId: 'd1',
    decision: 'MERGE',
    versionCheck: 'MATCHED',
    versionCheckLabel: '决定仍然成立',
    executable: true,
    blockReason: '',
    scope: 'SKU',
    scopeLabel: '同 SPU 合并（只并掉一条 SKU）',
    primarySkuId: 'p1',
    primaryCode: 'A-1',
    primaryName: '主',
    primarySpuId: 'sp1',
    primarySpuCode: 'SPU-1',
    primaryStatus: 'ACTIVE',
    primaryVersion: 0,
    secondarySkuId: 's1',
    secondaryCode: 'A-2',
    secondaryName: '副',
    secondarySpuId: 'sp1',
    secondarySpuCode: 'SPU-1',
    secondaryStatus: 'ACTIVE',
    secondaryVersion: 0,
    expectedPrimaryVersion: 0,
    expectedSecondaryVersion: 0,
    secondarySpuRemainingSkuCount: 0,
    report: report([ref({ dimension: 'BARCODE', migratedCount: 1, sourceCount: 1 })]),
    ...over,
  };
}

describe('执行门禁只读后端预演结论', () => {
  it('executable 为真才允许执行', () => {
    expect(canExecuteMerge(preview())).toBe(true);
    expect(canExecuteMerge(preview({ executable: false }))).toBe(false);
    expect(canExecuteMerge(null)).toBe(false);
  });

  it('决定过期/已执行/已合并/保留两条四种情况都要禁掉执行并给出理由', () => {
    for (const check of ['STALE', 'ALREADY_EXECUTED', 'ALREADY_MERGED', 'NOT_DECIDED', 'KEEP']) {
      const p = preview({ versionCheck: check, executable: false, blockReason: `原因-${check}` });
      expect(canExecuteMerge(p), check).toBe(false);
      expect(blockReason(p)).toBe(`原因-${check}`);
    }
  });

  it('blockReason 在可执行时返回 null（页面据此不渲染警告块）', () => {
    expect(blockReason(preview())).toBeNull();
    expect(blockReason(null)).toBeNull();
  });

  it('blockReason 缺省时退回 versionCheckLabel，不返回空串', () => {
    expect(blockReason(preview({ executable: false, blockReason: '' }))).toBe('决定仍然成立');
  });

  it('六种版本比对结论的标签与语气都不相同', () => {
    const labels = [
      'MATCHED',
      'STALE',
      'ALREADY_EXECUTED',
      'NOT_DECIDED',
      'ALREADY_MERGED',
      'KEEP',
    ];
    expect(new Set(labels.map(versionCheckLabel)).size).toBe(labels.length);
    expect(versionCheckTone('MATCHED')).toBe('ok');
    for (const c of labels.filter((x) => x !== 'MATCHED')) {
      expect(versionCheckTone(c), c).toBe('stop');
    }
    // 未知词表原样返回，不吞错
    expect(versionCheckLabel('WAT')).toBe('WAT');
  });
});

describe('引用迁移报告分项不折叠', () => {
  it('五种计数分开报，运营要判断的是「哪几类在挡路」', () => {
    const text = reportSummary(
      report([
        ref({ dimension: 'QUOTE', sourceCount: 3, migratedCount: 3 }),
        ref({ dimension: 'PACKAGE', sourceCount: 1, conflictCount: 1 }),
        ref({ dimension: 'MEDIA', sourceCount: 2, deduplicatedCount: 2 }),
        ref({ dimension: 'BOM', sourceCount: 2, summedCount: 1, migratedCount: 1 }),
        ref({ dimension: 'UNIT_CONVERSION_SNAPSHOT', sourceCount: 5, frozenCount: 5 }),
      ]),
    );
    expect(text).toContain('改指主记录 4 行');
    expect(text).toContain('用量相加并入 1 行');
    expect(text).toContain('去重删除 2 行');
    expect(text).toContain('搬不动、留在原处 1 行');
    expect(text).toContain('历史冻结 5 行');
  });

  it('合计为 0 时给「不需要搬动任何引用」而不是一排 0', () => {
    expect(reportSummary(report([ref()]))).toBe('不需要搬动任何引用（被合并记录上没有当前引用）');
    expect(reportSummary(null)).toBe('—');
  });

  it('actionableReferences 只列有东西要处理的维度（搬不动/被拒/去重/相加）', () => {
    const rows = [
      ref({ dimension: 'BARCODE', sourceCount: 1, migratedCount: 1 }),
      ref({ dimension: 'PACKAGE', sourceCount: 1, conflictCount: 1 }),
      ref({ dimension: 'ATTRIBUTE', sourceCount: 1, conflictCount: 1, blocked: true }),
      ref({ dimension: 'MEDIA', sourceCount: 1, deduplicatedCount: 1 }),
      ref({ dimension: 'UNIT_CONVERSION_SNAPSHOT', sourceCount: 3, frozenCount: 3 }),
    ];
    expect(actionableReferences(report(rows)).map((r) => r.dimension)).toEqual([
      'PACKAGE',
      'ATTRIBUTE',
      'MEDIA',
    ]);
    expect(actionableReferences(null)).toEqual([]);
  });

  it('被整维度拒绝的维度与「搬不动」分开表达', () => {
    const blocked = ref({
      dimension: 'ATTRIBUTE',
      sourceCount: 1,
      conflictCount: 1,
      blocked: true,
    });
    const conflict = ref({ dimension: 'PACKAGE', sourceCount: 1, conflictCount: 1 });
    expect(referenceOutcome(blocked)).toContain('整维度不搬');
    expect(referenceOutcome(conflict)).toBe('搬不动 1');
    expect(referenceOutcome(ref())).toBe('没有引用');
  });

  it('一条维度的处置摘要把各动作都报出来', () => {
    const row = ref({
      dimension: 'BOM',
      sourceCount: 4,
      migratedCount: 1,
      summedCount: 1,
      deduplicatedCount: 1,
      conflictCount: 1,
    });
    expect(referenceOutcome(row)).toBe('改指 1 · 相加 1 · 去重 1 · 搬不动 1');
  });

  it('报告说明必须带出「历史冻结是刻意不搬」', () => {
    expect(reportNote(report([]))).toContain('历史事实一律不搬');
    expect(reportNote(null)).toBe('');
  });
});

describe('跨商品合并的边界提示', () => {
  it('源商品上还有别的 SKU 时必须提示它们没被搬', () => {
    expect(remainingSpuNote(preview({ scope: 'SPU', secondarySpuRemainingSkuCount: 3 }))).toContain(
      '3',
    );
    expect(remainingSpuNote(preview({ scope: 'SPU', secondarySpuRemainingSkuCount: 3 }))).toContain(
      '一条都没动',
    );
  });

  it('同商品合并或源商品已空时不提示', () => {
    expect(remainingSpuNote(preview({ scope: 'SKU' }))).toBeNull();
    expect(
      remainingSpuNote(preview({ scope: 'SPU', secondarySpuRemainingSkuCount: 0 })),
    ).toBeNull();
    expect(remainingSpuNote(null)).toBeNull();
  });
});

describe('维度词表与后端一致', () => {
  it('21 个维度都有中文名与处置提示', () => {
    expect(Object.keys(DIMENSION_HINTS)).toHaveLength(21);
    for (const [key, v] of Object.entries(DIMENSION_HINTS)) {
      expect(v.label, key).not.toBe('');
      expect(v.hint, key).not.toBe('');
      expect(dimensionLabel(key)).toBe(v.label);
    }
  });

  it('冻结维度必须写明是历史，不让运营以为漏搬', () => {
    for (const key of [
      'UNIT_CONVERSION_SNAPSHOT',
      'STATUS_EVENT',
      'IMPORT_HISTORY',
      'RENDER_ARTIFACT',
      'BOM_EXPLOSION_HISTORY',
      'CODE_HISTORY',
      'DUPLICATE_HISTORY',
    ]) {
      expect(dimensionHint(key), key).toContain('历史');
    }
  });

  it('未知维度原样返回，不吞错', () => {
    expect(dimensionLabel('WAT')).toBe('WAT');
    expect(dimensionHint('WAT')).toBe('');
  });
});

describe('合并血缘摘要', () => {
  const base: SkuMergeTrace = {
    skuId: 's1',
    skuCode: 'A-2',
    skuName: '副',
    status: 'INACTIVE',
    mergedAway: true,
    mergedIntoSkuId: 'p1',
    mergedIntoCode: 'A-1',
    mergedIntoName: '主',
    mergedAt: '2026-10-07T09:00:00.000Z',
    mergedBy: 'u1',
    absorbed: [],
    chain: [],
    lastReport: report([]),
    note: '',
  };

  it('被并进的一方、主记录、从未参与三种说法互不相同', () => {
    expect(traceSummary(base)).toContain('已被并进主记录 A-1');
    expect(
      traceSummary({
        ...base,
        mergedAway: false,
        absorbed: [base],
      }),
    ).toContain('吸收了 1 条');
    expect(traceSummary({ ...base, mergedAway: false, absorbed: [] })).toBe('从未参与合并');
    expect(traceSummary(null)).toBe('');
  });
});

describe('时间与备注校验', () => {
  it('时间缺失或不可解析时给破折号而不是 Invalid Date', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText('')).toBe('—');
    expect(timeText('not-a-date')).toBe('—');
    expect(timeText('2026-10-07T09:00:00.000Z')).not.toBe('—');
  });

  it('备注超过 1000 字在本地就拦下', () => {
    expect(validateNote('')).toBeNull();
    expect(validateNote('a'.repeat(1000))).toBeNull();
    expect(validateNote('a'.repeat(1001))).toBe('合并备注最多 1000 字');
  });
});
