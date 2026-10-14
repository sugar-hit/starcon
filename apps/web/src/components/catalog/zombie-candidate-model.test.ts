import { describe, expect, it } from 'vitest';
import type {
  ZombieCandidate,
  ZombieCandidatePage,
  ZombieDimensionStatus,
} from '../../../../../packages/api-contracts/src/index';
import {
  VERDICT_ACTIONS,
  VERDICT_LABELS,
  batchBlockReason,
  blockedLines,
  countsConsistent,
  requiredDimensionCount,
  candidateHeadline,
  candidateQueryParams,
  deactivateBlockReason,
  dimensionObservedText,
  dimensionValueText,
  emptyCandidateListQuery,
  inactiveDaysProblem,
  isUnknown,
  maintenanceText,
  requiredDimensionText,
  ruleProblem,
  selectable,
  staleDaysProblem,
  toRuleForm,
  type Verdict,
} from './zombie-candidate-model';
import { ApiError } from './api';

/**
 * 3.9.2.2 僵尸主数据清理前端模型的离线单测。
 *
 * <p>钉死的是「后端与前端各说一套」最容易漂的四组：
 * 三态标签与动作互不相同、未知与零的区分、不可停用的两种原因、以及阈值边界与后端同口径。
 */

function dimension(overrides: Partial<ZombieDimensionStatus> = {}): ZombieDimensionStatus {
  return {
    metric: 'INVENTORY_ON_HAND',
    state: 'SATISFIED',
    value: 0,
    observedAt: '2026-03-01T00:00:00Z',
    stale: false,
    note: '',
    ...overrides,
  };
}

function candidate(overrides: Partial<ZombieCandidate> = {}): ZombieCandidate {
  return {
    entityId: '11111111-1111-1111-1111-111111111111',
    entityType: 'SKU',
    code: 'SKU-001',
    name: '螺丝刀',
    status: 'ACTIVE',
    lastMaintainedAt: '2025-01-01T00:00:00Z',
    daysSinceMaintained: 400,
    verdict: 'CANDIDATE',
    verdictLabel: '僵尸候选',
    reasons: [],
    dimensions: [],
    ruleInactiveDays: 365,
    ruleEnabled: true,
    ...overrides,
  };
}

// ------------------------------------------------------------ 三态

describe('三态标签与动作', () => {
  it('三种判定的标签与动作各不相同（不能共用一句话）', () => {
    const labels = Object.values(VERDICT_LABELS);
    const actions = Object.values(VERDICT_ACTIONS);
    expect(new Set(labels).size).toBe(3);
    expect(new Set(actions).size).toBe(3);
    for (const action of actions) expect(action).not.toBe('');
  });

  it('「无法判定」明确说未知不等于零', () => {
    expect(VERDICT_ACTIONS.INCOMPLETE).toContain('未知');
    expect(VERDICT_ACTIONS.INCOMPLETE).not.toBe(VERDICT_ACTIONS.NOT_ZOMBIE);
  });

  it('在用与无法判定都不可停用，但原因文案不同', () => {
    const inUse = deactivateBlockReason(candidate({ verdict: 'NOT_ZOMBIE' }));
    const incomplete = deactivateBlockReason(candidate({ verdict: 'INCOMPLETE' }));
    expect(inUse).not.toBeNull();
    expect(incomplete).not.toBeNull();
    expect(inUse).not.toBe(incomplete);
    expect(selectable(candidate({ verdict: 'CANDIDATE' }))).toBe(true);
    expect(selectable(candidate({ verdict: 'INCOMPLETE' }))).toBe(false);
  });

  it('未知判定状态也拒绝停用而不是默认放行', () => {
    expect(deactivateBlockReason(candidate({ verdict: 'WHATEVER' as Verdict }))).not.toBeNull();
  });
});

// ------------------------------------------------------------ 未知 vs 零

