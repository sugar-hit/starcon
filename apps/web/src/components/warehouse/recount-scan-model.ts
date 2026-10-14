import type {
  RecountCapabilities,
  RecountCreated,
  RecountDetail,
  RecountExceptionRow,
  RecountOpenInput,
  RecountPage,
  RecountRuleInput,
  RecountRulePage,
  RecountRuleRow,
  RecountScanInput,
  RecountScanRow,
  RecountVerdictReport,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.3.3.1 复核扫描前端模型。
 *
 * <p>蓝图 08 卷 8.3.3.1：「<b>打包前复核：逐件扫描商品与订单核对，检出漏件/错件/多件，支持开箱复核（高价值订单）。</b>
 * 输入：扫描记录（条码/序列号）；订单明细；复核规则。输出：复核通过/异常结果；异常单转人工处理。」
 * 关系：「<b>复核是错发拦截的最后机会</b>，序列号商品必须逐件核对。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>复核通过 ≠ 已发货 ≠ 已打包。</b>「完成」的文案与提示都必须说清
 *       「只把出库单交到待打包，货在打包区、预占仍在」；状态词表里也没有这两档。</li>
 *   <li><b>「通过」按钮的可点性由后端说了算。</b>{@code RecountVerdictReport.blocking}
 *       与 {@code blockingReasons} 是唯一判据，页面不自己数行、不自己判异常 ——
 *       那套数在前端再算一遍就会与账上的数差一位。</li>
 *   <li><b>件数相等 ≠ 核过了。</b>{@code requiresSerial} 的行即使
 *       {@code scannedQuantity === expectedQuantity}，只要 {@code serialMatch === false}
 *       就必须显示成「序列号不符」并把该行顶到最前面。</li>
 *   <li><b>应发 / 实拣 / 实扫三个数分开展示。</b>「拣漏了」和「复核没扫到」是两件事，
 *       混成一个数就分不清该退回拣货还是重扫一遍。</li>
 *   <li><b>漏件与多件只在结算时出现。</b>扫描过程中「还没扫齐」不算漏件，
 *       所以列表与详情都不在扫描动作里就地判定，只有结算/完成才定论。</li>
 *   <li><b>「高价值」在本服务只能取件数。</b>界面上写「应发件数达到阈值，建议开箱复核」，
 *       <b>不写「高价值金额」</b> —— 出库单上没有金额列，编一个金额出来比不给更糟。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/recount-scans}，刻意不挂在 {@code outbound-orders}
 * 之下 —— 一张出库单可以复核多次（判异常 → 退回拣货 → 重拣 → 再复核），
 * 每次是一张独立复核单。
 */

const base = 'warehouse/recount-scans';

export type RecountStatus = 'OPEN' | 'PASSED' | 'FAILED' | 'CANCELLED';
export type RecountLineStatus = 'PENDING' | 'MATCHED' | 'MISSING' | 'OVER' | 'MISMATCH';
export type RecountExceptionKind =
  | 'MISSING_ITEM'
  | 'WRONG_ITEM'
  | 'EXTRA_ITEM'
  | 'DUPLICATE_SCAN'
  | 'SERIAL_MISMATCH'
  | 'UNKNOWN_BARCODE';
export type RecountScanType = 'BARCODE' | 'SERIAL';

