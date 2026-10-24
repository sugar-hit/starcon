import type {
  FinanceBankReconCapabilities,
  FinanceBankReconClaimInput,
  FinanceBankReconClaimPage,
  FinanceBankReconExcludeInput,
  FinanceBankReconEventPage,
  FinanceBankReconImportInput,
  FinanceBankReconImportResult,
  FinanceBankReconMatchPage,
  FinanceBankReconMatchResult,
  FinanceBankReconOptions,
  FinanceBankReconReportRow,
  FinanceBankReconRuleInput,
  FinanceBankReconRuleRow,
  FinanceBankReconRulePage,
  FinanceBankReconSessionCreateInput,
  FinanceBankReconSessionPage,
  FinanceBankReconSessionRow,
  FinanceBankReconStatementLinePage,
  FinanceBankReconSystemLinePage,
  FinanceBankReconUnmatchedPage,
  FinanceBankReconUnmatchedRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.7.1.2 银行对账的前端模型。
 *
 * <p>蓝图 12-finance.md：「导入银行/支付平台流水与系统记录自动匹配，输出未匹配项，支持人工认领与说明，生成对账报告。」输入：「流水文件（导入）；系统收支记录；匹配规则。」输出：「匹配结果 + 未匹配清单 + 对账报告。」关系：「上游 = 12.7.1.1、12.2；下游 = 12.3.2.1 对账。<b>未匹配项要能认领到具体单据</b>，否则余额永远平不了——这是财务日常最高频的工作。」
 *
 * <p><b>命名空间</b>：{@code finance/bank-reconciliation}，与同 L3 的 12.7.1.1 账户管理
 * （{@code finance/fund-accounts}）、12.7.2.1 资金分析（{@code finance/fund-analysis}）
 * **刻意不共用前缀** —— 那是别的模块的命名空间，不要互相调用。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：页面**不做任何业务算术**。差额、调节后余额都由服务端算好。</li>
 *   <li><b>「算不出」必须能分开</b>：服务端给 {@code null}（账面余额读不到 ⇒ 差额算不出），
 *       页面显示「—」而**不是 0** —— 显示 0 会被读成「账是平的」。</li>
 *   <li><b>可读名取服务端下发</b>：状态 / 方向 / 规则类型 / 来源类型的标签都来自服务端，
 *       页面**不**自己写死一份（词表会变，两处实现迟早分叉）。</li>
 *   <li><b>导入告警必须看得见</b>：每一行没进来的流水都带行号与原因 ——
 *       页面不把「跳过 N 行」折叠成一句。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「还没建批次」与「筛选无结果」。</li>
 * </ul>
 */

const base = 'finance/bank-reconciliation';

/** 对账批次列表的筛选条件。 */
export interface BankReconSessionParams {
  accountId?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些真接通、哪些没交付、归谁。 */
export function fetchBankReconCapabilities() {
  return api<FinanceBankReconCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（受控词表 + 真实账户 + 本租户的真实规则）。 */
export function fetchBankReconOptions() {
  return api<FinanceBankReconOptions>(`${base}/options`);
}

/** 对账批次列表（真分页 + 真筛选 + 真搜索）。 */
export function fetchBankReconSessions(params: BankReconSessionParams = {}, page = 0, size = 20) {
  return api<FinanceBankReconSessionPage>(
    `${base}/sessions${query({
      accountId: params.accountId,
      status: params.status,
      keyword: params.keyword,
      page,
      size,
    })}`,
  );
}

/** 对账批次详情。 */
export function fetchBankReconSession(id: string) {
  return api<FinanceBankReconSessionRow>(`${base}/sessions/${id}`);
}

/**
 * 建对账批次。
 *
 * <p>⚠️ 账户必须**存在且启用中**；币种由服务端**继承账户**（前端不填币种）。
 */
export function createBankReconSession(input: FinanceBankReconSessionCreateInput) {
  return api<FinanceBankReconSessionRow>(`${base}/sessions`, 'POST', input);
}

/**
 * 导入银行 / 支付平台流水。
 *
 * <p>⚠️ 服务端**逐行解析**：不合法的行会进响应的 {@code warnings}（带行号与原因），
 * 前端**必须**把它们显示出来 —— 只报「导入 N 行」会让人以为没丢东西。
 */
export function importBankReconStatement(id: string, input: FinanceBankReconImportInput) {
  return api<FinanceBankReconImportResult>(`${base}/sessions/${id}/import`, 'POST', input);
}

/** 跑自动匹配（🔴 服务端禁止「金额相同即匹配」，页面不自己配对）。 */
export function runBankReconMatch(id: string) {
  return api<FinanceBankReconMatchResult>(`${base}/sessions/${id}/match`, 'POST');
}

/** 银行流水行（真分页）。 */
export function fetchBankReconStatementLines(id: string, page = 0, size = 20) {
  return api<FinanceBankReconStatementLinePage>(`${base}/sessions/${id}/statement-lines${query({ page, size })}`);
}

/** 系统记录快照（真分页）。 */
export function fetchBankReconSystemLines(id: string, page = 0, size = 20) {
  return api<FinanceBankReconSystemLinePage>(`${base}/sessions/${id}/system-lines${query({ page, size })}`);
}

/** 匹配结果（真分页）。 */
export function fetchBankReconMatches(id: string, page = 0, size = 20) {
  return api<FinanceBankReconMatchPage>(`${base}/sessions/${id}/matches${query({ page, size })}`);
}

/** 未匹配清单（真分页 + 真筛选）。 */
export function fetchBankReconUnmatched(
  id: string,
  status?: string | undefined,
  side?: string | undefined,
  page = 0,
  size = 20,
) {
  return api<FinanceBankReconUnmatchedPage>(
    `${base}/sessions/${id}/unmatched${query({ status, side, page, size })}`,
  );
}

/**
 * 人工认领到具体单据。
 *
 * <p>⚠️ 认领目标必须**真的存在**（服务端校验，认领不存在的单据会被拒）。
 */
export function claimBankReconUnmatched(
  id: string,
  unmatchedId: string,
  input: FinanceBankReconClaimInput,
) {
  return api<FinanceBankReconUnmatchedRow>(
    `${base}/sessions/${id}/unmatched/${unmatchedId}/claim`,
    'POST',
    input,
  );
}

/** 排除一条未匹配项（必须写理由）。 */
export function excludeBankReconUnmatched(
  id: string,
  unmatchedId: string,
  input: FinanceBankReconExcludeInput,
) {
  return api<FinanceBankReconUnmatchedRow>(
    `${base}/sessions/${id}/unmatched/${unmatchedId}/exclude`,
    'POST',
    input,
  );
}

/** 认领 / 说明记录（真分页；append-only 举证）。 */
export function fetchBankReconClaims(id: string, unmatchedId?: string | undefined, page = 0, size = 20) {
  return api<FinanceBankReconClaimPage>(`${base}/sessions/${id}/claims${query({ unmatchedId, page, size })}`);
}

/** 生成对账报告并确认对账（确认是**终态**）。 */
export function reconcileBankReconSession(id: string) {
  return api<FinanceBankReconReportRow>(`${base}/sessions/${id}/reconcile`, 'POST');
}

/** 读已生成的对账报告（还没生成 ⇒ 404）。 */
export function fetchBankReconReport(id: string) {
  return api<FinanceBankReconReportRow>(`${base}/sessions/${id}/report`);
}

/** 事件流（真分页；append-only 举证）。 */
export function fetchBankReconEvents(id: string, page = 0, size = 20) {
  return api<FinanceBankReconEventPage>(`${base}/sessions/${id}/events${query({ page, size })}`);
}

/** 匹配规则列表（真分页）。 */
export function fetchBankReconRules(page = 0, size = 20) {
  return api<FinanceBankReconRulePage>(`${base}/rules${query({ page, size })}`);
}

/** 新建一条匹配规则。 */
export function createBankReconRule(input: FinanceBankReconRuleInput) {
  return api<FinanceBankReconRuleRow>(`${base}/rules`, 'POST', input);
}

/** 改一条匹配规则（⚠️ **规则编码不可改** —— 编码是历史匹配的追溯依据）。 */
export function updateBankReconRule(id: string, input: FinanceBankReconRuleInput) {
  return api<FinanceBankReconRuleRow>(`${base}/rules/${id}`, 'PUT', input);
}

// ================================================================ 展示口径

/** 取服务端下发的可读名；没有就兜底成原值（不自己编）。 */
function serverLabel(code: string | null | undefined, fallback?: string | null | undefined): string {
  if (fallback && fallback.trim() !== '') return fallback;
  return code ?? '';
}

/** 对账批次状态的可读名。 */
export function bankReconStatusLabel(code: string, fallback?: string | null): string {
  return serverLabel(code, fallback);
}

/** 资金方向的可读名。 */
export function bankReconDirectionLabel(code: string, fallback?: string | null): string {
  return serverLabel(code, fallback);
}

/** 匹配规则类型的可读名。 */
export function bankReconRuleTypeLabel(code: string, fallback?: string | null): string {
  return serverLabel(code, fallback);
}

/** 系统记录来源类型的可读名。 */
export function bankReconSourceTypeLabel(code: string, fallback?: string | null): string {
  return serverLabel(code, fallback);
}

/** 未匹配项来自哪一侧的可读名。 */
export function bankReconSideLabel(code: string, fallback?: string | null): string {
  return serverLabel(code, fallback);
}

/** 未匹配项处理状态的可读名。 */
export function bankReconUnmatchedStatusLabel(code: string, fallback?: string | null): string {
  return serverLabel(code, fallback);
}

/** 认领到的单据类型的可读名。 */
export function bankReconDocumentTypeLabel(code: string, fallback?: string | null): string {
  return serverLabel(code, fallback);
}

/** 金额的原样展示（**不做任何换算**，4 位小数）。 */
export function formatBankReconAmount(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return '—';
  return n.toFixed(4);
}

/** 金额 + 币种；「算不出」（null）显示破折号而不是 0。 */
export function formatBankReconMoney(
  value: number | string | null | undefined,
  currency?: string | null,
): string {
  const amount = formatBankReconAmount(value);
  if (amount === '—') return '—';
  return currency ? `${amount} ${currency}` : amount;
}

/** 日期（原样 ISO 日期）。 */
export function formatBankReconDate(value: string | null | undefined): string {
  return value && value.trim() !== '' ? value : '—';
}

/** 时刻（原样 ISO 时刻；**不**做本地时区换算 —— 服务端给的就是事实时刻）。 */
export function formatBankReconTime(value: string | null | undefined): string {
  return value && value.trim() !== '' ? value : '—';
}

/**
 * 导入模板（CSV 表头 + 一行样例）。
 *
 * <p>⚠️ 这是**模板**，不是假数据：页面用它告诉用户该导出成什么形状，
 * 真正入库的每一行都来自用户自己的文件。
 */
export function bankReconCsvTemplate(): string {
  return [
    'occurredAt,direction,amount,currency,bankReference,externalReference,counterparty,memo',
    '2026-10-05T10:00:00+08:00,IN,1200.50,USD,BK-20261005-001,SO-1001,ACME Ltd,平台回款',
  ].join('\n');
}

/** 导入必填列（页面据此提示用户哪些列不能少）。 */
export const BANK_RECON_REQUIRED_COLUMNS = [
  'occurredAt',
  'direction',
  'amount',
  'currency',
  'bankReference',
] as const;

/** 导入可选列。 */
export const BANK_RECON_OPTIONAL_COLUMNS = ['externalReference', 'counterparty', 'memo'] as const;

/**
 * 余额调节表的一行（页面渲染用）。
 *
 * <p>🔴 调节项**只算「待处理」的未匹配项**（已认领 / 已排除的差异已被人工解决，
 * 不该再进调节表）—— 服务端算好的数，这里只负责把它排成表。
 */
export interface BankReconAdjustmentRow {
  label: string;
  amount: string;
  sign: '+' | '-' | '=' | '';
}

/** 把对账报告排成「余额调节表」的行序列。 */
export function bankReconAdjustmentRows(
  report: FinanceBankReconReportRow,
): BankReconAdjustmentRow[] {
  const rows: BankReconAdjustmentRow[] = [
    { label: '银行对账单期末余额', amount: formatBankReconMoney(report.statementClosingBalance, report.currency), sign: '' },
    {
      label: '加：银行已收、我方账上没有',
      amount: formatBankReconMoney(report.unmatchedBankInAmount, report.currency),
      sign: '+',
    },
    {
      label: '减：银行已付、我方账上没有',
      amount: formatBankReconMoney(report.unmatchedBankOutAmount, report.currency),
      sign: '-',
    },
    {
      label: '减：我方已收、银行账单上没有',
      amount: formatBankReconMoney(report.unmatchedSystemInAmount, report.currency),
      sign: '-',
    },
    {
      label: '加：我方已付、银行账单上没有',
      amount: formatBankReconMoney(report.unmatchedSystemOutAmount, report.currency),
      sign: '+',
    },
    {
      label: '＝ 调节后余额',
      amount: formatBankReconMoney(report.adjustedBalance, report.currency),
      sign: '=',
    },
    {
      label: '我方账面期末余额',
      amount: report.bookBalanceAvailable
        ? formatBankReconMoney(report.bookClosingBalance, report.currency)
        : '—（账面余额读不到）',
      sign: '',
    },
    {
      label: '差额（调节后余额 − 我方账面余额）',
      amount: formatBankReconMoney(report.difference, report.currency),
      sign: '=',
    },
  ];
  return rows;
}

/**
 * 差额的口径说明。
 *
 * <p>🔴 差额为 0 才叫「账平了」；读不到账面余额时差额**算不出**，
 * 页面必须如实说「算不出」，不能显示 0。
 */
export function bankReconDifferenceNote(report: FinanceBankReconReportRow): string {
  if (!report.bookBalanceAvailable) {
    return '我方账面余额读不到（12.7.1.1 的余额历史里没有这个账户在这个时点的记录），差额算不出来 —— 这不等于「账平了」。';
  }
  if (report.difference === null || report.difference === undefined) {
    return '差额算不出来。';
  }
  const diff = Number(report.difference);
  if (diff === 0) return '差额为 0，这一期账是平的。';
  return diff > 0
    ? `差额为正 ${formatBankReconMoney(report.difference, report.currency)}：银行侧比我方账面多这么多，通常是「银行已收、我方账上没有」还没认领。`
    : `差额为负 ${formatBankReconMoney(report.difference, report.currency)}：我方账面比银行侧多这么多，通常是「我方已记、银行还没到」还没认领。`;
}
