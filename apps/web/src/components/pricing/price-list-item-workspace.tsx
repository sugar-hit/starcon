'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  actionText,
  actionTone,
  applyPriceListBatch,
  batchModeOptions,
  batchScopeOptions,
  batchStats,
  conflictWindowText,
  createPriceListItem,
  deletePriceListItem,
  effectiveText,
  effectiveTone,
  emptyBatchRequest,
  emptyPriceListItemInput,
  fetchPriceListItemCapabilities,
  fetchPriceListItemConflicts,
  fetchPriceListItems,
  fileToBase64,
  formatInstant,
  importPriceListItems,
  importStatusText,
  importStatusTone,
  intervalText,
  priceListItemExportUrl,
  priceText,
  priorityText,
  previewPriceListBatch,
  quantityText,
  resolvePriceListItem,
  selectionTone,
  toBatchPayload,
  toPriceListItemInput,
  toPriceListItemPayload,
  updatePriceListItem,
  validateBatchRequest,
  validatePriceListItem,
} from './price-list-item-model';
import type {
  BatchMode,
  BatchScope,
  PriceListItemBatchPreview,
  PriceListItemConflict,
  PriceListItemCapabilities,
  PriceListItemImportReport,
  PriceListItemInput,
  PriceListItemResolution,
  PriceListItemView,
} from './price-list-item-model';
import { fetchPriceLists } from './price-list-model';
import type { PriceListSummary } from './price-list-model';

/**
 * 5.1.1.2 价目项工作区（`/pricing/lists` 的**价目项分段**）。
 *
 * <p>蓝图：「在价目表内维护 SKU 价格，支持按分类/品牌/SKU 范围批量设价、阶梯价（按数量门槛）、
 * Excel 导入导出，保存前预览影响范围。」输入「价目项录入/批量操作（固定值、按百分比、按类目统一）」，
 * 输出「价目项记录（SKU、单价、货币、数量门槛、生效区间）；导入校验报告」，并加粗
 * 「<b>同优先级多条有效价目必须报错让用户选</b>，不随机取」。
 *
 * <p>这是一个真实工作区而不是占位页：能力声明、价目表选择、价目项台账（真分页 + 筛选）、
 * 价目项表单（阶梯门槛 + 优先级 + 生效区间）、**批量设价的预览与执行**、
 * **冲突清单**、**按时刻解析**（歧义时逐条显示候选）、**Excel 导入的逐行校验报告**，
 * 加载态 / 错误态 / 空态三态齐全。
 *
 * <p>🔴 **价目项没有币种输入框** —— 币种钉在价目表头上，价目项在结构上无法跨币种。
 *
 * <p>🔴 **阶梯 ≠ 冲突**：同一个 SKU 的多行是阶梯价；只有「同 SKU + 同数量门槛 + 同优先级 +
 * 生效区间重叠」才是歧义，界面上分开显示。
 */
