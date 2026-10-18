'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
} from '@starcon/ui';
import { Loader2, PackageSearch, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  actionLabel,
  applyFilterChange,
  batchSupport,
  batchSummary,
  emptyFilters,
  fetchBatchTasks,
  fetchCapabilities,
  fetchFilterOptions,
  fetchListings,
  hasNextPage,
  hasPrevPage,
  pageSummary,
  priceCell,
  activeFilterCount,
  mappingLabel,
  resetFilters,
  runBatch,
  statusLabel,
  stockLabel,
  unavailableDimensions,
  type BatchAction,
  type ListingBatchResult,
  type ListingCapabilities,
  type ListingFilterOptions,
  type ListingFilters,
  type ListingPage,
  type ListingRow,
} from './listing-model';

/**
 * 4.1.1.1 Listing 列表 工作区。
 *
 * <p>蓝图：「按店铺/站点/状态/负责人/类目/是否有映射/是否缺货筛选平台商品，展示内部 SKU、价格、库存、销量、评分、
 * 近 7 日状态变化，支持批量改价改库存（跳 05/07）、批量下架、批量分配负责人。输出：分页列表（含内部 SKU 映射状态、
 * 缺货标记、审核状态）；批量操作任务；点击进入 4.1.1.2。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>价格位不画假数据</b>。卷 05 定价整卷未交付，服务端 `priceAvailable=false`，
 *       价格列渲染的是 `priceCell()` 给出的「未接入（归 05 定价）」，**不是 0、不是 "--"、不是猜的价**。
 *   <li><b>「未映射」与「缺货」是两个独立筛选</b>。筛选面板里是两个独立控件，表格里是两列，
 *       徽章文案也刻意不互相靠近 —— 一个已映射但平台缺货的商品必须能被同时筛出来并同时看见。
 *   <li><b>筛选 / 排序 / 分页全在服务端</b>。前端只翻译 query string，不在浏览器里过滤已分页的数据
 *       （那样会得到「总数说 500、实际只筛得出当前页 50 条」）。
 *   <li><b>改价 / 改库存按钮禁用并说明归属</b>，而不是点了给一个「成功但什么都没做」的提示。
 *       批量结果（含被拒绝的）原样回显，用户能看见「点了改价、系统没做」这条事实。
 * </ol>
 */
