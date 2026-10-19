/**
 * 11.2.3.1 质检与判定的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「退回商品质检：外观、功能、配件完整性、是否可再销售；
 * 给出可再销售/残次/报废判定与残值估算。」
 * 输出：「质检结论 + 残值金额 + <b>质量状态指令</b>。」
 * 加粗句：「<b>可再销售判定要保守</b>：包装破损但商品完好可判可再销售，缺少配件则应判残次。」
 *
 * <b>这一层对那条加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>不提供任何「执行处置 / 入账 / 索赔」的调用</b>：{@link concludeInspection} 的请求体里
 *       <b>没有</b>残值字段（后端传输层也没有），前端<b>自己也没造一个</b> ——
 *       残值是财务数据，一个可填的金额等于把损耗口径交给每个调用方自己编。
 *       {@link concludeInspection} 只提交「结论 + 理由 + 版本」。</li>
 *   <li><b>判定是三值枚举，不是「是否可售」的勾选框</b>：{@link fetchVerdictPreview} 把
 *       <b>能判成什么、不能判成什么、不能的理由</b>一并取回来，
 *       渲染成带 {@code allowed} 标记的选项而不是一个自由下拉 ——
 *       质检员不该在这里猜规则，规则要自己说出来。</li>
 *   <li><b>包装与商品状态是两组独立读数</b>（{@link QualityPendingRow.packagingCondition} /
 *       {@link QualityPendingRow.goodsCondition}）：「外箱损毁 + 商品完好」是蓝图点名的合法组合，
 *       合成一个下拉框就表达不了它。</li>
 * </ol>
 */
import type {
  QualityCategoryCreate,
  QualityCategoryPage,
  QualityCategoryRow,
  QualityCategoryUpdate,
  QualityInspectionCancel,
  QualityInspectionConclude,
  QualityInspectionCreate,
  QualityInspectionDetail,
  QualityInspectionPage,
  QualityInspectionUpdate,
  QualityPendingPage,
  QualitySkuMappingCreate,
  QualityTemplateCreate,
  QualityTemplateDetail,
  QualityTemplatePage,
  QualityTemplateUpdate,
  QualityVerdictPreview,
  ReturnQualityCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/return-quality} 同前缀。 */
const base = 'trade/return-quality';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  //   所以这里按有没有 body 分成两种完整对象，而不是合并一个可能半空的。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) {
    // ApiError 的签名是 (status, message, body)。
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

