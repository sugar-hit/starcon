import type {
  AvailableQuantityCapabilities,
  AvailableQuantityPage,
  AvailableQuantityReport,
  AvailableQuantitySlice,
  AvailableQuantityTotals,
} from '../../../../../packages/api-contracts/src/index';
import { api, ApiError } from './api';

/**
 * 7.1.1.3 可用量计算前端模型。
 *
 * <p>蓝图：「统一计算并展示可售量（available），明确其口径……支持查看构成拆解。」
 * 「<b>可售量是全系统的单一口径来源</b>，不允许各系统各自算 —— 这是超卖的第一根源。」
 *
 * <p>所以这一页的界面纪律全都在**不误导**上：
 *
 * <ul>
 *   <li><b>拆解是并列的切分，不是瀑布。</b>待检 / 残次 / 未上架是「被排除」，
 *       不是「再扣一次」。画成逐级相减的瀑布图会让人以为它们被扣了两道 ——
 *       而蓝图那句「不重复扣减」防的正是这个。
 *   <li><b>安全缓冲 null 显示「未配置」而不是 0。</b>「没留缓冲」与「留了 0 件缓冲」
 *       在业务上完全不同：前者是这条 SKU 还没有安全库存策略，后者是运营明确留了 0。
 *   <li><b>口径不平时把 bufferExceedsBase 顶到第一屏。</b>安全缓冲比可售基数还大时
 *       可售量是 0，而这不正常运营状态，必须让人看见而不是安静地显示 0。
 *   <li><b>恒等式破了就报警。</b>{@code sliceBalanced=false} 意味着五片之和对不上 available，
 *       口径已经不可信 —— 这时页面首要提示的是这件事，不是可售量。
 * </ul>
 */

/** 查询条件。与后端 `AvailableQuantityQuery` 一一对应。 */
export interface SellableParams {
  warehouseId?: string | undefined;
  legalEntityId?: string | undefined;
  locationId?: string | undefined;
  skuId?: string | undefined;
  qualityStatusId?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 报告端点的参数（不含分页与排序 —— 合计不受分页影响）。 */
export type SellableReportParams = Omit<SellableParams, 'sort' | 'page' | 'size'>;

export const defaultSellableParams: SellableParams = {
  keyword: '',
  sort: 'SELLABLE',
  page: 0,
  size: 50,
};

function queryString(params: SellableParams | SellableReportParams): string {
  const parts: string[] = [];
  const push = (key: keyof SellableParams, value: string | undefined) => {
    if (value !== undefined && value !== '') parts.push(`${key}=${encodeURIComponent(value)}`);
  };
  push('warehouseId', params.warehouseId);
  push('legalEntityId', params.legalEntityId);
  push('locationId', params.locationId);
  push('skuId', params.skuId);
  push('qualityStatusId', params.qualityStatusId);
  push('keyword', params.keyword);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchSellableReport(params: SellableReportParams) {
  return api<AvailableQuantityReport>(`warehouse/inventory/sellable${queryString(params)}`);
}

export function fetchSellableSkus(params: SellableParams) {
  const qs = queryString(params);
  const parts: string[] = [];
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  const tail = parts.length ? `${qs ? '&' : '?'}${parts.join('&')}` : qs;
  return api<AvailableQuantityPage>(`warehouse/inventory/sellable/skus${tail}`);
}

export function fetchSellableCapabilities() {
  return api<AvailableQuantityCapabilities>('warehouse/inventory/sellable/capabilities');
}

// ------------------------------------------------------------ 展示口径

/** 拆解片的固定行序。与后端 `slices()` 的顺序一致，界面不能按大小重排 —— 那会让「口径」看起来像「排名」。 */
export const sliceOrder = [
  'SELLABLE',
  'PENDING_INSPECTION',
  'DEFECTIVE',
  'UNLISTED',
  'BLOCKED_LOT',
] as const;

/** 排序键白名单。与后端一致；非法值不在下拉里出现，后端收到也会 400。 */
export const sellableSortLabels: Record<string, string> = {
  SELLABLE: '可售量从高到低',
  AVAILABLE: '可用量从高到低',
  ON_HAND: '在手从高到低',
  SKU_CODE: '按 SKU 编码',
};

/** 缓冲命中的档位中文名。`NONE` 是「没配」，不是「配了兜底档」。 */
export function bufferScopeLabel(scopeType?: string | null): string {
  switch (scopeType) {
    case 'SKU':
      return 'SKU 档';
    case 'WAREHOUSE':
      return '仓库档';
    case 'DEFAULT':
      return '全租户兜底档';
    case 'NONE':
      return '未配置安全缓冲';
    default:
      return '未配置安全缓冲';
  }
}

/**
 * 数量显示。契约里 BigDecimal 拍平成 `number`，但保留 string 分支
 * 以免上游改成字符串时静默算出 NaN。
 */
export function qty(value?: number | string | null): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  if (Number.isNaN(n)) return '—';
  // 去掉尾随 0：100.000000 显示成 100 而不是一长串小数
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(6)));
}

