'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { History, Plus, RefreshCw, Star, Trash2 } from 'lucide-react';
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
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';

import {
  EMPTY_ITEM_FILTERS,
  EMPTY_STALL_FILTERS,
  collectedAtText,
  collectionStatusText,
  collectionStatusTone,
  createStall,
  emptyCollectedItem,
  emptyStallForm,
  fetchCollectorEndpoint,
  fetchItem,
  fetchItems,
  fetchSkuSources,
  fetchSourceCapabilities,
  fetchSourceOptions,
  fetchSourceOverview,
  fetchStall,
  fetchStalls,
  fetchSupplierCandidates,
  favoriteItem,
  linkSku,
  moqText,
  priceRangeText,
  pushCollection,
  saveCollectorEndpoint,
  syncStall,
  unfavoriteItem,
  validateCollectedItem,
  validateCollectorEndpoint,
  validateStall,
} from './sources-model';
import type {
  CollectedItemInput,
  CollectorEndpointInput,
  CollectorEndpointRow,
  ItemFilters,
  SkuSourceView,
  SourceItemDetail,
  SourceItemPage,
  SourceOverview,
  StallDetail,
  StallFilters,
  StallInput,
  StallPage,
  SupplierCandidateRow,
  SupplierSourceCapabilities,
  SupplierSourceOptions,
  SyncResult,
} from './sources-model';

/**
 * 6.1.2.1 档口与货源池。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**货源是外部快照，会变**。
 * 所以这一页上：
 *
 * <ul>
 *   <li>价格**永远是区间**（下限 ~ 上限），没有一处把它压成「精确价」；
 *   <li>每一行都带着**采集时刻**——那个价是什么时候的，一眼可见；
 *   <li>同步失败时显示的是**失败原因**，不是空列表（抓不到就说抓不到）；
 *   <li>比价按**币种分组**给最低价，不跨币种换算（汇率属 12 财务）。
 * </ul>
 *
 * <p>与 6.1.1.x 供应商列表共用 L3「供应」入口，但**各占一个独立分区**：
 * 那边是供应商主数据的采购视角，这边是外部货源渠道的档口与货源池。
 */
