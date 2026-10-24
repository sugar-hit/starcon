import { describe, expect, it } from 'vitest';
import type { FinanceBankReconReportRow } from '../../../../../packages/api-contracts/src/index';
import {
  BANK_RECON_OPTIONAL_COLUMNS,
  BANK_RECON_REQUIRED_COLUMNS,
  bankReconAdjustmentRows,
  bankReconCsvTemplate,
  bankReconDifferenceNote,
  bankReconDirectionLabel,
  bankReconDocumentTypeLabel,
  bankReconRuleTypeLabel,
  bankReconSideLabel,
  bankReconSourceTypeLabel,
  bankReconStatusLabel,
  bankReconUnmatchedStatusLabel,
  formatBankReconAmount,
  formatBankReconDate,
  formatBankReconMoney,
  formatBankReconTime,
} from './bank-reconciliation-model';

/**
 * 12.7.1.2 银行对账前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何业务算术；</li>
 *   <li>「算不出」必须能分开（服务端给 null，页面显示「—」）——
 *       账面余额读不到时差额**算不出来**，显示 0 会被读成「账是平的」；</li>
 *   <li>可读名取**服务端下发**的值，页面不写死一份（词表会变，两处实现迟早分叉）；</li>
 *   <li>对账报告的**调节项只算待处理的未匹配项** ——
 *       已认领 / 已排除的差异已被人工解决，不该再进调节表。</li>
 * </ol>
 */

