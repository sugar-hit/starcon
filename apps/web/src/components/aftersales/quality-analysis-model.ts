/**
 * 11.5.2.1 质量分析的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「质量问题<b>专项分析</b>：缺陷类型分布、批次质量趋势、
 * 供应商质量对比、缺陷照片聚类、<b>支持开箱率与质量成本估算</b>。」
 * 输出：「<b>质量报表 + 责任分布 + 改进清单</b>。」
 * 加粗句：「<b>质量成本（退货物流 + 损耗 + 差评影响）应量化</b>，否则改进动力不足。」
 *
 * <b>这一层对那条加粗句负四件事</b>：
 *
 * <ol>
 *   <li><b>成本分项必须带依据标签</b>：{@link COST_BASIS} 的三值
 *       （实测 / 按参数估算 / 参数未登记）在界面上<b>逐项渲染</b>，
 *       「参数未登记」渲染成「未登记」而不是把金额画成 0 ——
 *       画成 0 的话，没登记参数这件事就在界面上彻底消失了。</li>
 *   <li><b>未登记项不参与合计</b>：{@link costTotalOf} 跳过
 *       {@code NOT_REGISTERED} 项，并在 {@link costTotalOf} 的返回值里带上
 *       被排除的项数，让「这个合计少算了什么」在界面上说得清。</li>
 *   <li><b>开箱率必须和覆盖率并排</b>：{@link openBoxReading} 把这两个数合成一句话，
 *       覆盖率低于 100% 时明确说「只代表已登记的那部分」——
 *       只显示一个开箱率数字，读的人无从知道它有多完整。</li>
 *   <li><b>照片聚类不提供「相似度」入参</b>：{@link photoClusterReading} 只描述
 *       「哪些照片被反复上传」，不描述「外观相似的缺陷有多少类」——
 *       本仓没有图像库、没有模型、没有视觉服务接入点。</li>
 * </ol>
 */
import type {
  CostCurrencyBreakdown,
  CostParameterCreate,
  CostParameterPage,
  DefectDistributionReport,
  DefectObservationCreate,
  DefectObservationPage,
  DefectObservationVoid,
  ImprovementReport,
  LotTrendReport,
  OpenBoxReport,
  PhotoClusterReport,
  QualityAnalysisCapabilities,
  QualityCostReport,
  QualityOverview,
  SupplierComparisonReport,
  SupplierRefPage,
  SupplierRefStatus,
  SupplierRefUpsert,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/quality-analysis} 同前缀。 */
const base = 'trade/quality-analysis';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) {
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

/**
 * 把筛选对象编成 query string。
 *
 * ⚠️ 空串与 undefined 都要跳过：传一个空 `stage=` 会让后端收到
 * 「按空环节筛选」而不是「不筛环节」，两种读法在列表页上表现完全不同。
 */
function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    search.set(key, String(value));
  }
  const suffix = search.toString();
  return suffix ? `?${suffix}` : '';
}

// ================================================================ 枚举标签

/** 缺陷维度（逐字对齐 11.2.3.1 的检项维度）。 */
export const DIMENSION_LABELS: Record<string, string> = {
  APPEARANCE: '外观',
  FUNCTION: '功能',
  ACCESSORY: '配件',
  PACKAGING: '包装',
};

/**
 * 发现环节。**顺序就是时间先后**，界面上也按这个顺序渲染。
 *
 * ⚠️ 刻意**没有** `NEGATIVE_REVIEW`（差评）这个取值：本仓全库没有商品评价表，
 * 造一个「买家给了差评」的发现环节就是在声称我们读到过买家评价。
 */
export const DISCOVERY_STAGE_LABELS: Record<string, string> = {
  BUYER_UNBOXING: '买家开箱即发现',
  DELIVERY_SIGNATURE: '签收时发现',
  WAREHOUSE_INTAKE: '入库开箱发现',
  POST_USE: '使用后发现',
};

/** 严重度（分四档，改进清单要按它排序）。 */
export const SEVERITY_LABELS: Record<string, string> = {
  MINOR: '轻微',
  MODERATE: '一般',
  MAJOR: '严重',
  CRITICAL: '致命',
};

/**
 * 成本分项的依据等级 —— 界面上<b>每一项</b>都要带这个标签。
 *
 * ⚠️ 三值的语气刻意不同：实测是正向（这是账），
 * 估算是中性的（这是算法），未登记是<b>警告</b>（这是缺数据，不是「没问题」）。
 */
export const COST_BASIS: Record<string, { label: string; tone: Tone; hint: string }> = {
  MEASURED: {
    label: '实测',
    tone: 'success',
    hint: '库里真有这两个数，相减即得。',
  },
  ESTIMATED: {
    label: '按参数估算',
    tone: 'warning',
    hint: '库里没有金额列，用租户自维护的单价乘件数算出来。',
  },
  NOT_REGISTERED: {
    label: '参数未登记',
    tone: 'danger',
    hint: '算不出来 —— 不是 0。已排除在合计之外。',
  },
};

