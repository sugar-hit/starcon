import type {
  WaybillBulkActionResult,
  WaybillDisplayStatusOption,
  WaybillFreightInput,
  WaybillFreightRow,
  WaybillListDetail,
  WaybillListFilterOptions,
  WaybillListPage,
  WaybillListRow,
  WaybillListSummary,
  WaybillListingCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 10.2.1.1 运单列表前端模型。
 *
 * <p>蓝图：「运单主列表：运单号、订单/包裹、承运商渠道、发货仓、状态（待取号/已取号/
 * 已揽收/运输中/已签收/异常/已取消）、下单时间、重量、运费。」输出「列表 + 批量操作
 * （申请运单、取消、打印）」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「已取号」不等于「在途中」。</b>蓝图加粗句要求运单状态从承运商侧获取为准，
 *       而 10.3 未交付 —— 所以已揽收/运输中/已签收/异常这四个态<b>没有数据源</b>。
 *       界面上绝不把它们画成「运输中」或留空：留空会被读成「一切正常」。
 *       {@link unavailableStatuses} 把它们显式标成不可选。</li>
 *   <li><b>「申请失败」不能画成「异常」。</b>前者是我们还没拿到号，后者是货在途中出问题；
 *       混成一个红色标签会让现场以为票已经在路上而漏跟进。</li>
 *   <li><b>运费「未登记」不是 0。</b>10.1.2.1 运费计算器未交付，本模块只能登记。
 *       单元格显示「未登记」而不是 {@code 0.00}，两种情况在汇总里也分开计数。</li>
 *   <li><b>汇总不受分页影响。</b>页脚显示的是<b>筛选后的全量</b>，不是当前这一页 ——
 *       否则翻到第二页会看到「共 20 条」。</li>
 *   <li><b>批量动作要逐条给结果。</b>「三条成了、一条跳过、一条失败」是三种不同的事，
 *       合成一个红数字会让现场以为「这批已经办好了」。</li>
 * </ul>
 */

const base = 'trade/logistics/waybills';

// ------------------------------------------------------------ 参数

export interface WaybillListParams {
  carrierCode?: string;
  carrierChannel?: string;
  status?: string;
  destinationCountry?: string;
  warehouseRef?: string;
  freight?: string;
  dateFrom?: string;
  dateTo?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/**
 * 把参数拼成 query string。
 *
 * <p>刻意把入参声明成 {@code object} 而不是复用调用方的 interface：TS 的 interface
 * <b>没有</b>隐式索引签名，直接传进去会报「不能赋给 Record<string, ...>」。
 */
function query(params: object): string {
  const parts = Object.entries(params as Record<string, string | number | boolean | undefined | null>)
    .filter(([, v]) => v !== undefined && v !== null && `${v}` !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 读

export function fetchListingCapabilities(): Promise<WaybillListingCapabilities> {
  return api<WaybillListingCapabilities>(`${base}/capabilities`);
}

export function fetchWaybillFilterOptions(): Promise<WaybillListFilterOptions> {
  return api<WaybillListFilterOptions>(`${base}/filter-options`);
}

export function fetchWaybillList(params: WaybillListParams): Promise<WaybillListPage> {
  return api<WaybillListPage>(`${base}/list${query(params)}`);
}

export function fetchWaybillDetail(id: string): Promise<WaybillListDetail> {
  return api<WaybillListDetail>(`${base}/${id}`);
}

// ------------------------------------------------------------ 写

export function registerWaybillFreight(
  id: string,
  input: WaybillFreightInput,
): Promise<WaybillFreightRow> {
  return api<WaybillFreightRow>(`${base}/${id}/freight`, 'POST', input);
}

// 三个批量动作各自写**字面量** URL，而不是共用一个 `act(action)` 拼串：
// 白名单测试靠「从源码反向抽调用点」来证明「代码里的每个形状都在白名单里」，
// 拼串会把动作名藏进变量里，反向抽出来就变成 `/actions/<id>` 这种查不出来的形状，
// 断言随之失去意义。

/** 批量申请运单：只对「申请失败」的票有意义，其余逐条返回跳过原因。 */
export function bulkApplyWaybill(
  requestIds: string[],
  reason = '',
): Promise<WaybillBulkActionResult> {
  return api<WaybillBulkActionResult>(`${base}/actions/apply`, 'POST', {
    requestIds,
    reason,
    printerTarget: '',
    remark: '',
  });
}

/** 批量取消：只作用于还没出号的申请；已取号的逐条点名 10.2.1.3（未交付）。 */
export function bulkCancelWaybill(
  requestIds: string[],
  reason: string,
): Promise<WaybillBulkActionResult> {
  return api<WaybillBulkActionResult>(`${base}/actions/cancel`, 'POST', {
    requestIds,
    reason,
    printerTarget: '',
    remark: '',
  });
}

/** 批量打印：只发已登记的面单凭据并逐件留痕，**不渲染 ZPL**（8.3.4.2 未交付）。 */
export function bulkPrintWaybill(
  requestIds: string[],
  printerTarget: string,
  remark = '',
): Promise<WaybillBulkActionResult> {
  return api<WaybillBulkActionResult>(`${base}/actions/print`, 'POST', {
    requestIds,
    reason: '',
    printerTarget,
    remark,
  });
}

// ------------------------------------------------------------ 展示派生
//
// 下面的函数都是**纯函数**：把后端字段翻译成界面上要显示的话，
// 并且在「不可信 / 缺失」时给出安全回落，而不是静默吞掉。
// 它们的单测在 waybill-listing-model.test.ts。

/**
 * 当前**拿不到数据源**的展示态。
 *
 * <p>这四个态（已揽收 / 运输中 / 已签收 / 承运商侧异常）在界面上要<b>禁用</b>而不是
 * 灰掉显示 —— 一个灰掉的状态选项看起来像「有但没权限」，而真实情况是「上游没交付」。
 */
export function unavailableStatuses(
  statuses: WaybillDisplayStatusOption[],
): WaybillDisplayStatusOption[] {
  return statuses.filter((s) => !s.available);
}

/** 状态分色。 */
export type WaybillTone = 'wait' | 'ok' | 'warn' | 'bad' | 'muted';

/**
 * 状态 → 色调。
 *
 * <p>「申请失败」与「已取消」是两种不同的红/灰：前者是系统没办成（要处理），
 * 后者是有人主动作废（正常）。画成同一个色会让人分不清该重试还是该忽略。
 */
export function displayStatusTone(row: WaybillListRow): WaybillTone {
  switch (row.displayStatus) {
    case 'PENDING_NUMBER':
      return 'wait';
    case 'NUMBER_ISSUED':
      return 'ok';
    case 'APPLICATION_FAILED':
      return 'bad';
    case 'CANCELLED':
      return 'muted';
    default:
      // 10.3 未交付时这四个态不会出现；真出现了必须显式区别于「正常」
      return 'warn';
  }
}

/**
 * 「已取号」这一格要附带的话。
 *
 * <p>蓝图要求状态以承运商侧为准，而 10.3 未交付 —— 所以每个已取号的行都必须说清
 * 「这不代表货已在路上」，否则运营会把「有单号」当成「在运输中」。
 */
export function issuedDisclaimer(row: WaybillListRow): string {
  return row.displayStatus === 'NUMBER_ISSUED'
    ? '已取号仅代表承运商已返回单号；揽收/在途/签收无数据源（10.3 未交付）'
    : '';
}

/**
 * 运费的单元格文案。
 *
 * <p>「未登记」与「0.00」是**两回事**，必须分开画：前者是没人录入（10.1.2.1 未交付），
 * 后者是渠道活动 / 内部调拨的合法 0。把两者都画成 0 会让成本报表少一块而没人知道。
 */
export function freightCell(row: WaybillListRow): { text: string; tone: WaybillTone } {
  if (row.freightGapCode === 'NOT_REGISTERED') return { text: '未登记', tone: 'warn' };
  const amount = row.freightAmount ?? 0;
  return {
    text: `${amount.toFixed(2)} ${row.freightCurrency || ''}`.trim(),
    tone: 'ok',
  };
}

/** 面单可打印性：没有凭据就不能送印，这一点必须写在界面上而不是留空。 */
export function printable(row: WaybillListRow): boolean {
  return row.labelAvailability === 'RECORDED_REFERENCE';
}

/**
 * 批量动作后的一行摘要。
 *
 * <p>「成了 / 跳过 / 失败」三个数字必须**分开显示** —— 合成一个红数字会让现场
 * 以为「这批已经办好了」。
 */
export function bulkSummaryLine(result: WaybillBulkActionResult): string {
  return `共 ${result.selected} 张：成功 ${result.applied}，跳过 ${result.skipped}，失败 ${result.failed}`;
}

/** 汇总数字（页脚）：强调它覆盖筛选后的全量，而不是当前页。 */
export function summaryLine(summary: WaybillListSummary): string {
  return (
    `筛选结果共 ${summary.total} 张` +
    `（待取号 ${summary.pendingNumber} · 已取号 ${summary.numberIssued}` +
    ` · 申请失败 ${summary.applicationFailed} · 已取消 ${summary.cancelled}）` +
    ` ｜ 运费已登记 ${summary.freightRegistered} / 未登记 ${summary.freightNotRegistered}` +
    ` ｜ 已出号但无承运商轨迹 ${summary.issuedWithoutCarrierTracking}`
  );
}

/**
 * 「已出号但无承运商轨迹」的告警。
 *
 * <p>10.3 未交付时这个数恒等于已取号数；界面上必须说出来，否则「已取号」会被
 * 当成「在运输中」。
 */
export function trackingGapWarning(summary: WaybillListSummary): string {
  return summary.issuedWithoutCarrierTracking > 0
    ? `其中 ${summary.issuedWithoutCarrierTracking} 张已取号但**没有任何承运商侧轨迹**（10.3.1.1 未交付）：已取号 ≠ 在途中`
    : '';
}

/** 下单时刻。空值显示「—」而不是 1970 —— 那是「没有」而不是「1970 年下单」。 */
export function timeText(value?: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 重量。后端给的是 kg，保留 3 位（承运商计费重通常到克）。 */
export function weightText(row: WaybillListRow): string {
  return `${row.grossWeightKg.toFixed(3)} kg`;
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}
