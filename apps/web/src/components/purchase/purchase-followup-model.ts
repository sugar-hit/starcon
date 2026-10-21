import type {
  PurchaseFollowupCapabilities,
  PurchaseFollowupChangeInput,
  PurchaseFollowupChangePage,
  PurchaseFollowupChangeRow,
  PurchaseFollowupDeliveryPage,
  PurchaseFollowupDeliveryStats,
  PurchaseFollowupRecordInput,
  PurchaseFollowupRecordPage,
  PurchaseFollowupRecordRow,
  PurchaseFollowupTimelinePage,
  PurchaseFollowupWritebackRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.3.2.1 跟单与交期前端模型。
 *
 * <p>蓝图：「记录与供应商的沟通跟进（催单、确认、异常反馈），维护承诺到货日变更历史，
 * 统计平均延迟天数。」输出「跟单时间线；交期变更历史；延迟统计。」
 *
 * <p><b>界面纪律</b>（逐条对应后端模型的口径，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>两个承诺口径必须同时显示</b>：单据承诺日（{@code documentPromiseDate}，6.3.1.1 持有）
 *       与跟单承诺日（{@code followupPromiseDate}，本模块的变更历史）。
 *       两者不一致时（{@code promiseDiverged}）页面要**两个都摆出来** ——
 *       只显示一个会让人以为两边一致，而实际上采购单上写的还是旧日期。</li>
 *   <li><b>「没到货」不是「延迟 0」</b>：{@code delayDays} 为 null 时显示「未到货」，
 *       另显示「已逾期 N 天」—— 延迟天数必须有终点，没到货就没有终点。</li>
 *   <li><b>延迟可以是负数</b>（提前到货）：负数显示为「提前 N 天」，不显示成逾期。</li>
 *   <li><b>回写如实呈现</b>：{@code status} / {@code available} / {@code deliveryInputApplied}
 *       决定徽标文案；失败要显示原因与影响，<b>不假装已经回写</b>。</li>
 *   <li><b>「催单」是沟通</b>：本模块的催单记录是「谁、何时、说了什么」，
 *       与 6.3.1.1 采购单上的「催过几次」是两件事，页面不混着说。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 */

const base = 'trade/purchase-followups';

// ================================================================ 查询参数

export interface PurchaseFollowupParams {
  purchaseOrderNo?: string | undefined;
  supplierRefId?: string | undefined;
  followupType?: string | undefined;
  channel?: string | undefined;
  occurredFrom?: string | undefined;
  occurredTo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface PurchaseFollowupChangeParams {
  purchaseOrderNo?: string | undefined;
  supplierRefId?: string | undefined;
  changedFrom?: string | undefined;
  changedTo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface PurchaseFollowupDeliveryParams {
  supplierRefId?: string | undefined;
  keyword?: string | undefined;
  onlyOverdue?: boolean | undefined;
  onlyDelayed?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/**
 * 跟单记录列表的查询串。
 *
 * <p>🔴 刻意做成**独立函数**而不是在模板里内联三元表达式：白名单守卫
 * （{@code purchase-followup-allowlist.test.ts}）会从本文件源码里**反向抽取**真实调用点，
 * 模板里多一个内联插值就会抽出一条对不上的假路径。
 */
export function purchaseFollowupQuery(params: PurchaseFollowupParams): string {
  const parts: string[] = [];
  if (params.purchaseOrderNo)
    parts.push(`purchaseOrderNo=${encodeURIComponent(params.purchaseOrderNo)}`);
  if (params.supplierRefId) parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.followupType) parts.push(`followupType=${encodeURIComponent(params.followupType)}`);
  if (params.channel) parts.push(`channel=${encodeURIComponent(params.channel)}`);
  if (params.occurredFrom) parts.push(`occurredFrom=${encodeURIComponent(params.occurredFrom)}`);
  if (params.occurredTo) parts.push(`occurredTo=${encodeURIComponent(params.occurredTo)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 交期变更历史的查询串。 */
export function purchaseFollowupChangeQuery(params: PurchaseFollowupChangeParams): string {
  const parts: string[] = [];
  if (params.purchaseOrderNo)
    parts.push(`purchaseOrderNo=${encodeURIComponent(params.purchaseOrderNo)}`);
  if (params.supplierRefId) parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.changedFrom) parts.push(`changedFrom=${encodeURIComponent(params.changedFrom)}`);
  if (params.changedTo) parts.push(`changedTo=${encodeURIComponent(params.changedTo)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 逐单交付实况的查询串。 */
export function purchaseFollowupDeliveryQuery(params: PurchaseFollowupDeliveryParams): string {
  const parts: string[] = [];
  if (params.supplierRefId) parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.onlyOverdue) parts.push('onlyOverdue=true');
  if (params.onlyDelayed) parts.push('onlyDelayed=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchPurchaseFollowups(params: PurchaseFollowupParams) {
  return api<PurchaseFollowupRecordPage>(`${base}${purchaseFollowupQuery(params)}`);
}

export function fetchPurchaseFollowupCapabilities() {
  return api<PurchaseFollowupCapabilities>(`${base}/capabilities`);
}

export function fetchPurchaseFollowupTimeline(params: PurchaseFollowupParams) {
  return api<PurchaseFollowupTimelinePage>(`${base}/timeline${purchaseFollowupQuery(params)}`);
}

export function fetchPurchaseFollowupChanges(params: PurchaseFollowupChangeParams) {
  return api<PurchaseFollowupChangePage>(`${base}/changes${purchaseFollowupChangeQuery(params)}`);
}

export function fetchPurchaseFollowupStats() {
  return api<PurchaseFollowupDeliveryStats>(`${base}/stats`);
}

export function fetchPurchaseFollowupDelivery(params: PurchaseFollowupDeliveryParams) {
  return api<PurchaseFollowupDeliveryPage>(
    `${base}/delivery${purchaseFollowupDeliveryQuery(params)}`,
  );
}

/** 登记一条跟单记录（沟通凭据，追加式）。 */
export function recordPurchaseFollowup(input: PurchaseFollowupRecordInput) {
  return api<PurchaseFollowupRecordRow>(`${base}`, 'POST', input);
}

/** 登记一次交期变更（落进交期变更历史，并触发回写 6.2.1.1 / 6.2.1.2）。 */
export function recordPurchaseFollowupChange(input: PurchaseFollowupChangeInput) {
  return api<PurchaseFollowupChangeRow>(`${base}/changes`, 'POST', input);
}

// ================================================================ 展示

/** 日期（YYYY-MM-DD）原样显示 —— 承诺到货日是日历日，不做时区换算。 */
export function fmtDate(value: string | null | undefined): string {
  if (!value) return '—';
  return value;
}

/** 时刻：本地化显示（沟通发生时刻是 timestamptz）。 */
export function fmtDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 延迟天数的展示文案。
 *
 * <p>🔴 **null 与 0 严格分开**：
 * <ul>
 *   <li>null = 还没全部到货 ⇒ 没有终点 ⇒ 不算延迟天数，显示「未到货」；</li>
 *   <li>0 = 到货那天正好是承诺日 ⇒ 准时，显示「准时」；</li>
 *   <li>负数 = 提前到货，显示「提前 N 天」；</li>
 *   <li>正数 = 晚到了，显示「延迟 N 天」。</li>
 * </ul>
 */
export function delayDaysText(delayDays: number | null | undefined): string {
  if (delayDays === null || delayDays === undefined) return '未到货（不计延迟）';
  if (delayDays === 0) return '准时';
  if (delayDays < 0) return `提前 ${Math.abs(delayDays)} 天`;
  return `延迟 ${delayDays} 天`;
}

/** 延迟天数的色调（正 = 负面，负 = 正面，0 / null = 中性）。 */
export function delayDaysTone(
  delayDays: number | null | undefined,
): 'positive' | 'negative' | 'neutral' {
  if (delayDays === null || delayDays === undefined || delayDays === 0) return 'neutral';
  return delayDays > 0 ? 'negative' : 'positive';
}

/** 已逾期天数：只对未到货且已逾期的单子有意义。 */
export function overdueDaysText(overdueDays: number | null | undefined): string {
  if (overdueDays === null || overdueDays === undefined) return '—';
  return `已逾期 ${overdueDays} 天`;
}

/** 平均延迟天数：没有已到货样本时是 null（算不出来），**不是 0**。 */
export function averageDelayText(days: number | null | undefined): string {
  if (days === null || days === undefined) return '无数据（尚无已到货样本）';
  if (days < 0) return `提前 ${Math.abs(Number(days.toFixed(2)))} 天`;
  if (days === 0) return '0 天';
  return `延迟 ${Number(days.toFixed(2))} 天`;
}

/** 准时率：后端给的是**百分数**（两位小数），不是 0~1 的比例。 */
export function onTimeRateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '无数据（尚无已到货样本）';
  return `${Number(rate).toFixed(2)}%`;
}

/**
 * 两个承诺口径的展示文案。
 *
 * <p>🔴 不一致时**两个日期都摆出来**：只显示跟单承诺日会让人以为采购单上也改了，
 * 而实际上单据承诺日仍归 6.3.1.1（改单是 6.3.1.2 的职责，尚未交付）。
 */
export function promiseText(row: {
  documentPromiseDate: string | null;
  followupPromiseDate: string | null;
  promiseDiverged: boolean;
}): string {
  const doc = fmtDate(row.documentPromiseDate);
  const follow = fmtDate(row.followupPromiseDate);
  if (row.promiseDiverged) {
    return `单据承诺 ${doc} / 跟单承诺 ${follow}（两个口径不同）`;
  }
  return `承诺 ${doc}`;
}

/** 回写状态的中文文案。 */
export function writebackStatusText(row: PurchaseFollowupWritebackRow): string {
  if (row.status === 'SUCCEEDED') {
    return row.deliveryInputApplied ? '已回写（交期已进参数）' : '已触发重算（交期数值未进参数）';
  }
  if (row.status === 'SKIPPED') return '未回写（缺少前提事实）';
  return '回写失败';
}

/** 回写状态的色调。 */
export function writebackTone(
  row: PurchaseFollowupWritebackRow,
): 'positive' | 'negative' | 'neutral' {
  if (row.status === 'SUCCEEDED') return 'positive';
  if (row.status === 'FAILED') return 'negative';
  return 'neutral';
}

/** 回写目标模块的中文名。 */
export function writebackTargetLabel(targetModule: string): string {
  if (targetModule === '6.2.1.1') return '6.2.1.1 断货风险';
  if (targetModule === '6.2.1.2') return '6.2.1.2 补货建议';
  return targetModule;
}

/** 跟单类型的中文名（后端也给了 label，这份是兜底）。 */
export function followupTypeLabel(type: string): string {
  switch (type) {
    case 'URGING':
      return '催单';
    case 'CONFIRM':
      return '确认';
    case 'EXCEPTION':
      return '异常反馈';
    default:
      return '其他';
  }
}

/** 沟通渠道的中文名。 */
export function followupChannelLabel(channel: string): string {
  switch (channel) {
    case 'PHONE':
      return '电话';
    case 'EMAIL':
      return '邮件';
    case 'IM':
      return '即时消息';
    case 'WECHAT':
      return '微信';
    case 'PLATFORM':
      return '平台消息';
    default:
      return '其他';
  }
}

/** 到货状态的中文名（6.3.2.2 的口径，本模块只读）。 */
export function arrivalStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'PENDING_SHIPMENT':
      return '待发货';
    case 'IN_TRANSIT':
      return '在途';
    case 'PARTIALLY_RECEIVED':
      return '部分到货';
    case 'FULLY_RECEIVED':
      return '全部到货';
    case 'OVERDUE':
      return '逾期未到';
    case 'CANCELLED':
      return '已取消';
    default:
      return '未跟踪';
  }
}

/** 时间线条目的类型标签。 */
export function timelineKindLabel(kind: string): string {
  return kind === 'DELIVERY_CHANGE' ? '交期变更' : '跟单记录';
}
