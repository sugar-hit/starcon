import { describe, expect, it } from 'vitest';
import {
  blockedCapabilities,
  concentrationTone,
  efficiencyStatusLabel,
  factKindLabel,
  fmt,
  fxStatusLabel,
  moneyText,
  NO_DATA_TEXT,
  onTimeStatusLabel,
  percentText,
  purchaseAnalyticsQuery,
  shareText,
  sourceTypeLabel,
  varianceStatusLabel,
} from './purchase-analytics-model';

/**
 * 6.5.2.1 采购分析前端模型的展示口径断言。
 *
 * <p>这些口径是「口径写在响应里、算不出来不是 0」在界面上的落点，
 * 断言它们是为了让口径改坏时**先红在这里**，而不是等到页面上没人看出来。
 */

describe('采购分析展示口径', () => {
  it('取不到的数显示「无数据」，绝不显示 0', () => {
    expect(fmt(null)).toBe(NO_DATA_TEXT);
    expect(fmt(undefined)).toBe(NO_DATA_TEXT);
    expect(moneyText(null, 'USD')).toBe(NO_DATA_TEXT);
    expect(percentText(null)).toBe(NO_DATA_TEXT);
    expect(shareText(null)).toBe(NO_DATA_TEXT);
    // 0 是真的 0，与「读不到」必须分得开。
    expect(fmt(0)).toBe('0');
    expect(percentText(0)).toBe('0.00%');
  });

  it('金额带币种，币种不能丢（跨币种不合并）', () => {
    expect(moneyText(250, 'USD')).toBe('250 USD');
    expect(moneyText(250, null)).toBe('250');
  });

  it('事实种类与来源各有各的名字，不折叠', () => {
    expect(factKindLabel('COST')).toBe('采购成本');
    expect(factKindLabel('RECEIPT')).toBe('实收量');
    expect(factKindLabel('PLAN')).toBe('计划量');
    expect(sourceTypeLabel('ARRIVAL_RECEIPT')).toContain('到货流水');
    expect(sourceTypeLabel('GOODS_RECEIPT')).toContain('收货单');
    expect(sourceTypeLabel('SUPPLIER_CLAIM')).toContain('索赔');
  });

  it('汇率未登记是一个独立状态，不是「已折算」', () => {
    expect(fxStatusLabel('APPLIED')).toBe('按汇率快照折算');
    expect(fxStatusLabel('SAME_CURRENCY')).toBe('原币即本位币');
    expect(fxStatusLabel('UNSET')).toContain('未折算');
    expect(fxStatusLabel('UNSET')).not.toBe(fxStatusLabel('APPLIED'));
  });

  it('准时状态里「未到货」与「逾期」是两个状态', () => {
    expect(onTimeStatusLabel('ON_TIME')).toBe('准时');
    expect(onTimeStatusLabel('LATE')).toBe('逾期');
    expect(onTimeStatusLabel('PENDING')).toBe('未到货');
    expect(onTimeStatusLabel('LATE')).not.toBe(onTimeStatusLabel('PENDING'));
  });

  it('效率与价格波动的状态都有中文名', () => {
    expect(efficiencyStatusLabel('PLAN_ONLY')).toBe('仅计划侧口径');
    expect(efficiencyStatusLabel('NO_RECEIPT')).toBe('无实收');
    expect(varianceStatusLabel('NO_PREVIOUS_DATA')).toBe('上期无数据');
    expect(varianceStatusLabel('UP')).toBe('上涨');
  });

  it('集中度按 HHI 分级：越高越危险', () => {
    expect(concentrationTone(0.5)).toBe('danger');
    expect(concentrationTone(0.2)).toBe('warning');
    expect(concentrationTone(0.05)).toBe('info');
    expect(concentrationTone(null)).toBe('info');
  });

  it('筛选参数进查询串，空的不进', () => {
    const query = purchaseAnalyticsQuery({
      from: '2026-03-01',
      to: '2026-03-31',
      currency: 'USD',
      keyword: ' PO-1 ',
      page: 0,
      size: 20,
    });
    expect(query).toContain('from=2026-03-01');
    expect(query).toContain('to=2026-03-31');
    expect(query).toContain('currency=USD');
    expect(query).toContain('keyword=PO-1');
    expect(query).toContain('page=0');
    expect(query).toContain('size=20');
    expect(query).not.toContain('shopCode');
    expect(purchaseAnalyticsQuery({})).toBe('');
  });

  it('能力声明里不可用的那些会被挑出来（页面要逐条点名）', () => {
    const sections = blockedCapabilities({
      sections: [
        {
          key: 'ARRIVAL_RECEIPT',
          label: '到货流水',
          available: true,
          ownerModule: null,
          blockedReason: null,
          impact: null,
          detail: '',
          evidence: null,
        },
        {
          key: 'PURCHASE_ORDER',
          label: '采购单原单',
          available: false,
          ownerModule: '6.3.1.1',
          blockedReason: '未交付',
          impact: '下钻到不了采购单原单',
          detail: '',
          evidence: null,
        },
      ],
    } as never);
    expect(sections).toHaveLength(1);
    expect(sections[0]?.key).toBe('PURCHASE_ORDER');
    expect(sections[0]?.ownerModule).toBe('6.3.1.1');
    expect(blockedCapabilities(null)).toEqual([]);
  });
});
