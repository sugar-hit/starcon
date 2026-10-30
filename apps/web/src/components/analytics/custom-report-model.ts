/**
 * 13.6.1.1 自定义报表 前端模型（卷 13「利润与经营分析」L3 13.6.1 下的第一格）。
 *
 * <p>蓝图原文：「业务人员自助构建报表：选择数据源（受控的语义层指标，不是直连库表）、
 * 维度、度量、筛选、图表类型、保存与共享、订阅推送。」输出「报表结果 + 分享链接 + 定时订阅。」
 * 关系：上游 = 语义层指标定义；下游 = 13.6.2.1 导出。
 * <strong>只允许基于语义层指标自助，不开放任意 SQL</strong>。
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「数据是 0」</b>。服务端 {@code availability='NOT_COMPARABLE'} 的度量渲染成
 *       「—（分母为 0 或缺数据）」，<strong>不是</strong> 0%。分母为 0 的比率显示成 0% 会被读成
 *       「效率为零」，而真相是「压根算不出来」。</li>
 *   <li><b>多币种绝不相加</b>。涉币指标按币种分行显示；与货币无关的量（笔数 / 件数）才给一个
 *       跨币种合计，它的币种是<strong>空串</strong>（空串 = 与币种无关，不是「币种未知」）。</li>
 *   <li><b>前端不做业务算术</b>。金额、比率、派生值全部来自服务端（{@code BigDecimal}），
 *       这里只做<strong>展示格式化</strong>。</li>
 *   <li><b>「未交付」就写未交付</b>。导出与实际投递属 13.6.2.1，页面上按能力声明原样显示
 *       「未交付」，<strong>不提供假按钮</strong>。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  CustomReportCapabilities,
  CustomReportCurrencyValue,
  CustomReportDeliveryPage,
  CustomReportDeliveryView,
  CustomReportDimensionView,
  CustomReportMeasureCell,
  CustomReportMetricView,
  CustomReportPage,
  CustomReportRow,
  CustomReportRunResponse,
  CustomReportShareView,
  CustomReportSharedResponse,
  CustomReportSubscriptionView,
  CustomReportSummary,
  CustomReportView,
} from '../../../../../packages/api-contracts/src/index';

export type {
  CustomReportCapabilities,
  CustomReportCurrencyValue,
  CustomReportDeliveryPage,
  CustomReportDeliveryView,
  CustomReportDimensionView,
  CustomReportMeasureCell,
  CustomReportMetricView,
  CustomReportPage,
  CustomReportRow,
  CustomReportRunResponse,
  CustomReportShareView,
  CustomReportSharedResponse,
  CustomReportSubscriptionView,
  CustomReportSummary,
  CustomReportView,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(19,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 数量刻度（服务端 numeric(19,6)，展示时保留 3 位）。 */
const QUANTITY_DECIMALS = 3;

/** 百分比刻度（服务端已经算成百分数，例如 60.0000 = 60%）。 */
const PERCENT_DECIMALS = 2;

/** 计数刻度。 */
const COUNT_DECIMALS = 0;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/** 单位 → 小数位。 */
function decimalsOf(unit: string): number {
  if (unit === 'PERCENT') return PERCENT_DECIMALS;
  if (unit === 'QUANTITY') return QUANTITY_DECIMALS;
  if (unit === 'COUNT') return COUNT_DECIMALS;
  return MONEY_DECIMALS;
}

/**
 * 一个度量取值的展示文本。
 *
 * <p>🔴 {@code value === null} ⇒ 「—」并<strong>带上原因</strong>；绝不用 0 顶替。
 */
export function measureValueText(value: CustomReportCurrencyValue, unit: string): string {
  if (value.value === null) return '—';
  const text = formatNumber(value.value, decimalsOf(unit));
  if (value.currency === '') return text;
  if (unit === 'PERCENT') return `${text}%`;
  return `${text} ${value.currency}`;
}

/**
 * 一个度量在某一行上的<strong>全部</strong>取值文本（多币种分行，不相加）。
 */