export type Tone = 'success' | 'warning' | 'danger' | 'neutral';

// ================================================================ 纯展示派生

/** 依据等级 → 语气；未知取值一律给 neutral（不猜）。 */
export function costBasisTone(basis: string | null | undefined): Tone {
  if (!basis) return 'neutral';
  return COST_BASIS[basis]?.tone ?? 'neutral';
}

/** 依据等级 → 中文标签。 */
export function costBasisLabel(basis: string | null | undefined): string {
  if (!basis) return '未知';
  return COST_BASIS[basis]?.label ?? basis;
}

/**
 * 合计里被排除的项数 → 一句话。
 *
 * ⚠️ 「排除 2 项」必须显式说出来：一个看不出自己少算了什么的合计，
 * 会被当成「质量一共就花了这么多钱」，而这正是那条加粗句要防的后果。
 */
export function excludedCostNote(excluded: number): string {
  if (excluded <= 0) return '三项全部可计价，合计已含实测与估算。';
  const names = excluded === 1 ? '一项' : `${excluded} 项`;
  return `合计**排除了 ${names}未登记的估算项** —— 报出来的数字是下限，不是全貌。`;
}

/**
 * 开箱率 + 覆盖率 → 一句话。
 *
 * ⚠️ 覆盖率 100% 才是完整的开箱率；40% 时这个数只代表「已登记的那 40%」，
 * 剩下的沉默**不能**被读成「没开箱问题」。
 */
export function openBoxReading(
  rate: number | null | undefined,
  coverage: number | null | undefined,
): { text: string; tone: Tone } {
  const pct = rate ?? 0;
  const cov = coverage ?? 0;
  if (cov >= 100) {
    return {
      text: `开箱率 ${pct.toFixed(2)}%（覆盖率 100%，口径完整）`,
      tone: pct >= 20 ? 'danger' : 'success',
    };
  }
  if (cov <= 0) {
    return {
      text: '开箱率 **暂无口径**：一条缺陷观察都还没登记，现在报出来的 0.00% 只是「没数据」。',
      tone: 'neutral',
    };
  }
  return {
    text: `开箱率 ${pct.toFixed(2)}% —— 但**覆盖率只有 ${cov.toFixed(2)}%**，这个数只代表已登记的那一部分。`,
    tone: 'warning',
  };
}

/**
 * 缺陷类型分布 → 占比读数。
 *
 * ⚠️ 分母是 FAIL 检项条数；占比 0.00 表示「这一档没有失败检项」，
 * 而**不是**「占比算不出来」。
 */
export function defectShareReading(share: number | null | undefined, total: number): string {
  const pct = share ?? 0;
  if (total <= 0) return '暂无失败检项';
  return `${pct.toFixed(2)}%（占全部 ${total} 条失败检项）`;
}

/**
 * 供应商名称的显示值。
 *
 * ⚠️ **未登记时显示「未登记供应商」而不是把 uuid 当名字**：一个 36 位 uuid
 * 看起来像供应商名，运营会照着它去联系一个根本不存在的供应商。
 */
export function supplierDisplayName(supplierName: string | null | undefined): string {
  const name = supplierName?.trim();
  return name ? name : '未登记供应商';
}

/**
 * 照片聚类的读法 —— 逐字说清「我们到底算了什么」。
 *
 * ⚠️ 绝不能写成「外观相似的缺陷有多少类」：本仓无图像库、无模型、
 * 无第三方视觉服务接入点，聚类键是 sha256 **完全相同**。
 */
export function photoClusterReading(cluster: {
  photoCount: number;
  attributionCount: number;
  repeated: boolean;
}): string {
  const times = `${cluster.photoCount} 张照片在 ${cluster.attributionCount} 张归因单上`;
  return cluster.repeated
    ? `${times}重复出现（**同一份字节被反复上传**，指纹完全相同）`
    : `${times}出现（指纹相同；本仓不做图像相似度，所以这不等于「看起来像」）`;
}

/** 发现环节 → 语气：只有「买家开箱即发现」是危险色，其余是中性。 */
export function discoveryStageTone(stage: string | null | undefined): Tone {
  return stage === 'BUYER_UNBOXING' ? 'danger' : 'neutral';
}

/** 严重度 → 语气（致命 / 严重为危险色）。 */
export function severityTone(severity: string | null | undefined): Tone {
  if (severity === 'CRITICAL' || severity === 'MAJOR') return 'danger';
  if (severity === 'MODERATE') return 'warning';
  return 'neutral';
}

/** 非可再销售率 → 语气（≥ 20% 危险、≥ 5% 警告）。 */
export function rateTone(percent: number | null | undefined): Tone {
  const value = percent ?? 0;
  if (value >= 20) return 'danger';
  if (value >= 5) return 'warning';
  return 'success';
}

/** 金额格式化：保留两位小数；null 显示为「未登记」而不是 0。 */
export function formatMoney(amount: number | string | null | undefined): string {
  if (amount === null || amount === undefined || amount === '') return '未登记';
  return Number(amount).toFixed(2);
}