describe('未观测与观测到零必须能区分', () => {
  it('未观测的取值显示为破折号而不是 0', () => {
    expect(dimensionValueText(dimension({ value: null }))).toBe('—');
    expect(dimensionValueText(dimension({ value: 0 }))).toBe('0');
  });

  it('未观测与过期在观测时刻上分别标注', () => {
    expect(dimensionObservedText(dimension({ observedAt: null }))).toBe('未观测');
    expect(dimensionObservedText(dimension({ stale: true }))).toContain('已过期');
    expect(dimensionObservedText(dimension({ stale: false }))).not.toContain('已过期');
  });

  it('只有 UNKNOWN 算无法判定，NOT_REQUIRED 不算', () => {
    expect(isUnknown(dimension({ state: 'UNKNOWN' }))).toBe(true);
    expect(isUnknown(dimension({ state: 'NOT_REQUIRED' }))).toBe(false);
    expect(isUnknown(dimension({ state: 'SATISFIED' }))).toBe(false);
  });
});

// ------------------------------------------------------------ 展示

describe('展示辅助', () => {
  it('从未维护与今天维护过不能都显示成「0 天」', () => {
    expect(maintenanceText(candidate({ daysSinceMaintained: null }))).toBe('从未维护');
    expect(maintenanceText(candidate({ daysSinceMaintained: 0 }))).toBe('今天维护过');
    expect(maintenanceText(candidate({ daysSinceMaintained: 12 }))).toContain('12');
  });

  it('名称为空时标题不塌成空白', () => {
    expect(candidateHeadline(candidate({ name: '' }))).toBe('SKU-001');
    expect(candidateHeadline(candidate())).toBe('SKU-001 螺丝刀');
  });

  it('规则依赖维度数为 0 时给出的是可用文案而不是空', () => {
    expect(requiredDimensionText(0)).toContain('未依赖');
    expect(requiredDimensionText(3)).toContain('3');
  });
});

// ------------------------------------------------------------ 校验（与后端同口径）

describe('阈值校验与后端 @Min/@Max、DB CHECK 同口径', () => {
  it('维护阈值边界 30 / 3650', () => {
    expect(inactiveDaysProblem(30)).toBeNull();
    expect(inactiveDaysProblem(29)).not.toBeNull();
    expect(inactiveDaysProblem(3650)).toBeNull();
    expect(inactiveDaysProblem(3651)).not.toBeNull();
    expect(inactiveDaysProblem(1.5)).toContain('整数');
  });

  it('快照过期边界 1 / 3650，且 0 被明确拒绝', () => {
    expect(staleDaysProblem(1)).toBeNull();
    expect(staleDaysProblem(0)).not.toBeNull();
    expect(staleDaysProblem(3651)).not.toBeNull();
  });

  it('停用备注为空时规则不可保存（停用记录要能说明依据）', () => {
    expect(ruleProblem(baseRule({ deactivateNote: '' }))).toContain('依据');
    expect(ruleProblem(baseRule({ deactivateNote: '   ' }))).not.toBeNull();
    expect(ruleProblem(baseRule({ deactivateNote: '按僵尸规则停用' }))).toBeNull();
  });

  it('规则问题按 维护阈值 → 过期阈值 → 备注 的顺序返回', () => {
    const problem = ruleProblem({
      inactiveDays: 1,
      snapshotStaleDays: 0,
      requireZeroOrderReference: false,
      requireZeroInventory: false,
      requireZeroListing: false,
      requireZeroCost: false,
      enabled: true,
      deactivateNote: '',
      version: 0,
    });
    expect(problem).toBe(inactiveDaysProblem(1));
  });

  it('表单初值以服务端规则为准（含 version，缺它会 409）', () => {
    const form = toRuleForm({
      entityType: 'SUPPLIER',
      entityTypeLabel: '供应商',
      inactiveDays: 180,
      snapshotStaleDays: 45,
      requireZeroOrderReference: true,
      requireZeroInventory: false,
      requireZeroListing: false,
      requireZeroCost: false,
      enabled: true,
      deactivateNote: '默认文案',
      version: 7,
      configured: true,
    });
    expect(form.version).toBe(7);
    expect(form.inactiveDays).toBe(180);
    expect(form.requireZeroOrderReference).toBe(true);
  });
});

