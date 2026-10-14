import type {
  PickTaskAssignInput,
  PickTaskCapabilities,
  PickTaskCancelInput,
  PickTaskDetail,
  PickTaskExceptionInput,
  PickTaskExceptionRow,
  PickTaskGenerateInput,
  PickTaskPage,
  PickTaskPickInput,
  PickTaskPickRow,
  PickTaskReopenInput,
  PickTaskResolveInput,
  PickTaskRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.3.2.1 拣货任务前端模型。
 *
 * <p>蓝图 08 卷 8.3.2.1：「<b>生成与执行拣货任务：按库位顺序排序优化路径，记录实际拣货库位/批次/序列号/数量，异常（缺货、破损）上报。</b>
 * 输入：出库单明细；库存分配结果；作业人员或 PDA 扫码。」输出：「拣货任务与拣货明细（实际库位/批次/序列号）；缺货异常记录。」
 * 关系：「下游 = 8.3.3.1、7 库存（已拣未发的占用状态）。<b>拣货完不等于已发货</b>。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>拣货完 ≠ 已发货。</b>「完成」按钮的文案与完成后的提示都必须说清
 *       「货在打包区、预占仍在、发货由 8.3.4.1 写」；任务状态词表里也没有「已发货」这一档。</li>
 *   <li><b>计划 ≠ 实际。</b>路径计划段（{@code sourceLocationCode}）与实拣记录
 *       （{@code PickTaskPickRow.locationCode}）分两块渲染：分配是建议，现场是事实。
 *       两者不同色标注，让「实际取货库位与计划不符」在界面上是一眼可见的事实，
 *       而不是被藏进详情里的一个数字。</li>
 *   <li><b>无货可拣 ≠ 还没拣。</b>{@code sourceHasStock === false} 的行显示「无可分配库存」
 *       而不是显示成 0 件待拣 —— 前者是「仓里没有」，后者是「还没开始」，两回事。</li>
 *   <li><b>不可拣库位必须顶到最前面提醒。</b>对应 8.5.2.1「高频货在不可拣库位」那条建议
 *       （ownerModule = 8.3.2.1）：让作业员在待检位 / 冻结位上动手是两种完全不同的错误。</li>
 *   <li><b>SKU 散在多库位要显示出来</b>（{@code skuLocationCount > 1}）。
 *       对应 8.5.2.1「SKU 散在多库位」那条建议：一次拣货要多走几趟，
 *       作业员有权知道这一行是不是「一趟拿不齐」。</li>
 *   <li><b>缺货必须写缺几件。</b>报缺货的表单里数量是<b>必填</b>，不给数字的「缺货」
 *       是一个仓库没法用的异常单。其余四类（破损 / 序列号缺失 / 错位 / 其它）
 *       <b>不显示</b>数量输入框 —— 数量由实拣与计划之差体现。</li>
 *   <li><b>进度分母为 0 时不给百分比。</b>后端 {@code progress} 为 null 表示
 *       「还没排出计划」，显示 0% 会把「没量过」说成「一件没拣」。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/pick-tasks}，刻意不挂在
 * {@code outbound-orders} 之下 —— 出库单是履约单据（8.3.1.x），拣货任务是现场作业工单，
 * 两者生命周期不同（单据取消后工单还在）。
 */

const base = 'warehouse/pick-tasks';

export type PickTaskStatus = PickTaskRow['status'];
export type PickTaskLineStatus = 'PENDING' | 'PICKED' | 'SHORT';
export type PickTaskExceptionKind = 'SHORTAGE' | 'DAMAGED' | 'MISSING_SERIAL' | 'WRONG_LOCATION' | 'OTHER';

