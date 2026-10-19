'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type { ConnectionView } from '../../../../../packages/api-contracts/src/index';
import {
  RESOURCE_TYPE_LABELS,
  STATUS_LABELS,
  TRIGGER_LABELS,
  buildTaskInput,
  cancelTask,
  countHint,
  createTask,
  delayedDiscoveryHint,
  emptyBackfillForm,
  fetchCapabilities,
  fetchTasks,
  formatInstant,
  statusTone,
  validateWindow,
  type BackfillForm,
} from './backfill-model';

/**
 * 2.6.2.1 增量补拉。
 *
 * <p>蓝图加粗句：「**补拉必须幂等**——同一条外部记录被补拉多次不得产生第二张订单或第二次扣减；
 * 平台若只提供"最近 N 条"而非按时间增量，必须分段轮询并靠去重兜底，此时要在页面上标明
 * "补拉可能延迟发现"。」
 *
 * <p>页面围绕这两句组织：
 *
 * <ol>
 *   <li><b>四个数字分开摆</b>：拉取 / 新增 / 更新 / 去重各占一格。合成一个总数会把
 *       「补到了东西」与「补到了没见过的东西」混为一谈 —— 而后者才是缺口是否真被填上的依据。
 *   <li><b>「补拉可能延迟发现」是显式横幅</b>，不是脚注：选了「只给最近 N 条」的平台，
 *       横幅立刻出现并说明为什么。把它做成小字灰句，就等于允许运营把一次不完整的补拉
 *       当成完整补拉验收。</li>
 *   <li><b>幂等键显式呈现</b>：发起前就能看到这次用的是哪个键，它按「连接 + 资源 + 窗口」派生，
 *       重复提交会拿回同一个任务，而不是悄悄产生第二次补拉。</li>
 * </ol>
 *
 * <p>这是**真实工作区**：任务列表、发起、补拉结果提交与逐条判定全部走
 * {@code /channel/backfills} 真实端点，加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 */
