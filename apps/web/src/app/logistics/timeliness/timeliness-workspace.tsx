'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, BarChart3, Clock, Globe2, RefreshCw, Save, Table2 } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
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
  deviationText,
  errorText,
  fetchAnomalies,
  fetchCapabilities,
  fetchMatrix,
  fetchMonthly,
  fetchOverview,
  fetchScoreSnapshots,
  fetchScores,
  fetchSubjects,
  hoursText,
  pageText,
  pivotCell,
  pivotMatrix,
  rateText,
  scoreGradeLabel,
  scoreText,
  shareText,
  snapshotScores,
  timelinessStatusLabel,
  type LogisticsTimelinessCapabilities,
  type LogisticsTimelinessOverview,
  type TimelinessAnomalyReport,
  type TimelinessMatrixPage,
  type TimelinessMonthlyReport,
  type TimelinessScorePage,
  type TimelinessScoreSnapshotPage,
  type TimelinessScoreSnapshotResult,
  type TimelinessSubjectPage,
} from './timeliness-model';

/**
 * 10.6.2.1 时效分析。
 *
 * <p>蓝图：「分析时效表现：各渠道各国家的<b>妥投率</b>、<b>超时率</b>、<b>平均时效波动</b>、
 * <b>旺季变化</b>，用于渠道评分与时效承诺设置。」输出「时效报表 + 渠道评分 + 异常集中分析」，
 * 并加粗「<b>时效数据要按渠道和国家交叉分析，总体平均会掩盖某些渠道在某些国家的系统性延误</b>」。
 *
 * <p>🔴 界面上唯一不可让步的一条：**报表必须是「渠道 × 国家」的交叉矩阵**。
 *
 * <ul>
 *   <li>第一张卡是真正的<b>矩阵</b>：行是渠道、列是国家，每格直接给超时率与妥投率 ——
 *       一眼能看出「哪条渠道在哪一格塌了」。</li>
 *   <li>总体平均只出现在顶部的合计卡里，<b>并且写明那是总体口径</b>；
 *       它不能代替任何一格。</li>
 *   <li>没有数据的格子<b>留空</b>，不补 0 —— 补 0 会让「这个组合没有货」
 *       看起来像「这个组合超时率 0%」。</li>
 *   <li>「不可比」和「0」在界面上是两件事：样本不足的格显示「不可比」，
 *       不显示 0.00%（0 是「比过了确实最差」）。</li>
 * </ul>
 *
 * <p>本模块**不**做的事（能力声明里写清楚了）：10.6.1.1 成本分析未交付，
 * 本页不含任何成本；10.1.2.2 比价用的渠道评分仍由人工登记，
 * 本页产出的时效评分台账**不回灌**比价的表；9.5.1.1 订单级时效在 trade 服务。
 */
