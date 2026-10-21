import type {
  DevelopmentCostEstimateView,
  DevelopmentHandoffInput,
  DevelopmentHandoffRow,
  DevelopmentMarketInput,
  DevelopmentMarketObservationInput,
  DevelopmentMarketObservationRow,
  DevelopmentMarketRow,
  DevelopmentMilestoneInput,
  DevelopmentMilestoneRow,
  DevelopmentProjectCapabilities,
  DevelopmentProjectCreateInput,
  DevelopmentProjectDetail,
  DevelopmentProjectOptions,
  DevelopmentProjectPage,
  DevelopmentProjectRow,
  DevelopmentProjectSummary,
  DevelopmentProjectUpdateInput,
  DevelopmentSkuCandidate,
  DevelopmentSpecCandidateInput,
  DevelopmentSpecCandidateRow,
  DevelopmentStatusOption,
  DevelopmentTransitionInput,
  DevelopmentTransitionRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.6.1.1 开发立项前端模型。
 *
 * <p>蓝图：「管理新品开发立项：立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、
 * 打样与量产时间计划、状态（评估/立项/开发中/打样/试产/量产/终止）。立项通过后可派生打样单与试产单，
 * 并作为 SKU 建档的前置。」输出「开发单（立项记录 + 里程碑计划）；可转 SKU 建档的候选参数」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>目标毛利取不到就显示「取不到」，绝不显示 0.00% 或 100%</b> ——
 *       成本不可得时后端给的是 {@code null}，页面把它渲染成 0% 就是在说假话。</li>
 *   <li><b>百分比的两位小数只是显示格式</b>：后端给的小数口径（0.253456）是唯一真相，
 *       页面只做一次「× 100 再舍入到两位」的格式化，<b>不重算</b>毛利率。</li>
 *   <li><b>状态只显示后端给的合法去向</b>：下拉里的目标状态来自 {@code allowedTargets}，
 *       页面不自己维护一份状态机。</li>
 *   <li><b>「跳过打样 / 试产」要显式填豁免码与豁免说明</b>，页面不提供「悄悄跳过去」的按钮。</li>
 *   <li><b>市场数据取不到就说取不到</b>：14 广告的 reporting 服务不存在，
 *       页面显示「系统侧市场数据未交付（归 14 广告管）」，并保留手工录入入口
 *       （手工录入**必须**填来源与采集时间）。</li>
 *   <li><b>下游未交付的模块不假装可用</b>：6.6.1.2 / 6.6.2.1 只能产交接单，
 *       页面显示「已交接，待 6.6.1.2 消费」而不是「打样单已创建」。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/development-projects';

// ================================================================ 枚举（本地字面量联合）
//
// 🔴 契约里 enum 被 codegen **内联**成字符串联合（`"EVALUATION" | "APPROVED" | …`），
// 不会导出成具名类型。所以这三个联合在这里**显式声明**，
// 而不是从 api-contracts 里 import 一个并不存在的名字。

/** 立项状态：评估 / 立项 / 开发中 / 打样 / 试产 / 量产 / 终止。 */
export type DevelopmentStatus =
  | 'EVALUATION'
  | 'APPROVED'
  | 'IN_DEVELOPMENT'
  | 'SAMPLING'
  | 'PILOT'
  | 'MASS_PRODUCTION'
  | 'TERMINATED';

/** 交接单类型：打样单 / 试产单 / SKU 建档。 */
export type DevelopmentHandoffType = 'SAMPLING_ORDER' | 'PILOT_ORDER' | 'SKU_ONBOARDING';

/** 里程碑类型：打样 / 试产 / 量产 / 上市。 */
export type DevelopmentMilestoneType = 'SAMPLING' | 'PILOT' | 'MASS_PRODUCTION' | 'LAUNCH';

/** 里程碑状态：计划中 / 已达成 / 错过。 */
export type DevelopmentMilestoneStatus = 'PLANNED' | 'REACHED' | 'MISSED';

// ================================================================ 查询参数

export interface DevelopmentProjectParams {
  status?: string;
  category?: string;
  ownerId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export function developmentProjectQuery(params: DevelopmentProjectParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.category) parts.push(`category=${encodeURIComponent(params.category)}`);
  if (params.ownerId) parts.push(`ownerId=${encodeURIComponent(params.ownerId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 目标成本估算（预览）的查询串。 */
export function developmentCostEstimateQuery(referenceSkuId?: string): string {
  return referenceSkuId ? `?referenceSkuId=${encodeURIComponent(referenceSkuId)}` : '';
}

/** 目标成本估算（应用）的查询串。 */
export function developmentApplyCostEstimateQuery(
  referenceSkuId?: string,
  referenceSkuCode?: string,
): string {
  const parts: string[] = [];
  if (referenceSkuId) parts.push(`referenceSkuId=${encodeURIComponent(referenceSkuId)}`);
  if (referenceSkuCode) parts.push(`referenceSkuCode=${encodeURIComponent(referenceSkuCode)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchDevelopmentProjectCapabilities() {
  return api<DevelopmentProjectCapabilities>(`${base}/capabilities`);
}

export function fetchDevelopmentProjectOptions() {
  return api<DevelopmentProjectOptions>(`${base}/options`);
}

export function fetchDevelopmentProjectSummary() {
  return api<DevelopmentProjectSummary>(`${base}/summary`);
}

export function fetchDevelopmentProjects(params: DevelopmentProjectParams) {
  return api<DevelopmentProjectPage>(`${base}${developmentProjectQuery(params)}`);
}

export function fetchDevelopmentProject(id: string) {
  return api<DevelopmentProjectDetail>(`${base}/${encodeURIComponent(id)}`);
}

export function createDevelopmentProject(input: DevelopmentProjectCreateInput) {
  return api<DevelopmentProjectDetail>(`${base}`, 'POST', input);
}

export function updateDevelopmentProject(id: string, input: DevelopmentProjectUpdateInput) {
  return api<DevelopmentProjectDetail>(`${base}/${encodeURIComponent(id)}`, 'PUT', input);
}

export function transitionDevelopmentProject(id: string, input: DevelopmentTransitionInput) {
  return api<DevelopmentProjectDetail>(`${base}/${encodeURIComponent(id)}/transitions`, 'POST', input);
}

export function fetchDevelopmentMilestones(projectId: string) {
  return api<DevelopmentMilestoneRow[]>(`${base}/${encodeURIComponent(projectId)}/milestones`);
}

export function createDevelopmentMilestone(projectId: string, input: DevelopmentMilestoneInput) {
  return api<DevelopmentMilestoneRow[]>(
    `${base}/${encodeURIComponent(projectId)}/milestones`,
    'POST',
    input,
  );
}

export function updateDevelopmentMilestone(
  projectId: string,
  milestoneId: string,
  input: DevelopmentMilestoneInput,
) {
  return api<DevelopmentMilestoneRow[]>(
    `${base}/${encodeURIComponent(projectId)}/milestones/${encodeURIComponent(milestoneId)}`,
    'PUT',
    input,
  );
}

export function deleteDevelopmentMilestone(projectId: string, milestoneId: string) {
  return api<DevelopmentMilestoneRow[]>(
    `${base}/${encodeURIComponent(projectId)}/milestones/${encodeURIComponent(milestoneId)}`,
    'DELETE',
  );
}

export function fetchDevelopmentMarkets(projectId: string) {
  return api<DevelopmentMarketRow[]>(`${base}/${encodeURIComponent(projectId)}/markets`);
}

export function createDevelopmentMarket(projectId: string, input: DevelopmentMarketInput) {
  return api<DevelopmentMarketRow[]>(`${base}/${encodeURIComponent(projectId)}/markets`, 'POST', input);
}

export function deleteDevelopmentMarket(projectId: string, marketId: string) {
  return api<DevelopmentMarketRow[]>(
    `${base}/${encodeURIComponent(projectId)}/markets/${encodeURIComponent(marketId)}`,
    'DELETE',
  );
}

export function fetchDevelopmentSpecCandidates(projectId: string) {
  return api<DevelopmentSpecCandidateRow[]>(
    `${base}/${encodeURIComponent(projectId)}/spec-candidates`,
  );
}

export function createDevelopmentSpecCandidate(
  projectId: string,
  input: DevelopmentSpecCandidateInput,
) {
  return api<DevelopmentSpecCandidateRow[]>(
    `${base}/${encodeURIComponent(projectId)}/spec-candidates`,
    'POST',
    input,
  );
}

export function deleteDevelopmentSpecCandidate(projectId: string, candidateId: string) {
  return api<DevelopmentSpecCandidateRow[]>(
    `${base}/${encodeURIComponent(projectId)}/spec-candidates/${encodeURIComponent(candidateId)}`,
    'DELETE',
  );
}

export function fetchDevelopmentMarketObservations(projectId: string) {
  return api<DevelopmentMarketObservationRow[]>(
    `${base}/${encodeURIComponent(projectId)}/market-observations`,
  );
}

export function createDevelopmentMarketObservation(
  projectId: string,
  input: DevelopmentMarketObservationInput,
) {
  return api<DevelopmentMarketObservationRow[]>(
    `${base}/${encodeURIComponent(projectId)}/market-observations`,
    'POST',
    input,
  );
}

export function fetchDevelopmentCostEstimate(projectId: string, referenceSkuId?: string) {
  return api<DevelopmentCostEstimateView>(
    `${base}/${encodeURIComponent(projectId)}/cost-estimate${developmentCostEstimateQuery(
      referenceSkuId,
    )}`,
  );
}

export function applyDevelopmentCostEstimate(
  projectId: string,
  referenceSkuId?: string,
  referenceSkuCode?: string,
) {
  return api<DevelopmentProjectDetail>(
    `${base}/${encodeURIComponent(projectId)}/cost-estimate${developmentApplyCostEstimateQuery(
      referenceSkuId,
      referenceSkuCode,
    )}`,
    'POST',
  );
}

export function fetchDevelopmentSkuCandidate(projectId: string) {
  return api<DevelopmentSkuCandidate>(`${base}/${encodeURIComponent(projectId)}/sku-candidate`);
}

export function fetchDevelopmentHandoffs(projectId: string) {
  return api<DevelopmentHandoffRow[]>(`${base}/${encodeURIComponent(projectId)}/handoffs`);
}

export function createDevelopmentHandoff(projectId: string, input: DevelopmentHandoffInput) {
  return api<DevelopmentHandoffRow>(
    `${base}/${encodeURIComponent(projectId)}/handoffs`,
    'POST',
    input,
  );
}

// ================================================================ 展示格式化

/** 金额 / 数量：null 显示成「无数据」，**绝不显示 0**。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '无数据';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 4 });
}

/** 带币种的金额。币种为空时只显示数字（不补一个猜出来的币种）。 */
export function money(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined) return '无数据';
  const text = value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
  return currency ? `${text} ${currency}` : text;
}

/**
 * 目标毛利的显示文本。
 *
 * 🔴 小数口径（0.253456）⇒ 百分比显示（25.35%）。**只做一次**格式化，
 *    不重算毛利率；null ⇒ 「取不到（成本不可得）」，**不是** 0.00% 也不是 100%。
 */
export function marginText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '取不到（成本不可得）';
  return `${(rate * 100).toFixed(2)}%`;
}

/** 目标毛利的数值文本（只给数字，不带 %）。 */
export function marginNumberText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '无数据';
  return (rate * 100).toFixed(2);
}

/** 目标成本的显示文本：取不到就说取不到，并点名归谁管。 */
export function costText(
  cost: number | null | undefined,
  currency: string | null | undefined,
  available: boolean,
): string {
  if (!available || cost === null || cost === undefined) return '取不到';
  return money(cost, currency);
}

/** 状态的中文名（后端已经给了 statusLabel，这里只兜底）。 */
export function statusLabel(row: { status: string; statusLabel?: string }): string {
  if (row.statusLabel) return row.statusLabel;
  const found = STATUS_LABELS[row.status];
  return found ?? row.status;
}

/** 状态徽章的色调（与 @starcon/ui 的 Badge 七个 tone 对齐）。 */
export function statusTone(
  status: string,
): 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'EVALUATION':
      return 'neutral';
    case 'APPROVED':
      return 'info';
    case 'IN_DEVELOPMENT':
      return 'primary';
    case 'SAMPLING':
      return 'warning';
    case 'PILOT':
      return 'warning';
    case 'MASS_PRODUCTION':
      return 'success';
    case 'TERMINATED':
      return 'danger';
    default:
      return 'neutral';
  }
}

