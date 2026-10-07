import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  allowedRiskBearers,
  cancelFirstMileShipment,
  confirmFirstMileShipment,
  createFirstMileShipment,
  dimensionCaption,
  dispatchFirstMileShipment,
  fetchFirstMileCapabilities,
  fetchFirstMileDetail,
  fetchFirstMileShipments,
  fetchSourceCandidates,
  fetchTransitLegs,
  firstMileQuery,
  fmt,
  legQuantityText,
  lineCaption,
  moment,
  money,
  pending,
  riskBearerLabel,
  riskPairingOk,
  shipmentActions,
  shipmentSortOptions,
  transportModeLabels,
  updateFirstMileShipment,
  type FirstMileParams,
} from './first-mile-shipment-model';

/** 抓出 api() 收到的 URL 与方法，用来断言**真实调用点**（不是手抄一份路由表）。 */
const calls: Array<{ url: string; method: string; body: unknown }> = [];

vi.mock('../inventory/api', () => ({
  api: (url: string, method = 'GET', body?: unknown) => {
    calls.push({ url, method, body });
    return Promise.resolve(null);
  },
}));

beforeEach(() => {
  calls.length = 0;
});

describe('查询串：每个筛选条件都真的进 URL', () => {
  it('空条件不带问号', () => {
    expect(firstMileQuery({})).toBe('');
  });

  it('全部筛选条件逐条落到查询串', () => {
    const q = firstMileQuery({
      status: ['CONFIRMED', 'DISPATCHED'],
      transportMode: 'SEA',
      incoterm: 'FOB',
      originWarehouseId: 'w1',
      destinationWarehouseId: 'w2',
      destinationCountryCode: ' US ',
      inTransitOnly: true,
      keyword: '  COSCO-1234 ',
      sort: 'EXPECTED_ARRIVE_AT',
      page: 2,
      size: 20,
    } satisfies FirstMileParams);
    expect(q.startsWith('?')).toBe(true);
    expect(q).toContain('status=CONFIRMED');
    expect(q).toContain('status=DISPATCHED');
    expect(q).toContain('transportMode=SEA');
    expect(q).toContain('incoterm=FOB');
    expect(q).toContain('originWarehouseId=w1');
    expect(q).toContain('destinationWarehouseId=w2');
    // 国家代码去空格并编码
    expect(q).toContain('destinationCountryCode=US');
    expect(q).toContain('inTransitOnly=true');
    expect(q).toContain('keyword=COSCO-1234');
    expect(q).toContain('sort=EXPECTED_ARRIVE_AT');
    expect(q).toContain('page=2');
    expect(q).toContain('size=20');
  });

  it('inTransitOnly 为 false 时不进查询串（不筛 ≠ 筛一个假值）', () => {
    expect(firstMileQuery({ inTransitOnly: false })).toBe('');
  });

  it('纯空白的关键字不进查询串', () => {
    expect(firstMileQuery({ keyword: '   ' })).toBe('');
  });
});

describe('真实调用点：从模型文件反抽的每条 URL 都被断言', () => {
  it('读：列表 / 能力 / 详情 / 选货 / 在途腿', async () => {
    await fetchFirstMileShipments({ page: 0, size: 20 });
    await fetchFirstMileCapabilities();
    await fetchFirstMileDetail('ship-1');
    await fetchSourceCandidates({ receiptId: 'r1', skuId: 's1', size: 50 });
    await fetchTransitLegs('ship-1');
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['GET', 'warehouse/first-mile-shipments?page=0&size=20'],
      ['GET', 'warehouse/first-mile-shipments/capabilities'],
      ['GET', 'warehouse/first-mile-shipments/ship-1'],
      ['GET', 'warehouse/first-mile-shipments/source-candidates?receiptId=r1&skuId=s1&size=50'],
      ['GET', 'warehouse/first-mile-shipments/ship-1/transit-legs'],
    ]);
  });

  it('写：建单 POST / 改单 PUT / 确认·发出·取消三个 POST', async () => {
    await createFirstMileShipment({} as never);
    await updateFirstMileShipment('ship-1', { expectedVersion: 0 } as never);
    await confirmFirstMileShipment('ship-1', { expectedVersion: 0 } as never);
    await dispatchFirstMileShipment('ship-1', { expectedVersion: 1 } as never);
    await cancelFirstMileShipment('ship-1', { expectedVersion: 1 } as never);
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['POST', 'warehouse/first-mile-shipments'],
      ['PUT', 'warehouse/first-mile-shipments/ship-1'],
      ['POST', 'warehouse/first-mile-shipments/ship-1/confirm'],
      ['POST', 'warehouse/first-mile-shipments/ship-1/dispatch'],
      ['POST', 'warehouse/first-mile-shipments/ship-1/cancel'],
    ]);
  });

  it('带 id 的 URL 走编码，不拼裸串', async () => {
    await fetchFirstMileDetail('a/../b');
    expect(calls[0]?.url).toBe('warehouse/first-mile-shipments/a%2F..%2Fb');
  });
});

