'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, Segmented } from '@starcon/ui';
import { ArrowDown, ArrowUp, Minus, RefreshCw, TriangleAlert } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import type { Access } from '../../../../../packages/api-contracts/src/index';
import {
  SCOPE_MODE_LABELS,
  WINDOW_OPTIONS,
  caliberTooltip,
  comparisonLine,
  currencyRows,
  emptyState,
  fetchCapabilities,
  fetchCards,
  fetchDrilldown,
  formatValue,
  headlineText,
  isUnavailable,
  scopeModeOf,
  statusLabel,
  statusTone,
  trendLabel,
  trendTone,
  unavailableCapabilities,
  unitLabel,
  windowLabel,
  type DrilldownPage,
  type MetricCardEntry,
  type MetricCardsCapabilities,
  type MetricCardsResponse,
  type WindowOption,
} from './metric-card-model';

/**
 * 1.1.2.1 核心指标卡工作区（卷 01 工作台与门户 L3 1.1.2 关键指标下的第一格）。
 *
 * <p>蓝图原文：「一屏展示今日/昨日/本月的销售额、订单量、销量、库存总额、毛利、回款、
 * 在途金额，每张卡带环比与同比，点击下钻到对应报表。」
 *
 * <p>🔴 **这一页的四条纪律**（每一条都写在页面上，不只写在注释里）：
 *
 * <ol>
 *   <li><b>口径单点定义</b>：卡片上的数值、公式、口径说明**全部来自后端**
 *       （13 的 {@code reporting_metric_definition}）。前端一个减法都不做 ——
 *       趋势箭头也是后端按变化率给的。</li>
 *   <li><b>「算不出来」与「等于 0」分开</b>：基期没数据 ⇒ 环比 / 同比显示「—」并写明原因，
 *       **不是 0%、不是「持平」、更不是「-100%」**；未接通的卡片显示灰格「未接通」，
 *       **不是 0**。</li>
 *   <li><b>多币种绝不相加</b>：没有本位币合计时卡片显示「按币种分列」，
 *       每个币种单独一行 —— 界面上**永远没有跨币种的「总计」**。</li>
 *   <li><b>只读 + 跳转</b>：本页没有任何写动作；每个数字的出口是
 *       「展开构成明细」或「跳到口径归属系统的报表页」。</li>
 * </ol>
 *
 * <p>⚠️ **数据范围只用于展示措辞**：租户来自已认证的成员关系，范围由服务端判定；
 * 前端不把租户号与范围参数当成可信输入往下传。
 */
