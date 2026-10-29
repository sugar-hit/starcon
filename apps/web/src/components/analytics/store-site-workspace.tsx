'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  METRIC_AMOUNT_SHARE,
  METRIC_QUANTITY_SHARE,
  amountText,
  availabilityLabel,
  baseRangeText,
  cellAvailability,
  cellNote,
  cellText,
  channelShareLabel,
  channelShareText,
  coverageText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  dimensionLabel,
  dimensionOptions,
  drilldownKindLabel,
  drilldownReferenceText,
  emptyReasonLabel,
  emptyText,
  metricFormulaText,
  metricUnitLabel,
  newVsExistingText,
  notDeliveredLines,
  occurredAtText,
  pageState,
  platformLabel,
  rangeText,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  shareVsContributionNote,
  statusLabel,
  storeAgeLabel,
  storeAgeTone,
  storeSiteColumns,
  unattributedText,
  upstreamModules,
} from './store-site-model';
import type {
  StoreSiteCapabilities,
  StoreSiteDrilldownResponse,
  StoreSiteMetricDefinitionView,
  StoreSiteRefreshResult,
  StoreSiteResponse,
} from './store-site-model';

/**
 * 13.2.2.1 店铺与站点工作区（卷 13「利润与经营分析」L3 13.2.2 维度拆解下的第一格）。
 *
 * <p>蓝图输出：「分组报表 + 占比与贡献度。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/store-site} —— 店铺 / 站点 / 平台维度拆解（含占比、增长贡献、新老店、渠道占比）；</li>
 *   <li>{@code GET reporting/store-site/drilldown} —— 构成明细（哪张订单、哪张订单的哪一行）；</li>
 *   <li>{@code GET reporting/store-site/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/store-site/metrics} —— 指标口径目录（口径唯一的展示形态）；</li>
 *   <li>{@code POST reporting/store-site/refresh} —— 刷新店铺/站点投影。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>：{@code null} 的取值显示「—」/「不可比」+ 原因，
 *       <b>绝不用 0 顶替</b>（假 0 会被读成「这个店没卖出去」）。</li>
 *   <li><b>多币种绝不相加</b>：金额按币种分别列出；件数与单数给跨币种合计（币种显示为空串 =
 *       与币种无关）。</li>
 *   <li><b>站点维度的归属边界必须显式</b>：订单不带站点，归不了任何站点的销量单列成
 *       「无法唯一归属到站点」，<b>不让用户以为「这个站点就是没卖出去」</b>。</li>
 *   <li><b>增长贡献度与占比分开说</b>：占比看当前窗口，贡献度看增量 ——
 *       这正是蓝图那句「增长贡献度比绝对值有用」的页面落点。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 * </ol>
 *
 * <p><b>🔴 前端不做业务算术</b>：占比、增长贡献度、汇总全部来自服务端，
 * 这里只做展示格式化。前端绝自己把两个币种的金额加起来。
 */
export function AnalyticsStoreSiteWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [dimension, setDimension] = useState('SHOP');
  const [page, setPage] = useState(0);

  const [data, setData] = useState<StoreSiteResponse | null>(null);
  const [drilldown, setDrilldown] = useState<StoreSiteDrilldownResponse | null>(null);
  const [drilldownTarget, setDrilldownTarget] = useState('');
  // 🔴 下钻有自己的错误态：下钻读不到**不应该**把整张报表打成错误态
  //   （报表已经拿到了，只是其中一块下钻读不到 —— 两件事分开说）。
  const [drilldownError, setDrilldownError] = useState('');
  const [capabilities, setCapabilities] = useState<StoreSiteCapabilities | null>(null);
  const [definitions, setDefinitions] = useState<StoreSiteMetricDefinitionView[]>([]);
  const [refreshResults, setRefreshResults] = useState<StoreSiteRefreshResult[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const query = useMemo(() => {
    const params = new URLSearchParams({
      from,
      to,
      dimension,
      page: String(page),
      size: '50',
    });
    return params.toString();
  }, [from, to, dimension, page]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [report, caps, metrics] = await Promise.all([
        api<StoreSiteResponse>(`reporting/store-site?${query}`),
        api<StoreSiteCapabilities>('reporting/store-site/capabilities'),
        api<StoreSiteMetricDefinitionView[]>('reporting/store-site/metrics'),
      ]);
      setData(report);
      setCapabilities(caps);
      setDefinitions(metrics);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取店铺与站点拆解报表失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query]);

  const openDrilldown = useCallback(
    async (dimensionKey: string) => {
      setDrilldownTarget(dimensionKey);
      setDrilldown(null);
      setDrilldownError('');
      try {
        const params = new URLSearchParams({
          from,
          to,
          dimension: data?.dimension ?? dimension,
          dimensionKey,
          page: '0',
          size: '50',
        });
        setDrilldown(await api<StoreSiteDrilldownResponse>(`reporting/store-site/drilldown?${params}`));
      } catch (e) {
        setDrilldownError(e instanceof Error ? e.message : '读取构成明细失败，请重试');
      }
    },
    [from, to, dimension, data?.dimension],
  );

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const params = new URLSearchParams({ from, to });
      const results = await api<StoreSiteRefreshResult[]>(
        `reporting/store-site/refresh?${params}`,
        'POST',
      );
      setRefreshResults(results);
      // 刷完立刻重读 —— 投影变了，页面上的数必须跟着变。
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新店铺/站点投影失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, load]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(data, error);
  const columns = useMemo(() => storeSiteColumns(definitions), [definitions]);

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
        description="三个维度是同一棵树的三层（平台 → 站点 → 店铺）。金额按币种分别列出，绝不跨币种相加。增长贡献度比绝对值有用 —— 它告诉你这次的增量是哪个店带来的。"
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
          <div className="flex flex-col gap-1">
            <span className="text-copy-13 text-muted-foreground">拆解维度</span>
            <Segmented
              value={dimension}
              onChange={(next) => {
                setDimension(next);
                setPage(0);
              }}
              options={dimensionOptions()}
              aria-label="店铺与站点拆解维度"
            />
          </div>
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => void refreshProjection()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新店铺/站点投影
          </Button>
        </div>
        <p className="text-copy-13 text-muted-foreground mt-3">
          当前维度：{dimensionLabel(data?.dimension ?? dimension)}；状态：
          {statusLabel(data?.status ?? 'EMPTY')}。
          {data ? <>基期（上一等长窗口）：{baseRangeText(data)}。</> : null}
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
            title="店铺与站点拆解报表读不到"
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
            title="正在读取店铺与站点拆解报表…"
            description="读取期间页面上的「数据截至」仍然有效。"
          />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            title={emptyReasonLabel(data?.emptyReason ?? null) || '没有店铺维度数据'}
            description={emptyText(data)}
          />
        </SectionCard>
      )}

      {/* ── 就绪态 ─────────────────────────────────────────────────────── */}
      {state === 'ready' && data && (
        <>
          {/* ── 无法唯一归属到站点（常驻，站点维度的销量不完整的答案）──── */}
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

          {/* ── 分组报表 ───────────────────────────────────────────────── */}
          <SectionCard
            title="维度拆解报表"
            description={`一行 = 一个${dimensionLabel(data.dimension)}。金额按币种分别列出；「—」= 这一格算不出来，不是 0。`}
            extra={
              <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
                <span>
                  第 {data.page + 1} / {Math.max(Math.ceil(data.totalRows / data.size), 1)} 页（
                  {data.totalRows} 条）
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
                  disabled={(data.page + 1) * data.size >= data.totalRows}
                  onClick={() => setPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            }
          >
            <p className="text-copy-13 text-muted-foreground mb-3">{shareVsContributionNote()}</p>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">{dimensionLabel(data.dimension)}</th>
                    <th className="py-2 pr-3 font-medium">平台</th>
                    <th className="py-2 pr-3 font-medium">站点 / 新老店</th>
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
                    <tr key={row.dimensionKey} className="border-border border-b">
                      <td className="py-2 pr-3">
                        <div className="font-medium">{row.dimensionLabel || row.dimensionKey}</div>
                        <div className="text-copy-13 text-muted-foreground">{row.dimensionKey}</div>
                        {row.note && (
                          <div className="text-copy-13 text-muted-foreground">{row.note}</div>
                        )}
                      </td>
                      <td className="py-2 pr-3">
                        {row.platformCode ? (
                          <>
                            <div>{row.platformName || row.platformCode}</div>
                            <div className="text-copy-13 text-muted-foreground">
                              {row.platformCode}
                            </div>
                          </>
                        ) : (
                          <span className="text-muted-foreground">{platformLabel(row.platformCode)}</span>
                        )}
                      </td>
                      <td className="py-2 pr-3">
                        {row.siteCode ? (
                          <div className="text-copy-13 text-muted-foreground">
                            站点 {row.siteName || row.siteCode}（{row.siteCode}）
                          </div>
                        ) : (
                          <div className="text-copy-13 text-muted-foreground">—</div>
                        )}
                        {row.storeAge ? (
                          <div className="mt-1">
                            <Badge tone={storeAgeTone(row.storeAge)}>
                              {storeAgeLabel(row.storeAge)}
                            </Badge>
                          </div>
                        ) : (
                          row.newShopCount + row.existingShopCount > 0 && (
                            <div className="text-copy-13 text-muted-foreground mt-1">
                              新店 {row.newShopCount} / 老店 {row.existingShopCount}
                            </div>
                          )
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
                          构成明细
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 新店 / 老店 ─────────────────────────────────────────────── */}
          <SectionCard
            title="新店 / 老店与增量归属"
            description="新店 / 老店按成交事实判定：当前窗口有成交、基期没有任何成交事实 ⇒ 新店。这正是「这次的增量里有多少来自新店」的答案。"
          >
            <p className="text-copy-13 text-foreground mb-3">{newVsExistingText(data.newVsExisting)}</p>
            <p className="text-copy-13 text-muted-foreground mb-3">{data.newVsExisting.note}</p>
            <div className="grid gap-3 md:grid-cols-2">
              <div className="border-border rounded-lg border px-4 py-3">
                <div className="text-copy-14 text-foreground font-medium">新店（{data.newVsExisting.newShopCount} 个）</div>
                <div className="text-copy-13 text-muted-foreground mt-1">
                  {data.newVsExisting.newStoreMetrics
                    .filter((m) => m.metricCode === 'STORE_SITE_SALES_AMOUNT')
                    .map((m) => amountText(m.value, m.currency))
                    .join(' / ') || '—'}
                </div>
              </div>
              <div className="border-border rounded-lg border px-4 py-3">
                <div className="text-copy-14 text-foreground font-medium">
                  老店（{data.newVsExisting.existingShopCount} 个）
                </div>
                <div className="text-copy-13 text-muted-foreground mt-1">
                  {data.newVsExisting.existingStoreMetrics
                    .filter((m) => m.metricCode === 'STORE_SITE_SALES_AMOUNT')
                    .map((m) => amountText(m.value, m.currency))
                    .join(' / ') || '—'}
                </div>
              </div>
            </div>
          </SectionCard>

          {/* ── 渠道占比 ───────────────────────────────────────────────── */}
          <SectionCard
            title="渠道占比（平台维度）"
            description="渠道占比始终按平台维度给出，与当前选中的拆解维度无关。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-13 text-muted-foreground">
                  <tr className="border-border border-b">
                    <th className="py-2 pr-3 font-medium">渠道（平台）</th>
                    <th className="py-2 pr-3 text-right font-medium">成交金额占比</th>
                    <th className="py-2 pr-3 text-right font-medium">销量占比</th>
                  </tr>
                </thead>
                <tbody>
                  {data.channelShares.map((share) => (
                    <tr key={share.platformCode || 'UNKNOWN'} className="border-border border-b">
                      <td className="py-2 pr-3">
                        <div className="font-medium">{channelShareLabel(share)}</div>
                        {share.note && (
                          <div className="text-copy-13 text-muted-foreground">{share.note}</div>
                        )}
                      </td>
                      <td className="py-2 pr-3 text-right">
                        {channelShareText(share, METRIC_AMOUNT_SHARE, 'PERCENT')}
                      </td>
                      <td className="py-2 pr-3 text-right">
                        {channelShareText(share, METRIC_QUANTITY_SHARE, 'PERCENT')}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {/* ── 销量覆盖度 ─────────────────────────────────────────────── */}
          {coverageText(data.coverage) && (
            <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
              <p>{coverageText(data.coverage)}</p>
            </div>
          )}

          {/* ── 构成明细 ───────────────────────────────────────────────── */}
          {drilldownTarget && (
            <SectionCard
              title={`构成明细：${drilldownTarget}`}
              description="每个汇总数字都能钻到构成明细（哪张订单、哪张订单的哪一行、哪个 SKU）。"
            >
              {drilldownError ? (
                <EmptyState
                  title="构成明细读不到"
                  description={`${drilldownError}（这是「读不到」，不是「没有明细」）—— 报表本身仍然可用。`}
                />
              ) : drilldown === null ? (
                <EmptyState title="正在读取构成明细…" description="" />
              ) : drilldown.rows.length === 0 ? (
                <EmptyState
                  title={emptyReasonLabel(drilldown.emptyReason ?? null) || '没有构成明细'}
                  description={drilldown.emptyNote}
                />
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-copy-13 text-muted-foreground">
                      <tr className="border-border border-b">
                        <th className="py-2 pr-3 font-medium">构成</th>
                        <th className="py-2 pr-3 font-medium">类型</th>
                        <th className="py-2 pr-3 font-medium">订单</th>
                        <th className="py-2 pr-3 font-medium">SKU</th>
                        <th className="py-2 pr-3 font-medium">时刻</th>
                        <th className="py-2 pr-3 text-right font-medium">件数</th>
                        <th className="py-2 pr-3 text-right font-medium">金额</th>
                      </tr>
                    </thead>
                    <tbody>
                      {drilldown.rows.map((row) => (
                        <tr key={`${row.referenceId}:${row.metricCode}`} className="border-border border-b">
                          <td className="py-2 pr-3">{drilldownReferenceText(row)}</td>
                          <td className="py-2 pr-3">{drilldownKindLabel(row.sourceKind)}</td>
                          <td className="py-2 pr-3">{row.orderNo || '—'}</td>
                          <td className="py-2 pr-3">
                            {row.skuCode ? `${row.skuName || row.skuCode}（${row.skuCode}）` : '—'}
                          </td>
                          <td className="py-2 pr-3">{occurredAtText(row)}</td>
                          <td className="py-2 pr-3 text-right">{row.quantity}</td>
                          <td className="py-2 pr-3 text-right">
                            {amountText(row.value, row.currency)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </SectionCard>
          )}

          {/* ── 指标口径目录 ───────────────────────────────────────────── */}
          <SectionCard
            title="指标口径目录"
            description="口径唯一的展示形态：一个指标只允许一处定义，其他地方引用不允许重算。"
          >
            <div className="space-y-2">
              {definitions.map((definition) => (
                <div key={definition.metricCode} className="border-border rounded-lg border px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-14 text-foreground font-medium">
                      {definition.displayName}
                    </span>
                    <span className="text-copy-13 text-muted-foreground">
                      {definition.metricCode}
                    </span>
                    <Badge tone="neutral">{metricUnitLabel(definition.unit)}</Badge>
                    <span className="text-copy-13 text-muted-foreground">
                      {metricFormulaText(definition)}
                    </span>
                  </div>
                  <p className="text-copy-13 text-muted-foreground mt-1">{definition.description}</p>
                </div>
              ))}
            </div>
          </SectionCard>

          {/* ── 能力声明 ───────────────────────────────────────────────── */}
          <SectionCard
            title="能力声明"
            description="哪些上游真接通、哪些口径在店铺/站点维度拿不到、判定规则是什么。"
          >
            <div className="space-y-3">
              <div>
                <div className="text-copy-14 text-foreground font-medium">上游模块</div>
                <ul className="text-copy-13 text-muted-foreground list-disc pl-5">
                  {upstreamModules(capabilities).map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
              <div>
                <div className="text-copy-14 text-foreground font-medium">确实拿不到的部分</div>
                <ul className="text-copy-13 text-muted-foreground list-disc pl-5">
                  {notDeliveredLines(capabilities).map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
              <div>
                <div className="text-copy-14 text-foreground font-medium">判定规则</div>
                <ul className="text-copy-13 text-muted-foreground list-disc pl-5">
                  {ruleNotes(capabilities).map((note) => (
                    <li key={note.ruleCode}>{note.description}</li>
                  ))}
                </ul>
              </div>
            </div>
          </SectionCard>
        </>
      )}
    </div>
  );
}