export function measureValueLines(cell: CustomReportMeasureCell): string[] {
  return cell.values.map((value) => measureValueText(value, cell.unit));
}

/** 与币种无关的量（笔数 / 件数）的跨币种合计 —— 币种是空串。 */
export function isCrossCurrencyTotal(value: CustomReportCurrencyValue): boolean {
  return value.currency === '';
}

/**
 * 度量的可得性文案（三种状态分开说）。
 *
 * <p>🔴 「不可比」与「没有数据」必须分开：前者是「算出来了但不可比」（分母为 0），
 * 后者是「压根没有这类事实」。
 */
export function measureAvailabilityText(cell: CustomReportMeasureCell): string {
  if (cell.availability === 'AVAILABLE') return '';
  if (cell.availability === 'NOT_COMPARABLE') {
    return cell.availabilityNote || '算不出来（不是 0）';
  }
  return cell.availabilityNote || '该口径下没有事实数据（不是 0）';
}

/** 度量在结果表里的列头（指标名 + 单位）。 */
export function measureColumnLabel(measure: CustomReportMetricView): string {
  if (measure.unit === 'PERCENT') return `${measure.displayName}（%）`;
  if (measure.unit === 'CURRENCY') return `${measure.displayName}（金额）`;
  if (measure.unit === 'QUANTITY') return `${measure.displayName}（数量）`;
  return measure.displayName;
}

// ================================================================ 维度

/** 维度取值的展示文本（空串 = 这一行不适用，例如公司级行的法人维度）。 */
export function dimensionValueText(value: string, kind: string): string {
  if (value === '') return '—（不适用）';
  if (kind === 'UUID') return value;
  return value;
}

/** 维度列的取值列表（按列顺序）。 */
export function dimensionColumns(response: CustomReportRunResponse): string[] {
  return response.dimensions.map((dimension) => dimension.label);
}

/** 一行的维度取值文本（按列顺序）。 */
export function rowDimensionTexts(row: CustomReportRow): string[] {
  return row.dimensions.map((dimension) =>
    dimensionValueText(
      dimension.value,
      dimension.dimensionCode === 'LEGAL_ENTITY' ? 'UUID' : 'TEXT',
    ),
  );
}

/** 维度白名单的说明（页面上照着显示，别自己编一句）。 */
export function dimensionNote(dimension: CustomReportDimensionView): string {
  return dimension.note;
}

// ================================================================ 口径

/** 聚合方式的中文标签。 */
export function aggregationLabel(aggregation: string): string {
  switch (aggregation) {
    case 'SUM':
      return '窗口内求和';
    case 'LAST':
      return '取最近一天的快照';
    case 'DERIVED_DIFF':
      return '派生差（分子 − 分母）';
    case 'DERIVED_RATIO':
      return '派生比（分子 ÷ 分母）';
    default:
      return aggregation;
  }
}

/**
 * 派生型指标的口径说明（分子 ÷ 分母）。
 *
 * <p>🔴 **口径写在页面上**，不是写在代码注释里 —— 跨部门沟通时「毛利率是哪个除哪个」
 * 必须是同一份定义。
 */
export function definitionFormula(definition: CustomReportMetricView): string {
  if (definition.aggregation === 'DERIVED_DIFF') {
    return `${definition.numeratorMetricCode} − ${definition.denominatorMetricCode}`;
  }
  if (definition.aggregation === 'DERIVED_RATIO') {
    return `${definition.numeratorMetricCode} ÷ ${definition.denominatorMetricCode}`;
  }
  if (definition.aggregation === 'LAST') return '窗口内最近一天的快照';
  return '窗口内求和';
}

// ================================================================ 能力声明

/** 导出是否可用（**未交付就写未交付**，不提供假按钮）。 */
export function exportAvailable(capabilities: CustomReportCapabilities | null): boolean {
  return capabilities?.exportAvailable ?? false;
}

/** 导出的未交付说明。 */
export function exportUnavailableReason(capabilities: CustomReportCapabilities | null): string {
  if (!capabilities) return '';
  if (capabilities.exportAvailable) return '';
  return `${capabilities.exportOwnerModule} · ${capabilities.exportUnavailableReason}`;
}

