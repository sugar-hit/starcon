'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Filter, RefreshCw, Scale, ShieldAlert } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  BuyerProfile,
  BuyerProfilePage,
  EvaluationPage,
  OrderReviewRuleCapabilities,
  ReviewDetail,
  ReviewRuleDetail,
  ReviewRuleInput,
  ReviewRulePage,
  ReviewRuleRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  BUYER_LEVELS,
  DIMENSION_LABELS,
  OPERATOR_LABELS,
  ORDER_SOURCES,
  RULE_STATUS_OPTIONS,
  anomalyText,
  blacklistText,
  buyerLevelText,
  canActivate,
  canDisable,
  changeRuleStatus,
  conditionSummary,
  ruleConditionSummary,
  createRule,
  decisionCounts,
  decisionHeadline,
  decisionTone,
  discountRateText,
  effectiveText,
  errorText,
  fetchBuyerProfiles,
  fetchCapabilities,
  fetchEvaluation,
  fetchEvaluations,
  fetchRule,
  fetchRules,
  hitConditionLines,
  hitSummary,
  modeText,
  money,
  operatorsFor,
  reviewOrder,
  saveBuyerProfile,
  statusTone,
  toConditionInput,
  trialOrder,
  updateRule,
} from './order-review-rule-model';

const PAGE_SIZE = 20;

/**
 * 9.2.1.2 审单规则工作区。
 *
 * <p>蓝图：「配置自动审单规则：按店铺/金额/买家等级/商品/收货国家自动通过或转人工；
 * 命中黑名单、地址异常、大额、异常折扣的强制转人工。规则有优先级与生效期。」
 * 输出是「规则命中结果（每单命中的规则链）；规则生效记录」。
 *
 * <p><b>区块顺序对应客服真正的工作顺序</b>：
 *
 * <ol>
 *   <li><b>能力声明</b>：店铺维度当前只能按平台判（D5 未补 shop_code）、买家等级与黑名单
 *       由本服务自维护 —— 这些必须写在最前面，而不是让运营配完规则才发现「店铺不生效」。</li>
 *   <li><b>规则</b>：条件按「维度 + 运算符」两段配。下拉框已按后端同一条口径过滤，
 *       配不出「收货国家 ≥ 50」这种永远判不出结果的条件。</li>
 *   <li><b>试跑 / 判定</b>：本页最重要的区。命中链**逐层展开**到
 *       「哪个条件、拿订单的哪个字段、实际值多少」—— 蓝图加粗句要的就是这一层。
 *       未命中的规则与条件同样显示。</li>
 *   <li><b>判定记录</b>：append-only 留痕，谁在什么时候按哪条规则判的。</li>
 *   <li><b>买家画像</b>：等级与黑名单的登记入口，拉黑必须写理由。</li>
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三态齐全：任何一块没取到都不会静默显示成 0 或空数组。
 */
