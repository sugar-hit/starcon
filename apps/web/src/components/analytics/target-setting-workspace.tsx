'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  amountText,
  availableCalipers,
  baselineLabel,
  baselineScopeLabel,
  baselineTone,
  bucketText,
  bucketValueText,
  caliberFormulaText,
  caliberOptionLabel,
  capabilityNotes,
  currencyTotalsNote,
  currencyTotalsText,
  currentPeriodText,
  emptyText,
  granularityLabel,
  isOrgScope,
  methodLabel,
  metricAvailable,
  metricCalipers,
  notDeliveredLines,
  ownerLabel,
  ownerModuleLabel,
  pageState,
  periodText,
  previewReconciliationText,
  previewRemainderText,
  reconciledLabel,
  reconciledTone,
  scopeLabel,
  statusLabel,
  statusTone,
  targetValueText,
  timezoneText,
  unitLabel,
  upstreamModules,
  validateTargetInput,
} from './target-setting-model';
import type {
  TargetCapabilities,
  TargetCurrentPeriod,
  TargetDecompositionPreview,
  TargetInput,
  TargetListResponse,
  TargetMetricView,
  TargetOwnerList,
  TargetView,
} from './target-setting-model';

/**
 * 13.1.2.1 目标设定工作区（卷 13「利润与经营分析」L3 13.1.2 目标管理下的第一格）。
 *
 * <p>蓝图输出：「目标记录 + 分解计划。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/targets} —— 目标清单（真分页 + 按币种合计）；</li>
 *   <li>{@code GET reporting/targets/capabilities} —— 能力声明；</li>
 *   <li>{@code GET reporting/targets/metrics} —— 蓝图点名的目标指标清单（按归属维度）；</li>
 *   <li>{@code GET reporting/targets/owners} —— 归属对象清单（部门 / 人员 / 店铺 / 产品）；</li>
 *   <li>{@code GET reporting/targets/current-period} —— 当前周期（按本租户时区算）；</li>
 *   <li>{@code GET reporting/targets/decomposition} —— 分解预览（不落库）；</li>
 *   <li>{@code POST/PUT/DELETE reporting/targets} —— 设定 / 修改 / 删除目标；</li>
 *   <li>{@code POST reporting/targets/{id}/baseline} —— 重读历史基线。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>目标挂在组织对象上</b>（蓝图关系句的命门）：归属对象是必选项，
 *       没有归属对象的目标**不给出提交入口**。</li>
 *   <li><b>口径唯一</b>：销售额在店铺维度是<b>三个并列口径</b>，页面把它们都列出来，
 *       不许只挑一个改名叫「销售额」；没登记口径码的指标显示「未登记」并给出归属模块。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>：历史基线取不到时显示「—」+ 原因。</li>
 *   <li><b>多币种不相加</b>：合计按币种分列。</li>
 *   <li><b>分解合计必须精确等于目标值</b>：预览与落库走同一套计算，
 *       余数归属写在页面上（不静默差 0.01）。</li>
 * </ol>
 */
