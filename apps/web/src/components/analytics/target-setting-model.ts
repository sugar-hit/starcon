/**
 * 13.1.2.1 目标设定 前端模型（卷 13「利润与经营分析」L3 13.1.2 目标管理下的第一格）。
 *
 * <p>蓝图原文：「设定经营目标：按部门/人员/产品/店铺设定销售额、毛利额、毛利率、广告 ROI、
 * 库存周转等指标与周期，支持分解到月/周。」
 * 输入「目标定义（指标、目标值、周期、归属对象）；历史基线参考。」
 * 输出「目标记录 + 分解计划。」
 * 关系：上游 = 17 组织（部门人员）；下游 = 13.1.2.2 达成追踪。
 *
 * <p><b>🔴 这一页最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。历史基线取不到时服务端给 {@code null}，
 *       这里渲染成「—」+ 原因，<b>绝不用 0 顶替</b> —— 假 0 会被读成「基线是 0」。</li>
 *   <li><b>🔴 口径唯一：目标只「引用」已登记的口径码</b>。销售额在店铺维度是
 *       <b>三个并列口径</b>（平台侧 / 还原口径 / 口径差异额），页面把三个都列出来，
 *       <b>不许只挑一个改名叫「销售额」</b>。</li>
 *   <li><b>🔴 没登记口径码的指标如实显示「未登记」</b>（广告 ROI 的组织维度、库存周转），
 *       <b>不许拿别的指标冒充</b>。</li>
 *   <li><b>多币种绝不相加</b>：合计按币种分列，页面不提供「折算合计」入口。</li>
 *   <li><b>前端不做业务算术</b>：分解项金额、合计、达成相关的一切都由服务端
 *       （{@code BigDecimal} 定标）算好，这里只做<b>展示格式化</b> ——
 *       前端自己加一遍就会引入二进制浮点误差，而且会让界面和服务端的数对不上。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  TargetBaselineView,
  TargetBucketView,
  TargetCaliberView,
  TargetCapabilities,
  TargetCurrencyTotal,
  TargetCurrentPeriod,
  TargetDecompositionPreview,
  TargetInput,
  TargetListResponse,
  TargetMetricView,
  TargetOwnerList,
  TargetOwnerView,
  TargetView,
} from '../../../../../packages/api-contracts/src/index';

export type {
  TargetBaselineView,
  TargetBucketView,
  TargetCaliberView,
  TargetCapabilities,
  TargetCurrencyTotal,
  TargetCurrentPeriod,
  TargetDecompositionPreview,
  TargetInput,
  TargetListResponse,
  TargetMetricView,
  TargetOwnerList,
  TargetOwnerView,
  TargetView,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度。 */
const PERCENT_DECIMALS = 2;

/** 倍数刻度。 */
const RATIO_DECIMALS = 2;

/** 笔数刻度。 */
const COUNT_DECIMALS = 0;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**。
 */
export function amountText(value: number | null, currency: string | null): string {
  if (value === null) return '—';
  const suffix = currency ? ` ${currency}` : '';
  return `${formatNumber(value, MONEY_DECIMALS)}${suffix}`;
}

/**
 * 百分比。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）。
 */
export function percentText(value: number | null): string {
  if (value === null) return '不可比';
  const sign = value > 0 ? '+' : '';
  return `${sign}${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/**
 * 倍数。
 *
 * <p>🔴 {@code null} ⇒ 「—」（**不是 0 倍**）。
 */
export function ratioText(value: number | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, RATIO_DECIMALS)}×`;
}

/** 笔数。🔴 {@code null} ⇒ 「—」，不是 0。 */
export function countText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, COUNT_DECIMALS);
}

/**
 * 按指标单位分派的取值文本。
 *
 * <p>⚠️ 单位来自**口径定义**（{@code TargetCaliberView.unit} / {@code TargetView.unit}），
 * 不来自取值本身 —— 猜单位就会把「百分比」当成「金额」渲染。
 */
