import type {
  FinanceSoftwareAccountInput,
  FinanceSoftwareAccountRow,
  FinanceSoftwareBalanceImportInput,
  FinanceSoftwareCapabilities,
  FinanceSoftwareCapability,
  FinanceSoftwareMasterMapInput,
  FinanceSoftwareMasterMapRow,
  FinanceSoftwareOverview,
  FinanceSoftwarePeriodOption,
  FinanceSoftwarePushInput,
  FinanceSoftwarePushOutcome,
  FinanceSoftwareReconcileBalanceInput,
  FinanceSoftwareReconcileDetail,
  FinanceSoftwareReconcileDiffRow,
  FinanceSoftwareReconcileTaskInput,
  FinanceSoftwareReconcileTaskRow,
  FinanceSoftwareSubjectMapInput,
  FinanceSoftwareSubjectMapRow,
  FinanceSoftwareVoucherExportInput,
  FinanceSoftwareVoucherExportResult,
  FinanceSoftwareVoucherFileDetail,
  FinanceSoftwareVoucherFileRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.2.1.1「财务软件」前端模型。
 *
 * 蓝图：「**财务软件**：对接第三方财务软件（金蝶、用友、SAP、Odoo）：凭证推送、科目映射、
 * 主数据同步、余额核对；导出标准凭证文件（Excel/XML）。输入：凭证数据；科目映射配置；
 * 对账需求。输出：推送结果；凭证文件；核对差异。关系：上游 = 12.5.2.1 凭证；下游 = 外部财务软件。
 * **业财一体化的常见做法是本系统管业务账、外部软件管法定账**，两边通过凭证文件与科目映射衔接。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「直连推送未接入」显示成「未接入」，不是「推送成功」**。本仓目前没有任何真实的
 *    财务软件适配器（backend / services / tools 下不存在 kingdee / yonyou / sap / odoo
 *    之类的客户端），所以 `/push` 会如实返回 409。前端**不**做本地兜底生成 ——
 *    一条「看起来推过去了」的假通道会让财务拿着一张**不存在的**法定凭证去做账。
 *    页面按**能力声明**（`available=false`）如实显示，并写明缺在谁身上。
 * 2. **本系统管业务账、外部软件管法定账**。凭证文件导出的是**法定账的输入**，
 *    不是「本系统的账已经做完了」。页面不把导出的文件说成「已入账」。
 * 3. **没配映射的行被标出来，不被静默丢掉**。`unmappedLineCount` 必须显示出来 ——
 *    丢掉的行会让导出的文件看起来是完整的，而它不是。
 * 4. **差异方向有口径**。外部余额按导入时的方向归一化（借正贷负），
 *    本地余额按科目余额方向取正负，两边同口径才能相减。页面显示**差额本身**，
 *    不把正负抹平成「差不多」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/finance-software';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, value);
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<FinanceSoftwareCapabilities> {
  return api<FinanceSoftwareCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<FinanceSoftwareOverview> {
  return api<FinanceSoftwareOverview>(`${base}/overview`);
}

/** 连接档案列表（真筛选）。 */
export function fetchAccounts(params: {
  vendor?: string | undefined;
  status?: string | undefined;
}): Promise<FinanceSoftwareAccountRow[]> {
  return api<FinanceSoftwareAccountRow[]>(`${base}/accounts?${query(params)}`);
}

/** 科目映射列表（真筛选 + 真关键词）。 */
export function fetchSubjectMaps(params: {
  accountId?: string | undefined;
  keyword?: string | undefined;
}): Promise<FinanceSoftwareSubjectMapRow[]> {
  return api<FinanceSoftwareSubjectMapRow[]>(`${base}/subject-maps?${query(params)}`);
}

/** 主数据映射列表（真筛选）。 */
export function fetchMasterMaps(params: {
  accountId?: string | undefined;
  masterType?: string | undefined;
}): Promise<FinanceSoftwareMasterMapRow[]> {
  return api<FinanceSoftwareMasterMapRow[]>(`${base}/master-maps?${query(params)}`);
}

/** 凭证文件导出记录列表（真数据）。 */
export function fetchVoucherFiles(params: {
  accountId?: string | undefined;
}): Promise<FinanceSoftwareVoucherFileRow[]> {
  return api<FinanceSoftwareVoucherFileRow[]>(`${base}/voucher-files?${query(params)}`);
}

/** 凭证文件详情（**含真内容** —— 事后要能原样取回当初导出的那一份）。 */
export function fetchVoucherFile(id: string): Promise<FinanceSoftwareVoucherFileDetail> {
  return api<FinanceSoftwareVoucherFileDetail>(`${base}/voucher-files/${id}`);
}

/** 核对任务列表（真数据）。 */
export function fetchReconcileTasks(params: {
  accountId?: string | undefined;
  status?: string | undefined;
}): Promise<FinanceSoftwareReconcileTaskRow[]> {
  return api<FinanceSoftwareReconcileTaskRow[]>(`${base}/reconcile-tasks?${query(params)}`);
}

/** 核对详情：任务 + 导入的外部余额 + 算出来的差异。 */
export function fetchReconcileDetail(id: string): Promise<FinanceSoftwareReconcileDetail> {
  return api<FinanceSoftwareReconcileDetail>(`${base}/reconcile-tasks/${id}`);
}

/** 会计期间清单（来自 12.5.2.1 凭证管理）—— 余额核对要按期间区间取本地余额。 */
export function fetchPeriods(): Promise<FinanceSoftwarePeriodOption[]> {
  return api<FinanceSoftwarePeriodOption[]>(`${base}/periods`);
}

/** 可选币种（来自 12.5.2.1 的真实取值，不是写死的词表）。 */
export function fetchCurrencies(): Promise<string[]> {
  return api<string[]>(`${base}/currencies`);
}

// ================================================================ 写入

/** 登记或修改一个连接档案。 */
export function saveAccount(input: FinanceSoftwareAccountInput): Promise<FinanceSoftwareAccountRow> {
  return api<FinanceSoftwareAccountRow>(`${base}/accounts`, 'POST', input);
}

/** 停用一个连接档案（**不删**：历史凭证文件与核对任务要能复现）。 */
export function disableAccount(id: string): Promise<FinanceSoftwareAccountRow> {
  return api<FinanceSoftwareAccountRow>(`${base}/accounts/${id}/disable`, 'POST');
}

/** 登记或修改一条科目映射。 */
export function saveSubjectMap(
  input: FinanceSoftwareSubjectMapInput,
): Promise<FinanceSoftwareSubjectMapRow> {
  return api<FinanceSoftwareSubjectMapRow>(`${base}/subject-maps`, 'POST', input);
}

/** 删除一条科目映射。 */
export function deleteSubjectMap(id: string): Promise<void> {
  return api<void>(`${base}/subject-maps/${id}`, 'DELETE');
}

/** 登记或修改一条主数据映射。 */
export function saveMasterMap(input: FinanceSoftwareMasterMapInput): Promise<FinanceSoftwareMasterMapRow> {
  return api<FinanceSoftwareMasterMapRow>(`${base}/master-maps`, 'POST', input);
}

/** 删除一条主数据映射。 */
export function deleteMasterMap(id: string): Promise<void> {
  return api<void>(`${base}/master-maps/${id}`, 'DELETE');
}

/**
 * 导出凭证文件（CSV / Excel / XML）。
 *
 * 🔴 真读上游 12.5.2.1 的**已过账**凭证 → 套科目映射 → 生成**真文件内容** →
 * 落 sha256 / 字节数 / 借贷合计 / 未映射行数 → 内容一并落库。
 */
export function exportVoucherFile(
  input: FinanceSoftwareVoucherExportInput,
): Promise<FinanceSoftwareVoucherExportResult> {
  return api<FinanceSoftwareVoucherExportResult>(`${base}/voucher-files/export`, 'POST', input);
}

/** 建一个核对任务。 */
export function createReconcileTask(
  input: FinanceSoftwareReconcileTaskInput,
): Promise<FinanceSoftwareReconcileTaskRow> {
  return api<FinanceSoftwareReconcileTaskRow>(`${base}/reconcile-tasks`, 'POST', input);
}

/** 删除一个**还没算**的核对任务。 */
export function deleteReconcileTask(id: string): Promise<void> {
  return api<void>(`${base}/reconcile-tasks/${id}`, 'DELETE');
}

/** 导入外部余额（一批科目；同科目重复导入**覆盖**）。 */
export function importBalances(
  taskId: string,
  input: FinanceSoftwareBalanceImportInput,
): Promise<{ imported: number }> {
  return api<{ imported: number }>(`${base}/reconcile-tasks/${taskId}/balances`, 'POST', input);
}

/** **算**核对差异。 */
export function computeReconcile(taskId: string): Promise<FinanceSoftwareReconcileTaskRow> {
  return api<FinanceSoftwareReconcileTaskRow>(`${base}/reconcile-tasks/${taskId}/compute`, 'POST');
}

/**
 * 直连推送凭证到外部财务软件。
 *
 * 🔴 **本仓当前恒 409，且一次数据都不写**：外部财务软件适配器未接入。
 * 这里**不**做任何本地兜底 —— 一条「看起来推过去了」的假通道会让
 * 「凭证已经推给金蝶了」在数据上看起来发生过，而现场一个凭证都没推出去。
 * 适配器交付之后，这个函数立刻变成真的。
 */
export function pushVouchers(input: FinanceSoftwarePushInput): Promise<FinanceSoftwarePushOutcome> {
  return api<FinanceSoftwarePushOutcome>(`${base}/push`, 'POST', input);
}

// ================================================================ 词表

/** 语气档位。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 外部财务软件厂商（与后端 `FinanceSoftwareVendor` 逐字对齐）。 */
export const VENDOR_OPTIONS = [
  { value: 'KINGDEE', label: '金蝶' },
  { value: 'YONYOU', label: '用友' },
  { value: 'SAP', label: 'SAP' },
  { value: 'ODOO', label: 'Odoo' },
] as const;

/** 主数据类型（与 12.5.2.1 凭证行的辅助核算类型对齐）。 */
export const MASTER_TYPE_OPTIONS = [
  { value: 'CUSTOMER', label: '客户' },
  { value: 'SUPPLIER', label: '供应商' },
  { value: 'PLATFORM', label: '平台/店铺' },
  { value: 'ACCOUNT', label: '资金账户' },
  { value: 'PRODUCT', label: '商品' },
  { value: 'DEPARTMENT', label: '部门' },
] as const;

/** 凭证文件格式。 */
export const FILE_FORMAT_OPTIONS = [
  { value: 'CSV', label: 'CSV（Excel 双击能开）' },
  { value: 'EXCEL', label: 'Excel（SpreadsheetML）' },
  { value: 'XML', label: 'XML（通用凭证）' },
] as const;

/** 连接档案状态。 */
export const ACCOUNT_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'DISABLED', label: '停用' },
] as const;