export function BackfillWorkspace() {
  const [connections, setConnections] = useState<ConnectionView[]>([]);
  const [tasks, setTasks] = useState<Awaited<ReturnType<typeof fetchTasks>>>([]);
  const [capabilities, setCapabilities] = useState<Awaited<ReturnType<typeof fetchCapabilities>> | null>(null);
  const [form, setForm] = useState<BackfillForm>(emptyBackfillForm());
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [list, caps] = await Promise.all([fetchTasks(), fetchCapabilities()]);
      setTasks(list);
      setCapabilities(caps);
    } catch (e) {
      setError(e instanceof Error ? e.message : '补拉任务加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    // 连接下拉的来源：2.2 授权连接（与补拉同服务的既有端点，不额外登记白名单）
    fetch('/api/channel/connections', { credentials: 'include' })
      .then((r) => (r.ok ? (r.json() as Promise<ConnectionView[]>) : []))
      .then(setConnections)
      .catch(() => setConnections([]));
  }, []);

  const windowError = useMemo(() => validateWindow(form), [form]);

  const patch = (next: Partial<BackfillForm>) => setForm((prev) => ({ ...prev, ...next }));

  async function submit() {
    if (!form.connectionId) {
      setError('请先选择要补拉的连接');
      return;
    }
    const invalid = validateWindow(form);
    if (invalid) {
      setError(invalid);
      return;
    }
    setSubmitting(true);
    setError('');
    setNotice('');
    try {
      const id = await createTask(buildTaskInput(form));
      setNotice(`补拉任务已受理：${id}。平台返回的记录按批提交到「提交补拉结果」端点。`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '补拉发起失败');
    } finally {
      setSubmitting(false);
    }
  }

  async function cancel(id: string) {
    setError('');
    try {
      await cancelTask(id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '中止补拉失败');
    }
  }

  const stats = useMemo(() => {
    const open = tasks.filter((t) => t.status === 'PENDING' || t.status === 'RUNNING').length;
    const failed = tasks.filter((t) => t.status === 'FAILED').length;
    const deduped = tasks.reduce((sum, t) => sum + t.deduplicatedCount, 0);
    return { open, failed, deduped, total: tasks.length };
  }, [tasks]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="增量补拉"
        description="webhook 漏推、平台故障恢复或发现时间窗缺口时，按增量接口主动补拉缺失区间。补拉幂等：同一条外部记录补拉多次只计去重，不产生第二条业务记录。"
        actions={
          <Button variant="ghost" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} aria-hidden />
            刷新
          </Button>
        }
      />

      {error && (
        <div role="alert" className="rounded-lg border border-red-300 bg-red-50 p-3 text-sm text-red-800">
          {error}
        </div>
      )}
      {notice && (
        <div role="status" className="rounded-lg border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-900">
          {notice}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="补拉任务" value={stats.total} />
        <StatCard label="进行中" value={stats.open} />
        <StatCard
          label="失败"
          value={stats.failed}
          polarity={stats.failed > 0 ? 'negative' : 'neutral'}
        />
        <StatCard
          label="累计去重条数"
          value={stats.deduped}
          unit="条"
          polarity="positive"
          comparisonLabel="去重 = 这条之前已补到过，本次不新增"
        />
      </div>

      <SectionCard title="发起补拉" description="按连接 + 站点 + 资源类型 + 时间窗发起；幂等键相同则拿回同一个任务。">
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          <label className="space-y-1 text-sm">
            <span className="font-medium">连接</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={form.connectionId}
              onChange={(e) => patch({ connectionId: e.target.value })}
            >
              <option value="">请选择连接</option>
              {connections.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.platformCode} · {c.authorizedAccount}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">资源类型</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={form.resourceType}
              onChange={(e) => patch({ resourceType: e.target.value as BackfillForm['resourceType'] })}
            >
              {Object.entries(RESOURCE_TYPE_LABELS).map(([code, label]) => (
                <option key={code} value={code}>
                  {label}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">触发来源</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={form.triggerSource}
              onChange={(e) => patch({ triggerSource: e.target.value as BackfillForm['triggerSource'] })}
            >
              {Object.entries(TRIGGER_LABELS).map(([code, label]) => (
                <option key={code} value={code}>
                  {label}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">时间窗左端（闭）</span>
            <Input
              type="datetime-local"
              value={form.windowStart}
              onChange={(e) => patch({ windowStart: e.target.value })}
            />
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">时间窗右端（开）</span>
            <Input
              type="datetime-local"
              value={form.windowEnd}
              onChange={(e) => patch({ windowEnd: e.target.value })}
            />
          </label>

          <label className="space-y-1 text-sm">
            <span className="font-medium">平台增量接口形态</span>
            <select
              className="w-full rounded-md border border-slate-300 px-2 py-1.5"
              value={form.paginationMode}
              onChange={(e) => patch({ paginationMode: e.target.value as BackfillForm['paginationMode'] })}
            >
              <option value="TIME_INCREMENTAL">支持按时间增量（一次轮询即可覆盖）</option>
              <option value="RECENT_N_SCAN">只提供「最近 N 条」（需分段轮询）</option>
            </select>
          </label>

          <label className="space-y-1 text-sm md:col-span-2">
            <span className="font-medium">幂等键</span>
            <Input
              value={form.idempotencyKey}
              placeholder="留空则按「连接 + 资源类型 + 窗口左端」派生"
              onChange={(e) => patch({ idempotencyKey: e.target.value })}
            />
            <span className="block text-xs text-slate-500">
              相同幂等键重复发起会拿回同一个补拉任务，不会产生第二次补拉。
            </span>
          </label>
        </div>

        {form.paginationMode === 'RECENT_N_SCAN' && (
          <div
            role="alert"
            className="mt-4 flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
          >
            <TriangleAlert className="mt-0.5 shrink-0" aria-hidden />
            <p>
              <strong className="font-semibold">补拉可能延迟发现。</strong>
              {delayedDiscoveryHint()}
            </p>
          </div>
        )}

        {windowError && <p className="mt-3 text-sm text-red-700">{windowError}</p>}

        <div className="mt-4 flex gap-2">
          <Button onClick={() => void submit()} disabled={submitting || !!windowError}>
            {submitting ? '提交中…' : '发起补拉'}
          </Button>
        </div>
      </SectionCard>

      {capabilities && (
        <SectionCard title="能力声明" description="未交付的环节显式标记，界面据此禁用而不是让人点了才报错。">
          <ul className="space-y-2 text-sm">
            {capabilities.capabilities.map((c) => (
              <li key={c.key} className="flex items-start gap-2">
                <Badge tone={c.available ? 'success' : 'neutral'}>{c.available ? '可用' : '未交付'}</Badge>
                <span>
                  {c.description}
                  {!c.available && c.ownerModule && (
                    <span className="ml-1 text-slate-500">（归属模块 {c.ownerModule}）</span>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      <SectionCard title="补拉任务" description="四个数字分开统计：拉取 / 新增 / 更新 / 去重。">
        {loading && <p className="text-sm text-slate-500">加载中…</p>}
        {!loading && tasks.length === 0 && (
          <EmptyState
            title="还没有补拉任务"
            description="发起一次补拉即可填补缺失区间。相同幂等键重复发起不会产生第二个任务。"
          />
        )}
        {!loading && tasks.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[64rem] text-left text-sm">
              <thead className="border-b border-slate-200 text-slate-500">
                <tr>
                  <th className="py-2 pr-3">连接 / 资源</th>
                  <th className="py-2 pr-3">时间窗</th>
                  <th className="py-2 pr-3">触发</th>
                  <th className="py-2 pr-3">接口形态</th>
                  <th className="py-2 pr-3">四个数字</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {tasks.map((t) => (
                  <tr key={t.id} className="border-b border-slate-100 align-top">
                    <td className="py-2 pr-3">
                      <div>{t.platformCode} · {t.authorizedAccount}</div>
                      <div className="text-xs text-slate-500">
                        {RESOURCE_TYPE_LABELS[t.resourceType as keyof typeof RESOURCE_TYPE_LABELS] ?? t.resourceType}
                      </div>
                    </td>
                    <td className="py-2 pr-3 text-xs">
                      <div>{formatInstant(t.windowStart)}</div>
                      <div className="text-slate-500">→ {formatInstant(t.windowEnd)}</div>
                    </td>
                    <td className="py-2 pr-3 text-xs">
                      {TRIGGER_LABELS[t.triggerSource as keyof typeof TRIGGER_LABELS] ?? t.triggerSource}
                    </td>
                    <td className="py-2 pr-3 text-xs">
                      <div>{t.paginationMode === 'RECENT_N_SCAN' ? '只给最近 N 条' : '按时间增量'}</div>
                      {t.delayedDiscovery && (
                        <Badge tone="warning">补拉可能延迟发现</Badge>
                      )}
                    </td>
                    <td className="py-2 pr-3 text-xs">{countHint(t)}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={statusTone(t.status)}>
                        {STATUS_LABELS[t.status as keyof typeof STATUS_LABELS] ?? t.status}
                      </Badge>
                      {t.lastError && <div className="mt-1 text-xs text-red-700">{t.lastError}</div>}
                    </td>
                    <td className="py-2 pr-3">
                      {(t.status === 'PENDING' || t.status === 'RUNNING') && (
                        <Button variant="ghost" onClick={() => void cancel(t.id)}>
                          中止
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>
    </div>
  );
}
