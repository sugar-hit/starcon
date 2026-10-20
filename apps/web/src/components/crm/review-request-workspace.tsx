'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { AlertTriangle, MessageSquarePlus, RefreshCw, ShieldCheck, Star } from 'lucide-react';
import type {
  CrmReviewBatchResult,
  CrmReviewCandidatePage,
  CrmReviewCapabilities,
  CrmReviewFrequencyPolicy,
  CrmReviewRecoveryStats,
  CrmReviewRequestDetail,
  CrmReviewRequestRow,
  CrmReviewShopView,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  createRecovery,
  createReviewRequest,
  createSendRecord,
  fetchFrequencyPolicy,
  fetchRecoveryStats,
  fetchReviewCandidates,
  fetchReviewCapabilities,
  fetchReviewRequest,
  fetchReviewRequests,
  fetchReviewShops,
  ratingDistributionText,
  recoveryRateText,
  rejectCodeLabel,
  reviewStatusLabel,
  ruleAgainstPolicy,
  runReviewBatch,
  saveFrequencyPolicy,
  sendOutcomeLabel,
} from './review-request-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.2.2.1 Request a Review（索评）工作区。
 *
 * <p>页面按「先看上限、再定规则、后动触达」的顺序排，因为蓝图加粗句是
 * 「**索评频次必须受限**」—— 界面上第一件要让人看到的就是**租户级硬上限**，
 * 而不是一张可以直接点「批量索评」的按钮。
 *
 * <ul>
 *   <li><b>能力声明</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       未接入的平台索评接口、候选单的店铺归属、买家档案三块是 available=false，
 *       页面把它们**显式灰掉**并写清归属模块，不给任何看起来能点的假入口。</li>
 *   <li><b>频次硬上限</b> —— 没有就显示「必须先配置」并给配置表单；
 *       规则只能比它更严，填松了当场提示（真正拦在后端与 DB 触发器）。</li>
 *   <li><b>发起索评</b> —— 单条（选一件已签收件 + 买家标识）与批量
 *       （按承运商 / 批次 / 签收时间窗 + 逐单买家标识）。批量结果逐条给结论与原因，
 *       被频次规则挡住的与缺买家标识的**都列出原因**。</li>
 *   <li><b>索评任务</b> —— 列表可按状态筛选，<b>「被频次规则拒绝」也是一条真实的索评请求</b>，
 *       页面给它独立状态并显示后端返回的原文原因。</li>
 *   <li><b>触达与回收</b> —— 发送记录只允许「人工交接」（平台接口未接入）；
 *       评价回收只落**回收桶**（下游 15.2.1.1 未交付），页面不提供回复 / 申诉 / 标签。</li>
 * </ul>
 */