export function PriceListItemWorkspace() {
  const [capabilities, setCapabilities] = useState<PriceListItemCapabilities | null>(null);
  const [lists, setLists] = useState<PriceListSummary[]>([]);
  const [listId, setListId] = useState('');
  const [items, setItems] = useState<PriceListItemView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [enabledFilter, setEnabledFilter] = useState('');
  const [effectiveFilter, setEffectiveFilter] = useState('');
  const [form, setForm] = useState<PriceListItemInput>(emptyPriceListItemInput());
  const [editing, setEditing] = useState<string | null>(null);
  const [batch, setBatch] = useState(emptyBatchRequest());
  const [preview, setPreview] = useState<PriceListItemBatchPreview | null>(null);
  const [conflicts, setConflicts] = useState<PriceListItemConflict[]>([]);
  const [resolveSku, setResolveSku] = useState('');
  const [resolveQuantity, setResolveQuantity] = useState('');
  const [resolution, setResolution] = useState<PriceListItemResolution | null>(null);
  const [resolveCandidates, setResolveCandidates] = useState<PriceListItemResolution['candidates']>(
    [],
  );
  const [importReport, setImportReport] = useState<PriceListItemImportReport | null>(null);
  const [problem, setProblem] = useState<{ field: string; message: string } | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const current = useMemo(() => lists.find((l) => l.id === listId) ?? null, [lists, listId]);
  const pages = Math.max(1, Math.ceil(total / 20));
  const stats = useMemo(() => batchStats(preview), [preview]);

  const load = useCallback(async () => {
    try {
      const caps = await fetchPriceListItemCapabilities();
      setCapabilities(caps);
    } catch (e) {
      setError(e instanceof Error ? e.message : '价目项能力声明读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  const loadLists = useCallback(async () => {
    try {
      const result = await fetchPriceLists({ page: 0, size: 100 });
      setLists(result.items);
      setListId((previous) => previous || (result.items[0]?.id ?? ''));
    } catch (e) {
      setError(e instanceof Error ? e.message : '价目表列表读不到');
    }
  }, []);

  const search = useCallback(
    async (nextPage = 0) => {
      if (!listId) return;
      setBusy(true);
      try {
        const result = await fetchPriceListItems(listId, {
          keyword: keyword.trim() || undefined,
          enabled: enabledFilter === '' ? null : enabledFilter === 'true',
          effectiveNow: effectiveFilter === '' ? null : effectiveFilter === 'true',
          page: nextPage,
          size: 20,
        });
        setItems(result.items);
        setTotal(result.total);
        setPage(result.page);
      } catch (e) {
        setError(e instanceof Error ? e.message : '价目项读不到');
      } finally {
        setBusy(false);
      }
    },
    [listId, keyword, enabledFilter, effectiveFilter],
  );

  const loadConflicts = useCallback(async () => {
    if (!listId) {
      setConflicts([]);
      return;
    }
    try {
      setConflicts(await fetchPriceListItemConflicts(listId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '冲突清单读不到');
    }
  }, [listId]);

  useEffect(() => {
    void load();
    void loadLists();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void search(0);
    void loadConflicts();
    setPreview(null);
    setResolution(null);
    setResolveCandidates([]);
    setImportReport(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listId]);

  const patch = useCallback((next: Partial<PriceListItemInput>) => {
    setForm((currentForm) => ({ ...currentForm, ...next }));
  }, []);

  const patchBatch = useCallback((next: Partial<typeof batch>) => {
    setBatch((currentBatch) => ({ ...currentBatch, ...next }));
  }, []);

  const open = useCallback(async (item: PriceListItemView) => {
    setForm(toPriceListItemInput(item));
    setEditing(item.id);
    setProblem(null);
  }, []);

  const reset = useCallback(() => {
    setEditing(null);
    setForm(emptyPriceListItemInput());
    setProblem(null);
  }, []);

  const submit = useCallback(async () => {
    const invalid = validatePriceListItem(form);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setProblem(null);
    setBusy(true);
    try {
      const payload = toPriceListItemPayload(form);
      const saved = editing
        ? await updatePriceListItem(listId, editing, payload)
        : await createPriceListItem(listId, payload);
      setEditing(saved.id);
      setForm(toPriceListItemInput(saved));
      setNotice(editing ? '价目项已更新' : '价目项已创建');
      await search(0);
      await loadConflicts();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }, [editing, form, listId, loadConflicts, search]);

  const remove = useCallback(async () => {
    if (!editing) return;
    setBusy(true);
    try {
      await deletePriceListItem(listId, editing);
      setNotice('价目项已删除');
      reset();
      await search(0);
      await loadConflicts();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    } finally {
      setBusy(false);
    }
  }, [editing, listId, loadConflicts, reset, search]);

  const runPreview = useCallback(async () => {
    const invalid = validateBatchRequest(batch);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setProblem(null);
    setBusy(true);
    try {
      const result = await previewPriceListBatch(listId, toBatchPayload(batch));
      setPreview(result);
      setNotice('预览已生成 —— 还没有落库');
    } catch (e) {
      setError(e instanceof Error ? e.message : '预览失败');
    } finally {
      setBusy(false);
    }
  }, [batch, listId]);

  const runApply = useCallback(async () => {
    const invalid = validateBatchRequest(batch);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setProblem(null);
    setBusy(true);
    try {
      const result = await applyPriceListBatch(listId, toBatchPayload(batch));
      setNotice(result.summary);
      setPreview(null);
      await search(0);
      await loadConflicts();
    } catch (e) {
      setError(e instanceof Error ? e.message : '批量执行失败');
    } finally {
      setBusy(false);
    }
  }, [batch, listId, loadConflicts, search]);

  const runResolve = useCallback(async () => {
    if (!resolveSku.trim()) {
      setProblem({ field: 'resolveSku', message: '请填 SKU 编码' });
      return;
    }
    setProblem(null);
    setBusy(true);
    try {
      const result = await resolvePriceListItem(listId, {
        skuCode: resolveSku,
        quantity: resolveQuantity === '' ? null : Number(resolveQuantity),
      });
      setResolution(result);
      setResolveCandidates([]);
    } catch (e) {
      // 🔴 409 歧义：把候选清单带出来（服务端在 body 里给了 candidates）。
      const body = (e as { body?: { candidates?: PriceListItemResolution['candidates'] } }).body;
      setResolution(null);
      if (body?.candidates?.length) {
        setResolveCandidates(body.candidates);
        setError('同优先级多条有效价目 —— 必须由你指定用哪一条，系统不会随机取一条');
      } else {
        setError(e instanceof Error ? e.message : '解析失败');
      }
    } finally {
      setBusy(false);
    }
  }, [listId, resolveQuantity, resolveSku]);

  const runImport = useCallback(
    async (file: File, apply: boolean) => {
      setBusy(true);
      try {
        const contentBase64 = await fileToBase64(file);
        const report = await importPriceListItems(listId, {
          fileName: file.name,
          contentBase64,
          apply,
        });
        setImportReport(report);
        setNotice(report.summary);
        if (report.applied) {
          await search(0);
          await loadConflicts();
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '导入失败');
      } finally {
        setBusy(false);
      }
    },
    [listId, loadConflicts, search],
  );

  if (!loaded) {
    return <EmptyState title="正在加载价目项…" description="请稍候。" />;
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="价目项"
        description="在价目表内维护 SKU 价格：阶梯价（按数量门槛）、按分类 / 品牌 / SKU 范围批量设价、Excel 导入导出，保存前先看影响范围。"
        actions={
          listId ? (
            <a
              className="text-label-13 text-primary underline"
              href={priceListItemExportUrl(listId)}
              download
            >
              导出 Excel（xlsx）
            </a>
          ) : null
        }
      />

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}
      {problem ? (
        <Notice tone="warning" title="表单还差一点">
          {problem.message}
        </Notice>
      ) : null}

      {/* ---------------- 能力声明 ---------------- */}
      <SectionCard
        title="能力声明"
        description="一价目一币种、阶梯 ≠ 冲突、同优先级必须让人选、导入先报后改。"
      >
        {capabilities ? (
          <div className="space-y-3">
            {capabilities.notes.map((note) => (
              <div key={note.title} className="border-border rounded-md border p-3">
                <p className="text-label-13 font-semibold">{note.title}</p>
                <p className="text-copy-12 text-muted-foreground mt-1">{note.content}</p>
              </div>
            ))}
            <Notice tone="info" title="上游与下游">
              {capabilities.upstreamNote} {capabilities.downstreamNote}
            </Notice>
            <Notice tone="warning" title="跨价目表的优先级不是本模块">
              {capabilities.conflictNote}
            </Notice>
          </div>
        ) : (
          <EmptyState title="能力声明读不到" description="请稍后重试。" />
        )}
      </SectionCard>

      {/* ---------------- 价目表选择 ---------------- */}
      <SectionCard
        title="价目表"
        description="价目项的币种来自这张表 —— 一张表只有一个币种，价目项结构上无法跨币种。"
      >
        {lists.length === 0 ? (
          <EmptyState
            title="还没有价目表"
            description="请先在「价目表」分段新建一张价目表，再回来维护它的价目项。"
          />
        ) : (
          <div className="space-y-2">
            <div className="flex flex-wrap gap-2">
              {lists.map((list) => (
                <button
                  key={list.id}
                  type="button"
                  onClick={() => setListId(list.id)}
                  className={
                    list.id === listId
                      ? 'border-primary text-primary text-label-13 rounded-md border px-3 py-1'
                      : 'border-border text-label-13 rounded-md border px-3 py-1'
                  }
                >
                  {list.name}（{list.currency}）
                </button>
              ))}
            </div>
            {current ? (
              <p className="text-copy-12 text-muted-foreground">
                当前价目表：{current.name} · 币种 <b>{current.currency}</b> · 生效区间{' '}
                {intervalText(current.validFrom, current.validTo)} · 表内价目项 {current.itemCount}{' '}
                个
              </p>
            ) : null}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 价目项台账 ---------------- */}
      <SectionCard
        title="价目项台账"
        description="真分页 + 关键词 / 启用状态 / 此刻是否生效筛选。"
        extra={
          <Button variant="secondary" onClick={() => void search(0)} disabled={busy || !listId}>
            筛选
          </Button>
        }
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="item-keyword" label="关键词">
              <Input
                value={keyword}
                placeholder="SKU 编码或名称"
                onChange={(e) => setKeyword(e.target.value)}
              />
            </FormField>
            <FormField id="item-enabled" label="启用状态">
              <select
                id="item-enabled"
                className="border-border text-label-13 h-9 w-full rounded-md border px-2"
                value={enabledFilter}
                onChange={(e) => setEnabledFilter(e.target.value)}
              >
                <option value="">全部</option>
                <option value="true">启用</option>
                <option value="false">停用</option>
              </select>
            </FormField>
            <FormField id="item-effective" label="此刻是否生效">
              <select
                id="item-effective"
                className="border-border text-label-13 h-9 w-full rounded-md border px-2"
                value={effectiveFilter}
                onChange={(e) => setEffectiveFilter(e.target.value)}
              >
                <option value="">全部</option>
                <option value="true">此刻生效</option>
                <option value="false">此刻不生效</option>
              </select>
            </FormField>
          </div>

          {items.length === 0 ? (
            <EmptyState
              title={keyword || enabledFilter || effectiveFilter ? '筛选无结果' : '还没有价目项'}
              description={
                keyword || enabledFilter || effectiveFilter
                  ? '换个筛选条件试试。'
                  : '用下面的表单新增一条，或者用批量设价一次写入多个 SKU。'
              }
            />
          ) : (
            <div className="space-y-2">
              {items.map((item) => (
                <div key={item.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="min-w-0">
                      <p className="text-label-13 font-semibold">
                        {item.skuCode} · {item.skuName}
                      </p>
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        单价 {priceText(item.unitPrice, item.currency)} · 门槛{' '}
                        {quantityText(item.minQuantity)} · 优先级 {priorityText(item.priority)} ·{' '}
                        {intervalText(item.validFrom, item.validTo)}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge tone={effectiveTone(item.effectiveNow)}>
                        {effectiveText(item.effectiveNow)}
                      </Badge>
                      {!item.enabled ? <Badge tone="neutral">已停用</Badge> : null}
                      <Button variant="secondary" onClick={() => void open(item)}>
                        编辑
                      </Button>
                    </div>
                  </div>
                </div>
              ))}
              <div className="flex items-center justify-between">
                <span className="text-copy-12 text-muted-foreground">
                  共 {total} 条 · 第 {page + 1} / {pages} 页
                </span>
                <div className="flex gap-2">
                  <Button
                    variant="secondary"
                    disabled={page <= 0 || busy}
                    onClick={() => void search(page - 1)}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={page + 1 >= pages || busy}
                    onClick={() => void search(page + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 价目项表单 ---------------- */}
      <SectionCard
        title={editing ? '修改价目项' : '新增价目项'}
        description="一条价目项 = 一个阶梯档位；数量门槛 0 = 基础档；优先级数字越小越优先。"
        extra={
          <Button onClick={() => void submit()} disabled={busy || !listId}>
            {editing ? '保存修改' : '新增价目项'}
          </Button>
        }
      >
        <div className="grid gap-3 md:grid-cols-3">
          <FormField id="item-sku" label="SKU 编码" required>
            <Input
              value={form.skuCode}
              placeholder="SKU-001"
              onChange={(e) => patch({ skuCode: e.target.value })}
            />
          </FormField>
          <FormField id="item-price" label="单价" required hint="不能是负数">
            <Input
              type="number"
              step="0.000001"
              value={form.unitPrice ?? ''}
              onChange={(e) =>
                patch({ unitPrice: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField id="item-min-quantity" label="数量门槛" hint="0 = 基础档">
            <Input
              type="number"
              step="0.000001"
              value={form.minQuantity ?? 0}
              onChange={(e) => patch({ minQuantity: Number(e.target.value) })}
            />
          </FormField>
          <FormField id="item-priority" label="优先级" hint="数字越小越优先">
            <Input
              type="number"
              value={form.priority ?? 0}
              onChange={(e) => patch({ priority: Number(e.target.value) })}
            />
          </FormField>
          <FormField id="item-valid-from" label="生效起点（含）" required>
            <Input
              type="datetime-local"
              value={form.validFrom ? form.validFrom.slice(0, 16) : ''}
              onChange={(e) =>
                patch({ validFrom: e.target.value ? new Date(e.target.value).toISOString() : '' })
              }
            />
          </FormField>
          <FormField id="item-valid-to" label="生效终点（不含）" hint="留空 = 长期有效">
            <Input
              type="datetime-local"
              value={form.validTo ? form.validTo.slice(0, 16) : ''}
              onChange={(e) =>
                patch({ validTo: e.target.value ? new Date(e.target.value).toISOString() : null })
              }
            />
          </FormField>
          <FormField id="item-note" label="备注">
            <Input value={form.note} onChange={(e) => patch({ note: e.target.value })} />
          </FormField>
        </div>
        {editing ? (
          <div className="mt-3">
            <Button variant="secondary" onClick={reset}>
              取消编辑
            </Button>
          </div>
        ) : null}
      </SectionCard>

      {/* ---------------- 批量设价 ---------------- */}
      <SectionCard
        title="批量设价"
        description="固定值 / 按百分比 / 按类目统一；范围 = 分类 / 品牌 / SKU 清单。保存前先预览影响范围。"
        extra={
          <div className="flex gap-2">
            <Button
              variant="secondary"
              onClick={() => void runPreview()}
              disabled={busy || !listId}
            >
              预览影响范围
            </Button>
            <Button onClick={() => void runApply()} disabled={busy || !listId}>
              执行
            </Button>
          </div>
        }
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="batch-mode" label="模式" required>
              <select
                id="batch-mode"
                className="border-border text-label-13 h-9 w-full rounded-md border px-2"
                value={batch.mode}
                onChange={(e) => patchBatch({ mode: e.target.value as BatchMode })}
              >
                {batchModeOptions().map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </FormField>
            {batch.mode === 'CATEGORY_UNIFIED' ? null : (
              <FormField id="batch-scope" label="范围" required>
                <select
                  id="batch-scope"
                  className="border-border text-label-13 h-9 w-full rounded-md border px-2"
                  value={batch.scope ?? 'CATEGORY'}
                  onChange={(e) => patchBatch({ scope: e.target.value as BatchScope })}
                >
                  {batchScopeOptions().map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </FormField>
            )}
            {batch.mode === 'CATEGORY_UNIFIED' ? null : batch.scope === 'CATEGORY' ? (
              <FormField id="batch-category" label="分类" required hint="分类名取自 spu.category">
                <Input
                  value={batch.category ?? ''}
                  onChange={(e) => patchBatch({ category: e.target.value })}
                />
              </FormField>
            ) : batch.scope === 'BRAND' ? (
              <FormField id="batch-brand" label="品牌" required hint="品牌名取自 spu.brand">
                <Input
                  value={batch.brand ?? ''}
                  onChange={(e) => patchBatch({ brand: e.target.value })}
                />
              </FormField>
            ) : (
              <FormField id="batch-skus" label="SKU 编码清单" required hint="逗号或换行分隔">
                <Input
                  value={batch.skuCodes.join(',')}
                  onChange={(e) =>
                    patchBatch({
                      skuCodes: e.target.value
                        .split(/[,\s]+/)
                        .map((code) => code.trim())
                        .filter(Boolean),
                    })
                  }
                />
              </FormField>
            )}
            {batch.mode === 'FIXED' ? (
              <FormField id="batch-price" label="单价" required>
                <Input
                  type="number"
                  step="0.000001"
                  value={batch.unitPrice ?? ''}
                  onChange={(e) =>
                    patchBatch({ unitPrice: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
            ) : null}
            {batch.mode === 'PERCENTAGE' ? (
              <FormField
                id="batch-percent"
                label="涨跌幅 %"
                required
                hint="10 = 涨 10%，-10 = 降 10%"
              >
                <Input
                  type="number"
                  step="0.01"
                  value={batch.percent ?? ''}
                  onChange={(e) =>
                    patchBatch({ percent: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
            ) : null}
            <FormField id="batch-min-quantity" label="数量门槛" hint="0 = 基础档">
              <Input
                type="number"
                step="0.000001"
                value={batch.minQuantity}
                onChange={(e) => patchBatch({ minQuantity: Number(e.target.value) })}
              />
            </FormField>
            <FormField id="batch-priority" label="优先级" hint="数字越小越优先">
              <Input
                type="number"
                value={batch.priority}
                onChange={(e) => patchBatch({ priority: Number(e.target.value) })}
              />
            </FormField>
            <FormField id="batch-valid-from" label="生效起点（含）" required>
              <Input
                type="datetime-local"
                value={batch.validFrom ? batch.validFrom.slice(0, 16) : ''}
                onChange={(e) =>
                  patchBatch({
                    validFrom: e.target.value ? new Date(e.target.value).toISOString() : '',
                  })
                }
              />
            </FormField>
            <FormField id="batch-valid-to" label="生效终点（不含）" hint="留空 = 长期有效">
              <Input
                type="datetime-local"
                value={batch.validTo ? batch.validTo.slice(0, 16) : ''}
                onChange={(e) =>
                  patchBatch({
                    validTo: e.target.value ? new Date(e.target.value).toISOString() : null,
                  })
                }
              />
            </FormField>
          </div>

          {batch.mode === 'CATEGORY_UNIFIED' ? (
            <div className="space-y-2">
              <p className="text-label-13">分类统一价（每个分类一个价）</p>
              {batch.categoryPrices.map((price, index) => (
                <div key={index} className="flex flex-wrap items-end gap-2">
                  <FormField id={`batch-cat-${index}`} label="分类">
                    <Input
                      value={price.category}
                      onChange={(e) =>
                        patchBatch({
                          categoryPrices: batch.categoryPrices.map((p, i) =>
                            i === index ? { ...p, category: e.target.value } : p,
                          ),
                        })
                      }
                    />
                  </FormField>
                  <FormField id={`batch-cat-price-${index}`} label="单价">
                    <Input
                      type="number"
                      step="0.000001"
                      value={price.unitPrice}
                      onChange={(e) =>
                        patchBatch({
                          categoryPrices: batch.categoryPrices.map((p, i) =>
                            i === index ? { ...p, unitPrice: Number(e.target.value) } : p,
                          ),
                        })
                      }
                    />
                  </FormField>
                  <Button
                    variant="secondary"
                    onClick={() =>
                      patchBatch({
                        categoryPrices: batch.categoryPrices.filter((_, i) => i !== index),
                      })
                    }
                  >
                    移除
                  </Button>
                </div>
              ))}
              <Button
                variant="secondary"
                onClick={() =>
                  patchBatch({
                    categoryPrices: [...batch.categoryPrices, { category: '', unitPrice: 0 }],
                  })
                }
              >
                添加一个分类
              </Button>
            </div>
          ) : null}

          {preview ? (
            <div className="space-y-3">
              {stats.length > 0 ? (
                <div className="grid gap-3 md:grid-cols-5">
                  {stats.map((stat) => (
                    <StatCard
                      key={stat.label}
                      label={stat.label}
                      value={stat.value}
                      comparisonLabel={stat.hint}
                      polarity="neutral"
                    />
                  ))}
                </div>
              ) : null}
              <Notice tone={preview.conflictSkus > 0 ? 'warning' : 'info'} title="影响范围预览">
                {preview.summary}
              </Notice>
              {preview.warnings.map((warning) => (
                <Notice key={warning} tone="warning" title="提醒">
                  {warning}
                </Notice>
              ))}
              {preview.lines.length === 0 ? (
                <EmptyState
                  title="没有命中任何 SKU"
                  description="检查分类 / 品牌 / SKU 编码的写法。"
                />
              ) : (
                <div className="space-y-2">
                  {preview.lines.map((line, index) => (
                    <div
                      key={`${line.skuCode}-${index}`}
                      className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border p-2"
                    >
                      <span className="text-copy-12">
                        {line.skuCode} · 基准 {priceText(line.basePrice)} → 新价{' '}
                        {priceText(line.newPrice, preview.currency)}
                      </span>
                      <span className="flex items-center gap-2">
                        {line.conflict ? <Badge tone="danger">同优先级冲突</Badge> : null}
                        <Badge tone={actionTone(line.action)}>{actionText(line.action)}</Badge>
                      </span>
                    </div>
                  ))}
                  {preview.truncated ? (
                    <p className="text-copy-12 text-muted-foreground">
                      明细只显示了前 {preview.lineLimit} 行（命中 {preview.matchedSkus} 个 SKU）——
                      统计数字是全量的。
                    </p>
                  ) : null}
                </div>
              )}
            </div>
          ) : null}
        </div>
      </SectionCard>

      {/* ---------------- 冲突清单 ---------------- */}
      <SectionCard
        title="冲突清单"
        description="同 SKU + 同数量门槛 + 同优先级 + 生效区间重叠 —— 在重叠窗口里这一档无法唯一解析。"
      >
        {conflicts.length === 0 ? (
          <EmptyState
            title="没有冲突"
            description="这张价目表里没有出现同优先级且生效区间重叠的价目项。"
          />
        ) : (
          <div className="space-y-2">
            {conflicts.map((conflict, index) => (
              <div
                key={`${conflict.skuId}-${index}`}
                className="border-border rounded-md border p-3"
              >
                <p className="text-label-13 font-semibold">
                  {conflict.skuCode} · 门槛 {quantityText(conflict.minQuantity)} · 优先级{' '}
                  {conflict.priority}
                </p>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  {conflictWindowText(conflict)}
                </p>
                <div className="mt-2 space-y-1">
                  {conflict.candidates.map((candidate) => (
                    <div key={candidate.id} className="flex items-center gap-2">
                      <Badge tone={selectionTone(candidate.selectionState)}>
                        {candidate.selectionStateLabel}
                      </Badge>
                      <span className="text-copy-12">
                        {priceText(candidate.unitPrice)} · 优先级 {candidate.priority} ·{' '}
                        {intervalText(candidate.validFrom, candidate.validTo)}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 按时刻解析 ---------------- */}
      <SectionCard
        title="按时刻解析"
        description="这个 SKU 在这张表此刻该卖多少。命中多条同优先级有效价目时会报错并列出候选 —— 系统不随机取。"
        extra={
          <Button variant="secondary" onClick={() => void runResolve()} disabled={busy || !listId}>
            解析
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-2">
            <FormField id="resolve-sku" label="SKU 编码" required>
              <Input
                value={resolveSku}
                placeholder="SKU-001"
                onChange={(e) => setResolveSku(e.target.value)}
              />
            </FormField>
            <FormField id="resolve-quantity" label="数量" hint="阶梯价按数量门槛适用">
              <Input
                type="number"
                step="0.000001"
                value={resolveQuantity}
                onChange={(e) => setResolveQuantity(e.target.value)}
              />
            </FormField>
          </div>
          {resolution ? (
            <div className="space-y-2">
              <Notice
                tone={resolution.ambiguous ? 'warning' : 'success'}
                title={resolution.ambiguous ? '歧义' : '解析结果'}
              >
                {resolution.ambiguous
                  ? (resolution.ambiguityProblem ?? '存在多条同优先级有效价目')
                  : `单价 ${priceText(resolution.unitPrice, resolution.currency)}`}
              </Notice>
              {resolution.warnings.map((warning) => (
                <Notice key={warning} tone="warning" title="提醒">
                  {warning}
                </Notice>
              ))}
              <div className="space-y-1">
                {resolution.candidates.map((candidate) => (
                  <div key={candidate.id} className="flex items-center gap-2">
                    <Badge tone={selectionTone(candidate.selectionState)}>
                      {candidate.selectionStateLabel}
                    </Badge>
                    <span className="text-copy-12">
                      {priceText(candidate.unitPrice, resolution.currency)} · 门槛{' '}
                      {quantityText(candidate.minQuantity)} · 优先级 {candidate.priority} ·{' '}
                      {intervalText(candidate.validFrom, candidate.validTo)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          ) : null}
          {resolveCandidates.length > 0 ? (
            <div className="space-y-2">
              <Notice tone="danger" title="必须由你指定用哪一条">
                下面是并列的候选 —— 系统不会随机挑一条，也不会折成一条。请改优先级或关掉其中一条。
              </Notice>
              <div className="space-y-1">
                {resolveCandidates.map((candidate) => (
                  <div key={candidate.id} className="flex items-center gap-2">
                    <Badge tone={selectionTone(candidate.selectionState)}>
                      {candidate.selectionStateLabel}
                    </Badge>
                    <span className="text-copy-12">
                      {priceText(candidate.unitPrice)} · 门槛 {quantityText(candidate.minQuantity)}{' '}
                      · 优先级 {candidate.priority} ·{' '}
                      {intervalText(candidate.validFrom, candidate.validTo)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          ) : null}
        </div>
      </SectionCard>

      {/* ---------------- Excel 导入 ---------------- */}
      <SectionCard
        title="Excel 导入"
        description="先出校验报告（哪一行错、为什么）；有错整批不落库。改完文件重新上传即可。"
      >
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-3">
            <input
              id="import-file"
              type="file"
              accept=".xlsx"
              className="text-label-13"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void runImport(file, false);
              }}
            />
            <Button
              variant="secondary"
              disabled={busy || !listId || !importReport}
              onClick={() => {
                const input = document.getElementById('import-file') as HTMLInputElement | null;
                const file = input?.files?.[0];
                if (file) void runImport(file, true);
              }}
            >
              校验无误，落库
            </Button>
          </div>
          {importReport ? (
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <Badge tone={importStatusTone(importReport.status)}>
                  {importStatusText(importReport.status)}
                </Badge>
                <span className="text-copy-12 text-muted-foreground">{importReport.summary}</span>
              </div>
              {importReport.rows.length === 0 ? (
                <EmptyState title="没有可校验的数据行" description="检查文件里是不是只有表头。" />
              ) : (
                <div className="space-y-2">
                  {importReport.rows.map((row) => (
                    <div
                      key={row.rowNo}
                      className="border-border text-copy-12 rounded-md border p-2"
                    >
                      <p>
                        第 {row.rowNo} 行 · {row.skuCode || '（SKU 编码为空）'} ·{' '}
                        {priceText(row.unitPrice)} · 门槛 {quantityText(row.minQuantity)}
                      </p>
                      {row.messages.length > 0 ? (
                        <ul className="mt-1 list-disc pl-5">
                          {row.messages.map((message) => (
                            <li key={message}>{message}</li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <EmptyState
              title="还没有导入过文件"
              description="选一个 .xlsx 文件（表头需要：SKU编码 / 单价 / 生效起点）。"
            />
          )}
        </div>
      </SectionCard>
    </div>
  );
}
