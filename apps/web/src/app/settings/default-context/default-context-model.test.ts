import { describe, expect, it } from 'vitest';
import {
  DIMENSION_LEGAL_ENTITY,
  DIMENSION_SHOP,
  DIMENSION_WAREHOUSE,
  describeContext,
  dimensionLabel,
  isSelectable,
  selectableLegalEntities,
  selectableShops,
  selectableWarehouses,
  selectedId,
  selectionOf,
  sourceOf,
  staleOf,
  type DefaultContextOptions,
  type DefaultContextView,
} from './default-context-model';

/**
 * 1.4.2.2 默认店铺与仓库 —— 前端模型的纯函数断言。
 *
 * <p>这些函数钉住的是**四件做错了不会报错、只会静默出错**的事：
 *
 * <ol>
 *   <li><b>失效项不能被预选</b>：失效的默认项已经不在权限范围内了，
 *       把它预选在表单上，用户会以为它还能用，而保存时后端会拒绝它。</li>
 *   <li><b>停用 / 归档的候选不可选，但仍在清单里</b>：抹掉它们会让用户以为
 *       「这家店凭空消失了」。</li>
 *   <li><b>「读不到」与「没有」要分开</b>：某个上游读不到时，那一个维度报读不到，
 *       不能变成一个空清单 —— 空清单看起来就像「你没权限」。</li>
 *   <li><b>维度名到字段的映射不能错</b>：店铺 / 法人 / 仓库三个维度长得一样，
 *       映射错一个，用户存进去的默认值就落到了另一个维度上。</li>
 * </ol>
 */

const view: DefaultContextView = {
  stored: true,
  shop: { id: 'shop-1', code: 'S-1', name: '美国店', status: 'ACTIVE' },
  legalEntity: { id: 'le-1', code: 'LE-1', name: '美国主体', status: 'ACTIVE' },
  warehouse: { id: 'wh-1', code: 'W-1', name: '深圳仓', status: 'ACTIVE' },
  stale: [
    {
      dimension: DIMENSION_SHOP,
      id: 'shop-old',
      name: '已转手的店',
      reason: '已不在你的可见清单里（权限变更 / 店铺转手 / 已被删除），不再作为默认值（已回落）',
    },
  ],
  sources: [
    {
      dimension: DIMENSION_SHOP,
      available: true,
      source: 'channel GET /channel/shops',
      reason: null,
      ownerModule: null,
    },
    {
      dimension: DIMENSION_WAREHOUSE,
      available: false,
      source: 'warehouse GET /warehouse/facilities',
      reason: 'warehouse 服务不可达：仓库清单读不到',
      ownerModule: '17.3 权限管理 / 上游部署配置',
    },
  ],
  note: '已回落到租户默认仓库：W-DEFAULT 默认发货仓',
  updatedAt: '2026-10-23T10:00:00+08:00',
};

const options: DefaultContextOptions = {
  shops: [
    { id: 'shop-1', code: 'S-1', name: '美国店', platformName: 'Amazon', status: 'ACTIVE' },
    { id: 'shop-2', code: 'S-2', name: '德国店', platformName: 'Amazon', status: 'ARCHIVED' },
  ],
  legalEntities: [
    { id: 'le-1', code: 'LE-1', name: '美国主体', status: 'ACTIVE' },
    { id: 'le-2', code: 'LE-2', name: '德国主体', status: 'SUSPENDED' },
  ],
  warehouses: [
    { id: 'wh-1', code: 'W-1', name: '深圳仓', status: 'ACTIVE', defaultWarehouse: true },
    { id: 'wh-2', code: 'W-2', name: '宁波仓', status: 'INACTIVE', defaultWarehouse: false },
  ],
  sources: [],
};

