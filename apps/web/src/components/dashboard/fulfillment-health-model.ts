import { api } from '../inventory/api';

/**
 * 1.1.2.3 履约健康 前端模型。
 *
 * 蓝图（`docs/product/capability-blueprint/01-dashboard-and-portal.md` 第 110–115 行）：
 * 「展示履约链路的健康水位：待发货积压、平均出库时长、超时发货率、物流异常件占比、
 * 平台绩效指标（ODR、迟发率、有效追踪率）。……输出：健康度卡片 + 红灯项明细跳转。」
 *
 * 🔴 卷 01 的定位（蓝图卷首）是「**前端聚合层** + 各服务的只读投影」——
 * 所以本文件是**聚合层**：履约/物流/平台绩效三块来自 trade 服务的只读投影
 * （`trade/fulfillment/health/*`），出库时长来自 08 仓储作业自己的只读报表
 * （`warehouse/outbound-timeliness/report`）。前端**只合并，不重算**：
 * 任何比率、任何分母都在服务端算好，这里原样显示。
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「量不到」显示成「未接通」，不显示 0**。平台 ODR 在仓内没有上游（没有
 *    `platform_performance_metric` 表），卡片上是灰格 + 「未接通」，不是 0%。
 * 2. **前端不重算比率**。卡片上的 `value` / `numerator` / `denominator` 全部原样显示；
 *    分母为 0 时后端给的是 `null`，这里渲染成「未接通」。
 * 3. **计数型卡片没有分子分母**。「待发货积压 3 件」不是「3/5 = 60%」——
 *    硬塞一个分母会让运营读出一个不存在的比例。
 * 4. **红灯项与卡片同源**。每张卡的 `redFlagKind` 指向明细列表的一个筛选，
 *    两边的条数必须对得上；对不上就是口径漂了。
 * 5. **出库时长的阈值来源要显示**。它是仓库可配的生效阈值
 *    （仓库覆盖 > 租户默认 > 内置兜底），不是写死的数字。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（trade 服务，**独立一条 services[] 元素**）。 */
export const BASE = 'trade/fulfillment/health';

/** 08 仓储作业的出库时效只读报表（warehouse 服务，**既有**白名单条目，无需新增）。 */
export const OUTBOUND_TIMELINESS_BASE = 'warehouse/outbound-timeliness';

/**
 * 出库时效里的「订单到出库」环节码。
 *
 * <p>四个环节（ORDER_TO_OUTBOUND / PICKING / PACKING / HANDOVER）里，
 * 「平均出库时长」取的是**订单到出库**那一段 —— 它是订单维度的那一段，
 * 三个内部环节是仓库内部作业用时，分母与结论对象都不同，不能混。
 */
export const OUTBOUND_STAGE = 'ORDER_TO_OUTBOUND';

// ================================================================ 类型

/** 能力维度（口径由服务端一处定义）。 */
export interface FulfillmentHealthCapabilityDimension {
  key: string;
  label: string;
  /** 该维度当前是否**量得到**（false = 量不到）。 */
  available: boolean;
  /** 量不到时说明为什么。 */
  reason: string;
  /** 量不到时归谁。 */
  ownerModule: string;
  /** 量不到对本页的影响。 */
  impact: string;
  /** 计算公式（人话版）。 */
  formula: string;
  /** 分母定义。 */
  denominator: string;
  /** 数据来源表。 */
  sourceTables: string;
  /** 本模块的处理方式。 */
  handling: string;
}

export interface FulfillmentHealthCapabilities {
  dimensions: FulfillmentHealthCapabilityDimension[];
  redFlagKinds: string[];
  levelRule: string;
  windowBasis: string;
  upstreamNote: string;
  ownershipNote: string;
  boundaryNote: string;
}

/**
 * 健康度卡片。
 *
 * <p>`metricKind` 的取值：服务端发出的卡片是 `COUNT`（计数）/ `RATE`（比率）/
 * `UNAVAILABLE`（未接通）；前端合并进来的出库时长卡是 `DURATION`（时长均值）。
 */
