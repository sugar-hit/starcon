'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  Activity,
  CircleAlert,
  Gauge,
  Loader2,
  RefreshCw,
  ScrollText,
  ShieldAlert,
  Timer,
  TriangleAlert,
} from 'lucide-react';
import {
  acknowledgeAlert,
  alertStatusLabel,
  alertStatusTone,
  alertTypeLabel,
  errorCodeLabel,
  fetchAlerts,
  fetchApps,
  fetchCalls,
  fetchCapabilities,
  fetchEndpoints,
  fetchOptions,
  fetchOverview,
  fetchSlowCalls,
  fetchTrend,
  formatDuration,
  formatRate,
  formatWindow,
  outcomeLabel,
  outcomeTone,
  quotaTargetHint,
  resolveAlert,
  runScan,
  severityTone,
  thresholdText,
  unavailableCapabilities,
  type Tone,
} from './call-monitor-model';
import type {
  ApiCallMonitorAlertPage,
  ApiCallMonitorAlertRow,
  ApiCallMonitorAppRow,
  ApiCallMonitorCallPage,
  ApiCallMonitorCapabilities,
  ApiCallMonitorEndpointRow,
  ApiCallMonitorOptions,
  ApiCallMonitorOverview,
  ApiCallMonitorSlowCall,
  ApiCallMonitorTrend,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.1.1.3「调用监控」工作区（卷 18 前端入口 `/integrations/call-monitor`）。
 *
 * 蓝图：「<strong>职责</strong>：监控 API 调用：QPS、错误率、限流命中、慢调用、按应用与接口维度分析，异常告警。
 * <strong>输入</strong>：调用埋点；配额配置。<strong>输出</strong>：调用统计 + 告警。
 * <strong>关系</strong>：上游 = API 网关；下游 = 1 工作台告警、18.1.1.1 配额调整。
 * <strong>外部系统调用错误要能被快速定位</strong>，否则对接方只会说"你们接口有问题"。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **限流命中与错误并排显示**，不合成一个数 —— 「有多少次被配额挡下来」与「有多少个 4xx」
 *    是两个问题。
 * 2. **阈值口径必须显示出来**（告警是按什么判的）。
 * 3. **不可用的下游要显式摆出来**（卷 01 工作台告警未交付）。
 * 4. **读不到 ≠ 没有**：端点的活契约读不到时写明原因，不悄悄删掉。
 * 5. **告警要指路**：限流类告警直接给出 18.1.1.1 的配额调整端点与建议值。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「这个窗口没有调用」与「筛选条件不对」。
 */

type Tab = 'overview' | 'calls' | 'slow' | 'alerts' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const WINDOWS = [300, 900, 3600, 86400];

export function CallMonitorWorkspace() {
  const [tab, setTab] = useState<Tab>('overview');
  const [windowSeconds, setWindowSeconds] = useState(900);

  const [capabilities, setCapabilities] = useState<ApiCallMonitorCapabilities | null>(null);
  const [options, setOptions] = useState<ApiCallMonitorOptions | null>(null);
  const [overview, setOverview] = useState<ApiCallMonitorOverview | null>(null);
  const [trend, setTrend] = useState<ApiCallMonitorTrend | null>(null);
  const [apps, setApps] = useState<ApiCallMonitorAppRow[]>([]);
  const [endpoints, setEndpoints] = useState<ApiCallMonitorEndpointRow[]>([]);
  const [slowCalls, setSlowCalls] = useState<ApiCallMonitorSlowCall[]>([]);

  const [calls, setCalls] = useState<ApiCallMonitorCallPage | null>(null);
  const [callOutcome, setCallOutcome] = useState('');
  const [callEndpoint, setCallEndpoint] = useState('');
  const [callPage, setCallPage] = useState(0);

  const [alerts, setAlerts] = useState<ApiCallMonitorAlertPage | null>(null);
  const [alertStatus, setAlertStatus] = useState('');
  const [alertPage, setAlertPage] = useState(0);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  // ================================================================ 读取

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, opts, ov, tr, aps, eps, slow] = await Promise.all([
        fetchCapabilities(),
        fetchOptions(),
        fetchOverview(windowSeconds),
        fetchTrend(windowSeconds, windowSeconds >= 3600 ? 'HOUR' : 'MINUTE'),
        fetchApps(windowSeconds),
        fetchEndpoints(windowSeconds),
        fetchSlowCalls(windowSeconds, 20),
      ]);
      setCapabilities(caps);
      setOptions(opts);
      setOverview(ov);
      setTrend(tr);
      setApps(aps);
      setEndpoints(eps);
      setSlowCalls(slow);
    } catch (e) {
      setError(e instanceof Error ? e.message : '调用统计暂时读不到，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [windowSeconds]);

  const loadCalls = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setCalls(
        await fetchCalls({
          windowSeconds,
          outcome: callOutcome || undefined,
          endpointCode: callEndpoint || undefined,
          page: callPage,
          size: 20,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '调用明细暂时读不到，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [windowSeconds, callOutcome, callEndpoint, callPage]);

  const loadAlerts = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setAlerts(await fetchAlerts({ status: alertStatus || undefined, page: alertPage, size: 20 }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '告警列表暂时读不到，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [alertStatus, alertPage]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (tab === 'calls') void loadCalls();
  }, [tab, loadCalls]);

  useEffect(() => {
    if (tab === 'alerts') void loadAlerts();
  }, [tab, loadAlerts]);

  // ================================================================ 动作

  const scan = useCallback(async () => {
    setBusy(true);
    setNotice('');
    setError('');
    try {
      const result = await runScan(windowSeconds);
      setNotice(
        `扫完 ${result.appsScanned} 个应用 / ${result.endpointsScanned} 个接口维度：` +
          `新开 ${result.alertsRaised} 条、刷新 ${result.alertsRefreshed} 条、自动闭环 ${result.alertsResolved} 条。`,
      );
      await loadAlerts();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '扫描失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }, [windowSeconds, loadAlerts, load]);

  const act = useCallback(
    async (alert: ApiCallMonitorAlertRow, action: 'acknowledge' | 'resolve') => {
      setBusy(true);
      setNotice('');
      setError('');
      try {
        const row =
          action === 'acknowledge'
            ? await acknowledgeAlert(alert.id)
            : await resolveAlert(alert.id);
        setNotice(
          `${alertTypeLabel(row.alertType)} —— 已${action === 'acknowledge' ? '确认' : '闭环'}。`,
        );
        await loadAlerts();
      } catch (e) {
        setError(e instanceof Error ? e.message : '操作失败，请稍后重试');
      } finally {
        setBusy(false);
      }
    },
    [loadAlerts],
  );

  // ================================================================ 派生

  const unavailable = useMemo(() => unavailableCapabilities(capabilities), [capabilities]);
  const thresholds = useMemo(() => thresholdText(options), [options]);
  const specMissing = useMemo(
    () => (options?.endpoints ?? []).filter((e) => !e.specAvailable),
    [options],
  );

  const windowPicker = (
    <select
      className="border-control-border bg-card h-9 rounded-md border px-2 text-sm"
      value={windowSeconds}
      onChange={(e) => {
        setWindowSeconds(Number(e.target.value));
        setCallPage(0);
        setAlertPage(0);
      }}
      aria-label="统计窗口"
    >
      {WINDOWS.map((seconds) => (
        <option key={seconds} value={seconds}>
          {formatWindow(seconds)}
        </option>
      ))}
    </select>
  );

  return (
    <div className="space-y-4">
      <PageHeader
        title="调用监控"
        description="监控对外 API 调用：QPS、错误率、限流命中、慢调用，按应用与接口维度分析，异常告警。"
        actions={
          <>
            {windowPicker}
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="h-4 w-4" />
              )}
              刷新
            </Button>
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="读不到">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="操作结果">
          {notice}
        </Notice>
      )}

      {/* 🔴 不可用的下游必须显式摆出来 —— 混进空态等于告诉运营「告警已经推给工作台了」。 */}
      {unavailable.length > 0 && (
        <Notice tone="warning" title={`${unavailable.length} 项能力当前不可用`}>
          <ul className="list-disc space-y-1 pl-4">
            {unavailable.map((c) => (
              <li key={c.name}>
                <span className="font-medium">{c.name}</span>
                {c.ownerModule ? `（归 ${c.ownerModule}）` : ''} —— {c.basis}
                {c.impact ? `。${c.impact}` : ''}
              </li>
            ))}
          </ul>
        </Notice>
      )}

      <Segmented<Tab>
        value={tab}
        onChange={setTab}
        options={[
          { value: 'overview', label: '概览与维度' },
          { value: 'calls', label: '调用明细' },
          { value: 'slow', label: '慢调用' },
          { value: 'alerts', label: '告警' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {/* ------------------------------------------------------ 概览与维度 */}
      {tab === 'overview' && (
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <StatCard
              label="调用总数"
              value={overview?.totalCalls ?? 0}
              unit="次"
              icon={<Activity className="h-4 w-4" />}
            />
            <StatCard
              label="QPS"
              value={Number((overview?.qps ?? 0).toFixed(4))}
              unit="次/秒"
              icon={<Gauge className="h-4 w-4" />}
            />
            <StatCard
              label="错误率"
              value={formatRate(overview?.errorRate)}
              icon={<CircleAlert className="h-4 w-4" />}
            />
            <StatCard
              label="限流命中"
              value={overview?.rateLimitedCalls ?? 0}
              unit="次"
              icon={<ShieldAlert className="h-4 w-4" />}
            />
          </div>
          <div className="grid gap-3 md:grid-cols-4">
            <StatCard
              label="慢调用"
              value={overview?.slowCalls ?? 0}
              unit="次"
              icon={<Timer className="h-4 w-4" />}
            />
            <StatCard label="p95 耗时" value={formatDuration(overview?.p95DurationMs)} />
            <StatCard label="平均耗时" value={formatDuration(overview?.avgDurationMs)} />
            <StatCard
              label="未闭环告警"
              value={overview?.openAlertCount ?? 0}
              unit="条"
              icon={<TriangleAlert className="h-4 w-4" />}
            />
          </div>

          <SectionCard
            title="趋势"
            description={`按${trend?.bucket === 'HOUR' ? '小时' : '分钟'}分桶，窗口 ${formatWindow(windowSeconds)}。`}
          >
            {!trend || trend.points.length === 0 ? (
              <EmptyState
                icon={<Activity className="h-6 w-6" />}
                title="这个窗口还没有调用"
                description="埋点来自网关入口 /open/v1/**：外部系统真的调过一次，这里才会有数据。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3 font-medium">时间</th>
                      <th className="py-2 pr-3 font-medium">调用</th>
                      <th className="py-2 pr-3 font-medium">错误</th>
                      <th className="py-2 pr-3 font-medium">错误率</th>
                      <th className="py-2 pr-3 font-medium">限流命中</th>
                      <th className="py-2 pr-3 font-medium">QPS</th>
                      <th className="py-2 pr-3 font-medium">平均耗时</th>
                      <th className="py-2 font-medium">p95 耗时</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trend.points.map((point) => (
                      <tr key={point.bucket} className="border-border/60 border-b">
                        <td className="whitespace-nowrap py-2 pr-3">
                          {point.bucket.replace('T', ' ')}
                        </td>
                        <td className="py-2 pr-3">{point.calls}</td>
                        <td className="py-2 pr-3">{point.errors}</td>
                        <td className="py-2 pr-3">{formatRate(point.errorRate)}</td>
                        <td className="py-2 pr-3">{point.rateLimitedCalls}</td>
                        <td className="py-2 pr-3">{Number(point.qps.toFixed(4))}</td>
                        <td className="py-2 pr-3">{formatDuration(point.avgDurationMs)}</td>
                        <td className="py-2">{formatDuration(point.p95DurationMs)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="按应用维度"
            description="每个应用在窗口内的调用量、错误率、限流命中与耗时。应用维度是真的（埋点带 app_id）。"
          >
            {apps.length === 0 ? (
              <EmptyState
                icon={<Activity className="h-6 w-6" />}
                title="这个窗口没有调用"
                description="换一个更长的窗口看看，或者确认外部系统真的调过网关。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3 font-medium">应用</th>
                      <th className="py-2 pr-3 font-medium">调用</th>
                      <th className="py-2 pr-3 font-medium">错误</th>
                      <th className="py-2 pr-3 font-medium">错误率</th>
                      <th className="py-2 pr-3 font-medium">限流命中</th>
                      <th className="py-2 pr-3 font-medium">慢调用</th>
                      <th className="py-2 pr-3 font-medium">QPS</th>
                      <th className="py-2 pr-3 font-medium">平均耗时</th>
                      <th className="py-2 font-medium">p95 耗时</th>
                    </tr>
                  </thead>
                  <tbody>
                    {apps.map((row) => (
                      <tr key={row.appId} className="border-border/60 border-b">
                        <td className="py-2 pr-3">
                          <div className="font-medium">{row.appName}</div>
                          <div className="text-muted-foreground text-xs">{row.appCode}</div>
                        </td>
                        <td className="py-2 pr-3">{row.totalCalls}</td>
                        <td className="py-2 pr-3">{row.errorCalls}</td>
                        <td className="py-2 pr-3">{formatRate(row.errorRate)}</td>
                        <td className="py-2 pr-3">{row.rateLimitedCalls}</td>
                        <td className="py-2 pr-3">{row.slowCalls}</td>
                        <td className="py-2 pr-3">{Number(row.qps.toFixed(4))}</td>
                        <td className="py-2 pr-3">{formatDuration(row.avgDurationMs)}</td>
                        <td className="py-2">{formatDuration(row.p95DurationMs)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="按接口维度"
            description="按端点聚合。端点名称取自 platform 服务活着的 OpenAPI 契约；契约读不到时如实标出来。"
          >
            {endpoints.length === 0 ? (
              <EmptyState
                icon={<ScrollText className="h-6 w-6" />}
                title="这个窗口没有调用"
                description="换一个更长的窗口看看。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3 font-medium">接口</th>
                      <th className="py-2 pr-3 font-medium">调用</th>
                      <th className="py-2 pr-3 font-medium">错误</th>
                      <th className="py-2 pr-3 font-medium">错误率</th>
                      <th className="py-2 pr-3 font-medium">限流命中</th>
                      <th className="py-2 pr-3 font-medium">慢调用</th>
                      <th className="py-2 pr-3 font-medium">QPS</th>
                      <th className="py-2 font-medium">p95 耗时</th>
                    </tr>
                  </thead>
                  <tbody>
                    {endpoints.map((row) => (
                      <tr key={row.endpointCode} className="border-border/60 border-b">
                        <td className="py-2 pr-3">
                          <div className="font-medium">{row.label}</div>
                          <div className="text-muted-foreground text-xs">
                            {row.httpMethod} {row.requestPath}
                          </div>
                        </td>
                        <td className="py-2 pr-3">{row.totalCalls}</td>
                        <td className="py-2 pr-3">{row.errorCalls}</td>
                        <td className="py-2 pr-3">{formatRate(row.errorRate)}</td>
                        <td className="py-2 pr-3">{row.rateLimitedCalls}</td>
                        <td className="py-2 pr-3">{row.slowCalls}</td>
                        <td className="py-2 pr-3">{Number(row.qps.toFixed(4))}</td>
                        <td className="py-2">{formatDuration(row.p95DurationMs)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>
        </div>
      )}

      {/* ------------------------------------------------------ 调用明细 */}
      {tab === 'calls' && (
        <SectionCard
          title="调用明细"
          description="每一次调用一行：状态码、结论码、细分错误码、耗时与请求号。请求号就是对接方报障时能对上的那把钥匙。"
          extra={
            <div className="flex flex-wrap items-center gap-2">
              <select
                className="border-control-border bg-card h-9 rounded-md border px-2 text-sm"
                value={callOutcome}
                onChange={(e) => {
                  setCallOutcome(e.target.value);
                  setCallPage(0);
                }}
                aria-label="结论码"
              >
                <option value="">全部结论</option>
                {['SUCCESS', 'AUTH_FAILED', 'FORBIDDEN', 'RATE_LIMITED', 'NOT_FOUND', 'ERROR'].map(
                  (code) => (
                    <option key={code} value={code}>
                      {outcomeLabel(code)}
                    </option>
                  ),
                )}
              </select>
              <Input
                className="h-9 w-44"
                placeholder="按端点码筛选"
                value={callEndpoint}
                onChange={(e) => {
                  setCallEndpoint(e.target.value);
                  setCallPage(0);
                }}
              />
            </div>
          }
        >
          {!calls || calls.items.length === 0 ? (
            <EmptyState
              icon={<ScrollText className="h-6 w-6" />}
              title={callOutcome || callEndpoint ? '没有匹配的调用' : '这个窗口没有调用'}
              description={
                callOutcome || callEndpoint
                  ? '换个筛选条件看看，或者把窗口拉长。'
                  : '埋点来自网关入口 /open/v1/**：外部系统真的调过一次，这里才会有数据。'
              }
            />
          ) : (
            <>
              <div className="text-muted-foreground mb-3 text-xs">
                共 {calls.total} 次调用，其中错误 {calls.errorCalls} 次、限流命中{' '}
                {calls.rateLimitedCalls} 次（第 {calls.page + 1} 页）
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3 font-medium">时刻</th>
                      <th className="py-2 pr-3 font-medium">应用</th>
                      <th className="py-2 pr-3 font-medium">接口</th>
                      <th className="py-2 pr-3 font-medium">状态码</th>
                      <th className="py-2 pr-3 font-medium">结论</th>
                      <th className="py-2 pr-3 font-medium">错误码</th>
                      <th className="py-2 pr-3 font-medium">耗时</th>
                      <th className="py-2 pr-3 font-medium">来源 IP</th>
                      <th className="py-2 font-medium">请求号</th>
                    </tr>
                  </thead>
                  <tbody>
                    {calls.items.map((row) => (
                      <tr key={row.id} className="border-border/60 border-b">
                        <td className="whitespace-nowrap py-2 pr-3">
                          {row.calledAt.replace('T', ' ').slice(0, 19)}
                        </td>
                        <td className="py-2 pr-3">{row.appCode}</td>
                        <td className="py-2 pr-3">
                          {row.httpMethod} {row.requestPath}
                        </td>
                        <td className="py-2 pr-3">{row.statusCode}</td>
                        <td className="py-2 pr-3">
                          <Badge tone={BADGE_TONE[outcomeTone(row.outcome)]}>
                            {row.outcomeLabel || outcomeLabel(row.outcome)}
                          </Badge>
                        </td>
                        <td className="py-2 pr-3">{errorCodeLabel(row.errorCode)}</td>
                        <td className="py-2 pr-3">{formatDuration(row.durationMs)}</td>
                        <td className="py-2 pr-3">{row.clientIp || '—'}</td>
                        <td className="py-2 font-mono text-xs">{row.requestId || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="mt-3 flex items-center justify-end gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={calls.page <= 0}
                  onClick={() => setCallPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-xs">第 {calls.page + 1} 页</span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(calls.page + 1) * calls.size >= calls.total}
                  onClick={() => setCallPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </>
          )}
        </SectionCard>
      )}

      {/* ------------------------------------------------------ 慢调用 */}
      {tab === 'slow' && (
        <SectionCard
          title="慢调用 Top 20"
          description="耗时超过慢调用线的调用，按耗时倒序。慢调用线是平台固定口径，在「能力声明」页里写明。"
        >
          {slowCalls.length === 0 ? (
            <EmptyState
              icon={<Timer className="h-6 w-6" />}
              title="这个窗口没有慢调用"
              description="换个更长的窗口看看，或者确认这个应用确实在调网关。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2 pr-3 font-medium">时刻</th>
                    <th className="py-2 pr-3 font-medium">应用</th>
                    <th className="py-2 pr-3 font-medium">接口</th>
                    <th className="py-2 pr-3 font-medium">状态码</th>
                    <th className="py-2 pr-3 font-medium">结论</th>
                    <th className="py-2 pr-3 font-medium">耗时</th>
                    <th className="py-2 font-medium">来源 IP</th>
                  </tr>
                </thead>
                <tbody>
                  {slowCalls.map((row) => (
                    <tr key={row.id} className="border-border/60 border-b">
                      <td className="whitespace-nowrap py-2 pr-3">
                        {row.calledAt.replace('T', ' ').slice(0, 19)}
                      </td>
                      <td className="py-2 pr-3">{row.appCode}</td>
                      <td className="py-2 pr-3">
                        {row.httpMethod} {row.requestPath}
                      </td>
                      <td className="py-2 pr-3">{row.statusCode}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={BADGE_TONE[outcomeTone(row.outcome)]}>
                          {outcomeLabel(row.outcome)}
                        </Badge>
                      </td>
                      <td className="py-2 pr-3">{formatDuration(row.durationMs)}</td>
                      <td className="py-2">{row.clientIp || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      )}

      {/* ------------------------------------------------------ 告警 */}
      {tab === 'alerts' && (
        <SectionCard
          title="告警"
          description="未闭环期间同一异常只一行（不刷屏）；指标回落后自动闭环。限流类告警直接指向 18.1.1.1 的配额调整。"
          extra={
            <div className="flex flex-wrap items-center gap-2">
              <select
                className="border-control-border bg-card h-9 rounded-md border px-2 text-sm"
                value={alertStatus}
                onChange={(e) => {
                  setAlertStatus(e.target.value);
                  setAlertPage(0);
                }}
                aria-label="告警状态"
              >
                <option value="">全部状态</option>
                {['OPEN', 'ACKNOWLEDGED', 'RESOLVED'].map((code) => (
                  <option key={code} value={code}>
                    {alertStatusLabel(code)}
                  </option>
                ))}
              </select>
              <Button size="sm" onClick={() => void scan()} disabled={busy}>
                {busy ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <TriangleAlert className="h-4 w-4" />
                )}
                扫描并开告警
              </Button>
            </div>
          }
        >
          {!alerts || alerts.items.length === 0 ? (
            <EmptyState
              icon={<TriangleAlert className="h-6 w-6" />}
              title={alertStatus ? '这个状态下没有告警' : '当前没有告警'}
              description="点「扫描并开告警」按平台固定口径判定一遍；口径在「能力声明」页里写明。"
            />
          ) : (
            <>
              <div className="text-muted-foreground mb-3 text-xs">
                共 {alerts.total} 条：待处理 {alerts.openCount}、已确认 {alerts.acknowledgedCount}
                、已闭环 {alerts.resolvedCount}（第 {alerts.page + 1} 页）
              </div>
              <div className="space-y-3">
                {alerts.items.map((alert) => (
                  <div key={alert.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={BADGE_TONE[severityTone(alert.severity)]}>
                        {alert.severity}
                      </Badge>
                      <Badge tone={BADGE_TONE[alertStatusTone(alert.status)]}>
                        {alertStatusLabel(alert.status)}
                      </Badge>
                      <span className="text-sm font-medium">{alertTypeLabel(alert.alertType)}</span>
                      <span className="text-muted-foreground text-xs">
                        {alert.appCode}
                        {alert.endpointCode ? ` · ${alert.endpointCode}` : ' · 应用级'}
                      </span>
                    </div>
                    <p className="mt-2 text-sm">{alert.message}</p>
                    {alert.suggestedAction && (
                      <p className="text-muted-foreground mt-1 text-xs">
                        建议：{alert.suggestedAction}
                      </p>
                    )}
                    {quotaTargetHint(alert) && (
                      <p className="text-muted-foreground mt-1 text-xs">{quotaTargetHint(alert)}</p>
                    )}
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      {alert.status === 'OPEN' && (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void act(alert, 'acknowledge')}
                        >
                          确认
                        </Button>
                      )}
                      {alert.status !== 'RESOLVED' && (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void act(alert, 'resolve')}
                        >
                          闭环
                        </Button>
                      )}
                      <span className="text-muted-foreground text-xs">
                        指标 {alert.metricValue} / 阈值 {alert.thresholdValue} · 窗口{' '}
                        {formatWindow(alert.windowSeconds)}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
              <div className="mt-3 flex items-center justify-end gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={alerts.page <= 0}
                  onClick={() => setAlertPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-xs">第 {alerts.page + 1} 页</span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(alerts.page + 1) * alerts.size >= alerts.total}
                  onClick={() => setAlertPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </>
          )}
        </SectionCard>
      )}

      {/* ------------------------------------------------------ 能力声明 */}
      {tab === 'capabilities' && (
        <div className="space-y-4">
          <SectionCard
            title="阈值口径"
            description="告警是平台固定口径判的（不替用户预置任何一行配置），但口径必须写在明处 —— 运营看到告警要知道是按什么判的。"
          >
            <ul className="list-disc space-y-1 pl-4 text-sm">
              {thresholds.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          </SectionCard>

          <SectionCard
            title="网关端点清单"
            description="这就是网关放行的全部（封闭、逐条可数），也是监控的「接口维度」词表。名称取自 platform 服务活着的 OpenAPI 契约。"
          >
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2 pr-3 font-medium">端点码</th>
                    <th className="py-2 pr-3 font-medium">方法</th>
                    <th className="py-2 pr-3 font-medium">路径</th>
                    <th className="py-2 pr-3 font-medium">名称</th>
                    <th className="py-2 pr-3 font-medium">所需授权</th>
                    <th className="py-2 font-medium">契约</th>
                  </tr>
                </thead>
                <tbody>
                  {(options?.endpoints ?? []).map((endpoint) => (
                    <tr key={endpoint.code} className="border-border/60 border-b">
                      <td className="py-2 pr-3 font-mono text-xs">{endpoint.code}</td>
                      <td className="py-2 pr-3">{endpoint.method}</td>
                      <td className="py-2 pr-3 font-mono text-xs">{endpoint.path}</td>
                      <td className="py-2 pr-3">
                        <div>{endpoint.label}</div>
                        <div className="text-muted-foreground text-xs">{endpoint.description}</div>
                      </td>
                      <td className="py-2 pr-3 text-xs">
                        {endpoint.resourceCode} · {endpoint.actionCode}
                      </td>
                      <td className="py-2 text-xs">
                        {endpoint.specAvailable ? (
                          <Badge tone="success">活契约里有</Badge>
                        ) : (
                          <Badge tone="warning" title={endpoint.specBasis}>
                            契约里没有
                          </Badge>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {/* 🔴 读不到 ≠ 没有：契约读不到的那几格要写明原因。 */}
            {specMissing.length > 0 && (
              <div className="mt-3">
                <Notice tone="warning" title={`${specMissing.length} 个端点在活契约里没有`}>
                  <ul className="list-disc space-y-1 pl-4">
                    {specMissing.map((endpoint) => (
                      <li key={endpoint.code}>
                        {endpoint.code} —— {endpoint.specBasis}
                      </li>
                    ))}
                  </ul>
                </Notice>
              </div>
            )}
          </SectionCard>

          <SectionCard title="能力声明" description="逐维说清「有没有、依据是什么、缺了归谁」。">
            <div className="space-y-3">
              {(capabilities?.capabilities ?? []).map((capability) => (
                <div key={capability.name} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={capability.available ? 'success' : 'warning'}>
                      {capability.available ? '已接通' : '不可用'}
                    </Badge>
                    <span className="text-sm font-medium">{capability.name}</span>
                    {capability.ownerModule && (
                      <span className="text-muted-foreground text-xs">
                        归 {capability.ownerModule}
                      </span>
                    )}
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">依据：{capability.basis}</p>
                  {capability.impact && (
                    <p className="text-muted-foreground mt-1 text-xs">影响：{capability.impact}</p>
                  )}
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard title="下游缺口" description="谁没交付、归谁。">
            {(capabilities?.downstreamGaps ?? []).length === 0 ? (
              <EmptyState title="没有下游缺口" />
            ) : (
              <div className="space-y-2">
                {(capabilities?.downstreamGaps ?? []).map((gap) => (
                  <div
                    key={`${gap.module}-${gap.name}`}
                    className="border-border rounded-md border p-3"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={gap.available ? 'success' : 'warning'}>
                        {gap.available ? '已接通' : '未交付'}
                      </Badge>
                      <span className="text-sm font-medium">{gap.name}</span>
                      <span className="text-muted-foreground text-xs">
                        {gap.module} · {gap.group}
                      </span>
                    </div>
                    <p className="text-muted-foreground mt-1 text-xs">{gap.detail}</p>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>
        </div>
      )}
    </div>
  );
}
