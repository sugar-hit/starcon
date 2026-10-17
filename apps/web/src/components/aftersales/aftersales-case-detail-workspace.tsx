'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, Paperclip, MessageSquare, Plus, RefreshCw, TriangleAlert } from 'lucide-react';
import { Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type { AftersalesCaseDetail, AftersalesDetailCapabilities } from '../../../../../packages/api-contracts/src/index';
import {
  addAftersalesAttachment,
  addAftersalesCaseLine,
  addAftersalesMessage,
  addAftersalesNote,
  actionUsable,
  detailSections,
  fetchAftersalesCaseDetail,
  fetchAftersalesDetailCapabilities,
  formatAmount,
  formatFileSize,
  formatQuantity,
  formatTime,
  groupAttachments,
  timelineTone,
  undeliveredBlock,
} from './aftersales-case-detail-model';
// 时效四档与倒计时文案复用 11.1.1.1 的模型 —— 阈值只有一个真身（后端 DB 函数），
// 前端这里**不**另抄一份，否则两处必然漂移。
import {
  countdownText,
  platformSourceLabel,
  timelinessBadgeClass,
  timelinessTone,
} from './aftersales-case-model';

/**
 * 11.1.1.2 售后单详情。
 *
 * <p>蓝图：「单售后全景：原订单信息、申请内容与买家诉求、商品行与数量、沟通记录、附件与凭证、
 * 处置流程状态、退款与退货关联、平台侧状态、责任判定。」
 * 输出句：「详情 + 操作入口 + 时间线。」
 * 加粗句：「<b>详情页要把"买家说了什么"和"我们做了什么"按时间线对齐</b>，这是纠纷举证的基础。」
 *
 * <p><b>本页面对那句加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>时间线是页面的主干，放在最上面</b>，不是塞在最后当附录 ——
 *       举证时人是从这里开始读的。</li>
 *   <li><b>每条事件按说话方分档着色</b>（买家 / 我方 / 平台 / 系统），
 *       顶部并排列出「买家侧 N 条 · 我方侧 N 条」。那句话问的是「谁先说的」，
 *       靠颜色分层才能一眼看出来。</li>
 *   <li><b>顺序完全交给后端的 occurredAt</b>：前端不按本地时间重排 ——
 *       补录的历史消息必须落在它真实发生的位置上。</li>
 * </ol>
 *
 * <p><b>未交付的下游不画空白格</b>：退货 / 退款 / 纠纷 / 受理 / 归因当前全部未交付，
 * 每一块都显示「该能力未交付 + 归属模块 + 原因」。空白会被读成「这张单没有退款也没有纠纷」。
 *
 * <p><b>这是真实工作区**，不是占位页：数据全部来自 /trade/aftersales 的真实聚合查询。
 */
export function AftersalesCaseDetailWorkspace({ caseId }: { caseId: string }) {
  const [detail, setDetail] = useState<AftersalesCaseDetail | null>(null);
  const [caps, setCaps] = useState<AftersalesDetailCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDetail(await fetchAftersalesCaseDetail(caseId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '售后单详情加载失败');
    } finally {
      setLoading(false);
    }
  }, [caseId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchAftersalesDetailCapabilities());
      } catch {
        // 能力声明拿不到不影响详情本身，页面仍要能看单。
      }
    })();
  }, []);

  if (loading && !detail) {
    return (
      <div className="space-y-4">
        <PageHeader title="售后单详情" description={`售后单 ${caseId}`} />
        <SectionCard title="加载中">
          <p className="text-muted-foreground text-copy-13">正在读取售后单全景…</p>
        </SectionCard>
      </div>
    );
  }

  if (error && !detail) {
    return (
      <div className="space-y-4">
        <PageHeader
          title="售后单详情"
          description={`售后单 ${caseId}`}
          actions={
            <Link href="/aftersales">
              <Button variant="outline" size="sm">
                <ArrowLeft className="mr-1.5 h-4 w-4" />
                返回售后单列表
              </Button>
            </Link>
          }
        />
        <SectionCard title="加载失败">
          <EmptyState
            title="读不到这张售后单"
            description={`${error}。若单据确实存在，多半是它不属于当前租户 —— 系统不会因为猜到一个 ID 就把别人的单显示出来。`}
            action={
              <Button variant="outline" size="sm" onClick={() => void load()}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                重试
              </Button>
            }
          />
        </SectionCard>
      </div>
    );
  }

  if (!detail) return null;

  const header = detail.header;
  const sections = detailSections(detail);
  const terminal = header.terminal;

  return (
    <div className="space-y-4">
      <PageHeader
        title={`${header.caseTypeLabel} ${header.caseNo}`}
        description={`关联订单 ${detail.order?.orderNo ?? '（关联订单缺失）'} · ${detail.order ? platformSourceLabel(detail.order.orderSource) : '平台未知'} · 受理于 ${formatTime(header.receivedAt)}`}
        actions={
          <>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              <RefreshCw className="mr-1.5 h-4 w-4" />
              刷新
            </Button>
            <Link href="/aftersales">
              <Button variant="outline" size="sm">
                <ArrowLeft className="mr-1.5 h-4 w-4" />
                返回列表
              </Button>
            </Link>
          </>
        }
      />

      {/* 时效：详情页同样必须显眼（超时直接影响账号绩效与资金冻结） */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="当前状态"
          value={`${header.statusLabel}${terminal ? '（已结案）' : ''}`}
          comparisonLabel={terminal ? `结案于 ${formatTime(header.resolvedAt)}` : `受理于 ${formatTime(header.receivedAt)}`}
        />
        <StatCard
          label="平台时效"
          value={countdownText(header.timelinessState, header.remainingSeconds)}
          polarity="negative"
          comparisonLabel={`${timelinessTone(header.timelinessState).label} · 回复时限 ${formatTime(header.replyDueAt)}`}
        />
        <StatCard
          label="申请金额"
          value={formatAmount(header.requestedAmount, header.currency)}
          comparisonLabel={`原因：${header.reasonLabel}`}
        />
        <StatCard
          label="涉及数量"
          value={formatQuantity(header.requestedQuantity)}
          comparisonLabel={`${detail.lines.length} 个订单行登记 · ${detail.orderLines.filter((l) => l.involved).length} 行涉及本单`}
        />
      </div>

      <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
        {sections.map((s) => (
          <StatCard key={s.key} label={s.label} value={s.value} />
        ))}
      </div>

      {/* ============================ 时间线 ============================ */}
      <SectionCard
        title={`时间线（${detail.timeline.length} 条 · 买家侧 ${detail.buyerEventCount} · 我方侧 ${detail.ourEventCount}）`}
        description={detail.alignmentNote}
      >
        {detail.timeline.length === 0 ? (
          <EmptyState
            title="还没有任何事件"
            description="受理、责任人、沟通、凭证与授权都会汇进这条时间线。"
          />
        ) : (
          <ol className="space-y-2">
            {detail.timeline.map((e) => {
              const side = timelineTone(e.actorSide);
              return (
                <li
                  key={`${e.kind}-${e.occurredAt}-${e.reference ?? ''}`}
                  className={`timeline-item border-border rounded-md border px-3 py-2 ${side.badge}`}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={`badge ${side.badge}`}>{side.label}</span>
                    <span className="text-copy-13 font-medium">{e.kindLabel}</span>
                    <span className="text-muted-foreground text-copy-12">{formatTime(e.occurredAt)}</span>
                  </div>
                  {e.summary && <p className="mt-1 text-copy-13">{e.summary}</p>}
                  {e.body && (
                    <p className="text-muted-foreground mt-1 text-copy-13 whitespace-pre-wrap">{e.body}</p>
                  )}
                  {e.reference && (
                    <p className="text-muted-foreground mt-1 text-copy-12">引用：{e.reference}</p>
                  )}
                </li>
              );
            })}
          </ol>
        )}
      </SectionCard>

      {/* ============================ 原订单信息 ============================ */}
      <SectionCard title="原订单信息" description="直接读 sales_order 簇（V99，同库只读），不是复制品 —— 截图上的订单号与系统里的不一致是纠纷举证里最致命的破绽。">
        {!detail.order ? (
          <EmptyState
            title="关联订单缺失"
            description="这张售后单指向的订单在当前租户下查不到。售后单与订单之间刻意不加外键（避免对别的车道的表产生 DDL 依赖），所以缺失要在这里显式说出来，而不是显示成一堆空白。"
          />
        ) : (
          <dl className="grid gap-x-6 gap-y-2 sm:grid-cols-2 lg:grid-cols-3">
            <Field label="订单号" value={detail.order.orderNo ?? '—'} />
            <Field label="平台（无店铺）" value={platformSourceLabel(detail.order.orderSource)} />
            <Field
              label="店铺"
              value="不可得"
              hint="sales_order 没有 shop_code 列，卷 2 店铺在 channel 独立库禁止跨服务读"
            />
            <Field label="外部订单号" value={detail.order.externalOrderNo ?? '—'} />
            <Field label="订单状态" value={detail.order.orderStatus ?? '—'} />
            <Field label="订单总额" value={formatAmount(detail.order.grandTotalAmount, detail.order.currency)} />
            <Field label="客户" value={detail.order.customerName ?? '—'} />
            <Field label="客户类型" value={detail.order.customerType ?? '—'} />
            <Field label="收货人" value={detail.order.recipientName ?? '—'} />
            <Field label="联系电话" value={detail.order.recipientPhone ?? '—'} />
            <Field label="收货地址" value={detail.order.addressLine ?? '—'} />
            <Field label="提交时间" value={formatTime(detail.order.submittedAt)} />
          </dl>
        )}
      </SectionCard>

      {/* ============================ 商品行与数量 ============================ */}
      <SectionCard title="商品行与数量" description="订单行是 V99 的下单快照；involved 标记本单涉及哪几行。SKU 与单价不在本模块复制 —— 复制会与订单漂移。">
        {detail.orderLines.length === 0 ? (
          <EmptyState title="订单没有商品行" description="这张单关联的订单下没有任何订单行。" />
        ) : (
          <table className="w-full text-copy-13">
            <thead>
              <tr className="border-border border-b text-left">
                <th className="py-2">行</th>
                <th className="py-2">SKU</th>
                <th className="py-2">规格</th>
                <th className="py-2 text-right">订购数量</th>
                <th className="py-2 text-right">行金额</th>
                <th className="py-2 text-right">本单涉及</th>
                <th className="py-2">买家原话</th>
              </tr>
            </thead>
            <tbody>
              {detail.orderLines.map((l) => {
                const line = detail.lines.find((x) => x.orderLineId === l.id);
                return (
                  <tr
                    key={l.id}
                    className={`border-border border-b ${l.involved ? 'tone-overdue' : ''}`}
                  >
                    <td className="py-2">{l.lineNo}</td>
                    <td className="py-2">
                      {l.skuCode}
                      <span className="text-muted-foreground ml-2">{l.skuName}</span>
                    </td>
                    <td className="py-2">{l.specSnapshot ?? '—'}</td>
                    <td className="py-2 text-right">{formatQuantity(l.quantity)}</td>
                    <td className="py-2 text-right">{formatAmount(l.lineAmount)}</td>
                    <td className="py-2 text-right">
                      {l.involved ? formatQuantity(l.aftersalesQuantity) : '未涉及'}
                    </td>
                    <td className="py-2">{line?.buyerNote ?? '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
        {detail.lines.length > 0 && (
          <p className="text-muted-foreground mt-3 text-copy-13">
            本单登记的申请金额合计：
            {formatAmount(
              detail.lines.reduce((acc, l) => acc + (l.requestedAmount ?? 0), 0),
              header.currency,
            )}
          </p>
        )}
      </SectionCard>

      {/* ============================ 买家诉求与处置流程 ============================ */}
      <SectionCard title="申请内容与处置流程状态" description="买家诉求原文与授权决定。授权只推进到「处理中」—— 把它标成已解决会凭空造出一个已完成的售后。">
        <div className="space-y-3">
          <div>
            <p className="text-muted-foreground text-copy-12">买家诉求原文</p>
            <p className="text-copy-13 mt-1 whitespace-pre-wrap">
              {header.buyerRequest ?? '未记录（内部补录的单可能没有买家原话）'}
            </p>
          </div>
          <div>
            <p className="text-muted-foreground text-copy-12">原因补充</p>
            <p className="text-copy-13 mt-1">{header.reasonNote ?? '—'}</p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className={`badge ${timelinessBadgeClass(header.timelinessState)}`}>
              {timelinessTone(header.timelinessState).label}
            </span>
            <span className="text-muted-foreground text-copy-13">
              {countdownText(header.timelinessState, header.remainingSeconds)}
            </span>
          </div>
          {detail.authorizations.length === 0 ? (
            <p className="text-muted-foreground text-copy-13">尚无授权记录（授权在列表页批量操作里做）。</p>
          ) : (
            <ul className="space-y-1">
              {detail.authorizations.map((a) => (
                <li key={a.id} className="text-copy-13">
                  {formatTime(a.decidedAt)} · {a.decisionLabel} · {a.authorizationKindLabel}
                  {a.authorizedAmount !== null &&
                    ` · ${formatAmount(a.authorizedAmount, a.currency)}`}
                  {a.reasonNote ? ` · ${a.reasonNote}` : ''}
                </li>
              ))}
            </ul>
          )}
          {detail.ticket && (
            <p className="text-copy-13">
              工单 {detail.ticket.ticketNo}（{detail.ticket.ticketStatusLabel} ·
              优先级 {detail.ticket.priorityLabel}）—— 归属 {detail.ticket.ownerModule}，该模块未交付。
            </p>
          )}
          <p className="text-muted-foreground text-copy-13">
            责任人流转：
            {detail.ownerAssignments.length === 0
              ? '未分配责任人'
              : detail.ownerAssignments
                  .map((o) => `${o.ownerDisplayName ?? o.ownerRef}（${formatTime(o.capturedAt)}）`)
                  .join(' → ')}
          </p>
        </div>
      </SectionCard>

      {/* ============================ 责任判定 ============================ */}
      <SectionCard title="责任判定" description="「我们认了还是没认」由真实的授权决定 + 原因分类 + 责任人流转合成；环节级归因未交付。">
        <div className="space-y-2">
          <p className="text-copy-13">{detail.liability.liability}</p>
          <p className="text-muted-foreground text-copy-13">
            归因（批次 / 生产 / 运输 / 仓储 / 描述）：
            {detail.liability.attributionDelivered
              ? '已交付'
              : `该能力未交付，归属 ${detail.liability.attributionOwner}`}
          </p>
          <p className="text-muted-foreground text-copy-12">{detail.liability.attributionNote}</p>
        </div>
      </SectionCard>

      {/* ============================ 平台侧状态 ============================ */}
      <SectionCard title="平台侧状态" description="受理号与时限是平台倒计时的依据；平台最终判责未交付，因此不显示任何判定结论。">
        <dl className="grid gap-x-6 gap-y-2 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="平台单号" value={detail.platform.platformCaseNo ?? '—'} />
          <Field
            label="平台链接"
            value={detail.platform.platformCaseUrl ?? '—'}
          />
          <Field label="平台受理时刻" value={formatTime(detail.platform.platformReceivedAt)} />
          <Field label="要求回复时限" value={formatTime(detail.platform.replyDueAt)} />
          <Field label="要求处理时限" value={formatTime(detail.platform.resolveDueAt)} />
          <Field label="最紧时限" value={formatTime(detail.platform.effectiveDueAt)} />
          <Field
            label="平台判定结果"
            value={detail.platform.decisionStatusAvailable ? '—' : '该能力未交付'}
            hint={
              detail.platform.decisionStatusAvailable
                ? null
                : `归属 ${detail.platform.decisionStatusOwnerModule}`
            }
          />
        </dl>
      </SectionCard>

      {/* ============================ 沟通记录 ============================ */}
      <SectionCard
        title={`沟通记录（${detail.messages.length} 条）`}
        description="「买家说了什么」在这里，与下方时间线同源。平台对话当前只能由租户转录 —— 卷 2 平台消息未交付（channel 独立库无任何平台消息表，跨服务读被禁止）。"
      >
        {detail.messages.length === 0 ? (
          <EmptyState title="还没有沟通记录" description="登记一条沟通记录，它会同时出现在时间线的对应一侧。" />
        ) : (
          <ul className="space-y-2">
            {detail.messages.map((m) => (
              <li key={m.id} className={`border-border rounded-md border px-3 py-2 ${m.direction === 'BUYER_TO_US' ? timelineTone('BUYER').badge : timelineTone('US').badge}`}>
                <div className="flex flex-wrap items-center gap-2 text-copy-12">
                  <span className="font-medium">{m.directionLabel}</span>
                  <span className="text-muted-foreground">{m.channelLabel}</span>
                  <span className="text-muted-foreground">发生于 {formatTime(m.sentAt)}</span>
                  <span className="text-muted-foreground">采集于 {formatTime(m.capturedAt)}</span>
                  <span className="text-muted-foreground">来源：{m.messageSourceLabel}</span>
                </div>
                <p className="text-copy-13 mt-1 whitespace-pre-wrap">{m.messageBody}</p>
                {m.externalMessageId && (
                  <p className="text-muted-foreground mt-1 text-copy-12">
                    平台消息号：{m.externalMessageId}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ============================ 附件与凭证 ============================ */}
      <SectionCard
        title={`附件与凭证（${detail.attachments.length} 份）`}
        description="按提供方分组：平台判责看的是谁的证据。本服务只登记对象存储定位符，不托管文件内容 —— 存 base64 会把一页凭证顶到几十 MB。"
      >
        {detail.attachments.length === 0 ? (
          <EmptyState title="还没有凭证" description="登记一份凭证，它会进入时间线并按提供方归组。" />
        ) : (
          groupAttachments(detail.attachments).map((g) => (
            <div key={g.side} className="mb-3">
              <p className="text-muted-foreground text-copy-12">
                {g.side === 'BUYER' ? '买家提供' : '我方提供'}（{g.items.length}）
              </p>
              <ul className="mt-1 space-y-1">
                {g.items.map((a) => (
                  <li key={a.id} className="text-copy-13 flex flex-wrap items-center gap-2">
                    <Paperclip className="h-4 w-4" />
                    <span className="font-medium">{a.fileName}</span>
                    <span className="text-muted-foreground">{a.evidenceKindLabel}</span>
                    <span className="text-muted-foreground">{formatFileSize(a.fileSizeBytes)}</span>
                    <span className="text-muted-foreground">采集于 {formatTime(a.capturedAt)}</span>
                    <span className="text-muted-foreground text-copy-12">引用：{a.storageRef}</span>
                  </li>
                ))}
              </ul>
            </div>
          ))
        )}
      </SectionCard>

      {/* ============================ 关联下游 ============================ */}
      <SectionCard
        title="退款与退货关联"
        description="职责句点名的这一块当前**拿不到真数据** —— 每一块都显示归属模块而不是空白。"
      >
        <div className="grid gap-2 sm:grid-cols-2">
          {detail.relatedModules.map((m) => {
            const b = undeliveredBlock(m);
            return (
              <div
                key={m.code}
                className={`rounded-md border px-3 py-2 ${b.available ? '' : 'border-dashed opacity-80'}`}
              >
                <p className="text-copy-13 font-medium">{m.label}</p>
                <p className="text-muted-foreground text-copy-13 mt-1">
                  {b.available ? b.detail : b.detail}
                </p>
              </div>
            );
          })}
        </div>
      </SectionCard>

      {/* ============================ 操作入口 ============================ */}
      <SectionCard
        title="操作入口"
        description="未交付的动作显示归属模块并禁用 —— 渲染一个点了没反应的按钮比不渲染更糟。"
      >
        <div className="flex flex-wrap gap-2">
          {detail.actions.map((a) => (
            <span
              key={a.code}
              className={`badge ${actionUsable(a) ? '' : 'opacity-60'}`}
              title={
                a.available
                  ? (a.precondition ?? undefined)
                  : `该能力未交付，归属 ${a.ownerModule ?? '未标注'}：${a.reason ?? ''}`
              }
            >
              {a.label}
              {!actionUsable(a) ? '（未交付）' : ''}
            </span>
          ))}
        </div>
        {terminal && (
          <p className="text-muted-foreground mt-3 text-copy-13">
            <TriangleAlert className="mr-1 inline h-4 w-4" />
            售后单已结案，追加沟通记录 / 凭证 / 商品行 / 备注一律被拒：结案的语义是「这张单办完了」，
            事后往里塞东西会让「结案时到底有什么证据」永远没有答案。
          </p>
        )}
      </SectionCard>

      <AppendPanels caseId={caseId} terminal={terminal} onDone={load} />

      {error && (
        <SectionCard title="刷新出错">
          <p className="text-copy-13">{error}</p>
          <Button className="mt-2" variant="outline" size="sm" onClick={() => void load()}>
            <RefreshCw className="mr-1.5 h-4 w-4" />
            重试
          </Button>
        </SectionCard>
      )}
    </div>
  );
}

function Field({ label, value, hint }: { label: string; value: string; hint?: string | null | undefined }) {
  return (
    <div>
      <dt className="text-muted-foreground text-copy-12">{label}</dt>
      <dd className="text-copy-13">{value}</dd>
      {hint && <p className="text-muted-foreground text-copy-12">{hint}</p>}
    </div>
  );
}

/**
 * 四个追加面板。
 *
 * <p>四张留痕表在 DB 层是 **append-only**（运行角色连 UPDATE/DELETE 权限都没有），
 * 所以这里只有「追加」与「刷新」，**没有**编辑与删除 —— 改掉或删掉一行就是毁掉纠纷证据。
 */
function AppendPanels({
  caseId,
  terminal,
  onDone,
}: {
  caseId: string;
  terminal: boolean;
  onDone: () => void;
}) {
  const [note, setNote] = useState('');
  const [msg, setMsg] = useState({
    direction: 'BUYER_TO_US',
    channel: 'PLATFORM',
    messageBody: '',
    sentAt: new Date().toISOString(),
  });
  const [att, setAtt] = useState({
    evidenceKind: 'BUYER_EVIDENCE',
    fileName: '',
    storageRef: '',
    attachedBySide: 'BUYER',
    description: '',
  });
  const [line, setLine] = useState({
    orderLineId: '',
    quantity: '',
    requestedAmount: '',
    buyerNote: '',
  });
  const [busy, setBusy] = useState<string | null>(null);
  const [result, setResult] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const run = async (key: string, fn: () => Promise<unknown>, ok: string) => {
    setBusy(key);
    setErr(null);
    setResult(null);
    try {
      await fn();
      setResult(ok);
      onDone();
    } catch (e) {
      setErr(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(null);
    }
  };

  if (terminal) return null;

  return (
    <SectionCard
      title="追加记录"
      description="备注 / 沟通 / 凭证 / 商品行四件都是追加式的，改与删在数据库层就没有权限 —— 这些都是纠纷举证的原材料。"
    >
      <div className="space-y-4">
        <div className="flex flex-wrap items-end gap-2">
          <Input
            className="w-72"
            placeholder="备注正文"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            aria-label="备注正文"
          />
          <Button
            size="sm"
            disabled={busy !== null || note.trim() === ''}
            onClick={() =>
              void run('note', () => addAftersalesNote(caseId, note.trim()), '已追加备注').then(
                () => setNote(''),
              )
            }
          >
            <MessageSquare className="mr-1.5 h-4 w-4" />
            加备注
          </Button>
        </div>

        <div className="flex flex-wrap items-end gap-2">
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={msg.direction}
            onChange={(e) => setMsg({ ...msg, direction: e.target.value })}
            aria-label="方向"
          >
            <option value="BUYER_TO_US">买家 → 我方</option>
            <option value="US_TO_BUYER">我方 → 买家</option>
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={msg.channel}
            onChange={(e) => setMsg({ ...msg, channel: e.target.value })}
            aria-label="渠道"
          >
            <option value="PLATFORM">平台站内信</option>
            <option value="EMAIL">邮件</option>
            <option value="CHAT">在线聊天</option>
            <option value="PHONE">电话</option>
            <option value="INTERNAL_NOTE">内部记录</option>
          </select>
          <Input
            className="w-52"
            placeholder="发生时刻（ISO，留空=现在）"
            value={msg.sentAt}
            onChange={(e) => setMsg({ ...msg, sentAt: e.target.value })}
            aria-label="发生时刻"
          />
          <Input
            className="w-64"
            placeholder="沟通正文"
            value={msg.messageBody}
            onChange={(e) => setMsg({ ...msg, messageBody: e.target.value })}
            aria-label="沟通正文"
          />
          <Button
            size="sm"
            disabled={busy !== null || msg.messageBody.trim() === ''}
            onClick={() =>
              void run(
                'msg',
                () =>
                  addAftersalesMessage(caseId, {
                    direction: msg.direction as 'BUYER_TO_US' | 'US_TO_BUYER',
                    channel: msg.channel as 'PLATFORM',
                    messageBody: msg.messageBody.trim(),
                    sentAt: msg.sentAt,
                  }),
                '已登记沟通记录',
              )
            }
          >
            <Plus className="mr-1.5 h-4 w-4" />
            登记沟通
          </Button>
        </div>

        <div className="flex flex-wrap items-end gap-2">
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={att.evidenceKind}
            onChange={(e) => setAtt({ ...att, evidenceKind: e.target.value })}
            aria-label="凭证类别"
          >
            <option value="BUYER_EVIDENCE">买家提供的证据</option>
            <option value="OUR_EVIDENCE">我方提供的证据</option>
            <option value="PRODUCT_PHOTO">商品照片</option>
            <option value="DELIVERY_PROOF">物流签收凭据</option>
            <option value="PAYMENT_PROOF">支付凭据</option>
            <option value="INSPECTION_REPORT">质检报告</option>
            <option value="OTHER">其他</option>
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={att.attachedBySide}
            onChange={(e) => setAtt({ ...att, attachedBySide: e.target.value })}
            aria-label="提供方"
          >
            <option value="BUYER">买家</option>
            <option value="US">我方</option>
          </select>
          <Input
            className="w-48"
            placeholder="文件名"
            value={att.fileName}
            onChange={(e) => setAtt({ ...att, fileName: e.target.value })}
            aria-label="文件名"
          />
          <Input
            className="w-64"
            placeholder="对象存储定位符（如 obj://case/xxx.jpg）"
            value={att.storageRef}
            onChange={(e) => setAtt({ ...att, storageRef: e.target.value })}
            aria-label="对象存储定位符"
          />
          <Button
            size="sm"
            disabled={busy !== null || att.fileName.trim() === '' || att.storageRef.trim() === ''}
            onClick={() =>
              void run(
                'att',
                () =>
                  addAftersalesAttachment(caseId, {
                    evidenceKind: att.evidenceKind as 'BUYER_EVIDENCE',
                    fileName: att.fileName.trim(),
                    storageRef: att.storageRef.trim(),
                    attachedBySide: att.attachedBySide as 'BUYER' | 'US',
                    description: att.description,
                  }),
                '已登记凭证',
              )
            }
          >
            <Paperclip className="mr-1.5 h-4 w-4" />
            登记凭证
          </Button>
        </div>

        <div className="flex flex-wrap items-end gap-2">
          <Input
            className="w-72"
            placeholder="订单行 ID"
            value={line.orderLineId}
            onChange={(e) => setLine({ ...line, orderLineId: e.target.value })}
            aria-label="订单行 ID"
          />
          <Input
            className="w-32"
            placeholder="涉及数量"
            value={line.quantity}
            onChange={(e) => setLine({ ...line, quantity: e.target.value })}
            aria-label="涉及数量"
          />
          <Input
            className="w-40"
            placeholder="行金额（仅退款类）"
            value={line.requestedAmount}
            onChange={(e) => setLine({ ...line, requestedAmount: e.target.value })}
            aria-label="行金额"
          />
          <Button
            size="sm"
            disabled={busy !== null || line.orderLineId.trim() === '' || line.quantity.trim() === ''}
            onClick={() =>
              void run(
                'line',
                () =>
                  addAftersalesCaseLine(caseId, {
                    orderLineId: line.orderLineId.trim(),
                    quantity: line.quantity.trim(),
                    ...(line.requestedAmount.trim()
                      ? { requestedAmount: line.requestedAmount.trim() }
                      : {}),
                    ...(line.buyerNote.trim() ? { buyerNote: line.buyerNote.trim() } : {}),
                  }),
                '已登记商品行',
              )
            }
          >
            <Plus className="mr-1.5 h-4 w-4" />
            登记商品行
          </Button>
        </div>

        <p className="text-muted-foreground text-copy-12">
          登记商品行需要填订单行 ID —— 它是上面「商品行与数量」表里的订单行主键；
          累计涉及数量不得超过单头的申请数量（数据库层钉死）。
        </p>
        {result && <p className="text-copy-13">{result}</p>}
        {err && <p className="text-copy-13 tone-overdue">{err}</p>}
      </div>
    </SectionCard>
  );
}
