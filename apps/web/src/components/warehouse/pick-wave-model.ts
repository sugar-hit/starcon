import type {
  PickWaveCapabilities,
  PickWaveCreateInput,
  PickWaveCandidatePage,
  PickWaveCancelInput,
  PickWaveCompleteInput,
  PickWaveCreated,
  PickWaveDetail,
  PickWavePage,
  PickWaveReleaseInput,
  PickWaveRuleInput,
  PickWaveRuleList,
  PickWaveRuleRetireInput,
  PickWaveRuleRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.3.2.2 波次与合并拣货前端模型。
 *
 * <p>蓝图 08 卷 8.3.2.2：「<b>把多张出库单按规则合并成拣货波次，提高拣货效率：按承运商/库区/订单数合并，
 * 支持波次进度跟踪。</b>」输入「出库单集合；合并规则（承运渠道、库区、时段）。」输出「波次任务 + 各出库单在波次中的位置。」
 * 关系：「<b>合并只影响拣货效率，不改变订单粒度</b>：波次完成后仍需按订单拆分复核与打包。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>合并不改订单粒度。</b>成员列表一行 = <b>一张出库单的一张拣货任务</b>，
 *       每行都显示自己的出库单号、自己的任务状态与进度；波次只是把它们排成一组一起走。
 *       界面上不许出现「这一波共 12 件货」这种把订单揉成一团的汇总口径。</li>
 *   <li><b>候选按合并键分组显示</b>（承运渠道 × 库区），不是一张混排的清单：
 *       一屏里混着四个承运商的订单，看的人无法判断现在该开哪一波。</li>
 *   <li><b>三个计数必须都显示</b>：{@code eligibleCount} 现在能并、
 *       {@code waitingCount} 得等上一波收工或等上限、{@code unruledCount} 没有规则认领。
 *       只显示前一个，看板上就只剩一个解释不通的空白。</li>
 *   <li><b>库区与时段的口径写在页面上</b>（{@code zoneBasis} / {@code timeSlotBasis}）：
 *       库区是从取货库位沿库位树上行得到的、时段按<b>仓本地</b>小时判 ——
 *       两条都会被误读，而误读的后果是「为什么这张单没被合进去」没人答得上来。</li>
 *   <li><b>跨区任务要标出来</b>（{@code crossZone}）：它的拣货段横跨多个区，没有单一库区，
 *       只能进「库区不限」的规则 —— 不标出来的话界面会让人以为它漏配了规则。</li>
 *   <li><b>进度分母为 0 时不给百分比</b>：{@code progress} 为 null 表示「还没排出计划」，
 *       显示 0% 会把「没量过」说成「一件没拣」。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/pick-waves}，刻意不挂在 {@code outbound-orders} 之下 ——
 * 与 {@code pick-tasks} 同一理由：一个波次横跨多张出库单，它不属于任何一张出库单。
 */

const base = 'warehouse/pick-waves';

export type PickWaveStatus = PickWaveDetail['wave']['status'];
export type PickWaveEventType = PickWaveDetail['events'][number]['eventType'];

export interface PickWaveParams {
  warehouseId?: string;
  status?: string;
  carrierCode?: string;
  zoneId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function pickWaveQuery(params: PickWaveParams): string {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.carrierCode) parts.push(`carrierCode=${encodeURIComponent(params.carrierCode)}`);
  if (params.zoneId) parts.push(`zoneId=${encodeURIComponent(params.zoneId)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchPickWaveCapabilities() {
  return api<PickWaveCapabilities>(`${base}/capabilities`);
}

export function fetchPickWaveRules(warehouseId?: string) {
  const query = warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : '';
  return api<PickWaveRuleList>(`${base}/rules${query}`);
}

export function createPickWaveRule(input: PickWaveRuleInput) {
  return api<PickWaveRuleRow>(`${base}/rules`, 'POST', input);
}

export function retirePickWaveRule(ruleId: string, input: PickWaveRuleRetireInput) {
  return api<PickWaveRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}/retire`, 'POST', input);
}

export function fetchPickWaveCandidates(warehouseId: string) {
  return api<PickWaveCandidatePage>(
    `${base}/candidates?warehouseId=${encodeURIComponent(warehouseId)}`,
  );
}

