import { describe, expect, it } from 'vitest';
import type { LocationNode, LocationTree } from '../../../../../packages/api-contracts/src/index';
import {
  isEmptyTree,
  locationTypeLabel,
  measureText,
  occupancyText,
  utilizationText,
} from './warehouse-location-tree-model';

/**
 * 8.1.2.1 库位树前端纯函数。
 *
 * <p>重点钉住四件「错了会误导人」的事：
 *
 * <ul>
 *   <li>**「未量过」不能显示成 0.0%。** 0% 是一句关于事实的断言（「量过了，是空的」），
 *       「未量」是一句关于知识的断言（「没人量过」）。混成同一个 0，运营会拿它排库位 ——
 *       而后端对容量未量的库位给的是 {@code capacityUtilization: null}，不是 0。
 *   <li>**手数与预占不相加。** 预占是已被订单锁住但还没走的货。
 *   <li>**天然不可拣要说出来**（待检 / 残次 / 冻结），那是 8.1.2.2 自动分配的硬规则前提。
 *   <li>**空树与「加载失败」是两种形状**：isEmptyTree 只看有没有层级行。
 * </ul>
 */

function node(overrides: Partial<LocationNode> = {}): LocationNode {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    parentId: '22222222-2222-2222-2222-222222222222',
    code: 'B-01',
    name: '01 货架 1 层',
    type: 'STORAGE',
    typeLabel: '存储',
    status: 'ACTIVE',
    statusLabel: '启用',
    pickable: true,
    pickableByNature: true,
    depth: 1,
    capacity: 10,
    weightLimit: 100,
    occupied: {
      onHand: 3,
      reserved: 0,
      skuCount: 2,
      bucketRows: 2,
      capacityUtilization: 0.3,
      capacityExceeded: false,
    },
    subtreeOccupancy: {
      onHand: 3,
      reserved: 0,
      skuCount: 2,
      bucketRows: 2,
      capacityUtilization: null,
      capacityExceeded: false,
    },
    createdAt: '2026-10-13T10:00:00+08:00',
    updatedAt: '2026-10-13T10:00:00+08:00',
    ...overrides,
  };
}

function tree(overrides: Partial<LocationTree> = {}): LocationTree {
  return {
    warehouseId: '33333333-3333-3333-3333-333333333333',
    levels: [node()],
    rootCount: 1,
    totals: {
      locationCount: 1,
      rootCount: 1,
      leafCount: 1,
      pickableCount: 1,
      capacityMeasuredCount: 1,
      onHand: 3,
      reserved: 0,
      skuCountDistinct: 2,
      skuCount: 2,
      typeCount: 1,
      byType: [{ type: 'STORAGE', label: '存储', count: 1 }],
    },
    note: '测试',
    ...overrides,
  };
}

describe('8.1.2.1 库位树 · 容量利用率', () => {
  it('未量过的容量显示「未量」而不是 0.0%', () => {
    expect(utilizationText(null)).toBe('未量');
    expect(utilizationText(undefined)).toBe('未量');
  });

  it('量过且空置才显示 0.0% —— 与「未量」是两句话', () => {
    expect(utilizationText(0)).toBe('0.0%');
    // 这条是本模块最容易被「顺手统一成 0%」改坏的地方
    expect(utilizationText(0)).not.toBe(utilizationText(null));
  });

  it('有值时按百分比保留一位小数', () => {
    expect(utilizationText(0.3)).toBe('30.0%');
    expect(utilizationText(1)).toBe('100.0%');
    expect(utilizationText(0.1234)).toBe('12.3%');
  });
});

describe('8.1.2.1 库位树 · 容量与承重缺值', () => {
  it('没量过显示「—」而不是 0', () => {
    expect(measureText(null)).toBe('—');
    expect(measureText(0)).toBe('0');
  });
});

describe('8.1.2.1 库位树 · 占用', () => {
  it('手数与预占分列不相加', () => {
    const text = occupancyText(
      node({
        occupied: {
          onHand: 10,
          reserved: 4,
          skuCount: 1,
          bucketRows: 1,
          capacityUtilization: null,
          capacityExceeded: false,
        },
      }),
    );
    expect(text.onHand).toBe('10');
    expect(text.reserved).toBe('4');
    expect(text.onHand).not.toBe('14');
  });

  it('天然不可拣却被手工设为可拣时给出提示', () => {
    const text = occupancyText(node({ type: 'QUARANTINE', pickableByNature: false, pickable: true }));
    expect(text.pickableHint).toContain('天然不可拣');
  });

  it('可拣类型没有多余提示', () => {
    expect(occupancyText(node()).pickableHint).toBeNull();
    expect(occupancyText(node({ type: 'FROZEN', pickableByNature: false, pickable: false })).pickableHint)
      .toBeNull();
  });
});

describe('8.1.2.1 库位树 · 类型标签', () => {
  it('六个词表值都有中文名', () => {
    expect(locationTypeLabel('STORAGE')).toBe('存储');
    expect(locationTypeLabel('RECEIVING')).toBe('收货暂存');
    expect(locationTypeLabel('SHIPPING')).toBe('发货暂存');
    expect(locationTypeLabel('QUARANTINE')).toBe('待检');
    expect(locationTypeLabel('DEFECTIVE')).toBe('残次');
    expect(locationTypeLabel('FROZEN')).toBe('冻结');
  });

  it('未知类型原样返回而不是显示成空白', () => {
    expect(locationTypeLabel('WHATEVER')).toBe('WHATEVER');
  });
});

describe('8.1.2.1 库位树 · 空态', () => {
  it('没有层级行才是空树；加载失败(null)也算空但由页面分三态渲染', () => {
    expect(isEmptyTree(tree({ levels: [] }))).toBe(true);
    expect(isEmptyTree(null)).toBe(true);
    expect(isEmptyTree(tree())).toBe(false);
  });
});