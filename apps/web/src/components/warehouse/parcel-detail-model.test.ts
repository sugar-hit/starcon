import { describe, expect, it } from 'vitest';
import type { ParcelCostAllocation, ParcelItemSalesOrderLine } from '../../../../../packages/api-contracts/src/index';
import {
  actionHint,
  allocationVerdict,
  dimensionCell,
  matchBasisLabel,
  orderLineLabel,
  quantityCell,
  unavailableText,
  unmatchedRows,
  volumeCell,
  weightCell,
} from './parcel-detail-model';

/**
 * 10.2.2.1 包裹详情前端模型的展示纪律测试。
 *
 * <p>这几条不是「测函数」而是**钉住界面上不能出现的误导**：
 * 未称重不许显示 0、退回订单级必须说清、弱关联必须与强关联区分开。
 */

function allocation(over: Partial<ParcelCostAllocation>): ParcelCostAllocation {
  return {
    allocatableToLine: true,
    totalQuantity: 4,
    matchedQuantity: 4,
    unmatchedQuantity: 0,
    itemCount: 1,
    linkedItemCount: 1,
    unlinkedItemCount: 0,
    distinctSalesOrderCount: 1,
    distinctSalesOrderLineCount: 1,
    fallbackLevel: 'LINE',
    fallbackReason: null,
    unmatchedItems: [],
    note: '',
    ...over,
  };
}

function link(over: Partial<ParcelItemSalesOrderLine>): ParcelItemSalesOrderLine {
  return {
    linkId: 'l1',
    salesOrderId: 'o1',
    salesOrderLineId: 'ol1',
    salesOrderNo: 'SO-1001',
    salesOrderLineNo: 1,
    skuId: 's1',
    skuCode: 'SKU-1',
    allocatedQuantity: 4,
    salesLineQuantity: 4,
    matchBasis: 'SKU_UNIQUE_ALIGNED',
    matchBasisLabel: '强关联（SKU 唯一键对齐）',
    strongMatch: true,
    createdAt: null,
    ...over,
  };
}

describe('未称重显示「未量」而不是 0', () => {
  it('重量与体积为 null 时不许出现 0', () => {
    expect(weightCell(null, 'g')).toBe('未量');
    expect(volumeCell(null)).toBe('未量');
  });

  it('有值时带单位返回', () => {
    expect(weightCell(1203, 'g')).toBe('1203 g');
    expect(volumeCell(0.006)).toBe('0.006 cbm');
  });

  it('外形尺寸缺任一件就整体显示「未量尺寸」，不拼半空的尺寸串', () => {
    expect(dimensionCell(30, 20, null)).toBe('未量尺寸');
    expect(dimensionCell(30, 20, 10)).toBe('30 × 20 × 10 cm');
  });

  it('0 本身是真值时照常显示（不是所有 0 都是缺失）', () => {
    expect(weightCell(0, 'g')).toBe('0 g');
  });
});

describe('归集到行 / 退回订单级必须一眼可见', () => {
  it('全部关联上时结论是「可归集到销售订单行」', () => {
    const v = allocationVerdict(allocation({}));
    expect(v.level).toBe('LINE');
    expect(v.tone).toBe('ok');
    expect(v.label).toContain('可归集到销售订单行');
  });

  it('有一条没关联就必须显示「只能归到订单级」并带上件数', () => {
    const v = allocationVerdict(
      allocation({
        allocatableToLine: false,
        fallbackLevel: 'ORDER',
        linkedItemCount: 0,
        unlinkedItemCount: 1,
        distinctSalesOrderLineCount: 0,
        matchedQuantity: 0,
        unmatchedQuantity: 5,
      }),
    );
    expect(v.level).toBe('ORDER');
    expect(v.tone).toBe('warn');
    // 蓝图那句「缺了就只能算到订单级」必须出现在界面上。
    expect(v.label).toContain('只能归到订单级');
    expect(v.label).toContain('5');
  });

  it('未关联的明细项逐条可追到具体是哪几件货', () => {
    const rows = unmatchedRows(
      allocation({
        unmatchedItems: [
          {
            parcelItemId: 'pi1',
            itemNo: 2,
            skuCode: 'SKU-2',
            skuName: '商品2',
            quantity: 3,
            reason: '未关联',
            ownerModule: null,
          },
        ],
      }),
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]).toContain('#2');
    expect(rows[0]).toContain('SKU-2');
    expect(rows[0]).toContain('3 件');
  });
});

describe('强弱关联必须区分', () => {
  it('强关联的标签写明强关联', () => {
    const label = orderLineLabel(link({}));
    expect(label).toContain('SO-1001');
    expect(label).toContain('第 1 行');
    expect(label).toContain('强关联');
  });

  it('弱关联绝不显示成强关联', () => {
    const label = orderLineLabel(
      link({ strongMatch: false, matchBasis: 'SKU_NAME_FALLBACK' }),
    );
    expect(label).toContain('弱关联');
    expect(label).not.toContain('强关联');
  });

  it('口径标签缺失时回落到枚举名，不静默吞掉', () => {
    expect(matchBasisLabel(link({ matchBasisLabel: '' }))).toBe('SKU_UNIQUE_ALIGNED');
  });
});

describe('数量与不可得分段的展示', () => {
  it('数量去掉无意义尾零但不改数', () => {
    expect(quantityCell(6)).toBe('6');
    expect(quantityCell(2.5)).toBe('2.5');
    expect(quantityCell(null)).toBe('—');
  });

  it('不可得分段写明归谁', () => {
    expect(
      unavailableText({ label: '物流轨迹', ownerModule: '10.3.1.1', reason: '未交付' }),
    ).toBe('物流轨迹 · 归 10.3.1.1 未接入');
    expect(unavailableText({ label: '运费与体积重', ownerModule: null, reason: '不在这算' })).toBe(
      '运费与体积重 · 未接入',
    );
  });

  it('没有前置条件的入口不显示「前置：无」这种废话', () => {
    expect(
      actionHint({ href: '/warehouse/packing-orders', ownerModule: '8.3.3.2', precondition: '无' }),
    ).toBe('8.3.3.2');
    expect(
      actionHint({ href: null, ownerModule: '12 财务', precondition: '明细项已关联' }),
    ).toContain('前置：明细项已关联');
  });
});
