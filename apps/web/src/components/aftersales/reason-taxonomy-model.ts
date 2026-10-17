import type {
  AftersalesReasonAttributionCreate,
  AftersalesReasonAttributionItem,
  AftersalesReasonAttributionView,
  AftersalesReasonCapabilities,
  AftersalesReasonCategoryCreate,
  AftersalesReasonCategoryUpdate,
  AftersalesReasonMappingCreate,
  AftersalesReasonMappingList,
  AftersalesReasonMappingRow,
  AftersalesReasonReport,
  AftersalesReasonTree,
  AftersalesReasonTreeNode,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 11.1.2.1 售后原因分类前端模型。
 *
 * <p>蓝图：「维护售后原因分类树（质量问题、描述不符、尺寸不符、物流损坏、买家原因、误购、
 * 欺诈退货），支持多选与平台原因码映射，驱动后续的质量归因分析。」
 * 加粗句：「<b>原因口径不统一会让退货率分析失真</b> —— 必须强制选择分类而不是留自由文本。」
 *
 * <p><b>本模型对那句加粗句負责的四件事</b>：
 *
 * <ol>
 *   <li><b>提交前就把非法形态挡在 UI 里</b>：{@link validateAttributionDraft} 拒绝
 *       「一个都没选」「多个主因」「没有主因」「权重为空或越界」四种写法，
 *       并给出与后端**同一套判据**的话术。界面上根本构造不出一个后端会拒绝的请求 ——
 *       这不是为了好看，而是为了让运营看到的报错就是真正的原因，而不是「保存失败」。
 *   <li><b>归因输入框的主因是单选、其它是复选</b>（{@link pickPrimary} /
 *       {@link toggleSecondary}），把「恰好一个主因」这条约束变成交互上的事实，
 *       而不是靠提交时报错。
 *   <li><b>自由文本渲染成「补充说明」而不是「原因」</b>（{@link notePresentation}）：
 *       它挂在归因集合上，界面上必须把它标成次要信息 —— 否则运营会以为写了说明就算归因了，
 *       而后端会拒。
 *   <li><b>不可归因的分类整支灰掉并说明原因</b>（{@link nodePresentation}），
 *       而不是只在勾选时才报错。判定依据全部来自后端的 {@code assignable}
 *       与 {@code notAssignableReason}，<b>前端不自己判断停用</b> ——
 *       「上级停用」这种跨层的规则在前端重写一遍必然与后端漂移。
 * </ol>
 *
 * <p>路径前缀是 {@code trade/aftersales/reasons}，前端路由是 {@code /aftersales/reasons}。
 */

const base = 'trade/aftersales/reasons';

// ================================================================ 取数

export interface AftersalesReasonTreeParams {
  categoryStatus?: string | undefined;
  rootKind?: string | undefined;
  assignableOnly?: boolean | undefined;
}

export function aftersalesReasonTreeQuery(params: AftersalesReasonTreeParams): string {
  const parts: string[] = [];
  const put = (k: string, v?: string) => {
    if (v && v.trim()) parts.push(`${k}=${encodeURIComponent(v.trim())}`);
  };
  put('categoryStatus', params.categoryStatus);
  put('rootKind', params.rootKind);
  if (params.assignableOnly) parts.push('assignableOnly=true');
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchReasonCapabilities() {
  return api<AftersalesReasonCapabilities>(`${base}/capabilities`);
}

export function fetchReasonTree(params: AftersalesReasonTreeParams = {}) {
  return api<AftersalesReasonTree>(`${base}/tree${aftersalesReasonTreeQuery(params)}`);
}

export function fetchReasonAttribution(caseId: string) {
  return api<AftersalesReasonAttributionView>(`${base}/attributions?caseId=${encodeURIComponent(caseId)}`);
}

export function fetchReasonReport() {
  return api<AftersalesReasonReport>(`${base}/reports`);
}

export interface AftersalesReasonMappingParams {
  platformSource?: string | undefined;
  categoryId?: string | undefined;
  mappingStatus?: string | undefined;
}

export function fetchReasonMappings(params: AftersalesReasonMappingParams = {}) {
  const parts: string[] = [];
  const put = (k: string, v?: string) => {
    if (v && v.trim()) parts.push(`${k}=${encodeURIComponent(v.trim())}`);
  };
  put('platformSource', params.platformSource);
  put('categoryId', params.categoryId);
  put('mappingStatus', params.mappingStatus);
  return api<AftersalesReasonMappingList>(`${base}/mappings${parts.length ? `?${parts.join('&')}` : ''}`);
}

// ================================================================ 写入

export function createReasonCategory(payload: AftersalesReasonCategoryCreate) {
  return api<AftersalesReasonTreeNode>(`${base}/categories`, 'POST', payload);
}

export function updateReasonCategory(id: string, payload: AftersalesReasonCategoryUpdate) {
  return api<AftersalesReasonTreeNode>(`${base}/categories/${id}`, 'PATCH', payload);
}

export function deleteReasonCategory(id: string) {
  return api<void>(`${base}/categories/${id}`, 'DELETE');
}

export function submitReasonAttribution(payload: AftersalesReasonAttributionCreate) {
  return api<AftersalesReasonAttributionView>(`${base}/attributions`, 'POST', payload);
}

export function createReasonMapping(payload: AftersalesReasonMappingCreate) {
  return api<AftersalesReasonMappingRow>(`${base}/mappings`, 'POST', payload);
}

export function updateReasonMapping(id: string, payload: AftersalesReasonMappingCreate) {
  return api<AftersalesReasonMappingRow>(`${base}/mappings/${id}`, 'PATCH', payload);
}

export function deleteReasonMapping(id: string) {
  return api<void>(`${base}/mappings/${id}`, 'DELETE');
}

// ================================================================ 归因草稿

/** 界面上正在编辑的一份归因。 */
export interface AftersalesReasonDraftItem {
  categoryId: string;
  categoryCode: string;
  categoryName: string;
  weight: string;
}

export interface AftersalesReasonDraft {
  caseId: string;
  note: string;
  /** 主因恒定只有一个，所以用单值而不是数组 —— 让「两个主因」在类型上就不可能。 */
  primary: AftersalesReasonDraftItem | null;
  secondaries: AftersalesReasonDraftItem[];
}

export function emptyDraft(caseId: string): AftersalesReasonDraft {
  return { caseId, note: '', primary: null, secondaries: [] };
}

/**
 * 把一条已有归因读回成草稿。
 *
 * <p>主因取后端返回的 {@code isPrimary}，**不按排序位置取第一条** ——
 * 后端已经把主因排在最前，但那是展示顺序；按位置取会在后端改了排序规则时静默取错。
 */
export function draftFromAttribution(view: AftersalesReasonAttributionView): AftersalesReasonDraft {
  const draft = emptyDraft(view.caseId);
  if (!view.attributed || !view.current) return draft;
  // ⚠️ 补充说明也要读回草稿：漏了这一句，运营打开一张**已归因**的单时
  //   会看到空白输入框，于是以为「当初没写说明」，一保存就把说明**抹掉**了 ——
  //   而那可能正是复判的理由。
  draft.note = view.current.note ?? '';
  for (const item of view.current.items) {
    const mapped: AftersalesReasonDraftItem = {
      categoryId: item.categoryId,
      categoryCode: item.categoryCode,
      categoryName: item.categoryName,
      weight: String(item.weight),
    };
    if (item.isPrimary) draft.primary = mapped;
    else draft.secondaries.push(mapped);
  }
  return draft;
}

/**
 * 选一个主因。
 *
 * <p>⚠️ 换主因时**原来的主因降级成次因**，而不是被丢掉：
 * 运营改判「主因是质量问题」为「主因是物流损坏」时，前者往往仍然成立
 * （包装确实压坏了内件）—— 直接丢掉会让他不得不重新勾一遍，
 * 而更糟的是：他可能就忘了，于是归因少了一条真实存在的原因。
 */
export function pickPrimary(
  draft: AftersalesReasonDraft,
  node: AftersalesReasonTreeNode,
  weight = '1.000000',
): AftersalesReasonDraft {
  const picked: AftersalesReasonDraftItem = {
    categoryId: node.id,
    categoryCode: node.categoryCode,
    categoryName: node.categoryName,
    weight,
  };
  const previous = draft.primary;
  const secondaries = draft.secondaries.filter((s) => s.categoryId !== node.id);
  if (previous && previous.categoryId !== node.id) {
    secondaries.push(previous);
  }
  return { ...draft, primary: picked, secondaries };
}

/** 增删一个次因。已经是主因的分类不允许被加进次因（否则一个分类既是主因又是次因）。 */
export function toggleSecondary(
  draft: AftersalesReasonDraft,
  node: AftersalesReasonTreeNode,
  weight = '0.500000',
): AftersalesReasonDraft {
  if (draft.primary?.categoryId === node.id) return draft;
  const exists = draft.secondaries.some((s) => s.categoryId === node.id);
  const secondaries = exists
    ? draft.secondaries.filter((s) => s.categoryId !== node.id)
    : [...draft.secondaries, { categoryId: node.id, categoryCode: node.categoryCode, categoryName: node.categoryName, weight }];
  return { ...draft, secondaries };
}

export function setWeight(
  draft: AftersalesReasonDraft,
  categoryId: string,
  weight: string,
): AftersalesReasonDraft {
  const fix = (item: AftersalesReasonDraftItem) =>
    item.categoryId === categoryId ? { ...item, weight } : item;
  return {
    ...draft,
    primary: draft.primary ? fix(draft.primary) : null,
    secondaries: draft.secondaries.map(fix),
  };
}

export function draftItemCount(draft: AftersalesReasonDraft): number {
  return (draft.primary ? 1 : 0) + draft.secondaries.length;
}

export function draftWeightSum(draft: AftersalesReasonDraft): string {
  const all = [...draft.secondaries, ...(draft.primary ? [draft.primary] : [])];
  const sum = all.reduce((acc, item) => acc + (Number(item.weight) || 0), 0);
  return sum.toFixed(6);
}

// ================================================================ 提交前校验

export interface AftersalesReasonValidation {
  ok: boolean;
  /** 第一条阻断原因；null 表示通过。 */
  message: string | null;
  /** 阻断原因的性质，决定界面怎么提示（区别于「只是提醒」）。 */
  severity: 'error' | 'none';
}

/**
 * 提交前的本地校验，判据与后端**逐条对齐**。
 *
 * <p>⚠️ 本地校验**不能替代**后端校验 —— 有人可以直接调接口，
 * 而 DB 的提交期约束触发器才是最后一道。这里做的是让运营**当场**看到原因，
 * 而不是提交后被 400 打回。
 *
 * <p>判据（与 {@code AftersalesReasonService.attribute} 同源）：
 * <ul>
 *   <li>至少一个原因（加粗句的核心：不允许只写自由文本）</li>
 *   <li>恰好一个主因（主次是「归因分析有意义」的前提）</li>
 *   <li>同一个分类不能既是主因又是次因</li>
 *   <li>权重必填且落在 (0, 1]，**不给默认值 1**</li>
 * </ul>
 */
export function validateAttributionDraft(draft: AftersalesReasonDraft): AftersalesReasonValidation {
  if (!draft.primary) {
    return {
      ok: false,
      severity: 'error',
      message:
        '必须选一个主因。原因口径不统一会让退货率分析失真，所以不能只写一段说明文字 —— ' +
        '说明文字是补充，不是归因本身。',
    };
  }
  if (!Number.isFinite(Number(draft.primary.weight)) || Number(draft.primary.weight) <= 0) {
    return { ok: false, severity: 'error', message: '主因必须给权重（0 < weight ≤ 1）' };
  }
  if (Number(draft.primary.weight) > 1) {
    return { ok: false, severity: 'error', message: '主因权重不能大于 1' };
  }
  const seen = new Set<string>([draft.primary.categoryId]);
  for (const s of draft.secondaries) {
    if (seen.has(s.categoryId)) {
      return { ok: false, severity: 'error', message: `「${s.categoryName}」不能既是主因又是次因` };
    }
    seen.add(s.categoryId);
    const w = Number(s.weight);
    if (!Number.isFinite(w) || w <= 0) {
      return { ok: false, severity: 'error', message: `「${s.categoryName}」必须给权重（0 < weight ≤ 1）` };
    }
    if (w > 1) {
      return { ok: false, severity: 'error', message: `「${s.categoryName}」权重不能大于 1` };
    }
  }
  return { ok: true, severity: 'none', message: null };
}

/** 把草稿转成后端入参（校验通过之后才调用）。 */
export function toAttributionCreate(draft: AftersalesReasonDraft): AftersalesReasonAttributionCreate {
  const items: AftersalesReasonAttributionItem[] = [];
  if (draft.primary) {
    items.push({
      categoryId: draft.primary.categoryId,
      isPrimary: true,
      weight: Number(draft.primary.weight),
    });
  }
  for (const s of draft.secondaries) {
    items.push({ categoryId: s.categoryId, isPrimary: false, weight: Number(s.weight) });
  }
  return { caseId: draft.caseId, note: draft.note, items };
}

// ================================================================ 展示口径

/**
 * 分类节点的展示口径。
 *
 * <p>⚠️ {@code assignable} 与 {@code notAssignableReason} **全部来自后端**：
 * 「上级已停用」是跨层的规则，祖先停用时子节点本身还是 ACTIVE ——
 * 前端拿本地数据判断必然漏掉这一层，而漏掉的后果是「运营在一个已作废的口径上继续归因」。
 */
export interface AftersalesReasonNodePresentation {
  /** 缩进层级，前端直接用 style 决定左边距。 */
  level: number;
  /** 树形前缀字符，让层级在纯文本视图里也读得出来。 */
  prefix: string;
  tone: 'assignable' | 'disabled' | 'blocked';
  /** 不可选时的中文原因；可选时为空串。 */
  hint: string;
  /** 编码路径，鼠标悬停可见 —— 它是报表与下游对齐用的稳定锚点。 */
  codePath: string;
}

export function nodePresentation(node: AftersalesReasonTreeNode): AftersalesReasonNodePresentation {
  const level = Math.max(node.depth - 1, 0);
  const tone = node.assignable
    ? 'assignable'
    : node.categoryStatus === 'DISABLED'
      ? 'disabled'
      : 'blocked';
  return {
    level,
    prefix: `${'　'.repeat(level)}${level > 0 ? '└ ' : ''}`,
    tone,
    hint: node.notAssignableReason ?? '',
    codePath: node.codePath,
  };
}

/** 自由文本的展示口径：**必须**被标成补充说明，不能被当成「原因」。 */
export function notePresentation(note: string): { label: string; text: string; muted: boolean } {
  const text = note.trim();
  return text
    ? { label: '补充说明（非归因本身）', text, muted: true }
    : { label: '补充说明（可留空）', text: '', muted: true };
}

/** 权重合计的提示。权重是相对值，**不要求**合计为 1，所以只提示不阻断。 */
export function weightSumHint(sum: number | string): { tone: 'ok' | 'warn'; text: string } {
  const n = Number(sum);
  if (!Number.isFinite(n)) return { tone: 'warn', text: '权重合计无法解析' };
  if (n === 0) return { tone: 'warn', text: '权重合计 0：请给每个原因一个权重' };
  if (Math.abs(n - 1) < 1e-9) return { tone: 'ok', text: '权重合计 1.000000' };
  return { tone: 'warn', text: `权重合计 ${n.toFixed(6)}（权重是相对值，不要求合计为 1）` };
}

/** 报表行的占比文案。⚠️ 分母为 0 时**显示 0.00%** 而不是「—」。 */
export function shareText(percent: number | string): string {
  const n = Number(percent);
  return Number.isFinite(n) ? `${n.toFixed(2)}%` : '—';
}

/**
 * 把分类树按层级顺序渲染成扁平行。
 *
 * <p>⚠️ 直接用后端返回的顺序（{@code depth, sort_order, category_code, id}），
 * <b>不在前端重新排序</b>：排序键唯一确定这件事是后端保证的，
 * 前端再排一遍就多了一处可能与后端不一致的地方。
 */
export function flattenTree(tree: AftersalesReasonTree): AftersalesReasonTreeNode[] {
  return [...tree.nodes].sort((a, b) => {
    if (a.depth !== b.depth) return a.depth - b.depth;
    if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder;
    return a.categoryCode.localeCompare(b.categoryCode);
  });
}

/** 当前可被归因的分类（后端已经算好 assignable，前端只做过滤）。 */
export function assignableNodes(tree: AftersalesReasonTree): AftersalesReasonTreeNode[] {
  return tree.nodes.filter((n) => n.assignable);
}
