import type {
  FinanceAgingBucketConfigInput,
  FinanceAgingBucketDef,
  FinanceAgingBucketInput,
  FinanceReceivableAgingBucketConfig,
  FinanceReceivableAgingCapabilities,
  FinanceReceivableAgingDistribution,
  FinanceReceivableAgingOptions,
  FinanceReceivableAgingOverduePage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.1.1.2 应收账龄的前端模型。
 *
 * <p>蓝图 12-finance.md：「按账龄区间（未到期/1-30/31-60/61-90/90+）统计应收分布，支持按店铺、客户、币种维度查看，识别逾期集中风险。」输入：「应收单集合；账龄区间配置。」输出：「账龄分布报表 + 逾期清单。」
 *
 * <p><b>命名空间</b>：{@code finance/receivable-aging}，与 12.1.1.1 的 {@code finance/receivables}（应收单管理）
 * **刻意不共用前缀** —— 那是另一个模块的命名空间，不要互相调用。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：分档金额、占比、集中度都由**服务端**算好，
 *       页面**不做任何业务算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总按币种分组渲染，**绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>争议中金额单列</b>：跨境应收最大的风险是平台争议冻结，
 *       争议金额必须能**单独**看到，不许混在总额里。</li>
 *   <li><b>账龄区间是租户自己的口径</b>：生效的区间由服务端下发（租户配的或蓝图默认的），
 *       页面**不**写死五档 —— 租户改了档位，页面跟着变。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有未核销应收」。</li>
 * </ul>
 */

const base = 'finance/receivable-aging';

/**
 * 账龄报表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface AgingParams {
  currency?: string | undefined;
  shopId?: string | undefined;
  counterpartyRef?: string | undefined;
  bucketCode?: string | undefined;
  disputeOnly?: boolean | undefined;
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
export function fetchReceivableAgingCapabilities() {
  return api<FinanceReceivableAgingCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（币种 / 店铺 / 客户）+ 当前生效的账龄区间。 */
export function fetchReceivableAgingOptions() {
  return api<FinanceReceivableAgingOptions>(`${base}/options`);
}

/** 生效中的账龄区间配置（租户配的，或蓝图默认的）。 */
export function fetchReceivableAgingBuckets() {
  return api<FinanceReceivableAgingBucketConfig>(`${base}/buckets`);
}

/**
 * 配置账龄区间（**整体替换**）。
 *
 * <p>⚠️ 服务端会整体校验：档位之间必须连续覆盖 1 天起，留缝或重叠都会被拒绝并说清原因。
 * 传空列表 = 回到**蓝图给定的五档**。
 */
export function updateReceivableAgingBuckets(buckets: FinanceAgingBucketInput[]) {
  return api<FinanceReceivableAgingBucketConfig>(`${base}/buckets`, 'PUT', {
    buckets,
  } as FinanceAgingBucketConfigInput);
}

/** 账龄分布报表：按店铺 / 客户 / 币种三个维度下钻，争议中金额单列。 */
export function fetchReceivableAgingDistribution(
  dimension: string,
  params: AgingParams = {},
) {
  return api<FinanceReceivableAgingDistribution>(
    `${base}/distribution${query({ dimension, ...params })}`,
  );
}

/** 逾期清单：真分页 + 真筛选 + 按币种汇总（覆盖整个筛选结果集）。 */
export function fetchReceivableAgingOverdue(
  params: AgingParams = {},
  page = 0,
  size = 50,
) {
  return api<FinanceReceivableAgingOverduePage>(
    `${base}/overdue${query({ ...params, page, size })}`,
  );
}

// ================================================================ 可读形态

/** 下钻维度的可读名。 */
export function agingDimensionLabel(dimension: string): string {
  switch (dimension) {
    case 'SHOP':
      return '按店铺';
    case 'COUNTERPARTY':
      return '按客户';
    case 'CURRENCY':
      return '按币种';
    default:
      return dimension;
  }
}

/**
 * 账龄区间的可读名。
 *
 * <p>⚠️ 名称取**服务端下发**的 label，不在前端写死五档 ——
 * 租户改了档位（档数、名称、边界都可能不同），页面必须跟着变。
 */
export function agingBucketLabel(bucket: FinanceAgingBucketDef | undefined): string {
  if (!bucket) return '—';
  return bucket.label;
}

/** 账龄区间的天数范围（可读形态）。 */
export function agingBucketRange(bucket: FinanceAgingBucketDef | undefined): string {
  if (!bucket) return '—';
  if (bucket.overdueFromDays === null) return '未到期';
  if (bucket.overdueToDays === null) return `${bucket.overdueFromDays} 天起`;
  return `${bucket.overdueFromDays}-${bucket.overdueToDays} 天`;
}

/** 区间来源的可读名。 */
export function agingBucketSourceLabel(source: string): string {
  switch (source) {
    case 'TENANT_CONFIG':
      return '租户自配';
    case 'BLUEPRINT_DEFAULT':
      return '蓝图默认';
    default:
      return source;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatAgingAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatAgingMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 占比的可读形态。
 *
 * <p>⚠️ 这里做的是**单位换算**（比例 → 百分数），不是业务算术：
 * 分子与分母都在服务端算好了，页面只把 0.6667 显示成 66.67%。
 * 分母为 0 时服务端给 {@code null}（「没有逾期」≠「占比 0」）—— 这里显示「—」。
 */
export function formatAgingShare(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined) return '—';
  return `${(ratio * 100).toFixed(2)}%`;
}

/**
 * 逾期天数的可读形态。
 *
 * <p>逾期天数由**服务端**算好（账龄基准日 - 到期日），页面不自己算日期差 ——
 * 换一条时区就会算出一套不同的天数。
 */
export function formatOverdueDays(days: number): string {
  if (days <= 0) return '未到期';
  return `逾期 ${days} 天`;
}