export function AnalyticsTargetSettingWorkspace() {
  const [scopeType, setScopeType] = useState('DEPARTMENT');
  // 列表筛选按**口径码**筛（后端筛的是 reporting_target.metric_code）。
  const [filterMetricCode, setFilterMetricCode] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [page, setPage] = useState(0);

  const [list, setList] = useState<TargetListResponse | null>(null);
  const [capabilities, setCapabilities] = useState<TargetCapabilities | null>(null);
  const [metrics, setMetrics] = useState<TargetMetricView[]>([]);
  const [owners, setOwners] = useState<TargetOwnerList | null>(null);
  const [period, setPeriod] = useState<TargetCurrentPeriod | null>(null);
  const [preview, setPreview] = useState<TargetDecompositionPreview | null>(null);
  const [selected, setSelected] = useState<TargetView | null>(null);

  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // ---- 设定表单 ----
  // 🔴 「蓝图目标指标键」（如 target.sales）与「引用的口径码」（如 STORE_GMV_PLATFORM）
  //   是**两件事**：店铺维度的销售额有三个并列口径，必须让用户自己选哪一个。
  const [metricKey, setMetricKey] = useState('');
  const [caliberCode, setCaliberCode] = useState('');
  const [ownerId, setOwnerId] = useState('');
  const [targetValue, setTargetValue] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [granularity, setGranularity] = useState('MONTHLY');
  const [method, setMethod] = useState('EVEN');
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');
  const [note, setNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({ scopeType, page: String(page), size: '50' });
      if (filterMetricCode) params.set('metricCode', filterMetricCode);
      if (status) params.set('status', status);
      const [targets, caps, metricList, ownerList, current] = await Promise.all([
        api<TargetListResponse>(`reporting/targets?${params.toString()}`),
        api<TargetCapabilities>('reporting/targets/capabilities'),
        api<TargetMetricView[]>(`reporting/targets/metrics?scopeType=${scopeType}`),
        api<TargetOwnerList>(`reporting/targets/owners?scopeType=${scopeType}`),
        api<TargetCurrentPeriod>('reporting/targets/current-period?granularity=MONTHLY'),
      ]);
      setList(targets);
      setCapabilities(caps);
      setMetrics(metricList);
      setOwners(ownerList);
      setPeriod(current);
      setPeriodStart((previous) => previous || current.from);
      setPeriodEnd((previous) => previous || current.to);
    } catch (e) {
      setList(null);
      setError(e instanceof Error ? e.message : '读取目标清单失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [scopeType, filterMetricCode, status, page]);

  useEffect(() => {
    void load();
  }, [load]);

  // 切换归属维度时，换一份指标清单并清掉选中的归属对象 / 口径码。
  useEffect(() => {
    setFilterMetricCode('');
    setMetricKey('');
    setCaliberCode('');
    setOwnerId('');
  }, [scopeType]);

  /** 选中的**蓝图目标指标**（如「销售额」）。 */
  const selectedMetric = useMemo(
    () => metrics.find((metric) => metric.key === metricKey) ?? null,
    [metrics, metricKey],
  );

  /** 该维度下可引用的口径码（**口径唯一**：只能选已登记的）。 */
  const calipers = useMemo(
    () => (selectedMetric ? metricCalipers(selectedMetric) : []),
    [selectedMetric],
  );

  /** 列表筛选器的候选（当前维度下全部可引用的口径码）。 */
  const filterCalipers = useMemo(() => availableCalipers(metrics), [metrics]);

  /** 分解预览（不落库）—— 与真正落库走**同一套服务端计算**。 */
  const refreshPreview = useCallback(async () => {
    if (!periodStart || !periodEnd || !targetValue) {
      setPreview(null);
      return;
    }
    try {
      const params = new URLSearchParams({
        periodStart,
        periodEnd,
        granularity,
        method,
        currency,
        targetValue,
      });
      const result = await api<TargetDecompositionPreview>(
        `reporting/targets/decomposition?${params.toString()}`,
      );
      setPreview(result);
    } catch {
      setPreview(null);
    }
  }, [periodStart, periodEnd, granularity, method, currency, targetValue]);

  const submit = useCallback(async () => {
    setFormError('');
    if (!caliberCode || !ownerId || !targetValue || !periodStart || !periodEnd) {
      setFormError('归属对象、目标指标口径码、目标值、周期起止都要填 —— 目标必须挂在组织对象上。');
      return;
    }
    const payload: TargetInput = {
      metricCode: caliberCode,
      scopeType,
      ownerId,
      currency,
      targetValue: Number(targetValue),
      periodStart,
      periodEnd,
      granularity,
      decompositionMethod: method,
      weights: null,
      status: null,
      note: note || null,
    };
    const problems = validateTargetInput(payload);
    if (problems.length > 0) {
      setFormError(problems.join(' '));
      return;
    }
    setBusy(true);
    try {
      await api<TargetView>('reporting/targets', 'POST', payload);
      setOwnerId('');
      setTargetValue('');
      setNote('');
      setPreview(null);
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '设定目标失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [
    caliberCode,
    scopeType,
    ownerId,
    targetValue,
    periodStart,
    periodEnd,
    granularity,
    method,
    currency,
    note,
    load,
  ]);

  const remove = useCallback(
    async (id: string) => {
      setBusy(true);
      try {
        await api<void>(`reporting/targets/${id}`, 'DELETE');
        if (selected?.id === id) setSelected(null);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : '删除目标失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [selected, load],
  );

  const reloadBaseline = useCallback(
    async (id: string) => {
      setBusy(true);
      try {
        const refreshed = await api<TargetView>(`reporting/targets/${id}/baseline`, 'POST');
        setSelected(refreshed);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : '重读历史基线失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const state = pageState(list, error, loading);
  const totalPages = list ? Math.max(1, Math.ceil(list.total / Math.max(list.size, 1))) : 1;

  return (
    <div className="space-y-4">
      {/* ── 常驻提醒（口径纪律 + 拿不到的部分）────────────────────────── */}
      <div className="border-border bg-muted/40 text-copy-13 text-muted-foreground space-y-1 rounded-lg border px-4 py-3">
        <p>
          <span className="text-foreground font-medium">口径唯一：</span>
          目标只**引用**已登记的口径码（口径真相在 13 的口径表里），本模块不重算口径。
        </p>
        <p>
          <span className="text-foreground font-medium">分解计划：</span>
          分解项合计**精确等于**目标值 —— 均分时差额归最后一个分解项，按权重时用最大余额法补齐，
          余数归属写在下面（不静默差 0.01）。
        </p>
        <p>
          <span className="text-foreground font-medium">多币种：</span>
          合计按币种分列，**绝不跨币种相加**。
        </p>
        <p>{timezoneText(period)}</p>
        {notDeliveredLines(capabilities).map((line, index) => (
          <p key={index}>
            <TriangleAlert className="mr-1 inline h-3.5 w-3.5" />
            {line}
          </p>
        ))}
      </div>

      {/* ── 筛选 ───────────────────────────────────────────────────────── */}
      <SectionCard
        title="目标筛选"
        description="归属对象类型决定用哪张口径表：部门 / 人员 / 产品走租户口径表，店铺走店铺口径表。"
      >
        <div className="flex flex-wrap items-end gap-4">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            归属对象类型
            <select
              value={scopeType}
              onChange={(e) => setScopeType(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              {['DEPARTMENT', 'MEMBER', 'SHOP', 'PRODUCT'].map((scope) => (
                <option key={scope} value={scope}>
                  {scopeLabel(scope)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            目标口径码
            <select
              value={filterMetricCode}
              onChange={(e) => setFilterMetricCode(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              {filterCalipers.map((caliber) => (
                <option key={caliber.metricCode} value={caliber.metricCode}>
                  {caliberOptionLabel(caliber)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            状态
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              <option value="ACTIVE">生效中</option>
              <option value="ARCHIVED">已归档</option>
            </select>
          </label>
          <Button variant="outline" size="sm" disabled={loading || busy} onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            重读
          </Button>
        </div>

        {/* 指标清单：可引用的口径码 / 没登记的指标 */}
        <div className="mt-4 space-y-2">
          {metrics.map((metric) => (
            <div key={metric.key} className="border-border rounded-lg border px-4 py-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-copy-14 text-foreground font-medium">{metric.displayName}</span>
                <Badge tone={metricAvailable(metric) ? 'success' : 'neutral'}>
                  {metricAvailable(metric) ? '可设目标' : '未登记口径'}
                </Badge>
                <span className="text-copy-13 text-muted-foreground">
                  单位 {unitLabel(metric.unit)} · 口径归 {ownerModuleLabel(metric.ownerModule)}
                </span>
              </div>
              {metricAvailable(metric) ? (
                <ul className="text-copy-13 text-muted-foreground mt-1 list-disc pl-5">
                  {metric.calipers.map((caliber) => (
                    <li key={caliber.metricCode}>
                      <span className="text-foreground">{caliberOptionLabel(caliber)}</span> —{' '}
                      {caliber.caliberNote}（{caliberFormulaText(caliber)}）
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-copy-13 text-muted-foreground mt-1">{metric.unavailableReason}</p>
              )}
            </div>
          ))}
        </div>
      </SectionCard>

      {/* ── 设定目标 ───────────────────────────────────────────────────── */}
      <SectionCard
        title="设定目标"
        description="目标 = 已登记的口径码 + 目标值 + 币种 + 周期 + 归属对象。归属对象由服务端回查权威来源并取快照。"
      >
        <div className="flex flex-wrap items-end gap-4">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            归属对象（{scopeLabel(scopeType)}）
            <select
              value={ownerId}
              onChange={(e) => setOwnerId(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">请选择</option>
              {(owners?.owners ?? []).map((owner) => (
                <option key={owner.ownerId} value={owner.ownerId}>
                  {owner.ownerName}
                  {owner.ownerCode ? `（${owner.ownerCode}）` : ''}
                  {owner.detail ? ` · ${owner.detail}` : ''}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            目标指标
            <select
              value={metricKey}
              onChange={(e) => {
                const key = e.target.value;
                setMetricKey(key);
                const next = metrics.find((metric) => metric.key === key);
                setCaliberCode(next && next.available ? (next.calipers[0]?.metricCode ?? '') : '');
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">请选择</option>
              {metrics
                .filter((metric) => metricAvailable(metric))
                .map((metric) => (
                  <option key={metric.key} value={metric.key}>
                    {metric.displayName}
                  </option>
                ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            引用口径码
            <select
              value={caliberCode}
              onChange={(e) => setCaliberCode(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              disabled={calipers.length === 0}
            >
              {calipers.length === 0 && <option value="">请先选目标指标</option>}
              {calipers.map((caliber) => (
                <option key={caliber.metricCode} value={caliber.metricCode}>
                  {caliberOptionLabel(caliber)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            目标值
            <input
              type="number"
              step="0.01"
              value={targetValue}
              onChange={(e) => setTargetValue(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            币种
            <input
              type="text"
              maxLength={3}
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            周期起
            <input
              type="date"
              value={periodStart}
              onChange={(e) => setPeriodStart(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            周期止
            <input
              type="date"
              value={periodEnd}
              onChange={(e) => setPeriodEnd(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            分解粒度
            <select
              value={granularity}
              onChange={(e) => setGranularity(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="MONTHLY">按月分解</option>
              <option value="WEEKLY">按周分解</option>
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            分解方式
            <select
              value={method}
              onChange={(e) => setMethod(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="EVEN">均分</option>
              <option value="WEIGHTED">按权重</option>
            </select>
          </label>
          <Button variant="outline" size="sm" disabled={busy} onClick={() => void refreshPreview()}>
            预览分解
          </Button>
          <Button size="sm" disabled={busy} onClick={() => void submit()}>
            设定目标
          </Button>
        </div>

        {formError && (
          <p className="text-copy-13 text-destructive mt-3">
            <TriangleAlert className="mr-1 inline h-3.5 w-3.5" />
            {formError}
          </p>
        )}

        {preview && (
          <div className="border-border mt-4 rounded-lg border px-4 py-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-copy-14 text-foreground font-medium">分解预览</span>
              <Badge tone={reconciledTone(preview.reconciled)}>
                {reconciledLabel(preview.reconciled)}
              </Badge>
              <span className="text-copy-13 text-muted-foreground">
                {granularityLabel(preview.granularity)} · {methodLabel(preview.decompositionMethod)}
              </span>
            </div>
            <p className="text-copy-13 text-muted-foreground mt-1">
              {previewReconciliationText(preview)}
            </p>
            <p className="text-copy-13 text-muted-foreground mt-1">{previewRemainderText(preview)}</p>
            <table className="mt-3 w-full text-copy-13">
              <thead>
                <tr className="text-muted-foreground text-left">
                  <th className="py-1 pr-3 font-medium">分解项</th>
                  <th className="py-1 pr-3 font-medium">目标金额</th>
                </tr>
              </thead>
              <tbody>
                {preview.buckets.map((bucket) => (
                  <tr key={bucket.bucketStart} className="border-border border-t">
                    <td className="py-1 pr-3">{bucketText(bucket)}</td>
                    <td className="py-1 pr-3">{bucketValueText(bucket)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ── 错误态 ─────────────────────────────────────────────────────── */}
      {state === 'error' && (
        <SectionCard>
          <EmptyState
            title="目标清单读不到"
            description={`${error}（这是「读不到」，不是「没有目标」）—— 身份服务 / 商品主数据不可达或没有读权限时就会出现。`}
            action={
              <Button variant="outline" size="sm" disabled={loading || busy} onClick={() => void load()}>
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
          <EmptyState title="正在读取目标清单…" description="读取期间下面的口径纪律提醒仍然有效。" />
        </SectionCard>
      )}

      {/* ── 空态 ───────────────────────────────────────────────────────── */}
      {state === 'empty' && (
        <SectionCard>
          <EmptyState title="还没有设定任何目标" description={emptyText(list)} />
        </SectionCard>
      )}

      {/* ── 就绪态 ─────────────────────────────────────────────────────── */}
      {state === 'ready' && list && (
        <>
          <SectionCard
            title={`目标清单（${list.total} 条）`}
            description="每条目标带自己的分解计划；点开看分解项与历史基线。"
            extra={
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page <= 0}
                  onClick={() => setPage((previous) => Math.max(previous - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-copy-13 text-muted-foreground">
                  {page + 1} / {totalPages}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page + 1 >= totalPages}
                  onClick={() => setPage((previous) => previous + 1)}
                >
                  下一页
                </Button>
              </div>
            }
          >
            <div className="space-y-3">
              {list.targets.map((target) => (
                <div key={target.id} className="border-border rounded-lg border px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-14 text-foreground font-medium">
                      {target.metricDisplayName}
                    </span>
                    <Badge tone={statusTone(target.status)}>{statusLabel(target.status)}</Badge>
                    <Badge tone="neutral">{scopeLabel(target.scopeType)}</Badge>
                    <span className="text-copy-13 text-muted-foreground">{ownerLabel(target)}</span>
                  </div>
                  <div className="text-copy-13 text-muted-foreground mt-1 flex flex-wrap gap-x-4 gap-y-1">
                    <span>
                      目标值 {targetValueText(target.unit, target.targetValue, target.currency)}
                    </span>
                    <span>
                      周期 {periodText(target)}（{granularityLabel(target.granularity)} ·{' '}
                      {methodLabel(target.decompositionMethod)}）
                    </span>
                    <span>
                      分解 {target.bucketCount} 项 · 合计{' '}
                      {targetValueText(target.unit, target.bucketSum, target.currency)}
                    </span>
                    <span>
                      基线{' '}
                      <Badge tone={baselineTone(target.baseline.status)}>
                        {baselineLabel(target.baseline.status)}
                      </Badge>{' '}
                      {target.baseline.value === null
                        ? '—'
                        : targetValueText(target.unit, target.baseline.value, target.baseline.currency)}{' '}
                      （{baselineScopeLabel(target.baseline.scope)}）
                    </span>
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setSelected(target)}
                      disabled={selected?.id === target.id}
                    >
                      {selected?.id === target.id ? '已展开' : '看分解计划与基线'}
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      onClick={() => void reloadBaseline(target.id)}
                    >
                      重读历史基线
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      onClick={() => void remove(target.id)}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              ))}
            </div>
            <p className="text-copy-13 text-muted-foreground mt-3">
              合计：{currencyTotalsText(list.currencyTotals)}
            </p>
            <p className="text-copy-13 text-muted-foreground">{currencyTotalsNote(list.currencyTotals)}</p>
          </SectionCard>

          {selected && (
            <SectionCard
              title={`${selected.metricDisplayName} · ${ownerLabel(selected)}`}
              description={`周期 ${periodText(selected)} · 口径码 ${selected.metricCode} · 引用 ${selected.metricDisplayName}`}
              extra={
                <Button variant="outline" size="sm" onClick={() => setSelected(null)}>
                  收起
                </Button>
              }
            >
              <div className="text-copy-13 text-muted-foreground space-y-1">
                <p>目标值 {targetValueText(selected.unit, selected.targetValue, selected.currency)}</p>
                <p>
                  基线：{baselineLabel(selected.baseline.status)} ·{' '}
                  {selected.baseline.value === null
                    ? '—'
                    : targetValueText(selected.unit, selected.baseline.value, selected.baseline.currency)}{' '}
                  （{baselineScopeLabel(selected.baseline.scope)}）
                </p>
                <p>{selected.baseline.note}</p>
                {selected.baseline.from && selected.baseline.to && (
                  <p>
                    基线窗口 {selected.baseline.from} ~ {selected.baseline.to}（目标周期的去年同期）
                  </p>
                )}
              </div>
              <table className="mt-3 w-full text-copy-13">
                <thead>
                  <tr className="text-muted-foreground text-left">
                    <th className="py-1 pr-3 font-medium">分解项</th>
                    <th className="py-1 pr-3 font-medium">目标金额</th>
                    <th className="py-1 pr-3 font-medium">权重</th>
                  </tr>
                </thead>
                <tbody>
                  {selected.buckets.map((bucket) => (
                    <tr key={bucket.bucketStart} className="border-border border-t">
                      <td className="py-1 pr-3">{bucketText(bucket)}</td>
                      <td className="py-1 pr-3">{bucketValueText(bucket)}</td>
                      <td className="py-1 pr-3">{bucket.weight}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="text-copy-13 text-muted-foreground mt-2">
                {selected.reconciled
                  ? '分解项合计与目标值一致。'
                  : `⚠️ 分解项合计 ${selected.bucketSum} 与目标值 ${selected.targetValue} 不一致。`}
              </p>
            </SectionCard>
          )}
        </>
      )}

      {/* ── 能力声明 ───────────────────────────────────────────────────── */}
      <SectionCard
        title="能力声明"
        description="本服务真的读过哪些上游、确实拿不到什么、判定规则是什么。"
      >
        <div className="text-copy-13 text-muted-foreground space-y-2">
          <div>
            <span className="text-foreground font-medium">上游（真的读过）：</span>
            <ul className="mt-1 list-disc pl-5">
              {upstreamModules(capabilities).map((line, index) => (
                <li key={index}>{line}</li>
              ))}
            </ul>
          </div>
          <div>
            <span className="text-foreground font-medium">确实拿不到：</span>
            <ul className="mt-1 list-disc pl-5">
              {notDeliveredLines(capabilities).map((line, index) => (
                <li key={index}>{line}</li>
              ))}
            </ul>
          </div>
          <div>
            <span className="text-foreground font-medium">口径纪律：</span>
            <ul className="mt-1 list-disc pl-5">
              {capabilityNotes(capabilities).map((line, index) => (
                <li key={index}>{line}</li>
              ))}
            </ul>
          </div>
        </div>
      </SectionCard>
    </div>
  );
}