export function ListingWorkspace() {
  const [filters, setFilters] = useState<ListingFilters>(emptyFilters);
  const [page, setPage] = useState<ListingPage | null>(null);
  const [caps, setCaps] = useState<ListingCapabilities | null>(null);
  const [options, setOptions] = useState<ListingFilterOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 批量：勾选 + 结果回显
  const [selected, setSelected] = useState<string[]>([]);
  const [action, setAction] = useState<BatchAction>('DELIST');
  const [ownerId, setOwnerId] = useState('');
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkError, setBulkError] = useState<string | null>(null);
  const [bulkResult, setBulkResult] = useState<ListingBatchResult | null>(null);
  const [tasks, setTasks] = useState<ListingBatchResult[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(await fetchListings(filters));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Listing 列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchCapabilities());
        setOptions(await fetchFilterOptions());
        setTasks(await fetchBatchTasks());
      } catch {
        // 能力声明 / 词表 / 任务历史拿不到不影响列表本身，页面仍要能看单。
      }
    })();
  }, []);

  const rows = useMemo(() => page?.items ?? [], [page]);
  const support = batchSupport(action);
  const price = priceCell(caps);
  const missing = unavailableDimensions(caps);
  const activeCount = activeFilterCount(filters);

  // 🔴 本页统计只覆盖**当前这一页**，文案里写明「本页」——
  // 拿 50 条的一页去说「全店 80% 未映射」是本模块最典型的假事实。
  const pageStats = useMemo(() => {
    let unmapped = 0;
    let outOfStock = 0;
    for (const r of rows) {
      if (r.mappingStatus !== 'MAPPED') unmapped += 1;
      if (r.platformStockState === 'OUT_OF_STOCK') outOfStock += 1;
    }
    return { unmapped, outOfStock };
  }, [rows]);

  const toggle = (id: string) =>
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  const toggleAllOnPage = () => {
    const ids = rows.map((r) => r.id);
    const allOn = ids.length > 0 && ids.every((id) => selected.includes(id));
    setSelected((prev) =>
      allOn ? prev.filter((id) => !ids.includes(id)) : [...new Set([...prev, ...ids])],
    );
  };

  const executeBatch = async () => {
    if (selected.length === 0 || !support.supported) return;
    setBulkBusy(true);
    setBulkError(null);
    setBulkResult(null);
    try {
      const result = await runBatch({
        action,
        listingIds: selected,
        ownerId: action === 'ASSIGN_OWNER' ? ownerId.trim() : null,
      });
      setBulkResult(result);
      setSelected([]);
      // 批量改了负责人 / 状态，必须重读，否则界面显示的是旧事实。
      await load();
      setTasks(await fetchBatchTasks());
    } catch (e) {
      setBulkError(e instanceof Error ? e.message : '批量操作失败');
    } finally {
      setBulkBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Listing 列表"
        description="按店铺 / 站点 / 状态 / 负责人 / 类目 / 是否有映射 / 是否缺货筛选平台商品。未映射与缺货是两个独立状态，可分别筛选也可同时命中。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {/* 🔴 未接入维度明账：价格 / 销量 / 内部可售量如实列出，不画 0 也不画占位 */}
      {missing.length > 0 && (
        <SectionCard title="本模块未接入的维度" description="如实列出而不是填 0 或留空：0 会被读成「这个商品卖 0 块」。">
          <ul className="space-y-2">
            {missing.map((d) => (
              <li key={d.dimension} className="text-copy-13 flex flex-wrap items-baseline gap-2">
                <Badge tone="warning">{d.dimension}</Badge>
                <span className="text-foreground">{d.description}</span>
                <span className="text-muted-foreground">归 {d.ownerModule}</span>
              </li>
            ))}
          </ul>
          {price.note && (
            <p className="text-copy-13 text-muted-foreground mt-3 border-t pt-3">{price.note}</p>
          )}
        </SectionCard>
      )}

      {/* 七个筛选维度 */}
      <SectionCard
        title="筛选"
        description={`当前 ${activeCount} 个条件生效，全部走服务端 where 子句。`}
        extra={
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setFilters(resetFilters(filters))}
            disabled={activeCount === 0}
          >
            清空筛选
          </Button>
        }
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="店铺编码">
            <Input
              value={filters.shopCode}
              onChange={(e) =>
                setFilters(applyFilterChange(filters, { shopCode: e.target.value }))
              }
              placeholder="留空 = 全部店铺"
            />
          </Field>
          <Field label="站点">
            <Input
              value={filters.marketplaceCode}
              onChange={(e) =>
                setFilters(applyFilterChange(filters, { marketplaceCode: e.target.value }))
              }
              placeholder="留空 = 全部站点"
            />
          </Field>
          <Field label="平台商品状态">
            <Select
              value={filters.status}
              onChange={(v) => setFilters(applyFilterChange(filters, { status: v }))}
              options={options?.statuses ?? []}
              labels={STATUS_LABELS}
              emptyLabel="全部状态"
            />
          </Field>
          <Field label="负责人 ID">
            <Input
              value={filters.ownerId}
              onChange={(e) => setFilters(applyFilterChange(filters, { ownerId: e.target.value }))}
              placeholder="留空 = 全部负责人"
            />
          </Field>
          <Field label="类目">
            <Input
              value={filters.category}
              onChange={(e) => setFilters(applyFilterChange(filters, { category: e.target.value }))}
              placeholder="按类目路径模糊匹配"
            />
          </Field>
          {/* 🔴 两个独立维度：各自一个控件、各自一条 where 子句 */}
          <Field label="内部 SKU 映射状态">
            <Select
              value={filters.mappingStatus}
              onChange={(v) => setFilters(applyFilterChange(filters, { mappingStatus: v }))}
              options={options?.mappingStatuses ?? []}
              labels={MAPPING_LABELS}
              emptyLabel="全部映射状态"
            />
          </Field>
          <Field label="是否缺货（平台侧）">
            <Select
              value={
                filters.outOfStock === undefined
                  ? ''
                  : filters.outOfStock
                    ? 'OUT'
                    : 'IN'
              }
              onChange={(v) =>
                setFilters(
                  applyFilterChange(filters, {
                    outOfStock: v === '' ? undefined : v === 'OUT',
                  }),
                )
              }
              options={['OUT', 'IN']}
              labels={{ OUT: '仅缺货', IN: '仅有货' }}
              emptyLabel="不限"
            />
          </Field>
          <Field label="排序">
            <Select
              value={filters.sort}
              onChange={(v) => setFilters({ ...filters, sort: v, page: 0 })}
              options={options?.sortFields ?? []}
              labels={SORT_LABELS}
              emptyLabel="默认"
            />
          </Field>
          <Field label="搜索（标题 / 平台商品 ID）" className="sm:col-span-2 lg:col-span-4">
            <Input
              value={filters.search}
              onChange={(e) => setFilters(applyFilterChange(filters, { search: e.target.value }))}
              placeholder="如 B0C… 或商品标题关键词"
            />
          </Field>
        </div>
      </SectionCard>

      {/* 汇总：总数用 total（服务端真实总数），本页统计标明「本页」 */}
      <div className="flex flex-wrap items-center gap-3">
        <Badge tone="primary">共 {page?.total ?? 0} 条</Badge>
        <Badge tone={pageStats.unmapped > 0 ? 'warning' : 'neutral'}>
          本页未映射 / 部分映射 {pageStats.unmapped}
        </Badge>
        <Badge tone={pageStats.outOfStock > 0 ? 'danger' : 'neutral'}>
          本页平台缺货 {pageStats.outOfStock}
        </Badge>
        <span className="text-copy-13 text-muted-foreground">{pageSummary(page)}</span>
      </div>

      {/* 批量操作 */}
      <SectionCard
        title="批量操作"
        description="批量分配负责人与批量下架由本模块执行；批量改价改库存归 05 / 07，按钮禁用并说明归属。"
        extra={
          <Button
            size="sm"
            onClick={() => void executeBatch()}
            disabled={bulkBusy || selected.length === 0 || !support.supported}
          >
            {bulkBusy && <Loader2 className="h-4 w-4 animate-spin" />}
            执行「{actionLabel(action)}」
          </Button>
        }
      >
        <div className="flex flex-wrap items-end gap-3">
          <Field label="动作" className="min-w-48">
            <Select
              value={action}
              onChange={(v) => setAction(v as BatchAction)}
              options={caps?.batchActions ?? ['ASSIGN_OWNER', 'DELIST', 'REPRICE', 'RESTOCK']}
              labels={ACTION_LABELS}
              emptyLabel="选择动作"
            />
          </Field>
          {action === 'ASSIGN_OWNER' && (
            <Field label="目标负责人 ID" className="min-w-48">
              <Input
                value={ownerId}
                onChange={(e) => setOwnerId(e.target.value)}
                placeholder="成员 UUID"
              />
            </Field>
          )}
          <Badge tone={support.supported ? 'info' : 'warning'}>
            已选 {selected.length} 条
          </Badge>
        </div>

        {!support.supported && support.reason && (
          <p className="text-copy-13 text-warning mt-3">{support.reason}</p>
        )}
        {bulkError && (
          <p className="text-copy-13 text-destructive mt-3">{bulkError}</p>
        )}
        {bulkResult && (
          <p
            className={`text-copy-13 mt-3 ${bulkResult.outcome === 'REJECTED' ? 'text-warning' : 'text-success'}`}
          >
            {batchSummary(bulkResult)}
          </p>
        )}
      </SectionCard>

      {/* 列表 */}
      <SectionCard
        title="平台商品"
        description="平台商品 ID 原文照抄，不折叠大小写。内部 SKU 只显示逻辑 ID —— 编码与名称归 03 商品，本模块不复制。"
      >
        {rows.length === 0 && !loading ? (
          <EmptyState
            icon={<PackageSearch className="h-7 w-7" />}
            title="没有符合条件的平台商品"
            description="当前筛选条件下没有数据。可以放宽筛选，或确认平台商品是否已通过 02 同步登记。"
            action={
              activeCount > 0 ? (
                <Button variant="outline" onClick={() => setFilters(resetFilters(filters))}>
                  清空筛选
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="w-10 px-3 py-2">
                    <input
                      type="checkbox"
                      aria-label="全选本页"
                      checked={rows.length > 0 && rows.every((r) => selected.includes(r.id))}
                      onChange={toggleAllOnPage}
                    />
                  </th>
                  <th className="px-3 py-2">平台商品</th>
                  <th className="px-3 py-2">店铺 / 站点</th>
                  <th className="px-3 py-2">状态</th>
                  {/* 🔴 两列分开，且列名刻意不互相靠近 */}
                  <th className="px-3 py-2">内部 SKU 映射</th>
                  <th className="px-3 py-2">平台侧可售</th>
                  <th className="px-3 py-2">售价</th>
                  <th className="px-3 py-2">评分</th>
                  <th className="px-3 py-2">近 7 日状态变化</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} className="border-border/60 border-b last:border-0">
                    <td className="px-3 py-2">
                      <input
                        type="checkbox"
                        aria-label={`选择 ${r.externalItemId}`}
                        checked={selected.includes(r.id)}
                        onChange={() => toggle(r.id)}
                      />
                    </td>
                    <td className="px-3 py-2">
                      <div className="text-foreground font-medium">{r.title}</div>
                      <div className="text-muted-foreground font-mono text-label-12">
                        {r.externalItemId}
                      </div>
                      {r.categoryPath && (
                        <div className="text-muted-foreground text-label-12">{r.categoryPath}</div>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <div>{r.shopName}</div>
                      <div className="text-muted-foreground text-label-12">
                        {r.shopCode}
                        {r.marketplaceCode ? ` · ${r.marketplaceCode}` : ' · 未归站点'}
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={statusTone(r.status)}>{statusLabel(r.status)}</Badge>
                      {r.reviewMessage && (
                        <div className="text-muted-foreground mt-1 text-label-12">
                          {r.reviewMessage}
                        </div>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={mappingTone(r.mappingStatus)}>{mappingLabel(r.mappingStatus)}</Badge>
                      <div className="text-muted-foreground mt-1 text-label-12">
                        {r.mappedVariantCount}/{r.variantCount} 变体
                        {r.internalSkuIds.length > 0 && ` · ${r.internalSkuIds.length} 个内部 SKU`}
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={stockTone(r.platformStockState)}>
                        {stockLabel(r.platformStockState)}
                      </Badge>
                      {r.platformStockQty !== null && (
                        <div className="text-muted-foreground mt-1 text-label-12">
                          平台侧 {String(r.platformStockQty)}
                        </div>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      {/* 🔴 不画 0、不画占位价：服务端说未接入就如实显示未接入 */}
                      {price.available ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        <Badge tone="warning">{price.ownerModule ?? '价格未接入'}</Badge>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      {r.rating === null ? (
                        <span className="text-muted-foreground">平台未评分</span>
                      ) : (
                        <>
                          <span>{String(r.rating)}</span>
                          <span className="text-muted-foreground">
                            {' '}
                            · {r.ratingCount === null ? '—' : String(r.ratingCount)} 条
                          </span>
                        </>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      {r.statusChangeCount7d === 0 ? (
                        <span className="text-muted-foreground">7 日内无变化</span>
                      ) : (
                        <div>
                          <Badge tone="info">{r.statusChangeCount7d} 次</Badge>
                          <ul className="text-muted-foreground mt-1 space-y-0.5 text-label-12">
                            {r.recentStatusChanges.slice(0, 3).map((c, i) => (
                              <li key={`${c.occurredAt}-${i}`}>
                                {statusLabel(c.fromStatus ?? '—')} → {statusLabel(c.toStatus)}
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="border-border mt-4 flex items-center justify-between border-t pt-4">
          <span className="text-copy-13 text-muted-foreground">{pageSummary(page)}</span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={!hasPrevPage(page) || loading}
              onClick={() => setFilters({ ...filters, page: Math.max(0, filters.page - 1) })}
            >
              上一页
            </Button>
            <span className="text-copy-13">第 {(filters.page ?? 0) + 1} 页</span>
            <Button
              variant="outline"
              size="sm"
              disabled={!hasNextPage(page) || loading}
              onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
            >
              下一页
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* 批量任务历史：被拒绝的改价同样可回溯 */}
      {tasks.length > 0 && (
        <SectionCard title="批量操作历史" description="被拒绝的批量操作同样落库留痕 —— 「点了改价、系统没做」必须可回溯。">
          <ul className="space-y-2">
            {tasks.slice(0, 10).map((t) => (
              <li key={t.taskId} className="flex flex-wrap items-center gap-2 text-copy-13">
                <Badge tone={t.outcome === 'REJECTED' ? 'warning' : 'success'}>
                  {t.outcome === 'REJECTED' ? '已拒绝' : '已执行'}
                </Badge>
                <span className="text-foreground">{actionLabel(t.action)}</span>
                <span className="text-muted-foreground">{batchSummary(t)}</span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}

// ================================================================ 小组件与文案

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  ON_SALE: '在售',
  UNDER_REVIEW: '平台审核中',
  REJECTED: '平台驳回',
  DELISTED_BY_US: '主动下架',
  DELISTED_BY_PLATFORM: '平台下架',
  BLOCKED: '被封禁',
};

const MAPPING_LABELS: Record<string, string> = {
  UNMAPPED: '未映射',
  PARTIAL: '部分映射',
  MAPPED: '已映射',
};

const SORT_LABELS: Record<string, string> = {
  RECENT: '最近更新',
  RATING_DESC: '评分从高到低',
  RATING_ASC: '评分从低到高',
  STATUS: '按状态',
  SHOP: '按店铺',
};

const ACTION_LABELS: Record<string, string> = {
  ASSIGN_OWNER: '批量分配负责人',
  DELIST: '批量下架',
  REPRICE: '批量改价（归 05）',
  RESTOCK: '批量改库存（归 07）',
};

function statusTone(status: string): 'success' | 'info' | 'warning' | 'danger' | 'neutral' {
  if (status === 'ON_SALE') return 'success';
  if (status === 'UNDER_REVIEW') return 'info';
  if (status === 'BLOCKED' || status === 'REJECTED') return 'danger';
  if (status === 'DRAFT') return 'neutral';
  return 'warning';
}

/** 🔴 映射状态与缺货的配色刻意用不同语汇，不让两者在视觉上互相冒充。 */
function mappingTone(status: ListingRow['mappingStatus']): 'success' | 'warning' | 'neutral' {
  if (status === 'MAPPED') return 'success';
  if (status === 'PARTIAL') return 'warning';
  return 'neutral';
}

function stockTone(state: ListingRow['platformStockState']): 'success' | 'danger' | 'neutral' {
  if (state === 'IN_STOCK') return 'success';
  if (state === 'OUT_OF_STOCK') return 'danger';
  return 'neutral';
}

function Field({
  label,
  children,
  className,
}: {
  label: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <label className={`flex flex-col gap-1.5 ${className ?? ''}`}>
      <span className="text-muted-foreground text-label-12">{label}</span>
      {children}
    </label>
  );
}

function Select({
  value,
  onChange,
  options,
  labels,
  emptyLabel,
}: {
  value: string;
  onChange: (value: string) => void;
  options: readonly string[];
  labels: Record<string, string>;
  emptyLabel: string;
}) {
  return (
    <select
      className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    >
      <option value="">{emptyLabel}</option>
      {options.map((o) => (
        <option key={o} value={o}>
          {labels[o] ?? o}
        </option>
      ))}
    </select>
  );
}
