'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  ClipboardList,
  Loader2,
  RefreshCw,
  Search,
  Stethoscope,
  TriangleAlert,
} from 'lucide-react';
import {
  ANOMALY_TYPES,
  SUBJECT_TYPES,
  conclusionRows,
  diagnoseEvent,
  evidenceRows,
  executionBoundaryNotice,
  fetchCapabilities,
  fetchEventDetail,
  fetchEvents,
  fetchOverview,
  fetchRunDetail,
  formatTime,
  formatValue,
  resolveEvent,
  runReadState,
  severityLabel,
  severityTone,
  sourceLabel,
  statusLabel,
  statusTone,
  submitEvent,
  syncInternalFailures,
  unavailableNote,
  type Tone,
} from './ai-diagnosis-model';
import type {
  AiDiagnosisCapabilities,
  AiDiagnosisEventDetail,
  AiDiagnosisEventRow,
  AiDiagnosisOverview,
  AiDiagnosisRunDetail,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.4.2.3「异常诊断」工作区（卷 18 前端入口 `/ai/diagnosis`）。
 *
 * 蓝图：「**AI 异常诊断**：对库存异常、利润异常、发货延迟、同步失败、广告亏损等给出
 * 可能原因分析（自动关联相关数据）与处置建议。输入：异常事件 + 关联数据（订单、库存、
 * 成本、历史记录）。输出：诊断结论 + 依据 + 建议动作。关系：上游 = 1 工作台待办、
 * 各域异常；下游 = 对应处理页。**诊断要给依据**（"因为 SKU X 的头程成本上涨 18%"），
 * 不能只给结论。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **依据摊开在结论下面**，不是藏在「详情」里。每条依据显示事实表 / 字段 / 数值 /
 *    快照时间 / 下钻引用 / 口径说明 —— 这是「凭什么这么说」的可下钻答案。
 * 2. **「读不到」与「没有结论」分开**：一次运行读不到某一格上游时，页面明确写
 *    「本次诊断有读不到的事实源」，不让人把「没查出来」读成「没问题」。
 * 3. **未接通的上游如实显示**（能力声明页），并写明缺在哪个模块，不显示成「暂无」。
 * 4. **仅诊断，不执行**：处置建议指向处理页并点名归谁，本页不替任何域改数据。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「筛选无结果」与「还没有异常事件」。
 * 6. **人工登记与内部失败源两条来源都可见**（来源列 + 同步按钮），
 *    幂等同步不刷屏。
 */

type Tab = 'events' | 'capabilities';

const PAGE_SIZE = 10;

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const TABS: { value: Tab; label: string }[] = [
  { value: 'events', label: '异常事件' },
  { value: 'capabilities', label: '能力声明' },
];

export function AiDiagnosisWorkspace() {
  const [tab, setTab] = useState<Tab>('events');

  const [capabilities, setCapabilities] = useState<AiDiagnosisCapabilities | null>(null);
  const [overview, setOverview] = useState<AiDiagnosisOverview | null>(null);

  const [events, setEvents] = useState<AiDiagnosisEventRow[]>([]);
  const [eventTotal, setEventTotal] = useState(0);
  const [eventPage, setEventPage] = useState(0);
  const [eventTotalPages, setEventTotalPages] = useState(0);
  const [anomalyFilter, setAnomalyFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  const [detail, setDetail] = useState<AiDiagnosisEventDetail | null>(null);
  const [runDetail, setRunDetail] = useState<AiDiagnosisRunDetail | null>(null);

  const [form, setForm] = useState({
    anomalyType: 'INVENTORY',
    subjectType: 'SKU',
    subjectRef: '',
    subjectLabel: '',
    severity: 'WARNING',
    reportedValue: '',
    reportedUnit: '',
    note: '',
  });

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    setError(e instanceof Error ? e.message : '请求失败，请稍后重试');
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, page] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchEvents({
          anomalyType: anomalyFilter || undefined,
          status: statusFilter || undefined,
          keyword: keyword || undefined,
          page: eventPage,
          size: PAGE_SIZE,
        }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setEvents(page.items ?? []);
      setEventTotal(page.total ?? 0);
      setEventPage(page.page ?? 0);
      setEventTotalPages(page.totalPages ?? 0);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [anomalyFilter, statusFilter, keyword, eventPage]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开事件详情：事件 + 历史运行，并自动带上最近一次运行的结论与依据。 */
  const openDetail = useCallback(async (id: string) => {
    setBusy(true);
    setError('');
    try {
      const loaded = await fetchEventDetail(id);
      setDetail(loaded);
      const latest = loaded.runs?.[0];
      setRunDetail(latest ? await fetchRunDetail(latest.id) : null);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }, []);

  const onDiagnose = useCallback(
    async (id: string) => {
      setBusy(true);
      setError('');
      setNotice('');
      try {
        const result = await diagnoseEvent(id);
        setRunDetail(result);
        const loaded = await fetchEventDetail(id);
        setDetail(loaded);
        setNotice(
          `诊断完成：本次运行 ${result.run.ruleVersion} 口径产出 ${result.conclusions.length} 条结论、${
            result.run.evidenceCount
          } 条依据。`,
        );
        await load();
      } catch (e) {
        fail(e);
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const onResolve = useCallback(
    async (id: string) => {
      const note = window.prompt('处置说明（必填）：这笔异常是怎么处理的？');
      if (note === null) return;
      if (!note.trim()) {
        setError('处置说明不能为空 —— 不然「为什么关掉」无从追问。');
        return;
      }
      setBusy(true);
      setError('');
      setNotice('');
      try {
        await resolveEvent(id, note.trim());
        setNotice('已标记为「已处置」。本模块只记录人的决定，不会替你执行任何下游动作。');
        await load();
        if (detail?.event.id === id) await openDetail(id);
      } catch (e) {
        fail(e);
      } finally {
        setBusy(false);
      }
    },
    [detail, load, openDetail],
  );

  const onSubmit = useCallback(async () => {
    if (!form.subjectRef.trim()) {
      setError('异常对象不能为空 —— 诊断要拿它去对事实（SKU 码 / 订单号 / 活动 id …）。');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const created = await submitEvent({
        anomalyType: form.anomalyType,
        subjectType: form.subjectType,
        subjectRef: form.subjectRef.trim(),
        subjectLabel: form.subjectLabel.trim(),
        occurredAt: new Date().toISOString(),
        reportedValue: form.reportedValue ? Number(form.reportedValue) : null,
        reportedUnit: form.reportedUnit.trim(),
        severity: form.severity,
        note: form.note.trim(),
      });
      setNotice(`异常事件已登记：${created.eventNo}`);
      setForm((f) => ({ ...f, subjectRef: '', subjectLabel: '', reportedValue: '', note: '' }));
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }, [form, load]);

  const onSync = useCallback(async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await syncInternalFailures();
      setNotice(
        `内部失败源同步完成：扫了 ${result.scanned} 条事实，新登记 ${result.created} 条异常事件，` +
          `${result.skipped} 条因已登记过而跳过（幂等，不刷屏）。`,
      );
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }, [load]);

  const readState = useMemo(
    () => runReadState(runDetail?.run ?? null, runDetail?.conclusions.length ?? 0),
    [runDetail],
  );

  /**
   * 结论 + 依据的**一次性**视图。
   *
   * 🔴 依据在同一条结论下**摊开成表**（事实表 / 字段 / 数值 / 下钻引用 / 快照时间）
   *    与**口径说明**两行 —— 这是「诊断要给依据」的落点。
   *    这里一次算好（不重复 find + 重复摊开），避免同一条依据被渲染两遍。
   */
  const conclusionViews = useMemo(() => {
    if (!runDetail) return [];
    return conclusionRows(runDetail.conclusions).map((row) => {
      const source = runDetail.conclusions.find((c) => c.conclusion.id === row.id);
      return { ...row, evidences: evidenceRows(source?.evidences ?? []) };
    });
  }, [runDetail]);

  const hasEvents = eventTotal > 0;
  const filtered = Boolean(anomalyFilter || statusFilter || keyword);

  return (
    <div className="space-y-4">
      <PageHeader
        title="异常诊断"
        description="对库存异常、利润异常、发货延迟、同步失败、广告亏损给出可能原因分析与处置建议。每条结论都带可下钻的依据（事实表、字段、数值、快照时间）。仅诊断，不执行处置动作。"
        actions={
          <div className="flex items-center gap-2">
            <Button onClick={() => void onSync()} disabled={busy || loading}>
              {busy ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <ClipboardList className="h-4 w-4" />
              )}
              同步内部失败源
            </Button>
            <Button onClick={() => void load()} disabled={loading}>
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="h-4 w-4" />
              )}
              刷新
            </Button>
          </div>
        }
      />

      {error && (
        <div className="text-destructive flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {notice && (
        <div className="text-muted-foreground flex items-start gap-2 rounded-md border p-3 text-sm">
          <Stethoscope className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="异常事件" value={overview.events} unit="条" polarity="neutral" />
          <StatCard label="待诊断" value={overview.openEvents} unit="条" polarity="negative" />
          <StatCard label="诊断运行" value={overview.runs} unit="次" polarity="neutral" />
          <StatCard label="归因依据" value={overview.evidences} unit="条" polarity="neutral" />
        </div>
      )}

      <SectionCard
        title="异常事件"
        description="事件来源有两条：人工登记，以及从平台内部失败源（死信 / 永久失败 / 限流 / 鉴权失败 / 规则执行失败）聚合。"
        extra={
          <Segmented
            value={tab}
            onChange={(value) => setTab(value as Tab)}
            options={TABS}
            aria-label="视图切换"
          />
        }
      >
        {tab === 'events' ? (
          <div className="space-y-4">
            {/* ---------------- 筛选 ---------------- */}
            <div className="flex flex-wrap items-end gap-2">
              <div className="space-y-1">
                <div className="text-copy-12 text-muted-foreground">异常类别</div>
                <select
                  className="border-input bg-background h-9 rounded-md border px-3 text-sm"
                  value={anomalyFilter}
                  onChange={(event) => {
                    setAnomalyFilter(event.target.value);
                    setEventPage(0);
                  }}
                >
                  <option value="">全部</option>
                  {ANOMALY_TYPES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="space-y-1">
                <div className="text-copy-12 text-muted-foreground">状态</div>
                <select
                  className="border-input bg-background h-9 rounded-md border px-3 text-sm"
                  value={statusFilter}
                  onChange={(event) => {
                    setStatusFilter(event.target.value);
                    setEventPage(0);
                  }}
                >
                  <option value="">全部</option>
                  <option value="OPEN">待诊断</option>
                  <option value="DIAGNOSED">已诊断</option>
                  <option value="RESOLVED">已处置</option>
                </select>
              </div>
              <div className="min-w-56 flex-1 space-y-1">
                <div className="text-copy-12 text-muted-foreground">关键词（单号 / 对象）</div>
                <Input
                  value={keyword}
                  placeholder="按单号或异常对象搜索"
                  onChange={(event) => {
                    setKeyword(event.target.value);
                    setEventPage(0);
                  }}
                />
              </div>
              <Button
                variant="outline"
                onClick={() => {
                  setAnomalyFilter('');
                  setStatusFilter('');
                  setKeyword('');
                  setEventPage(0);
                }}
                disabled={!filtered}
              >
                <Search className="h-4 w-4" />
                清除筛选
              </Button>
            </div>

            {/* ---------------- 人工登记 ---------------- */}
            <div className="bg-muted/40 space-y-3 rounded-md border p-4">
              <div className="text-copy-14 text-foreground font-medium">登记异常事件</div>
              <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
                <div className="space-y-1">
                  <div className="text-copy-12 text-muted-foreground">异常类别</div>
                  <select
                    className="border-input bg-background h-9 w-full rounded-md border px-3 text-sm"
                    value={form.anomalyType}
                    onChange={(event) =>
                      setForm((f) => ({ ...f, anomalyType: event.target.value }))
                    }
                  >
                    {ANOMALY_TYPES.map((item) => (
                      <option key={item.value} value={item.value}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="space-y-1">
                  <div className="text-copy-12 text-muted-foreground">对象类型</div>
                  <select
                    className="border-input bg-background h-9 w-full rounded-md border px-3 text-sm"
                    value={form.subjectType}
                    onChange={(event) =>
                      setForm((f) => ({ ...f, subjectType: event.target.value }))
                    }
                  >
                    {SUBJECT_TYPES.map((item) => (
                      <option key={item.value} value={item.value}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="space-y-1">
                  <div className="text-copy-12 text-muted-foreground">
                    异常对象（SKU 码 / 订单号…）
                  </div>
                  <Input
                    value={form.subjectRef}
                    placeholder="必填"
                    onChange={(event) => setForm((f) => ({ ...f, subjectRef: event.target.value }))}
                  />
                </div>
                <div className="space-y-1">
                  <div className="text-copy-12 text-muted-foreground">对象名称</div>
                  <Input
                    value={form.subjectLabel}
                    onChange={(event) =>
                      setForm((f) => ({ ...f, subjectLabel: event.target.value }))
                    }
                  />
                </div>
                <div className="space-y-1">
                  <div className="text-copy-12 text-muted-foreground">严重度</div>
                  <select
                    className="border-input bg-background h-9 w-full rounded-md border px-3 text-sm"
                    value={form.severity}
                    onChange={(event) => setForm((f) => ({ ...f, severity: event.target.value }))}
                  >
                    <option value="INFO">提示</option>
                    <option value="WARNING">警告</option>
                    <option value="CRITICAL">严重</option>
                  </select>
                </div>
                <div className="space-y-1">
                  <div className="text-copy-12 text-muted-foreground">度量值（可选）</div>
                  <div className="flex gap-2">
                    <Input
                      value={form.reportedValue}
                      placeholder="如 12.5"
                      onChange={(event) =>
                        setForm((f) => ({ ...f, reportedValue: event.target.value }))
                      }
                    />
                    <Input
                      className="w-24"
                      value={form.reportedUnit}
                      placeholder="单位"
                      onChange={(event) =>
                        setForm((f) => ({ ...f, reportedUnit: event.target.value }))
                      }
                    />
                  </div>
                </div>
              </div>
              <div className="flex flex-wrap items-end gap-2">
                <div className="min-w-64 flex-1 space-y-1">
                  <div className="text-copy-12 text-muted-foreground">备注</div>
                  <Input
                    value={form.note}
                    onChange={(event) => setForm((f) => ({ ...f, note: event.target.value }))}
                  />
                </div>
                <Button onClick={() => void onSubmit()} disabled={busy}>
                  {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                  登记
                </Button>
              </div>
            </div>

            {/* ---------------- 事件列表 ---------------- */}
            {loading ? (
              <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" />
                加载中…
              </div>
            ) : !hasEvents ? (
              <EmptyState
                icon={<ClipboardList className="h-7 w-7" />}
                title={filtered ? '当前筛选条件下没有异常事件' : '还没有任何异常事件'}
                description={
                  filtered
                    ? '换一个类别或状态再看看 —— 这是「筛掉了」，不是「系统里没有」。'
                    : '可以手工登记一条异常，或点右上角「同步内部失败源」把平台自己的失败事实聚过来。'
                }
              />
            ) : (
              <div className="space-y-3">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="text-muted-foreground text-copy-12">
                      <tr className="border-border border-b">
                        <th className="px-2 py-2 text-left font-medium">单号</th>
                        <th className="px-2 py-2 text-left font-medium">类别</th>
                        <th className="px-2 py-2 text-left font-medium">异常对象</th>
                        <th className="px-2 py-2 text-left font-medium">严重度</th>
                        <th className="px-2 py-2 text-left font-medium">状态</th>
                        <th className="px-2 py-2 text-left font-medium">来源</th>
                        <th className="px-2 py-2 text-left font-medium">发生时间</th>
                        <th className="px-2 py-2 text-right font-medium">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {events.map((row) => (
                        <tr key={row.id} className="border-border border-b">
                          <td className="px-2 py-2 font-mono text-xs">{row.eventNo}</td>
                          <td className="px-2 py-2">{row.anomalyTypeLabel}</td>
                          <td className="px-2 py-2">
                            <div>{row.subjectRef}</div>
                            {row.subjectLabel && (
                              <div className="text-muted-foreground text-copy-12">
                                {row.subjectLabel}
                              </div>
                            )}
                          </td>
                          <td className="px-2 py-2">
                            <Badge tone={BADGE_TONE[severityTone(row.severity)]}>
                              {severityLabel(row.severity)}
                            </Badge>
                          </td>
                          <td className="px-2 py-2">
                            <Badge tone={BADGE_TONE[statusTone(row.status)]}>
                              {statusLabel(row.status)}
                            </Badge>
                          </td>
                          <td className="px-2 py-2">{sourceLabel(row.source)}</td>
                          <td className="whitespace-nowrap px-2 py-2">
                            {formatTime(row.occurredAt)}
                          </td>
                          <td className="px-2 py-2">
                            <div className="flex items-center justify-end gap-2">
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => void openDetail(row.id)}
                                disabled={busy}
                              >
                                详情
                              </Button>
                              <Button
                                size="sm"
                                onClick={() => void onDiagnose(row.id)}
                                disabled={busy}
                              >
                                诊断
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => void onResolve(row.id)}
                                disabled={busy || row.status === 'RESOLVED'}
                              >
                                标记已处置
                              </Button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="text-muted-foreground text-copy-12 flex items-center justify-between">
                  <span>
                    共 {eventTotal} 条 · 第 {eventPage + 1} / {Math.max(eventTotalPages, 1)} 页
                  </span>
                  <span className="flex gap-2">
                    <Button
                      size="sm"
                      disabled={eventPage <= 0}
                      onClick={() => setEventPage((value) => Math.max(value - 1, 0))}
                    >
                      上一页
                    </Button>
                    <Button
                      size="sm"
                      disabled={eventPage + 1 >= eventTotalPages}
                      onClick={() => setEventPage((value) => value + 1)}
                    >
                      下一页
                    </Button>
                  </span>
                </div>
              </div>
            )}
          </div>
        ) : (
          <CapabilitiesView capabilities={capabilities} />
        )}
      </SectionCard>

      {/* ---------------- 事件详情 + 结论与依据 ---------------- */}
      {detail && (
        <SectionCard
          title={`事件详情 · ${detail.event.eventNo}`}
          description="结论 + 依据是本模块的产出主体 —— 依据可下钻到事实行，处置建议只指向处理页。"
          extra={
            <Button size="sm" onClick={() => setDetail(null)}>
              收起
            </Button>
          }
        >
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard label="异常类别" value={detail.event.anomalyTypeLabel} polarity="neutral" />
              <StatCard label="状态" value={statusLabel(detail.event.status)} polarity="neutral" />
              <StatCard label="来源" value={sourceLabel(detail.event.source)} polarity="neutral" />
              <StatCard label="历史运行" value={detail.runs.length} unit="次" polarity="neutral" />
            </div>

            <div className="text-muted-foreground text-copy-12">
              异常对象 {detail.event.subjectType} / {detail.event.subjectRef}
              {detail.event.subjectLabel ? `（${detail.event.subjectLabel}）` : ''} · 发生于{' '}
              {formatTime(detail.event.occurredAt)} · 发现于 {formatTime(detail.event.detectedAt)}
              {detail.event.reportedValue !== null && detail.event.reportedValue !== undefined
                ? ` · 上报度量 ${formatValue(detail.event.reportedValue)} ${detail.event.reportedUnit}`
                : ''}
              {detail.event.note ? ` · 备注：${detail.event.note}` : ''}
            </div>

            {/* 运行列表 */}
            <div>
              <div className="text-copy-14 text-foreground mb-2 font-medium">诊断运行</div>
              {detail.runs.length === 0 ? (
                <div className="text-muted-foreground bg-muted/40 rounded-md border p-3 text-sm">
                  这个事件**还没有跑过诊断**。「没诊断过」不等于「没问题」——
                  点「诊断」跑一次才会知道。
                </div>
              ) : (
                <div className="space-y-2">
                  {detail.runs.map((run) => (
                    <div
                      key={run.id}
                      className="border-border flex flex-wrap items-center gap-3 rounded-md border px-3 py-2 text-sm"
                    >
                      <span className="font-mono text-xs">{run.runNo}</span>
                      <Badge tone={BADGE_TONE[run.status === 'COMPLETED' ? 'ok' : 'muted']}>
                        {run.status}
                      </Badge>
                      <span className="text-copy-12 text-muted-foreground">
                        口径 {run.ruleVersion} · {run.conclusionCount} 条结论 / {run.evidenceCount}{' '}
                        条依据
                      </span>
                      <span className="text-copy-12 text-muted-foreground">
                        {formatTime(run.startedAt)}
                      </span>
                      <span className="text-copy-12 text-muted-foreground ml-auto">{run.note}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* 结论 + 依据 */}
            <div>
              <div className="text-copy-14 text-foreground mb-2 font-medium">归因结论与依据</div>
              <div
                className={
                  readState.tone === 'danger'
                    ? 'text-destructive mb-2 flex items-start gap-2 rounded-md border p-3 text-sm'
                    : 'text-muted-foreground mb-2 flex items-start gap-2 rounded-md border p-3 text-sm'
                }
              >
                {readState.tone === 'danger' ? (
                  <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
                ) : null}
                <span>
                  <b>{readState.title}</b>
                  {readState.detail ? ` —— ${readState.detail}` : ''}
                </span>
              </div>

              {!runDetail ? (
                <div className="text-muted-foreground bg-muted/40 rounded-md border p-3 text-sm">
                  还没有可展示的结论 —— 先跑一次诊断。
                </div>
              ) : runDetail.conclusions.length === 0 ? (
                <div className="text-muted-foreground bg-muted/40 rounded-md border p-3 text-sm">
                  本次运行**没有命中任何归因规则**（读到了事实，但事实没越线）。
                </div>
              ) : (
                <div className="space-y-3">
                  {conclusionViews.map((row) => (
                    <div key={row.id} className="border-border rounded-md border">
                      <div className="border-border flex flex-wrap items-center gap-2 border-b px-3 py-2">
                        <Badge tone={BADGE_TONE[row.severityTone]}>
                          {severityLabel(row.severity)}
                        </Badge>
                        <span className="text-copy-14 text-foreground font-medium">
                          {row.ruleLabel}
                        </span>
                        <span className="text-muted-foreground font-mono text-xs">
                          {row.ruleCode}
                        </span>
                        <Badge tone={BADGE_TONE[row.evidenceLevelTone]}>
                          {row.evidenceLevelLabel}
                        </Badge>
                        <span className="text-copy-12 text-muted-foreground ml-auto">
                          {row.impact !== '—' ? `影响 ${row.impact} ${row.impactUnit}` : ''}
                          {row.impactText ? ` · ${row.impactText}` : ''}
                        </span>
                      </div>
                      <div className="space-y-2 px-3 py-2">
                        <div className="text-copy-13 text-foreground">{row.summary}</div>
                        {row.detail && (
                          <div className="text-muted-foreground text-copy-12">{row.detail}</div>
                        )}
                        <div className="bg-muted/40 text-copy-12 rounded-md border p-2">
                          <b>处置建议（仅建议，不自动执行）</b>：{row.recommendation}
                          {row.actionOwner ? ` —— 归 ${row.actionOwner}` : ''}
                          {row.actionRef ? `，处理页 ${row.actionRef}` : ''}
                        </div>

                        {/* 🔴 依据摊开 —— 这是「诊断要给依据」的落点 */}
                        <div className="space-y-1">
                          <div className="text-copy-13 text-foreground font-medium">
                            依据（{row.evidenceCount} 条，可下钻）
                          </div>
                          <div className="overflow-x-auto">
                            <table className="w-full text-xs">
                              <thead className="text-muted-foreground">
                                <tr className="border-border border-b">
                                  <th className="px-2 py-1 text-left font-medium">事实源</th>
                                  <th className="px-2 py-1 text-left font-medium">事实表</th>
                                  <th className="px-2 py-1 text-left font-medium">字段</th>
                                  <th className="px-2 py-1 text-left font-medium">数值</th>
                                  <th className="px-2 py-1 text-left font-medium">下钻引用</th>
                                  <th className="px-2 py-1 text-left font-medium">快照时间</th>
                                </tr>
                              </thead>
                              <tbody>
                                {row.evidences.map((e, index) => (
                                  <tr key={`${row.id}-${index}`} className="border-border border-b">
                                    <td className="px-2 py-1">{e.service}</td>
                                    <td className="px-2 py-1 font-mono">{e.table}</td>
                                    <td className="px-2 py-1 font-mono">{e.field}</td>
                                    <td className="px-2 py-1">
                                      {e.value}
                                      {e.unit ? ` ${e.unit}` : ''}
                                    </td>
                                    <td className="px-2 py-1 font-mono">{e.ref || '—'}</td>
                                    <td className="whitespace-nowrap px-2 py-1">{e.snapshotAt}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                          <div className="text-muted-foreground text-copy-12 space-y-0.5">
                            {row.evidences.map((e, index) => (
                              <div key={`${row.id}-note-${index}`}>· {e.explanation}</div>
                            ))}
                          </div>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="text-muted-foreground bg-muted/40 text-copy-12 rounded-md border p-3">
              {executionBoundaryNotice()}
            </div>
          </div>
        </SectionCard>
      )}
    </div>
  );
}

/**
 * 能力声明页：能力 / 缺口 / 规则 / 阈值 / 事实源逐条列出。
 *
 * 🔴 **未接通的上游显示成「未接通（归 X 模块）」**，不显示成「暂无」——
 * 「暂无」会被读成「查过了，结果就是空」。
 */
function CapabilitiesView({ capabilities }: { capabilities: AiDiagnosisCapabilities | null }) {
  if (!capabilities) {
    return (
      <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
        <Loader2 className="h-4 w-4 animate-spin" />
        加载中…
      </div>
    );
  }
  return (
    <div className="space-y-5">
      <div>
        <div className="text-copy-14 text-foreground mb-2 font-medium">能力</div>
        <div className="space-y-2">
          {capabilities.capabilities.map((cap) => (
            <div
              key={`${cap.moduleId}-${cap.name}`}
              className="border-border rounded-md border p-3"
            >
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={BADGE_TONE[cap.available ? 'ok' : 'muted']}>
                  {cap.available ? '已接通' : '未接通'}
                </Badge>
                <span className="text-copy-14 text-foreground font-medium">{cap.name}</span>
                <span className="text-muted-foreground font-mono text-xs">{cap.moduleId}</span>
              </div>
              <div className="text-muted-foreground text-copy-12 mt-1">{cap.evidence}</div>
              {!cap.available && cap.note && (
                <div className="text-muted-foreground text-copy-12 mt-1">
                  {unavailableNote(cap.ownerModule, cap.note)}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="text-copy-14 text-foreground mb-2 font-medium">
          缺口（上游 / 下游未交付的部分）
        </div>
        <div className="space-y-2">
          {capabilities.gaps.map((gap) => (
            <div
              key={`${gap.moduleId}-${gap.name}`}
              className="border-border rounded-md border p-3"
            >
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={BADGE_TONE[gap.available ? 'ok' : 'muted']}>
                  {gap.available ? '已接通' : '未接通'}
                </Badge>
                <span className="text-copy-14 text-foreground font-medium">{gap.name}</span>
                <Badge tone="neutral">{gap.relation}</Badge>
                <span className="text-muted-foreground font-mono text-xs">{gap.moduleId}</span>
              </div>
              <div className="text-muted-foreground text-copy-12 mt-1">{gap.note}</div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="text-copy-14 text-foreground mb-2 font-medium">
          归因规则（触发条件 + 归因依据 + 处置建议）
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-copy-12">
              <tr className="border-border border-b">
                <th className="px-2 py-2 text-left font-medium">规则</th>
                <th className="px-2 py-2 text-left font-medium">归哪类异常</th>
                <th className="px-2 py-2 text-left font-medium">触发条件</th>
                <th className="px-2 py-2 text-left font-medium">归因依据</th>
                <th className="px-2 py-2 text-left font-medium">处置建议</th>
              </tr>
            </thead>
            <tbody>
              {capabilities.rules.map((rule) => (
                <tr key={rule.code} className="border-border border-b align-top">
                  <td className="px-2 py-2">
                    <div>{rule.name}</div>
                    <div className="text-muted-foreground font-mono text-xs">{rule.code}</div>
                  </td>
                  <td className="px-2 py-2">{rule.anomalyTypeLabel}</td>
                  <td className="px-2 py-2">{rule.trigger}</td>
                  <td className="px-2 py-2">{rule.basis}</td>
                  <td className="px-2 py-2">
                    {rule.recommendation}
                    {rule.actionOwner ? ` —— 归 ${rule.actionOwner}` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div>
          <div className="text-copy-14 text-foreground mb-2 font-medium">阈值</div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-copy-12">
                <tr className="border-border border-b">
                  <th className="px-2 py-2 text-left font-medium">阈值</th>
                  <th className="px-2 py-2 text-left font-medium">取值</th>
                  <th className="px-2 py-2 text-left font-medium">含义</th>
                </tr>
              </thead>
              <tbody>
                {capabilities.thresholds.map((threshold) => (
                  <tr key={threshold.code} className="border-border border-b">
                    <td className="px-2 py-2">{threshold.name}</td>
                    <td className="px-2 py-2">
                      {formatValue(threshold.value)} {threshold.unit}
                    </td>
                    <td className="px-2 py-2">{threshold.description}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <div className="text-copy-14 text-foreground mb-2 font-medium">事实源</div>
          <div className="space-y-2">
            {capabilities.sources.map((source) => (
              <div key={source.code} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={BADGE_TONE[source.connected ? 'ok' : 'muted']}>
                    {source.connected ? '已接通' : '未接通'}
                  </Badge>
                  <span className="text-copy-14 text-foreground font-medium">{source.name}</span>
                  <span className="text-muted-foreground font-mono text-xs">{source.service}</span>
                </div>
                {source.endpoint && (
                  <div className="text-muted-foreground mt-1 font-mono text-xs">
                    {source.endpoint}
                  </div>
                )}
                {!source.connected && (
                  <div className="text-muted-foreground text-copy-12 mt-1">
                    {unavailableNote(source.ownerModule, source.note)}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
