import type {
  CategoryInput,
  CategoryMoveInput,
  CategoryNameInput,
  CategoryNode,
  CategoryOverview,
  CategorySaved,
  CategoryTree,
  CategoryTreeInput,
  SpuCategoryAssignment,
  SpuCategoryInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.3.1.1 分类树管理前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li>**「直接商品数」和「子树商品数」不能画成一个数字**。前者是主分类恰好挂在这一节点的 SPU 数，
 *       后者含所有子孙。把商品挂到子类目时，前者不变、后者增加——界面上只显示一个的话，
 *       「把商品挪到子类目，为什么这个类目的数字没变」会变成查不到的问题。
 *   <li>**停用不等于删除**。停用后类目从树里过滤掉，但它身上的属性要求与允许规格轴还在
 *       （那些规则是既有商品录入校验的依据），已挂的商品也不摘。所以节点上要能看到
 *       「停用后仍挂着多少件商品、多少条规则」，否则运营会以为停用等于清空。
 *   <li>**树是独立实体**。内部管理分类与各平台类目映射是**多棵独立的树**，维护节奏完全不同。
 *       界面上必须显式切换树，不能把所有类目铺在一棵树上让人分不清「这是内部类目还是平台类目」。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export function fetchOverview() {
  return api<CategoryOverview>('catalog/categories/overview');
}

export function fetchTrees() {
  return api<CategoryTree[]>('catalog/categories/trees');
}

export function createTree(input: CategoryTreeInput) {
  return api<CategoryTree>('catalog/categories/trees', 'POST', input);
}

export function updateTree(id: string, input: CategoryTreeInput) {
  return api<CategoryTree>(`catalog/categories/trees/${id}`, 'PUT', input);
}

export function deleteTree(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/categories/trees/${id}?version=${version}`, 'DELETE');
}

export function fetchNodes(treeId: string, includeDisabled = false) {
  const query = includeDisabled ? '?includeDisabled=true' : '';
  return api<CategoryNode[]>(`catalog/categories/trees/${treeId}/nodes${query}`);
}

export function createNode(treeId: string, input: CategoryInput) {
  return api<CategorySaved>(`catalog/categories/trees/${treeId}/nodes`, 'POST', input);
}

export function updateNode(id: string, input: CategoryInput) {
  return api<CategorySaved>(`catalog/categories/nodes/${id}`, 'PUT', input);
}

/**
 * 拖拽落点。**只传父节点与位置**，不带上名称与启用状态——
 * 那两个字段可能正在被别人编辑，拖拽不该顺带把它们提交。
 */
export function moveNode(id: string, input: CategoryMoveInput) {
  return api<CategorySaved>(`catalog/categories/nodes/${id}/move`, 'PUT', input);
}

export function setNodeEnabled(id: string, enabled: boolean) {
  return api<{ enabled: boolean }>(
    `catalog/categories/nodes/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

export function deleteNode(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/categories/nodes/${id}?version=${version}`, 'DELETE');
}

export function fetchSpuCategories(spuId: string) {
  return api<SpuCategoryAssignment>(`catalog/categories/spus/${spuId}`);
}

export function saveSpuCategories(spuId: string, input: SpuCategoryInput) {
  return api<SpuCategoryAssignment>(`catalog/categories/spus/${spuId}`, 'PUT', input);
}

// ------------------------------------------------------------ 错误语义

/** 404：记录不存在或当前角色没有访问权限——RLS 已过滤过跨租户的行，不区分两者。 */
export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁冲突、编码重复、类目上有商品不能删、防环被拒。界面必须说明发生了什么。 */
export function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

/** 400：规则层的业务拒绝（防环、跨树挂载、挂到停用类目下）。后端已给出可读原因，原样显示。 */
export function errorText(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback;
}

// ------------------------------------------------------------ 词表与校验（与后端 CategoryTreeRules 同口径）

/** 树种类：内部管理分类 / 平台类目映射。蓝图明确要求这是多棵独立的树。 */
export const TREE_KINDS = [
  { value: 'INTERNAL', label: '内部管理分类' },
  { value: 'PLATFORM', label: '平台类目映射' },
] as const;

/** 平台编码候选：平台树必须指明平台，与 02 渠道与店铺的平台词表同口径。 */
export const PLATFORMS = ['AMAZON', 'SHOPIFY', 'EBAY', 'TIKTOK', 'ALI1688'] as const;

export function treeKindLabel(kind: string) {
  return TREE_KINDS.find((k) => k.value === kind)?.label ?? kind;
}

/** 树标题：平台树带上平台名，否则界面上「Amazon 类目」和「内部类目」会长得一模一样。 */
export function treeTitle(tree: Pick<CategoryTree, 'name' | 'treeKind' | 'platformCode'>) {
  return tree.treeKind === 'PLATFORM' && tree.platformCode
    ? `${tree.name}（${tree.platformCode}）`
    : tree.name;
}

/** 分类编码规范化：与后端 CategoryTreeRules.normalizeCode 同口径。 */
export function normalizeCode(value: string) {
  return value.trim().toUpperCase();
}

/** 分类编码校验。返回 null 表示可用；否则给出可执行的原因。 */
export function codeProblem(value: string) {
  const code = normalizeCode(value);
  if (!code) return '分类编码不能为空';
  if (code.length > 64) return '分类编码最多 64 个字符';
  if (!/^[A-Z0-9][A-Z0-9_-]{0,63}$/.test(code))
    return '分类编码请使用英文字母、数字、下划线或短横线，长度 1~64 位';
  return null;
}

export function nameProblem(value: string) {
  const name = value.trim();
  if (!name) return '分类名称不能为空';
  if (name.length > 100) return '分类名称最多 100 个字符';
  return null;
}

/** 平台树必须带平台、内部树必须不带：与后端 platformProblem 同口径。 */
export function platformProblem(treeKind: string, platformCode: string) {
  const platform = platformCode.trim();
  if (treeKind === 'PLATFORM') {
    if (!platform) return '平台类目树必须指明所属平台（如 AMAZON / SHOPIFY），否则刊登侧取不到';
    return null;
  }
  return platform ? '内部管理分类不指明平台：平台编码只属于平台类目映射树' : null;
}

export function normalizeLocale(value: string) {
  return value.trim().toLowerCase();
}

/** 单条多语言名校验。返回 null 表示可用。 */
export function localeNameProblem(locale: string, name: string) {
  const key = normalizeLocale(locale);
  const value = name.trim();
  if (!key) return '语言标识不能为空（如 zh-CN / en-US）';
  if (!/^[a-z]{2,3}(-[a-z0-9]{2,8})?$/.test(key))
    return '语言标识请使用 BCP 47 形式，如 zh-CN / en-US / ja';
  if (!value) return '多语言名的内容不能为空';
  if (value.length > 200) return '多语言名的内容最多 200 个字符';
  return null;
}

/**
 * 多语言名集合校验：逐条合法 + 同一个语言不重复 + 数量上限。
 *
 * <p>「同一个语言两条」不在这里挡住的话，保存是成功的，界面上却看不出哪条生效了。
 */
export function namesProblem(names: readonly CategoryNameInput[]) {
  if (names.length === 0) return null;
  if (names.length > 20) return '每个分类最多配置 20 个语言';
  const seen = new Set<string>();
  for (const entry of names) {
    if (!entry) return '多语言名不能有空项';
    const problem = localeNameProblem(entry.locale, entry.name);
    if (problem) return problem;
    const key = normalizeLocale(entry.locale);
    if (seen.has(key)) return `语言「${key}」配置了两条：同一个语言只能有一个名称`;
    seen.add(key);
  }
  return null;
}

/**
 * 一个节点在界面上的展示状态。
 *
 * <p>停用的节点默认不出现在树里；勾了「显示已停用」之后它会带着一条明显的停用标记回来，
 * 并且仍然显示商品数与规则数——因为停用**没有**清空它们。
 */
export type NodeTone = 'active' | 'disabled' | 'branch';

/**
 * 节点计数文案。
 *
 * <p>三个计数含义完全不同，分开写而不是拼成一句「共 N 件」：
 * 直接 / 子树 / 辅助，混在一起会让「挪到子类目后数字没变」变成一个查不到的问题。
 */
export function nodeCounts(
  node: Pick<CategoryNode, 'directProductCount' | 'subtreeProductCount' | 'secondaryProductCount'>,
) {
  return {
    direct: node.directProductCount,
    subtree: node.subtreeProductCount,
    secondary: node.secondaryProductCount,
    /** 子树里有商品但直接挂 0 件：提示「这个类目下的商品都在子类目里」。 */
    inherited: node.directProductCount === 0 && node.subtreeProductCount > 0,
  };
}

/** 该节点上还有多少条规则（属性要求 + 允许规格轴）——决定停用它会影响多少录入校验。 */
export function ruleImpact(node: Pick<CategoryNode, 'attributeRuleCount' | 'specAxisRuleCount'>) {
  return node.attributeRuleCount + node.specAxisRuleCount;
}

/**
 * 把扁平节点列表按 parentId 装配成树。
 *
 * <p>后端已经按 path_ids 排好序返回，前端只需要按 parentId 归组。
 * 找不到父亲的节点一律挂到根下（而不是丢弃）：那说明数据有问题，
 * 但界面上把节点显示出来比悄悄少显示几个更容易被察觉。
 */
export interface CategoryBranch {
  node: CategoryNode;
  children: CategoryBranch[];
}

export function buildTree(nodes: readonly CategoryNode[]): CategoryBranch[] {
  const byParent = new Map<string | null, CategoryNode[]>();
  for (const node of nodes) {
    const key = node.parentId ?? null;
    const bucket = byParent.get(key);
    if (bucket) bucket.push(node);
    else byParent.set(key, [node]);
  }
  const build = (parent: string | null, depth: number): CategoryBranch[] =>
    (byParent.get(parent) ?? [])
      .sort((a, b) => a.position - b.position || a.code.localeCompare(b.code))
      .map((node) => ({ node, children: build(node.id, depth + 1) }));
  const roots = build(null, 1);
  // 兜底：parentId 指向了本次结果里不存在的节点（停用过滤或数据问题），挂在根下不丢弃。
  const known = new Set(nodes.map((n) => n.id));
  for (const node of nodes) {
    if (node.parentId && !known.has(node.parentId) && !roots.some((r) => r.node.id === node.id)) {
      roots.push({ node, children: build(node.id, 2) });
    }
  }
  return roots;
}

/** 树的节点总数（含各级），用于概览里的「本树共 N 个类目」。 */
export function countNodes(branches: readonly CategoryBranch[]): number {
  return branches.reduce((sum, b) => sum + 1 + countNodes(b.children), 0);
}

/** 树的最大深度（根为 1），空树为 0。 */
export function maxDepth(branches: readonly CategoryBranch[]): number {
  return branches.reduce((deepest, b) => Math.max(deepest, 1 + maxDepth(b.children)), 0);
}

/**
 * 一个落点能不能作为某节点的父节点：不能是它自己，也不能是它的子孙。
 *
 * <p>与后端 CategoryTreeService.isDescendant / V14 触发器同一条口径，
 * 但**界面上必须先挡一次**：让用户把节点拖到非法位置、松手后才弹一句
 * 「不能把分类挂到它自己的子孙节点下」，是最糟糕的拖拽体验。
 */
export function moveProblem(
  nodeId: string,
  targetParentId: string | null,
  branches: readonly CategoryBranch[],
): string | null {
  if (targetParentId === nodeId) return '不能把分类挂到它自己下面';
  if (!targetParentId) return null;
  const all = flattenNodes(branches);
  if (!all.some((n) => n.id === targetParentId)) return '落点不在当前这棵树里';
  if (descendantsOf(nodeId, branches).some((n) => n.id === targetParentId))
    return '不能把分类挂到它自己的子孙节点下：这会让分类树形成环';
  return null;
}

/** 摊平整棵树（自己 + 所有子孙），保持装配顺序。 */
export function flattenNodes(branches: readonly CategoryBranch[]): CategoryNode[] {
  return branches.flatMap((b) => [b.node, ...flattenNodes(b.children)]);
}

/** 某节点的全部子孙（不含自己）。 */
export function descendantsOf(nodeId: string, branches: readonly CategoryBranch[]): CategoryNode[] {
  const find = (list: readonly CategoryBranch[]): CategoryBranch | undefined => {
    for (const branch of list) {
      if (branch.node.id === nodeId) return branch;
      const hit = find(branch.children);
      if (hit) return hit;
    }
    return undefined;
  };
  const branch = find(branches);
  if (!branch) return [];
  const out: CategoryNode[] = [];
  const walk = (list: readonly CategoryBranch[]) => {
    for (const b of list) {
      out.push(b.node);
      walk(b.children);
    }
  };
  walk(branch.children);
  return out;
}

/** 树为空时的引导文案：区分「还没建树」与「树建了但还没有类目」，下一步动作完全不同。 */
export function emptyHint(treeCount: number, kind: 'INTERNAL' | 'PLATFORM') {
  return treeCount === 0
    ? kind === 'INTERNAL'
      ? '还没有内部管理分类树。建一棵之后才能维护商品类目。'
      : '还没有平台类目映射树。建一棵并指明平台之后，才能维护该平台的类目对应关系。'
    : '这棵树还没有分类。先建一个根类目，再往下挂子类目。';
}
