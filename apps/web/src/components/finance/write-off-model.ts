import type {
  FinanceWriteOffAdvanceBalance,
  FinanceWriteOffAdvanceRow,
  FinanceWriteOffAllocationInput,
  FinanceWriteOffAllocationRow,
  FinanceWriteOffAutoMatchInput,
  FinanceWriteOffAutoMatchResult,
  FinanceWriteOffCapabilities,
  FinanceWriteOffDetail,
  FinanceWriteOffInput,
  FinanceWriteOffMatchCandidate,
  FinanceWriteOffOptions,
  FinanceWriteOffPage,
  FinanceWriteOffPending,
  FinanceWriteOffRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.2.1.2 核销的前端模型。
 *
 * <p>蓝图 12-finance.md：「把收款核销到应收：自动匹配（金额+订单号+日期）或人工指定，
 * 支持部分核销、合并核销、预收挂账、核销撤销。」输出：「核销记录 + 应收余额更新 + 预收余额。」
 * 加粗句：「<b>核销必须精确到明细行</b>，只核销到订单头无法处理部分回款与折让。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：汇总与余额都由**服务端**算好，页面**不做任何前端算术**
 *       （USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>核销的目标是明细行</b>：页面选的是**应收明细行**，不是应收单头 ——
 *       页面上每一行都显示行号、订单号与该行未核销余额。</li>
 *   <li><b>自动匹配要说清「凭什么匹配上」</b>：三个条件（订单号 + 金额 + 日期）逐条展示，
 *       命中原因由服务端给出，前端不自己解释。</li>
 *   <li><b>预收 ⊂ 未核销余额</b>：预收余额分两段显示（挂账 / 预收标记），
 *       页面明确说明挂着的钱是**专用**的。</li>
 *   <li><b>下游没交付的事就说没交付</b>：回款口径（13 利润）**未交付**，
 *       页面显示能力声明里的缺口归属，不提供任何假装能出利润报表的入口。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有核销记录」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/write-off}，与同服务的 12.1.1.1（{@code finance/receivables}）、
 * 12.2.1.1（{@code finance/receipts}）刻意不共用前缀 —— 核销是**跨两张单据的动作**。
 */

