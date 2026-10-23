import type {
  FinanceSettlementArrivalInput,
  FinanceSettlementCapabilities,
  FinanceSettlementCycleInput,
  FinanceSettlementCycleRow,
  FinanceSettlementDetail,
  FinanceSettlementDifference,
  FinanceSettlementImportInput,
  FinanceSettlementImportResult,
  FinanceSettlementInput,
  FinanceSettlementOptions,
  FinanceSettlementPage,
  FinanceSettlementRow,
  FinanceSettlementShopOption,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.3.1.1 结算单的前端模型。
 *
 * <p>蓝图 12-finance.md：「管理平台结算单：结算周期、店铺、平台账单汇总（销售额、退款、佣金、FBA 费用、
 * 广告费、赔付、其他调整）、结算金额、到账状态。」输出：「结算单 + 汇总项 + 明细行 + 与我方账的差额。」
 * 加粗句：「<b>平台账单是财务事实的重要外部来源，需保留原始文件与解析版本。</b>」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：结算金额与差额都由**服务端**算好，页面**不做任何前端算术**
 *       （USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>符号看得懂</b>：汇总项里的金额是「对结算金额的贡献」（正=增加、负=减少），
 *       页面必须把这个含义写在界面上，否则「退款 −100」会被读成「退款了 100 块」。</li>
 *   <li><b>上游没交付的事就说没交付</b>：平台结算报告自动同步（上游「卷 02 同步」未交付）
 *       **不提供**建单入口；自动对账任务（12.3.2.1）与利润（13）**未交付**，
 *       页面只显示**差额**，不提供任何「跑对账 / 看利润」的入口。</li>
 *   <li><b>导入的告警原样显示</b>：文件级错误整体失败，行级错误只跳过那一行 ——
 *       页面必须把 warnings 一条条显示出来，绝不能让它看起来像「跑过了但一条都没有」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有结算单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/settlement}，与同服务的 12.1.1.1（{@code finance/receivables}）、
 * 12.2.1.1（{@code finance/receipts}）以及卷 12 的其余模块刻意不共用前缀。
 */

const base = 'finance/settlement';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface SettlementParams {
  status?: string | undefined;
  shopId?: string | undefined;
  currency?: string | undefined;
  source?: string | undefined;
  platformCode?: string | undefined;
  periodFrom?: string | undefined;
  periodTo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
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

/** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
export function fetchSettlementCapabilities() {
  return api<FinanceSettlementCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchSettlementOptions() {
  return api<FinanceSettlementOptions>(`${base}/options`);
}

/** 店铺选项（来自上游 2 渠道与店铺 2.3.1.1）。 */
export function fetchSettlementShops() {
  return api<Array<FinanceSettlementShopOption>>(`${base}/shops`);
}

/** 结算周期配置列表（一家店一份）。 */
export function fetchSettlementCycles() {
  return api<Array<FinanceSettlementCycleRow>>(`${base}/cycles`);
}

/** 登记 / 改写一家店的结算周期配置。 */
export function upsertSettlementCycle(input: FinanceSettlementCycleInput) {
  return api<FinanceSettlementCycleRow>(`${base}/cycles`, 'POST', input);
}

/** 结算单列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchSettlements(params: SettlementParams = {}) {
  return api<FinanceSettlementPage>(`${base}${query({ ...params })}`);
}

/** 结算单详情：单头 + 汇总项 + 明细行 + 事件流 + 原始文件。 */
export function fetchSettlement(id: string) {
  return api<FinanceSettlementDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 「与我方账的差额」：平台侧金额 vs 我方应收（实时算出来的，不是快照）。 */
export function fetchSettlementDifference(id: string) {
  return api<FinanceSettlementDifference>(`${base}/${encodeURIComponent(id)}/difference`);
}

/**
 * 手工登记一张结算单（连明细行一起）。
 *
 * <p>⚠️ 「平台结算报告同步」当前**不可产生**（上游「卷 02 同步」未交付）：后端会拒绝并写明归属模块，
 * 页面也不提供这个入口。
 */
export function createSettlement(input: FinanceSettlementInput) {
  return api<FinanceSettlementRow>(`${base}`, 'POST', input);
}

/**
 * 平台账单导入（CSV / JSON）。
 *
 * <p>⚠️ 文件级错误后端**整体失败**（400）；行级错误只跳过那一行并逐条写进 warnings ——
 * 页面必须把 warnings 原样显示出来。
 */
export function importSettlementBill(input: FinanceSettlementImportInput) {
  return api<FinanceSettlementImportResult>(`${base}/import`, 'POST', input);
}

/** 登记到账：写实际到账时间与到账金额。 */
export function recordSettlementArrival(id: string, input: FinanceSettlementArrivalInput) {
  return api<FinanceSettlementRow>(`${base}/${encodeURIComponent(id)}/arrival`, 'POST', input);
}

/** 作废（红冲的等价物）：终态，整单冻结。 */
export function voidSettlement(id: string, reason: string) {
  return api<FinanceSettlementRow>(`${base}/${encodeURIComponent(id)}/void`, 'POST', { reason });
}

// ================================================================ 可读形态

/** 汇总类别的可读名。 */
export function settlementCategoryLabel(category: string): string {
  switch (category) {
    case 'SALES':
      return '销售额';
    case 'REFUND':
      return '退款';
    case 'COMMISSION':
      return '佣金';
    case 'FBA_FEE':
      return 'FBA 费用';
    case 'ADVERTISING':
      return '广告费';
    case 'COMPENSATION':
      return '赔付';
    case 'ADJUSTMENT':
      return '其他调整';
    default:
      return category;
  }
}

/**
 * 结算来源的可读名。
 *
 * <p>⚠️ 「平台结算报告同步」在界面上必须带着「不可用」的提示 ——
 * 上游「卷 02 同步」没有这个端点，放一个能点的入口就是骗人。
 */
export function settlementSourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL_UPLOAD':
      return '人工上传账单';
    case 'PLATFORM_SYNC':
      return '平台结算报告同步（当前不可用）';
    case 'MANUAL':
      return '人工登记';
    default:
      return source;
  }
}