/**
 * 建波次。
 *
 * <p>入参直接用契约类型而不是手写字面量：springdoc 把 {@code @Schema(nullable = true)}
 * 的字段生成成**必填但可为 null** 的键，而本仓库开了 {@code exactOptionalPropertyTypes} ——
 * 写成可选的 {@code ruleId?: string} 再传 {@code undefined} 会被类型系统当场拒绝，
 * 传 {@code null} 才与后端一致（与 8.3.2.1 的 {@link assignPickTask} 同一纪律）。
 */
export function createPickWave(input: PickWaveCreateInput) {
  return api<PickWaveCreated>(`${base}`, 'POST', input);
}

export function fetchPickWaves(params: PickWaveParams) {
  return api<PickWavePage>(`${base}${pickWaveQuery(params)}`);
}

export function fetchPickWave(waveId: string) {
  return api<PickWaveDetail>(`${base}/${encodeURIComponent(waveId)}`);
}

export function releasePickWave(waveId: string, input: PickWaveReleaseInput) {
  return api<PickWaveDetail>(`${base}/${encodeURIComponent(waveId)}/release`, 'POST', input);
}

export function completePickWave(waveId: string, input: PickWaveCompleteInput) {
  return api<PickWaveDetail>(`${base}/${encodeURIComponent(waveId)}/complete`, 'POST', input);
}

export function cancelPickWave(waveId: string, input: PickWaveCancelInput) {
  return api<PickWaveDetail>(`${base}/${encodeURIComponent(waveId)}/cancel`, 'POST', input);
}

export function detachPickWaveTask(waveId: string, pickTaskId: string) {
  return api<PickWaveDetail>(
    `${base}/${encodeURIComponent(waveId)}/tasks/${encodeURIComponent(pickTaskId)}/detach`,
    'POST',
    {},
  );
}

// ================================================================ 展示口径

export const statusLabels: Record<PickWaveStatus, string> = {
  PLANNED: '已排波',
  PICKING: '拣货中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

export function statusLabel(status: PickWaveStatus): string {
  return statusLabels[status] ?? status;
}

/**
 * 状态色调。已取消刻意用 danger 而不是 success：取消是「这一波作废了」，
 * 涂成和完成一样的绿会让作业台看起来像多了完成量。
 */
export function statusTone(status: PickWaveStatus): 'neutral' | 'info' | 'success' | 'danger' {
  switch (status) {
    case 'PICKING':
      return 'info';
    case 'COMPLETED':
      return 'success';
    case 'CANCELLED':
      return 'danger';
    default:
      return 'neutral';
  }
}

export const eventTypeLabels: Record<string, string> = {
  PLANNED: '排波',
  RELEASED: '放行',
  TASK_ATTACHED: '并入工单',
  TASK_DETACHED: '移出工单',
  COMPLETED: '完成',
  CANCELLED: '取消',
};

export function eventTypeLabel(type: string): string {
  return eventTypeLabels[type] ?? type;
}

// ---------------------------------------------------------------- 可用动作

/**
 * 能不能放行。只有「已排波」能放行，且必须给负责人 ——
 * 没有负责人的波次会在作业台上一直挂着，而 8.5.1.1 的作业效率也无从归因。
 */
export function canRelease(status: PickWaveStatus): boolean {
  return status === 'PLANNED';
}

/** 能不能移出成员。放行之后不行：拣货组已经按位置走了，中途抽走会让已发出的单对不上。 */
export function canDetach(status: PickWaveStatus, memberCount: number): boolean {
  return status === 'PLANNED' && memberCount > 1;
}

/** 能不能取消。未完态都能取消；已完成 / 已取消的波次取消没有意义。 */
export function canCancel(status: PickWaveStatus): boolean {
  return status === 'PLANNED' || status === 'PICKING';
}

/**
 * 能不能完成波次。
 *
 * <p>判据是「全部成员工单都到终态」，而不是「拣了多少件」：完成波次是把这一组
 * 交给下游复核，而复核是<b>按出库单逐张</b>做的 —— 少一张没拣完就报完成，
 * 下游拿到的凭据是假的。
 */
export function canComplete(
  status: PickWaveStatus,
  terminalTaskCount: number,
  taskCount: number,
): boolean {
  return status === 'PICKING' && taskCount > 0 && terminalTaskCount === taskCount;
}

// ---------------------------------------------------------------- 进度与计数

/**
 * 进度文案。
 *
 * <p>后端 {@code progress} 为 null 表示「计划量还没有」（分母为 0），
 * 这时显示「—」而不是 0% —— 「没量过」与「量过了，是 0」在作业台上是两件事。
 */
export function progressText(progress: number | null): string {
  if (progress === null) return '—';
  return `${Math.round(progress * 1000) / 10}%`;
}

/** 数量格式化：去掉无意义的小数尾零，但 0 必须显示成 0 而不是空串。 */
export function qtyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value === 0) return '0';
  const text = String(value);
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/**
 * 合并键的显示文本。
 *
 * <p>库区为 null 时显示「跨区或库区不限」而不是留空：空格子在这张表上
 * 有两种完全不同的含义（跨区 / 不限），而这两种都区别于「有一个叫空的区」。
 */
