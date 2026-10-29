'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  cancelAdBiddingDispatch,
  changeAdBiddingStatus,
  createAdBiddingAdjustment,
  createAdBiddingPolicy,
  dateText,
  dispatchActionLabel,
  dispatchStatusLabel,
  dispatchStatusTone,
  dynamicStrategyLabel,
  dynamicStrategyTone,
  fetchAdBiddingAdjustments,
  fetchAdBiddingCapabilities,
  fetchAdBiddingDispatches,
  fetchAdBiddingKeywords,
  fetchAdBiddingOptions,
  fetchAdBiddingPolicies,
  fetchAdBiddingPolicy,
  fetchAdBiddingSuggestion,
  fetchAdBiddingSuggestions,
  adjustmentStatusLabel,
  adjustmentStatusTone,
  generateAdBiddingSuggestion,
  guardrailLabel,
  money,
  multiplierText,
  policyStatusLabel,
  policyStatusTone,
  profitStatusLabel,
  ratioText,
  runModeLabel,
  scopeLabel,
  suggestionStatusLabel,
  timestampText,
  validateBiddingPolicy,
} from './ad-bidding-model';
import type {
  AdBiddingAdjustmentSummary,
  AdBiddingAdjustmentView,
  AdBiddingCapabilities,
  AdBiddingDispatchView,
  AdBiddingKeywordRow,
  AdBiddingOptions,
  AdBiddingPolicyPage,
  AdBiddingPolicyView,
  AdBiddingSuggestionSummary,
  AdBiddingSuggestionView,
} from './ad-bidding-model';

/**
 * 14.2.1.1 竞价管理。
 *
 * <p>🔴 页面结构按蓝图的职责句排：
 *
 * <ol>
 *   <li><b>能力声明</b> —— 哪些做得了、哪些**做不了**（做不了的写清责任模块与影响）。
 *   <li><b>竞价策略</b> —— 🔴 动态竞价策略**三档枚举**（不是自由文本）+
 *       🔴 竞价范围上限（闸①）+ 单次调整额度（闸②）+ 目标 ACOS/ROAS（二选一）。
 *   <li><b>竞价候选清单</b> —— 关键词的当前竞价、窗口表现、归到 SKU 的结论。
 *   <li><b>目标 ACOS 反推建议</b> —— SKU 级口径行（ACOS/ROAS/盈亏平衡）+ 关键词级建议竞价。
 *   <li><b>批量调整任务</b> —— 逐条明细含**被哪道闸拦下**。
 *   <li><b>待下发队列</b> —— 🔴 写「待下发」而不是「已生效」；唯一动作是「撤销」。
 * </ol>
 *
 * <p>🔴 **页面不做任何算术**：建议竞价、倍率、盈亏平衡 ACOS、上调合计全是服务端
 * {@code BigDecimal} 的事。
 */
