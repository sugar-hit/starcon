'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, PageHeader, SectionCard } from '@starcon/ui';
import { Activity, AlertTriangle, Loader2, RefreshCw } from 'lucide-react';
import {
  BASE,
  coverageHints,
  emptyState,
  fetchCapabilities,
  fetchOutboundTimeliness,
  fetchRedFlags,
  fetchSummary,
  groupRedFlags,
  levelLabel,
  levelTone,
  mergeCards,
  severityTone,
  unavailableDimensions,
  type FulfillmentHealthCapabilities,
  type FulfillmentHealthCard,
  type FulfillmentHealthRedFlagPage,
  type FulfillmentHealthSummary,
  type OutboundTimelinessReport,
} from './fulfillment-health-model';

/**
 * 1.1.2.3 履约健康 工作区。
 *
 * 蓝图：「展示履约链路的健康水位：待发货积压、平均出库时长、超时发货率、物流异常件占比、
 * 平台绩效指标（ODR、迟发率、有效追踪率）。……输出：健康度卡片 + 红灯项明细跳转。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「未接通」显示成灰格 + 「未接通」，不是 0%**。平台 ODR 在仓内没有上游
 *    （没有 `platform_performance_metric` 表），所以它永远是一格灰的，
 *    并且把「归谁」与「少了什么」写在卡片下面。
 * 2. **每一张卡都能点进去看明细**，而且明细的条数与卡片上的数字一致
 *    （服务端把 `redFlagKind` 与 `alertCount` 一起给了）。
 * 3. **前端不重算任何比率**。卡片上显示的分子分母都是服务端算好的。
 * 4. **页面只读**。这里没有「登记时限 / 改轨迹 / 处理异常」按钮 ——
 *    那些动作回 9.4.1.1 / 9.4.1.2 / 9.6.1.3 完成（卷 01 的红线）。
 */