export function mergeKeyText(carrierCode: string, zoneCode: string | null): string {
  const carrier = carrierCode ? carrierCode : '承运渠道不限';
  return `${carrier} / ${zoneCode ?? '跨区或库区不限'}`;
}

/** 时段窗口的显示文本。 */
export function slotText(start: number, end: number): string {
  const left = String(start).padStart(2, '0');
  const right = String(end).padStart(2, '0');
  return `${left}:00-${right}:00`;
}

/**
 * 规则覆盖范围的显示文本。
 *
 * <p>「不限」写出来而不是留空：一条不限承运渠道的规则和一条只认 SF 的规则，
 * 在界面上必须一眼能分开 —— 否则看的人会以为这条规则没填完整。
 */
export function ruleScopeText(rule: PickWaveRuleRow): string {
  const carrier = rule.carrierUnbounded ? '承运渠道不限' : rule.carrierMatch;
  const zone = rule.zoneUnbounded ? '库区不限' : `库区 ${rule.zoneCode ?? rule.zoneId}`;
  return `${carrier} / ${zone} / ${rule.slotLabel}`;
}

/**
 * 一组候选的结论。
 *
 * <p>三种结论要分开说，因为「现在不能建波次」有三种完全不同的原因，
 * 处理动作也不同（开波 / 等上一波 / 去配规则）。
 */
export function groupVerdict(group: {
  openWaveId: string | null;
  openWaveCode: string | null;
  eligibleCount: number;
  waitingCount: number;
  unruledCount: number;
}): string {
  if (group.openWaveId) {
    return (
      `已有未完波次 ${group.openWaveCode}：同库区同承运渠道同时只能走一个波次，` +
      `${group.waitingCount} 张在等它收工`
    );
  }
  if (group.eligibleCount === 0 && group.unruledCount > 0) {
    return `${group.unruledCount} 张出库单没有任何生效规则认领：请先配置合并规则（承运渠道 / 库区 / 时段 / 订单数上限）`;
  }
  if (group.waitingCount > 0) {
    return `可并入 ${group.eligibleCount} 张；另有 ${group.waitingCount} 张已撞上订单数上限，等这一波收工`;
  }
  return `可并入 ${group.eligibleCount} 张`;
}

/**
 * 成员的告警语。
 *
 * <p>跨区与未拣齐两件事都值得提一句：前者是「它进不了按库区合并的波次」，
 * 后者是「波次还不能完成」。两者都不阻断操作，所以返回数组而不是单条字符串。
 */
export function memberWarnings(member: {
  crossZoneNote?: string | null;
  fullyPicked: boolean;
  taskStatus: string;
}): string[] {
  const out: string[] = [];
  if (member.crossZoneNote) out.push(member.crossZoneNote);
  if (member.taskStatus === 'CANCELLED') out.push('这张拣货任务已取消：不拣了，但波次仍按它计成员');
  if (!member.fullyPicked && member.taskStatus !== 'CANCELLED') out.push('还没拣齐');
  return out;
}

/**
 * 波次状态的一句话提示。
 *
 * <p>刻意带「合并只影响拣货效率、不改变订单粒度」这句：完成之后出库单还要
 * 各自去 8.3.3.1 复核与打包，这句话不说，界面上「已完成」很容易被读成「货可以发了」。
 */
export function waveHint(status: PickWaveStatus): string {
  switch (status) {
    case 'PLANNED':
      return '已排波，成员位置已定；放行之后要指定负责人，中途不能再移出成员。';
    case 'PICKING':
      return '拣货中：成员各自在 8.3.2.1 上推进，全部终态之后才能完成波次。';
    case 'COMPLETED':
      return '合并结束：出库单各自按 8.3.3.1 逐单复核与打包。合并只影响拣货效率，不改变订单粒度。';
    case 'CANCELLED':
      return '已取消：成员被放回候选池，货仍在原库位、预占不变。';
    default:
      return '';
  }
}
