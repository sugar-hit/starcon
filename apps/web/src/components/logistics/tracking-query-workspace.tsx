'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Clock, PackageSearch, Radio, RefreshCw, Route } from 'lucide-react';
import {
  Badge,
  Button,
  DetailDrawer,
  DetailDrawerBody,
  DetailDrawerContent,
  DetailDrawerDescription,
  DetailDrawerHeader,
  DetailDrawerTitle,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import type {
  TrackingCapabilities,
  TrackingEventPage,
  TrackingPullTaskDetail,
  TrackingPullTaskInput,
  TrackingPullTaskPage,
  TrackingSubjectPage,
  TrackingSubjectSummary,
  TrackingTimelineDetail,
} from './tracking-query-model';
import {
  EMPTY_TRACKING_FILTERS,
  EMPTY_TRACKING_PULL_FILTERS,
  EMPTY_TRACKING_PULL_TASK,
  carrierEtaText,
  createTrackingPullTask,
  eventSourceLabel,
  etaStatusLabel,
  etaStatusTone,
  exceptionText,
  fetchTrackingCapabilities,
  fetchTrackingEvents,
  fetchTrackingOverview,
  fetchTrackingPullTask,
  fetchTrackingPullTasks,
  fetchTrackingSubject,
  fetchTrackingSubjects,
  formatMoment,
  formatWeight,
  latestText,
  nodeTypeLabel,
  parseWaybillList,
  pullItemStatusLabel,
  pullTaskStatusLabel,
  pullTaskStatusTone,
  promiseText,
  serviceLevelLabel,
  subjectStatusLabel,
  syncTrackingSubjects,
  validatePullTaskInput,
} from './tracking-query-model';

/**
 * 10.3.1.1 轨迹查询。
 *
 * <p>蓝图：「查询并展示包裹物流轨迹：节点时间线（揽收-运输-清关-转运-派送-签收）、
 * 预计时效对比、异常节点标记，支持按承运商批量拉取」，输出「轨迹时间线 + 预计到达时间
 * + 异常标记」。
 *
 * <p>🔴 这一页的三个设计前提：
 *
 * <ol>
 *   <li><b>去重不靠页面</b>：页面**从不**自己判断「这条事件是不是重复的」。
 *       重复与否由后端在 DB 唯一约束（tenant_id + carrier_code + carrier_event_id）上判定，
 *       页面拿到的就是最终账本。页面自己再去个重，等于把去重规则复制一份到前端，
 *       而前端这份在绕过页面直接写库时**不生效** —— 那正是蓝图加粗句要防的事。
 *       所以本页展示「重复 N 条」时，那是后端如实记账的结果，不是页面猜的。</li>
 *   <li><b>时效对比不靠页面算</b>：ON_TIME / AT_RISK / DELAYED / NO_PROMISE 由后端
 *       **读时**判定（是否延误取决于现在几点），并且承诺（我方规则）与预计（承运商说的）
 *       分成两栏展示 —— 两个来源合并成一句话之后，界面上就分不清是谁承诺的。</li>
 *   <li><b>异常只标记不处置</b>：本页显示异常节点与异常码，但**不提供**任何处置入口
 *       （联系承运商 / 理赔 / 补发 / 改址）—— 那是 10.3.1.2 的职责，
 *       在这里造出第二份处置台账会让责任无处可查。</li>
 * </ol>
 *
 * <p>本页**不涉及任何金额**：运费成本归集与结算归 12 财务（未交付）。
 */
export function TrackingQueryWorkspace() {
  const [capabilities, setCapabilities] = useState<TrackingCapabilities | null>(null);
  const [overview, setOverview] = useState<TrackingSubjectSummary | null>(null);
  const [subjects, setSubjects] = useState<TrackingSubjectPage | null>(null);
  const [events, setEvents] = useState<TrackingEventPage | null>(null);
  const [pullTasks, setPullTasks] = useState<TrackingPullTaskPage | null>(null);
  const [pullDetail, setPullDetail] = useState<TrackingPullTaskDetail | null>(null);
  const [detail, setDetail] = useState<TrackingTimelineDetail | null>(null);
  const [detailId, setDetailId] = useState('');

  const [filters, setFilters] = useState(EMPTY_TRACKING_FILTERS);
  const [pullFilters, setPullFilters] = useState(EMPTY_TRACKING_PULL_FILTERS);
  const [pullForm, setPullForm] = useState(EMPTY_TRACKING_PULL_TASK);
  const [pullWaybills, setPullWaybills] = useState('');

  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [detailError, setDetailError] = useState('');

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [caps, ov, subs, evs, tasks] = await Promise.all([
        fetchTrackingCapabilities(),
        fetchTrackingOverview(),
        fetchTrackingSubjects(filters),
        fetchTrackingEvents(filters),
        fetchTrackingPullTasks(pullFilters),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setSubjects(subs);
      setEvents(evs);
      setPullTasks(tasks);
    } catch (e) {
      setError(e instanceof Error ? e.message : '轨迹数据读不到');
    } finally {
      setBusy(false);
    }
  }, [filters, pullFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(id: string) {
    setDetailId(id);
    setDetail(null);
    setDetailError('');
    try {
      setDetail(await fetchTrackingSubject(id));
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : '轨迹详情读不到');
    }
  }

  async function syncSubjects() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await syncTrackingSubjects({
        carrierCode: filters.carrierCode.trim(),
        serviceLevel: 'STANDARD',
        page: 0,
        size: 200,
      });
      const skipped = result.skipped > 0 ? `，跳过 ${result.skipped} 票` : '';
      setNotice(
        `已同步 ${result.scanned} 票运单：新建 ${result.created} 票、更新 ${result.updated} 票${skipped}`,
      );
      await load();
    } catch (e) {
      // 上游读不到时后端返回 503，理由写「读不到」而不是「没有数据」——
      // 空清单在界面上与「上游真的没有运单」无法区分。
      setError(e instanceof Error ? e.message : '同步运单集合失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitPullTask() {
    const input: TrackingPullTaskInput = {
      ...pullForm,
      carrierCode: pullForm.carrierCode.trim(),
      waybillNos: parseWaybillList(pullWaybills),
    };
    const problems = validatePullTaskInput(input);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await createTrackingPullTask(input);
      setPullDetail(result);
      setPullForm(EMPTY_TRACKING_PULL_TASK);
      setPullWaybills('');
      setNotice(
        `已建拉取任务 ${result.task.taskNo}：${pullTaskStatusLabel(result.task.status)}` +
          `，取回 ${result.task.insertedEventCount} 条事件、重复 ${result.task.duplicateCount} 条`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建拉取任务失败');
    } finally {
      setBusy(false);
    }
  }

  async function openPullTask(id: string) {
    setError('');
    try {
      setPullDetail(await fetchTrackingPullTask(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '拉取任务详情读不到');
    }
  }

  const gaps = capabilities?.dimensions.filter((d) => !d.available) ?? [];
  const delivered = capabilities?.dimensions.filter((d) => d.available) ?? [];

  return (
    <div className="space-y-4">
      <PageHeader
        title="轨迹查询"
        description="查询并展示包裹物流轨迹：节点时间线、预计时效对比、异常节点标记，支持按承运商批量拉取。"
        actions={
          <>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void load()}>
              <RefreshCw className="mr-1 h-4 w-4" />
              刷新
            </Button>
            <Button size="sm" disabled={busy} onClick={() => void syncSubjects()}>
              <PackageSearch className="mr-1 h-4 w-4" />
              同步运单集合
            </Button>
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="操作未完成">
          {error}
        </Notice>
      )}
      {notice && !error && (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      )}
      {busy && !subjects && <p className="text-muted-foreground text-sm">正在加载轨迹…</p>}
      {error && !busy && (
        <Button size="sm" variant="outline" onClick={() => void load()}>
          重试
        </Button>
      )}

      {/* 🔴 这一句是这一页的设计前提，不是提示语 */}
      <Notice tone="info" title="去重在数据库层，不在页面层">
        {capabilities?.highlight ??
          '轨迹事件按承运商事件 ID 在 DB 唯一约束上去重：同一节点重复推送不产生重复记录。'}
      </Notice>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="在跟踪运单" value={overview?.totalCount ?? 0} />
        <StatCard label="已延误" value={overview?.delayedCount ?? 0} />
        <StatCard label="有风险" value={overview?.atRiskCount ?? 0} />
        <StatCard label="异常票" value={overview?.exceptionCount ?? 0} />
      </div>

      <Tabs defaultValue="subjects">
        <TabsList>
          <TabsTrigger value="subjects">轨迹清单</TabsTrigger>
          <TabsTrigger value="events">事件流</TabsTrigger>
          <TabsTrigger value="pull">批量拉取</TabsTrigger>
          <TabsTrigger value="capabilities">能力与边界</TabsTrigger>
        </TabsList>

        {/* ============================ 轨迹清单 ============================ */}
        <TabsContent value="subjects">
          <SectionCard title="筛选" className="mt-4">
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="tkKeyword" label="关键词">
                <Input
                  id="tkKeyword"
                  value={filters.keyword}
                  placeholder="运单号 / 销售单号 / 出库单号"
                  onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="tkCarrier" label="承运商">
                <Input
                  id="tkCarrier"
                  value={filters.carrierCode}
                  placeholder="SF / FEDEX"
                  onChange={(e) =>
                    setFilters({ ...filters, carrierCode: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="tkCountry" label="目的国">
                <Input
                  id="tkCountry"
                  value={filters.destinationCountry}
                  placeholder="US / DE"
                  onChange={(e) =>
                    setFilters({ ...filters, destinationCountry: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="tkNode" label="最新节点">
                <Input
                  id="tkNode"
                  value={filters.nodeType}
                  placeholder="IN_TRANSIT / DELIVERED"
                  onChange={(e) => setFilters({ ...filters, nodeType: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="tkEta" label="时效状态">
                <Input
                  id="tkEta"
                  value={filters.etaStatus}
                  placeholder="DELAYED / AT_RISK"
                  onChange={(e) => setFilters({ ...filters, etaStatus: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="tkStatus" label="主体状态">
                <Input
                  id="tkStatus"
                  value={filters.status}
                  placeholder="ACTIVE / DELIVERED"
                  onChange={(e) => setFilters({ ...filters, status: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="tkSort" label="排序">
                <Input
                  id="tkSort"
                  value={filters.sort}
                  placeholder="LATEST_DESC"
                  onChange={(e) => setFilters({ ...filters, sort: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="tkException" label="只看异常">
                <label className="text-copy-14 text-foreground flex items-center gap-2">
                  <input
                    id="tkException"
                    type="checkbox"
                    checked={filters.exceptionOnly}
                    onChange={(e) =>
                      setFilters({ ...filters, exceptionOnly: e.target.checked, page: 0 })
                    }
                  />
                  只看有异常节点的票
                </label>
              </FormField>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              统计覆盖当前筛选结果且不受分页影响。目的国是主筛选维度：跨境延误常常集中在某些国家。
            </p>
          </SectionCard>

          <SectionCard title={`轨迹清单（${subjects?.totalElements ?? 0}）`} className="mt-4">
            {subjects && subjects.items.length > 0 ? (
              <div className="space-y-3">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left">
                        <th className="p-2">运单号</th>
                        <th className="p-2">承运商</th>
                        <th className="p-2">目的国</th>
                        <th className="p-2">最新节点</th>
                        <th className="p-2">时效状态</th>
                        <th className="p-2">异常</th>
                        <th className="p-2">事件数</th>
                        <th className="p-2">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {subjects.items.map((row) => (
                        <tr key={row.id} className="border-b last:border-0">
                          <td className="p-2">
                            <div className="font-medium">{row.masterWaybillNo}</div>
                            <div className="text-muted-foreground text-xs">
                              {row.salesOrderNo || '—'} · {formatWeight(row.grossWeightKg)} ·{' '}
                              {serviceLevelLabel(row.serviceLevel)}
                            </div>
                          </td>
                          <td className="p-2">
                            {row.carrierCode}
                            {row.channelCode ? (
                              <div className="text-muted-foreground text-xs">{row.channelCode}</div>
                            ) : null}
                          </td>
                          <td className="p-2">{row.destinationCountry || '—'}</td>
                          <td className="p-2">
                            <Badge tone={row.exceptionFlag ? 'danger' : 'neutral'}>
                              {row.latestNodeLabel || nodeTypeLabel(row.latestNode)}
                            </Badge>
                            <div className="text-muted-foreground text-xs">
                              {latestText(row)}
                            </div>
                          </td>
                          <td className="p-2">
                            <Badge tone={etaStatusTone(row.etaStatus)}>
                              {row.etaStatusLabel || etaStatusLabel(row.etaStatus)}
                            </Badge>
                          </td>
                          <td className="p-2">
                            {row.exceptionFlag ? (
                              <span className="text-destructive text-xs">
                                {exceptionText(row)}
                              </span>
                            ) : (
                              <span className="text-muted-foreground text-xs">无</span>
                            )}
                          </td>
                          <td className="p-2">{row.eventCount}</td>
                          <td className="p-2">
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => void openDetail(row.id)}
                            >
                              轨迹详情
                            </Button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={subjects.page <= 0 || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {subjects.page + 1} / {Math.max(subjects.totalPages, 1)} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={subjects.page + 1 >= subjects.totalPages || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                icon={<Route className="h-6 w-6" />}
                title="还没有轨迹主体"
                description="轨迹主体来自上游运单集合。点右上角「同步运单集合」把上游已发货的运单拉过来；上游读不到时这里会显式报错，不会给你一张空清单。"
              />
            )}
          </SectionCard>
        </TabsContent>

        {/* ============================ 事件流 ============================ */}
        <TabsContent value="events">
          <SectionCard title={`事件流（${events?.totalElements ?? 0}）`} className="mt-4">
            {events && events.items.length > 0 ? (
              <div className="space-y-2">
                {events.items.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={row.exception ? 'danger' : 'neutral'}>
                        {row.nodeLabel || nodeTypeLabel(row.nodeType)}
                      </Badge>
                      <span className="text-copy-13 font-medium">{row.description || '—'}</span>
                      <span className="text-muted-foreground text-xs">
                        {formatMoment(row.occurredAt)}
                      </span>
                      <Badge tone="info">{eventSourceLabel(row.source)}</Badge>
                      {row.exception ? (
                        <span className="text-destructive text-xs">
                          {row.exceptionCode || '未给异常码'}
                          {row.exceptionNote ? `：${row.exceptionNote}` : ''}
                        </span>
                      ) : null}
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      承运商事件号 {row.carrierEventId} · 承运商 {row.carrierCode}
                      {row.location ? ` · ${row.location}` : ''}
                      {row.estimatedDeliveryAt
                        ? ` · 预计到达 ${formatMoment(row.estimatedDeliveryAt)}`
                        : ''}
                    </div>
                  </div>
                ))}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={events.page <= 0 || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {events.page + 1} / {Math.max(events.totalPages, 1)} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={events.page + 1 >= events.totalPages || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                icon={<Radio className="h-6 w-6" />}
                title="还没有轨迹事件"
                description="事件来自承运商推送或按承运商批量拉取。这一页空不代表货没动 —— 先同步运单集合，再看拉取任务的逐票结果。"
              />
            )}
          </SectionCard>
        </TabsContent>

        {/* ============================ 批量拉取 ============================ */}
        <TabsContent value="pull">
          <SectionCard title="建一轮批量拉取" className="mt-4">
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="tkPullCarrier" label="承运商" required>
                <Input
                  id="tkPullCarrier"
                  value={pullForm.carrierCode}
                  placeholder="SF / FEDEX"
                  onChange={(e) => setPullForm({ ...pullForm, carrierCode: e.target.value })}
                />
              </FormField>
              <FormField id="tkPullTrigger" label="触发来源" required>
                <Input
                  id="tkPullTrigger"
                  value={pullForm.triggerSource}
                  placeholder="MANUAL / SCHEDULED"
                  onChange={(e) =>
                    setPullForm({
                      ...pullForm,
                      triggerSource: e.target.value.toUpperCase() as TrackingPullTaskInput['triggerSource'],
                    })
                  }
                />
              </FormField>
              <FormField id="tkPullWaybills" label="运单号（可空 = 该承运商全部在跟踪的运单）">
                <Input
                  id="tkPullWaybills"
                  value={pullWaybills}
                  placeholder="WB-1, WB-2"
                  onChange={(e) => setPullWaybills(e.target.value)}
                />
              </FormField>
            </div>
            <div className="mt-3">
              <Button size="sm" disabled={busy} onClick={() => void submitPullTask()}>
                建轮并执行
              </Button>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              没有已接通的承运商轨迹通道时，这一轮会如实落「通道未接通」并写明理由 ——
              不会返回空轨迹冒充「承运商说这一票没有轨迹」。
            </p>
          </SectionCard>

          <SectionCard title={`拉取任务（${pullTasks?.totalElements ?? 0}）`} className="mt-4">
            {pullTasks && pullTasks.items.length > 0 ? (
              <div className="space-y-2">
                {pullTasks.items.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-13 font-medium">{row.taskNo}</span>
                      <Badge tone={pullTaskStatusTone(row.status)}>
                        {row.statusLabel || pullTaskStatusLabel(row.status)}
                      </Badge>
                      <span className="text-muted-foreground text-xs">{row.carrierCode}</span>
                      <span className="text-muted-foreground text-xs">
                        {row.triggerSource === 'SCHEDULED' ? '调度触发' : '手动触发'}
                      </span>
                      <span className="text-muted-foreground text-xs">
                        {formatMoment(row.createdAt)}
                      </span>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void openPullTask(row.id)}
                      >
                        逐票结果
                      </Button>
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      取回 {row.insertedEventCount} 条 · 重复 {row.duplicateCount} 条 · 承运商回复无轨迹{' '}
                      {row.noEventCount} 票 · 失败 {row.failedCount} 票
                    </div>
                    {row.failureReason ? (
                      <div className="text-destructive mt-1 text-xs">{row.failureReason}</div>
                    ) : null}
                  </div>
                ))}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={pullTasks.page <= 0 || busy}
                    onClick={() => setPullFilters({ ...pullFilters, page: pullFilters.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {pullTasks.page + 1} / {Math.max(pullTasks.totalPages, 1)} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={pullTasks.page + 1 >= pullTasks.totalPages || busy}
                    onClick={() => setPullFilters({ ...pullFilters, page: pullFilters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                icon={<Clock className="h-6 w-6" />}
                title="还没有拉取任务"
                description="拉取任务由外部调度（K8s CronJob / 平台任务执行器）触发，也可以在这里手动建一轮。每一轮都是可审计的账本：拉了哪几票、取回几条、几条重复、哪几票失败。"
              />
            )}
          </SectionCard>

          {pullDetail ? (
            <SectionCard
              title={`逐票结果 · ${pullDetail.task.taskNo}`}
              className="mt-4"
              extra={
                <Badge tone={pullTaskStatusTone(pullDetail.task.status)}>
                  {pullDetail.task.statusLabel || pullTaskStatusLabel(pullDetail.task.status)}
                </Badge>
              }
            >
              {pullDetail.items.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left">
                        <th className="p-2">运单号</th>
                        <th className="p-2">结果</th>
                        <th className="p-2">新增事件</th>
                        <th className="p-2">重复事件</th>
                        <th className="p-2">说明</th>
                      </tr>
                    </thead>
                    <tbody>
                      {pullDetail.items.map((item) => (
                        <tr key={item.id} className="border-b last:border-0">
                          <td className="p-2">{item.waybillNo}</td>
                          <td className="p-2">
                            {item.statusLabel || pullItemStatusLabel(item.status)}
                          </td>
                          <td className="p-2">{item.insertedEventCount}</td>
                          <td className="p-2">{item.duplicateEventCount}</td>
                          <td className="text-muted-foreground p-2 text-xs">{item.detail || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <EmptyState
                  title="这一轮没有逐票记录"
                  description="拉取范围是空的：既没有指定运单号，该承运商下也没有在跟踪的运单。"
                />
              )}
            </SectionCard>
          ) : null}
        </TabsContent>

        {/* ============================ 能力与边界 ============================ */}
        <TabsContent value="capabilities">
          <SectionCard title="本模块已交付的能力" className="mt-4">
            {delivered.length > 0 ? (
              <ul className="space-y-2">
                {delivered.map((d) => (
                  <li key={d.dimension} className="border-border rounded-md border p-3">
                    <div className="flex items-center gap-2">
                      <Badge tone="success">已交付</Badge>
                      <span className="text-copy-13 font-medium">{d.description}</span>
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">来源：{d.source}</div>
                    <div className="text-muted-foreground mt-1 text-xs">{d.impact}</div>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState title="能力声明读不到" description="刷新重试。" />
            )}
          </SectionCard>

          <SectionCard title="本模块未覆盖的段（如实列出归属与影响）" className="mt-4">
            {gaps.length > 0 ? (
              <ul className="space-y-2">
                {gaps.map((d) => (
                  <li key={d.dimension} className="border-border rounded-md border p-3">
                    <div className="flex items-center gap-2">
                      <Badge tone="warning">未交付</Badge>
                      <span className="text-copy-13 font-medium">{d.description}</span>
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      归属模块 {d.ownerModule} · 来源 {d.source}
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">{d.impact}</div>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState
                icon={<AlertTriangle className="h-6 w-6" />}
                title="没有未覆盖的段"
                description="能力声明读到的每一段都已交付。"
              />
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>

      {/* ============================ 轨迹详情抽屉 ============================ */}
      <DetailDrawer open={detailId !== ''} onOpenChange={(open) => !open && setDetailId('')}>
        <DetailDrawerContent width="lg">
          <DetailDrawerHeader>
            <DetailDrawerTitle>
              {detail ? detail.subject.masterWaybillNo : '轨迹详情'}
            </DetailDrawerTitle>
            <DetailDrawerDescription>
              {detail
                ? `${detail.subject.carrierCode} · ${detail.subject.destinationCountry || '未知国家'} · ${detail.subject.channelCode ?? '未归渠道'}`
                : '正在读取…'}
            </DetailDrawerDescription>
          </DetailDrawerHeader>
          <DetailDrawerBody>
            {detailError ? (
              <Notice tone="danger" title="轨迹详情读不到">
                {detailError}
              </Notice>
            ) : null}
            {detail ? (
              <div className="space-y-4">
                <div className="grid gap-3 sm:grid-cols-2">
                  <StatCard
                    label="时效状态"
                    value={detail.eta.etaStatusLabel || etaStatusLabel(detail.eta.etaStatus)}
                  />
                  <StatCard label="轨迹事件" value={detail.timeline.length} />
                </div>

                <SectionCard title="预计时效对比">
                  <dl className="space-y-2 text-sm">
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">我方承诺（发货当时的时效口径）</dt>
                      <dd className="text-right">{promiseText(detail.subject)}</dd>
                    </div>
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">承运商预计（承运商说的）</dt>
                      <dd className="text-right">{carrierEtaText(detail.subject)}</dd>
                    </div>
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">最后节点</dt>
                      <dd className="text-right">
                        {detail.subject.latestNodeLabel || nodeTypeLabel(detail.subject.latestNode)}{' '}
                        {formatMoment(detail.subject.latestOccurredAt)}
                      </dd>
                    </div>
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">主体状态</dt>
                      <dd className="text-right">
                        {detail.subject.statusLabel || subjectStatusLabel(detail.subject.status)}
                      </dd>
                    </div>
                  </dl>
                  <p className="text-muted-foreground mt-2 text-xs">{detail.eta.note}</p>
                </SectionCard>

                <SectionCard title={`节点时间线（${detail.timeline.length}）`}>
                  {detail.timeline.length > 0 ? (
                    <ol className="space-y-2">
                      {detail.timeline.map((event) => (
                        <li key={event.id} className="border-border rounded-md border p-3">
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge tone={event.exception ? 'danger' : 'neutral'}>
                              {event.nodeLabel || nodeTypeLabel(event.nodeType)}
                            </Badge>
                            <span className="text-copy-13">{event.description || '—'}</span>
                            <span className="text-muted-foreground text-xs">
                              {formatMoment(event.occurredAt)}
                            </span>
                          </div>
                          <div className="text-muted-foreground mt-1 text-xs">
                            承运商事件号 {event.carrierEventId} · 来源{' '}
                            {eventSourceLabel(event.source)}
                            {event.location ? ` · ${event.location}` : ''}
                          </div>
                          {event.exception ? (
                            <div className="text-destructive mt-1 text-xs">
                              {event.exceptionCode || '未给异常码'}
                              {event.exceptionNote ? `：${event.exceptionNote}` : ''}
                            </div>
                          ) : null}
                        </li>
                      ))}
                    </ol>
                  ) : (
                    <EmptyState
                      title="这一票还没有轨迹事件"
                      description="事件来自承运商推送或批量拉取；先同步运单集合，再触发一轮拉取。"
                    />
                  )}
                </SectionCard>

                <SectionCard title="边界说明">
                  <ul className="text-muted-foreground list-disc space-y-1 pl-5 text-xs">
                    {detail.boundaryNotes.map((note) => (
                      <li key={note}>{note}</li>
                    ))}
                  </ul>
                </SectionCard>
              </div>
            ) : null}
          </DetailDrawerBody>
        </DetailDrawerContent>
      </DetailDrawer>
    </div>
  );
}
