'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, PackageSearch, RefreshCw, ShieldAlert, ShieldCheck } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  SafetyStockAlertDetail,
  SafetyStockAlertPage,
  SafetyStockAlertRow,
  SafetyStockCapabilities,
  SafetyStockEvaluationPage,
  SafetyStockPolicyPage,
  SafetyStockSalesPage,
  SafetyStockSuggestionPage,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from './api';
import {
  acknowledgeAlert,
  acknowledgeSuggestion,
  alertStatusLabel,
  alertTypeText,
  coverageDaysText,
  dailyAverageText,
  daysOfCoverText,
  dismissSuggestion,
  evaluate,
  evaluationCoverageText,
  fetchAlert,
  evaluationSummary,
  fetchAlerts,
  fetchCapabilities,
  fetchEvaluations,
  fetchPolicies,
  fetchSales,
  fetchSuggestions,
  isPlatformRisk,
  platformEscalationNote,
  registeredDaysText,
  resolveAlert,
  retirePolicy,
  scopeTypeLabel,
  severityEscalated,
  severityLabel,
  severityRank,
  severityText,
  shortfallText,
  suggestionStatusLabel,
  suggestedText,
  targetStockText,
  thresholdText,
  unavailableNotices,
  warehouseKindLabel,
} from './safety-stock-model';

/**
 * 7.2.2.2 安全库存工作区。
 *
 * <p>蓝图：「按 SKU/分类/仓库配置安全库存阈值与预警级别，低于阈值时触发预警并可关联自动生成
 * 补货建议」，且加粗写着「**安全库存要区分"平台断货风险"和"自有仓耗尽"：FBA 断货造成的损失远大于
 * 自有仓**」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>预警工作台占第一屏，且平台侧排在最前</b>。这一页存在的理由是"现在该先救哪个"，
 *       所以第一屏是预警而不是配置表单；排序用后端算好的提级后严重度，
 *       界面上再把"被提过级"标出来 —— 否则配置同样 INFO 的 FBA 与自有仓预警长得一模一样。
 *   <li><b>算不出来显示「不可得 + 原因」，不显示 0</b>。0 天会被读成今天断货，
 *       0 件建议会被读成不用补货 —— 后者会让该补的那批直接过去。
 *   <li><b>没配阈值与阈值安全长得不一样</b>。评估出 0 条预警时，如果一个阈值都没配，
 *       那不是"库存健康"而是"压根没人受保护"，这条要占版面。
 *   <li><b>四环不可用全部显式渲染</b>（销量需登记 / 分类展开需调用方给 / 6.2.1.2 未交付 /
 *       工作台未交付），不让人从 404 或空数组里猜哪一环没通。
 *   <li><b>阈值只给新建与换版，没有"就地改"</b>。改阈值 = 收口旧档 + 开新档，
 *       页面不给就地修改的入口，历史每一档因此永远是"当时那条预警依据"的凭据。
 * </ul>
 */

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有安全库存的维护权限';
    if (error.status === 0) return '正在连接仓库服务，请稍后重试';
    return error.message;
  }
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}

type Tab = 'alerts' | 'policies' | 'sales' | 'suggestions';

