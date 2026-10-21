'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  BATCH_SECTION,
  batchExportUrl,
  createBatchRun,
  decisionLabel,
  emptyFilters,
  emptyRequest,
  fetchBatchCapabilities,
  fetchBatchResults,
  fetchBatchRuns,
  fetchBatchSites,
  filterSummary,
  flagLabel,
  moneyText,
  moneyWithCurrency,
  percentText,
  quantityText,
  priceSourceLabel,
  rowTone,
  statusLabel,
  summaryStats,
  validateRequest,
} from './batch-calculation-model';
import type {
  BatchCalculationCapabilities,
  BatchCalculationRequest,
  BatchResultFilters,
  BatchResultPage,
  BatchRunDetail,
  BatchRunPage,
  BatchValidationError,
  SiteOption,
} from './batch-calculation-model';

/**
 * 5.3.1.2 批量测算分区。
 *
 * <p>🔴 这一块与 5.3.1.1 的利润测算**共用** {@code /pricing/calculator} 路由，
 * 但它是**独立的一块**：批量测算是「对一批 SKU 跑测算」，利润测算是「一次测算 + 方案」。
 * 本组件不改动兄弟模块的骨架 —— 它自带表单、结果表、筛选与导出。
 *
 * <p>🔴 **三种「没有」在页面上必须长得不一样**：
 * 跳过（无价格观测）、成本不完整（没有毛利率数字）、真的是 0。
 * 把它们都渲染成 0.00，清库存的人会拿着一张混着「没测」和「卖 0 元」的表做决策。
 */
