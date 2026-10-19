import { api } from '../inventory/api';
import type {
  CollectListingResult,
  ListingCollectCapabilities,
  ListingCollectDraftRow,
  ListingCollectKeyValue,
  ListingCollectPage,
  ListingCollectSourceRow,
  ListingCollectSummaryRow,
  ListingCollectTaskRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 4.4.1.1 链接采集 前端模型。
 *
 * 蓝图：「粘贴外部商品链接（1688/淘宝/供应商站/同行 listing），抓取标题、主图、规格、属性、描述等，生成采集草稿供认领为内部商品。输入：商品
 * URL（支持平台白名单）；抓取任务。输出：采集到的商品草稿（图片下载入媒体库）；来源 URL 与抓取时间。关系：上游 = 外部页面；下游 =
 * 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。<b>采集只产生草稿</b>，进入正式商品库必须走 03 的建档流程并补全属性与合规信息。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **采集只产生草稿**。页面不出现「发布」「上架」「建档」按钮，也不出现价格 / 库存输入框 ——
 *    采集品要进商品库得走 4.4.1.3 采集箱 → 3.1.1.1 建档，页面给出的是**未交付段明账**。
 * 2. **图片只记 URL**。图片区显示的是**外部图片地址**（可点开），并明写「未下载入媒体库」。
 * 3. **白名单外要明确说原因**。服务端返回 400 时把**原文**显示出来（哪个域名不在白名单、
 *    当前允许哪些来源），而不是笼统的「采集失败」。
 * 4. **去重要看得见**。命中同 URL 去重时页面明说「已采过，直接返回既有草稿（未再次请求来源站点）」。
 * 5. **失败要能重试**。失败态显示服务端给的**失败原因**，并给出重试按钮（重试 = 再发同一个 URL）。
 */

/** 采集结果状态的取值域（与后端 `ListingCollectModels.STATES` 一致）。 */
export const COLLECT_STATES = ['SUCCEEDED', 'FAILED'] as const;

export type CollectState = (typeof COLLECT_STATES)[number];

export type {
  CollectListingResult,
  ListingCollectCapabilities,
  ListingCollectDraftRow,
  ListingCollectKeyValue,
  ListingCollectPage,
  ListingCollectSourceRow,
  ListingCollectSummaryRow,
  ListingCollectTaskRow,
};

// ================================================================ 取数

export async function fetchCollectCapabilities(): Promise<ListingCollectCapabilities> {
  return api<ListingCollectCapabilities>('channel/listing-collects/capabilities');
}

export async function fetchCollects(
  state?: string,
  sourceCode?: string,
  page = 0,
  size = 50,
): Promise<ListingCollectPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (state) query.set('state', state);
  if (sourceCode) query.set('sourceCode', sourceCode);
  return api<ListingCollectPage>(`channel/listing-collects?${query.toString()}`);
}

export async function fetchCollect(taskId: string): Promise<CollectListingResult> {
  return api<CollectListingResult>(`channel/listing-collects/${taskId}`);
}

export async function createCollect(url: string, note = ''): Promise<CollectListingResult> {
  return api<CollectListingResult>('channel/listing-collects', 'POST', { url, note });
}

// ================================================================ 纯函数（可单测）

/**
 * 🔴 客户端侧的链接预检：只挡**明显不合法**的输入，不代替服务端的白名单判定。
 *
 * <p>白名单是**服务端的权威**（租户与运维都可能改），前端预检只是为了少发一次无谓请求。
 * 真正的判定永远在服务端 —— 前端说"可以采"不代表能采。
 */
