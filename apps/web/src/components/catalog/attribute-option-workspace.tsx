'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState } from '@starcon/ui';
import {
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  ListFilter,
  RefreshCw,
  Tags,
  TriangleAlert,
} from 'lucide-react';
import type { AttributeOptionOverviewRow } from '../../../../../packages/api-contracts/src/index';
import {
  ENABLED_FILTERS,
  OPTION_SORTS,
  REFERENCED_FILTERS,
  emptyHint,
  errorText,
  fetchOptionOverview,
  groupByAttribute,
  moveWithin,
  referenceText,
  reorderOptions,
  retainHint,
  toggleOption,
  type OptionListQuery,
  type OptionSort,
} from './attribute-option-model';
import { Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.2.1.2 属性选项管理工作区。
 *
 * <p>这是**跨属性**的选项面：把租户下所有枚举属性的选项拍平成一张表，回答
 * 「哪些选项没人用」「哪些属性还没配值」「这个选项还能删吗」。
 * 3.2.1.1 的字典页管的是「一条属性」，两者在同一路由下用标签页切换。
 *
 * <p>五条界面纪律：
 *
 * <ul>
 *   <li>**引用计数如实呈现**。`productValueCount` 目前恒为 0（字典条目拿不到商品属性值），
 *       界面上说「暂无引用」而不是「已被 0 个商品使用」——后者会让人以为这是实测结果。
 *   <li>**停用与删除分开**。有引用的选项只给停用，并说清为什么不能删。
 *   <li>**排序是覆盖式的**。拖动任意一项都会提交该属性的完整顺序，
 *       否则新旧 position 会撞在一起。
 *   <li>**加载 / 错误 / 空态三态齐全**。筛选无结果与租户根本没有选项是两种空态，文案不同。
 *   <li>**只读角色看得见但动不了**。写操作按 `catalog.attribute.update` 收敛。
 * </ul>
 */
export function AttributeOptionWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.attribute.update');

  const [page, setPage] = useState<AttributeOptionOverviewRow[]>([]);
  const [total, setTotal] = useState(0);
  const [enabledCount, setEnabledCount] = useState(0);
  const [inUseCount, setInUseCount] = useState(0);
  const [note, setNote] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [keyword, setKeyword] = useState('');
  const [attributeId, setAttributeId] = useState<string | null>(null);
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [referenced, setReferenced] = useState<boolean | null>(null);
  const [sort, setSort] = useState<OptionSort>('POSITION');
  const [index, setIndex] = useState(0);

  const query: OptionListQuery = useMemo(
    () => ({ keyword, attributeId, enabled, referenced, sort, page: index, size: PAGE_SIZE }),
    [keyword, attributeId, enabled, referenced, sort, index],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchOptionOverview(query);
      setPage(result.items);
      setTotal(result.total);
      setEnabledCount(result.enabledCount);
      setInUseCount(result.inUseCount);
      setNote(result.note);
    } catch (e) {
      setError(errorText(e, '选项总览加载失败'));
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const groups = useMemo(() => groupByAttribute(page), [page]);
  const filtered = Boolean(keyword.trim()) || attributeId !== null || enabled !== null || referenced !== null;
  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);

  const reset = () => {
    setKeyword('');
    setAttributeId(null);
    setEnabled(null);
    setReferenced(null);
    setIndex(0);
  };

  async function onToggle(row: AttributeOptionOverviewRow) {
    setBusy(true);
    setActionError(null);
    try {
      await toggleOption(row.attributeId, row.value, !row.enabled);
      await load();
    } catch (e) {
      setActionError(errorText(e, '状态切换失败'));
    } finally {
      setBusy(false);
    }
  }

  /** 批量重排：提交该属性的**完整**顺序，不是只提交被拖动的那一项。 */
  async function onMove(groupRows: AttributeOptionOverviewRow[], from: number, to: number) {
    const items = moveWithin(groupRows, from, to);
    if (!items) return;
    setBusy(true);
    setActionError(null);
    try {
      await reorderOptions(groupRows[0]?.attributeId ?? '', items);
      await load();
    } catch (e) {
      setActionError(errorText(e, '排序保存失败'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-h-20 text-primary font-semibold tracking-tight">
            属性选项管理
          </h1>
          <p className="text-muted-foreground text-copy-13 mt-1">
            租户下所有枚举属性的选项总览。停用只挡住新建使用，历史单据仍可展示该取值。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link href="/products/attributes">
            <Button variant="ghost" size="sm">
              <ArrowLeft className="h-4 w-4" />
              回到属性字典
            </Button>
          </Link>
          <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </Button>
        </div>
      </div>

      {/* 概览：两个计数都不受筛选影响，所以放在筛选框旁边而不是跟着结果走。 */}
      <div className="grid gap-3 sm:grid-cols-4">
        <Stat label="选项总数" value={total} />
        <Stat label="启用中（全租户）" value={enabledCount} />
        <Stat label="被引用（全租户）" value={inUseCount} />
        <Stat label="本页展示" value={page.length} />
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <Field label="搜索">
          <input
            className={control}
            value={keyword}
            placeholder="选项值 / 属性编码 / 属性名称"
            onChange={(e) => {
              setKeyword(e.target.value);
              setIndex(0);
            }}
          />
        </Field>
        <Field label="启用状态">
          <select
            className={control}
            value={enabled === null ? '' : String(enabled)}
            onChange={(e) => {
              setEnabled(e.target.value === '' ? null : e.target.value === 'true');
              setIndex(0);
            }}
          >
            {ENABLED_FILTERS.map((f) => (
              <option key={String(f.value)} value={f.value === null ? '' : String(f.value)}>
                {f.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="引用状态">
          <select
            className={control}
            value={referenced === null ? '' : String(referenced)}
            onChange={(e) => {
              setReferenced(e.target.value === '' ? null : e.target.value === 'true');
              setIndex(0);
            }}
          >
            {REFERENCED_FILTERS.map((f) => (
              <option key={String(f.value)} value={f.value === null ? '' : String(f.value)}>
                {f.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="排序">
          <select
            className={control}
            value={sort}
            onChange={(e) => setSort(e.target.value as OptionSort)}
          >
            {OPTION_SORTS.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </Field>
        <div className="flex items-end">
          <Button variant="ghost" onClick={reset} disabled={!filtered && index === 0}>
            <ListFilter className="h-4 w-4" />
            重置筛选
          </Button>
        </div>
      </div>

      {actionError && (
        <div
          role="alert"
          className="border-destructive-soft bg-destructive-soft/40 text-destructive flex items-start gap-2 rounded-md border px-3 py-2 text-sm"
        >
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {/* 加载态：保留筛选框，避免每次刷新都把用户输入清掉。 */}
      {loading ? (
        <div className="text-muted-foreground rounded-md border border-dashed p-10 text-center text-sm">
          正在加载选项总览…
        </div>
      ) : error ? (
        <EmptyState
          icon={<TriangleAlert className="h-5 w-5" />}
          title="选项总览加载失败"
          description={error}
          action={
            <Button variant="ghost" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      ) : page.length === 0 ? (
        <EmptyState
          icon={<Tags className="h-5 w-5" />}
          title={filtered ? '没有匹配的选项' : '还没有任何属性选项'}
          description={emptyHint(total, filtered)}
          action={
            filtered ? (
              <Button variant="ghost" onClick={reset}>
                重置筛选
              </Button>
            ) : (
              <Link href="/products/attributes">
                <Button variant="ghost">去属性字典登记取值</Button>
              </Link>
            )
          }
        />
      ) : (
        <div className="space-y-4">
          {groups.map((group) => (
            <section key={group.attributeId} className="space-y-2">
              <div className="flex items-center gap-2">
                <h2 className="text-copy-13 font-medium">{group.label}</h2>
                <Badge tone="neutral">{group.rows.length} 个选项</Badge>
              </div>
              <div className="overflow-x-auto rounded-md border">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground bg-muted/40">
                    <tr>
                      <th className="px-3 py-2 text-left font-medium">取值</th>
                      <th className="px-3 py-2 text-left font-medium">状态</th>
                      <th className="px-3 py-2 text-left font-medium">引用</th>
                      {writable && <th className="px-3 py-2 text-right font-medium">操作</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {group.rows.map((row, position) => {
                      const hint = retainHint(row);
                      return (
                        <tr key={row.value} className="border-t">
                          <td className="px-3 py-2">
                            <div className="font-medium">{row.value}</div>
                            <div className="text-muted-foreground text-xs">位置 {row.position}</div>
                          </td>
                          <td className="px-3 py-2">
                            <Badge tone={row.enabled ? 'success' : 'neutral'}>
                              {row.enabled ? '启用' : '停用'}
                            </Badge>
                          </td>
                          <td className="px-3 py-2">
                            <div className="flex flex-wrap items-center gap-1.5">
                              <Badge tone={row.inUse ? 'info' : 'neutral'}>
                                {referenceText(row)}
                              </Badge>
                              {!row.productValueCount && (
                                <span
                                  className="text-muted-foreground text-xs"
                                  title="字典条目暂时拿不到商品属性值，这个 0 是数据库现状而非实测结果"
                                >
                                  商品取用未接入
                                </span>
                              )}
                            </div>
                            {hint && (
                              <p className="text-muted-foreground mt-1 text-xs">{hint}</p>
                            )}
                          </td>
                          {writable && (
                            <td className="px-3 py-2">
                              <div className="flex items-center justify-end gap-1">
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  disabled={busy || position === 0}
                                  title="上移"
                                  onClick={() => void onMove(group.rows, position, position - 1)}
                                >
                                  <ArrowUp className="h-4 w-4" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  disabled={busy || position === group.rows.length - 1}
                                  title="下移"
                                  onClick={() =>
                                    void onMove(group.rows, position, position + 1)
                                  }
                                >
                                  <ArrowDown className="h-4 w-4" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  disabled={busy}
                                  onClick={() => void onToggle(row)}
                                >
                                  {row.enabled ? '停用' : '启用'}
                                </Button>
                              </div>
                            </td>
                          )}
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </section>
          ))}
        </div>
      )}

      {!loading && !error && page.length > 0 && (
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">
            第 {index + 1} / {lastPage + 1} 页 · 共 {total} 个选项
          </span>
          <div className="flex gap-2">
            <Button
              variant="ghost"
              size="sm"
              disabled={index === 0}
              onClick={() => setIndex((i) => Math.max(0, i - 1))}
            >
              上一页
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={index >= lastPage}
              onClick={() => setIndex((i) => i + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      )}

      {/* 口径说明常驻：productValueCount 为 0 是数据库现状，不解释清楚会被当成计数坏了。 */}
      {note && (
        <p className="text-muted-foreground border-t pt-3 text-xs leading-relaxed">{note}</p>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-md border p-3">
      <p className="text-muted-foreground text-xs">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
    </div>
  );
}