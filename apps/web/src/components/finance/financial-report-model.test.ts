import { describe, expect, it } from 'vitest';
import type {
  FinanceReportCheck,
  FinanceReportLedgerEntry,
  FinanceReportRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  formatLedgerEntryRef,
  formatReportAmount,
  formatReportDelta,
  formatReportMoney,
  groupRowsBySection,
  isReportTotalRow,
  reportCategoryLabel,
  reportCheckSummary,
  reportCheckTone,
  reportLineTypeLabel,
  reportSectionLabel,
  reportTypeLabel,
  toApiPath,
} from './financial-report-model';

/**
 * 12.5.2.2 财务报表的前端模型断言。
 *
 * <p>这几条守的是**界面纪律**，不是排版：
 *
 * <ul>
 *   <li>五张报表各有可读名，段落也各有可读名；</li>
 *   <li>金额**原样展示**（四位小数），页面不做任何业务算术；</li>
 *   <li>变动额正数带 + 号，零就是 0（不是「+0.0000」）；</li>
 *   <li>自检差额不为 0 是**危险**语气 —— 「报表与明细对不上」是财务系统的致命问题，
 *       不能用「警告」轻轻带过；</li>
 *   <li>段落分组只做**展示分组**，顺序沿用服务端给的行序（不自己重排）；</li>
 *   <li>钻取路径是服务端给的**真实路径**，前端只做「去掉开头的斜杠」这一件事。</li>
 * </ul>
 */

/** 造一行报表（只填断言用得到的字段）。 */
function row(overrides: Partial<FinanceReportRow>): FinanceReportRow {
  return {
    rowKey: 'USD|1001',
    section: 'ASSET',
    sectionLabel: '资产',
    lineCode: '1001',
    lineName: '库存现金',
    subjectCode: '1001',
    subjectName: '库存现金',
    currency: 'USD',
    category: 'ASSET',
    balanceDirection: 'DEBIT',
    openingDebit: 0,
    openingCredit: 0,
    openingBalance: 0,
    periodDebit: 100,
    periodCredit: 0,
    periodNet: 100,
    closingDebit: 100,
    closingCredit: 0,
    closingBalance: 100,
    comparisonPeriodNet: 0,
    comparisonClosingBalance: 0,
    deltaPeriodNet: 100,
    deltaClosingBalance: 100,
    voucherCount: 1,
    lineCount: 1,
    totalRow: false,
    drillPath: '/finance/financial-reports/drill?reportType=BALANCE_SHEET&lineCode=1001',
    ...overrides,
  };
}