/**
 * 安全缓冲的显示。
 *
 * <p>null → 「未配置」，**不是 0**。这是本模块最重要的一处 null 纪律：
 * 「这个 SKU 没有安全库存策略」和「运营决定留 0 件」是两件事，
 * 前者要去配策略，后者是一个已经做过的决策。混成一个 0 会让前者永远不被发现。
 */
export function safetyBufferText(totals: AvailableQuantityTotals | null | undefined): string {
  if (!totals) return '—';
  if (totals.safetyBuffer === null || totals.safetyBuffer === undefined) return '未配置';
  return qty(totals.safetyBuffer);
}

/** 安全缓冲是否真的配过（而不是恰好是 0）。界面上用它决定要不要给「去配置」的提示。 */
export function safetyBufferConfigured(
  totals: AvailableQuantityTotals | null | undefined,
): boolean {
  if (!totals) return false;
  return totals.bufferedSkuCount > 0;
}

/** 按固定行序返回拆解片。缺哪片补哪片 —— 接口少一片不该让表格少一行。 */
export function orderedSlices(
  slices: AvailableQuantitySlice[] | undefined,
): AvailableQuantitySlice[] {
  const byCode = new Map((slices ?? []).map((s) => [s.code, s]));
  return sliceOrder.map((code) => byCode.get(code)).filter((s): s is AvailableQuantitySlice => !!s);
}

/**
 * 一片是「计入可售」还是「被排除」。
 *
 * <p>界面上这两类必须**用不同的视觉**区分：计入的那片是可卖的钱，被排除的四片是「拿不出去」的原因。
 * 混成同一列数字的话，运营会把 40 件批次冻结的货算进「我有 90 件可卖」里。
 */
export function sliceTone(slice: AvailableQuantitySlice): 'counted' | 'excluded' {
  return slice.countedInSellable ? 'counted' : 'excluded';
}

/**
 * 拆解是否自洽。五片之和必须恰好等于 available。
 *
 * <p>这是「不重复扣减」的可执行判据。false 时页面要把它当第一优先提示 ——
 * 一个不自洽的可售量比没有更危险。
 */
export function sliceBalanced(totals: AvailableQuantityTotals | null | undefined): boolean {
  if (!totals) return true;
  const sum = orderedSlices(totals.slices).reduce(
    (acc, s) => acc + Number(s.amount ?? 0),
    0,
  );
  const available = Number(totals.available ?? 0);
  // 容差取 1e-6：NUMERIC(19,4) 的四舍五入残渣不该被当成口径坏了
  return Math.abs(sum - available) < 1e-6 && totals.sliceBalanced;
}

/** 页面上最该顶到第一屏的那个问题；没有就返回空串。 */
export function headlineWarning(totals: AvailableQuantityTotals | null | undefined): string {
  if (!totals) return '';
  if (!sliceBalanced(totals)) {
    return '构成拆解与可用量对不上（口径已不自洽），请勿把本页数字用于回写平台';
  }
  if (totals.bufferExceedsBase) {
    return `安全缓冲（${qty(totals.safetyBufferDeducted)}）大于可售基数（${qty(
      totals.sellableBeforeBuffer,
    )}），可售量已按 0 计 —— 请下调缓冲或补货`;
  }
  return '';
}

