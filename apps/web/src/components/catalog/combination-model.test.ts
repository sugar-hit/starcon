import { describe, expect, it } from 'vitest';
import type {
  BoundAxis,
  CombinationConflict,
  ExistingCombination,
} from '../../../../../packages/api-contracts/src/index';
import {
  combinationSourceText,
  combinationText,
  conflictLabel,
  conflictPositionText,
  conflictTargetText,
  emptySelectionDraft,
  explicitToInput,
  hasConflictList,
  localConflictReasons,
  matrixDraftInput,
  matrixHint,
  occurredAtProblem,
  selectionRowCount,
  signature,
  syncPicks,
  togglePick,
} from './combination-model';

/** 3.1.3.2 组合唯一性前端纯规则。与后端 CombinationRulesTest 同口径。 */

const axis = (
  specAxisId: string,
  code: string,
  name: string,
  values: string[],
  enabled = true,
): BoundAxis => ({
  specAxisId,
  code,
  name,
  position: 0,
  enabled,
  options: values.map((value, index) => ({ value, position: index, enabled: true })),
});

const twoAxes = () => [
  axis('axis-color', 'CLR', '颜色', ['红', '蓝']),
  axis('axis-size', 'SIZ', '尺码', ['S', 'M', 'L']),
];

describe('signature', () => {
  it('与填写顺序无关：先尺码后颜色与反过来必须是同一个值', () => {
    const forward = signature([
      { axisCode: 'CLR', optionValue: '红' },
      { axisCode: 'SIZ', optionValue: '大' },
    ]);
    const backward = signature([
      { axisCode: 'SIZ', optionValue: '大' },
      { axisCode: 'CLR', optionValue: '红' },
    ]);
    expect(forward).toBe(backward);
    expect(forward).toBe('CLR=红|SIZ=大');
  });

  it('轴编码按字节序排，后端 COLLATE "C" 才能和它对上', () => {
    expect(
      signature([
        { axisCode: 'Z9', optionValue: 'y' },
        { axisCode: 'A_', optionValue: 'z' },
        { axisCode: '-A', optionValue: 'w' },
      ]),
    ).toBe('-A=w|A_=z|Z9=y');
  });

  it('算不出签名时返回 null，不返回空串', () => {
    // 空串会让两个不同的非法组合撞进唯一索引。
    expect(signature([])).toBeNull();
    expect(signature([{ axisCode: '', optionValue: '红' }])).toBeNull();
    expect(signature([{ axisCode: 'CLR', optionValue: '' }])).toBeNull();
  });
});

describe('localConflictReasons', () => {
  it('缺轴、混用别的 SPU 的轴、取值不属于该轴、同一组合内轴重复，各自给一条', () => {
    const axes = twoAxes();
    expect(localConflictReasons([[{ specAxisId: 'axis-color', value: '红' }]], axes)).toEqual([
      '第 1 条组合：缺少规格轴「尺码」的取值',
    ]);
    expect(
      localConflictReasons([[{ specAxisId: 'axis-other', value: '红' }]], axes).some((reason) =>
        reason.includes('不属于该 SPU'),
      ),
    ).toBe(true);
    expect(
      localConflictReasons(
        [
          [
            { specAxisId: 'axis-color', value: 'L' },
            { specAxisId: 'axis-size', value: 'S' },
          ],
        ],
        axes,
      ).some((reason) => reason.includes('没有取值「L」')),
    ).toBe(true);
    expect(
      localConflictReasons(
        [
          [
            { specAxisId: 'axis-color', value: '红' },
            { specAxisId: 'axis-color', value: '蓝' },
            { specAxisId: 'axis-size', value: 'S' },
          ],
        ],
        axes,
      ).some((reason) => reason.includes('同一条规格轴出现了多次')),
    ).toBe(true);
  });

  it('同一次提交里两个组合相同要报重复', () => {
    const axes = twoAxes();
    const row = [
      { specAxisId: 'axis-color', value: '红' },
      { specAxisId: 'axis-size', value: 'S' },
    ];
    expect(localConflictReasons([row, [...row]], axes).some((r) => r.includes('重复'))).toBe(true);
  });

  it('SPU 没有绑定任何轴时直接说清楚，不给一堆逐轴报错', () => {
    expect(localConflictReasons([[]], [])).toEqual(['该 SPU 还没有绑定任何规格轴，无法生成组合']);
  });
});

describe('selectionRowCount', () => {
  it('是各轴勾选数的笛卡尔积，有一条轴没选就是 0 而不是 1', () => {
    const axes = twoAxes();
    const draft = emptySelectionDraft(axes);
    expect(selectionRowCount(axes, draft.picks)).toBe(0);
    const withColor = togglePick(togglePick(draft, 'axis-color', '红'), 'axis-size', 'S');
    expect(selectionRowCount(axes, withColor.picks)).toBe(1);
    const twoSizes = togglePick(withColor, 'axis-size', 'M');
    expect(selectionRowCount(axes, twoSizes.picks)).toBe(2);
    expect(selectionRowCount([], {})).toBe(0);
  });

  it('勾选可以取消，且新增的轴会补一个空选择', () => {
    const axes = twoAxes();
    let draft = emptySelectionDraft(axes);
    draft = togglePick(draft, 'axis-color', '红');
    expect(draft.picks['axis-color']).toEqual(['红']);
    draft = togglePick(draft, 'axis-color', '红');
    expect(draft.picks['axis-color']).toEqual([]);
    const extra = [...axes, axis('axis-cap', 'CAP', '容量', ['500ml'])];
    const synced = syncPicks(draft.picks, extra);
    expect(Object.keys(synced).sort()).toEqual(['axis-cap', 'axis-color', 'axis-size']);
    expect(synced['axis-cap']).toEqual([]);
  });
});

