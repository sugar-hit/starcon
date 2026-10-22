'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  AlarmClock,
  CheckCircle2,
  CircleSlash,
  MessageSquare,
  PauseCircle,
  PlayCircle,
  RefreshCw,
  Ticket,
  TriangleAlert,
} from 'lucide-react';
import type {
  CrmServiceTicketProcessActionPage,
  CrmServiceTicketProcessCapabilities,
  CrmServiceTicketProcessLinkPage,
  CrmServiceTicketProcessNotePage,
  CrmServiceTicketProcessQueuePage,
  CrmServiceTicketProcessResolutionPage,
  CrmServiceTicketProcessTicket,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  addServiceTicketProcessNote,
  changeServiceTicketProcessStatus,
  closeServiceTicketProcessTicket,
  confirmServiceTicketProcessResolution,
  escalateServiceTicketProcessTicket,
  fetchServiceTicketProcessActions,
  fetchServiceTicketProcessCapabilities,
  fetchServiceTicketProcessLinks,
  fetchServiceTicketProcessNotes,
  fetchServiceTicketProcessQueue,
  fetchServiceTicketProcessResolutions,
  fetchServiceTicketProcessTicket,
  linkServiceTicketProcessDocument,
  pauseServiceTicketProcessSla,
  resumeServiceTicketProcessSla,
  revokeServiceTicketProcessResolution,
  sendServiceTicketProcessReply,
  sendServiceTicketProcessSatisfaction,
  serviceTicketProcessActionLabel,
  serviceTicketProcessChannelLabel,
  serviceTicketProcessCloseReasonLabel,
  serviceTicketProcessLinkTypeLabel,
  serviceTicketProcessPriorityLabel,
  serviceTicketProcessPriorityTone,
  serviceTicketProcessRemainingText,
  serviceTicketProcessResolutionLabel,
  serviceTicketProcessSlaStateLabel,
  serviceTicketProcessSlaTargetLabel,
  serviceTicketProcessSlaTone,
  serviceTicketProcessStatusLabel,
  serviceTicketProcessTypeLabel,
} from './service-ticket-process-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.4.1.2 工单处理工作区。
 *
 * <p>页面按「先看清口径、再处理、最后留痕」的顺序排，因为蓝图加粗句是
 * 「<b>工单关闭不等于问题解决</b>，需确认实际结果（退款到账、换货发出）才计为解决」。
 * 界面上第一件要让人看到的就是<b>「已关闭」与「已确认解决」是两个字段</b>，
 * 而不是一张把两者揉在一起的状态表。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」；
 *       「把处理结果推给上游售后单 / 订单」这一维刻意是 {@code available=false}
 *       （上游没有改写端点），页面**不放**任何假装能推的入口。</li>
 *   <li><b>处理队列</b> —— 真分页真筛选；每行给出**暂停感知**的营业时间 SLA 剩余
 *       与「是否已确认解决」。</li>
 *   <li><b>处理工作台</b> —— 回复 / 备注 / 状态流转 / 关闭 / 解决确认 / 满意度 /
 *       SLA 暂停恢复 / 关联单据 / 升级，每一步都留下处理记录。</li>
 *   <li><b>处理记录</b>、<b>备注与回复</b>、<b>关联单据</b>、<b>解决确认</b>
 *       四条线分开显示 —— 「做了什么」「说了什么」「关联了哪一单」「到底解决了没有」
 *       是四件不同的事，揉在一起就看不出来了。</li>
 * </ul>
 */