export function MetricCardWorkspace() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);

  const [cards, setCards] = useState<MetricCardsResponse | null>(null);
  const [capabilities, setCapabilities] = useState<MetricCardsCapabilities | null>(null);
  const [scopeMode, setScopeMode] = useState<string>('SELF');

  const [window, setWindow] = useState<WindowOption>('TODAY');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const [drillMetric, setDrillMetric] = useState('');
  const [drilldown, setDrilldown] = useState<DrilldownPage | null>(null);
  const [drillError, setDrillError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      // 1. 先向身份服务确认身份与权限（租户**不**来自客户端头）。
      const access = await api<Access>('auth/session');
      setScopeMode(
        SCOPE_MODE_LABELS[scopeModeOf(access?.grants ?? null, 'reporting.overview.read', Boolean(access?.departmentId))],
      );

      // 2. 能力声明与卡片数据（两个接口并行读；能力声明读不到不该让卡片也读不到）。
      const [caps, data] = await Promise.all([
        fetchCapabilities().catch(() => null),
        fetchCards(window, from || undefined, to || undefined),
      ]);
      setCapabilities(caps);
      setCards(data);
    } catch (failure) {
      setError(
        failure instanceof ApiError
          ? failure.message
          : '指标卡暂时读不到，请稍后重试（后端不可达或身份校验未通过）。',
      );
    } finally {
      setLoading(false);
      setReady(true);
    }
  }, [window, from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDrilldown = useCallback(
    async (card: MetricCardEntry) => {
      setDrillMetric(card.metricCode);
      setDrilldown(null);
      setDrillError('');
      try {
        setDrilldown(await fetchDrilldown(card.metricCode, cards?.from ?? '', cards?.to ?? ''));
      } catch (failure) {
        setDrillError(
          failure instanceof ApiError ? failure.message : '构成明细暂时读不到，请稍后重试。',
        );
      }
    },
    [cards],
  );

  const hasFacts = useMemo(
    () => (cards?.cards ?? []).some((card) => card.status === 'AVAILABLE'),
    [cards],
  );
  const missing = useMemo(() => unavailableCapabilities(capabilities), [capabilities]);

  if (!ready) {
    return (
      <SectionCard>
        <EmptyState title="正在读取核心指标卡…" description="指标来自 reporting 服务的投影读模型。" />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-copy-16 font-medium">核心指标卡</h1>
          <p className="text-copy-12 text-muted-foreground">
            销售额、订单量、销量、库存总额、毛利、回款、在途金额 —— 一屏看完，每张卡带环比与同比。
            <b>口径由 13 单点定义</b>，本页只引用不重算；本页只读，不写任何业务数据。
          </p>
          {cards && (
            <p className="text-copy-12 text-muted-foreground">
              窗口 {cards.from} ~ {cards.to}（{windowLabel(cards.windowPreset)}） · 时区{' '}
              {cards.timezone}
              {cards.timezoneSource === 'FALLBACK_UTC' ? '（本租户还没有投影数据，暂按 UTC）' : ''} ·
              数据截至 {cards.dataUpdatedAt ? cards.dataUpdatedAt.replace('T', ' ').slice(0, 19) : '尚未投影任何数据'}{' '}
              · 数据范围：{scopeMode}
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Segmented
            value={window}
            onChange={(value) => setWindow(value as WindowOption)}
            options={WINDOW_OPTIONS.map((option) => ({ label: option.label, value: option.value }))}
            aria-label="时间口径"
          />
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        </div>
      </header>

      {window === 'CUSTOM' && (
        <SectionCard title="自定义区间" description="起止日期按租户时区算（含首尾）。">
          <div className="flex flex-wrap items-end gap-3">
            <label className="text-copy-13 flex flex-col gap-1">
              起
              <input
                type="date"
                value={from}
                onChange={(event) => setFrom(event.target.value)}
                className="border-border rounded-md border px-2 py-1"
              />
            </label>
            <label className="text-copy-13 flex flex-col gap-1">
              止
              <input
                type="date"
                value={to}
                onChange={(event) => setTo(event.target.value)}
                className="border-border rounded-md border px-2 py-1"
              />
            </label>
            <Button variant="outline" onClick={() => void load()} disabled={loading || !from || !to}>
              应用区间
            </Button>
          </div>
        </SectionCard>
      )}

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}

      {/* ── 指标卡 ─────────────────────────────────────────────────────── */}
      <SectionCard
        title="核心指标"
        description="每个数字都能展开构成明细或跳到归属系统的报表页；口径写在卡片上，不在代码注释里。"
      >
        {loading ? (
          <EmptyState title="正在读取指标…" description="投影读模型可能要跑一次聚合。" />
        ) : error && !cards ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title={emptyState(false, [], false).title}
            description={emptyState(false, [], false).description}
          />
        ) : !cards || cards.cards.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title={emptyState(true, [], hasFacts).title}
            description={emptyState(true, [], hasFacts).description}
          />
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {cards.cards.map((card) => (
              <MetricCardView
                key={card.key}
                card={card}
                onDrilldown={() => void openDrilldown(card)}
              />
            ))}
          </div>
        )}

        {cards?.currencyNote && (
          <p className="text-copy-13 text-muted-foreground mt-4">{cards.currencyNote}</p>
        )}
        {cards && cards.warnings.length > 0 && (
          <ul className="text-copy-13 text-muted-foreground mt-3 space-y-1">
            {cards.warnings.map((warning) => (
              <li key={warning}>· {warning}</li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ── 能力声明 ───────────────────────────────────────────────────── */}
      {capabilities && (
        <SectionCard
          title="能力声明"
          description="逐个指标报：上游接通了没有、口径归谁、下钻目标在不在。接不通的**不给公式也不给分母**。"
        >
          <ul className="space-y-2">
            {capabilities.metrics.map((metric) => (
              <li
                key={metric.key}
                className="text-copy-12 border-border rounded-md border p-2"
              >
                <Badge tone={metric.available ? 'success' : 'warning'}>
                  {metric.available ? '已接通' : '未接通'}
                </Badge>
                <span className="ml-2">
                  {metric.displayName} · 口径归属：{metric.ownerModule || '—'}
                  {metric.aggregation ? ` · 聚合方式：${metric.aggregation}` : ''}
                  {metric.numeratorMetricCode
                    ? ` · 分子 ${metric.numeratorMetricCode} / 分母 ${metric.denominatorMetricCode}`
                    : ''}
                  {metric.sourceTables ? ` · 来源：${metric.sourceTables}` : ''}
                  {metric.drilldownTargetName ? ` · 下钻：${metric.drilldownTargetName}` : ''}
                </span>
                {!metric.available && metric.unavailableReason && (
                  <span className="mt-1 block">—— {metric.unavailableReason}</span>
                )}
                {metric.available && metric.note && (
                  <span className="text-muted-foreground mt-1 block">{metric.note}</span>
                )}
              </li>
            ))}
          </ul>

          {missing.length > 0 && (
            <div className="mt-4">
              <h3 className="text-copy-14 font-medium">未接通清单（逐条）</h3>
              <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
                {capabilities.notDelivered.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          )}

          <div className="mt-4">
            <h3 className="text-copy-14 font-medium">上游（真的读过的）</h3>
            <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
              {capabilities.upstreamModules.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>

          <div className="mt-4">
            <h3 className="text-copy-14 font-medium">口径纪律</h3>
            <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
              {capabilities.notes.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
        </SectionCard>
      )}

      {/* ── 下钻（复用 13.1.1.1 的构成明细端点） ────────────────────────── */}
      {drillMetric && (
        <SectionCard
          title={`下钻 · ${drillMetric}`}
          description="这个汇总数字是由哪些订单 / SKU / 库存行构成的。"
          extra={
            <Button variant="outline" size="sm" onClick={() => setDrillMetric('')}>
              收起
            </Button>
          }
        >
          {drillError ? (
            <EmptyState title="构成明细读不到" description={drillError} />
          ) : !drilldown ? (
            <EmptyState title="正在读取构成明细…" />
          ) : drilldown.items.length === 0 ? (
            <EmptyState
              title="这个指标没有构成明细"
              description="该指标在这个时间窗口下没有可下钻的明细行（不是 0）。"
            />
          ) : (
            <table className="w-full text-left text-sm">
              <thead className="text-copy-13 text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3 font-medium">构成</th>
                  <th className="py-2 pr-3 font-medium">类型</th>
                  <th className="py-2 pr-3 font-medium">归属日</th>
                  <th className="py-2 font-medium">贡献</th>
                </tr>
              </thead>
              <tbody>
                {drilldown.items.map((row) => (
                  <tr
                    key={`${row.sourceKind}|${row.referenceId}|${row.currency}`}
                    className="border-border border-t"
                  >
                    <td className="py-2 pr-3">
                      <div className="text-foreground">{row.referenceLabel || row.referenceId}</div>
                      <div className="text-copy-13 text-muted-foreground">{row.referenceId}</div>
                    </td>
                    <td className="text-copy-13 text-muted-foreground py-2 pr-3">
                      {sourceKindLabel(row.sourceKind)}
                    </td>
                    <td className="text-copy-13 text-muted-foreground py-2 pr-3">{row.bucketDate}</td>
                    <td className="py-2 tabular-nums">
                      {row.value === null ? '—' : formatValue(row.value, 'CURRENCY')}{' '}
                      {row.currency}
                      {row.quantity !== null && row.quantity !== 0
                        ? ` · ${formatValue(row.quantity, 'QUANTITY')}`
                        : ''}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </SectionCard>
      )}
    </div>
  );
}

/** 一张卡。 */
function MetricCardView({
  card,
  onDrilldown,
}: {
  card: MetricCardEntry;
  onDrilldown: () => void;
}) {
  const unavailable = isUnavailable(card);
  const rows = currencyRows(card);
  const tooltip = caliberTooltip(card);
  const trendClass = TREND_ICON_CLASS[trendTone(card.trend)];

  return (
    <div
      className={`rounded-lg border p-4 ${
        unavailable ? 'border-dashed bg-muted/30' : 'border-border bg-card'
      }`}
      title={tooltip || undefined}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="text-copy-13 text-muted-foreground">{card.displayName}</div>
        <Badge tone={statusTone(card.status)}>{statusLabel(card.status)}</Badge>
      </div>

      <div className="mt-2 flex items-baseline gap-1">
        <span className={`text-h-24 tracking-tight ${unavailable ? 'text-muted-foreground' : ''}`}>
          {headlineText(card)}
        </span>
        {card.unit === 'COUNT' || card.unit === 'QUANTITY' ? (
          <span className="text-copy-13 text-muted-foreground">{unitLabel(card.unit)}</span>
        ) : null}
      </div>

      {unavailable ? (
        <div className="text-copy-12 text-muted-foreground mt-2 space-y-1">
          <p>{card.note || '未接通'}</p>
          {/* 🔴 未接通的指标必须写清「归谁」—— 否则运营以为这是系统坏了，而不是口径没接通。 */}
          {card.ownerModule && <p>口径归属：{card.ownerModule}</p>}
        </div>
      ) : (
        <div className="mt-2 space-y-1">
          <div className="text-copy-12 text-muted-foreground flex items-center gap-1">
            <span>{trendLabel(card.trend)}</span>
            {card.trend === 'UP' ? (
              <ArrowUp className={`h-3 w-3 ${trendClass}`} aria-hidden="true" />
            ) : card.trend === 'DOWN' ? (
              <ArrowDown className={`h-3 w-3 ${trendClass}`} aria-hidden="true" />
            ) : (
              <Minus className="h-3 w-3" aria-hidden="true" />
            )}
            <span>环比 {comparisonLine(card.previousPeriod)}</span>
          </div>
          <div className="text-copy-12 text-muted-foreground">同比 {comparisonLine(card.yearOverYear)}</div>
          {rows.length > 1 && (
            <ul className="text-copy-12 text-muted-foreground space-y-0.5">
              {rows.map((row) => (
                <li key={row.currency}>
                  {row.currency} {formatValue(row.value, card.unit)}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {card.drilldown.available && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          {card.drilldown.detailPath && (
            <Button variant="outline" size="sm" onClick={onDrilldown}>
              展开构成明细
            </Button>
          )}
          {card.drilldown.reportPath && (
            <Link
              href={card.drilldown.reportPath}
              className="text-copy-13 text-primary underline-offset-2 hover:underline"
            >
              到 {card.drilldown.reportName ?? '报表页'}
            </Link>
          )}
        </div>
      )}
      {!card.drilldown.available && card.drilldown.reason && (
        <p className="text-copy-12 text-muted-foreground mt-3">{card.drilldown.reason}</p>
      )}
    </div>
  );
}

/** 趋势箭头的色调（**静态类名** —— Tailwind 只认字面量）。 */
const TREND_ICON_CLASS: Record<string, string> = {
  success: 'text-success',
  destructive: 'text-destructive',
  neutral: 'text-muted-foreground',
};

/** 构成明细的来源类型标签。 */
function sourceKindLabel(kind: string): string {
  switch (kind) {
    case 'ORDER':
      return '订单';
    case 'SKU':
      return '商品';
    case 'CAMPAIGN':
      return '广告活动';
    case 'RECEIVABLE':
      return '应收单';
    case 'STOCK':
      return '库存行';
    default:
      return kind;
  }
}
