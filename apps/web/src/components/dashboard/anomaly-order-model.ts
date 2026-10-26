/**
 * 1.1.1.2 异常订单队列的前端展示口径与**跨服务聚合**。
 *
 * <p>卷 01 的定位：「统一入口。不产生新的业务事实，只聚合各系统的投影」。
 * 所以这个模块里最容易做错的，是把「聚合」当成「把别处的数据抄一份过来」——
 * 抄来的那份会与归属系统漂移，而页面上的数字一旦漂移就没法解释。
 *
 * <p>本文件因此只做三件事：
 *
 * <ol>
 *   <li><b>合并</b>：把 trade 的订单侧信号（后端已算好）与 channel 的黑名单标记、
 *       warehouse 的可售量**按各自的连接键**并到同一行上；</li>
 *   <li><b>口径</b>：计数、筛选、分页全部基于**合并后的同一份集合**，
 *       页面上每个数字都能点进去看到明细，且明细与计数同源；</li>
 *   <li><b>导航</b>：建议动作只给出**归属系统的入口**（前端路由），
 *       工作台自己不写业务数据。</li>
 * </ol>
 *
 * <p>🔴 **连接键必须与归属系统逐字同式**，否则会静默漏判：
 *
 * <ul>
 *   <li>黑名单连接键 {@code buyer_key} = 收件人三件套的 md5
 *       （分隔符 U+001F）—— trade 侧由
 *       {@code md5(concat_ws(E'\x1f', recipient_name, recipient_phone, recipient_email))} 现算，
 *       与 channel 16.5.1.1 的 {@code buyer_blacklist_entry.buyer_key} 同一公式；</li>
 *   <li>库存连接键 {@code sku_id} —— {@code sales_order_line.sku_id} 与
 *       {@code stock_bucket.sku_id} 指的是同一个逻辑商品。</li>
 * </ul>
 *
 * <p>⚠️ **「查无库存账」不等于「库存充足」**：warehouse 的可售量接口对
 * 「这个 SKU 从来没有库存账」返回的是**没有这一行**，而不是 0。
 * 本模块把它单列为「查无库存账」并如实报出，不把它算成「库存不足」——
 * 那是一次凭空捏造的判定。
 */

// 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
// 用别名会让这个模型的单测在 vite 下加载失败。与 `usage-stat-model` 保持同一套约定。
import type {
  AnomalyOrderAction,
  AnomalyOrderCapabilities,
  AnomalyOrderListRow,
  AvailableQuantityRow,
  BuyerBlacklistOrderFlagView,
} from '../../../../../packages/api-contracts/src/index';

/** 七类异常（顺序即蓝图里的顺序）。 */
export const ANOMALY_TYPES = [
  'PLATFORM_SHIPMENT_TIMEOUT',
  'ADDRESS_UNDELIVERABLE',
  'BUYER_MESSAGE_PENDING',
  'BLACKLIST_BUYER',
  'INSUFFICIENT_STOCK',
  'ADDRESS_FRAUD_SUSPECTED',
  'DUPLICATE_ORDER',
] as const;

export type AnomalyType = (typeof ANOMALY_TYPES)[number];

/** 跨服务聚合的两类：事实不在 trade，由前端合并层补进来。 */
export const CROSS_SERVICE_TYPES: readonly string[] = ['BLACKLIST_BUYER', 'INSUFFICIENT_STOCK'];

/** 本服务侧的五类：后端已经算好，前端只负责展示。 */
export const TRADE_SIDE_TYPES: readonly string[] = [
  'PLATFORM_SHIPMENT_TIMEOUT',
  'ADDRESS_UNDELIVERABLE',
  'BUYER_MESSAGE_PENDING',
  'ADDRESS_FRAUD_SUSPECTED',
  'DUPLICATE_ORDER',
];

export const ANOMALY_TYPE_LABELS: Record<string, string> = {
  PLATFORM_SHIPMENT_TIMEOUT: '平台超时未回传发货',
  ADDRESS_UNDELIVERABLE: '地址不可达',
  BUYER_MESSAGE_PENDING: '买家留言待确认',
  BLACKLIST_BUYER: '黑名单买家',
  INSUFFICIENT_STOCK: '库存不足卡单',
  ADDRESS_FRAUD_SUSPECTED: '地址欺诈疑似',
  DUPLICATE_ORDER: '重复下单',
};

export const SEVERITY_LABELS: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '严重',
};

/** 建议动作的标签与归属模块（后端 {@code capabilities.actions} 也带一份，这里是前端兜底文案）。 */
export const ACTION_LABELS: Record<string, string> = {
  OPEN_ORDER: '打开订单处理页',
  ESCALATE_MANUAL: '转人工',
  TRANSFER_AFTERSALES: '转售后',
  QUERY_PLATFORM: '平台侧查询',
  OPEN_BLACKLIST: '查看黑名单',
  OPEN_INVENTORY: '查看库存可售量',
  MARK_IGNORED: '标记忽略',
};

