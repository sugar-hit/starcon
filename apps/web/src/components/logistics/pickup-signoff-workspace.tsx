'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, BellRing, ClipboardCheck, PackageCheck, RefreshCw, ScanSearch } from 'lucide-react';
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
  LogisticsPickupSignoffCapabilities,
  PickupSignoffCaseDetail,
  PickupSignoffCasePage,
  PickupSignoffNotificationPage,
  PickupSignoffReminderPage,
  PickupSignoffSlaRulePage,
  PickupSignoffSummary,
} from './pickup-signoff-model';
import {
  EMPTY_PICKUPSIGNOFF_FILTERS,
  EMPTY_PICKUPSIGNOFF_NOTIFICATION_FILTERS,
  EMPTY_PICKUPSIGNOFF_REMINDER_FILTERS,
  EMPTY_PICKUPSIGNOFF_SLA_RULE,
  acknowledgePickupSignoffReminder,
  attestPickupSignoff,
  consumePickupSignoffNotification,
  deadlineText,
  deliveryStateTone,
  deliveryText,
  fetchPickupSignoffCapabilities,
  fetchPickupSignoffCase,
  fetchPickupSignoffCases,
  fetchPickupSignoffNotifications,
  fetchPickupSignoffOverview,
  fetchPickupSignoffReminders,
  fetchPickupSignoffSlaRules,
  formatMoment,
  gapText,
  pickupStateTone,
  pickupText,
  reconcilePickupSignoff,
  registerPickupSignoffSlaRule,
  reminderKindLabel,
  reminderStatusLabel,
  resolvePickupSignoffReminder,
  signoffStatusLabel,
  signoffStatusTone,
  syncPickupSignoffCases,
  validateAttestInput,
  validateSlaRuleInput,
} from './pickup-signoff-model';

/**
 * 10.3.2.2 揽收与签收确认。
 *
 * <p>蓝图：「确认承运商揽收（避免超时未揽收被平台判未发货）与签收（触发售后窗口关闭
 * 与评价邀请时机）」，输出「状态更新 + 提醒任务」，并加粗
 * 「<b>揽收状态直接影响平台履约指标，需主动监控而非等轨迹同步</b>」。
 *
 * <p>🔴 这一页的三个设计前提：
 *
 * <ol>
 *   <li><b>超时不靠页面算</b>：是否超时由**后端巡检**按 now() 与时限快照判定。
 *       页面自己比一次时刻就多了一套口径，而且会在后端没巡检时说「没超时」——
 *       而「没超时」正是平台判我们未发货之前最危险的一句话。</li>
 *   <li><b>主动监控要有入口</b>：页面给一个显式的「巡检」动作，扫一轮就把确认单补齐、
 *       状态推进、超时判定、提醒与待通知全部跑完。不自带调度器（多副本要做主节点选举），
 *       由外部调度触发同一个端点。</li>
 *   <li><b>下游未接线就如实说</b>：9.4.2.1 回传与 15 客服（索评 / 售后窗口）在本页只是
 *       <b>待通知记录</b>，状态到「到时机 · 待下游消费」为止 —— 页面不写「已回传」。</li>
 * </ol>
 *
 * <p>本页**不涉及任何金额**：运费成本归集与结算归 12 财务（未开发）。
 */
