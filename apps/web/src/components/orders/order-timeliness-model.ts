import type {
  OrderTimelinessCapabilities,
  OrderTimelinessDetail,
  OrderTimelinessEventPage,
  OrderTimelinessFactPage,
  OrderTimelinessReport,
  TimelinessDeliveryInput,
  TimelinessDimensionPage,
  TimelinessOverduePage,
  TimelinessPromiseInput,
  TimelinessSyncInput,
  TimelinessSyncResult,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.5.1.1 时效分析前端模型。
 *
 * <p>蓝图：「统计订单时效分布：下单到出库、出库到交接、承诺到货 vs 实际到货，
 * 按店铺、仓库、商品、承运商维度拆解。」输出是「时效报表 + 分维度对比 + 超时明细」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「没测出来」与「0 秒」必须长得不一样。</b>后端把每一段拆成
 *       {@code measuredCount} 与 {@code unsetCount} 两个数（缺一个端点就是 UNSET），
 *       前端把 UNSET 渲染成灰底「未测出」，<b>绝不</b>渲染成 {@code 0 秒} ——
 *       0 秒的履约是一个比「算不出来」严重得多的结论，它会被人当真去汇报。</li>
 *   <li><b>UNSET 桶不是失败，也不是准时。</b>发货时限没登记 → 既不计入准时也不计入超时；
 *       界面用中性色而不是红色，避免把「还没登记」画成「已经迟发」。</li>
 *   <li><b>「没登记承诺」与「货还在路上」分成两个桶。</b>NO_PROMISE 与 NO_ACTUAL
 *       在平台申诉里要的举证材料完全不同（前者要平台 SLA，后者要轨迹签收），
 *       合并之后「我们压根没登记承诺」会伪装成「一切正常」。</li>
 *   <li><b>口径不自算。</b>达成率、超时率的<b>分母</b>全部来自服务端；
 *       前端只负责把分母一起显示出来，绝不自己用分子除总数重算一遍。</li>
 *   <li><b>分维度对比是真 GROUP BY。</b>与翻页无关，切页不会重算。</li>
 * </ul>
 */

const base = 'trade/fulfillment/timeliness';

// ------------------------------------------------------------ 参数

export interface TimelinessFilter {
  shopRef?: string;
  warehouseRef?: string;
  carrierCode?: string;
  from?: string;
  to?: string;
}

export interface TimelinessListParams extends TimelinessFilter {
  shipSlaState?: string;
  promiseState?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface TimelinessEventParams {
  kind?: string;
  factId?: string;
  page?: number;
  size?: number;
}

/**
 * 把参数拼成 query string。
 *
 * <p>刻意把入参声明成 {@code Record<string, ...>} 而不是复用调用方的 interface：
 * TS 的 interface **没有**隐式索引签名，直接传进去会报
 * 「不能赋给 Record<string, string|number>」。展开成新对象而不是 `as` 断言，
 * 是为了让这条约束在编译期真的被检查到。
 */
function query(params: object): string {
  const parts = Object.entries(params as Record<string, string | number | undefined | null>)
    .filter(([, v]) => v !== undefined && v !== null && `${v}` !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 读

export function fetchTimelinessCapabilities(): Promise<OrderTimelinessCapabilities> {
  return api<OrderTimelinessCapabilities>(`${base}/capabilities`);
}

export function fetchTimelinessReport(params: TimelinessFilter): Promise<OrderTimelinessReport> {
  return api<OrderTimelinessReport>(`${base}/report${query(params)}`);
}

export function fetchTimelinessDimensions(
  params: TimelinessFilter & { dimensionBy?: string; limit?: number },
): Promise<TimelinessDimensionPage> {
  return api<TimelinessDimensionPage>(`${base}/dimensions${query(params)}`);
}

export function fetchTimelinessOverdue(
  params: TimelinessFilter & { page?: number; size?: number },
): Promise<TimelinessOverduePage> {
  return api<TimelinessOverduePage>(`${base}/overdue${query(params)}`);
}

export function fetchTimelinessFacts(
  params: TimelinessListParams,
): Promise<OrderTimelinessFactPage> {
  return api<OrderTimelinessFactPage>(`${base}/facts${query(params)}`);
}

export function fetchTimelinessFact(factId: string): Promise<OrderTimelinessDetail> {
  return api<OrderTimelinessDetail>(`${base}/facts/${factId}`);
}

export function fetchTimelinessEvents(
  params: TimelinessEventParams,
): Promise<OrderTimelinessEventPage> {
  return api<OrderTimelinessEventPage>(`${base}/events${query(params)}`);
}

// ------------------------------------------------------------ 写

/** 从 9.4.1.2 的发货记录刷新时效事实（幂等投影，唯一的批量写入口）。 */
export function syncTimeliness(input: TimelinessSyncInput): Promise<TimelinessSyncResult> {
  return api<TimelinessSyncResult>(`${base}/sync`, 'POST', input);
}

/** 登记承诺到货 —— 2.5.1.1 未交付，本模块逐票登记，不假装读到了平台 SLA。 */
export function registerPromise(
  factId: string,
  input: TimelinessPromiseInput,
): Promise<OrderTimelinessDetail> {
  return api<OrderTimelinessDetail>(`${base}/facts/${factId}/promise`, 'POST', input);
}

/** 登记实际到货 —— 10.3 未交付，本模块登记人工上报的签收，不假装抓到了轨迹。 */
export function registerDelivery(
  factId: string,
  input: TimelinessDeliveryInput,
): Promise<OrderTimelinessDetail> {
  return api<OrderTimelinessDetail>(`${base}/facts/${factId}/delivery`, 'POST', input);
}

// ------------------------------------------------------------ 展示口径

/** 展示一个时长。`null` 一律渲染成「未测出」，**绝不**渲染成 0。 */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '未测出';
  const negative = seconds < 0;
  const abs = Math.abs(seconds);
  const d = Math.floor(abs / 86400);
  const h = Math.floor((abs % 86400) / 3600);
  const m = Math.floor((abs % 3600) / 60);
  const s = abs % 60;
  const body = d > 0 ? `${d}天${h}小时` : h > 0 ? `${h}小时${m}分` : m > 0 ? `${m}分${s}秒` : `${s}秒`;
  return negative ? `提前 ${body}` : body;
}

/**
 * 比率展示。**分母为 0 时返回 null 而不是 0%** ——
 * 「0% 的准时率」与「一票都没登记，所以算不出来」是两件事。
 */
export function formatRate(rate: number | string | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '—（无法判定）';
  return `${rate}%`;
}

export type BucketTone = 'ok' | 'warn' | 'bad' | 'neutral';

/**
 * 「承诺到货 vs 实际到货」四桶的色调。
 *
 * <p>刻意把两个未登记桶都画成 <b>neutral</b> 而不是 warn：
 * NO_PROMISE / NO_ACTUAL 不是已确认的问题，把它们画成警告会引人去查承运商，
 * 而真正该做的是把承诺 / 签收登记补上。
 */
export function promiseTone(state: string): BucketTone {
  switch (state) {
    case 'MET':
      return 'ok';
    case 'LATE':
      return 'bad';
    case 'NO_PROMISE':
    case 'NO_ACTUAL':
      return 'neutral';
    default:
      return 'neutral';
  }
}

export function promiseLabel(state: string): string {
  switch (state) {
    case 'MET':
      return '达成承诺';
    case 'LATE':
      return '超出承诺';
    case 'NO_PROMISE':
      return '未登记承诺到货';
    case 'NO_ACTUAL':
      return '未登记实际到货';
    default:
      return state || '—';
  }
}

/** 发货时限三桶：UNSET 是「没登记」，不是「准时」也不是「迟发」。 */
export function slaTone(state: string): BucketTone {
  switch (state) {
    case 'ON_TIME':
      return 'ok';
    case 'OVERDUE':
      return 'bad';
    case 'UNSET':
      return 'neutral';
    default:
      return 'neutral';
  }
}

export function slaLabel(state: string): string {
  switch (state) {
    case 'ON_TIME':
      return '按时发货';
    case 'OVERDUE':
      return '超出发货时限';
    case 'UNSET':
      return '未登记发货时限';
    default:
      return state || '—';
  }
}

/** 超时明细两类来源的中文说法。两者刻意不合并成一个「超时数」。 */
export function overdueKindLabel(kind: string): string {
  return kind === 'SHIP_SLA' ? '超出发货时限' : kind === 'PROMISE' ? '超出到货承诺' : kind;
}

/** 可用性缺口的措辞：available=false 的维度必须显示归属模块，否则现场不知道找谁。 */
export function capabilityTone(available: boolean): BucketTone {
  return available ? 'ok' : 'neutral';
}

export { ApiError };