export function FulfillmentHealthWorkspace() {
  const [caps, setCaps] = useState<FulfillmentHealthCapabilities | null>(null);
  const [summary, setSummary] = useState<FulfillmentHealthSummary | null>(null);
  const [outbound, setOutbound] = useState<OutboundTimelinessReport | null>(null);
  const [flags, setFlags] = useState<FulfillmentHealthRedFlagPage | null>(null);
  const [flagKind, setFlagKind] = useState<string>('');
  const [flagPage, setFlagPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const FLAG_PAGE_SIZE = 20;

  const load = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      // 三个 trade 端点与 warehouse 的出库时效报表**并行**取：
      // 出库时效报表取不到时其余四张卡仍然要显示（它只是一个降级，不是整页失败）。
      const [capabilities, healthSummary, outboundReport] = await Promise.all([
        fetchCapabilities(),
        fetchSummary(),
        fetchOutboundTimeliness(),
      ]);
      setCaps(capabilities);
      setSummary(healthSummary);
      setOutbound(outboundReport);
      const page = await fetchRedFlags(flagPage, FLAG_PAGE_SIZE, flagKind || undefined);
      setFlags(page);
      setLoading(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : '健康度数据加载失败');
      setLoading(false);
    } finally {
      setBusy(false);
    }
  }, [flagKind, flagPage]);

  useEffect(() => {
    void load();
  }, [load]);

  const cards = useMemo(() => (summary ? mergeCards(summary, outbound) : []), [summary, outbound]);
  const hints = useMemo(() => (summary ? coverageHints(summary.coverage) : []), [summary]);
  const missing = useMemo(() => unavailableDimensions(caps), [caps]);
  const groups = useMemo(() => (flags ? groupRedFlags(flags.items) : []), [flags]);
  const empty = emptyState(flags?.total ?? 0, !loading);

  return (
    <div className="space-y-6">
      <PageHeader
        title="履约健康"
        description="履约链路的健康水位：待发货积压、平均出库时长、超时发货率、物流异常件占比、平台绩效指标。"
        actions={
          <Button variant="outline" onClick={() => void load()} disabled={busy}>
            {busy ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
            <span className="ml-2">刷新</span>
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/30 bg-destructive-soft text-destructive rounded-lg border p-4 text-sm">
          {error}
        </div>
      )}

      {loading ? (
        <SectionCard title="健康度卡片">
          <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在读取履约健康数据…
          </div>
        </SectionCard>
      ) : (
        <>
          {/* ---------------- 健康度卡片 ---------------- */}
          <SectionCard
            title="健康度卡片"
            description="六格水位：每一格都带分子分母（比率型）与灯色判定依据，点「查看明细」跳到归属系统的清单。"
          >
            {cards.length === 0 ? (
              <EmptyState
                icon={<Activity className="h-5 w-5" />}
                title="还没有健康度数据"
                description="后端返回了空的卡片集。请确认履约投影已同步（9.4.1.1 / 9.4.1.2）。"
              />
            ) : (
              <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                {cards.map((card) => (
                  <HealthCard key={card.key} card={card} />
                ))}
              </div>
            )}
          </SectionCard>

          {/* ---------------- 覆盖度提示 ---------------- */}
          {hints.length > 0 && (
            <SectionCard
              title="口径提示"
              description="这些量**没有**进比率的分母 —— 不说出来的话，运营会把「迟发率 0%」读成「一件都没迟发」。"
            >
              <ul className="text-muted-foreground space-y-2 text-sm">
                {hints.map((hint) => (
                  <li key={hint} className="flex gap-2">
                    <AlertTriangle className="text-warning mt-0.5 h-4 w-4 shrink-0" />
                    <span>{hint}</span>
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}

          {/* ---------------- 拆分 ---------------- */}
          {summary && summary.breakdowns.length > 0 && (
            <SectionCard
              title="拆分"
              description="卡片背后的构成：按状态、按时限、按轨迹状态、按异常类型。"
            >
              <div className="grid gap-4 md:grid-cols-2">
                {summary.breakdowns.map((b) => (
                  <div key={b.key} className="border-control-border rounded-lg border p-4">
                    <div className="text-foreground mb-3 text-sm font-medium">{b.label}</div>
                    <div className="space-y-2">
                      {b.buckets.map((bucket) => (
                        <div key={bucket.key} className="flex items-center justify-between text-sm">
                          <span className="text-muted-foreground">{bucket.label}</span>
                          <span className="text-foreground font-medium">{bucket.count}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}

          {/* ---------------- 红灯项明细 ---------------- */}
          <SectionCard
            title="红灯项明细"
            description="点卡片上的「查看明细」筛到对应类型。这里的条数与卡片上的告警数一致 —— 两边对不上就是口径漂了。"
            extra={
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  variant={flagKind === '' ? 'default' : 'outline'}
                  size="sm"
                  onClick={() => {
                    setFlagPage(0);
                    setFlagKind('');
                  }}
                >
                  全部
                </Button>
                {(caps?.redFlagKinds ?? []).map((kind) => (
                  <Button
                    key={kind}
                    variant={flagKind === kind ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => {
                      setFlagPage(0);
                      setFlagKind(kind);
                    }}
                  >
                    {redFlagKindLabel(kind)}
                  </Button>
                ))}
              </div>
            }
          >
            {flags && flags.items.length === 0 ? (
              <EmptyState
                icon={<Activity className="h-5 w-5" />}
                title={empty.title}
                description={empty.description}
              />
            ) : (
              <div className="space-y-4">
                {groups.map((group) => (
                  <div key={group.kind}>
                    <div className="text-foreground mb-2 text-sm font-medium">
                      {group.kindLabel}
                      <span className="text-muted-foreground ml-2">{group.items.length} 条</span>
                    </div>
                    <div className="space-y-2">
                      {group.items.map((item) => (
                        <div
                          key={`${item.kind}-${item.refId}`}
                          className="border-control-border flex flex-col gap-1 rounded-lg border p-3 sm:flex-row sm:items-start sm:justify-between"
                        >
                          <div className="min-w-0">
                            <div className="flex items-center gap-2">
                              <Badge tone={severityTone(item.severity)}>{item.severity}</Badge>
                              <span className="text-foreground truncate text-sm font-medium">
                                {item.title}
                              </span>
                              <span className="text-muted-foreground truncate text-sm">
                                {item.refNo}
                              </span>
                            </div>
                            <div className="text-muted-foreground mt-1 text-xs">{item.detail}</div>
                          </div>
                          {item.linkPath && (
                            <Link
                              href={item.linkPath}
                              className="text-brand shrink-0 text-sm underline-offset-4 hover:underline"
                            >
                              去处理
                            </Link>
                          )}
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
                {flags && flags.total > FLAG_PAGE_SIZE && (
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">
                      共 {flags.total} 条，第 {flags.page + 1} /{' '}
                      {Math.ceil(flags.total / flags.size)} 页
                    </span>
                    <div className="flex gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={flags.page <= 0}
                        onClick={() => setFlagPage((p) => Math.max(0, p - 1))}
                      >
                        上一页
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={flags.page + 1 >= Math.ceil(flags.total / flags.size)}
                        onClick={() => setFlagPage((p) => p + 1)}
                      >
                        下一页
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </SectionCard>

          {/* ---------------- 未接通的维度 ---------------- */}
          {missing.length > 0 && (
            <SectionCard
              title="未接通的维度"
              description="这些格子在本页是灰的 —— **不是 0**。没有来源的指标填成 0，会让人以为「一切正常」，而它其实是「我们根本没采」。"
            >
              <div className="space-y-3">
                {missing.map((dim) => (
                  <div key={dim.key} className="border-control-border rounded-lg border p-4">
                    <div className="flex items-center gap-2">
                      <Badge tone="neutral">未接通</Badge>
                      <span className="text-foreground text-sm font-medium">{dim.label}</span>
                    </div>
                    <p className="text-muted-foreground mt-2 text-sm">{dim.reason}</p>
                    <p className="text-muted-foreground mt-1 text-sm">
                      归属模块：{dim.ownerModule}
                    </p>
                    <p className="text-muted-foreground mt-1 text-sm">影响：{dim.impact}</p>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}

          {/* ---------------- 口径与边界 ---------------- */}
          {caps && (
            <SectionCard
              title="口径与边界"
              description="每一维的算法、分母与来源表都在服务端一处定义；页面只引用不重算。"
            >
              <div className="space-y-4 text-sm">
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[720px] text-left">
                    <thead className="text-muted-foreground text-xs uppercase tracking-wide">
                      <tr>
                        <th className="py-2 pr-3 font-medium">维度</th>
                        <th className="py-2 pr-3 font-medium">状态</th>
                        <th className="py-2 pr-3 font-medium">算法</th>
                        <th className="py-2 pr-3 font-medium">分母</th>
                        <th className="py-2 font-medium">来源</th>
                      </tr>
                    </thead>
                    <tbody className="divide-control-border divide-y">
                      {caps.dimensions.map((dim) => (
                        <tr key={dim.key}>
                          <td className="text-foreground py-2 pr-3">{dim.label}</td>
                          <td className="py-2 pr-3">
                            <Badge tone={dim.available ? 'success' : 'neutral'}>
                              {dim.available ? '已接通' : '未接通'}
                            </Badge>
                          </td>
                          <td className="text-muted-foreground py-2 pr-3">{dim.formula || '—'}</td>
                          <td className="text-muted-foreground py-2 pr-3">
                            {dim.denominator || '—'}
                          </td>
                          <td className="text-muted-foreground py-2">{dim.sourceTables || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="text-muted-foreground space-y-1">
                  <p>灯色判定：{caps.levelRule}</p>
                  <p>时间口径：{caps.windowBasis}</p>
                  <p>上游：{caps.upstreamNote}</p>
                  <p>边界：{caps.boundaryNote}</p>
                </div>
                {summary && summary.boundaryNotes.length > 0 && (
                  <ul className="text-muted-foreground space-y-1">
                    {summary.boundaryNotes.map((note) => (
                      <li key={note}>{note}</li>
                    ))}
                  </ul>
                )}
              </div>
            </SectionCard>
          )}

          <p className="text-muted-foreground text-xs">
            本页是只读聚合层（前端聚合 + 各服务只读投影）：所有状态变更回归属系统完成。 后端路径：
            <code className="font-mono">{BASE}</code>
          </p>
        </>
      )}
    </div>
  );
}

/** 一张健康度卡片。 */
function HealthCard({ card }: { card: FulfillmentHealthCard }) {
  return (
    <div className="border-control-border flex flex-col rounded-lg border p-4">
      <div className="flex items-start justify-between gap-2">
        <span className="text-foreground text-sm font-medium">{card.label}</span>
        <Badge tone={levelTone(card.level)}>{levelLabel(card.level)}</Badge>
      </div>

      <div className="mt-3 flex items-baseline gap-1">
        <span className="text-foreground text-2xl font-semibold">
          {card.available ? card.valueText : '未接通'}
        </span>
      </div>

      {card.metricKind === 'RATE' && card.numerator !== null && card.denominator !== null && (
        <div className="text-muted-foreground mt-1 text-xs">
          分子 {card.numerator} ÷ 分母 {card.denominator}
        </div>
      )}

      {card.alertCount !== null && card.alertCount > 0 && (
        <div className="text-destructive mt-1 text-xs">{card.alertCount} 条红灯项</div>
      )}

      <p className="text-muted-foreground mt-2 flex-1 text-xs leading-relaxed">{card.basis}</p>

      {card.detailPath && (
        <Link
          href={card.detailPath}
          className="text-brand mt-3 text-sm underline-offset-4 hover:underline"
        >
          查看明细
        </Link>
      )}
    </div>
  );
}

/** 红灯类型的人话名。 */
function redFlagKindLabel(kind: string): string {
  switch (kind) {
    case 'PENDING_SHIPMENT_OVERDUE':
      return '待发货超时';
    case 'SHIP_OVERDUE':
      return '迟发';
    case 'LOGISTICS_EXCEPTION':
      return '轨迹异常';
    case 'UNTRACKABLE_SHIPMENT':
      return '无轨迹观测';
    case 'DROPSHIP_TRACKING_EXCEPTION':
      return '代发异常';
    default:
      return kind;
  }
}