export function PickupSignoffWorkspace() {
  const [capabilities, setCapabilities] = useState<LogisticsPickupSignoffCapabilities | null>(
    null,
  );
  const [overview, setOverview] = useState<PickupSignoffSummary | null>(null);
  const [cases, setCases] = useState<PickupSignoffCasePage | null>(null);
  const [reminders, setReminders] = useState<PickupSignoffReminderPage | null>(null);
  const [notifications, setNotifications] = useState<PickupSignoffNotificationPage | null>(null);
  const [rules, setRules] = useState<PickupSignoffSlaRulePage | null>(null);
  const [detail, setDetail] = useState<PickupSignoffCaseDetail | null>(null);
  const [detailId, setDetailId] = useState('');

  const [filters, setFilters] = useState(EMPTY_PICKUPSIGNOFF_FILTERS);
  const [reminderFilters, setReminderFilters] = useState(EMPTY_PICKUPSIGNOFF_REMINDER_FILTERS);
  const [notificationFilters, setNotificationFilters] = useState(
    EMPTY_PICKUPSIGNOFF_NOTIFICATION_FILTERS,
  );
  const [ruleForm, setRuleForm] = useState(EMPTY_PICKUPSIGNOFF_SLA_RULE);
  const [attestKind, setAttestKind] = useState('PICKUP');
  const [attestRef, setAttestRef] = useState('');
  const [attestNote, setAttestNote] = useState('');

  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [detailError, setDetailError] = useState('');

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [caps, ov, cs, rms, nfs, rls] = await Promise.all([
        fetchPickupSignoffCapabilities(),
        fetchPickupSignoffOverview(),
        fetchPickupSignoffCases(filters),
        fetchPickupSignoffReminders(reminderFilters),
        fetchPickupSignoffNotifications(notificationFilters),
        fetchPickupSignoffSlaRules({
          ...EMPTY_PICKUPSIGNOFF_REMINDER_FILTERS,
          carrierCode: reminderFilters.carrierCode,
        }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setCases(cs);
      setReminders(rms);
      setNotifications(nfs);
      setRules(rls);
    } catch (e) {
      setError(e instanceof Error ? e.message : '揽收签收数据读不到');
    } finally {
      setBusy(false);
    }
  }, [filters, reminderFilters, notificationFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(id: string) {
    setDetailId(id);
    setDetail(null);
    setDetailError('');
    try {
      setDetail(await fetchPickupSignoffCase(id));
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : '确认单详情读不到');
    }
  }

  async function syncCases() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await syncPickupSignoffCases({
        carrierCode: filters.carrierCode.trim(),
        batchNo: filters.batchNo.trim(),
      });
      setNotice(
        `已从交运批次投影 ${result.scanned} 个交运件：新建 ${result.created} 单、更新 ${result.updated} 单、跳过 ${result.skipped} 个` +
          (result.notes.length > 0 ? `（${result.notes.join('；')}）` : ''),
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '同步确认单失败');
    } finally {
      setBusy(false);
    }
  }

  async function runReconcile() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await reconcilePickupSignoff({
        carrierCode: filters.carrierCode.trim(),
        batchNo: filters.batchNo.trim(),
      });
      setNotice(
        `巡检完成：扫 ${result.scanned} 个交运件，新建 ${result.created} 单，` +
          `确认揽收 ${result.pickupConfirmed}、确认签收 ${result.deliveryConfirmed}、` +
          `判超时 ${result.overdueDetected}、即将超时 ${result.dueSoonDetected}，` +
          `生成提醒 ${result.remindersCreated} 条、待通知 ${result.notificationsCreated} 条`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '巡检失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitAttest() {
    const problems = validateAttestInput({ kind: attestKind, evidenceRef: attestRef });
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    if (detailId === '') {
      setError('先在清单里打开一个确认单，再登记人工确认');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await attestPickupSignoff(detailId, {
        kind: attestKind,
        occurredAt: new Date().toISOString(),
        evidenceRef: attestRef.trim(),
        note: attestNote.trim(),
      });
      setDetail(result);
      setAttestRef('');
      setAttestNote('');
      setNotice('已登记人工确认（凭据号与时刻已落库）');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '人工确认失败');
    } finally {
      setBusy(false);
    }
  }

  async function actOnReminder(id: string, action: 'acknowledge' | 'resolve') {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const note = action === 'acknowledge' ? '已认领' : '已处置';
      const row =
        action === 'acknowledge'
          ? await acknowledgePickupSignoffReminder(id, note)
          : await resolvePickupSignoffReminder(id, note);
      setNotice(`提醒 ${row.reminderKindLabel || reminderKindLabel(row.reminderKind)}：${row.statusLabel || reminderStatusLabel(row.status)}`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提醒处置失败');
    } finally {
      setBusy(false);
    }
  }

  async function consumeNotification(id: string, result: 'CONSUMED' | 'FAILED') {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const row = await consumePickupSignoffNotification(
        id,
        result,
        result === 'FAILED' ? '下游消费失败（页面登记）' : '下游已取走（页面登记）',
      );
      setNotice(`待通知 ${row.notificationKind}：${row.downstreamStatusLabel}`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记下游消费结果失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitSlaRule() {
    const problems = validateSlaRuleInput(ruleForm);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const row = await registerPickupSignoffSlaRule({
        ...ruleForm,
        carrierCode: ruleForm.carrierCode.trim(),
        note: ruleForm.note.trim(),
      });
      setNotice(
        `已登记 ${row.carrierCode} 的时限规则：揽收 ${row.pickupSlaHours} 小时` +
          `（已存在的确认单**不受影响** —— 它们的时限是建单时的快照）`,
      );
      setRuleForm(EMPTY_PICKUPSIGNOFF_SLA_RULE);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记时限规则失败');
    } finally {
      setBusy(false);
    }
  }

  const gaps = capabilities?.dimensions.filter((d) => !d.available) ?? [];
  const delivered = capabilities?.dimensions.filter((d) => d.available) ?? [];

  return (
    <div className="space-y-4">
      <PageHeader
        title="揽收与签收确认"
        description="确认承运商揽收（避免超时未揽收被平台判未发货）与签收（触发售后窗口关闭与评价邀请时机）。"
        actions={
          <>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void load()}>
              <RefreshCw className="mr-1 h-4 w-4" />
              刷新
            </Button>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void syncCases()}>
              <PackageCheck className="mr-1 h-4 w-4" />
              同步确认单
            </Button>
            <Button size="sm" disabled={busy} onClick={() => void runReconcile()}>
              <ScanSearch className="mr-1 h-4 w-4" />
              巡检
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
      {busy && !cases && <p className="text-muted-foreground text-sm">正在加载揽收签收数据…</p>}
      {error && !busy && (
        <Button size="sm" variant="outline" onClick={() => void load()}>
          重试
        </Button>
      )}

      {/* 🔴 这一句是这一页的设计前提，不是提示语 */}
      <Notice tone="info" title="超时要主动巡检，不是等轨迹同步顺带告诉你">
        {capabilities?.highlight ??
          '揽收状态直接影响平台履约指标：巡检按当前时刻判定超时并生成提醒，时限是建单时的快照。'}
      </Notice>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="确认单（交运件）" value={overview?.totalCount ?? 0} />
        <StatCard label="超时未揽收" value={overview?.overdueCount ?? 0} polarity="negative" />
        <StatCard label="已签收" value={overview?.deliveredCount ?? 0} />
        <StatCard label="待处理提醒" value={overview?.reminderPendingCount ?? 0} />
      </div>

      {overview && (overview.noWaybillCount > 0 ||
        overview.subjectMissingCount > 0 ||
        overview.subjectAmbiguousCount > 0 ||
        overview.slaUnconfiguredCount > 0) ? (
        <Notice tone="warning" title="有推进不了的件（如实列出，不用默认值冒充）">
          缺运单号 {overview.noWaybillCount} · 缺轨迹主体 {overview.subjectMissingCount} ·
          运单号不唯一 {overview.subjectAmbiguousCount} · 未配时限规则{' '}
          {overview.slaUnconfiguredCount}
        </Notice>
      ) : null}

      <Tabs defaultValue="cases">
        <TabsList>
          <TabsTrigger value="cases">确认单清单</TabsTrigger>
          <TabsTrigger value="reminders">提醒任务</TabsTrigger>
          <TabsTrigger value="notifications">下游待通知</TabsTrigger>
          <TabsTrigger value="rules">时限规则</TabsTrigger>
          <TabsTrigger value="capabilities">能力与边界</TabsTrigger>
        </TabsList>

        {/* ============================ 确认单清单 ============================ */}
        <TabsContent value="cases">
          <SectionCard title="筛选" className="mt-4">
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="psKeyword" label="关键词">
                <Input
                  id="psKeyword"
                  value={filters.keyword}
                  placeholder="运单号 / 单号 / 箱号 / 批次号"
                  onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="psCarrier" label="承运商">
                <Input
                  id="psCarrier"
                  value={filters.carrierCode}
                  placeholder="SF / FEDEX"
                  onChange={(e) =>
                    setFilters({ ...filters, carrierCode: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="psStatus" label="状态">
                <Input
                  id="psStatus"
                  value={filters.status}
                  placeholder="PENDING_PICKUP / PICKUP_OVERDUE / DELIVERED"
                  onChange={(e) => setFilters({ ...filters, status: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="psPickup" label="揽收状态">
                <Input
                  id="psPickup"
                  value={filters.pickupState}
                  placeholder="PENDING / CONFIRMED / OVERDUE"
                  onChange={(e) => setFilters({ ...filters, pickupState: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="psBatch" label="交运批次号">
                <Input
                  id="psBatch"
                  value={filters.batchNo}
                  placeholder="HNB-20261015-000001"
                  onChange={(e) => setFilters({ ...filters, batchNo: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="psGap" label="缺口">
                <Input
                  id="psGap"
                  value={filters.gapCode}
                  placeholder="NO_WAYBILL / SLA_UNCONFIGURED"
                  onChange={(e) => setFilters({ ...filters, gapCode: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="psSort" label="排序">
                <Input
                  id="psSort"
                  value={filters.sort}
                  placeholder="HANDOVER_AT_DESC / DEADLINE_ASC"
                  onChange={(e) => setFilters({ ...filters, sort: e.target.value, page: 0 })}
                />
              </FormField>
              <div className="flex items-end gap-2">
                <label className="text-copy-13 flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={filters.overdueOnly}
                    onChange={(e) =>
                      setFilters({ ...filters, overdueOnly: e.target.checked, page: 0 })
                    }
                  />
                  只看超时
                </label>
                <label className="text-copy-13 flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={filters.exceptionOnly}
                    onChange={(e) =>
                      setFilters({ ...filters, exceptionOnly: e.target.checked, page: 0 })
                    }
                  />
                  只看异常
                </label>
              </div>
            </div>
          </SectionCard>

          <SectionCard title={`确认单（${cases?.totalElements ?? 0}）`} className="mt-4">
            {cases && cases.items.length > 0 ? (
              <div className="space-y-2">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left">
                        <th className="p-2">单号 / 运单</th>
                        <th className="p-2">交运件</th>
                        <th className="p-2">状态</th>
                        <th className="p-2">揽收</th>
                        <th className="p-2">时限（快照）</th>
                        <th className="p-2">签收</th>
                        <th className="p-2">缺口</th>
                        <th className="p-2" />
                      </tr>
                    </thead>
                    <tbody>
                      {cases.items.map((row) => (
                        <tr key={row.id} className="border-b last:border-0">
                          <td className="p-2">
                            <div className="font-medium">{row.caseNo || '—'}</div>
                            <div className="text-muted-foreground text-xs">
                              {row.masterWaybillNo || '无运单号'} · {row.carrierCode}
                            </div>
                          </td>
                          <td className="p-2 text-xs">
                            <div>第 {row.parcelNo} 件</div>
                            <div className="text-muted-foreground">
                              批次 {row.sourceBatchNo} · 交运 {formatMoment(row.handoverAt)}
                            </div>
                          </td>
                          <td className="p-2">
                            <Badge tone={signoffStatusTone(row.status)}>
                              {row.statusLabel || signoffStatusLabel(row.status)}
                            </Badge>
                          </td>
                          <td className="p-2 text-xs">
                            <Badge tone={pickupStateTone(row.pickupState)}>
                              {row.pickupStateLabel || row.pickupState}
                            </Badge>
                            <div className="text-muted-foreground mt-1">{pickupText(row)}</div>
                          </td>
                          <td className="text-muted-foreground p-2 text-xs">
                            {deadlineText(row)}
                          </td>
                          <td className="p-2 text-xs">
                            <Badge tone={deliveryStateTone(row.deliveryState)}>
                              {row.deliveryStateLabel || row.deliveryState}
                            </Badge>
                            <div className="text-muted-foreground mt-1">{deliveryText(row)}</div>
                          </td>
                          <td className="p-2 text-xs">
                            {row.gapCode ? (
                              <span className="text-destructive">{gapText(row)}</span>
                            ) : (
                              <span className="text-muted-foreground">—</span>
                            )}
                          </td>
                          <td className="p-2">
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => void openDetail(row.id)}
                            >
                              详情
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
                    disabled={cases.page <= 0 || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {cases.page + 1} / {Math.max(cases.totalPages, 1)} 页 · 共{' '}
                    {cases.totalElements} 单
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={cases.page + 1 >= cases.totalPages || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                icon={<ClipboardCheck className="h-6 w-6" />}
                title="还没有确认单"
                description="确认单来自 10.3.2.1 的交运批次（逐件）。点右上角「同步确认单」把已登记交接的批次投影过来 —— 批次还在草稿状态时责任还没交出去，不建单。"
              />
            )}
          </SectionCard>
        </TabsContent>

        {/* ============================ 提醒任务 ============================ */}
        <TabsContent value="reminders">
          <SectionCard
            title={`提醒任务（${reminders?.totalElements ?? 0}）`}
            className="mt-4"
            description="提醒是「当时该做什么」的留痕；已认领 / 已处置都保留，不做物理删除。"
          >
            {reminders && reminders.items.length > 0 ? (
              <div className="space-y-2">
                {reminders.items.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={row.reminderKind === 'PICKUP_OVERDUE' ? 'danger' : 'warning'}>
                        {row.reminderKindLabel || reminderKindLabel(row.reminderKind)}
                      </Badge>
                      <span className="text-copy-13 font-medium">{row.title}</span>
                      <Badge tone="neutral">
                        {row.statusLabel || reminderStatusLabel(row.status)}
                      </Badge>
                      <span className="text-muted-foreground text-xs">
                        {row.caseNo} · {row.masterWaybillNo || '无运单号'} · {row.carrierCode}
                      </span>
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      {row.detail} · 截止 {formatMoment(row.dueAt)} · 建单{' '}
                      {formatMoment(row.createdAt)}
                    </div>
                    {row.status === 'PENDING' || row.status === 'ACKNOWLEDGED' ? (
                      <div className="mt-2 flex gap-2">
                        {row.status === 'PENDING' ? (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() => void actOnReminder(row.id, 'acknowledge')}
                          >
                            认领
                          </Button>
                        ) : null}
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          onClick={() => void actOnReminder(row.id, 'resolve')}
                        >
                          处置完成
                        </Button>
                      </div>
                    ) : null}
                  </div>
                ))}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={reminders.page <= 0 || busy}
                    onClick={() =>
                      setReminderFilters({ ...reminderFilters, page: reminderFilters.page - 1 })
                    }
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {reminders.page + 1} / {Math.max(reminders.totalPages, 1)} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={reminders.page + 1 >= reminders.totalPages || busy}
                    onClick={() =>
                      setReminderFilters({ ...reminderFilters, page: reminderFilters.page + 1 })
                    }
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                icon={<BellRing className="h-6 w-6" />}
                title="没有待处理的提醒"
                description="巡检会在「即将超时」与「已超时」时生成提醒；一旦确认揽收或签收，相应的提醒会被作废（留痕，不删除）。"
              />
            )}
          </SectionCard>
        </TabsContent>

        {/* ============================ 下游待通知 ============================ */}
        <TabsContent value="notifications">
          <SectionCard
            title={`下游待通知（${notifications?.totalElements ?? 0}）`}
            className="mt-4"
            description="9.4.2.1 回传与 15 客服在本页只是待办记录：状态到「到时机 · 待下游消费」为止，页面不写「已回传」。"
          >
            {notifications && notifications.items.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-2">类型</th>
                      <th className="p-2">归属下游</th>
                      <th className="p-2">状态</th>
                      <th className="p-2">到时机</th>
                      <th className="p-2">确认单</th>
                      <th className="p-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {notifications.items.map((row) => (
                      <tr key={row.id} className="border-b last:border-0">
                        <td className="p-2">
                          <div>{row.notificationKindLabel || row.notificationKind}</div>
                          <div className="text-muted-foreground text-xs">
                            {row.masterWaybillNo || '无运单号'} · {row.carrierCode}
                          </div>
                        </td>
                        <td className="p-2 text-xs">{row.downstreamModule}</td>
                        <td className="p-2">
                          <Badge tone={row.downstreamStatus === 'CONSUMED' ? 'success' : 'warning'}>
                            {row.downstreamStatusLabel || row.downstreamStatus}
                          </Badge>
                          {row.lastError ? (
                            <div className="text-destructive mt-1 text-xs">{row.lastError}</div>
                          ) : null}
                        </td>
                        <td className="p-2 text-xs">{formatMoment(row.dueAt)}</td>
                        <td className="p-2 text-xs">{row.caseNo}</td>
                        <td className="p-2">
                          {row.downstreamStatus === 'READY' ? (
                            <div className="flex gap-2">
                              <Button
                                size="sm"
                                variant="outline"
                                disabled={busy}
                                onClick={() => void consumeNotification(row.id, 'CONSUMED')}
                              >
                                登记已消费
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                disabled={busy}
                                onClick={() => void consumeNotification(row.id, 'FAILED')}
                              >
                                登记失败
                              </Button>
                            </div>
                          ) : null}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="mt-2 flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={notifications.page <= 0 || busy}
                    onClick={() =>
                      setNotificationFilters({
                        ...notificationFilters,
                        page: notificationFilters.page - 1,
                      })
                    }
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {notifications.page + 1} / {Math.max(notifications.totalPages, 1)} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={notifications.page + 1 >= notifications.totalPages || busy}
                    onClick={() =>
                      setNotificationFilters({
                        ...notificationFilters,
                        page: notificationFilters.page + 1,
                      })
                    }
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                title="没有待通知记录"
                description="待通知由确认揽收 / 签收时产生：揽收 → 平台履约回传，签收 → 评价邀请与售后窗口关闭。"
              />
            )}
          </SectionCard>
        </TabsContent>

        {/* ============================ 时限规则 ============================ */}
        <TabsContent value="rules">
          <SectionCard
            title="登记 / 修改时限规则"
            className="mt-4"
            description="规则改动不影响已存在的确认单 —— 它们的时限是建单时的快照（事后改档期不能让一笔已经延误的货变成准点）。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="psRuleCarrier" label="承运商">
                <Input
                  id="psRuleCarrier"
                  value={ruleForm.carrierCode}
                  placeholder="SF"
                  onChange={(e) => setRuleForm({ ...ruleForm, carrierCode: e.target.value })}
                />
              </FormField>
              <FormField id="psRuleHours" label="揽收时限（小时）">
                <Input
                  id="psRuleHours"
                  value={ruleForm.pickupSlaHours ?? ''}
                  placeholder="24"
                  onChange={(e) =>
                    setRuleForm({
                      ...ruleForm,
                      pickupSlaHours: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField id="psRuleLead" label="提醒提前量（小时）">
                <Input
                  id="psRuleLead"
                  value={ruleForm.reminderLeadHours ?? ''}
                  placeholder="4"
                  onChange={(e) =>
                    setRuleForm({
                      ...ruleForm,
                      reminderLeadHours: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField id="psRuleReview" label="评价邀请延迟（小时）">
                <Input
                  id="psRuleReview"
                  value={ruleForm.reviewInviteDelayHours ?? ''}
                  placeholder="0"
                  onChange={(e) =>
                    setRuleForm({
                      ...ruleForm,
                      reviewInviteDelayHours:
                        e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField id="psRuleWindow" label="售后窗口（小时）">
                <Input
                  id="psRuleWindow"
                  value={ruleForm.afterSalesWindowHours ?? ''}
                  placeholder="168"
                  onChange={(e) =>
                    setRuleForm({
                      ...ruleForm,
                      afterSalesWindowHours:
                        e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField id="psRuleNote" label="备注">
                <Input
                  id="psRuleNote"
                  value={ruleForm.note}
                  placeholder="与承运商合同口径"
                  onChange={(e) => setRuleForm({ ...ruleForm, note: e.target.value })}
                />
              </FormField>
              <div className="flex items-end">
                <Button size="sm" disabled={busy} onClick={() => void submitSlaRule()}>
                  保存时限规则
                </Button>
              </div>
            </div>
          </SectionCard>

          <SectionCard title={`时限规则（${rules?.totalElements ?? 0}）`} className="mt-4">
            {rules && rules.items.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-2">承运商</th>
                      <th className="p-2">渠道</th>
                      <th className="p-2">揽收时限</th>
                      <th className="p-2">提醒提前</th>
                      <th className="p-2">评价延迟</th>
                      <th className="p-2">售后窗口</th>
                      <th className="p-2">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rules.items.map((row) => (
                      <tr key={row.id} className="border-b last:border-0">
                        <td className="p-2">{row.carrierCode}</td>
                        <td className="p-2 text-xs">
                          {row.channelName || row.channelCode || '承运商级'}
                        </td>
                        <td className="p-2 text-xs">{row.pickupSlaHours} 小时</td>
                        <td className="p-2 text-xs">{row.reminderLeadHours} 小时</td>
                        <td className="p-2 text-xs">{row.reviewInviteDelayHours} 小时</td>
                        <td className="p-2 text-xs">{row.afterSalesWindowHours} 小时</td>
                        <td className="p-2">
                          <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {row.statusLabel}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState
                title="还没有时限规则"
                description="没有配规则的件会如实报「未配时限规则」，判不了超时 —— 不用默认值冒充一个看起来有依据的时限。"
              />
            )}
          </SectionCard>
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

      {/* ============================ 确认单详情抽屉 ============================ */}
      <DetailDrawer open={detailId !== ''} onOpenChange={(open) => !open && setDetailId('')}>
        <DetailDrawerContent width="lg">
          <DetailDrawerHeader>
            <DetailDrawerTitle>
              {detail ? detail.signoffCase.caseNo || '确认单详情' : '确认单详情'}
            </DetailDrawerTitle>
            <DetailDrawerDescription>
              {detail
                ? `${detail.signoffCase.masterWaybillNo || '无运单号'} · ${detail.signoffCase.carrierCode} · 第 ${detail.signoffCase.parcelNo} 件 · 批次 ${detail.signoffCase.sourceBatchNo}`
                : '正在读取…'}
            </DetailDrawerDescription>
          </DetailDrawerHeader>
          <DetailDrawerBody>
            {detailError ? (
              <Notice tone="danger" title="确认单详情读不到">
                {detailError}
              </Notice>
            ) : null}
            {detail ? (
              <div className="space-y-4">
                <div className="grid gap-3 sm:grid-cols-2">
                  <StatCard
                    label="状态"
                    value={detail.signoffCase.statusLabel || signoffStatusLabel(detail.signoffCase.status)}
                  />
                  <StatCard label="揽收" value={detail.signoffCase.pickupStateLabel} />
                </div>

                <SectionCard title="确认口径">
                  <dl className="space-y-2 text-sm">
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">交运时刻（责任起点）</dt>
                      <dd className="text-right">{formatMoment(detail.signoffCase.handoverAt)}</dd>
                    </div>
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">揽收时限（建单时快照）</dt>
                      <dd className="text-right">{deadlineText(detail.signoffCase)}</dd>
                    </div>
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">揽收</dt>
                      <dd className="text-right">{pickupText(detail.signoffCase)}</dd>
                    </div>
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">签收</dt>
                      <dd className="text-right">{deliveryText(detail.signoffCase)}</dd>
                    </div>
                    <div className="flex justify-between gap-4">
                      <dt className="text-muted-foreground">缺口</dt>
                      <dd className="text-right">{gapText(detail.signoffCase) || '无'}</dd>
                    </div>
                  </dl>
                </SectionCard>

                <SectionCard title="人工确认（承运商通道未接通时的凭据登记）">
                  <div className="grid gap-3 md:grid-cols-3">
                    <FormField id="psAttestKind" label="确认类型">
                      <Input
                        id="psAttestKind"
                        value={attestKind}
                        placeholder="PICKUP / DELIVERY"
                        onChange={(e) => setAttestKind(e.target.value)}
                      />
                    </FormField>
                    <FormField id="psAttestRef" label="凭据号（必填）">
                      <Input
                        id="psAttestRef"
                        value={attestRef}
                        placeholder="现场登记单号 / 短信回执号"
                        onChange={(e) => setAttestRef(e.target.value)}
                      />
                    </FormField>
                    <FormField id="psAttestNote" label="备注">
                      <Input
                        id="psAttestNote"
                        value={attestNote}
                        placeholder="可选"
                        onChange={(e) => setAttestNote(e.target.value)}
                      />
                    </FormField>
                  </div>
                  <div className="mt-3">
                    <Button size="sm" disabled={busy} onClick={() => void submitAttest()}>
                      登记人工确认
                    </Button>
                  </div>
                  <p className="text-muted-foreground mt-2 text-xs">
                    人工确认带凭据号与时刻，且**不覆盖**承运商事件的证据来源标记 ——
                    人工登记与承运商推送在账上是两个来源，混成一个就分不清「谁说的」。
                  </p>
                </SectionCard>

                <SectionCard title={`确认时间线（${detail.timeline.length}）`}>
                  {detail.timeline.length > 0 ? (
                    <ol className="space-y-2">
                      {detail.timeline.map((event) => (
                        <li key={event.id} className="border-border rounded-md border p-3">
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge
                              tone={
                                event.eventKind === 'PICKUP_OVERDUE' ? 'danger' : 'neutral'
                              }
                            >
                              {event.eventKindLabel || event.eventKind}
                            </Badge>
                            <span className="text-copy-13">{event.detail || '—'}</span>
                            <span className="text-muted-foreground text-xs">
                              {formatMoment(event.occurredAt)}
                            </span>
                          </div>
                          {event.carrierEventId ? (
                            <div className="text-muted-foreground mt-1 text-xs">
                              承运商事件号 {event.carrierEventId}
                            </div>
                          ) : null}
                        </li>
                      ))}
                    </ol>
                  ) : (
                    <EmptyState title="还没有确认事件" description="建单或巡检后会在这里留下变迁。" />
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
