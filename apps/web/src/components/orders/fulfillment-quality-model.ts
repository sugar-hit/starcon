import type {
  AfterSaleSignalPage,
  AfterSaleSignalRow,
  AlertEvaluateResult,
  AlertPage,
  AlertRuleRow,
  FulfillmentExceptionPage,
  FulfillmentExceptionRow,
  QualityCapabilities,
  QualityDimensionPage,
  QualityDrilldownPage,
  QualityEventPage,
  QualityReport,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.5.2.1 履约质量前端模型。
 *
 * <p>蓝图：「统计履约质量：发货及时率、完整发货率、错发漏发率、取消率、纠纷率、
 * 退货率，按店铺与人员维度对比。」输出是「质量指标卡 + 明细下钻」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「算不出来」与「0%」必须长得不一样。</b>服务端把分母为 0 的
 *       {@code rate} 返成 {@code null}，前端一律渲染成灰底「无法判定」，
 *       <b>绝不</b>渲染成 {@code 0%} —— 「及时率 0%」是一个会被当真去汇报的结论，
 *       而它实际表达的是「这个窗口一票都没测过」。</li>
 *   <li><b>每个比率都必须带上分母。</b>只显示 66.67% 而不显示「发全 2 行 ÷ 有进度 3 行」，
 *       这个数字就没法用来做任何判断：分母 2 和分母 2000 的 66.67% 完全是两件事。</li>
 *   <li><b>「没登记」不是「没问题」。</b>纠纷率 / 退货率的上游在别的服务的独立库，
 *       当前读的是租户维护的信号表 —— 界面上必须显示「未登记 N 单」，
 *       让人一眼看出「这个 0% 是没人登记，不是没有纠纷」。</li>
 *   <li><b>缺口语义要显眼。</b>错发 SKU 源、售后真实数据、shop_code、16/17 联动
 *       四处 available=false 由 capabilities 逐条返回，界面用独立的缺口区列出来，
 *       而不是让它混在指标卡的 0% 里。</li>
 *   <li><b>口径不自算。</b>比率与分子分母全部来自服务端，前端只用分子分母做展示，
 *       绝不自己用分子除总数重算一遍。</li>
 * </ul>
 */

const base = 'trade/fulfillment/quality';

// ------------------------------------------------------------ 参数

export interface QualityFilter {
  from?: string;
  to?: string;
}

export interface QualityDimensionParams extends QualityFilter {
  dimensionBy?: string;
  limit?: number;
}

export interface QualityDrilldownParams extends QualityFilter {
  metric?: string;
  dimensionBy?: string;
  dimensionValue?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface SignalListParams {
  signalType?: string;
  signalState?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface ExceptionListParams {
  exceptionType?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface AlertListParams {
  metric?: string;
  dimensionCode?: string;
  page?: number;
  size?: number;
}

export interface EventListParams {
  kind?: string;
  orderId?: string;
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

export function fetchQualityCapabilities(): Promise<QualityCapabilities> {
  return api<QualityCapabilities>(`${base}/capabilities`);
}

export function fetchQualityReport(params: QualityFilter): Promise<QualityReport> {
  return api<QualityReport>(`${base}/report${query(params)}`);
}

export function fetchQualityDimensions(params: QualityDimensionParams): Promise<QualityDimensionPage> {
  return api<QualityDimensionPage>(`${base}/dimensions${query(params)}`);
}

export function fetchQualityDrilldown(params: QualityDrilldownParams): Promise<QualityDrilldownPage> {
  return api<QualityDrilldownPage>(`${base}/drilldown${query(params)}`);
}

export function fetchAfterSaleSignals(params: SignalListParams): Promise<AfterSaleSignalPage> {
  return api<AfterSaleSignalPage>(`${base}/after-sale-signals${query(params)}`);
}

export function fetchFulfillmentExceptions(
  params: ExceptionListParams,
): Promise<FulfillmentExceptionPage> {
  return api<FulfillmentExceptionPage>(`${base}/exceptions${query(params)}`);
}

export function fetchAlertRules(): Promise<AlertRuleRow[]> {
  return api<AlertRuleRow[]>(`${base}/alert-rules`);
}

export function fetchAlerts(params: AlertListParams): Promise<AlertPage> {
  return api<AlertPage>(`${base}/alerts${query(params)}`);
}

export function fetchQualityEvents(params: EventListParams): Promise<QualityEventPage> {
  return api<QualityEventPage>(`${base}/events${query(params)}`);
}

// ------------------------------------------------------------ 写

export interface AfterSaleSignalBody {
  orderId: string;
  occurredAt: string;
  signalType: string;
  signalState?: string;
  platformOrderNo?: string;
  sourceReference?: string;
  note?: string;
}

/** 登记一笔纠纷 / 退货。11 售后未交付 —— 这是当前唯一诚实的来源形状。 */
export function registerAfterSaleSignal(body: AfterSaleSignalBody): Promise<AfterSaleSignalRow> {
  return api<AfterSaleSignalRow>(`${base}/after-sale-signals`, 'POST', body);
}

export interface FulfillmentExceptionBody {
  orderId: string;
  orderLineId?: string;
  lineNo?: number;
  skuCode?: string;
  exceptionType: string;
  affectedQty?: number;
  reportedAt: string;
  sourceReference?: string;
  note?: string;
}

/** 登记一次错发 / 漏发。错发没有 SKU 级上游，只能由人举证登记。 */
export function registerFulfillmentException(
  body: FulfillmentExceptionBody,
): Promise<FulfillmentExceptionRow> {
  return api<FulfillmentExceptionRow>(`${base}/exceptions`, 'POST', body);
}

export interface AlertRuleBody {
  thresholdValue: number;
  comparison: string;
  severity?: string;
  enabled?: boolean;
  note?: string;
}

/** 配置某指标的阈值（幂等 upsert：一个指标一条生效规则）。 */
export function upsertAlertRule(metric: string, body: AlertRuleBody): Promise<AlertRuleRow> {
  return api<AlertRuleRow>(`${base}/alert-rules/${metric}`, 'PUT', body);
}

/** 评估预警。分母为 0 的指标**不会**产生预警 —— 没有样本不是指标变差。 */
export function evaluateAlerts(body: { from?: string; to?: string; perDimension?: boolean }): Promise<AlertEvaluateResult> {
  return api<AlertEvaluateResult>(`${base}/alerts/evaluate`, 'POST', body);
}

/** 确认一条预警。append-only ⇒ 确认落事件，不改预警行。 */
export function acknowledgeAlert(id: string): Promise<AlertPage> {
  return api<AlertPage>(`${base}/alerts/${id}/acknowledge`, 'POST');
}

// ------------------------------------------------------------ 展示口径

export type BucketTone = 'ok' | 'warn' | 'bad' | 'neutral';

/**
 * 比率展示。**分母为 0 时返回「无法判定」而不是 0%。**
 *
 * <p>这是本模型最重要的一条：把「没有样本」画成「0%」会让看板读者以为
 * 这一格是红色的坏消息，而它其实是空白的 —— 两者的处置完全不同。
 */
export function formatRate(rate: number | string | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '无法判定（无样本）';
  const value = typeof rate === 'string' ? Number(rate) : rate;
  // 服务端保留 6 位小数，展示层去掉多余的零，但**绝不**四舍五入到整数
  return `${value.toFixed(2)}%`;
}

/** 指标色调：方向显式 —— 高越好与低越好必须分开判。 */
export function metricTone(direction: string | undefined, rate: number | string | null | undefined): BucketTone {
  if (rate === null || rate === undefined || rate === '') return 'neutral';
  const value = typeof rate === 'string' ? Number(rate) : rate;
  if (direction === 'HIGHER_IS_BETTER') {
    if (value >= 95) return 'ok';
    if (value >= 85) return 'warn';
    return 'bad';
  }
  if (value <= 1) return 'ok';
  if (value <= 5) return 'warn';
  return 'bad';
}

/** 六个指标的中文名。前端不自己维护一份 —— 服务端 capabilities 才是权威。 */
export const METRIC_ORDER = [
  'SHIP_ON_TIME_RATE',
  'COMPLETE_SHIPMENT_RATE',
  'WRONG_OR_MISSING_RATE',
  'CANCELLATION_RATE',
  'DISPUTE_RATE',
  'RETURN_RATE',
] as const;

export const METRIC_FALLBACK_LABEL: Record<string, string> = {
  SHIP_ON_TIME_RATE: '发货及时率',
  COMPLETE_SHIPMENT_RATE: '完整发货率',
  WRONG_OR_MISSING_RATE: '错发漏发率',
  CANCELLATION_RATE: '取消率',
  DISPUTE_RATE: '纠纷率',
  RETURN_RATE: '退货率',
};

export function metricLabel(code: string, displayName?: string): string {
  return displayName && displayName.length > 0
    ? displayName
    : (METRIC_FALLBACK_LABEL[code] ?? code);
}

/** 维度值 → 人可读标签。「未认领」「无订单归属」是本模块自定的占位桶。 */
export function dimensionLabel(key: string, label?: string): string {
  if (label && label.length > 0) return label;
  if (key === 'UNASSIGNED') return '（未认领）';
  if (key === 'UNATTACHED') return '（无订单归属）';
  return key;
}

/** 缺口区的措辞：available=false 的维度必须显示归属模块，否则现场不知道找谁。 */
export function capabilityTone(available: boolean): BucketTone {
  return available ? 'ok' : 'neutral';
}

/** 预警色调。 */
export function severityTone(severity: string): BucketTone {
  if (severity === 'CRITICAL') return 'bad';
  if (severity === 'WARN') return 'warn';
  return 'neutral';
}

export function severityLabel(severity: string): string {
  if (severity === 'CRITICAL') return '严重';
  if (severity === 'WARN') return '警告';
  if (severity === 'INFO') return '提示';
  return severity;
}

export function comparisonLabel(comparison: string): string {
  if (comparison === 'GT') return '大于';
  if (comparison === 'GTE') return '大于等于';
  if (comparison === 'LT') return '小于';
  if (comparison === 'LTE') return '小于等于';
  return comparison;
}

/** 分子/分母的可读写法：给出「几 ÷ 几」而不是只给一个百分数。 */
export function basisText(numeratorCount: number, denominatorCount: number): string {
  if (denominatorCount === 0) return '本窗口没有可判定样本';
  return `${numeratorCount} ÷ ${denominatorCount}`;
}

export { ApiError };
