'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import { History, Loader2, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  activeFilterCount,
  affectedOrdersText,
  applyFilterChange,
  changedFieldLabel,
  changeSourceLabel,
  effectiveWindow,
  emptyFilters,
  fetchCapabilities,
  fetchFilterOptions,
  fetchVersion,
  fetchVersions,
  formatMoment,
  hasNextPage,
  hasPrevPage,
  pageSummary,
  relationLabel,
  resetFilters,
  unavailableDimensions,
  type ListingMappingVersionCapabilities,
  type MappingVersionDetail,
  type VersionFilterOptions,
  type VersionFilters,
  type VersionPage,
} from './mapping-version-model';

/**
 * 4.1.2.2 映射版本 工作区。
 *
 * <p>蓝图：「映射变更时生成新版本而非原地修改，保留每个版本对应的商品/订单范围，支持查看某订单使用的是哪个版本、按版本回溯受影响订单。输入：映射变更；生效时间点；变更原因。输出：<b>版本列表 + 受影响订单范围查询</b>。关系：上游 = 4.1.2.1；下游 = 09 订单（`order_line_component` 冻结）。<b>改映射不影响已产生的订单</b>，只对新订单生效——这是财务与售后追溯的前提。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>改映射不影响既有版本</b>：列表逐版给出版本号 / 生效时间点 / 变更原因，
 *       详情区把「相对上一版改了什么」由服务端真比对后列出。前端不自己 diff ——
 *       字段缺失时前端 diff 会把「没变」读成「变了」。</li>
 *   <li><b>商品范围是冻结快照</b>：详情区把「版本落库当时的标题」与「平台侧当前标题」
 *       并排给出，漂移时给徽章。合成一个字段的话，平台重新同步一次就会让历史悄悄改写。</li>
 *   <li><b>未交付段如实显示，且不画空列表</b>：「受影响订单范围」在 09 订单侧尚无真实来源，
 *       本页显示归属模块与不可用的原因，<b>绝不</b>画一个「0 条受影响订单」的表格 ——
 *       空表格会被读成「这一版确实没影响任何订单」。</li>
 *   <li><b>筛选与分页全在服务端</b>：前端只翻译 query string。</li>
 * </ol>
 */
