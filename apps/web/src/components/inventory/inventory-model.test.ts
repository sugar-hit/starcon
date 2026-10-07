import { describe, expect, it } from 'vitest';
import {
  actionNote,
  bucketPlacement,
  bucketQuery,
  errorText,
  grainNote,
  groupByDimension,
  groupLabel,
  ledgerKindLabels,
  ledgerKindOptions,
  ledgerQuery,
  lotLabel,
  parseDetailHref,
  parseLedgerHref,
  pickableNote,
  isPlatformRow,
  observationFreshness,
  rowSourceNote,
  turnoverBasisLabels,
  turnoverText,
  warehouseKindLabels,
  qty,
  query,
  roleLabels,
  rowComposition,
  scopeLabels,
  diffText,
  severityLabels,
  signed,
  signedTone,
  statusLabels,
  suspectCategoryLabels,
  windowLabel,
  withOffset,
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

// ================================================================ 7.1.2.1 库存流水台账

describe('7.1.2.1 台账查询串', () => {
  it('空值不拼进去：后端把"未传"与"传空串"当成两种含义', () => {
    expect(ledgerQuery({})).toBe('');
    expect(ledgerQuery({ from: '', skuId: '', keyword: '   ' })).toBe('');
  });

  it('时间参数原样发出并带时区偏移', () => {
    // 服务端按 timestamptz 的绝对时刻筛选，偏移量必须一起发出去
    expect(ledgerQuery({ from: '2026-10-01T00:00:00+08:00' })).toBe(
      '?from=2026-10-01T00%3A00%3A00%2B08%3A00',
    );
  });

  it('业务类型与来源单据类型用重复参数表示多选', () => {
    expect(ledgerQuery({ kinds: ['RECEIPT', 'SHIPMENT'] })).toBe('?kinds=RECEIPT&kinds=SHIPMENT');
    expect(ledgerQuery({ refTypes: ['ORDER'] })).toBe('?refTypes=ORDER');
  });

  it('分页与排序按固定顺序拼接', () => {
    expect(ledgerQuery({ sort: 'OCCURRED_AT', page: 2, size: 50 })).toBe(
      '?sort=OCCURRED_AT&page=2&size=50',
    );
  });
});

describe('7.1.2.1 数量符号', () => {
  it('正数显式加 +、负数用真正的减号、零不带符号', () => {
    // 台账的方向就是入库/出库，只靠颜色区分在灰度和色觉障碍下不可读
    expect(signed(40)).toBe('+40.00');
    expect(signed(-15)).toBe('−15.00');
    expect(signed(0)).toBe('0.00');
  });

  it('空值显示破折号而不是 0，避免"没有数据"被读成"数量为零"', () => {
    expect(signed(null)).toBe('—');
    expect(signed(undefined)).toBe('—');
  });

  it('颜色只是辅助：符号方向与文本一致', () => {
    expect(signedTone(40)).toContain('emerald');
    expect(signedTone(-15)).toContain('rose');
    expect(signedTone(0)).toContain('muted');
  });
});

describe('7.1.2.1 业务类型标签', () => {
  it('八个码与 inventory_transaction.kind 的 CHECK 约束一一对应', () => {
    expect(Object.keys(ledgerKindLabels).sort()).toEqual([
      'ADJUSTMENT',
      'RECEIPT',
      'RELEASE',
      'RESERVE',
      'RETURN',
      'SHIPMENT',
      'TRANSFER_IN',
      'TRANSFER_OUT',
    ]);
  });

  it('调整这一类显式写明同时承载盘点与组装', () => {
    // 蓝图列了盘点与组装，但 V1 的 CHECK 只有八个码：界面不能造出筛不出来的类型
    expect(ledgerKindLabels.ADJUSTMENT).toContain('盘点');
    expect(ledgerKindLabels.ADJUSTMENT).toContain('组装');
  });

  it('下拉选项与标签表同源，不在第二处重写一份', () => {
    expect(ledgerKindOptions).toHaveLength(Object.keys(ledgerKindLabels).length);
    expect(ledgerKindOptions.every((o) => o.label === ledgerKindLabels[o.value])).toBe(true);
  });
});

describe('7.1.2.1 粒度口径说明', () => {
  it('带维度筛选时说明行上的量是整笔净变动', () => {
    expect(grainNote(true)).toContain('整笔业务');
    expect(grainNote(true)).toContain('差额');
  });

  it('无维度筛选时说明两个口径恒等', () => {
    expect(grainNote(false)).toContain('恒等');
  });
});

describe('7.1.1.2 → 7.1.2.1 下钻契约', () => {
  it('解析「查看流水」的 href，取出台账接得住的两个维度', () => {
    expect(
      parseLedgerHref('/inventory/ledger?skuId=s1&warehouseId=w1&locationId=l1&lotId=t1'),
    ).toEqual({ skuId: 's1', warehouseId: 'w1', transactionId: '' });
  });

  it('bucket 级的库位与批次维度被丢弃：台账读的是 balance 口径', () => {
    const parsed = parseLedgerHref('/inventory/ledger?locationId=l1&lotId=t1');
    expect(parsed).toBeNull();
  });

  it('只带一个维度也能下钻（不要求成对）', () => {
    expect(parseLedgerHref('/inventory/ledger?skuId=s1')).toEqual({
      skuId: 's1',
      warehouseId: '',
      transactionId: '',
    });
  });

  it('7.1.2.2 核对页带着 transactionId 进来时也解析（直接定位到那笔流水）', () => {
    expect(parseLedgerHref('/inventory/ledger?transactionId=tx-9')).toEqual({
      skuId: '',
      warehouseId: '',
      transactionId: 'tx-9',
    });
  });

  it('非台账路由的 href 不解析，且不抛异常', () => {
    expect(parseLedgerHref('/inventory/adjustments?skuId=s1')).toBeNull();
    expect(parseLedgerHref('')).toBeNull();
    expect(parseLedgerHref(null)).toBeNull();
  });
});

// ================================================================ 7.1.2.2 账实核对

describe('账实核对的前端口径', () => {
  it('datetime-local 的墙钟串被补上本机偏移量', () => {
    const local = '2026-10-01T09:30';
    const stamped = withOffset(local);
    // 必须带偏移：后端不接受无偏移的时间串，否则同一份请求在不同环境核出不同区间
    expect(stamped).toMatch(/^2026-10-01T09:30:00[+-]\d{2}:\d{2}$/);
    expect(new Date(stamped).getTime()).toBe(new Date(local).getTime());
  });

  it('已经带秒的串不会被重复补秒', () => {
    const local = '2026-10-01T09:30:00';
    expect(withOffset(local)).toBe(`2026-10-01T09:30:00${withOffset(local).slice(19)}`);
  });

  it('无法解析的串原样返回，不吞也不编一个时间', () => {
    expect(withOffset('不是时间')).toBe('不是时间');
  });

  it('差异为 0 显示成 0.0000 而不是空白（空白会被读成"没查到"）', () => {
    expect(diffText(0)).toBe('0.0000');
    expect(diffText(null)).toBe('0.0000');
    expect(diffText(undefined)).toBe('0.0000');
    expect(diffText(-3.5)).toBe('-3.5000');
  });

  it('可疑区间按天分桶：同一天显示一天，跨天显示区间', () => {
    // 用本地时刻构造，换任何时区跑都是 10-03 / 10-04，断言不依赖 CI 的时区
    const start = new Date(2026, 9, 3, 0, 0, 0).toISOString();
    const end = new Date(2026, 9, 4, 0, 0, 0).toISOString();
    expect(windowLabel(start, end)).toBe('2026-10-03 ~ 2026-10-04');
    expect(windowLabel(start, null)).toBe('2026-10-03');
  });

  it('桶标签按本地时区取日：+08:00 的凌晨不会被折算成前一天', () => {
    expect(windowLabel('2026-10-03T00:30:00+08:00', '2026-10-04T00:30:00+08:00')).toBe(
      '2026-10-03 ~ 2026-10-04',
    );
  });

  it('无法解析的时间原样返回，不编一个日期出来', () => {
    expect(windowLabel('不是时间', null)).toBe('不是时间');
  });

  it('可疑类别与后端四类一一对应，没有为"其它"留一个假类别', () => {
    expect(Object.keys(suspectCategoryLabels).sort()).toEqual([
      'BACKFILL',
      'DUPLICATE',
      'LOST_UPDATE',
      'MISSING',
    ]);
    expect(suspectCategoryLabels.MISSING).toBe('漏记');
    expect(suspectCategoryLabels.DUPLICATE).toBe('重复记');
    expect(suspectCategoryLabels.LOST_UPDATE).toBe('并发丢失');
  });

  it('结论标签：一致与有差异都是有效结论，都要有中文说法', () => {
    expect(statusLabels.CONSISTENT).toBe('账实一致');
    expect(statusLabels.DISCREPANT).toBe('存在差异');
    expect(severityLabels.HIGH).toBe('严重');
  });
});

// ================================================================ 7.1.3.1 多仓分布视图

/**
 * 构造一条矩阵行。自有仓与平台仓两组列互斥有值 —— 这正是本模块要守住的口径。
 */
function ownRow(overrides: Record<string, unknown> = {}) {
  return {
    warehouseId: 'w1',
    warehouseCode: 'WH-SH',
    warehouseName: '上海仓',
    warehouseKind: 'LOCAL',
    countryCode: 'CN',
    timezone: 'Asia/Shanghai',
    onHand: 100,
    reserved: 30,
    available: 70,
    inTransit: 10,
    platformOnHand: null,
    platformReserved: null,
    platformAvailable: null,
    platformInbound: null,
    source: null,
    collectedAt: null,
    observationAgeSeconds: null,
    turnoverDays: 12.5,
    turnoverComputable: true,
    turnoverBasis: 'OWN_OUTBOUND',
    turnoverNote: '在手 ÷（近 30 天发货出库 240 ÷ 30）',
    bucketCount: 2,
    locationCount: 2,
    lotCount: 1,
    lastMovementAt: '2026-10-01T00:00:00+08:00',
    ...overrides,
  } as never;
}

function platformRow(overrides: Record<string, unknown> = {}) {
  return ownRow({
    warehouseKind: 'PLATFORM_FBA',
    onHand: null,
    reserved: null,
    available: null,
    inTransit: null,
    platformOnHand: 80,
    platformReserved: 5,
    platformAvailable: 75,
    platformInbound: 25,
    source: 'AMAZON_FBA',
    collectedAt: '2026-10-07T10:00:00+08:00',
    observationAgeSeconds: 3600,
    bucketCount: 0,
    locationCount: 0,
    lotCount: 0,
    lastMovementAt: null,
    ...overrides,
  });
}

describe('7.1.3.1 多仓分布：自有账本量与平台观测量必须分区', () => {
  it('仓库性质标签覆盖蓝图点名的四类仓', () => {
    expect(warehouseKindLabels.LOCAL).toBe('本地自营仓');
    expect(warehouseKindLabels.OVERSEAS).toBe('海外自营仓');
    expect(warehouseKindLabels.PLATFORM_FBA).toBe('平台仓（FBA）');
    expect(warehouseKindLabels.THIRD_PARTY).toBe('三方仓');
  });

  it('自有仓与平台仓的判定只看 kind，不看有没有数量', () => {
    // 平台仓缺货时数量是 0，仍然是平台仓 —— 判定不能被数量带偏
    expect(isPlatformRow(platformRow({ platformOnHand: 0 }))).toBe(true);
    expect(isPlatformRow(ownRow())).toBe(false);
  });

  it('来源标注如实说明这一行的数字是账还是快照', () => {
    expect(rowSourceNote(ownRow())).toBe('自有账本量');
    // 平台行必须点名"观测值、不是自有账本"并带出来源，否则用户会把它当账用
    const note = rowSourceNote(platformRow());
    expect(note).toContain('观测值');
    expect(note).toContain('不是自有账本');
    expect(note).toContain('AMAZON_FBA');
  });

  it('周转天数算不出时显示「—」而不是 0', () => {
    // 0 会被读成「瞬间卖完」；空值加原因才是"没算出来"
    const t = turnoverText(
      ownRow({ turnoverComputable: false, turnoverDays: null, turnoverBasis: 'NONE', turnoverNote: '没有发货出库记录' }),
    );
    expect(t.value).toBe('—');
    expect(t.basis).toBe(turnoverBasisLabels.NONE);
    expect(t.title).toBe('没有发货出库记录');
  });

  it('周转天数两个口径分开标注，不能直接比大小', () => {
    expect(turnoverBasisLabels.OWN_OUTBOUND).toBe('自有发货速度');
    expect(turnoverBasisLabels.PLATFORM_OBSERVATION_DIFF).toBe('平台观测差分');
    const own = turnoverText(ownRow());
    const fba = turnoverText(
      platformRow({ turnoverBasis: 'PLATFORM_OBSERVATION_DIFF', turnoverNote: '观测快照差分，与自有仓口径不可直接比较' }),
    );
    expect(own.basis).not.toBe(fba.basis);
    expect(own.value).toBe('12.5 天');
  });

  it('观测新鲜度超过一天要标出数据较旧', () => {
    const now = Date.parse('2026-10-07T12:00:00+08:00');
    const fresh = observationFreshness(platformRow(), now);
    expect(fresh).toContain('2 小时前');
    expect(fresh).not.toContain('数据较旧');

    const stale = observationFreshness(
      platformRow({ collectedAt: '2026-10-01T10:00:00+08:00' }),
      now,
    );
    expect(stale).toContain('数据较旧');
    // 自有仓没有"采集时间"这个概念，返回破折号而不是编一个时间
    expect(observationFreshness(ownRow(), now)).toBe('—');
  });
});
