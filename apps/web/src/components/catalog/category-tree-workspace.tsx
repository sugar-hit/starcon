'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  ChevronDown,
  ChevronRight,
  ClipboardCheck,
  Languages,
  Layers,
  Package,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  TriangleAlert,
} from 'lucide-react';
import type {
  CategoryNameInput,
  CategoryNode,
  CategoryOverview,
  CategoryTree,
} from '../../../../../packages/api-contracts/src/catalog';
import { Field, control } from '../common/fields';
import {
  PLATFORMS,
  TREE_KINDS,
  buildTree,
  codeProblem,
  createNode,
  createTree,
  deleteNode,
  deleteTree,
  emptyHint,
  errorText,
  fetchNodes,
  fetchOverview,
  fetchTrees,
  countNodes,
  maxDepth,
  moveNode,
  moveProblem,
  nameProblem,
  namesProblem,
  nodeCounts,
  normalizeCode,
  platformProblem,
  ruleImpact,
  setNodeEnabled,
  treeTitle,
  updateNode,
  type CategoryBranch,
} from './category-tree-model';

/**
 * 3.3.1.1 分类树管理工作区。
 *
 * <p>页面按「先选树、再管节点」的顺序排：树列表（含新建）→ 概览 → 当前树的节点树。
 * 概览放在节点树之前而不是之后，因为「本租户有几棵树、多少类目、多少商品还没有分类」
 * 决定了运营该不该先补类目，再往下才是具体维护。
 *
 * <p>拖拽用「选落点 + 移动」两步而不是 HTML5 drag-and-drop：原生 DnD 在触屏上不可用，
 * 而且拖拽中途没有「这个落点非法」的反馈，松手才报错体验很差。这里改成
 * 先选目标父节点与位置，非法落点当场挡住（moveProblem），与服务层和 V14 触发器同一口径。
 */
