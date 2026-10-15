import type {
  DealerAlertPage,
  DealerAuthorizationDetail,
  DealerAuthorizationInput,
  DealerAuthorizationPage,
  DealerAuthorizationRow,
  DealerAuthorizationStatusInput,
  DealerCapabilities,
  DealerDiversionAlertRow,
  DealerProfileInput,
  DealerProfilePage,
  DealerProfileRow,
  DealerQuotaAttainmentPage,
  DealerQuotaAttainmentRow,
  DealerQuotaInput,
  DealerRebateAccrueInput,
  DealerRebatePage,
  DealerRebateRow,
  DealerSaleInput,
  DealerSalePage,
  DealerSaleRow,
  DealerSaved,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 枚举类型**从行类型派生**，不单独 import。
 *
 * <p>codegen 把 Java 枚举展开成内联的字面量联合，不会生成独立的 `DealerBreachKind` 导出；
 * 从行类型的字段派生，两者永远跟着 spec 走，spec 改值域这里自动跟着变。
 */
export type DealerAuthorizationStatus = DealerAuthorizationRow['status'];
export type DealerComplianceState = DealerAuthorizationRow['complianceState'];
export type DealerBreachKind = DealerDiversionAlertRow['breachKind'];
export type DealerAlertState = DealerDiversionAlertRow['alertState'];
export type DealerSeverity = DealerDiversionAlertRow['severity'];
export type DealerAccrualState = DealerRebateRow['accrualState'];
export type DealerCustomerLevel = DealerProfileRow['customerLevel'];

/**
 * 9.7.1.2 经销与分销管理前端模型。
 *
 * <p>蓝图：「管理经销商/分销商：授权区域与产品线、授权期限、经销价与返点、
 * 销量配额与回款考核、窜货监控（跨区域销售预警）」。
 *
 * <p><b>界面纪律</b>（与后端逐条对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「未与财务确认」必须显式写在返点上</b>：计提行恒带
 *       {@code financeConfirmed=false}，页面直接显示「本模块口径 · 未与财务确认」。
 *       把它藏在 tooltip 里等于没写 —— 看板上最该被质疑的正是这一列金额。</li>
 *   <li><b>「合规未校验」不是「校验通过」</b>：{@code complianceState} 为
 *       {@code UNVERIFIED} 时显示「未校验（16 合规未交付）」，
 *       而不是留一个空标签让人以为是「通过」。</li>
 *   <li><b>完成率可能真的没有</b>：目标为 0（不限配额）时后端返回
 *       {@code null}，页面渲染成「不考核」而**不是 0%** ——
 *       0% 会被当成「一个都没完成」，那是编造出来的结论。</li>
 *   <li><b>金额不在前端算</b>：完成率与返点金额都由后端返回精确值，
 *       页面只做展示格式化，不做任何乘除（契约里是 {@code number}，
 *       前端一算就会引入浮点误差）。</li>
 *   <li><b>期限显示后端返回的两个日期</b>，不做时区换算（换算就是那些 −28800s 的来源）。</li>
 *   <li><b>未交付的下游写明归属模块</b>，而不是给一个点下去 404 的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/dealers';

export interface DealerAuthorizationParams {
  status?: string;
  dealerProfileId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DealerAttainmentParams {
  periodStart?: string;
  periodEnd?: string;
  dealerProfileId?: string;
  page?: number;
  size?: number;
}

export interface DealerSaleParams {
  authorizationId?: string;
  regionCode?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface DealerAlertParams {
  alertState?: string;
  breachKind?: string;
  dealerProfileId?: string;
  page?: number;
  size?: number;
}

function query(parts: string[]): string {
  return parts.length ? `?${parts.join('&')}` : '';
}

export function dealerAuthorizationQuery(params: DealerAuthorizationParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.dealerProfileId)
    parts.push(`dealerProfileId=${encodeURIComponent(params.dealerProfileId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return query(parts);
}

export function dealerAttainmentQuery(params: DealerAttainmentParams): string {
  const parts: string[] = [];
  if (params.periodStart) parts.push(`periodStart=${encodeURIComponent(params.periodStart)}`);
  if (params.periodEnd) parts.push(`periodEnd=${encodeURIComponent(params.periodEnd)}`);
  if (params.dealerProfileId)
    parts.push(`dealerProfileId=${encodeURIComponent(params.dealerProfileId)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return query(parts);
}

export function dealerSaleQuery(params: DealerSaleParams): string {
  const parts: string[] = [];
  if (params.authorizationId)
    parts.push(`authorizationId=${encodeURIComponent(params.authorizationId)}`);
  if (params.regionCode?.trim())
    parts.push(`regionCode=${encodeURIComponent(params.regionCode.trim())}`);
  if (params.from) parts.push(`from=${encodeURIComponent(params.from)}`);
  if (params.to) parts.push(`to=${encodeURIComponent(params.to)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return query(parts);
}

export function dealerAlertQuery(params: DealerAlertParams): string {
  const parts: string[] = [];
  if (params.alertState) parts.push(`alertState=${encodeURIComponent(params.alertState)}`);
  if (params.breachKind) parts.push(`breachKind=${encodeURIComponent(params.breachKind)}`);
  if (params.dealerProfileId)
    parts.push(`dealerProfileId=${encodeURIComponent(params.dealerProfileId)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return query(parts);
}

export function dealerProfileQuery(params: {
  keyword?: string;
  page?: number;
  size?: number;
}): string {
  const parts: string[] = [];
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return query(parts);
}

export function dealerRebateQuery(params: {
  dealerProfileId?: string;
  accrualState?: string;
  size?: number;
}): string {
  const parts: string[] = [];
  if (params.dealerProfileId)
    parts.push(`dealerProfileId=${encodeURIComponent(params.dealerProfileId)}`);
  if (params.accrualState) parts.push(`accrualState=${encodeURIComponent(params.accrualState)}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return query(parts);
}

export function dealerCandidatesQuery(params: { keyword?: string }): string {
  const parts: string[] = [];
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  return query(parts);
}

// ================================================================ 端点

// 查询串统一走**具名** query 函数，不在 URL 模板里就地拼条件表达式：
// 嵌套模板会让「从源码反向抽取真实调用点」的守卫读不懂这个调用点，
// 于是白名单漏登记时那条守卫恰好抽不到它 —— 守卫自己先失效了。

export function fetchCapabilities() {
  return api<DealerCapabilities>(`${base}/capabilities`);
}

/**
 * 经销商候选（**跨服务只读** 3.6.1.2）。
 *
 * <p>上游不可达时后端返回 503 而不是空清单；页面据此显示「商品主数据服务不可达」，
 * **不**显示「还没有经销商」—— 空清单会让运营以为可以放心签新的经销商。
 */
export function fetchCandidates(params: { keyword?: string } = {}) {
  return api<{ id: string; code: string; name: string; partyType: string; status: string }[]>(
    `${base}/candidates${dealerCandidatesQuery(params)}`,
  );
}

export function fetchProfiles(params: { keyword?: string; page?: number; size?: number } = {}) {
  return api<DealerProfilePage>(`${base}/profiles${dealerProfileQuery(params)}`);
}

export function saveProfile(input: DealerProfileInput) {
  return api<DealerProfileRow>(`${base}/profiles`, 'PUT', input);
}

/** 输出①：授权台账 */
export function fetchAuthorizations(params: DealerAuthorizationParams = {}) {
  return api<DealerAuthorizationPage>(`${base}/authorizations${dealerAuthorizationQuery(params)}`);
}

export function createAuthorization(input: DealerAuthorizationInput) {
  return api<DealerSaved>(`${base}/authorizations`, 'POST', input);
}

export function fetchAuthorizationDetail(id: string) {
  return api<DealerAuthorizationDetail>(`${base}/authorizations/${id}`);
}

export function changeAuthorizationStatus(id: string, input: DealerAuthorizationStatusInput) {
  return api<DealerSaved>(`${base}/authorizations/${id}/status`, 'PUT', input);
}

/** 输出②：配额与完成率 */
export function saveQuota(input: DealerQuotaInput) {
  return api<DealerSaved>(`${base}/quotas`, 'POST', input);
}

export function fetchQuotaAttainment(params: DealerAttainmentParams = {}) {
  return api<DealerQuotaAttainmentPage>(`${base}/quota-attainment${dealerAttainmentQuery(params)}`);
}

/** 销售与回款记录（配额完成率与窜货判定的唯一来源） */
export function fetchSales(params: DealerSaleParams = {}) {
  return api<DealerSalePage>(`${base}/sales${dealerSaleQuery(params)}`);
}

export function recordSale(input: DealerSaleInput) {
  return api<DealerSaleRow>(`${base}/sales`, 'POST', input);
}

/** 输出③：返点计提 */
export function fetchRebates(
  params: { dealerProfileId?: string; accrualState?: string; size?: number } = {},
) {
  return api<DealerRebatePage>(`${base}/rebates${dealerRebateQuery(params)}`);
}

export function accrueRebate(input: DealerRebateAccrueInput) {
  return api<DealerRebateRow>(`${base}/rebates/accrue`, 'POST', input);
}

/** 输出④：窜货预警 */
export function fetchAlerts(params: DealerAlertParams = {}) {
  return api<DealerAlertPage>(`${base}/diversion-alerts${dealerAlertQuery(params)}`);
}

export function changeAlertState(id: string, alertState: DealerAlertState, note: string) {
  return api<DealerDiversionAlertRow>(`${base}/diversion-alerts/${id}/state`, 'PUT', {
    alertState,
    note,
  });
}

// ================================================================ 展示格式化

/**
 * 完成率 → 百分比字符串。
 *
 * <p>`null` 渲染成「不考核」而不是 `0%`：目标为 0 表示**不限配额**，
 * 显示成 0% 会让运营去追一个不存在的缺口。后端用 `nullif(target,0)` 把它算成 null，
 * 这里照原样透传 —— 前端绝不为「后端没给数」编一个默认值。
 */
export function formatAttainment(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '不考核';
  return `${(rate * 100).toFixed(2)}%`;
}

/**
 * 完成率对应的 Badge 色调（仅用于着色，不参与任何计算）。
 *
 * <p>取 {@code @starcon/ui} 的 Badge tone 词表，不自造一套：自造一套会在
 * 「界面到底支持哪几个 tone」上多出第二个答案，症状是类型过不了或悄悄退回默认色。
 */
export function attainmentTone(
  rate: number | null | undefined,
): 'neutral' | 'danger' | 'warning' | 'success' {
  if (rate === null || rate === undefined) return 'neutral';
  if (rate < 0.5) return 'danger';
  if (rate < 1) return 'warning';
  return 'success';
}

/**
 * 返点口径标签。
 *
 * <p>`financeConfirmed=false` 是**常态**：蓝图写死「需与财务确认计提口径」，
 * 而 12 应付/返点未交付。所以这里永远不显示「已确认」以外的正面措辞。
 */
export function rebateBasisLabel(row: Pick<DealerRebateRow, 'financeConfirmed'>): string {
  return row.financeConfirmed ? '财务已确认口径' : '本模块口径 · 未与财务确认';
}

/** 合规状态标签：UNVERIFIED 要说清「没校验过」，不能留空让人以为是「通过」。 */
export function complianceLabel(state: DealerComplianceState): string {
  switch (state) {
    case 'VERIFIED':
      return '已通过合规校验';
    case 'REJECTED':
      return '合规否决';
    default:
      return '未校验（16 合规未交付）';
  }
}

/** 授权期限展示：两个落库日期，**不做任何时区或「今天 + N 天」推算**。 */
export function validityLabel(row: Pick<DealerAuthorizationRow, 'validFrom' | 'validTo'>): string {
  return `${row.validFrom} ~ ${row.validTo}`;
}

/** 越界维度的人话。 */
export function breachLabel(kind: DealerBreachKind): string {
  switch (kind) {
    case 'REGION':
      return '跨区域';
    case 'PRODUCT_LINE':
      return '跨产品线';
    default:
      return '跨区域 + 跨产品线';
  }
}
