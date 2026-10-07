import { describe, expect, it } from 'vitest';
import {
  actionNote,
  bucketPlacement,
  bucketQuery,
  errorText,
  groupByDimension,
  groupLabel,
  lotLabel,
  parseDetailHref,
  pickableNote,
  qty,
  query,
  roleLabels,
  rowComposition,
  scopeLabels,
  type OverviewParams,
} from './inventory-model';
import type {
  BucketAction,
  BucketRow,
  OverviewGroup,
  OverviewRow,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError } from './api';

function bucketRow(partial: Partial<BucketRow>): BucketRow {
  return {
    bucketId: 'k1',
    balanceId: 'b1',
    skuId: 's1',
    skuCode: 'SKU-1',
    skuName: '商品一',
    warehouseId: 'w1',
    warehouseCode: 'WH-1',
    warehouseName: '上海仓',
    locationId: 'loc1',
    locationCode: 'A-01-01',
    locationName: 'A区01排01',
    locationType: 'STORAGE',
    pickable: true,
    lotId: 'lot1',
    lotCode: 'LOT-1',
    expiresOn: '2027-01-01',
    lotStatus: 'ACTIVE',
    expired: false,
    legalEntityId: 'l1',
    legalEntityCode: 'LE-1',
    legalEntityName: '主体一',
    qualityStatusId: 'q1',
    qualityStatusCode: 'SELLABLE',
    qualityStatusName: '可售',
    qualityRole: 'SELLABLE',
    onHand: 100,
    reserved: 30,
    available: 70,
    zeroStock: false,
    version: 0,
    lastMovementAt: '2026-10-07T00:00:00Z',
    updatedAt: '2026-10-07T00:00:00Z',
    ...partial,
  };
}

function bucketAction(partial: Partial<BucketAction>): BucketAction {
  return {
    action: 'ADJUST',
    label: '发起调整',
    href: '/inventory/adjustments?skuId={skuId}',
    available: false,
    ownerModule: '7.4.1.1',
    note: '目标模块 7.4.1.1 尚未交付，入口契约已就位但暂不可用',
    ...partial,
  };
}

function group(partial: Partial<OverviewGroup>): OverviewGroup {
  return {
    dimension: 'WAREHOUSE',
    key: null,
    label: 'WH-1',
    onHand: 0,
    reserved: 0,
    available: 0,
    inTransit: 0,
    sellable: 0,
    pendingInspection: 0,
    defective: 0,
    rowCount: 0,
    zeroStockRowCount: 0,
    overstockRowCount: 0,
    ...partial,
  };
}

function row(partial: Partial<OverviewRow>): OverviewRow {
  return {
    balanceId: 'b1',
    skuId: 's1',
    skuCode: 'SKU-1',
    skuName: '商品一',
    warehouseId: 'w1',
    warehouseCode: 'WH-1',
    warehouseName: '上海仓',
    legalEntityId: 'l1',
    legalEntityCode: 'LE-1',
    legalEntityName: '主体一',
    qualityStatusId: 'q1',
    qualityStatusCode: 'SELLABLE',
    qualityStatusName: '可售',
    qualityRole: 'SELLABLE',
    onHand: 100,
    reserved: 30,
    available: 70,
    inTransit: 0,
    sellable: 100,
    pendingInspection: 0,
    defective: 0,
    zeroStock: false,
    thresholdConfigured: false,
    maxOnHand: null,
    policyScope: null,
    overstock: false,
    version: 0,
    updatedAt: '2026-10-07T00:00:00Z',
    detailHref: '/inventory?skuId=s1&warehouseId=w1&legalEntityId=l1&qualityStatusId=q1',
    ...partial,
  };
}