function baseRule(overrides: Partial<{ deactivateNote: string }> = {}) {
  return {
    inactiveDays: 365,
    snapshotStaleDays: 90,
    requireZeroOrderReference: false,
    requireZeroInventory: false,
    requireZeroListing: false,
    requireZeroCost: false,
    enabled: true,
    deactivateNote: '按僵尸规则停用',
    version: 0,
    ...overrides,
  };
}

// ------------------------------------------------------------ 查询参数

describe('列表查询参数', () => {
  it('空关键词不进入 URL（避免 keyword= 变成"匹配空串"）', () => {
    const params = candidateQueryParams(emptyCandidateListQuery());
    expect(params.get('entityType')).toBe('SKU');
    expect(params.get('keyword')).toBeNull();
    expect(params.get('verdict')).toBeNull();
    expect(params.get('page')).toBe('0');
  });

  it('关键词去空白后提交', () => {
    const params = candidateQueryParams({
      ...emptyCandidateListQuery(),
      keyword: '  SKU-001 ',
      verdict: 'CANDIDATE',
    });
    expect(params.get('keyword')).toBe('SKU-001');
    expect(params.get('verdict')).toBe('CANDIDATE');
  });
});

// ------------------------------------------------------------ 批量停用与整批拒绝

describe('批量停用', () => {
  it('未勾选时按钮禁用并说明原因', () => {
    expect(batchBlockReason([])).toContain('先勾选');
    expect(batchBlockReason([candidate()])).toBeNull();
  });

  it('409 的 blocked 清单逐条转成可读文案', () => {
    const error = new ApiError(409, '整批拒绝', {
      detail: '整批拒绝',
      blocked: [
        { code: 'SKU-1', name: '螺丝刀', reason: '判定为在用或有订单/库存/刊登/成本引用' },
        { code: 'SKU-2', name: '扳手', reason: '引用维度缺少有效观测，无法判定' },
      ],
    });
    const lines = blockedLines(error);
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('SKU-1');
    expect(lines[0]).toContain('判定为在用');
    expect(lines[1]).toContain('无法判定');
    expect(lines[0]).not.toBe(lines[1]);
  });

  it('非 409 或没有 blocked 清单时返回空数组（不制造假的行）', () => {
    expect(blockedLines(new ApiError(400, '参数错'))).toEqual([]);
    expect(blockedLines(new ApiError(409, '整批拒绝', {}))).toEqual([]);
    expect(blockedLines(new Error('boom'))).toEqual([]);
  });
});

// ------------------------------------------------------------ 计数一致性

describe('分页计数', () => {
  it('后端给出的三态计数相加等于 total（分页没漏算）', () => {
    const page: ZombieCandidatePage = {
      items: [],
      total: 7,
      page: 0,
      size: 20,
      entityType: 'SKU',
      candidateCount: 3,
      inUseCount: 2,
      incompleteCount: 2,
    };
    expect(countsConsistent(page)).toBe(true);
    expect(page.candidateCount + page.inUseCount + page.incompleteCount).toBe(page.total);
    // 漏算时必须报 false，而不是让分页"看起来"正常。
    expect(countsConsistent({ ...page, total: 8 })).toBe(false);
  });

  it('requiredDimensionCount 按契约里的四个布尔列现算', () => {
    const base = {
      requireZeroOrderReference: false,
      requireZeroInventory: false,
      requireZeroListing: false,
      requireZeroCost: false,
    };
    expect(requiredDimensionCount(base)).toBe(0);
    expect(requiredDimensionCount({ ...base, requireZeroCost: true })).toBe(1);
    expect(requiredDimensionCount({ ...base, requireZeroOrderReference: true, requireZeroInventory: true })).toBe(2);
  });
});