describe('1.4.2.2 维度映射', () => {
  it('三个维度各自取到自己的选择', () => {
    expect(selectionOf(view, DIMENSION_SHOP)?.code).toBe('S-1');
    expect(selectionOf(view, DIMENSION_LEGAL_ENTITY)?.code).toBe('LE-1');
    expect(selectionOf(view, DIMENSION_WAREHOUSE)?.code).toBe('W-1');
  });

  it('未知维度返回空而不是别的维度的值', () => {
    expect(selectionOf(view, 'NOPE')).toBeNull();
    expect(selectionOf(null, DIMENSION_SHOP)).toBeNull();
  });

  it('维度名是人话', () => {
    expect(dimensionLabel(DIMENSION_SHOP)).toBe('店铺');
    expect(dimensionLabel(DIMENSION_LEGAL_ENTITY)).toBe('法人');
    expect(dimensionLabel(DIMENSION_WAREHOUSE)).toBe('仓库');
  });
});

describe('1.4.2.2 候选集筛选', () => {
  it('只有 ACTIVE 的候选可选，停用的仍在清单里但不可选', () => {
    expect(selectableShops(options).map((shop) => shop.code)).toEqual(['S-1']);
    expect(selectableLegalEntities(options).map((entity) => entity.code)).toEqual(['LE-1']);
    expect(selectableWarehouses(options).map((warehouse) => warehouse.code)).toEqual(['W-1']);
    // 清单本身仍然含有停用的那条
    expect(options.shops).toHaveLength(2);
  });

  it('候选集还没加载时是空数组而不是报错', () => {
    expect(selectableShops(null)).toEqual([]);
    expect(selectableLegalEntities(null)).toEqual([]);
    expect(selectableWarehouses(null)).toEqual([]);
  });

  it('isSelectable 只认 ACTIVE', () => {
    expect(isSelectable('ACTIVE')).toBe(true);
    expect(isSelectable('ARCHIVED')).toBe(false);
    expect(isSelectable('SUSPENDED')).toBe(false);
    expect(isSelectable(null)).toBe(false);
  });
});

describe('1.4.2.2 生效值与失效值', () => {
  it('当前选中取的是生效值', () => {
    expect(selectedId(view, DIMENSION_SHOP)).toBe('shop-1');
    expect(selectedId(view, DIMENSION_WAREHOUSE)).toBe('wh-1');
  });

  it('失效项单独可查，且带上原因', () => {
    const stale = staleOf(view, DIMENSION_SHOP);
    expect(stale?.id).toBe('shop-old');
    expect(stale?.reason).toContain('可见清单');
    expect(staleOf(view, DIMENSION_WAREHOUSE)).toBeUndefined();
  });

  it('没有失效项时返回 undefined 而不是空对象', () => {
    expect(staleOf(null, DIMENSION_SHOP)).toBeUndefined();
  });
});

describe('1.4.2.2 来源状态', () => {
  it('读不到的维度带原因与归属', () => {
    const source = sourceOf(view.sources, DIMENSION_WAREHOUSE);
    expect(source?.available).toBe(false);
    expect(source?.reason).toContain('不可达');
    expect(source?.ownerModule).toContain('17.3');
  });

  it('读得到的维度没有原因', () => {
    const source = sourceOf(view.sources, DIMENSION_SHOP);
    expect(source?.available).toBe(true);
    expect(source?.reason).toBeNull();
  });

  it('来源列表缺失时返回 undefined', () => {
    expect(sourceOf(undefined, DIMENSION_SHOP)).toBeUndefined();
  });
});

describe('1.4.2.2 摘要', () => {
  it('逐维度写清楚有没有设', () => {
    const lines = describeContext(view);
    expect(lines[0]).toBe('默认店铺：S-1 美国店');
    expect(lines[1]).toBe('默认法人：LE-1 美国主体');
    expect(lines[2]).toBe('默认仓库：W-1 深圳仓');
  });

  it('失效项会被点名，不静默消失', () => {
    expect(describeContext(view).some((line) => line.includes('已失效'))).toBe(true);
  });

  it('视图还没有时返回空数组', () => {
    expect(describeContext(null)).toEqual([]);
  });
});
