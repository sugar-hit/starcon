'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  RmaCapabilities,
  RmaDetail,
  RmaPage,
  RmaReturnableReport,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionButton,
  addressText,
  amount,
  cancelRma,
  createRma,
  decideRma,
  dispositionHint,
  eventText,
  expireOverdue,
  fetchRmaCapabilities,
  fetchRmaDetail,
  fetchRmaList,
  fetchReturnableQuantities,
  quantity,
  receivedHint,
  receivedQuantityText,
  recordReturnLogistics,
  remainingText,
  returnableText,
  shippedSourceText,
  stateTone,
  timelinessTone,
  unavailableText,
} from './rma-authorization-model';

/**
 * 11.2.1.1 退货授权单。
 *
 * <p>蓝图：「审批退货申请：确认可退数量（原订单行已退/已发数量约束）、退货地址、买家回寄物流、
 * 目标处理方式；部分退货需说明理由。」
 * 加粗句：「<b>授权不等于已收到</b>——授权后货可能在路上或根本没寄回，需要超时未寄自动关闭。」
 *
 * <p><b>页面围绕那句加粗句组织</b>：
 *
 * <ol>
 *   <li><b>第一屏就是「在途 / 已超时」</b>（{@link StatCard}），不是「一共多少张单」——
 *       在途的那些才是要天天盯的：买家还没把货寄回来。</li>
 *   <li><b>「可退数量」对话框先算再批</b>：三个来源（订购 / 已发 / 其他在途授权）服务端实时算，
 *       页面**不自己推断**能不能批。</li>
 *   <li><b>没有「登记已收到」这个按钮</b>：11.2.2.1 未交付。渲染一个注定 422 的按钮
 *       等于告诉运营系统坏了，而真相是这条路径本就不该存在。</li>
 *   <li><b>已退数量显示「不可得（11.2.2.2 未交付）」而不是 0</b>。</li>
 * </ol>
 */