describe('12.5.2.2 财务报表 · 前端模型', () => {
  it('五张报表各有可读名，未知值原样返回', () => {
    expect(reportTypeLabel('BALANCE_SHEET')).toBe('资产负债表');
    expect(reportTypeLabel('INCOME_STATEMENT')).toBe('利润表');
    expect(reportTypeLabel('CASH_FLOW')).toBe('现金流量表');
    expect(reportTypeLabel('TRIAL_BALANCE')).toBe('科目余额表');
    expect(reportTypeLabel('GENERAL_LEDGER')).toBe('明细账');
    expect(reportTypeLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('段落各有可读名；未分类必须叫「未分类」而不是被吞掉', () => {
    expect(reportSectionLabel('ASSET')).toBe('资产');
    expect(reportSectionLabel('LIABILITY')).toBe('负债');
    expect(reportSectionLabel('EQUITY')).toBe('所有者权益');
    expect(reportSectionLabel('RETAINED')).toBe('损益结转');
    expect(reportSectionLabel('REVENUE')).toBe('收入');
    expect(reportSectionLabel('EXPENSE')).toBe('成本与费用');
    expect(reportSectionLabel('OPERATING')).toBe('经营活动');
    expect(reportSectionLabel('UNCLASSIFIED')).toBe('未分类');
    expect(reportSectionLabel('CUSTOM')).toBe('CUSTOM');
  });

  it('金额原样展示四位小数，绝不在前端做算术', () => {
    expect(formatReportAmount(1234.5)).toBe('1234.5000');
    expect(formatReportAmount(0)).toBe('0.0000');
    expect(formatReportAmount(null)).toBe('—');
    expect(formatReportAmount(undefined)).toBe('—');
    expect(formatReportMoney(12.3456, 'USD')).toBe('12.3456 USD');
    expect(formatReportMoney(12.3456, '')).toBe('12.3456');
  });

  it('变动额正数带 + 号，零就是 0', () => {
    expect(formatReportDelta(100)).toBe('+100.0000');
    expect(formatReportDelta(0)).toBe('0.0000');
    expect(formatReportDelta(-100)).toBe('-100.0000');
    expect(formatReportDelta(null)).toBe('—');
  });

  it('自检差额不为 0 是危险语气 —— 报表与明细对不上是致命问题', () => {
    const balanced: FinanceReportCheck = {
      checkName: '借贷平衡（本期）',
      balanced: true,
      leftValue: 100,
      rightValue: 100,
      difference: 0,
      detail: '',
    };
    const broken: FinanceReportCheck = { ...balanced, balanced: false, difference: 60 };
    expect(reportCheckTone(balanced)).toBe('success');
    expect(reportCheckTone(broken)).toBe('danger');
    expect(reportCheckSummary(balanced)).toContain('差额 0.0000');
    expect(reportCheckSummary(broken)).toContain('差额 60.0000');
  });

  it('段落分组只做展示分组，顺序沿用服务端给的行序', () => {
    const rows = [
      row({ section: 'ASSET', sectionLabel: '资产', lineCode: '1001' }),
      row({ section: 'ASSET', sectionLabel: '资产', lineCode: '1002' }),
      row({ section: 'LIABILITY', sectionLabel: '负债', lineCode: '2202' }),
      row({ section: 'TOTAL', sectionLabel: '合计', lineCode: 'TOTAL:ASSET', totalRow: true }),
    ];
    const groups = groupRowsBySection(rows);
    expect(groups.map((g) => g.section)).toEqual(['ASSET', 'LIABILITY', 'TOTAL']);
    expect(groups[0]!.rows).toHaveLength(2);
    expect(groups[0]!.label).toBe('资产');
    // 合计行自带 totalRow 标记，页面据此加粗。
    expect(isReportTotalRow(groups[2]!.rows[0]!)).toBe(true);
    expect(isReportTotalRow(rows[0]!)).toBe(false);
  });

  it('钻取路径是服务端给的真实路径，前端只去掉开头的斜杠', () => {
    expect(
      toApiPath('/finance/financial-reports/drill?reportType=BALANCE_SHEET&lineCode=1001'),
    ).toBe('finance/financial-reports/drill?reportType=BALANCE_SHEET&lineCode=1001');
    expect(toApiPath('finance/financial-reports/drill')).toBe(
      'finance/financial-reports/drill',
    );
  });

  it('借贷方向与科目类别各有可读名；没登记的科目要说「未在科目表登记」', () => {
    expect(reportLineTypeLabel('DEBIT')).toBe('借');
    expect(reportLineTypeLabel('CREDIT')).toBe('贷');
    expect(reportCategoryLabel('ASSET')).toBe('资产');
    expect(reportCategoryLabel('PROFIT_LOSS')).toBe('损益');
    expect(reportCategoryLabel('')).toBe('未在科目表登记');
  });

  it('明细账逐笔的引用可读形态：日期 + 凭证号 + 行号', () => {
    const entry = {
      entryDate: '2026-10-05',
      voucherNo: 'VC-202610-0001',
      lineNo: 2,
    } as FinanceReportLedgerEntry;
    expect(formatLedgerEntryRef(entry)).toBe('2026-10-05 · VC-202610-0001 · 行 2');
  });
});
