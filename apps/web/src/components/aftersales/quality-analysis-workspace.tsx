'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  DefectDistributionReport,
  DefectObservationPage,
  ImprovementReport,
  LotTrendReport,
  OpenBoxReport,
  PhotoClusterReport,
  QualityAnalysisCapabilities,
  QualityCostReport,
  QualityOverview,
  SupplierComparisonReport,
} from '../../../../../packages/api-contracts/src/index';
import {
  costBasisLabel,
  costBasisTone,
  costTotalOf,
  COST_BASIS,
  defectShareReading,
  DISCOVERY_STAGE_LABELS,
  excludedCostNote,
  fetchCapabilities,
  fetchDefectDistribution,
  fetchImprovements,
  fetchLotTrend,
  fetchObservations,
  fetchOpenBoxRate,
  fetchOverview,
  fetchPhotoClusters,
  fetchQualityCost,
  fetchSupplierComparison,
  formatMoney,
  formatPercent,
  openBoxReading,
  photoClusterReading,
  rateTone,
  recordObservation,
  severityTone,
  supplierDisplayName,
  voidObservation,
  type Tone,
} from './quality-analysis-model';

/**
 * 11.5.2.1 质量分析。
 *
 * <p>蓝图：「质量问题<b>专项分析</b>：缺陷类型分布、批次质量趋势、供应商质量对比、
 * 缺陷照片聚类、<b>支持开箱率与质量成本估算</b>。」
 * 输出：「<b>质量报表 + 责任分布 + 改进清单</b>。」
 * 加粗句：「<b>质量成本（退货物流 + 损耗 + 差评影响）应量化</b>，否则改进动力不足。」
 *
 * <p><b>页面围绕那条加粗句组织</b>：质量成本那一块把三项**分来源**并排显示，
 * 每项都带依据标签（实测 / 按参数估算 / 参数未登记），并把「合计排除了几项未登记」
 * 原样印出来 —— 把三种来源不同的数字合成一个漂亮的总数，正是这条加粗句要防的事。
 *
 * <p><b>页面刻意没有的四个东西</b>：
 *
 * <ol>
 *   <li><b>「相似度」滑块 / 照片外观聚类</b>：本仓没有图像库、没有模型、没有视觉服务接入点，
 *       聚类口径是<b>内容指纹完全相同</b>。渲染一个相似度控件就是在冒充图像识别能力。</li>
 *   <li><b>「差评」这个发现环节</b>：全库没有商品评价表，
 *       本模块<b>不产出「买家给了差评」这个事实</b>，只在成本里按租户参数估算其影响。</li>
 *   <li><b>成本合计里的「0 元」</b>：参数未登记时显示「未登记」并**排除在合计之外**。
 *       画成 0 等于宣称「质量没花钱」，而真相是「算不出来」。</li>
 *   <li><b>「发起供应商索赔」「写入评级」</b>：属 6.4.2.2 / 6.1.1.3，<b>均未交付</b>。</li>
 * </ol>
 */
