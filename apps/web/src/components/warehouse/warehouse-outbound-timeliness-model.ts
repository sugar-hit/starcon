import type {
  OutboundStageDistribution,
  OutboundThresholdView,
  OutboundTimelinessCapabilities,
  OutboundTimelinessReport,
  OutboundTimelinessRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';
import { errorText } from './warehouse-facility-model';

export { errorText };

/**
 * 8.5.3.1 出库时效前端模型。
 *
 * <p>蓝图：「统计出库各环节时效：订单到出库时长、拣货时长、打包时长、交接时长，识别超时环节与瓶颈。
 * 输入：订单时间戳；各作业环节时间戳。输出：时效分布 + 瓶颈环节 + 超时明细。」
 * 以及「时效数据直接对应平台迟发率指标，超时会导致绩效违规与搜索降权。」
 *
 * <p>本模型钉住四件在界面上最容易说错、且说错会直接导致误判绩效的事：
 *
 * <ul>
 *   <li><b>「不可计算」与 0 分钟必须分开。</b>后端对「还没走到该环节终点」和「负时长（时间戳错了）」
 *       一律给 {@code null}，并把这类单数报在 {@code missingSampleCounts}。渲染成 {@code 0} 会把
 *       「这单还没发」显示成「这单超快」—— 在一个直接决定平台迟发率的模块里，这是最贵的一种显示错。
 *   <li><b>阈值必须带来源一起显示。</b>看到 40 分钟这个数时，用户要能当场回答「这是谁定的」
 *       （仓库覆盖 / 租户默认 / 内置兜底）。只显示数字，调完阈值没人知道有没有生效。
 *   <li><b>瓶颈只在内部三环节里选。</b>订单到出库是总时长，恒大于任一环节，把它算进排名会让
 *       「瓶颈永远是订单到出库」这条结论恒为零。所以界面必须显示 basis，不只显示一个环节名。
 *   <li><b>缺失样本数要显示出来。</b>「今天时效很好」有可能只是「货还没发出去」；
 *       覆盖率与缺失数一起看，才知道那个平均值在说什么。
 * </ul>
 */

const base = 'warehouse/outbound-timeliness';

export interface TimelinessFilters {
  /** 统计区间首日（Asia/Shanghai 自然日）。留空时后端回落到近 30 天。 */
  from: string;
  /** 统计区间末日（含）。 */
  to: string;
  /** 仓库筛选；空串 = 全租户视角。 */
  warehouseId: string;
  page: number;
}

export const defaultFilters: TimelinessFilters = {
  from: '',
  to: '',
  warehouseId: '',
  page: 0,
};

/** 空串条件一律不传 —— 传空值会让后端拿到「筛一个空字符串」的语义。 */
function query(filters: TimelinessFilters): string {
  const parts = new URLSearchParams();
  if (filters.from.trim() !== '') parts.set('from', filters.from.trim());
  if (filters.to.trim() !== '') parts.set('to', filters.to.trim());
  if (filters.warehouseId.trim() !== '') parts.set('warehouseId', filters.warehouseId.trim());
  parts.set('page', String(filters.page));
  parts.set('size', '20');
  return parts.toString();
}

export function fetchTimeliness(filters: TimelinessFilters): Promise<OutboundTimelinessReport> {
  return api<OutboundTimelinessReport>(`${base}/report?${query(filters)}`);
}

export function fetchTimelinessCapabilities(
  warehouseId: string | null,
): Promise<OutboundTimelinessCapabilities> {
  // 空 = 全租户视角；后端把「不属于本租户的仓库」与「不存在」都报 404，不给猜 id 的侧信道。
  const suffix =
    warehouseId !== null && warehouseId.trim() !== ''
      ? `?warehouseId=${encodeURIComponent(warehouseId.trim())}`
      : '';
  return api<OutboundTimelinessCapabilities>(`${base}/capabilities${suffix}`);
}

/**
 * 环节时长文本（分钟）。
 *
 * <p>`null` 一律显示「不可计算」而不是 0 —— 见类注释第一条。
 * 这是本模块唯一一个「绝不能折成 0」的渲染点。
 */
export function minutesText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '不可计算';
  return `${value.toFixed(1)} 分钟`;
}

