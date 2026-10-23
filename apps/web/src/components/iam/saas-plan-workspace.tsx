'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RefreshCw } from 'lucide-react';
import type {
  EntitlementCheckView,
  PlanDetailView,
  PlanView,
  SaasCapabilitiesView,
  SaasOptionsView,
  SubscriptionDetailView,
  SubscriptionView,
  UsageView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';
import {
  ENTITLEMENT_KIND_LABELS,
  EVENT_ACTION_LABELS,
  OVER_LIMIT_ACTION_LABELS,
  PERIOD_LABELS,
  PLAN_STATUS_LABELS,
  SUBSCRIPTION_STATUS_LABELS,
  capabilitySummary,
  entitlementVerdict,
  eventSummary,
  nextStatuses,
  planSummary,
  subscriptionStatusText,
  usageSummary,
  validateEntitlementCode,
  validateLimit,
  validatePlanCode,
} from './saas-plan-model';

/**
 * 17.7.1.1 套餐与权益。
 *
 * <p>这是 starcon **自身**的 SaaS 商业化域（套餐定义、权益项、订阅状态、试用与到期、
 * 超限策略），**仅平台管理员可见，租户不可访问**。页面上没有「租户自助查看自己套餐」
 * 的入口——蓝图把这一域整体划在平台运营侧，留一个租户读口就会与租户自身的采购/应付
 * 业务混在一起。
 *
 * <p>页面分四块：
 *
 * <ul>
 *   <li><b>能力声明</b>——把「实做了什么」与「**没做**什么」都摆在明面上。未交付的维度
 *       （用量自动计量、超限降级的执行、计费与收入确认）逐条点名归属模块与影响面，
 *       不用空列表或 0 冒充「这一项没有数据」。
 *   <li><b>套餐与权益</b>——套餐是平台级定义（不带 tenant_id），权益项给出六个维度的
 *       上限（数量，不是金额）与超限策略。
 *   <li><b>订阅看板</b>——所有租户的订阅状态一行一条，**没开通的租户也在列表里**
 *       （「哪些客户还没开通套餐」正是这个看板要回答的问题）。
 *   <li><b>用量与超限判定</b>——按目标租户读权益用量，并给出可被下游功能开关消费的判定结论。
 * </ul>
 *
 * <p>四种状态各不相同：加载中、读不到（没有平台运营权限）、出错（可重试）、
 * 真的没有数据（空态）。「读不到」与「没有数据」混成一句话，平台运营就会以为
 * 「平台还没配套餐」而去找错方向。
 */
export function SaasPlanWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [capabilities, setCapabilities] = useState<SaasCapabilitiesView | null>(null);
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [options, setOptions] = useState<SaasOptionsView | null>(null);
  const [selectedPlan, setSelectedPlan] = useState('');
  const [detail, setDetail] = useState<PlanDetailView | null>(null);

  const [subscriptions, setSubscriptions] = useState<SubscriptionView[]>([]);
  const [selectedSubscription, setSelectedSubscription] = useState('');
  const [subscriptionDetail, setSubscriptionDetail] = useState<SubscriptionDetailView | null>(null);

  const [usageTenant, setUsageTenant] = useState('');
  const [usage, setUsage] = useState<UsageView[]>([]);
  const [check, setCheck] = useState<EntitlementCheckView | null>(null);
  const [checkCode, setCheckCode] = useState('');

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const canReadPlans = can('identity.plan.read');
  const canReadSubscriptions = can('identity.subscription.read');

  const load = useCallback(async () => {
    setError('');
    if (!canReadPlans && !canReadSubscriptions) {
      setReady(true);
      return;
    }
    try {
      const calls: Promise<unknown>[] = [];
      if (canReadPlans) {
        calls.push(api<SaasCapabilitiesView>('identity/saas-plans/capabilities'));
        calls.push(api<PlanView[]>('identity/saas-plans/plans'));
        calls.push(api<SaasOptionsView>('identity/saas-plans/options'));
      }
      if (canReadSubscriptions)
        calls.push(api<SubscriptionView[]>('identity/saas-plans/subscriptions'));
      const results = await Promise.all(calls);
      let i = 0;
      if (canReadPlans) {
        setCapabilities(results[i++] as SaasCapabilitiesView);
        setPlans(results[i++] as PlanView[]);
        setOptions(results[i++] as SaasOptionsView);
      }
      if (canReadSubscriptions) setSubscriptions(results[i++] as SubscriptionView[]);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [canReadPlans, canReadSubscriptions]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadPlanDetail = useCallback(
    async (code: string) => {
      if (!code || !canReadPlans) return;
      setError('');
      try {
        setDetail(await api<PlanDetailView>(`identity/saas-plans/plans/${code}`));
      } catch (e) {
        setError(e instanceof Error ? e.message : '套餐详情加载失败，请重试');
      }
    },
    [canReadPlans],
  );

  const loadSubscription = useCallback(
    async (id: string) => {
      if (!id || !canReadSubscriptions) return;
      setError('');
      try {
        setSubscriptionDetail(
          await api<SubscriptionDetailView>(`identity/saas-plans/subscriptions/${id}`),
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : '订阅详情加载失败，请重试');
      }
    },
    [canReadSubscriptions],
  );

  const loadUsage = useCallback(
    async (tenantId: string) => {
      if (!tenantId || !canReadSubscriptions) return;
      setError('');
      try {
        setUsage(await api<UsageView[]>(`identity/saas-plans/usage?tenantId=${tenantId}`));
      } catch (e) {
        setError(e instanceof Error ? e.message : '权益用量加载失败，请重试');
      }
    },
    [canReadSubscriptions],
  );

  async function run(action: () => Promise<unknown>, message: string) {
    setBusy(true);
    setError('');
    try {
      await action();
      setNotice(message);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  async function createPlan() {
    const code = window.prompt(
      '新建套餐\n\n请输入套餐编码（大写字母开头，2–64 位字母数字下划线）：',
    );
    if (code === null) return;
    const problem = validatePlanCode(code);
    if (problem) {
      setError(problem);
      return;
    }
    const name = window.prompt(`新建套餐「${code.trim()}」\n\n请输入套餐名称：`);
    if (name === null || !name.trim()) {
      setError('套餐名称不能为空');
      return;
    }
    await run(
      () =>
        api('identity/saas-plans/plans', 'POST', {
          code: code.trim(),
          name: name.trim(),
          description: '',
          tierLevel: 0,
          trialDays: 0,
          sortOrder: 0,
        }),
      '套餐已创建',
    );
  }

  async function createEntitlement(planCode: string) {
    const code = window.prompt(
      `为套餐「${planCode}」新增权益项\n\n请输入权益编码（大写字母开头，2–64 位）：`,
    );
    if (code === null) return;
    const problem = validateEntitlementCode(code);
    if (problem) {
      setError(problem);
      return;
    }
    const kind = window.prompt(
      '请输入权益维度（SHOP_COUNT 店铺数 / SKU_COUNT SKU 数 / ORDER_VOLUME 订单量 / USER_COUNT 用户数 / API_CALL API 调用量 / AI_USAGE AI 用量）：',
      'SKU_COUNT',
    );
    if (kind === null) return;
    const limit = window.prompt(
      '请输入上限（数量，不是金额；不限量请填 0 并在下一步勾选不限量）：',
      '1000',
    );
    if (limit === null) return;
    const unlimited = limit.trim() === '0';
    const limitProblem = validateLimit(limit, unlimited);
    if (limitProblem) {
      setError(limitProblem);
      return;
    }
    const action = window.prompt(
      '请输入超限策略（BLOCK 阻断 / DEGRADE 降级 / ALLOW 放行）：',
      'BLOCK',
    );
    if (action === null) return;
    await run(
      () =>
        api(`identity/saas-plans/plans/${planCode}/entitlements`, 'POST', {
          entitlementCode: code.trim(),
          entitlementKind: kind.trim(),
          limitValue: unlimited ? null : Number(limit),
          unlimited,
          overLimitAction: action.trim(),
          period: 'MONTH',
          unit: '',
          sortOrder: 0,
        }),
      '权益项已新增',
    );
    await loadPlanDetail(planCode);
  }

  async function removeEntitlement(planCode: string, entitlementCode: string) {
    if (!window.confirm(`删除权益项「${entitlementCode}」？已被用量登记引用过的删不掉。`)) return;
    await run(
      () => api(`identity/saas-plans/plans/${planCode}/entitlements/${entitlementCode}`, 'DELETE'),
      '权益项已删除',
    );
    await loadPlanDetail(planCode);
  }

  async function openSubscription() {
    const tenantId = window.prompt(
      '开通订阅\n\n请输入目标租户 ID（平台运营替客户开通，租户不可自助）：',
      usageTenant,
    );
    if (tenantId === null || !tenantId.trim()) return;
    const planCode = window.prompt('请输入套餐编码：', options?.plans[0]?.code ?? 'STARTER');
    if (planCode === null || !planCode.trim()) return;
    const today = new Date();
    const nextYear = new Date(today.getTime());
    nextYear.setFullYear(nextYear.getFullYear() + 1);
    await run(
      () =>
        api('identity/saas-plans/subscriptions', 'POST', {
          tenantId: tenantId.trim(),
          planCode: planCode.trim(),
          status: 'ACTIVE',
          startedAt: today.toISOString(),
          expiresAt: nextYear.toISOString(),
          note: '',
        }),
      '订阅已开通',
    );
  }

  async function changeStatus(subscription: SubscriptionView, next: string) {
    const reason = window.prompt(
      `把订阅状态改为「${SUBSCRIPTION_STATUS_LABELS[next] ?? next}」\n\n请填写变更原因（必填，事后要能解释）：`,
    );
    if (reason === null) return;
    if (!reason.trim()) {
      setError('必须填写原因：事后要能解释这次状态变更');
      return;
    }
    const payload: Record<string, unknown> = { status: next, reason: reason.trim() };
    if (next === 'ACTIVE') {
      const startedAt = window.prompt('请输入正式订阅的开始时间（YYYY-MM-DD）：');
      if (startedAt === null) return;
      const expiresAt = window.prompt('请输入正式订阅的到期时间（YYYY-MM-DD）：');
      if (expiresAt === null) return;
      payload.startedAt = new Date(startedAt).toISOString();
      payload.expiresAt = new Date(expiresAt).toISOString();
    }
    await run(
      () => api(`identity/saas-plans/subscriptions/${subscription.id}/status`, 'POST', payload),
      '订阅状态已变更',
    );
    if (selectedSubscription) await loadSubscription(selectedSubscription);
  }

  async function runCheck() {
    if (!usageTenant || !checkCode) {
      setError('请先选择目标租户并填写权益维度');
      return;
    }
    setError('');
    try {
      setCheck(
        await api<EntitlementCheckView>(
          `identity/saas-plans/entitlement-check?tenantId=${usageTenant}&entitlementCode=${encodeURIComponent(checkCode.trim())}`,
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '超限判定失败，请重试');
    }
  }

  const unreadable = !canReadPlans && !canReadSubscriptions;
  const subscribedTenants = useMemo(
    () => subscriptions.filter((s) => s.id !== null),
    [subscriptions],
  );
  const notSubscribed = useMemo(() => subscriptions.filter((s) => s.id === null), [subscriptions]);

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载套餐与权益…
      </p>
    );

  if (unreadable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有套餐与权益的查看权限。套餐与权益是<b>平台运营域</b>，租户不可访问——
        请联系平台运营方开通平台运营角色。
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          套餐与权益是 starcon <b>自身的 SaaS 商业化域</b>，仅平台管理员可见。 权益上限与用量都是
          <b>数量</b>（店铺数 / SKU 数 / 订单量 / 用户数 / API 调用量 / AI
          用量），本模块不涉及任何金额。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => void load()} disabled={busy}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {can('identity.plan.create') && (
            <Button size="sm" onClick={() => void createPlan()} disabled={busy}>
              <Plus className="h-4 w-4" />
              新建套餐
            </Button>
          )}
          {can('identity.subscription.create') && (
            <Button size="sm" onClick={() => void openSubscription()} disabled={busy}>
              <Plus className="h-4 w-4" />
              开通订阅
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">能力声明</h2>
          <ul className="space-y-1">
            {capabilities.dimensions.map((d) => (
              <li
                key={d.dimension}
                className={`text-copy-12 rounded-md border p-2 ${
                  d.available ? 'text-muted-foreground' : 'border-warning/40 bg-warning/5'
                }`}
              >
                <Badge tone={d.available ? 'info' : 'warning'}>
                  {d.available ? '已交付' : '未交付'}
                </Badge>
                <span className="ml-2">{capabilitySummary(d)}</span>
              </li>
            ))}
          </ul>
          <p className="text-copy-12 text-muted-foreground">{capabilities.financeNote}</p>
        </section>
      )}

      {/* ---------------- 套餐与权益 ---------------- */}
      {canReadPlans && (
        <section className="space-y-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-copy-15 font-medium">套餐与权益</h2>
            <p className="text-copy-12 text-muted-foreground">
              套餐是平台级定义，全平台共用；权益项给出各维度上限与超限策略。
            </p>
          </div>
          {plans.length === 0 ? (
            <div className="bg-card rounded-lg border p-8 text-center">
              <p className="text-copy-13 text-muted-foreground">
                还没有任何套餐。平台运营新建一个套餐后，这里才会出现内容。
              </p>
            </div>
          ) : (
            <ul className="grid gap-2 md:grid-cols-2 xl:grid-cols-3">
              {plans.map((p) => (
                <li key={p.code}>
                  <button
                    type="button"
                    onClick={() => {
                      const next = selectedPlan === p.code ? '' : p.code;
                      setSelectedPlan(next);
                      void loadPlanDetail(next);
                    }}
                    aria-pressed={selectedPlan === p.code}
                    className={`w-full rounded-md border p-3 text-left transition-colors ${
                      selectedPlan === p.code
                        ? 'border-ring bg-accent'
                        : 'bg-card hover:border-ring hover:bg-accent/50'
                    }`}
                  >
                    <p className="text-copy-13 font-medium">{planSummary(p)}</p>
                    {p.description && (
                      <p className="text-copy-12 text-muted-foreground mt-1">{p.description}</p>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}

          {selectedPlan && detail && (
            <div className="bg-card rounded-lg border p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-copy-14 font-medium">
                  {detail.plan.name}（{detail.plan.code}）的权益项
                </h3>
                {can('identity.plan.create') && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => void createEntitlement(detail.plan.code)}
                    disabled={busy}
                  >
                    <Plus className="h-4 w-4" />
                    新增权益项
                  </Button>
                )}
              </div>
              {detail.entitlements.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground mt-3">
                  这个套餐还没有任何权益项——不配权益项的话，超限判定对所有维度都是「判定不了」。
                </p>
              ) : (
                <table className="text-copy-12 mt-3 w-full">
                  <thead>
                    <tr className="text-muted-foreground">
                      <th className="text-left font-normal">编码</th>
                      <th className="text-left font-normal">维度</th>
                      <th className="text-left font-normal">上限</th>
                      <th className="text-left font-normal">周期</th>
                      <th className="text-left font-normal">超限策略</th>
                      <th className="text-right font-normal">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.entitlements.map((e) => (
                      <tr key={e.entitlementCode} className="border-t">
                        <td className="py-1 font-mono">{e.entitlementCode}</td>
                        <td>{ENTITLEMENT_KIND_LABELS[e.entitlementKind] ?? e.entitlementKind}</td>
                        <td>{e.unlimited ? '不限量' : e.limitValue}</td>
                        <td>{PERIOD_LABELS[e.period] ?? e.period}</td>
                        <td>{OVER_LIMIT_ACTION_LABELS[e.overLimitAction] ?? e.overLimitAction}</td>
                        <td className="py-1 text-right">
                          {can('identity.plan.update') && (
                            <button
                              type="button"
                              className="text-ring underline underline-offset-2"
                              onClick={() =>
                                void removeEntitlement(detail.plan.code, e.entitlementCode)
                              }
                              disabled={busy}
                            >
                              删除
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </section>
      )}

      {/* ---------------- 订阅看板 ---------------- */}
      {canReadSubscriptions && (
        <section className="space-y-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-copy-15 font-medium">订阅看板</h2>
            <p className="text-copy-12 text-muted-foreground">
              所有租户一行一条；<b>没开通套餐的租户也在列表里</b>
              ——「谁还没开通」正是这里要回答的问题。
            </p>
          </div>
          {subscriptions.length === 0 ? (
            <div className="bg-card rounded-lg border p-8 text-center">
              <p className="text-copy-13 text-muted-foreground">
                平台侧还没有任何租户，订阅看板暂时没有内容。
              </p>
            </div>
          ) : (
            <>
              <p className="text-copy-12 text-muted-foreground">
                已开通 {subscribedTenants.length} 个 · 未开通 {notSubscribed.length} 个
              </p>
              <div className="overflow-x-auto">
                <table className="text-copy-12 w-full">
                  <thead>
                    <tr className="text-muted-foreground">
                      <th className="text-left font-normal">租户</th>
                      <th className="text-left font-normal">套餐</th>
                      <th className="text-left font-normal">状态</th>
                      <th className="text-left font-normal">到期</th>
                      <th className="text-left font-normal">用量</th>
                      <th className="text-right font-normal">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {subscriptions.map((s) => (
                      <tr key={s.tenantId} className="border-t">
                        <td className="py-1">
                          <button
                            type="button"
                            className="text-ring underline underline-offset-2"
                            onClick={() => {
                              setSelectedSubscription(s.id ?? '');
                              setUsageTenant(s.tenantId);
                              void loadSubscription(s.id ?? '');
                              void loadUsage(s.tenantId);
                            }}
                          >
                            {s.tenantName ?? s.tenantCode ?? s.tenantId}
                          </button>
                        </td>
                        <td>{s.planName ?? '—'}</td>
                        <td>
                          {subscriptionStatusText(s.status, s.effectiveStatus)}
                          {s.blockedCount > 0 && (
                            <Badge tone="warning">阻断 {s.blockedCount}</Badge>
                          )}
                        </td>
                        <td>{s.expiresAt ? s.expiresAt.slice(0, 10) : '—'}</td>
                        <td>
                          已接通 {s.usageAvailableCount} · 未接通 {s.usageMissingCount}
                        </td>
                        <td className="py-1 text-right">
                          {s.id &&
                            can('identity.subscription.update') &&
                            nextStatuses(s.status).map((next) => (
                              <button
                                key={next}
                                type="button"
                                className="text-ring ml-2 underline underline-offset-2"
                                onClick={() => void changeStatus(s, next)}
                                disabled={busy}
                              >
                                {SUBSCRIPTION_STATUS_LABELS[next] ?? next}
                              </button>
                            ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {can('identity.subscription.update') && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    void run(
                      () => api('identity/saas-plans/subscriptions/expire-due', 'POST'),
                      '已处理到期订阅',
                    )
                  }
                  disabled={busy}
                >
                  处理到期订阅
                </Button>
              )}
            </>
          )}
        </section>
      )}

      {/* ---------------- 用量与超限判定 ---------------- */}
      {canReadSubscriptions && (
        <section className="space-y-3">
          <h2 className="text-copy-15 font-medium">权益用量与超限判定</h2>
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex flex-col gap-1">
              <span className="text-copy-12 text-muted-foreground">目标租户 ID</span>
              <input
                value={usageTenant}
                onChange={(e) => setUsageTenant(e.target.value)}
                placeholder="选择看板里的租户，或粘贴租户 ID"
                aria-label="目标租户 ID"
                className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-64 rounded-md border px-2 outline-none"
              />
            </label>
            <Button
              size="sm"
              variant="outline"
              onClick={() => void loadUsage(usageTenant)}
              disabled={!usageTenant || busy}
            >
              读取用量
            </Button>
            <label className="flex flex-col gap-1">
              <span className="text-copy-12 text-muted-foreground">权益维度</span>
              <input
                value={checkCode}
                onChange={(e) => setCheckCode(e.target.value)}
                placeholder="如 SKU / ORDER"
                aria-label="权益维度"
                className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-40 rounded-md border px-2 outline-none"
              />
            </label>
            <Button size="sm" onClick={() => void runCheck()} disabled={!usageTenant || !checkCode}>
              判定是否超限
            </Button>
          </div>

          {check && (
            <div
              className={`text-copy-12 rounded-md border p-3 ${
                check.blocked ? 'border-destructive/40 bg-destructive/5' : 'bg-card'
              }`}
            >
              <p className="font-medium">
                {check.entitlementCode}：{entitlementVerdict(check.state)}
              </p>
              <p className="text-muted-foreground mt-1">
                {check.reason}
                {check.ownerModule ? `（归属 ${check.ownerModule}）` : ''}
              </p>
            </div>
          )}

          {usage.length === 0 ? (
            <div className="bg-card rounded-lg border p-8 text-center">
              <p className="text-copy-13 text-muted-foreground">
                该租户还没有权益用量登记。没接通计量的维度判定结果是「判定不了」，
                <b>不是</b>「没超限」。
              </p>
            </div>
          ) : (
            <table className="text-copy-12 w-full">
              <thead>
                <tr className="text-muted-foreground">
                  <th className="text-left font-normal">维度</th>
                  <th className="text-left font-normal">用量 / 上限</th>
                  <th className="text-left font-normal">判定</th>
                  <th className="text-left font-normal">来源</th>
                </tr>
              </thead>
              <tbody>
                {usage.map((u) => (
                  <tr key={u.id} className="border-t">
                    <td className="py-1">
                      {ENTITLEMENT_KIND_LABELS[u.entitlementKind ?? ''] ?? u.entitlementCode}
                    </td>
                    <td>{usageSummary(u)}</td>
                    <td>{entitlementVerdict(u.state)}</td>
                    <td>
                      {u.sourceSystem || '—'}
                      {!u.available && u.ownerModule ? `（归属 ${u.ownerModule}）` : ''}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}

      {/* ---------------- 订阅详情 ---------------- */}
      {subscriptionDetail && (
        <section className="space-y-3">
          <h2 className="text-copy-15 font-medium">订阅变更留痕</h2>
          {subscriptionDetail.events.length === 0 ? (
            <p className="text-copy-13 text-muted-foreground">这条订阅还没有变更留痕。</p>
          ) : (
            <ul className="space-y-1">
              {subscriptionDetail.events.map((e) => (
                <li key={e.id} className="text-copy-12">
                  <span className="text-muted-foreground">
                    {e.occurredAt.slice(0, 19).replace('T', ' ')}
                  </span>
                  <span className="ml-2">
                    {eventSummary({
                      action: e.action,
                      fromStatus: e.fromStatus,
                      toStatus: e.toStatus,
                      reason: e.reason,
                    })}
                  </span>
                  {e.actorName && (
                    <span className="text-muted-foreground ml-2">· {e.actorName}</span>
                  )}
                </li>
              ))}
            </ul>
          )}
          <p className="text-copy-12 text-muted-foreground">
            留痕动作：{Object.values(EVENT_ACTION_LABELS).join(' / ')}；套餐状态：
            {Object.values(PLAN_STATUS_LABELS).join(' / ')}。
          </p>
        </section>
      )}
    </div>
  );
}