export function RmaAuthorizationWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [data, setData] = useState<RmaPage | null>(null);
  const [caps, setCaps] = useState<RmaCapabilities | null>(null);
  const [detail, setDetail] = useState<RmaDetail | null>(null);
  const [quantities, setQuantities] = useState<RmaReturnableReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」，
      //   不能把 undefined 当成一个已给的键塞进去。
      const filters: Parameters<typeof fetchRmaList>[0] = { page, size: 20 };
      if (status) filters.status = status;
      if (keyword) filters.keyword = keyword;
      if (overdueOnly) filters.overdueOnly = true;
      const [list, capabilities] = await Promise.all([
        fetchRmaList(filters),
        fetchRmaCapabilities(),
      ]);
      setData(list);
      setCaps(capabilities);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [page, status, keyword, overdueOnly]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setError(null);
    try {
      setDetail(await fetchRmaDetail(id));
      setQuantities(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载详情失败');
    }
  }, []);

  const checkQuantities = useCallback(async (caseId: string) => {
    setError(null);
    setBusy(true);
    try {
      setQuantities(await fetchReturnableQuantities(caseId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '可退数量校验失败');
    } finally {
      setBusy(false);
    }
  }, []);

  const act = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : '操作失败');
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const summary = data?.summary;
  // StatCard 没有 tone 属性：语气走 polarity（trendClasses 用的那一套）。
  const overdue = summary?.overdue ?? 0;
  const stats: { label: string; value: number; polarity: 'positive' | 'negative' | 'neutral' }[] = [
    { label: '在途（等买家寄回）', value: summary?.awaitingShipment ?? 0, polarity: 'positive' },
    { label: '已超时未寄', value: overdue, polarity: overdue ? 'negative' : 'neutral' },
    { label: '待审批草稿', value: summary?.draft ?? 0, polarity: 'neutral' },
    {
      label: '已拒绝 / 已撤销 / 超时关闭',
      value: (summary?.rejected ?? 0) + (summary?.cancelled ?? 0) + (summary?.expired ?? 0),
      polarity: 'neutral',
    },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="退货授权单"
        description="审批退货申请：可退数量校验、退货地址、买家回寄物流、目标处理方式。授权不等于已收到。"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
          </Button>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <StatCard key={s.label} label={s.label} value={s.value} polarity={s.polarity} />
        ))}
      </div>

      {/* 加粗句的页面级说明：横幅而不是脚注，因为它决定了这个页面**没有**什么。 */}
      <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden />
        <p>{receivedHint}</p>
      </div>

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-sm">
            <span className="mb-1 block">状态</span>
            <select
              className="rounded border px-2 py-1"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              <option value="DRAFT">草稿</option>
              <option value="AUTHORIZED">全部授权</option>
              <option value="PARTIALLY_AUTHORIZED">部分授权</option>
              <option value="REJECTED">已拒绝</option>
              <option value="EXPIRED">超时关闭</option>
              <option value="CANCELLED">已撤销</option>
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block">关键字</span>
            <Input
              value={keyword}
              placeholder="授权单号 / 售后单号 / 订单号"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={overdueOnly}
              onChange={(e) => {
                setOverdueOnly(e.target.checked);
                setPage(0);
              }}
            />
            只看已超时
          </label>
          <Button
            variant="secondary"
            disabled={busy}
            onClick={() => void act(() => expireOverdue())}
            title="把时限已到且仍在途的单自动关闭（蓝图：超时未寄自动关闭）"
          >
            执行超时未寄关闭
          </Button>
        </div>
      </SectionCard>

      {error && (
        <SectionCard>
          <p className="text-sm text-danger" role="alert">
            {error}
          </p>
        </SectionCard>
      )}

      {loading && !data ? (
        <SectionCard>
          <p className="text-sm text-muted">加载中…</p>
        </SectionCard>
      ) : !data || data.rows.length === 0 ? (
        <EmptyState
          title="还没有退货授权单"
          description="退货授权单由售后单发起：先在售后单详情登记商品行，再对本单发起退货授权。"
        />
      ) : (
        <SectionCard title={`退货授权单（${data.total} 张）`}>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">授权单号</th>
                <th className="p-2">售后单 / 订单</th>
                <th className="p-2">状态</th>
                <th className="p-2">授权 / 申请</th>
                <th className="p-2">寄回时限</th>
                <th className="p-2">买家回寄</th>
                <th className="p-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((row) => (
                <tr key={row.id} className="border-b">
                  <td className="p-2 font-mono text-xs">{row.rmaNo}</td>
                  <td className="p-2">
                    <div>{row.caseNo ?? '—'}</div>
                    <div className="text-xs text-muted">{row.orderNo ?? '—'}</div>
                  </td>
                  <td className="p-2">
                    <div className="flex flex-col gap-1">
                      <Badge tone={stateTone(row.rmaStatus)}>{row.rmaStatusLabel}</Badge>
                      <Badge tone={timelinessTone(row.timelinessState)}>
                        {row.timelinessStateLabel} · {remainingText(row.remainingSeconds)}
                      </Badge>
                    </div>
                  </td>
                  <td className="p-2">
                    {quantity(row.authorizedQty)} / {quantity(row.requestedQty)}
                    <div className="text-xs text-muted">{row.targetDispositionLabel}</div>
                  </td>
                  <td className="p-2 text-xs">{row.returnByAt ? new Date(row.returnByAt).toLocaleString() : '—'}</td>
                  <td className="p-2 text-xs">
                    {row.returnTrackingNo ? `${row.returnCarrier} ${row.returnTrackingNo}` : '—'}
                    {/* 登记了运单号也**不等于**收到 —— 这里刻意不加「已收到」徽标。 */}
                  </td>
                  <td className="p-2">
                    <div className="flex gap-2">
                      <Button variant="ghost" onClick={() => void openDetail(row.id)}>
                        详情
                      </Button>
                      <Button
                        variant={actionButton(row.action).variant}
                        disabled={actionButton(row.action).disabled || busy}
                        title={actionButton(row.action).reason ?? undefined}
                        onClick={() => {
                          if (row.action?.allowed && detail?.head.id === row.id) {
                            void act(() =>
                              decideRma(row.id, {
                                decision: 'APPROVED',
                                returnByAt: new Date(Date.now() + 14 * 86400_000).toISOString(),
                                decisionReason: null,
                                expectedVersion: detail.head.version,
                                lines: detail.lines.map((l) => ({ lineId: l.id, authorizedQty: l.returnableQty ?? 0, lineNote: null })),
                              }),
                            );
                          } else {
                            void openDetail(row.id);
                          }
                        }}
                      >
                        {actionButton(row.action).label}
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mt-3 flex items-center justify-between text-sm">
            <span className="text-muted">
              第 {data.page + 1} 页 · 每页 {data.size} · 共 {data.total} 张
            </span>
            <div className="flex gap-2">
              <Button variant="secondary" disabled={data.page === 0} onClick={() => setPage((p) => p - 1)}>
                上一页
              </Button>
              <Button
                variant="secondary"
                disabled={(data.page + 1) * data.size >= data.total}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </SectionCard>
      )}

      {detail && (
        <SectionCard title={`${detail.head.rmaNo} · ${detail.head.rmaStatusLabel}`}>
          <dl className="grid gap-3 text-sm sm:grid-cols-2">
            <div>
              <dt className="text-muted">退货地址</dt>
              <dd>{addressText(detail.head) || '—'}</dd>
              <dd className="text-xs text-muted">
                {detail.head.returnAddressSourceLabel} · {detail.head.returnRecipientPhone}
              </dd>
            </div>
            <div>
              <dt className="text-muted">目标处理方式（意图，非处置结论）</dt>
              <dd>{detail.head.targetDispositionLabel}</dd>
              <dd className="text-xs text-muted">{dispositionHint}</dd>
            </div>
            <div>
              <dt className="text-muted">买家回寄物流</dt>
              <dd>
                {detail.head.returnTrackingNo
                  ? `${detail.head.returnCarrier} ${detail.head.returnTrackingNo}`
                  : '未登记'}
              </dd>
              <dd className="text-xs text-muted">{detail.head.returnLogisticsSourceLabel}</dd>
            </div>
            <div>
              <dt className="text-muted">寄回时限</dt>
              <dd>
                {detail.head.returnByAt ? new Date(detail.head.returnByAt).toLocaleString() : '—'}
                {' · '}
                {detail.timelinessStateLabel} · {remainingText(detail.remainingSeconds)}
              </dd>
            </div>
          </dl>

          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => void checkQuantities(detail.head.caseId)} disabled={busy}>
              校验可退数量
            </Button>
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() =>
                void act(() =>
                  recordReturnLogistics(detail.head.id, {
                    carrier: 'FedEx',
                    trackingNo: `RMA-${detail.head.rmaNo}`,
                    shippedAt: new Date().toISOString(),
                    logisticsSource: 'TENANT_MAINTAINED',
                    expectedVersion: detail.head.version,
                  }),
                )
              }
            >
              登记买家回寄物流
            </Button>
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() => void act(() => cancelRma(detail.head.id, { reason: '人工撤销', expectedVersion: detail.head.version }))}
            >
              撤销授权
            </Button>
          </div>

          <h4 className="mt-4 font-medium">授权行</h4>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">SKU</th>
                <th className="p-2">申请</th>
                <th className="p-2">授权</th>
                <th className="p-2">订购 / 已发 / 已被占</th>
                <th className="p-2">审批时可退</th>
                <th className="p-2">指示性金额</th>
              </tr>
            </thead>
            <tbody>
              {detail.lines.map((line) => (
                <tr key={line.id} className="border-b">
                  <td className="p-2">
                    <div className="font-mono text-xs">{line.skuCode}</div>
                    <div className="text-xs text-muted">{line.skuName}</div>
                  </td>
                  <td className="p-2">{quantity(line.requestedQty)}</td>
                  <td className="p-2">{quantity(line.authorizedQty)}</td>
                  <td className="p-2 text-xs">
                    {quantity(line.orderedQtySnapshot)} / {quantity(line.shippedQtySnapshot)} /{' '}
                    {quantity(line.priorAuthorizedQtySnapshot)}
                  </td>
                  <td className="p-2">{quantity(line.returnableQty)}</td>
                  <td className="p-2 text-xs">
                    {amount(line.indicativeRefundAmount)}
                    <div className="text-muted">不是可退余额校验结果</div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <h4 className="mt-4 font-medium">审批留痕（追加式，不可改写）</h4>
          <ul className="space-y-1 text-sm">
            {detail.events.map((e) => (
              <li key={e.id}>
                <span className="text-muted">{new Date(e.occurredAt).toLocaleString()}</span> · {eventText(e)}
                {e.reasonNote ? <span className="text-muted"> — {e.reasonNote}</span> : null}
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      {quantities && (
        <SectionCard title="可退数量校验（三个来源服务端实时查）">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">SKU</th>
                <th className="p-2">申请量</th>
                <th className="p-2">订购量</th>
                <th className="p-2">已发量</th>
                <th className="p-2">其他在途授权</th>
                <th className="p-2">已退数量</th>
                <th className="p-2">可退上限</th>
                <th className="p-2">挡住它的原因</th>
              </tr>
            </thead>
            <tbody>
              {quantities.lines.map((line) => {
                const r = returnableText(line);
                return (
                  <tr key={line.caseLineId} className="border-b">
                    <td className="p-2">
                      <div className="font-mono text-xs">{line.skuCode}</div>
                      <div className="text-xs text-muted">{line.skuName}</div>
                    </td>
                    <td className="p-2">{quantity(line.caseRequestedQty)}</td>
                    <td className="p-2">{quantity(line.orderedQty)}</td>
                    <td className="p-2" title={shippedSourceText(line)}>
                      {quantity(line.shippedQty ?? null)}
                    </td>
                    <td className="p-2">{quantity(line.inflightAuthorizedQty)}</td>
                    {/* 不可得就显示不可得 —— 显示 0 会让人以为「从来没退过」。 */}
                    <td className="p-2 text-xs text-muted">{receivedQuantityText(line)}</td>
                    <td className="p-2">
                      <Badge tone={r.tone}>{r.value}</Badge>
                    </td>
                    <td className="p-2 text-xs text-muted">
                      {line.blockingReasons.length > 0 ? line.blockingReasons.join('；') : '—'}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="mt-2 text-xs text-muted">{quantities.note}</p>
        </SectionCard>
      )}

      {caps && (
        <SectionCard title="本模块的能力边界">
          <p className="text-sm">
            <strong>可退数量口径：</strong>
            {caps.returnableQuantityNote}
          </p>
          <h4 className="mt-3 font-medium">未交付的下游（**不建影子表**）</h4>
          <ul className="mt-1 space-y-1 text-sm">
            {caps.relatedModules.map((m) => (
              <li key={m.id}>
                <Badge tone="warning">{m.label}</Badge>{' '}
                <span className="text-muted">{unavailableText(m)}</span>
              </li>
            ))}
          </ul>
          <h4 className="mt-3 font-medium">刻意不做的事</h4>
          <ul className="mt-1 space-y-1 text-sm">
            {caps.boundaries.map((b) => (
              <li key={b.boundary}>
                <strong>{b.boundary}</strong>
                <span className="text-muted">
                  {' '}
                  — 归属 {b.ownerModule}：{b.reason}
                </span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}