describe('库存总览 · 查询参数', () => {
  it('空条件不产生查询串', () => {
    expect(query({})).toBe('');
  });

  it('只拼非空条件，零库存与超储显式带上布尔值', () => {
    const params: OverviewParams = {
      warehouseId: 'w1',
      legalEntityId: 'l1',
      // 首尾空白在拼串时就被 trim 掉，不该进到查询串里
      keyword: '  上海  ',
      zeroStock: true,
      overstock: true,
      sort: 'ON_HAND',
      page: 0,
      size: 50,
    };
    expect(query(params)).toBe(
      '?warehouseId=w1&legalEntityId=l1&keyword=%E4%B8%8A%E6%B5%B7&zeroStock=true&overstock=true&sort=ON_HAND&page=0&size=50',
    );
  });

  it('false 不等于未传：后端把两者视为不同含义', () => {
    // zeroStock=false 必须发出去，否则"只看零库存"取消后仍会带着 true 的语义。
    expect(query({ zeroStock: false })).toBe('?zeroStock=false');
    expect(query({})).not.toContain('zeroStock');
  });

  it('空白关键字不参与筛选', () => {
    expect(query({ keyword: '   ' })).toBe('');
  });

  it('条件值做 URL 编码，避免破坏查询串', () => {
    expect(query({ keyword: 'a&b=c' })).toBe('?keyword=a%26b%3Dc');
  });
});

describe('库存总览 · 数量与口径展示', () => {
  it('固定两位小数，空值显示为破折号而不是 0', () => {
    // 0 与"没有值"含义不同：空值画成 0 会被读成"库存为零"。
    expect(qty(null)).toBe('—');
    expect(qty(undefined)).toBe('—');
    expect(qty(0)).toBe('0.00');
    expect(qty(1234.5)).toBe('1,234.50');
  });

  it('行构成把在仓与在途分开，不合并成"在手"', () => {
    expect(rowComposition(row({ onHand: 100, inTransit: 12 }))).toBe('在仓 100.00 · 在途 12.00');
  });

  it('在途为 0 时仍显式写出 0，而不是省略', () => {
    // 省略会让人以为"没有在途数据"，而不是"在途确实是 0"。
    expect(rowComposition(row({ onHand: 5, inTransit: 0 }))).toBe('在仓 5.00 · 在途 0.00');
  });

  it('质量角色与阈值范围有中文名', () => {
    expect(roleLabels.SELLABLE).toBe('可售');
    expect(roleLabels.PENDING_INSPECTION).toBe('待检');
    expect(roleLabels.DEFECTIVE).toBe('残次');
    expect(scopeLabels.WAREHOUSE).toBe('仓库级');
    expect(scopeLabels.DEFAULT).toBe('默认级');
  });
});

describe('库存总览 · 分组统计', () => {
  const groups = [
    group({ dimension: 'WAREHOUSE', label: 'WH-1 上海仓', onHand: 100 }),
    group({ dimension: 'WAREHOUSE', label: 'WH-2 深圳仓', onHand: 50 }),
    group({ dimension: 'QUALITY_ROLE', label: 'SELLABLE', onHand: 120 }),
    group({ dimension: 'SKU', label: 'SKU-1', onHand: 150 }),
  ];

  it('按维度过滤，各维度分组之和等于该维度的合计', () => {
    const byWarehouse = groupByDimension(groups, 'WAREHOUSE');
    expect(byWarehouse).toHaveLength(2);
    expect(byWarehouse.reduce((total, g) => total + g.onHand, 0)).toBe(150);
  });

  it('质量角色维度用角色中文名做标签，而不是行名', () => {
    const role = groupByDimension(groups, 'QUALITY_ROLE')[0]!;
    expect(groupLabel(role)).toBe('可售');
    // 仓库维度保留"编码 + 名称"，不套用角色映射
    expect(groupLabel(groups[0]!)).toBe('WH-1 上海仓');
  });

  it('未知维度返回空数组而不是抛错', () => {
    expect(groupByDimension(groups, 'NOT_A_DIMENSION')).toEqual([]);
  });
});

describe('库存总览 · 错误文案', () => {
  it('422 单独说明是筛选不可用，而不是笼统的请求失败', () => {
    const message = errorText(new ApiError(422, '「类目」筛选依赖的数据尚未接入本服务'));
    expect(message).toContain('尚未接入');
    expect(message).not.toContain('请求失败');
  });

  it('权限不足与服务不可用分别给出可读原因', () => {
    expect(errorText(new ApiError(403, 'x'))).toContain('权限');
    expect(errorText(new ApiError(0, 'x'))).toContain('仓库服务');
  });

  it('未知异常有兜底文案', () => {
    expect(errorText(new Error('boom'))).toBe('加载失败，请稍后重试');
  });
});