export interface RecountParams {
  warehouseId?: string;
  outboundOrderId?: string;
  status?: string;
  reviewerUserId?: string;
  openBoxOnly?: boolean;
  exceptionsOnly?: boolean;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function recountQuery(params: RecountParams): string {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.outboundOrderId)
    parts.push(`outboundOrderId=${encodeURIComponent(params.outboundOrderId)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.reviewerUserId)
    parts.push(`reviewerUserId=${encodeURIComponent(params.reviewerUserId)}`);
  if (params.openBoxOnly) parts.push('openBoxOnly=true');
  if (params.exceptionsOnly) parts.push('exceptionsOnly=true');
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchRecountCapabilities() {
  return api<RecountCapabilities>(`${base}/capabilities`);
}

export function fetchRecounts(params: RecountParams) {
  return api<RecountPage>(`${base}${recountQuery(params)}`);
}

export function fetchRecount(sessionId: string) {
  return api<RecountDetail>(`${base}/${encodeURIComponent(sessionId)}`);
}

export function openRecount(input: RecountOpenInput) {
  return api<RecountCreated>(`${base}`, 'POST', input);
}

export function scanRecountItem(sessionId: string, input: RecountScanInput) {
  return api<RecountScanRow>(`${base}/${encodeURIComponent(sessionId)}/scans`, 'POST', input);
}

export function settleRecount(sessionId: string) {
  return api<RecountVerdictReport>(`${base}/${encodeURIComponent(sessionId)}/settle`, 'POST');
}

export function completeRecount(sessionId: string) {
  return api<RecountVerdictReport>(`${base}/${encodeURIComponent(sessionId)}/complete`, 'POST');
}

export function openRecountBox(sessionId: string, reason: string) {
  return api<RecountCreated>(
    `${base}/${encodeURIComponent(sessionId)}/open-box`,
    'POST',
    { reason },
  );
}

export function escalateRecount(sessionId: string, note: string) {
  return api<RecountExceptionRow>(
    `${base}/${encodeURIComponent(sessionId)}/escalate`,
    'POST',
    { note },
  );
}

export function resolveRecountException(
  sessionId: string,
  exceptionId: string,
  resolution: string,
  note: string,
) {
  return api<RecountExceptionRow>(
    `${base}/${encodeURIComponent(sessionId)}/exceptions/${encodeURIComponent(exceptionId)}/resolve`,
    'POST',
    { resolution, note },
  );
}

export function cancelRecount(sessionId: string, reason: string) {
  return api<RecountCreated>(
    `${base}/${encodeURIComponent(sessionId)}/cancel`,
    'POST',
    { reason },
  );
}

export function fetchRecountRules() {
  return api<RecountRulePage>(`${base}/rules`);
}

export function createRecountRule(input: RecountRuleInput) {
  return api<RecountRuleRow>(`${base}/rules`, 'POST', input);
}

// ---------------------------------------------------------------- 展示口径

/**
 * 件数文本。
 *
 * <p><b>null 不是 0。</b>后端把「还没量过」给成 null（例如分母为空时的合计），
 * 直接印 0 会把「没量」说成「一件都没有」—— 那是两种完全不同的现场事实。
 */
export function qtyText(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return String(value);
}

/** 差值文本：只在该行真的没核齐时才有意义。 */
export function shortfallText(
  expected: string | null | undefined,
  scanned: string | null | undefined,
): string {
  if (expected === null || expected === undefined || expected === '') return '';
  const e = Number(expected);
  const s = Number(scanned ?? '0');
  if (Number.isNaN(e) || Number.isNaN(s)) return '';
  const diff = e - s;
  if (diff === 0) return '';
  return diff > 0 ? `少 ${diff} 件` : `多 ${Math.abs(diff)} 件`;
}

export function statusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '复核中';
    case 'PASSED':
      return '复核通过';
    case 'FAILED':
      return '复核异常';
    case 'CANCELLED':
      return '已取消';
    default:
      return status;
  }
}

/**
 * 状态色调。
 *
 * <p>「复核异常」用红不是黄：复核是错发拦截的最后机会，一单没过就意味着
 * 它<b>本来会被错发出去</b>，这不是「有点问题」，是事故。
 */
export function statusTone(status: string): 'neutral' | 'info' | 'success' | 'danger' {
  switch (status) {
    case 'PASSED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'OPEN':
      return 'info';
    default:
      return 'neutral';
  }
}

export function lineStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '未核';
    case 'MATCHED':
      return '核过';
    case 'MISSING':
      return '漏件';
    case 'OVER':
      return '多件';
    case 'MISMATCH':
      return '序列号不符';
    default:
      return status;
  }
}

/**
 * 行状态色调。
 *
 * <p><b>「序列号不符」与「多件」都按危险色渲染</b>，尽管多件有时只是多拿了一件 ——
 * 在复核这一步，「多出来的东西」和「少掉的东西」后果一样：包裹内容与订单不符。
 */
export function lineStatusTone(status: string): 'neutral' | 'info' | 'success' | 'danger' {
  switch (status) {
    case 'MATCHED':
      return 'success';
    case 'PENDING':
      return 'neutral';
    case 'MISMATCH':
    case 'MISSING':
    case 'OVER':
      return 'danger';
    default:
      return 'neutral';
  }
}

export function exceptionKindLabel(kind: string): string {
  switch (kind) {
    case 'MISSING_ITEM':
      return '漏件';
    case 'WRONG_ITEM':
      return '错件';
    case 'EXTRA_ITEM':
      return '多件';
    case 'DUPLICATE_SCAN':
      return '重复扫';
    case 'SERIAL_MISMATCH':
      return '序列号集合不符';
    case 'UNKNOWN_BARCODE':
      return '未知码';
    default:
      return kind;
  }
}

export function resolutionLabel(resolution: string): string {
  switch (resolution) {
    case 'PENDING':
      return '待处理';
    case 'MANUAL':
      return '转人工处理';
    case 'CORRECTED':
      return '已修正';
    case 'ACCEPTED':
      return '接受现状';
    case 'IGNORED':
      return '忽略';
    default:
      return resolution;
  }
}

export function verdictLabel(verdict: string): string {
  switch (verdict) {
    case 'PASSED':
      return '核过';
    case 'FAILED':
      return '没核过';
    case 'OPEN':
      return '待结算';
    default:
      return verdict;
  }
}

export function scanTypeLabel(scanType: string): string {
  return scanType === 'SERIAL' ? '序列号' : '条码';
}

/**
 * 行上的警告语。
 *
 * <p>刻意区分三种说法，因为它们指向三种不同的下一步：
 * 「序列号集合不符」要回去查是哪台机器不对，「少 N 件」要补货或退回拣货，
 * 「多 N 件」要拿掉多出来的东西。
 */
export function lineWarnings(line: {
  lineStatus: string;
  requiresSerial: boolean;
  serialMatch: boolean;
  expectedQuantity: string;
  scannedQuantity: string;
}): string[] {
  const warnings: string[] = [];
  if (line.requiresSerial && !line.serialMatch) {
    warnings.push('序列号集合与实拣不相等 —— 件数对得上也不算数，必须逐件核对到每一个号');
  }
  const gap = shortfallText(line.expectedQuantity, line.scannedQuantity);
  if (gap !== '') warnings.push(`${gap}：订单明细与实扫对不上`);
  if (line.lineStatus === 'PENDING') warnings.push('一件都还没扫到');
  return warnings;
}

/**
 * 能不能点「完成复核」。
 *
 * <p>只用后端给的 {@code blocking}，**不在前端自己数行数异常**。
 * 前端那套数与账上的数差一位时，界面会显示「可以放行」而后端拒 —— 那是最难查的一种错。
 */
export function canComplete(sessionStatus: string, verdict?: RecountVerdictReport | null): boolean {
  if (sessionStatus !== 'OPEN') return false;
  return verdict ? !verdict.blocking : false;
}

/** 能不能扫。只有「复核中」可以。 */
export function canScan(sessionStatus: string): boolean {
  return sessionStatus === 'OPEN';
}

/** 能不能开箱。只有「复核中」可以，而且必须写原因。 */
export function canOpenBox(sessionStatus: string, alreadyOpen: boolean): boolean {
  return sessionStatus === 'OPEN' && !alreadyOpen;
}

/** 能不能转人工。有未处理异常才有东西可转。 */
export function canEscalate(pendingExceptionCount: number, sessionStatus: string): boolean {
  return sessionStatus !== 'CANCELLED' && pendingExceptionCount > 0;
}

/** 处置意见必填：四个处置值全都要求写下怎么处理或为什么接受。 */
export function needsResolveNote(resolution: string): boolean {
  return (
    resolution === 'MANUAL' || resolution === 'CORRECTED' || resolution === 'ACCEPTED' || resolution === 'IGNORED'
  );
}

/**
 * 完成后的提示文案。
 *
 * <p>刻意逐字写明「货在打包区、预占仍在」：复核是只读核对动作，
 * 作业员最容易误解的就是「复核完了是不是就发出去了」。
 */
export function completionText(verdict: RecountVerdictReport | null | undefined): string {
  if (!verdict) return '';
  if (verdict.verdict === 'PASSED') {
    return '复核通过：每一行都核过、序列号逐件对上、没有未处理的异常。'
      + '出库单已交到「待打包」（8.3.3.2）——复核不写库存分录、不释放预占，'
      + '货与占用都还在打包区，发货由 8.3.4.1 写。';
  }
  return `复核没通过：${verdict.blockingReasons.join('；')}。`
    + '检出拣错或漏件时的出口是退回拣货（出库单「待复核」→「拣货中」，由 8.3.2.1 重开），'
    + '本模块不改库存。';
}

/**
 * 「高价值」提示文案。
 *
 * <p>只说<b>应发件数</b>达到阈值，绝不写成金额：出库单上没有金额列
 * （V72 的 COPY 语义不存售价与成本），界面上编一个金额比不给更糟。
 */
export function highValueHint(verdict: RecountVerdictReport | null | undefined): string {
  if (!verdict?.openBoxRequired) return '';
  return verdict.openBox
    ? '本单已开箱复核。'
    : `本单应发件数 ${verdict.baselineQuantity ?? '—'} 达到高价值阈值，`
      + '建议开箱复核后再放行（开箱要人工确认并写明原因）。';
}