/** 结算状态的可读名。 */
export function settlementStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待到账';
    case 'PARTIAL':
      return '部分到账';
    case 'SETTLED':
      return '已到账';
    case 'VOID':
      return '已作废';
    case 'SUPERSEDED':
      return '已被新版本取代';
    default:
      return status;
  }
}

/** 结算状态的可读色调。 */
export function settlementStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' | 'info' {
  switch (status) {
    case 'PENDING':
      return 'warning';
    case 'PARTIAL':
      return 'info';
    case 'SETTLED':
      return 'success';
    case 'VOID':
      return 'danger';
    case 'SUPERSEDED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 结算周期类型的可读名。 */
export function settlementCycleTypeLabel(cycleType: string): string {
  switch (cycleType) {
    case 'WEEKLY':
      return '周结';
    case 'BIWEEKLY':
      return '双周结';
    case 'MONTHLY':
      return '月结';
    default:
      return cycleType;
  }
}

/** 事件类型的可读名。 */
export function settlementEventLabel(eventType: string): string {
  switch (eventType) {
    case 'CREATED':
      return '手工登记';
    case 'IMPORTED':
      return '平台账单导入';
    case 'REPARSED':
      return '重新解析';
    case 'ARRIVED':
      return '登记到账';
    case 'VOIDED':
      return '作废';
    case 'SUPERSEDED':
      return '被新版本取代';
    default:
      return eventType;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 * 后端给 `null` 时显示「未登记」而不是 0。
 */
export function formatSettlementAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatSettlementMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未登记';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 汇总项金额的可读形态（带符号说明）。
 *
 * <p>⚠️ 汇总项里的金额是**对结算金额的贡献**（正=增加、负=减少）。
 * 页面必须把这个含义写出来，否则「退款 −100」会被读成「退款了 100 块」。
 */
export function formatContribution(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  const sign = amount > 0 ? '+' : '';
  return `${sign}${amount.toFixed(4)}`;
}

/**
 * 日期（结算周期、账单行日期）的可读形态。
 *
 * <p>⚠️ 只做显示格式化，**不**做时区换算 —— 日历日就是日历日。
 */
export function formatSettlementDate(value: string | null | undefined): string {
  if (!value) return '未登记';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleDateString('zh-CN');
}

/**
 * 时刻（预计到账、实际到账、解析时刻）的可读形态。
 *
 * <p>⚠️ 只做显示格式化，**不**在前端做时区换算 ——
 * 后端存的是 timestamptz，展示用浏览器本地时区即可（换算只是显示，不影响账）。
 */
export function formatSettlementTime(value: string | null | undefined): string {
  if (!value) return '未登记';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 结算单事实的可读形态（冻结项一览）。
 *
 * <p>⚠️ 结算事实登记后不可改 —— 页面要让人知道这一点，
 * 否则用户会以为「填错了回去改一下就行」。
 */
export function settlementFactHint(row: FinanceSettlementRow): string {
  const parts = [
    `周期 ${row.periodStart} ~ ${row.periodEnd}`,
    `币种 ${row.currency}`,
    `来源 ${settlementSourceLabel(row.source)}`,
    `第 ${row.parseVersion} 版解析`,
  ];
  return parts.join(' · ');
}

/**
 * 差额的可读形态。
 *
 * <p>⚠️ **前端不重算差额**：差额是服务端按「平台结算金额 − 我方应收未核销余额」算出来的，
 * 页面原样显示，并把两边的口径写清楚。
 */
export function formatDifference(
  difference: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (difference === null || difference === undefined) return '未登记';
  const sign = difference > 0 ? '+' : '';
  return `${sign}${difference.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 差额说明的可读形态（口径 + 未交付的部分）。
 *
 * <p>⚠️ 收款侧现在恒为 0（12.2.1.1 的 settlement_no 恒为 NULL），
 * 自动对账任务（12.3.2.1）未交付 —— 界面必须说清，否则会有人以为「已经对过了，差额 0」。
 */
export function differenceHint(difference: FinanceSettlementDifference): string {
  return [
    `平台结算金额 ${formatSettlementMoney(difference.platformSettlementAmount, difference.currency)}`,
    `我方应收未核销余额 ${formatSettlementMoney(difference.receivableOpenAmount, difference.currency)}`,
    '差额 = 平台结算金额 − 我方应收未核销余额（实时计算，不是快照）',
  ].join('；');
}
