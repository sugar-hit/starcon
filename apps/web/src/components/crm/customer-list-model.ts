import type {
  CrmCustomerListAmount,
  CrmCustomerListCapabilities,
  CrmCustomerListPage,
  CrmCustomerListRow,
  CrmCustomerListStats,
  CrmCustomerListSyncResult,
  CrmCustomerListSyncRunPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.3.1.1 客户列表前端模型。
 *
 * <p>蓝图：「客户列表：客户标识（按店铺）、来源店铺、首末购买时间、订单数、累计消费、
 * 退款率、客单价、标签、联系方式（脱敏）、风险标记。输入：筛选条件（店铺、标签、
 * 价值分层、风险）；用户数据范围。输出：客户列表 + 分层统计。关系：上游 = 9 订单；
 * 下游 = 15.3.1.2 详情、15.3.1.3 跨店合并结果。**列表默认脱敏显示联系方式**，
 * 需要显式授权才完整展示。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>客户是「按来源店铺」的</b>：同一个客户标识在两个来源下是**两行**
 *       （那是 15.3.1.3 跨店合并要做的事，本模块**不提前合并**）。
 *       页面必须把「来源店铺」摆在最显眼的位置，否则运营会以为看漏了人。</li>
 *   <li><b>联系方式默认脱敏</b>：后端默认给 {@code a***@***.com} / {@code 138****1234}；
 *       明文要**另外**的权限码（{@code crm.customers.pii.read}）+ 页面上的显式开关。
 *       没有权限时开关必须**禁用**（而不是点了之后报 403）。</li>
 *   <li><b>金额按币种分开</b>：累计消费与客单价按币种分行显示，
 *       <b>前端不做任何算术</b>（占比、客单价、比率都由后端算好）。</li>
 *   <li><b>退款率「不可判定」是一等状态</b>：分母为 0 时后端给 null，
 *       页面显示「不可判定」而不是「0%」。</li>
 *   <li><b>「样本不足」与「没风险」分开</b>：{@code INSUFFICIENT_SAMPLE} 必须有自己的说法。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有客户档案」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/customers}，与同服务的 {@code crm/customer-tags}、
 * {@code crm/customer-contacts}、{@code crm/knowledge}、{@code crm/review-requests}
 * **刻意不共用**。
 */