export function CategoryTreeWorkspace() {
  const [overview, setOverview] = useState<CategoryOverview | null>(null);
  const [trees, setTrees] = useState<CategoryTree[]>([]);
  const [nodes, setNodes] = useState<CategoryNode[]>([]);
  const [treeId, setTreeId] = useState('');
  const [includeDisabled, setIncludeDisabled] = useState(false);
  const [ready, setReady] = useState(false);
  const [loadingNodes, setLoadingNodes] = useState(false);
  const [error, setError] = useState('');
  const [nodeError, setNodeError] = useState('');
  const [notice, setNotice] = useState('');
  const [treeForm, setTreeForm] = useState(false);
  const [editing, setEditing] = useState<CategoryNode | 'new-root' | null>(null);
  const [moving, setMoving] = useState<CategoryNode | null>(null);

  const current = trees.find((t) => t.id === treeId) ?? null;
  const branches = useMemo(() => buildTree(nodes), [nodes]);

  const loadTrees = useCallback(async () => {
    try {
      setError('');
      const [nextOverview, nextTrees] = await Promise.all([fetchOverview(), fetchTrees()]);
      setOverview(nextOverview);
      setTrees(nextTrees);
      setTreeId((current) => current || nextTrees[0]?.id || '');
    } catch (e) {
      setError(errorText(e, '分类树加载失败'));
    } finally {
      setReady(true);
    }
  }, []);

  const loadNodes = useCallback(async () => {
    if (!treeId) {
      setNodes([]);
      return;
    }
    setLoadingNodes(true);
    try {
      setNodeError('');
      setNodes(await fetchNodes(treeId, includeDisabled));
    } catch (e) {
      setNodeError(errorText(e, '分类节点加载失败'));
      setNodes([]);
    } finally {
      setLoadingNodes(false);
    }
  }, [treeId, includeDisabled]);

  useEffect(() => {
    void loadTrees();
  }, [loadTrees]);
  useEffect(() => {
    void loadNodes();
  }, [loadNodes]);

  async function refresh() {
    await loadTrees();
    await loadNodes();
  }

  return (
    <div className="space-y-5">
      <SectionCard
        title="分类树"
        description="分类是多棵独立的树：内部管理分类与各平台类目映射的维护节奏完全不同，不能混成一棵。"
        extra={
          <>
            <Link href="/products/categories/requirements">
              <Button variant="ghost">
                <ClipboardCheck /> 类目属性要求
              </Button>
            </Link>
            <Button variant="ghost" onClick={() => void refresh()}>
              <RefreshCw /> 刷新
            </Button>
            <Button onClick={() => setTreeForm((v) => !v)}>
              <Plus /> 新建树
            </Button>
          </>
        }
      >
        {!ready ? (
          <p className="text-muted-foreground py-6 text-sm">正在加载分类树…</p>
        ) : error ? (
          <p role="alert" className="text-danger py-6 text-sm">
            {error}
          </p>
        ) : trees.length === 0 ? (
          <EmptyState title="还没有分类树" description={emptyHint(0, 'INTERNAL')} />
        ) : (
          <div className="flex flex-wrap gap-2">
            {trees.map((tree) => (
              <button
                key={tree.id}
                type="button"
                onClick={() => setTreeId(tree.id)}
                aria-pressed={tree.id === treeId}
                className={
                  tree.id === treeId
                    ? 'border-brand bg-brand-subtle text-copy-13 flex h-9 items-center gap-2 rounded-md border px-3 font-medium'
                    : 'hover:bg-muted text-copy-13 text-muted-foreground flex h-9 items-center gap-2 rounded-md border px-3'
                }
              >
                <Layers className="h-4 w-4" />
                {treeTitle(tree)}
                {tree.treeKind === 'PLATFORM' ? (
                  <Badge tone="info">{tree.treeKind === 'PLATFORM' ? '平台' : '内部'}</Badge>
                ) : null}
                {!tree.enabled ? <Badge tone="neutral">已停用</Badge> : null}
              </button>
            ))}
          </div>
        )}

        {treeForm ? (
          <TreeForm
            onCancel={() => setTreeForm(false)}
            onSaved={async (id) => {
              setTreeForm(false);
              await loadTrees();
              setTreeId(id);
              setNotice('分类树已创建');
            }}
          />
        ) : null}
      </SectionCard>

      {overview ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="分类树"
            value={overview.treeCount}
            comparisonLabel={`内部 ${overview.internalTreeCount} / 平台 ${overview.platformTreeCount}`}
          />
          <StatCard
            label="分类节点"
            value={overview.categoryCount}
            comparisonLabel={`已停用 ${overview.disabledCategoryCount}`}
          />
          <StatCard label="最深层级" value={overview.maxDepth} comparisonLabel="根为第 1 层" />
          <StatCard
            label="没有分类的商品"
            value={overview.uncategorizedSpuCount}
            polarity="negative"
            comparisonLabel={`共 ${overview.productCount} 件商品`}
          />
        </div>
      ) : null}

      {overview && overview.uncategorizedSpuCount > 0 ? (
        <p className="text-muted-foreground bg-muted rounded-md px-3 py-2 text-xs">
          有 <b className="text-warning">{overview.uncategorizedSpuCount}</b> 件商品既没有主分类、
          也没有历史类目文本。它们不会出现在任何类目的商品数统计里， 3.1.1.1 的类目筛选与 13
          分析的品类口径都会漏掉它们。
        </p>
      ) : null}

      <SectionCard
        title={current ? treeTitle(current) : '分类节点'}
        description="拖拽调整层级与同级排序、防环、启用停用都在这里。停用不等于删除：规则与已挂商品都会保留。"
        extra={
          current ? (
            <>
              <label className="text-copy-13 text-muted-foreground flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={includeDisabled}
                  onChange={(e) => setIncludeDisabled(e.target.checked)}
                />
                显示已停用
              </label>
              <Button onClick={() => setEditing('new-root')}>
                <Plus /> 新建根类目
              </Button>
            </>
          ) : null
        }
      >
        {!current ? (
          <EmptyState
            title="先选一棵树"
            description="内部管理分类与平台类目映射是独立的树，节点挂在各自的树上，不会互相混用。"
          />
        ) : loadingNodes ? (
          <p className="text-muted-foreground py-6 text-sm">正在加载分类节点…</p>
        ) : nodeError ? (
          <p role="alert" className="text-danger py-6 text-sm">
            {nodeError}
          </p>
        ) : branches.length === 0 ? (
          <EmptyState
            title="这棵树还没有分类"
            description={emptyHint(trees.length, current.treeKind as 'INTERNAL' | 'PLATFORM')}
          />
        ) : (
          <ul className="space-y-1">
            {branches.map((branch) => (
              <NodeRow
                key={branch.node.id}
                branch={branch}
                depth={1}
                treeId={current.id}
                includeDisabled={includeDisabled}
                onEdit={setEditing}
                onMove={setMoving}
                onChanged={refresh}
                onError={setNodeError}
              />
            ))}
          </ul>
        )}

        {notice ? <p className="text-success mt-3 text-sm">{notice}</p> : null}
        {nodeError && branches.length > 0 ? (
          <p role="alert" className="text-danger mt-3 text-sm">
            {nodeError}
          </p>
        ) : null}
      </SectionCard>

      {editing ? (
        <NodeForm
          node={editing === 'new-root' ? null : editing}
          treeId={current?.id ?? ''}
          nodes={nodes}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await refresh();
            setNotice('分类已保存');
          }}
        />
      ) : null}

      {moving ? (
        <MoveForm
          node={moving}
          treeId={current?.id ?? ''}
          branches={branches}
          onClose={() => setMoving(null)}
          onSaved={async () => {
            setMoving(null);
            await refresh();
            setNotice('分类已移动');
          }}
          onError={setNodeError}
        />
      ) : null}
    </div>
  );
}

