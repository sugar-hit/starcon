import { describe, expect, it } from 'vitest';
import {
  DIMENSION_OPTIONS,
  GRANULARITY_OPTIONS,
  abcTone,
  amountText,
  caliberLabel,
  coverageText,
  crossCurrencyNote,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyText,
  pageState,
  quantityText,
  rangeText,
  rateText,
  shareBarWidth,
  shareText,
  slowMovingStatusLabel,
  stockStatusTone,
  turnoverTone,
} from './inventory-analysis-model';
import type { InventoryStructureResponse } from './inventory-analysis-model';

/**
 * 13.5.1.1 库存分析 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的五件事**：
 *
 * 1. **「算不出来」≠「数值是 0」**。服务端给 `null` 的金额 / 占比 / 周转率必须渲染成「—」，
 *    **不是** 0.00（假 0 会被读成「这些货不占资金」「周转率是 0」）。
 * 2. **周转率按成本口径**。页面上不出现任何「按售价算」的换算 —— 前端一个乘法都不做。
 * 3. **多币种绝不相加**。金额必须带自己的币种；跨币种的合计不存在。
 * 4. **数据更新时间常驻**。没有投影过库存事实时也要有一句话，不能什么都不显示。
 * 5. **前端不做业务算术**。金额、占比、周转率、周转天数、ABC 分类一律来自服务端。
 */

function structure(overrides: Partial<InventoryStructureResponse> = {}): InventoryStructureResponse {
  return {
    asOf: '2026-10-10',
    dimension: 'ALL',
    rows: [
      {
        dimension: 'ALL',
        dimensionKey: '',
        dimensionLabel: '全部',
        currency: 'USD',
        inventoryValue: 1350,
        onHandQuantity: 155,
        availableQuantity: 155,
        skuCount: 3,
        valueShare: 100,
        comparable: true,
        zeroStockSkuCount: 0,
        overstockSkuCount: 1,
        overstockQuantity: 2,
        note: '',
      },
    ],
    totals: [
      {
        currency: 'USD',
        inventoryValue: 1350,
        onHandQuantity: 155,
        availableQuantity: 155,
        skuCount: 3,
        costMissingSkuCount: 2,
        costMissingQuantity: 10,
      },
    ],
    totalInventoryValue: 1350,
    totalCurrency: 'USD',
    costMissingSkuCount: 2,
    costMissingQuantity: 10,
    dataUpdatedAt: '2026-10-10T03:00:00Z',
    dataUpdatedNote: '数据截至（这是**投影**的更新时间）',
    caliberNote: '库存金额 = 在手数量 × **catalog 5.2.1.2 成本构成里的单位成本**',
    warnings: [],
    ...overrides,
  };
}

describe('13.5.1.1 库存分析 · 展示格式化', () => {
  it('金额为 null 时显示「—」，绝不显示 0 元', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(0, 'USD')).toBe('0.00 USD');
    // 🔴 假 0 与「算不出来」在报表里长得一样，但含义相反 —— 必须分开。
    expect(amountText(null, 'USD')).not.toBe(amountText(0, 'USD'));
  });

  it('金额带自己的币种（多币种绝不相加）', () => {
    expect(amountText(1350, 'USD')).toBe('1,350.00 USD');
    expect(amountText(70, 'EUR')).toBe('70.00 EUR');
  });

  it('占比为 null 时显示「—」，不是 0%', () => {
    expect(shareText(null)).toBe('—');
    expect(shareText(0)).toBe('0.00%');
    expect(shareText(74.0741)).toBe('74.07%');
  });

  it('周转率为 null 时显示「—」，不是 0 次', () => {
    expect(rateText(null)).toBe('—');
    expect(rateText(0)).toBe('0.0000 次');
    expect(rateText(0.2222)).toBe('0.2222 次');
  });

  it('数量为 null 时显示「—」', () => {
    expect(quantityText(null)).toBe('—');
    expect(quantityText(155)).toBe('155.000');
  });

  it('结构条只做展示钳位，不做算术', () => {
    expect(shareBarWidth(null)).toBe(0);
    expect(shareBarWidth(-5)).toBe(0);
    expect(shareBarWidth(120)).toBe(100);
    expect(shareBarWidth(38.7097)).toBe(38.7097);
  });
});