export function SourceWorkspace() {
  const [options, setOptions] = useState<SupplierSourceOptions | null>(null);
  const [overview, setOverview] = useState<SourceOverview | null>(null);
  const [capabilities, setCapabilities] = useState<SupplierSourceCapabilities | null>(null);
  const [stallPage, setStallPage] = useState<StallPage | null>(null);
  const [stallFilters, setStallFilters] = useState<StallFilters>(EMPTY_STALL_FILTERS);
  const [itemPage, setItemPage] = useState<SourceItemPage | null>(null);
  const [itemFilters, setItemFilters] = useState<ItemFilters>(EMPTY_ITEM_FILTERS);
  const [itemDetail, setItemDetail] = useState<SourceItemDetail | null>(null);
  const [skuId, setSkuId] = useState('');
  const [skuView, setSkuView] = useState<SkuSourceView | null>(null);
  const [endpoints, setEndpoints] = useState<CollectorEndpointRow[]>([]);
  const [suppliers, setSuppliers] = useState<SupplierCandidateRow[]>([]);
  const [stallForm, setStallForm] = useState<StallInput | null>(null);
  const [pushedItems, setPushedItems] = useState<CollectedItemInput[]>([emptyCollectedItem()]);
  const [endpointForms, setEndpointForms] = useState<Record<string, CollectorEndpointInput>>({});
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [opts, ov, caps, stalls, items] = await Promise.all([
        fetchSourceOptions(),
        fetchSourceOverview(),
        fetchSourceCapabilities(),
        fetchStalls(stallFilters),
        fetchItems(itemFilters),
      ]);
      setOptions(opts);
      setOverview(ov);
      setCapabilities(caps);
      setEndpoints(opts.collectorEndpoints);
      setStallPage(stalls);
      setItemPage(items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '货源池读不到');
    } finally {
      setLoading(false);
    }
  }, [stallFilters, itemFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadSuppliers = useCallback(async () => {
    try {
      setSuppliers(await fetchSupplierCandidates());
    } catch (e) {
      setError(e instanceof Error ? e.message : '供应商清单读不到');
    }
  }, []);

  useEffect(() => {
    void loadSuppliers();
  }, [loadSuppliers]);

  async function submitStall() {
    if (!stallForm) return;
    const problems = validateStall(stallForm);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setError('');
    setNotice('');
    try {
      const created = await createStall(stallForm);
      setStallForm(null);
      setNotice(`档口「${created.name}」已登记。`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记档口失败');
    }
  }

  async function runSync(stallId: string) {
    setError('');
    setNotice('');
    setSyncResult(null);
    try {
      const result = await syncStall(stallId);
      setSyncResult(result);
      if (result.status === 'FAILED') {
        setError(`同步失败：${result.failureReason}`);
      } else {
        setNotice(
          `同步完成：采到 ${result.itemCount} 条` +
            (result.skippedCount > 0
              ? `，跳过 ${result.skippedCount} 条（${result.skippedReason}）`
              : ''),
        );
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '同步失败');
    }
  }

  async function pushItems(stallId: string) {
    const problems = pushedItems.flatMap((item) => validateCollectedItem(item));
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setError('');
    setNotice('');
    setSyncResult(null);
    try {
      const result = await pushCollection(stallId, pushedItems);
      setSyncResult(result);
      if (result.status === 'FAILED') {
        setError(`采集结果提交失败：${result.failureReason}`);
      } else {
        setNotice(
          `采集结果已入库：写入 ${result.itemCount} 条` +
            (result.skippedCount > 0
              ? `，跳过 ${result.skippedCount} 条（${result.skippedReason}）`
              : ''),
        );
      }
      setPushedItems([emptyCollectedItem()]);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交采集结果失败');
    }
  }

  async function toggleFavorite(itemId: string, favorited: boolean) {
    setError('');
    try {
      if (favorited) {
        await unfavoriteItem(itemId);
        setNotice('已取消收藏。');
      } else {
        await favoriteItem(itemId);
        setNotice('已收藏。');
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '收藏操作失败');
    }
  }

  async function openHistory(itemId: string) {
    setError('');
    try {
      setItemDetail(await fetchItem(itemId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '价格历史读不到');
    }
  }

  async function attachSku(itemId: string, target: string) {
    if (!target.trim()) {
      setError('请先填内部 SKU ID');
      return;
    }
    setError('');
    try {
      await linkSku(itemId, target.trim());
      setNotice('已建立货源与内部 SKU 的关联。');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '建立 SKU 关联失败');
    }
  }

  async function runCompare() {
    if (!skuId.trim()) {
      setError('请先填内部 SKU ID');
      return;
    }
    setError('');
    try {
      setSkuView(await fetchSkuSources(skuId.trim()));
    } catch (e) {
      setError(e instanceof Error ? e.message : '比价读不到');
      setSkuView(null);
    }
  }

  async function saveEndpoint(platform: string) {
    const form = endpointForms[platform];
    if (!form) return;
    const problems = validateCollectorEndpoint(form);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setError('');
    try {
      await saveCollectorEndpoint(platform, form);
      setNotice(`${platform} 的采集接入已保存。`);
      setEndpoints(
        await Promise.all((options?.platforms ?? []).map((p) => fetchCollectorEndpoint(p.value))),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存采集接入失败');
    }
  }

  const stallTotalPages = stallPage
    ? Math.max(Math.ceil(stallPage.total / Math.max(stallPage.size, 1)), 1)
    : 1;
  const itemTotalPages = itemPage
    ? Math.max(Math.ceil(itemPage.total / Math.max(itemPage.size, 1)), 1)
    : 1;

  return (
    <>
      <PageHeader
        title="档口与货源池"
        description="管理 1688 / 义乌购等货源渠道的店铺档口，抓取与同步在售货源（标题、起订量、价格区间、规格、图片），支持收藏、比价与内部 SKU 货源关联。价格与起订量都是**某一次采集的快照值**，采购下单时必须以实际下单金额为准。"
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
      {syncResult?.status === 'FAILED' ? (
        <Notice tone="warning" title="这次采集没有拿到货源">
          {syncResult.failureReason || '外部平台没有返回可用结果。'}
        </Notice>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="档口"
          value={String(overview?.stallCount ?? 0)}
          comparisonLabel={`启用 ${overview?.activeStallCount ?? 0} · 停用 ${overview?.pausedStallCount ?? 0}`}
        />
        <StatCard
          label="货源条目"
          value={String(overview?.itemCount ?? 0)}
          polarity="neutral"
          comparisonLabel={`在售 ${overview?.onSaleCount ?? 0} · 下架 ${overview?.offSaleCount ?? 0}`}
        />
        <StatCard
          label="收藏"
          value={String(overview?.favoriteCount ?? 0)}
          polarity="neutral"
          comparisonLabel={`SKU 关联 ${overview?.skuLinkCount ?? 0} 条`}
        />
        <StatCard
          label="采集失败"
          value={String(overview?.failedCollectionCount ?? 0)}
          polarity={overview?.failedCollectionCount ? 'negative' : 'neutral'}
          comparisonLabel={
            overview?.lastCollectedAt
              ? `最近采集 ${collectedAtText(overview.lastCollectedAt)}`
              : '从未采集'
          }
        />
      </div>

      <Tabs defaultValue="stalls" className="mt-6">
        <TabsList>
          <TabsTrigger value="stalls">档口</TabsTrigger>
          <TabsTrigger value="items">货源条目</TabsTrigger>
          <TabsTrigger value="compare">SKU 比价</TabsTrigger>
          <TabsTrigger value="endpoints">采集接入</TabsTrigger>
          <TabsTrigger value="boundary">能力与边界</TabsTrigger>
        </TabsList>

        {/* ============================================================ 档口 */}
        <TabsContent value="stalls">
          <SectionCard title="筛选">
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="sKeyword" label="关键词" hint="编码 / 名称 / 平台档口 ID">
                <Input
                  id="sKeyword"
                  value={stallFilters.keyword}
                  placeholder="全部"
                  onChange={(e) =>
                    setStallFilters({ ...stallFilters, keyword: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="sPlatform" label="平台">
                <select
                  id="sPlatform"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={stallFilters.platform}
                  onChange={(e) =>
                    setStallFilters({ ...stallFilters, platform: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.platforms ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="sMethod" label="采集方式">
                <select
                  id="sMethod"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={stallFilters.collectMethod}
                  onChange={(e) =>
                    setStallFilters({ ...stallFilters, collectMethod: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.collectMethods ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="sStatus" label="状态">
                <select
                  id="sStatus"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={stallFilters.status}
                  onChange={(e) =>
                    setStallFilters({ ...stallFilters, status: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.statuses ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <label className="text-muted-foreground flex items-center gap-2 text-xs">
                <input
                  type="checkbox"
                  checked={stallFilters.favoritedOnly}
                  onChange={(e) =>
                    setStallFilters({ ...stallFilters, favoritedOnly: e.target.checked, page: 0 })
                  }
                />
                只看有收藏货源的档口
              </label>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => setStallFilters({ ...EMPTY_STALL_FILTERS })}
              >
                重置筛选
              </Button>
            </div>
            {stallPage?.note ? (
              <p className="text-muted-foreground mt-2 text-xs">{stallPage.note}</p>
            ) : null}
          </SectionCard>

          <SectionCard
            title={`档口（${stallPage?.total ?? 0}）`}
            extra={
              <Button size="sm" variant="outline" onClick={() => setStallForm(emptyStallForm())}>
                <Plus className="h-4 w-4" />
                登记档口
              </Button>
            }
            className="mt-4"
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在读取档口…</p>
            ) : stallPage && stallPage.items.length > 0 ? (
              <div className="space-y-3">
                {stallPage.items.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Link
                        href={`/supply/sources/${row.id}`}
                        className="text-foreground text-sm font-medium underline-offset-4 hover:underline"
                      >
                        {row.name}
                      </Link>
                      <Badge tone="info">{row.platformLabel}</Badge>
                      <Badge tone="neutral">{row.collectMethodLabel}</Badge>
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'warning'}>
                        {row.statusLabel}
                      </Badge>
                      <Badge tone={collectionStatusTone(row.lastCollectionStatus)}>
                        {collectionStatusText(row.lastCollectionStatus, row.lastFailureReason)}
                      </Badge>
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      编码 {row.code} · 平台档口 ID {row.platformStallId}
                      {row.supplierName ? ` · 供应商 ${row.supplierName}` : ' · 未关联供应商'} ·
                      在售货源 {row.itemCount} 条 · 收藏 {row.favoriteCount} 条 · SKU 关联{' '}
                      {row.skuLinkCount} 条
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      最近采集：{collectedAtText(row.lastCollectedAt)}
                    </div>
                    <div className="mt-2 flex flex-wrap gap-2">
                      {row.collectMethod === 'URL_SYNC' ? (
                        <Button size="sm" variant="outline" onClick={() => runSync(row.id)}>
                          <RefreshCw className="h-4 w-4" />
                          同步
                        </Button>
                      ) : (
                        <Link href={`/supply/sources/${row.id}`}>
                          <Button size="sm" variant="outline">
                            提交采集结果
                          </Button>
                        </Link>
                      )}
                    </div>
                  </div>
                ))}
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">
                    第 {stallPage.page + 1} / {stallTotalPages} 页，共 {stallPage.total} 个档口
                  </span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={stallPage.page <= 0}
                      onClick={() =>
                        setStallFilters({ ...stallFilters, page: Math.max(0, stallPage.page - 1) })
                      }
                    >
                      上一页
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={stallPage.page + 1 >= stallTotalPages}
                      onClick={() => setStallFilters({ ...stallFilters, page: stallPage.page + 1 })}
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              </div>
            ) : (
              <EmptyState
                title="还没有登记任何档口"
                description="档口是外部货源平台上的店铺。登记后可以抓取（或由插件推送）在售货源。"
                action={
                  <Button size="sm" onClick={() => setStallForm(emptyStallForm())}>
                    <Plus className="h-4 w-4" />
                    登记第一个档口
                  </Button>
                }
              />
            )}
          </SectionCard>

          {stallForm ? (
            <SectionCard title="登记档口" className="mt-4">
              <div className="grid gap-3 md:grid-cols-3">
                <FormField id="code" label="档口编码" hint="租户内唯一，建后不可改">
                  <Input
                    id="code"
                    value={stallForm.code}
                    onChange={(e) => setStallForm({ ...stallForm, code: e.target.value })}
                  />
                </FormField>
                <FormField id="platform" label="平台">
                  <select
                    id="platform"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={stallForm.platform}
                    onChange={(e) => setStallForm({ ...stallForm, platform: e.target.value })}
                  >
                    {(options?.platforms ?? []).map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="platformStallId" label="平台档口 ID" hint="外部平台上的店铺 ID">
                  <Input
                    id="platformStallId"
                    value={stallForm.platformStallId}
                    onChange={(e) =>
                      setStallForm({ ...stallForm, platformStallId: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="name" label="档口名称">
                  <Input
                    id="name"
                    value={stallForm.name}
                    onChange={(e) => setStallForm({ ...stallForm, name: e.target.value })}
                  />
                </FormField>
                <FormField id="url" label="档口 URL">
                  <Input
                    id="url"
                    value={stallForm.url}
                    onChange={(e) => setStallForm({ ...stallForm, url: e.target.value })}
                  />
                </FormField>
                <FormField id="collectMethod" label="采集方式">
                  <select
                    id="collectMethod"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={stallForm.collectMethod}
                    onChange={(e) => setStallForm({ ...stallForm, collectMethod: e.target.value })}
                  >
                    {(options?.collectMethods ?? []).map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="supplier" label="关联供应商" hint="catalog 只读（可留空）">
                  <select
                    id="supplier"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={stallForm.supplierPartyId ?? ''}
                    onChange={(e) =>
                      setStallForm({
                        ...stallForm,
                        supplierPartyId: e.target.value || null,
                      })
                    }
                  >
                    <option value="">暂不关联</option>
                    {suppliers.map((s) => (
                      <option key={s.roleId} value={s.roleId}>
                        {s.partyName}（{s.partyCode}）
                      </option>
                    ))}
                  </select>
                </FormField>
              </div>
              <div className="mt-3 flex gap-2">
                <Button size="sm" onClick={submitStall}>
                  保存
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setStallForm(null)}>
                  取消
                </Button>
              </div>
            </SectionCard>
          ) : null}
        </TabsContent>

        {/* ============================================================ 货源条目 */}
        <TabsContent value="items">
          <SectionCard title="筛选">
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="iKeyword" label="关键词" hint="标题 / 平台货源 ID">
                <Input
                  id="iKeyword"
                  value={itemFilters.keyword}
                  placeholder="全部"
                  onChange={(e) =>
                    setItemFilters({ ...itemFilters, keyword: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="iPlatform" label="平台">
                <select
                  id="iPlatform"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={itemFilters.platform}
                  onChange={(e) =>
                    setItemFilters({ ...itemFilters, platform: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.platforms ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="iOnSale" label="在售状态" hint="只由平台显式返回的状态决定">
                <select
                  id="iOnSale"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={itemFilters.onSale}
                  onChange={(e) =>
                    setItemFilters({
                      ...itemFilters,
                      onSale: e.target.value as ItemFilters['onSale'],
                      page: 0,
                    })
                  }
                >
                  <option value="ALL">全部</option>
                  <option value="ON_SALE">在售</option>
                  <option value="OFF_SALE">已下架</option>
                </select>
              </FormField>
              <FormField id="iSort" label="排序" hint="价格排序按下限升序">
                <select
                  id="iSort"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={itemFilters.sort}
                  onChange={(e) =>
                    setItemFilters({ ...itemFilters, sort: e.target.value, page: 0 })
                  }
                >
                  {(options?.sorts ?? []).map((o) => (
                    <option key={o.key} value={o.key}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="iPriceFrom" label="价格下限筛选" hint="区间重叠即命中">
                <Input
                  id="iPriceFrom"
                  value={itemFilters.priceFrom}
                  placeholder="不限"
                  onChange={(e) =>
                    setItemFilters({ ...itemFilters, priceFrom: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="iPriceTo" label="价格上限筛选">
                <Input
                  id="iPriceTo"
                  value={itemFilters.priceTo}
                  placeholder="不限"
                  onChange={(e) =>
                    setItemFilters({ ...itemFilters, priceTo: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="iSku" label="内部 SKU ID" hint="只看关联到这个 SKU 的货源">
                <Input
                  id="iSku"
                  value={itemFilters.skuId}
                  placeholder="不限"
                  onChange={(e) =>
                    setItemFilters({ ...itemFilters, skuId: e.target.value, page: 0 })
                  }
                />
              </FormField>
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <label className="text-muted-foreground flex items-center gap-2 text-xs">
                <input
                  type="checkbox"
                  checked={itemFilters.favoritedOnly}
                  onChange={(e) =>
                    setItemFilters({ ...itemFilters, favoritedOnly: e.target.checked, page: 0 })
                  }
                />
                只看已收藏
              </label>
              <label className="text-muted-foreground flex items-center gap-2 text-xs">
                <input
                  type="checkbox"
                  checked={itemFilters.desc}
                  onChange={(e) =>
                    setItemFilters({ ...itemFilters, desc: e.target.checked, page: 0 })
                  }
                />
                倒序
              </label>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => setItemFilters({ ...EMPTY_ITEM_FILTERS })}
              >
                重置筛选
              </Button>
            </div>
            {itemPage?.note ? (
              <p className="text-muted-foreground mt-2 text-xs">{itemPage.note}</p>
            ) : null}
          </SectionCard>

          <SectionCard title={`货源条目（${itemPage?.total ?? 0}）`} className="mt-4">
            {loading ? (
              <p className="text-muted-foreground text-sm">正在读取货源条目…</p>
            ) : itemPage && itemPage.items.length > 0 ? (
              <div className="space-y-3">
                {itemPage.items.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-foreground text-sm font-medium">{row.title}</span>
                      <Badge tone="info">{row.platformLabel}</Badge>
                      <Badge tone={row.onSale ? 'success' : 'neutral'}>
                        {row.onSale ? '在售' : '已下架'}
                      </Badge>
                      {row.favorited ? <Badge tone="warning">已收藏</Badge> : null}
                      {row.skuCodes.length > 0 ? (
                        <Badge tone="neutral">SKU {row.skuCodes.join(' / ')}</Badge>
                      ) : null}
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      档口 {row.stallName}
                      {row.supplierName ? ` · 供应商 ${row.supplierName}` : ''} · 平台货源 ID{' '}
                      {row.platformItemId} · 规格 {row.spec || '—'}
                    </div>
                    <div className="text-foreground mt-1 text-sm">
                      价格区间 {priceRangeText(row.priceMin, row.priceMax, row.currency)} · 起订量{' '}
                      {moqText(row.moq)}
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      第 {row.snapshotSeq} 版快照 · 采集于 {collectedAtText(row.collectedAt)}
                    </div>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => toggleFavorite(row.id, row.favorited)}
                      >
                        <Star className="h-4 w-4" />
                        {row.favorited ? '取消收藏' : '收藏'}
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => openHistory(row.id)}>
                        <History className="h-4 w-4" />
                        价格历史
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => attachSku(row.id, itemFilters.skuId)}
                      >
                        关联当前 SKU
                      </Button>
                    </div>
                  </div>
                ))}
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">
                    第 {itemPage.page + 1} / {itemTotalPages} 页，共 {itemPage.total} 条
                  </span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={itemPage.page <= 0}
                      onClick={() =>
                        setItemFilters({ ...itemFilters, page: Math.max(0, itemPage.page - 1) })
                      }
                    >
                      上一页
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={itemPage.page + 1 >= itemTotalPages}
                      onClick={() => setItemFilters({ ...itemFilters, page: itemPage.page + 1 })}
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              </div>
            ) : (
              <EmptyState
                title="货源池还是空的"
                description="货源条目来自外部平台的采集。先登记档口，再同步（或由插件推送），货源才会出现在这里。"
              />
            )}
          </SectionCard>

          {itemDetail ? (
            <SectionCard
              title={`价格历史 · ${itemDetail.item.title}`}
              description={itemDetail.note}
              extra={
                <Button size="sm" variant="ghost" onClick={() => setItemDetail(null)}>
                  收起
                </Button>
              }
              className="mt-4"
            >
              <div className="space-y-2">
                {itemDetail.snapshots.map((snap) => (
                  <div
                    key={snap.id}
                    className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-xs"
                  >
                    <span className="text-foreground font-medium">第 {snap.snapshotSeq} 版</span>
                    {snap.current ? <Badge tone="success">当前</Badge> : null}
                    <span>
                      {priceRangeText(snap.priceMin, snap.priceMax, snap.currency)} · 起订量{' '}
                      {moqText(snap.moq)}
                    </span>
                    <span className="text-muted-foreground">
                      采集于 {collectedAtText(snap.collectedAt)} · 采集批次 {snap.collectionStatus}
                    </span>
                    <span className="text-muted-foreground">{snap.onSale ? '在售' : '已下架'}</span>
                  </div>
                ))}
              </div>
            </SectionCard>
          ) : null}
        </TabsContent>

        {/* ============================================================ SKU 比价 */}
        <TabsContent value="compare">
          <SectionCard
            title="内部 SKU 货源比价"
            description="按币种分组给出最低价；本模块不跨币种换算。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <FormField id="cmpSku" label="内部 SKU ID" hint="catalog 的 SKU id">
                <Input
                  id="cmpSku"
                  value={skuId}
                  placeholder="例如 6f1c…"
                  onChange={(e) => setSkuId(e.target.value)}
                />
              </FormField>
              <Button size="sm" onClick={runCompare}>
                比价
              </Button>
            </div>
            {skuView ? (
              <div className="mt-4 space-y-3">
                <div className="text-muted-foreground text-xs">
                  {skuView.skuCode ? `${skuView.skuCode} · ${skuView.skuName}` : 'SKU'} · 关联{' '}
                  {skuView.linkCount} 条
                </div>
                {skuView.candidates.length > 0 ? (
                  <>
                    {skuView.lowestByCurrency.map((low) => (
                      <Notice key={low.currency} tone="success" title={`${low.currency} 最低价`}>
                        {low.title} · {priceRangeText(low.priceMin, low.priceMax, low.currency)} ·
                        起订量 {moqText(low.moq)}
                      </Notice>
                    ))}
                    <div className="space-y-2">
                      {skuView.candidates.map((c) => (
                        <div
                          key={c.itemId}
                          className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-xs"
                        >
                          <span className="text-foreground font-medium">{c.title}</span>
                          {c.lowestInCurrency ? <Badge tone="success">该币种最低</Badge> : null}
                          <span>{c.stallName}</span>
                          {c.supplierName ? <span>· {c.supplierName}</span> : null}
                          <span>
                            {priceRangeText(c.priceMin, c.priceMax, c.currency)} · 起订量{' '}
                            {moqText(c.moq)}
                          </span>
                          <span className="text-muted-foreground">
                            采集于 {collectedAtText(c.collectedAt)}
                          </span>
                        </div>
                      ))}
                    </div>
                  </>
                ) : (
                  <EmptyState
                    title="这个 SKU 还没有关联任何货源"
                    description="在「货源条目」分区里给某条货源建立 SKU 关联后，这里会给出候选货源与各币种最低价。"
                  />
                )}
                <p className="text-muted-foreground text-xs">{skuView.note}</p>
              </div>
            ) : null}
          </SectionCard>
        </TabsContent>

        {/* ============================================================ 采集接入 */}
        <TabsContent value="endpoints">
          <SectionCard
            title="采集接入登记"
            description="只登记**引用**：凭证填 secret:// / kv:// / vault:// / external-secrets:// 开头的引用名，本系统不保存任何明文密钥。"
          >
            <div className="space-y-4">
              {(options?.platforms ?? []).map((platform) => {
                const current = endpoints.find((e) => e.platform === platform.value);
                const form = endpointForms[platform.value] ?? {
                  baseUrl: current?.baseUrl ?? '',
                  credentialRef: current?.credentialRef ?? '',
                  wired: current?.wired ?? false,
                  note: current?.note ?? '',
                };
                return (
                  <div key={platform.value} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-foreground text-sm font-medium">{platform.label}</span>
                      <Badge tone={current?.wired ? 'success' : 'warning'}>
                        {current?.wired ? '已接线' : '未接线'}
                      </Badge>
                      {current?.updatedAt ? (
                        <span className="text-muted-foreground text-xs">
                          更新于 {collectedAtText(current.updatedAt)}
                        </span>
                      ) : null}
                    </div>
                    <p className="text-muted-foreground mt-1 text-xs">{platform.hint}</p>
                    <div className="mt-2 grid gap-3 md:grid-cols-3">
                      <FormField id={`ep-url-${platform.value}`} label="网关地址">
                        <Input
                          id={`ep-url-${platform.value}`}
                          value={form.baseUrl}
                          onChange={(e) =>
                            setEndpointForms({
                              ...endpointForms,
                              [platform.value]: { ...form, baseUrl: e.target.value },
                            })
                          }
                        />
                      </FormField>
                      <FormField id={`ep-ref-${platform.value}`} label="凭证引用">
                        <Input
                          id={`ep-ref-${platform.value}`}
                          value={form.credentialRef}
                          placeholder="secret://1688/collect"
                          onChange={(e) =>
                            setEndpointForms({
                              ...endpointForms,
                              [platform.value]: { ...form, credentialRef: e.target.value },
                            })
                          }
                        />
                      </FormField>
                      <FormField id={`ep-wired-${platform.value}`} label="是否已接线">
                        <label className="text-muted-foreground flex h-9 items-center gap-2 text-xs">
                          <input
                            type="checkbox"
                            checked={form.wired}
                            onChange={(e) =>
                              setEndpointForms({
                                ...endpointForms,
                                [platform.value]: { ...form, wired: e.target.checked },
                              })
                            }
                          />
                          声称已接线
                        </label>
                      </FormField>
                    </div>
                    <div className="mt-2">
                      <Button size="sm" onClick={() => saveEndpoint(platform.value)}>
                        保存
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          </SectionCard>
        </TabsContent>

        {/* ============================================================ 能力与边界 */}
        <TabsContent value="boundary">
          <SectionCard title="快照语义" description={capabilities?.snapshotSemantics}>
            <div className="space-y-2 text-sm">
              <p>{capabilities?.note}</p>
            </div>
          </SectionCard>
          <SectionCard title="未交付的下游" className="mt-4">
            <div className="space-y-3">
              {(capabilities?.gaps ?? []).map((gap) => (
                <div key={gap.moduleId} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-foreground text-sm font-medium">
                      {gap.moduleId} {gap.moduleName}
                    </span>
                    <Badge tone="warning">未交付</Badge>
                    <span className="text-muted-foreground text-xs">归属 {gap.ownerModule}</span>
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">{gap.impact}</p>
                </div>
              ))}
              {(capabilities?.gaps ?? []).length === 0 ? (
                <p className="text-muted-foreground text-sm">正在读取能力声明…</p>
              ) : null}
            </div>
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}

/**
 * 6.1.2.1 档口详情（`/supply/sources/{stallId}`）。
 *
 * <p>回答的是「这个档口现在是什么样」：档口头 + 它下面的**当前**货源条目 + 采集批次历史
 * （成功与失败都在，失败带原因）。
 */
export function StallDetailWorkspace({ stallId }: { stallId: string }) {
  const [detail, setDetail] = useState<StallDetail | null>(null);
  const [items, setItems] = useState<CollectedItemInput[]>([emptyCollectedItem()]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setDetail(await fetchStall(stallId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '档口详情读不到');
    } finally {
      setLoading(false);
    }
  }, [stallId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function runSync() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await syncStall(stallId);
      if (result.status === 'FAILED') {
        setError(`同步失败：${result.failureReason}`);
      } else {
        setNotice(`同步完成：采到 ${result.itemCount} 条。`);
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '同步失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitItems() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await pushCollection(stallId, items);
      if (result.status === 'FAILED') {
        setError(`采集结果提交失败：${result.failureReason}`);
      } else {
        setNotice(
          `采集结果已入库：写入 ${result.itemCount} 条` +
            (result.skippedCount > 0 ? `，跳过 ${result.skippedCount} 条` : ''),
        );
      }
      setItems([emptyCollectedItem()]);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交采集结果失败');
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return <p className="text-muted-foreground text-sm">正在读取档口…</p>;
  }

  if (!detail) {
    return (
      <>
        <PageHeader title="档口详情" />
        {error ? (
          <Notice tone="danger" title="出错了">
            {error}
          </Notice>
        ) : null}
      </>
    );
  }

  const stall = detail.stall;

  return (
    <>
      <PageHeader
        title={stall.name}
        description={`${stall.platformLabel} · ${stall.code} · 平台档口 ID ${stall.platformStallId}。价格与起订量是**某一次采集的快照值**，采购下单时必须以实际下单金额为准。`}
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

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="在售货源" value={String(stall.itemCount)} polarity="neutral" />
        <StatCard label="收藏" value={String(stall.favoriteCount)} polarity="neutral" />
        <StatCard label="SKU 关联" value={String(stall.skuLinkCount)} polarity="neutral" />
        <StatCard
          label="最近采集"
          value={collectedAtText(stall.lastCollectedAt)}
          polarity="neutral"
          comparisonLabel={collectionStatusText(
            stall.lastCollectionStatus,
            stall.lastFailureReason,
          )}
        />
      </div>

      <SectionCard
        title="采集动作"
        description={
          stall.collectMethod === 'URL_SYNC'
            ? '本服务按档口 URL 主动抓取；未登记采集接入时会显式失败并写明缺什么。'
            : '该档口由插件采集：把采到的货源条目提交上来，本服务不主动发起外部请求。'
        }
        className="mt-4"
      >
        {stall.collectMethod === 'URL_SYNC' ? (
          <Button size="sm" variant="outline" disabled={busy} onClick={runSync}>
            <RefreshCw className="h-4 w-4" />
            同步
          </Button>
        ) : (
          <div className="space-y-3">
            {items.map((item, index) => (
              <div
                key={index}
                className="border-border grid gap-3 rounded-md border p-3 md:grid-cols-3"
              >
                <FormField id={`p-id-${index}`} label="平台货源 ID">
                  <Input
                    id={`p-id-${index}`}
                    value={item.platformItemId}
                    onChange={(e) =>
                      setItems(
                        items.map((it, i) =>
                          i === index ? { ...it, platformItemId: e.target.value } : it,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`p-title-${index}`} label="标题">
                  <Input
                    id={`p-title-${index}`}
                    value={item.title}
                    onChange={(e) =>
                      setItems(
                        items.map((it, i) => (i === index ? { ...it, title: e.target.value } : it)),
                      )
                    }
                  />
                </FormField>
                <FormField id={`p-spec-${index}`} label="规格">
                  <Input
                    id={`p-spec-${index}`}
                    value={item.spec}
                    onChange={(e) =>
                      setItems(
                        items.map((it, i) => (i === index ? { ...it, spec: e.target.value } : it)),
                      )
                    }
                  />
                </FormField>
                <FormField id={`p-min-${index}`} label="价格下限">
                  <Input
                    id={`p-min-${index}`}
                    value={item.priceMin}
                    onChange={(e) =>
                      setItems(
                        items.map((it, i) =>
                          i === index ? { ...it, priceMin: e.target.value } : it,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`p-max-${index}`} label="价格上限">
                  <Input
                    id={`p-max-${index}`}
                    value={item.priceMax}
                    onChange={(e) =>
                      setItems(
                        items.map((it, i) =>
                          i === index ? { ...it, priceMax: e.target.value } : it,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`p-currency-${index}`} label="币种">
                  <Input
                    id={`p-currency-${index}`}
                    value={item.currency}
                    onChange={(e) =>
                      setItems(
                        items.map((it, i) =>
                          i === index ? { ...it, currency: e.target.value } : it,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`p-moq-${index}`} label="起订量">
                  <Input
                    id={`p-moq-${index}`}
                    value={item.moq}
                    onChange={(e) =>
                      setItems(
                        items.map((it, i) => (i === index ? { ...it, moq: e.target.value } : it)),
                      )
                    }
                  />
                </FormField>
                <div className="flex items-end">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => setItems(items.filter((_, i) => i !== index))}
                    disabled={items.length === 1}
                  >
                    <Trash2 className="h-4 w-4" />
                    删除这条
                  </Button>
                </div>
              </div>
            ))}
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="ghost"
                onClick={() => setItems([...items, emptyCollectedItem()])}
              >
                <Plus className="h-4 w-4" />
                再加一条
              </Button>
              <Button size="sm" disabled={busy} onClick={submitItems}>
                提交采集结果
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      <SectionCard title={`货源条目（${detail.items.length}）`} className="mt-4">
        {detail.items.length > 0 ? (
          <div className="space-y-3">
            {detail.items.map((row) => (
              <div key={row.id} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-foreground text-sm font-medium">{row.title}</span>
                  <Badge tone={row.onSale ? 'success' : 'neutral'}>
                    {row.onSale ? '在售' : '已下架'}
                  </Badge>
                  {row.favorited ? <Badge tone="warning">已收藏</Badge> : null}
                </div>
                <div className="text-foreground mt-1 text-sm">
                  价格区间 {priceRangeText(row.priceMin, row.priceMax, row.currency)} · 起订量{' '}
                  {moqText(row.moq)}
                </div>
                <div className="text-muted-foreground mt-1 text-xs">
                  第 {row.snapshotSeq} 版快照 · 采集于 {collectedAtText(row.collectedAt)}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="这个档口还没有采到货源"
            description="点「同步」由本服务去抓，或由插件把采到的货源条目提交上来。"
          />
        )}
      </SectionCard>

      <SectionCard title="采集批次历史" className="mt-4">
        {detail.collections.length > 0 ? (
          <div className="space-y-2">
            {detail.collections.map((row) => (
              <div
                key={row.id}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-xs"
              >
                <Badge tone={collectionStatusTone(row.status)}>{row.statusLabel}</Badge>
                <span>{row.triggerSourceLabel}</span>
                <span>采集于 {collectedAtText(row.collectedAt)}</span>
                <span>采到 {row.itemCount} 条</span>
                {row.skippedCount > 0 ? <span>跳过 {row.skippedCount} 条</span> : null}
                {row.failureReason ? (
                  <span className="text-destructive">{row.failureReason}</span>
                ) : null}
                {row.skippedReason ? (
                  <span className="text-muted-foreground">{row.skippedReason}</span>
                ) : null}
              </div>
            ))}
          </div>
        ) : (
          <EmptyState title="还没有采集记录" description="这个档口一次都没采过。" />
        )}
      </SectionCard>

      <div className="mt-4">
        <Button size="sm" variant="ghost" onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      </div>
    </>
  );
}
