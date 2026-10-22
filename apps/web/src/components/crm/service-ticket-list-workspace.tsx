'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { AlarmClock, CalendarClock, RefreshCw, Ticket, TriangleAlert } from 'lucide-react';
import type {
  CrmBusinessHoursConfig,
  CrmServiceTicketCapabilities,
  CrmServiceTicketPage,
  CrmServiceTicketRow,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  businessHourWeekdayLabel,
  fetchBusinessHours,
  fetchServiceTicketCapabilities,
  fetchServiceTickets,
  serviceTicketPriorityLabel,
  serviceTicketPriorityTone,
  serviceTicketRemainingText,
  serviceTicketSlaStateLabel,
  serviceTicketSlaTone,
  serviceTicketStatusLabel,
  serviceTicketTypeLabel,
  updateBusinessHours,
  updateBusinessHolidays,
} from './service-ticket-list-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.4.1.1 工单列表工作区。
 *
 * <p>页面按「先看清口径、再看工单」的顺序排，因为蓝图加粗句是
 * 「<b>SLA 计时需按营业时间而非自然时间</b>，否则夜间积压会造成误判」。
 * 界面上第一件要让人看到的就是**这条口径**与**它当前配置成什么样**，
 * 而不是一张写着「还剩 3 小时」的表 —— 那 3 小时是自然时间还是营业时间，
 * 看不出来就等于没写。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」；
 *       15.4.1.2 工单处理未交付 ⇒ 该维 {@code available=false}，
 *       页面**不提供**任何处理按钮。</li>
 *   <li><b>营业时间口径</b> —— 可读可改；每次改写后端都留审计。
 *       没配过时页面明确说「SLA 算不出来」，<b>不</b>退回自然时间。</li>
 *   <li><b>工单列表</b> —— 真分页真筛选；每行显示类型 / 优先级 / 状态 /
 *       <b>营业时间口径的 SLA 剩余</b> / 关联买家与订单 / 分配客服 / 转派与升级。</li>
 *   <li><b>查不到就说查不到</b> —— 关联订单与买家读不到时显示**后端给的原因**，
 *       不显示空白。</li>
 * </ul>
 */
