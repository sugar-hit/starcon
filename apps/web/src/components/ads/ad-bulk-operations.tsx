'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  bulkGuardrailText,
  bulkItemResultLabel,
  bulkItemResultTone,
  bulkMoney,
  bulkOperationLabel,
  bulkTargetLabel,
  bulkTargetName,
  bulkTaskStatusLabel,
  bulkTaskStatusTone,
  bulkTimestamp,
  bulkVerdictLabel,
  bulkVerdictTone,
  emptyAdBulkFormInput,
  fetchBulkCapabilities,
  fetchBulkOptions,
  fetchBulkSelection,
  fetchBulkTask,
  fetchBulkTasks,
  previewBulkOperation,
  submitBulkTask,
  validateAdBulkForm,
} from './ad-bulk-operation-model';
import type {
  AdBulkCapabilities,
  AdBulkFormInput,
  AdBulkOperationRequest,
  AdBulkOptions,
  AdBulkPreview,
  AdBulkSelectedRow,
  AdBulkSelection,
  AdBulkTaskDetail,
  AdBulkTaskPage,
  AdBulkTaskSubmission,
} from './ad-bulk-operation-model';

/**
 * 14.1.1.3 批量操作。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **「按筛选结果全选」在服务端**。页面只把筛选条件递过去，拿回一页 id 与一个命中计数 ——
 *    绝不在浏览器里把 id 全拉回来再循环（那等于全表扫）。
 * 2. **超上限 ⇒ 整批拒绝**。预览区把「净变化」与「绝对敞口」两个数都摆出来：
 *    「+1000 与 −1000 净变化是 0，但动的是 2000 的钱」。越上限时**一条都不执行**，
 *    不是「做了一半」。
 * 3. **「已生效」= 本地已生效**。平台回写队列未交付，每条结果旁边都写「还没回写平台」。
 */