export function TimelinessWorkspace() {
  const [capabilities, setCapabilities] = useState<LogisticsTimelinessCapabilities | null>(null);
  const [overview, setOverview] = useState<LogisticsTimelinessOverview | null>(null);
  const [matrix, setMatrix] = useState<TimelinessMatrixPage | null>(null);
  const [monthly, setMonthly] = useState<TimelinessMonthlyReport | null>(null);
  const [anomalies, setAnomalies] = useState<TimelinessAnomalyReport | null>(null);
  const [scores, setScores] = useState<TimelinessScorePage | null>(null);
  const [snapshots, setSnapshots] = useState<TimelinessScoreSnapshotPage | null>(null);
  const [subjects, setSubjects] = useState<TimelinessSubjectPage | null>(null);

  // 统计窗口（空串 = 用服务端默认窗口）
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  // 交叉报表筛选与分页
  const [countryFilter, setCountryFilter] = useState('');
  const [matrixSort, setMatrixSort] = useState('OVERDUE_DESC');
  const [matrixPage, setMatrixPage] = useState(0);
  const [matrixSize, setMatrixSize] = useState(50);

  // 明细筛选与分页
  const [statusFilter, setStatusFilter] = useState('');
  const [subjectPage, setSubjectPage] = useState(0);
  const [subjectSize, setSubjectSize] = useState(50);

  // 评分快照
  const [minSample, setMinSample] = useState('');
  const [snapshotResult, setSnapshotResult] = useState<TimelinessScoreSnapshotResult | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, mx, mo, an, sc, sn, sb] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(from, to),
        fetchMatrix({
          from,
          to,
          countryCode: countryFilter,
          sort: matrixSort,
          page: matrixPage,
          size: matrixSize,
        }),
        fetchMonthly({ from, to }),
        fetchAnomalies(from, to),
        fetchScores({ from, to, page: 0, size: 50 }),
        fetchScoreSnapshots({ from, to, page: 0, size: 50 }),
        fetchSubjects({ from, to, status: statusFilter, page: subjectPage, size: subjectSize }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setMatrix(mx);
      setMonthly(mo);
      setAnomalies(an);
      setScores(sc);
      setSnapshots(sn);
      setSubjects(sb);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [
    from,
    to,
    countryFilter,
    matrixSort,
    matrixPage,
    matrixSize,
    statusFilter,
    subjectPage,
    subjectSize,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  async function runSnapshot() {
    setBusy(true);
    setError('');
    try {
      const threshold = Number(minSample);
      const result = await snapshotScores({
        from: from || undefined,
        to: to || undefined,
        minSampleThreshold:
          minSample.trim() && Number.isFinite(threshold) && threshold > 0
            ? Math.floor(threshold)
            : undefined,
      });
      setSnapshotResult(result);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const pivot = useMemo(() => pivotMatrix(matrix?.rows ?? []), [matrix]);
  const window = matrix?.window ?? overview?.window ?? null;

  return (
    <>
      <PageHeader
        title="时效分析"
        description="各渠道各国家的妥投率、超时率、平均时效波动与旺季变化，用于渠道评分与时效承诺设置。"
      />

      {error ? (
        <Notice tone="danger" title="加载未完成">
          {error}
        </Notice>
      ) : null}

      {capabilities ? (
        <Notice tone="warning" title="报表按「渠道 × 国家」交叉给">
          {capabilities.highlight}
        </Notice>
      ) : null}

      {/* ---- 统计窗口 ---- */}
      <SectionCard
        className="mt-4"
        title="统计窗口"
        description={
          window
            ? `${window.note}（当前窗口 ${window.days} 天，分桶时区 ${window.timeZone}）`
            : '起止留空时用服务端默认窗口（最近 90 天）。'
        }
        extra={
          <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
            <RefreshCw className="mr-1.5 h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <label className="text-muted-foreground">起</label>
          <input
            type="date"
            className="rounded border px-2 py-1"
            value={from}
            onChange={(e) => {
              setFrom(e.target.value);
              setMatrixPage(0);
            }}
          />
          <label className="text-muted-foreground">止</label>
          <input
            type="date"
            className="rounded border px-2 py-1"
            value={to}
            onChange={(e) => {
              setTo(e.target.value);
              setMatrixPage(0);
            }}
          />
          {from || to ? (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setFrom('');
                setTo('');
                setMatrixPage(0);
              }}
            >
              回到默认窗口
            </Button>
          ) : null}
        </div>
      </SectionCard>

      {/* ---- 顶部合计（总体口径，写明是总体）---- */}
      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="已发出票数"
          value={String(overview?.totals.shippedCount ?? 0)}
          comparisonLabel={`总体口径：${overview?.totals.deliveredCount ?? 0} 票已签收、${
            overview?.totals.pendingCount ?? 0
          } 票在途、${overview?.totals.returnedCount ?? 0} 票退回`}
        />
        <StatCard
          label="总体妥投率"
          value={rateText(overview?.totals.deliveryRate)}
          comparisonLabel="总体口径，不能代替任何一个「渠道 × 国家」的格"
        />
        <StatCard
          label="总体超时率"
          value={rateText(overview?.totals.overdueRate)}
          comparisonLabel={`超时 ${overview?.totals.overdueCount ?? 0} 票 / 可比 ${
            overview?.totals.comparableCount ?? 0
          } 票（可比 = 已签收且有承诺时效）`}
        />
        <StatCard
          label="平均时效"
          value={hoursText(overview?.totals.avgTransitHours)}
          comparisonLabel={`平均偏差 ${deviationText(overview?.totals.avgDeviationHours)}（正数 = 晚于承诺）`}
        />
      </div>

      <Tabs defaultValue="matrix" className="mt-6">
        <TabsList>
          <TabsTrigger value="matrix">交叉报表</TabsTrigger>
          <TabsTrigger value="monthly">旺季与趋势</TabsTrigger>
          <TabsTrigger value="anomalies">异常集中</TabsTrigger>
          <TabsTrigger value="scores">渠道评分</TabsTrigger>
          <TabsTrigger value="subjects">逐票明细</TabsTrigger>
        </TabsList>

        {/* ---------------- 交叉报表（渠道 × 国家）---------------- */}
        <TabsContent value="matrix">
          <SectionCard
            title="渠道 × 国家 交叉矩阵"
            description="行是渠道、列是国家，每格给超时率与妥投率。总体平均会掩盖某些渠道在某些国家的系统性延误 —— 所以这里没有「某渠道总体」那一行。没有数据的格子留空，不补 0。"
            extra={
              <div className="flex items-center gap-2">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={matrixSort}
                  onChange={(e) => {
                    setMatrixSort(e.target.value);
                    setMatrixPage(0);
                  }}
                >
                  <option value="OVERDUE_DESC">按超时数降序</option>
                  <option value="RATE_DESC">按超时率降序</option>
                  <option value="SHIPPED_DESC">按发出量降序</option>
                  <option value="CHANNEL_ASC">按渠道升序</option>
                </select>
              </div>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <select
                className="rounded border px-2 py-1"
                value={countryFilter}
                onChange={(e) => {
                  setCountryFilter(e.target.value);
                  setMatrixPage(0);
                }}
              >
                <option value="">全部国家</option>
                {[...new Set((matrix?.rows ?? []).map((r) => r.countryCode))].map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </select>
              <span className="text-muted-foreground">
                共 {matrix?.totalElements ?? 0} 个「渠道 × 国家」的格
              </span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载交叉报表…</p>
            ) : !matrix || matrix.rows.length === 0 ? (
              <EmptyState
                icon={<Table2 className="h-6 w-6" />}
                title="这个窗口里没有可交叉的时效数据"
                description="交叉报表需要「已发出 + 归到某个渠道 + 有目的国」的轨迹。先在轨迹查询里同步运单集合，再回来看。"
              />
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-muted-foreground border-b text-left">
                        <th className="py-2">渠道 \ 国家</th>
                        {pivot.countries.map((country) => (
                          <th key={country} className="py-2 text-right">
                            {country}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {pivot.channels.map((channel) => (
                        <tr key={channel} className="border-b last:border-0">
                          <td className="py-2">
                            <div className="font-medium">{channel}</div>
                            <div className="text-muted-foreground text-xs">
                              {matrix.rows.find((r) => r.channelCode === channel)?.channelName}
                            </div>
                          </td>
                          {pivot.countries.map((country) => {
                            const cell = pivotCell(pivot, channel, country);
                            return (
                              <td key={country} className="py-2 text-right align-top">
                                {cell ? (
                                  <>
                                    <div
                                      className={
                                        cell.overdueRate !== null && cell.overdueRate > 0
                                          ? 'text-destructive font-medium'
                                          : 'font-medium'
                                      }
                                    >
                                      超时 {rateText(cell.overdueRate)}
                                    </div>
                                    <div className="text-muted-foreground text-xs">
                                      妥投 {rateText(cell.deliveryRate)}
                                    </div>
                                    <div className="text-muted-foreground text-xs">
                                      均时 {hoursText(cell.avgTransitHours)}
                                    </div>
                                    <div className="text-muted-foreground text-xs">
                                      {cell.shippedCount} 票 · 超时 {cell.overdueCount} 票
                                    </div>
                                  </>
                                ) : (
                                  <span className="text-muted-foreground">—</span>
                                )}
                              </td>
                            );
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <div className="mt-6">
                  <p className="text-muted-foreground mb-2 text-sm">
                    逐格明细（当前页 {pageText(matrix.page, matrix.size, matrix.totalElements)}）
                  </p>
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-muted-foreground border-b text-left">
                          <th className="py-2">渠道</th>
                          <th className="py-2">国家</th>
                          <th className="py-2 text-right">发出 / 签收</th>
                          <th className="py-2 text-right">可比</th>
                          <th className="py-2 text-right">妥投率</th>
                          <th className="py-2 text-right">达成率</th>
                          <th className="py-2 text-right">超时率</th>
                          <th className="py-2 text-right">平均时效</th>
                          <th className="py-2 text-right">平均偏差</th>
                          <th className="py-2 text-right">P90</th>
                          <th className="py-2 text-right">最大超时</th>
                          <th className="py-2 text-right">评分</th>
                        </tr>
                      </thead>
                      <tbody>
                        {matrix.rows.map((row) => (
                          <tr
                            key={`${row.channelCode}/${row.countryCode}`}
                            className="border-b last:border-0"
                          >
                            <td className="py-2">{row.channelCode}</td>
                            <td className="py-2">{row.countryCode}</td>
                            <td className="py-2 text-right">
                              {row.shippedCount} / {row.deliveredCount}
                            </td>
                            <td className="py-2 text-right">
                              {row.comparableCount}
                              {row.noPromiseCount > 0 ? (
                                <span
                                  className="text-muted-foreground text-xs"
                                  title="已签收但没有承诺时效可比，不算准点也不算超时"
                                >
                                  （+{row.noPromiseCount} 无承诺）
                                </span>
                              ) : null}
                            </td>
                            <td className="py-2 text-right">{rateText(row.deliveryRate)}</td>
                            <td className="py-2 text-right">{rateText(row.onTimeRate)}</td>
                            <td className="py-2 text-right">
                              <span
                                className={
                                  row.overdueRate !== null && row.overdueRate > 0
                                    ? 'text-destructive font-medium'
                                    : ''
                                }
                              >
                                {rateText(row.overdueRate)}
                              </span>
                            </td>
                            <td className="py-2 text-right">{hoursText(row.avgTransitHours)}</td>
                            <td className="py-2 text-right">
                              {deviationText(row.avgDeviationHours)}
                            </td>
                            <td className="py-2 text-right">{hoursText(row.p90TransitHours)}</td>
                            <td className="py-2 text-right">{hoursText(row.maxOverdueHours)}</td>
                            <td className="py-2 text-right">
                              {row.sampleSufficient ? (
                                <Badge tone="info">
                                  {scoreText(row.score)} · {scoreGradeLabel(row.scoreGrade)}
                                </Badge>
                              ) : (
                                <Badge tone="neutral">不可比（样本不足）</Badge>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>

                  <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={matrix.page <= 0}
                      onClick={() => setMatrixPage(Math.max(matrix.page - 1, 0))}
                    >
                      上一页
                    </Button>
                    <span className="text-muted-foreground">
                      第 {matrix.page + 1} / {Math.max(matrix.totalPages, 1)} 页
                    </span>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={matrix.page + 1 >= matrix.totalPages}
                      onClick={() => setMatrixPage(matrix.page + 1)}
                    >
                      下一页
                    </Button>
                    <select
                      className="rounded border px-2 py-1"
                      value={matrixSize}
                      onChange={(e) => {
                        setMatrixSize(Number(e.target.value));
                        setMatrixPage(0);
                      }}
                    >
                      <option value={20}>每页 20</option>
                      <option value={50}>每页 50</option>
                      <option value={100}>每页 100</option>
                    </select>
                  </div>
                </div>
              </>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 旺季与趋势 ---------------- */}
        <TabsContent value="monthly">
          <SectionCard
            title="旺季与趋势"
            description={
              monthly?.note ??
              '按 UTC 月分桶；「旺季」= 该月超时率高于窗口基线。基线是窗口内加权口径，不是各月平均值的平均。'
            }
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载月度趋势…</p>
            ) : !monthly || monthly.rows.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="h-6 w-6" />}
                title="这个窗口里没有月度趋势"
                description="月度分桶按轨迹的发货时刻；窗口里没有已发出的货就没有月份桶。"
              />
            ) : (
              <>
                <div className="mb-3 flex flex-wrap gap-4 text-sm">
                  <span>
                    基线超时率：
                    <strong>{rateText(monthly.baseline.overdueRate)}</strong>
                  </span>
                  <span>
                    旺季月份：
                    <strong>{monthly.peakMonthCount}</strong> 个
                  </span>
                  <span>
                    基线平均时效：
                    <strong>{hoursText(monthly.baseline.avgTransitHours)}</strong>
                  </span>
                </div>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">月份</th>
                      <th className="py-2 text-right">发出 / 签收</th>
                      <th className="py-2 text-right">可比</th>
                      <th className="py-2 text-right">妥投率</th>
                      <th className="py-2 text-right">超时率</th>
                      <th className="py-2 text-right">平均时效</th>
                      <th className="py-2 text-right">平均偏差</th>
                      <th className="py-2 text-right">相对基线</th>
                      <th className="py-2">旺季</th>
                    </tr>
                  </thead>
                  <tbody>
                    {monthly.rows.map((row) => (
                      <tr key={row.monthKey} className="border-b last:border-0">
                        <td className="py-2 font-mono text-xs">{row.monthKey}</td>
                        <td className="py-2 text-right">
                          {row.shippedCount} / {row.deliveredCount}
                        </td>
                        <td className="py-2 text-right">{row.comparableCount}</td>
                        <td className="py-2 text-right">{rateText(row.deliveryRate)}</td>
                        <td className="py-2 text-right">
                          <span
                            className={
                              row.overdueRate !== null && row.overdueRate > 0
                                ? 'text-destructive font-medium'
                                : ''
                            }
                          >
                            {rateText(row.overdueRate)}
                          </span>
                        </td>
                        <td className="py-2 text-right">{hoursText(row.avgTransitHours)}</td>
                        <td className="py-2 text-right">{deviationText(row.avgDeviationHours)}</td>
                        <td className="py-2 text-right">
                          {deviationText(row.vsBaselineTransitHours)}
                        </td>
                        <td className="py-2">
                          {row.peakMonth ? (
                            <Badge tone="warning">旺季</Badge>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 异常集中分析 ---------------- */}
        <TabsContent value="anomalies">
          <SectionCard
            title="异常集中分析"
            description={
              anomalies?.note ??
              '贡献率与累计贡献率在「渠道 × 国家」的格上算：所以「哪个渠道在哪个国家拖后腿」能被直接读出来。'
            }
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载异常集中分析…</p>
            ) : !anomalies || anomalies.cells.length === 0 ? (
              <EmptyState
                icon={<AlertTriangle className="h-6 w-6" />}
                title="窗口内没有超时"
                description="没有超时就没有「谁贡献了大部分超时」可分析 —— 这不是异常为空，是这批货都准点。"
              />
            ) : (
              <>
                <div className="mb-3 flex flex-wrap gap-4 text-sm">
                  <span>
                    超时总量：
                    <strong>{anomalies.totalOverdue}</strong> 票
                  </span>
                  <span>
                    头号格贡献：
                    <strong>{shareText(anomalies.topOneShare)}</strong>
                  </span>
                  <span>
                    前三格贡献：
                    <strong>{shareText(anomalies.topThreeShare)}</strong>
                  </span>
                  <span>
                    帕累托前沿：
                    <strong>
                      {anomalies.paretoCellCount} 格 / {anomalies.paretoOverdueCount} 票
                    </strong>
                  </span>
                </div>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">#</th>
                      <th className="py-2">渠道</th>
                      <th className="py-2">国家</th>
                      <th className="py-2 text-right">超时数</th>
                      <th className="py-2 text-right">超时率</th>
                      <th className="py-2 text-right">平均偏差</th>
                      <th className="py-2 text-right">最大超时</th>
                      <th className="py-2 text-right">贡献率</th>
                      <th className="py-2 text-right">累计</th>
                      <th className="py-2">帕累托</th>
                    </tr>
                  </thead>
                  <tbody>
                    {anomalies.cells.map((cell) => (
                      <tr
                        key={`${cell.channelCode}/${cell.countryCode}`}
                        className="border-b last:border-0"
                      >
                        <td className="py-2">{cell.rank}</td>
                        <td className="py-2">{cell.channelCode}</td>
                        <td className="py-2">{cell.countryCode}</td>
                        <td className="py-2 text-right">{cell.overdueCount}</td>
                        <td className="py-2 text-right">{rateText(cell.overdueRate)}</td>
                        <td className="py-2 text-right">{deviationText(cell.avgDeviationHours)}</td>
                        <td className="py-2 text-right">{hoursText(cell.maxOverdueHours)}</td>
                        <td className="py-2 text-right">{shareText(cell.contributionRate)}</td>
                        <td className="py-2 text-right">{shareText(cell.cumulativeRate)}</td>
                        <td className="py-2">
                          {cell.inPareto ? (
                            <Badge tone="danger">贡献大部分超时</Badge>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <div className="mt-6 grid gap-4 sm:grid-cols-2">
                  <div>
                    <p className="text-muted-foreground mb-2 text-sm">按渠道集中度</p>
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-muted-foreground border-b text-left">
                          <th className="py-2">渠道</th>
                          <th className="py-2 text-right">超时数</th>
                          <th className="py-2 text-right">占比</th>
                        </tr>
                      </thead>
                      <tbody>
                        {anomalies.byChannel.map((group) => (
                          <tr key={group.key} className="border-b last:border-0">
                            <td className="py-2">{group.label}</td>
                            <td className="py-2 text-right">{group.overdueCount}</td>
                            <td className="py-2 text-right">{shareText(group.share)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div>
                    <p className="text-muted-foreground mb-2 text-sm">按国家集中度</p>
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-muted-foreground border-b text-left">
                          <th className="py-2">国家</th>
                          <th className="py-2 text-right">超时数</th>
                          <th className="py-2 text-right">占比</th>
                        </tr>
                      </thead>
                      <tbody>
                        {anomalies.byCountry.map((group) => (
                          <tr key={group.key} className="border-b last:border-0">
                            <td className="py-2">{group.label}</td>
                            <td className="py-2 text-right">{group.overdueCount}</td>
                            <td className="py-2 text-right">{shareText(group.share)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              </>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 渠道评分 ---------------- */}
        <TabsContent value="scores">
          <SectionCard
            title="渠道时效评分"
            description={
              scores?.note ??
              '评分按「渠道 × 国家」给，0~100；样本不足时是不可比（null）而不是 0 分。'
            }
            extra={
              <Button size="sm" onClick={() => void runSnapshot()} disabled={busy}>
                <Save className="mr-1.5 h-4 w-4" />
                重算并落库
              </Button>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <label className="text-muted-foreground">最小样本量</label>
              <input
                className="rounded border px-2 py-1"
                value={minSample}
                placeholder={String(capabilities?.defaultMinSampleThreshold ?? 10)}
                onChange={(e) => setMinSample(e.target.value)}
              />
              <span className="text-muted-foreground">
                留空用默认（{capabilities?.defaultMinSampleThreshold ?? 10}）
              </span>
            </div>

            {snapshotResult ? (
              <Notice tone="success" title={`已落库 ${snapshotResult.written} 格`}>
                {snapshotResult.notes.length > 0
                  ? snapshotResult.notes.join('；')
                  : `窗口 ${snapshotResult.window.days} 天，样本门槛 ${snapshotResult.minSampleThreshold}。`}
              </Notice>
            ) : null}

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载渠道评分…</p>
            ) : !scores || scores.rows.length === 0 ? (
              <EmptyState
                icon={<Globe2 className="h-6 w-6" />}
                title="没有可评分的格子"
                description="评分按「渠道 × 国家」给；窗口里没有「已发出 + 有渠道 + 有国家 + 有承诺可比」的票就没有可评分的格。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">渠道</th>
                    <th className="py-2">国家</th>
                    <th className="py-2 text-right">可比样本</th>
                    <th className="py-2 text-right">妥投率</th>
                    <th className="py-2 text-right">达成率</th>
                    <th className="py-2 text-right">超时率</th>
                    <th className="py-2 text-right">平均偏差</th>
                    <th className="py-2 text-right">评分</th>
                    <th className="py-2">落库时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {scores.rows.map((row) => (
                    <tr
                      key={`${row.channelCode}/${row.countryCode}`}
                      className="border-b last:border-0"
                    >
                      <td className="py-2">{row.channelCode}</td>
                      <td className="py-2">{row.countryCode}</td>
                      <td className="py-2 text-right">
                        {row.comparableCount}
                        <span className="text-muted-foreground text-xs">
                          （门槛 {row.minSampleThreshold}）
                        </span>
                      </td>
                      <td className="py-2 text-right">{rateText(row.deliveryRate)}</td>
                      <td className="py-2 text-right">{rateText(row.onTimeRate)}</td>
                      <td className="py-2 text-right">{rateText(row.overdueRate)}</td>
                      <td className="py-2 text-right">{deviationText(row.avgDeviationHours)}</td>
                      <td className="py-2 text-right">
                        {row.sampleSufficient ? (
                          <Badge tone="info">
                            {scoreText(row.score)} · {scoreGradeLabel(row.scoreGrade)}
                          </Badge>
                        ) : (
                          <Badge tone="neutral">不可比（样本不足）</Badge>
                        )}
                      </td>
                      <td className="py-2 text-xs">
                        {row.snapshotComputedAt ? (
                          new Date(row.snapshotComputedAt).toLocaleString()
                        ) : (
                          <span className="text-muted-foreground">尚未落库</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          <SectionCard
            className="mt-4"
            title="评分台账"
            description="落库的时效评分快照（真分页 + 真筛选）。比率由数据库生成列算，绕过服务层写 SQL 也写不进一个与计数对不上的比率。"
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载评分台账…</p>
            ) : !snapshots || snapshots.rows.length === 0 ? (
              <EmptyState
                icon={<Save className="h-6 w-6" />}
                title="台账还是空的"
                description="点右上角的「重算并落库」把当前窗口的时效评分落成台账。同一窗口重算一次是幂等的，不会堆出第二份。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">渠道</th>
                    <th className="py-2">国家</th>
                    <th className="py-2">窗口</th>
                    <th className="py-2 text-right">发出 / 可比</th>
                    <th className="py-2 text-right">超时率</th>
                    <th className="py-2 text-right">评分</th>
                    <th className="py-2">计算时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {snapshots.rows.map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2">{row.channelCode}</td>
                      <td className="py-2">{row.countryCode}</td>
                      <td className="py-2 text-xs">
                        {new Date(row.windowStart).toISOString().slice(0, 10)} ~{' '}
                        {new Date(row.windowEnd).toISOString().slice(0, 10)}
                      </td>
                      <td className="py-2 text-right">
                        {row.shippedCount} / {row.comparableCount}
                      </td>
                      <td className="py-2 text-right">{rateText(row.overdueRate)}</td>
                      <td className="py-2 text-right">
                        {row.sampleSufficient ? (
                          <Badge tone="info">
                            {scoreText(row.score)} · {scoreGradeLabel(row.scoreGrade)}
                          </Badge>
                        ) : (
                          <Badge tone="neutral">不可比（样本不足）</Badge>
                        )}
                      </td>
                      <td className="py-2 text-xs">{new Date(row.computedAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 逐票明细 ---------------- */}
        <TabsContent value="subjects">
          <SectionCard
            title="逐票时效明细"
            description={
              subjects?.note ?? '想核实某一格为什么超时，往这里看：逐票给实际时效与相对承诺的偏差。'
            }
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <select
                className="rounded border px-2 py-1"
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value);
                  setSubjectPage(0);
                }}
              >
                <option value="">全部时效状态</option>
                <option value="OVERDUE">超时送达</option>
                <option value="NO_PROMISE">无承诺可比</option>
                <option value="IN_TRANSIT">在途未签收</option>
                <option value="ON_TIME">准点送达</option>
              </select>
              <span className="text-muted-foreground">共 {subjects?.totalElements ?? 0} 条</span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载逐票明细…</p>
            ) : !subjects || subjects.rows.length === 0 ? (
              <EmptyState
                icon={<Clock className="h-6 w-6" />}
                title="没有符合条件的运单"
                description="明细来自上游 10.3.1.1 的轨迹主体：窗口里没有已发出的货就没有明细。"
              />
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-muted-foreground border-b text-left">
                        <th className="py-2">运单号</th>
                        <th className="py-2">渠道 / 国家</th>
                        <th className="py-2">发货</th>
                        <th className="py-2">承诺到达</th>
                        <th className="py-2">实际签收</th>
                        <th className="py-2 text-right">实际时效</th>
                        <th className="py-2 text-right">偏差</th>
                        <th className="py-2">时效状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {subjects.rows.map((row) => (
                        <tr key={row.id} className="border-b last:border-0">
                          <td className="py-2 font-mono text-xs">{row.masterWaybillNo}</td>
                          <td className="py-2">
                            <div>{row.channelCode ?? '未归渠道'}</div>
                            <div className="text-muted-foreground text-xs">{row.countryCode}</div>
                          </td>
                          <td className="py-2 text-xs">
                            {row.shippedAt
                              ? new Date(row.shippedAt).toISOString().slice(0, 16)
                              : '—'}
                          </td>
                          <td className="py-2 text-xs">
                            {row.promisedEtaAt
                              ? new Date(row.promisedEtaAt).toISOString().slice(0, 16)
                              : '无承诺'}
                          </td>
                          <td className="py-2 text-xs">
                            {row.deliveredAt
                              ? new Date(row.deliveredAt).toISOString().slice(0, 16)
                              : '—'}
                          </td>
                          <td className="py-2 text-right">{hoursText(row.transitHours)}</td>
                          <td className="py-2 text-right">
                            <span
                              className={
                                row.deviationHours !== null && row.deviationHours > 0
                                  ? 'text-destructive font-medium'
                                  : ''
                              }
                            >
                              {deviationText(row.deviationHours)}
                            </span>
                          </td>
                          <td className="py-2">
                            <Badge
                              tone={
                                row.timelinessStatus === 'OVERDUE'
                                  ? 'danger'
                                  : row.timelinessStatus === 'ON_TIME'
                                    ? 'success'
                                    : 'neutral'
                              }
                            >
                              {timelinessStatusLabel(row.timelinessStatus)}
                            </Badge>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={subjects.page <= 0}
                    onClick={() => setSubjectPage(Math.max(subjects.page - 1, 0))}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground">
                    第 {subjects.page + 1} / {Math.max(subjects.totalPages, 1)} 页 ·{' '}
                    {pageText(subjects.page, subjects.size, subjects.totalElements)}
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={subjects.page + 1 >= subjects.totalPages}
                    onClick={() => setSubjectPage(subjects.page + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}
