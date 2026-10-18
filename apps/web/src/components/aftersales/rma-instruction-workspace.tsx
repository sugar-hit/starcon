'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  RmaInstructionCapabilities,
  RmaInstructionDetail,
  RmaInstructionPage,
  RmaLabelContent,
} from '../../../../../packages/api-contracts/src/index';
import {
  acknowledgeInstruction,
  actionButton,
  addressText,
  appendTracking,
  cancelInstruction,
  createInstruction,
  deliverLabel,
  eventText,
  expireInstructions,
  fetchInstructionCapabilities,
  fetchInstructionDetail,
  fetchInstructionList,
  fetchLabelContent,
  generateLabel,
  goodsReceivedHint,
  issueInstruction,
  labelIntegrityHint,
  lagText,
  quantity,
  remainingText,
  stateTone,
  timelinessTone,
  trackingEventText,
  trackingTone,
  voidLabel,
} from './rma-instruction-model';

/**
 * 11.2.1.2 退货指令下发。
 *
 * <p>蓝图：「向买家发出退货指引（地址、要求、时限），生成退货标签，跟踪买家回寄物流。」
 * 输出：「退货指令 + 标签文件 + 回寄物流轨迹跟踪。」
 * 加粗句：「<b>多数平台退货由买家寄回</b>，本系统只负责指引与跟踪，<b>不控制实物</b>。」
 *
 * <p><b>页面围绕那句加粗句组织</b>：
 *
 * <ol>
 *   <li><b>第一屏就是「已送达 / 已超时」</b>（{@link StatCard}），不是「一共多少条」——
 *       已送达的那些才是要天天盯的：买家已经拿到指引，货可能在路上，也可能压根没寄。</li>
 *   <li><b>没有「登记已收到」这个按钮</b>：11.2.2.1 未交付。渲染一个注定被拒的按钮
 *       等于告诉运营系统坏了，而真相是这条路径本就不该存在。</li>
 *   <li><b>轨迹「已签收」旁边永远跟着一句话</b>：承运商口径的送达 ≠ 我们收到货。
 *       退回商品<b>默认进待检</b>，直接当已收货处理会把残次品当新品卖出去。</li>
 *   <li><b>标签完整性校验失败时红色警示</b>，不照常渲染那份地址。</li>
 * </ol>
 */
