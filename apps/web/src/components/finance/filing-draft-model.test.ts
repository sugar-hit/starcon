import { describe, expect, it } from 'vitest';
import * as model from './filing-draft-model';
import {
  UNAVAILABLE,
  deductibleNote,
  describeCoverage,
  draftStatusLabel,
  draftTitle,
  filingCycleLabel,
  filingTaxTypeLabel,
  formatAmount,
  formatCount,
  formatEffectiveWindow,
  formatExchangeRate,
  formatMoment,
  formatPeriod,
  formatRate,
  unavailableSources,
} from './filing-draft-model';

/**
 * 12.6.1.2 申报底稿前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>「读不到」不是「零」</b>：null 一律显示「读不到」，**绝不**显示 0 ——
 *       0 会被读成「这个数是零」这个业务结论；</li>
 *   <li><b>多币种绝不相加</b>：模型**不提供**任何跨币种求和的函数，
 *       也不提供「总额」这个概念；</li>
 *   <li><b>产品边界写在页面上</b>：模型**不提供**任何「提交申报」「申报状态」的
 *       措辞 —— 本系统提供底稿，不替代税务申报动作；</li>
 *   <li><b>取数被截断必须说出来</b>：否则底稿会被当成「这个期间全部的销售」。</li>
 * </ol>
 */