describe('状态机只给合法动作', () => {
  it('草稿可改可确认可取消', () => {
    expect(shipmentActions('DRAFT')).toEqual(['edit', 'confirm', 'cancel']);
  });

  it('已确认可改可发出可取消', () => {
    expect(shipmentActions('CONFIRMED')).toEqual(['edit', 'dispatch', 'cancel']);
  });

  it('已发出是终态：不再给编辑/发出/取消（重复发出会双记在途）', () => {
    expect(shipmentActions('DISPATCHED')).toEqual([]);
  });

  it('已取消是终态', () => {
    expect(shipmentActions('CANCELLED')).toEqual([]);
  });

  it('未知状态不给任何动作，不静默给全部', () => {
    expect(shipmentActions('WHAT_IS_THIS')).toEqual([]);
  });
});

describe('货权与成本节点：贸易术语 × 风险承担方', () => {
  const caps = {
    incoterms: [
      {
        value: 'EXW',
        label: 'EXW 工厂交货',
        riskPoint: 'SELLER_TO_BUYER',
        allowedRiskBearers: ['BUYER'],
      },
      {
        value: 'DDP',
        label: 'DDP 完税后交货',
        riskPoint: 'SELLER_UNTIL',
        allowedRiskBearers: ['SELLER'],
      },
      {
        value: 'FOB',
        label: 'FOB 装运港船上交货',
        riskPoint: 'AT_ORIGIN',
        allowedRiskBearers: ['SELLER', 'BUYER', 'CARRIER'],
      },
    ],
  } as never;

  it('可选风险承担方从后端能力读，不在前端抄一份枚举', () => {
    expect(allowedRiskBearers(caps, 'EXW')).toEqual(['BUYER']);
    expect(allowedRiskBearers(caps, 'DDP')).toEqual(['SELLER']);
    expect(allowedRiskBearers(caps, 'FOB')).toEqual(['SELLER', 'BUYER', 'CARRIER']);
  });

  it('能力数据没到位时宽放，最终裁判仍在后端', () => {
    expect(allowedRiskBearers(null, 'EXW')).toEqual(['SELLER', 'BUYER', 'CARRIER']);
  });

  it('EXW 配卖方 / DDP 配买方都是不自洽组合（DB 两条 CHECK 同样钉死）', () => {
    expect(riskPairingOk('EXW', 'SELLER')).toBe(false);
    expect(riskPairingOk('EXW', 'BUYER')).toBe(true);
    expect(riskPairingOk('DDP', 'BUYER')).toBe(false);
    expect(riskPairingOk('DDP', 'SELLER')).toBe(true);
  });

  it('风险承担方有中文标签，未知码原样返回（不静默吞掉）', () => {
    expect(riskBearerLabel('SELLER')).toBe('卖方承担');
    expect(riskBearerLabel('BUYER')).toBe('买方承担');
    expect(riskBearerLabel('CARRIER')).toBe('承运人承担');
    expect(riskBearerLabel('WHO_KNOWS')).toBe('WHO_KNOWS');
  });

  it('四类运输方式与蓝图 10.4.1.1 原文逐值对应', () => {
    expect(Object.keys(transportModeLabels)).toEqual(['SEA', 'AIR', 'RAIL', 'EXPRESS']);
  });

  it('排序白名单 5 键，都是后端 orderBy 认的键', () => {
    expect(shipmentSortOptions.map((o) => o.value)).toEqual([
      'UPDATED_AT',
      'CREATED_AT',
      'SHIPMENT_CODE',
      'DEPART_AT',
      'EXPECTED_ARRIVE_AT',
    ]);
  });
});

