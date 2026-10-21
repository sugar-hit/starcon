'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  ServiceTicketCapabilities,
  ServiceTicketDetail,
  ServiceTicketPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionBlockedReason,
  achievementText,
  assignServiceTicket,
  autoAssignServiceTicket,
  changeServiceTicketStatus,
  claimServiceTicket,
  closeServiceTicket,
  CLOSE_REASON_OPTIONS,
  countdownText,
  createServiceTicket,
  escalateHint,
  escalateMissingFields,
  escalateServiceTicket,
  ESCALATE_CASE_TYPE_OPTIONS,
  ESCALATE_DISPUTE_TYPE_OPTIONS,
  ESCALATE_REASON_CODE_OPTIONS,
  fetchServiceTicket,
  fetchServiceTicketCapabilities,
  fetchServiceTickets,
  formatTime,
  priorityTone,
  recordServiceTicketFirstReply,
  recordServiceTicketSatisfaction,
  releaseServiceTicket,
  satisfactionStars,
  SLA_STATE_OPTIONS,
  slaTone,
  statusTone,
  TICKET_PRIORITY_OPTIONS,
  TICKET_SORT_OPTIONS,
  TICKET_SOURCE_OPTIONS,
  TICKET_STATUS_OPTIONS,
  TICKET_TYPE_OPTIONS,
  transferServiceTicket,
} from './service-ticket-model';