export function RmaInstructionWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [carrier, setCarrier] = useState('');
  const [trackingNo, setTrackingNo] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [data, setData] = useState<RmaInstructionPage | null>(null);
  const [caps, setCaps] = useState<RmaInstructionCapabilities | null>(null);
  const [detail, setDetail] = useState<RmaInstructionDetail | null>(null);
  const [labelContent, setLabelContent] = useState<RmaLabelContent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // 登记轨迹的三个输入：真实表单，不是硬编码常量。
  const [evCarrier, setEvCarrier] = useState('');
  const [evTrackingNo, setEvTrackingNo] = useState('');
  const [evCode, setEvCode] = useState('IN_TRANSIT');
  const [evAt, setEvAt] = useState(() => new Date().toISOString().slice(0, 16));

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」，
      //   不能把 undefined 当成一个已给的键塞进去。
      const filters: Parameters<typeof fetchInstructionList>[0] = { page, size: 20 };
      if (status) filters.status = status;
      if (keyword) filters.keyword = keyword;
      if (carrier) filters.carrier = carrier;
      if (trackingNo) filters.trackingNo = trackingNo;
      if (overdueOnly) filters.overdueOnly = true;
      const [list, capabilities] = await Promise.all([
        fetchInstructionList(filters),
        fetchInstructionCapabilities(),
      ]);
      setData(list);
      setCaps(capabilities);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [page, status, keyword, carrier, trackingNo, overdueOnly]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setError(null);
    setLabelContent(null);
    try {
      setDetail(await fetchInstructionDetail(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载详情失败');
    }
  }, []);

  const loadLabel = useCallback(async (id: string) => {
    setError(null);
    try {
      setLabelContent(await fetchLabelContent(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取标签文件失败');
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
    {
      label: '已送达买家（还要管）',
      value: summary?.deliveredToBuyer ?? 0,
      polarity: 'positive',
    },
    { label: '已超寄回时限', value: overdue, polarity: overdue ? 'negative' : 'neutral' },
    { label: '待下发草稿', value: summary?.draft ?? 0, polarity: 'neutral' },
    {
      label: '有回寄轨迹 / 已登记实收',
      // 已登记实收**恒为 0**：登记实收属 11.2.2.1，未交付。
      value: summary?.withTracking ?? 0,
      polarity: 'neutral',
    },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="退货指令下发"
        description="向买家发出退货指引（地址 / 要求 / 时限），生成退货标签，跟踪买家回寄物流。"
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
        <p>{goodsReceivedHint}</p>
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
              <option value="ISSUED">已下发</option>
              <option value="ACKNOWLEDGED">买家已确认</option>
              <option value="EXPIRED">时限已到，自动失效</option>
              <option value="CANCELLED">已撤销</option>
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block">关键字</span>
            <Input
              value={keyword}
              placeholder="指令号 / 授权单号 / 售后单号 / 订单号 / 买家邮箱"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label className="text-sm">
            <span className="mb-1 block">承运商</span>
            <Input
              value={carrier}
              placeholder="FedEx / UPS / ..."
              onChange={(e) => {
                setCarrier(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label className="text-sm">
            <span className="mb-1 block">运单号</span>
            <Input
              value={trackingNo}
              placeholder="客服手上只有一个单号时"
              onChange={(e) => {
                setTrackingNo(e.target.value);
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
            onClick={() => void act(() => expireInstructions())}
            title="把寄回时限已到且仍在途的指引自动失效（只判时限，不判货有没有回来）"
          >
            执行时限到期失效
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
          title="还没有退货指引"
          description="退货指引由退货授权单发起：先在退货授权单页把该单审批为「授权 / 部分授权」，再对本单下发指引。"
        />
      ) : (
        <SectionCard title={`退货指引（${data.total} 条）`}>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">指令号</th>
                <th className="p-2">授权单 / 售后单 / 订单</th>
                <th className="p-2">状态</th>
                <th className="p-2">应寄数量</th>
                <th className="p-2">寄回时限</th>
                <th className="p-2">标签 / 回寄轨迹</th>
                <th className="p-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((row) => (
                <tr key={row.id} className="border-b">
                  <td className="p-2 font-mono text-xs">{row.instructionNo}</td>
                  <td className="p-2">
                    <div>{row.rmaNo ?? '—'}</div>
                    <div className="text-xs text-muted">{row.caseNo ?? '—'}</div>
                    <div className="text-xs text-muted">{row.orderNo ?? '—'}</div>
                  </td>
                  <td className="p-2">
                    <div className="flex flex-col gap-1">
                      <Badge tone={stateTone(row.instructionStatus)}>
                        {row.instructionStatusLabel}
                      </Badge>
                      <Badge tone={timelinessTone(row.timelinessState)}>
                        {row.timelinessStateLabel} · {remainingText(row.remainingSeconds)}
                      </Badge>
                    </div>
                  </td>
                  <td className="p-2">
                    {quantity(row.expectedQty)}
                    <div className="text-xs text-muted">{row.lineCount} 行 · {row.deliveryChannelLabel}</div>
                  </td>
                  <td className="p-2 text-xs">
                    {row.returnByAt ? new Date(row.returnByAt).toLocaleString() : '—'}
                  </td>
                  <td className="p-2 text-xs">
                    <div>{row.labelStatus ? `${row.labelStatus} · ${row.labelNo}` : '未出标签'}</div>
                    {row.trackingEventCount > 0 ? (
                      <div className="mt-1 flex flex-wrap items-center gap-1">
                        <Badge tone={trackingTone(row.latestTrackingEventCode)}>
                          {row.latestTrackingEventLabel ?? '—'}
                        </Badge>
                        <span className="text-muted">{row.trackingEventCount} 条轨迹</span>
                      </div>
                    ) : (
                      <span className="text-muted">无回寄轨迹</span>
                    )}
                    {/* 有轨迹也**不等于**收到 —— 这里刻意不加「已收到」徽标。 */}
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
                          if (row.action?.allowed) {
                            void act(() =>
                              issueInstruction(row.id, {
                                issueNote: null,
                                expectedVersion: 0,
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
              第 {data.page + 1} 页 · 每页 {data.size} · 共 {data.total} 条
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
        <SectionCard title={`${detail.head.instructionNo} · ${detail.head.instructionStatusLabel}`}>
          <dl className="grid gap-3 text-sm sm:grid-cols-2">
            <div>
              <dt className="text-muted">退货地址（快照，下发后冻结）</dt>
              <dd>{addressText(detail.head) || '—'}</dd>
              <dd className="text-xs text-muted">
                {detail.head.addressSourceLabel}
                {detail.head.returnRecipientPhone ? ` · ${detail.head.returnRecipientPhone}` : ''}
              </dd>
            </div>
            <div>
              <dt className="text-muted">指引送达渠道</dt>
              <dd>{detail.head.deliveryChannelLabel}</dd>
              <dd className="text-xs text-muted">
                收件方：{detail.head.recipientName ?? '—'}
                {detail.head.recipientEmail ? ` · ${detail.head.recipientEmail}` : ''}
              </dd>
            </div>
            <div>
              <dt className="text-muted">寄回时限</dt>
              <dd>
                {new Date(detail.head.returnByAt).toLocaleString()} · {detail.timelinessStateLabel} ·{' '}
                {remainingText(detail.remainingSeconds)}
              </dd>
              <dd className="text-xs text-muted">
                时限到期只判「时限过了」，不判「货有没有回来」。
              </dd>
            </div>
            <div>
              <dt className="text-muted">包装与寄回要求</dt>
              <dd>{detail.head.packingRequirements || '—'}</dd>
              {detail.head.buyerNote && (
                <dd className="text-xs text-muted">附注：{detail.head.buyerNote}</dd>
              )}
            </div>
          </dl>

          <div className="mt-4 flex flex-wrap gap-2">
            {detail.actions
              .filter((a) => a.required)
              .map((action) => {
                const button = actionButton(action);
                return (
                  <Button
                    key={action.code}
                    variant={button.variant}
                    disabled={button.disabled || busy}
                    title={button.reason ?? undefined}
                    onClick={() => {
                      const id = detail.head.id;
                      const version = detail.head.version;
                      if (action.code === 'ISSUE') {
                        void act(() => issueInstruction(id, { issueNote: null, expectedVersion: version }));
                      } else if (action.code === 'ACKNOWLEDGE') {
                        void act(() => acknowledgeInstruction(id, { acknowledgeNote: '买家回复已收到', expectedVersion: version }));
                      } else if (action.code === 'CANCEL') {
                        void act(() => cancelInstruction(id, { reason: '人工撤销', expectedVersion: version }));
                      } else if (action.code === 'VOID_LABEL') {
                        void act(() => voidLabel(id, { reason: '承运商取消了运单' }));
                      }
                    }}
                  >
                    {button.label}
                  </Button>
                );
              })}
            <Button
              variant="secondary"
              disabled={busy || !detail.label}
              title={detail.label ? '按指令真值渲染 / 登记外部标签，并给出 SHA-256 校验和' : '当前没有未作废的标签'}
              onClick={() =>
                void act(() =>
                  generateLabel(detail.head.id, {
                    labelSource: 'SELF_GENERATED_TEMPLATE',
                    carrierCode: null,
                    serviceLevel: 'STANDARD',
                    labelFormat: 'TEXT_TEMPLATE',
                    trackingNo: null,
                    weightKg: null,
                    lengthCm: null,
                    widthCm: null,
                    heightCm: null,
                    content: null,
                  }),
                )
              }
            >
              生成退货标签
            </Button>
            <Button
              variant="secondary"
              disabled={busy || !detail.label || detail.label.labelStatus === 'DELIVERED'}
              title="生成 ≠ 交付：贴纸真的到买家手上了才算交付"
              onClick={() => void act(() => deliverLabel(detail.head.id, { deliveredVia: 'BUYER_EMAIL' }))}
            >
              交付标签
            </Button>
            <Button
              variant="secondary"
              disabled={busy || !detail.label}
              onClick={() => void loadLabel(detail.head.id)}
            >
              查看标签文件
            </Button>
          </div>

          {detail.actions
            .filter((a) => !a.allowed && a.unavailableReason)
            .map((a) => (
              <p key={a.code} className="mt-2 text-xs text-muted">
                「{a.label}」不可用 —— 归属 {a.ownerModule}：{a.unavailableReason}
              </p>
            ))}

          <h3 className="mt-5 text-sm font-medium">应寄商品（{quantity(detail.expectedQty)}）</h3>
          <table className="mt-2 w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">SKU</th>
                <th className="p-2">应寄 / 授权</th>
                <th className="p-2">单价</th>
                <th className="p-2">逐行说明</th>
              </tr>
            </thead>
            <tbody>
              {detail.lines.map((line) => (
                <tr key={line.id} className="border-b">
                  <td className="p-2">
                    <div className="font-mono text-xs">{line.skuCode}</div>
                    <div className="text-xs text-muted">{line.skuName}</div>
                  </td>
                  <td className="p-2">
                    {quantity(line.expectedQty)} / {quantity(line.authorizedQty)}
                  </td>
                  <td className="p-2 text-xs">
                    {line.unitPrice} {line.currency}
                  </td>
                  <td className="p-2 text-xs text-muted">{line.lineNote || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h3 className="mt-5 text-sm font-medium">
            退货标签
            {detail.label ? (
              <span className="ml-2 font-normal text-muted">{detail.label.labelStatusLabel}</span>
            ) : null}
          </h3>
          {detail.label ? (
            <p className="mt-1 text-xs text-muted">
              {detail.label.labelSourceLabel} · {detail.label.labelNo} · SHA-256{' '}
              <span className="font-mono">{detail.label.labelChecksum.slice(0, 16)}…</span> ·{' '}
              {detail.label.contentLength} 字符
              {detail.label.carrierCode
                ? ` · ${detail.label.carrierCode}${detail.label.trackingNo ? ` ${detail.label.trackingNo}` : ''}`
                : ' · 无承运商运单号（本模块出的模板标签不含运单号，运单号由轨迹登记带入）'}
            </p>
          ) : (
            <p className="mt-1 text-xs text-muted">当前没有未作废的退货标签。</p>
          )}

          {labelContent && (
            <div
              className={`mt-2 rounded-md border p-3 text-sm ${
                labelContent.checksumVerified
                  ? 'border-border'
                  : 'border-red-500/60 bg-red-500/10'
              }`}
            >
              {!labelContent.checksumVerified && (
                <p className="mb-2 text-danger" role="alert">
                  ⚠️ 完整性校验失败 —— 库里存的内容与它的校验和对不上，标签文件在入库之后被改过。
                  <span className="block text-xs text-muted">{labelIntegrityHint}</span>
                </p>
              )}
              <pre className="whitespace-pre-wrap break-all text-xs">{labelContent.content}</pre>
            </div>
          )}

          <h3 className="mt-5 text-sm font-medium">回寄物流轨迹</h3>
          {detail.trackingParcels.length === 0 ? (
            <p className="mt-1 text-xs text-muted">还没有任何回寄轨迹。</p>
          ) : (
            <div className="mt-2 flex flex-wrap gap-2">
              {detail.trackingParcels.map((parcel) => (
                <Badge key={`${parcel.carrierCode}-${parcel.trackingNo}`} tone={trackingTone(parcel.currentEventCode)}>
                  {parcel.carrierCode} {parcel.trackingNo} · {parcel.currentEventCodeLabel} ·{' '}
                  {parcel.eventCount} 条
                </Badge>
              ))}
            </div>
          )}
          {detail.trackingParcels.some((p) => p.currentEventCode === 'DELIVERED') && (
            <p className="mt-2 text-xs text-muted">
              ⚠️ 「已签收」只表示**承运商说**这个包裹送到了，不等于我们收到货。登记实收属 11.2.2.1，
              未交付；退回商品<b>默认进待检</b>。
            </p>
          )}
          <ol className="mt-2 space-y-1 text-xs">
            {detail.trackingEvents.map((event) => (
              <li key={event.id} className="flex flex-wrap items-center gap-2">
                <Badge tone={trackingTone(event.eventCode)}>{event.eventCodeLabel}</Badge>
                <span>{trackingEventText(event)}</span>
                <span className="text-muted">
                  {new Date(event.occurredAt).toLocaleString()} · {lagText(event.lagSeconds)}
                </span>
              </li>
            ))}
          </ol>

          {/* 登记轨迹：人工登记是当前**唯一**真能写进来的来源。 */}
          <div className="mt-3 flex flex-wrap items-end gap-3">
            <label className="text-sm">
              <span className="mb-1 block">承运商</span>
              <Input value={evCarrier} onChange={(e) => setEvCarrier(e.target.value)} placeholder="FedEx" />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">运单号</span>
              <Input value={evTrackingNo} onChange={(e) => setEvTrackingNo(e.target.value)} />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">事件</span>
              <select className="rounded border px-2 py-1" value={evCode} onChange={(e) => setEvCode(e.target.value)}>
                <option value="ACCEPTED">已揽收</option>
                <option value="PICKED_UP">已取件</option>
                <option value="IN_TRANSIT">运输中</option>
                <option value="ARRIVED_AT_ORIGIN">已到目的地</option>
                <option value="CUSTOMS_HOLD">清关扣留</option>
                <option value="OUT_FOR_DELIVERY">派送中</option>
                <option value="DELIVERED">已签收</option>
                <option value="EXCEPTION">异常</option>
                <option value="RETURNED_TO_SENDER">退回寄件人</option>
                <option value="CANCELLED">已取消</option>
              </select>
            </label>
            <label className="text-sm">
              <span className="mb-1 block">发生时刻</span>
              <Input type="datetime-local" value={evAt} onChange={(e) => setEvAt(e.target.value)} />
            </label>
            <Button
              variant="secondary"
              disabled={busy || !evCarrier || !evTrackingNo}
              title="追加式：承运商重复推送会被去重"
              onClick={() =>
                void act(() =>
                  appendTracking(detail.head.id, {
                    carrierCode: evCarrier,
                    trackingNo: evTrackingNo,
                    eventCode: evCode,
                    eventLocation: null,
                    eventDescription: null,
                    occurredAt: new Date(evAt).toISOString(),
                    trackedAt: null,
                    trackingSource: 'TENANT_MANUAL_ENTRY',
                  }),
                )
              }
            >
              登记一条轨迹
            </Button>
          </div>

          <h3 className="mt-5 text-sm font-medium">指令留痕</h3>
          <ol className="mt-2 space-y-1 text-xs">
            {detail.events.map((event) => (
              <li key={event.id}>
                {new Date(event.occurredAt).toLocaleString()} · {eventText(event)}
                {event.reasonNote ? ` · ${event.reasonNote}` : ''}
              </li>
            ))}
          </ol>

          <p className="mt-5 text-xs text-muted">{detail.note}</p>

          {caps && (
            <>
              <h3 className="mt-5 text-sm font-medium">本模块刻意不做的事</h3>
              <ul className="mt-2 space-y-1 text-xs text-muted">
                {caps.boundaries.map((b) => (
                  <li key={b.boundary}>
                    <span className="text-foreground">{b.boundary}</span>（归属 {b.ownerModule}）：{b.reason}
                  </li>
                ))}
              </ul>
            </>
          )}
        </SectionCard>
      )}

      {error && detail && (
        <Button variant="ghost" onClick={() => setDetail(null)}>
          收起详情
        </Button>
      )}

      <SectionCard title="新建退货指引">
        <p className="text-xs text-muted">
          指引必须挂在一张**已授权 / 部分授权**的退货授权单下；地址与时限由服务端从授权单复制成
          **快照**，不给手工重敲的机会。
        </p>
        <NewInstructionForm
          busy={busy}
          onCreate={(body) =>
            void act(async () => {
              const created = await createInstruction(body);
              setDetail(created);
            })
          }
        />
      </SectionCard>
    </div>
  );
}

/** 新建指引表单：退货授权单 id + 送达渠道 + 收件邮箱 + 包装要求，全部真提交给后端。 */
function NewInstructionForm({
  busy,
  onCreate,
}: {
  busy: boolean;
  onCreate: (body: unknown) => void;
}) {
  const [rmaId, setRmaId] = useState('');
  const [channel, setChannel] = useState('BUYER_EMAIL');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [requirements, setRequirements] = useState('');

  return (
    <div className="mt-3 flex flex-wrap items-end gap-3">
      <label className="text-sm">
        <span className="mb-1 block">退货授权单 id</span>
        <Input value={rmaId} onChange={(e) => setRmaId(e.target.value)} placeholder="uuid" />
      </label>
      <label className="text-sm">
        <span className="mb-1 block">送达渠道</span>
        <select className="rounded border px-2 py-1" value={channel} onChange={(e) => setChannel(e.target.value)}>
          <option value="BUYER_EMAIL">买家邮箱</option>
          <option value="PLATFORM_MESSAGE">平台站内消息</option>
          <option value="TENANT_DOWNLOAD_PRINT">租户下载后打印/转发</option>
          <option value="API_CALLBACK">外部系统回调下发</option>
        </select>
      </label>
      <label className="text-sm">
        <span className="mb-1 block">收件邮箱</span>
        <Input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="买家邮箱" />
      </label>
      <label className="text-sm">
        <span className="mb-1 block">买家电话</span>
        <Input value={phone} onChange={(e) => setPhone(e.target.value)} />
      </label>
      <label className="text-sm">
        <span className="mb-1 block">包装与寄回要求</span>
        <Input value={requirements} onChange={(e) => setRequirements(e.target.value)} placeholder="外箱请加固" />
      </label>
      <Button
        variant="secondary"
        disabled={busy || !rmaId || (channel === 'BUYER_EMAIL' && !email)}
        onClick={() =>
          onCreate({
            rmaId,
            deliveryChannel: channel,
            recipientName: null,
            recipientEmail: channel === 'BUYER_EMAIL' ? email : null,
            recipientPhone: phone || null,
            packingRequirements: requirements || null,
            buyerNote: null,
            syncFromRma: true,
            lines: null,
          })
        }
      >
        登记指引草稿
      </Button>
    </div>
  );
}
