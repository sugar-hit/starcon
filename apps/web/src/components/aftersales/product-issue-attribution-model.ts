/**
 * 11.1.2.2 商品问题归因的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「把质量问题<b>归因到具体环节</b>（生产、运输、仓储、描述），
 * 支持<b>批次追溯</b>与<b>照片比对</b>，形成对供应商或流程的<b>改进建议</b>。」
 * 输出：「<b>归因结论 + 批次质量标记</b>。」
 * 加粗句：「<b>批次归因是「同一个批次退回来 10 件」和「10 个批次各退 1 件」的区别</b>，
 * 前者是供应商责任，后者可能是运输或买家因素。」
 *
 * <b>这一层对那条加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>照片比对不提供「相似度」入参</b>：{@link comparePhotos} 的请求体里
 *       <b>没有</b> {@code compareResult} —— 结果由后端按两张照片的 sha256
 *       <b>真</b>比对得出。本仓没有图像库、没有模型、没有视觉服务接入点，
 *       前端自己编一个相似度滑块就是在冒充图像识别能力。</li>
 *   <li><b>批次质量标记的统计列是只读的</b>：{@link LotFlagRow} 的
 *       {@code suspectReturnCount} / {@code affectedReturnQty} / {@code stageBreakdown}
 *       由后端 + DB 触发器重算，前端<b>没有</b>一个「填计数」的输入框 ——
 *       一个可编辑的计数等于把「这批次坏了几件」交给每个操作员自己编。</li>
 *   <li><b>改进建议是必填且人写的</b>：{@link concludeAttribution} 把
 *       {@code improvementSuggestion} 与理由、环节一起提交。
 *       界面上它是textarea 而不是「AI 生成」按钮 —— 本仓没有模型接入点。</li>
 * </ol>
 */
import type {
  AttributionConclude,
  AttributionCreate,
  AttributionDetail,
  AttributionPage,
  AttributionRow,
  AttributionSupersede,
  LotFlagPage,
  LotFlagRefresh,
  LotFlagRow,
  PhotoCompareRequest,
  PhotoRegister,
  PhotoRegisterRequest,
  ProductAttributionCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/product-attribution} 同前缀。 */
const base = 'trade/product-attribution';

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

/** 归因单列表。 */
export function fetchAttributions(params: {
  page?: number;
  size?: number;
  status?: string;
  stage?: string;
  skuCode?: string;
  lotId?: string;
  keyword?: string;
  sort?: string;
}): Promise<AttributionPage> {
  return call(`${base}/attributions${query(params)}`);
}

/** 归因单全景：头 + 照片 + 比对 + 批次标记 + 留痕 + 可执行动作。 */
export function fetchAttribution(id: string): Promise<AttributionDetail> {
  return call(`${base}/attributions/${id}`);
}