/**
 * 把筛选对象编成 query string。
 *
 * ⚠️ 空串与 undefined 都要跳过：传一个空 `status=` 会让后端收到
 * 「按空状态筛选」而不是「不筛状态」，两种读法在列表页上表现完全不同。
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

// ==========================================================================
// 能力声明
// ==========================================================================

export const fetchQualityCapabilities = () =>
  call<ReturnQualityCapabilities>(`${base}/capabilities`);

// ==========================================================================
// 质检类目（租户自维护的作业口径，**不是**商品主数据）
// ==========================================================================

export const fetchQualityCategories = (params: {
  page?: number;
  size?: number;
  status?: string;
  keyword?: string;
} = {}) => call<QualityCategoryPage>(`${base}/categories${query(params)}`);

export const createQualityCategory = (body: QualityCategoryCreate) =>
  call<QualityCategoryRow>(`${base}/categories`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const updateQualityCategory = (id: string, body: QualityCategoryUpdate) =>
  call<QualityCategoryRow>(`${base}/categories/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

/** 把一个 SKU 归到质检类目。拿不到类目就没有模板 —— 所以这一格不能省。 */
export const mapQualitySku = (id: string, body: QualitySkuMappingCreate) =>
  call<void>(`${base}/categories/${id}/skus`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// 质检模板（按类目取用）
// ==========================================================================

export const fetchQualityTemplates = (params: {
  page?: number;
  size?: number;
  categoryId?: string;
  status?: string;
  keyword?: string;
  sort?: string;
} = {}) => call<QualityTemplatePage>(`${base}/templates${query(params)}`);

export const fetchQualityTemplate = (id: string) =>
  call<QualityTemplateDetail>(`${base}/templates/${id}`);

export const createQualityTemplate = (body: QualityTemplateCreate) =>
  call<QualityTemplateDetail>(`${base}/templates`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const updateQualityTemplate = (id: string, body: QualityTemplateUpdate) =>
  call<QualityTemplateDetail>(`${base}/templates/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

/** 建模板 ≠ 启用模板：草稿期间口径还可以改。 */
export const activateQualityTemplate = (id: string) =>
  call<QualityTemplateDetail>(`${base}/templates/${id}/activate`, { method: 'POST' });

/** 停用是**口径变更**（不是删除）：历史质检单要能复现当时的口径。 */
export const retireQualityTemplate = (id: string) =>
  call<QualityTemplateDetail>(`${base}/templates/${id}/retire`, { method: 'POST' });

// ==========================================================================
// 待检队列与判定预览
// ==========================================================================

/** 待检队列：**现在有多少件货等着我判**，不是「一共入了多少」。 */
export const fetchQualityQueue = (params: {
  page?: number;
  size?: number;
  skuCode?: string;
} = {}) => call<QualityPendingPage>(`${base}/queue${query(params)}`);

/**
 * 判定预览：提交之前就告诉质检员「能判成什么、不能判成什么、残值大概多少」。
 *
 * ⚠️ 残值是**试算**，最终值以判定那一刻 DB 触发器按同一个式子重算为准。
 */
export const fetchVerdictPreview = (postingId: string) =>
  call<QualityVerdictPreview>(
    `${base}/verdict-preview${query({ postingId })}`,
  );

// ==========================================================================
// 质检单
// ==========================================================================

export const fetchQualityInspections = (params: {
  page?: number;
  size?: number;
  status?: string;
  verdict?: string;
  categoryId?: string;
  templateId?: string;
  receiptId?: string;
  skuCode?: string;
  keyword?: string;
  sort?: string;
} = {}) => call<QualityInspectionPage>(`${base}/inspections${query(params)}`);

export const fetchQualityInspection = (id: string) =>
  call<QualityInspectionDetail>(`${base}/inspections/${id}`);

/**
 * 开质检单。
 *
 * ⚠️ 请求体里**刻意只有** postingId 与 templateId：数量、SKU、币种、残值
 * 都由 DB 触发器从待检分录与订单行取，传进来也传不进。
 */
export const createQualityInspection = (body: QualityInspectionCreate) =>
  call<QualityInspectionDetail>(`${base}/inspections`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const recordQualityResults = (id: string, body: QualityInspectionUpdate) =>
  call<QualityInspectionDetail>(`${base}/inspections/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

/**
 * 判定。
 *
 * ⚠️ **请求体里没有残值字段**：残值由「原值 × 生效系数」算出后写库，
 * 再由 DB 触发器重算比对。前端**不预填、不编辑**残值 —— 它只显示服务端算出来的试算值。
 */
export const concludeInspection = (id: string, body: QualityInspectionConclude) =>
  call<QualityInspectionDetail>(`${base}/inspections/${id}/conclude`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const cancelQualityInspection = (id: string, body: QualityInspectionCancel) =>
  call<QualityInspectionDetail>(`${base}/inspections/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// 纯函数：视图与测试共用的判定口径
// ==========================================================================

/** 判定结论的可选值（**三值枚举，不是布尔勾选框**）。 */
export const VERDICT_OPTIONS = [
  { code: 'RESALABLE', label: '可再销售' },
  { code: 'DEFECTIVE', label: '残次' },
  { code: 'SCRAPPED', label: '报废' },
] as const;

/** 逐项结果的三值。**刻意没有「未检」** —— 未检就是这一行还不存在。 */
export const CHECK_OUTCOME_OPTIONS = [
  { code: 'PASS', label: '合格' },
  { code: 'FAIL', label: '不合格' },
  { code: 'NOT_APPLICABLE', label: '不适用' },
] as const;

/** 检查维度：外观 / 功能 / 配件完整性 / 包装。 */
export const DIMENSION_OPTIONS = [
  { code: 'APPEARANCE', label: '外观' },
  { code: 'FUNCTION', label: '功能' },
  { code: 'ACCESSORY', label: '配件完整性' },
  { code: 'PACKAGING', label: '包装' },
] as const;

/**
 * 该维度失败是否**禁止**判可再销售。
 *
 * ⚠️ 与后端 {@code QualityDimension.blocksResale()} 同一份口径，但前端**也独立存一份**：
 * 质检员在录逐项结果时就要看见「这一项挂了，可再销售就选不了了」，
 * 等提交被拒才知道规则，录检项的整个过程都是逆行的。
 */
export function blocksResale(dimension: string | null | undefined): boolean {
  return dimension === 'FUNCTION' || dimension === 'ACCESSORY';
}

/**
 * 失败项**禁止**的结论集合。
 *
 * ⚠️ 这是蓝图那句加粗句的前端镜像，而且必须是**镜像而不是转发**：
 * 后端 {@code verdictOptions} 会连同 `blockedReason` 一起给回来（那是权威口径），
 * 本函数只服务于「还在录检项、还没拉预览」的中间态 ——
 * 那时后端还答不了「这张单能判成什么」，页面不能因此显示成「随便选」。
 */
export function blockedVerdicts(dimensions: Array<string | null | undefined>): string[] {
  const list = dimensions.filter((d): d is string => !!d);
  if (list.some((d) => blocksResale(d))) {
    // 功能坏了：可再销售被禁（残次 / 报废仍可选）
    return ['RESALABLE'];
  }
  return [];
}

/**
 * 判定按钮是否可点。理由**必填** —— 按钮变灰而不给理由会让人以为系统坏了。
 */
export function concludeBlockedReason(detail: {
  head?: { inspectionStatus?: string | null } | null;
  mandatoryMissingCount?: number | null;
  verdictOptions?: Array<{ code: string; allowed: boolean }> | null;
}): string | null {
  const status = detail.head?.inspectionStatus ?? null;
  if (status === 'CONCLUDED') return '已判定：结论与质量状态指令已生成，是终态不可逆（判错只能作废重判）';
  if (status === 'CANCELLED') return '已撤销：内容与逐项结果是证据不是草稿';
  const missing = Number(detail.mandatoryMissingCount ?? 0);
  if (missing > 0) return `还有 ${missing} 个强制检项没有结果（漏检是质检事故最常见的来源）`;
  const options = detail.verdictOptions ?? [];
  if (options.length > 0 && options.every((o) => !o.allowed)) {
    return '当前检项结果下没有任何一个结论被允许（配件缺失只能判残次）';
  }
  return null;
}

/** 逐项结果的草稿态（本地编辑用，与后端 recordResults 的载荷同形状）。 */
export type CheckDraft = {
  templateItemId: string;
  checkOutcome: string;
  checkNote: string;
  photoId: string | null;
};

/** 由模板检项初始化一份草稿：默认全部「合格」，强制项先铺满而不是留空。 */
export function initialDrafts(
  items: Array<{
    id: string;
    itemRequired?: boolean | null;
  }>,
): CheckDraft[] {
  return items.map((i) => ({
    templateItemId: i.id,
    checkOutcome: 'PASS',
    checkNote: '',
    photoId: null,
  }));
}

/** 草稿里不合格且没写说明的检项 —— 这些提交后会被后端拒（判残次要拿去索赔）。 */
export function failsMissingNote(drafts: CheckDraft[]): CheckDraft[] {
  return drafts.filter((d) => d.checkOutcome === 'FAIL' && !d.checkNote.trim());
}

/** 当前可点 / 不可点的动作（来自后端，前端只做渲染分组）。 */
export function actionable(actions: Array<{ allowed: boolean }> | null | undefined) {
  return (actions ?? []).filter((a) => a.allowed);
}

/** 明确不可点的动作 —— 页面要把**归属模块**一起显示出来。 */
export function unavailableActions(actions: Array<{ allowed: boolean }> | null | undefined) {
  return (actions ?? []).filter((a) => !a.allowed);
}

/** 留痕按发生时刻排序展示（后端已排序，这里只做兜底）。 */
export function sortEvents<T extends { occurredAt: string }>(events: T[] | null | undefined): T[] {
  return [...(events ?? [])].sort((a, b) =>
    a.occurredAt === b.occurredAt ? 0 : a.occurredAt < b.occurredAt ? -1 : 1,
  );
}
