'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented } from '@starcon/ui';
import { Layers3, RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  amountText,
  attributionLabel,
  attributionTone,
  availabilityLabel,
  cellAvailability,
  cellNote,
  cellText,
  countText,
  currencyLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  drilldownReferenceText,
  emptyReasonLabel,
  emptyText,
  levelLabel,
  metricFormulaText,
  metricUnitLabel,
  notDeliveredLines,
  occurredAtText,
  pageState,
  platformCodesLabel,
  productColumns,
  quantityText,
  rangeText,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  shareText,
  shareTotalNote,
  spuMergeEvidence,
  statusLabel,
  unattributedText,
  upstreamModules,
} from './product-sales-model';
import type {
  ProductDrilldownResponse,
  ProductMetricDefinitionView,
  ProductRefreshResult,
  ProductSalesCapabilities,
  ProductSalesResponse,
  SpuSummaryResponse,
  VariantDistributionResponse,
} from './product-sales-model';

/**
 * 13.2.2.2 SKU/SPU/Listing 工作区（卷 13「利润与经营分析」L3 13.2.2 维度拆解下的第二格）。
 *
 * <p>蓝图输出：「商品销量报表 + 层级钻取。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/product-sales} —— 商品销量报表（SKU / SPU / Listing 三层）；</li>
 *   <li>{@code GET reporting/product-sales/spu-summary} —— SPU 汇总（多平台多 SKU 合并看）；</li>
 *   <li>{@code GET reporting/product-sales/variants} —— 变体销量分布；</li>
 *   <li>{@code GET reporting/product-sales/drilldown} —— 层级钻取（哪张订单的哪一行）；</li>
 *   <li>{@code GET reporting/product-sales/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/product-sales/metrics} —— 指标口径目录（口径唯一的展示形态）；</li>
 *   <li>{@code POST reporting/product-sales/refresh} —— 刷新商品维度投影。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 的取值显示「—」/「不可比」+ 原因，
 *       <b>绝不用 0 顶替</b>（假 0 会被读成「这个商品卖不出去」）。</li>
 *   <li><b>多币种绝不相加</b>：金额按币种分别列出；件数与单数给跨币种合计（币种显示为空串 =
 *       与币种无关）。</li>
 *   <li><b>Listing 层的销量归属边界必须显式</b>：归不了的部分单列成「无法唯一归属」，
 *       <b>不让用户以为「这个 Listing 就是没卖出去」</b>。</li>
 *   <li><b>SPU 是跨平台经营的基本单位</b>：SPU 汇总把名下所有平台、所有 SKU 合并，
 *       并在行上写明合并了多少 SKU × 平台 × Listing。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 * </ol>
 *
 * <p><b>🔴 前端不做业务算术</b>：占比、件均价、客单价、SPU 合并后的汇总全部来自服务端，
 * 这里只做展示格式化。SPU 的合并口径由服务端算 —— 前端绝自己把 SKU 相加。
 */