/** 建归因单（恒为草稿）。批次由后端经 warehouse 授权端点真查并写快照。 */
export function createAttribution(request: AttributionCreate): Promise<AttributionDetail> {
  return call(`${base}/attributions`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** 登记 / 替换照片（整单替换）。只提交**对象键 + 内容指纹**，不传二进制。 */
export function registerPhotos(
  id: string,
  photos: PhotoRegister[],
): Promise<AttributionDetail> {
  const payload: PhotoRegisterRequest = { photos };
  return call(`${base}/attributions/${id}/photos`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

/**
 * 照片比对（**内容指纹**口径）。
 *
 * ⚠️ 这里刻意**不接受** {@code compareResult}：结果由后端按两张照片的 sha256 真比对得出。
 * 一个前端能指定的「相似度」或「结果」等于绕过那条纪律。
 */
export function comparePhotos(
  id: string,
  leftPhotoId: string,
  rightPhotoId: string,
  comparisonNote?: string,
): Promise<AttributionDetail> {
  const payload: PhotoCompareRequest = { leftPhotoId, rightPhotoId, comparisonNote: comparisonNote ?? '' };
  return call(`${base}/attributions/${id}/photos/compare`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/**
 * 出归因结论：**环节 + 理由 + 改进建议三者缺一不可**。
 *
 * ⚠️ 不传版本号 —— 归因单还是草稿，不存在并发改判；终态之后只能走 supersede 留痕。
 */
export function concludeAttribution(
  id: string,
  request: AttributionConclude,
): Promise<AttributionDetail> {
  return call(`${base}/attributions/${id}/attribute`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** 改判：原单标记已取代留痕，另起一版直接归因。 */
export function supersedeAttribution(
  id: string,
  request: AttributionSupersede,
): Promise<AttributionDetail> {
  return call(`${base}/attributions/${id}/supersede`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** 批次质量标记列表（输出句第二样）。 */
export function fetchLotFlags(params: {
  flagStatus?: string;
  skuCode?: string;
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<LotFlagPage> {
  return call(`${base}/lot-flags${query(params)}`);
}

/** 单个批次的质量标记。 */
export function fetchLotFlag(lotId: string): Promise<LotFlagRow> {
  return call(`${base}/lot-flags/by-lot${query({ lotId })}`);
}

/**
 * 刷新批次质量标记。
 *
 * ⚠️ 请求体里**没有** suspectReturnCount / affectedReturnQty / stageBreakdown：
 * 那三列是统计结论，由后端聚合 + DB 触发器重算比对，传输层根本收不到。
 */
export function refreshLotFlag(
  attributionId: string,
  flagNote?: string,
  flagStatus?: string,
): Promise<LotFlagRow> {
  const payload: LotFlagRefresh = { flagNote: flagNote ?? '', flagStatus: flagStatus ?? '' };
  return call(`${base}/attributions/${attributionId}/lot-flag/refresh`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** 能力声明：下游是否已交付、哪些动作被刻意挡住、边界在哪。 */
export function fetchCapabilities(): Promise<ProductAttributionCapabilities> {
  return call(`${base}/capabilities`);
}

// ==========================================================================
// 纯展示派生（单测直接覆盖这几条）
// ==========================================================================

/** 归因环节六值的展示名。与后端 {@code AttributionStage.label()} 逐字一致。 */
export const STAGE_LABELS: Record<string, string> = {
  PRODUCTION: '生产',
  TRANSPORT: '运输',
  WAREHOUSE: '仓储',
  DESCRIPTION: '描述',
  BUYER_FACTOR: '买家因素',
  UNDETERMINED: '待定',
};

/** 环节的**诚实出口**：待定与买家因素不是失败态，界面不该把它们标成「异常」。 */
export const HONEST_STAGES = new Set(['UNDETERMINED', 'BUYER_FACTOR']);

/**
 * 环节徽标的语气。
 *
 * ⚠️ 「待定」给中性色而不是危险色：把它标成红色等于告诉运营「你还没干活」，
 * 而「责任待定」是一个**合法**的结论 —— 逼着人编一个环节才会让结论失真。
 */
export function stageTone(stage: string | null): 'neutral' | 'success' | 'warning' {
  if (!stage) return 'neutral';
  if (stage === 'UNDETERMINED') return 'warning';
  if (HONEST_STAGES.has(stage)) return 'neutral';
  return 'success';
}

/** 环节是否必须挂批次（生产责任）。 */
export function stageRequiresLot(stage: string): boolean {
  return stage === 'PRODUCTION';
}

/** 批次标记状态的展示语气。 */
export function flagTone(status: string): 'neutral' | 'warning' | 'danger' {
  if (status === 'CONFIRMED') return 'danger';
  if (status === 'SUSPECT') return 'warning';
  return 'neutral';
}

/**
 * 主导环节的展示文案。
 *
 * ⚠️ 六个环节**平票**时返回「责任分散」而不是随便挑一个 ——
 * 「一半生产一半运输」本身就是一个需要人看的结论，挑一个会把这个信息抹掉。
 */
export function dominantStageText(
  breakdown:
    | {
        production: number;
        transport: number;
        warehouse: number;
        description: number;
        buyerFactor: number;
        undetermined: number;
      }
    | undefined,
): string {
  if (!breakdown) return '—';
  const pairs: Array<[string, number]> = [
    ['PRODUCTION', breakdown.production],
    ['TRANSPORT', breakdown.transport],
    ['WAREHOUSE', breakdown.warehouse],
    ['DESCRIPTION', breakdown.description],
    ['BUYER_FACTOR', breakdown.buyerFactor],
    ['UNDETERMINED', breakdown.undetermined],
  ];
  const total = pairs.reduce((sum, [, count]) => sum + count, 0);
  if (total === 0) return '—';
  const max = Math.max(...pairs.map(([, count]) => count));
  const winners = pairs.filter(([, count]) => count === max);
  if (winners.length > 1) return '责任分散';
  return STAGE_LABELS[winners[0]![0]] ?? winners[0]![0];
}

/** 环节分布里六个环节的完整列表（缺键补 0 —— 空白会被读成「没有数据」而不是「真的是 0」）。 */
export function stageBreakdownRows(
  breakdown:
    | {
        production: number;
        transport: number;
        warehouse: number;
        description: number;
        buyerFactor: number;
        undetermined: number;
      }
    | undefined,
): Array<{ stage: string; label: string; count: number }> {
  if (!breakdown) {
    return Object.keys(STAGE_LABELS).map((stage) => ({
      stage,
      label: STAGE_LABELS[stage]!,
      count: 0,
    }));
  }
  return [
    { stage: 'PRODUCTION', label: '生产', count: breakdown.production },
    { stage: 'TRANSPORT', label: '运输', count: breakdown.transport },
    { stage: 'WAREHOUSE', label: '仓储', count: breakdown.warehouse },
    { stage: 'DESCRIPTION', label: '描述', count: breakdown.description },
    { stage: 'BUYER_FACTOR', label: '买家因素', count: breakdown.buyerFactor },
    { stage: 'UNDETERMINED', label: '待定', count: breakdown.undetermined },
  ];
}

/**
 * 归因单能否出结论的**本地**预检。
 *
 * ⚠️ 这只是提前告诉操作员「还差什么」，**不是**校验 ——
 * 后端与 DB 触发器各判一次，本地预检全绿而后端拒的情况依然存在，
 * 所以 UI 永远要把后端的错误消息原样显示出来。
 */
export function concludeBlockers(
  stage: string,
  note: string,
  suggestion: string,
  hasLot: boolean,
): string[] {
  const blockers: string[] = [];
  if (!stage) blockers.push('必须选归因环节');
  if (stageRequiresLot(stage) && !hasLot) {
    blockers.push('归因到「生产」必须挂批次：没有批次就指不到某一次投料');
  }
  if (!note.trim()) blockers.push('必须写归因理由');
  if (!suggestion.trim()) blockers.push('必须写改进建议（职责句要求的输出）');
  return blockers;
}

/** 列表行的主标识。 */
export function rowKey(row: AttributionRow): string {
  return row.id;
}