export function MappingVersionWorkspace() {
  const [filters, setFilters] = useState<VersionFilters>(emptyFilters);
  const [page, setPage] = useState<VersionPage | null>(null);
  const [caps, setCaps] = useState<ListingMappingVersionCapabilities | null>(null);
  const [options, setOptions] = useState<VersionFilterOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 详情：选中的一版 + 它的商品范围 + 受影响订单范围的交付状态
  const [detail, setDetail] = useState<MappingVersionDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(await fetchVersions(filters));
    } catch (e) {
      setError(e instanceof Error ? e.message : '版本列表加载失败');
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
      } catch {
        // 能力声明读不到不该让整个页面瘫掉：列表才是主内容，
        // 「未交付段明账」这一块退化成不显示即可（下面 missing 为空）。
        setCaps(null);
      }
    })();
  }, []);

  const openVersion = useCallback(async (id: string) => {
    setDetailLoading(true);
    setDetailError(null);
    try {
      setDetail(await fetchVersion(id));
    } catch (e) {
      setDetail(null);
      setDetailError(e instanceof Error ? e.message : '版本详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const missing = useMemo(() => unavailableDimensions(caps), [caps]);
  const affectedNote = affectedOrdersText(detail?.affectedOrders);

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="映射版本"
        description="映射变更生成新版本而不是原地改：每个版本逐条留存生效时间点与变更原因，旧版本只读可回溯。改映射不影响已产生的订单，只对新订单生效。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            {loading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
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

      {/* 🔴 未交付段明账：逐条列出归属模块与影响，不画 0 也不画占位 */}
      {missing.length > 0 && (
        <SectionCard
          title="本模块未接入的能力"
          description="如实列出而不是填 0 或留空：0 会被读成「这件事已经做完了」。"
        >
          <ul className="space-y-2">
            {missing.map((d) => (
              <li key={d.dimension} className="text-copy-13 flex flex-wrap items-baseline gap-2">
                <Badge tone="warning">{d.dimension}</Badge>
                <span className="text-foreground">{d.description}</span>
                <span className="text-muted-foreground">归 {d.ownerModule}</span>
                <span className="text-muted-foreground">{d.impact}</span>
              </li>
            ))}
          </ul>
          {caps?.gapNote && (
            <p className="text-copy-13 text-muted-foreground mt-3 border-t pt-3">{caps.gapNote}</p>
          )}
        </SectionCard>
      )}

      {/* 筛选 */}
      <SectionCard
        title="筛选"
        description="筛选与分页都在服务端：前端只翻译 query string，不在浏览器里过滤已分页的数据。"
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="映射 ID">
            <Input
              value={filters.mappingId}
              onChange={(e) => setFilters(applyFilterChange(filters, { mappingId: e.target.value }))}
              placeholder="listing_mapping 的 UUID"
            />
          </Field>
          <Field label="内部 SKU">
            <Input
              value={filters.internalSkuId}
              onChange={(e) =>
                setFilters(applyFilterChange(filters, { internalSkuId: e.target.value }))
              }
              placeholder="03 的 SKU 逻辑 ID"
            />
          </Field>
          <Field label="平台商品 ID">
            <Input
              value={filters.listingId}
              onChange={(e) => setFilters(applyFilterChange(filters, { listingId: e.target.value }))}
              placeholder="listing 的 UUID"
            />
          </Field>
          <Field label="店铺编码">
            <Input
              value={filters.shopCode}
              onChange={(e) => setFilters(applyFilterChange(filters, { shopCode: e.target.value }))}
              placeholder="精确匹配"
            />
          </Field>
          <Field label="生效状态">
            <Select
              value={filters.effectiveState}
              onChange={(v) => setFilters(applyFilterChange(filters, { effectiveState: v }))}
              options={[
                { value: '', label: '全部' },
                { value: 'CURRENT', label: '当前生效' },
                { value: 'CLOSED', label: '已关闭' },
              ]}
            />
          </Field>
          <Field label="变更来源">
            <Select
              value={filters.changeSource}
              onChange={(v) => setFilters(applyFilterChange(filters, { changeSource: v }))}
              options={[
                { value: '', label: '全部' },
                ...(options?.changeSources ?? []).map((s) => ({
                  value: s,
                  label: changeSourceLabel(s),
                })),
              ]}
            />
          </Field>
          <Field label="生效区间起">
            <Input
              type="datetime-local"
              value={filters.windowFrom}
              onChange={(e) => setFilters(applyFilterChange(filters, { windowFrom: e.target.value }))}
              placeholder="窗口起点"
            />
          </Field>
          <Field label="生效区间止">
            <Input
              type="datetime-local"
              value={filters.windowTo}
              onChange={(e) => setFilters(applyFilterChange(filters, { windowTo: e.target.value }))}
              placeholder="窗口终点"
            />
          </Field>
          <Field label="关键词" className="sm:col-span-2">
            <Input
              value={filters.search}
              onChange={(e) => setFilters(applyFilterChange(filters, { search: e.target.value }))}
              placeholder="变更原因 / 平台商品 ID / 标题 / 变体 / 店铺 / SKU"
            />
          </Field>
          <Field label="排序">
            <Select
              value={filters.sort}
              onChange={(v) => setFilters(applyFilterChange(filters, { sort: v }))}
              options={[
                { value: 'RECENT', label: '最近落库' },
                { value: 'EFFECTIVE', label: '生效时间（新→旧）' },
                { value: 'OLDEST', label: '生效时间（旧→新）' },
                { value: 'VERSION', label: '版本号（同映射内 1→N）' },
              ]}
            />
          </Field>
        </div>
        <p className="text-copy-13 text-muted-foreground mt-3">
          生效区间按<b>重叠</b>筛选：一版只要在窗口内曾经生效过就会被列出来。
          已启用 {activeFilterCount(filters)} 个筛选条件。
          <button
            type="button"
            className="ml-3 underline underline-offset-2"
            onClick={() => setFilters(resetFilters(filters))}
          >
            清空
          </button>
        </p>
      </SectionCard>

      {/* 版本列表 */}
      <SectionCard
        title="版本列表"
        description="改映射生成新版本而不是原地改：历史版本只读，生效时间点与变更原因逐版可查。"
        extra={<span className="text-copy-13 text-muted-foreground">{pageSummary(page)}</span>}
      >
        {loading && !page ? (
          <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
            <Loader2 className="h-4 w-4 animate-spin" />
            加载版本列表…
          </div>
        ) : page && page.items.length === 0 ? (
          <EmptyState
            title="没有符合条件的版本"
            description="改一次映射就会在这里留下一条版本记录；换几个筛选条件再试，或去 4.1.2.1 建立映射。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2">版本</th>
                  <th className="px-3 py-2">生效时间点</th>
                  <th className="px-3 py-2">平台商品（快照）</th>
                  <th className="px-3 py-2">基数</th>
                  <th className="px-3 py-2">内部 SKU</th>
                  <th className="px-3 py-2">变更原因</th>
                  <th className="px-3 py-2">来源</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                {page?.items.map((v) => (
                  <tr key={v.id} className="border-b last:border-0">
                    <td className="px-3 py-2">
                      <span className="font-medium">v{v.versionNo}</span>
                      {v.current && (
                        <Badge tone="success" className="ml-2">
                          当前生效
                        </Badge>
                      )}
                    </td>
                    <td className="px-3 py-2 text-muted-foreground">
                      {effectiveWindow(v)}
                    </td>
                    <td className="px-3 py-2">
                      <div>{v.titleSnapshot}</div>
                      <div className="text-muted-foreground">
                        {v.shopCode} · {v.externalItemId} · {v.externalVariantId}
                      </div>
                    </td>
                    <td className="px-3 py-2">{relationLabel(v.relationType)}</td>
                    <td className="px-3 py-2 font-mono text-xs break-all">{v.internalSkuId ?? '—'}</td>
                    <td className="px-3 py-2">{v.changeReason}</td>
                    <td className="px-3 py-2 text-muted-foreground">
                      {changeSourceLabel(v.changeSource)}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <Button variant="outline" size="sm" onClick={() => void openVersion(v.id)}>
                        <History className="h-4 w-4" />
                        回溯
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-3 flex items-center justify-between">
          <span className="text-copy-13 text-muted-foreground">{pageSummary(page)}</span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={!hasPrevPage(page) || loading}
              onClick={() => setFilters({ ...filters, page: Math.max(0, filters.page - 1) })}
            >
              上一页
            </Button>
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

      {/* 版本详情 */}
      {detailError && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{detailError}</span>
        </div>
      )}

      {detail && (
        <SectionCard
          title={`v${detail.version.versionNo} 回溯`}
          description="这一版当时是什么、生效了哪段时间、覆盖哪个平台商品、相对上一版改了什么。"
        >
          <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <Fact label="版本号">
              v{detail.version.versionNo}
              {detail.previousVersionNo !== null && (
                <span className="text-muted-foreground"> （上一版 v{detail.previousVersionNo}）</span>
              )}
              {detail.nextVersionNo !== null && (
                <span className="text-muted-foreground"> （下一版 v{detail.nextVersionNo}）</span>
              )}
            </Fact>
            <Fact label="生效时间点">{effectiveWindow(detail.version)}</Fact>
            <Fact label="映射基数">{relationLabel(detail.version.relationType)}</Fact>
            <Fact label="内部 SKU">
              <span className="font-mono text-xs break-all">{detail.version.internalSkuId ?? '—'}</span>
            </Fact>
            <Fact label="变更原因">{detail.version.changeReason}</Fact>
            <Fact label="变更来源">{changeSourceLabel(detail.version.changeSource)}</Fact>
            <Fact label="落库时刻">{formatMoment(detail.version.createdAt)}</Fact>
            <Fact label="操作者">
              <span className="font-mono text-xs break-all">{detail.version.actorId ?? '—'}</span>
            </Fact>
            <Fact label="相对上一版改了什么">
              {detail.changedFields.length === 0 ? (
                <span className="text-muted-foreground">
                  {detail.previousVersionNo === null ? '首版，没有可比对的上一版' : '与上一版逐字段一致'}
                </span>
              ) : (
                <div className="flex flex-wrap gap-1">
                  {detail.changedFields.map((f) => (
                    <Badge key={f} tone="info">
                      {changedFieldLabel(f)}
                    </Badge>
                  ))}
                </div>
              )}
            </Fact>
          </dl>

          {/* 🔴 商品范围：快照与平台当前值并排，漂移要说出来 */}
          <div className="mt-4 border-t pt-4">
            <h3 className="text-copy-13 font-medium">该版本对应的商品范围（冻结快照）</h3>
            <p className="text-copy-13 text-muted-foreground mt-1">{detail.productScope.note}</p>
            <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Fact label="当时（版本落库时）">
                <div>{detail.productScope.titleSnapshot}</div>
                <div className="text-muted-foreground">
                  {detail.productScope.shopCode} · {detail.productScope.externalItemId} ·{' '}
                  {detail.productScope.externalVariantId}
                </div>
              </Fact>
              <Fact label="平台侧当前值">
                <div>
                  {detail.productScope.currentTitle ?? '（平台商品已删除）'}
                  {detail.productScope.driftedFromPlatform && (
                    <Badge tone="warning" className="ml-2">
                      与快照不一致
                    </Badge>
                  )}
                </div>
                <div className="text-muted-foreground">
                  {detail.productScope.currentExternalItemId ?? '—'}
                </div>
              </Fact>
            </dl>
          </div>

          {/* 🔴 受影响订单范围：未交付段只写归属与原因，绝不画「0 条」表格 */}
          <div className="mt-4 border-t pt-4">
            <h3 className="text-copy-13 font-medium">受影响订单范围</h3>
            {detail.affectedOrders.available ? (
              // 本模块当前不交付这一段（available 恒为 false）。真接上之后这里换成订单清单，
              // 但**不要**在清单还没接上时先画一张空表格。
              <p className="text-copy-13 text-muted-foreground mt-1">
                订单清单由 {detail.affectedOrders.ownerModule} 提供，本模块不跨服务读取订单数据。
              </p>
            ) : (
              <div className="mt-2">
                <p className="text-copy-13 text-warning-soft">{affectedNote}</p>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  前置条件：{detail.affectedOrders.prerequisite}
                </p>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  本模块能真实交付的是「该版本对应的商品范围」与「改了什么」，两者都已落地。
                </p>
              </div>
            )}
          </div>

          {detailLoading && (
            <p className="text-copy-13 text-muted-foreground mt-3">正在读取版本详情…</p>
          )}
        </SectionCard>
      )}
    </div>
  );
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
    <label className={`flex flex-col gap-1 ${className ?? ''}`}>
      <span className="text-copy-13 text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}

function Select({
  value,
  onChange,
  options,
}: {
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <select
      className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    >
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-copy-13 text-muted-foreground">{label}</dt>
      <dd className="text-copy-13 mt-0.5">{children}</dd>
    </div>
  );
}