export function ServiceTicketListWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.service_ticket.update');

  const [capabilities, setCapabilities] = useState<CrmServiceTicketCapabilities | null>(null);
  const [page, setPage] = useState<CrmServiceTicketPage | null>(null);
  const [businessHours, setBusinessHours] = useState<CrmBusinessHoursConfig | null>(null);
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
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [sort, setSort] = useState('created_desc');
  const [onlyOverdue, setOnlyOverdue] = useState(false);
  const [onlyUnassigned, setOnlyUnassigned] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);

  // 营业时间编辑
  const [timezone, setTimezone] = useState('Asia/Shanghai');
  const [windowRows, setWindowRows] = useState<
    { weekday: number; start: string; end: string; on: boolean }[]
  >([]);
  const [holidayRows, setHolidayRows] = useState<
    { date: string; label: string; closed: boolean }[]
  >([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, result, hours] = await Promise.all([
        fetchServiceTicketCapabilities(),
        fetchServiceTickets({
          status: status || undefined,
          ticketType: ticketType || undefined,
          priority: priority || undefined,
          assignedToRef: assignedToRef || undefined,
          slaState: slaState || undefined,
          overdueOnly: onlyOverdue ? true : undefined,
          unassignedOnly: onlyUnassigned ? true : undefined,
          keyword: keyword || undefined,
          from: from || undefined,
          to: to || undefined,
          sort,
          page: pageIndex,
          size: 20,
        }),
        fetchBusinessHours(),
      ]);
      setCapabilities(caps);
      setPage(result);
      setBusinessHours(hours);
      if (hours.timezone) setTimezone(hours.timezone);
      setWindowRows(
        hours.windows.map((w) => ({
          weekday: w.weekday,
          start: w.startTime,
          end: w.endTime,
          on: w.enabled,
        })),
      );
      setHolidayRows(
        hours.holidays.map((h) => ({ date: h.date, label: h.label, closed: h.closed })),
      );
    } catch (e) {
      setError(errorText(e, '工单列表读取失败'));
    } finally {
      setLoading(false);
    }
  }, [
    status,
    ticketType,
    priority,
    assignedToRef,
    slaState,
    onlyOverdue,
    onlyUnassigned,
    keyword,
    from,
    to,
    sort,
    pageIndex,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const saveWindows = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const saved = await updateBusinessHours({
        shopId: null,
        timezone: timezone.trim(),
        windows: windowRows
          .filter((row) => row.on && row.start && row.end)
          .map((row) => ({
            weekday: row.weekday,
            startTime: row.start,
            endTime: row.end,
            enabled: true,
          })),
        note: '',
      });
      setBusinessHours(saved);
      setNotice(
        `营业时间已保存（共 ${saved.windows.length} 段，时区 ${saved.timezone ?? timezone}）：` +
          'SLA 剩余从下一次读取起按这条口径计算。',
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '营业时间保存失败'));
    } finally {
      setBusy(false);
    }
  }, [timezone, windowRows, load]);

  const saveHolidays = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const saved = await updateBusinessHolidays({
        shopId: null,
        holidays: holidayRows
          .filter((row) => row.date)
          .map((row) => ({ date: row.date, label: row.label, closed: row.closed })),
        note: '',
      });
      setBusinessHours(saved);
      setNotice(`节假日例外已保存（共 ${saved.holidays.length} 条）：例外日期整日不计时。`);
      await load();
    } catch (e) {
      setActionError(errorText(e, '节假日例外保存失败'));
    } finally {
      setBusy(false);
    }
  }, [holidayRows, load]);

  const availableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => d.available) ?? [],
    [capabilities],
  );
  const unavailableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => !d.available) ?? [],
    [capabilities],
  );
  const hasFilter = Boolean(
    status ||
    ticketType ||
    priority ||
    assignedToRef ||
    slaState ||
    keyword ||
    from ||
    to ||
    onlyOverdue ||
    onlyUnassigned,
  );
  const summary = page?.summary;
  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(page.size, 1))) : 1;

  return (
    <div className="space-y-4">
      {/* ------------------------------------------------ 能力边界 */}
      <SectionCard
        title="能力与边界"
        description="本页是只读列表：工单的创建、派单、转派与关闭仍然只在工单管理服务；工单处理尚未交付，所以这里没有任何处理按钮。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-3.5 w-3.5" />
            刷新
          </Button>
        }
      >
        {loading && !capabilities ? (
          <p className="text-copy-13 text-muted-foreground">正在读取能力声明…</p>
        ) : error && !capabilities ? (
          <p className="text-copy-13 text-destructive">{error}</p>
        ) : (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-2">
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">已具备</div>
                {availableDimensions.map((dimension) => (
                  <div key={dimension.dimension} className="rounded-md border p-3">
                    <div className="flex items-center gap-2">
                      <Badge tone="success">可用</Badge>
                      <span className="text-copy-13 font-medium">{dimension.description}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      依据：{dimension.source}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">{dimension.impact}</p>
                  </div>
                ))}
              </div>
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">未交付 / 不在本模块</div>
                {unavailableDimensions.map((dimension) => (
                  <div key={dimension.dimension} className="rounded-md border p-3">
                    <div className="flex items-center gap-2">
                      <Badge tone="neutral">不可用</Badge>
                      <span className="text-copy-13 font-medium">{dimension.description}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      归属：{dimension.ownerModule ?? '—'}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">{dimension.impact}</p>
                  </div>
                ))}
              </div>
            </div>
            {capabilities && (
              <div className="space-y-2">
                <div className="text-copy-13 font-medium">上下游</div>
                <div className="flex flex-wrap gap-2">
                  {capabilities.related.map((module) => (
                    <Badge key={module.id} tone={module.delivered ? 'success' : 'neutral'}>
                      {module.id} {module.label}
                      {module.delivered ? '' : `（未交付 · 归属 ${module.ownerModule ?? '—'}）`}
                    </Badge>
                  ))}
                </div>
                <p className="text-copy-12 text-muted-foreground">{capabilities.upstreamNote}</p>
                <p className="text-copy-12 text-muted-foreground">{capabilities.downstreamNote}</p>
              </div>
            )}
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 营业时间口径 */}
      <SectionCard
        title="营业时间口径（SLA 计时轴）"
        description="SLA 到期时刻在这条轴上累加，不是「创建时间 + N 小时」。没配过时 SLA 算不出来（状态显示「未配营业时间」），不会退回自然时间。"
      >
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <Badge tone={businessHours?.configured ? 'success' : 'warning'}>
              {businessHours?.configured ? '已配置' : '未配置'}
            </Badge>
            {businessHours?.timezone && (
              <span className="text-copy-13 text-muted-foreground">
                时区 {businessHours.timezone}
              </span>
            )}
            {businessHours?.note && (
              <span className="text-copy-12 text-muted-foreground">{businessHours.note}</span>
            )}
          </div>

          <div className="grid gap-3 md:grid-cols-2">
            <Field label="时区（IANA 名，如 Asia/Shanghai）">
              <input
                className={control}
                value={timezone}
                disabled={!writable}
                onChange={(e) => setTimezone(e.target.value)}
              />
            </Field>
          </div>

          <div className="space-y-2">
            <div className="text-copy-13 font-medium">每周营业时段</div>
            {windowRows.length === 0 ? (
              <p className="text-copy-12 text-muted-foreground">
                还没有任何营业时段 ⇒ SLA 算不出来（不是「没有时限」）。
              </p>
            ) : null}
            {windowRows.map((row, index) => (
              <div key={row.weekday} className="flex flex-wrap items-end gap-3">
                <span className="text-copy-13 w-16">{businessHourWeekdayLabel(row.weekday)}</span>
                <input
                  type="time"
                  aria-label={`${businessHourWeekdayLabel(row.weekday)} 上班时间`}
                  className={control}
                  value={row.start}
                  disabled={!writable}
                  onChange={(e) => {
                    const next = [...windowRows];
                    next[index] = { ...row, start: e.target.value };
                    setWindowRows(next);
                  }}
                />
                <span className="text-copy-13 text-muted-foreground">至</span>
                <input
                  type="time"
                  aria-label={`${businessHourWeekdayLabel(row.weekday)} 下班时间`}
                  className={control}
                  value={row.end}
                  disabled={!writable}
                  onChange={(e) => {
                    const next = [...windowRows];
                    next[index] = { ...row, end: e.target.value };
                    setWindowRows(next);
                  }}
                />
                <label className="text-copy-13 flex items-center gap-1.5">
                  <input
                    type="checkbox"
                    checked={row.on}
                    disabled={!writable}
                    onChange={(e) => {
                      const next = [...windowRows];
                      next[index] = { ...row, on: e.target.checked };
                      setWindowRows(next);
                    }}
                  />
                  营业
                </label>
              </div>
            ))}
            {writable && (
              <div className="flex flex-wrap gap-2">
                {[1, 2, 3, 4, 5, 6, 7]
                  .filter((weekday) => !windowRows.some((row) => row.weekday === weekday))
                  .map((weekday) => (
                    <Button
                      key={weekday}
                      variant="outline"
                      size="sm"
                      onClick={() =>
                        setWindowRows([
                          ...windowRows,
                          { weekday, start: '09:00', end: '18:00', on: true },
                        ])
                      }
                    >
                      添加{businessHourWeekdayLabel(weekday)}
                    </Button>
                  ))}
                <Button size="sm" onClick={() => void saveWindows()} disabled={busy}>
                  <CalendarClock className="mr-1 h-3.5 w-3.5" />
                  保存营业时段
                </Button>
              </div>
            )}
          </div>

          <div className="space-y-2">
            <div className="text-copy-13 font-medium">节假日例外（整日不计时）</div>
            {holidayRows.map((row, index) => (
              <div key={`${row.date}-${index}`} className="flex flex-wrap items-end gap-3">
                <input
                  type="date"
                  aria-label="例外日期"
                  className={control}
                  value={row.date}
                  disabled={!writable}
                  onChange={(e) => {
                    const next = [...holidayRows];
                    next[index] = { ...row, date: e.target.value };
                    setHolidayRows(next);
                  }}
                />
                <input
                  aria-label="例外说明"
                  className={control}
                  placeholder="说明（如春节）"
                  value={row.label}
                  disabled={!writable}
                  onChange={(e) => {
                    const next = [...holidayRows];
                    next[index] = { ...row, label: e.target.value };
                    setHolidayRows(next);
                  }}
                />
                <label className="text-copy-13 flex items-center gap-1.5">
                  <input
                    type="checkbox"
                    checked={row.closed}
                    disabled={!writable}
                    onChange={(e) => {
                      const next = [...holidayRows];
                      next[index] = { ...row, closed: e.target.checked };
                      setHolidayRows(next);
                    }}
                  />
                  整日关闭
                </label>
              </div>
            ))}
            {writable && (
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    setHolidayRows([...holidayRows, { date: '', label: '', closed: true }])
                  }
                >
                  添加例外
                </Button>
                <Button size="sm" onClick={() => void saveHolidays()} disabled={busy}>
                  保存节假日
                </Button>
              </div>
            )}
          </div>

          {actionError && <p className="text-copy-13 text-destructive">{actionError}</p>}
          {notice && <p className="text-copy-13 text-muted-foreground">{notice}</p>}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 筛选 */}
      <SectionCard title="筛选" description="筛选与分页都在服务端执行（真分页真筛选）。">
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="状态">
            <select
              className={control}
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.ticketStatuses ?? []).map((value) => (
                <option key={value} value={value}>
                  {serviceTicketStatusLabel(value)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="类型">
            <select
              className={control}
              value={ticketType}
              onChange={(e) => {
                setTicketType(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.ticketTypes ?? []).map((value) => (
                <option key={value} value={value}>
                  {serviceTicketTypeLabel(value)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="优先级">
            <select
              className={control}
              value={priority}
              onChange={(e) => {
                setPriority(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.priorities ?? []).map((value) => (
                <option key={value} value={value}>
                  {serviceTicketPriorityLabel(value)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="SLA 状态（营业时间口径）">
            <select
              className={control}
              value={slaState}
              onChange={(e) => {
                setSlaState(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.slaStates ?? []).map((value) => (
                <option key={value} value={value}>
                  {serviceTicketSlaStateLabel(value)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="分配客服">
            <input
              className={control}
              value={assignedToRef}
              onChange={(e) => {
                setAssignedToRef(e.target.value);
                setPageIndex(0);
              }}
              placeholder="客服标识"
            />
          </Field>
          <Field label="关键字">
            <input
              className={control}
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageIndex(0);
              }}
              placeholder="工单号 / 主题 / 诉求"
            />
          </Field>
          <Field label="创建时间起">
            <input
              type="datetime-local"
              className={control}
              value={from}
              onChange={(e) => {
                setFrom(e.target.value);
                setPageIndex(0);
              }}
            />
          </Field>
          <Field label="创建时间止">
            <input
              type="datetime-local"
              className={control}
              value={to}
              onChange={(e) => {
                setTo(e.target.value);
                setPageIndex(0);
              }}
            />
          </Field>
          <Field label="排序">
            <select
              className={control}
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPageIndex(0);
              }}
            >
              {(capabilities?.sorts ?? ['created_desc']).map((value) => (
                <option key={value} value={value}>
                  {value === 'sla_asc'
                    ? '按 SLA 剩余（营业时间）升序'
                    : value === 'priority_desc'
                      ? '按优先级'
                      : value === 'assigned_asc'
                        ? '按分配客服'
                        : '按创建时间倒序'}
                </option>
              ))}
            </select>
          </Field>
          <div className="flex items-end gap-4">
            <label className="text-copy-13 flex items-center gap-1.5">
              <input
                type="checkbox"
                checked={onlyOverdue}
                onChange={(e) => {
                  setOnlyOverdue(e.target.checked);
                  setPageIndex(0);
                }}
              />
              只看逾期
            </label>
            <label className="text-copy-13 flex items-center gap-1.5">
              <input
                type="checkbox"
                checked={onlyUnassigned}
                onChange={(e) => {
                  setOnlyUnassigned(e.target.checked);
                  setPageIndex(0);
                }}
              />
              只看未分配
            </label>
          </div>
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 汇总 */}
      {summary ? (
        <div className="grid gap-3 md:grid-cols-4 lg:grid-cols-6">
          <StatCard label="命中工单" value={summary.total} />
          <StatCard label="待处理 / 处理中" value={`${summary.open} / ${summary.inProgress}`} />
          <StatCard label="待买家" value={summary.waitingBuyer} />
          <StatCard label="逾期（营业时间口径）" value={summary.overdue} polarity="negative" />
          <StatCard label="未分配" value={summary.unassigned} />
          <StatCard label="未配营业时间" value={summary.noCalendar} />
        </div>
      ) : null}
      {summary ? <p className="text-copy-12 text-muted-foreground">{summary.scopeNote}</p> : null}

      {/* ------------------------------------------------ 列表 */}
      <SectionCard
        title="工单列表"
        description="类型、优先级、状态、SLA 剩余（营业时间口径）、关联买家与订单、分配客服、转派与升级。"
      >
        {loading && !page ? (
          <p className="text-copy-13 text-muted-foreground">正在读取工单列表…</p>
        ) : error ? (
          <div className="space-y-3">
            <p className="text-copy-13 text-destructive">{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重试
            </Button>
          </div>
        ) : !page || page.rows.length === 0 ? (
          <EmptyState
            icon={<Ticket className="h-6 w-6" />}
            title={hasFilter ? '没有命中的工单' : '还没有工单'}
            description={
              hasFilter
                ? '换个筛选条件再试 —— 筛选与分页都在服务端执行。'
                : '工单由工单管理服务创建；本页只读，不提供建单入口。'
            }
          />
        ) : (
          <div className="space-y-3">
            <div className="space-y-2">
              {page.rows.map((row) => (
                <ServiceTicketRowCard key={row.id} row={row} />
              ))}
            </div>
            <div className="flex items-center justify-between">
              <span className="text-copy-12 text-muted-foreground">
                第 {page.page + 1} / {totalPages} 页 · 共 {page.total} 张
                {page.totalExact ? '' : '（下界，扫描已达上限）'}
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page <= 0}
                  onClick={() => setPageIndex(Math.max(page.page - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page + 1 >= totalPages}
                  onClick={() => setPageIndex(page.page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
            <p className="text-copy-12 text-muted-foreground">{page.paginationNote}</p>
            <p className="text-copy-12 text-muted-foreground">{page.slaNote}</p>
            <p className="text-copy-12 text-muted-foreground">{page.enrichmentNote}</p>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 一行工单（卡片式布局：窄屏也能读）。 */
function ServiceTicketRowCard({ row }: { row: CrmServiceTicketRow }) {
  const sla = row.sla;
  return (
    <div className="rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-copy-13 font-medium">{row.ticketNo}</span>
        <Badge tone="neutral">{serviceTicketTypeLabel(row.ticketType, row.ticketTypeLabel)}</Badge>
        <Badge tone={serviceTicketPriorityTone(row.priority)}>
          {serviceTicketPriorityLabel(row.priority, row.priorityLabel)}
        </Badge>
        <Badge tone={row.ticketStatus === 'RESOLVED' ? 'success' : 'info'}>
          {serviceTicketStatusLabel(row.ticketStatus, row.ticketStatusLabel)}
        </Badge>
        <Badge tone={serviceTicketSlaTone(sla.replyState)}>
          <AlarmClock className="mr-1 h-3 w-3" />
          回复 {serviceTicketSlaStateLabel(sla.replyState)}
        </Badge>
        {sla.replyState !== 'NO_CALENDAR' && sla.replyDueAt ? (
          <span className="text-copy-12 text-muted-foreground">
            {serviceTicketRemainingText(sla.replyBusinessSecondsRemaining)}
          </span>
        ) : null}
        {sla.calendarConfigured ? null : (
          <span className="text-copy-12 text-warning inline-flex items-center gap-1">
            <TriangleAlert className="h-3 w-3" />
            未配营业时间，SLA 算不出来
          </span>
        )}
      </div>

      <div className="text-copy-13 mt-2">{row.subject || '（无主题）'}</div>

      <dl className="text-copy-12 text-muted-foreground mt-2 grid gap-x-4 gap-y-1 md:grid-cols-3">
        <div className="flex gap-1">
          <dt>分配客服：</dt>
          <dd className="text-foreground">{row.assignedToRef || '未分配'}</dd>
        </div>
        <div className="flex gap-1">
          <dt>关联买家：</dt>
          <dd className="text-foreground">
            {row.buyer.available
              ? `${row.buyer.buyerName || row.buyer.buyerReference || row.buyer.contactRef || '—'}${
                  row.buyer.shopName ? `（${row.buyer.shopName}）` : ''
                }`
              : '不可用'}
          </dd>
        </div>
        <div className="flex gap-1">
          <dt>关联订单：</dt>
          <dd className="text-foreground">
            {row.order.available
              ? `${row.order.orderNo ?? '—'}${
                  row.order.currency && row.order.grandTotalAmount !== null
                    ? ` · ${row.order.currency} ${row.order.grandTotalAmount}`
                    : ''
                }`
              : '不可用'}
          </dd>
        </div>
        <div className="flex gap-1">
          <dt>转派：</dt>
          <dd className="text-foreground">
            {row.transfer.historyAvailable
              ? `${row.transfer.transferCount} 次转派${
                  row.transfer.lastFromOwnerRef
                    ? `（最近：${row.transfer.lastFromOwnerRef} → ${row.transfer.lastToOwnerRef ?? '—'}）`
                    : ''
                }`
              : '转派历史不可用'}
          </dd>
        </div>
        <div className="flex gap-1">
          <dt>升级去向：</dt>
          <dd className="text-foreground">
            {row.transfer.escalatedCaseNo
              ? `售后单 ${row.transfer.escalatedCaseNo}`
              : row.transfer.escalatedDisputeNo
                ? `平台纠纷 ${row.transfer.escalatedDisputeNo}`
                : '未升级'}
          </dd>
        </div>
        <div className="flex gap-1">
          <dt>创建：</dt>
          <dd className="text-foreground">{new Date(row.createdAt).toLocaleString('zh-CN')}</dd>
        </div>
      </dl>

      {!row.order.available && row.order.note ? (
        <p className="text-copy-12 text-muted-foreground mt-1">订单：{row.order.note}</p>
      ) : null}
      {!row.buyer.available && row.buyer.note ? (
        <p className="text-copy-12 text-muted-foreground mt-1">买家：{row.buyer.note}</p>
      ) : null}
      {row.transfer.note ? (
        <p className="text-copy-12 text-muted-foreground mt-1">转派：{row.transfer.note}</p>
      ) : null}
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e && typeof e === 'object' && 'body' in e) {
    const body = (e as { body?: { detail?: string; message?: string } | null }).body;
    if (body?.detail) return body.detail;
    if (body?.message) return body.message;
  }
  if (e instanceof Error && e.message) return `${fallback}：${e.message}`;
  return fallback;
}
