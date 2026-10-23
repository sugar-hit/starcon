import type {
  FinanceCollectionTaskInput,
  FinanceCollectionTaskPage,
  FinanceCollectionTaskRow,
  FinanceReceivableAging,
  FinanceReceivableCapabilities,
  FinanceReceivableDetail,
  FinanceReceivableDisputeInput,
  FinanceReceivableImportResult,
  FinanceReceivableInput,
  FinanceReceivableOptions,
  FinanceReceivablePage,
  FinanceReceivablePolicyInput,
  FinanceReceivablePolicyView,
  FinanceReceivableRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.1.1.1 应收单管理的前端模型。
 *
 * <p>蓝图 12-finance.md：「管理应收账款：来源（平台结算、平台代扣、B2B 客户账期、其他）、
 * 应收对象、金额与币种、到期日、账龄、明细核销状态。」输出：「应收单 + 明细行 + 账龄分布；
 * 生成催收任务。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：占比、合计、逾期额都由**服务端**算好，
 *       页面**不做任何前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总按币种分组渲染，**绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>「未配置」与「已禁用」是两件事</b>：应收确认政策里，没配过（你还没决定）
 *       与配了但关掉（你决定了这个来源不产生应收）必须分开显示。</li>
 *   <li><b>上游没交付的事就说没交付</b>：平台结算 / 平台代扣两个来源当前**不可产生**
 *       （上游 12.3.1 结算未交付），页面**不提供**用它们建单的入口，
 *       只在能力声明里如实报出归属模块。</li>
 *   <li><b>核销相关一律只读</b>：核销（12.2.1.2）与坏账（12.1.1.3）**均未交付**，
 *       页面只显示明细的核销状态与已核销金额，**不提供**任何改核销状态的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有应收单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/receivables}，与同服务的其它卷 12 模块
 * （12.2 收付款 / 12.3 结算 / 12.5 总账 …）刻意不共用前缀 ——
 * 那些模块还没交付，等它们落地时各自有自己的前缀。
 */

const base = 'finance/receivables';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface ReceivableParams {
  status?: string | undefined;
  source?: string | undefined;
  currency?: string | undefined;
  counterpartyRef?: string | undefined;
  shopId?: string | undefined;
  disputeOnly?: boolean | undefined;
  overdueOnly?: boolean | undefined;
  dueFrom?: string | undefined;
  dueTo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 催收任务筛选条件。 */
export interface CollectionTaskParams {
  status?: string | undefined;
  level?: string | undefined;
  receivableId?: string | undefined;
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
export function fetchReceivableCapabilities() {
  return api<FinanceReceivableCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchReceivableOptions() {
  return api<FinanceReceivableOptions>(`${base}/options`);
}

/** 应收单列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchReceivables(params: ReceivableParams = {}) {
  return api<FinanceReceivablePage>(`${base}${query({ ...params })}`);
}

/** 应收单详情：单头 + 明细行 + 事件流 + 催收任务。 */
export function fetchReceivable(id: string) {
  return api<FinanceReceivableDetail>(`${base}/${encodeURIComponent(id)}`);
}

/**
 * 人工录入一张应收单。
 *
 * <p>⚠️ 只有「人工录入」与「其他」能手工建单：B2B 客户账期应收**只能**由发货确认产生
 * （手工录入等于自己编一个发货确认事实），平台两个来源的上游还没交付。
 */
export function createReceivable(input: FinanceReceivableInput) {
  return api<FinanceReceivableRow>(`${base}`, 'POST', input);
}

/**
 * 从上游（9 订单发货确认）导入账期应收。
 *
 * <p>⚠️ 上游读不到时后端**整体失败**（503），不会返回「导入 0 条」。
 * 页面必须把失败显式显示出来，绝不能让它看起来像「跑过了但一条都没有」。
 */
export function importReceivablesFromShipmentConfirmations() {
  return api<FinanceReceivableImportResult>(`${base}/import-b2b`, 'POST');
}

/** 作废（红冲的等价物）：终态，整单冻结。已核销的不可作废。 */
export function voidReceivable(id: string, reason: string) {
  return api<FinanceReceivableRow>(`${base}/${encodeURIComponent(id)}/void`, 'POST', { reason });
}

/** 标注 / 取消「争议中」（争议事实来自 11.4.1.1，当前只支持人工标注）。 */
export function flagReceivableDispute(id: string, input: FinanceReceivableDisputeInput) {
  return api<FinanceReceivableRow>(`${base}/${encodeURIComponent(id)}/dispute`, 'POST', input);
}

/** 账龄分布：五档 × 币种，争议中金额单列。 */
export function fetchReceivableAging() {
  return api<FinanceReceivableAging>(`${base}/aging`);
}

/** 催收任务列表：真分页 + 真筛选。 */
export function fetchCollectionTasks(params: CollectionTaskParams = {}) {
  return api<FinanceCollectionTaskPage>(`${base}/collection-tasks${query({ ...params })}`);
}

/** 给一张应收单建催收任务（同一张单同一级别只允许一条）。 */
export function createCollectionTask(receivableId: string, input: FinanceCollectionTaskInput) {
  return api<FinanceCollectionTaskRow>(
    `${base}/${encodeURIComponent(receivableId)}/collection-tasks`,
    'POST',
    input,
  );
}

/** 批量生成逾期催收任务（幂等：已有同级别任务的不重复建）。 */
export function generateOverdueCollectionTasks() {
  return api<FinanceCollectionTaskRow[]>(`${base}/collection-tasks/generate`, 'POST');
}

/** 关闭催收任务。 */
export function closeCollectionTask(taskId: string, reason: string) {
  return api<FinanceCollectionTaskRow>(
    `${base}/collection-tasks/${encodeURIComponent(taskId)}/close`,
    'POST',
    { reason },
  );
}

/** 应收确认政策：五个来源全列（含未配置的）。 */
export function fetchReceivablePolicies() {
  return api<FinanceReceivablePolicyView[]>(`${base}/policies`);
}

/** 配置一个来源的确认政策（幂等 upsert）。 */
export function updateReceivablePolicy(source: string, input: FinanceReceivablePolicyInput) {
  return api<FinanceReceivablePolicyView>(`${base}/policies/${encodeURIComponent(source)}`, 'PUT', {
    ...input,
  });
}

// ================================================================ 可读形态

/** 应收来源的可读名。 */
export function receivableSourceLabel(source: string): string {
  switch (source) {
    case 'PLATFORM_SETTLEMENT':
      return '平台结算';
    case 'PLATFORM_WITHHOLDING':
      return '平台代扣';
    case 'B2B_CREDIT_TERM':
      return 'B2B 客户账期';
    case 'MANUAL':
      return '人工录入';
    case 'OTHER':
      return '其他';
    default:
      return source;
  }
}

/**
 * 应收状态的可读名。
 *
 * <p>⚠️ 「未核销」与「部分核销」必须分开：后者说明**已经收回来一部分**，
 * 合成一个「未结清」会让催收的人以为一分没收到。
 */
export function receivableStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '未核销';
    case 'PARTIALLY_SETTLED':
      return '部分核销';
    case 'SETTLED':
      return '已核销';
    case 'VOID':
      return '已作废';
    case 'DRAFT':
      return '草稿';
    default:
      return status;
  }
}

/** 应收状态的可读色调。 */
export function receivableStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'OPEN':
      return 'warning';
    case 'PARTIALLY_SETTLED':
      return 'warning';
    case 'SETTLED':
      return 'success';
    case 'VOID':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 应收对象类型的可读名。 */
export function counterpartyTypeLabel(type: string): string {
  switch (type) {
    case 'PLATFORM':
      return '平台';
    case 'CUSTOMER':
      return '客户';
    case 'OTHER':
      return '其他';
    default:
      return type;
  }
}

/** 账龄区间的可读名。 */
export function agingBucketLabel(bucket: string): string {
  switch (bucket) {
    case 'NOT_DUE':
      return '未到期';
    case 'D1_30':
      return '逾期 1-30 天';
    case 'D31_60':
      return '逾期 31-60 天';
    case 'D61_90':
      return '逾期 61-90 天';
    case 'D90_PLUS':
      return '逾期 90 天以上';
    default:
      return bucket;
  }
}

/** 催收级别的可读名。 */
export function collectionTaskLevelLabel(level: string): string {
  switch (level) {
    case 'REMINDER':
      return '到期提醒';
    case 'OVERDUE':
      return '逾期催收';
    case 'ESCALATION':
      return '升级处理';
    default:
      return level;
  }
}

/** 催收任务状态的可读名。 */
export function collectionTaskStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '待处理';
    case 'DONE':
      return '已完成';
    case 'CANCELLED':
      return '已取消';
    default:
      return status;
  }
}