/** 缓冲档位的中文说明，附在数字后面让人知道这个数从哪来。 */
export function bufferScopeHint(totals: AvailableQuantityTotals | null | undefined): string {
  if (!totals) return '';
  if (!safetyBufferConfigured(totals)) {
    return '范围内没有任何 SKU 配置安全缓冲，可售量 = 可售基数';
  }
  return `${totals.bufferedSkuCount} 个 SKU 配置了安全缓冲，按 SKU > 仓库 > 全租户兜底解析`;
}

/** 渠道口径的显示文案。未配置与「配了 0 比例」必须分开。 */
export function channelPolicyText(
  policy: AvailableQuantityReport['channelPolicy'] | null | undefined,
): string {
  if (!policy || !policy.configured) return '未配置渠道可售量口径（待 7.2.2.1）';
  const parts = [`${policy.resolvedScopeType} 档`];
  if (policy.poolMode) parts.push(policy.poolMode === 'SHARED' ? '共享池' : '独占池');
  if (policy.bufferRatio !== null && policy.bufferRatio !== undefined) {
    parts.push(`缓冲比例 ${(Number(policy.bufferRatio) * 100).toFixed(1)}%`);
  }
  if (policy.usedFallback) parts.push('（回退到兜底档）');
  return parts.join(' · ');
}

/** 是否真的有库存（有 bucket 数量）。空态与「口径坏了」是两件事，不能共用一个提示。 */
export function isEmptyScope(totals: AvailableQuantityTotals | null | undefined): boolean {
  if (!totals) return true;
  return Number(totals.skuCount ?? 0) === 0 && Number(totals.bucketCount ?? 0) === 0;
}

/** 未交付能力在界面上的折叠区条目。`available=false` 的要说明归属模块。 */
export interface CapabilityGap {
  key: string;
  label: string;
  available: boolean;
  ownerModule: string;
  note: string;
}

export function capabilityGaps(
  caps: AvailableQuantityCapabilities | null | undefined,
): CapabilityGap[] {
  if (!caps) return [];
  return [
    {
      key: 'quantityRole',
      label: '质量状态口径',
      available: caps.quantityRoleAvailable,
      ownerModule: caps.quantityRoleOwnerModule,
      note: caps.quantityRoleNote,
    },
    {
      key: 'locationPickable',
      label: '库位可拣属性',
      available: caps.locationPickableAvailable,
      ownerModule: caps.locationPickableOwnerModule,
      note: caps.locationPickableNote,
    },
    {
      key: 'lotStatus',
      label: '批次状态',
      available: caps.lotStatusAvailable,
      ownerModule: caps.lotStatusOwnerModule,
      note: caps.lotStatusNote,
    },
    {
      key: 'safetyStock',
      label: '安全缓冲策略',
      available: caps.safetyStockAvailable,
      ownerModule: caps.safetyStockOwnerModule,
      note: caps.safetyStockNote,
    },
    {
      key: 'channelPolicy',
      label: '渠道可售量口径',
      available: caps.channelPolicyAvailable,
      ownerModule: caps.channelPolicyOwnerModule,
      note: caps.channelPolicyNote,
    },
    {
      key: 'expiryByDate',
      label: '按日期的效期剔除',
      available: caps.expiryByDateAvailable,
      ownerModule: caps.expiryByDateOwnerModule,
      note: caps.expiryByDateNote,
    },
    {
      key: 'reservationRelease',
      label: '预占过期自动释放',
      available: caps.reservationReleaseAvailable,
      ownerModule: caps.reservationReleaseOwnerModule,
      note: caps.reservationReleaseNote,
    },
  ];
}

/** 错误文案。403 与 401 要分开说：前者是权限没开，后者是登录过期。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401) return '登录已失效，请重新登录';
    if (e.status === 403) return '当前角色没有查看可售量的权限';
    if (e.status === 400) return '请求参数不被接受（排序键或档位名不合法）';
    return e.message;
  }
  if (e instanceof Error) return e.message;
  return '读取可售量失败，请稍后重试';
}