export function targetValueText(unit: string, value: number | null, currency: string | null): string {
  switch (unit) {
    case 'CURRENCY':
      return amountText(value, currency);
    case 'PERCENT':
      return percentText(value);
    case 'RATIO':
      return ratioText(value);
    case 'COUNT':
    case 'QUANTITY':
      return countText(value);
    default:
      return value === null ? '—' : formatNumber(value, MONEY_DECIMALS);
  }
}

// ================================================================ 口径

/** 单位的展示名。 */
export function unitLabel(unit: string): string {
  switch (unit) {
    case 'CURRENCY':
      return '金额';
    case 'COUNT':
      return '笔数';
    case 'QUANTITY':
      return '数量';
    case 'PERCENT':
      return '百分比';
    case 'RATIO':
      return '倍数';
    default:
      return unit;
  }
}

/** 聚合方式的展示名。 */
export function aggregationLabel(aggregation: string): string {
  switch (aggregation) {
    case 'SUM':
      return '窗口内相加';
    case 'LAST':
      return '取最近一天快照';
    case 'DERIVED_DIFF':
      return '分子 − 分母';
    case 'DERIVED_RATIO':
      return '分子 ÷ 分母';
    default:
      return aggregation;
  }
}

/**
 * 口径的「怎么算出来的」说明。
 *
 * <p>🔴 **派生型必须说清依赖的是谁** —— 否则页面上的「毛利率」是个没有出处的数。
 */
export function caliberFormulaText(caliber: TargetCaliberView): string {
  if (caliber.aggregation === 'DERIVED_RATIO') return '派生比（分子 ÷ 分母，口径表里已登记）';
  if (caliber.aggregation === 'DERIVED_DIFF') return '派生差（分子 − 分母，口径表里已登记）';
  return aggregationLabel(caliber.aggregation);
}

/** 口径归属模块的展示名（空串 = 上游已在本服务内闭环）。 */
export function ownerModuleLabel(ownerModule: string): string {
  return ownerModule ? ownerModule : '本服务内闭环';
}

// ================================================================ 语义色（Badge tone）

/**
 * Badge 的 tone 取值（与 `@starcon/ui` 的 `Badge` 一致）。
 *
 * <p>本文件是纯函数层，不 import UI 包 —— 但 tone 的**取值集合**必须与 UI 一致，
 * 所以在这里显式列出来（多一个值就会在编译期报出来，而不是运行时悄悄变灰）。
 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/**
 * 目标状态的语义色。
 *
 * <p>⚠️ **tone 跟语义挂钩，不跟颜色挂钩**：
 *
 * <ul>
 *   <li>ACTIVE 生效中 ⇒ primary（这是当前要盯的目标）；</li>
 *   <li>ARCHIVED 已归档 ⇒ neutral（归档是历史，不是错误）。</li>
 * </ul>
 */
export function statusTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE':
      return 'primary';
    case 'ARCHIVED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 目标状态的中文标签。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '生效中';
    case 'ARCHIVED':
      return '已归档';
    default:
      return status;
  }
}

/**
 * 基线状态的语义色。
 *
 * <p>🔴 {@code NO_DATA} 与 {@code NOT_AVAILABLE} 都是 neutral：
 * 「这一格没有事实」和「这一维度没有事实来源」都是**如实说明**，不是错误。
 */
