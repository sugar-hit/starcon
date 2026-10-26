import type {
  FinancePeriodCloseCapabilities,
  FinancePeriodCloseCheckAmountRow,
  FinancePeriodCloseCheckItemPage,
  FinancePeriodCloseCheckItemRow,
  FinancePeriodCloseChecklistDetail,
  FinancePeriodCloseChecklistPage,
  FinancePeriodCloseCheckRow,
  FinancePeriodCloseCurrencyGroup,
  FinancePeriodCloseExecuteInput,
  FinancePeriodCloseExecutionDetail,
  FinancePeriodCloseExecutionPage,
  FinancePeriodCloseOptions,
  FinancePeriodCloseSubmitResult,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 12.5.1.3 关账的前端模型。
 *
 * <p>蓝图 12-finance.md：「期末关账流程：检查未处理事项（未对账差异、未结转成本、未核销款项、
 * 未过账凭证），按已批准清单区分阻断项与可结转未清项后关闭期间；禁止向已关闭期间录入业务凭证。」
 * 输入：「关账检查清单结果；关账确认。」输出：「关账完成记录；期间锁定。」
 * 关系：「上游 = 12.5.1.2；下游 = 12.5.2.2 报表。**关账检查清单是防错最后一道关**：
 * 阻断项未解决不允许关账；正常未到期应收应付不要求清零，例外与结转须保留依据。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>不可判定就是不可判定</b>：上游 12.3.2.1 / 12.3.2.2 未交付 ⇒ 「未对账差异」
 *       显示为「不可判定」，**绝不**显示成「通过」或「无差异」。</li>
 *   <li><b>阻断项与可结转未清项分开说</b>：阻断项是 danger，可结转未清项是 warning ——
 *       两者混成一句「有问题」就等于没防错。</li>
 *   <li><b>例外依据必须看得见</b>：被下调为可结转的项，页面要显示它**凭什么**被结转。</li>
 *   <li><b>多币种绝不相加</b>：金额一律按币种分组呈现，页面**不产出**一个跨币种的「合计」。</li>
 *   <li><b>关账结局如实说</b>：审批中 / 已驳回 / 已关账三种结局分开显示；
 *       只有「已关账」才真的锁定期间。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/period-close}，与同服务的其它端点首 token 全不相同
 * （{@code receivables} / {@code settlement} / {@code vouchers} / {@code accounting-periods} …）。
 */

/** 后端命名空间（与 api-proxy 白名单里的首 token 一致）。 */
const base = 'finance/period-close';

/** 检查项编码（与契约里的枚举一致）。 */
export type PeriodCloseCheckCodeValue =
  | 'UNRECONCILED_DIFFERENCE'
  | 'UNCARRIED_COST'
  | 'UNSETTLED_PAYMENT'
  | 'UNPOSTED_VOUCHER';

/** 分类：阻断项 / 可结转未清项。 */
export type PeriodCloseClassificationValue = 'BLOCKING' | 'CARRYWARD';

/** 检查结果。 */
export type PeriodCloseResultValue = 'PASS' | 'FAIL' | 'NOT_DETERMINABLE';

/** 关账执行状态。 */
export type PeriodCloseExecutionStatusValue =
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'CLOSED';

/** 提交关账的结局。 */
export type PeriodCloseSubmitOutcome = 'BLOCKED' | 'PENDING_APPROVAL' | 'CLOSED';

/**
 * 清单列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface PeriodCloseChecklistParams {
  periodId?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 关账执行记录的筛选条件。 */
