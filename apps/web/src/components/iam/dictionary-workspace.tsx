'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RefreshCw, Search } from 'lucide-react';
import type {
  DictCatalogView,
  DictHistoryView,
  DictItemUsageView,
  DictItemView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';
import {
  ITEM_KIND_LABELS,
  disableActions,
  groupByDictionary,
  historySummary,
  referenceSummary,
  validateItemEdit,
} from './dictionary-model';

/**
 * 17.5.2.1 数据字典。
 *
 * 三个区块对应蓝图的三件事：**字典目录**（平台出厂的分类，状态枚举 / 原因码 / 分类标签 /
 * 单位类型）、**字典项**（租户自定义扩展，带真实引用统计）、**变更历史**（追加不可改）。
 *
 * <p>界面上**没有删除按钮**，这是模块的核心纪律：被引用后只能停用，否则历史单据的下拉
 * 会渲染成空白。停用的项从「下拉选项」里消失，但仍留在列表里并标注原因——它随时可能被恢复，
 * 也随时可能被历史单据读回去。
 *
 * <p>引用统计来自后端对引用台账的真实聚合，不是任何写死的数字。停用一个被引用了 9000 条
 * 和一个从未被引用的字典项，风险完全不同，所以统计必须摆在列表里让人先看见。
 */
export function DictionaryWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [catalog, setCatalog] = useState<DictCatalogView[]>([]);
  const [items, setItems] = useState<DictItemView[]>([]);
  const [history, setHistory] = useState<DictHistoryView[]>([]);
  const [dictCode, setDictCode] = useState('');
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<DictItemView | 'new' | null>(null);
  const [inspecting, setInspecting] = useState<DictItemView | null>(null);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.dictionary.read');

  const load = useCallback(async () => {
    setError('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「还没有字典项」，那是骗人。
      setReady(true);
      return;
    }
    try {
      const filter = new URLSearchParams();
      if (dictCode) filter.set('dictCode', dictCode);
      if (search.trim()) filter.set('search', search.trim());
      const suffix = filter.size > 0 ? `?${filter.toString()}` : '';
      const [catalogList, itemList, historyList] = await Promise.all([
        api<DictCatalogView[]>('identity/dict-catalog'),
        api<DictItemView[]>(`identity/dict-items${suffix}`),
        api<DictHistoryView[]>('identity/dict-history'),
      ]);
      setCatalog(catalogList);
      setItems(itemList);
      setHistory(historyList);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [readable, dictCode, search]);

  useEffect(() => {
    void load();
  }, [load]);

  const groups = useMemo(() => groupByDictionary(items), [items]);
  const visibleHistory = useMemo(
    () => (dictCode ? history.filter((row) => row.dictCode === dictCode) : history),
    [history, dictCode],
  );

  async function setStatus(row: DictItemView, status: 'ACTIVE' | 'DISABLED') {
    const reason = window.prompt(
      status === 'DISABLED'
        ? `停用字典项「${row.itemCode}」\n\n停用后不再出现在新单据的下拉里，历史单据仍按它显示。请填写停用原因：`
        : `恢复字典项「${row.itemCode}」\n\n恢复后会重新出现在下拉选项里。请填写恢复原因：`,
    );
    if (reason === null) return;
    if (!reason.trim()) {
      setError('必须填写原因：事后要能解释这次停用或恢复的依据');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await api(
        `identity/dict-items/${row.id}/${status === 'DISABLED' ? 'deactivate' : 'reactivate'}`,
        'POST',
        {
          status,
          reason: reason.trim(),
        },
      );
      setNotice(status === 'DISABLED' ? '字典项已停用' : '字典项已恢复');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载数据字典…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有数据字典查看权限，请联系租户所有者开通。
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          字典项被业务系统引用后<b>只能停用不能删除</b>，编码也不再可改——否则历史单据会显示空白。
          名称与说明随时可以改。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索编码或名称"
              aria-label="搜索字典项"
              className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-48 rounded-md border pl-8 pr-2 outline-none"
            />
          </label>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {can('identity.dictionary.create') && (
            <Button size="sm" onClick={() => setEditing('new')} disabled={catalog.length === 0}>
              <Plus className="h-4 w-4" />
              新增字典项
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {/* ---------------- 字典目录 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">字典目录</h2>
          <p className="text-copy-12 text-muted-foreground">
            平台出厂的分类，全租户共用。点一项可以只看它的字典项。
          </p>
        </div>
        {catalog.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">字典目录为空，请联系平台管理员</p>
          </div>
        ) : (
          <ul className="grid gap-2 md:grid-cols-2 xl:grid-cols-3">
            {catalog.map((c) => {
              const active = dictCode === c.code;
              return (
                <li key={c.code}>
                  <button
                    type="button"
                    onClick={() => setDictCode(active ? '' : c.code)}
                    aria-pressed={active}
                    className={`w-full rounded-md border p-3 text-left transition-colors ${
                      active
                        ? 'border-ring bg-accent'
                        : 'bg-card hover:border-ring hover:bg-accent/50'
                    }`}
                  >
                    <p className="text-copy-13 flex flex-wrap items-center gap-2 font-medium">
                      {c.name}
                      <Badge tone="info">{ITEM_KIND_LABELS[c.itemKind] ?? c.itemKind}</Badge>
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1 font-mono">{c.code}</p>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      共 {c.itemCount} 项 · 启用 {c.activeItemCount} 项
                    </p>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        {dictCode && (
          <p className="text-copy-12 text-muted-foreground">
            正在只看「{dictCode}」的字典项。
            <button
              type="button"
              onClick={() => setDictCode('')}
              className="text-ring ml-2 underline underline-offset-2"
            >
              显示全部
            </button>
          </p>
        )}
      </section>

      {/* ---------------- 字典项 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">字典项</h2>
          <p className="text-copy-12 text-muted-foreground">
            共 {items.length} 项。停用项仍显示在这里，只是不再出现在新单据的下拉里。
          </p>
        </div>
        {groups.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {catalog.length === 0
                ? '字典目录为空，无法新增字典项'
                : search.trim()
                  ? '没有匹配当前搜索的字典项'
                  : '当前租户还没有任何字典项，点「新增字典项」开始维护'}
            </p>
          </div>
        ) : (
          groups.map((group) => (
            <div key={group.dictCode} className="space-y-2">
              <p className="text-copy-12 text-muted-foreground">
                {group.label} · <span className="font-mono">{group.dictCode}</span>
              </p>
              <ul className="space-y-2">
                {group.rows.map((row) => {
                  const actions = disableActions(row);
                  return (
                    <li key={row.id} className="bg-card rounded-md border p-3">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div className="min-w-0">
                          <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                            {row.name}
                            {row.status === 'DISABLED' && <Badge tone="neutral">已停用</Badge>}
                            {row.codeLocked && <Badge tone="warning">编码锁定</Badge>}
                          </p>
                          <p className="text-copy-12 text-muted-foreground mt-1 font-mono">
                            {row.itemCode}
                            {row.description ? ` · ${row.description}` : ''}
                          </p>
                          <p className="text-copy-12 text-muted-foreground mt-1">
                            {referenceSummary(row)}
                          </p>
                          {row.status === 'DISABLED' && row.disabledReason && (
                            <p className="text-copy-12 text-muted-foreground mt-1">
                              停用原因：{row.disabledReason}
                              {row.disabledAt ? ` · ${row.disabledAt}` : ''}
                            </p>
                          )}
                        </div>
                        <div className="flex items-center gap-2">
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => setInspecting(row)}
                            disabled={!row.referenced}
                          >
                            引用明细
                          </Button>
                          {can('identity.dictionary.update') && (
                            <>
                              <Button size="sm" variant="ghost" onClick={() => setEditing(row)}>
                                编辑
                              </Button>
                              {actions.canDeactivate && (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  disabled={busy}
                                  onClick={() => void setStatus(row, 'DISABLED')}
                                >
                                  停用
                                </Button>
                              )}
                              {actions.canReactivate && (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  disabled={busy}
                                  onClick={() => void setStatus(row, 'ACTIVE')}
                                >
                                  恢复
                                </Button>
                              )}
                            </>
                          )}
                        </div>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))
        )}
      </section>

      {/* ---------------- 变更历史 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">变更历史</h2>
          <p className="text-copy-12 text-muted-foreground">
            只追加、不可修改，按时间倒序。最近 {visibleHistory.length} 条。
          </p>
        </div>
        {visibleHistory.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">还没有字典变更记录</p>
          </div>
        ) : (
          <ol className="space-y-2">
            {visibleHistory.map((row) => (
              <li key={row.id} className="bg-card rounded-md border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <p className="text-copy-13">{historySummary(row)}</p>
                  <div className="text-copy-12 text-muted-foreground text-right">
                    <p>{row.dictCode}</p>
                    <p>{row.changedAt}</p>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>

      {editing && (
        <ItemForm
          editing={editing === 'new' ? null : editing}
          catalog={catalog}
          busy={busy}
          onCancel={() => setEditing(null)}
          onSubmit={async (input) => {
            setBusy(true);
            setError('');
            try {
              if (editing === 'new') {
                await api('identity/dict-items', 'POST', input);
                setNotice('字典项已创建');
              } else {
                await api(`identity/dict-items/${editing.id}`, 'PUT', input);
                setNotice('字典项已更新');
              }
              setEditing(null);
              await load();
            } catch (e) {
              setError(e instanceof Error ? e.message : '保存失败，请重试');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {inspecting && <UsagePanel item={inspecting} onClose={() => setInspecting(null)} />}
    </div>
  );
}

// ------------------------------------------------------------------
// 字典项表单
// ------------------------------------------------------------------

type ItemInput = {
  dictCode: string;
  itemCode: string;
  name: string;
  description: string;
  sortOrder: number;
};

function ItemForm({
  editing,
  catalog,
  busy,
  onCancel,
  onSubmit,
}: {
  editing: DictItemView | null;
  catalog: DictCatalogView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: ItemInput) => Promise<void>;
}) {
  const [dictCode, setDictCode] = useState(editing?.dictCode ?? catalog[0]?.code ?? '');
  const [itemCode, setItemCode] = useState(editing?.itemCode ?? '');
  const [name, setName] = useState(editing?.name ?? '');
  const [description, setDescription] = useState(editing?.description ?? '');
  const [sortOrder, setSortOrder] = useState(String(editing?.sortOrder ?? 0));

  const problem = validateItemEdit(editing, itemCode, name);
  const current = catalog.find((c) => c.code === dictCode);
  const codeLocked = editing?.codeLocked ?? current?.codeLocked ?? true;

  return (
    <div className="bg-card space-y-4 rounded-md border p-4">
      <p className="text-copy-14 font-medium">{editing ? '编辑字典项' : '新增字典项'}</p>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="所属字典" {...(current?.description ? { hint: current.description } : {})}>
          <select
            value={dictCode}
            onChange={(e) => setDictCode(e.target.value)}
            // 编辑时字典不可改：换字典等于换一条记录，引用统计会被切成两半
            disabled={!!editing}
            className={inputClass}
            aria-label="选择所属字典"
          >
            {catalog.map((c) => (
              <option key={c.code} value={c.code}>
                {ITEM_KIND_LABELS[c.itemKind] ?? c.itemKind} · {c.name}
              </option>
            ))}
          </select>
        </Field>
        <Field
          label="编码"
          hint={
            editing?.referenced
              ? '已被历史数据引用，不能修改；名称与说明仍可改'
              : codeLocked
                ? '大写字母开头，2–64 位字母数字下划线。被引用后不可修改'
                : '大写字母开头，2–64 位字母数字下划线'
          }
        >
          <input
            value={itemCode}
            onChange={(e) => setItemCode(e.target.value.toUpperCase())}
            disabled={!!editing && editing.referenced}
            placeholder="例如 PAID"
            className={inputClass}
            aria-label="字典项编码"
          />
        </Field>
        <Field label="名称">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="例如 已付款"
            className={inputClass}
            aria-label="字典项名称"
          />
        </Field>
        <Field label="排序">
          <input
            type="number"
            value={sortOrder}
            onChange={(e) => setSortOrder(e.target.value)}
            className={inputClass}
            aria-label="排序"
          />
        </Field>
        <Field label="说明">
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="这一项用来区分什么"
            className={inputClass}
            aria-label="说明"
          />
        </Field>
      </div>
      {problem && (
        <p role="alert" className="text-destructive text-copy-12">
          {problem}
        </p>
      )}
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || problem !== ''}
          onClick={() =>
            void onSubmit({
              dictCode,
              // 编辑时留空表示不改编码，不是把它清空
              itemCode: itemCode.trim(),
              name: name.trim(),
              description: description.trim(),
              sortOrder: Number(sortOrder) || 0,
            })
          }
        >
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------
// 引用明细
// ------------------------------------------------------------------

function UsagePanel({ item, onClose }: { item: DictItemView; onClose: () => void }) {
  const [rows, setRows] = useState<DictItemUsageView[] | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    api<DictItemUsageView[]>(`identity/dict-items/${item.id}/usage`)
      .then((data) => {
        if (!cancelled) setRows(data);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : '引用明细加载失败');
      });
    return () => {
      cancelled = true;
    };
  }, [item.id]);

  return (
    <div className="bg-card space-y-3 rounded-md border p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-copy-14 font-medium">引用明细 · {item.itemCode}</p>
        <Button size="sm" variant="ghost" onClick={onClose}>
          关闭
        </Button>
      </div>
      {error && (
        <p role="alert" className="text-destructive text-copy-12">
          {error}
        </p>
      )}
      {rows === null && !error && (
        <p role="status" className="text-muted-foreground text-copy-12">
          正在加载引用明细…
        </p>
      )}
      {rows !== null && rows.length === 0 && (
        <p className="text-muted-foreground text-copy-13">这个字典项还没有被任何业务系统引用。</p>
      )}
      {rows !== null && rows.length > 0 && (
        <ul className="space-y-2">
          {rows.map((row) => (
            <li key={row.id} className="rounded-md border p-2">
              <p className="text-copy-13 font-medium">
                {row.sourceSystem} · {row.usageCount} 条
              </p>
              <p className="text-copy-12 text-muted-foreground mt-1">
                首次引用 {row.firstReferencedAt} · 最近 {row.lastReferencedAt}
                {row.note ? ` · ${row.note}` : ''}
              </p>
            </li>
          ))}
        </ul>
      )}
      <p className="text-copy-12 text-muted-foreground">
        引用计数为 0 也不代表没被引用过：只要有登记记录，这个字典项的编码就锁死、不可删除。
      </p>
    </div>
  );
}

const inputClass =
  'border-border bg-background text-copy-13 focus-visible:border-ring h-9 rounded-md border px-2 outline-none';

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-copy-13 mb-1 block font-medium">{label}</span>
      {children}
      {hint && <span className="text-copy-12 text-muted-foreground mt-1 block">{hint}</span>}
    </label>
  );
}