export function ReviewRequestWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.review.update');

  const [capabilities, setCapabilities] = useState<CrmReviewCapabilities | null>(null);
  const [policy, setPolicy] = useState<CrmReviewFrequencyPolicy | null>(null);
  const [policyMissing, setPolicyMissing] = useState(false);
  const [shops, setShops] = useState<CrmReviewShopView[]>([]);
  const [rows, setRows] = useState<CrmReviewRequestRow[]>([]);
  const [candidates, setCandidates] = useState<CrmReviewCandidatePage | null>(null);
  const [stats, setStats] = useState<CrmReviewRecoveryStats | null>(null);
  const [detail, setDetail] = useState<CrmReviewRequestDetail | null>(null);
  const [batch, setBatch] = useState<CrmReviewBatchResult | null>(null);

  const [shopId, setShopId] = useState('');
  const [buyerRef, setBuyerRef] = useState('');
  const [upstreamCaseId, setUpstreamCaseId] = useState('');
  const [delayHours, setDelayHours] = useState('48');
  const [cooldownHours, setCooldownHours] = useState('96');
  const [maxPerBuyer, setMaxPerBuyer] = useState('3');
  const [maxPerOrder, setMaxPerOrder] = useState('1');

  const [batchCarrier, setBatchCarrier] = useState('');
  const [batchNo, setBatchNo] = useState('');
  const [deliveredFrom, setDeliveredFrom] = useState('');
  const [deliveredTo, setDeliveredTo] = useState('');
  const [buyerBindings, setBuyerBindings] = useState('');

  const [statusFilter, setStatusFilter] = useState('');
  const [orderFilter, setOrderFilter] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const rule = useMemo(
    () => ({
      delayHours: Number(delayHours) || 0,
      cooldownHours: Number(cooldownHours) || 0,
      maxPerBuyer: Number(maxPerBuyer) || 0,
      maxPerOrder: Number(maxPerOrder) || 0,
    }),
    [delayHours, cooldownHours, maxPerBuyer, maxPerOrder],
  );
  const ruleProblem = useMemo(() => ruleAgainstPolicy(rule, policy), [rule, policy]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, shopList, requests] = await Promise.all([
        fetchReviewCapabilities(),
        fetchReviewShops(),
        fetchReviewRequests({
          status: statusFilter || undefined,
          salesOrderNo: orderFilter || undefined,
          size: 100,
        }),
      ]);
      setCapabilities(caps);
      setShops(shopList);
      setRows(requests);
      // 频次策略没配置过时后端回 404 —— 那不是「用默认值」，是「还没上限」。
      try {
        setPolicy(await fetchFrequencyPolicy());
        setPolicyMissing(false);
      } catch {
        setPolicy(null);
        setPolicyMissing(true);
      }
      try {
        setStats(await fetchRecoveryStats({ days: 30 }));
      } catch {
        setStats(null);
      }
    } catch (e) {
      setError(errorText(e, '索评工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [statusFilter, orderFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadCandidates = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      const page = await fetchReviewCandidates({
        carrierCode: batchCarrier || undefined,
        batchNo: batchNo || undefined,
        size: 50,
      });
      setCandidates(page);
      const first = page.items[0];
      if (first && !upstreamCaseId) setUpstreamCaseId(first.upstreamCaseId);
    } catch (e) {
      setActionError(errorText(e, '已签收候选单读取失败'));
    } finally {
      setBusy(false);
    }
  }, [batchCarrier, batchNo, upstreamCaseId]);

  const openDetail = useCallback(async (requestId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchReviewRequest(requestId));
    } catch (e) {
      setActionError(errorText(e, '索评请求详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitSingle = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createReviewRequest({
        upstreamCaseId,
        shopId,
        buyerRef,
        rule,
        note: '',
      });
      setNotice(
        created.status === 'REJECTED'
          ? `索评请求 ${created.requestNo} 已被频次规则拒绝：${created.rejectReason}（这一行已落库，可在下方列表按「被频次规则拒绝」筛出）`
          : `索评请求 ${created.requestNo} 已排期，计划发送时刻 ${created.scheduledAt}`,
      );
      setUpstreamCaseId('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '发起索评失败'));
    } finally {
      setBusy(false);
    }
  }, [upstreamCaseId, shopId, buyerRef, rule, load]);

  const submitBatch = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const buyers = parseBindings(buyerBindings);
      const result = await runReviewBatch({
        shopId,
        carrierCode: batchCarrier,
        batchNo,
        deliveredFrom: deliveredFrom || null,
        deliveredTo: deliveredTo || null,
        buyers,
        rule,
        note: '',
      });
      setBatch(result);
      setNotice(
        `批量执行完成：候选 ${result.candidateCount} 单，已排期 ${result.createdCount} 单，` +
          `被频次规则拒绝 ${result.rejectedCount} 单，跳过 ${result.skippedCount} 单。`,
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '批量索评失败'));
    } finally {
      setBusy(false);
    }
  }, [shopId, batchCarrier, batchNo, deliveredFrom, deliveredTo, buyerBindings, rule, load]);

  const savePolicy = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      await saveFrequencyPolicy({
        maxPerBuyerPerWindow: Number(maxPerBuyer) || 1,
        windowDays: 30,
        minCooldownHours: Number(cooldownHours) || 1,
        maxPerOrder: Number(maxPerOrder) || 1,
        minDelayHours: Number(delayHours) || 1,
        maxBatchSize: 200,
      });
      setNotice('频次硬上限已保存。请求自带的规则只能比它更严。');
      await load();
    } catch (e) {
      setActionError(errorText(e, '频次策略保存失败'));
    } finally {
      setBusy(false);
    }
  }, [maxPerBuyer, cooldownHours, maxPerOrder, delayHours, load]);

  const markSent = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    try {
      await createSendRecord(detail.request.id, {
        transport: 'MANUAL_HANDOFF',
        outcome: 'SENT',
        failureReason: '',
        note: '运营已人工交接并发出',
      });
      setNotice(`索评请求 ${detail.request.requestNo} 已登记为「已发出」（人工交接）。`);
      await openDetail(detail.request.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记发送记录失败'));
    } finally {
      setBusy(false);
    }
  }, [detail, openDetail, load]);

  const recordRecovery = useCallback(
    async (rating: number, content: string, receivedAt: string) => {
      if (!detail) return;
      setBusy(true);
      setActionError(null);
      try {
        await createRecovery(detail.request.id, {
          rating,
          content,
          hasImage: false,
          hasVideo: false,
          receivedAt,
          note: '',
        });
        setNotice(`索评请求 ${detail.request.requestNo} 已登记一条评价回收。`);
        await openDetail(detail.request.id);
        await load();
      } catch (e) {
        setActionError(errorText(e, '登记评价回收失败'));
      } finally {
        setBusy(false);
      }
    },
    [detail, openDetail, load],
  );

  if (loading) {
    return (
      <SectionCard title="索评工作区">
        <p className="text-muted-foreground text-copy-13">加载中…</p>
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
          {error}
        </div>
      )}
      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 rounded-md px-3 py-2 text-copy-13">{notice}</div>
      )}

      {/* ---------------------------------------------------------- 能力声明 */}
      <SectionCard title="能力声明">
        <ul className="space-y-2 text-copy-13">
          {(capabilities?.dimensions ?? []).map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone={dimension.available ? 'success' : 'warning'}>
                {dimension.available ? '可用' : '未接入'}
              </Badge>
              <span className="font-medium">{dimension.description}</span>
              {!dimension.available && (
                <span className="text-muted-foreground">
                  （归属模块 {dimension.ownerModule}）—— {dimension.impact}
                </span>
              )}
            </li>
          ))}
        </ul>
        {capabilities?.highlight && (
          <p className="text-muted-foreground mt-3 text-copy-12">{capabilities.highlight}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 频次硬上限 */}
      <SectionCard title="索评频次硬上限">
        {policyMissing ? (
          <div className="space-y-3">
            <p className="text-destructive text-copy-13">
              本租户还没有配置索评频次策略。索评频次必须受限 —— 没有硬上限就不允许发起索评，
              下面这份表单就是那个上限。
            </p>
            <PolicyForm
              delayHours={delayHours}
              cooldownHours={cooldownHours}
              maxPerBuyer={maxPerBuyer}
              maxPerOrder={maxPerOrder}
              setDelayHours={setDelayHours}
              setCooldownHours={setCooldownHours}
              setMaxPerBuyer={setMaxPerBuyer}
              setMaxPerOrder={setMaxPerOrder}
              writable={writable}
              busy={busy}
              onSave={savePolicy}
            />
          </div>
        ) : (
          policy && (
            <div className="space-y-2 text-copy-13">
              <p>
                每买家上限 <strong>{policy.maxPerBuyerPerWindow}</strong> 次 / {policy.windowDays} 天 ·
                每订单上限 <strong>{policy.maxPerOrder}</strong> 次 · 冷却窗口下限{' '}
                <strong>{policy.minCooldownHours}</strong> 小时 · 时点下限{' '}
                <strong>{policy.minDelayHours}</strong> 小时 · 单批上限{' '}
                <strong>{policy.maxBatchSize}</strong> 条。
              </p>
              <p className="text-muted-foreground">
                本次索评用的规则<b>只能比这些数字更严</b>：放宽上限的唯一合法路径是显式改这里，
                而这里每个值在数据库层另有天花板。
              </p>
              <PolicyForm
                delayHours={delayHours}
                cooldownHours={cooldownHours}
                maxPerBuyer={maxPerBuyer}
                maxPerOrder={maxPerOrder}
                setDelayHours={setDelayHours}
                setCooldownHours={setCooldownHours}
                setMaxPerBuyer={setMaxPerBuyer}
                setMaxPerOrder={setMaxPerOrder}
                writable={writable}
                busy={busy}
                onSave={savePolicy}
              />
            </div>
          )
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 发起索评 */}
      <SectionCard title="发起索评">
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="目标店铺" hint="来自上游 channel 的真实店铺清单，不是前端写死的常量。">
              <select
                className={control}
                value={shopId}
                onChange={(e) => setShopId(e.target.value)}
                disabled={!writable}
              >
                <option value="">（选择一家店铺）</option>
                {shops.map((shop) => (
                  <option key={shop.id} value={shop.id}>
                    {shop.name} · {shop.platformCode}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="买家标识"
              hint="本仓没有买家档案（15.3.1.1 未交付），由运营按店铺提供，只作频次去重键。"
            >
              <input
                className={control}
                value={buyerRef}
                onChange={(e) => setBuyerRef(e.target.value)}
                placeholder="例：AMZN-BUYER-8821"
                disabled={!writable}
              />
            </Field>
          </div>

          <div className="grid gap-3 md:grid-cols-4">
            <Field label="时点（签收后延后小时）">
              <input
                className={control}
                value={delayHours}
                onChange={(e) => setDelayHours(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="冷却窗口（小时）">
              <input
                className={control}
                value={cooldownHours}
                onChange={(e) => setCooldownHours(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="每买家上限（窗口内）">
              <input
                className={control}
                value={maxPerBuyer}
                onChange={(e) => setMaxPerBuyer(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="每订单上限">
              <input
                className={control}
                value={maxPerOrder}
                onChange={(e) => setMaxPerOrder(e.target.value)}
                disabled={!writable}
              />
            </Field>
          </div>

          {ruleProblem && (
            <p className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
              {ruleProblem}
            </p>
          )}

          <div className="flex flex-wrap gap-2">
            <Button
              onClick={() => void loadCandidates()}
              disabled={busy}
              variant="secondary"
              size="sm"
            >
              <RefreshCw className="mr-1 h-4 w-4" />
              读已签收候选单
            </Button>
            <Button
              onClick={() => void submitSingle()}
              disabled={busy || !writable || !upstreamCaseId || !shopId || !buyerRef || !!ruleProblem}
              size="sm"
            >
              <ShieldCheck className="mr-1 h-4 w-4" />
              发起索评
            </Button>
          </div>

          {candidates && (
            <div className="space-y-2">
              <p className="text-muted-foreground text-copy-12">
                上游共 {candidates.totalElements} 件已签收确认单（来源：10.3.2.2 揽收与签收确认，
                经 channel 授权 API 读取）。挑一件发起索评：
              </p>
              <ul className="max-h-64 space-y-1 overflow-auto text-copy-12">
                {candidates.items.map((item) => (
                  <li key={item.upstreamCaseId}>
                    <button
                      type="button"
                      className={`hover:underline ${item.upstreamCaseId === upstreamCaseId ? 'font-semibold' : ''}`}
                      onClick={() => setUpstreamCaseId(item.upstreamCaseId)}
                      disabled={!writable}
                    >
                      {item.caseNo} · 订单 {item.salesOrderNo || '（无订单号）'} · {item.carrierCode} ·
                      签收 {item.deliveredAt}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 批量执行 */}
      <SectionCard title="批量执行（按店铺 / 时间 / 物流状态）">
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="承运商（留空 = 不限）">
              <input
                className={control}
                value={batchCarrier}
                onChange={(e) => setBatchCarrier(e.target.value)}
              />
            </Field>
            <Field label="交运批次号（留空 = 不限）">
              <input className={control} value={batchNo} onChange={(e) => setBatchNo(e.target.value)} />
            </Field>
            <Field label="签收时间窗起点（含）">
              <input
                className={control}
                value={deliveredFrom}
                onChange={(e) => setDeliveredFrom(e.target.value)}
                placeholder="2026-06-01T00:00:00+08:00"
              />
            </Field>
            <Field label="签收时间窗终点（不含）">
              <input
                className={control}
                value={deliveredTo}
                onChange={(e) => setDeliveredTo(e.target.value)}
                placeholder="2026-07-01T00:00:00+08:00"
              />
            </Field>
          </div>
          <Field
            label="逐单买家标识"
            hint="每行一条「订单号 空格 买家标识」。上游确认单不带买家维度，缺买家标识的候选单会被跳过并列出原因。"
          >
            <textarea
              className={control}
              rows={4}
              value={buyerBindings}
              onChange={(e) => setBuyerBindings(e.target.value)}
              placeholder={'SO-1001 AMAZON-BUYER-1\nSO-1002 AMAZON-BUYER-2'}
            />
          </Field>
          <Button
            onClick={() => void submitBatch()}
            disabled={busy || !writable || !shopId || !!ruleProblem}
            size="sm"
          >
            <ShieldCheck className="mr-1 h-4 w-4" />
            批量索评
          </Button>

          {batch && (
            <div className="space-y-2 text-copy-12">
              <p>{batch.boundaryNote}</p>
              <ul className="max-h-64 space-y-1 overflow-auto">
                {batch.items.map((item, index) => (
                  <li key={`${item.salesOrderNo}-${index}`} className="flex flex-wrap gap-2">
                    <Badge tone={item.outcome === 'REJECTED' ? 'danger' : 'neutral'}>
                      {item.outcome === 'REJECTED'
                        ? rejectCodeLabel(item.outcome) || '被拒绝'
                        : item.outcome === 'SKIPPED'
                          ? '跳过'
                          : '已排期'}
                    </Badge>
                    <span>
                      {item.salesOrderNo || '（无订单号）'} · {item.buyerRef || '（无买家标识）'}
                    </span>
                    {item.reason && <span className="text-muted-foreground">{item.reason}</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 索评任务 */}
      <SectionCard title="索评任务">
        <div className="mb-3 flex flex-wrap gap-2">
          <select
            className={control}
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
          >
            <option value="">全部状态</option>
            <option value="SCHEDULED">已排期</option>
            <option value="SENT">已发出</option>
            <option value="CANCELLED">已取消</option>
            <option value="REJECTED">被频次规则拒绝</option>
          </select>
          <input
            className={control}
            value={orderFilter}
            onChange={(e) => setOrderFilter(e.target.value)}
            placeholder="按订单号筛选"
          />
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        </div>

        {rows.length === 0 ? (
          <EmptyState
            title="还没有索评任务"
            description="发起一次索评后，这里会出现这条任务；被频次规则拒绝的请求也会落在同一张表里。"
          />
        ) : (
          <ul className="space-y-2 text-copy-13">
            {rows.map((row) => (
              <li key={row.id} className="border-border rounded-md px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={row.status === 'REJECTED' ? 'danger' : 'neutral'}>
                    {reviewStatusLabel(row.status)}
                  </Badge>
                  <button
                    type="button"
                    className="hover:underline font-medium"
                    onClick={() => void openDetail(row.id)}
                  >
                    {row.requestNo}
                  </button>
                  <span>
                    {row.shopName} · 订单 {row.salesOrderNo} · 买家 {row.buyerRef}
                  </span>
                  <span className="text-muted-foreground">
                    签收 {row.deliveredAt} · 计划发送 {row.scheduledAt}
                  </span>
                </div>
                {row.status === 'REJECTED' && (
                  <p className="text-destructive mt-1 text-copy-12">
                    {rejectCodeLabel(row.rejectCode)}：{row.rejectReason}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 详情 */}
      {detail && (
        <SectionCard title={`索评请求详情 · ${detail.request.requestNo}`}>
          <div className="space-y-3 text-copy-13">
            <p className="text-muted-foreground">
              上游确认单 {detail.request.upstreamCaseNo} · 运单 {detail.request.upstreamWaybillNo} ·
              承运商 {detail.request.upstreamCarrierCode} · 签收 {detail.request.deliveredAt}
            </p>

            <div>
              <h3 className="font-medium">发送记录</h3>
              {detail.sendRecords.length === 0 ? (
                <p className="text-muted-foreground text-copy-12">
                  还没有触达记录。平台侧索评接口**未接入**，只允许登记「人工交接」。
                </p>
              ) : (
                <ul className="space-y-1 text-copy-12">
                  {detail.sendRecords.map((record) => (
                    <li key={record.id}>
                      {sendOutcomeLabel(record.status)} · {record.transport} ·{' '}
                      {record.attemptedAt || '（尚未发出）'}
                      {record.failureReason && ` · ${record.failureReason}`}
                    </li>
                  ))}
                </ul>
              )}
              {writable && detail.request.status !== 'REJECTED' && (
                <Button onClick={() => void markSent()} size="sm" variant="secondary" className="mt-2">
                  <MessageSquarePlus className="mr-1 h-4 w-4" />
                  登记人工交接（已发出）
                </Button>
              )}
            </div>

            <div>
              <h3 className="font-medium">评价回收桶</h3>
              {detail.recoveries.length === 0 ? (
                <p className="text-muted-foreground text-copy-12">
                  还没有回收到的评价。下游 15.2.1.1 评价列表未交付，只能人工登记。
                </p>
              ) : (
                <ul className="space-y-1 text-copy-12">
                  {detail.recoveries.map((recovery) => (
                    <li key={recovery.id}>
                      <Star className="mr-1 inline h-3 w-3" />
                      {recovery.rating} 星 · {recovery.content || '（无文字）'} ·{' '}
                      {recovery.receivedAt}
                    </li>
                  ))}
                </ul>
              )}
              {writable && detail.request.status !== 'REJECTED' && (
                <RecoveryForm onSubmit={(rating, content, receivedAt) => void recordRecovery(rating, content, receivedAt)} />
              )}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 回收统计 */}
      <SectionCard title="评价回收统计">
        {!stats ? (
          <p className="text-muted-foreground text-copy-13">统计暂不可用。</p>
        ) : (
          <div className="space-y-2 text-copy-13">
            <p>
              最近 {stats.scopeDays} 天：索评请求 {stats.totalRequests} 条（已排期 {stats.scheduledCount} ·
              已发出 {stats.sentCount} · 已取消 {stats.cancelledCount} · 被频次规则拒绝{' '}
              {stats.rejectedCount}），回收 {stats.recoveryRecords} 条、覆盖 {stats.recoveredRequests}{' '}
              条请求，回收率 {recoveryRateText(stats.recoveryRate)}，平均星级{' '}
              {stats.averageRating === null ? '暂无' : stats.averageRating}。
            </p>
            <p className="text-muted-foreground text-copy-12">
              星级分布：{ratingDistributionText(stats.ratingDistribution)}
            </p>
            <p className="text-muted-foreground text-copy-12">{stats.note}</p>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 频次硬上限表单（同时用于「还没配置」与「调整上限」两种场景）。 */
function PolicyForm({
  delayHours,
  cooldownHours,
  maxPerBuyer,
  maxPerOrder,
  setDelayHours,
  setCooldownHours,
  setMaxPerBuyer,
  setMaxPerOrder,
  writable,
  busy,
  onSave,
}: {
  delayHours: string;
  cooldownHours: string;
  maxPerBuyer: string;
  maxPerOrder: string;
  setDelayHours: (v: string) => void;
  setCooldownHours: (v: string) => void;
  setMaxPerBuyer: (v: string) => void;
  setMaxPerOrder: (v: string) => void;
  writable: boolean;
  busy: boolean;
  onSave: () => void;
}) {
  return (
    <div className="space-y-2">
      <div className="grid gap-3 md:grid-cols-4">
        <Field label="每买家上限（窗口内）">
          <input
            className={control}
            value={maxPerBuyer}
            onChange={(e) => setMaxPerBuyer(e.target.value)}
            disabled={!writable}
          />
        </Field>
        <Field label="每订单上限">
          <input
            className={control}
            value={maxPerOrder}
            onChange={(e) => setMaxPerOrder(e.target.value)}
            disabled={!writable}
          />
        </Field>
        <Field label="冷却窗口下限（小时）">
          <input
            className={control}
            value={cooldownHours}
            onChange={(e) => setCooldownHours(e.target.value)}
            disabled={!writable}
          />
        </Field>
        <Field label="时点下限（小时）">
          <input
            className={control}
            value={delayHours}
            onChange={(e) => setDelayHours(e.target.value)}
            disabled={!writable}
          />
        </Field>
      </div>
      <Button onClick={onSave} size="sm" disabled={busy || !writable}>
        保存频次硬上限
      </Button>
    </div>
  );
}

/** 评价回收登记表单。 */
function RecoveryForm({
  onSubmit,
}: {
  onSubmit: (rating: number, content: string, receivedAt: string) => void;
}) {
  const [rating, setRating] = useState('5');
  const [content, setContent] = useState('');
  const [receivedAt, setReceivedAt] = useState(() => new Date().toISOString());
  return (
    <div className="mt-2 space-y-2">
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="星级">
          <select className={control} value={rating} onChange={(e) => setRating(e.target.value)}>
            {[5, 4, 3, 2, 1].map((value) => (
              <option key={value} value={value}>
                {value} 星
              </option>
            ))}
          </select>
        </Field>
        <Field label="评价时刻（带时区偏移量）">
          <input
            className={control}
            value={receivedAt}
            onChange={(e) => setReceivedAt(e.target.value)}
          />
        </Field>
        <Field label="评价原文">
          <input className={control} value={content} onChange={(e) => setContent(e.target.value)} />
        </Field>
      </div>
      <Button onClick={() => onSubmit(Number(rating) || 5, content, receivedAt)} size="sm" variant="secondary">
        <AlertTriangle className="mr-1 h-4 w-4" />
        登记评价回收
      </Button>
    </div>
  );
}

/** 解析「订单号 买家标识」逐行输入。 */
function parseBindings(text: string): { salesOrderNo: string; buyerRef: string }[] {
  const out: { salesOrderNo: string; buyerRef: string }[] = [];
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const [order, ...rest] = trimmed.split(/\s+/);
    const buyer = rest.join(' ').trim();
    if (!order || !buyer) continue;
    out.push({ salesOrderNo: order, buyerRef: buyer });
  }
  return out;
}

/** 错误文案：优先用后端给的人话。 */
function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}
