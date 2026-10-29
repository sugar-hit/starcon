'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  abcLabel,
  abcRankText,
  actionLabel,
  actionTone,
  amountRowText,
  amountText,
  availabilityLabel,
  availabilityTone,
  currencyLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  dimensionOptions,
  emptyReasonLabel,
  emptyText,
  grossProfitNote,
  metricFormulaText,
  notDeliveredLines,
  pageState,
  percentText,
  populatedSegments,
  quantityText,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  ruleThresholdText,
  segmentLabel,
  segmentOptions,
  segmentTone,
  statusLabel,
  upstreamModules,
} from './lifecycle-segmentation-model';
import type {
  LifecycleMetricReference,
  LifecycleSegmentationAmountRow,
  LifecycleSegmentationCapabilities,
  LifecycleSegmentationResponse,
  LifecycleSegmentationRow,
  LifecycleSegmentationRuleView,
  LifecycleSegmentSummary,
} from './lifecycle-segmentation-model';

/**
 * 13.3.1.2 生命周期与分层工作区（卷 13「利润与经营分析」L3 13.3.1 产品分析下的第二格）。
 *
 * <p>蓝图输出：「分层结果 + 各层建议（备货/清仓/下架/推广）。」
 * 蓝图关系：**分层是资源分配的依据** —— 爆款备货、滞销清仓，没有分层就只能平均用力。
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）的投影读模型</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/lifecycle-segmentation} —— 分档汇总 + 商品明细 + 生效规则；</li>
 *   <li>{@code GET reporting/lifecycle-segmentation/rules} —— 分层规则配置（可审计）；</li>
 *   <li>{@code GET reporting/lifecycle-segmentation/capabilities} —— 能力声明；</li>
 *   <li>{@code PUT reporting/lifecycle-segmentation/rules/{dimensionType}} —— 改规则（加版本）；</li>
 *   <li>{@code POST reporting/lifecycle-segmentation/refresh} —— 算一遍并落库。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的四件事</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>：档位算不出来时显示「不可比 / 没有数据」+ 原因，
 *       <b>绝不用 0 或某个默认档位顶替</b>（假档位会被读成「这个商品是平款」）。</li>
 *   <li><b>不跨币种相加</b>：金额<b>按币种分行</b>，每个金额行旁边贴着币种。</li>
 *   <li><b>毛利额可能算不出来</b>（出库成本没投影）⇒ 那一格显示「—」+ 原因，<b>不是 0 元</b>。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首加粗句「数据延迟要显示」）：加载中 / 出错时也显示。</li>
 * </ol>
 *
 * <p><b>🔴 前端不做业务算术</b>：档位、ABC 分档、销量份额、下滑比例、毛利额、建议动作
 * 全部来自服务端，这里只做展示格式化。前端绝自己把两个币种的金额加起来，
 * 也绝不自己算份额或下滑比例。
 */
