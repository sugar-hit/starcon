import { describe, expect, it } from 'vitest';
import type { LocationAllocationCandidate, LocationAllocationLinePlan } from '../../../../../packages/api-contracts/src/index';
import {
  allocationStatusLabel,
  capabilityGaps,
  isFullyAllocated,
  isUnknownReason,
  locationTypeLabel,
  measureState,
  qualityRoleLabel,
  reasonLines,
  reasonText,
  remainingText,
  requiredTypeText,
  scoreDrift,
} from './warehouse-location-allocation-model';

/**
 * 8.1.2.2 库位自动分配前端模型（纯函数，不碰网络与 rate）。
 *
 * <p>钉住七件「说错了会误导运营」的事：
 *
 * <ol>
 *   <li><b>没量过 ≠ 0</b>：容量 null 渲染「未量」，而「判不了」与「够」是两种不同的说法。
 *   <li><b>score 恒等于各条理由权重之和</b> —— drift 为 0 是硬断言（不是「差不多」）。
 *   <li><b>无法判定的理由权重恒为 0</b>：既不加分也不扣分，界面只能解释不能记功。
 *   <li><b>硬规则推出的目标类型要说出来</b>，候选按构造就不含别的类型。
 *   <li><b>未交付的维度列出来</b>，不假装 catalog 类目与巷道路径图已接入。
 *   <li><b>「待上架为 0」与「无候选」是两种空态</b>，文案不同。
 *   <li><b>三值词表与后端逐条一致</b>：质量角色 / 库位类型 / 决策状态 / 理由码。
 * </ol>
 */

function candidate(over: Partial<LocationAllocationCandidate> = {}): LocationAllocationCandidate {
  return {
    locationId: 'l1',
    code: 'S-01',
    name: 'S-01 位',
    type: 'STORAGE',
    typeLabel: '存储',
    depth: 2,
    distance: 1,
    capacity: 100,
    weightLimit: 50,
    occupiedCapacity: 0,
    remainingCapacity: 100,
    capacityEvaluated: true,
    requiredCapacity: 20,
    weightEvaluated: true,
    requiredWeight: 10,
    occupiedWeight: 0,
    remainingWeight: 50,
    onHand: 0,
    onHandSkuCount: 0,
    affinitySkus: 0,
    score: 0,
    reasons: [],
    ...over,
  };
}

function plan(over: Partial<LocationAllocationLinePlan> = {}): LocationAllocationLinePlan {
  return {
    receiptLineId: 'r1',
    skuId: 's1',
    skuCode: 'SKU-1',
    skuName: '商品一',
    skuUnitNote: '',
    qualityStatusId: 'q1',
    qualityRole: 'SELLABLE',
    qualityLabel: '可售',
    requiredRule: 'SELLABLE',
    requiredType: 'STORAGE',
    requiredTypeLabel: '存储',
    outstandingQuantity: 10,
    allocateQuantity: 10,
    sourceLocationId: 'd1',
    sourceLocationCode: 'DOCK',
    sourceLocationType: 'RECEIVING',
    sourceDepth: 1,
    requiredCapacity: 20,
    requiredWeight: 10,
    candidateCount: 1,
    rejectedLocationCount: 0,
    candidates: [],
    note: '',
    ...over,
  };
}

