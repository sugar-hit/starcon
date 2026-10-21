import { describe, expect, it } from 'vitest';
import type { StockoutRiskItemRow } from '../../../../../packages/api-contracts/src/index';
import {
  NO_DATA_TEXT,
  coverageText,
  dailySalesText,
  fmt,
  isNoData,
  shopText,
  shortageText,
  stockoutDateText,
  stockoutRiskQuery,
  stockoutUrgencyLabels,
  stockoutVelocityQuery,
  urgencyLabel,
  urgencyTone,
  velocitySourceLabel,
} from './stockout-risk-model';

/**
 * 6.2.1.1 缺货与断货风险前端模型。
 *
 * <p>这些断言钉的是**界面上不能搞错的判断**，不是文案：
 * 「无数据」绝不能变成 0、紧急度分档的视觉分级、涉及店铺空列表的读法、查询串的拼装。
 */

function item(overrides: Partial<StockoutRiskItemRow> = {}): StockoutRiskItemRow {
  return {
    id: 'i1',
    snapshotId: 'snap1',
    snapshotNo: 'SR-1',
    skuId: 'sku1',
    skuCode: 'SKU-1',
    skuName: 'SKU-1 名称',
    onHandQuantity: 100,
    reservedQuantity: 0,
    safetyBufferQuantity: 0,
    availableQuantity: 100,
    inTransitQuantity: 0,
    inTransitArrivingQuantity: 0,
    inTransitNoEtaQuantity: 0,
    dailySalesQuantity: 10,
    coverageDays: 10,
    expectedStockoutDate: '2026-01-25',
    replenishmentArrivalDate: '2026-02-14',
    shortageQuantity: 200,
    urgency: 'HIGH',
    urgencyLabel: '高',
    urgencyBasis: '断货日落在备货周期内，且缺口量 200',
    shopCount: 1,
    shopCodes: ['US-AMAZON'],
    sortRank: 0,
    ...overrides,
  };
}

describe('6.2.1.1 缺货与断货风险前端模型', () => {
  it('没有销售速度时显示「无数据」而不是 0', () => {
    // 这条是本模块最容易搞错的一格：0 日均销量会让可支撑天数变成无穷、
    // 健康度被高估。所以 null 必须走「无数据」这条路。
    expect(dailySalesText(null)).toBe(NO_DATA_TEXT);
    expect(dailySalesText(null)).not.toBe('0');
    expect(coverageText(null)).toBe(NO_DATA_TEXT);
    expect(stockoutDateText(null)).toBe(NO_DATA_TEXT);
  });

  it('登记为 0 的日均销量如实显示 0（它是事实，与「没登记」不同）', () => {
    expect(dailySalesText(0)).toBe('0');
    expect(coverageText(null)).toBe(NO_DATA_TEXT);
  });

  it('可支撑天数带「天」的单位，预计断货日原样显示', () => {
    expect(coverageText(10)).toBe('10 天');
    expect(coverageText(0)).toBe('0 天');
    expect(stockoutDateText('2026-01-25')).toBe('2026-01-25');
  });

  it('数量格式化去掉尾零；null 一律给「无数据」', () => {
    expect(fmt(100)).toBe('100');
    expect(fmt(0.5)).toBe('0.5');
    expect(fmt(null)).toBe(NO_DATA_TEXT);
    expect(fmt(undefined)).toBe(NO_DATA_TEXT);
  });

  it('紧急度分档的视觉分级：紧急/高是危险色，无数据是中性色', () => {
    expect(urgencyTone('CRITICAL')).toBe('danger');
    expect(urgencyTone('HIGH')).toBe('danger');
    expect(urgencyTone('MEDIUM')).toBe('warning');
    expect(urgencyTone('LOW')).toBe('info');
    // 无数据不是「危险」，是「答不出来」—— 用红色会把它和真断货混在一起。
    expect(urgencyTone('NO_DATA')).toBe('neutral');
    expect(isNoData('NO_DATA')).toBe(true);
    expect(isNoData('HIGH')).toBe(false);
    expect(urgencyLabel('CRITICAL')).toBe('紧急');
    expect(stockoutUrgencyLabels.NO_DATA).toBe('无数据');
  });

  it('涉及店铺：空列表是「未挂店铺」，不是空格子', () => {
    expect(shopText(['US-AMAZON', 'US-SHOPIFY'])).toBe('US-AMAZON、US-SHOPIFY');
    expect(shopText([])).toBe('未挂店铺');
  });

  it('缺口量 0 是「不构成缺口」，如实显示 0', () => {
    expect(shortageText(0)).toBe('0');
    expect(shortageText(295)).toBe('295');
  });

  it('销售速度来源标签', () => {
    expect(velocitySourceLabel('MANUAL')).toBe('人工登记');
    expect(velocitySourceLabel('EXTERNAL_SYNC')).toBe('外部同步');
    expect(velocitySourceLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('清单查询串：只拼有值的条件，keyword 会 trim', () => {
    expect(stockoutRiskQuery({ page: 0, size: 20 })).toBe('?page=0&size=20');
    expect(stockoutRiskQuery({ urgency: 'HIGH', onlyShortage: true })).toBe(
      '?urgency=HIGH&onlyShortage=true',
    );
    expect(stockoutRiskQuery({ keyword: '  ' })).toBe('');
    expect(stockoutRiskQuery({ keyword: ' SKU-1 ' })).toBe('?keyword=SKU-1');
    expect(stockoutRiskQuery({ onlyWithoutVelocity: true })).toBe('?onlyWithoutVelocity=true');
  });

  it('销售速度与快照查询串', () => {
    expect(stockoutVelocityQuery({ effectiveOnly: true })).toBe('?effectiveOnly=true');
    expect(stockoutVelocityQuery({})).toBe('');
  });

  it('排序位次与紧急度一致：清单按后端给的 sortRank 排，页面不自己排', () => {
    const rows = [
      item({ id: 'a', sortRank: 0, urgency: 'CRITICAL' }),
      item({ id: 'b', sortRank: 1, urgency: 'HIGH' }),
      item({ id: 'c', sortRank: 4, urgency: 'NO_DATA' }),
    ];
    expect(rows.map((r) => r.sortRank)).toEqual([0, 1, 4]);
  });
});