export interface PickTaskParams {
  warehouseId?: string;
  status?: string;
  assigneeUserId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function pickTaskQuery(params: PickTaskParams): string {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.assigneeUserId) parts.push(`assigneeUserId=${encodeURIComponent(params.assigneeUserId)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchPickTaskCapabilities() {
  return api<PickTaskCapabilities>(`${base}/capabilities`);
}

export function fetchPickTasks(params: PickTaskParams) {
  return api<PickTaskPage>(`${base}${pickTaskQuery(params)}`);
}

export function fetchPickTask(taskId: string) {
  return api<PickTaskDetail>(`${base}/${encodeURIComponent(taskId)}`);
}

export function generatePickTask(input: PickTaskGenerateInput) {
  return api(`${base}`, 'POST', input);
}

/**
 * 接单。
 *
 * <p>输入类型取自契约而不是手写字面量：springdoc 把 {@code @Schema(nullable = true)} 的字段
 * 生成成**必填但可为 null** 的键，而本仓库开了 {@code exactOptionalPropertyTypes} ——
 * 传 {@code undefined} 会被类型系统当场拒绝，写成 {@code null} 才与后端一致。
 */
export function assignPickTask(taskId: string, input: PickTaskAssignInput) {
  return api(`${base}/${encodeURIComponent(taskId)}/assign`, 'POST', input);
}

export function recordPick(taskId: string, input: PickTaskPickInput) {
  return api<PickTaskPickRow>(`${base}/${encodeURIComponent(taskId)}/picks`, 'POST', input);
}

export function reportPickException(taskId: string, input: PickTaskExceptionInput) {
  return api<PickTaskExceptionRow>(
    `${base}/${encodeURIComponent(taskId)}/exceptions`,
    'POST',
    input,
  );
}

export function resolvePickException(
  taskId: string,
  exceptionId: string,
  input: PickTaskResolveInput,
) {
  return api<PickTaskExceptionRow>(
    `${base}/${encodeURIComponent(taskId)}/exceptions/${encodeURIComponent(exceptionId)}/resolve`,
    'POST',
    input,
  );
}

export function completePickTask(taskId: string) {
  return api<PickTaskDetail>(`${base}/${encodeURIComponent(taskId)}/complete`, 'POST', {});
}

export function reopenPickTask(taskId: string, input: PickTaskReopenInput) {
  return api<PickTaskRow>(`${base}/${encodeURIComponent(taskId)}/reopen`, 'POST', input);
}

export function cancelPickTask(taskId: string, input: PickTaskCancelInput) {
  return api<PickTaskRow>(`${base}/${encodeURIComponent(taskId)}/cancel`, 'POST', input);
}

// ================================================================ 展示口径

export const statusLabels: Record<PickTaskStatus, string> = {
  CREATED: '已生成',
  IN_PROGRESS: '拣货中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

export function statusLabel(status: PickTaskStatus): string {
  return statusLabels[status] ?? status;
}

/**
 * 状态色调。已取消与已完成的配色刻意不同：已取消是「这张工单作废了」，
 * 涂成和已完成一样的绿会让作业台看起来像多了完成量。
 */
export function statusTone(status: PickTaskStatus): 'neutral' | 'info' | 'success' | 'danger' {
  switch (status) {
    case 'IN_PROGRESS':
      return 'info';
    case 'COMPLETED':
      return 'success';
    case 'CANCELLED':
      return 'danger';
    default:
      return 'neutral';
  }
}

export const lineStatusLabels: Record<PickTaskLineStatus, string> = {
  PENDING: '待拣',
  PICKED: '拣齐',
  SHORT: '拣不齐',
};

export function lineStatusLabel(status: PickTaskLineStatus): string {
  return lineStatusLabels[status] ?? status;
}

/** 只有「拣货中」能登记实拣；只有未完态能上报异常。按钮禁用时要说清原因。 */
export function canRecordPick(status: PickTaskStatus): boolean {
  return status === 'IN_PROGRESS';
}

export function canReportException(status: PickTaskStatus): boolean {
  return status === 'CREATED' || status === 'IN_PROGRESS';
}

export function canComplete(status: PickTaskStatus): boolean {
  return status === 'IN_PROGRESS';
}

export function canReopen(status: PickTaskStatus): boolean {
  return status === 'COMPLETED' || status === 'CANCELLED';
}

export function canCancel(status: PickTaskStatus): boolean {
  return status === 'CREATED' || status === 'IN_PROGRESS';
}

export const exceptionKindLabels: Record<PickTaskExceptionKind, string> = {
  SHORTAGE: '缺货',
  DAMAGED: '破损',
  MISSING_SERIAL: '序列号缺失',
  WRONG_LOCATION: '错位',
  OTHER: '其它',
};

export function exceptionKindLabel(kind: string): string {
  return exceptionKindLabels[kind as PickTaskExceptionKind] ?? kind;
}

export const resolutionLabels: Record<string, string> = {
  REPLACED: '换货',
  SPLIT: '拆单',
  WRITTEN_OFF: '报损',
  IGNORED: '忽略',
};

export function resolutionLabel(resolution: string): string {
  return resolutionLabels[resolution] ?? resolution;
}

/**
 * 只有缺货要填数量。
 *
 * <p>这条不是界面偏好，是后端 CHECK 的镜像：非缺货类填了数量，
 * 请求会被 V110 直接拒（两个地方各记一遍数量会与实拣/计划之差对不上）。
 */
export function needsShortageQuantity(kind: string): boolean {
  return kind === 'SHORTAGE';
}

/**
 * 数量格式化：去掉无意义的小数尾零，但 0 必须显示成 0 而不是空串。
 *
 * <p>只削**小数部分**的尾零 —— 直接对整串跑 `/\.?0+$/` 会把 `10` 变成 `1`
 * （整数位的 0 不是「无意义的尾零」），那一行数量在作业台上凭空少了一个零，
 * 而 typecheck / lint / vitest 全部绿灯。
 */
export function qtyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value === 0) return '0';
  const text = String(value);
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/**
 * 进度文案。
 *
 * <p>后端 {@code progress} 为 null 表示「计划量还没有」（分母为 0），
 * 这时显示「—」而不是 0% —— 「没量过」与「量过了，是 0」在作业台上是两件事
 * （与 8.5.2.1 的 {@code capacityMeasured} 同一纪律）。
 */
export function progressText(progress: number | null): string {
  if (progress === null) return '—';
  return `${Math.round(progress * 1000) / 10}%`;
}

/**
 * 拣齐判据。
 *
 * <p>{@code fullyPicked} 与「拣了多少件」不是一回事：拣了 9/10 件也有进度，
 * 但拣不齐就<b>不能</b>提交复核。界面上必须给这两个数各留一格。
 */
export function pickCompletionText(row: PickTaskRow): string {
  if (row.plannedLineCount === 0) return '未排出计划';
  return row.fullyPicked ? '已拣齐' : `${row.pickedLineCount}/${row.plannedLineCount} 段拣齐`;
}

/**
 * 路径警示语：这一行有哪些「该让作业员知道」的事。
 *
 * <p>三条分别对应 8.5.2.1 里 ownerModule=8.3.2.1 的两类建议，加上「没货可拣」：
 *
 * <ul>
 *   <li>{@code nonPickableSource} —— 高频货在不可拣库位（待检位 / 冻结位 / 残次位）；
 *   <li>{@code skuLocationCount > 1} —— SKU 散在多库位，一次拣货要多走；
 *   <li>{@code !sourceHasStock} —— 整个仓里一个可分配的库位都没有。
 * </ul>
 *
 * <p>三条是<b>并列</b>而不是互斥的：一行完全可以既散在多库位又只能退到不可拣位。
 */
export function lineWarnings(line: {
  nonPickableSource: boolean;
  skuLocationCount: number;
  sourceHasStock: boolean;
}): string[] {
  const out: string[] = [];
  if (!line.sourceHasStock) out.push('无可分配库存：这一行拣不了，请先补货或改单');
  if (line.nonPickableSource) out.push('只能从不可拣库位取货（对应 8.5.2.1「高频货在不可拣库位」）');
  if (line.skuLocationCount > 1) out.push(`该 SKU 散在 ${line.skuLocationCount} 个库位（对应 8.5.2.1「SKU 散在多库位」）`);
  return out;
}

/** 实拣库位与计划不一致时要说出来 —— 这是分配错了还是现场换了个地方，两种都要有人知道。 */
export function locationDrifted(line: { sourceLocationId: string }, pick: { locationId: string }): boolean {
  return line.sourceLocationId !== pick.locationId;
}

/** 缺口的展示：只有「还差几件」大于 0 才需要说。 */
export function shortfallText(shortfall: number): string {
  return shortfall > 0 ? `还差 ${qtyText(shortfall)} 件` : '已拣齐';
}

/** 序列号逐个列出（复核要按号核对，界面上不该只给一个条数）。 */
export function serialText(pick: PickTaskPickRow): string {
  if (pick.serialCount === 0) return '';
  return pick.serials.map((s) => s.serialNumber).join('、');
}