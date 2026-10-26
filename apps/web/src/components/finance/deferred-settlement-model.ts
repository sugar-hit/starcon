import type {
  FinanceDeferredSettlementBasisNote,
  FinanceDeferredSettlementCapabilities,
  FinanceDeferredSettlementCurrencySummary,
  FinanceDeferredSettlementDetail,
  FinanceDeferredSettlementOptions,
  FinanceDeferredSettlementPage,
  FinanceDeferredSettlementRow,
  FinanceDeferredSettlementRuleInput,
  FinanceDeferredSettlementRuleRow,
  FinanceDeferredSettlementScanResult,
  FinanceDeferredSettlementSummary,
  FinanceDeferredSettlementVoidInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.3.1.3 延迟结算的前端模型。
 *
 * <p>蓝图 12-finance.md：「管理平台延迟结算（部分平台按周期后结算或滚动结算）：单列在途未结算金额、
 * 结算条件、预计到账时间，支持查询「订单已发货但钱未到账」的明细。」输入：「结算规则；订单与结算状态。」
 * 输出：「延迟结算明细 + 在途资金统计 + 预计到账时间。」关系：「下游 = 13 利润（按已结算/未结算区分口径）。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>两套口径必须同屏出现</b>：下游 13 利润要「按已结算 / 未结算区分口径」，
 *       所以页面**同时**显示<b>订单口径</b>与<b>已结算口径</b>，以及两者的差
 *       <b>在途未结算金额</b>。只显示一个数就等于替下游做了取舍。</li>
 *   <li><b>金额只原样展示</b>：三个口径金额都由**服务端**算好，页面**不做任何前端算术**
 *       （USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>口径说明要跟着数字一起出现</b>：数字脱离口径说明，半年后没人说得清
 *       「订单口径」到底是毛额还是净额 —— 后端把口径定义随响应返回，页面原样显示。</li>
 *   <li><b>预计到账时间与结算条件是本模块的主角</b>：页面必须把「什么时候能到账、
 *       按什么条件结算」摆在最显眼的位置，而不是只给一个金额。</li>
 *   <li><b>上游没交付的事就说没交付</b>：自动对账任务（12.3.2.1）与利润（13）**未交付**，
 *       页面**不提供**「跑对账 / 看利润」的入口。</li>
 *   <li><b>扫描的告警原样显示</b>：没进来的订单要逐条说明原因 ——
 *       「扫了 0 笔」如果不说清原因，现场会以为平台没有订单。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有延迟结算台账」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/deferred-settlement}，与同服务的 12.3.1.1（{@code finance/settlement}）、
 * 12.3.1.2（{@code finance/transactions}）**并列**而非嵌套 —— 首 token 也刻意不同
 * （{@code deferred-settlement} ≠ {@code settlement} ≠ {@code transactions}）。
 */

/** 后端命名空间（与 api-proxy 白名单里的首 token 一致）。 */
const base = 'finance/deferred-settlement';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface DeferredSettlementParams {
  status?: string | undefined;
  platformCode?: string | undefined;
  shopCode?: string | undefined;
  currency?: string | undefined;
  shippedFrom?: string | undefined;
  shippedTo?: string | undefined;
  overdueOnly?: boolean | undefined;
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
export function fetchDeferredSettlementCapabilities() {
  return api<FinanceDeferredSettlementCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchDeferredSettlementOptions() {
  return api<FinanceDeferredSettlementOptions>(`${base}/options`);
}

/** 结算规则清单（店铺级规则 + 平台级默认规则）。 */
export function fetchDeferredSettlementRules() {
  return api<Array<FinanceDeferredSettlementRuleRow>>(`${base}/rules`);
}

/**
 * 登记 / 改写一条结算规则（按「平台 + 店铺」upsert）。
 *
 * <p>⚠️ 预计到账时间由**服务端**从「发货时间 + 结算模式 + 周期 + 到账偏移」派生，
 * 页面**不提供**「预计到账时间」输入框 —— 提供了也是假的。
 */
export function upsertDeferredSettlementRule(input: FinanceDeferredSettlementRuleInput) {
  return api<FinanceDeferredSettlementRuleRow>(`${base}/rules`, 'POST', input);
}

/** 延迟结算明细列表：真分页 + 真筛选 + 两套口径汇总。 */
export function fetchDeferredSettlements(params: DeferredSettlementParams = {}) {
  return api<FinanceDeferredSettlementPage>(`${base}${query({ ...params })}`);
}

/** 在途资金统计（两套口径 + 预计到账时间）。 */
export function fetchDeferredSettlementSummary(params: DeferredSettlementParams = {}) {
  return api<FinanceDeferredSettlementSummary>(`${base}/summary${query({ ...params })}`);
}

/** 台账明细：单头 + 事件流。 */
export function fetchDeferredSettlement(id: string) {
  return api<FinanceDeferredSettlementDetail>(`${base}/${encodeURIComponent(id)}`);
}

/**
 * 从上游扫描「已发货但钱未到账」的订单进台账。
 *
 * <p>⚠️ 上游读不到时后端返回 503 —— 页面**如实显示**这个失败，
 * 绝不显示成「没有订单」。
 */
export function scanDeferredSettlements() {
  return api<FinanceDeferredSettlementScanResult>(`${base}/scan`, 'POST');
}

/** 作废（红冲的等价物）：终态，不进任何口径。理由必填。 */
export function voidDeferredSettlement(id: string, reason: string) {
  const input: FinanceDeferredSettlementVoidInput = { reason };
  return api<FinanceDeferredSettlementRow>(`${base}/${encodeURIComponent(id)}/void`, 'POST', input);
}

// ================================================================ 可读形态

/** 结算模式的可读名。 */
export function deferredSettlementModeLabel(mode: string): string {
  switch (mode) {
    case 'PERIODIC':
      return '周期后结算';
    case 'ROLLING':
      return '滚动结算';
    default:
      return mode;
  }
}

/** 台账状态的可读名。 */
export function deferredSettlementStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '在途未结算（钱未到账）';
    case 'SETTLED':
      return '已结算（钱已到账）';
    case 'VOID':
      return '已作废';
    default:
      return status;
  }
}