export function AdBulkOperationsWorkspace() {
  const [capabilities, setCapabilities] = useState<AdBulkCapabilities | null>(null);
  const [options, setOptions] = useState<AdBulkOptions | null>(null);

  const [filters, setFilters] = useState({
    platformId: '',
    shopCode: '',
    status: '',
    internalAdType: '',
    keyword: '',
  });
  const [targetType, setTargetType] = useState('CAMPAIGN');
  const [selection, setSelection] = useState<AdBulkSelection | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [page, setPage] = useState<AdBulkSelectedRow[]>([]);

  const [form, setForm] = useState<AdBulkFormInput>(emptyAdBulkFormInput);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [preview, setPreview] = useState<AdBulkPreview | null>(null);
  const [submission, setSubmission] = useState<AdBulkTaskSubmission | null>(null);

  const [tasks, setTasks] = useState<AdBulkTaskPage | null>(null);
  const [detail, setDetail] = useState<AdBulkTaskDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchBulkCapabilities(),
          fetchBulkOptions(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
      } catch (e) {
        if (alive) setError(errorText(e));
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const loadTasks = useCallback(async () => {
    try {
      setTasks(await fetchBulkTasks(0, 20));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    void loadTasks();
  }, [loadTasks]);

  /** 🔴 服务端解析：只递筛选条件，拿回一页 id。 */
  const resolveSelection = useCallback(
    async (nextCursor: string | null) => {
      setBusy(true);
      setError('');
      try {
        const result = await fetchBulkSelection({
          platformId: filters.platformId || undefined,
          shopCode: filters.shopCode || undefined,
          status: filters.status || undefined,
          internalAdType: filters.internalAdType || undefined,
          keyword: filters.keyword || undefined,
          targetType,
          cursor: nextCursor ?? undefined,
          size: 50,
        });
        setSelection(result);
        setPage(result.items);
        setCursor(result.nextCursor);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [filters, targetType],
  );

  const buildRequest = useCallback((): AdBulkOperationRequest => {
    return {
      platformId: filters.platformId || undefined,
      shopCode: filters.shopCode || undefined,
      status: filters.status || undefined,
      internalAdType: filters.internalAdType || undefined,
      keyword: filters.keyword || undefined,
      targetType: form.targetType,
      operation: form.operation,
      budget: form.operation === 'SET_BUDGET' ? Number(form.budget) : undefined,
      delta: form.operation === 'ADJUST_BUDGET' ? Number(form.delta) : undefined,
      bid: form.bid.trim() === '' ? undefined : Number(form.bid),
      bidDelta: form.bidDelta.trim() === '' ? undefined : Number(form.bidDelta),
      endAt: form.clearEndAt ? undefined : form.endAt || undefined,
      clearEndAt: form.operation === 'SET_END_AT' ? form.clearEndAt : undefined,
      reason: form.reason,
    };
  }, [filters, form]);

  const runPreview = useCallback(async () => {
    const check = validateAdBulkForm(form);
    setFormErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      setPreview(await previewBulkOperation(buildRequest()));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [form, buildRequest]);

  const runSubmit = useCallback(async () => {
    const check = validateAdBulkForm(form);
    setFormErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await submitBulkTask(buildRequest());
      setSubmission(result);
      setDetail(await fetchBulkTask(result.task.id, 0, 200));
      await loadTasks();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [form, buildRequest, loadTasks]);

  const openTask = useCallback(async (id: string) => {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchBulkTask(id, 0, 200));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const guardrails = options?.guardrails ?? capabilities?.guardrails ?? null;
  const targetLabel = useMemo(() => bulkTargetLabel(form.targetType), [form.targetType]);

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">广告批量操作</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          跨活动批量调整：预算增减、竞价调整、状态切换、结束时间设置。
          <strong>「按筛选结果全选」在服务端完成</strong> —— 页面只递筛选条件，
          拿回一页目标与一个命中计数，不把整张表拉回浏览器再循环。
          提交后是一个<strong>异步任务</strong>，逐条结果回头查。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {/* ================= 护栏 ================= */}
      <Section title="护栏上限">
        <p className="text-sm">{bulkGuardrailText(guardrails)}</p>
        {guardrails ? (
          <p className="text-muted-foreground text-xs">{guardrails.note}</p>
        ) : (
          <p className="text-muted-foreground text-sm">正在加载护栏上限…</p>
        )}
        <p className="text-muted-foreground text-xs">
          🔴 超上限时<strong>整批拒绝</strong>（一条都不执行），不是逐条拒绝、也不是跳过超限的那几条。
        </p>
      </Section>

      {/* ================= 筛选与选择 ================= */}
      <Section title="按筛选结果全选">
        <div className="flex flex-wrap gap-3">
          <Field label="平台">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={filters.platformId}
              onChange={(e) => setFilters({ ...filters, platformId: e.target.value })}
            >
              <option value="">全部平台</option>
              {options?.platforms.map((p) => (
                <option key={p.value} value={p.value}>
                  {p.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={filters.shopCode}
              onChange={(e) => setFilters({ ...filters, shopCode: e.target.value })}
            >
              <option value="">全部店铺</option>
              {options?.shops.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="状态">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={filters.status}
              onChange={(e) => setFilters({ ...filters, status: e.target.value })}
            >
              <option value="">全部状态</option>
              {options?.statuses.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="内部广告类型">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={filters.internalAdType}
              onChange={(e) => setFilters({ ...filters, internalAdType: e.target.value })}
            >
              <option value="">全部类型</option>
              {options?.internalAdTypes.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="名称 / 平台侧 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={filters.keyword}
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value })}
              placeholder="模糊匹配"
            />
          </Field>
          <Field label="调整落点">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={targetType}
              onChange={(e) => {
                setTargetType(e.target.value);
                setForm({ ...form, targetType: e.target.value });
              }}
            >
              <option value="CAMPAIGN">活动</option>
              <option value="AD_GROUP">广告组（竞价）</option>
            </select>
          </Field>
          <button
            type="button"
            className="self-end rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => void resolveSelection(null)}
          >
            解析目标
          </button>
        </div>

        {selection ? (
          <>
            <p className="text-sm">
              🔴 命中 <strong>{selection.total}</strong> 条
              {selection.truncated ? (
                <span className="text-destructive">
                  ，已超过单次上限 {selection.limit} 条 —— 批量任务会
                  <strong>整批拒绝</strong>，请先缩小筛选范围
                </span>
              ) : (
                <>，本次展示 {page.length} 条</>
              )}
            </p>
            <p className="text-muted-foreground text-xs">{selection.note}</p>
            {page.length > 0 ? (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">目标</th>
                    <th className="py-1">活动</th>
                    <th className="py-1">状态</th>
                    <th className="py-1">日预算</th>
                    <th className="py-1">默认竞价</th>
                    <th className="py-1">结束时间</th>
                  </tr>
                </thead>
                <tbody>
                  {page.map((row) => (
                    <tr key={`${row.targetType}-${row.groupId ?? row.campaignId}`} className="border-t">
                      <td className="py-1">{bulkTargetLabel(row.targetType)}</td>
                      <td className="py-1">{row.campaignName}</td>
                      <td className="py-1">{row.status}</td>
                      <td className="py-1">{bulkMoney(row.dailyBudget, row.currency)}</td>
                      <td className="py-1">{bulkMoney(row.defaultBid, row.bidCurrency)}</td>
                      <td className="py-1">{bulkTimestamp(row.endAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-muted-foreground text-sm">
                这个筛选条件下没有解析出任何目标 —— 先放宽筛选条件再试。
              </p>
            )}
            {selection.nextCursor ? (
              <button
                type="button"
                className="self-start rounded border px-3 py-1 text-sm"
                disabled={busy}
                onClick={() => void resolveSelection(selection.nextCursor)}
              >
                下一页
              </button>
            ) : null}
          </>
        ) : (
          <p className="text-muted-foreground text-sm">先设好筛选条件，再点「解析目标」。</p>
        )}
      </Section>

      {/* ================= 调整参数 ================= */}
      <Section title="调整参数">
        <div className="flex flex-wrap gap-3">
          <Field label="操作">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={form.operation}
              onChange={(e) => setForm({ ...form, operation: e.target.value })}
            >
              {options?.operations.map((op) => (
                <option key={op.value} value={op.value}>
                  {op.label}
                </option>
              ))}
            </select>
          </Field>
          {form.operation === 'SET_BUDGET' ? (
            <Field label="目标日预算">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.budget}
                onChange={(e) => setForm({ ...form, budget: e.target.value })}
              />
            </Field>
          ) : null}
          {form.operation === 'ADJUST_BUDGET' ? (
            <Field label="预算增减额（可负）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.delta}
                onChange={(e) => setForm({ ...form, delta: e.target.value })}
              />
            </Field>
          ) : null}
          {form.operation === 'ADJUST_BID' ? (
            <>
              <Field label="目标竞价（与增减额二选一）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.bid}
                  onChange={(e) => setForm({ ...form, bid: e.target.value })}
                />
              </Field>
              <Field label="竞价增减额（可负）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.bidDelta}
                  onChange={(e) => setForm({ ...form, bidDelta: e.target.value })}
                />
              </Field>
            </>
          ) : null}
          {form.operation === 'SET_END_AT' ? (
            <>
              <Field label="目标结束时刻">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.endAt}
                  onChange={(e) => setForm({ ...form, endAt: e.target.value })}
                  placeholder="2026-12-31T23:59:59+08:00"
                />
              </Field>
              <Field label="清空结束时间">
                <input
                  type="checkbox"
                  checked={form.clearEndAt}
                  onChange={(e) => setForm({ ...form, clearEndAt: e.target.checked })}
                />
              </Field>
            </>
          ) : null}
          <Field label="操作理由（写进审计）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={form.reason}
              onChange={(e) => setForm({ ...form, reason: e.target.value })}
            />
          </Field>
        </div>
        {formErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {formErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        <div className="flex gap-2">
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => void runPreview()}
          >
            预览变更
          </button>
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            disabled={busy || (preview !== null && !preview.allowed)}
            onClick={() => void runSubmit()}
          >
            提交批量任务
          </button>
        </div>
        <p className="text-muted-foreground text-xs">
          🔴 调整落点：<strong>{targetLabel}</strong>
          {form.operation === 'ADJUST_BID' ? '（竞价改的是广告组的默认竞价，不是活动预算）' : ''}
        </p>
      </Section>

      {/* ================= 预览 ================= */}
      <Section title="变更预览（不落库）">
        {preview ? (
          <>
            <p className={`text-sm ${preview.allowed ? 'text-emerald-600' : 'text-destructive'}`}>
              {preview.allowed ? (
                <>
                  🔴 可以整批执行：目标 <strong>{preview.targetCount}</strong> 条
                </>
              ) : (
                <>
                  🔴 <strong>整批拒绝</strong>：{preview.rejectionReason}
                </>
              )}
            </p>
            <p className="text-sm">
              总预算净增量 <strong>{preview.totalBudgetDelta}</strong>，
              绝对敞口 <strong>{preview.totalBudgetDeltaAbs}</strong>
              {preview.totalBidDelta !== 0 || preview.totalBidDeltaAbs !== 0 ? (
                <>
                  ；总竞价净增量 <strong>{preview.totalBidDelta}</strong>，
                  绝对敞口 <strong>{preview.totalBidDeltaAbs}</strong>
                </>
              ) : null}
            </p>
            <p className="text-muted-foreground text-xs">
              🔴 净增量与绝对敞口是**两个数**：「+1000 与 −1000 净变化是 0，但动的是 2000 的钱」。
              护栏判的是绝对敞口。
            </p>
            <p className="text-muted-foreground text-xs">{preview.spendImpactNote}</p>
            {preview.items.length > 0 ? (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">目标</th>
                    <th className="py-1">变更前</th>
                    <th className="py-1">变更后</th>
                    <th className="py-1">结论</th>
                    <th className="py-1">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.items.map((item) => (
                    <tr
                      key={`${item.targetType}-${item.groupId ?? item.campaignId}`}
                      className="border-t"
                    >
                      <td className="py-1">{bulkTargetName(item)}</td>
                      <td className="py-1">
                        {item.budgetDelta !== null && item.budgetDelta !== undefined
                          ? bulkMoney(item.beforeBudget)
                          : item.bidDelta !== null && item.bidDelta !== undefined
                            ? bulkMoney(item.beforeBid)
                            : item.beforeStatus || bulkTimestamp(item.beforeEndAt)}
                      </td>
                      <td className="py-1">
                        {item.budgetDelta !== null && item.budgetDelta !== undefined
                          ? bulkMoney(item.afterBudget)
                          : item.bidDelta !== null && item.bidDelta !== undefined
                            ? bulkMoney(item.afterBid)
                            : item.afterStatus || bulkTimestamp(item.afterEndAt)}
                      </td>
                      <td className={`py-1 ${bulkVerdictTone(item.verdict)}`}>
                        {bulkVerdictLabel(item.verdict)}
                      </td>
                      <td className="text-muted-foreground py-1 text-xs">{item.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-muted-foreground text-sm">这个筛选条件下没有解析出任何目标。</p>
            )}
            {preview.previewTruncated ? (
              <p className="text-muted-foreground text-xs">
                明细只展示前 {preview.previewItemCount} 条，共 {preview.targetCount} 条。
              </p>
            ) : null}
            <p className="text-muted-foreground text-xs">{preview.note}</p>
          </>
        ) : (
          <p className="text-muted-foreground text-sm">
            先点「预览变更」：预演**不落库**，能看到会变成什么样再决定要不要提交。
          </p>
        )}
      </Section>

      {/* ================= 批量任务 ================= */}
      <Section title="批量任务（异步）">
        {submission ? (
          <p className="text-sm">
            已受理任务 <strong>{submission.task.id}</strong>，
            状态 <strong className={bulkTaskStatusTone(submission.task.status)}>
              {bulkTaskStatusLabel(submission.task.status)}
            </strong>
            。{submission.note}
          </p>
        ) : (
          <p className="text-muted-foreground text-sm">
            提交后这里会出现任务；逐条执行结果用任务详情查。
          </p>
        )}

        {tasks && tasks.items.length > 0 ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">操作</th>
                <th className="py-1">落点</th>
                <th className="py-1">选择</th>
                <th className="py-1">状态</th>
                <th className="py-1">生效 / 被拒 / 跳过</th>
                <th className="py-1">预算净增量</th>
                <th className="py-1">创建时刻</th>
                <th className="py-1" />
              </tr>
            </thead>
            <tbody>
              {tasks.items.map((task) => (
                <tr key={task.id} className="border-t">
                  <td className="py-1">{bulkOperationLabel(task.operation)}</td>
                  <td className="py-1">{bulkTargetLabel(task.targetType)}</td>
                  <td className="py-1 text-xs">{task.selectionSummary}</td>
                  <td className={`py-1 ${bulkTaskStatusTone(task.status)}`}>
                    {bulkTaskStatusLabel(task.status)}
                  </td>
                  <td className="py-1">
                    {task.appliedCount} / {task.rejectedCount} / {task.skippedCount}
                  </td>
                  <td className="py-1">{task.totalBudgetDelta}</td>
                  <td className="py-1">{bulkTimestamp(task.createdAt)}</td>
                  <td className="py-1">
                    <button
                      type="button"
                      className="rounded border px-2 py-0.5 text-xs"
                      onClick={() => void openTask(task.id)}
                    >
                      逐条结果
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="text-muted-foreground text-sm">还没有批量任务。</p>
        )}
      </Section>

      {/* ================= 逐条结果 ================= */}
      {detail ? (
        <Section title={`逐条执行结果 · ${bulkOperationLabel(detail.task.operation)}`}>
          <p className="text-sm">
            状态{' '}
            <strong className={bulkTaskStatusTone(detail.task.status)}>
              {bulkTaskStatusLabel(detail.task.status)}
            </strong>
            ，共 {detail.itemTotal} 条。{detail.note}
          </p>
          {detail.task.message ? (
            <p className="text-destructive text-sm">{detail.task.message}</p>
          ) : null}
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">目标</th>
                <th className="py-1">变更前</th>
                <th className="py-1">变更后</th>
                <th className="py-1">结果</th>
                <th className="py-1">平台回写</th>
                <th className="py-1">说明</th>
              </tr>
            </thead>
            <tbody>
              {detail.items.map((item) => (
                <tr key={item.id} className="border-t">
                  <td className="py-1">{bulkTargetName(item)}</td>
                  <td className="py-1">
                    {item.beforeBudget !== null && item.beforeBudget !== undefined
                      ? bulkMoney(item.beforeBudget)
                      : item.beforeBid !== null && item.beforeBid !== undefined
                        ? bulkMoney(item.beforeBid)
                        : item.beforeStatus || bulkTimestamp(item.beforeEndAt)}
                  </td>
                  <td className="py-1">
                    {item.afterBudget !== null && item.afterBudget !== undefined
                      ? bulkMoney(item.afterBudget)
                      : item.afterBid !== null && item.afterBid !== undefined
                        ? bulkMoney(item.afterBid)
                        : item.afterStatus || bulkTimestamp(item.afterEndAt)}
                  </td>
                  <td className={`py-1 ${bulkItemResultTone(item.result)}`}>
                    {bulkItemResultLabel(item.result)}
                  </td>
                  <td className="py-1 text-xs">
                    {item.pendingPlatformWriteback ? '本地已改，还没回写平台' : '—'}
                  </td>
                  <td className="text-muted-foreground py-1 text-xs">{item.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      ) : null}

      {/* ================= 能力声明 ================= */}
      {capabilities ? (
        <Section title="能力声明">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">维度</th>
                <th className="py-1">说明</th>
                <th className="py-1">状态</th>
                <th className="py-1">责任模块 / 影响</th>
              </tr>
            </thead>
            <tbody>
              {capabilities.dimensions.map((dim) => (
                <tr key={dim.dimension} className="border-t">
                  <td className="py-1">{dim.dimension}</td>
                  <td className="py-1 text-xs">{dim.description}</td>
                  <td className={`py-1 ${dim.available ? 'text-emerald-600' : 'text-muted-foreground'}`}>
                    {dim.available ? '已交付' : '未交付'}
                  </td>
                  <td className="text-muted-foreground py-1 text-xs">
                    {dim.available ? dim.source : `${dim.ownerModule} —— ${dim.impact}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-muted-foreground text-xs">{capabilities.writebackNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.upstreamNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.boundaryNote}</p>
        </Section>
      ) : loading ? (
        <p className="text-muted-foreground text-sm">正在加载能力声明…</p>
      ) : null}
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded border p-4">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      {children}
    </label>
  );
}