export interface PeriodCloseExecutionParams {
  periodId?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 明细钻取的筛选条件。 */
export interface PeriodCloseItemParams {
  checkId?: string | undefined;
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
export function fetchPeriodCloseCapabilities() {
  return api<FinancePeriodCloseCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchPeriodCloseOptions() {
  return api<FinancePeriodCloseOptions>(`${base}/options`);
}

/** 检查清单列表：真分页 + 真筛选。 */
export function fetchPeriodCloseChecklists(params: PeriodCloseChecklistParams = {}) {
  return api<FinancePeriodCloseChecklistPage>(`${base}/checklists${query({ ...params })}`);
}

/** 跑一次关账检查清单（真查数据）。 */
export function runPeriodCloseChecklist(periodId: string, note: string) {
  return api<FinancePeriodCloseChecklistDetail>(`${base}/checklists`, 'POST', {
    periodId,
    note,
  });
}

/** 清单详情：清单 + 四类检查项 + 按币种的金额分组。 */
export function fetchPeriodCloseChecklist(id: string) {
  return api<FinancePeriodCloseChecklistDetail>(`${base}/checklists/${encodeURIComponent(id)}`);
}

/** 检查明细（可钻取）：真分页。 */
export function fetchPeriodCloseCheckItems(id: string, params: PeriodCloseItemParams = {}) {
  return api<FinancePeriodCloseCheckItemPage>(
    `${base}/checklists/${encodeURIComponent(id)}/items${query({ ...params })}`,
  );
}

/**
 * 把一个**阻断项**下调为**可结转未清项**（例外）。
 *
 * <p>🔴 **必须写例外依据** —— 蓝图加粗句「例外与结转须保留依据」。
 */
export function carryForwardPeriodCloseCheck(
  checklistId: string,
  checkId: string,
  exceptionBasis: string,
) {
  return api<FinancePeriodCloseCheckRow>(
    `${base}/checklists/${encodeURIComponent(checklistId)}/checks/${encodeURIComponent(checkId)}/carry-forward`,
    'POST',
    { exceptionBasis },
  );
}

/** 关账执行记录列表：真分页 + 真筛选。 */
export function fetchPeriodCloseExecutions(params: PeriodCloseExecutionParams = {}) {
  return api<FinancePeriodCloseExecutionPage>(`${base}/executions${query({ ...params })}`);
}

/** 关账执行详情：执行记录 + 审批链路 + 据以关账的那份清单。 */
export function fetchPeriodCloseExecution(id: string) {
  return api<FinancePeriodCloseExecutionDetail>(`${base}/executions/${encodeURIComponent(id)}`);
}

/**
 * **同步审批结果**；审批已批准 ⇒ 真的执行关账（期间随即锁定、禁止过账）。
 *
 * <p>🔴 审批不可达 ⇒ 服务端报错、期间保持开放 —— 页面**绝不**显示成「已关账」。
 */
export function syncPeriodCloseApproval(id: string) {
  return api<FinancePeriodCloseExecutionDetail>(
    `${base}/executions/${encodeURIComponent(id)}/sync-approval`,
    'POST',
    {},
  );
}

/** 提交关账的结果（把「被阻断」也当成一种结局，而不是异常）。 */
export interface PeriodCloseSubmitReport {
  outcome: PeriodCloseSubmitOutcome;
  message: string;
  /** 阻断原因清单（outcome=BLOCKED 时非空）。 */
  blockingReasons: FinancePeriodCloseSubmitResult['blockingReasons'];
  /** 刚创建 / 已存在的关账执行记录 id（outcome=BLOCKED 时为空）。 */
  executionId: string | null;
}

/**
 * **提交关账**。
 *
 * <p>🔴 服务端在「阻断项未解决」时返回 **409 + 结构化阻断原因清单**。
 * 本函数把它翻译成一种**结局**（`BLOCKED`）而不是一个异常 ——
 * 但阻断原因一条不少地带回来（`ApiError.body` 就是给这种场景用的）。
 */
export async function submitPeriodClose(
  input: FinancePeriodCloseExecuteInput,
): Promise<PeriodCloseSubmitReport> {
  try {
    const result = await api<FinancePeriodCloseSubmitResult>(`${base}/executions`, 'POST', input);
    return {
      outcome: (result.outcome as PeriodCloseSubmitOutcome) ?? 'PENDING_APPROVAL',
      message: result.message,
      blockingReasons: result.blockingReasons ?? [],
      executionId: result.executionId ?? null,
    };
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      const body = e.body as FinancePeriodCloseSubmitResult | null;
      return {
        outcome: 'BLOCKED',
        message: body?.message ?? e.message,
        blockingReasons: body?.blockingReasons ?? [],
        executionId: null,
      };
    }
    throw e;
  }
}

// ================================================================ 可读形态

/** 检查项的可读名。 */
export function periodCloseCheckCodeLabel(code: string): string {
  switch (code) {
    case 'UNRECONCILED_DIFFERENCE':
      return '未对账差异';
    case 'UNCARRIED_COST':
      return '未结转成本';
    case 'UNSETTLED_PAYMENT':
      return '未核销款项';
    case 'UNPOSTED_VOUCHER':
      return '未过账凭证';
    default:
      return code;
  }
}

/** 分类的可读名。 */
export function periodCloseClassificationLabel(classification: string): string {
  switch (classification) {
    case 'BLOCKING':
      return '阻断项';
    case 'CARRYWARD':
      return '可结转未清项';
    default:
      return classification;
  }
}

/**
 * 检查结果的可读名。
 *
 * <p>🔴 `NOT_DETERMINABLE` 译成「不可判定」—— **不是**「通过」，**不是**「无差异」。
 */
export function periodCloseResultLabel(result: string): string {
  switch (result) {
    case 'PASS':
      return '通过';
    case 'FAIL':
      return '未通过';
    case 'NOT_DETERMINABLE':
      return '不可判定';
    default:
      return result;
  }
}

/**
 * 检查结果的语义色。
 *
 * <p>🔴 **不可判定用 warning 而不是 success** —— 把它渲染成绿色就等于说「没问题」，
 * 而真相是「上游没交付，判不了」。
 */
export function periodCloseResultTone(result: string): 'success' | 'danger' | 'warning' {
  switch (result) {
    case 'PASS':
      return 'success';
    case 'FAIL':
      return 'danger';
    case 'NOT_DETERMINABLE':
      return 'warning';
    default:
      return 'warning';
  }
}

/** 分类的语义色：阻断项是 danger，可结转未清项是 warning。 */
export function periodCloseClassificationTone(
  classification: string,
): 'danger' | 'warning' | 'neutral' {
  switch (classification) {
    case 'BLOCKING':
      return 'danger';
    case 'CARRYWARD':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 关账执行状态的可读名。 */
export function periodCloseExecutionStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING_APPROVAL':
      return '审批中（期间仍然开放）';
    case 'APPROVED':
      return '审批通过';
    case 'REJECTED':
      return '审批驳回（期间仍然开放）';
    case 'CLOSED':
      return '已关账（期间已锁定、禁止过账）';
    default:
      return status;
  }
}

/**
 * 关账执行状态的语义色。
 *
 * <p>🔴 「已关账」用 **warning** 而不是 success —— 关账不是「完成」也不是「坏了」，
 * 它是一个需要人盯着的状态（期间不能过账了）。
 */
export function periodCloseExecutionStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'CLOSED':
      return 'warning';
    case 'PENDING_APPROVAL':
    case 'APPROVED':
      return 'neutral';
    case 'REJECTED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/**
 * 提交结局的可读形态。
 *
 * <p>🔴 **「被阻断」不是失败，是这一局的真实结论** —— 说成「关账失败」会让人以为
 * 系统出错，而真相是「有 N 项阻断项没解决」。
 */
export function periodCloseOutcomeText(outcome: string): string {
  switch (outcome) {
    case 'BLOCKED':
      return '被阻断项拦下（期间仍然开放）';
    case 'PENDING_APPROVAL':
      return '已提交审批（审批通过前期间仍然开放）';
    case 'CLOSED':
      return '已关账（期间已锁定、禁止过账）';
    default:
      return outcome;
  }
}

/** 提交结局的语义色。 */
export function periodCloseOutcomeTone(
  outcome: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (outcome) {
    case 'BLOCKED':
      return 'danger';
    case 'PENDING_APPROVAL':
      return 'warning';
    case 'CLOSED':
      return 'success';
    default:
      return 'neutral';
  }
}

/**
 * 阻断原因清单的可读形态（一条一条列出来，不合并成一句）。
 *
 * <p>🔴 每条都要带**依据** —— 阻断原因不说是「不让人处理」。
 */
export function periodCloseBlockingSummary(
  reasons: { checkName: string; itemCount: number; basis: string }[],
): string {
  if (reasons.length === 0) return '没有阻断项';
  return reasons
    .map((reason, index) => {
      const count = reason.itemCount > 0 ? `${reason.itemCount} 条` : '0 条';
      return `${index + 1}. ${reason.checkName}（${count}）：${reason.basis}`;
    })
    .join('；');
}

/**
 * 一个币种分组的可读形态。
 *
 * <p>🔴 **绝不跨币种求和**：这一行只说「这一种币种有多少」。
 * 折算值（如果拿到汇率）另起一句，并写明「不与其它币种相加」。
 */
export function periodCloseCurrencyGroupText(group: FinancePeriodCloseCurrencyGroup): string {
  const parts = [`${group.currency} ${group.amount}（${group.itemCount} 条）`];
  if (group.baseAmount !== null && group.exchangeRate !== null) {
    parts.push(`本位币折算 ${group.baseAmount}（汇率 ${group.exchangeRate}）`);
  }
  if (group.conversionNote) parts.push(group.conversionNote);
  return parts.join(' · ');
}

/**
 * 一个检查项能不能被下调为可结转未清项，以及不能时**为什么**。
 *
 * <p>🔴 页面据此决定「能不能继续过账」的提示 —— 用别的理由说「不行」等于撒谎。
 */
export function periodCloseCarryForwardGuard(check: {
  ruleClassification: string;
  classification: string;
  exceptionBasis: string;
}): { allowed: boolean; reason: string } {
  if (check.ruleClassification !== 'BLOCKING') {
    return {
      allowed: false,
      reason: '规则判定不是阻断项，本来就可以结转，无需例外下调。',
    };
  }
  if (check.classification !== 'BLOCKING') {
    return {
      allowed: false,
      reason: '该项已经下调为可结转未清项，不能重复下调。',
    };
  }
  if (!check.exceptionBasis.trim()) {
    return { allowed: true, reason: '下调必须写明例外依据（蓝图：例外与结转须保留依据）。' };
  }
  return { allowed: true, reason: '' };
}

/** 明细一行的可读形态。 */
export function periodCloseItemText(item: {
  sourceModule: string;
  sourceType: string;
  sourceNo: string;
  currency: string;
  amount: number;
  reason: string;
}): string {
  return `${item.sourceModule} · ${item.sourceType} ${item.sourceNo || '（无单号）'} · ${item.currency} ${item.amount} · ${item.reason}`;
}

/** 检查项的金额（按币种）—— 返回该检查项的金额行。 */
export function periodCloseCheckAmounts(
  check: FinancePeriodCloseCheckRow,
): FinancePeriodCloseCheckAmountRow[] {
  return check.amounts ?? [];
}

/** 明细行列表（空态判定用）。 */
export function periodCloseHasItems(page: FinancePeriodCloseCheckItemPage | null): boolean {
  return Boolean(page && page.items.length > 0);
}