export function OrderReviewRuleWorkspace() {
  const [capabilities, setCapabilities] = useState<OrderReviewRuleCapabilities | null>(null);
  const [rules, setRules] = useState<ReviewRulePage | null>(null);
  const [ruleDetail, setRuleDetail] = useState<ReviewRuleDetail | null>(null);
  const [evaluations, setEvaluations] = useState<EvaluationPage | null>(null);
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const [profiles, setProfiles] = useState<BuyerProfilePage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [filters, setFilters] = useState({ keyword: '', status: '', forcedOnly: false });
  const [page, setPage] = useState(0);

  // 判定表单：订单 id + 判定时刻。时刻由用户显式给出，不取服务器当前时间。
  const [run, setRun] = useState({ orderId: '', at: new Date().toISOString() });

  // 画像表单：身份键由判定详情回显，运营复制过来就能登记等级 / 拉黑。
  const [profile, setProfile] = useState({
    buyerKey: '',
    buyerName: '',
    buyerPhone: '',
    buyerEmail: '',
    buyerLevel: 'STANDARD',
    blacklisted: false,
    blacklistReason: '',
    note: '',
  });

  const [editing, setEditing] = useState<ReviewRuleInput | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [caps, rulePage, evaluationPage, profilePage] = await Promise.all([
        fetchCapabilities(),
        fetchRules({ ...filters, page, size: PAGE_SIZE }),
        fetchEvaluations({ page: 0, size: PAGE_SIZE }),
        fetchBuyerProfiles({ page: 0, size: PAGE_SIZE }),
      ]);
      setCapabilities(caps);
      setRules(rulePage);
      setEvaluations(evaluationPage);
      setProfiles(profilePage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [filters, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const guard = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      await work();
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const openRule = useCallback(
    (ruleId: string) =>
      guard(async () => {
        setRuleDetail(await fetchRule(ruleId));
      }),
    [guard],
  );

  const openEvaluation = useCallback(
    (evaluationId: string) =>
      guard(async () => {
        setDetail(await fetchEvaluation(evaluationId));
      }),
    [guard],
  );

  const runTrial = useCallback(
    (mode: 'trial' | 'review') =>
      guard(async () => {
        const input = { evaluatedAt: new Date(run.at).toISOString(), ruleIds: [] as string[] };
        const result = mode === 'trial'
          ? await trialOrder(run.orderId, input)
          : await reviewOrder(run.orderId, input);
        setDetail(result);
        setNotice(
          mode === 'trial'
            ? '试跑完成。这是试跑结论，不是生产判定。'
            : '判定完成，已写入规则生效记录（append-only，不可改写）。',
        );
        // 判定详情回显买家身份键：运营可以直接复制去登记等级 / 拉黑，
        // 不必自己算 md5（也算不对）。
        setProfile((p) => ({
          ...p,
          buyerKey: result.orderFacts.buyerKey || p.buyerKey,
          buyerName: result.orderFacts.recipientName || p.buyerName,
          buyerPhone: result.orderFacts.recipientPhone || p.buyerPhone,
          buyerEmail: result.orderFacts.recipientEmail || p.buyerEmail,
          blacklisted: result.orderFacts.blacklisted ?? p.blacklisted,
        }));
        await load();
      }),
    [guard, load, run],
  );

  const toggleStatus = useCallback(
    (rule: ReviewRuleRow) =>
      guard(async () => {
        const disabling = rule.status === 'ACTIVE';
        const note = disabling
          ? window.prompt(
              '停用这条规则会改变之后所有订单的审单结论，必须写明为什么停用（会记在规则上）：',
            )
          : '';
        if (disabling && (note === null || note.trim() === '')) return;
        const saved = await changeRuleStatus(rule.id, {
          status: disabling ? 'DISABLED' : 'ACTIVE',
          effectiveFrom: null,
          effectiveTo: null,
          note: note ?? '',
          version: rule.version,
        });
        setNotice(saved.message);
        await load();
      }),
    [guard, load],
  );

  const submitRule = useCallback(
    (input: ReviewRuleInput, ruleId?: string) =>
      guard(async () => {
        const saved = ruleId
          ? await updateRule(ruleId, input)
          : await createRule(input);
        setNotice(saved.message);
        setEditing(null);
        await load();
      }),
    [guard, load],
  );

  const submitProfile = useCallback(
    () =>
      guard(async () => {
        const saved: BuyerProfile = await saveBuyerProfile({
          buyerKey: profile.buyerKey.trim(),
          buyerName: profile.buyerName.trim(),
          buyerPhone: profile.buyerPhone.trim(),
          buyerEmail: profile.buyerEmail.trim(),
          buyerLevel: profile.buyerLevel,
          blacklisted: profile.blacklisted,
          blacklistReason: profile.blacklistReason.trim(),
          blacklistedAt: profile.blacklisted ? new Date().toISOString() : null,
          note: profile.note.trim(),
          version: null,
        });
        setNotice(
          `买家画像已保存：${saved.buyerName || saved.buyerEmail || saved.buyerKey}` +
            `${saved.blacklisted ? '，已列入黑名单' : ''}。`,
        );
        await load();
      }),
    [guard, load, profile],
  );

  const profileBlocked = useMemo(
    () => (profile.buyerKey.trim() ? null : '身份键由判定详情回显，先跑一次判定再登记画像。'),
    [profile.buyerKey],
  );

  if (loading && !capabilities) {
    return <PageHeader title="审单规则" description="正在载入审单规则与判定记录…" />;
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="审单规则"
        description="按店铺、金额、买家等级、商品、收货国家自动通过或转人工；命中黑名单、地址异常、大额、异常折扣的强制转人工。规则有优先级与生效期，命中结果逐层可解释。"
        actions={
          <Button onClick={() => void load()} disabled={busy}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="审单规则数据加载失败">
          {error}
          <Button className="ml-3" onClick={() => void load()}>
            重试
          </Button>
        </Notice>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      )}

      {/* ------------------------------------------------ 1. 能力声明 */}
      <SectionCard
        title="能力与上下游可用性"
        description="这一栏先说清楚「哪些维度真的生效」，避免配完规则才发现店铺维度只按平台判。"
      >
        {capabilities ? (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              {capabilities.dimensions.map((d) => (
                <span
                  key={d.code}
                  className={
                    d.available
                      ? 'rounded border border-emerald-500/40 bg-emerald-500/10 px-2 py-1 text-xs'
                      : 'rounded border border-amber-500/40 bg-amber-500/10 px-2 py-1 text-xs'
                  }
                >
                  {d.label}：{d.available ? '可用' : '未接通'}
                </span>
              ))}
            </div>
            {capabilities.dimensions
              .filter((d) => !d.available && d.unavailableReason)
              .map((d) => (
                <p key={d.code} className="text-xs text-muted-foreground">
                  <strong>{d.label}</strong>：{d.unavailableReason}
                </p>
              ))}
            <p className="text-xs text-muted-foreground">{capabilities.notes}</p>
            <p className="text-xs text-muted-foreground">{capabilities.downstreamGaps}</p>
            <div className="grid gap-3 sm:grid-cols-3">
              <StatCard label="规则总数" value={String(capabilities.ruleCount)} />
              <StatCard label="生效中的规则" value={String(capabilities.activeRuleCount)} />
              <StatCard label="已登记买家画像" value={String(capabilities.buyerProfileCount)} />
            </div>
          </div>
        ) : (
          <EmptyState title="能力声明未取到" description="请刷新重试。" />
        )}
      </SectionCard>

      {/* ------------------------------------------------ 2. 规则 */}
      <SectionCard
        title="审单规则"
        description="条件按「维度 + 运算符」两段配：下拉框已按后端同一口径过滤，配不出永远判不出结果的条件。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-4">
            <FormField id="filter-keyword" label="规则编号 / 名称">
              <Input
                value={filters.keyword}
                onChange={(e) => {
                  setPage(0);
                  setFilters((f) => ({ ...f, keyword: e.target.value }));
                }}
                placeholder="模糊匹配"
              />
            </FormField>
            <FormField id="filter-status" label="状态">
              <select
                className="h-9 w-full rounded border bg-background px-2 text-sm"
                value={filters.status}
                onChange={(e) => {
                  setPage(0);
                  setFilters((f) => ({ ...f, status: e.target.value }));
                }}
              >
                {RULE_STATUS_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="filter-forced" label="只看强制转人工">
              <label className="flex h-9 items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={filters.forcedOnly}
                  onChange={(e) => {
                    setPage(0);
                    setFilters((f) => ({ ...f, forcedOnly: e.target.checked }));
                  }}
                />
                强制转人工规则
              </label>
            </FormField>
            <FormField id="filter-new-rule" label=" ">
              <Button
                onClick={() =>
                  setEditing({
                    ruleCode: '',
                    ruleName: '',
                    description: '',
                    priority: 100,
                    action: 'MANUAL_REVIEW',
                    forcedManual: false,
                    matchMode: 'ALL',
                    effectiveFrom: new Date().toISOString(),
                    effectiveTo: '',
                    capabilityNote: '',
                    note: '',
                    conditions: [
                      {
                        seqNo: 1,
                        dimension: 'AMOUNT',
                        operator: 'GTE',
                        valueSet: [],
                        valueNumeric: 500,
                        description: '',
                      },
                    ],
                    version: null,
                  })
                }
                disabled={busy}
              >
                新建规则
              </Button>
            </FormField>
          </div>

          {editing && (
            <RuleEditor
              value={editing}
              onChange={setEditing}
              onSubmit={() => void submitRule(editing, undefined)}
              onCancel={() => setEditing(null)}
              busy={busy}
            />
          )}

          {ruleDetail && (
            <Notice tone="info" title={`规则 ${ruleDetail.rule.ruleCode} 的条件`}>
              <ul className="mt-1 space-y-1 text-xs">
                {ruleDetail.conditions.map((c) => (
                  <li key={c.id}>
                    {c.seqNo}. {ruleConditionSummary(c)}
                  </li>
                ))}
                {ruleDetail.conditions.length === 0 && <li>（还没有条件）</li>}
              </ul>
              <p className="mt-1 text-xs">{ruleDetail.notes}</p>
              <Button className="mt-2" size="sm" onClick={() => setRuleDetail(null)}>
                收起
              </Button>
            </Notice>
          )}

          {!rules || rules.items.length === 0 ? (
            <EmptyState
              title="还没有审单规则"
              description="新建一条规则，或先用「试跑」看看没有规则时订单会被怎么判（默认转人工）。"
            />
          ) : (
            <div className="space-y-2">
              {rules.items.map((rule) => (
                <div key={rule.id} className="rounded border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong className="text-sm">{rule.ruleName}</strong>
                    <code className="text-xs text-muted-foreground">{rule.ruleCode}</code>
                    <span className="rounded border px-2 py-0.5 text-xs">
                      优先级 {rule.priority}
                    </span>
                    <span className="rounded border px-2 py-0.5 text-xs">{rule.actionLabel}</span>
                    {rule.forcedManual && (
                      <span className="flex items-center gap-1 rounded border border-amber-500/40 px-2 py-0.5 text-xs">
                        <ShieldAlert className="h-3 w-3" />
                        强制转人工
                      </span>
                    )}
                    <span
                      className={`rounded border px-2 py-0.5 text-xs ${
                        statusTone(rule.status) === 'success'
                          ? 'border-emerald-500/40 text-emerald-600'
                          : 'text-muted-foreground'
                      }`}
                    >
                      {rule.statusLabel}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">{effectiveText(rule)}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {rule.conditionCount} 个条件：
                    {rule.dimensionSummary
                      ? rule.dimensionSummary
                          .split(',')
                          .map((d) => DIMENSION_LABELS[d] ?? d)
                          .join('、')
                      : '（无）'}
                    {rule.matchMode === 'ANY' ? '（任一满足即命中）' : '（全部满足才命中）'}
                  </p>
                  <div className="mt-2 flex gap-2">
                    <Button size="sm" variant="outline" onClick={() => void openRule(rule.id)}>
                      看条件
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || !canActivate(rule)}
                      title={
                        rule.conditionCount === 0
                          ? '零条件的规则不能启用：ANY 会放行每一张单，ALL 永不命中'
                          : undefined
                      }
                      onClick={() => void toggleStatus(rule)}
                    >
                      {rule.status === 'ACTIVE' ? '停用' : '启用'}
                    </Button>
                  </div>
                </div>
              ))}
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>
                  共 {rules.total} 条 · 生效中 {rules.activeCount} · 强制 {rules.forcedCount} ·
                  此刻在生效期内 {rules.effectiveNowCount}
                </span>
                <span className="flex gap-2">
                  <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={(page + 1) * PAGE_SIZE >= rules.total}
                    onClick={() => setPage(page + 1)}
                  >
                    下一页
                  </Button>
                </span>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 3. 试跑 / 判定 */}
      <SectionCard
        title="试跑与判定"
        description="这是本页最重要的一块：命中链逐层展开到「哪个条件、拿订单的哪个字段、实际值多少」，未命中的规则与条件同样显示。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-3">
            <FormField id="run-order-id" label="订单 id（uuid）">
              <Input
                value={run.orderId}
                onChange={(e) => setRun((r) => ({ ...r, orderId: e.target.value }))}
                placeholder="sales_order.id"
              />
            </FormField>
            <FormField id="run-at" label="判定时刻（timestamptz）">
              <Input
                type="datetime-local"
                value={run.at.slice(0, 16)}
                onChange={(e) =>
                  setRun((r) => ({ ...r, at: new Date(e.target.value).toISOString() }))
                }
              />
            </FormField>
            <FormField id="run-actions" label=" ">
              <div className="flex gap-2">
                <Button
                  disabled={busy || !run.orderId}
                  onClick={() => void runTrial('trial')}
                  title="试跑不产生生产结论，可以点名还没启用的草稿规则"
                >
                  试跑
                </Button>
                <Button
                  disabled={busy || !run.orderId}
                  onClick={() => void runTrial('review')}
                  title="真实判定，会写入规则生效记录（append-only）"
                >
                  <Scale className="mr-2 h-4 w-4" />
                  判定
                </Button>
              </div>
            </FormField>
          </div>

          {detail ? <ReviewDetailPanel detail={detail} /> : (
            <EmptyState
              title="还没有判定结果"
              description="填一个本租户的订单 id，点「试跑」看规则会怎么判；确认无误后再点「判定」写入生效记录。"
            />
          )}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 4. 判定记录 */}
      <SectionCard
        title="规则生效记录"
        description="append-only 留痕：谁在什么时候按哪条规则判的。判定一经落库就不可改写。"
      >
        {!evaluations || evaluations.items.length === 0 ? (
          <EmptyState title="还没有判定记录" description="跑一次判定后会出现在这里。" />
        ) : (
          <div className="space-y-2">
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard label="总条数" value={String(evaluations.total)} />
              <StatCard label="自动通过" value={String(evaluations.autoApproveCount)} />
              <StatCard label="转人工" value={String(evaluations.manualReviewCount)} />
              <StatCard label="强制转人工触发" value={String(evaluations.forcedCount)} />
            </div>
            {evaluations.items.map((d) => (
              <button
                key={d.id}
                type="button"
                onClick={() => void openEvaluation(d.id)}
                className="w-full rounded border p-3 text-left"
              >
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <code>{d.orderNo}</code>
                  <span
                    className={`rounded border px-2 py-0.5 text-xs ${
                      decisionTone(d.decision) === 'success'
                        ? 'border-emerald-500/40 text-emerald-600'
                        : 'border-amber-500/40 text-amber-600'
                    }`}
                  >
                    {d.decisionLabel}
                  </span>
                  <span className="rounded border px-2 py-0.5 text-xs">{modeText(d)}</span>
                  <span className="text-xs text-muted-foreground">{d.evaluatedAt}</span>
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{decisionCounts(d)}</p>
              </button>
            ))}
            <p className="text-xs text-muted-foreground">
              其中「无规则命中默认转人工」{evaluations.noRuleMatchedCount} 条 —— 审单不给隐含默认值，
              自动通过必须由某条规则明确给出。
            </p>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 5. 买家画像 */}
      <SectionCard
        title="买家画像（等级与黑名单）"
        description="15 客户与 16 合规未交付，等级与黑名单由本服务自维护。未登记 = 等级未知，不是 STANDARD。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-3">
            <FormField id="profile-key" label="买家身份键（32 位 md5）">
              <Input
                value={profile.buyerKey}
                onChange={(e) => setProfile((p) => ({ ...p, buyerKey: e.target.value }))}
                placeholder="跑一次判定后自动回显"
              />
            </FormField>
            <FormField id="profile-level" label="买家等级">
              <select
                className="h-9 w-full rounded border bg-background px-2 text-sm"
                value={profile.buyerLevel}
                onChange={(e) => setProfile((p) => ({ ...p, buyerLevel: e.target.value }))}
              >
                {BUYER_LEVELS.map((level) => (
                  <option key={level} value={level}>
                    {level}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="profile-name" label="姓名 / 电话 / 邮箱（客服要能对得上人）">
              <Input
                value={profile.buyerName}
                onChange={(e) => setProfile((p) => ({ ...p, buyerName: e.target.value }))}
                placeholder={profile.buyerEmail || profile.buyerPhone || '可留空'}
              />
            </FormField>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={profile.blacklisted}
              onChange={(e) => setProfile((p) => ({ ...p, blacklisted: e.target.checked }))}
            />
            列入黑名单（拉黑必须写理由 —— 没有理由的黑名单，客服无法向买家解释）
          </label>
          {profile.blacklisted && (
            <FormField id="profile-blacklist-reason" label="拉黑理由">
              <Input
                value={profile.blacklistReason}
                onChange={(e) => setProfile((p) => ({ ...p, blacklistReason: e.target.value }))}
                placeholder="例如：同一收货地址短期内下单 14 次且频繁改址"
              />
            </FormField>
          )}
          <Button disabled={busy || !!profileBlocked} onClick={() => void submitProfile()}>
            保存画像
          </Button>
          {profileBlocked && <p className="text-xs text-muted-foreground">{profileBlocked}</p>}

          {profiles && profiles.items.length > 0 && (
            <div className="space-y-2">
              {profiles.items.map((p) => (
                <div key={p.id} className="rounded border p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong>{p.buyerName || p.buyerEmail || p.buyerKey.slice(0, 8)}</strong>
                    <span className="rounded border px-2 py-0.5 text-xs">
                      {buyerLevelText(p.buyerLevel)}
                    </span>
                    {p.blacklisted && (
                      <span className="rounded border border-amber-500/40 px-2 py-0.5 text-xs">
                        黑名单
                      </span>
                    )}
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {p.buyerEmail || '（无邮箱）'} · {p.buyerPhone || '（无电话）'}
                  </p>
                  {p.blacklistReason && (
                    <p className="mt-1 text-xs">理由：{p.blacklistReason}</p>
                  )}
                </div>
              ))}
              <p className="text-xs text-muted-foreground">
                共 {profiles.total} 人 · 黑名单 {profiles.blacklistedCount} 人
              </p>
            </div>
          )}
        </div>
      </SectionCard>
    </div>
  );
}

/**
 * 命中链面板：结论 → 订单事实 → 逐条规则（含未命中）→ 逐条件到订单字段。
 *
 * <p>三层都必须显示。少任何一层，客服都答不出「为什么这单被拦了」：
 * 只给结论没有规则，只给规则没有字段与实际值。
 */
function ReviewDetailPanel({ detail }: { detail: ReviewDetail }) {
  const d = detail.decision;
  const f = detail.orderFacts;
  return (
    <div className="space-y-3">
      <div className="rounded border p-3">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`rounded border px-2 py-0.5 text-sm ${
              decisionTone(d.decision) === 'success'
                ? 'border-emerald-500/40 text-emerald-600'
                : 'border-amber-500/40 text-amber-600'
            }`}
          >
            {d.decisionLabel}
          </span>
          <span className="rounded border px-2 py-0.5 text-xs">{modeText(d)}</span>
          <code className="text-xs">{f.orderNo}</code>
          <span className="text-xs text-muted-foreground">{d.evaluatedAt}</span>
        </div>
        <p className="mt-2 text-sm">{decisionHeadline(d)}</p>
        <p className="mt-1 text-xs text-muted-foreground">{decisionCounts(d)}</p>
      </div>

      <div className="rounded border p-3 text-xs">
        <p className="font-medium">判定时用到的订单事实</p>
        <ul className="mt-1 space-y-0.5 text-muted-foreground">
          <li>
            来源 {f.orderSource} · 币种 {f.currencyCode} · 订单总额 {money(f.orderTotal)} ·
            折扣 {money(f.discountAmount)} / 小计 {money(f.subtotalAmount)} · 折扣率{' '}
            {discountRateText(f.discountRatePercent)}
          </li>
          <li>
            收货国家 {f.shippingCountry || '（未取到）'} · 收件人 {f.recipientName || '（未取到）'} ·{' '}
            {f.recipientPhone || '（无电话）'} · {f.recipientEmail || '（无邮箱）'}
          </li>
          <li>
            买家等级 {buyerLevelText(f.buyerLevel)} · 黑名单 {blacklistText(f.blacklisted)} ·
            身份键 {f.buyerKey}
          </li>
          <li>地址异常：{anomalyText(f.addressAnomalyCodes)}</li>
          <li>
            SKU（{f.skuCodes.length} 个 / {f.lineCount} 行 / {f.totalQty} 件）：
            {f.skuCodes.join('、') || '（无）'}
          </li>
        </ul>
      </div>

      <div className="space-y-2">
        <p className="text-sm font-medium">
          命中链（{detail.hits.length} 条参与判定，含未命中）
        </p>
        {detail.hits.length === 0 ? (
          <p className="text-xs text-muted-foreground">
            该时刻没有任何生效中的规则 → 默认转人工。自动通过必须由某条规则明确给出。
          </p>
        ) : (
          detail.hits.map((hit) => (
            <div
              key={hit.id}
              className={`rounded border p-3 ${
                hit.matched
                  ? hit.forcedManual
                    ? 'border-amber-500/40'
                    : 'border-emerald-500/30'
                  : 'opacity-80'
              }`}
            >
              <p className="text-sm">{hitSummary(hit)}</p>
              <ul className="mt-2 space-y-2">
                {hit.conditions.map((c) => {
                  const lines = hitConditionLines(c);
                  return (
                    <li key={c.seqNo} className="text-xs">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="rounded border px-1.5 py-0.5">
                          {c.satisfied ? '满足' : '不满足'}
                        </span>
                        <span>{conditionSummary(c)}</span>
                      </div>
                      <div className="mt-1 text-muted-foreground">
                        {lines.map((l) => (
                          <span key={l.field} className="mr-3">
                            {l.field} = {l.value}
                          </span>
                        ))}
                      </div>
                      <p className="mt-1">{c.explanation}</p>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))
        )}
      </div>

      <p className="text-xs text-muted-foreground">{detail.notes}</p>
    </div>
  );
}

/**
 * 规则编辑器。
 *
 * <p>条件按「维度 + 运算符 + 取值」三段配：切换维度时**自动**把运算符收敛到后端允许的那几个
 * （与 V149 的 operator_fits_dimension CHECK 同一口径），并把序号重排 —— 序号是命中明细的
 * 排序依据，重复会让解释顺序不可复现。
 */
function RuleEditor({
  value,
  onChange,
  onSubmit,
  onCancel,
  busy,
}: {
  value: ReviewRuleInput;
  onChange: (next: ReviewRuleInput) => void;
  onSubmit: () => void;
  onCancel: () => void;
  busy: boolean;
}) {
  const conditions = value.conditions.map(toConditionInput);

  const setCondition = (index: number, patch: Partial<(typeof conditions)[number]>) => {
    const next = conditions.map((c, i) => (i === index ? { ...c, ...patch } : c));
    onChange({ ...value, conditions: next.map((c, i) => ({ ...c, seqNo: i + 1 })) });
  };

  return (
    <div className="space-y-3 rounded border p-3">
      <div className="grid gap-3 sm:grid-cols-4">
        <FormField id="rule-code" label="规则编号（租户内唯一，排序依据）">
          <Input
            value={value.ruleCode}
            onChange={(e) => onChange({ ...value, ruleCode: e.target.value })}
            placeholder="R-BIG-AMOUNT"
          />
        </FormField>
        <FormField id="rule-name" label="规则名称">
          <Input
            value={value.ruleName}
            onChange={(e) => onChange({ ...value, ruleName: e.target.value })}
          />
        </FormField>
        <FormField id="rule-priority" label="优先级（数字大的先判定）">
          <Input
            type="number"
            value={value.priority}
            onChange={(e) => onChange({ ...value, priority: Number(e.target.value) })}
          />
        </FormField>
        <FormField id="rule-action" label="动作">
          <select
            className="h-9 w-full rounded border bg-background px-2 text-sm"
            value={value.action ?? 'MANUAL_REVIEW'}
            onChange={(e) =>
              onChange({ ...value, action: e.target.value as ReviewRuleInput['action'] })
            }
          >
            <option value="AUTO_APPROVE">自动通过</option>
            <option value="MANUAL_REVIEW">转人工</option>
          </select>
        </FormField>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        <FormField id="rule-match-mode" label="条件组合">
          <select
            className="h-9 w-full rounded border bg-background px-2 text-sm"
            value={value.matchMode ?? 'ALL'}
            onChange={(e) => onChange({ ...value, matchMode: e.target.value })}
          >
            <option value="ALL">全部满足才命中</option>
            <option value="ANY">任一满足即命中</option>
          </select>
        </FormField>
        <FormField id="rule-from" label="生效起（必填）">
          <Input
            type="datetime-local"
            value={value.effectiveFrom.slice(0, 16)}
            onChange={(e) =>
              onChange({ ...value, effectiveFrom: new Date(e.target.value).toISOString() })
            }
          />
        </FormField>
        <FormField id="rule-to" label="生效止（留空 = 长期有效）">
          <Input
            type="datetime-local"
            value={value.effectiveTo ? value.effectiveTo.slice(0, 16) : ''}
            onChange={(e) =>
              onChange({
                ...value,
                effectiveTo: e.target.value ? new Date(e.target.value).toISOString() : '',
              })
            }
          />
        </FormField>
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={value.forcedManual ?? false}
          onChange={(e) => onChange({ ...value, forcedManual: e.target.checked })}
        />
        强制转人工（命中即压过任何自动通过规则，效力高于优先级）
      </label>

      <div className="space-y-2">
        {conditions.map((c, index) => {
          const ops = operatorsFor(c.dimension);
          const isSet = c.operator === 'IN' || c.operator === 'NOT_IN';
          return (
            <div key={index} className="grid gap-2 sm:grid-cols-4">
              <FormField id={`cond-${index}-dimension`} label={`条件 ${index + 1} · 维度`}>
                <select
                  className="h-9 w-full rounded border bg-background px-2 text-sm"
                  value={c.dimension}
                  onChange={(e) => {
                    const dim = e.target.value;
                    // 换维度时把运算符收敛到该维度允许的那几个：
                    // 留着旧运算符会配出「收货国家 ≥ 50」这种永远判不出结果的条件。
                    const allowed = operatorsFor(dim);
                    const operator = allowed.includes(c.operator) ? c.operator : allowed[0]!;
                    setCondition(index, {
                      dimension: dim as typeof c.dimension,
                      operator: operator as typeof c.operator,
                      valueSet: isSetFor(dim) ? c.valueSet : [],
                      valueNumeric: isSetFor(dim) ? null : c.valueNumeric,
                    });
                  }}
                >
                  {Object.entries(DIMENSION_LABELS).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id={`cond-${index}-operator`} label="运算符">
                <select
                  className="h-9 w-full rounded border bg-background px-2 text-sm"
                  value={c.operator}
                  onChange={(e) =>
                    setCondition(index, { operator: e.target.value as typeof c.operator })
                  }
                >
                  {ops.map((op) => (
                    <option key={op} value={op}>
                      {OPERATOR_LABELS[op] ?? op}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id={`cond-${index}-value`} label={isSet ? '取值（逗号分隔）' : '阈值'}>
                {isSet ? (
                  <Input
                    value={(c.valueSet ?? []).join(',')}
                    onChange={(e) =>
                      setCondition(index, {
                        valueSet: e.target.value
                          .split(',')
                          .map((v) => v.trim())
                          .filter(Boolean),
                      })
                    }
                    placeholder={placeholderFor(c.dimension)}
                  />
                ) : (
                  <Input
                    type="number"
                    value={c.valueNumeric ?? ''}
                    onChange={(e) =>
                      setCondition(index, {
                        valueNumeric: e.target.value === '' ? null : Number(e.target.value),
                      })
                    }
                  />
                )}
              </FormField>
              <FormField id={`cond-${index}-actions`} label=" ">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={conditions.length <= 1}
                  onClick={() =>
                    onChange({
                      ...value,
                      conditions: conditions
                        .filter((_, i) => i !== index)
                        .map((x, i) => ({ ...x, seqNo: i + 1 })),
                    })
                  }
                >
                  删除此条件
                </Button>
              </FormField>
            </div>
          );
        })}
        <Button
          size="sm"
          variant="outline"
          disabled={conditions.length >= 20}
          onClick={() =>
            onChange({
              ...value,
              conditions: [
                ...conditions,
                {
                  seqNo: conditions.length + 1,
                  dimension: 'SHOP',
                  operator: 'IN',
                  valueSet: ['AMAZON'],
                  valueNumeric: null,
                  description: '',
                },
              ],
            })
          }
        >
          添加条件
        </Button>
      </div>

      <FormField id="rule-note" label="备注（停用时必填）">
        <Input value={value.note ?? ''} onChange={(e) => onChange({ ...value, note: e.target.value })} />
      </FormField>

      <div className="flex gap-2">
        <Button disabled={busy || !value.ruleCode || !value.ruleName} onClick={onSubmit}>
          保存为草稿
        </Button>
        <Button variant="outline" onClick={onCancel}>
          取消
        </Button>
      </div>
      <p className="flex items-center gap-1 text-xs text-muted-foreground">
        <Filter className="h-3 w-3" />
        草稿不参与判定：保存后先在上方「试跑」里拿真实订单验一遍，再启用。
      </p>
    </div>
  );
}

/** 该维度是不是集合型（决定用取值还是用阈值）。与 operatorsFor 同一族口径。 */
function isSetFor(dimension: string): boolean {
  const ops = operatorsFor(dimension);
  return ops.includes('IN') || ops.includes('NOT_IN');
}

function placeholderFor(dimension: string): string {
  switch (dimension) {
    case 'SHOP':
      return ORDER_SOURCES.slice(0, 3).join(',');
    case 'BUYER_LEVEL':
      return 'VIP,STRATEGIC';
    case 'ADDRESS_ANOMALY':
      return 'RESERVED_POSTCODE,MISSING_PHONE';
    case 'SHIPPING_COUNTRY':
      return 'US,CA';
    default:
      return 'SKU-001,SKU-002';
  }
}

export { RuleEditor, ReviewDetailPanel };