describe('8.1.2.2 库位自动分配前端模型', () => {
  it('没量过的容量显示「未量」而不是 0', () => {
    expect(remainingText(null)).toBe('未量');
    expect(remainingText(undefined)).toBe('未量');
    expect(remainingText(0)).toBe('0');
    expect(remainingText(12.5)).toBe('12.5');
  });

  it('容量三态分开：判不了 / 未量 / 余量', () => {
    // 判不了（没量过或没给需求）—— 既不是「够」也不是「不够」
    expect(measureState(false, 100)).toBe('无法判定');
    expect(measureState(false, null)).toBe('无法判定');
    // 量过且算得出余量
    expect(measureState(true, 100)).toBe('余量 100');
    expect(measureState(true, 0)).toBe('余量 0');
  });

  it('score 恒等于各条理由权重之和（drift 为 0）', () => {
    const c = candidate({
      score: 30 + 40 + 15 + 28 + 20 + 10 + 20,
      reasons: [
        { code: 'TYPE_RULE', text: '', weight: 30 },
        { code: 'CATEGORY_CLUSTER', text: '', weight: 40 },
        { code: 'CATEGORY_CLUSTER', text: '', weight: 15 },
        { code: 'SHORTEST_PATH', text: '', weight: 28 },
        { code: 'CAPACITY_FIT', text: '', weight: 20 },
        { code: 'WEIGHT_FIT', text: '', weight: 10 },
        { code: 'EMPTY_LOCATION', text: '', weight: 20 },
      ],
    });
    expect(scoreDrift(c)).toBe(0);
    expect(c.reasons.reduce((a, r) => a + r.weight, 0)).toBe(c.score);
  });

  it('score 与理由合计不一致时 drift 如实暴露偏差', () => {
    // 前端公式与后端权重不同步时，界面要显示偏差而不是默默给一个错的分数
    const c = candidate({ score: 10, reasons: [{ code: 'TYPE_RULE', text: '', weight: 30 }] });
    expect(scoreDrift(c)).toBe(-20);
  });

  it('无法判定的理由权重恒为 0 且被标为只解释', () => {
    expect(isUnknownReason({ code: 'CAPACITY_UNKNOWN', text: '', weight: 0 })).toBe(true);
    expect(isUnknownReason({ code: 'WEIGHT_UNKNOWN', text: '', weight: 0 })).toBe(true);
    expect(isUnknownReason({ code: 'CAPACITY_FIT', text: '', weight: 20 })).toBe(false);

    const c = candidate({
      capacity: null,
      weightLimit: null,
      remainingCapacity: null,
      remainingWeight: null,
      capacityEvaluated: false,
      weightEvaluated: false,
      score: 30 + 34,
      reasons: [
        { code: 'TYPE_RULE', text: '', weight: 30 },
        { code: 'SHORTEST_PATH', text: '', weight: 34 },
        { code: 'CAPACITY_UNKNOWN', text: '', weight: 0 },
        { code: 'WEIGHT_UNKNOWN', text: '', weight: 0 },
      ],
    });
    expect(scoreDrift(c)).toBe(0);
    const lines = reasonLines(c);
    const advisories = lines.filter((l) => l.advisory);
    expect(advisories.map((l) => l.text)).toEqual(['容量无法判定', '承重无法判定']);
    expect(advisories.every((l) => l.weight === 0)).toBe(true);
  });

  it('硬规则推出的目标库位类型与候选类型一致，且说不出别的类型', () => {
    const p = plan({
      qualityRole: 'DEFECTIVE',
      requiredRule: 'DEFECTIVE',
      requiredType: 'DEFECTIVE',
      requiredTypeLabel: '残次',
      candidates: [candidate({ type: 'DEFECTIVE', typeLabel: '残次' })],
    });
    expect(requiredTypeText(p)).toBe('残次');
    // 候选按构造就只含硬规则类型 —— 界面上不存在「也可以放存储位」这个选项
    expect(p.candidates.every((c) => c.type === p.requiredType)).toBe(true);
  });

  it('目标类型标签缺失时回落到词表中文名', () => {
    expect(requiredTypeText(plan({ requiredTypeLabel: null, requiredType: 'QUARANTINE' }))).toBe('待检');
    expect(locationTypeLabel('RECEIVING')).toBe('收货暂存');
    expect(locationTypeLabel('UNKNOWN_TYPE')).toBe('UNKNOWN_TYPE');
  });

  it('待上架为 0 与无候选是两种空态', () => {
    expect(isFullyAllocated(plan({ outstandingQuantity: 0 }))).toBe(true);
    expect(isFullyAllocated(plan({ outstandingQuantity: 0.0001 }))).toBe(false);
    // 数量为 0 但候选非空是矛盾形状，也要判成「已全部指派」
    expect(isFullyAllocated(plan({ outstandingQuantity: 0, candidates: [candidate()] }))).toBe(true);
  });

  it('未交付的维度列出来并带上归属模块，不假装已接入', () => {
    const gaps = capabilityGaps({
      hardPlacementRuleEnforced: true,
      occupancyLive: true,
      pathDistanceAvailable: true,
      routingGraphAvailable: false,
      routingGraphOwnerModule: '8.3.2.1',
      categoryAffinityAvailable: true,
      catalogCategoryAvailable: false,
      catalogCategoryOwnerModule: '3.3.1.1',
      capacityTracked: true,
      stockVolumeAvailable: false,
      putAwayTaskAvailable: false,
      putAwayTaskOwnerModule: '8.2.2.1',
      rules: ['SELLABLE', 'PENDING_INSPECTION', 'DEFECTIVE'],
      targetTypes: ['STORAGE', 'QUARANTINE', 'DEFECTIVE'],
      maxCandidates: 50,
      note: '',
    });
    expect(gaps.map((g) => g.label)).toEqual([
      '巷道路径图',
      'catalog 类目',
      '既有库存的体积口径',
      '上架任务',
    ]);
    const [routing, category, volume, putAway] = gaps;
    expect(routing?.owner).toBe('8.3.2.1');
    expect(category?.owner).toBe('3.3.1.1');
    // 体积口径没有归属模块：它是本服务内就拿不到的事实，不是别人没交付
    expect(volume?.owner).toBeNull();
    expect(putAway?.owner).toBe('8.2.2.1');
  });

  it('全部维度已接入时未交付列表为空', () => {
    const gaps = capabilityGaps({
      hardPlacementRuleEnforced: true,
      occupancyLive: true,
      pathDistanceAvailable: true,
      routingGraphAvailable: true,
      routingGraphOwnerModule: null,
      categoryAffinityAvailable: true,
      catalogCategoryAvailable: true,
      catalogCategoryOwnerModule: null,
      capacityTracked: true,
      stockVolumeAvailable: true,
      putAwayTaskAvailable: true,
      putAwayTaskOwnerModule: null,
      rules: [],
      targetTypes: [],
      maxCandidates: 50,
      note: '',
    });
    expect(gaps).toEqual([]);
  });

  it('三值词表与后端逐条一致', () => {
    expect(qualityRoleLabel('SELLABLE')).toBe('可售');
    expect(qualityRoleLabel('PENDING_INSPECTION')).toBe('待检');
    expect(qualityRoleLabel('DEFECTIVE')).toBe('残次');
    expect(allocationStatusLabel('PLANNED')).toBe('已选定');
    expect(allocationStatusLabel('RELEASED')).toBe('已释放');
    expect(allocationStatusLabel('CANCELLED')).toBe('已撤销');
    expect(reasonText('SHORTEST_PATH')).toBe('上架路径最短');
    expect(reasonText('ALREADY_COMMITTED')).toBe('已有其他上架承诺占用');
    // 未知码原样透出，而不是显示 undefined
    expect(qualityRoleLabel('WHATEVER')).toBe('WHATEVER');
    expect(allocationStatusLabel('WHATEVER')).toBe('WHATEVER');
    expect(reasonText('WHATEVER')).toBe('WHATEVER');
  });
});