describe('可读名一律取服务端下发的标签', () => {
  it('有服务端标签就用服务端标签', () => {
    expect(bankReconStatusLabel('MATCHED', '已匹配')).toBe('已匹配');
    expect(bankReconDirectionLabel('IN', '收入')).toBe('收入');
    expect(bankReconRuleTypeLabel('REFERENCE_EXACT', '单号精确匹配')).toBe('单号精确匹配');
    expect(bankReconSourceTypeLabel('RECEIPT', '收款单')).toBe('收款单');
    expect(bankReconSideLabel('BANK', '银行侧')).toBe('银行侧');
    expect(bankReconUnmatchedStatusLabel('OPEN', '待处理')).toBe('待处理');
    expect(bankReconDocumentTypeLabel('RECEIPT', '收款单')).toBe('收款单');
  });

  it('没有服务端标签时兜底成原值，页面不自己编', () => {
    expect(bankReconStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(bankReconStatusLabel('SOMETHING_NEW', '')).toBe('SOMETHING_NEW');
    expect(bankReconStatusLabel('SOMETHING_NEW', '   ')).toBe('SOMETHING_NEW');
  });
});

describe('金额的原样展示', () => {
  it('4 位小数，不做任何换算', () => {
    expect(formatBankReconAmount(1234.5)).toBe('1234.5000');
    expect(formatBankReconAmount(0)).toBe('0.0000');
    expect(formatBankReconAmount(-12.3456)).toBe('-12.3456');
  });

  it('「算不出」（null / undefined）显示破折号而不是 0', () => {
    expect(formatBankReconAmount(null)).toBe('—');
    expect(formatBankReconAmount(undefined)).toBe('—');
    expect(formatBankReconMoney(null, 'USD')).toBe('—');
  });

  it('金额 + 币种拼在一起', () => {
    expect(formatBankReconMoney(1500.5, 'USD')).toBe('1500.5000 USD');
  });
});

describe('日期与时刻原样展示', () => {
  it('原样给出，不做本地时区换算', () => {
    expect(formatBankReconDate('2026-10-01')).toBe('2026-10-01');
    expect(formatBankReconTime('2026-10-05T02:00:00Z')).toBe('2026-10-05T02:00:00Z');
  });

  it('空值显示破折号', () => {
    expect(formatBankReconDate('')).toBe('—');
    expect(formatBankReconDate(null)).toBe('—');
    expect(formatBankReconTime(undefined)).toBe('—');
  });
});

describe('导入模板', () => {
  it('表头包含全部必需的列', () => {
    const header = bankReconCsvTemplate().split('\n')[0] ?? '';
    for (const column of BANK_RECON_REQUIRED_COLUMNS) {
      expect(header).toContain(column);
    }
  });

  it('模板把可选列也列出来（缺失它们是允许的，列出来只是告诉用户长什么样）', () => {
    const header = bankReconCsvTemplate().split('\n')[0] ?? '';
    for (const column of BANK_RECON_OPTIONAL_COLUMNS) {
      expect(header).toContain(column);
    }
    // 可选列的个数少于必需列 —— 少了它们照样能导进去。
    expect(BANK_RECON_OPTIONAL_COLUMNS.length).toBeLessThan(BANK_RECON_REQUIRED_COLUMNS.length);
  });

  it('样例行的时间带时区偏移（没有偏移会被服务端拒收）', () => {
    const sample = bankReconCsvTemplate().split('\n')[1] ?? '';
    expect(sample).toMatch(/Z$|[+-]\d{2}:\d{2}/);
  });
});

describe('对账报告的余额调节表', () => {
  const base: FinanceBankReconReportRow = {
    id: 'r1',
    sessionId: 's1',
    reconNo: 'BR-1',
    currency: 'USD',
    periodStart: '2026-10-01',
    periodEnd: '2026-10-31',
    statementLineCount: 2,
    systemLineCount: 1,
    matchedCount: 1,
    unmatchedBankCount: 1,
    unmatchedSystemCount: 0,
    claimedCount: 0,
    excludedCount: 0,
    matchedAmount: 1000,
    statementInAmount: 1200,
    statementOutAmount: 0,
    systemInAmount: 1000,
    systemOutAmount: 0,
    unmatchedBankInAmount: 200,
    unmatchedBankOutAmount: 0,
    unmatchedSystemInAmount: 0,
    unmatchedSystemOutAmount: 0,
    statementOpeningBalance: 0,
    statementClosingBalance: 1200,
    bookClosingBalance: 1000,
    bookBalanceAvailable: true,
    adjustedBalance: 1400,
    difference: 400,
    generatedBy: 'u1',
    generatedAt: '2026-10-31T00:00:00Z',
  };

  it('调节项逐条列出，方向符号正确', () => {
    const rows = bankReconAdjustmentRows(base);
    const labels = rows.map((r) => r.label);
    expect(labels).toContain('银行对账单期末余额');
    expect(labels).toContain('加：银行已收、我方账上没有');
    expect(labels).toContain('减：银行已付、我方账上没有');
    expect(labels).toContain('＝ 调节后余额');
    expect(rows.find((r) => r.label === '加：银行已收、我方账上没有')?.amount).toBe('200.0000 USD');
    expect(rows.find((r) => r.label === '＝ 调节后余额')?.amount).toBe('1400.0000 USD');
    expect(rows.find((r) => r.label === '差额（调节后余额 − 我方账面余额）')?.amount).toBe('400.0000 USD');
  });

  it('账面余额读不到时调节后余额与差额显示破折号，而不是 0', () => {
    const rows = bankReconAdjustmentRows({
      ...base,
      bookBalanceAvailable: false,
      bookClosingBalance: null,
      adjustedBalance: null,
      difference: null,
    });
    expect(rows.find((r) => r.label === '＝ 调节后余额')?.amount).toBe('—');
    expect(rows.find((r) => r.label === '差额（调节后余额 − 我方账面余额）')?.amount).toBe('—');
    expect(rows.find((r) => r.label === '我方账面期末余额')?.amount).toContain('读不到');
  });

  it('差额为 0 才是「账平了」', () => {
    expect(bankReconDifferenceNote({ ...base, difference: 0 })).toContain('账是平的');
  });

  it('读不到账面余额时口径说明必须说「算不出来」而不是「账平了」', () => {
    const note = bankReconDifferenceNote({
      ...base,
      bookBalanceAvailable: false,
      bookClosingBalance: null,
      adjustedBalance: null,
      difference: null,
    });
    expect(note).toContain('算不出来');
    // 「账是平的」这句话只允许出现在差额真的为 0 的时候。
    expect(note).not.toContain('账是平的');
  });

  it('差额为正与为负的说法不一样', () => {
    expect(bankReconDifferenceNote({ ...base, difference: 400 })).toContain('为正');
    expect(bankReconDifferenceNote({ ...base, difference: -400 })).toContain('为负');
  });
});
