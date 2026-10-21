'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { CalendarClock, MessageSquare, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import type {
  PurchaseFollowupCapabilities,
  PurchaseFollowupChangePage,
  PurchaseFollowupChangeRow,
  PurchaseFollowupDeliveryPage,
  PurchaseFollowupDeliveryStats,
  PurchaseFollowupRecordPage,
  PurchaseFollowupRecordRow,
  PurchaseFollowupTimelinePage,
  PurchaseFollowupWritebackRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  arrivalStatusLabel,
  averageDelayText,
  delayDaysText,
  delayDaysTone,
  fetchPurchaseFollowupCapabilities,
  fetchPurchaseFollowupChanges,
  fetchPurchaseFollowupDelivery,
  fetchPurchaseFollowupStats,
  fetchPurchaseFollowupTimeline,
  fetchPurchaseFollowups,
  fmtDate,
  fmtDateTime,
  followupChannelLabel,
  followupTypeLabel,
  onTimeRateText,
  overdueDaysText,
  promiseText,
  recordPurchaseFollowup,
  recordPurchaseFollowupChange,
  timelineKindLabel,
  writebackStatusText,
  writebackTargetLabel,
  writebackTone,
  type PurchaseFollowupChangeParams,
  type PurchaseFollowupDeliveryParams,
  type PurchaseFollowupParams,
} from './purchase-followup-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.3.2.1 跟单与交期工作区。
 *
 * <p>蓝图：「记录与供应商的沟通跟进（催单、确认、异常反馈），维护承诺到货日变更历史，
 * 统计平均延迟天数。」输出「跟单时间线；交期变更历史；延迟统计。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>两个承诺口径并排显示</b>：单据承诺日（6.3.1.1 持有）与跟单承诺日（本模块）。
 *       不一致时两个都摆出来 —— 只显示一个会让人以为采购单上也改了。</li>
 *   <li><b>「没到货」与「延迟 0」是两件事</b>：没到货显示「未到货（不计延迟）」，
 *       另给「已逾期 N 天」；把没到货算成 0 天会让供应商看起来完美。</li>
 *   <li><b>延迟可以为负</b>（提前到货）⇒ 显示「提前 N 天」，不显示成逾期。</li>
 *   <li><b>回写如实呈现</b>：成功 / 失败 / 跳过各有徽标与原因；
 *       「交期数值没有进参数」这件事单独标出来，<b>不假装已经回写</b>。</li>
 *   <li><b>「催单」是沟通</b>：本模块的催单记录显示的是「谁、何时、说了什么、供应商怎么答」，
 *       与 6.3.1.1 采购单上的「催过几次」不是一回事，页面不混着说。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 */