/**
 * 11.4.2.1 工单管理。
 *
 * <p>蓝图：「管理非售后类的客户服务工单：咨询、投诉、催单、平台政策咨询；
 * 支持分配、SLA 计时、转派、关闭与满意度评价。」
 * 输出：「工单（待处理/处理中/待买家/已关闭）+ SLA 达成统计。」
 *
 * <p><b>页面围绕三条硬规则组织</b>：
 *
 * <ol>
 *   <li><b>倒计时由绝对截止时刻现算</b>：页面每秒重算一次剩余时间，
 *       拿的是 {@code replyDueAt} 这一个事实 —— 页面停着不动也不会变成假数据。</li>
 *   <li><b>「关闭」与「办完」在界面上分得开</b>：已解决 / 已升级转单算办完，
 *       买家未回应 / 重复工单 / 不再跟进只算「不再跟了」。</li>
 *   <li><b>动作的禁用理由由后端给</b>：每一个按钮灰掉时都带一句人话理由，
 *       不让人猜。</li>
 * </ol>
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/service-tickets} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 */
export function ServiceTicketWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [ticketType, setTicketType] = useState('');
  const [priority, setPriority] = useState('');
  const [sourceChannel, setSourceChannel] = useState('');
  const [slaState, setSlaState] = useState('');
  const [assignedToRef, setAssignedToRef] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [unassignedOnly, setUnassignedOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('sla_asc');

  const [data, setData] = useState<ServiceTicketPage | null>(null);
  const [caps, setCaps] = useState<ServiceTicketCapabilities | null>(null);
  const [detail, setDetail] = useState<ServiceTicketDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // ---- 倒计时的「现在」：每秒走一格，倒计时由**绝对截止时刻**现算 ----
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  // ---- 建单 ----
  const [newCaseId, setNewCaseId] = useState('');
  const [newType, setNewType] = useState('INQUIRY');
  const [newSource, setNewSource] = useState('MANUAL');
  const [newSourceRef, setNewSourceRef] = useState('');
  const [newSubject, setNewSubject] = useState('');
  const [newPriority, setNewPriority] = useState('NORMAL');
  const [newReason, setNewReason] = useState('');
  const [newOwner, setNewOwner] = useState('');

  // ---- 动作 ----
  const [ownerRef, setOwnerRef] = useState('');
  const [transferTo, setTransferTo] = useState('');
  const [reasonNote, setReasonNote] = useState('');
  const [closeReason, setCloseReason] = useState('RESOLVED');
  const [closeNote, setCloseNote] = useState('');
  const [score, setScore] = useState('5');
  const [comment, setComment] = useState('');
  const [escalateTarget, setEscalateTarget] = useState('COMPLAINT');
  const [escalateReason, setEscalateReason] = useState('');
  // ---- 升级为投诉：要在原售后单之外新建一张售后单，所以要目标售后单的全套要素 ----
  const [escalateCaseType, setEscalateCaseType] = useState('RETURN_REFUND');
  const [escalateReasonCode, setEscalateReasonCode] = useState('QUALITY_DEFECT');
  const [escalateAmount, setEscalateAmount] = useState('');
  const [escalateCurrency, setEscalateCurrency] = useState('USD');
  const [escalateQuantity, setEscalateQuantity] = useState('1');
  // ---- 升级为纠纷：要新建一条平台纠纷（11.4.1.1），平台主数据在 channel 独立库 ⇒ 快照落库 ----
  const [escalatePlatformCode, setEscalatePlatformCode] = useState('AMAZON');
  const [escalatePlatformName, setEscalatePlatformName] = useState('Amazon');
  const [escalateDisputeNo, setEscalateDisputeNo] = useState('');
  const [escalateOrderNo, setEscalateOrderNo] = useState('');
  const [escalateDisputeType, setEscalateDisputeType] = useState('A_TO_Z');
  const [escalateDisputeAmount, setEscalateDisputeAmount] = useState('');
  const [escalateDisputeCurrency, setEscalateDisputeCurrency] = useState('USD');
  const [escalateEvidenceDeadline, setEscalateEvidenceDeadline] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」。
      const filters: Parameters<typeof fetchServiceTickets>[0] = { page, size: 20, sort };
      if (status) filters.status = status;
      if (ticketType) filters.ticketType = ticketType;
      if (priority) filters.priority = priority;
      if (sourceChannel) filters.sourceChannel = sourceChannel;
      if (slaState) filters.slaState = slaState;
      if (assignedToRef) filters.assignedToRef = assignedToRef;
      if (overdueOnly) filters.overdueOnly = true;
      if (unassignedOnly) filters.unassignedOnly = true;
      if (keyword) filters.keyword = keyword;
      setData(await fetchServiceTickets(filters));
      setCaps(await fetchServiceTicketCapabilities());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [
    page,
    status,
    ticketType,
    priority,
    sourceChannel,
    slaState,
    assignedToRef,
    overdueOnly,
    unassignedOnly,
    keyword,
    sort,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const open = useCallback(async (id: string) => {
    setError(null);
    try {
      setDetail(await fetchServiceTicket(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<ServiceTicketDetail>) => {
      setBusy(true);
      setError(null);
      try {
        const d = await work();
        setDetail(d);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const head = detail?.head ?? null;
  const totalPages = data ? Math.max(1, Math.ceil(data.total / Math.max(1, data.size))) : 1;
  // ⚠️ noUncheckedIndexedAccess：数组下标取出来是 `T | undefined`，
  //   所以这里显式取一次并用真值判断，不在 JSX 里散落 `!`。
  const slaSnapshot = detail?.slaSnapshots[0] ?? null;
  const satisfaction = detail?.satisfactions[0] ?? null;
  // ⚠️ 升级表单缺什么**当场说清**，别让点了才被后端 400 挡回来。
  const escalateIncomplete = escalateMissingFields(escalateTarget, {
    reasonNote: escalateReason.trim() || reasonNote.trim(),
    caseType: escalateCaseType,
    reasonCode: escalateReasonCode,
    amount: escalateAmount,
    currency: escalateCurrency,
    quantity: escalateQuantity,
    platformCode: escalatePlatformCode,
    platformName: escalatePlatformName,
    disputeNo: escalateDisputeNo,
    disputeType: escalateDisputeType,
    disputeAmount: escalateDisputeAmount,
    disputeCurrency: escalateDisputeCurrency,
    evidenceDeadline: escalateEvidenceDeadline,
  });

  const slaSummary = useMemo(() => {
    const stats = data?.slaStats;
    if (!stats) return null;
    return {
      closed: stats.closedCount,
      reply: `${stats.replyMet}/${stats.replyMeasured}`,
      resolve: `${stats.resolveMet}/${stats.resolveMeasured}`,
      replyRate: achievementText(stats.replyAchievementRate),
      resolveRate: achievementText(stats.resolveAchievementRate),
      replyBreached: stats.replyBreached,
      resolveBreached: stats.resolveBreached,
    };
  }, [data]);

  const create = () => {
    void run(async () => {
      const d = await createServiceTicket({
        caseId: newCaseId.trim(),
        ticketType: newType,
        sourceChannel: newSource,
        sourceRef: newSourceRef.trim() || null,
        subject: newSubject.trim() || null,
        priority: newPriority,
        reasonNote: newReason.trim() || null,
        assignedToRef: newOwner.trim() || null,
        replyDueAt: null,
        resolveDueAt: null,
        knowledgeEntryId: null,
      });
      setNewCaseId('');
      setNewSubject('');
      setNewReason('');
      return d;
    });
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title="工单管理"
        description="11.4.2.1 · 咨询 / 投诉 / 催单 / 平台政策咨询的客服工单：分配、SLA 计时、转派、关闭与满意度评价。"
        actions={
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
          </Button>
        }
      />

      {error ? (
        <div className="flex items-start gap-2 rounded border border-destructive/40 bg-destructive/5 p-3 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
          <span className="text-destructive">{error}</span>
        </div>
      ) : null}

      {/* ---- 概览 ---- */}
      <div className="grid gap-3 md:grid-cols-4">
        <StatCard label="工单总数" value={data?.summary.total ?? 0} />
        <StatCard label="待处理" value={data?.summary.open ?? 0} />
        <StatCard label="待买家" value={data?.summary.waitingBuyer ?? 0} />
        <StatCard label="已超期未回" value={data?.summary.overdue ?? 0} />
      </div>

      {/* ---- SLA 达成统计（口径只认关闭时冻结的快照）---- */}
      {slaSummary ? (
        <SectionCard
          title="SLA 达成统计"
          description="口径只认**关闭那一刻冻结的快照** —— 「当时有没有达标」是已经发生的事实，不随规则改档翻来覆去。"
        >
          <div className="grid gap-3 text-sm md:grid-cols-5">
            <div>
              <div className="text-muted-foreground text-xs">已关闭工单</div>
              <div className="text-lg font-medium">{slaSummary.closed}</div>
            </div>
            <div>
              <div className="text-muted-foreground text-xs">回复达标</div>
              <div className="text-lg font-medium">{slaSummary.reply}</div>
              <div className="text-muted-foreground text-xs">超期 {slaSummary.replyBreached}</div>
            </div>
            <div>
              <div className="text-muted-foreground text-xs">处理达标</div>
              <div className="text-lg font-medium">{slaSummary.resolve}</div>
              <div className="text-muted-foreground text-xs">超期 {slaSummary.resolveBreached}</div>
            </div>
            <div>
              <div className="text-muted-foreground text-xs">回复达成率</div>
              <div className="text-lg font-medium">{slaSummary.replyRate}</div>
            </div>
            <div>
              <div className="text-muted-foreground text-xs">处理达成率</div>
              <div className="text-lg font-medium">{slaSummary.resolveRate}</div>
              <div className="text-muted-foreground text-xs">没配处理时限的不进分母</div>
            </div>
          </div>
        </SectionCard>
      ) : null}

      {/* ---- 边界声明：哪些上下游还没落地 ---- */}
      {caps ? (
        <SectionCard title="模块边界（上下游未交付的逐条说明）">
          <div className="space-y-2 text-sm">
            {caps.related
              .filter((r) => !r.delivered)
              .map((r) => (
                <div key={r.id} className="flex flex-wrap items-center gap-2">
                  <Badge tone="warning">{r.ownerModule ?? '未交付'}</Badge>
                  <span className="font-medium">{r.label}</span>
                  <span className="text-muted-foreground text-xs">{r.reason}</span>
                </div>
              ))}
          </div>
          <div className="text-muted-foreground mt-3 text-xs">{caps.upstreamGap}</div>
        </SectionCard>
      ) : null}

      {/* ---- 创建工单 ---- */}
      <SectionCard title="创建工单">
        <div className="grid gap-2 md:grid-cols-4">
          <Input
            placeholder="售后单 ID（必填：工单永远挂在一张售后单上）"
            value={newCaseId}
            onChange={(e) => setNewCaseId(e.target.value)}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={newType}
            onChange={(e) => setNewType(e.target.value)}
          >
            {TICKET_TYPE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={newSource}
            onChange={(e) => setNewSource(e.target.value)}
          >
            {TICKET_SOURCE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={newPriority}
            onChange={(e) => setNewPriority(e.target.value)}
          >
            {TICKET_PRIORITY_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <Input
            placeholder="来源引用（消息号 / 自动触发规则号）"
            value={newSourceRef}
            onChange={(e) => setNewSourceRef(e.target.value)}
          />
          <Input
            placeholder="工单主题（一句话说清这次要处理什么）"
            value={newSubject}
            onChange={(e) => setNewSubject(e.target.value)}
          />
          <Input
            placeholder="买家诉求原文"
            value={newReason}
            onChange={(e) => setNewReason(e.target.value)}
          />
          <Input
            placeholder="首派责任人（名册里的 owner_ref，可留空）"
            value={newOwner}
            onChange={(e) => setNewOwner(e.target.value)}
          />
        </div>
        <div className="mt-2 flex items-center gap-2">
          <Button disabled={busy || !newCaseId.trim()} onClick={create}>
            创建工单
          </Button>
          <span className="text-muted-foreground text-xs">
            一张售后单**只转一次工单**：重复转会明确报错并附上已有工单号。
            SLA 时限从租户 SLA 策略解析，**没配就报错**（本服务不编一个默认小时数）。
          </span>
        </div>
      </SectionCard>

      {/* ---- 工单列表 ---- */}
      <SectionCard title="工单列表">
        <div className="mb-3 grid gap-2 md:grid-cols-4">
          <Input
            placeholder="关键字（工单号 / 主题 / 诉求 / 责任人 / 售后单号）"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">全部状态</option>
            {TICKET_STATUS_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={ticketType}
            onChange={(e) => setTicketType(e.target.value)}
          >
            <option value="">全部类型</option>
            {TICKET_TYPE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
          >
            <option value="">全部优先级</option>
            {TICKET_PRIORITY_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={sourceChannel}
            onChange={(e) => setSourceChannel(e.target.value)}
          >
            <option value="">全部来源</option>
            {TICKET_SOURCE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={slaState}
            onChange={(e) => setSlaState(e.target.value)}
          >
            <option value="">全部 SLA 状态</option>
            {SLA_STATE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <Input
            placeholder="责任人（名册 owner_ref）"
            value={assignedToRef}
            onChange={(e) => setAssignedToRef(e.target.value)}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={sort}
            onChange={(e) => setSort(e.target.value)}
          >
            {TICKET_SORT_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={overdueOnly}
              onChange={(e) => setOverdueOnly(e.target.checked)}
            />
            只看已超期
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={unassignedOnly}
              onChange={(e) => setUnassignedOnly(e.target.checked)}
            />
            只看待认领
          </label>
        </div>

        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : data && data.rows.length === 0 ? (
          <EmptyState
            title="没有工单"
            description="从上面的表单建一张工单，或者调整筛选条件。"
          />
        ) : data ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">工单</th>
                <th className="py-2">类型 / 来源</th>
                <th className="py-2">售后单</th>
                <th className="py-2">SLA</th>
                <th className="py-2">责任人 / 状态</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((r) => (
                <tr
                  key={r.id}
                  className="hover:bg-muted/50 cursor-pointer border-b"
                  onClick={() => void open(r.id)}
                >
                  <td className="py-2">
                    <div className="font-medium">{r.ticketNo}</div>
                    <div className="text-muted-foreground text-xs">
                      {r.subject || '（无主题）'} · {formatTime(r.createdAt)}
                    </div>
                  </td>
                  <td className="py-2">
                    <div className="flex flex-wrap items-center gap-1">
                      <Badge tone="neutral">{r.ticketTypeLabel}</Badge>
                      <Badge tone={priorityTone(r.priority)}>{r.priorityLabel}</Badge>
                    </div>
                    <div className="text-muted-foreground text-xs">
                      {r.sourceChannelLabel}
                      {r.sourceRef ? ` · ${r.sourceRef}` : ''}
                    </div>
                  </td>
                  <td className="py-2">
                    <div>{r.caseNo}</div>
                    <div className="text-muted-foreground text-xs">
                      状态 {r.caseStatus}
                      {r.caseReplyDueAt ? ` · 平台回复时限 ${formatTime(r.caseReplyDueAt)}` : ''}
                    </div>
                  </td>
                  <td className="py-2">
                    {/* ⚠️ 倒计时由绝对截止时刻现算，不看接口给的快照。 */}
                    <Badge tone={slaTone(r.replySlaState)}>
                      {r.replySlaStateLabel} · {countdownText(r.replyDueAt, now)}
                    </Badge>
                    <div className="text-muted-foreground text-xs">
                      时限来源 {r.slaBasisLabel || '—'}
                      {r.slaReplyHours ? ` · ${r.slaReplyHours} 小时` : ''}
                    </div>
                  </td>
                  <td className="py-2">
                    <div className="flex flex-wrap items-center gap-1">
                      <Badge tone={statusTone(r.ticketStatus)}>{r.ticketStatusLabel}</Badge>
                      {r.satisfactionRating !== null ? (
                        <span className="text-muted-foreground text-xs">
                          {satisfactionStars(r.satisfactionRating)}
                        </span>
                      ) : null}
                    </div>
                    <div className="text-muted-foreground text-xs">
                      {r.assignedToRef || '待认领'}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}

        {data && data.total > 0 ? (
          <div className="mt-3 flex items-center justify-between text-sm">
            <span className="text-muted-foreground text-xs">
              第 {data.page + 1} / {totalPages} 页 · 共 {data.total} 条
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={loading || data.page <= 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={loading || data.page + 1 >= totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        ) : null}
      </SectionCard>

      {/* ---- 详情 ---- */}
      {detail && head ? (
        <SectionCard title={`工单详情 · ${head.ticketNo}`}>
          <div className="space-y-3">
            {/* 头 */}
            <div className="grid gap-2 text-sm md:grid-cols-3">
              <div>
                <div className="text-muted-foreground text-xs">类型 / 来源</div>
                <div className="flex flex-wrap items-center gap-1">
                  <Badge tone="neutral">{head.ticketTypeLabel}</Badge>
                  <Badge tone={priorityTone(head.priority)}>{head.priorityLabel}</Badge>
                </div>
                <div className="text-muted-foreground text-xs">
                  {head.sourceChannelLabel}
                  {head.sourceRef ? ` · ${head.sourceRef}` : ''}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">所属售后单（上下文）</div>
                <div>{head.caseNo}</div>
                <div className="text-muted-foreground text-xs">
                  状态 {head.caseStatus}
                  {head.caseReplyDueAt ? ` · 平台回复时限 ${formatTime(head.caseReplyDueAt)}` : ''}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">SLA</div>
                <div className="flex flex-wrap items-center gap-1">
                  <Badge tone={slaTone(head.replySlaState)}>{head.replySlaStateLabel}</Badge>
                  <Badge tone={slaTone(head.resolveSlaState)}>{head.resolveSlaStateLabel}</Badge>
                </div>
                <div className="text-muted-foreground text-xs">
                  回复时限 {formatTime(head.replyDueAt)} · 首次回复{' '}
                  {head.firstReplyAt ? formatTime(head.firstReplyAt) : '未回复'}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">责任人</div>
                <div>{head.assignedToRef || '待认领'}</div>
                <div className="text-muted-foreground text-xs">
                  {head.assignedAt ? `分配于 ${formatTime(head.assignedAt)}` : '尚未分配'}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">主题 / 诉求</div>
                <div>{head.subject || '—'}</div>
                <div className="text-muted-foreground text-xs">{head.reasonNote || '—'}</div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">关闭 / 升级</div>
                <div>
                  {head.closedAt ? (
                    <>
                      <Badge tone={statusTone(head.ticketStatus)}>{head.ticketStatusLabel}</Badge>{' '}
                      {head.closeReasonLabel}
                    </>
                  ) : (
                    '未关闭'
                  )}
                </div>
                <div className="text-muted-foreground text-xs">
                  {head.escalatedCaseNo ? `已转投诉单 ${head.escalatedCaseNo}` : ''}
                  {head.escalatedDisputeNo ? `已转纠纷 ${head.escalatedDisputeNo}` : ''}
                  {!head.escalatedCaseNo && !head.escalatedDisputeNo
                    ? escalateHint(head.ticketType)
                    : ''}
                </div>
              </div>
            </div>

            {/* ---- 动作区：每个按钮灰掉时都带人话理由 ---- */}
            <div className="rounded border p-3">
              <div className="mb-2 text-sm font-medium">可执行动作</div>
              <div className="grid gap-2 md:grid-cols-4">
                <Input
                  placeholder="责任人（名册 owner_ref）"
                  value={ownerRef}
                  onChange={(e) => setOwnerRef(e.target.value)}
                />
                <Input
                  placeholder="转派目标（名册 owner_ref）"
                  value={transferTo}
                  onChange={(e) => setTransferTo(e.target.value)}
                />
                <Input
                  placeholder="理由（转派 / 释放必填）"
                  value={reasonNote}
                  onChange={(e) => setReasonNote(e.target.value)}
                />
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={closeReason}
                  onChange={(e) => setCloseReason(e.target.value)}
                >
                  {CLOSE_REASON_OPTIONS.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="mt-2 flex flex-wrap gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={
                    busy ||
                    !ownerRef.trim() ||
                    actionBlockedReason(detail.actions, 'ASSIGN') !== null
                  }
                  onClick={() =>
                    void run(async () =>
                      assignServiceTicket(head.id, {
                        ownerRef: ownerRef.trim(),
                        ownerUserId: null,
                        reasonNote: reasonNote.trim() || null,
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  派单
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || actionBlockedReason(detail.actions, 'AUTO_ASSIGN') !== null}
                  onClick={() =>
                    void run(async () =>
                      autoAssignServiceTicket(head.id, {
                        reasonNote: reasonNote.trim() || null,
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  名册轮转派单
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={
                    busy ||
                    !transferTo.trim() ||
                    actionBlockedReason(detail.actions, 'TRANSFER') !== null
                  }
                  onClick={() =>
                    void run(async () =>
                      transferServiceTicket(head.id, {
                        toOwnerRef: transferTo.trim(),
                        toOwnerUserId: null,
                        reasonNote: reasonNote.trim(),
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  转派
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={
                    busy ||
                    !ownerRef.trim() ||
                    actionBlockedReason(detail.actions, 'CLAIM') !== null
                  }
                  onClick={() =>
                    void run(async () =>
                      claimServiceTicket(head.id, {
                        ownerRef: ownerRef.trim(),
                        ownerUserId: null,
                        reasonNote: reasonNote.trim() || null,
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  认领
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || actionBlockedReason(detail.actions, 'RELEASE') !== null}
                  onClick={() =>
                    void run(async () =>
                      releaseServiceTicket(head.id, {
                        reasonNote: reasonNote.trim(),
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  释放
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || actionBlockedReason(detail.actions, 'FIRST_REPLY') !== null}
                  onClick={() =>
                    void run(async () =>
                      recordServiceTicketFirstReply(head.id, {
                        replyNote: reasonNote.trim() || null,
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  记录首次回复
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || actionBlockedReason(detail.actions, 'CHANGE_STATUS') !== null}
                  onClick={() =>
                    void run(async () =>
                      changeServiceTicketStatus(head.id, {
                        targetStatus: 'IN_PROGRESS',
                        note: reasonNote.trim() || null,
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  开始处理
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || actionBlockedReason(detail.actions, 'CHANGE_STATUS') !== null}
                  onClick={() =>
                    void run(async () =>
                      changeServiceTicketStatus(head.id, {
                        targetStatus: 'WAITING_BUYER',
                        note: reasonNote.trim() || null,
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  转待买家
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || actionBlockedReason(detail.actions, 'CLOSE') !== null}
                  onClick={() =>
                    void run(async () =>
                      closeServiceTicket(head.id, {
                        closeReason,
                        closeNote: closeNote.trim() || null,
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  关闭
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={
                    busy ||
                    escalateIncomplete.length > 0 ||
                    actionBlockedReason(detail.actions, 'ESCALATE') !== null
                  }
                  onClick={() =>
                    void run(async () =>
                      escalateServiceTicket(head.id, {
                        targetType: escalateTarget,
                        reasonNote: escalateReason.trim() || reasonNote.trim(),
                        targetCaseType: escalateTarget === 'COMPLAINT' ? escalateCaseType : null,
                        targetReasonCode:
                          escalateTarget === 'COMPLAINT' ? escalateReasonCode : null,
                        targetAmount:
                          escalateTarget === 'COMPLAINT' && escalateAmount
                            ? Number(escalateAmount)
                            : null,
                        targetCurrency:
                          escalateTarget === 'COMPLAINT' ? escalateCurrency : null,
                        targetQuantity:
                          escalateTarget === 'COMPLAINT' && escalateQuantity
                            ? Number(escalateQuantity)
                            : null,
                        platformCode: escalateTarget === 'DISPUTE' ? escalatePlatformCode : null,
                        platformName: escalateTarget === 'DISPUTE' ? escalatePlatformName : null,
                        platformDisputeNo:
                          escalateTarget === 'DISPUTE' ? escalateDisputeNo : null,
                        platformOrderNo: escalateTarget === 'DISPUTE' ? escalateOrderNo : null,
                        disputeType: escalateTarget === 'DISPUTE' ? escalateDisputeType : null,
                        disputeAmount:
                          escalateTarget === 'DISPUTE' && escalateDisputeAmount
                            ? Number(escalateDisputeAmount)
                            : null,
                        disputeCurrency:
                          escalateTarget === 'DISPUTE' ? escalateDisputeCurrency : null,
                        evidenceDeadline:
                          escalateTarget === 'DISPUTE' && escalateEvidenceDeadline
                            ? new Date(escalateEvidenceDeadline).toISOString()
                            : null,
                        expectedVersion: head.version,
                      }),
                    )
                  }
                >
                  升级
                </Button>
              </div>
              <div className="text-muted-foreground mt-2 space-y-1 text-xs">
                {detail.actions
                  .filter((a) => !a.allowed)
                  .map((a) => (
                    <div key={a.code}>
                      <span className="font-medium">{a.label}</span>：{a.blockedReason}
                    </div>
                  ))}
              </div>
            </div>

            {/* ---- 升级：咨询 → 投诉 / 投诉 → 纠纷（两条真通道，各自要不同的要素）---- */}
            <div className="rounded border p-3">
              <div className="mb-1 text-sm font-medium">
                升级（{escalateHint(head.ticketType)}）
              </div>
              <div className="grid gap-2 md:grid-cols-4">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={escalateTarget}
                  onChange={(e) => setEscalateTarget(e.target.value)}
                >
                  <option value="COMPLAINT">升级为投诉（新建售后单）</option>
                  <option value="DISPUTE">升级为纠纷（新建平台纠纷）</option>
                </select>
                <Input
                  placeholder="升级理由（必填）"
                  value={escalateReason}
                  onChange={(e) => setEscalateReason(e.target.value)}
                />
              </div>
              {escalateTarget === 'COMPLAINT' ? (
                <div className="mt-2 grid gap-2 md:grid-cols-4">
                  <select
                    className="rounded border px-2 py-1 text-sm"
                    value={escalateCaseType}
                    onChange={(e) => setEscalateCaseType(e.target.value)}
                  >
                    {ESCALATE_CASE_TYPE_OPTIONS.map((o) => (
                      <option key={o.code} value={o.code}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                  <select
                    className="rounded border px-2 py-1 text-sm"
                    value={escalateReasonCode}
                    onChange={(e) => setEscalateReasonCode(e.target.value)}
                  >
                    {ESCALATE_REASON_CODE_OPTIONS.map((o) => (
                      <option key={o.code} value={o.code}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                  <Input
                    placeholder="申请金额"
                    value={escalateAmount}
                    onChange={(e) => setEscalateAmount(e.target.value)}
                  />
                  <Input
                    placeholder="币种（USD）"
                    value={escalateCurrency}
                    onChange={(e) => setEscalateCurrency(e.target.value.toUpperCase())}
                  />
                  <Input
                    placeholder="申请数量"
                    value={escalateQuantity}
                    onChange={(e) => setEscalateQuantity(e.target.value)}
                  />
                </div>
              ) : (
                <div className="mt-2 grid gap-2 md:grid-cols-4">
                  <Input
                    placeholder="平台码（如 AMAZON）"
                    value={escalatePlatformCode}
                    onChange={(e) => setEscalatePlatformCode(e.target.value.toUpperCase())}
                  />
                  <Input
                    placeholder="平台名称（登记那一刻的快照）"
                    value={escalatePlatformName}
                    onChange={(e) => setEscalatePlatformName(e.target.value)}
                  />
                  <Input
                    placeholder="平台纠纷单号"
                    value={escalateDisputeNo}
                    onChange={(e) => setEscalateDisputeNo(e.target.value)}
                  />
                  <Input
                    placeholder="平台订单号"
                    value={escalateOrderNo}
                    onChange={(e) => setEscalateOrderNo(e.target.value)}
                  />
                  <select
                    className="rounded border px-2 py-1 text-sm"
                    value={escalateDisputeType}
                    onChange={(e) => setEscalateDisputeType(e.target.value)}
                  >
                    {ESCALATE_DISPUTE_TYPE_OPTIONS.map((o) => (
                      <option key={o.code} value={o.code}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                  <Input
                    placeholder="争议金额"
                    value={escalateDisputeAmount}
                    onChange={(e) => setEscalateDisputeAmount(e.target.value)}
                  />
                  <Input
                    placeholder="争议币种（USD）"
                    value={escalateDisputeCurrency}
                    onChange={(e) => setEscalateDisputeCurrency(e.target.value.toUpperCase())}
                  />
                  <Input
                    placeholder="举证截止时刻（平台的时限）"
                    type="datetime-local"
                    value={escalateEvidenceDeadline}
                    onChange={(e) => setEscalateEvidenceDeadline(e.target.value)}
                  />
                </div>
              )}
              <div className="text-muted-foreground mt-1 text-xs">
                升级**不是**换个标签：咨询 → 投诉会在原售后单**之外**真的建出一张售后单，
                投诉 → 纠纷会真的建出一条平台纠纷（11.4.1.1），
                两边都把工单号 / 售后单号 / 主题 / 诉求原文带过去，并把新单号记回工单。
                {escalateIncomplete ? ` 当前还差：${escalateIncomplete}` : ''}
              </div>
            </div>

            {/* ---- 满意度评价 ---- */}
            <div className="rounded border p-3">
              <div className="mb-2 text-sm font-medium">
                满意度评价
                {satisfaction ? `：${satisfactionStars(satisfaction.score)}` : '：未评价'}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Input
                  placeholder="评分 1~5"
                  value={score}
                  onChange={(e) => setScore(e.target.value)}
                  className="w-24"
                />
                <Input
                  placeholder="评价内容"
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  className="flex-1"
                />
                <Button
                  size="sm"
                  variant="outline"
                  disabled={
                    busy ||
                    actionBlockedReason(detail.actions, 'SATISFACTION') !== null ||
                    detail.satisfactions.length > 0
                  }
                  onClick={() =>
                    void run(async () =>
                      recordServiceTicketSatisfaction(head.id, {
                        score: Number(score),
                        comment: comment.trim() || null,
                      }),
                    )
                  }
                >
                  记录评价
                </Button>
              </div>
              <div className="text-muted-foreground mt-1 text-xs">
                评价是「这次服务怎么样」的**一次**结论：不可改不可删，一张工单一条。
              </div>
            </div>

            {/* ---- SLA 达成快照 ---- */}
            <div className="rounded border p-3">
              <div className="mb-2 text-sm font-medium">SLA 达成快照（关闭那一刻冻结）</div>
              {slaSnapshot ? (
                <div className="grid gap-2 text-sm md:grid-cols-4">
                  <div>
                    <div className="text-muted-foreground text-xs">回复时限</div>
                    <Badge tone={slaSnapshot.replyMet ? 'success' : 'danger'}>
                      {slaSnapshot.replyMet ? '已达标' : '已超期'}
                    </Badge>
                    <div className="text-muted-foreground text-xs">
                      截止 {formatTime(slaSnapshot.replyDueAt)}
                    </div>
                  </div>
                  <div>
                    <div className="text-muted-foreground text-xs">处理时限</div>
                    {slaSnapshot.resolveDueAt === null ? (
                      <Badge tone="neutral">没配处理时限</Badge>
                    ) : (
                      <Badge tone={slaSnapshot.resolveMet ? 'success' : 'danger'}>
                        {slaSnapshot.resolveMet ? '已达标' : '已超期'}
                      </Badge>
                    )}
                    <div className="text-muted-foreground text-xs">
                      截止 {formatTime(slaSnapshot.resolveDueAt)}
                    </div>
                  </div>
                  <div>
                    <div className="text-muted-foreground text-xs">关闭时刻</div>
                    <div>{formatTime(slaSnapshot.closedAt)}</div>
                  </div>
                  <div>
                    <div className="text-muted-foreground text-xs">SLA 档</div>
                    <div>
                      {slaSnapshot.slaReplyHours
                        ? `回复 ${slaSnapshot.slaReplyHours} 小时`
                        : '平台给定'}
                    </div>
                  </div>
                </div>
              ) : (
                <div className="text-muted-foreground text-xs">
                  还没有快照 —— 工单关闭那一刻，DB 用**同一个**判定函数把达标结论算出来并冻结。
                </div>
              )}
            </div>

            {/* ---- 转派历史（append-only）---- */}
            <div>
              <div className="mb-1 text-sm font-medium">转派历史（{detail.assignments.length}）</div>
              {detail.assignments.length === 0 ? (
                <div className="text-muted-foreground text-xs">还没有派单记录。</div>
              ) : (
                <div className="space-y-1">
                  {detail.assignments.map((a) => (
                    <div
                      key={a.id}
                      className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2 text-sm"
                    >
                      <Badge tone="info">{a.assignActionLabel}</Badge>
                      <span className="text-muted-foreground text-xs">{a.assignModeLabel}</span>
                      <span>
                        {a.fromOwnerRef || '（无）'} → {a.toOwnerRef || '（无）'}
                      </span>
                      {a.reasonNote ? (
                        <span className="text-muted-foreground text-xs">{a.reasonNote}</span>
                      ) : null}
                      <span className="text-muted-foreground ml-auto text-xs">
                        {formatTime(a.createdAt)}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* ---- 事件流（append-only）---- */}
            <div>
              <div className="mb-1 text-sm font-medium">事件留痕（{detail.events.length}）</div>
              {detail.events.length === 0 ? (
                <div className="text-muted-foreground text-xs">还没有事件。</div>
              ) : (
                <div className="space-y-1">
                  {detail.events.map((e) => (
                    <div
                      key={e.id}
                      className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2 text-sm"
                    >
                      <Badge tone="neutral">{e.eventTypeLabel}</Badge>
                      {e.fromStatus && e.toStatus && e.fromStatus !== e.toStatus ? (
                        <span className="text-muted-foreground text-xs">
                          {e.fromStatus} → {e.toStatus}
                        </span>
                      ) : null}
                      {e.note ? (
                        <span className="text-muted-foreground text-xs">{e.note}</span>
                      ) : null}
                      <span className="text-muted-foreground ml-auto text-xs">
                        {formatTime(e.occurredAt)}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="text-muted-foreground text-xs">{detail.note}</div>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
