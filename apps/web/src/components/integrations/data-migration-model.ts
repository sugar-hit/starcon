import type {
  DataMigrationCapabilities,
  DataMigrationCapability,
  DataMigrationGap,
  DataMigrationMappingPage,
  DataMigrationMappingRow,
  DataMigrationOverview,
  DataMigrationRowPage,
  DataMigrationRowRow,
  DataMigrationRunPage,
  DataMigrationRunRow,
  DataMigrationTargetRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.1.3.2「数据迁移」前端模型。
 *
 * 蓝图：「<strong>职责</strong>：支持从其他 ERP 迁移历史数据：映射配置（外部字段 → 内部模型）、
 * 试运行预览、正式迁移、失败回滚与核对报告。<strong>输入</strong>：源数据文件；字段映射配置。
 * <strong>输出</strong>：迁移任务 + 迁移核对报告（成功/失败/跳过数量与原因）。
 * <strong>关系</strong>：上游 = 18.1.3.1；下游 = 各业务域。
 * <strong>历史数据迁移必须支持试运行</strong>，直接正式跑会产生大量脏数据难以回退。」
 *
 * 🔴 这个页面上最容易做丢的六件事，这里都挡住了：
 *
 * 1. **「已受理」不是「已完成」**。发起迁移后服务端返回的是「任务已创建」，
 *    逐行判重与写目标库都在后台跑 —— 页面**绝不**在提交后显示「迁移完成」，
 *    必须轮询任务详情拿真实的行数与状态。
 * 2. **试运行是一等公民**。蓝图那句「必须支持试运行」在页面上的落点是：
 *    发起面板把「试运行」与「正式迁移」摆成**两个并列且都显眼的选项**，
 *    而不是把正式迁移做成默认值 —— 默认值会让「直接正式跑」变成一次误点。
 * 3. **没接通的业务域要显式摆出来**。订单 / 库存 / 凭证**没有**面向迁移的写入端点，
 *    「失败回滚」只能是假的（页面写着「已回滚」，目标库里的数据其实还在）——
 *    那几格如实标「未接通」并写明缺什么、归谁。
 * 4. **核对报告的三个口径要看得见**。成功 / 失败 / 跳过**加起来等于**处理过的行数 ——
 *    那份报告的全部意义就是「加起来对得上」，页面上必须能一眼核对。
 * 5. **逐行结论要有行号与原因**，不是一句「迁移失败」。
 * 6. **读不到 ≠ 没有**。端点读不到时显示「读不到」并写明原因，**不渲染绿色空态**。
 *
 * <p><b>类型全部从契约派生</b>（{@code packages/api-contracts} 的 codegen 产物），前端不手抄字符串联合。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/data-migration';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<DataMigrationCapabilities> {
  return api<DataMigrationCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<DataMigrationOverview> {
  return api<DataMigrationOverview>(`${base}/overview`);
}

/** 目标内部模型目录（**没接通的那几个照样出现**）。 */
export function fetchTargets(): Promise<DataMigrationTargetRow[]> {
  return api<DataMigrationTargetRow[]>(`${base}/targets`);
}

/** 映射配置列表（真分页 + 真筛选）。 */
export function fetchMappings(params: {
  targetModel?: string | undefined;
  q?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<DataMigrationMappingPage> {
  return api<DataMigrationMappingPage>(`${base}/mappings?${query(params)}`);
}

/** 映射配置详情。 */
export function fetchMapping(id: string): Promise<DataMigrationMappingRow> {
  return api<DataMigrationMappingRow>(`${base}/mappings/${id}`);
}

/** 迁移任务列表（真分页 + 真筛选）。 */
export function fetchRuns(params: {
  mode?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<DataMigrationRunPage> {
  return api<DataMigrationRunPage>(`${base}/runs?${query(params)}`);
}

/** 迁移任务详情（**进度跟踪**：行数、状态都在这里）。 */
export function fetchRun(id: string): Promise<DataMigrationRunRow> {
  return api<DataMigrationRunRow>(`${base}/runs/${id}`);
}

/** 逐行结论（**核对报告的明细**：哪一行、什么结论、为什么）。 */
export function fetchRows(
  id: string,
  params: { outcome?: string | undefined; page?: number | undefined; size?: number | undefined },
): Promise<DataMigrationRowPage> {
  return api<DataMigrationRowPage>(`${base}/runs/${id}/rows?${query(params)}`);
}

// ================================================================ 动作

/** 新建映射配置（外部字段 → 内部模型）。 */
export function createMapping(input: {
  code: string;
  name: string;
  sourceSystem: string;
  targetModel: string;
  fields: {
    source: string;
    target: string;
    required: boolean;
    transform: string;
    defaultValue: string;
  }[];
  enabled: boolean;
  remark: string;
}): Promise<DataMigrationMappingRow> {
  return api<DataMigrationMappingRow>(`${base}/mappings`, 'POST', input);
}

/** 修改映射配置（**已发起的迁移任务用的是它冻结的快照**，不受影响）。 */
export function updateMapping(
  id: string,
  input: {
    code: string;
    name: string;
    sourceSystem: string;
    targetModel: string;
    fields: {
      source: string;
      target: string;
      required: boolean;
      transform: string;
      defaultValue: string;
    }[];
    enabled: boolean;
    remark: string;
  },
): Promise<DataMigrationMappingRow> {
  return api<DataMigrationMappingRow>(`${base}/mappings/${id}`, 'PUT', input);
}

/** 删除映射配置（已被迁移任务引用的**不能删**）。 */
export function deleteMapping(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/mappings/${id}`, 'DELETE');
}

/**
 * 发起一次迁移（**已受理**：逐行判重与写目标库在后台跑）。
 *
 * @param mode `DRY_RUN`（试运行）/ `APPLY`（正式迁移）
 */
export function createRun(
  mappingId: string,
  mode: 'DRY_RUN' | 'APPLY',
  filename: string,
  content: ArrayBuffer,
): Promise<DataMigrationRunRow> {
  const bytes = new Uint8Array(content);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return api<DataMigrationRunRow>(`${base}/runs`, 'POST', {
    mappingId,
    mode,
    filename,
    contentBase64: btoa(binary),
  });
}

/** 发起回滚（**已受理**：真的去目标库删掉建出来的记录）。 */
export function requestRollback(id: string): Promise<DataMigrationRunRow> {
  return api<DataMigrationRunRow>(`${base}/runs/${id}/rollback`, 'POST');
}

/** 清理过期文件（**迁移任务记录保留** —— 它是审计事实）。 */
export function expireFiles(): Promise<{ expiredFiles: number; failedTasks: number }> {
  return api<{ expiredFiles: number; failedTasks: number }>(`${base}/expire`, 'POST');
}

// ================================================================ 下载

/**
 * 核对报告下载链接（**普通链接**，不是 `api()`）。
 *
 * 🔴 同源代理对 `text/csv` 是**原样透传**的（走 JSON.parse 会把 CSV 解析失败变成 503），
 * 且代理会用会话 cookie 补上 `Authorization` 头 —— 所以浏览器直接开这个链接就能拿到文件。
 */
export function reportUrl(id: string): string {
  return `/api/${base}/runs/${id}/report`;
}

/** 回滚报告下载链接。 */
export function rollbackReportUrl(id: string): string {
  return `/api/${base}/runs/${id}/rollback-report`;
}

// ================================================================ 词表

/** 语气 → Badge 的 tone。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 迁移模式。 */
export type Mode = 'DRY_RUN' | 'APPLY';

/** 迁移模式的显示口径。 */
export function modeLabel(mode: string): string {
  switch (mode) {
    case 'DRY_RUN':
      return '试运行';
    case 'APPLY':
      return '正式迁移';
    default:
      return mode || '—';
  }
}

/**
 * 迁移模式的语气。
 *
 * 🔴 **试运行是「安全」而不是「成功」**：标成绿色会让人以为「已经迁进去了」。
 * 它跑完之后目标库里**一个记录都没有** —— 页面必须让这一点一眼可见。
 */
export function modeTone(mode: string): Tone {
  return mode === 'DRY_RUN' ? 'muted' : 'warn';
}

/** 迁移状态的显示口径。 */
export function runStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待执行';
    case 'RUNNING':
      return '执行中';
    case 'SUCCEEDED':
      return '已完成';
    case 'FAILED':
      return '已失败';
    case 'CANCELLED':
      return '已作废';
    default:
      return status || '—';
  }
}

/** 迁移状态的语气。 */
export function runStatusTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'ok';
    case 'RUNNING':
    case 'PENDING':
      return 'warn';
    case 'FAILED':
      return 'danger';
    default:
      return 'muted';
  }
}

/** 回滚状态的显示口径（**任务级**）。 */
export function rollbackStatusLabel(status: string): string {
  switch (status) {
    case 'NONE':
      return '未回滚';
    case 'RUNNING':
      return '回滚中';
    case 'SUCCEEDED':
      return '已回滚';
    case 'FAILED':
      return '回滚失败';
    default:
      return status || '—';
  }
}

/** 回滚状态的语气。 */
export function rollbackStatusTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'ok';
    case 'RUNNING':
      return 'warn';
    case 'FAILED':
      return 'danger';
    default:
      return 'muted';
  }
}

/** 逐行结论的显示口径。 */
export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'SKIPPED':
      return '跳过';
    default:
      return outcome || '—';
  }
}

/** 逐行结论的语气。 */
export function outcomeTone(outcome: string): Tone {
  switch (outcome) {
    case 'SUCCEEDED':
      return 'ok';
    case 'FAILED':
      return 'danger';
    case 'SKIPPED':
      return 'warn';
    default:
      return 'muted';
  }
}

/**
 * 逐行**回滚**结论的显示口径（**行级**词表，与任务级不是同一套）。
 *
 * 🔴 行级是「未回滚 / **已删除** / 未删除」，任务级是「未回滚 / 回滚中 / 已回滚 / 回滚失败」。
 * 用错的话页面会把「这一行删掉了」显示成「已回滚」，让人以为**每一行**都删干净了。
 */
export function rowRollbackLabel(status: string): string {
  switch (status) {
    case 'NONE':
      return '未回滚';
    case 'DELETED':
      return '已删除';
    case 'FAILED':
      return '未删除';
    default:
      return status || '—';
  }
}

/** 逐行回滚结论的语气。 */
export function rowRollbackTone(status: string): Tone {
  switch (status) {
    case 'DELETED':
      return 'ok';
    case 'FAILED':
      return 'danger';
    default:
      return 'muted';
  }
}

/**
 * 失败原因码的显示口径（后端给的是稳定码，页面据此显示人话）。
 *
 * 🔴 「查不清就跳过」也是**失败原因**的一种（`TARGET_CHECK_INCONCLUSIVE`）：
 * 目标库查询被截断时跳过那一行，而不是把一条可能已存在的记录再迁一遍。
 */
export function reasonLabel(code: string): string {
  switch (code) {
    case '':
      return '—';
    case 'MISSING_REQUIRED_FIELD':
      return '必填字段为空';
    case 'INVALID_CODE':
      return '编码格式不合法';
    case 'ALREADY_EXISTS':
      return '目标库已有同编码';
    case 'DUPLICATE_IN_FILE':
      return '文件内编码重复';
    case 'TARGET_CHECK_INCONCLUSIVE':
      return '目标库查不清（已跳过）';
    case 'PARENT_SPU_NOT_FOUND':
      return '所属商品在目标库不存在';
    case 'UPSTREAM_REJECTED':
      return '目标库拒绝了这行';
    default:
      return code;
  }
}

/**
 * 能力声明里**不可用**的那几格。
 *
 * 🔴 这些必须显式摆出来 —— 混进空态等于告诉运营「订单也已经能迁历史数据了」。
 */
export function unavailableCapabilities(
  capabilities: DataMigrationCapabilities | null | undefined,
): DataMigrationCapability[] {
  return (capabilities?.capabilities ?? []).filter((c) => !c.available);
}

/** 能力声明里**已接通**的那几格。 */
export function availableCapabilities(
  capabilities: DataMigrationCapabilities | null | undefined,
): DataMigrationCapability[] {
  return (capabilities?.capabilities ?? []).filter((c) => c.available);
}

/** 接线状态（上游 / 邻居 / 下游）。 */
export function gaps(
  capabilities: DataMigrationCapabilities | null | undefined,
): DataMigrationGap[] {
  return capabilities?.gaps ?? [];
}

/**
 * **已接通迁移**的目标模型（发起面板的下拉里只列这些）。
 *
 * 🔴 只列可用的：发起面板的下拉里不该出现一个点下去必然 400 的选项。
 * 不可用的那些在「目标模型目录」与「能力声明」里如实摆着。
 */
export function migratableTargets(
  targets: DataMigrationTargetRow[] | null | undefined,
): DataMigrationTargetRow[] {
  return (targets ?? []).filter((t) => t.fields.length > 0);
}

/** **没接通迁移**的目标模型（照样摆出来，写明缺什么、归谁）。 */
export function unmigratableTargets(
  targets: DataMigrationTargetRow[] | null | undefined,
): DataMigrationTargetRow[] {
  return (targets ?? []).filter((t) => t.fields.length === 0);
}

/**
 * 迁移的进度百分比。
 *
 * 🔴 没有总行数时**不编一个百分比出来** —— 返回 null 让页面显示「进行中」而不是「0%」。
 */
export function progressPercent(run: DataMigrationRunRow | null | undefined): number | null {
  if (!run) return null;
  if (run.totalRows <= 0) return null;
  if (run.status === 'SUCCEEDED') return 100;
  return Math.min(100, Math.round((run.processedRows / run.totalRows) * 100));
}

/**
 * 这个任务**能不能回滚**。
 *
 * 🔴 三个前提，缺一不可：① 正式迁移（试运行没写过任何东西）；② 迁移**已经跑完**
 * （还在跑就回滚 ⇒ 删一半又建一半，比不回滚更糟）；③ 还没回滚过。
 */
export function rollbackable(run: DataMigrationRunRow | null | undefined): boolean {
  if (!run) return false;
  if (run.mode !== 'APPLY') return false;
  if (run.status !== 'SUCCEEDED' && run.status !== 'FAILED') return false;
  if (run.rollbackStatus !== 'NONE') return false;
  return run.succeededRows > 0;
}

/**
 * 核对报告**能不能下载**。
 *
 * 🔴 报告是一次性产物（有有效期），过期后按钮要**消失**并说明「重新发起一次」——
 * 让用户点一下拿到一个 410 是把后端的判断甩给用户。
 */
export function reportDownloadable(run: DataMigrationRunRow | null | undefined): boolean {
  if (!run) return false;
  return Boolean(run.reportFileId);
}

/** 回滚报告**能不能下载**。 */
export function rollbackReportDownloadable(run: DataMigrationRunRow | null | undefined): boolean {
  if (!run) return false;
  return Boolean(run.rollbackReportFileId);
}

/**
 * 核对报告的三个口径**对不对得上**。
 *
 * 🔴 成功 + 失败 + 跳过 必须等于处理过的行数 —— 那份报告的全部意义就是「加起来对得上」。
 * 对不上时页面要**显式说对不上**（这是数据坏了，不是界面坏了）。
 */
export function reportBalanced(run: DataMigrationRunRow | null | undefined): boolean {
  if (!run) return false;
  if (run.processedRows === 0) return true;
  return run.succeededRows + run.failedRows + run.skippedRows === run.processedRows;
}

/** 逐行结论的三个口径（来自服务端，与任务上的计数互相印证）。 */
export function rowCounts(page: DataMigrationRowPage | null | undefined): {
  succeeded: number;
  failed: number;
  skipped: number;
} {
  return {
    succeeded: page?.succeededCount ?? 0,
    failed: page?.failedCount ?? 0,
    skipped: page?.skippedCount ?? 0,
  };
}

/** 逐行结论里**迁成了**的那些行（它们的上游 id 是回滚的依据）。 */
export function migratedRows(
  rows: DataMigrationRowRow[] | null | undefined,
): DataMigrationRowRow[] {
  return (rows ?? []).filter((r) => r.outcome === 'SUCCEEDED' && Boolean(r.upstreamId));
}