export function AdBiddingWorkspace() {
  const [capabilities, setCapabilities] = useState<AdBiddingCapabilities | null>(null);
  const [options, setOptions] = useState<AdBiddingOptions | null>(null);
  const [policies, setPolicies] = useState<AdBiddingPolicyPage | null>(null);
  const [policy, setPolicy] = useState<AdBiddingPolicyView | null>(null);
  const [keywords, setKeywords] = useState<AdBiddingKeywordRow[]>([]);
  const [keywordTotal, setKeywordTotal] = useState(0);
  const [suggestions, setSuggestions] = useState<AdBiddingSuggestionSummary[]>([]);
  const [suggestion, setSuggestion] = useState<AdBiddingSuggestionView | null>(null);
  const [adjustments, setAdjustments] = useState<AdBiddingAdjustmentSummary[]>([]);
  const [adjustment, setAdjustment] = useState<AdBiddingAdjustmentView | null>(null);
  const [dispatches, setDispatches] = useState<AdBiddingDispatchView[]>([]);

  const [shopCode, setShopCode] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');

  // ---- 新建策略表单 ----
  const [policyCode, setPolicyCode] = useState('');
  const [policyName, setPolicyName] = useState('');
  const [strategy, setStrategy] = useState('DOWN_ONLY');
  const [currency, setCurrency] = useState('USD');
  const [scope, setScope] = useState('SHOP');
  const [targetCampaignId, setTargetCampaignId] = useState('');
  const [minBid, setMinBid] = useState('');
  const [maxBid, setMaxBid] = useState('');
  const [capRatio, setCapRatio] = useState('');
  const [capAmount, setCapAmount] = useState('');
  const [targetAcos, setTargetAcos] = useState('0.25');
  const [targetRoas, setTargetRoas] = useState('');
  const [dryRun, setDryRun] = useState(true);
  const [formErrors, setFormErrors] = useState<string[]>([]);

  // ---- 手工调价表单 ----
  const [adjustBid, setAdjustBid] = useState('');
  const [adjustKeywordId, setAdjustKeywordId] = useState('');
  const [applyDryRun, setApplyDryRun] = useState(true);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [policyPage, keywordPage, suggestionPage, adjustmentPage, dispatchPage] =
        await Promise.all([
          fetchAdBiddingPolicies({
            shopCode: shopCode || undefined,
            status: status || undefined,
            keyword: keyword || undefined,
            page: 0,
            size: 50,
          }),
          fetchAdBiddingKeywords({
            shopCode: shopCode || undefined,
            keyword: keyword || undefined,
            page: 0,
            size: 50,
          }),
          fetchAdBiddingSuggestions({ page: 0, size: 20 }),
          fetchAdBiddingAdjustments({ page: 0, size: 20 }),
          fetchAdBiddingDispatches({ shopCode: shopCode || undefined, page: 0, size: 50 }),
        ]);
      setPolicies(policyPage);
      setKeywords(keywordPage.items);
      setKeywordTotal(keywordPage.total);
      setSuggestions(suggestionPage.items);
      setAdjustments(adjustmentPage.items);
      setDispatches(dispatchPage.items);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [shopCode, status, keyword]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchAdBiddingCapabilities(),
          fetchAdBiddingOptions(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const selectedShop = useMemo(
    () => options?.shops.find((s) => s.value === shopCode) ?? null,
    [options, shopCode],
  );

  async function onCreatePolicy() {
    setFormErrors([]);
    setError('');
    setNotice('');
    const errors = validateBiddingPolicy({
      policyCode,
      policyName,
      shopCode,
      dynamicBiddingStrategy: strategy,
      currency,
      scope,
      targetCampaignId,
      minBid,
      maxBid,
      singleAdjustCapRatio: capRatio,
      singleAdjustCapAmount: capAmount,
      targetAcos,
      targetRoas,
    });
    if (errors.length > 0) {
      setFormErrors(errors);
      return;
    }
    setBusy(true);
    try {
      const created = await createAdBiddingPolicy({
        policyCode,
        policyName,
        platformId: selectedShop?.note ?? '',
        shopCode,
        dynamicBiddingStrategy: strategy,
        currency,
        scope,
        targetCampaignId,
        minBid: minBid ? Number(minBid) : null,
        maxBid: maxBid ? Number(maxBid) : null,
        singleAdjustCapRatio: capRatio ? Number(capRatio) : null,
        singleAdjustCapAmount: capAmount ? Number(capAmount) : null,
        targetAcos: targetAcos ? Number(targetAcos) : null,
        targetRoas: targetRoas ? Number(targetRoas) : null,
        dryRun,
        note: '页面新建',
      });
      setNotice(`策略「${created.policyName}」已建成（草稿）。激活后会进**待下发队列**。`);
      setPolicyCode('');
      setPolicyName('');
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenPolicy(id: string) {
    setBusy(true);
    setError('');
    try {
      setPolicy(await fetchAdBiddingPolicy(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onChangeStatus(next: string) {
    if (!policy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const view = await changeAdBiddingStatus(policy.id, next, '页面操作');
      setPolicy(view);
      setNotice(
        next === 'ACTIVE'
          ? '策略已激活。🔴 平台广告 API 未交付 ⇒ 动态竞价策略**待下发**，界面不写「已生效」。'
          : `策略状态已改为 ${policyStatusLabel(next)}。`,
      );
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onGenerateSuggestion() {
    if (!policy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const batch = await generateAdBiddingSuggestion({
        policyId: policy.id,
        from: '',
        to: '',
        idempotencyKey: `page-${policy.id}-${Date.now()}`,
        note: '页面生成',
      });
      setSuggestion(batch);
      setSuggestions(
        (await fetchAdBiddingSuggestions({ policyId: policy.id, page: 0, size: 20 })).items,
      );
      if (batch.status !== 'READY') {
        setNotice(
          `建议批次已生成（${suggestionStatusLabel(batch.status)}）：` +
            `${batch.suggestedCount} 个 SKU 给出建议，${batch.rejectedCount} 个算不出来。`,
        );
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenSuggestion(id: string) {
    setBusy(true);
    setError('');
    try {
      setSuggestion(await fetchAdBiddingSuggestion(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onAdjust(input: {
    suggestionId?: string;
    targets?: { keywordId: string; bidValue: number | null }[];
  }) {
    if (!policy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await createAdBiddingAdjustment({
        policyId: policy.id,
        suggestionId: input.suggestionId ?? '',
        currency: policy.currency,
        dryRun: applyDryRun,
        targets: input.targets ?? [],
        idempotencyKey: `page-${policy.id}-${Date.now()}`,
        note: '页面提交',
      });
      setAdjustment(result);
      setAdjustments(
        (await fetchAdBiddingAdjustments({ policyId: policy.id, page: 0, size: 20 })).items,
      );
      setNotice(
        result.runMode === 'DRY_RUN'
          ? `试运行：${result.appliedCount} 条「本来会变成多少」（**没有真的改**）。`
          : `已提交：本地改了 ${result.appliedCount} 条，失败 ${result.failedCount} 条。` +
              '🔴 平台广告 API 未交付 ⇒ 变更**待下发**，界面不写「已生效」。',
      );
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onCancelDispatch(id: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const cancelled = await cancelAdBiddingDispatch(id, '页面撤销');
      setDispatches(
        (await fetchAdBiddingDispatches({ shopCode: shopCode || undefined, page: 0, size: 50 }))
          .items,
      );
      setNotice(
        `已撤销一条待下发（${cancelled.cancelReason}）。` +
          '🔴 **撤销 = 停止下发，不等于回滚**：本地竞价已经改过了，不会被改回去。',
      );
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">竞价管理</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          动态竞价策略选择（<strong>只增只降 / 只降低 / 只提高</strong>三档）、手动竞价调整、
          竞价范围上限，以及按<strong>商品/SKU 的目标 ACOS 反推竞价建议</strong>。 目标 ACOS 反推是
          <strong>广告与利润打通的接口</strong>：没有利润数据，投放只能看平台给的建议，
          无法判断是否真的赚钱。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {notice ? <p className="text-sm">{notice}</p> : null}

      {loading && !policies ? (
        <p className="text-muted-foreground text-sm">正在加载竞价管理…</p>
      ) : null}

      {/* ============================================================ 能力声明 */}
      {capabilities ? (
        <section className="flex flex-col gap-2">
          <h2 className="text-lg font-medium">能力声明</h2>
          <div className="flex flex-col gap-1 text-sm">
            {capabilities.dimensions.map((dimension) => (
              <p key={dimension.dimension} className="flex flex-col">
                <span>
                  <strong>{dimension.description}</strong>
                  {dimension.available ? (
                    <span className="text-emerald-600"> · 已交付</span>
                  ) : (
                    <span className="text-amber-600">
                      {' '}
                      · 未交付（责任模块：{dimension.ownerModule}）
                    </span>
                  )}
                </span>
                {dimension.available ? null : (
                  <span className="text-muted-foreground text-xs">{dimension.impact}</span>
                )}
              </p>
            ))}
          </div>
          <details className="text-muted-foreground text-xs">
            <summary className="cursor-pointer">
              口径说明（策略 / 两道闸 / 比率 / 利润 / 上游 / 待下发 / 边界）
            </summary>
            <ul className="flex list-disc flex-col gap-1 pl-5">
              <li>{capabilities.strategyNote}</li>
              <li>{capabilities.guardrailNote}</li>
              <li>{capabilities.ratioNote}</li>
              <li>{capabilities.profitNote}</li>
              <li>{capabilities.upstreamNote}</li>
              <li>{capabilities.dispatchNote}</li>
              <li>{capabilities.boundaryNote}</li>
            </ul>
          </details>
        </section>
      ) : null}

      {/* ============================================================ 新建策略 */}
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">新建竞价策略</h2>
        <div className="flex flex-wrap items-end gap-3 text-sm">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">策略编码</span>
            <input
              className="rounded border px-2 py-1"
              value={policyCode}
              onChange={(e) => setPolicyCode(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">策略名</span>
            <input
              className="rounded border px-2 py-1"
              value={policyName}
              onChange={(e) => setPolicyName(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">店铺</span>
            <select
              className="rounded border px-2 py-1"
              value={shopCode}
              onChange={(e) => setShopCode(e.target.value)}
            >
              <option value="">请选择</option>
              {(options?.shops ?? []).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">动态竞价策略（三档）</span>
            <select
              className="rounded border px-2 py-1"
              value={strategy}
              onChange={(e) => setStrategy(e.target.value)}
            >
              {(options?.dynamicStrategies ?? []).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">币种</span>
            <input
              className="rounded border px-2 py-1"
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">作用范围</span>
            <select
              className="rounded border px-2 py-1"
              value={scope}
              onChange={(e) => setScope(e.target.value)}
            >
              <option value="SHOP">店铺级</option>
              <option value="CAMPAIGN">活动级</option>
            </select>
          </label>
          {scope === 'CAMPAIGN' ? (
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground">目标活动 id</span>
              <input
                className="rounded border px-2 py-1"
                value={targetCampaignId}
                onChange={(e) => setTargetCampaignId(e.target.value)}
              />
            </label>
          ) : null}
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">闸① 竞价范围下限</span>
            <input
              className="rounded border px-2 py-1"
              value={minBid}
              onChange={(e) => setMinBid(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">闸① 竞价范围上限</span>
            <input
              className="rounded border px-2 py-1"
              value={maxBid}
              onChange={(e) => setMaxBid(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">闸② 单次调整比例额度</span>
            <input
              className="rounded border px-2 py-1"
              value={capRatio}
              onChange={(e) => setCapRatio(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">闸② 单次调整金额额度</span>
            <input
              className="rounded border px-2 py-1"
              value={capAmount}
              onChange={(e) => setCapAmount(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">目标 ACOS（比例）</span>
            <input
              className="rounded border px-2 py-1"
              value={targetAcos}
              onChange={(e) => setTargetAcos(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">目标 ROAS（比例，与 ACOS 二选一）</span>
            <input
              className="rounded border px-2 py-1"
              value={targetRoas}
              onChange={(e) => setTargetRoas(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">试运行</span>
            <input type="checkbox" checked={dryRun} onChange={(e) => setDryRun(e.target.checked)} />
          </label>
          <button
            className="rounded border px-3 py-1 disabled:opacity-50"
            disabled={busy}
            onClick={() => void onCreatePolicy()}
          >
            新建策略
          </button>
        </div>
        {formErrors.length > 0 ? (
          <ul className="text-destructive flex list-disc flex-col gap-1 pl-5 text-sm">
            {formErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {options ? (
          <p className="text-muted-foreground text-xs">
            🔴 14.1.3.1 已交付写路径的护栏上限（**继承，不另设**）：单次幅度 ±
            {(options.upstreamGuardrails.maxBidDeltaRatio * 100).toFixed(0)}%、单条绝对{' '}
            {money(options.upstreamGuardrails.maxBidAbsolute)}、单次总上调{' '}
            {money(options.upstreamGuardrails.maxTotalBidIncrease)}。
          </p>
        ) : null}
      </section>

      {/* ============================================================ 策略清单 */}
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">竞价策略</h2>
        <div className="flex flex-wrap items-end gap-3 text-sm">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">店铺</span>
            <input
              className="rounded border px-2 py-1"
              value={shopCode}
              onChange={(e) => setShopCode(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">状态</span>
            <input
              className="rounded border px-2 py-1"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">关键词</span>
            <input
              className="rounded border px-2 py-1"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </label>
        </div>
        {policies && policies.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有竞价策略。先在上面建一条。</p>
        ) : null}
        {policies ? (
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-muted-foreground">
                <th className="p-2">策略</th>
                <th className="p-2">店铺</th>
                <th className="p-2">动态竞价策略</th>
                <th className="p-2">目标 ACOS / ROAS</th>
                <th className="p-2">闸① 竞价范围</th>
                <th className="p-2">状态</th>
                <th className="p-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {policies.items.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="p-2">
                    {row.policyName}
                    <div className="text-muted-foreground text-xs">{row.policyCode}</div>
                  </td>
                  <td className="p-2">{row.shopName || row.shopCode}</td>
                  <td className="p-2">
                    {dynamicStrategyLabel(row.dynamicBiddingStrategy)}
                    <span className="text-muted-foreground text-xs">
                      {' '}
                      · {scopeLabel(row.scope)}
                    </span>
                  </td>
                  <td className="p-2">
                    {ratioText(row.targetAcos)} / {ratioText(row.targetRoas)}
                  </td>
                  <td className="p-2">
                    {money(row.minBid)} ~ {money(row.maxBid)}
                  </td>
                  <td className="p-2">{policyStatusLabel(row.status)}</td>
                  <td className="p-2">
                    <button
                      className="rounded border px-2 py-1"
                      onClick={() => void onOpenPolicy(row.id)}
                    >
                      打开
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        {policies ? (
          <p className="text-muted-foreground text-xs">共 {policies.total} 条（筛选后的全集）。</p>
        ) : null}
      </section>

      {/* ============================================================ 策略详情 */}
      {policy ? (
        <section className="flex flex-col gap-3">
          <h2 className="text-lg font-medium">策略详情：{policy.policyName}</h2>
          <div className="flex flex-wrap gap-4 text-sm">
            <span>动态竞价策略：{dynamicStrategyLabel(policy.dynamicBiddingStrategy)}</span>
            <span>币种：{policy.currency}</span>
            <span>目标 ACOS：{ratioText(policy.targetAcos)}</span>
            <span>目标 ROAS：{ratioText(policy.targetRoas)}</span>
            <span>
              闸① 竞价范围：{money(policy.minBid)} ~ {money(policy.maxBid)}
            </span>
            <span>
              闸② 调整额度：比例 {ratioText(policy.singleAdjustCapRatio)} / 金额{' '}
              {money(policy.singleAdjustCapAmount)}
            </span>
            <span>状态：{policyStatusLabel(policy.status)}</span>
            <span>试运行：{policy.dryRun ? '开' : '关'}</span>
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              className="rounded border px-3 py-1 disabled:opacity-50"
              disabled={busy}
              onClick={() => void onChangeStatus('ACTIVE')}
            >
              激活
            </button>
            <button
              className="rounded border px-3 py-1 disabled:opacity-50"
              disabled={busy}
              onClick={() => void onChangeStatus('PAUSED')}
            >
              暂停
            </button>
            <button
              className="rounded border px-3 py-1 disabled:opacity-50"
              disabled={busy}
              onClick={() => void onGenerateSuggestion()}
            >
              生成目标 ACOS 反推建议
            </button>
          </div>
        </section>
      ) : null}

      {/* ============================================================ 竞价候选清单 + 手工调价 */}
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">竞价候选清单</h2>
        <p className="text-muted-foreground text-xs">
          🔴 窗口内的花费 / 销售额是<strong>平台自报口径</strong>（14.1.3.1）； 反推竞价用的是
          14.3.2.1 的<strong>归因</strong>销售额 —— 两个口径不同。 归不到 SKU
          的关键词照样列出来并写清原因。
        </p>
        {keywords.length === 0 ? (
          <p className="text-muted-foreground text-sm">这个筛选条件下没有关键词。</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-muted-foreground">
                <th className="p-2">关键词</th>
                <th className="p-2">当前竞价</th>
                <th className="p-2">窗口花费 / 销售额</th>
                <th className="p-2">SKU 归属</th>
                <th className="p-2">手工调价</th>
              </tr>
            </thead>
            <tbody>
              {keywords.map((row) => (
                <tr key={row.keywordId} className="border-t">
                  <td className="p-2">{row.keyword}</td>
                  <td className="p-2">
                    {money(row.bid)} {row.bidCurrency}
                  </td>
                  <td className="p-2">
                    {money(row.windowSpend)} / {money(row.windowSales)}
                    <div className="text-muted-foreground text-xs">{row.windowDays} 天有指标</div>
                  </td>
                  <td className="p-2">
                    {row.skuCode || '—'}
                    <div className="text-muted-foreground text-xs">{row.skuNote}</div>
                  </td>
                  <td className="p-2">
                    <button
                      className="rounded border px-2 py-1"
                      onClick={() => {
                        setAdjustKeywordId(row.keywordId);
                        setAdjustBid('');
                      }}
                    >
                      选中
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {keywordTotal > keywords.length ? (
          <p className="text-muted-foreground text-xs">
            共 {keywordTotal} 条，当前页显示前 {keywords.length} 条。
          </p>
        ) : null}

        {adjustKeywordId ? (
          <div className="flex flex-wrap items-end gap-3 text-sm">
            <span className="text-muted-foreground">把选中的关键词调成</span>
            <input
              className="rounded border px-2 py-1"
              value={adjustBid}
              onChange={(e) => setAdjustBid(e.target.value)}
              placeholder="绝对竞价"
            />
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground">试运行</span>
              <input
                type="checkbox"
                checked={applyDryRun}
                onChange={(e) => setApplyDryRun(e.target.checked)}
              />
            </label>
            <button
              className="rounded border px-3 py-1 disabled:opacity-50"
              disabled={busy || !adjustBid}
              onClick={() =>
                void onAdjust({
                  targets: [{ keywordId: adjustKeywordId, bidValue: Number(adjustBid) }],
                })
              }
            >
              提交调整
            </button>
            <span className="text-muted-foreground text-xs">
              🔴 改竞价走 <strong>14.1.3.1 已交付的写路径</strong> ⇒ 护栏 / 幂等 / 审计全部继承。
            </span>
          </div>
        ) : null}
      </section>

      {/* ============================================================ 目标 ACOS 反推建议 */}
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">目标 ACOS 反推竞价建议</h2>
        {suggestions.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有建议批次。打开一条策略后点「生成目标 ACOS 反推建议」。
          </p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-muted-foreground">
                <th className="p-2">批次</th>
                <th className="p-2">窗口</th>
                <th className="p-2">目标 ACOS / ROAS</th>
                <th className="p-2">归因口径</th>
                <th className="p-2">SKU 给出 / 算不出</th>
                <th className="p-2">数据截至</th>
                <th className="p-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {suggestions.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="p-2">
                    {row.policyName}
                    <div className="text-muted-foreground text-xs">
                      {suggestionStatusLabel(row.status)}
                    </div>
                  </td>
                  <td className="p-2">
                    {dateText(row.windowFrom)} ~ {dateText(row.windowTo)}
                  </td>
                  <td className="p-2">
                    {ratioText(row.targetAcos)} / {ratioText(row.targetRoas)}
                  </td>
                  <td className="p-2">
                    {row.attributionRuleCode || '—'}
                    <div className="text-muted-foreground text-xs">
                      {row.attributionModel || '没有生效的归因规则'}
                      {row.attributionRuleVersion !== null
                        ? ` · v${row.attributionRuleVersion}`
                        : ''}
                    </div>
                  </td>
                  <td className="p-2">
                    {row.suggestedCount} / {row.rejectedCount}
                  </td>
                  <td className="p-2">{timestampText(row.dataAsOf)}</td>
                  <td className="p-2">
                    <button
                      className="rounded border px-2 py-1"
                      onClick={() => void onOpenSuggestion(row.id)}
                    >
                      打开
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {suggestion ? (
          <div className="flex flex-col gap-2">
            <h3 className="text-base font-medium">
              批次明细：{suggestion.policyName}（{dateText(suggestion.windowFrom)} ~
              {dateText(suggestion.windowTo)}）
            </h3>
            {suggestion.profitAvailable ? null : (
              <p className="text-sm text-amber-600">
                🔴 利润侧这次读不到 ⇒ 本次**未做**「会不会亏」的校验：
                {suggestion.profitUnavailableReason}
              </p>
            )}
            {suggestion.skus.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                这个窗口里没有可算的 SKU（关键词没关联商品，或币种与策略不一致）。
              </p>
            ) : null}
            {suggestion.skus.map((sku) => (
              <div key={sku.id} className="flex flex-col gap-1 border-t pt-2 text-sm">
                <p>
                  <strong>{sku.skuCode || sku.skuId}</strong> · 花费 {money(sku.spend)} · 归因销售额{' '}
                  {money(sku.attributedSales)} {sku.currency}
                </p>
                <p className="text-muted-foreground text-xs">
                  当前 ACOS {ratioText(sku.currentAcos)} · 当前 ROAS {ratioText(sku.currentRoas)} ·
                  盈亏平衡 ACOS {ratioText(sku.breakEvenAcos)}（
                  {profitStatusLabel(sku.profitStatus)}） · 建议倍率 {multiplierText(sku.bidRatio)}
                </p>
                {sku.status === 'SUGGESTED' ? null : (
                  <p className="text-xs text-amber-600">
                    {guardrailLabel(sku.guardrailCode)}：{sku.reason}
                  </p>
                )}
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="text-muted-foreground">
                      <th className="p-1">关键词</th>
                      <th className="p-1">当前竞价</th>
                      <th className="p-1">建议竞价</th>
                      <th className="p-1">差额</th>
                      <th className="p-1">结论</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sku.items.map((item) => (
                      <tr key={item.id} className="border-t">
                        <td className="p-1">{item.keywordText}</td>
                        <td className="p-1">
                          {money(item.currentBid)} {item.bidCurrency}
                        </td>
                        <td className="p-1">{money(item.suggestedBid)}</td>
                        <td className="p-1">{money(item.bidDelta)}</td>
                        <td className="p-1">
                          {item.status === 'SUGGESTED' ? (
                            '可调'
                          ) : (
                            <>
                              {guardrailLabel(item.guardrailCode)}
                              <div className="text-muted-foreground">{item.reason}</div>
                            </>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
            {suggestion ? (
              <button
                className="self-start rounded border px-3 py-1 disabled:opacity-50"
                disabled={busy}
                onClick={() => void onAdjust({ suggestionId: suggestion.id })}
              >
                按建议批量调价（走已交付写路径）
              </button>
            ) : null}
          </div>
        ) : null}
      </section>

      {/* ============================================================ 批量调整任务 */}
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">批量竞价调整任务</h2>
        {adjustments.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有调整任务。</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-muted-foreground">
                <th className="p-2">策略 / 店铺</th>
                <th className="p-2">运行口径</th>
                <th className="p-2">状态</th>
                <th className="p-2">成功 / 失败</th>
                <th className="p-2">创建时间</th>
              </tr>
            </thead>
            <tbody>
              {adjustments.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="p-2">
                    {row.policyName}
                    <div className="text-muted-foreground text-xs">{row.shopCode}</div>
                  </td>
                  <td className="p-2">{runModeLabel(row.runMode)}</td>
                  <td className="p-2">{adjustmentStatusLabel(row.status)}</td>
                  <td className="p-2">
                    {row.appliedCount} / {row.failedCount}
                  </td>
                  <td className="p-2">{timestampText(row.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {adjustment ? (
          <div className="flex flex-col gap-1 text-sm">
            <h3 className="text-base font-medium">任务明细</h3>
            <p className="text-muted-foreground text-xs">
              14.1.3.1 批量任务 id：{adjustment.bulkActionId || '（试运行，没有真的调）'}
            </p>
            {adjustment.items.map((item) => (
              <p key={item.id} className="border-t pt-1">
                {item.targetRef} · {adjustmentStatusLabel(item.status)}
                {item.status === 'SUGGESTED' ? null : null}
                {item.guardrailCode ? (
                  <span className="text-amber-600"> · {guardrailLabel(item.guardrailCode)}</span>
                ) : null}
                <span className="text-muted-foreground"> {item.reason}</span>
                <span className="text-muted-foreground text-xs">
                  {' '}
                  {money(item.beforeBid)} → {money(item.afterBid)}
                </span>
              </p>
            ))}
          </div>
        ) : null}
      </section>

      {/* ============================================================ 待下发队列 */}
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">待下发队列</h2>
        <p className="text-muted-foreground text-xs">
          🔴 平台广告 API 未交付 ⇒ 这里的记录停在「<strong>待下发</strong>」，**不是**「已生效」。
          本地竞价已经改了，平台侧还没改。
        </p>
        {dispatches.length === 0 ? (
          <p className="text-muted-foreground text-sm">队列是空的。</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-muted-foreground">
                <th className="p-2">动作</th>
                <th className="p-2">目标</th>
                <th className="p-2">竞价变化</th>
                <th className="p-2">状态</th>
                <th className="p-2">入队时间</th>
                <th className="p-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {dispatches.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="p-2">{dispatchActionLabel(row.actionType)}</td>
                  <td className="p-2">
                    {row.keywordText || row.shopCode}
                    <div className="text-muted-foreground text-xs">
                      {row.externalKeywordId || row.externalCampaignId}
                    </div>
                  </td>
                  <td className="p-2">
                    {money(row.beforeBid)} → {money(row.afterBid)}
                  </td>
                  <td className="p-2">{dispatchStatusLabel(row.dispatchStatus)}</td>
                  <td className="p-2">{timestampText(row.createdAt)}</td>
                  <td className="p-2">
                    {row.dispatchStatus === 'PENDING' ? (
                      <button
                        className="rounded border px-2 py-1 disabled:opacity-50"
                        disabled={busy}
                        onClick={() => void onCancelDispatch(row.id)}
                      >
                        撤销
                      </button>
                    ) : (
                      <span className="text-muted-foreground text-xs">
                        {row.cancelReason || row.dispatchRef || '—'}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
