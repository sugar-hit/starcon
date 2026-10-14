import type {
  VmiAgreement,
  VmiAgreementDetail,
  VmiAgreementPage,
  VmiAuditEntry,
  VmiCapabilities,
  VmiLine,
  VmiOwnershipSplit,
  VmiSalePage,
  VmiSettlement,
  VmiSettlementPage,
  VmiSignal,
  VmiSignalPage,
  VmiWaterLevelMonitor,
  VmiSettlementWindow,
  VmiSnapshotPage,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.7.2.1 VMI 供应商管理库存前端模型。
 *
 * <p>本模块界面上最容易出错、也是蓝图加粗的那一条：**「可售」≠「自有」**。
 * 可售量要计入可售额，货值不能计入自有资产，两者必须分开统计。这个模型里所有相关函数
 * 都遵守同一条纪律：
 *
 * <ul>
 *   <li>**自有货值恒为 null，显示成「—（本口径不存在）」而不是 0**。
 *       显示 0 会被读成「这些货值 0 元」，那是谎报；null 才是「这个数不存在」。
 *   <li>**两张卡片永不合并**。VMI 可售额与我方自有量并排展示，界面上没有「合计库存价值」
 *       这样的合计数 —— 一旦相加就等于把供应商的货算成我们的资产。
 *   <li>**缺来源要说出来**。账本查不到、租户也没登记时报 MISSING，界面必须显眼地标出来，
 *       而不是悄悄当成 0。
 * </ul>
 */

export interface AgreementParams {
  status?: string;
  replenishmentMode?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface SignalParams {
  agreementId?: string;
  status?: string;
  signalTarget?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'warehouse/inventory/vmi';

export const fetchVmiCapabilities = () => api<VmiCapabilities>(`${base}/capabilities`);

export const fetchVmiAgreements = (params: AgreementParams = {}) =>
  api<VmiAgreementPage>(`${base}/agreements${query(params)}`);

export const fetchVmiAgreement = (agreementId: string) =>
  api<VmiAgreementDetail>(`${base}/agreements/${agreementId}`);

export const createVmiAgreement = (body: unknown) =>
  api<VmiAgreementDetail>(`${base}/agreements`, 'POST', body);

export const activateVmiAgreement = (agreementId: string, note?: string) =>
  api<VmiAgreement>(`${base}/agreements/${agreementId}/activate`, 'POST', { note: note ?? '' });

export const suspendVmiAgreement = (agreementId: string, note?: string) =>
  api<VmiAgreement>(`${base}/agreements/${agreementId}/suspend`, 'POST', { note: note ?? '' });

export const closeVmiAgreement = (agreementId: string, note?: string) =>
  api<VmiAgreement>(`${base}/agreements/${agreementId}/close`, 'POST', { note: note ?? '' });

export const fetchVmiWaterLevels = (agreementId: string) =>
  api<VmiWaterLevelMonitor>(`${base}/agreements/${agreementId}/water-levels`);

export const generateVmiSignals = (agreementId: string, lineIds: string[] = [], note?: string) =>
  api<{ createdCount: number; skippedReasons: string[] }>(
    `${base}/agreements/${agreementId}/replenishment-signals`,
    'POST',
    { lineIds, note: note ?? '' },
  );

export const fetchVmiSignals = (params: SignalParams = {}) =>
  api<VmiSignalPage>(`${base}/replenishment-signals${query(params)}`);

export const acknowledgeVmiSignal = (signalId: string, note?: string) =>
  api<VmiSignal>(`${base}/replenishment-signals/${signalId}/acknowledge`, 'POST', {
    note: note ?? '',
  });

export const dispatchVmiSignal = (signalId: string, note?: string) =>
  api<VmiSignal>(`${base}/replenishment-signals/${signalId}/dispatch`, 'POST', {
    note: note ?? '',
  });

export const fulfilVmiSignal = (signalId: string, fulfilledQuantity: number, note?: string) =>
  api<VmiSignal>(`${base}/replenishment-signals/${signalId}/fulfil`, 'POST', {
    fulfilledQuantity,
    note: note ?? '',
  });

export const cancelVmiSignal = (signalId: string, note?: string) =>
  api<VmiSignal>(`${base}/replenishment-signals/${signalId}/cancel`, 'POST', { note: note ?? '' });

export const recordVmiSnapshot = (agreementId: string, body: unknown) =>
  api<unknown>(`${base}/agreements/${agreementId}/snapshots`, 'POST', body);

export const fetchVmiSnapshots = (agreementId: string, lineId?: string, page = 0, size = 20) =>
  api<VmiSnapshotPage>(
    `${base}/agreements/${agreementId}/snapshots${query({ lineId, page, size })}`,
  );

export const recordVmiSale = (agreementId: string, lineId: string, body: unknown) =>
  api<unknown>(`${base}/agreements/${agreementId}/lines/${lineId}/sales`, 'POST', body);

export const fetchVmiSales = (
  agreementId: string,
  unsettleableOnly?: boolean,
  page = 0,
  size = 20,
) =>
  api<VmiSalePage>(
    `${base}/agreements/${agreementId}/sales${query({ unsettleableOnly, page, size })}`,
  );

export const fetchVmiSettlementWindow = (agreementId: string) =>
  api<VmiSettlementWindow>(`${base}/agreements/${agreementId}/settlement-window`);

export const createVmiSettlement = (
  agreementId: string,
  periodStart: string,
  periodEnd: string,
  note?: string,
) =>
  api<unknown>(`${base}/agreements/${agreementId}/settlements`, 'POST', {
    periodStart,
    periodEnd,
    note: note ?? '',
  });

export const fetchVmiSettlements = (agreementId?: string, status?: string, page = 0, size = 20) =>
  api<VmiSettlementPage>(`${base}/settlements${query({ agreementId, status, page, size })}`);

export const confirmVmiSettlement = (settlementId: string, note?: string) =>
  api<VmiSettlement>(`${base}/settlements/${settlementId}/confirm`, 'POST', { note: note ?? '' });

export const fetchVmiAudit = (agreementId: string) =>
  api<VmiAuditEntry[]>(`${base}/agreements/${agreementId}/audit`);

// ------------------------------------------------------------ 格式化

/** 数量最多 6 位小数（与后端 numeric(19,6) 口径一致）。 */
export function formatQty(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/**
 * 金额（可售额、结算价、应付）。最多 4 位小数 —— 与后端 numeric(19,4)/(24,4) 一致。
 *
 * <p>后端已经按 HALF_UP 抹到分，这里不再二次四舍五入，避免展示层改变账面口径。
 */
export function formatAmount(
  value: number | string | null | undefined,
  currency: string,
  unavailableText = '—',
): string {
  if (value === null || value === undefined || value === '') return unavailableText;
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return unavailableText;
  return `${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })} ${currency}`;
}

/**
 * 【本模块最关键的一个函数】自有货值的展示文本。
 *
 * <p>null 表示**这个口径不存在**（VMI 货值不计入我方自有资产），显示成 0 会被读成
 * 「这些货值 0 元」，那是谎报。所以这里显式返回「—（不计入自有资产）」。
 */
export function formatOwnedAssetValue(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—（不计入自有资产）';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return '—（不计入自有资产）';
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

// ------------------------------------------------------------ 水位

export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/**
 * 语气 → {@link Notice} 的 tone。
 *
 * <p>分两套词表是因为「语气」回答「该不该显眼」，Notice 的 tone 回答「用哪种颜色」；
 * 混用一套会让「余量不足」被画成和「断货」一样重。
 */
export function noticeTone(tone: Tone): 'info' | 'success' | 'warning' | 'danger' {
  switch (tone) {
    case 'ok':
      return 'success';
    case 'warn':
      return 'warning';
    case 'danger':
      return 'danger';
    default:
      return 'info';
  }
}

/** 语气对应的文字色 class（表格内联使用）。 */
export function toneClass(tone: Tone): string {
  switch (tone) {
    case 'ok':
      return 'text-success';
    case 'warn':
      return 'text-warning';
    case 'danger':
      return 'text-destructive';
    default:
      return 'text-muted-foreground';
  }
}

/**
 * 水位状态的语气。四档分开而不是笼统的「库存低」——
 * 断、要跌破水位、余量不足三者的紧急度与处置完全不同，界面语气必须跟着分开。
 */
export function waterLevelTone(status: string): Tone {
  switch (status) {
    case 'STOCKOUT':
      return 'danger';
    case 'BELOW_SAFETY_LEVEL':
      return 'warn';
    case 'BELOW_BUFFER':
      return 'muted';
    default:
      return 'ok';
  }
}

export function waterLevelLabel(status: string, fallback = ''): string {
  const labels: Record<string, string> = {
    HEALTHY: '水位充足',
    BELOW_BUFFER: '余量不足',
    BELOW_SAFETY_LEVEL: '低于安全水位',
    STOCKOUT: '断货',
  };
  return labels[status] ?? fallback ?? status;
}

/** 该行是否需要出补货信号（水位不健康即需要）。 */
export function needsSignal(line: Pick<VmiLine, 'waterLevelStatus'>): boolean {
  return line.waterLevelStatus !== 'HEALTHY';
}

/**
 * 距安全水位的缺口语义化文本。
 *
 * <p>正数 = 还差多少才够；负数 = 超出水位多少。两种都要讲清楚，
 * 只给一个带符号的数，使用者得自己判断方向。
 */
export function safetyGapText(gap: number | string | null | undefined): string {
  const n = Number(gap ?? 0);
  if (!Number.isFinite(n)) return '—';
  if (n > 0) return `还差 ${formatQty(n)} 到安全水位`;
  if (n < 0) return `高于安全水位 ${formatQty(Math.abs(n))}`;
  return '正好在水位线上';
}

/** 覆盖率（天）。没有销量时是 null —— 显示 0 天会被读成「今天就断货」。 */
export function formatCoverageDays(days: number | null | undefined): string {
  if (days === null || days === undefined) return '—（近 30 天无销量）';
  return `约 ${formatQty(days)} 天`;
}

// ------------------------------------------------------------ 来源标注

/**
 * 在库量来源的标注。**MISSING 必须显眼** —— 两边都查不到时如实报缺口，
 * 而不是悄悄按 0 处理：0 会触发补货，而使用者会以为系统算过了。
 */
export function stockSourceTone(source: string): Tone {
  if (source === 'MISSING') return 'danger';
  if (source === 'WAREHOUSE_BUCKET') return 'ok';
  return 'muted';
}

export function stockSourceLabel(source: string, fallback = ''): string {
  const labels: Record<string, string> = {
    WAREHOUSE_BUCKET: '自有仓账本算出',
    TENANT_MAINTAINED: '租户登记',
    VENDOR_FEED: '供应商送数',
    MISSING: '查不到来源',
  };
  return labels[source] ?? fallback ?? source;
}

/** 缺来源时的提示文案：说清后果（会按 0 触发补货）与该怎么补。 */
export function missingStockHint(): string {
  return '这行在库量两侧都查不到（账本里没有这个 SKU×仓 的桶，也没有登记观测），已按 0 计入水位 —— 请先登记在库观测，否则会误判成断货并触发补货。';
}

// ------------------------------------------------------------ 可售 / 自有

/**
 * 【本模块的核心口径】把「可售」与「自有」讲成一句人话，界面上必须常驻。
 *
 * <p>返回的文案刻意同时给出两个方向：可售额计入、自有货值不计入。
 * 只讲一半的话，使用者一定会把 VMI 的量当成我方资产。
 */
export function ownershipSplitNote(
  split: Pick<
    VmiOwnershipSplit,
    'sellableQuantity' | 'sellableValue' | 'ownedAssetValue' | 'countsAsOwnedAsset'
  >,
  currency: string,
): string {
  const qty = formatQty(split.sellableQuantity);
  const value = formatAmount(split.sellableValue, currency);
  if (split.countsAsOwnedAsset) {
    return `异常：可售 ${qty} 件 / ${value}，但这批货被标成了自有资产 —— 请检查货主配置。`;
  }
  return `VMI 可售 ${qty} 件，可售额 ${value} **计入可售**；自有货值 ${formatOwnedAssetValue(split.ownedAssetValue)} —— 这批货属供应商，不计入我方自有资产。`;
}

/**
 * 两张卡片并排时的提示：为什么不合并。
 *
 * <p>界面上「合计」是把供应商的货算成我们资产的唯一入口，所以这句话必须写在两张卡片旁边。
 */
export function separationWarning(
  vmiQuantity: number | string | null | undefined,
  selfOwnedQuantity: number | string | null | undefined,
): string {
  return (
    `VMI 可售 ${formatQty(vmiQuantity)} 件与我方自有 ${formatQty(selfOwnedQuantity)} 件**不可相加**：` +
    '两者货主不同，相加就等于把供应商的货算成我们的资产。'
  );
}

/** 监控页第一屏的口径卡片（可售额 / 自有货值两张卡，绝不合成一张）。 */
export function monitorOwnershipCards(
  monitor: Pick<VmiWaterLevelMonitor, 'ownershipSplit' | 'selfOwnedSplit' | 'currency'>,
): { sellable: string; sellableValue: string; owned: string; note: string } {
  return {
    sellable: formatQty(monitor.ownershipSplit.sellableQuantity),
    sellableValue: formatAmount(monitor.ownershipSplit.sellableValue, monitor.currency),
    owned: formatOwnedAssetValue(monitor.ownershipSplit.ownedAssetValue),
    note: separationWarning(
      monitor.ownershipSplit.sellableQuantity,
      monitor.selfOwnedSplit.selfOwnedQuantity,
    ),
  };
}

// ------------------------------------------------------------ 信号

export function signalTone(status: string): Tone {
  switch (status) {
    case 'FULFILLED':
      return 'ok';
    case 'CANCELLED':
      return 'muted';
    case 'OPEN':
      return 'danger';
    default:
      return 'warn';
  }
}

/** 信号是否仍在推进中（未了结）。未了结时不允许再出第二条。 */
export function isSignalOpen(signal: Pick<VmiSignal, 'status'>): boolean {
  return (
    signal.status === 'OPEN' || signal.status === 'ACKNOWLEDGED' || signal.status === 'DISPATCHED'
  );
}

/** 该信号此刻能不能登记到货（只有已下发的才能）。 */
export function canFulfil(signal: Pick<VmiSignal, 'status'>): boolean {
  return signal.status === 'DISPATCHED';
}

/** 信号出处的一句话：下游未交付时要说清只留了定位键。 */
export function signalTargetHint(target: string): string {
  if (target === 'PROCUREMENT') {
    return '交给 6 采购备货（6.2.1.2 尚未交付，本系统只出单并写交接键）';
  }
  return '推给供应商备货（VMI 的原始形态）';
}

/** 建议补货量的展示文本，并说明它是怎么算出来的。 */
export function projectedHint(
  line: Pick<VmiLine, 'safetyLevel' | 'replenishmentMultiple' | 'projectedQuantity'>,
): string {
  return `建议补 ${formatQty(line.projectedQuantity)} 件 =（安全水位 ${formatQty(line.safetyLevel)} − 当前可售）× 补货倍数 ${formatQty(line.replenishmentMultiple)}，向上取整`;
}

// ------------------------------------------------------------ 状态机

export function isAgreementActive(agreement: Pick<VmiAgreement, 'status'>): boolean {
  return agreement.status === 'ACTIVE';
}

/** 对账单是否已被确认（确认后明细被数据库冻结，界面不该再给改数量的入口）。 */
export function isSettlementLocked(settlement: Pick<VmiSettlement, 'status'>): boolean {
  return settlement.status === 'CONFIRMED' || settlement.status === 'CANCELLED';
}

/** 审计时间线的事件中文标签。 */
export function auditLabel(eventType: string): string {
  const labels: Record<string, string> = {
    AGREEMENT_CREATED: '建立协议',
    LINE_ADDED: '新增明细行',
    AGREEMENT_ACTIVATED: '启用协议',
    AGREEMENT_SUSPENDED: '暂停协议',
    AGREEMENT_CLOSED: '关闭协议',
    SIGNAL_GENERATED: '出补货信号',
    SIGNAL_ACKNOWLEDGED: '受理信号',
    SIGNAL_DISPATCHED: '下发信号',
    SIGNAL_FULFILLED: '登记到货',
    SIGNAL_CANCELLED: '作废信号',
    SNAPSHOT_RECORDED: '登记在库观测',
    SALE_RECORDED: '记录售出',
    SETTLEMENT_CREATED: '出结算对账单',
    SETTLEMENT_CONFIRMED: '确认对账单（交 12 结算）',
  };
  return labels[eventType] ?? eventType;
}

/** 把 ApiError 变成一句能直接展示的话。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '操作失败，请重试。';
}