describe('数量与金额口径', () => {
  it('余量用后端给的 pendingQuantity，不在前端自减', () => {
    expect(pending({ pendingQuantity: 40 })).toBe(40);
    // 后端给负数时夹到 0：负余量是脏数据，界面上不能显示「还能发 -5」
    expect(pending({ pendingQuantity: -5 })).toBe(0);
    expect(pending({ pendingQuantity: Number.NaN })).toBe(0);
  });

  it('在途腿守恒等式：原发 = 已了结 + 未达', () => {
    const ok = legQuantityText({ quantity: 100, settledQuantity: 60, outstandingQuantity: 40 });
    expect(ok.ok).toBe(true);
    expect(ok.text).toContain('原发 100');
    expect(ok.text).toContain('已了结 60');
    expect(ok.text).toContain('未达 40');
    // 跨两张表对不上时必须显式提示，而不是只显示 7.5.1.2 那一个数
    const bad = legQuantityText({ quantity: 100, settledQuantity: 60, outstandingQuantity: 50 });
    expect(bad.ok).toBe(false);
    expect(bad.text).toContain('对不上');
  });

  it('数量与空值展示', () => {
    expect(fmt(1234.5)).toBe('1,234.5');
    expect(fmt(null)).toBe('—');
    expect(fmt(undefined)).toBe('—');
    // 0 是真实值，必须显示 0 而不是 —：成本预估为 0 是能被质疑的数，空值才是数据丢了
    expect(fmt(0)).toBe('0');
  });

  it('金额带币种，空值显示破折号', () => {
    expect(money(2990, 'USD')).toBe('2,990.00 USD');
    expect(money(null, 'USD')).toBe('—');
    expect(money(0, 'USD')).toBe('0.00 USD');
  });
});

describe('时间展示：带偏移量，不改时刻', () => {
  it('合法 ISO 显示本地化时刻', () => {
    expect(moment('2026-11-20T09:45:00+08:00')).not.toBe('—');
  });

  it('空值与非法值都如实显示，不静默变成 1970', () => {
    expect(moment(null)).toBe('—');
    expect(moment('不是时间')).toBe('不是时间');
  });
});

describe('未交付维度：禁用并写明归属模块', () => {
  it('未交付的来源带 ownerModule 后缀', () => {
    expect(
      dimensionCaption({ label: '供应商采购单', available: false, ownerModule: '6.3.2.2' }),
    ).toBe('供应商采购单（6.3.2.2 未交付）');
  });

  it('已交付的维度只显示标签', () => {
    expect(dimensionCaption({ label: '收货单', available: true, ownerModule: '8.2.1.1' })).toBe(
      '收货单',
    );
  });
});

describe('明细行摘要：批次来源必须可读', () => {
  it('行摘要带行号、批次、来源单与三个数量', () => {
    const text = lineCaption({
      lineNo: 2,
      skuCode: 'SKU-1',
      skuName: '商品一',
      lotCode: 'LOT-1',
      sourceReceiptCode: 'GR-20261001-000001',
      sourceWaybillNo: 'WB-1',
      plannedQuantity: 100,
      shippedQuantity: 60,
      pendingQuantity: 40,
      declaredItemName: '棉制男士衬衫',
    });
    expect(text).toContain('第 2 行');
    expect(text).toContain('批次 LOT-1');
    expect(text).toContain('GR-20261001-000001');
    expect(text).toContain('WB-1');
    expect(text).toContain('计划 100');
    expect(text).toContain('已发 60');
    expect(text).toContain('余量 40');
    expect(text).toContain('棉制男士衬衫');
  });

  it('无批次与非批次管理的货如实显示「无批次」', () => {
    const text = lineCaption({
      lineNo: 1,
      skuCode: 'SKU-2',
      skuName: '商品二',
      lotCode: null,
      sourceReceiptCode: 'GR-1',
      sourceWaybillNo: null,
      plannedQuantity: 10,
      shippedQuantity: 10,
      pendingQuantity: 0,
      declaredItemName: '棉制衬衫',
    });
    expect(text).toContain('无批次');
    expect(text).toContain('余量 0');
  });
});