export function SafetyStockWorkspace() {
  const [tab, setTab] = useState<Tab>('alerts');
  const [caps, setCaps] = useState<SafetyStockCapabilities | null>(null);
  const [alerts, setAlerts] = useState<SafetyStockAlertPage | null>(null);
  const [policies, setPolicies] = useState<SafetyStockPolicyPage | null>(null);
  const [sales, setSales] = useState<SafetyStockSalesPage | null>(null);
  const [suggestions, setSuggestions] = useState<SafetyStockSuggestionPage | null>(null);
  const [evaluations, setEvaluations] = useState<SafetyStockEvaluationPage | null>(null);
  const [detail, setDetail] = useState<SafetyStockAlertDetail | null>(null);
  const [platformOnly, setPlatformOnly] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // 一次全取：四张表 + 能力声明 + 评估批次，页面首屏需要它们一起才说得清「扫了什么」。
      const [capabilities, alertPage, policyPage, salesPage, suggestionPage, evaluationPage] =
        await Promise.all([
          fetchCapabilities(),
          fetchAlerts({ platformRiskOnly: platformOnly, size: 50 }),
          fetchPolicies({ size: 50 }),
          fetchSales({ size: 50 }),
          fetchSuggestions({ size: 50 }),
          fetchEvaluations({ size: 10 }),
        ]);
      setCaps(capabilities);
      setAlerts(alertPage);
      setPolicies(policyPage);
      setSales(salesPage);
      setSuggestions(suggestionPage);
      setEvaluations(evaluationPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [platformOnly]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开预警详情：取回它关联的补货建议（可能为 null —— 没开自动建议或不可推算）。 */
  const openDetail = useCallback(async (id: string) => {
    setBusy(true);
    setError(null);
    try {
      setDetail(await fetchAlert(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const run = useCallback(
    async (action: () => Promise<unknown>, success: string) => {
      setBusy(true);
      setError(null);
      setMessage(null);
      try {
        await action();
        setMessage(success);
        await load();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const notices = useMemo(() => unavailableNotices(caps), [caps]);
  const alertRows = alerts?.alerts ?? [];
  const platformCount = alertRows.filter((r) => isPlatformRisk(r.alertType)).length;
  const ownCount = alertRows.length - platformCount;
  const unquantified = (suggestions?.suggestions ?? []).filter(
    (s) => s.suggestedQuantity === null || s.suggestedQuantity === undefined,
  );
  const unquantifiedAlerts = alertRows.filter((r) => r.daysOfCover === null);
  const latest = evaluations?.evaluations?.[0] ?? null;
  const noPolicyAtAll = (policies?.total ?? 0) === 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="安全库存"
        description="按 SKU/分类/仓库配置安全库存阈值与预警级别，低于阈值时触发预警并可关联自动生成补货建议。"
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => void run(() => evaluate({}), '已发起一次评估。')}
              disabled={loading || busy}
            >
              <RefreshCw className="mr-1 h-4 w-4" />
              发起评估
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void load()}
              disabled={loading || busy}
            >
              刷新
            </Button>
          </div>
        }
      />

      {/* 四环不可用全部显式渲染：不让人从 404 或空数组里猜哪一环没通。 */}
      {notices.length > 0 ? (
        <SectionCard title="本模块的已知边界" description="以下每一项都是真实约束，不是待办。">
          <ul className="space-y-2">
            {notices.map((notice) => (
              <li
                key={notice.key}
                className="rounded-md border border-amber-300 bg-amber-50/60 p-3"
              >
                <div className="flex items-center gap-2 text-sm font-medium text-amber-900">
                  <AlertTriangle className="h-4 w-4" />
                  {notice.title}
                </div>
                <p className="mt-1 text-sm text-amber-900/90">{notice.detail}</p>
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard
          label="待处理预警"
          value={alerts === null ? '—' : alertRows.length}
          polarity={alertRows.length > 0 ? 'negative' : 'positive'}
          comparisonLabel={
            alerts === null
              ? '加载中'
              : alertRows.length > 0
                ? '低于阈值，需要排进补货计划'
                : noPolicyAtAll
                  ? '当前一个阈值都没配：这不是库存健康'
                  : '已评估范围内没有低于阈值的 SKU'
          }
        />
        <StatCard
          label="平台断货风险"
          value={alerts === null ? '—' : platformCount}
          polarity={platformCount > 0 ? 'negative' : 'neutral'}
          comparisonLabel="FBA 断货损失远大于自有仓，已按上一级严重度处理"
        />
        <StatCard
          label="自有仓耗尽"
          value={alerts === null ? '—' : ownCount}
          polarity={ownCount > 0 ? 'negative' : 'neutral'}
          comparisonLabel="常规补货即可"
        />
        <StatCard
          label="建议量算不出的预警"
          value={alerts === null ? '—' : unquantifiedAlerts.length}
          polarity={unquantifiedAlerts.length > 0 ? 'negative' : 'neutral'}
          comparisonLabel="未登记销量：拿 0 兜底会被读成「不用补货」"
        />
      </div>

      {error ? (
        <div className="rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-800">
          {error}
          <Button className="ml-3" variant="outline" size="sm" onClick={() => void load()}>
            重试
          </Button>
        </div>
      ) : null}
      {message ? (
        <div className="rounded-md border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-900">
          {message}
        </div>
      ) : null}

      {latest ? (
        <SectionCard
          title="最近一次评估"
          description={evaluationCoverageText(latest)}
          extra={loading ? <span className="text-muted-foreground text-xs">加载中…</span> : null}
        >
          <p className="text-foreground text-sm">{evaluationSummary(latest)}</p>
          {latest.note ? <p className="text-muted-foreground mt-1 text-xs">{latest.note}</p> : null}
        </SectionCard>
      ) : null}

      <div className="flex items-center gap-2">
        {(
          [
            ['alerts', `预警（${alerts?.total ?? 0}）`],
            ['policies', `阈值分档（${policies?.total ?? 0}）`],
            ['sales', `日均销量（${sales?.total ?? 0}）`],
            ['suggestions', `补货建议（${suggestions?.total ?? 0}）`],
          ] as [Tab, string][]
        ).map(([key, text]) => (
          <Button
            key={key}
            variant={tab === key ? 'default' : 'outline'}
            size="sm"
            onClick={() => setTab(key)}
          >
            {text}
          </Button>
        ))}
      </div>

      {tab === 'alerts' ? (
        <SectionCard
          title="安全库存预警"
          description={
            caps
              ? platformEscalationNote(caps)
              : '平台断货风险一律按上一级严重度处理（蓝图：FBA 断货造成的损失远大于自有仓）。'
          }
          extra={
            <Button
              variant={platformOnly ? 'default' : 'outline'}
              size="sm"
              onClick={() => setPlatformOnly((v) => !v)}
              disabled={loading}
            >
              {platformOnly ? '只看平台断货（已开）' : '只看平台断货'}
            </Button>
          }
        >
          {loading ? (
            <EmptyState
              icon={<PackageSearch className="h-5 w-5" />}
              title="加载中"
              description="正在读取预警。"
            />
          ) : alertRows.length === 0 ? (
            <EmptyState
              icon={
                noPolicyAtAll ? (
                  <AlertTriangle className="h-5 w-5" />
                ) : (
                  <ShieldCheck className="h-5 w-5" />
                )
              }
              title={
                noPolicyAtAll
                  ? '还没有配置任何安全库存阈值'
                  : platformOnly
                    ? '没有平台断货风险预警'
                    : '没有低于阈值的 SKU'
              }
              description={
                noPolicyAtAll
                  ? '当前一个阈值都没配：此时 0 条预警指的是压根没有 SKU 受保护，不是库存健康。先去「阈值分档」配置。'
                  : '已评估范围内没有 SKU 低于阈值。可以点「发起评估」重新跑一次。'
              }
            />
          ) : (
            <ul className="divide-border divide-y">
              {alertRows.map((row) => (
                <li key={row.id} className="py-3">
                  <AlertCard
                    row={row}
                    busy={busy}
                    onOpen={() => void openDetail(row.id)}
                    onAcknowledge={() =>
                      void run(() => acknowledgeAlert(row.id), '已认领。预警读数快照原样保留。')
                    }
                    onResolve={() => void run(() => resolveAlert(row.id), '已处置。')}
                    onAcknowledgeSuggestion={() => {
                      const id = detail?.suggestion?.id;
                      if (id) void run(() => acknowledgeSuggestion(id), '已采纳。');
                    }}
                  />
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      ) : null}

      {tab === 'policies' ? (
        <SectionCard
          title="阈值分档"
          description="改阈值 = 收口旧档 + 开新档，历史各档原样保留。一档阈值挂的仓必须同类型：自有仓与 FBA 是两个独立的池子，混在一起求和会掩盖掉其中一个早已归零的事实。"
        >
          {loading ? (
            <EmptyState
              icon={<PackageSearch className="h-5 w-5" />}
              title="加载中"
              description="正在读取阈值分档。"
            />
          ) : (policies?.policies.length ?? 0) === 0 ? (
            <EmptyState
              icon={<ShieldAlert className="h-5 w-5" />}
              title="还没有配置阈值"
              description="没有任何阈值时，任何 SKU 都不会触发安全库存预警。"
            />
          ) : (
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="py-2">范围</th>
                  <th className="py-2">SKU</th>
                  <th className="py-2">阈值</th>
                  <th className="py-2">预警级别</th>
                  <th className="py-2">覆盖天数</th>
                  <th className="py-2">生效区间</th>
                  <th className="py-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {(policies?.policies ?? []).map((p) => (
                  <tr key={p.id} className="border-border border-t">
                    <td className="py-2">{scopeTypeLabel(p.scopeType)}</td>
                    <td className="py-2">{p.skuLabel || '—'}</td>
                    <td className="py-2">{thresholdText(p.thresholdQuantity)}</td>
                    <td className="py-2">{severityLabel(p.alertSeverity)}</td>
                    <td className="py-2">
                      {p.autoSuggestReplenishment
                        ? coverageDaysText(p.reorderCoverageDays)
                        : '未开自动建议'}
                    </td>
                    <td className="text-muted-foreground py-2 text-xs">
                      {p.effectiveTo
                        ? `${p.effectiveFrom} ~ ${p.effectiveTo}（已收口）`
                        : `${p.effectiveFrom} ~ 至今`}
                    </td>
                    <td className="py-2">
                      {p.effectiveTo ? null : (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() =>
                            void run(
                              () => retirePolicy(p.id),
                              '已作废：行本身保留，随时可回看当时阈值。',
                            )
                          }
                        >
                          作废
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </SectionCard>
      ) : null}

      {tab === 'sales' ? (
        <SectionCard
          title="日均销量的登记"
          description="日均销量无法自动派生：库存流水没有渠道维度，13 销量域尚未交付。销量由这里登记进来，它是本服务唯一的销量真相来源。同一来源同一 SKU 的登记区间不得重叠。"
        >
          {loading ? (
            <EmptyState
              icon={<PackageSearch className="h-5 w-5" />}
              title="加载中"
              description="正在读取销量登记。"
            />
          ) : (sales?.sales.length ?? 0) === 0 ? (
            <EmptyState
              icon={<AlertTriangle className="h-5 w-5" />}
              title="还没有登记任何销量"
              description="没有销量时，可售天数与补货建议量算不出来（会如实留空，而不是拿 0 冒充）。"
            />
          ) : (
            <ul className="divide-border divide-y">
              {(sales?.sales ?? []).map((row) => (
                <li key={row.skuId} className="flex items-center justify-between py-3 text-sm">
                  <span>{row.skuLabel || row.skuId}</span>
                  <span className="text-muted-foreground">
                    {dailyAverageText(row.dailyAverage)} · {registeredDaysText(row)} ·{' '}
                    {row.firstPeriodStart} ~ {row.lastPeriodEnd}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      ) : null}

      {tab === 'suggestions' ? (
        <SectionCard
          title="补货建议"
          description="由预警按「目标库存 = 阈值 + 日均销量 × 覆盖天数」推算。6.2.1.2 补货建议尚未交付且属 06 采购域，本模块只落库、不跨库写采购单；算不出量的那些会单独标出来。"
        >
          {loading ? (
            <EmptyState
              icon={<PackageSearch className="h-5 w-5" />}
              title="加载中"
              description="正在读取补货建议。"
            />
          ) : (suggestions?.suggestions.length ?? 0) === 0 ? (
            <EmptyState
              icon={<ShieldCheck className="h-5 w-5" />}
              title="还没有补货建议"
              description="只有阈值档打开了「预警时自动生成补货建议」才会生成建议。"
            />
          ) : (
            <ul className="divide-border divide-y">
              {(suggestions?.suggestions ?? []).map((s) => (
                <li key={s.id} className="flex items-center justify-between py-3 text-sm">
                  <div>
                    <div>{s.skuLabel || s.skuCode || s.skuId}</div>
                    <div className="text-muted-foreground text-xs">
                      {s.warehouseCode} · {suggestedText(s.suggestedQuantity, s.unquantifiedReason)}{' '}
                      · {targetStockText(s.targetStockQuantity)}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-muted-foreground text-xs">
                      {suggestionStatusLabel(s.status)}
                    </span>
                    {s.status === 'PROPOSED' ? (
                      <>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void run(() => acknowledgeSuggestion(s.id), '已采纳。')}
                        >
                          采纳
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() =>
                            void run(
                              () => dismissSuggestion(s.id, '本季清库存，暂不补货（页面快捷操作）'),
                              '已忽略，理由已留档。',
                            )
                          }
                        >
                          忽略
                        </Button>
                      </>
                    ) : null}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      ) : null}

      {detail ? (
        <SectionCard
          title="预警详情"
          description={`${detail.alert.skuLabel} · ${detail.alert.warehouseName}`}
        >
          <Button variant="outline" size="sm" className="mb-2" onClick={() => setDetail(null)}>
            关闭详情
          </Button>
          <AlertCard
            row={detail.alert}
            busy={busy}
            onOpen={() => undefined}
            onAcknowledge={() => void run(() => acknowledgeAlert(detail.alert.id), '已认领。')}
            onResolve={() => void run(() => resolveAlert(detail.alert.id), '已处置。')}
            onAcknowledgeSuggestion={() => {
              const id = detail.suggestion?.id;
              if (id) void run(() => acknowledgeSuggestion(id), '已采纳。');
            }}
          />
        </SectionCard>
      ) : null}

      {unquantified.length > 0 ? (
        <SectionCard
          title={`算不出建议量的建议（${unquantified.length}）`}
          description="这些是最容易被忽略的一批：拿 0 兜底会变成「不用补货」，于是该补的那批就这么过去了。"
        >
          <ul className="space-y-2 text-sm">
            {unquantified.map((s) => (
              <li key={s.id} className="border-border rounded-md border p-3">
                <div>
                  {s.skuLabel || s.skuCode || s.skuId} · {s.warehouseCode}
                </div>
                <div className="text-muted-foreground">
                  {s.unquantifiedReason || '尚未登记销量'}
                </div>
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}
    </div>
  );
}

/** 一条预警。平台侧与自有仓侧在**视觉上**也必须分得开，而不只是文字不同。 */
function AlertCard({
  row,
  busy,
  onOpen,
  onAcknowledge,
  onResolve,
  onAcknowledgeSuggestion,
}: {
  row: SafetyStockAlertRow;
  busy: boolean;
  onOpen: () => void;
  onAcknowledge: () => void;
  onResolve: () => void;
  onAcknowledgeSuggestion: () => void;
}) {
  const platform = isPlatformRisk(row.alertType);
  const escalated = severityEscalated(row.severity, row.effectiveSeverity);
  const ordered = severityRank(row.effectiveSeverity);
  const border =
    ordered === 0
      ? 'border-red-400 bg-red-50/70'
      : ordered === 1
        ? 'border-amber-300 bg-amber-50/50'
        : 'border-border';
  return (
    <div className={`rounded-md border p-3 ${border}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span
          className={`rounded px-2 py-0.5 text-xs font-medium ${
            platform ? 'bg-red-600 text-white' : 'bg-slate-200 text-slate-800'
          }`}
        >
          {alertTypeText(row.alertType)}
        </span>
        <span className="text-sm font-medium">
          {severityText(row.severity, row.effectiveSeverity)}
        </span>
        {escalated ? (
          <span className="rounded bg-red-600/10 px-2 py-0.5 text-xs text-red-700">已提级</span>
        ) : null}
        <span className="text-sm">{row.skuLabel || row.skuCode || row.skuId}</span>
        <span className="text-muted-foreground text-xs">{row.warehouseName}</span>
        <span className="text-muted-foreground ml-auto text-xs">
          {alertStatusLabel(row.status)}
        </span>
      </div>
      <div className="text-muted-foreground mt-2 grid gap-1 text-sm sm:grid-cols-3">
        <span>可用 {row.availableQuantity}</span>
        <span>阈值 {thresholdText(row.thresholdQuantity)}</span>
        <span>{shortfallText(row.shortfallQuantity)}</span>
        <span>{dailyAverageText(row.dailyAverageSales)}</span>
        <span>{daysOfCoverText(row.daysOfCover, row.dailyAverageSales)}</span>
        <span>{suggestedText(row.suggestedQuantity)}</span>
      </div>
      {row.reviewNote ? <p className="mt-2 text-xs text-amber-800">{row.reviewNote}</p> : null}
      <div className="mt-2 flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={onOpen} disabled={busy}>
          查看建议
        </Button>
        {row.status === 'OPEN' ? (
          <Button variant="outline" size="sm" onClick={onAcknowledge} disabled={busy}>
            认领
          </Button>
        ) : null}
        {row.status === 'ACKNOWLEDGED' ? (
          <Button variant="outline" size="sm" onClick={onResolve} disabled={busy}>
            处置完成
          </Button>
        ) : null}
        {row.suggestedQuantity !== null && row.suggestedQuantity !== undefined ? (
          <Button variant="outline" size="sm" onClick={onAcknowledgeSuggestion} disabled={busy}>
            采纳补货建议
          </Button>
        ) : null}
        <span className="text-muted-foreground text-xs">
          挂仓类型：{warehouseKindLabel(platform ? 'PLATFORM_FBA' : 'OWN')}
        </span>
      </div>
    </div>
  );
}