/** 百分比格式化。 */
export function formatPercent(percent: number | string | null | undefined): string {
  if (percent === null || percent === undefined) return '—';
  return `${Number(percent).toFixed(2)}%`;
}

// ================================================================ 端点

export function fetchCapabilities(): Promise<QualityAnalysisCapabilities> {
  return call(`${base}/capabilities`);
}

export function fetchOverview(params: {
  from?: string;
  to?: string;
  skuCode?: string;
}): Promise<QualityOverview> {
  return call(`${base}/overview${query(params)}`);
}

export function fetchDefectDistribution(params: {
  from?: string;
  to?: string;
  skuCode?: string;
  limit?: number;
}): Promise<DefectDistributionReport> {
  return call(`${base}/defect-distribution${query(params)}`);
}

export function fetchLotTrend(params: {
  from?: string;
  to?: string;
  granularity?: string;
  batchNo?: string;
}): Promise<LotTrendReport> {
  return call(`${base}/lot-trend${query(params)}`);
}

export function fetchSupplierComparison(params: {
  from?: string;
  to?: string;
  limit?: number;
}): Promise<SupplierComparisonReport> {
  return call(`${base}/supplier-comparison${query(params)}`);
}

export function fetchPhotoClusters(params: {
  minClusterSize?: number;
  limit?: number;
}): Promise<PhotoClusterReport> {
  return call(`${base}/photo-clusters${query(params)}`);
}

export function fetchOpenBoxRate(params: {
  from?: string;
  to?: string;
  limit?: number;
}): Promise<OpenBoxReport> {
  return call(`${base}/open-box-rate${query(params)}`);
}

export function fetchQualityCost(params: {
  from?: string;
  to?: string;
}): Promise<QualityCostReport> {
  return call(`${base}/quality-cost${query(params)}`);
}

export function fetchImprovements(params: {
  stage?: string;
  lotCode?: string;
  page?: number;
  size?: number;
}): Promise<ImprovementReport> {
  return call(`${base}/improvements${query(params)}`);
}

export function fetchObservations(params: {
  inspectionId?: string;
  stage?: string;
  dimension?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<DefectObservationPage> {
  return call(`${base}/defect-observations${query(params)}`);
}

/**
 * 登记缺陷观察。
 *
 * ⚠️ 请求体里**没有** `observationStatus`：建行恒为 RECORDED，
 * 作废走 {@link voidObservation} 一个入口。
 */
export function recordObservation(request: DefectObservationCreate): Promise<DefectObservationRowLike> {
  return call(`${base}/defect-observations`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** 作废缺陷观察（终态：业务列冻结，判错了请另记一条）。 */
export function voidObservation(
  id: string,
  voidReason: string,
  version: number,
): Promise<DefectObservationRowLike> {
  const payload: DefectObservationVoid = { voidReason, version };
  return call(`${base}/defect-observations/${id}/void`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** 结构性占位：真实行类型由 codegen 契约提供，这里只需要 id / 版本 / 状态。 */
type DefectObservationRowLike = {
  id: string;
  version: number;
  observationStatus: string;
};

export function fetchCostParameters(params: {
  status?: string;
  page?: number;
  size?: number;
}): Promise<CostParameterPage> {
  return call(`${base}/cost-parameters${query(params)}`);
}

/** 建成本参数（建行恒为生效，且不预设截止日）。 */
export function createCostParameter(request: CostParameterCreate): Promise<{
  id: string;
  version: number;
}> {
  return call(`${base}/cost-parameters`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** 成本参数换版（终态：老版本保留为已换版）。 */
export function supersedeCostParameter(
  id: string,
  supersedeReason: string,
  version: number,
): Promise<{ id: string; version: number }> {
  return call(`${base}/cost-parameters/${id}/supersede`, {
    method: 'POST',
    body: JSON.stringify({ supersedeReason, version }),
  });
}

export function fetchSupplierRefs(params: {
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<SupplierRefPage> {
  return call(`${base}/supplier-refs${query(params)}`);
}

/**
 * 登记 / 更新供应商引用。
 *
 * ⚠️ 界面上**没有删除按钮**：停用是留痕的、删除不是。
 */
export function upsertSupplierRef(request: SupplierRefUpsert): Promise<{ id: string }> {
  return call(`${base}/supplier-refs`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export function setSupplierRefStatus(
  id: string,
  request: SupplierRefStatus,
): Promise<{ id: string }> {
  return call(`${base}/supplier-refs/${id}/status`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** 一个币种的合计（跳过未登记项）—— 与后端的排除规则逐字一致。 */
export function costTotalOf(breakdown: CostCurrencyBreakdown): {
  total: number;
  excluded: number;
} {
  const items = [breakdown.loss, breakdown.returnLogistics, breakdown.reviewImpact];
  let total = 0;
  let excluded = 0;
  for (const item of items) {
    if (item.basis === 'NOT_REGISTERED' || item.amount === null || item.amount === undefined) {
      excluded += 1;
      continue;
    }
    total += Number(item.amount);
  }
  return { total, excluded };
}
