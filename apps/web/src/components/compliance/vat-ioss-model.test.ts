import { describe, expect, it } from 'vitest';
import {
  FILING_SCOPES,
  REASON_CODES,
  emptyStateKind,
  emptyStateText,
  filingStatusLabel,
  formatAmount,
  groupTotalsByCurrency,
  nextFilingActions,
  reasonLabel,
  scopeCounts,
  scopeDescription,
  scopeLabel,
  splitByScope,
  taxRateDisplay,
  type VatIossStatisticsRow,
} from './vat-ioss-model';

/**
 * 16.3.2.1 VAT/IOSS 前端模型的**纯函数**断言。
 *
 * 🔴 守的是蓝图那句加粗句：
 * 「**IOSS 订单与一般 VAT 订单必须区分统计**，混在一起申报是最常见的错误。」
 *
 * 页面上的错法有三种，这三组用例各堵一种：
 *
 * 1. **把 IOSS 与一般 VAT 加在一起显示一个总数**（颜色区分不算数）；
 * 2. **把不同币种的金额加在一起**（EUR + USD 不是钱）；
 * 3. **把「读不到」渲染成 0%**（0% 看起来像免税，而实际是没读到）。
 */

function row(
  scope: string,
  currency: string,
  orderCount: number,
  taxBase: string,
  taxAmount: string,
): VatIossStatisticsRow {
  return {
    filingScope: scope,
    filingScopeLabel: scope,
    currency,
    orderCount,
    goodsAmount: taxBase,
    shippingAmount: '0.000000',
    taxBase,
    taxAmount,
  };
}

describe('16.3.2.1 分档：IOSS 与一般 VAT 必须分开', () => {
  it('四档各自独立，判不了不并进任何一档', () => {
    const rows = [
      row('IOSS', 'EUR', 3, '300.000000', '57.000000'),
      row('GENERAL_VAT', 'EUR', 5, '900.000000', '171.000000'),
      row('OUT_OF_SCOPE', 'EUR', 2, '200.000000', '0.000000'),
      row('UNRESOLVED', 'EUR', 1, '100.000000', '0.000000'),
    ];
    const split = splitByScope(rows);
    expect(split.IOSS).toHaveLength(1);
    expect(split.GENERAL_VAT).toHaveLength(1);
    expect(split.OUT_OF_SCOPE).toHaveLength(1);
    expect(split.UNRESOLVED).toHaveLength(1);

    const counts = scopeCounts(rows);
    expect(counts.IOSS).toBe(3);
    expect(counts.GENERAL_VAT).toBe(5);
    expect(counts.OUT_OF_SCOPE).toBe(2);
    // 🔴 「判不了」独立计数 —— 不并进 IOSS，也不并进不在范围
    expect(counts.UNRESOLVED).toBe(1);
    expect(counts.IOSS + counts.GENERAL_VAT).not.toBe(11);
  });

  it('分档值域是闭合的四档，且每档都有人话', () => {
    expect(FILING_SCOPES).toEqual(['IOSS', 'GENERAL_VAT', 'OUT_OF_SCOPE', 'UNRESOLVED']);
    for (const scope of FILING_SCOPES) {
      expect(scopeLabel(scope)).not.toBe('');
      expect(scopeLabel(scope)).not.toBe('未知分档');
      expect(scopeDescription(scope)).not.toBe('');
    }
    expect(scopeDescription('GENERAL_VAT')).toContain('不要');
  });
});

describe('16.3.2.1 金额：不同币种绝不直接相加', () => {
  it('按币种分组，EUR 与 USD 各算各的', () => {
    const totals = groupTotalsByCurrency([
      row('IOSS', 'EUR', 2, '200.000000', '38.000000'),
      row('IOSS', 'USD', 3, '300.000000', '0.000000'),
      row('GENERAL_VAT', 'EUR', 1, '150.000000', '28.500000'),
    ]);
    expect(totals).toHaveLength(2);
    const eur = totals.find((t) => t.currency === 'EUR');
    const usd = totals.find((t) => t.currency === 'USD');
    expect(eur?.orderCount).toBe(3);
    expect(eur?.taxBase).toBe('350.000000');
    expect(usd?.orderCount).toBe(3);
    expect(usd?.taxBase).toBe('300.000000');
    // 🔴 绝不存在一个「总额」把 350 EUR + 300 USD 加在一起
    expect(totals.some((t) => t.currency === '')).toBe(false);
    expect(totals.map((t) => t.currency)).toEqual(['EUR', 'USD']);
  });

  it('金额显示永远带币种', () => {
    expect(formatAmount('19.000000', 'EUR')).toBe('EUR 19.00');
    expect(formatAmount('0', 'USD')).toBe('USD 0.00');
  });
});

describe('16.3.2.1 税率读不到 ≠ 0%', () => {
  it('空税率显示「读不到」，绝不显示 0.00%', () => {
    const shown = taxRateDisplay('', 'UNAVAILABLE');
    expect(shown).toContain('读不到');
    // 🔴 判据是「**渲染出来的是一个税率**」，不是「文案里有没有 0% 这两个字」——
    //    「不是 0%」这句话本身就得含 0%。
    expect(shown).not.toMatch(/\d+(\.\d+)?%(\s|$)/);
    expect(shown).not.toContain('0.00%');
  });

  it('有税率时按百分比显示', () => {
    expect(taxRateDisplay('0.190000', 'FINANCE')).toBe('19.00%');
  });
});

describe('16.3.2.1 判定原因词表闭合', () => {
  it('每个原因都有人话', () => {
    for (const code of REASON_CODES) {
      expect(reasonLabel(code)).not.toBe('');
      expect(reasonLabel(code)).not.toBe('未知原因');
    }
  });
});

describe('16.3.2.1 申报状态只能前进', () => {
  it('DRAFT → DRAFTED → CONFIRMED，回退没有入口', () => {
    expect(nextFilingActions('DRAFT')).toEqual(['draft']);
    expect(nextFilingActions('DRAFTED')).toEqual(['confirm']);
    expect(nextFilingActions('CONFIRMED')).toEqual([]);
    expect(filingStatusLabel('DRAFT')).toContain('待提交');
    expect(filingStatusLabel('DRAFTED')).toContain('已出底稿');
    expect(filingStatusLabel('CONFIRMED')).toContain('已确认');
  });
});

describe('16.3.2.1 三态空态各不相同', () => {
  it('没识别过 / 筛选无结果 / 这一期没底稿，说的话不一样', () => {
    const never = emptyStateKind({ everSynced: false, filtered: false, hasScopeFilter: false });
    const filtered = emptyStateKind({ everSynced: true, filtered: true, hasScopeFilter: true });
    const noDraft = emptyStateKind({ everSynced: true, filtered: false, hasScopeFilter: false });

    expect(never).toBe('never-synced');
    expect(filtered).toBe('filtered');
    expect(noDraft).toBe('no-draft');
    expect(emptyStateText(never)).not.toBe(emptyStateText(filtered));
    expect(emptyStateText(filtered)).not.toBe(emptyStateText(noDraft));
    expect(emptyStateText(never)).toContain('识别');
  });
});