/**
 * 建议动作 → 前端路由。
 *
 * <p>「导航到哪儿」是前端的责任，「这个动作归谁管」是后端的责任 —— 两者分开，
 * 才不会出现前端把一个后端说「归属系统没有入口」的动作渲染成一个能点的按钮。
 *
 * @param orderId 订单 id。{@link ACTION_OPEN_ORDER} 需要它拼出订单处理页地址。
 */
export function actionTarget(code: string, orderId?: string): string {
  switch (code) {
    case 'OPEN_ORDER':
      return orderId ? `/orders/${orderId}` : '/orders';
    case 'ESCALATE_MANUAL':
      return '/compliance/intercept';
    case 'TRANSFER_AFTERSALES':
      return '/aftersales/intake';
    case 'QUERY_PLATFORM':
      return '/channels/writeback';
    case 'OPEN_BLACKLIST':
      return '/compliance/buyer-blacklist';
    case 'OPEN_INVENTORY':
      return '/inventory';
    default:
      return '';
  }
}

/** 一条库存缺口（前端算出来的，事实来自 warehouse 的可售量投影）。 */
export interface StockShortage {
  skuId: string;
  skuCode: string;
  need: number;
  sellable: number;
}

/** 合并后的一行：订单侧信号 + 跨服务信号。 */
export interface MergedAnomalyOrder {
  row: AnomalyOrderListRow;
  types: string[];
  severity: string;
  dwellSeconds: number;
  /** 命中的黑名单标记（channel 16.5.1.1）。 */
  blacklist: BuyerBlacklistOrderFlagView | null;
  /** 库存缺口（warehouse 7.1.1.3 可售量）。 */
  shortages: StockShortage[];
  /** 仓库里**查无库存账**的 SKU（判不了，不是「库存充足」）。 */
  unknownSkus: string[];
  /** 建议动作码（后端给的 + 跨服务补的）。 */
  actions: string[];
}

const SEVERITY_RANK: Record<string, number> = { LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 };

/** 单类异常的基准严重度（跨服务两类按各自的读数细化）。 */
const BASE_SEVERITY: Record<string, string | undefined> = {
  PLATFORM_SHIPMENT_TIMEOUT: 'HIGH',
  ADDRESS_UNDELIVERABLE: 'HIGH',
  BUYER_MESSAGE_PENDING: 'MEDIUM',
  BLACKLIST_BUYER: 'MEDIUM',
  INSUFFICIENT_STOCK: 'MEDIUM',
  ADDRESS_FRAUD_SUSPECTED: 'HIGH',
  DUPLICATE_ORDER: 'MEDIUM',
};

/**
 * 黑名单标记是否落在这一单上。
 *
 * <p>两个条件都要满足：
 *
 * <ul>
 *   <li><b>买家身份键相同</b> —— 连接键，逐字同式（见文件头）；</li>
 *   <li><b>订单引用相容</b> —— 命中标记里记的 {@code order_ref} 为空（买家级标记，
 *       对该买家的这一单同样成立），或等于本单的订单号 / 订单 id。</li>
 * </ul>
 *
 * <p>⚠️ 只按 {@code order_ref} 匹配是不够的：channel 侧允许 {@code order_ref} 留空
 * （下单时没给），那一条标记只有买家键可用。
 */
export function matchBlacklistFlag(
  row: AnomalyOrderListRow,
  flags: readonly BuyerBlacklistOrderFlagView[],
): BuyerBlacklistOrderFlagView | null {
  if (!row.buyerKey) return null;
  for (const flag of flags) {
    if (flag.buyerKey !== row.buyerKey) continue;
    const ref = (flag.orderRef ?? '').trim();
    if (!ref) return flag;
    if (ref === row.orderNo || ref === row.orderId) return flag;
  }
  return null;
}

/**
 * 库存缺口：这一行要的量**超过**仓库的可售量。
 *
 * <p>⚠️ 仓库里**查无此 SKU 的库存账**时判不了 —— 那不是「库存为零」，
 * 所以不进缺口清单，只进 {@link MergedAnomalyOrder.unknownSkus} 由页面如实报出。
 */
export function matchStockShortages(
  row: AnomalyOrderListRow,
  sellableBySku: ReadonlyMap<string, AvailableQuantityRow>,
): { shortages: StockShortage[]; unknownSkus: string[] } {
  const shortages: StockShortage[] = [];
  const unknownSkus: string[] = [];
  for (const need of row.skuNeeds ?? []) {
    const available = sellableBySku.get(need.skuId);
    if (!available) {
      unknownSkus.push(need.skuCode);
      continue;
    }
    if (need.quantity > available.sellable) {
      shortages.push({
        skuId: need.skuId,
        skuCode: need.skuCode,
        need: need.quantity,
        sellable: available.sellable,
      });
    }
  }
  return { shortages, unknownSkus };
}

/** 黑名单命中的严重度：跟着 channel 给的风险等级走。 */
function blacklistSeverity(flag: BuyerBlacklistOrderFlagView | null): string {
  if (!flag) return 'MEDIUM';
  switch (flag.riskLevel) {
    case 'CRITICAL':
      return 'CRITICAL';
    case 'HIGH':
      return 'HIGH';
    default:
      return 'MEDIUM';
  }
}