describe('13.5.1.1 库存分析 · 口径文案', () => {
  it('库存口径三态分别说清楚', () => {
    expect(caliberLabel('AVERAGE')).toContain('平均库存');
    expect(caliberLabel('CLOSING')).toContain('期末库存');
    expect(caliberLabel('NONE')).toContain('没有库存快照');
  });

  it('呆滞档位有中文名', () => {
    expect(slowMovingStatusLabel('OBSOLETE')).toBe('呆滞');
    expect(slowMovingStatusLabel('SLOW_MOVING')).toBe('滞销');
    expect(slowMovingStatusLabel('OBSOLETE_AND_UP')).toBe('呆滞及以上');
  });

  it('覆盖率文案点明「没有维护成本」的 SKU 与数量', () => {
    expect(coverageText(2, 10)).toContain('2 个 SKU');
    expect(coverageText(2, 10)).toContain('没有维护成本');
    expect(coverageText(0, 0)).toBe('');
  });

  it('跨币种时明确说「合计不存在」', () => {
    const totals = [{ currency: 'USD' }, { currency: 'EUR' }];
    expect(crossCurrencyNote(totals, null)).toContain('跨币种的合计不存在');
    // 单币种时不多嘴。
    expect(crossCurrencyNote([{ currency: 'USD' }], 'USD')).toBe('');
  });
});

describe('13.5.1.1 库存分析 · 语义色', () => {
  it('ABC 三类各有语义色', () => {
    expect(abcTone('A')).toBe('danger');
    expect(abcTone('B')).toBe('warning');
    expect(abcTone('C')).toBe('neutral');
  });

  it('算不出来不是错误（neutral），不是 danger', () => {
    expect(turnoverTone(false)).toBe('neutral');
    expect(turnoverTone(true)).toBe('success');
  });

  it('缺货优先于超储', () => {
    expect(stockStatusTone(1, 0)).toBe('danger');
    expect(stockStatusTone(0, 1)).toBe('warning');
    expect(stockStatusTone(0, 0)).toBe('success');
  });
});

describe('13.5.1.1 库存分析 · 页面状态', () => {
  it('「读不到」与「没有数据」是两种状态', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(structure({ rows: [] }), '')).toBe('empty');
    expect(pageState(structure(), '')).toBe('ready');
  });

  it('空态文案说清是「没有事实」而不是「出了错」', () => {
    expect(emptyText(structure({ rows: [] }))).toContain('没有任何库存事实');
    expect(emptyText(null)).toBe('');
    expect(emptyText(structure())).toBe('');
  });

  it('数据截至常驻；没有投影过时有专门的一句话', () => {
    expect(dataUpdatedText(structure())).toContain('数据截至');
    expect(dataUpdatedText(structure({ dataUpdatedAt: null }))).toContain('尚未投影任何库存事实');
    expect(dataUpdatedNote(structure())).toContain('投影');
  });
});

describe('13.5.1.1 库存分析 · 筛选与时间', () => {
  it('默认窗口是最近 30 天（含今天）', () => {
    const range = defaultRange('2026-10-10');
    expect(range.to).toBe('2026-10-10');
    expect(range.from).toBe('2026-09-11');
  });

  it('窗口文本原样显示', () => {
    expect(rangeText('2026-09-11', '2026-10-10')).toBe('2026-09-11 ~ 2026-10-10');
  });

  it('维度与粒度选项覆盖蓝图点名的维度', () => {
    const values = DIMENSION_OPTIONS.map((o) => o.value);
    expect(values).toContain('WAREHOUSE');
    expect(values).toContain('CATEGORY');
    expect(values).toContain('SHOP');
    expect(GRANULARITY_OPTIONS.map((o) => o.value)).toEqual(['DAY', 'WEEK', 'MONTH']);
  });
});