/** 余额方向。 */
export const DIRECTION_OPTIONS = [
  { value: 'DEBIT', label: '借方' },
  { value: 'CREDIT', label: '贷方' },
] as const;

/**
 * 「仅本地有」那一行的**占位前缀**。
 *
 * 🔴 外部那边根本没有对应科目，所以外部科目列放的是 `LOCAL:<本地科目编码>` 这样的
 * **占位符**，不是空串（后端迁移里那一列要求非空），也不是裸本地编码
 * （那会与「对方恰好用同一个编码、而我们没配映射」的外部科目撞唯一键）。
 */
export const LOCAL_ONLY_PREFIX = 'LOCAL:';

// ================================================================ 展示口径

/**
 * 金额的显示口径。
 *
 * 🔴 后端是 `BigDecimal` + 库里 `numeric(20,6)`，JSON 里是数字。
 * 这里**只做显示**，不做任何记账运算 —— 前端算出来的钱不是账。
 * 用 {@code toFixed} 再去尾零，避免出现 `1.3e+4` 这种财务人员看不懂的样子。
 */
export function formatAmount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  if (value === 0) return '0';
  const fixed = value.toFixed(6);
  const trimmed = fixed.replace(/\.?0+$/, '');
  return trimmed === '-0' ? '0' : trimmed;
}