const STATUS_LABELS: Record<string, string> = {
  EVALUATION: '评估',
  APPROVED: '立项',
  IN_DEVELOPMENT: '开发中',
  SAMPLING: '打样',
  PILOT: '试产',
  MASS_PRODUCTION: '量产',
  TERMINATED: '终止',
};

/** 里程碑状态的中文名。 */
export function milestoneStatusLabel(status: string): string {
  switch (status) {
    case 'PLANNED':
      return '计划中';
    case 'REACHED':
      return '已达成';
    case 'MISSED':
      return '错过';
    default:
      return status;
  }
}

/** 里程碑状态的色调。 */
export function milestoneStatusTone(status: string): 'neutral' | 'success' | 'warning' {
  switch (status) {
    case 'PLANNED':
      return 'neutral';
    case 'REACHED':
      return 'success';
    case 'MISSED':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 交接单状态的文案：下游未交付 ⇒ 显示「已交接，待 X 消费」。 */
export function handoffStatusText(status: string, targetModule: string): string {
  switch (status) {
    case 'ACCEPTED':
      return `${targetModule} 已接收`;
    case 'CANCELLED':
      return '已撤销';
    default:
      return `已交接，待 ${targetModule} 消费`;
  }
}

/** 时间显示：null 显示「未安排」。 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '未安排';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toISOString().slice(0, 16).replace('T', ' ');
}

/** 成本来源的中文名。 */
export function costSourceLabel(source: string): string {
  switch (source) {
    case 'CATALOG_ESTIMATE':
      return '5.3.1.1 利润测算器';
    case 'MANUAL_ESTIMATE':
      return '手工估算';
    default:
      return '取不到';
  }
}

/** 目标状态选项（来自后端的 allowedTargets，页面不自己维护状态机）。 */
export function allowedTargetOptions(
  options: DevelopmentProjectOptions | null,
  current: string,
): DevelopmentStatusOption[] {
  const found = options?.statuses.find((item) => item.value === current);
  if (!found) return [];
  return options?.statuses.filter((item) => found.allowedTargets.includes(item.value)) ?? [];
}

/** 这次迁移是否需要显式豁免（跳过打样 / 试产）。 */
export function transitionNeedsExemption(from: string, to: string): boolean {
  if (from === 'IN_DEVELOPMENT' && (to === 'PILOT' || to === 'MASS_PRODUCTION')) return true;
  if (from === 'SAMPLING' && to === 'MASS_PRODUCTION') return true;
  return false;
}

/** 状态机的中文提示（页面顶部常驻）。 */
export function stateMachineHint(caps: DevelopmentProjectCapabilities | null): string {
  return caps?.stateMachineNote ?? '状态只能沿合法迁移推进；终止是终态，不提供恢复。';
}
