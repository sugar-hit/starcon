'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  DECISION_LABELS,
  REASON_LABELS,
  RELATION_LABELS,
  SORT_LABELS,
  SOURCE_LABELS,
  STATUS_LABELS,
  buildGapQuery,
  buildMapInput,
  createSkuForGap,
  fetchCapabilities,
  fetchFilterOptions,
  fetchGaps,
  fetchSummary,
  formatInstant,
  ignoreGap,
  mapGap,
  priceCell,
  priceFilterScopeHint,
  statusTone,
  unavailableDimensions,
  validateCreateSkuForm,
  validateIgnoreForm,
  validateMapForm,
  type CreateSkuForm,
  type GapFilters,
  type IgnoreForm,
  type MapForm,
  type MappingGapCapabilities,
  type MappingGapFilterOptions,
  type MappingGapPage,
  type MappingGapRow,
  type MappingGapSummary,
} from './mapping-gap-model';

/**
 * 2.4.2.3 映射缺失队列。
 *
 * <p>蓝图加粗句：「**不按名称猜 SKU**」在页面上的落点：
 *
 * <ol>
 *   <li><b>不出现「建议 SKU」列</b>。页面只摆「哪个平台变体还没有内部 SKU」，
 *       决策由人给。放一个"推荐"列哪怕标着"仅供参考"，赶订单的人也会去选它。</li>
 *   <li><b>「映射到已有 SKU」只接受明确的内部 SKU id</b>，不接受名字 ——
 *       服务端也会拒（模型层的 {@code validateMapForm} 先挡一道）。</li>
 *   <li><b>「新建 SKU」只登记意图</b>，按钮旁明写「03 侧未接线，不会真的创建 SKU」，
 *       提交后的提示也照实说「没有创建任何 SKU」。</li>
 * </ol>
 *
 * <p>另外三件事也在页面上明说：09 订单来源**未接线**、价格**归 05 定价**（不画假价格）、
 * 映射建立后的**受影响订单回填未接线**（所以不显示任何订单条数）。
 *
 * <p>这是**真实工作区**：清单、筛选、三个操作入口全部走
 * {@code /channel/mapping-gaps} 真实端点，加载 / 空 / 错误三态齐全。
 */