export interface FulfillmentHealthCard {
  key: string;
  label: string;
  available: boolean;
  unavailableReason: string;
  ownerModule: string;
  metricKind: string;
  unit: string;
  /** 指标值；`null` = 量不到，**不是 0**。 */
  value: number | null;
  /** 展示串（服务端算好；量不到时是「未接通」）。 */
  valueText: string;
  /** 分子（只有比率型有）。 */
  numerator: number | null;
  /** 分母（只有比率型有）。 */
  denominator: number | null;
  /** 这张卡背后具体有多少条红灯项。 */
  alertCount: number | null;
  /** 红灯项类型键；红灯项列表按它过滤的 total 必须等于 alertCount。 */
  redFlagKind: string | null;
  /** 口径说明。 */
  basis: string;
  /** 灯色：RED / AMBER / GREEN / UNKNOWN。 */
  level: string;
  /** 灯色判定依据。 */
  levelRule: string;
  /** 点进去能看到的明细路径。 */
  detailPath: string;
  sourceTables: string[];
}

export interface FulfillmentHealthBucket {
  key: string;
  label: string;
  count: number;
}

export interface FulfillmentHealthBreakdown {
  key: string;
  label: string;
  buckets: FulfillmentHealthBucket[];
}

export interface FulfillmentHealthCoverage {
  pendingProjectionTotal: number;
  pendingHandedOverUnits: number;
  pendingCancelledUnits: number;
  shipmentRecordsEvaluated: number;
  shipmentRecordsUnsetSla: number;
  shipmentRecordsUntrackable: number;
  dropshipOpenExceptions: number;
  dropshipTrackings: number;
}

export interface FulfillmentHealthSummary {
  cards: FulfillmentHealthCard[];
  breakdowns: FulfillmentHealthBreakdown[];
  coverage: FulfillmentHealthCoverage;
  boundaryNotes: string[];
}

export interface FulfillmentHealthRedFlag {
  kind: string;
  kindLabel: string;
  refId: string;
  refNo: string;
  title: string;
  detail: string;
  severity: string;
  status: string;
  occurredAt: string | null;
  linkPath: string;
}

export interface FulfillmentHealthRedFlagPage {
  items: FulfillmentHealthRedFlag[];
  total: number;
  page: number;
  size: number;
  boundaryNotes: string[];
}

/** 08 出库时效的一个环节分布（本页只取 ORDER_TO_OUTBOUND 那一段）。 */
export interface OutboundStageDistribution {
  stageCode: string;
  stageLabel: string;
  unit: string;
  sampledCount: number;
  missingOrderCount: number;
  coverageRate: number | null;
  avgMinutes: number | null;
  p50Minutes: number | null;
  p90Minutes: number | null;
  maxMinutes: number | null;
  thresholdMinutes: number;
  thresholdSource: string;
  overdueCount: number;
  overdueRate: number | null;
  buckets: { bucket: string; orderCount: number; share: number | null }[];
}

/** 08 出库时效报表（只声明本页用到的字段，其余字段不用就不写）。 */
export interface OutboundTimelinessReport {
  from: string;
  to: string;
  timezone: string;
  shippedOrderCount: number;
  overdueOrderCount: number;
  overdueOrderRate: number | null;
  distribution: OutboundStageDistribution[];
  missingSampleCounts?: Record<string, number>;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<FulfillmentHealthCapabilities> {
  return api<FulfillmentHealthCapabilities>(`${BASE}/capabilities`);
}

export async function fetchSummary(): Promise<FulfillmentHealthSummary> {
  return api<FulfillmentHealthSummary>(`${BASE}/summary`);
}

export async function fetchRedFlags(
  page: number,
  size: number,
  kind?: string,
): Promise<FulfillmentHealthRedFlagPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (kind) query.set('kind', kind);
  return api<FulfillmentHealthRedFlagPage>(`${BASE}/red-flags?${query.toString()}`);
}