// ------------------------------------------------------------ 树表单

function TreeForm({
  onCancel,
  onSaved,
}: {
  onCancel: () => void;
  onSaved: (id: string) => void | Promise<void>;
}) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [kind, setKind] = useState<string>('INTERNAL');
  const [platform, setPlatform] = useState('AMAZON');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const problem =
    codeProblem(code) ??
    nameProblem(name) ??
    platformProblem(kind, kind === 'PLATFORM' ? platform : '');

  async function submit() {
    setBusy(true);
    try {
      setError('');
      const saved = await createTree({
        code: normalizeCode(code),
        name: name.trim(),
        treeKind: kind,
        platformCode: kind === 'PLATFORM' ? platform : null,
        enabled: true,
        version: 0,
      });
      await onSaved(saved.id);
    } catch (e) {
      setError(errorText(e, '分类树创建失败'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="bg-muted mt-4 space-y-3 rounded-md p-3">
      <p className="text-sm font-medium">新建分类树</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="树编码" hint="租户内唯一；平台树建议用平台名（AMAZON / SHOPIFY）。">
          <input className={control} value={code} onChange={(e) => setCode(e.target.value)} />
        </Field>
        <Field label="树名称">
          <input className={control} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field
          label="树种类"
          hint="平台类目映射树的节点供 04 刊登做类目映射，维护节奏与内部类目不同。"
        >
          <select className={control} value={kind} onChange={(e) => setKind(e.target.value)}>
            {TREE_KINDS.map((k) => (
              <option key={k.value} value={k.value}>
                {k.label}
              </option>
            ))}
          </select>
        </Field>
        {kind === 'PLATFORM' ? (
          <Field label="所属平台" hint="平台树必须指明平台，否则刊登侧取不到这棵树。">
            <select
              className={control}
              value={platform}
              onChange={(e) => setPlatform(e.target.value)}
            >
              {PLATFORMS.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </Field>
        ) : null}
      </div>
      {problem && !error ? <p className="text-warning text-xs">{problem}</p> : null}
      {error ? (
        <p role="alert" className="text-danger text-xs">
          {error}
        </p>
      ) : null}
      <div className="flex gap-2">
        <Button onClick={() => void submit()} disabled={busy || Boolean(problem)}>
          <Save /> {busy ? '保存中…' : '创建'}
        </Button>
        <Button variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------ 节点行

function NodeRow({
  branch,
  depth,
  treeId,
  includeDisabled,
  onEdit,
  onMove,
  onChanged,
  onError,
}: {
  branch: CategoryBranch;
  depth: number;
  treeId: string;
  includeDisabled: boolean;
  onEdit: (node: CategoryNode) => void;
  onMove: (node: CategoryNode) => void;
  onChanged: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [open, setOpen] = useState(depth < 3);
  const { node, children } = branch;
  const counts = nodeCounts(node);
  const rules = ruleImpact(node);

  async function toggleEnabled() {
    onError('');
    try {
      await setNodeEnabled(node.id, !node.enabled);
      await onChanged();
    } catch (e) {
      onError(errorText(e, '启用停用失败'));
    }
  }

  async function remove() {
    if (!window.confirm(`确定删除分类「${node.name}」？它身上的类目规则会一并删除。`)) return;
    onError('');
    try {
      await deleteNode(node.id, node.version);
      await onChanged();
    } catch (e) {
      onError(errorText(e, '删除失败'));
    }
  }

  return (
    <li>
      <div
        className="hover:bg-muted/60 flex flex-wrap items-center gap-2 rounded-md px-2 py-2"
        style={{ paddingLeft: `${depth * 16 + 8}px` }}
      >
        {children.length > 0 ? (
          <button
            type="button"
            aria-label={open ? '收起子分类' : '展开子分类'}
            onClick={() => setOpen((v) => !v)}
            className="text-muted-foreground"
          >
            {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </button>
        ) : (
          <span className="w-4" />
        )}

        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
            <span className="truncate">{node.name}</span>
            <span className="text-muted-foreground font-mono text-xs">{node.code}</span>
            {!node.enabled ? <Badge tone="neutral">已停用</Badge> : null}
          </p>
          <p className="text-muted-foreground mt-0.5 truncate text-xs" title={node.path}>
            {node.path}
          </p>
        </div>

        <div className="text-muted-foreground flex flex-wrap items-center gap-2 text-xs">
          <span title="主分类恰好挂在这一节点上的商品数">
            <Package className="mr-1 inline h-3.5 w-3.5" />
            本级 {counts.direct}
          </span>
          <span title="主分类落在本节点或任一子孙上的商品数">
            <Layers className="mr-1 inline h-3.5 w-3.5" />
            含子类 {counts.subtree}
          </span>
          {counts.secondary > 0 ? <span>辅助 {counts.secondary}</span> : null}
          {rules > 0 ? <span>规则 {rules}</span> : null}
        </div>

        <div className="flex items-center gap-1">
          <Button variant="ghost" size="sm" onClick={() => onMove(node)}>
            移动
          </Button>
          <Button variant="ghost" size="sm" onClick={() => onEdit(node)}>
            <Pencil /> 编辑
          </Button>
          <Button variant="ghost" size="sm" onClick={() => void toggleEnabled()}>
            {node.enabled ? '停用' : '启用'}
          </Button>
          <Button variant="ghost" size="sm" onClick={() => void remove()}>
            <Trash2 />
          </Button>
        </div>
      </div>

      {counts.inherited ? (
        <p
          className="text-muted-foreground text-xs"
          style={{ paddingLeft: `${depth * 16 + 40}px` }}
        >
          本级没有直接挂商品，这 {counts.subtree} 件都在子类目里。
        </p>
      ) : null}

      {open && children.length > 0 ? (
        <ul className="space-y-1">
          {children.map((child) => (
            <NodeRow
              key={child.node.id}
              branch={child}
              depth={depth + 1}
              treeId={treeId}
              includeDisabled={includeDisabled}
              onEdit={onEdit}
              onMove={onMove}
              onChanged={onChanged}
              onError={onError}
            />
          ))}
        </ul>
      ) : null}
    </li>
  );
}

// ------------------------------------------------------------ 节点表单

function NodeForm({
  node,
  treeId,
  nodes,
  onClose,
  onSaved,
}: {
  node: CategoryNode | null;
  treeId: string;
  nodes: readonly CategoryNode[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [code, setCode] = useState(node?.code ?? '');
  const [name, setName] = useState(node?.name ?? '');
  const [parentId, setParentId] = useState(node?.parentId ?? '');
  const [enabled, setEnabled] = useState(node?.enabled ?? true);
  // 契约上 names 是 {  }（Map<String,String> 的 codegen 产物），所以逐条收成 string，
  // 不能把 unknown 直接塞进 CategoryNameInput。
  const [names, setNames] = useState<readonly CategoryNameInput[]>(
    Object.entries(node?.names ?? {}).map(([locale, value]) => ({
      locale,
      name: String(value),
    })),
  );
  const [locale, setLocale] = useState('');
  const [localeName, setLocaleName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // 建后不可改编码：编辑时把它锁掉并说明原因，而不是让用户改完保存时才被拒。
  const codeLocked = Boolean(node);
  // 编辑时不允许在这里换父节点——换父节点是「拖拽移动」，两者混在一个表单里会让人
  // 以为只改了名称，结果整棵子树被挪了位置。
  const parentLocked = Boolean(node);

  const problem =
    (codeLocked ? null : codeProblem(code)) ??
    nameProblem(name) ??
    namesProblem(names) ??
    (locale || localeName ? namesProblem([...names, { locale, name: localeName }]) : null);

  async function submit() {
    setBusy(true);
    try {
      setError('');
      const payload = {
        code: normalizeCode(code),
        name: name.trim(),
        parentId: parentId || null,
        position: node?.position ?? 0,
        enabled,
        names: names.filter((n): n is CategoryNameInput => Boolean(n)),
        version: node?.version ?? 0,
      };
      if (node) await updateNode(node.id, payload);
      else await createNode(treeId, payload);
      await onSaved();
    } catch (e) {
      setError(errorText(e, '分类保存失败'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="bg-muted space-y-3 rounded-md p-3">
      <p className="text-sm font-medium">{node ? `编辑分类「${node.name}」` : '新建分类'}</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field
          label="分类编码"
          hint={
            codeLocked
              ? '编码建后不可修改：刊登映射与类目规则按 code 对齐它。要改请新建一个分类。'
              : '树内唯一；用英文字母、数字、下划线或短横线。'
          }
        >
          <input
            className={control}
            value={code}
            disabled={codeLocked}
            onChange={(e) => setCode(e.target.value)}
          />
        </Field>
        <Field label="分类名称" hint="中文类目名是常态，这里不限字符集。">
          <input className={control} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field
          label="父分类"
          hint={
            parentLocked
              ? '改父节点请用「移动」：拖拽会带着整棵子树一起走，藏在保存表单里很容易误触。'
              : '留空即根节点。'
          }
        >
          <select
            className={control}
            value={parentId}
            disabled={parentLocked}
            onChange={(e) => setParentId(e.target.value)}
          >
            <option value="">（根节点）</option>
            {nodes
              .filter((n) => !node || n.id !== node.id)
              .map((n) => (
                <option key={n.id} value={n.id}>
                  {n.path}
                </option>
              ))}
          </select>
        </Field>
        <Field label="启用状态" hint="停用后从树里过滤掉，但规则与已挂商品都保留。">
          <label className="flex h-9 items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
            />
            {enabled ? '启用' : '停用'}
          </label>
        </Field>
      </div>

      <div className="space-y-2">
        <p className="flex items-center gap-1.5 text-sm font-medium">
          <Languages className="h-4 w-4" />
          多语言名称
        </p>
        <p className="text-muted-foreground text-xs">
          基础名称在上面「分类名称」上，这里配的是补充：按语言给同一个类目另一个名字。 语言标识用
          BCP 47 形式（zh-CN / en-US / ja）。
        </p>
        {names.length > 0 ? (
          <ul className="flex flex-wrap gap-2">
            {names.map((entry, index) => (
              <li key={`${entry?.locale}-${index}`} className="flex items-center gap-1 text-xs">
                <Badge tone="info">
                  {entry?.locale}：{entry?.name}
                </Badge>
                <button
                  type="button"
                  aria-label={`删除 ${entry?.locale} 的名称`}
                  className="text-muted-foreground hover:text-danger"
                  onClick={() => setNames(names.filter((_, i) => i !== index))}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-muted-foreground text-xs">还没有配其他语言的名称。</p>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <Field label="语言">
            <input
              className={control}
              value={locale}
              placeholder="en-US"
              onChange={(e) => setLocale(e.target.value)}
            />
          </Field>
          <Field label="该语言的名称">
            <input
              className={control}
              value={localeName}
              onChange={(e) => setLocaleName(e.target.value)}
            />
          </Field>
          <Button
            variant="ghost"
            onClick={() => {
              setNames([...names, { locale, name: localeName }]);
              setLocale('');
              setLocaleName('');
            }}
          >
            <Plus /> 添加
          </Button>
        </div>
      </div>

      {problem && !error ? <p className="text-warning text-xs">{problem}</p> : null}
      {error ? (
        <p role="alert" className="text-danger text-xs">
          {error}
        </p>
      ) : null}
      <div className="flex gap-2">
        <Button onClick={() => void submit()} disabled={busy || Boolean(problem)}>
          <Save /> {busy ? '保存中…' : '保存'}
        </Button>
        <Button variant="ghost" onClick={onClose}>
          取消
        </Button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------ 移动（拖拽落点）

function MoveForm({
  node,
  treeId,
  branches,
  onClose,
  onSaved,
  onError,
}: {
  node: CategoryNode;
  treeId: string;
  branches: readonly CategoryBranch[];
  onClose: () => void;
  onSaved: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [parentId, setParentId] = useState(node.parentId ?? '');
  const [position, setPosition] = useState(String(node.position));
  const [busy, setBusy] = useState(false);

  // 防环在**界面上先判一次**：与后端 isDescendant 和 V14 触发器同口径。
  // 让用户拖到非法位置、松手后才报错是最糟糕的拖拽体验。
  const problem = moveProblem(node.id, parentId || null, branches);

  async function submit() {
    setBusy(true);
    try {
      onError('');
      await moveNode(node.id, {
        parentId: parentId || null,
        position: Number(position) || 0,
        version: node.version,
      });
      await onSaved();
    } catch (e) {
      onError(errorText(e, '移动失败'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="bg-muted space-y-3 rounded-md p-3">
      <p className="text-sm font-medium">移动分类「{node.name}」</p>
      <p className="text-muted-foreground text-xs">
        移动会带着整棵子树一起走：当前路径 <b>{node.path}</b>，下面{' '}
        <b>{countDescendants(branches, node.id)}</b> 个子分类会跟着换路径。
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="新的父分类" hint="留空即移到根层级。">
          <select
            className={control}
            value={parentId}
            onChange={(e) => setParentId(e.target.value)}
          >
            <option value="">（根节点）</option>
            {branches
              .flatMap(flattenBranch)
              .filter((n) => n.id !== node.id)
              .map((n) => (
                <option key={n.id} value={n.id}>
                  {n.path}
                </option>
              ))}
          </select>
        </Field>
        <Field label="同级位置" hint="0 表示排在该层级的第一位。">
          <input
            type="number"
            min={0}
            className={control}
            value={position}
            onChange={(e) => setPosition(e.target.value)}
          />
        </Field>
      </div>
      {problem ? (
        <p className="text-danger flex items-center gap-1.5 text-xs">
          <TriangleAlert className="h-3.5 w-3.5" />
          {problem}
        </p>
      ) : null}
      <div className="flex gap-2">
        <Button onClick={() => void submit()} disabled={busy || Boolean(problem)}>
          <Save /> {busy ? '移动中…' : '移动'}
        </Button>
        <Button variant="ghost" onClick={onClose}>
          取消
        </Button>
      </div>
    </div>
  );
}

function flattenBranch(branch: CategoryBranch): CategoryNode[] {
  return [branch.node, ...branch.children.flatMap(flattenBranch)];
}

function countDescendants(branches: readonly CategoryBranch[], nodeId: string): number {
  const find = (list: readonly CategoryBranch[]): CategoryBranch | undefined => {
    for (const b of list) {
      if (b.node.id === nodeId) return b;
      const hit = find(b.children);
      if (hit) return hit;
    }
    return undefined;
  };
  const branch = find(branches);
  if (!branch) return 0;
  return countNodes(branch.children);
}