const base = 'crm/customers';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CustomerListParams {
  sourceShop?: string | undefined;
  tagCode?: string | undefined;
  valueTier?: string | undefined;
  riskFlag?: string | undefined;
  keyword?: string | undefined;
  minOrderCount?: number | undefined;
  minTotalAmount?: string | undefined;
  currency?: string | undefined;
  sort?: string | undefined;
  revealContact?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 价值分层的可读名（后端也给了 label，这份是**兜底**：后端没给时页面不至于显示裸码）。 */
export function customerValueTierLabel(code: string | undefined): string {
  switch (code) {
    case 'VIP':
      return '高价值';
    case 'HIGH':
      return '较高价值';
    case 'NORMAL':
      return '常规';
    case 'LOW':
      return '无有效消费';
    default:
      return code ?? '';
  }
}

/**
 * 风险标记的可读名。
 *
 * <p>🔴 `INSUFFICIENT_SAMPLE` 与 `NONE` **必须是两句话**：
 * 「样本不足」画成「无风险标记」，会让人以为「这个人没问题」——
 * 而他其实**没被判过**。
 */
export function customerRiskFlagLabel(code: string | undefined): string {
  switch (code) {
    case 'NONE':
      return '无风险标记';
    case 'HIGH_RETURN_RATE':
      return '退款率显著高于租户基线';
    case 'OPT_OUT':
      return '已登记生效中的退订';
    case 'HIGH_RETURN_RATE_AND_OPT_OUT':
      return '退款率偏高且已退订';
    case 'INSUFFICIENT_SAMPLE':
      return '样本不足，不可判定';
    default:
      return code ?? '';
  }
}

/** 退订状态的可读名。 */
export function customerOptOutStateLabel(code: string | undefined): string {
  switch (code) {
    case 'NONE':
      return '无退订记录';
    case 'ACTIVE':
      return '退订生效中';
    case 'REVOKED':
      return '退订已撤销';
    default:
      return code ?? '';
  }
}

/**
 * 退款率的显示文本。
 *
 * <p>🔴 分母为 0（后端给 null）⇒「不可判定」，**不是**「0%」：
 * 一个人只下一单、退了一次，退款率是 100%，那不是「退款率高的买家」，那是样本不足。
 */
export function customerRefundRateText(rate: string | number | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '不可判定';
  return String(rate);
}

/**
 * 金额按币种分行的文本（**一行一个币种**，不做任何前端算术）。
 *
 * <p>「累计消费 USD 315.60 / EUR 12.00」比「327.60」诚实 ——
 * USD 与 EUR 加起来不是任何一个金额。
 */
export function customerAmountText(amounts: CrmCustomerListAmount[] | undefined): string {
  if (!amounts || amounts.length === 0) return '无消费记录';
  return amounts
    .map((amount) => {
      const total = String(amount.totalAmount);
      // 客单价在**订单数为 0** 时是 null（不可判定），不是 0 —— 后端给的，页面只原样显示。
      const avg = amount.avgOrderAmount === null ? '—' : String(amount.avgOrderAmount);
      return `${amount.currency} ${total}（客单价 ${avg}）`;
    })
    .join(' / ');
}

/** 一行客户的「首末购买时间」文本。 */
export function customerPurchaseWindowText(row: CrmCustomerListRow): string {
  const first = row.firstOrderAt ? row.firstOrderAt.slice(0, 10) : '—';
  const last = row.lastOrderAt ? row.lastOrderAt.slice(0, 10) : '—';
  return `${first} ~ ${last}`;
}

/**
 * 联系方式的显示文本。
 *
 * <p>🔴 默认给**脱敏**形态（后端已经脱敏好，页面只原样显示）。
 * 明文只有后端授权后才会给 —— 页面**不自己**去拼联系方式。
 */
export function customerContactText(row: CrmCustomerListRow): string {
  const email = row.contactEmail || '—';
  const phone = row.contactPhone || '—';
  return `${email} / ${phone}`;
}

/** 联系方式是明文还是脱敏（页面据此提示「本次已展示明文」）。 */
export function customerContactIsPlain(row: CrmCustomerListRow): boolean {
  return row.contactMasked === false;
}

/**
 * 空态文案：**区分**「筛选无结果」与「还没有客户档案」。
 *
 * <p>这两种空态要的行动完全不同：前者是「把筛选条件放宽」，
 * 后者是「先从上游刷新一次客户档案」。
 */
export function customerEmptyStateText(
  filtered: boolean,
  hasRuns: boolean,
): { title: string; description: string } {
  if (filtered) {
    return {
      title: '没有命中任何客户',
      description: '当前筛选条件下没有客户。放宽筛选条件再试一次 —— 这不代表这个租户没有客户。',
    };
  }
  if (!hasRuns) {
    return {
      title: '还没有客户档案',
      description:
        '客户档案来自上游 9 订单的订单事实（经授权 HTTP API 只读）。点「刷新客户档案」从上游拉一次。',
    };
  }
  return {
    title: '还没有客户档案',
    description: '上一次刷新没有产出客户档案（上游窗口内没有订单，或那次刷新失败了）。',
  };
}

/** 分层统计的一句话说明（**不受分页影响**，后端单独一条查询算出来的）。 */
export function customerStatsNote(stats: CrmCustomerListStats | undefined): string {
  if (!stats) return '';
  return `客户 ${stats.totalCustomers} 位 · 订单 ${stats.totalOrders} 单 · 退款单 ${stats.totalRefundCases} 张。${stats.note ?? ''}`;
}

// ================================================================ 请求

export function fetchCustomerListCapabilities() {
  return api<CrmCustomerListCapabilities>(`${base}/capabilities`);
}

export function fetchCustomerList(params: CustomerListParams = {}) {
  return api<CrmCustomerListPage>(`${base}${query({ ...params })}`);
}

export function fetchCustomerSyncRuns() {
  return api<CrmCustomerListSyncRunPage>(`${base}/runs?size=20`);
}

export function syncCustomerList(input: { windowFrom?: string; windowTo?: string } = {}) {
  return api<CrmCustomerListSyncResult>(`${base}/sync`, 'POST', input);
}

export function registerCustomerContact(input: {
  customerRef: string;
  sourceShop: string;
  email?: string | undefined;
  phone?: string | undefined;
}) {
  return api<CrmCustomerListRow>(`${base}/contact`, 'PUT', input);
}

/** 拼查询串（**空值一律不拼** —— 拼一个空的 sourceShop= 会把筛选悄悄变成「来源店铺为空」）。 */
function query(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}