export function precheckUrl(raw: string): string | null {
  const value = raw.trim();
  if (!value) return '请粘贴商品链接';
  if (!/^https?:\/\//i.test(value)) return '链接必须以 http:// 或 https:// 开头';
  try {
    const url = new URL(value);
    if (!url.hostname) return '链接缺少主机名';
  } catch {
    return '链接格式不正确';
  }
  return null;
}

/** 抓取状态文案。 */
export function collectStateLabel(state: string): string {
  switch (state) {
    case 'SUCCEEDED':
      return '采集成功';
    case 'FAILED':
      return '采集失败';
    default:
      return state;
  }
}

export function collectStateTone(state: string): 'success' | 'warning' | 'neutral' {
  if (state === 'SUCCEEDED') return 'success';
  if (state === 'FAILED') return 'warning';
  return 'neutral';
}

/**
 * 🔴 失败态的提示：把服务端给的**失败原因原文**显示出来。
 *
 * <p>不加工、不吞掉、不换成"采集失败"四个字 —— 服务端已经写清了是 404、是限频、
 * 还是页面里没有商品信息，运营要按这个去处理。
 */
export function failureReason(task: ListingCollectTaskRow | null): string {
  if (!task) return '';
  if (task.state !== 'FAILED') return '';
  return task.errorMessage || '采集失败（服务端未给出原因）';
}

/** 🔴 失败态是否可以重试：白名单拒绝与限频可以重试，404 之类重试也没用但无害。 */
export function canRetry(task: ListingCollectTaskRow | null): boolean {
  if (!task) return false;
  return task.state === 'FAILED';
}

/**
 * 🔴 去重命中的提示。
 *
 * <p>去重是**服务端**判的（同 URL 已有成功记录 ⇒ 不再请求来源站点），
 * 前端必须把这件事说出来 —— 否则运营会以为"我明明点了一次采集，怎么没反应"。
 */
export function deduplicatedNote(result: CollectListingResult | null): string {
  if (!result || !result.deduplicated) return '';
  return '🔴 这个链接已经采过（规范化后是同一个页面），已直接返回既有采集草稿，**没有再次请求来源站点**。';
}

/** 抓取时刻的界面文案。 */
export function capturedAtLabel(capturedAt: string | null): string {
  if (!capturedAt) return '—';
  const date = new Date(capturedAt);
  if (Number.isNaN(date.getTime())) return capturedAt;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 键值项列表 → 便于渲染的 Map（同名键后者覆盖前者，与页面显示一致）。 */
export function keyValueMap(items: ListingCollectKeyValue[] | null | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const item of items ?? []) {
    if (!item?.name) continue;
    out[item.name] = item.value;
  }
  return out;
}

/** 键值项列表 → 渲染用的行（过滤掉空键与空值）。 */
export function keyValueRows(items: ListingCollectKeyValue[] | null | undefined) {
  return (items ?? [])
    .filter((item) => item && item.name && item.value)
    .map((item) => ({ name: item.name, value: item.value }));
}

/**
 * 🔴 外部页面原文标价的界面措辞。
 *
 * <p>**不是我们的售价** —— 售价归 05 定价（整卷未交付）。页面必须写清这一点，
 * 否则运营会把来源站的报价当成自己的定价直接用。
 */
export function sourcePriceLabel(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未抓到';
  const value = String(amount);
  return currency ? `${value} ${currency}` : value;
}

/** 🔴 图片区的说明：只记 URL，没下载。 */
export const IMAGE_URL_ONLY_NOTE =
  '🔴 图片**只记录外部地址**（未下载二进制、未入媒体库）—— 媒体库归 03 catalog，跨服务写表被禁止。';

/** 🔴 草稿边界的说明：采集只产生草稿。 */
export const DRAFT_ONLY_NOTE =
  '🔴 采集**只产生草稿**：这里没有 SPU / SKU、不是平台 listing、也没有我方售价与库存。'
  + '进正式商品库要走 4.4.1.3 采集箱 → 3.1.1.1 商品建档，并补全属性与合规信息。';

/** 未交付的维度（媒体库 / 采集箱 / 批量采集 / 认领 / 售价 / 库存）。 */
export function unavailableDimensions(caps: ListingCollectCapabilities | null) {
  if (!caps) return [];
  return caps.dimensions.filter((d) => !d.available);
}

/**
 * 白名单里可采集的域名（页面提示用）。
 *
 * <p>后端返回的 {@code sources} **只含启用中的行**（停用的平台在服务端就被过滤掉了，
 * 不该出现在"你可以采这些"的提示里）。
 */
export function allowedHosts(caps: ListingCollectCapabilities | null): string[] {
  if (!caps) return [];
  return caps.sources.map((s) => s.host);
}

/** 白名单行的展示文案。 */
export function sourceKindLabel(kind: string): string {
  switch (kind) {
    case 'PLATFORM':
      return '平台';
    case 'SUPPLIER':
      return '供应商站';
    case 'PEER':
      return '同行站';
    default:
      return kind;
  }
}

/** 空态文案：还没有采集历史时显示什么。 */
export function emptyCollectsMessage(): string {
  return '还没有采集记录。粘贴一个白名单内的商品链接（1688 / 淘宝 / 天猫 / 供应商站等），'
    + '点「开始采集」就会真的去抓那个页面。';
}

/** 空态文案：白名单为空时显示什么（运维没配白名单也是一种状态）。 */
export function emptyAllowlistMessage(caps: ListingCollectCapabilities | null): string {
  if (caps && allowedHosts(caps).length === 0) {
    return '🔴 当前平台白名单是空的，一个来源都不可采。请运维在平台白名单（listing_collect_source）中登记来源域名。';
  }
  return '';
}

/** 采集历史里"这是第几次尝试"的文案。 */
export function attemptLabel(attemptNo: number): string {
  if (attemptNo <= 1) return '首次采集';
  return `第 ${attemptNo} 次尝试`;
}