export function MappingGapWorkspace() {
  const [page, setPage] = useState<MappingGapPage | null>(null);
  const [summary, setSummary] = useState<MappingGapSummary | null>(null);
  const [capabilities, setCapabilities] = useState<MappingGapCapabilities | null>(null);
  const [options, setOptions] = useState<MappingGapFilterOptions | null>(null);
  const [filters, setFilters] = useState<GapFilters>({ sort: 'RECENT' });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [active, setActive] = useState<{ row: MappingGapRow; action: 'map' | 'sku' | 'ignore' } | null>(
    null,
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [list, sum, caps, opts] = await Promise.all([
        fetchGaps(filters, 0, 50),
        fetchSummary(),
        fetchCapabilities(),
        fetchFilterOptions(),
      ]);
      setPage(list);
      setSummary(sum);
      setCapabilities(caps);
      setOptions(opts);
    } catch (e) {
      setError(e instanceof Error ? e.message : '待映射队列加载失败');
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = (next: Partial<GapFilters>) => setFilters((prev) => ({ ...prev, ...next }));

  const stats = useMemo(() => {
    const items = page?.items ?? [];
    return {
      pending: summary?.pendingTotal ?? 0,
      mapped: summary?.byStatus.find((c) => c.key === 'MAPPED')?.total ?? 0,
      ignored: summary?.byStatus.find((c) => c.key === 'IGNORED')?.total ?? 0,
      oldestDays: items.length ? Math.max(...items.map((r) => r.waitingDays)) : 0,
    };
  }, [page, summary]);

  const unavailable = useMemo(() => unavailableDimensions(capabilities), [capabilities]);
  const hasFilter = Object.entries(filters).some(
    ([key, value]) => key !== 'sort' && value !== undefined && value !== '',
  );

  async function run(action: () => Promise<{ message: string }>, ok: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await action();
      setNotice(`${ok}。${result.message}`);
      setActive(null);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="映射缺失队列"
        description="平台商品已同步但还没有映射到内部 SKU 的记录，等人工建立映射、登记新建 SKU，或标记忽略。绝不按名称猜 SKU —— 队列只呈现待决策的缺口，决策由人给。"
        actions={
          <Button variant="ghost" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} aria-hidden />
            刷新
          </Button>
        }
      />

      {error && (
        <div role="alert" className="rounded-lg border border-red-300 bg-red-50 p-3 text-sm text-red-800">
          {error}
        </div>
      )}
      {notice && (
        <div
          role="status"
          className="rounded-lg border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-900"
        >
          {notice}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="待映射" value={stats.pending} unit="条" />
        <StatCard label="已建立映射" value={stats.mapped} unit="条" />
        <StatCard label="已忽略" value={stats.ignored} unit="条" />
        <StatCard
          label="本页最久未处理"
          value={stats.oldestDays}
          unit="天"
          polarity={stats.oldestDays > 7 ? 'negative' : 'neutral'}
        />
      </div>

      {unavailable.length > 0 && (
        <SectionCard
          title="本模块未接入什么"
          description="未交付的环节显式列出（归属模块 + 不交付的影响），界面据此禁用而不是让人点了才报错。"
        >
          <ul className="space-y-2 text-sm">
            {unavailable.map((d) => (
              <li key={d.dimension} className="flex items-start gap-2">
                <Badge tone="neutral">未交付</Badge>
                <span>
                  {d.description}
                  {d.ownerModule && <span className="ml-1 text-slate-500">（归属模块 {d.ownerModule}）</span>}
                  {d.impact && <span className="block text-xs text-slate-500">{d.impact}</span>}
                </span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      <SectionCard
        title="待映射清单"
        description="六个筛选维度全在服务端执行：店铺 / 平台 / 变体关键词 / 价格区间 / 状态 / 来源。"
      >
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          <label className="space-y-1 text-sm">
            <span className="font-medium">店铺</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={filters.shopCode ?? ''}
              onChange={(e) => patch({ shopCode: e.target.value })}
            >
              <option value="">全部店铺</option>
              {(options?.shops ?? []).map((s) => (
                <option key={s.shopCode} value={s.shopCode}>
                  {s.shopCode} · {s.platformCode}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">平台</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={filters.platformCode ?? ''}
              onChange={(e) => patch({ platformCode: e.target.value })}
            >
              <option value="">全部平台</option>
              {(options?.platformCodes ?? []).map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">变体 / 商品关键词</span>
            <Input
              value={filters.search ?? ''}
              placeholder="标题 / 平台商品 ID / 变体 ID / 店铺 / 订单号"
              onChange={(e) => patch({ search: e.target.value })}
            />
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">状态</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={filters.status ?? ''}
              onChange={(e) => patch({ status: e.target.value })}
            >
              <option value="">全部状态</option>
              {(options?.statuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {STATUS_LABELS[s as keyof typeof STATUS_LABELS] ?? s}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">来源</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={filters.source ?? ''}
              onChange={(e) => patch({ source: e.target.value })}
            >
              <option value="">全部来源</option>
              {(options?.sources ?? []).map((s) => (
                <option key={s} value={s}>
                  {SOURCE_LABELS[s as keyof typeof SOURCE_LABELS] ?? s}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">排序</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={filters.sort ?? 'RECENT'}
              onChange={(e) => patch({ sort: e.target.value })}
            >
              {(options?.sorts ?? Object.keys(SORT_LABELS)).map((s) => (
                <option key={s} value={s}>
                  {SORT_LABELS[s as keyof typeof SORT_LABELS] ?? s}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">价格区间（下限）</span>
            <Input
              type="number"
              step="0.01"
              min="0"
              value={filters.minPrice ?? ''}
              onChange={(e) => patch({ minPrice: e.target.value })}
            />
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">价格区间（上限）</span>
            <Input
              type="number"
              step="0.01"
              min="0"
              value={filters.maxPrice ?? ''}
              onChange={(e) => patch({ maxPrice: e.target.value })}
            />
          </label>
        </div>

        <p className="mt-3 text-xs text-slate-500">
          {priceFilterScopeHint(capabilities, page?.priceSnapshotInPage ?? 0)}
        </p>
      </SectionCard>

      <SectionCard title="缺口明细" description="一条缺口 = 一个还没有内部 SKU 的平台变体。">
        {loading && <p className="text-sm text-slate-500">加载中…</p>}
        {!loading && (page?.items.length ?? 0) === 0 && (
          <EmptyState
            title={hasFilter ? '当前筛选没有匹配到缺口' : '没有待映射的缺口'}
            description={
              hasFilter
                ? '换个筛选条件再试。注意：价格区间只覆盖带平台价快照的行，平台商品来源当前没有价格快照。'
                : '平台商品同步进来、但还没有映射到内部 SKU 的变体会自动出现在这里。'
            }
          />
        )}
        {!loading && (page?.items.length ?? 0) > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[72rem] text-left text-sm">
              <thead className="border-b border-slate-200 text-slate-500">
                <tr>
                  <th className="py-2 pr-3">平台商品 / 变体</th>
                  <th className="py-2 pr-3">店铺 / 平台</th>
                  <th className="py-2 pr-3">价格</th>
                  <th className="py-2 pr-3">来源 / 原因</th>
                  <th className="py-2 pr-3">状态 / 决策</th>
                  <th className="py-2 pr-3">等待</th>
                  <th className="py-2 pr-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {(page?.items ?? []).map((row) => {
                  const price = priceCell(row, capabilities);
                  return (
                    <tr key={row.id} className="border-b border-slate-100 align-top">
                      <td className="py-2 pr-3">
                        <div>{row.titleSnapshot || '（无标题）'}</div>
                        <div className="text-xs text-slate-500">
                          {row.externalItemId} · {row.externalVariantId || '—'}
                        </div>
                      </td>
                      <td className="py-2 pr-3 text-xs">
                        <div>{row.shopCode}</div>
                        <div className="text-slate-500">{row.platformCode}</div>
                      </td>
                      <td className="py-2 pr-3 text-xs">
                        {price.available ? (
                          <span>
                            {price.value} {price.currency}
                          </span>
                        ) : (
                          <Badge tone="warning">{price.ownerModule ?? '价格未接入'}</Badge>
                        )}
                      </td>
                      <td className="py-2 pr-3 text-xs">
                        <div>{SOURCE_LABELS[row.source as keyof typeof SOURCE_LABELS] ?? row.source}</div>
                        <div className="text-slate-500">
                          {REASON_LABELS[row.gapReason as keyof typeof REASON_LABELS] ?? row.gapReason}
                        </div>
                        {row.orderRef && <div className="text-slate-500">订单 {row.orderRef}</div>}
                      </td>
                      <td className="py-2 pr-3 text-xs">
                        <Badge tone={statusTone(row.status)}>
                          {STATUS_LABELS[row.status as keyof typeof STATUS_LABELS] ?? row.status}
                        </Badge>
                        <div className="mt-1 text-slate-500">
                          {DECISION_LABELS[row.decision as keyof typeof DECISION_LABELS] ?? row.decision}
                        </div>
                        {row.mappingVersionId && (
                          <div className="mt-1 text-slate-500">
                            回填版本 {row.mappingVersionId.slice(0, 8)}（历史事实，不可改写）
                          </div>
                        )}
                      </td>
                      <td className="py-2 pr-3 text-xs">{row.waitingDays} 天</td>
                      <td className="py-2 pr-3">
                        {row.status === 'PENDING' ? (
                          <div className="flex flex-col gap-1">
                            <Button
                              variant="ghost"
                              onClick={() => setActive({ row, action: 'map' })}
                              disabled={busy}
                            >
                              映射到已有 SKU
                            </Button>
                            <Button
                              variant="ghost"
                              onClick={() => setActive({ row, action: 'sku' })}
                              disabled={busy}
                            >
                              新建 SKU
                            </Button>
                            <Button
                              variant="ghost"
                              onClick={() => setActive({ row, action: 'ignore' })}
                              disabled={busy}
                            >
                              标记忽略
                            </Button>
                          </div>
                        ) : (
                          <span className="text-xs text-slate-500">已离队</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {active && (
        <ActionPanel
          row={active.row}
          action={active.action}
          busy={busy}
          onCancel={() => setActive(null)}
          onSubmit={run}
        />
      )}

      {capabilities && (
        <SectionCard title="能力声明" description="上游 / 下游依赖与未交付段，原样透出服务端措辞。">
          <p className="text-sm">{capabilities.dependencyNote}</p>
          <p className="mt-3 text-sm text-slate-600">{capabilities.gapNote}</p>
        </SectionCard>
      )}
    </div>
  );
}

/** 三个操作入口共用的操作面板。 */
function ActionPanel({
  row,
  action,
  busy,
  onCancel,
  onSubmit,
}: {
  row: MappingGapRow;
  action: 'map' | 'sku' | 'ignore';
  busy: boolean;
  onCancel: () => void;
  onSubmit: (run: () => Promise<{ message: string }>, ok: string) => Promise<void>;
}) {
  const [mapForm, setMapForm] = useState<MapForm>({
    internalSkuId: '',
    relationType: 'ONE_TO_ONE',
    componentQuantity: '',
    changeReason: '',
  });
  const [skuForm, setSkuForm] = useState<CreateSkuForm>({
    requestedCode: '',
    requestedName: '',
    requestedBarcode: '',
    requestedBaseUnit: '',
    note: '',
  });
  const [ignoreForm, setIgnoreForm] = useState<IgnoreForm>({ reason: '', note: '' });

  const mapError = validateMapForm(mapForm);
  const skuError = validateCreateSkuForm(skuForm);
  const ignoreError = validateIgnoreForm(ignoreForm);

  const title =
    action === 'map' ? '映射到已有 SKU' : action === 'sku' ? '新建 SKU' : '标记忽略';

  return (
    <SectionCard title={title} description={`平台变体 ${row.externalVariantId || row.listingVariantId} · ${row.titleSnapshot}`}>
      {action === 'map' && (
        <div className="space-y-4">
          <div
            role="note"
            className="flex items-start gap-2 rounded-lg border border-slate-300 bg-slate-50 p-3 text-sm text-slate-700"
          >
            <TriangleAlert className="mt-0.5 shrink-0" aria-hidden />
            <p>
              只能填写<strong>明确的内部 SKU id</strong>（03 商品主数据的逻辑 id）。
              本模块<strong>不按名称或标题相似度猜 SKU</strong> —— 候选与相似度在
              <code className="mx-1">/listings/mappings</code>，且恒需人工确认。
            </p>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="font-medium">内部 SKU id</span>
              <Input
                value={mapForm.internalSkuId}
                placeholder="完整 UUID"
                onChange={(e) => setMapForm((f) => ({ ...f, internalSkuId: e.target.value }))}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">映射基数</span>
              <select
                className="w-full rounded-md border border-slate-300 px-2 py-1.5"
                value={mapForm.relationType}
                onChange={(e) =>
                  setMapForm((f) => ({ ...f, relationType: e.target.value as MapForm['relationType'] }))
                }
              >
                {Object.entries(RELATION_LABELS).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            {mapForm.relationType === 'ONE_TO_MANY' && (
              <label className="space-y-1 text-sm">
                <span className="font-medium">组件数量</span>
                <Input
                  type="number"
                  step="0.000001"
                  min="0"
                  value={mapForm.componentQuantity}
                  onChange={(e) =>
                    setMapForm((f) => ({ ...f, componentQuantity: e.target.value }))
                  }
                />
              </label>
            )}
            <label className="space-y-1 text-sm md:col-span-2">
              <span className="font-medium">变更原因</span>
              <Input
                value={mapForm.changeReason}
                placeholder="为什么这样建（版本历史靠它回溯）"
                onChange={(e) => setMapForm((f) => ({ ...f, changeReason: e.target.value }))}
              />
            </label>
          </div>
          {mapError && <p className="text-sm text-red-700">{mapError}</p>}
          <div className="flex gap-2">
            <Button
              disabled={busy || !!mapError}
              onClick={() =>
                void onSubmit(() => mapGap(row.id, buildMapInput(mapForm)), '已建立映射')
              }
            >
              {busy ? '提交中…' : '确认映射'}
            </Button>
            <Button variant="ghost" onClick={onCancel} disabled={busy}>
              取消
            </Button>
          </div>
        </div>
      )}

      {action === 'sku' && (
        <div className="space-y-4">
          <div
            role="note"
            className="flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
          >
            <TriangleAlert className="mt-0.5 shrink-0" aria-hidden />
            <p>
              <strong>03 商品主数据侧目前没有接线。</strong>
              本操作只<strong>登记建档请求</strong>（落库、可查、可撤），请求会停在「待派发」，
              <strong>不会真的创建 SKU</strong>，也不会自动把变体映射上去 —— 那一步仍要人工建立映射。
            </p>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="font-medium">期望的 SKU 编码</span>
              <Input
                value={skuForm.requestedCode}
                onChange={(e) => setSkuForm((f) => ({ ...f, requestedCode: e.target.value }))}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">期望的 SKU 名称</span>
              <Input
                value={skuForm.requestedName}
                onChange={(e) => setSkuForm((f) => ({ ...f, requestedName: e.target.value }))}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">期望的条码</span>
              <Input
                value={skuForm.requestedBarcode}
                onChange={(e) => setSkuForm((f) => ({ ...f, requestedBarcode: e.target.value }))}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">期望的基本单位</span>
              <Input
                value={skuForm.requestedBaseUnit}
                onChange={(e) => setSkuForm((f) => ({ ...f, requestedBaseUnit: e.target.value }))}
              />
            </label>
            <label className="space-y-1 text-sm md:col-span-2">
              <span className="font-medium">备注</span>
              <Input
                value={skuForm.note}
                onChange={(e) => setSkuForm((f) => ({ ...f, note: e.target.value }))}
              />
            </label>
          </div>
          {skuError && <p className="text-sm text-red-700">{skuError}</p>}
          <div className="flex gap-2">
            <Button
              disabled={busy || !!skuError}
              onClick={() =>
                void onSubmit(
                  () =>
                    createSkuForGap(row.id, {
                      requestedCode: skuForm.requestedCode.trim(),
                      requestedName: skuForm.requestedName.trim(),
                      requestedBarcode: skuForm.requestedBarcode.trim(),
                      requestedBaseUnit: skuForm.requestedBaseUnit.trim(),
                      note: skuForm.note.trim(),
                    }),
                  '已登记建档请求',
                )
              }
            >
              {busy ? '提交中…' : '登记建档请求'}
            </Button>
            <Button variant="ghost" onClick={onCancel} disabled={busy}>
              取消
            </Button>
          </div>
        </div>
      )}

      {action === 'ignore' && (
        <div className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="font-medium">忽略原因</span>
              <Input
                value={ignoreForm.reason}
                placeholder="为什么这条不用映射"
                onChange={(e) => setIgnoreForm((f) => ({ ...f, reason: e.target.value }))}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">备注</span>
              <Input
                value={ignoreForm.note}
                onChange={(e) => setIgnoreForm((f) => ({ ...f, note: e.target.value }))}
              />
            </label>
          </div>
          {ignoreError && <p className="text-sm text-red-700">{ignoreError}</p>}
          <div className="flex gap-2">
            <Button
              disabled={busy || !!ignoreError}
              onClick={() =>
                void onSubmit(
                  () =>
                    ignoreGap(row.id, {
                      reason: ignoreForm.reason.trim(),
                      note: ignoreForm.note.trim(),
                    }),
                  '已标记忽略',
                )
              }
            >
              {busy ? '提交中…' : '确认忽略'}
            </Button>
            <Button variant="ghost" onClick={onCancel} disabled={busy}>
              取消
            </Button>
          </div>
        </div>
      )}
    </SectionCard>
  );
}