export function BatchCalculationSection() {
  const [capabilities, setCapabilities] = useState<BatchCalculationCapabilities | null>(null);
  const [sites, setSites] = useState<SiteOption[]>([]);
  const [request, setRequest] = useState<BatchCalculationRequest>(emptyRequest());
  const [runs, setRuns] = useState<BatchRunPage | null>(null);
  const [selected, setSelected] = useState<BatchRunDetail | null>(null);
  const [filters, setFilters] = useState<BatchResultFilters>(emptyFilters());
  const [results, setResults] = useState<BatchResultPage | null>(null);
  const [page, setPage] = useState(0);
  const [problem, setProblem] = useState<BatchValidationError | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [caps, siteList, runPage] = await Promise.all([
        fetchBatchCapabilities(),
        fetchBatchSites().catch(() => [] as SiteOption[]),
        fetchBatchRuns(0, 20).catch(() => null),
      ]);
      setCapabilities(caps);
      setSites(siteList);
      if (runPage) {
        setRuns(runPage);
        // 默认打开最近一次批次 —— 运营多半是想看刚才那一批。
        setSelected((prev) => prev ?? runPage.items[0] ?? null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '批量测算读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = useCallback((next: Partial<BatchCalculationRequest>) => {
    setRequest((prev) => ({ ...prev, ...next }));
    setProblem(null);
  }, []);

  const patchFilters = useCallback((next: Partial<BatchResultFilters>) => {
    setFilters((prev) => ({ ...prev, ...next }));
    setPage(0);
  }, []);

  // ---- 发起一次批量测算 ----
  async function run() {
    const invalid = validateRequest(request);
    if (invalid) {
      setProblem(invalid);
      setError('');
      setNotice('');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const created = await createBatchRun(request);
      setSelected(created);
      setFilters(emptyFilters());
      setPage(0);
      const runPage = await fetchBatchRuns(0, 20);
      setRuns(runPage);
      setNotice(
        `已测算出 ${created.summary.calculated} 个 SKU（范围内 ${created.summary.total} 个，` +
          `跳过 ${created.summary.skippedNoPrice} 个没有价格观测的）。`,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '批量测算失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }

  // ---- 结果列表（真筛选 + 真分页，全部在数据库层）----
  const loadResults = useCallback(
    async (runId: string, nextPage: number) => {
      try {
        const data = await fetchBatchResults(runId, filters, nextPage, 20);
        setResults(data);
      } catch (e) {
        setError(e instanceof Error ? e.message : '结果读不到');
      }
    },
    [filters],
  );

  useEffect(() => {
    if (!selected) {
      setResults(null);
      return;
    }
    void loadResults(selected.id, page);
  }, [selected, page, loadResults]);

  const platforms = useMemo(() => {
    const names = new Map<string, string>();
    for (const site of sites) names.set(site.platformCode, site.platformName);
    return [...names.entries()].map(([code, name]) => ({ code, name }));
  }, [sites]);

  const storeOptions = useMemo(
    () => sites.filter((site) => !request.platformCode || site.platformCode === request.platformCode),
    [sites, request.platformCode],
  );

  const stats = useMemo(() => summaryStats(selected?.summary ?? null), [selected]);
  const exportHref = selected ? batchExportUrl(selected.id, filters) : '';

  return (
    <div className="space-y-4">
      <SectionCard title={BATCH_SECTION.title} description={BATCH_SECTION.description}>
        {capabilities ? (
          <div className="grid gap-2 md:grid-cols-2">
            <Notice tone="warning" title="售价来源">
              {capabilities.priceSourceNote}
            </Notice>
            <Notice tone="info" title="上游">
              {capabilities.upstreamNote}
            </Notice>
            <Notice tone="info" title="下游">
              {capabilities.downstreamNote}
            </Notice>
            <Notice tone="danger" title="决策就绪闸">
              {capabilities.decisionGateNote}
            </Notice>
            <Notice tone="info" title="范围">
              {capabilities.scopeNote}
            </Notice>
            <Notice tone="info" title="异常标记">
              {capabilities.flagNote}
            </Notice>
            <Notice tone="info" title="筛选">
              {capabilities.filterNote}
            </Notice>
            <Notice tone="info" title="导出">
              {capabilities.exportNote}
            </Notice>
            <Notice tone="info" title="跳过">
              {capabilities.skipNote}
            </Notice>
            <Notice tone="info" title="精度">
              {capabilities.precisionNote}
            </Notice>
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在读能力声明……</p>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读不到。</p>
        )}
      </SectionCard>

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="info" title="已处理">
          {notice}
        </Notice>
      ) : null}

      {/* ============================================================ 测算参数 */}
      <SectionCard
        title="批量测算参数"
        description="SKU 范围按分类 / 品牌 / 店铺三个维度；平台费率与物流单价是手工假设（5.2.3.1 / 5.2.3.2 未交付）。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="bc-platform" label="平台" required error={problem?.field === 'platformCode' ? problem.message : undefined}>
            <select
              id="bc-platform"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={request.platformCode}
              onChange={(e) => {
                const platform = e.target.value;
                const first = sites.find((site) => site.platformCode === platform);
                patch({
                  platformCode: platform,
                  siteCode: '',
                  currency: first?.defaultCurrency ?? request.currency,
                });
              }}
            >
              <option value="">请选择平台</option>
              {platforms.map((platform) => (
                <option key={platform.code} value={platform.code}>
                  {platform.name}（{platform.code}）
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="bc-site" label="店铺（站点）" required hint="决定取价与币种" error={problem?.field === 'siteCode' ? problem.message : undefined}>
            <select
              id="bc-site"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={request.siteCode}
              onChange={(e) => {
                const site = sites.find((s) => s.siteCode === e.target.value);
                patch({
                  siteCode: e.target.value,
                  platformCode: site?.platformCode ?? request.platformCode,
                  currency: site?.defaultCurrency ?? request.currency,
                });
              }}
            >
              <option value="">请选择店铺</option>
              {storeOptions.map((site) => (
                <option key={`${site.platformCode}-${site.siteCode}`} value={site.siteCode}>
                  {site.siteName}（{site.siteCode}）
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="bc-category" label="分类" hint="精确匹配，空 = 不限定" error={problem?.field === 'category' ? problem.message : undefined}>
            <Input
              id="bc-category"
              value={request.category}
              placeholder="例如：家居"
              onChange={(e) => patch({ category: e.target.value })}
            />
          </FormField>
          <FormField id="bc-brand" label="品牌" hint="精确匹配，空 = 不限定" error={problem?.field === 'brand' ? problem.message : undefined}>
            <Input
              id="bc-brand"
              value={request.brand}
              placeholder="例如：Acme"
              onChange={(e) => patch({ brand: e.target.value })}
            />
          </FormField>
          <FormField id="bc-quantity" label="数量" hint="用于阶梯与运费" error={problem?.field === 'quantity' ? problem.message : undefined}>
            <Input
              id="bc-quantity"
              type="number"
              value={request.quantity ?? ''}
              onChange={(e) =>
                patch({ quantity: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="bc-fee"
            label="平台费率（手工假设）"
            hint="0.15 = 15%"
            error={problem?.field === 'platformFeeRate' ? problem.message : undefined}
          >
            <Input
              id="bc-fee"
              type="number"
              step="0.01"
              value={request.platformFeeRate ?? ''}
              onChange={(e) =>
                patch({ platformFeeRate: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="bc-logistics"
            label="物流每单位运费（手工假设）"
            hint="每单位尾程运费"
            error={problem?.field === 'logisticsCostPerUnit' ? problem.message : undefined}
          >
            <Input
              id="bc-logistics"
              type="number"
              step="0.01"
              value={request.logisticsCostPerUnit ?? ''}
              onChange={(e) =>
                patch({ logisticsCostPerUnit: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
          <FormField
            id="bc-threshold"
            label="低毛利阈值"
            hint="0.15 = 15%，小数口径"
            error={problem?.field === 'lowMarginThreshold' ? problem.message : undefined}
          >
            <Input
              id="bc-threshold"
              type="number"
              step="0.01"
              value={request.lowMarginThreshold ?? ''}
              onChange={(e) =>
                patch({ lowMarginThreshold: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </FormField>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button type="button" onClick={() => void run()} disabled={busy}>
            {busy ? '测算中……' : '开始批量测算'}
          </Button>
          <span className="text-copy-12 text-muted-foreground">
            一次最多 200 个 SKU（每个 SKU 的成本构成要跨服务读 warehouse）。超出会**明确报错**，不静默截断。
          </span>
        </div>
      </SectionCard>

      {/* ============================================================ 批次台账 */}
      <SectionCard title="测算批次" description="按时间倒序。点一行看它的利润表与异常标记。">
        {runs && runs.items.length ? (
          <div className="space-y-2">
            {runs.items.map((run) => (
              <div
                key={run.id}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
              >
                <button
                  type="button"
                  className="text-label-13 font-semibold underline-offset-2 hover:underline"
                  onClick={() => {
                    setSelected(run);
                    setFilters(emptyFilters());
                    setPage(0);
                  }}
                >
                  {run.platformCode} / {run.siteCode}
                </button>
                <span className="text-copy-12 text-muted-foreground">
                  分类 {run.scopeCategory || '不限'} · 品牌 {run.scopeBrand || '不限'} ·{' '}
                  {run.currency}
                </span>
                <span className="text-copy-12 text-muted-foreground">
                  范围 {run.summary.total} · 已测算 {run.summary.calculated} · 跳过{' '}
                  {run.summary.skippedNoPrice}
                </span>
                <Badge tone={run.summary.loss > 0 ? 'danger' : 'success'}>
                  亏损品 {run.summary.loss}
                </Badge>
                <Badge tone={run.summary.lowMargin > 0 ? 'warning' : 'success'}>
                  低毛利品 {run.summary.lowMargin}
                </Badge>
                <span className="text-copy-12 text-muted-foreground">{run.createdAt}</span>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="还没有批量测算"
            description="填好上面的范围与参数，点「开始批量测算」。"
          />
        )}
      </SectionCard>

      {/* ============================================================ 结果表 */}
      {selected ? (
        <>
          <div className="grid gap-3 md:grid-cols-5">
            {stats.map((stat) => (
              <StatCard
                key={stat.label}
                label={stat.label}
                value={stat.value}
                comparisonLabel={stat.hint}
              />
            ))}
          </div>

          <SectionCard
            title="批量测算结果表"
            description={`当前筛选：${filterSummary(filters)}。筛选与排序都在数据库层 —— 不是把整表拉回来在内存里筛。`}
          >
            <div className="grid gap-3 md:grid-cols-5">
              <FormField id="bc-margin-below" label="毛利率 <" hint="小数口径，0.05 = 5%">
                <Input
                  id="bc-margin-below"
                  type="number"
                  step="0.01"
                  value={filters.marginBelow ?? ''}
                  onChange={(e) =>
                    patchFilters({
                      marginBelow: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField id="bc-price-below" label="售价 <" hint="每单位售价">
                <Input
                  id="bc-price-below"
                  type="number"
                  step="0.01"
                  value={filters.priceBelow ?? ''}
                  onChange={(e) =>
                    patchFilters({
                      priceBelow: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField id="bc-flag" label="异常标记">
                <select
                  id="bc-flag"
                  className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
                  value={filters.flag}
                  onChange={(e) =>
                    patchFilters({ flag: e.target.value as BatchResultFilters['flag'] })
                  }
                >
                  <option value="">全部</option>
                  <option value="LOSS">只看亏损品</option>
                  <option value="LOW_MARGIN">只看低毛利品</option>
                </select>
              </FormField>
              <FormField id="bc-status" label="状态">
                <select
                  id="bc-status"
                  className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
                  value={filters.status}
                  onChange={(e) =>
                    patchFilters({ status: e.target.value as BatchResultFilters['status'] })
                  }
                >
                  <option value="">全部</option>
                  <option value="CALCULATED">已测算</option>
                  <option value="SKIPPED_NO_PRICE">跳过（无价格观测）</option>
                </select>
              </FormField>
              <FormField id="bc-keyword" label="关键字" hint="SKU 编码或名称">
                <Input
                  id="bc-keyword"
                  value={filters.keyword}
                  onChange={(e) => patchFilters({ keyword: e.target.value })}
                />
              </FormField>
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <FormField id="bc-sort" label="排序">
                <select
                  id="bc-sort"
                  className="border-border bg-background text-copy-13 h-9 rounded-md border px-3"
                  value={filters.sort}
                  onChange={(e) =>
                    patchFilters({ sort: e.target.value as BatchResultFilters['sort'] })
                  }
                >
                  <option value="MARGIN_ASC">毛利率 从低到高</option>
                  <option value="MARGIN_DESC">毛利率 从高到低</option>
                  <option value="PRICE_ASC">售价 从低到高</option>
                  <option value="PRICE_DESC">售价 从高到低</option>
                </select>
              </FormField>
              <a
                className="border-border text-copy-13 rounded-md border px-3 py-1.5"
                href={exportHref}
                download
              >
                导出 CSV（当前筛选）
              </a>
              <span className="text-copy-12 text-muted-foreground">
                导出的就是当前筛选命中的那些行；CSV 原样透传，不在浏览器里二次拼表格。
              </span>
            </div>

            {results && results.items.length ? (
              <>
                <div className="mt-3 overflow-x-auto">
                  <table className="w-full text-copy-13">
                    <thead>
                      <tr className="border-b text-left">
                        <th className="p-2">SKU</th>
                        <th className="p-2">状态</th>
                        <th className="p-2">售价</th>
                        <th className="p-2">售价来源</th>
                        <th className="p-2">已知成本</th>
                        <th className="p-2">平台费</th>
                        <th className="p-2">物流</th>
                        <th className="p-2">扣减合计</th>
                        <th className="p-2">毛利额</th>
                        <th className="p-2">毛利率</th>
                        <th className="p-2">标记</th>
                      </tr>
                    </thead>
                    <tbody>
                      {results.items.map((row) => (
                        <tr key={row.id} className="border-border border-b">
                          <td className="p-2">
                            <span className="font-semibold">{row.skuCode}</span>
                            <span className="text-muted-foreground ml-2">{row.skuName}</span>
                            {row.category ? (
                              <span className="text-muted-foreground ml-2">{row.category}</span>
                            ) : null}
                          </td>
                          <td className="p-2">
                            <Badge tone={rowTone(row)}>{statusLabel(row.status)}</Badge>
                            {row.status === 'SKIPPED_NO_PRICE' ? (
                              <p className="text-copy-12 text-muted-foreground mt-1">
                                {row.skipReason}
                              </p>
                            ) : null}
                          </td>
                          <td className="p-2">
                            {moneyWithCurrency(row.unitPrice, row.currency, '未测')}
                            {row.priceObservedAt ? (
                              <p className="text-copy-12 text-muted-foreground">
                                {priceSourceLabel(row.priceSource)} · 观测于 {row.priceObservedAt}
                              </p>
                            ) : null}
                          </td>
                          <td className="p-2">{moneyText(row.knownCostTotal)}</td>
                          <td className="p-2">{moneyText(row.platformFeeAmount)}</td>
                          <td className="p-2">{moneyText(row.logisticsAmount)}</td>
                          <td className="p-2">{moneyText(row.totalDeductions)}</td>
                          <td className="p-2">{moneyText(row.grossProfit, '不可信')}</td>
                          <td className="p-2">{percentText(row.grossMarginRate)}</td>
                          <td className="p-2">
                            <Badge tone={rowTone(row)}>{flagLabel(row)}</Badge>
                            {row.blockingGaps.length ? (
                              <p className="text-copy-12 text-muted-foreground mt-1">
                                缺 {row.blockingGaps.join('、')}
                              </p>
                            ) : null}
                            {row.decisionBasis === 'COMPLETE_FROM_MANUAL_ASSUMPTIONS' ? (
                              <p className="text-copy-12 text-muted-foreground mt-1">
                                {decisionLabel(row.decisionBasis)}
                              </p>
                            ) : null}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <span className="text-copy-12 text-muted-foreground">
                    共 {results.total} 行 · 第 {results.page + 1} 页 · 每页 {results.size} 行
                  </span>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    disabled={results.page <= 0}
                    onClick={() => setPage((p) => Math.max(p - 1, 0))}
                  >
                    上一页
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    disabled={(results.page + 1) * results.size >= results.total}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    下一页
                  </Button>
                  <span className="text-copy-12 text-muted-foreground">
                    数量口径 {quantityText(selected.quantity)}
                  </span>
                </div>
              </>
            ) : (
              <EmptyState
                title="这个筛选下没有结果"
                description={
                  results
                    ? `筛选「${filterSummary(filters)}」命中 0 行。放宽条件再试。`
                    : '正在读结果……'
                }
              />
            )}
          </SectionCard>
        </>
      ) : null}
    </div>
  );
}