/** 金额的显示口径（带币种）。 */
export function formatMoney(value: number | null | undefined, currency?: string | undefined): string {
  const amount = formatAmount(value);
  if (amount === '—') return amount;
  return currency ? `${amount} ${currency}` : amount;
}

/** 差异原因的中文说法。 */
export function reconcileReasonLabel(reason: string): string {
  switch (reason) {
    case 'BALANCE_DIFF':
      return '金额不等';
    case 'ONLY_LOCAL':
      return '仅本地有';
    case 'ONLY_EXTERNAL':
      return '仅外部有';
    case 'CURRENCY_MISMATCH':
      return '币种不一致';
    default:
      return reason;
  }
}

/**
 * 差异原因的语气。
 *
 * 🔴 **「仅本地有」与「仅外部有」是红，「金额不等」也是红，但「币种不一致」是黄** ——
 * 币种不一致往往是要先换算，不是账错了；混成同一种红会让真正的账差被淹没。
 */
export function reconcileReasonTone(reason: string): Tone {
  switch (reason) {
    case 'CURRENCY_MISMATCH':
      return 'warn';
    case 'BALANCE_DIFF':
    case 'ONLY_LOCAL':
    case 'ONLY_EXTERNAL':
      return 'danger';
    default:
      return 'muted';
  }
}

/**
 * 差异行里「外部科目编码」这一格该显示什么。
 *
 * 🔴 占位符（`LOCAL:` 前缀）**必须显示成「无外部科目」**，不能把 `LOCAL:6602`
 * 原样甩给财务 —— 那看着像个真实的外部科目编码。
 */
export function diffExternalCodeLabel(code: string): { label: string; placeholder: boolean } {
  if (!code) return { label: '—', placeholder: true };
  if (code.startsWith(LOCAL_ONLY_PREFIX)) {
    return { label: '无外部科目（本地独有）', placeholder: true };
  }
  return { label: code, placeholder: false };
}