export function baselineTone(status: string): Tone {
  switch (status) {
    case 'AVAILABLE':
      return 'success';
    case 'NO_DATA':
      return 'neutral';
    case 'NOT_AVAILABLE':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 基线状态的中文标签。 */
export function baselineLabel(status: string): string {
  switch (status) {
    case 'AVAILABLE':
      return '有基线';
    case 'NO_DATA':
      return '没有基线';
    case 'NOT_AVAILABLE':
      return '该维度没有基线来源';
    default:
      return status;
  }
}

/**
 * 基线维度的标签。
 *
 * <p>🔴 **OWNER 与 TENANT 必须分开说**：OWNER 是「该归属对象自己的历史」，
 * TENANT 是「租户级参考值」—— 把两者混为一谈就是拿公司维度的数冒充部门维度的历史。
 */
export function baselineScopeLabel(scope: string): string {
  switch (scope) {
    case 'OWNER':
      return '归属对象维度';
    case 'TENANT':
      return '租户级参考值';
    default:
      return scope;
  }
}

/** 分解是否对得上的语义色。 */
export function reconciledTone(reconciled: boolean): Tone {
  return reconciled ? 'success' : 'danger';
}

/** 分解是否对得上的中文标签。 */
export function reconciledLabel(reconciled: boolean): string {
  return reconciled ? '合计与目标值一致' : '合计与目标值不一致';
}

// ================================================================ 归属对象

/** 归属对象类型的中文标签。 */
export function scopeLabel(scopeType: string): string {
  switch (scopeType) {
    case 'DEPARTMENT':
      return '部门';
    case 'MEMBER':
      return '人员';
    case 'SHOP':
      return '店铺';
    case 'PRODUCT':
      return '产品';
    default:
      return scopeType;
  }
}

/** 归属对象类型是否属于「组织维度」（部门 / 人员 / 产品）。 */
export function isOrgScope(scopeType: string): boolean {
  return scopeType === 'DEPARTMENT' || scopeType === 'MEMBER' || scopeType === 'PRODUCT';
}

/**
 * 归属对象的展示名（**快照**）。
 *
 * <p>⚠️ 快照是「当时的写法」—— 组织改名后历史目标仍认得出是谁。
 */
export function ownerLabel(target: TargetView): string {
  if (!target.ownerName) return target.ownerId;
  return target.ownerCode ? `${target.ownerName}（${target.ownerCode}）` : target.ownerName;
}

// ================================================================ 周期与粒度

/** 分解粒度的中文标签。 */
export function granularityLabel(granularity: string): string {
  switch (granularity) {
    case 'MONTHLY':
      return '按月分解';
    case 'WEEKLY':
      return '按周分解';
    default:
      return granularity;
  }
}

/** 分解方式的中文标签。 */
export function methodLabel(method: string): string {
  switch (method) {
    case 'EVEN':
      return '均分';
    case 'WEIGHTED':
      return '按权重';
    default:
      return method;
  }
}

/** 周期的展示文本。 */
export function periodText(target: TargetView): string {
  return `${target.periodStart} ~ ${target.periodEnd}`;
}

/** 当前周期的展示文本。 */
export function currentPeriodText(period: TargetCurrentPeriod | null): string {
  if (!period) return '—';
  return `${period.from} ~ ${period.to}`;
}

/**
 * 当前周期的时区说明。
 *
 * <p>🔴 **时区回退必须说清楚**：本租户还没有投影事实时周期暂按 UTC 算，
 * 一旦投影进来就会改用该租户自己的时区 —— 这条要常驻在页面上。
 */
export function timezoneText(period: TargetCurrentPeriod | null): string {
  if (!period) return '—';
  if (period.timezoneSource === 'FALLBACK_UTC') {
    return `周期边界暂按 UTC 计算（本租户还没有投影事实，取不到自己的分桶时区）`;
  }
  return `周期边界按本租户时区 ${period.timezone} 计算（不是写死 UTC）`;
}

// ================================================================ 目标指标

/**
 * 🔴 **该维度上能不能设这个目标** —— 没登记口径码就是不能设。
 *
 * <p>返回 false 时页面**不给可选项**，并显示归属模块 —— 而不是给一个「近似指标」。
 */
export function metricAvailable(metric: TargetMetricView): boolean {
  return metric.available && metric.calipers.length > 0;
}

/**
 * 该指标在当前维度下可引用的口径码。
 *
 * <p>🔴 **销售额在店铺维度是三个并列口径** —— 页面必须把它们都列出来，
 * 让用户自己选口径并看见差异（取其一改名叫「销售额」就是抹掉口径差异）。
 */
export function metricCalipers(metric: TargetMetricView): TargetCaliberView[] {
  return metric.calipers;
}

/**
 * 当前维度下**全部可引用的口径码**（跨蓝图目标指标摊平、去重）。
 *
 * <p>列表筛选器按**口径码**筛（后端 {@code reporting_target.metric_code}）——
 * 所以筛选器要给口径码，不是蓝图的目标指标键。
 */
export function availableCalipers(metrics: TargetMetricView[]): TargetCaliberView[] {
  const out: TargetCaliberView[] = [];
  const seen = new Set<string>();
  for (const metric of metrics) {
    if (!metricAvailable(metric)) continue;
    for (const caliber of metric.calipers) {
      if (seen.has(caliber.metricCode)) continue;
      seen.add(caliber.metricCode);
      out.push(caliber);
    }
  }
  return out;
}

/** 口径的展示名（口径表里的名字，不是蓝图的名字）。 */
export function caliberLabel(caliber: TargetCaliberView): string {
  return caliber.displayName;
}

/**
 * 口径的可选文本（展示名 + 口径码）。
 *
 * <p>⚠️ 口径码也显示出来：口径唯一，页面上要让人能对着口径表核对。
 */
export function caliberOptionLabel(caliber: TargetCaliberView): string {
  return `${caliber.displayName}（${caliber.metricCode}）`;
}

// ================================================================ 列表

/** 页面状态（加载 / 错误 / 空 / 就绪）。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态判定。
 *
 * <p>⚠️ **空态与错误态必须分开**：读不到（上游挂了 / 没权限）是 error，
 * 读到了但一条都没有才是 empty —— 两者混在一起会让人以为「这个模块没做」。
 */
export function pageState(list: TargetListResponse | null, error: string, loading: boolean): PageState {
  if (loading) return 'loading';
  if (error) return 'error';
  if (!list) return 'loading';
  if (list.total === 0) return 'empty';
  return 'ready';
}

/** 空态说明。 */
export function emptyText(list: TargetListResponse | null): string {
  if (!list) return '读取中…';
  return '这个租户还没有设定任何目标。目标要挂在组织对象上（部门 / 人员 / 店铺 / 产品），否则无法与绩效核算对接。';
}

/**
 * 按币种的合计文本（**绝不跨币种相加**）。
 *
 * <p>🔴 **一个币种都没有时显示「—」，不是 0** —— 「还没有目标」与「目标合计为 0」是两件事。
 */
export function currencyTotalsText(totals: TargetCurrencyTotal[]): string {
  if (totals.length === 0) return '—';
  return totals
    .map((total) => `${total.currency} ${formatNumber(total.total, MONEY_DECIMALS)}（${total.count} 条）`)
    .join('　');
}

/** 合计口径说明（常驻）。 */
export function currencyTotalsNote(totals: TargetCurrencyTotal[]): string {
  if (totals.length <= 1) {
    return '合计按币种分列：不同币种的目标绝不相加（一个 USD 目标和一个 EUR 目标的大小本来就不可比）。';
  }
  return `⚠️ 这里有 ${totals.length} 个币种：合计按币种分列，**没有跨币种相加**（没有汇率就不折算）。`;
}

// ================================================================ 分解计划

/** 分解项的展示文本。 */
export function bucketText(bucket: TargetBucketView): string {
  return `${bucket.bucketStart} ~ ${bucket.bucketEnd}`;
}

/** 分解项金额文本。 */
export function bucketValueText(bucket: TargetBucketView): string {
  return amountText(bucket.targetValue, bucket.currency);
}

/**
 * 分解合计的一致性说明。
 *
 * <p>🔴 合计与目标值对不上时**必须显式报警**（数据层还有一道延迟约束触发器兜底，
 * 但页面上的提示让人第一眼就看见）。
 */
export function reconciliationText(
  targetValue: number,
  bucketSum: number,
  reconciled: boolean,
): string {
  if (reconciled) {
    return `分解项合计 ${formatNumber(bucketSum, MONEY_DECIMALS)} 与目标值一致。`;
  }
  return `⚠️ 分解项合计 ${formatNumber(bucketSum, MONEY_DECIMALS)} 与目标值 ${formatNumber(
    targetValue,
    MONEY_DECIMALS,
  )} 不一致 —— 这种情况不应该出现（数据层有延迟约束触发器兜底），请报给服务端。`;
}

// ================================================================ 能力声明

/** 能力声明里的「拿不到」条目。 */
export function notDeliveredLines(capabilities: TargetCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 能力声明里的口径纪律说明。 */
export function capabilityNotes(capabilities: TargetCapabilities | null): string[] {
  return capabilities?.notes ?? [];
}

/** 能力声明里「真的读过」的上游模块。 */
export function upstreamModules(capabilities: TargetCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

// ================================================================ 分解预览

/** 分解预览的合计说明。 */
export function previewReconciliationText(preview: TargetDecompositionPreview): string {
  if (preview.reconciled) {
    return `分解项合计 ${formatNumber(preview.bucketSum, MONEY_DECIMALS)} 与目标值一致。`;
  }
  return `⚠️ 分解项合计 ${formatNumber(preview.bucketSum, MONEY_DECIMALS)} 与目标值不一致。`;
}

/** 分解预览的余数归属说明。 */
export function previewRemainderText(preview: TargetDecompositionPreview): string {
  return preview.remainderNote;
}

// ================================================================ 写入入参

/**
 * 构造写入入参（**不含 tenantId / userId / membershipId**）。
 *
 * <p>⚠️ **租户只从已认证成员关系解析**（AGENTS.md 多租户纪律）——
 * 请求体里出现租户号就是第二个真相。
 */
export function buildTargetInput(input: {
  metricCode: string;
  scopeType: string;
  ownerId: string;
  currency: string;
  targetValue: string;
  periodStart: string;
  periodEnd: string;
  granularity: string;
  decompositionMethod: string;
  weights?: number[];
  status?: string;
  note?: string;
}): TargetInput {
  return {
    metricCode: input.metricCode,
    scopeType: input.scopeType,
    ownerId: input.ownerId,
    currency: input.currency,
    targetValue: Number(input.targetValue),
    periodStart: input.periodStart,
    periodEnd: input.periodEnd,
    granularity: input.granularity,
    decompositionMethod: input.decompositionMethod,
    weights: input.weights && input.weights.length > 0 ? input.weights : null,
    status: input.status ?? null,
    note: input.note ?? null,
  };
}

/**
 * 写入前的本地校验（**只拦明显不成立的**，不代替服务端校验）。
 *
 * <p>⚠️ 服务端是最终裁判（周期对齐、权重个数、唯一性都在服务端查）——
 * 这里只是让人在点提交之前就知道哪里填错了。
 */
export function validateTargetInput(input: TargetInput): string[] {
  const problems: string[] = [];
  if (!input.metricCode) problems.push('请选择一个目标指标口径码。');
  if (!input.ownerId) problems.push('请选择归属对象。');
  if (!/^[A-Z]{3}$/.test(input.currency)) problems.push('币种必须是 3 位大写字母。');
  if (!Number.isFinite(input.targetValue)) problems.push('目标值必须是数字。');
  if (!input.periodStart || !input.periodEnd) problems.push('请给出目标周期的起止日期。');
  if (input.periodStart && input.periodEnd && input.periodStart > input.periodEnd) {
    problems.push('周期起始日期不能晚于结束日期。');
  }
  if (input.granularity === 'MONTHLY') {
    if (input.periodStart && !input.periodStart.endsWith('-01')) {
      problems.push('按月分解时周期起点必须是某月 1 日。');
    }
    if (input.periodEnd) {
      const end = new Date(`${input.periodEnd}T00:00:00Z`);
      const next = new Date(end);
      next.setUTCDate(next.getUTCDate() + 1);
      if (next.getUTCDate() !== 1) {
        problems.push('按月分解时周期终点必须是某月的最后一天。');
      }
    }
  }
  if (input.granularity === 'WEEKLY') {
    const start = new Date(`${input.periodStart}T00:00:00Z`);
    if (start.getUTCDay() !== 1) problems.push('按周分解时周期起点必须是周一。');
    const end = new Date(`${input.periodEnd}T00:00:00Z`);
    if (end.getUTCDay() !== 0) problems.push('按周分解时周期终点必须是周日。');
  }
  if (input.decompositionMethod === 'WEIGHTED' && (!input.weights || input.weights.length === 0)) {
    problems.push('按权重分解必须给出每个分解项的权重。');
  }
  return problems;
}