/** 邮件 / IM 的实际投递是否可用（**未交付就写未交付**）。 */
export function deliveryAvailable(capabilities: CustomReportCapabilities | null): boolean {
  return capabilities?.deliveryAvailable ?? false;
}

/** 投递的未交付说明。 */
export function deliveryUnavailableReason(capabilities: CustomReportCapabilities | null): string {
  if (!capabilities) return '';
  if (capabilities.deliveryAvailable) return '';
  return `${capabilities.deliveryOwnerModule} · ${capabilities.deliveryUnavailableReason}`;
}

// ================================================================ 报表清单

/** 可见范围的中文标签。 */
export function visibilityLabel(visibility: string): string {
  return visibility === 'TEAM' ? '团队可见' : '仅自己可见';
}

/** 图表类型的中文标签。 */
export function chartTypeLabel(chartType: string): string {
  switch (chartType) {
    case 'BAR':
      return '柱状图';
    case 'LINE':
      return '折线图';
    case 'PIE':
      return '饼图';
    default:
      return '表格';
  }
}

/** 报表清单的一行摘要（页面上照着显示）。 */
export function summaryText(summary: CustomReportSummary): string {
  return `${summary.measureCount} 个度量 · ${summary.dimensionCount} 个维度 · ${chartTypeLabel(summary.chartType)}`;
}

// ================================================================ 订阅与投递

/**
 * 投递状态的中文标签。
 *
 * <p>🔴 **「待投递」不是「已发送」**：真正的邮件 / IM 投递属 13.6.2.1（未交付）。
 */
export function deliveryStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待投递';
    case 'DELIVERED':
      return '已投递';
    case 'FAILED':
      return '投递失败';
    case 'CANCELLED':
      return '已取消';
    default:
      return status;
  }
}

/** 投递记录的一行摘要。 */
export function deliverySummary(delivery: CustomReportDeliveryView): string {
  const target = `${delivery.reportCode} · ${deliveryStatusLabel(delivery.status)}`;
  return delivery.lastError ? `${target} · ${delivery.lastError}` : target;
}

/** 订阅的下一次执行时刻文案（没算出来时如实说）。 */
export function nextRunText(subscription: CustomReportSubscriptionView): string {
  if (!subscription.enabled) return '已停用';
  if (!subscription.nextRunAt) return '下一次执行时刻未算出';
  return `下一次执行：${subscription.nextRunAt}`;
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「<strong>数据延迟要显示</strong>」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: CustomReportRunResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何数据';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻<strong>具体指什么</strong>）。 */
export function dataUpdatedNote(response: CustomReportRunResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 页面状态

/** 页面的三种状态分开说。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态判定。
 *
 * <p>🔴 **「读不到」与「没有数据」是两件事**：读不到（服务不可达 / 无权限）⇒ 错误态；
 * 读到了但这个窗口一格数据都没有 ⇒ 空态。两者都渲染成同一种空白，运维会以为「这个租户没生意」。
 */
export function pageState(response: CustomReportRunResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.status === 'NO_DATA') return 'empty';
  return 'ready';
}

/** 空态文案（区分「这个窗口没有事实」与「还没配过报表」）。 */
export function emptyText(response: CustomReportRunResponse | null): string {
  if (!response) return '';
  if (response.emptyReason === 'NO_FACTS') {
    return response.emptyNote || '这个时间窗口里没有任何事实数据（不是 0）。';
  }
  return '这个时间窗口里没有任何维度组合有数据。';
}

/** 报表清单的空态文案（区分「筛选无结果」与「还没建过报表」）。 */
export function listEmptyText(page: CustomReportPage | null): string {
  if (!page) return '';
  if (page.keyword || page.visibilityFilter) {
    return '没有报表匹配当前筛选条件。';
  }
  return '还没有建过报表。先建一份：选度量 → 选维度 → 加筛选 → 保存。';
}