export function QualityAnalysisWorkspace() {
  const [caps, setCaps] = useState<QualityAnalysisCapabilities | null>(null);
  const [overview, setOverview] = useState<QualityOverview | null>(null);
  const [distribution, setDistribution] = useState<DefectDistributionReport | null>(null);
  const [trend, setTrend] = useState<LotTrendReport | null>(null);
  const [suppliers, setSuppliers] = useState<SupplierComparisonReport | null>(null);
  const [clusters, setClusters] = useState<PhotoClusterReport | null>(null);
  const [openBox, setOpenBox] = useState<OpenBoxReport | null>(null);
  const [cost, setCost] = useState<QualityCostReport | null>(null);
  const [improvements, setImprovements] = useState<ImprovementReport | null>(null);
  const [observations, setObservations] = useState<DefectObservationPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  // 登记缺陷观察（开箱率的唯一数据来源）。
  const [obsCheckResultId, setObsCheckResultId] = useState('');
  const [obsStage, setObsStage] = useState('BUYER_UNBOXING');
  const [obsQty, setObsQty] = useState('1');
  const [obsSeverity, setObsSeverity] = useState('MAJOR');
  const [obsName, setObsName] = useState('');

  /**
   * 期间参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   `from?: string` 与 `from: string | undefined` 不是同一个类型。
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const window: { from?: string; to?: string } = {
    ...(from ? { from } : {}),
    ...(to ? { to } : {}),
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, d, t, s, p, x, q, i, v] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(window),
        fetchDefectDistribution(window),
        fetchLotTrend({ ...window, granularity: 'month' }),
        fetchSupplierComparison(window),
        fetchPhotoClusters({ minClusterSize: 2 }),
        fetchOpenBoxRate(window),
        fetchQualityCost(window),
        fetchImprovements({ page: 0, size: 20 }),
        fetchObservations({ page: 0, size: 20 }),
      ]);
      setCaps(c);
      setOverview(o);
      setDistribution(d);
      setTrend(t);
      setSuppliers(s);
      setClusters(p);
      setOpenBox(x);
      setCost(q);
      setImprovements(i);
      setObservations(v);
    } catch (e) {
      setError(e instanceof Error ? e.message : '质量报表加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitObservation() {
    setActionError('');
    setNotice('');
    const row = observations?.rows.find((r) => r.id === obsCheckResultId);
    if (!row) {
      setActionError('请先从下面的观察列表里选一条已登记的观察（本页只演示作废；登记需要 11.2.3.1 的检项 id）');
      return;
    }
    try {
      await voidObservation(row.id, '页面演示作废', row.version);
      setNotice('已作废 —— 作废是留痕，判错了请另记一条。');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '作废失败');
    }
  }

  const openBoxRead = openBoxReading(openBox?.openBoxRate, openBox?.observationCoverage);

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="质量分析"
        description="缺陷类型分布 / 批次质量趋势 / 供应商质量对比 / 缺陷照片聚类 / 开箱率 / 质量成本估算"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}

      {loading && <p className="text-sm text-muted-foreground">加载中…</p>}

      {!loading && !error && overview && (
        <>
          {/* ---------- 总览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="已判定质检单" value={overview.concludedInspections} unit="单" />
            <StatCard
              label="非可再销售率"
              value={formatPercent(overview.nonResalableRate)}
              polarity={rateTone(overview.nonResalableRate) === 'danger' ? 'negative' : 'positive'}
              comparisonLabel={`${overview.nonResalableQty} / ${overview.inspectedQty} 件`}
            />
            <StatCard
              label="开箱率"
              value={formatPercent(overview.openBoxRate)}
              polarity={openBoxRead.tone === 'neutral' ? 'neutral' : 'negative'}
              comparisonLabel={`观察覆盖率 ${formatPercent(overview.observationCoverage)}`}
            />
            <StatCard
              label="质量成本合计"
              value={
                overview.costTotals.length
                  ? overview.costTotals.map((t) => `${t.currency} ${formatMoney(t.amount)}`).join(' / ')
                  : '暂无'
              }
              polarity={
                overview.costTotals.some((t) => t.excludedItemCount > 0) ? 'neutral' : 'negative'
              }
              comparisonLabel={
                overview.costTotals.length
                  ? `${overview.costTotals[0]?.basisLabel ?? ''}（逐项依据见下方成本块）`
                  : '还没有已判定质检单'
              }
            />
          </div>

          <SectionCard title="总览口径">
            <p className="text-sm text-muted-foreground">{overview.windowNote}</p>
            <p className="mt-2 text-sm">{overview.basisNote}</p>
            <p className="mt-2 text-xs text-muted-foreground">参考时刻：{overview.generatedAt}</p>
          </SectionCard>

          {/* ---------- 质量成本（加粗句所在）---------- */}
          {cost && (
            <SectionCard title="质量成本估算">
              <p className="text-sm text-muted-foreground">{cost.basisNote}</p>
              {cost.currencies.length === 0 ? (
                <EmptyState title="还没有已判定质检单" description="质检判定之后才会有损耗金额可算。" />
              ) : (
                <div className="mt-4 space-y-4">
                  {cost.currencies.map((c) => {
                    const { total, excluded } = costTotalOf(c);
                    return (
                      <div key={c.currency} className="rounded-md border p-4">
                        <div className="mb-2 flex items-center justify-between">
                          <h4 className="font-medium">{c.currency}</h4>
                          <span className="text-sm">
                            合计 <strong>{formatMoney(total)}</strong> · {excludedCostNote(excluded)}
                          </span>
                        </div>
                        <table className="w-full text-sm">
                          <thead>
                            <tr className="text-left text-muted-foreground">
                              <th className="py-1">分项</th>
                              <th className="py-1">依据</th>
                              <th className="py-1 text-right">金额</th>
                              <th className="py-1">算法</th>
                            </tr>
                          </thead>
                          <tbody>
                            {[c.loss, c.returnLogistics, c.reviewImpact].map((item) => (
                              <tr key={item.key} className="border-t">
                                <td className="py-2">{item.label}</td>
                                <td className="py-2">
                                  <Badge tone={costBasisTone(item.basis)}>{costBasisLabel(item.basis)}</Badge>
                                </td>
                                <td className="py-2 text-right">
                                  {item.basis === 'NOT_REGISTERED' ? (
                                    <span className="text-muted-foreground">未登记</span>
                                  ) : (
                                    formatMoney(item.amount)
                                  )}
                                </td>
                                <td className="py-2 text-xs text-muted-foreground">{item.formula}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    );
                  })}
                  <p className="text-xs text-muted-foreground">{cost.reviewNote}</p>
                  <div>
                    <h4 className="mb-1 text-sm font-medium">成本参数</h4>
                    {cost.parameters.length === 0 ? (
                      <p className="text-sm text-muted-foreground">
                        <Badge tone="danger">{COST_BASIS.NOT_REGISTERED?.label}</Badge> 还没有登记任何币种的参数 ——
                        退货物流与差评影响这两项因此算不出来，也<b>不在合计里</b>。
                      </p>
                    ) : (
                      <ul className="space-y-1 text-sm">
                        {cost.parameters.map((p) => (
                          <li key={p.id}>
                            {p.currency}：退货物流 {formatMoney(p.returnLogisticsPerCase)} / 件 ·
                            差评影响 {formatMoney(p.reviewImpactPerDefect)} / 件
                            {p.sourceNote ? <span className="text-muted-foreground">（{p.sourceNote}）</span> : null}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                </div>
              )}
            </SectionCard>
          )}

          {/* ---------- 开箱率 ---------- */}
          {openBox && (
            <SectionCard title="开箱率（到手即损）">
              <p className={`text-sm ${openBoxRead.tone === 'warning' ? 'text-amber-600' : ''}`}>{openBoxRead.text}</p>
              <p className="mt-2 text-sm text-muted-foreground">{openBox.basisNote}</p>
              {openBox.stages.length === 0 ? (
                <EmptyState title="还没有登记缺陷观察" description="开箱率的唯一数据来源是「缺陷观察」，一条都没有时任何开箱率都只是「没数据」。" />
              ) : (
                <div className="mt-3 flex flex-wrap gap-2">
                  {openBox.stages.map((s) => (
                    <Badge key={s.discoveryStage} tone={s.discoveryStage === 'BUYER_UNBOXING' ? 'danger' : 'neutral'}>
                      {s.stageLabel} {s.affectedQty} 件（{formatPercent(s.share)}）
                    </Badge>
                  ))}
                </div>
              )}
              {openBox.bySku.length > 0 && (
                <table className="mt-4 w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1">SKU</th>
                      <th className="py-1 text-right">已登记件数</th>
                      <th className="py-1 text-right">开箱发现</th>
                      <th className="py-1 text-right">开箱率</th>
                      <th className="py-1 text-right">覆盖率</th>
                    </tr>
                  </thead>
                  <tbody>
                    {openBox.bySku.map((row) => (
                      <tr key={row.skuId} className="border-t">
                        <td className="py-2">{row.skuCode}</td>
                        <td className="py-2 text-right">{row.observedQty}</td>
                        <td className="py-2 text-right">{row.openBoxQty}</td>
                        <td className="py-2 text-right">{formatPercent(row.openBoxRate)}</td>
                        <td className="py-2 text-right">{formatPercent(row.observationCoverage)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ---------- 缺陷类型分布 ---------- */}
          {distribution && (
            <SectionCard title="缺陷类型分布">
              <p className="text-sm text-muted-foreground">{distribution.basisNote}</p>
              {distribution.types.length === 0 ? (
                <EmptyState title="没有失败检项" description="全部检项都判了 PASS —— 这一档没有缺陷可统计。" />
              ) : (
                <table className="mt-3 w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1">维度</th>
                      <th className="py-1">检项</th>
                      <th className="py-1 text-right">失败条数</th>
                      <th className="py-1">占比</th>
                      <th className="py-1 text-right">开箱发现</th>
                    </tr>
                  </thead>
                  <tbody>
                    {distribution.types.map((t) => (
                      <tr key={`${t.dimension}-${t.itemCode}`} className="border-t">
                        <td className="py-2">{t.dimensionLabel}</td>
                        <td className="py-2">{t.itemName}</td>
                        <td className="py-2 text-right">{t.failCount}</td>
                        <td className="py-2">{defectShareReading(t.share, distribution.totals.failCheckCount)}</td>
                        <td className="py-2 text-right">{t.openBoxQty}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ---------- 批次质量趋势 ---------- */}
          {trend && (
            <SectionCard title="批次质量趋势">
              <p className="text-sm text-muted-foreground">{trend.basisNote}</p>
              {trend.buckets.length === 0 ? (
                <EmptyState title="期间内没有已判定质检单" description="换一个期间试试，或先在 11.2.3.1 完成质检判定。" />
              ) : (
                <ul className="mt-3 space-y-2">
                  {trend.buckets.map((b) => (
                    <li key={b.bucketStart} className="flex items-center justify-between text-sm">
                      <span>{b.label}</span>
                      <span className="flex items-center gap-3">
                        <Badge tone={rateTone(b.nonResalableRate)}>
                          非可再销售 {formatPercent(b.nonResalableRate)}
                        </Badge>
                        <span className="text-muted-foreground">
                          {b.inspectionCount} 单 / {b.lotCount} 批次 / 缺陷项 {b.defectiveQty}
                        </span>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ---------- 供应商质量对比 ---------- */}
          {suppliers && (
            <SectionCard title="供应商质量对比">
              <p className="text-sm text-muted-foreground">{suppliers.basisNote}</p>
              {suppliers.suppliers.length === 0 ? (
                <EmptyState title="还没有带供应商的归因" description="批次上挂了供应商之后才会有对比数据。" />
              ) : (
                <table className="mt-3 w-full text-sm">
                  <thead>
                    <tr className="text-left text-muted-foreground">
                      <th className="py-1">供应商</th>
                      <th className="py-1 text-right">批次数</th>
                      <th className="py-1 text-right">生产责任占比</th>
                      <th className="py-1 text-right">被标记批次</th>
                      <th className="py-1 text-right">改进建议</th>
                    </tr>
                  </thead>
                  <tbody>
                    {suppliers.suppliers.map((s) => (
                      <tr key={s.supplierRefId} className="border-t">
                        <td className="py-2">
                          {supplierDisplayName(s.supplierName)}
                          {!s.registered ? (
                            <span className="ml-2 text-xs text-muted-foreground">（未登记名称）</span>
                          ) : null}
                        </td>
                        <td className="py-2 text-right">{s.lotCount}</td>
                        <td className="py-2 text-right">{formatPercent(s.productionShare)}</td>
                        <td className="py-2 text-right">{s.flaggedLots}</td>
                        <td className="py-2 text-right">{s.improvementCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {suppliers.unnamedCount > 0 && (
                <p className="mt-2 text-xs text-muted-foreground">
                  有 {suppliers.unnamedCount} 行尚未登记供应商名称 —— 显示为「未登记供应商」，
                  而不是把 uuid 当成供应商名。
                </p>
              )}
            </SectionCard>
          )}

          {/* ---------- 缺陷照片聚类 ---------- */}
          {clusters && (
            <SectionCard title="缺陷照片聚类">
              <p className="text-sm text-muted-foreground">{clusters.basisNote}</p>
              {clusters.clusters.length === 0 ? (
                <EmptyState title="没有重复出现的照片" description="同一份字节在两张以上归因单上复用时才会成簇。" />
              ) : (
                <ul className="mt-3 space-y-2 text-sm">
                  {clusters.clusters.map((c) => (
                    <li key={c.contentSha256} className="rounded-md border p-3">
                      <code className="text-xs">{c.contentSha256.slice(0, 16)}…</code>
                      <p className="mt-1">{photoClusterReading(c)}</p>
                      {c.sampleUris ? (
                        <p className="mt-1 break-all text-xs text-muted-foreground">{c.sampleUris}</p>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ---------- 责任分布 + 改进清单 ---------- */}
          {improvements && (
            <SectionCard title="责任分布与改进清单">
              <p className="text-sm text-muted-foreground">{improvements.basisNote}</p>
              <div className="mt-3 flex flex-wrap gap-2">
                {improvements.responsibility.map((r) => (
                  <Badge key={r.stage} tone={r.attributionCount === 0 ? 'neutral' : 'warning'}>
                    {r.stageLabel} {r.attributedQty} 件（{formatPercent(r.share)}）
                  </Badge>
                ))}
              </div>
              {improvements.items.length === 0 ? (
                <EmptyState title="还没有归因结论" description="改进清单来自 11.1.2.2 的归因单。" />
              ) : (
                <ul className="mt-4 space-y-3">
                  {improvements.items.map((item) => (
                    <li key={item.attributionId} className="rounded-md border p-3 text-sm">
                      <div className="flex items-center justify-between">
                        <span className="font-medium">
                          {item.skuName || item.skuCode} · {item.lotCode || '无批次'}
                        </span>
                        <span className="flex gap-2">
                          <Badge tone="neutral">{item.stageLabel}</Badge>
                          {item.lotFlagged ? <Badge tone="danger">{item.lotFlagStatus}</Badge> : null}
                        </span>
                      </div>
                      <p className="mt-1 text-muted-foreground">{item.attributionNote}</p>
                      <p className="mt-1">改进建议：{item.improvementSuggestion}</p>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ---------- 缺陷观察 ---------- */}
          {observations && (
            <SectionCard title="缺陷观察（开箱率的唯一数据来源）">
              <p className="text-sm text-muted-foreground">
                列表<b>默认含已作废行</b>：作废是留痕，抹掉就看不出这里曾经记错过什么。
                筛选「仅有效」才只看计入统计的行。
              </p>
              <div className="mt-3 flex flex-wrap items-end gap-3">
                <label className="text-sm">
                  发现环节
                  <select
                    className="ml-2 rounded border px-2 py-1"
                    value={obsStage}
                    onChange={(e) => setObsStage(e.target.value)}
                  >
                    {Object.entries(DISCOVERY_STAGE_LABELS).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-sm">
                  名称
                  <Input className="ml-2 w-40" value={obsName} onChange={(e) => setObsName(e.target.value)} />
                </label>
                <label className="text-sm">
                  件数
                  <Input className="ml-2 w-20" value={obsQty} onChange={(e) => setObsQty(e.target.value)} />
                </label>
                <label className="text-sm">
                  严重度
                  <select
                    className="ml-2 rounded border px-2 py-1"
                    value={obsSeverity}
                    onChange={(e) => setObsSeverity(e.target.value)}
                  >
                    {Object.entries({ MINOR: '轻微', MODERATE: '一般', MAJOR: '严重', CRITICAL: '致命' }).map(
                      ([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ),
                    )}
                  </select>
                </label>
                <Button variant="secondary" onClick={() => void submitObservation()}>
                  作废选中的观察
                </Button>
              </div>
              {actionError && <p className="mt-2 text-sm text-red-600">{actionError}</p>}
              {notice && <p className="mt-2 text-sm text-emerald-600">{notice}</p>}
              {observations.rows.length === 0 ? (
                <EmptyState title="还没有登记缺陷观察" description="登记之后开箱率才有分母。" />
              ) : (
                <ul className="mt-3 space-y-2 text-sm">
                  {observations.rows.map((row) => (
                    <li key={row.id} className="flex items-center justify-between border-t py-2">
                      <span>
                        {row.defectName} · {row.discoveryStageLabel} · {row.affectedQty} 件
                      </span>
                      <span className="flex gap-2">
                        <Badge tone={severityTone(row.severity) as Tone}>{row.severityLabel}</Badge>
                        <Badge tone={row.observationStatus === 'RECORDED' ? 'success' : 'neutral'}>
                          {row.observationStatusLabel}
                        </Badge>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ---------- 边界声明 ---------- */}
          {caps && (
            <SectionCard title="本模块的边界">
              <ul className="list-disc space-y-1 pl-5 text-sm">
                {caps.boundaries.map((b) => (
                  <li key={b}>{b.replaceAll('**', '')}</li>
                ))}
              </ul>
              <p className="mt-3 text-xs text-muted-foreground">{caps.crossServiceNote.replaceAll('**', '')}</p>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