/**
 * 把跨服务信号并进行内。
 *
 * @param rows trade 侧的行（后端已算好本服务侧五类信号）
 * @param flags channel 16.5.1.1 的订单拦截标记
 * @param sellableBySku warehouse 7.1.1.3 的可售量（按 skuId 索引）
 */
export function mergeCrossService(
  rows: readonly AnomalyOrderListRow[],
  flags: readonly BuyerBlacklistOrderFlagView[],
  sellableBySku: ReadonlyMap<string, AvailableQuantityRow>,
): MergedAnomalyOrder[] {
  return rows.map((row) => {
    const types = new Set<string>(row.anomalyTypes ?? []);
    const actions = new Set<string>(row.recommendedActions ?? []);
    const blacklist = matchBlacklistFlag(row, flags);
    if (blacklist) {
      types.add('BLACKLIST_BUYER');
      actions.add('OPEN_BLACKLIST');
      actions.add('ESCALATE_MANUAL');
    }
    const { shortages, unknownSkus } = matchStockShortages(row, sellableBySku);
    if (shortages.length > 0) {
      types.add('INSUFFICIENT_STOCK');
      actions.add('OPEN_INVENTORY');
      actions.add('ESCALATE_MANUAL');
    }
    const severity = [...types].reduce((worst, type) => {
      const candidate =
        type === 'BLACKLIST_BUYER' ? blacklistSeverity(blacklist) : BASE_SEVERITY[type] ?? 'MEDIUM';
      const rank = SEVERITY_RANK[candidate] ?? 1;
      const worstRank = SEVERITY_RANK[worst] ?? 1;
      return rank > worstRank ? candidate : worst;
    }, 'LOW');
    return {
      row,
      types: ANOMALY_TYPES.filter((type) => types.has(type)),
      severity,
      dwellSeconds: row.dwellSeconds,
      blacklist,
      shortages,
      unknownSkus,
      actions: [...actions],
    };
  });
}

/** 清单筛选条件（前端侧，全部基于**合并后**的集合）。 */
export interface AnomalyFilters {
  type: string;
  shopCode: string;
  keyword: string;
  minDwellHours: number;
}

/** 按筛选条件过滤合并后的集合。 */
export function filterMerged(
  rows: readonly MergedAnomalyOrder[],
  filters: AnomalyFilters,
): MergedAnomalyOrder[] {
  const keyword = filters.keyword.trim().toLowerCase();
  return rows.filter((row) => {
    if (filters.type && !row.types.includes(filters.type)) return false;
    if (filters.shopCode && row.row.shopCode !== filters.shopCode) return false;
    if (filters.minDwellHours > 0 && row.dwellSeconds < filters.minDwellHours * 3600) return false;
    if (keyword) {
      const haystack = `${row.row.orderNo} ${row.row.shopCode} ${row.row.platformCode}`.toLowerCase();
      if (!haystack.includes(keyword)) return false;
    }
    return true;
  });
}

/** 合并后集合的汇总计数（**与列表同一个集合**，翻页时它不变）。 */
export function summarizeMerged(rows: readonly MergedAnomalyOrder[]): {
  total: number;
  byType: Record<string, number>;
  oldestDwellSeconds: number;
} {
  const byType: Record<string, number> = {};
  for (const type of ANOMALY_TYPES) byType[type] = 0;
  let oldest = 0;
  for (const row of rows) {
    for (const type of row.types) byType[type] = (byType[type] ?? 0) + 1;
    if (row.dwellSeconds > oldest) oldest = row.dwellSeconds;
  }
  return { total: rows.length, byType, oldestDwellSeconds: oldest };
}

/**
 * 批量动作：来自后端能力声明里的动作清单。
 *
 * <p>🔴 {@code available=false} 的动作（当前只有「标记忽略」）
 * **不能**被渲染成一个能一键落地的按钮 —— 归属系统没有那个写端点。
 * 它会带着「缺什么」一起显示，由人转到归属系统处理。
 */
export function batchActions(capabilities: AnomalyOrderCapabilities | null): AnomalyOrderAction[] {
  if (!capabilities) return [];
  return capabilities.actions ?? [];
}

/** 停留时长文案。 */
export function formatDwell(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '—';
  if (seconds < 60) return '不到 1 分钟';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  if (hours < 24) return restMinutes ? `${hours} 小时 ${restMinutes} 分钟` : `${hours} 小时`;
  const days = Math.floor(hours / 24);
  const restHours = hours % 24;
  return restHours ? `${days} 天 ${restHours} 小时` : `${days} 天`;
}

/** 金额文案。金额一律用 BigDecimal / NUMERIC 口径，前端不参与计算，只做展示。 */
export function formatAmount(currency: string, amount: number | null | undefined): string {
  if (amount === null || amount === undefined || !Number.isFinite(amount)) return '—';
  return `${currency || ''} ${amount.toFixed(2)}`.trim();
}

/** 数量文案。 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

/** 把选中的订单号拼成一段可交接的清单（批量动作落到归属系统时用）。 */
export function orderNumberDigest(rows: readonly MergedAnomalyOrder[]): string {
  return rows.map((row) => row.row.orderNo).filter(Boolean).join(', ');
}