export function AnalyticsLifecycleSegmentationWorkspace() {
  const today = new Date().toISOString().slice(0, 10);
  const initialRange = defaultRange(today);

  const [from, setFrom] = useState(initialRange.from);
  const [to, setTo] = useState(initialRange.to);
  const [dimensionType, setDimensionType] = useState('SKU');
  const [segment, setSegment] = useState('');
  const [abc, setAbc] = useState('');
  const [page, setPage] = useState(0);

  const [data, setData] = useState<LifecycleSegmentationResponse | null>(null);
  const [capabilities, setCapabilities] = useState<LifecycleSegmentationCapabilities | null>(null);
  const [rules, setRules] = useState<LifecycleSegmentationRuleView[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [refreshNote, setRefreshNote] = useState('');

  const query = useMemo(() => {
    const params = new URLSearchParams({
      from,
      to,
      dimensionType,
      page: String(page),
      size: '20',
    });
    if (segment) params.set('segment', segment);
    if (abc) params.set('abcByQuantity', abc);
    return params.toString();
  }, [from, to, dimensionType, segment, abc, page]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [report, caps, ruleViews] = await Promise.all([
        api<LifecycleSegmentationResponse>(`reporting/lifecycle-segmentation?${query}`),
        api<LifecycleSegmentationCapabilities>('reporting/lifecycle-segmentation/capabilities'),
        api<LifecycleSegmentationRuleView[]>(
          `reporting/lifecycle-segmentation/rules?dimensionType=${dimensionType}`,
        ),
      ]);
      setData(report);
      setCapabilities(caps);
      setRules(ruleViews);
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取分层结果失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [query, dimensionType]);

  const refreshProjection = useCallback(async () => {
    setBusy(true);
    setError('');
    setRefreshNote('');
    try {
      const params = new URLSearchParams({ from, to, dimensionType });
      const results = await api<{ status: string; note: string }[]>(
        `reporting/lifecycle-segmentation/refresh?${params}`,
        'POST',
      );
      setRefreshNote(results.map((result) => `${refreshStatusLabel(result.status)}：${result.note}`).join(' / '));
      // 刷完立刻重读 —— 投影变了，页面上的数必须跟着变。
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新分层结果失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [from, to, dimensionType, load]);

  useEffect(() => {
    void load();
  }, [load]);

  const state = pageState(data, error);
  const populated = populatedSegments(data);

  return (
    <div className="space-y-4">
      {/* ── 数据更新时间（常驻）────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">{dataUpdatedText(data)}</span>
          <span className="text-muted-foreground ml-2">{dataUpdatedNote(data)}</span>
        </div>
        <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
          <span>
            {from} ~ {to}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={loading || busy}
            onClick={() => void load()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            重读
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={loading || busy}
            onClick={() => void refreshProjection()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            重新分层
          </Button>
        </div>
      </div>

      {/* ── 筛选 ───────────────────────────────────────────────────────── */}
      <SectionCard
        title="时间范围与维度"
        description="🔴 **窗口外的事实不参与计算**：销量 / 销售额 / 毛利 / 销量份额全部只取这个窗口。新品判定要回看「首次销售日」、衰退判定要比「对比窗口」—— 那两段是规则里显式配置的独立窗口，规则卡片上写得很清楚。金额按币种分别列出，**绝不跨币种相加**。"
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
          <div className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            维度层
            <Segmented
              options={dimensionOptions()}
              value={dimensionType}
              onChange={(value) => {
                setDimensionType(value);
                setPage(0);
              }}
            />
          </div>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            档位
            <select
              value={segment}
              onChange={(e) => {
                setSegment(e.target.value);
                setPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {segmentOptions().map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            ABC（按销量）
            <select
              value={abc}
              onChange={(e) => {
                setAbc(e.target.value);
                setPage(0);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              <option value="A">A 档</option>
              <option value="B">B 档</option>
              <option value="C">C 档</option>
            </select>
          </label>
        </div>
        {refreshNote && (
          <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground mt-3 rounded-lg border px-4 py-3">
            {refreshNote}
          </div>
        )}
      </SectionCard>

      {/* ── 错误态 ─────────────────────────────────────────────────────── */}
      {state === 'error' && (
        <SectionCard>
          <EmptyState
            title="分层结果读不到"
            description={`${error}（这是「读不到」，不是「没有数据」）`}
            action={
              <Button variant="outline" size="sm" onClick={() => void load()}>
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
            title="正在读取分层结果…"
            description="读取期间页面上的「数据截至」仍然有效。"
          />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState
            title={emptyReasonLabel(data?.emptyReason ?? null) || '没有分层结果'}
            description={emptyText(data)}
            action={
              <Button
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={() => void refreshProjection()}
              >
                重新分层
              </Button>
            }
          />
        </SectionCard>
      )}

      {/* ── 就绪态 ─────────────────────────────────────────────────────── */}
      {state === 'ready' && data && (
        <>
          {/* ── 窗口说明（请求窗口 ≠ 实际窗口时点名）────────────────────── */}
          {!data.windowMatched && (
            <div className="border-border bg-muted/40 text-copy-13 text-foreground flex items-start gap-2 rounded-lg border px-4 py-3">
              <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              {data.windowNote}
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

          {/* ── 分档汇总 + 各层建议 ─────────────────────────────────────── */}
          <SectionCard
            title="分层结果与各层建议"
            description="🔴 **分层是资源分配的依据**：爆款备货、滞销清仓，没有分层就只能平均用力。金额按币种分别列出；某一档的毛利若有商品算不出来，合计会标成「不可比」并写明缺几个 —— **不会拿一个残缺的合计冒充完整的**。"
          >
            {populated.length === 0 ? (
              <EmptyState
                title="这个窗口内没有分层结果"
                description="没有分层结果不等于「所有商品都是平款」—— 真相可能是「这个窗口还没跑过分层」。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">档位</th>
                      <th className="py-2 pr-3 text-right font-medium">商品数</th>
                      <th className="py-2 pr-3 text-right font-medium">销量（件）</th>
                      <th className="py-2 pr-3 font-medium">金额（按币种）</th>
                      <th className="py-2 pr-3 font-medium">建议动作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {populated.map((summary) => (
                      <SummaryRow key={summary.segment} summary={summary} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ── 商品明细 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="商品分层明细"
            description="🔴 **档位算不出来时是「不可比 / 没有数据」+ 原因**，不是某个默认档位；**毛利算不出来时是「—」+ 原因**，不是 0 元。ABC 按销量与按金额是两套口径，按金额**在同一个币种内部**排名。"
          >
            {data.rows.length === 0 ? (
              <EmptyState
                title="没有符合筛选条件的商品"
                description="换个档位或 ABC 条件再看看 —— 或者先「重新分层」把这一档算出来。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-copy-13 text-muted-foreground">
                    <tr className="border-border border-b">
                      <th className="py-2 pr-3 font-medium">商品</th>
                      <th className="py-2 pr-3 font-medium">档位</th>
                      <th className="py-2 pr-3 font-medium">ABC（销量）</th>
                      <th className="py-2 pr-3 text-right font-medium">销量（件）</th>
                      <th className="py-2 pr-3 text-right font-medium">销量份额</th>
                      <th className="py-2 pr-3 text-right font-medium">下滑比例</th>
                      <th className="py-2 pr-3 font-medium">金额与毛利（按币种）</th>
                      <th className="py-2 pr-3 font-medium">建议动作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((row) => (
                      <RowView key={`${row.dimensionType}-${row.dimensionId}`} row={row} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className="text-copy-13 text-muted-foreground mt-3 flex items-center gap-2">
              <span>
                第 {data.page + 1} / {Math.max(Math.ceil(data.total / data.size), 1)} 页（{data.total} 条）
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
            {data.coverageNote && (
              <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground mt-3 rounded-lg border px-4 py-3">
                {data.coverageNote}
              </div>
            )}
          </SectionCard>

          {/* ── 分层规则（可配置、可审计）──────────────────────────────── */}
          <SectionCard
            title="分层规则（可配置、可审计）"
            description="🔴 阈值与分类口径**在库里，不在代码里**：改规则 = 加一个版本（旧版本停用），**历史分层结果仍指向旧版本**。规则改完要「重新分层」才生效。"
          >
            {rules.length === 0 ? (
              <EmptyState
                title="还没有生效的分层规则"
                description="规则由服务端在第一次分层时按标准规则播种 —— 先点一次「重新分层」。"
              />
            ) : (
              <div className="space-y-3">
                {rules.map((rule) => (
                  <div key={`${rule.ruleCode}-${rule.version}`} className="border-border rounded-lg border px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">{rule.ruleName}</span>
                      <Badge tone={rule.enabled ? 'success' : 'neutral'}>
                        {rule.enabled ? '生效中' : '已停用'}
                      </Badge>
                      <Badge tone="info">v{rule.version}</Badge>
                      <Badge tone="neutral">{rule.dimensionType}</Badge>
                    </div>
                    <p className="text-copy-13 text-muted-foreground mt-1">{ruleThresholdText(rule)}</p>
                    <p className="text-copy-13 text-muted-foreground mt-1">
                      口径引用：{rule.quantityMetricCode} / {rule.amountMetricCode} /{' '}
                      {rule.grossProfitMetricCode}（**引用 13.2.2.2 与 13.3.1.1 的口径表，本模块不重复登记**）
                    </p>
                    <p className="text-copy-13 text-muted-foreground mt-1">
                      生效区间：{rule.effectiveFrom} ~ {rule.effectiveTo ?? '（一直生效）'} · 变更人：
                      {rule.createdBy || '—'}
                    </p>
                    {rule.note && (
                      <p className="text-copy-13 text-muted-foreground mt-1">{rule.note}</p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {/* ── 被引用的口径 ─────────────────────────────────────────────── */}
          {data.metricReferences.length > 0 && (
            <SectionCard
              title="被引用的口径（口径唯一，本模块不重复登记）"
              description="🔴 销量 / 销售额的口径在 **13.2.2.2**，毛利额的口径在 **13.3.1.1**（派生：销售额 − 出库成本）—— 本模块只**引用**，不另起一张口径表登记同一批指标。"
            >
              <ul className="text-copy-13 space-y-2">
                {data.metricReferences.map((reference) => (
                  <ReferenceView key={reference.metricCode} reference={reference} />
                ))}
              </ul>
            </SectionCard>
          )}

          {/* ── 能力声明 ─────────────────────────────────────────────────── */}
          <SectionCard
            title="判定规则与能力边界"
            description="口径唯一：一个指标只允许一处定义。下面的说明是页面上原样显示的口径来源与统计范围。"
          >
            <div className="text-copy-13 space-y-3">
              {upstreamModules(capabilities).length > 0 && (
                <div>
                  <h4 className="text-muted-foreground mb-1">上游（真的读过的）</h4>
                  <ul className="list-disc space-y-1 pl-5">
                    {upstreamModules(capabilities).map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </div>
              )}
              {notDeliveredLines(capabilities).length > 0 && (
                <div>
                  <h4 className="text-muted-foreground mb-1">确实拿不到的部分</h4>
                  <ul className="list-disc space-y-1 pl-5">
                    {notDeliveredLines(capabilities).map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </div>
              )}
              {ruleNotes(capabilities).length > 0 && (
                <div>
                  <h4 className="text-muted-foreground mb-1">判定规则</h4>
                  <ul className="list-disc space-y-1 pl-5">
                    {ruleNotes(capabilities).map((rule) => (
                      <li key={rule.ruleCode}>
                        <span className="text-foreground">{rule.ruleCode}</span>
                        <span className="text-muted-foreground">：{rule.description}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="text-muted-foreground">
                状态：{statusLabel(data.status)} · {data.statusNote}
              </div>
            </div>
          </SectionCard>
        </>
      )}
    </div>
  );
}

/**
 * 分档汇总的一行。
 *
 * <p>🔴 金额**按币种分行**：一个档位可能有多个币种，各算各的，绝不相加。
 */
function SummaryRow({ summary }: { summary: LifecycleSegmentSummary }) {
  return (
    <tr className="border-border border-b">
      <td className="py-2 pr-3">
        <Badge tone={segmentTone(summary.segment)}>{segmentLabel(summary.segment)}</Badge>
      </td>
      <td className="py-2 pr-3 text-right">{summary.productCount}</td>
      <td className="py-2 pr-3 text-right">{quantityText(summary.salesQuantity)}</td>
      <td className="py-2 pr-3">
        {summary.amounts.length === 0 ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <div className="space-y-1">
            {summary.amounts.map((amount) => (
              <div key={amount.currency}>
                <span className="text-foreground">{amountText(amount.salesAmount, amount.currency)}</span>
                <span className="text-muted-foreground">
                  {' '}
                  · 毛利 {amountText(amount.grossProfitAmount, amount.currency)}
                </span>
                <Badge tone={availabilityTone(amount.grossProfitAvailability)}>
                  {availabilityLabel(amount.grossProfitAvailability)}
                </Badge>
                {amount.grossProfitNote && (
                  <div className="text-muted-foreground">{amount.grossProfitNote}</div>
                )}
              </div>
            ))}
          </div>
        )}
      </td>
      <td className="py-2 pr-3">
        {summary.recommendedAction ? (
          <>
            <Badge tone={actionTone(summary.recommendedAction)}>
              {actionLabel(summary.recommendedAction)}
            </Badge>
            <div className="text-copy-13 text-muted-foreground">{summary.actionNote}</div>
          </>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </td>
    </tr>
  );
}

/** 商品明细的一行。 */
function RowView({ row }: { row: LifecycleSegmentationRow }) {
  return (
    <tr className="border-border border-b">
      <td className="py-2 pr-3">
        <div className="text-foreground">{row.dimensionKey}</div>
        {row.dimensionName && (
          <div className="text-copy-13 text-muted-foreground">{row.dimensionName}</div>
        )}
      </td>
      <td className="py-2 pr-3">
        <Badge tone={segmentTone(row.lifecycleSegment)}>
          {segmentLabel(row.lifecycleSegment)}
        </Badge>
        {row.segmentAvailability !== 'AVAILABLE' && (
          <div className="mt-1">
            <Badge tone={availabilityTone(row.segmentAvailability)}>
              {availabilityLabel(row.segmentAvailability)}
            </Badge>
          </div>
        )}
        {row.segmentNote && (
          <div className="text-copy-13 text-muted-foreground mt-1">{row.segmentNote}</div>
        )}
      </td>
      <td className="py-2 pr-3">
        <div>{abcRankText(row.abcByQuantity, row.abcRankByQuantity, row.abcTotalByQuantity)}</div>
        {row.amounts.length > 0 && (
          <div className="text-copy-13 text-muted-foreground">
            {row.amounts
              .map(
                (amount) =>
                  `${currencyLabel(amount.currency)} ${abcLabel(amount.abcByAmount)}`,
              )
              .join(' · ')}
          </div>
        )}
      </td>
      <td className="py-2 pr-3 text-right">{quantityText(row.salesQuantity)}</td>
      <td className="py-2 pr-3 text-right">{percentText(row.salesShare)}</td>
      <td className="py-2 pr-3 text-right">{percentText(row.declineRatio)}</td>
      <td className="py-2 pr-3">
        {row.amounts.length === 0 ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <div className="space-y-1">
            {row.amounts.map((amount) => (
              <AmountView key={amount.currency} amount={amount} />
            ))}
          </div>
        )}
        <div className="text-copy-13 text-muted-foreground mt-1">
          在手 {quantityText(row.onHandQuantity)} 件
          {row.agingDays !== null && ` · 库龄 ${quantityText(row.agingDays)} 天`}
        </div>
      </td>
      <td className="py-2 pr-3">
        {row.recommendedAction ? (
          <>
            <Badge tone={actionTone(row.recommendedAction)}>
              {actionLabel(row.recommendedAction)}
            </Badge>
            <div className="text-copy-13 text-muted-foreground">{row.actionNote}</div>
          </>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </td>
    </tr>
  );
}

/** 一个币种的金额行（**币种贴在旁边**）。 */
function AmountView({ amount }: { amount: LifecycleSegmentationAmountRow }) {
  return (
    <div>
      <span className="text-foreground">{amountRowText(amount)}</span>
      <span className="text-muted-foreground"> · {currencyLabel(amount.currency)}</span>
      {amount.grossProfitAvailability !== 'AVAILABLE' && (
        <Badge tone={availabilityTone(amount.grossProfitAvailability)}>
          {availabilityLabel(amount.grossProfitAvailability)}
        </Badge>
      )}
      {grossProfitNote(amount) && (
        <div className="text-copy-13 text-muted-foreground">{grossProfitNote(amount)}</div>
      )}
    </div>
  );
}

/** 被引用的口径的一行。 */
function ReferenceView({ reference }: { reference: LifecycleMetricReference }) {
  return (
    <li>
      <span className="text-foreground">{reference.displayName}</span>
      <span className="text-muted-foreground">
        （{reference.metricCode}）· 口径归 {reference.caliberOwner} · {metricFormulaText(reference)}
      </span>
      {reference.description && (
        <div className="text-muted-foreground">{reference.description}</div>
      )}
      {!reference.available && (
        <div className="text-muted-foreground">
          本轮不可用（归 {reference.ownerModule || '—'} 管）：{reference.availabilityNote}
        </div>
      )}
    </li>
  );
}