export function AnalyticsProductSalesWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [level, setLevel] = useState('SKU');
  const [mergeSpu, setMergeSpu] = useState(false);
  const [currency, setCurrency] = useState('');
  const [page, setPage] = useState(0);

  const [data, setData] = useState<ProductSalesResponse | null>(null);
  const [summary, setSummary] = useState<SpuSummaryResponse | null>(null);
  const [variants, setVariants] = useState<VariantDistributionResponse | null>(null);
  const [variantSpuKey, setVariantSpuKey] = useState('');
  const [drilldown, setDrilldown] = useState<ProductDrilldownResponse | null>(null);
  const [drilldownTarget, setDrilldownTarget] = useState('');
  const [capabilities, setCapabilities] = useState<ProductSalesCapabilities | null>(null);
  const [definitions, setDefinitions] = useState<ProductMetricDefinitionView[]>([]);
  const [refreshResults, setRefreshResults] = useState<ProductRefreshResult[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({ from, to, level, page: String(page), size: '50' });
    if (mergeSpu) params.set('mergeSpu', 'true');
    if (currency) params.set('currency', currency);
    return params.toString();
  }, [from, to, level, mergeSpu, currency, page]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [report, caps, metrics, spuRows] = await Promise.all([
        api<ProductSalesResponse>(`reporting/product-sales?${query}`),
        api<ProductSalesCapabilities>('reporting/product-sales/capabilities'),
        api<ProductMetricDefinitionView[]>('reporting/product-sales/metrics'),
        api<SpuSummaryResponse>(
          `reporting/product-sales/spu-summary?${new URLSearchParams({ from, to, page: '0', size: '50' })}`,
        ),
      ]);
      setData(report);
      setCapabilities(caps);
      setDefinitions(metrics);
      setSummary(spuRows);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取商品销量报表失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query, from, to]);

  const loadVariants = useCallback(async () => {
    if (!variantSpuKey) {
      setVariants(null);
      return;
    }
    try {
      const params = new URLSearchParams({ from, to, spuKey: variantSpuKey });
      setVariants(
        await api<VariantDistributionResponse>(`reporting/product-sales/variants?${params}`),
      );
    } catch (e) {
      setVariants(null);
      setError(e instanceof Error ? e.message : '读取变体销量分布失败，请重试');
    }
  }, [variantSpuKey, from, to]);

  const openDrilldown = useCallback(
    async (dimensionKey: string) => {
      setDrilldownTarget(dimensionKey);
      setDrilldown(null);
      try {
        const params = new URLSearchParams({
          from,
          to,
          level: data?.level ?? level,
          dimensionKey,
          page: '0',
          size: '50',
        });
        setDrilldown(
          await api<ProductDrilldownResponse>(`reporting/product-sales/drilldown?${params}`),
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : '读取构成明细失败，请重试');
      }
    },
    [from, to, level, data?.level],
  );

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams({ from, to, timezone: 'UTC' });
      const results = await api<ProductRefreshResult[]>(
        `reporting/product-sales/refresh?${params}`,
        'POST',
      );
      setRefreshResults(results);
      // 刷完立刻重读 —— 投影变了，页面上的数必须跟着变。
      await load();
      await loadVariants();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新商品维度投影失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, load, loadVariants]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadVariants();
  }, [loadVariants]);

  const state = pageState(data, error);
  const columns = useMemo(
    () => productColumns(definitions, data?.level ?? level),
    [definitions, data, level],
  );
  const spuOptions = useMemo(
    () =>
      (summary?.rows ?? []).map((row) => ({ value: row.spuKey, label: row.spuName || row.spuKey })),
    [summary],
  );

  return (
    <div className="space-y-4">
      {/* ── 数据更新时间（常驻）────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">{dataUpdatedText(data)}</span>
          <span className="text-muted-foreground ml-2">{dataUpdatedNote(data)}</span>
        </div>
        <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
          <span>{rangeText(from, to)}</span>
          <Button
            variant="outline"
            size="sm"
            disabled={loading || busy}
            onClick={() => void load()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            重读
          </Button>
        </div>
      </div>

      {/* ── 时间范围与维度选择 ─────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与维度"
        description="维度层决定看哪一层：SKU 层按平台单品看，SPU 层跨平台合并看，Listing 层按平台商品看。金额按币种分别列出，绝不跨币种相加。"
      >
        <div className="flex flex-wrap items-end gap-4">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            起始日期
            <input
              type="date"
              value={from}
              max={to}
              onChange={(e) => {
                setFrom(e.target.value);
                setPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            结束日期
            <input
              type="date"
              value={to}
              min={from}
              onChange={(e) => {
                setTo(e.target.value);
                setPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            币种（可选）
            <input
              type="text"
              value={currency}
              placeholder="不填 = 按币种分别报"
              onChange={(e) => {
                setCurrency(e.target.value.toUpperCase());
                setPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <div className="flex flex-col gap-1">
            <span className="text-copy-13 text-muted-foreground">维度层</span>
            <Segmented
              value={level}
              onChange={(next) => {
                setLevel(next);
                setMergeSpu(false);
                setPage(0);
              }}
              options={[
                { label: 'SKU', value: 'SKU' },
                { label: 'SPU', value: 'SPU' },
                { label: 'Listing', value: 'LISTING' },
              ]}
              aria-label="商品维度层"
            />
          </div>
          <label className="text-copy-13 text-muted-foreground flex items-center gap-2 pb-1.5">
            <input
              type="checkbox"
              checked={mergeSpu}
              onChange={(e) => {
                setMergeSpu(e.target.checked);
                setPage(0);
              }}
            />
            合并 SPU（跨平台合并看）
          </label>
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => void refreshProjection()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新商品维度投影
          </Button>
        </div>
        <p className="text-copy-13 text-muted-foreground mt-3">
          当前维度：{levelLabel(data?.level ?? level)}
          {data?.mergeSpu ? '（已按 SPU 合并）' : ''}；状态：
          {statusLabel(data?.status ?? 'NOT_PROJECTED')}。
        </p>
      </SectionCard>

      {/* ── 刷新结果 ───────────────────────────────────────────────────── */}
      {refreshResults && (
        <SectionCard
          title="投影刷新结果"
          description="上游读不到就是读不到（失败），不会返回空集合冒充「上游没有数据」。"
        >
          <div className="space-y-2">
            {refreshResults.map((result) => (
              <div key={result.source} className="border-border rounded-lg border px-4 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-14 text-foreground font-medium">{result.source}</span>
                  <Badge tone={refreshStatusTone(result.status)}>
                    {refreshStatusLabel(result.status)}
                  </Badge>
                  <span className="text-copy-13 text-muted-foreground">
                    拉取 {result.fetched} 行 / 落库 {result.projected} 行
                  </span>
                </div>
                <p className="text-copy-13 text-muted-foreground mt-1">{result.note}</p>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      {/* ── 错误态 ─────────────────────────────────────────────────────── */}
      {state === 'error' && (
        <SectionCard>
          <EmptyState
            title="商品销量报表读不到"
            description={`${error}（这是「读不到」，不是「没有数据」）—— 上游不可达或没有读权限时就会出现。`}
            action={
              <Button
                variant="outline"
                size="sm"
                disabled={loading || busy}
                onClick={() => void load()}
              >
                <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                重试
              </Button>
            }
          />
        </SectionCard>
      )}

      {/* ── 加载态 ─────────────────────────────────────────────────────── */}
      {state === 'loading' && (
        <SectionCard>
          <EmptyState
            title="正在读取商品销量报表…"
            description="读取期间页面上的「数据截至」仍然有效。"
          />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            title={emptyReasonLabel(data?.emptyReason ?? null) || '没有商品销量数据'}
            description={emptyText(data)}
          />
        </SectionCard>
      )}

      {/* ── 就绪态 ─────────────────────────────────────────────────────── */}
      {state === 'ready' && data && (
        <>
          {/* ── 无法唯一归属的销量（常驻，Listing 层的销量不完整的答案）──── */}
          {unattributedText(data.unattributed) && (
            <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
              <p className="text-foreground flex items-start gap-2 font-medium">
                <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                {unattributedText(data.unattributed)}
              </p>
            </div>
          )}

          {/* ── 口径说明（常驻）────────────────────────────────────────── */}
          {data.caliberNotes.length > 0 && (
            <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground space-y-1 rounded-lg border px-4 py-3">
              {data.caliberNotes.map((note, index) => (
                <p key={index}>{note}</p>
              ))}
            </div>
          )}

          {/* ── 商品销量报表 ───────────────────────────────────────────── */}
          <SectionCard
            title="商品销量报表"
            description={`一行 = 一个${levelLabel(data.level).replace(/（.*/, '')}。金额按币种分别列出；「—」= 这一格算不出来，不是 0。`}
            extra={
              <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
                <span>
                  第 {data.page + 1} / {Math.max(Math.ceil(data.total / data.size), 1)} 页（
                  {data.total} 条）
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={data.page <= 0}
                  onClick={() => setPage((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(data.page + 1) * data.size >= data.total}
                  onClick={() => setPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            }
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">商品</th>
                    <th className="py-2 pr-3 font-medium">上层</th>
                    <th className="py-2 pr-3 font-medium">平台</th>
                    <th className="py-2 pr-3 font-medium">SKU / Listing</th>
                    {columns.map((column) => (
                      <th key={column.metricCode} className="py-2 pr-3 text-right font-medium">
                        {column.label}
                      </th>
                    ))}
                    <th className="py-2 pr-3 font-medium">钻取</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr
                      key={`${row.dimensionType}:${row.dimensionKey}`}
                      className="border-border border-b"
                    >
                      <td className="py-2 pr-3">
                        <div className="font-medium">{row.dimensionName || row.dimensionKey}</div>
                        <div className="text-copy-13 text-muted-foreground">{row.dimensionKey}</div>
                        {row.variantSignature && (
                          <div className="text-copy-13 text-muted-foreground">
                            变体签名：{row.variantSignature}
                          </div>
                        )}
                      </td>
                      <td className="py-2 pr-3">
                        {row.parentDimensionKey ? (
                          <>
                            <div>{row.parentDimensionName || row.parentDimensionKey}</div>
                            <div className="text-copy-13 text-muted-foreground">
                              {row.parentDimensionKey}
                            </div>
                          </>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="py-2 pr-3">{platformCodesLabel(row.platformCodes)}</td>
                      <td className="py-2 pr-3">
                        {row.skuCount} SKU / {row.listingCount} Listing
                        {row.attributionStatus !== 'NOT_APPLICABLE' && (
                          <div className="mt-1">
                            <Badge tone={attributionTone(row.attributionStatus)}>
                              {attributionLabel(row.attributionStatus)}
                            </Badge>
                          </div>
                        )}
                      </td>
                      {columns.map((column) => (
                        <td key={column.metricCode} className="py-2 pr-3 text-right">
                          <div>{cellText(row, column)}</div>
                          {cellAvailability(row, column) !== 'AVAILABLE' && (
                            <div className="text-copy-13 text-muted-foreground">
                              {availabilityLabel(cellAvailability(row, column))}
                              {cellNote(row, column) ? ` · ${cellNote(row, column)}` : ''}
                            </div>
                          )}
                        </td>
                      ))}
                      <td className="py-2 pr-3">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => void openDrilldown(row.dimensionKey)}
                        >
                          <Layers3 className="mr-1.5 h-3.5 w-3.5" />
                          构成明细
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── SPU 汇总 ───────────────────────────────────────────────── */}
          <SectionCard
            title="SPU 汇总（跨平台合并看）"
            description="🔴 SPU 口径是跨平台经营的基本单位：一行 SPU = 名下所有平台、所有 SKU 的销量之和，不按平台拆 —— 只按平台 SKU 看会误判商品整体表现。"
          >
            {summary && summary.rows.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">SPU</th>
                      <th className="py-2 pr-3 font-medium">合并范围</th>
                      <th className="py-2 pr-3 text-right font-medium">销量（件）</th>
                      <th className="py-2 pr-3 text-right font-medium">成交金额</th>
                      <th className="py-2 pr-3 text-right font-medium">成交订单数</th>
                      <th className="py-2 pr-3 text-right font-medium">件均价</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.rows.map((row) => {
                      const quantity = row.metrics.find(
                        (m) => m.metricCode === 'PRODUCT_SALES_QUANTITY',
                      );
                      const amount = row.metrics.filter(
                        (m) => m.metricCode === 'PRODUCT_SALES_AMOUNT',
                      );
                      const orders = row.metrics.find(
                        (m) => m.metricCode === 'PRODUCT_ORDER_COUNT',
                      );
                      const avg = row.metrics.find(
                        (m) => m.metricCode === 'PRODUCT_AVG_UNIT_PRICE',
                      );
                      return (
                        <tr key={row.spuKey} className="border-border border-b">
                          <td className="py-2 pr-3">
                            <div className="font-medium">{row.spuName || row.spuKey}</div>
                            <div className="text-copy-13 text-muted-foreground">{row.spuKey}</div>
                          </td>
                          <td className="py-2 pr-3">
                            <div>{spuMergeEvidence(row)}</div>
                            <div className="text-copy-13 text-muted-foreground">
                              平台：{platformCodesLabel(row.platformCodes)}
                            </div>
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {quantityText(quantity?.value ?? null)}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {amount.length === 0
                              ? '—'
                              : amount.map((m) => amountText(m.value, m.currency)).join(' / ')}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {countText(orders?.value ?? null)}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {avg ? amountText(avg.value, avg.currency) : '—'}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState
                title="还没有 SPU 汇总"
                description={summary?.emptyNote ?? '这个窗口里没有任何 SPU 维度的事实。'}
              />
            )}
          </SectionCard>

          {/* ── 变体销量分布 ───────────────────────────────────────────── */}
          <SectionCard
            title="变体销量分布"
            description="占比 = 该变体销量 ÷ 所属 SPU 的销量（口径写在服务端的口径表里）。分母为 0 时算不出来（不是 0%）。"
          >
            <div className="flex flex-wrap items-end gap-4">
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                SPU
                <select
                  value={variantSpuKey}
                  onChange={(e) => setVariantSpuKey(e.target.value)}
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                >
                  <option value="">选择一个 SPU</option>
                  {spuOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            {variants && variants.variants.length > 0 ? (
              <div className="mt-4 space-y-3">
                <p className="text-copy-13 text-muted-foreground">
                  {variants.spuName || variants.spuKey} · 总销量{' '}
                  {quantityText(variants.totalQuantity)} 件 · {shareTotalNote(variants)}
                </p>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">变体（SKU）</th>
                        <th className="py-2 pr-3 font-medium">变体签名</th>
                        <th className="py-2 pr-3 font-medium">平台</th>
                        <th className="py-2 pr-3 text-right font-medium">销量（件）</th>
                        <th className="py-2 pr-3 text-right font-medium">成交金额</th>
                        <th className="py-2 pr-3 text-right font-medium">订单数</th>
                        <th className="py-2 pr-3 text-right font-medium">SPU 内占比</th>
                      </tr>
                    </thead>
                    <tbody>
                      {variants.variants.map((variant) => (
                        <tr key={variant.skuKey} className="border-border border-b">
                          <td className="py-2 pr-3">
                            <div className="font-medium">{variant.skuName || variant.skuKey}</div>
                            <div className="text-copy-13 text-muted-foreground">
                              {variant.skuKey}
                            </div>
                          </td>
                          <td className="py-2 pr-3">{variant.variantSignature || '—'}</td>
                          <td className="py-2 pr-3">{platformCodesLabel(variant.platformCodes)}</td>
                          <td className="py-2 pr-3 text-right">{quantityText(variant.quantity)}</td>
                          <td className="py-2 pr-3 text-right">
                            {variant.amounts.length === 0
                              ? '—'
                              : variant.amounts
                                  .map((a) => amountText(a.amount, a.currency))
                                  .join(' / ')}
                          </td>
                          <td className="py-2 pr-3 text-right">{countText(variant.orderCount)}</td>
                          <td className="py-2 pr-3 text-right">
                            <div>{shareText(variant.share)}</div>
                            {variant.share === null && variant.shareNote && (
                              <div className="text-copy-13 text-muted-foreground">
                                {variant.shareNote}
                              </div>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <EmptyState
                title={variantSpuKey ? '这个 SPU 没有变体数据' : '先选一个 SPU'}
                description={
                  variantSpuKey
                    ? (variants?.emptyNote ?? '这个 SPU 名下没有投影任何 SKU 维度的事实。')
                    : '变体销量分布是**按 SPU** 看的 —— 选一个 SPU 就能看到它名下各变体的销量与占比。'
                }
              />
            )}
          </SectionCard>

          {/* ── 层级钻取 ───────────────────────────────────────────────── */}
          {drilldownTarget && (
            <SectionCard
              title={`层级钻取：${drilldownTarget}`}
              description="每个汇总数字都能钻到构成明细 —— 哪张订单的哪一行、哪个 SKU 贡献了这笔数字。"
              extra={
                <Button variant="outline" size="sm" onClick={() => setDrilldownTarget('')}>
                  收起
                </Button>
              }
            >
              {drilldown && drilldown.rows.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">订单</th>
                        <th className="py-2 pr-3 font-medium">SKU</th>
                        <th className="py-2 pr-3 font-medium">下单时刻</th>
                        <th className="py-2 pr-3 font-medium">分桶日 / 时区</th>
                        <th className="py-2 pr-3 text-right font-medium">件数</th>
                        <th className="py-2 pr-3 text-right font-medium">金额</th>
                      </tr>
                    </thead>
                    <tbody>
                      {drilldown.rows.map((row) => (
                        <tr key={row.referenceId} className="border-border border-b">
                          <td className="py-2 pr-3">
                            <div>{drilldownReferenceText(row)}</div>
                            <div className="text-copy-13 text-muted-foreground">{row.orderNo}</div>
                          </td>
                          <td className="py-2 pr-3">
                            <div>{row.skuName || row.skuCode}</div>
                            <div className="text-copy-13 text-muted-foreground">{row.skuCode}</div>
                          </td>
                          <td className="py-2 pr-3">{occurredAtText(row)}</td>
                          <td className="py-2 pr-3">
                            {row.bucketDate} · {row.timezone}
                          </td>
                          <td className="py-2 pr-3 text-right">{quantityText(row.quantity)}</td>
                          <td className="py-2 pr-3 text-right">
                            {amountText(
                              row.value,
                              currencyLabel(row.currency) === '与币种无关' ? '' : row.currency,
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <EmptyState
                  title="没有构成明细"
                  description={drilldown?.emptyNote ?? '这个维度在这个窗口里没有构成明细。'}
                />
              )}
            </SectionCard>
          )}
        </>
      )}

      {/* ── 指标口径目录（常驻，口径唯一的展示形态）───────────────────── */}
      {definitions.length > 0 && (
        <SectionCard
          title="指标口径目录"
          description="口径是租户自己的（服务端的口径表是唯一真相）。派生指标只写分子分母引用，不写公式 —— 口径改一处，引用它的地方自动跟着改。"
        >
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-copy-13 text-muted-foreground">
                <tr className="border-border border-b">
                  <th className="py-2 pr-3 font-medium">指标</th>
                  <th className="py-2 pr-3 font-medium">单位</th>
                  <th className="py-2 pr-3 font-medium">怎么算出来的</th>
                  <th className="py-2 pr-3 font-medium">归属</th>
                </tr>
              </thead>
              <tbody>
                {definitions.map((definition) => (
                  <tr key={definition.metricCode} className="border-border border-b">
                    <td className="py-2 pr-3">
                      <div className="font-medium">{definition.displayName}</div>
                      <div className="text-copy-13 text-muted-foreground">
                        {definition.metricCode}
                      </div>
                    </td>
                    <td className="py-2 pr-3">{metricUnitLabel(definition.unit)}</td>
                    <td className="py-2 pr-3">
                      <div>{metricFormulaText(definition)}</div>
                      <div className="text-copy-13 text-muted-foreground">
                        {definition.description}
                      </div>
                    </td>
                    <td className="py-2 pr-3">{definition.ownerModule}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {/* ── 能力声明（常驻）────────────────────────────────────────────── */}
      <SectionCard
        title="能力声明"
        description="本服务**真的读过**哪些上游、**确实拿不到**哪些东西 —— 写清楚比藏起来好。"
      >
        <div className="space-y-3">
          <div>
            <h4 className="text-copy-14 text-foreground font-medium">真读过的上游</h4>
            <ul className="text-copy-13 text-muted-foreground mt-1 list-disc space-y-0.5 pl-5">
              {upstreamModules(capabilities).map((line, index) => (
                <li key={index}>{line}</li>
              ))}
            </ul>
          </div>
          <div>
            <h4 className="text-copy-14 text-foreground font-medium">确实拿不到的部分</h4>
            <ul className="text-copy-13 text-muted-foreground mt-1 list-disc space-y-0.5 pl-5">
              {notDeliveredLines(capabilities).map((line, index) => (
                <li key={index}>{line}</li>
              ))}
            </ul>
          </div>
          <div>
            <h4 className="text-copy-14 text-foreground font-medium">判定规则</h4>
            <ul className="text-copy-13 text-muted-foreground mt-1 list-disc space-y-0.5 pl-5">
              {ruleNotes(capabilities).map((note) => (
                <li key={note.ruleCode}>
                  <span className="text-foreground">{note.ruleCode}</span>：{note.description}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </SectionCard>
    </div>
  );
}