/** 事件类型的可读名。 */
export function receivableEventLabel(eventType: string): string {
  switch (eventType) {
    case 'CREATED':
      return '建单';
    case 'CONFIRMED':
      return '确认';
    case 'IMPORTED':
      return '从发货确认导入';
    case 'DISPUTE_FLAGGED':
      return '标注争议';
    case 'DISPUTE_CLEARED':
      return '取消争议标注';
    case 'VOIDED':
      return '作废';
    case 'COLLECTION_TASK_CREATED':
      return '生成催收任务';
    case 'COLLECTION_TASK_CLOSED':
      return '关闭催收任务';
    default:
      return eventType;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 * 分母为 0 / 未登记时后端给 {@code null} —— 这里显示「未登记」而不是 0。
 */
export function formatReceivableAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatReceivableMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未登记';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 应收确认政策的状态可读形态。
 *
 * <p>🔴 **「未配置」与「已禁用」是两件不同的事**，这里刻意用不同的文案：
 * 把它们合成一个「否」会让「你还没决定」看起来像「你决定了这个来源不产生应收」。
 */
export function receivablePolicyStateLabel(policy: FinanceReceivablePolicyView): string {
  if (!policy.configured) return '未配置';
  if (!policy.enabled) return '已禁用';
  return policy.dueDays > 0 ? `已启用 · 账期 ${policy.dueDays} 天` : '已启用 · 确认即到期';
}

/** 政策状态的可读色调。 */
export function receivablePolicyStateTone(
  policy: FinanceReceivablePolicyView,
): 'success' | 'warning' | 'danger' | 'neutral' {
  if (!policy.configured) return 'neutral';
  if (!policy.enabled) return 'danger';
  if (!policy.producible) return 'warning';
  return 'success';
}

/**
 * 上游来源的可读形态（谁产出了这张应收）。
 *
 * <p>⚠️ 「人工录入」与「从发货确认导入」必须能分开看 ——
 * 运营要知道这张应收是谁给的。
 */
export function receivableSourceSystemLabel(sourceSystem: string): string {
  switch (sourceSystem) {
    case 'manual':
      return '人工录入';
    case 'trade':
      return '从发货确认导入';
    default:
      return sourceSystem;
  }
}

/**
 * 逾期天数的可读形态（**不**在前端算日期差 —— 后端已经分好桶了）。
 */
export function receivableOverdueHint(row: FinanceReceivableRow, asOfDate: string): string {
  if (row.status === 'SETTLED' || row.status === 'VOID') return '—';
  if (row.dueDate >= asOfDate) return '未到期';
  return `逾期中（到期日 ${row.dueDate}）`;
}