/**
 * 差异行的排序口径：**钱差在前**，其次是「只在一边有」，最后是币种不一致。
 *
 * <p>🔴 财务看核对结果时最关心的是「哪条金额对不上」，不是「哪条只是缺一个科目」。
 */
export function sortDiffs(diffs: FinanceSoftwareReconcileDiffRow[]): FinanceSoftwareReconcileDiffRow[] {
  const rank = (reason: string) =>
    reason === 'BALANCE_DIFF' ? 0 : reason === 'ONLY_LOCAL' || reason === 'ONLY_EXTERNAL' ? 1 : 2;
  return [...diffs].sort((a, b) => rank(a.reason) - rank(b.reason));
}

/**
 * 导出结果的一行摘要。
 *
 * 🔴 **未映射行数必须出现在摘要里** —— 那是「这份文件不完整」的唯一信号。
 * 把 1 行未映射的文件说成「4 行分录」看着是完整的，而它不是。
 */
export function exportSummary(result: {
  voucherCount: number;
  lineCount: number;
  unmappedLineCount: number;
}): string {
  const base = `${result.voucherCount} 张凭证 / ${result.lineCount} 行分录`;
  if (result.unmappedLineCount > 0) {
    return `${base}，其中 ${result.unmappedLineCount} 行未配科目映射`;
  }
  return `${base}，全部已映射`;
}

/** 未映射行数的语气：有未映射行是黄的（文件不完整），没有是绿的。 */
export function unmappedTone(unmappedLineCount: number): Tone {
  return unmappedLineCount > 0 ? 'warn' : 'ok';
}

/** sha256 的显示口径：只显示前 12 位（够指认，不必把一整屏哈希怼到人脸上）。 */
export function shortSha(sha: string): string {
  if (!sha) return '—';
  return sha.length <= 12 ? sha : `${sha.slice(0, 12)}…`;
}

/**
 * 「直连推送」这一维的能力声明。
 *
 * 🔴 返回 {@code null} 表示后端**没有声明**这一维 —— 那与「声明了但未接入」不同：
 * 前者要显示成「未知」，不能显示成「已接入」。
 */
export function pushCapability(
  capabilities: FinanceSoftwareCapabilities | null,
): FinanceSoftwareCapability | null {
  const list = capabilities?.capabilities ?? [];
  return (
    list.find((c) => c.name.includes('直连推送')) ??
    list.find((c) => c.name.includes('直连同步')) ??
    null
  );
}

/**
 * 页面**要不要**提供「直连推送」这个动作。
 *
 * 🔴 只有后端明确声明 `available=true` 时才提供。未接入时页面**不**提供这个按钮 ——
 * 一个点了必然 409 的按钮不如没有：它会让运营以为「点一下就能推」。
 * 缺的那一段写在能力声明里（`evidence` / `ownerModule` / `note`）。
 */
export function canPush(capabilities: FinanceSoftwareCapabilities | null): boolean {
  const capability = pushCapability(capabilities);
  return capability?.available === true;
}

/** 能力声明的语气。 */
export function capabilityTone(available: boolean): Tone {
  return available ? 'ok' : 'muted';
}

/**
 * 未接入的能力该怎么显示。
 *
 * 🔴 显示成「未接入（归 18.2 财务与资金）」+ **一句说清现在怎么办的话**，
 * 而不是把那一格藏起来 —— 藏起来等于「这个功能不存在」，而真相是
 * 「它缺在某个模块身上，账套文件那条路今天就能用」。
 */
export function unavailableHint(capability: FinanceSoftwareCapability | null): string {
  if (!capability) return '后端没有声明这一维能力 —— 状态未知。';
  const owner = capability.ownerModule ? `（归 ${capability.ownerModule}）` : '';
  return `未接入${owner}：${capability.note || capability.evidence}`;
}

/** 连接档案状态的语气：停用是灰的，启用是绿的。 */
export function accountStatusTone(status: string): Tone {
  return status === 'ACTIVE' ? 'ok' : 'muted';
}

/** 核对任务状态的语气：已算出差异是蓝的（结论已定），待计算是灰的。 */
export function reconcileStatusTone(status: string): Tone {
  return status === 'COMPUTED' ? 'ok' : 'muted';
}

/** 余额方向的中文说法。 */
export function directionLabel(direction: string): string {
  if (direction === 'DEBIT') return '借方';
  if (direction === 'CREDIT') return '贷方';
  return direction || '—';
}

/**
 * 导出文件的下载名。
 *
 * 🔴 用**后端返回的** filename —— 那一半是账套号 + 日期区间，
 * 现场对账时要按这个名字去对方系统里找对应的那一份。
 */
export function downloadName(row: { filename?: string | undefined; fileNo?: string | undefined }): string {
  return row.filename || row.fileNo || 'vouchers.csv';
}