export function ServiceTicketProcessWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.service_ticket.update');

  const [capabilities, setCapabilities] = useState<CrmServiceTicketProcessCapabilities | null>(null);
  const [queue, setQueue] = useState<CrmServiceTicketProcessQueuePage | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [ticket, setTicket] = useState<CrmServiceTicketProcessTicket | null>(null);
  const [notes, setNotes] = useState<CrmServiceTicketProcessNotePage | null>(null);
  const [actions, setActions] = useState<CrmServiceTicketProcessActionPage | null>(null);
  const [links, setLinks] = useState<CrmServiceTicketProcessLinkPage | null>(null);
  const [resolutions, setResolutions] = useState<CrmServiceTicketProcessResolutionPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  // 筛选
  const [status, setStatus] = useState('');
  const [ticketType, setTicketType] = useState('');
  const [priority, setPriority] = useState('');
  const [assignedToRef, setAssignedToRef] = useState('');
  const [slaState, setSlaState] = useState('');
  const [keyword, setKeyword] = useState('');
  const [resolutionState, setResolutionState] = useState('');
  const [onlyPaused, setOnlyPaused] = useState(false);
  const [onlyOverdue, setOnlyOverdue] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);

  // 处理动作的表单
  const [replyContent, setReplyContent] = useState('');
  const [replyChannel, setReplyChannel] = useState('PLATFORM_MESSAGE');
  const [noteContent, setNoteContent] = useState('');
  const [targetStatus, setTargetStatus] = useState('IN_PROGRESS');
  const [statusNote, setStatusNote] = useState('');
  const [closeReason, setCloseReason] = useState('RESOLVED');
  const [closeNote, setCloseNote] = useState('');
  const [resolutionType, setResolutionType] = useState('REFUND_RECEIVED');
  const [resolutionNote, setResolutionNote] = useState('');
  const [satisfactionScore, setSatisfactionScore] = useState('5');
  const [satisfactionComment, setSatisfactionComment] = useState('');
  const [slaTarget, setSlaTarget] = useState('BOTH');
  const [pauseReason, setPauseReason] = useState('');
  const [resumeReason, setResumeReason] = useState('');
  const [linkType, setLinkType] = useState('ORDER');
  const [linkNo, setLinkNo] = useState('');
  const [linkNote, setLinkNote] = useState('');
  const [escalateType, setEscalateType] = useState('COMPLAINT');
  const [escalateReason, setEscalateReason] = useState('');

  const loadQueue = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, page] = await Promise.all([
        fetchServiceTicketProcessCapabilities(),
        fetchServiceTicketProcessQueue({
          status,
          ticketType,
          priority,
          assignedToRef,
          slaState,
          keyword,
          resolutionState,
          pausedOnly: onlyPaused || undefined,
          overdueOnly: onlyOverdue || undefined,
          page: pageIndex,
          size: 20,
        }),
      ]);
      setCapabilities(caps);
      setQueue(page);
    } catch (e) {
      setError(e instanceof Error ? e.message : '处理队列读取失败');
    } finally {
      setLoading(false);
    }
  }, [
    assignedToRef,
    keyword,
    onlyOverdue,
    onlyPaused,
    pageIndex,
    priority,
    resolutionState,
    slaState,
    status,
    ticketType,
  ]);

  const loadTicket = useCallback(async (id: string) => {
    try {
      const [head, notePage, actionPage, linkPage, resolutionPage] = await Promise.all([
        fetchServiceTicketProcessTicket(id),
        fetchServiceTicketProcessNotes(id, 0, 20),
        fetchServiceTicketProcessActions(id, 0, 20),
        fetchServiceTicketProcessLinks(id, 0, 20),
        fetchServiceTicketProcessResolutions(id, 0, 20),
      ]);
      setTicket(head);
      setNotes(notePage);
      setActions(actionPage);
      setLinks(linkPage);
      setResolutions(resolutionPage);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '工单详情读取失败');
      setTicket(null);
    }
  }, []);

  useEffect(() => {
    void loadQueue();
  }, [loadQueue]);

  useEffect(() => {
    if (selectedId) {
      void loadTicket(selectedId);
    } else {
      setTicket(null);
    }
  }, [loadTicket, selectedId]);

  /** 执行一个处理动作：成功后刷新工单与四条线，失败时**显示后端给的原因**。 */
  const run = useCallback(
    async (label: string, work: () => Promise<{ message?: string | null }>) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const outcome = await work();
        setNotice(outcome?.message ? `${label}：${outcome.message}` : `${label}已完成`);
        if (selectedId) {
          await loadTicket(selectedId);
        }
        await loadQueue();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : `${label}失败`);
      } finally {
        setBusy(false);
      }
    },
    [loadQueue, loadTicket, selectedId],
  );

  const available = useMemo(() => new Set(ticket?.availableActions ?? []), [ticket]);
  const canDo = useCallback((action: string) => writable && available.has(action), [available, writable]);

  const summary = queue?.summary;
  const sla = ticket?.sla;

  return (
    <div className="space-y-4">
      {/* ------------------------------------------------ 能力边界 */}
      <SectionCard
        title="能力边界"
        description="逐维说明「有没有、依据是什么、缺了会怎样」。「把处理结果推给上游售后单 / 订单」这一维刻意不可用 —— 上游没有改写端点，界面不假装能推。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void loadQueue()} disabled={busy}>
            <RefreshCw className="mr-1 h-3.5 w-3.5" />
            刷新
          </Button>
        }
      >
        {error ? (
          <div className="text-copy-13 text-destructive flex items-center gap-2">
            <TriangleAlert className="h-4 w-4" />
            {error}
          </div>
        ) : null}
        {capabilities ? (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-2">
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">已交付</div>
                {capabilities.dimensions
                  .filter((dimension) => dimension.available)
                  .map((dimension) => (
                    <div key={dimension.code} className="rounded-md border p-3">
                      <div className="flex items-center gap-2">
                        <Badge tone="success">可用</Badge>
                        <span className="text-copy-13 font-medium">{dimension.label}</span>
                      </div>
                      <p className="text-copy-12 text-muted-foreground mt-1">依据：{dimension.basis}</p>
                      <p className="text-copy-12 text-muted-foreground mt-1">{dimension.impact}</p>
                    </div>
                  ))}
              </div>
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">未交付 / 不在本模块</div>
                {capabilities.dimensions
                  .filter((dimension) => !dimension.available)
                  .map((dimension) => (
                    <div key={dimension.code} className="rounded-md border p-3">
                      <div className="flex items-center gap-2">
                        <Badge tone="neutral">不可用</Badge>
                        <span className="text-copy-13 font-medium">{dimension.label}</span>
                      </div>
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        归属：{dimension.ownerModule || '—'}
                      </p>
                      <p className="text-copy-12 text-muted-foreground mt-1">{dimension.impact}</p>
                    </div>
                  ))}
              </div>
            </div>
            <div className="space-y-2">
              <div className="text-copy-13 font-medium">上下游</div>
              <div className="flex flex-wrap gap-2">
                {capabilities.related.map((module) => (
                  <Badge key={module.moduleId} tone={module.delivered ? 'success' : 'neutral'}>
                    {module.moduleId} {module.label}
                    {module.delivered ? '' : `（未交付 · 归属 ${module.ownerModule || '—'}）`}
                  </Badge>
                ))}
              </div>
            </div>
            <div className="space-y-2">
              <div className="text-copy-13 font-medium">本模块刻意不做的事</div>
              {capabilities.boundaries.map((boundary) => (
                <div key={boundary.rule} className="rounded-md border p-3">
                  <div className="text-copy-13 font-medium">{boundary.rule}</div>
                  <p className="text-copy-12 text-muted-foreground mt-1">{boundary.reason}</p>
                  {boundary.ownerModule ? (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      归属：{boundary.ownerModule}
                    </p>
                  ) : null}
                </div>
              ))}
            </div>
            <div className="bg-warning-soft text-warning rounded-md p-3 text-copy-12">
              {capabilities.closeVersusResolutionNote}
            </div>
            <p className="text-copy-12 text-muted-foreground">{capabilities.slaBasisNote}</p>
            <p className="text-copy-12 text-muted-foreground">{capabilities.sourceNote}</p>
            <p className="text-copy-12 text-muted-foreground">{capabilities.downstreamNote}</p>
          </div>
        ) : null}
      </SectionCard>

      {/* ------------------------------------------------ 处理队列 */}
      <SectionCard
        title="处理队列"
        description="哪些工单还在等我处理。SLA 剩余是**营业时间口径**（暂停区间已扣除）；「已确认解决」是独立字段，与「已关闭」互不代表。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <Field label="状态">
              <input
                className={control}
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPageIndex(0);
                }}
              />
            </Field>
            <Field label="类型">
              <input
                className={control}
                value={ticketType}
                onChange={(e) => {
                  setTicketType(e.target.value);
                  setPageIndex(0);
                }}
              />
            </Field>
            <Field label="优先级">
              <input
                className={control}
                value={priority}
                onChange={(e) => {
                  setPriority(e.target.value);
                  setPageIndex(0);
                }}
              />
            </Field>
            <Field label="分配客服">
              <input
                className={control}
                value={assignedToRef}
                onChange={(e) => {
                  setAssignedToRef(e.target.value);
                  setPageIndex(0);
                }}
              />
            </Field>
            <Field label="SLA 状态">
              <input
                className={control}
                value={slaState}
                placeholder="PENDING / PAUSED / BREACHED …"
                onChange={(e) => {
                  setSlaState(e.target.value);
                  setPageIndex(0);
                }}
              />
            </Field>
            <Field label="解决确认">
              <select
                className={control}
                value={resolutionState}
                onChange={(e) => {
                  setResolutionState(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">不限</option>
                <option value="PENDING">尚未确认解决</option>
                <option value="CONFIRMED">已确认解决</option>
              </select>
            </Field>
            <Field label="关键字">
              <input
                className={control}
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageIndex(0);
                }}
              />
            </Field>
            <div className="flex items-end gap-4 pb-1.5">
              <label className="text-copy-13 flex items-center gap-1.5">
                <input
                  type="checkbox"
                  checked={onlyPaused}
                  onChange={(e) => {
                    setOnlyPaused(e.target.checked);
                    setPageIndex(0);
                  }}
                />
                只看暂停中
              </label>
              <label className="text-copy-13 flex items-center gap-1.5">
                <input
                  type="checkbox"
                  checked={onlyOverdue}
                  onChange={(e) => {
                    setOnlyOverdue(e.target.checked);
                    setPageIndex(0);
                  }}
                />
                只看已超期
              </label>
            </div>
          </div>

          {summary ? (
            <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-7">
              <StatCard label="本页工单" value={summary.total} polarity="neutral" />
              <StatCard label="待处理" value={summary.open} polarity="neutral" />
              <StatCard label="处理中" value={summary.inProgress} polarity="neutral" />
              <StatCard label="待买家" value={summary.waitingBuyer} polarity="neutral" />
              <StatCard label="已关闭" value={summary.closed} polarity="neutral" />
              <StatCard label="暂停中" value={summary.paused} polarity="neutral" />
              <StatCard label="已确认解决" value={summary.resolutionConfirmed} polarity="positive" />
            </div>
          ) : null}

          {loading ? (
            <p className="text-copy-13 text-muted-foreground">正在读取处理队列…</p>
          ) : queue && queue.rows.length === 0 ? (
            <EmptyState
              icon={<Ticket className="h-6 w-6" />}
              title={
                status || ticketType || priority || keyword || slaState || resolutionState
                  ? '没有符合条件的工单'
                  : '现在没有待处理的工单'
              }
              description={
                status || ticketType || priority || keyword || slaState || resolutionState
                  ? '换个筛选条件再看看。'
                  : '所有工单都处理完了。'
              }
            />
          ) : queue ? (
            <div className="space-y-2">
              {queue.rows.map((row) => (
                <button
                  key={row.id}
                  type="button"
                  onClick={() => setSelectedId(row.id)}
                  className={`w-full rounded-md border p-3 text-left transition ${
                    selectedId === row.id ? 'border-ring bg-muted' : 'hover:bg-muted'
                  }`}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="neutral">{row.ticketNo}</Badge>
                    <Badge tone="info">{serviceTicketProcessTypeLabel(row.ticketType, row.ticketTypeLabel)}</Badge>
                    <Badge tone={serviceTicketProcessPriorityTone(row.priority)}>
                      {serviceTicketProcessPriorityLabel(row.priority, row.priorityLabel)}
                    </Badge>
                    <Badge tone={row.closed ? 'neutral' : 'warning'}>
                      {serviceTicketProcessStatusLabel(row.ticketStatus, row.ticketStatusLabel)}
                    </Badge>
                    {row.pause?.paused ? <Badge tone="info">SLA 暂停中</Badge> : null}
                    {row.resolutionConfirmed ? <Badge tone="success">已确认解决</Badge> : null}
                    {row.closed && !row.resolutionConfirmed ? (
                      <Badge tone="neutral">已关闭 · 未确认解决</Badge>
                    ) : null}
                  </div>
                  <div className="text-copy-13 mt-1">{row.subject ?? '（无主题）'}</div>
                  <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap gap-3">
                    <span>分配客服：{row.assignedToRef ?? '未分配'}</span>
                    <span>关联售后单：{row.caseNo ?? '—'}</span>
                    <span>
                      回复时限：
                      {serviceTicketProcessSlaStateLabel(row.sla?.replyState ?? 'NO_DEADLINE')} ·{' '}
                      {serviceTicketProcessRemainingText(row.sla?.replyRemainingSeconds ?? 0)}
                    </span>
                  </div>
                </button>
              ))}
            </div>
          ) : null}

          {queue ? (
            <div className="text-copy-12 text-muted-foreground flex flex-wrap items-center gap-3">
              <span>{queue.paginationNote}</span>
              <span>{queue.boundaryNote}</span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(pageIndex + 1) * 20 >= queue.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 处理工作台 */}
      {selectedId && ticket ? (
        <>
          <SectionCard
            title={`处理工作台 · ${ticket.ticketNo}`}
            description={ticket.subject ?? '（无主题）'}
            extra={
              <Button variant="outline" size="sm" onClick={() => void loadTicket(ticket.id)}>
                <RefreshCw className="mr-1 h-3.5 w-3.5" />
                刷新这一张
              </Button>
            }
          >
            <div className="space-y-4">
              {notice ? (
                <div className="bg-success-soft text-success rounded-md p-3 text-copy-12">
                  {notice}
                </div>
              ) : null}
              {actionError ? (
                <div className="bg-destructive-soft text-destructive flex items-center gap-2 rounded-md p-3 text-copy-12">
                  <TriangleAlert className="h-4 w-4" />
                  {actionError}
                </div>
              ) : null}

              <div className="grid gap-3 md:grid-cols-4">
                <StatCard label="状态" value={ticket.ticketStatusLabel} polarity="neutral" />
                <StatCard label="优先级" value={ticket.priorityLabel} polarity="neutral" />
                <StatCard label="已关闭" value={ticket.closed ? '是' : '否'} polarity="neutral" />
                <StatCard
                  label="已确认解决"
                  value={ticket.resolutionConfirmed ? '是' : '否'}
                  polarity={ticket.resolutionConfirmed ? 'positive' : 'negative'}
                />
              </div>

              <div className="bg-warning-soft text-warning flex items-start gap-2 rounded-md p-3 text-copy-12">
                <CircleSlash className="mt-0.5 h-4 w-4 shrink-0" />
                <div>
                  <div>{ticket.closeVersusResolutionNote}</div>
                  {ticket.confirmedResolutionTypes.length > 0 ? (
                    <div className="mt-1">
                      已确认的结果：
                      {ticket.confirmedResolutionTypes
                        .map((code) => serviceTicketProcessResolutionLabel(code))
                        .join('、')}
                    </div>
                  ) : null}
                </div>
              </div>

              <div className="grid gap-3 md:grid-cols-2">
                <div className="space-y-2 rounded-md border p-3">
                  <div className="flex items-center gap-2">
                    <AlarmClock className="h-4 w-4" />
                    <span className="text-copy-13 font-medium">回复时限（营业时间口径）</span>
                    <Badge tone={serviceTicketProcessSlaTone(sla?.replyState ?? 'NO_DEADLINE')}>
                      {serviceTicketProcessSlaStateLabel(sla?.replyState ?? 'NO_DEADLINE')}
                    </Badge>
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    剩余：{serviceTicketProcessRemainingText(sla?.replyRemainingSeconds ?? 0)}
                    {sla?.replyPaused ? '（计时器冻着）' : ''}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    到期时刻：{sla?.replyDueAt ?? '暂停中，恢复之后才能定下来'}
                  </div>
                </div>
                <div className="space-y-2 rounded-md border p-3">
                  <div className="flex items-center gap-2">
                    <AlarmClock className="h-4 w-4" />
                    <span className="text-copy-13 font-medium">处理时限（营业时间口径）</span>
                    <Badge tone={serviceTicketProcessSlaTone(sla?.resolveState ?? 'NO_DEADLINE')}>
                      {serviceTicketProcessSlaStateLabel(sla?.resolveState ?? 'NO_DEADLINE')}
                    </Badge>
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    剩余：{serviceTicketProcessRemainingText(sla?.resolveRemainingSeconds ?? 0)}
                    {sla?.resolvePaused ? '（计时器冻着）' : ''}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    到期时刻：{sla?.resolveDueAt ?? '暂停中，恢复之后才能定下来'}
                  </div>
                </div>
              </div>
              {sla?.note ? (
                <p className="text-copy-12 text-muted-foreground">{sla.note}</p>
              ) : null}
              {ticket.pause?.paused ? (
                <div className="bg-info-soft text-info flex items-center gap-2 rounded-md p-3 text-copy-12">
                  <PauseCircle className="h-4 w-4" />
                  <span>
                    SLA 暂停中（{serviceTicketProcessSlaTargetLabel(ticket.pause?.slaTarget ?? '')}）：
                    {ticket.pause?.pauseReason} · 自 {ticket.pause?.pausedAt}
                  </span>
                </div>
              ) : null}

              <div className="text-copy-12 text-muted-foreground">
                分配客服：{ticket.assignedToRef ?? '未分配'} · 关联售后单：{ticket.caseNo ?? '—'} ·
                创建于 {ticket.createdAt}
                {ticket.closedAt ? ` · 关闭于 ${ticket.closedAt}` : ''}
                {ticket.closeReason
                  ? ` · 关闭原因：${serviceTicketProcessCloseReasonLabel(ticket.closeReason)}`
                  : ''}
              </div>
            </div>
          </SectionCard>

          {/* -------------------------------------------- 处理动作 */}
          <SectionCard
            title="处理动作"
            description="每一步都会留下处理记录。不可用的动作会灰掉并说明原因 —— 页面不猜当前能不能做。"
          >
            <div className="space-y-5">
              {/* 回复 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">回复（对买家）</div>
                <div className="grid gap-3 md:grid-cols-3">
                  <Field label="回复内容">
                    <input
                      className={control}
                      value={replyContent}
                      disabled={!canDo('REPLY')}
                      onChange={(e) => setReplyContent(e.target.value)}
                    />
                  </Field>
                  <Field label="回复渠道">
                    <select
                      className={control}
                      value={replyChannel}
                      disabled={!canDo('REPLY')}
                      onChange={(e) => setReplyChannel(e.target.value)}
                    >
                      {(capabilities?.replyChannels ?? []).map((channel) => (
                        <option key={channel} value={channel}>
                          {serviceTicketProcessChannelLabel(channel)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <div className="flex items-end pb-1.5">
                    <Button
                      size="sm"
                      disabled={busy || !canDo('REPLY') || replyContent.trim() === ''}
                      onClick={() =>
                        void run('回复', async () => {
                          const outcome = await sendServiceTicketProcessReply(ticket.id, {
                            content: replyContent,
                            replyChannel,
                          });
                          setReplyContent('');
                          return outcome;
                        })
                      }
                    >
                      <MessageSquare className="mr-1 h-3.5 w-3.5" />
                      发送回复
                    </Button>
                  </div>
                </div>
              </div>

              {/* 内部备注 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">内部备注（只追加）</div>
                <div className="grid gap-3 md:grid-cols-3">
                  <Field label="备注内容">
                    <input
                      className={control}
                      value={noteContent}
                      disabled={!canDo('NOTE')}
                      onChange={(e) => setNoteContent(e.target.value)}
                    />
                  </Field>
                  <div className="flex items-end pb-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || !canDo('NOTE') || noteContent.trim() === ''}
                      onClick={() =>
                        void run('备注', async () => {
                          const outcome = await addServiceTicketProcessNote(ticket.id, {
                            content: noteContent,
                          });
                          setNoteContent('');
                          return outcome;
                        })
                      }
                    >
                      写备注
                    </Button>
                  </div>
                </div>
              </div>

              {/* 状态流转 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">状态流转</div>
                <div className="grid gap-3 md:grid-cols-3">
                  <Field label="目标状态">
                    <select
                      className={control}
                      value={targetStatus}
                      disabled={!canDo('STATUS_CHANGE')}
                      onChange={(e) => setTargetStatus(e.target.value)}
                    >
                      {(capabilities?.targetStatuses ?? []).map((code) => (
                        <option key={code} value={code}>
                          {serviceTicketProcessStatusLabel(code)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="说明">
                    <input
                      className={control}
                      value={statusNote}
                      disabled={!canDo('STATUS_CHANGE')}
                      onChange={(e) => setStatusNote(e.target.value)}
                    />
                  </Field>
                  <div className="flex items-end pb-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || !canDo('STATUS_CHANGE')}
                      onClick={() =>
                        void run('状态流转', () =>
                          changeServiceTicketProcessStatus(ticket.id, {
                            targetStatus,
                            note: statusNote || null,
                          }),
                        )
                      }
                    >
                      流转状态
                    </Button>
                  </div>
                </div>
              </div>

              {/* 关闭 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">关闭工单（≠ 解决）</div>
                <div className="grid gap-3 md:grid-cols-3">
                  <Field label="关闭原因">
                    <select
                      className={control}
                      value={closeReason}
                      disabled={!canDo('CLOSE')}
                      onChange={(e) => setCloseReason(e.target.value)}
                    >
                      {(capabilities?.closeReasons ?? []).map((code) => (
                        <option key={code} value={code}>
                          {serviceTicketProcessCloseReasonLabel(code)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="关闭说明">
                    <input
                      className={control}
                      value={closeNote}
                      disabled={!canDo('CLOSE')}
                      onChange={(e) => setCloseNote(e.target.value)}
                    />
                  </Field>
                  <div className="flex items-end pb-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || !canDo('CLOSE')}
                      onClick={() =>
                        void run('关闭工单', async () => {
                          const outcome = await closeServiceTicketProcessTicket(ticket.id, {
                            closeReason,
                            closeNote: closeNote || null,
                          });
                          setCloseNote('');
                          return outcome;
                        })
                      }
                    >
                      关闭工单
                    </Button>
                  </div>
                </div>
              </div>

              {/* 解决确认 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">解决确认（实际结果）</div>
                <div className="grid gap-3 md:grid-cols-3">
                  <Field label="结果类型">
                    <select
                      className={control}
                      value={resolutionType}
                      disabled={!canDo('RESOLUTION_CONFIRM')}
                      onChange={(e) => setResolutionType(e.target.value)}
                    >
                      {(capabilities?.resolutionTypes ?? []).map((code) => (
                        <option key={code} value={code}>
                          {serviceTicketProcessResolutionLabel(code)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="结果说明">
                    <input
                      className={control}
                      value={resolutionNote}
                      disabled={!canDo('RESOLUTION_CONFIRM')}
                      onChange={(e) => setResolutionNote(e.target.value)}
                    />
                  </Field>
                  <div className="flex items-end pb-1.5">
                    <Button
                      size="sm"
                      disabled={busy || !canDo('RESOLUTION_CONFIRM')}
                      onClick={() =>
                        void run('解决确认', async () => {
                          const outcome = await confirmServiceTicketProcessResolution(ticket.id, {
                            resolutionType,
                            resultNote: resolutionNote || null,
                          });
                          setResolutionNote('');
                          return outcome;
                        })
                      }
                    >
                      <CheckCircle2 className="mr-1 h-3.5 w-3.5" />
                      确认解决
                    </Button>
                  </div>
                </div>
                {!canDo('RESOLUTION_CONFIRM') ? (
                  <p className="text-copy-12 text-muted-foreground">
                    这一步现在不可用：解决确认**要求工单已关闭**（先关后确认，顺序本身就是纪律的落点）。
                  </p>
                ) : null}
              </div>

              {/* 满意度 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">满意度回收（1~5 分）</div>
                <div className="grid gap-3 md:grid-cols-3">
                  <Field label="评分">
                    <input
                      className={control}
                      type="number"
                      min={1}
                      max={5}
                      value={satisfactionScore}
                      disabled={!canDo('SATISFACTION')}
                      onChange={(e) => setSatisfactionScore(e.target.value)}
                    />
                  </Field>
                  <Field label="评价内容">
                    <input
                      className={control}
                      value={satisfactionComment}
                      disabled={!canDo('SATISFACTION')}
                      onChange={(e) => setSatisfactionComment(e.target.value)}
                    />
                  </Field>
                  <div className="flex items-end pb-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || !canDo('SATISFACTION')}
                      onClick={() =>
                        void run('满意度回收', () =>
                          sendServiceTicketProcessSatisfaction(ticket.id, {
                            score: Number(satisfactionScore),
                            comment: satisfactionComment || null,
                          }),
                        )
                      }
                    >
                      回收满意度
                    </Button>
                  </div>
                </div>
              </div>

              {/* SLA 暂停 / 恢复 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">SLA 暂停 / 恢复（沿营业时间轴）</div>
                <div className="grid gap-3 md:grid-cols-4">
                  <Field label="暂停目标">
                    <select
                      className={control}
                      value={slaTarget}
                      disabled={!canDo('SLA_PAUSE')}
                      onChange={(e) => setSlaTarget(e.target.value)}
                    >
                      {(capabilities?.slaTargets ?? []).map((code) => (
                        <option key={code} value={code}>
                          {serviceTicketProcessSlaTargetLabel(code)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="暂停理由">
                    <input
                      className={control}
                      value={pauseReason}
                      disabled={!canDo('SLA_PAUSE')}
                      onChange={(e) => setPauseReason(e.target.value)}
                    />
                  </Field>
                  <div className="flex items-end gap-2 pb-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || !canDo('SLA_PAUSE') || pauseReason.trim() === ''}
                      onClick={() =>
                        void run('暂停 SLA', async () => {
                          const outcome = await pauseServiceTicketProcessSla(ticket.id, {
                            slaTarget,
                            pauseReason,
                          });
                          setPauseReason('');
                          return outcome;
                        })
                      }
                    >
                      <PauseCircle className="mr-1 h-3.5 w-3.5" />
                      暂停
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || !canDo('SLA_RESUME') || resumeReason.trim() === ''}
                      onClick={() =>
                        void run('恢复 SLA', async () => {
                          const outcome = await resumeServiceTicketProcessSla(ticket.id, {
                            resumeReason,
                          });
                          setResumeReason('');
                          return outcome;
                        })
                      }
                    >
                      <PlayCircle className="mr-1 h-3.5 w-3.5" />
                      恢复
                    </Button>
                  </div>
                  <Field label="恢复理由">
                    <input
                      className={control}
                      value={resumeReason}
                      disabled={!canDo('SLA_RESUME')}
                      onChange={(e) => setResumeReason(e.target.value)}
                    />
                  </Field>
                </div>
              </div>

              {/* 关联单据 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">关联订单与售后单</div>
                <div className="grid gap-3 md:grid-cols-4">
                  <Field label="关联类型">
                    <select
                      className={control}
                      value={linkType}
                      disabled={!canDo('LINK_ORDER') && !canDo('LINK_CASE')}
                      onChange={(e) => setLinkType(e.target.value)}
                    >
                      {(capabilities?.linkTypes ?? []).map((code) => (
                        <option key={code} value={code}>
                          {serviceTicketProcessLinkTypeLabel(code)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label={linkType === 'ORDER' ? '订单号' : '售后单号'}>
                    <input
                      className={control}
                      value={linkNo}
                      disabled={!canDo('LINK_ORDER') && !canDo('LINK_CASE')}
                      onChange={(e) => setLinkNo(e.target.value)}
                    />
                  </Field>
                  <Field label="关联说明">
                    <input
                      className={control}
                      value={linkNote}
                      disabled={!canDo('LINK_ORDER') && !canDo('LINK_CASE')}
                      onChange={(e) => setLinkNote(e.target.value)}
                    />
                  </Field>
                  <div className="flex items-end pb-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={
                        busy ||
                        (!canDo('LINK_ORDER') && !canDo('LINK_CASE')) ||
                        linkNo.trim() === ''
                      }
                      onClick={() =>
                        void run('关联单据', async () => {
                          const outcome = await linkServiceTicketProcessDocument(ticket.id, {
                            linkType,
                            orderNo: linkType === 'ORDER' ? linkNo : null,
                            caseNo: linkType === 'AFTER_SALES_CASE' ? linkNo : null,
                            note: linkNote || null,
                          });
                          setLinkNo('');
                          setLinkNote('');
                          return outcome;
                        })
                      }
                    >
                      建立关联
                    </Button>
                  </div>
                </div>
              </div>

              {/* 升级 */}
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">升级（转投诉 / 转纠纷 ⇒ 下游 11 售后）</div>
                <div className="grid gap-3 md:grid-cols-3">
                  <Field label="升级类型">
                    <select
                      className={control}
                      value={escalateType}
                      disabled={!canDo('ESCALATE')}
                      onChange={(e) => setEscalateType(e.target.value)}
                    >
                      {(capabilities?.escalationTypes ?? []).map((code) => (
                        <option key={code} value={code}>
                          {code === 'COMPLAINT' ? '转投诉（新建售后单）' : '转纠纷（新建平台纠纷）'}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="升级理由">
                    <input
                      className={control}
                      value={escalateReason}
                      disabled={!canDo('ESCALATE')}
                      onChange={(e) => setEscalateReason(e.target.value)}
                    />
                  </Field>
                  <div className="flex items-end pb-1.5">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || !canDo('ESCALATE') || escalateReason.trim() === ''}
                      onClick={() =>
                        void run('升级', async () => {
                          const outcome = await escalateServiceTicketProcessTicket(ticket.id, {
                            targetType: escalateType,
                            reasonNote: escalateReason,
                          });
                          setEscalateReason('');
                          return outcome;
                        })
                      }
                    >
                      升级
                    </Button>
                  </div>
                </div>
              </div>
            </div>
          </SectionCard>

          {/* -------------------------------------------- 四条线 */}
          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard
              title={`处理记录（${actions?.total ?? 0} 条）`}
              description={actions?.note}
            >
              {actions && actions.rows.length > 0 ? (
                <div className="space-y-2">
                  {actions.rows
                    .filter((row): row is NonNullable<typeof row> => row !== null)
                    .map((row) => (
                    <div key={row.id} className="rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="neutral">{serviceTicketProcessActionLabel(row.actionType, row.actionTypeLabel)}</Badge>
                        {row.fromStatus && row.toStatus ? (
                          <span className="text-copy-12 text-muted-foreground">
                            {serviceTicketProcessStatusLabel(row.fromStatus)} →{' '}
                            {serviceTicketProcessStatusLabel(row.toStatus)}
                          </span>
                        ) : null}
                        <span className="text-copy-12 text-muted-foreground">{row.occurredAt}</span>
                      </div>
                      {row.detail ? (
                        <p className="text-copy-13 mt-1 whitespace-pre-wrap">{row.detail}</p>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-copy-13 text-muted-foreground">
                  还没有处理记录 —— 处理动作做一次就留一行。
                </p>
              )}
            </SectionCard>

            <SectionCard
              title={`备注与回复（${notes?.total ?? 0} 条）`}
              description={notes?.note}
            >
              {notes && notes.rows.length > 0 ? (
                <div className="space-y-2">
                  {notes.rows
                    .filter((row): row is NonNullable<typeof row> => row !== null)
                    .map((row) => (
                    <div key={row.id} className="rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={row.noteType === 'REPLY' ? 'info' : 'neutral'}>
                          {row.noteTypeLabel}
                        </Badge>
                        {row.replyChannelLabel ? (
                          <Badge tone="neutral">{row.replyChannelLabel}</Badge>
                        ) : null}
                        <span className="text-copy-12 text-muted-foreground">{row.createdAt}</span>
                      </div>
                      <p className="text-copy-13 mt-1 whitespace-pre-wrap">{row.content}</p>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-copy-13 text-muted-foreground">
                  还没有备注或回复 —— 写下来的话会一直留在这一张上。
                </p>
              )}
            </SectionCard>

            <SectionCard
              title={`关联单据（${links?.total ?? 0} 条）`}
              description={links?.note}
            >
              {links && links.rows.length > 0 ? (
                <div className="space-y-2">
                  {links.rows
                    .filter((row): row is NonNullable<typeof row> => row !== null)
                    .map((row) => (
                    <div key={row.id} className="rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="neutral">{row.linkTypeLabel}</Badge>
                        <span className="text-copy-13">{row.orderNo ?? row.caseNo ?? '—'}</span>
                      </div>
                      {row.caseStatus ? (
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          售后单状态：{row.caseStatus}
                          {row.caseType ? `（${row.caseType}）` : ''}
                        </p>
                      ) : null}
                      {row.orderStatus ? (
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          订单状态：{row.orderStatus}
                          {row.orderPaymentStatus ? ` · 支付 ${row.orderPaymentStatus}` : ''}
                          {row.orderFulfillmentStatus ? ` · 履约 ${row.orderFulfillmentStatus}` : ''}
                          {row.orderAftersalesStatus ? ` · 售后 ${row.orderAftersalesStatus}` : ''}
                        </p>
                      ) : null}
                      {row.orderAmount ? (
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          金额：{row.orderAmount} {row.orderCurrency ?? ''}
                        </p>
                      ) : null}
                      {row.factsNote ? (
                        <p className="text-copy-12 text-warning mt-1">⚠️ {row.factsNote}</p>
                      ) : null}
                      {row.lastSyncNote ? (
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          最近更新：{row.lastSyncNote}
                        </p>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-copy-13 text-muted-foreground">
                  还没有关联单据 —— 关联时服务端会去上游核实，查不到的号会被拒。
                </p>
              )}
            </SectionCard>

            <SectionCard
              title={`解决确认（${resolutions?.total ?? 0} 条）`}
              description={resolutions?.note}
            >
              {resolutions && resolutions.rows.length > 0 ? (
                <div className="space-y-2">
                  {resolutions.rows
                    .filter((row): row is NonNullable<typeof row> => row !== null)
                    .map((row) => (
                    <div key={row.id} className="rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={row.active ? 'success' : 'neutral'}>
                          {serviceTicketProcessResolutionLabel(row.resolutionType, row.resolutionTypeLabel)}
                        </Badge>
                        {row.active ? null : <Badge tone="neutral">已撤销</Badge>}
                        <span className="text-copy-12 text-muted-foreground">{row.confirmedAt}</span>
                      </div>
                      {row.resultNote ? (
                        <p className="text-copy-13 mt-1 whitespace-pre-wrap">{row.resultNote}</p>
                      ) : null}
                      {row.active && writable ? (
                        <div className="mt-2">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() =>
                              void run('撤销解决确认', () =>
                                revokeServiceTicketProcessResolution(ticket.id, row.id),
                              )
                            }
                          >
                            撤销这条确认
                          </Button>
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-copy-13 text-muted-foreground">
                  还没有解决确认 —— 工单关闭只表示「不再跟了」，实际结果到了要在这里另记一条。
                </p>
              )}
            </SectionCard>
          </div>
        </>
      ) : selectedId ? (
        <SectionCard title="处理工作台">
          <p className="text-copy-13 text-muted-foreground">正在读取这张工单…</p>
        </SectionCard>
      ) : (
        <SectionCard title="处理工作台">
          <EmptyState
            icon={<Ticket className="h-6 w-6" />}
            title="先从上面的处理队列里选一张工单"
            description="选中之后这里会显示它的 SLA、可用动作、处理记录、备注与回复、关联单据和解决确认。"
          />
        </SectionCard>
      )}
    </div>
  );
}
