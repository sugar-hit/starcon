'use client';

import { useCallback, useEffect, useState } from 'react';
import { BellRing, Radar, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import type {
  DelayThresholdRow,
  TrackingRow,
  TransitTrackCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import {
  acknowledgeDelayAlert,
  closeDelayAlert,
  closeTransitTracking,
  confirmTransitTrackingReceipt,
  createTransitTracking,
  delayLevelLabel,
  delayTone,
  dimensionCaption,
  evaluateAllAlerts,
  fetchDelayAlerts,
  fetchDelayThresholds,
  fetchTransitTrackingCapabilities,
  fetchTransitTrackingDetail,
  fetchTransitTrackings,
  fmt,
  legQuantityText,
  moment,
  recordTransitTrackingEvent,
  remainingWindow,
  sourceCaption,
  trackingActions,
  transitTrackingSortOptions,
  updateDelayThreshold,
  type TransitTrackingParams,
} from './transit-tracking-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 10.4.1.2 在途跟踪。
 *
 * <p>蓝图：「跟踪头程在途：位置、预计到港、延误预警、清关状态、目的仓收货确认，
 * 节点异常主动告警」；输出「在途看板 + 延误预警 + 到港提醒」；关系段原文
 * **「头程延误是断货的主要外因，预警必须提前到预计到港前若干天」**。
 *
 * <p>页面必须让四件事看得见：
 * ① **提前量口径常驻** —— 三档提前量来自阈值表且**可在页面上改**（改一行就换口径，不发版）；
 * ② **剩余窗口与「已经晚了」分开显示** —— 负数是已过预计到港仍未到货，严重度完全不同；
 * ③ **在途余量是借来的数** —— 原发 = 已了结 + 未达，守恒等式算出来显示，对不上就说对不上；
 * ④ **外部同步未接入如实写明** —— 没有连接器就没有「同步中」按钮，给一个点下去必然失败的按钮
 * 比不给更糟。
 */
export function TransitTrackingWorkspace() {
  const [params, setParams] = useState<TransitTrackingParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchTransitTrackings>> | null>(
    null,
  );
  const [caps, setCaps] = useState<TransitTrackCapabilities | null>(null);
  const [alerts, setAlerts] = useState<
    Awaited<ReturnType<typeof fetchDelayAlerts>> | null
  >(null);
  const [thresholds, setThresholds] = useState<DelayThresholdRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [newShipmentId, setNewShipmentId] = useState('');
  const [newLocation, setNewLocation] = useState('');
  const [newLocationAt, setNewLocationAt] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [page, alertPage, thresholdRows] = await Promise.all([
        fetchTransitTrackings(params),
        fetchDelayAlerts({ page: 0, size: 50 }),
        fetchDelayThresholds(),
      ]);
      setData(page);
      setAlerts(alertPage);
      setThresholds(thresholdRows);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchTransitTrackingCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是未交付依赖的说明缺失。
        // 最终裁判仍在后端（DB 的 CHECK 与状态机照旧生效）。
        setCaps(null);
      }
    })();
  }, []);

  const rows = data?.rows ?? [];
  const unavailable = (caps?.dependencies ?? []).filter((d) => !d.available);

  const run = async (work: () => Promise<unknown>) => {
    setBusy(true);
    setActionError(null);
    try {
      await work();
      await load();
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const submitCreate = async () => {
    if (!newShipmentId.trim()) {
      setActionError('建跟踪必须指定发运单：只有已发出的发运单才谈得上在途');
      return;
    }
    // 位置与位置发生时间成对：报位置却不说是什么时候的事，那条位置答不出问题。
    if (newLocation.trim() && !newLocationAt.trim()) {
      setActionError('填了位置就必须填位置发生时间');
      return;
    }
    await run(async () => {
      const created = await createTransitTracking({
        shipmentId: newShipmentId.trim(),
        currentLocation: newLocation.trim() || null,
        locationOccurredAt: newLocationAt ? new Date(newLocationAt).toISOString() : null,
        customsStatus: null,
        customsHoldReason: null,
        trackingStage: null,
        notes: null,
      });
      setCreating(false);
      setNewShipmentId('');
      setNewLocation('');
      setNewLocationAt('');
      setDetailId(created.id);
    });
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title="在途跟踪"
        description="头程在途的位置、预计到港、延误预警、清关状态与目的仓收货确认"
        actions={
          <>
            <Button variant="ghost" onClick={() => void load()}>
              <RefreshCw className="size-4" />
              刷新
            </Button>
            {/* 批量重算是手动触发的：常驻调度与外部连接器均未交付，
                这里**不**做一个「自动同步中」的假按钮。 */}
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => void run(() => evaluateAllAlerts())}
            >
              <BellRing className="size-4" />
              重算全部预警
            </Button>
            <Button onClick={() => setCreating(true)}>建跟踪</Button>
          </>
        }
      />

      {/* 口径常驻：这三条不写清，页面上的「延误」会被读成「已经晚了」 */}
      <SectionCard title="预警口径">
        <ul className="text-muted-foreground space-y-1 text-sm">
          <li>
            <strong className="text-foreground">提前分级，不是到港当天才发现</strong>
            ：剩余窗口 = 生效预计到港 − 现在，按阈值表逐级判定。三档提前量来自
            <code> first_mile_delay_threshold</code> 表，可在「阈值」页签里改，改一行就换口径，不发版。
          </li>
          <li>
            <strong className="text-foreground">负数不是「提前」</strong>
            ：剩余窗口为负 = 已经过了预计到港仍未到货，严重度与「还有几天到」完全不同，列表分开显示。
          </li>
          <li>
            <strong className="text-foreground">在途余量是借来的数</strong>
            ：发运 / 已了结 / 未达一律读 7.5.1.2 的在途腿，本模块不持有余额，也不写库存。
          </li>
          {(caps?.notes ?? []).map((note) => (
            <li key={note}>{note}</li>
          ))}
          {unavailable.map((d) => (
            <li key={d.code}>
              <strong className="text-foreground">{dimensionCaption(d)}</strong>：{d.note}
            </li>
          ))}
        </ul>
      </SectionCard>

      {data && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="在途跟踪"
            value={String(data.trackingTotal)}
            comparisonLabel="仍在跟踪中的批次（不含分页）"
          />
          <StatCard
            label="临近到港 / 延误"
            value={`${data.watchCount} / ${data.warningCount}`}
            comparisonLabel="提前提示区 / 延误预警区，按本租户阈值判定"
          />
          <StatCard
            label="严重延误"
            value={String(data.criticalCount)}
            comparisonLabel="已进入最紧一档"
          />
          <StatCard
            label="已过预计到港"
            value={String(data.overdueCount)}
            comparisonLabel={`清关被扣 / 退单 ${data.customsHeldCount} 条`}
          />
        </div>
      )}

      <Tabs defaultValue="board">
        <TabsList>
          <TabsTrigger value="board">在途看板</TabsTrigger>
          <TabsTrigger value="alerts">延误预警</TabsTrigger>
          <TabsTrigger value="thresholds">阈值口径</TabsTrigger>
        </TabsList>

        <TabsContent value="board" className="space-y-4">
          <SectionCard title="筛选">
            <div className="grid gap-3 md:grid-cols-4">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">关键字</span>
                <input
                  className="rounded-md border px-3 py-2"
                  placeholder="发运单号 / 承运人 / 订舱号 / 船名航班号 / 当前位置"
                  defaultValue={params.keyword ?? ''}
                  onBlur={(e) =>
                    setParams((p) => ({ ...p, keyword: e.target.value, page: 0 }))
                  }
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">预警档位</span>
                <select
                  className="rounded-md border px-3 py-2"
                  value={params.delayLevel?.[0] ?? ''}
                  onChange={(e) =>
                    setParams((p) => {
                      const next: TransitTrackingParams = { ...p, page: 0 };
                      if (e.target.value) next.delayLevel = [e.target.value];
                      else delete next.delayLevel;
                      return next;
                    })
                  }
                >
                  <option value="">全部档位</option>
                  {(data?.delayLevels ?? []).map((l) => (
                    <option key={l.code} value={l.code}>
                      {l.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">清关状态</span>
                <select
                  className="rounded-md border px-3 py-2"
                  value={params.customsStatus?.[0] ?? ''}
                  onChange={(e) =>
                    setParams((p) => {
                      const next: TransitTrackingParams = { ...p, page: 0 };
                      if (e.target.value) next.customsStatus = [e.target.value];
                      else delete next.customsStatus;
                      return next;
                    })
                  }
                >
                  <option value="">全部清关状态</option>
                  {(data?.customsStatuses ?? []).map((l) => (
                    <option key={l.code} value={l.code}>
                      {l.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">排序</span>
                <select
                  className="rounded-md border px-3 py-2"
                  value={params.sort ?? ''}
                  onChange={(e) =>
                    setParams((p) => {
                      const next: TransitTrackingParams = { ...p, page: 0 };
                      if (e.target.value) next.sort = e.target.value;
                      else delete next.sort;
                      return next;
                    })
                  }
                >
                  <option value="">默认（最近更新）</option>
                  {transitTrackingSortOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex items-center gap-2 text-sm md:col-span-4">
                <input
                  type="checkbox"
                  checked={params.delayedOnly ?? false}
                  onChange={(e) =>
                    setParams((p) => ({ ...p, delayedOnly: e.target.checked, page: 0 }))
                  }
                />
                <span>
                  只看有延误信号的（含<strong>临近到港提示区</strong>
                  —— 提前量最大的那一档正是最该被提前看见的）
                </span>
              </label>
            </div>
          </SectionCard>

          {actionError && (
            <SectionCard title="操作未完成">
              <div className="flex flex-col items-start gap-2">
                <TriangleAlert className="text-destructive size-5" />
                <p className="text-destructive text-sm">{actionError}</p>
              </div>
            </SectionCard>
          )}

          {loading && !data ? (
            <SectionCard title="在途看板">
              <p className="text-muted-foreground text-sm">正在加载在途跟踪…</p>
            </SectionCard>
          ) : error ? (
            <SectionCard title="在途看板">
              <div className="flex flex-col items-start gap-2">
                <TriangleAlert className="text-destructive size-5" />
                <p className="text-destructive text-sm">{error}</p>
                <Button variant="ghost" onClick={() => void load()}>
                  重试
                </Button>
              </div>
            </SectionCard>
          ) : rows.length === 0 ? (
            <SectionCard title="在途看板">
              <EmptyState
                icon={<Radar className="size-6 w-6" />}
                title="还没有在途跟踪"
                description="先在 10.4.1.1 把发运单发出去（只有已发出的单才谈得上在途），再回来建跟踪。"
              />
            </SectionCard>
          ) : (
            <SectionCard title={`在途看板（共 ${data?.total ?? 0} 条）`}>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="p-2">发运单 / 承运</th>
                      <th className="p-2">当前位置</th>
                      <th className="p-2">阶段 / 清关</th>
                      <th className="p-2">预计到港</th>
                      <th className="p-2">剩余窗口</th>
                      <th className="p-2">预警档位</th>
                      <th className="p-2 text-right">在途 / 已了结</th>
                      <th className="p-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r: TrackingRow) => {
                      const windowText = remainingWindow(r);
                      const actions = trackingActions(r);
                      return (
                        <tr key={r.id} className="border-b last:border-0">
                          <td className="p-2">
                            <button
                              className="underline-offset-2 hover:underline"
                              onClick={() => setDetailId(r.id)}
                            >
                              {r.shipmentCode}
                            </button>
                            <div className="text-muted-foreground text-xs">
                              {r.transportMode} · {r.carrierName || '承运人未登记'} ·{' '}
                              {r.destinationWarehouseName || '—'}
                            </div>
                          </td>
                          <td className="p-2">
                            {r.currentLocation || '—'}
                            <div className="text-muted-foreground text-xs">
                              {moment(r.locationOccurredAt)}
                            </div>
                          </td>
                          <td className="p-2">
                            {(data?.stages ?? []).find((s) => s.code === r.trackingStage)?.label ??
                              r.trackingStage}
                            <div className="text-muted-foreground text-xs">
                              清关{' '}
                              {(data?.customsStatuses ?? []).find(
                                (c) => c.code === r.customsStatus,
                              )?.label ?? r.customsStatus}
                            </div>
                          </td>
                          <td className="p-2">
                            {moment(r.effectiveEta)}
                            {r.etaRevisedCount > 0 ? (
                              <div className="text-muted-foreground text-xs">
                                已改期 {r.etaRevisedCount} 次（原 {moment(r.originalEta)}）
                              </div>
                            ) : null}
                          </td>
                          <td className="p-2">
                            <span className={windowText.late ? 'text-destructive' : undefined}>
                              {windowText.text}
                            </span>
                          </td>
                          <td className="p-2">
                            <Badge tone={delayTone(r.delayLevel)}>{delayLevelLabel(r.delayLevel)}</Badge>
                            {r.openAlertCount > 0 ? (
                              <div className="text-muted-foreground text-xs">
                                {r.openAlertCount} 条未结预警
                              </div>
                            ) : null}
                          </td>
                          <td className="p-2 text-right">
                            {fmt(r.inTransitQuantity)} / {fmt(r.settledQuantity)}
                            <div className="text-muted-foreground text-xs">
                              {r.legCount} 条在途腿
                            </div>
                          </td>
                          <td className="p-2">
                            <div className="flex flex-wrap gap-1">
                              <Button size="sm" variant="ghost" onClick={() => setDetailId(r.id)}>
                                详情
                              </Button>
                              {actions.includes('receipt') && (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  disabled={busy}
                                  onClick={() =>
                                    void run(() =>
                                      confirmTransitTrackingReceipt(r.id, {
                                        receiptReference: null,
                                        receivedAt: new Date().toISOString(),
                                        notes: null,
                                      }),
                                    )
                                  }
                                >
                                  收货确认
                                </Button>
                              )}
                              {actions.includes('close') && (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  disabled={busy}
                                  onClick={() => {
                                    const reason = window.prompt('关闭跟踪的原因（必填）');
                                    if (!reason?.trim()) return;
                                    void run(() => closeTransitTracking(r.id, reason.trim()));
                                  }}
                                >
                                  关闭
                                </Button>
                              )}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <div className="mt-3 flex items-center justify-between text-sm">
                <span className="text-muted-foreground">
                  第 {(params.page ?? 0) + 1} 页 / 共 {data?.total ?? 0} 条
                </span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={(params.page ?? 0) <= 0}
                    onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) - 1 }))}
                  >
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={
                  (params.page ?? 0) + 1 >= Math.ceil((data?.total ?? 0) / (params.size ?? 20))
                }
                    onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </SectionCard>
          )}
        </TabsContent>

        <TabsContent value="alerts">
          <SectionCard title={`延误预警（未结 ${alerts?.openCount ?? 0} / 已确认 ${alerts?.acknowledgedCount ?? 0}）`}>
            {(alerts?.rows ?? []).length === 0 ? (
              <EmptyState
                icon={<BellRing className="size-6 w-6" />}
                title="当前没有延误预警"
                description="预警按剩余窗口提前分级：到港前几天就会进区，不是等它晚了才报。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="p-2">发运单</th>
                      <th className="p-2">档位</th>
                      <th className="p-2">剩余窗口</th>
                      <th className="p-2">说明</th>
                      <th className="p-2">状态</th>
                      <th className="p-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(alerts?.rows ?? []).map((a) => (
                      <tr key={a.id} className="border-b last:border-0">
                        <td className="p-2">{a.shipmentCode || '—'}</td>
                        <td className="p-2">
                          <Badge tone={delayTone(a.level)}>{delayLevelLabel(a.level)}</Badge>
                        </td>
                        <td className="p-2">{a.remainingDays ?? '—'}</td>
                        <td className="p-2">{a.message}</td>
                        <td className="p-2">
                          {a.status === 'OPEN'
                            ? '未处理'
                            : a.status === 'ACKNOWLEDGED'
                              ? '已确认'
                              : `已关闭${a.closeReason ? `：${a.closeReason}` : ''}`}
                          <div className="text-muted-foreground text-xs">
                            {moment(a.raisedAt)}
                          </div>
                        </td>
                        <td className="p-2">
                          <div className="flex flex-wrap gap-1">
                            {a.status === 'OPEN' && (
                              <Button
                                size="sm"
                                variant="outline"
                                disabled={busy}
                                onClick={() =>
                                  void run(() =>
                                    acknowledgeDelayAlert(a.id, { note: '已联系货代' }),
                                  )
                                }
                              >
                                确认
                              </Button>
                            )}
                            {a.status !== 'CLOSED' && (
                              <Button
                                size="sm"
                                variant="ghost"
                                disabled={busy}
                                onClick={() => {
                                  const reason = window.prompt(
                                    '关闭预警的原因（必填：货到了 / 改期了 / 查明原因都算）',
                                  );
                                  if (!reason?.trim()) return;
                                  void run(() => closeDelayAlert(a.id, { reason: reason.trim() }));
                                }}
                              >
                                关闭
                              </Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>
        </TabsContent>

        <TabsContent value="thresholds">
          <SectionCard title="预警阈值口径（改一行就换口径，不发版）">
            <p className="text-muted-foreground mb-3 text-sm">
              三档提前量按 (运输方式, 目的国) 逐级回落匹配，最差命中{' '}
              <code>(*, *)</code> 通用行。三级必须递减，否则「进观察区」反而比「进提示区」晚。
            </p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="p-2">适用</th>
                    <th className="p-2 text-right">提示（天）</th>
                    <th className="p-2 text-right">预警（天）</th>
                    <th className="p-2 text-right">严重（天）</th>
                    <th className="p-2">在用</th>
                    <th className="p-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {thresholds.map((t) => (
                    <tr key={t.id} className="border-b last:border-0">
                      <td className="p-2">
                        {t.transportMode === '*' ? '全部运输方式' : t.transportMode} ·{' '}
                        {t.countryCode === '*' ? '不限国家' : t.countryCode}
                        <div className="text-muted-foreground text-xs">{t.notes}</div>
                      </td>
                      <td className="p-2 text-right">{t.watchLeadDays}</td>
                      <td className="p-2 text-right">{t.warningLeadDays}</td>
                      <td className="p-2 text-right">{t.criticalLeadDays}</td>
                      <td className="p-2">{t.inUse ? '正被在途批次命中' : '—'}</td>
                      <td className="p-2">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          onClick={() => {
                            const watch = window.prompt(
                              '提示区提前几天（进区后显示「临近到港」）',
                              String(t.watchLeadDays),
                            );
                            if (watch === null) return;
                            const warning = window.prompt(
                              '预警区提前几天（必须 ≤ 提示区）',
                              String(t.warningLeadDays),
                            );
                            if (warning === null) return;
                            const critical = window.prompt(
                              '严重区提前几天',
                              String(t.criticalLeadDays),
                            );
                            if (critical === null) return;
                            const watchN = Number(watch);
                            const warningN = Number(warning);
                            const criticalN = Number(critical);
                            if (
                              ![watchN, warningN, criticalN].every((n) => Number.isInteger(n) && n >= 0)
                            ) {
                              setActionError('三档提前量必须是不小于 0 的整数天');
                              return;
                            }
                            void run(() =>
                              updateDelayThreshold(t.id, {
                                watchLeadDays: watchN,
                                warningLeadDays: warningN,
                                criticalLeadDays: criticalN,
                                notes: null,
                              }),
                            );
                          }}
                        >
                          改口径
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>
        </TabsContent>
      </Tabs>

      {creating && (
        <Modal open={creating} onOpenChange={setCreating}>
          <ModalContent>
            <ModalHeader>
              <ModalTitle>建在途跟踪</ModalTitle>
              <ModalDescription>
                一张发运单一条跟踪记录，只有**已发出**的发运单才谈得上在途。
              </ModalDescription>
            </ModalHeader>
            <div className="space-y-3">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">发运单 id（10.4.1.1 已发出的单）</span>
                <input
                  className="rounded-md border px-3 py-2"
                  value={newShipmentId}
                  onChange={(e) => setNewShipmentId(e.target.value)}
                  placeholder="uuid"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">初始位置（可选）</span>
                <input
                  className="rounded-md border px-3 py-2"
                  value={newLocation}
                  onChange={(e) => setNewLocation(e.target.value)}
                  placeholder="上海洋山港"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">
                  位置发生时间（填了位置就必填）
                </span>
                <input
                  type="datetime-local"
                  className="rounded-md border px-3 py-2"
                  value={newLocationAt}
                  onChange={(e) => setNewLocationAt(e.target.value)}
                />
              </label>
            </div>
            <ModalFooter>
              <Button variant="ghost" onClick={() => setCreating(false)}>
                取消
              </Button>
              <Button disabled={busy} onClick={() => void submitCreate()}>
                建立跟踪
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      )}

      {detailId && (
        <TrackingDetailDialog
          trackingId={detailId}
          busy={busy}
          onClose={() => setDetailId(null)}
          onRun={run}
        />
      )}
    </div>
  );
}

/** 跟踪详情：节点事件流 + 在途腿（7.5.1.2 名下）+ 预警 + 当前阈值口径。 */
function TrackingDetailDialog({
  trackingId,
  busy,
  onClose,
  onRun,
}: {
  trackingId: string;
  busy: boolean;
  onClose: () => void;
  onRun: (work: () => Promise<unknown>) => Promise<void>;
}) {
  const [detail, setDetail] = useState<
    Awaited<ReturnType<typeof fetchTransitTrackingDetail>> | null
  >(null);
  const [err, setErr] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [location, setLocation] = useState('');
  const [locationAt, setLocationAt] = useState('');
  const [newEta, setNewEta] = useState('');
  const [etaReason, setEtaReason] = useState('');
  const [source, setSource] = useState('MANUAL');
  const [referenceId, setReferenceId] = useState('');

  const reload = useCallback(async () => {
    try {
      setDetail(await fetchTransitTrackingDetail(trackingId));
    } catch (e) {
      setErr(errorText(e));
    }
  }, [trackingId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const submitLocation = () => {
    if (!location.trim() || !locationAt.trim()) {
      setErr('位置与位置发生时间必须成对给出');
      return;
    }
    if (source !== 'MANUAL' && !referenceId.trim()) {
      setErr('外部来源必须给出该来源系统里的单号：写不出出处的外部事实不入库');
      return;
    }
    void onRun(async () => {
      await recordTransitTrackingEvent(trackingId, {
        kind: 'LOCATION',
        sourceSystem: source,
        referenceId: source === 'MANUAL' ? null : referenceId.trim(),
        locationText: location.trim(),
        locationOccurredAt: new Date(locationAt).toISOString(),
        etaAfter: null,
        reason: null,
        customsStatus: null,
        customsHoldReason: null,
        toStage: null,
        detail: null,
        occurredAt: new Date().toISOString(),
      });
      setLocation('');
      setLocationAt('');
      await reload();
    });
  };

  const submitEta = () => {
    if (!newEta.trim() || !etaReason.trim()) {
      setErr('改期必须同时给出新的预计到港与原因');
      return;
    }
    if (source !== 'MANUAL' && !referenceId.trim()) {
      setErr('外部来源必须给出该来源系统里的单号');
      return;
    }
    void onRun(async () => {
      await recordTransitTrackingEvent(trackingId, {
        kind: 'ETA_REVISED',
        sourceSystem: source,
        referenceId: source === 'MANUAL' ? null : referenceId.trim(),
        locationText: null,
        locationOccurredAt: null,
        etaAfter: new Date(newEta).toISOString(),
        reason: etaReason.trim(),
        customsStatus: null,
        customsHoldReason: null,
        toStage: null,
        detail: null,
        occurredAt: new Date().toISOString(),
      });
      setNewEta('');
      setEtaReason('');
      await reload();
    });
  };

  const submitNote = () => {
    if (!note.trim()) return;
    void onRun(async () => {
      await recordTransitTrackingEvent(trackingId, {
        kind: 'NOTE',
        sourceSystem: 'MANUAL',
        referenceId: null,
        locationText: null,
        locationOccurredAt: null,
        etaAfter: null,
        reason: null,
        customsStatus: null,
        customsHoldReason: null,
        toStage: null,
        detail: note.trim(),
        occurredAt: new Date().toISOString(),
      });
      setNote('');
      await reload();
    });
  };

  const windowText = detail ? remainingWindow(detail.tracking) : null;

  return (
    <Modal open onOpenChange={(open) => (open ? undefined : onClose())}>
      <ModalContent className="max-w-3xl">
        <ModalHeader>
          <ModalTitle>
            在途跟踪 {detail?.tracking.shipmentCode ?? ''}
            {detail ? ` · ${delayLevelLabel(detail.tracking.delayLevel)}` : ''}
          </ModalTitle>
          <ModalDescription>
            {windowText ? windowText.text : '加载中…'}
            {detail ? ` · 阶段 ${detail.tracking.trackingStage}` : ''}
          </ModalDescription>
        </ModalHeader>

        {err && <p className="text-destructive text-sm">{err}</p>}

        {detail && (
          <div className="space-y-4">
            <div className="grid gap-2 text-sm md:grid-cols-3">
              <div>
                <div className="text-muted-foreground text-xs">当前位置</div>
                <div>
                  {detail.tracking.currentLocation || '—'}{' '}
                  <span className="text-muted-foreground text-xs">
                    {moment(detail.tracking.locationOccurredAt)}
                  </span>
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">
                  预计到港（改期 {detail.tracking.etaRevisedCount} 次）
                </div>
                <div>{moment(detail.tracking.effectiveEta)}</div>
                {detail.tracking.originalEta ? (
                  <div className="text-muted-foreground text-xs">
                    原 {moment(detail.tracking.originalEta)}
                  </div>
                ) : null}
              </div>
              <div>
                <div className="text-muted-foreground text-xs">当前生效阈值口径</div>
                <div className="text-xs">{detail.threshold.basis}</div>
              </div>
            </div>

            <SectionCard title="在途腿（7.5.1.2 名下，本模块只读）">
              <ul className="space-y-1 text-sm">
                {detail.legs.map((leg) => {
                  const qty = legQuantityText(leg);
                  return (
                    <li key={leg.legId}>
                      第 {leg.lineNo} 行 {leg.skuCode} {leg.skuName}：
                      <span className={qty.ok ? undefined : 'text-destructive'}>{qty.text}</span>
                      <span className="text-muted-foreground text-xs">
                        {' '}
                        · 预计到港 {moment(leg.expectedArriveAt)}
                        {leg.trackingNo ? ` · 运单 ${leg.trackingNo}` : ''}
                      </span>
                    </li>
                  );
                })}
                {detail.legs.length === 0 ? (
                  <li className="text-muted-foreground">暂无在途腿（发出时由 10.4.1.1 登记）</li>
                ) : null}
              </ul>
            </SectionCard>

            <SectionCard title="登记节点">
              <div className="space-y-3 text-sm">
                <div className="grid gap-2 md:grid-cols-2">
                  <label className="flex flex-col gap-1">
                    <span className="text-muted-foreground">事实来源</span>
                    <select
                      className="rounded-md border px-3 py-2"
                      value={source}
                      onChange={(e) => setSource(e.target.value)}
                    >
                      <option value="MANUAL">人工登记</option>
                      <option value="FORWARDER">货代</option>
                      <option value="CARRIER">船司/航空公司</option>
                      <option value="CUSTOMS_BROKER">清关行/报关行</option>
                    </select>
                  </label>
                  <label className="flex flex-col gap-1">
                    <span className="text-muted-foreground">
                      来源单号（外部来源必填）
                    </span>
                    <input
                      className="rounded-md border px-3 py-2"
                      value={referenceId}
                      onChange={(e) => setReferenceId(e.target.value)}
                    />
                  </label>
                </div>
                <div className="grid gap-2 md:grid-cols-2">
                  <input
                    className="rounded-md border px-3 py-2"
                    placeholder="当前位置，如 Singapore"
                    value={location}
                    onChange={(e) => setLocation(e.target.value)}
                  />
                  <input
                    type="datetime-local"
                    className="rounded-md border px-3 py-2"
                    value={locationAt}
                    onChange={(e) => setLocationAt(e.target.value)}
                  />
                </div>
                <Button size="sm" disabled={busy} onClick={submitLocation}>
                  登记位置
                </Button>

                <div className="grid gap-2 md:grid-cols-2">
                  <input
                    type="datetime-local"
                    className="rounded-md border px-3 py-2"
                    value={newEta}
                    onChange={(e) => setNewEta(e.target.value)}
                  />
                  <input
                    className="rounded-md border px-3 py-2"
                    placeholder="改期原因（必填）"
                    value={etaReason}
                    onChange={(e) => setEtaReason(e.target.value)}
                  />
                </div>
                <Button size="sm" disabled={busy} onClick={submitEta}>
                  登记改期
                </Button>

                <div className="flex gap-2">
                  <input
                    className="rounded-md border px-3 py-2 flex-1"
                    placeholder="备注"
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                  />
                  <Button size="sm" variant="outline" disabled={busy} onClick={submitNote}>
                    追加备注
                  </Button>
                </div>
              </div>
            </SectionCard>

            <SectionCard title="节点事件流">
              <ul className="space-y-2 text-sm">
                {detail.events.map((e) => (
                  <li key={e.id} className="border-b last:border-0 pb-1">
                    <div>
                      <strong>{e.kind}</strong> · {sourceCaption(e)} ·{' '}
                      {moment(e.occurredAt)}
                    </div>
                    {e.locationText ? <div>位置：{e.locationText}</div> : null}
                    {e.etaAfter ? (
                      <div>
                        ETA {moment(e.etaBefore)} → {moment(e.etaAfter)}
                      </div>
                    ) : null}
                    {e.detail ? <div className="text-muted-foreground">{e.detail}</div> : null}
                  </li>
                ))}
              </ul>
            </SectionCard>

            <SectionCard title="预警">
              {detail.alerts.length === 0 ? (
                <p className="text-muted-foreground text-sm">当前没有预警行</p>
              ) : (
                <ul className="space-y-1 text-sm">
                  {detail.alerts.map((a) => (
                    <li key={a.id}>
                      {delayLevelLabel(a.level)} · 剩余 {a.remainingDays} 天 · {a.status} ·{' '}
                      {a.message}
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          </div>
        )}

        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}