const base = 'finance/write-off';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface WriteOffParams {
  status?: string | undefined;
  mode?: string | undefined;
  currency?: string | undefined;
  receiptId?: string | undefined;
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

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchWriteOffCapabilities() {
  return api<FinanceWriteOffCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchWriteOffOptions() {
  return api<FinanceWriteOffOptions>(`${base}/options`);
}

/** 核销记录列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchWriteOffs(params: WriteOffParams = {}) {
  return api<FinanceWriteOffPage>(`${base}${query({ ...params })}`);
}

/** 核销记录详情：单头 + 分配明细 + 事件流。 */
export function fetchWriteOff(id: string) {
  return api<FinanceWriteOffDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 待核销视图：可核销的收款单 + 可核销的应收明细行（两侧都要能选）。 */
export function fetchWriteOffPending() {
  return api<FinanceWriteOffPending>(`${base}/pending`);
}

/** 预收余额（按币种分开；分「挂账」与「预收标记」两段）。 */
export function fetchWriteOffAdvanceBalance() {
  return api<FinanceWriteOffAdvanceBalance>(`${base}/advance-balance`);
}

/** 预收挂账行列表。 */
export function fetchWriteOffAdvanceRows(status?: string, currency?: string) {
  return api<FinanceWriteOffAdvanceRow[]>(
    `${base}/advance${query({ status, currency })}`,
  );
}

/** 自动匹配的候选（只读，不落库）：先看清「凭什么匹配上」再决定要不要核销。 */
export function fetchWriteOffMatchCandidates(params: {
  receiptId: string;
  orderNo: string;
  amount?: number | undefined;
  matchDate?: string | undefined;
  dateToleranceDays?: number | undefined;
}) {
  return api<FinanceWriteOffMatchCandidate[]>(
    `${base}/auto-match/candidates${query({ ...params })}`,
  );
}

/**
 * 人工指定核销。
 *
 * <p>⚠️ 分配里可以混两种类型：SETTLEMENT（核销到应收**明细行**）与 ADVANCE（预收挂账）。
 */
export function createWriteOff(input: FinanceWriteOffInput) {
  return api<FinanceWriteOffDetail>(`${base}`, 'POST', input);
}

/**
 * 自动匹配核销。
 *
 * <p>⚠️ 命中 0 条时后端**整体失败**（400）—— 页面必须把这句话原样显示出来，
 * 绝不能让它看起来像「跑过了但没匹配上」。
 */
export function autoMatchWriteOff(input: FinanceWriteOffAutoMatchInput) {
  return api<FinanceWriteOffAutoMatchResult>(`${base}/auto-match`, 'POST', input);
}

/**
 * 核销撤销：把明细行的已核销金额**真的退回去**。
 *
 * <p>⚠️ 应收单**整单核销完成**后是上游写死的终态 ⇒ 那时撤销会被拒，
 * 页面必须把后端给的解释原样显示（替代路径是「作废 + 新单」，属 12.1.1.3）。
 */
export function reverseWriteOff(id: string, reason: string) {
  return api<FinanceWriteOffDetail>(`${base}/${encodeURIComponent(id)}/reverse`, 'POST', { reason });
}

/**
 * 释放一笔预收挂账：这笔钱不再专用，可以拿去核销应收。
 *
 * <p>⚠️ 挂着的钱是**专用**的（核销时按「未核销余额 − 未释放挂账额」封顶），
 * 所以要动用它必须先显式释放 —— 不静默改变口径。
 */
export function releaseWriteOffAdvance(ledgerId: string) {
  return api<FinanceWriteOffAdvanceRow>(
    `${base}/advance/${encodeURIComponent(ledgerId)}/release`,
    'POST',
  );
}

// ================================================================ 可读形态

/** 核销方式的可读名。 */
export function writeOffModeLabel(mode: string): string {
  switch (mode) {
    case 'AUTO_MATCH':
      return '自动匹配';
    case 'MANUAL':
      return '人工指定';
    case 'ADVANCE_PARKING':
      return '预收挂账';
    default:
      return mode;
  }
}

/** 核销状态的可读名。 */
export function writeOffStatusLabel(status: string): string {
  switch (status) {
    case 'POSTED':
      return '已核销';
    case 'REVERSED':
      return '已撤销';
    default:
      return status;
  }
}

/** 核销状态的可读色调。 */
export function writeOffStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'POSTED':
      return 'success';
    case 'REVERSED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 分配类型的可读名。 */
export function allocationTypeLabel(type: string): string {
  switch (type) {
    case 'SETTLEMENT':
      return '核销到应收明细行';
    case 'ADVANCE':
      return '预收挂账';
    default:
      return type;
  }
}

/** 预收挂账状态的可读名。 */
export function advanceStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '挂账中';
    case 'RELEASED':
      return '已释放';
    case 'REVERSED':
      return '已撤销';
    default:
      return status;
  }
}

/** 事件类型的可读名。 */
export function writeOffEventLabel(eventType: string): string {
  switch (eventType) {
    case 'AUTO_MATCHED':
      return '自动匹配核销';
    case 'MANUAL_ASSIGNED':
      return '人工指定核销';
    case 'ADVANCE_PARKED':
      return '预收挂账';
    case 'RELEASED':
      return '释放预收挂账';
    case 'REVERSED':
      return '核销撤销';
    default:
      return eventType;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 * 后端给 {@code null} 时显示「未登记」而不是 0。
 */
export function formatWriteOffAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatWriteOffMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未登记';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 核销时刻的可读形态。
 *
 * <p>⚠️ 只做显示格式化，**不**在前端做时区换算 ——
 * 后端存的是 timestamptz，展示用浏览器本地时区即可（换算只是显示，不影响账）。
 */
export function formatOccurredAt(value: string | null | undefined): string {
  if (!value) return '未登记';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 自动匹配条件的可读形态。
 *
 * <p>⚠️ 页面必须让操作者看清**三个条件**都在生效 —— 只显示「匹配成功」会让人以为
 * 系统是按金额乱配的（卷 12 明写「不允许金额相同即匹配」）。
 */
export function matchConditionHint(candidate: FinanceWriteOffMatchCandidate): string {
  return candidate.reasons.join('；');
}

/**
 * 预收余额两段口径的可读形态。
 *
 * <p>⚠️ 两个口径**互不重复计数**：本模块挂账的预收 + 12.2.1.1 预收标记收款单的未核销余额。
 */
export function advanceBalanceHint(
  row: { parkedAmount: number; flaggedAmount: number; totalAmount: number },
): string {
  return `挂账 ${formatWriteOffAmount(row.parkedAmount)} + 预收标记 ${formatWriteOffAmount(
    row.flaggedAmount,
  )} = ${formatWriteOffAmount(row.totalAmount)}`;
}

/**
 * 分配明细的可读形态。
 *
 * <p>⚠️ **核销精确到明细行**：页面上必须能看到行号与订单号，
 * 否则「核销到了哪一行」就答不上来。
 */
export function allocationTargetLabel(allocation: FinanceWriteOffAllocationRow): string {
  if (allocation.allocationType === 'ADVANCE') return '预收挂账（还没有对应应收）';
  const line = allocation.lineNo === null ? '—' : `第 ${allocation.lineNo} 行`;
  const order = allocation.orderNo ? ` · 订单 ${allocation.orderNo}` : '';
  return `${allocation.receivableNo ?? '—'} ${line}${order}`;
}