/**
 * 时长列的可比较性文本：用于排序 / 判断是否超时。
 *
 * <p>`null` 返回 `null`（不是 0），调用方据此跳过比较 ——
 * 排序时把「不可计算」当 0 排到最前，会让人以为那是最快的单。
 */
export function sortableMinutes(value: number | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  return value;
}

/** 比率文本（后端给的是百分数，如 12.5000 表示 12.5%）。 */
export function rateText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${value.toFixed(2)}%`;
}

/** 阈值来源中文名（后端已给 sourceLabel，这份是契约外的防御）。 */
export const thresholdSourceLabels: Record<string, string> = {
  WAREHOUSE: '仓库覆盖',
  TENANT: '租户默认',
  BUILTIN: '内置兜底',
};

export function thresholdSourceLabel(view: OutboundThresholdView): string {
  return view.sourceLabel || thresholdSourceLabels[view.source] || view.source;
}

/**
 * 阈值文本，**永远带来源**。
 *
 * <p>「40 分钟（仓库覆盖）」而不是「40 分钟」：绩效申诉时要能回答「这是谁定的」，
 * 否则调完阈值没人知道有没有生效。
 */
export function thresholdText(view: OutboundThresholdView): string {
  return `${view.thresholdMinutes.toFixed(1)} 分钟（${thresholdSourceLabel(view)}）`;
}

/** 某环节在本次报表里生效的阈值视图。 */
export function thresholdFor(
  thresholds: readonly OutboundThresholdView[],
  stageCode: string,
): OutboundThresholdView | undefined {
  return thresholds.find((t) => t.stageCode === stageCode);
}

/** 分布里某个环节的统计；不存在时给 undefined，界面据此说「本区间无该环节样本」。 */
export function stageDistribution(
  distribution: readonly OutboundStageDistribution[],
  stageCode: string,
): OutboundStageDistribution | undefined {
  return distribution.find((d) => d.stageCode === stageCode);
}

/** 逐单时长取值（与后端的 stage 枚举一一对应）。 */
export function rowMinutes(
  row: OutboundTimelinessRow,
  stageCode: string,
): number | null | undefined {
  switch (stageCode) {
    case 'ORDER_TO_OUTBOUND':
      return row.orderToOutboundMinutes;
    case 'PICKING':
      return row.pickingMinutes;
    case 'PACKING':
      return row.packingMinutes;
    case 'HANDOVER':
      return row.handoverMinutes;
    default:
      return null;
  }
}

/** 报表为空（区间内一单已发货出库单都没有）。 */
export function isEmptyTimeliness(report: OutboundTimelinessReport): boolean {
  return report.shippedOrderCount === 0 && report.rows.length === 0;
}

/** 缺失样本数（该环节终点未发生的单数）—— 0 也是合法值，必须与「不可计算」区分开。 */
export function missingCount(report: OutboundTimelinessReport, stageCode: string): number {
  // 生成契约把 missingSampleCounts 落成 `{}`（无索引签名），按 Record 取值：
  // 缺键回落 0（= 没有单缺失），这与「该环节时长为 null」不是同一件事。
  const counts = report.missingSampleCounts as Record<string, number | undefined>;
  return counts[stageCode] ?? 0;
}

/**
 * 分桶文本。
 *
 * <p>空样本时 share 为 null（后端分母为 0）—— 显示「—」而不是 0%，
 * 因为「没有样本」与「样本都落在这一档」是两件事。
 */
export function bucketText(orderCount: number, share: number): string {
  return `${orderCount} 单 · ${share === null ? '—' : `${share.toFixed(2)}%`}`;
}