/**
 * 出库时效只读报表（08 仓储作业）。
 *
 * <p>仓库服务不可达时**返回 null 而不是抛错**：出库时长是六张卡里的一张，
 * 缺了它其余五张仍然要看得到 —— 让整页因为一个服务挂了就白屏是更糟的做法。
 */
export async function fetchOutboundTimeliness(): Promise<OutboundTimelinessReport | null> {
  try {
    return await api<OutboundTimelinessReport>(`${OUTBOUND_TIMELINESS_BASE}/report`);
  } catch {
    return null;
  }
}

// ================================================================ 纯函数

/** 灯色 → 徽章色调。 */
export function levelTone(level: string): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (level) {
    case 'RED':
      return 'danger';
    case 'AMBER':
      return 'warning';
    case 'GREEN':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 灯色 → 人话。 */
export function levelLabel(level: string): string {
  switch (level) {
    case 'RED':
      return '红灯';
    case 'AMBER':
      return '黄灯';
    case 'GREEN':
      return '绿灯';
    default:
      return '未接通';
  }
}

/** 严重度 → 徽章色调。 */
export function severityTone(severity: string): 'danger' | 'warning' | 'neutral' {
  if (severity === 'CRITICAL') return 'danger';
  if (severity === 'WARNING') return 'warning';
  return 'neutral';
}

/**
 * 出库时长卡：从 08 的只读报表里取「订单到出库」这一段。
 *
 * <p>报表不可达、或该环节一个样本都没有时返回 `null` —— 由调用方渲染成
 * 「未接通」的空位，而不是一个 0 分钟。
 */
export function outboundDurationCard(
  report: OutboundTimelinessReport | null,
): FulfillmentHealthCard | null {
  if (!report) return null;
  const stage = report.distribution?.find((d) => d.stageCode === OUTBOUND_STAGE);
  if (!stage) return null;

  const hasSample = stage.sampledCount > 0;
  const level = !hasSample ? 'UNKNOWN' : stage.overdueCount > 0 ? 'RED' : 'GREEN';
  const thresholdSource =
    stage.thresholdSource === 'BUILTIN'
      ? '内置兜底'
      : stage.thresholdSource === 'WAREHOUSE'
        ? '仓库覆盖'
        : stage.thresholdSource === 'TENANT'
          ? '租户默认'
          : stage.thresholdSource || '未标注';

  return {
    key: 'AVERAGE_OUTBOUND_DURATION',
    label: '平均出库时长',
    available: true,
    unavailableReason: '',
    ownerModule: '',
    metricKind: 'DURATION',
    unit: '分钟',
    value: hasSample ? stage.avgMinutes : null,
    valueText: hasSample ? `${stage.avgMinutes ?? 0} 分钟` : '未接通',
    numerator: null,
    denominator: null,
    alertCount: stage.overdueCount,
    redFlagKind: null,
    basis:
      `平均出库时长 = 已发货出库单的「订单到出库」时长均值（分钟）；` +
      `样本 ${stage.sampledCount} 单，终点未发生 ${stage.missingOrderCount} 单（不混进分母）；` +
      `生效阈值 ${stage.thresholdMinutes} 分钟（来源：${thresholdSource}），` +
      `超时 ${stage.overdueCount} 单。`,
    level,
    levelRule:
      '红灯 = 有出库单超过生效阈值；绿灯 = 全部在阈值内；' +
      '一个样本都没有时判灰（这段时间还没有可测量的出库数据，不等于「时效很好」）。',
    detailPath: '/warehouse/outbound-timeliness',
    sourceTables: ['outbound_order', 'pick_task', 'packing_order', 'shipment_handover'],
  };
}

/**
 * 合并出库时长卡。
 *
 * <p>顺序：出库时长排在待发货积压之后（履约链路的自然顺序：待发货 → 出库 → 发货 → 轨迹）。
 * 报表不可达时**不补一张假卡** —— 让页面上少一格并说明原因，好过补一张 0 分钟的卡。
 */
export function mergeCards(
  summary: FulfillmentHealthSummary,
  outbound: OutboundTimelinessReport | null,
): FulfillmentHealthCard[] {
  const extra = outboundDurationCard(outbound);
  if (!extra) return summary.cards;
  const withoutIt = summary.cards.filter((c) => c.key !== extra.key);
  const backlogIndex = withoutIt.findIndex((c) => c.key === 'PENDING_SHIPMENT_BACKLOG');
  if (backlogIndex < 0) return [...withoutIt, extra];
  return [...withoutIt.slice(0, backlogIndex + 1), extra, ...withoutIt.slice(backlogIndex + 1)];
}

/** 量不到的维度（页面上要如实列出来，而不是悄悄消失）。 */
export function unavailableDimensions(
  caps: FulfillmentHealthCapabilities | null,
): FulfillmentHealthCapabilityDimension[] {
  if (!caps) return [];
  return caps.dimensions.filter((d) => !d.available);
}

/**
 * 覆盖度提示。
 *
 * <p>「未登记时限的记录不进迟发率分母」「不可追踪的件没进有效追踪率的分子」这类事实
 * 必须显式说出来：不说的话，运营会把「迟发率 0%」读成「一件都没迟发」。
 */
export function coverageHints(coverage: FulfillmentHealthCoverage): string[] {
  const hints: string[] = [];
  if (coverage.shipmentRecordsUnsetSla > 0) {
    hints.push(
      `${coverage.shipmentRecordsUnsetSla} 条发货记录**未登记发货时限**，落 UNSET 桶：` +
        '既不算准时也不算迟发，不在迟发率的分母里 —— 迟发率可能因此偏低。',
    );
  }
  if (coverage.shipmentRecordsUntrackable > 0) {
    hints.push(
      `${coverage.shipmentRecordsUntrackable} 条发货记录有主运单号但**没有任何轨迹观测**，` +
        '承运商没给到轨迹（平台照样扣分），不计入有效追踪率。',
    );
  }
  if (coverage.pendingProjectionTotal === 0) {
    hints.push('待发货投影里一条记录都没有：要么真的没有待发货，要么同步还没跑过。');
  }
  if (coverage.dropshipOpenExceptions > 0) {
    hints.push(
      `代发路径还有 ${coverage.dropshipOpenExceptions} 条未结案异常` +
        `（被监测的代发跟踪共 ${coverage.dropshipTrackings} 条）。`,
    );
  }
  return hints;
}

/** 红灯项按类型分组（页面上按类型折叠）。 */
export function groupRedFlags(
  flags: FulfillmentHealthRedFlag[],
): { kind: string; kindLabel: string; items: FulfillmentHealthRedFlag[] }[] {
  const order: string[] = [];
  const map = new Map<string, { kindLabel: string; items: FulfillmentHealthRedFlag[] }>();
  for (const flag of flags) {
    if (!map.has(flag.kind)) {
      map.set(flag.kind, { kindLabel: flag.kindLabel, items: [] });
      order.push(flag.kind);
    }
    map.get(flag.kind)!.items.push(flag);
  }
  return order.map((kind) => ({
    kind,
    kindLabel: map.get(kind)!.kindLabel,
    items: map.get(kind)!.items,
  }));
}

/**
 * 空态文案。
 *
 * <p>三种空态**文案不同**（不是同一个空态换个图标）：
 * 没有红灯项 / 没有数据 / 服务不可达。
 */
export function emptyState(total: number, loaded: boolean): { title: string; description: string } {
  if (!loaded) {
    return {
      title: '健康度数据加载失败',
      description: '后端暂时不可达或身份校验未通过，请刷新重试。页面上已有的卡片不会因此被清空。',
    };
  }
  if (total === 0) {
    return {
      title: '当前没有红灯项',
      description:
        '已发货记录没有迟发、没有轨迹异常，待发货也没有超过发货时限。' +
        '「未登记时限」与「无轨迹观测」的件不在这张红灯清单里 —— 它们在上面的覆盖度提示里。',
    };
  }
  return {
    title: '没有匹配的项',
    description: '换一个红灯类型筛选看看，或翻到下一页。',
  };
}