/** 台账状态的语义色（页面据此上色，不解释语义）。 */
export function deferredSettlementStatusTone(status: string): 'warning' | 'success' | 'neutral' {
  switch (status) {
    case 'PENDING':
      return 'warning';
    case 'SETTLED':
      return 'success';
    default:
      return 'neutral';
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 * 后端给 `null` 时显示「未登记」而不是 0。
 */
export function formatDeferredSettlementAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatDeferredSettlementMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未登记';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 时间（发货时间 / 结算周期区间 / 预计到账时间）的可读形态。
 *
 * <p>⚠️ 这三个字段是 `timestamptz`（带时刻），**不是**日历日 —— 所以这里做时区换算，
 * 与「日历日原样显示」的纪律正好相反。日历日会被 `new Date()` 当成 UTC 午夜，
 * 在 UTC 以西的时区渲染出来就是**前一天**；时刻则必须按本地时区显示，
 * 否则「预计 10 月 11 日到账」会被显示成 10 月 10 日。
 */
export function formatDeferredSettlementDateTime(value: string | null | undefined): string {
  if (!value) return '未登记';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 预计到账时间的可读形态（只到日）。
 *
 * <p>⚠️ 这是**时刻**的本地日期，不是日历日字段 —— 用来回答「哪天能到账」。
 */
export function formatDeferredSettlementDay(value: string | null | undefined): string {
  if (!value) return '未登记';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleDateString('zh-CN');
}

/**
 * 结算条件的可读形态。
 *
 * <p>⚠️ 「没有结算条件」是**正常状态**（规则没写说明）：页面显示「—」，不渲染成异常。
 */
export function formatSettlementCondition(value: string | null | undefined): string {
  if (!value) return '—';
  return value;
}

/**
 * 币种口径汇总的可读形态（一行一句话）。
 *
 * <p>⚠️ **三个数并排显示，不做加减**：页面不替下游做口径取舍，
 * 只把服务端算好的三个口径原样摆出来。
 */
export function deferredSettlementCurrencyLine(
  summary: FinanceDeferredSettlementCurrencySummary,
): string {
  return [
    `订单口径 ${formatDeferredSettlementMoney(summary.orderBasisAmount, summary.currency)}`,
    `已结算口径 ${formatDeferredSettlementMoney(summary.settledBasisAmount, summary.currency)}`,
    `在途未结算 ${formatDeferredSettlementMoney(summary.inTransitAmount, summary.currency)}`,
  ].join('；');
}

/**
 * 口径说明的可读形态（逐条列出）。
 *
 * <p>⚠️ 口径说明随响应返回，页面**原样显示** —— 数字脱离口径说明就没有意义。
 */
export function deferredSettlementBasisLines(basis: FinanceDeferredSettlementBasisNote): string[] {
  return [
    `订单口径：${basis.orderBasis}`,
    `已结算口径：${basis.settledBasis}`,
    `在途未结算：${basis.inTransitBasis}`,
    `口径基数差：${basis.basisDifference}`,
    `币种纪律：${basis.currencyPolicy}`,
  ];
}

/**
 * 扫描结果的可读形态。
 *
 * <p>⚠️ 「扫了 0 笔」如果不说清原因，现场会以为平台没有订单 ——
 * 所以 warnings 必须**逐条**显示，不能吞掉。
 */
export function deferredSettlementScanSummary(result: FinanceDeferredSettlementScanResult): string {
  return [
    `读到平台订单 ${result.fetchedOrders} 笔、发货记录 ${result.fetchedShipments} 条`,
    `命中已发货 ${result.matched} 笔`,
    `新建 ${result.created} 笔、更新 ${result.updated} 笔`,
    `跳过 ${result.skipped} 笔`,
    result.truncated ? '⚠️ 触到翻页上限，还有订单没扫进来' : '',
  ]
    .filter(Boolean)
    .join('；');
}