describe('7.1.1.2 库存明细 · 查询串', () => {
  it('空条件不产生查询串', () => {
    expect(bucketQuery({})).toBe('');
  });

  it('逐级下钻的六个维度各自独立拼进查询串', () => {
    const q = bucketQuery({
      warehouseId: 'w1',
      legalEntityId: 'l1',
      locationId: 'loc1',
      skuId: 's1',
      lotId: 'lot1',
      qualityStatusId: 'q1',
    });
    expect(q).toContain('warehouseId=w1');
    expect(q).toContain('legalEntityId=l1');
    expect(q).toContain('locationId=loc1');
    expect(q).toContain('skuId=s1');
    expect(q).toContain('lotId=lot1');
    expect(q).toContain('qualityStatusId=q1');
  });

  it('关键字首尾空白在拼串时就被 trim 掉', () => {
    expect(bucketQuery({ keyword: '  A-01  ' })).toBe('?keyword=A-01');
  });

  it('只看可拣库位只在勾选时出现，未勾选与 false 同义（后端按未传处理）', () => {
    expect(bucketQuery({})).not.toContain('onlyPickable');
    expect(bucketQuery({ onlyPickable: false })).not.toContain('onlyPickable');
    expect(bucketQuery({ onlyPickable: true })).toContain('onlyPickable=true');
  });

  it('总览的查询串不会误带明细独有的库位与批次参数', () => {
    // 两套拼装刻意分开：共用一个函数就得给两边都塞可选字段，读起来分不清哪条路支持哪个条件
    expect(query({ warehouseId: 'w1' })).toBe('?warehouseId=w1');
  });
});

describe('7.1.1.2 库存明细 · 行展示口径', () => {
  it('库位·批次·质量状态合成一列，三样都能看到', () => {
    const r = bucketRow({ locationCode: 'A-01-01', lotCode: 'LOT-1', qualityRole: 'SELLABLE' });
    expect(bucketPlacement(r)).toBe('A-01-01 · LOT-1 · 可售');
  });

  it('无批次显示成"无批次"而不是空白格', () => {
    // 空白在表里会被读成"数据缺失"，而无批次是一个真实取值
    expect(lotLabel(null)).toBe('无批次');
    expect(lotLabel('LOT-1')).toBe('LOT-1');
    expect(bucketPlacement(bucketRow({ lotCode: null }))).toContain('无批次');
  });

  it('不可拣库位给出"为什么发不出去"的理由', () => {
    expect(pickableNote(bucketRow({ pickable: true }))).toBe('可拣，可发货');
    expect(pickableNote(bucketRow({ pickable: false, locationType: 'QUARANTINE' }))).toBe(
      '待检库位，不可发货',
    );
  });

  it('未交付的操作入口显示原因，不给点下去 404 的入口', () => {
    const action = bucketAction({ available: false });
    expect(actionNote(action)).toContain('7.4.1.1');
    expect(actionNote(bucketAction({ available: true }))).toContain('7.4.1.1');
  });
});

describe('7.1.1.1 → 7.1.1.2 下钻契约', () => {
  const href =
    '/inventory?skuId=s1&warehouseId=w1&legalEntityId=l1&qualityStatusId=q1';

  it('解析后端给的 detailHref，取出四个行级维度', () => {
    expect(parseDetailHref(href)).toEqual({
      skuId: 's1',
      warehouseId: 'w1',
      legalEntityId: 'l1',
      qualityStatusId: 'q1',
    });
  });

  it('缺任一维度都按"无下钻"处理，而不是只带一半条件', () => {
    // 带一半条件会让用户以为看到的就是那一行，实际是整个仓库
    expect(parseDetailHref('/inventory?skuId=s1&warehouseId=w1')).toBeNull();
    expect(parseDetailHref('')).toBeNull();
    expect(parseDetailHref(null)).toBeNull();
  });

  it('非 /inventory 的 href 不解析，且不抛异常', () => {
    expect(parseDetailHref('/somewhere-else?skuId=s1')).toBeNull();
    expect(parseDetailHref('/inventory')).toBeNull();
  });
});