describe('matrixHint', () => {
  const base = {
    spuId: 'spu',
    spuCode: 'SPU-1',
    spuName: 'T恤',
    matrixRowCount: 0,
    reservedCombinationCount: 0,
    existingSkuCount: 0,
    conflictMatrixCount: 0,
    availableMatrixCount: 0,
    matrixTooLarge: false,
    matrixCap: 2000,
    combinations: [] as ExistingCombination[],
    drafts: [],
    note: '',
  };

  it('把矩阵多大、占了多少、还能生成多少分开讲', () => {
    const hint = matrixHint({
      ...base,
      axes: twoAxes(),
      matrixRowCount: 6,
      conflictMatrixCount: 2,
      reservedCombinationCount: 1,
      existingSkuCount: 1,
      availableMatrixCount: 4,
    });
    expect(hint).toContain('矩阵共 6 条');
    expect(hint).toContain('已被占用 2 条');
    expect(hint).toContain('还可生成 4 条');
  });

  it('「没绑轴」与「绑了但没取值」是两种说法，下一步动作不同', () => {
    expect(matrixHint({ ...base, axes: [] })).toContain('还没有绑定任何规格轴');
    expect(matrixHint({ ...base, axes: twoAxes(), matrixRowCount: 0 })).toContain('0 行');
  });

  it('矩阵超限时报真实行数而不是让人以为能生成', () => {
    const hint = matrixHint({
      ...base,
      axes: twoAxes(),
      matrixRowCount: 2025,
      matrixTooLarge: true,
    });
    expect(hint).toContain('2025');
    expect(hint).toContain('超过单次草稿上限');
  });
});

describe('conflict display', () => {
  const conflict = (patch: Partial<CombinationConflict>): CombinationConflict => ({
    reasonCode: 'DUPLICATE_IN_BATCH',
    reason: '本次提交内重复',
    message: '',
    signature: 'CLR=红|SIZ=大',
    values: null,
    occurrenceIndexes: [0, 2],
    existingCombinationId: null,
    existingSkuId: null,
    existingDraftId: null,
    ...patch,
  });

  it('原因分组、落点与撞谁都要能读出来', () => {
    expect(conflictLabel(conflict({}))).toBe('本次提交内重复');
    expect(conflictLabel(conflict({ reasonCode: 'ALREADY_SKU' }))).toBe('与已存在的 SKU 相同');
    expect(conflictLabel(conflict({ reasonCode: 'UNKNOWN_CODE' }))).toBe('UNKNOWN_CODE');
    expect(conflictPositionText(conflict({}))).toBe('第 1 条、第 3 条');
    expect(conflictPositionText(conflict({ occurrenceIndexes: [] }))).toBe('');
    expect(conflictTargetText(conflict({ existingSkuId: 'sku-1' }))).toBe('已存在的 SKU');
    expect(conflictTargetText(conflict({ existingDraftId: 'draft-1' }))).toBe('另一份矩阵草稿');
    expect(conflictTargetText(conflict({}))).toBe('');
  });

  it('组合文案按轴编码排序，历史 SKU 的来源要说清只比到签名文本', () => {
    expect(
      combinationText([
        { axisName: '尺码', axisCode: 'SIZ', value: '大' },
        { axisName: '颜色', axisCode: 'CLR', value: '红' },
      ]),
    ).toBe('颜色 红 × 尺码 大');
    expect(
      combinationSourceText({
        signature: 'CLR=红',
        values: [],
        source: 'SKU_SIGNATURE',
        combinationId: null,
        skuId: 'sku-1',
        skuCode: 'SKU-1',
        draftId: null,
      }),
    ).toContain('仅签名文本可比对');
  });
});

describe('draft input', () => {
  it('空备注与空业务时间提交 null，让后端用默认值而不是空串', () => {
    expect(matrixDraftInput({ picks: {}, notes: '  ', occurredAt: '' })).toEqual({
      mode: 'MATRIX',
      combinations: null,
      notes: null,
      occurredAt: null,
    });
  });

  it('显式模式丢掉空行与没填的格子，不提交空组合', () => {
    const input = explicitToInput({
      rows: [
        [
          { specAxisId: 'axis-color', value: ' 红 ' },
          { specAxisId: 'axis-size', value: '大' },
        ],
        [{ specAxisId: 'axis-color', value: '  ' }],
        [],
      ],
      notes: ' 手工挑的 ',
      occurredAt: '2026-01-02T03:04:05Z',
    });
    expect(input.mode).toBe('EXPLICIT');
    expect(input.combinations).toHaveLength(1);
    expect(input.combinations?.[0]?.values).toEqual([
      { specAxisId: 'axis-color', value: '红' },
      { specAxisId: 'axis-size', value: '大' },
    ]);
    expect(input.notes).toBe('手工挑的');
  });

  it('业务时间必须是 ISO 串，留空表示用当前时间', () => {
    expect(occurredAtProblem('')).toBeNull();
    expect(occurredAtProblem('2026-01-02T03:04:05Z')).toBeNull();
    expect(occurredAtProblem('昨天')).toContain('ISO');
  });
});

describe('hasConflictList', () => {
  it('只有拿到结构化清单才返回 true，普通错误返回 false', () => {
    expect(hasConflictList({ body: { conflicts: [{ reasonCode: 'ALREADY_SKU' }] } })).toBe(true);
    expect(hasConflictList({ body: {} })).toBe(false);
    expect(hasConflictList(null)).toBe(false);
  });
});