export function PurchaseFollowupWorkspace() {
  const [view, setView] = useState<'timeline' | 'followups' | 'changes' | 'delivery'>('timeline');

  const [params, setParams] = useState<PurchaseFollowupParams>({ page: 0, size: 20 });
  const [changeParams, setChangeParams] = useState<PurchaseFollowupChangeParams>({
    page: 0,
    size: 20,
  });
  const [deliveryParams, setDeliveryParams] = useState<PurchaseFollowupDeliveryParams>({
    page: 0,
    size: 20,
  });

  const [caps, setCaps] = useState<PurchaseFollowupCapabilities | null>(null);
  const [stats, setStats] = useState<PurchaseFollowupDeliveryStats | null>(null);
  const [timeline, setTimeline] = useState<PurchaseFollowupTimelinePage | null>(null);
  const [followups, setFollowups] = useState<PurchaseFollowupRecordPage | null>(null);
  const [changes, setChanges] = useState<PurchaseFollowupChangePage | null>(null);
  const [delivery, setDelivery] = useState<PurchaseFollowupDeliveryPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选器的输入态：查询时才落到 params（输入过程中的半截值不该打后端）。
  const [purchaseOrderNo, setPurchaseOrderNo] = useState('');
  const [keyword, setKeyword] = useState('');
  const [followupType, setFollowupType] = useState('');
  const [onlyOverdue, setOnlyOverdue] = useState(false);
  const [onlyDelayed, setOnlyDelayed] = useState(false);

  // 登记弹窗
  const [followupOpen, setFollowupOpen] = useState(false);
  const [changeOpen, setChangeOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [capsResult, statsResult] = await Promise.all([
        fetchPurchaseFollowupCapabilities(),
        fetchPurchaseFollowupStats(),
      ]);
      setCaps(capsResult);
      setStats(statsResult);
      if (view === 'timeline') {
        setTimeline(await fetchPurchaseFollowupTimeline(params));
      } else if (view === 'followups') {
        setFollowups(await fetchPurchaseFollowups(params));
      } else if (view === 'changes') {
        setChanges(await fetchPurchaseFollowupChanges(changeParams));
      } else {
        setDelivery(await fetchPurchaseFollowupDelivery(deliveryParams));
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [changeParams, deliveryParams, params, view]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const nextFollowupParams = useCallback(
    (): PurchaseFollowupParams => ({
      ...params,
      purchaseOrderNo: purchaseOrderNo.trim() || undefined,
      keyword: keyword.trim() || undefined,
      followupType: followupType || undefined,
      page: 0,
    }),
    [followupType, keyword, params, purchaseOrderNo],
  );

  const nextChangeParams = useCallback(
    (): PurchaseFollowupChangeParams => ({
      ...changeParams,
      purchaseOrderNo: purchaseOrderNo.trim() || undefined,
      keyword: keyword.trim() || undefined,
      page: 0,
    }),
    [changeParams, keyword, purchaseOrderNo],
  );

  const nextDeliveryParams = useCallback(
    (): PurchaseFollowupDeliveryParams => ({
      ...deliveryParams,
      keyword: keyword.trim() || undefined,
      onlyOverdue,
      onlyDelayed,
      page: 0,
    }),
    [deliveryParams, keyword, onlyDelayed, onlyOverdue],
  );

  const goPage = useCallback(
    (page: number) => {
      if (view === 'timeline') setParams((prev) => ({ ...prev, page }));
      else if (view === 'followups') setParams((prev) => ({ ...prev, page }));
      else if (view === 'changes') setChangeParams((prev) => ({ ...prev, page }));
      else setDeliveryParams((prev) => ({ ...prev, page }));
    },
    [view],
  );

  const total =
    view === 'timeline'
      ? (timeline?.total ?? 0)
      : view === 'followups'
        ? (followups?.total ?? 0)
        : view === 'changes'
          ? (changes?.total ?? 0)
          : (delivery?.total ?? 0);
  const size = 20;
  const page =
    view === 'timeline' || view === 'followups'
      ? (params.page ?? 0)
      : view === 'changes'
        ? (changeParams.page ?? 0)
        : (deliveryParams.page ?? 0);
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);

  const rating = stats?.ratingWriteback;
  const writebackRows = useMemo(
    () => (changes?.rows ?? []).flatMap((row) => row.writeback.map((w) => ({ change: row, w }))),
    [changes],
  );

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="跟单与交期"
        description="记录与供应商的沟通跟进（催单、确认、异常反馈），维护承诺到货日变更历史，统计平均延迟天数。交期变更落库后会回写 6.2.1.1 断货风险与 6.2.1.2 补货建议。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button variant="secondary" onClick={() => setFollowupOpen(true)} disabled={busy}>
              <MessageSquare />
              登记跟单
            </Button>
            <Button onClick={() => setChangeOpen(true)} disabled={busy}>
              <CalendarClock />
              登记交期变更
            </Button>
          </div>
        }
      />

      {/* 口径常驻：延迟天数与两个承诺口径必须一直摆在眼前 */}
      {caps && (
        <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div className="flex flex-col gap-1">
            <p className="font-medium">延迟与承诺口径</p>
            {caps.notes.map((note) => (
              <p key={note} className="text-muted-foreground text-xs">
                {note}
              </p>
            ))}
          </div>
        </div>
      )}

      {stats && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="平均延迟天数"
            value={averageDelayText(stats.averageDelayDays)}
            comparisonLabel={`已全部到货 ${stats.arrivedOrderCount} 张`}
            polarity={delayDaysTone(stats.averageDelayDays)}
          />
          <StatCard
            label="准时率"
            value={onTimeRateText(stats.onTimeRate)}
            comparisonLabel={`准时 ${stats.onTimeCount} 张`}
            polarity={stats.onTimeRate !== null && stats.onTimeRate >= 90 ? 'positive' : 'neutral'}
          />
          <StatCard
            label="未到货 / 其中已逾期"
            value={`${stats.pendingOrderCount} / ${stats.overduePendingCount}`}
            comparisonLabel={
              stats.averageOverdueDays !== null
                ? `平均已逾期 ${Number(stats.averageOverdueDays.toFixed(2))} 天`
                : '暂无逾期样本'
            }
            polarity={stats.overduePendingCount > 0 ? 'negative' : 'neutral'}
          />
          <StatCard
            label="交期变更"
            value={stats.changeCount}
            comparisonLabel={`涉及 ${stats.changedOrderCount} 张采购单`}
          />
        </div>
      )}

      {/* 下游缺口常驻：6.1.1.3 评级未交付，页面必须说清，而不是显示成空白 */}
      {rating && !rating.available && (
        <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div className="flex flex-col gap-1">
            <p className="font-medium">供应商评级（{rating.ownerModule}）尚未交付</p>
            <p className="text-muted-foreground text-xs">{rating.impact}</p>
            <p className="text-muted-foreground text-xs">{rating.note}</p>
          </div>
        </div>
      )}

      <SectionCard title="视图">
        <Segmented
          value={view}
          onChange={(v) => setView(v as typeof view)}
          options={[
            { value: 'timeline', label: '跟单时间线' },
            { value: 'followups', label: '跟单记录' },
            { value: 'changes', label: '交期变更历史' },
            { value: 'delivery', label: '交付实况' },
          ]}
        />
      </SectionCard>

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          {view !== 'delivery' && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">采购单号</span>
              <Input
                value={purchaseOrderNo}
                placeholder="精确匹配"
                onChange={(e) => setPurchaseOrderNo(e.target.value)}
                className="w-48"
              />
            </label>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="单号 / 供应商 / 内容"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-56"
            />
          </label>
          {view === 'followups' && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">跟单类型</span>
              <select
                className="h-9 rounded-md border px-2"
                value={followupType}
                onChange={(e) => setFollowupType(e.target.value)}
              >
                <option value="">全部</option>
                {['URGING', 'CONFIRM', 'EXCEPTION', 'OTHER'].map((t) => (
                  <option key={t} value={t}>
                    {followupTypeLabel(t)}
                  </option>
                ))}
              </select>
            </label>
          )}
          {view === 'delivery' && (
            <>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={onlyOverdue}
                  onChange={(e) => setOnlyOverdue(e.target.checked)}
                />
                只看已逾期
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={onlyDelayed}
                  onChange={(e) => setOnlyDelayed(e.target.checked)}
                />
                只看已延迟
              </label>
            </>
          )}
          <Button
            onClick={() => {
              if (view === 'timeline' || view === 'followups') setParams(nextFollowupParams());
              else if (view === 'changes') setChangeParams(nextChangeParams());
              else setDeliveryParams(nextDeliveryParams());
            }}
          >
            查询
          </Button>
          <Button
            variant="ghost"
            onClick={() => {
              setPurchaseOrderNo('');
              setKeyword('');
              setFollowupType('');
              setOnlyOverdue(false);
              setOnlyDelayed(false);
              if (view === 'timeline' || view === 'followups') setParams({ page: 0, size });
              else if (view === 'changes') setChangeParams({ page: 0, size });
              else setDeliveryParams({ page: 0, size });
            }}
          >
            重置
          </Button>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {notice && (
        <div className="flex items-start gap-2 rounded-md border border-emerald-500/40 bg-emerald-500/10 p-3 text-sm text-emerald-700">
          <p>{notice}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载跟单与交期数据…</p>}

      {!loading && total === 0 && !error && (
        <EmptyState
          title="没有跟单与交期记录"
          description="当前筛选条件下没有记录。跟单记录登记的是「与供应商说了什么」，交期变更登记的是「承诺到货日改到了哪天」—— 两者都是举证凭据，登记后不可改写。"
        />
      )}

      {!loading && view === 'timeline' && (timeline?.rows.length ?? 0) > 0 && (
        <SectionCard title={`跟单时间线（${total}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">时刻</th>
                  <th className="px-2 py-1">类型</th>
                  <th className="px-2 py-1">采购单号</th>
                  <th className="px-2 py-1">供应商</th>
                  <th className="px-2 py-1">标题</th>
                  <th className="px-2 py-1">内容 / 原因</th>
                  <th className="px-2 py-1">交期变更</th>
                </tr>
              </thead>
              <tbody>
                {timeline?.rows.map((row) => (
                  <tr key={`${row.kind}-${row.id}`} className="border-t">
                    <td className="whitespace-nowrap px-2 py-1">{fmtDateTime(row.occurredAt)}</td>
                    <td className="px-2 py-1">
                      <Badge tone="neutral">{timelineKindLabel(row.kind)}</Badge>
                    </td>
                    <td className="px-2 py-1">{row.purchaseOrderNo}</td>
                    <td className="px-2 py-1">
                      {row.supplierCode} · {row.supplierName}
                    </td>
                    <td className="px-2 py-1">{row.title}</td>
                    <td className="px-2 py-1">{row.detail}</td>
                    <td className="whitespace-nowrap px-2 py-1">
                      {row.fromPromisedDate && row.toPromisedDate
                        ? `${fmtDate(row.fromPromisedDate)} → ${fmtDate(row.toPromisedDate)}`
                        : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager total={total} page={page} size={size} lastPage={lastPage} onGo={goPage} />
        </SectionCard>
      )}

      {!loading && view === 'followups' && (followups?.rows.length ?? 0) > 0 && (
        <SectionCard title={`跟单记录（${total}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">跟单号</th>
                  <th className="px-2 py-1">采购单号</th>
                  <th className="px-2 py-1">供应商</th>
                  <th className="px-2 py-1">类型</th>
                  <th className="px-2 py-1">渠道</th>
                  <th className="px-2 py-1">沟通内容</th>
                  <th className="px-2 py-1">供应商答复</th>
                  <th className="px-2 py-1">发生时刻</th>
                </tr>
              </thead>
              <tbody>
                {followups?.rows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1">{row.followupNo}</td>
                    <td className="px-2 py-1">{row.purchaseOrderNo}</td>
                    <td className="px-2 py-1">
                      {row.supplierCode} · {row.supplierName}
                    </td>
                    <td className="px-2 py-1">
                      <Badge tone="info">{row.followupTypeLabel}</Badge>
                    </td>
                    <td className="px-2 py-1">{row.channelLabel}</td>
                    <td className="px-2 py-1">{row.content}</td>
                    <td className="px-2 py-1">{row.supplierReply || '—'}</td>
                    <td className="whitespace-nowrap px-2 py-1">{fmtDateTime(row.occurredAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager total={total} page={page} size={size} lastPage={lastPage} onGo={goPage} />
        </SectionCard>
      )}

      {!loading && view === 'changes' && (changes?.rows.length ?? 0) > 0 && (
        <>
          <SectionCard title={`交期变更历史（${total}）`}>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="px-2 py-1">变更号</th>
                    <th className="px-2 py-1">采购单号</th>
                    <th className="px-2 py-1">供应商</th>
                    <th className="px-2 py-1">变更前 → 变更后</th>
                    <th className="px-2 py-1">原因</th>
                    <th className="px-2 py-1">承诺口径</th>
                    <th className="px-2 py-1">变更时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {changes?.rows.map((row) => (
                    <tr key={row.id} className="border-t">
                      <td className="px-2 py-1">{row.changeNo}</td>
                      <td className="px-2 py-1">{row.purchaseOrderNo}</td>
                      <td className="px-2 py-1">
                        {row.supplierCode} · {row.supplierName}
                      </td>
                      <td className="whitespace-nowrap px-2 py-1">
                        {fmtDate(row.fromPromisedDate)} → {fmtDate(row.toPromisedDate)}
                      </td>
                      <td className="px-2 py-1">{row.reason}</td>
                      <td className="px-2 py-1">{promiseText(row)}</td>
                      <td className="whitespace-nowrap px-2 py-1">{fmtDateTime(row.changedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager total={total} page={page} size={size} lastPage={lastPage} onGo={goPage} />
          </SectionCard>

          {/* 回写留痕：成功 / 失败 / 跳过都摊开，附上原因与影响 */}
          {writebackRows.length > 0 && (
            <SectionCard title="回写留痕（交期变更 → 下游重算）">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">变更号</th>
                      <th className="px-2 py-1">目标模块</th>
                      <th className="px-2 py-1">写入入口</th>
                      <th className="px-2 py-1">结果</th>
                      <th className="px-2 py-1">结果定位</th>
                      <th className="px-2 py-1">原因 / 影响</th>
                    </tr>
                  </thead>
                  <tbody>
                    {writebackRows.map(({ change, w }) => (
                      <tr key={w.id} className="border-t">
                        <td className="px-2 py-1">{change.changeNo}</td>
                        <td className="px-2 py-1">{writebackTargetLabel(w.targetModule)}</td>
                        <td className="px-2 py-1 text-xs">{w.targetEndpoint}</td>
                        <td className="px-2 py-1">
                          <Badge
                            tone={
                              writebackTone(w) === 'negative'
                                ? 'danger'
                                : writebackTone(w) === 'positive'
                                  ? 'success'
                                  : 'warning'
                            }
                          >
                            {writebackStatusText(w)}
                          </Badge>
                        </td>
                        <td className="px-2 py-1">{w.resultRef || '—'}</td>
                        <td className="px-2 py-1 text-xs">
                          {w.errorMessage || w.resultSummary || w.impact || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}
        </>
      )}

      {!loading && view === 'delivery' && (delivery?.rows.length ?? 0) > 0 && (
        <SectionCard title={`逐单交付实况（${total}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">采购单号</th>
                  <th className="px-2 py-1">供应商</th>
                  <th className="px-2 py-1">承诺口径</th>
                  <th className="px-2 py-1">到货状态</th>
                  <th className="px-2 py-1">实际到货日</th>
                  <th className="px-2 py-1">延迟天数</th>
                  <th className="px-2 py-1">已逾期</th>
                  <th className="px-2 py-1">跟单 / 变更</th>
                </tr>
              </thead>
              <tbody>
                {delivery?.rows.map((row) => (
                  <tr key={row.purchaseOrderNo} className="border-t">
                    <td className="px-2 py-1">{row.purchaseOrderNo}</td>
                    <td className="px-2 py-1">
                      {row.supplierCode} · {row.supplierName}
                    </td>
                    <td className="px-2 py-1">{promiseText(row)}</td>
                    <td className="px-2 py-1">{arrivalStatusLabel(row.arrivalStatus)}</td>
                    <td className="px-2 py-1">{fmtDate(row.arrivedAt)}</td>
                    <td className="px-2 py-1">
                      <Badge
                        tone={
                          delayDaysTone(row.delayDays) === 'negative'
                            ? 'danger'
                            : delayDaysTone(row.delayDays) === 'positive'
                              ? 'success'
                              : 'neutral'
                        }
                      >
                        {delayDaysText(row.delayDays)}
                      </Badge>
                    </td>
                    <td className="px-2 py-1">{overdueDaysText(row.overdueDays)}</td>
                    <td className="whitespace-nowrap px-2 py-1">
                      跟单 {row.followupCount} 次 / 变更 {row.changeCount} 次
                      {row.lastFollowupAt ? `（最近 ${fmtDateTime(row.lastFollowupAt)}）` : ''}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager total={total} page={page} size={size} lastPage={lastPage} onGo={goPage} />
        </SectionCard>
      )}

      <FollowupModal
        open={followupOpen}
        onClose={() => setFollowupOpen(false)}
        busy={busy}
        onSubmit={async (input) => {
          await run(async () => {
            const row = await recordPurchaseFollowup(input);
            setNotice(`已登记跟单 ${row.followupNo}（追加式凭据，不可改写）。`);
            setFollowupOpen(false);
            await load();
          });
        }}
      />

      <ChangeModal
        open={changeOpen}
        onClose={() => setChangeOpen(false)}
        busy={busy}
        onSubmit={async (input) => {
          await run(async () => {
            const row = await recordPurchaseFollowupChange(input);
            const failed = row.writeback.filter((w) => w.status !== 'SUCCEEDED');
            setNotice(
              failed.length === 0
                ? `已登记交期变更 ${row.changeNo}，并回写了 ${row.writeback.length} 个下游。`
                : `已登记交期变更 ${row.changeNo}（变更已落库），但 ${failed.length} 个下游未回写成功：${failed
                    .map(
                      (w) =>
                        `${writebackTargetLabel(w.targetModule)}（${w.errorMessage || writebackStatusText(w)}）`,
                    )
                    .join('；')}。`,
            );
            setChangeOpen(false);
            await load();
          });
        }}
      />
    </div>
  );
}

// ================================================================ 分页

function Pager({
  total,
  page,
  size,
  lastPage,
  onGo,
}: {
  total: number;
  page: number;
  size: number;
  lastPage: number;
  onGo: (page: number) => void;
}) {
  if (total <= size) return null;
  return (
    <div className="mt-3 flex items-center justify-end gap-2 text-sm">
      <Button variant="ghost" disabled={page <= 0} onClick={() => onGo(Math.max(0, page - 1))}>
        上一页
      </Button>
      <span className="text-muted-foreground">
        第 {page + 1} / {lastPage + 1} 页（共 {total} 条）
      </span>
      <Button
        variant="ghost"
        disabled={page >= lastPage}
        onClick={() => onGo(Math.min(lastPage, page + 1))}
      >
        下一页
      </Button>
    </div>
  );
}

// ================================================================ 登记跟单

function FollowupModal({
  open,
  onClose,
  busy,
  onSubmit,
}: {
  open: boolean;
  onClose: () => void;
  busy: boolean;
  onSubmit: (input: {
    purchaseOrderNo: string;
    purchaseOrderRefId: null;
    supplierRefId: null;
    followupType: 'URGING' | 'CONFIRM' | 'EXCEPTION' | 'OTHER';
    channel: 'PHONE' | 'EMAIL' | 'IM' | 'WECHAT' | 'PLATFORM' | 'OTHER';
    content: string;
    supplierReply: string | null;
    occurredAt: string;
    nextFollowupAt: string | null;
  }) => Promise<void>;
}) {
  const [purchaseOrderNo, setPurchaseOrderNo] = useState('');
  const [type, setType] = useState<'URGING' | 'CONFIRM' | 'EXCEPTION' | 'OTHER'>('URGING');
  const [channel, setChannel] = useState<
    'PHONE' | 'EMAIL' | 'IM' | 'WECHAT' | 'PLATFORM' | 'OTHER'
  >('PHONE');
  const [content, setContent] = useState('');
  const [reply, setReply] = useState('');
  const [occurredAt, setOccurredAt] = useState('');

  useEffect(() => {
    if (open) setOccurredAt(new Date().toISOString());
  }, [open]);

  const submit = useCallback(async () => {
    if (!purchaseOrderNo.trim() || !content.trim()) return;
    await onSubmit({
      purchaseOrderNo: purchaseOrderNo.trim(),
      purchaseOrderRefId: null,
      supplierRefId: null,
      followupType: type,
      channel,
      content: content.trim(),
      supplierReply: reply.trim() || null,
      occurredAt: new Date(occurredAt).toISOString(),
      nextFollowupAt: null,
    });
  }, [channel, content, occurredAt, onSubmit, purchaseOrderNo, reply, type]);

  return (
    <Modal open={open} onOpenChange={(v) => !v && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>登记跟单记录</ModalTitle>
          <ModalDescription>
            跟单记录是「与供应商说了什么」的举证凭据，登记后不可改写。 ⚠️ 这里的「催单」是
            <b>一次沟通</b>，与 6.3.1.1 采购单上的「催过几次」不是一回事。
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">采购单号（必填）</span>
            <Input
              value={purchaseOrderNo}
              placeholder="例如 PO-2026-0001"
              onChange={(e) => setPurchaseOrderNo(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">跟单类型</span>
            <select
              className="h-9 rounded-md border px-2"
              value={type}
              onChange={(e) => setType(e.target.value as typeof type)}
            >
              {['URGING', 'CONFIRM', 'EXCEPTION', 'OTHER'].map((t) => (
                <option key={t} value={t}>
                  {followupTypeLabel(t)}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">沟通渠道</span>
            <select
              className="h-9 rounded-md border px-2"
              value={channel}
              onChange={(e) => setChannel(e.target.value as typeof channel)}
            >
              {['PHONE', 'EMAIL', 'IM', 'WECHAT', 'PLATFORM', 'OTHER'].map((c) => (
                <option key={c} value={c}>
                  {followupChannelLabel(c)}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">沟通内容（必填）</span>
            <textarea
              className="min-h-20 rounded-md border p-2"
              value={content}
              onChange={(e) => setContent(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">供应商答复</span>
            <textarea
              className="min-h-16 rounded-md border p-2"
              value={reply}
              onChange={(e) => setReply(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">沟通发生时刻</span>
            <Input
              type="datetime-local"
              value={occurredAt}
              onChange={(e) => setOccurredAt(e.target.value)}
            />
          </label>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button
            onClick={() => void submit()}
            disabled={busy || !purchaseOrderNo.trim() || !content.trim()}
          >
            保存
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 登记交期变更

function ChangeModal({
  open,
  onClose,
  busy,
  onSubmit,
}: {
  open: boolean;
  onClose: () => void;
  busy: boolean;
  onSubmit: (input: {
    purchaseOrderNo: string;
    purchaseOrderRefId: null;
    supplierRefId: null;
    fromPromisedDate: string;
    toPromisedDate: string;
    reason: string;
    changedAt: string | null;
  }) => Promise<void>;
}) {
  const [purchaseOrderNo, setPurchaseOrderNo] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');

  const submit = useCallback(async () => {
    if (!purchaseOrderNo.trim() || !from || !to || !reason.trim()) return;
    await onSubmit({
      purchaseOrderNo: purchaseOrderNo.trim(),
      purchaseOrderRefId: null,
      supplierRefId: null,
      fromPromisedDate: from,
      toPromisedDate: to,
      reason: reason.trim(),
      changedAt: null,
    });
  }, [from, onSubmit, purchaseOrderNo, reason, to]);

  return (
    <Modal open={open} onOpenChange={(v) => !v && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>登记交期变更</ModalTitle>
          <ModalDescription>
            变更前的承诺到货日必须等于<b>当前跟单承诺日</b>（第一次变更时等于采购单上的承诺日），
            否则交期历史会断链、延迟天数会算到一个不存在的承诺上。 ⚠️ 本模块<b>不改</b>
            采购单上的承诺日（那归 6.3.1.1 / 6.3.1.2）， 页面会同时显示「单据承诺 /
            跟单承诺」两个口径。
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">采购单号（必填）</span>
            <Input
              value={purchaseOrderNo}
              placeholder="例如 PO-2026-0001"
              onChange={(e) => setPurchaseOrderNo(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">变更前的承诺到货日（必填）</span>
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">变更后的承诺到货日（必填）</span>
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">变更原因（必填）</span>
            <textarea
              className="min-h-20 rounded-md border p-2"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button
            onClick={() => void submit()}
            disabled={busy || !purchaseOrderNo.trim() || !from || !to || !reason.trim()}
          >
            保存并回写
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