describe('申报线与周期的可读名', () => {
  it('两条申报线各有可读名，不认识的原样返回', () => {
    expect(filingTaxTypeLabel('VAT')).toBe('增值税申报');
    expect(filingTaxTypeLabel('IOSS')).toBe('IOSS 申报');
    expect(filingTaxTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(filingTaxTypeLabel(null)).toBe('');
  });

  it('周期与状态各有可读名', () => {
    expect(filingCycleLabel('MONTHLY')).toBe('月度');
    expect(filingCycleLabel('QUARTERLY')).toBe('季度');
    expect(filingCycleLabel(null)).toBe('');
    expect(draftStatusLabel('GENERATED')).toBe('生效中');
    expect(draftStatusLabel('SUPERSEDED')).toBe('已被取代');
    expect(draftStatusLabel(null)).toBe('');
  });

  it('不提供任何「提交申报 / 已申报」的措辞 —— 系统不替代税务申报动作', () => {
    const labels = [
      filingTaxTypeLabel('VAT'),
      filingTaxTypeLabel('IOSS'),
      filingCycleLabel('MONTHLY'),
      filingCycleLabel('QUARTERLY'),
      draftStatusLabel('GENERATED'),
      draftStatusLabel('SUPERSEDED'),
    ];
    for (const label of labels) {
      expect(label).not.toContain('提交');
      expect(label).not.toContain('受理');
      expect(label).not.toContain('已申报');
      expect(label).not.toContain('待申报');
    }
  });
});

describe('「读不到」不是「零」', () => {
  it('金额为 null 时显示「读不到」，绝不显示 0', () => {
    expect(formatAmount(null)).toBe(UNAVAILABLE);
    expect(formatAmount(undefined)).toBe(UNAVAILABLE);
    expect(formatAmount(null)).not.toBe('0');
    expect(formatAmount(null)).not.toContain('0.00');
  });

  it('数量为 null 时显示「读不到」，绝不显示 0', () => {
    expect(formatCount(null)).toBe(UNAVAILABLE);
    expect(formatCount(undefined)).toBe(UNAVAILABLE);
    expect(formatCount(null)).not.toBe('0');
  });

  it('税率与汇率为 null 时显示「读不到」', () => {
    expect(formatRate(null)).toBe(UNAVAILABLE);
    expect(formatExchangeRate(null)).toBe(UNAVAILABLE);
  });

  it('真的拿到 0 时照常显示 0 —— 0 是一个真实的值', () => {
    expect(formatAmount(0)).toContain('0.00');
    expect(formatCount(0)).toBe('0');
    expect(formatRate(0)).toBe('0.00%');
  });

  it('可抵扣进项取不到时，说明用的是后端写明原因的那句话', () => {
    const line = {
      id: 'l1',
      draftId: 'd1',
      currency: 'EUR',
      taxableSales: 100,
      vatCollected: 19,
      iossOrderCount: null,
      iossCollected: null,
      deductibleInput: null,
      deductibleAvailable: false,
      deductibleNote: '🔴 无可抵扣进项数据',
      netPayable: null,
      taxRateId: null,
      taxCode: 'STANDARD',
      taxRate: 0.19,
      taxRateEffectiveFrom: null,
      taxRateEffectiveTo: null,
      exchangeRate: null,
      exchangeRateAsOf: null,
      exchangeRateSource: '',
      exchangeRateAvailable: false,
      convertedNetPayable: null,
      receivedAmount: null,
      orderCount: 1,
      note: '',
    };
    expect(deductibleNote(line)).toBe('🔴 无可抵扣进项数据');
    expect(deductibleNote({ ...line, deductibleAvailable: true })).toBe('已取到可抵扣进项');
  });
});

describe('多币种绝不相加', () => {
  it('模型不提供任何跨币种求和的函数', () => {
    // 汇总按币种分组呈现：每个币种一行，页面自己也不许把它们加成一个数。
    // 这里钉住「模型没有 total / sumTotal 这类出口」。
    const forbidden = ['total', 'sumtotal', 'sumby', 'sumof', 'grandtotal', 'allcurrency'];
    const exported = Object.keys(model);
    expect(exported.length).toBeGreaterThan(0);
    for (const name of exported) {
      const lower = name.toLowerCase();
      for (const word of forbidden) {
        expect(lower).not.toContain(word);
      }
    }
  });

  it('每个币种一行，各自带自己的金额', () => {
    const eur = { currency: 'EUR', taxableSales: 900, vatCollected: 190 };
    const usd = { currency: 'USD', taxableSales: 500, vatCollected: 95 };
    expect(eur.currency).not.toBe(usd.currency);
    expect(formatAmount(eur.taxableSales)).not.toBe(formatAmount(usd.taxableSales));
  });
});

describe('取数覆盖情况', () => {
  const base = {
    orderCount: 10,
    includedCount: 6,
    outOfScopeCount: 3,
    unmappedCount: 1,
    orderTruncated: false,
  };

  it('四类订单数都写进说明里', () => {
    const text = describeCoverage(base);
    expect(text).toContain('候选 10 单');
    expect(text).toContain('纳入本辖区 6 单');
    expect(text).toContain('归属到其它辖区 3 单');
    expect(text).toContain('归属不到国家一级辖区 1 单');
  });

  it('被截断时必须说出来 —— 否则底稿会被当成该期间全部的销售', () => {
    const text = describeCoverage({ ...base, orderTruncated: true });
    expect(text).toContain('截断');
    expect(text).toContain('不是该期间的全部销售');
  });
});

describe('数据来源可用性', () => {
  it('只列出读不到的来源，不把「读不到」说成「没有数据」', () => {
    const sources = [
      { key: 'TAX_RATE', module: '12.6.1.1', name: '按生效时间取税率', available: true, ownerService: 'finance', detail: '' },
      { key: 'IOSS_ORDER_LIST', module: '16.3.2.1', name: 'IOSS 订单清单', available: false, ownerService: 'channel', detail: '🔴 读不到 IOSS 订单清单' },
    ];
    const missing = unavailableSources(sources);
    expect(missing).toHaveLength(1);
    expect(missing[0]?.key).toBe('IOSS_ORDER_LIST');
    expect(missing[0]?.detail).toContain('读不到');
  });
});

describe('展示口径', () => {
  it('期间与时刻的展示', () => {
    expect(formatPeriod('2026-01-01', '2026-01-31')).toBe('2026-01-01 ~ 2026-01-31');
    expect(formatPeriod(null, '2026-01-31')).toBe('—');
    expect(formatMoment('2026-02-01T09:30:00Z')).toBe('2026-02-01 09:30');
    expect(formatMoment(null)).toBe('—');
  });

  it('生效区间为空结束时间 = 一直生效，不是「没填」', () => {
    expect(formatEffectiveWindow('2026-01-01T00:00:00Z', null)).toContain('一直生效');
    expect(formatEffectiveWindow('2026-01-01T00:00:00Z', '2026-07-01T00:00:00Z')).toContain('→');
  });

  it('税率只做单位换算', () => {
    expect(formatRate(0.19)).toBe('19.00%');
    expect(formatRate(0.075)).toBe('7.50%');
  });

  it('底稿标题：辖区 + 申报线 + 期间', () => {
    expect(
      draftTitle({
        jurisdictionCode: 'EU-DE',
        jurisdictionName: '德国',
        taxType: 'VAT',
        periodStart: '2026-01-01',
        periodEnd: '2026-01-31',
      }),
    ).toBe('德国 · 增值税申报 · 2026-01-01 ~ 2026-01-31');
  });
});
