'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  activationBlockers,
  applyAdDaypartRecommendation,
  archiveAdDaypartStrategy,
  changeAdDaypartStatus,
  createAdDaypartStrategy,
  daypartActionLabel,
  daypartActionTone,
  daypartStatusLabel,
  daypartStatusTone,
  editableCurve,
  effectiveHourCount,
  fetchAdDaypartCapabilities,
  fetchAdDaypartRecommendations,
  fetchAdDaypartShopCaps,
  fetchAdDaypartShops,
  fetchAdDaypartStrategies,
  fetchAdDaypartStrategy,
  fetchAdDaypartTask,
  fetchAdDaypartTasks,
  generateAdDaypartRecommendation,
  guardrailLabel,
  hourLabel,
  money,
  multiplierText,
  ratioText,
  replaceAdDaypartCurve,
  runAdDaypartTasks,
  taskResultLabel,
  taskResultTone,
  taskStatusLabel,
  taskStatusTone,
  timestampText,
  timezoneStatusLabel,
  timezoneStatusTone,
  upstreamStatusLabel,
  upstreamStatusTone,
  validateDaypartCurve,
} from './ad-daypart-model';
import type {
  AdDaypartCapabilities,
  AdDaypartHourInput,
  AdDaypartRecommendationView,
  AdDaypartRunResult,
  AdDaypartShopCapsView,
  AdDaypartShopTimezone,
  AdDaypartStrategyInput,
  AdDaypartStrategyPage,
  AdDaypartStrategyView,
  AdDaypartTaskPage,
  AdDaypartTaskView,
} from './ad-daypart-model';

/**
 * 14.2.1.2 分时策略。
 *
 * <p>🔴 页面结构按蓝图的职责句排：
 *
 * <ol>
 *   <li><b>能力声明</b> —— 哪些做得了、哪些**做不了**（做不了的写清责任模块与影响）。
 *   <li><b>店铺与时区</b> —— 🔴 定不了时区的店铺**单列**并写清原因，
 *       不混进任何默认时区的桶里。
 *   <li><b>分时策略配置</b> —— 一个店铺一条策略 + 24 个**店铺本地小时**的曲线。
 *   <li><b>推荐曲线</b> —— 按 13.2.1.2 的历史出单时段算出来的建议（可整套套用）。
 *   <li><b>定时调整任务</b> —— 跑一轮（试运行 / 真调）+ 逐条留痕。
 *   <li><b>调整上限</b> —— 供 14.2.2.2 自动规则取用的读取形状。
 * </ol>
 *
 * <p>🔴 **页面不做任何算术**：目标预算、建议系数、上调合计全是服务端 `BigDecimal` 的事。
 */
export function AdDaypartWorkspace() {
  const [capabilities, setCapabilities] = useState<AdDaypartCapabilities | null>(null);
  const [shops, setShops] = useState<AdDaypartShopTimezone[]>([]);
  const [page, setPage] = useState<AdDaypartStrategyPage | null>(null);
  const [detail, setDetail] = useState<AdDaypartStrategyView | null>(null);
  const [curve, setCurve] = useState<AdDaypartHourInput[]>([]);
  const [recommendations, setRecommendations] = useState<AdDaypartRecommendationView[]>([]);
  const [tasks, setTasks] = useState<AdDaypartTaskPage | null>(null);
  const [taskDetail, setTaskDetail] = useState<AdDaypartTaskView | null>(null);
  const [caps, setCaps] = useState<AdDaypartShopCapsView | null>(null);
  const [runResult, setRunResult] = useState<AdDaypartRunResult | null>(null);

  const [shopCode, setShopCode] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [strategyCode, setStrategyCode] = useState('');
  const [strategyName, setStrategyName] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [singleCap, setSingleCap] = useState('1000.0000');
  const [totalCap, setTotalCap] = useState('5000.0000');
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [curveErrors, setCurveErrors] = useState<string[]>([]);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [strategies, taskPage] = await Promise.all([
        fetchAdDaypartStrategies({
          shopCode: shopCode || undefined,
          status: status || undefined,
          keyword: keyword || undefined,
          page: 0,
          size: 50,
        }),
        fetchAdDaypartTasks({ page: 0, size: 20 }),
      ]);
      setPage(strategies);
      setTasks(taskPage);
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
        const [capsResponse, shopList] = await Promise.all([
          fetchAdDaypartCapabilities(),
          fetchAdDaypartShops(),
        ]);
        if (!alive) return;
        setCapabilities(capsResponse);
        setShops(shopList);
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

  // 🔴 **定不了时区的店铺单列**：它们不是「没有店铺」，而是「时区定不了」。
  const resolvableShops = useMemo(
    () => shops.filter((shop) => shop.timezoneStatus === 'RESOLVED'),
    [shops],
  );
  const unresolvedShops = useMemo(
    () => shops.filter((shop) => shop.timezoneStatus !== 'RESOLVED'),
    [shops],
  );

  async function onOpenStrategy(id: string) {
    setBusy(true);
    setError('');
    try {
      const view = await fetchAdDaypartStrategy(id);
      setDetail(view);
      setCurve(editableCurve(view.hours));
      setCurveErrors([]);
      setRecommendations(await fetchAdDaypartRecommendations(id));
      setCaps(await fetchAdDaypartShopCaps(view.shopCode));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onCreate() {
    const problems: string[] = [];
    if (!strategyCode.trim()) problems.push('策略编码不能为空');
    if (!strategyName.trim()) problems.push('策略名不能为空');
    if (!shopCode.trim()) problems.push('必须选一个店铺');
    if (!/^[A-Z]{3}$/.test(currency.trim().toUpperCase())) problems.push('币种必须是 3 位大写字母');
    if (!(Number(singleCap) > 0)) problems.push('单次调整上限必须大于 0');
    if (!(Number(totalCap) > 0)) problems.push('总额上限必须大于 0');
    setFormErrors(problems);
    if (problems.length > 0) return;

    const input: AdDaypartStrategyInput = {
      strategyCode: strategyCode.trim(),
      strategyName: strategyName.trim(),
      shopCode: shopCode.trim(),
      scope: 'ALL_CAMPAIGNS',
      targetCampaignId: '',
      currency: currency.trim().toUpperCase(),
      singleAdjustCapAmount: Number(singleCap),
      singleAdjustCapPercent: 0.5,
      totalCapAmount: Number(totalCap),
      totalCapCount: 50,
      totalCapWindowHours: 24,
      minBudget: null,
      maxBudget: null,
      minBid: null,
      maxBid: null,
      dryRun: true,
      scheduleEnabled: false,
      runIntervalMinutes: 60,
      recommendPeakShareMultiple: 2,
      recommendTroughShareMultiple: 0.5,
      recommendPauseShareMultiple: 0.25,
      recommendBoostBidMultiplier: 1.5,
      recommendBoostBudgetMultiplier: 1.5,
      recommendReduceBidMultiplier: 0.7,
      recommendReduceBudgetMultiplier: 0.7,
      recommendPauseBidMultiplier: 0.5,
      recommendPauseBudgetMultiplier: 0,
      note: '',
    };
    setBusy(true);
    setError('');
    try {
      const created = await createAdDaypartStrategy(input);
      setStrategyCode('');
      setStrategyName('');
      await load();
      await onOpenStrategy(created.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSaveCurve() {
    if (!detail) return;
    const problems = validateDaypartCurve(curve);
    setCurveErrors(problems);
    if (problems.length > 0) return;
    setBusy(true);
    setError('');
    try {
      const view = await replaceAdDaypartCurve(detail.id, curve);
      setDetail(view);
      setCurve(editableCurve(view.hours));
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onChangeStatus(next: string) {
    if (!detail) return;
    setBusy(true);
    setError('');
    try {
      const view = await changeAdDaypartStatus(detail.id, next, '页面操作');
      setDetail(view);
      setCurve(editableCurve(view.hours));
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onArchive() {
    if (!detail) return;
    setBusy(true);
    setError('');
    try {
      await archiveAdDaypartStrategy(detail.id);
      setDetail(null);
      setCurve([]);
      setRecommendations([]);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onGenerateRecommendation() {
    if (!detail) return;
    setBusy(true);
    setError('');
    try {
      const batch = await generateAdDaypartRecommendation(detail.id);
      setRecommendations(await fetchAdDaypartRecommendations(detail.id));
      if (batch.upstreamStatus !== 'OK') {
        // 🔴 上游没给出曲线时**如实说**，不假装成功。
        setError(
          `推荐曲线没有产出（${upstreamStatusLabel(batch.upstreamStatus)}）：${
            batch.timezoneReason || batch.upstreamMessage || '上游未给出原因'
          }`,
        );
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onApplyRecommendation(id: string) {
    setBusy(true);
    setError('');
    try {
      const view = await applyAdDaypartRecommendation(id);
      setDetail(view);
      setCurve(editableCurve(view.hours));
      setRecommendations(await fetchAdDaypartRecommendations(view.id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onRun(runMode: string) {
    setBusy(true);
    setError('');
    try {
      const result = await runAdDaypartTasks({
        strategyId: detail?.id ?? '',
        runMode,
        triggerSource: 'MANUAL',
      });
      setRunResult(result);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenTask(id: string) {
    setBusy(true);
    setError('');
    try {
      setTaskDetail(await fetchAdDaypartTask(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const blockers = detail ? activationBlockers(detail.timezoneStatus, curve) : [];

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">分时策略</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          按<strong>店铺本地时区</strong>的小时设置不同竞价与预算（高峰提价提预算、低谷降价停投），
          并按历史出单时段自动推荐分时曲线。跨时区店铺用错时区会完全失效，
          所以定不了时区的店铺会被单列出来，策略也不许激活。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {loading && !page ? <p className="text-muted-foreground text-sm">正在加载分时策略…</p> : null}

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
            <summary className="cursor-pointer">口径说明（时区 / 系数 / 上限 / 执行 / 上游 / 边界）</summary>
            <ul className="flex list-disc flex-col gap-1 pl-5">
              <li>{capabilities.timezoneNote}</li>
              <li>{capabilities.multiplierNote}</li>
              <li>{capabilities.guardrailNote}</li>
              <li>{capabilities.executionNote}</li>
              <li>{capabilities.upstreamNote}</li>
              <li>{capabilities.boundaryNote}</li>
            </ul>
          </details>
        </section>
      ) : null}

      {/* ============================================================ 店铺与时区 */}
      <section className="flex flex-col gap-2">
        <h2 className="text-lg font-medium">店铺与时区</h2>
        {shops.length === 0 ? (
          <p className="text-muted-foreground text-sm">本租户还没有店铺。</p>
        ) : (
          <>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">店铺</th>
                  <th className="py-1">店铺本地时区</th>
                  <th className="py-1">启用站点数</th>
                  <th className="py-1">归属结论</th>
                </tr>
              </thead>
              <tbody>
                {resolvableShops.map((shop) => (
                  <tr key={shop.shopCode} className="border-t">
                    <td className="py-1">{shop.shopName || shop.shopCode}</td>
                    <td className="py-1">{shop.shopTimezone}</td>
                    <td className="py-1">{shop.activeSiteCount}</td>
                    <td className={`py-1 ${timezoneStatusTone(shop.timezoneStatus)}`}>
                      {timezoneStatusLabel(shop.timezoneStatus)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {/* 🔴 定不了时区的店铺**单列** —— 混进某个默认时区的桶里就是「用错时区」。 */}
            {unresolvedShops.length > 0 ? (
              <div className="flex flex-col gap-1 text-sm">
                <p className="text-destructive text-xs">
                  🔴 以下店铺<b>未能按店铺本地时区归属</b>（策略不许激活，推荐不产出曲线）：
                </p>
                <ul className="flex list-disc flex-col gap-1 pl-5">
                  {unresolvedShops.map((shop) => (
                    <li key={shop.shopCode} className="text-muted-foreground">
                      <strong>{shop.shopName || shop.shopCode}</strong>（{shop.shopCode}）：
                      {shop.timezoneReason}
                      {shop.siteTimezones.length > 0
                        ? `（站点时区：${shop.siteTimezones.join('、')}）`
                        : ''}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </>
        )}
      </section>

      {/* ============================================================ 新建策略 */}
      <section className="flex flex-col gap-2">
        <h2 className="text-lg font-medium">新建分时策略</h2>
        <div className="flex flex-wrap items-end gap-2 text-sm">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">店铺</span>
            <select
              className="rounded border px-2 py-1"
              value={shopCode}
              onChange={(event) => setShopCode(event.target.value)}
            >
              <option value="">（选一个店铺）</option>
              {resolvableShops.map((shop) => (
                <option key={shop.shopCode} value={shop.shopCode}>
                  {shop.shopName || shop.shopCode}（{shop.shopTimezone}）
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">策略编码</span>
            <input
              className="rounded border px-2 py-1"
              value={strategyCode}
              onChange={(event) => setStrategyCode(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">策略名</span>
            <input
              className="rounded border px-2 py-1"
              value={strategyName}
              onChange={(event) => setStrategyName(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">币种</span>
            <input
              className="rounded border px-2 py-1"
              value={currency}
              onChange={(event) => setCurrency(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">单次上限（金额）</span>
            <input
              className="rounded border px-2 py-1"
              value={singleCap}
              onChange={(event) => setSingleCap(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">总额上限</span>
            <input
              className="rounded border px-2 py-1"
              value={totalCap}
              onChange={(event) => setTotalCap(event.target.value)}
            />
          </label>
          <button
            className="rounded border px-3 py-1 text-sm"
            disabled={busy}
            onClick={() => void onCreate()}
          >
            新建
          </button>
        </div>
        {formErrors.length > 0 ? (
          <ul className="text-destructive flex list-disc flex-col gap-1 pl-5 text-xs">
            {formErrors.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
        ) : null}
      </section>

      {/* ============================================================ 策略清单 */}
      <section className="flex flex-col gap-2">
        <h2 className="text-lg font-medium">分时策略清单</h2>
        <div className="flex flex-wrap items-end gap-2 text-sm">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">按店铺筛</span>
            <input
              className="rounded border px-2 py-1"
              value={shopCode}
              onChange={(event) => setShopCode(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">按状态筛</span>
            <select
              className="rounded border px-2 py-1"
              value={status}
              onChange={(event) => setStatus(event.target.value)}
            >
              <option value="">（全部）</option>
              {(capabilities?.statuses ?? []).map((value) => (
                <option key={value} value={value}>
                  {daypartStatusLabel(value)}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">按编码 / 名称搜</span>
            <input
              className="rounded border px-2 py-1"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
            />
          </label>
        </div>
        {!page || page.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有分时策略。新建一条（一个店铺一条）之后才能配分时曲线。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">策略</th>
                <th className="py-1">店铺 / 时区</th>
                <th className="py-1">状态</th>
                <th className="py-1">在动小时</th>
                <th className="py-1">币种</th>
                <th className="py-1">最近一次跑</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="py-1">
                    <button
                      className="underline"
                      disabled={busy}
                      onClick={() => void onOpenStrategy(row.id)}
                    >
                      {row.strategyName || row.strategyCode}
                    </button>
                  </td>
                  <td className="py-1">
                    {row.shopName || row.shopCode}
                    <span className="text-muted-foreground text-xs">
                      {' '}
                      / {row.shopTimezone || '时区定不了'}
                    </span>
                  </td>
                  <td className={`py-1 ${daypartStatusTone(row.status)}`}>
                    {daypartStatusLabel(row.status)}
                    {row.dryRun ? <span className="text-muted-foreground text-xs"> · 试运行</span> : null}
                  </td>
                  <td className="py-1">{row.effectiveHourCount} / 24</td>
                  <td className="py-1">{row.currency}</td>
                  <td className="py-1">{timestampText(row.lastRunAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* ============================================================ 策略详情 + 曲线 */}
      {detail ? (
        <section className="flex flex-col gap-3">
          <h2 className="text-lg font-medium">
            {detail.strategyName || detail.strategyCode} · 分时曲线
          </h2>
          <p className="text-muted-foreground text-xs">
            店铺 <strong>{detail.shopName || detail.shopCode}</strong> 的本地时区：
            <strong>{detail.shopTimezone || '定不了'}</strong>
            （{timezoneStatusLabel(detail.timezoneStatus)}）
            {detail.timezoneReason ? ` · ${detail.timezoneReason}` : ''}
            ；币种 {detail.currency}；单次上限 {money(detail.singleAdjustCapAmount)} /
            {multiplierText(detail.singleAdjustCapPercent)}；总额上限{' '}
            {money(detail.totalCapAmount)} / {detail.totalCapCount} 次 /{' '}
            {detail.totalCapWindowHours} 小时。
          </p>

          {blockers.length > 0 ? (
            <ul className="text-amber-600 flex list-disc flex-col gap-1 pl-5 text-xs">
              {blockers.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          ) : null}

          <div className="flex flex-wrap gap-2">
            <button
              className="rounded border px-3 py-1 text-sm"
              disabled={busy || detail.status === 'ACTIVE'}
              onClick={() => void onChangeStatus('ACTIVE')}
            >
              激活
            </button>
            <button
              className="rounded border px-3 py-1 text-sm"
              disabled={busy || detail.status !== 'ACTIVE'}
              onClick={() => void onChangeStatus('PAUSED')}
            >
              暂停
            </button>
            <button
              className="rounded border px-3 py-1 text-sm"
              disabled={busy}
              onClick={() => void onSaveCurve()}
            >
              保存曲线
            </button>
            <button
              className="rounded border px-3 py-1 text-sm"
              disabled={busy}
              onClick={() => void onGenerateRecommendation()}
            >
              生成推荐曲线
            </button>
            <button
              className="rounded border px-3 py-1 text-sm"
              disabled={busy}
              onClick={() => void onRun('DRY_RUN')}
            >
              跑一轮（试运行，只算不调）
            </button>
            <button
              className="rounded border px-3 py-1 text-sm"
              disabled={busy || detail.dryRun}
              onClick={() => void onRun('APPLY')}
            >
              跑一轮（真调）
            </button>
            <button
              className="rounded border px-3 py-1 text-sm"
              disabled={busy}
              onClick={() => void onArchive()}
            >
              归档
            </button>
          </div>
          {detail.dryRun ? (
            <p className="text-amber-600 text-xs">
              🔴 这条策略处于<b>试运行</b>：跑一轮只算「本来会变成多少」，**不真调**。
              要真调必须先在策略属性里关掉试运行。
            </p>
          ) : null}

          {curveErrors.length > 0 ? (
            <ul className="text-destructive flex list-disc flex-col gap-1 pl-5 text-xs">
              {curveErrors.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          ) : null}

          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">本地小时</th>
                <th className="py-1">动作</th>
                <th className="py-1">竞价系数</th>
                <th className="py-1">预算系数</th>
                <th className="py-1">依据</th>
              </tr>
            </thead>
            <tbody>
              {curve.map((hour, index) => (
                <tr key={hour.localHour} className="border-t">
                  <td className="py-1">{hourLabel(hour.localHour)}</td>
                  <td className="py-1">
                    <select
                      className="rounded border px-1 py-0.5 text-xs"
                      value={hour.action}
                      onChange={(event) => {
                        const action = event.target.value;
                        setCurve((prev) =>
                          prev.map((row, i) =>
                            i === index
                              ? {
                                  ...row,
                                  action,
                                  // 🔴 切到「停投」时预算系数必须压到 0（自相矛盾的配置服务端会拒）。
                                  budgetMultiplier: action === 'PAUSE' ? 0 : row.budgetMultiplier,
                                }
                              : row,
                          ),
                        );
                      }}
                    >
                      {(capabilities?.actions ?? ['KEEP', 'BOOST', 'REDUCE', 'PAUSE']).map(
                        (action) => (
                          <option key={action} value={action}>
                            {daypartActionLabel(action)}
                          </option>
                        ),
                      )}
                    </select>
                  </td>
                  <td className="py-1">
                    <input
                      className="w-28 rounded border px-1 py-0.5 text-xs"
                      value={String(hour.bidMultiplier)}
                      onChange={(event) => {
                        const value = Number(event.target.value);
                        setCurve((prev) =>
                          prev.map((row, i) => (i === index ? { ...row, bidMultiplier: value } : row)),
                        );
                      }}
                    />
                  </td>
                  <td className="py-1">
                    <input
                      className="w-28 rounded border px-1 py-0.5 text-xs"
                      value={String(hour.budgetMultiplier)}
                      onChange={(event) => {
                        const value = Number(event.target.value);
                        setCurve((prev) =>
                          prev.map((row, i) =>
                            i === index ? { ...row, budgetMultiplier: value } : row,
                          ),
                        );
                      }}
                    />
                  </td>
                  <td className="text-muted-foreground py-1 text-xs">{hour.basisNote || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-muted-foreground text-xs">
            在动的小时数：{effectiveHourCount(curve)} / 24。
            🔴 「停投」= 预算系数置 0（该小时不再花钱），<b>不是</b>把活动状态改成暂停 ——
            那是 14.1.1.1 的能力，本模块不越界。
          </p>
        </section>
      ) : null}

      {/* ============================================================ 推荐曲线 */}
      {detail ? (
        <section className="flex flex-col gap-2">
          <h2 className="text-lg font-medium">推荐曲线（来自 13.2.1.2 分时与分周模式）</h2>
          {recommendations.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              还没有推荐批次。点「生成推荐曲线」按历史出单时段算一算。
            </p>
          ) : (
            recommendations.map((batch) => (
              <div key={batch.id} className="flex flex-col gap-1 text-sm">
                <p>
                  <span className={`${upstreamStatusTone(batch.upstreamStatus)}`}>
                    {upstreamStatusLabel(batch.upstreamStatus)}
                  </span>
                  <span className="text-muted-foreground text-xs">
                    {' '}
                    窗口 {batch.windowFrom} ~ {batch.windowTo} · 口径 {batch.alignment} · 时区{' '}
                    {batch.shopTimezone || '定不了'} · 投影 {batch.projectedDays}/{batch.expectedDays} 天
                  </span>
                </p>
                {batch.timezoneReason || batch.upstreamMessage ? (
                  <p className="text-amber-600 text-xs">
                    {batch.timezoneReason || batch.upstreamMessage}
                  </p>
                ) : null}
                {batch.hours.length > 0 ? (
                  <>
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-muted-foreground text-left text-xs">
                          <th className="py-1">本地小时</th>
                          <th className="py-1">订单笔数</th>
                          <th className="py-1">订单占比</th>
                          <th className="py-1">建议动作</th>
                          <th className="py-1">建议竞价系数</th>
                          <th className="py-1">建议预算系数</th>
                          <th className="py-1">依据</th>
                        </tr>
                      </thead>
                      <tbody>
                        {batch.hours.map((hour) => (
                          <tr key={hour.localHour} className="border-t">
                            <td className="py-1">{hourLabel(hour.localHour)}</td>
                            <td className="py-1">{hour.orderCount}</td>
                            <td className="py-1">{ratioText(hour.orderShare)}</td>
                            <td className={`py-1 ${daypartActionTone(hour.suggestedAction)}`}>
                              {daypartActionLabel(hour.suggestedAction)}
                            </td>
                            <td className="py-1">{multiplierText(hour.suggestedBidMultiplier)}</td>
                            <td className="py-1">{multiplierText(hour.suggestedBudgetMultiplier)}</td>
                            <td className="text-muted-foreground py-1 text-xs">{hour.basisNote}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <button
                      className="w-fit rounded border px-3 py-1 text-sm"
                      disabled={busy}
                      onClick={() => void onApplyRecommendation(batch.id)}
                    >
                      把这一批套到策略上
                    </button>
                  </>
                ) : null}
              </div>
            ))
          )}
        </section>
      ) : null}

      {/* ============================================================ 定时调整任务 */}
      <section className="flex flex-col gap-2">
        <h2 className="text-lg font-medium">定时调整任务</h2>
        <p className="text-muted-foreground text-xs">
          🔴 「定时」在<strong>外部调度</strong>（K8s CronJob）里：本服务不装 @Scheduled，
          调度器按策略上的 run_interval_minutes 调运行入口。跑一轮按
          <strong>店铺本地时区</strong>算当前小时（DB 的 now() + IANA 时区 ⇒ 夏令时对得上）。
        </p>
        {runResult ? (
          <div className="flex flex-col gap-1 text-sm">
            <p>
              触发 {runResult.strategyCount} 条 · 执行 {runResult.executedCount} 条 · 跳过{' '}
              {runResult.skippedCount} 条
            </p>
            {runResult.skips.map((skip) => (
              <p key={skip.strategyId} className="text-amber-600 text-xs">
                跳过 {skip.strategyCode}（{skip.shopCode}）：{skip.reason}
              </p>
            ))}
          </div>
        ) : null}
        {!tasks || tasks.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有跑过任何一轮。</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">策略 / 店铺</th>
                <th className="py-1">本地小时</th>
                <th className="py-1">口径</th>
                <th className="py-1">状态</th>
                <th className="py-1">生效 / 被拒 / 跳过</th>
                <th className="py-1">上调合计</th>
                <th className="py-1">执行时刻</th>
              </tr>
            </thead>
            <tbody>
              {tasks.items.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="py-1">
                    <button
                      className="underline"
                      disabled={busy}
                      onClick={() => void onOpenTask(row.id)}
                    >
                      {row.strategyCode}
                    </button>
                    <span className="text-muted-foreground text-xs"> / {row.shopCode}</span>
                  </td>
                  <td className="py-1">{hourLabel(row.localHour)}</td>
                  <td className="py-1">{row.runMode === 'DRY_RUN' ? '试运行' : '真调'}</td>
                  <td className={`py-1 ${taskStatusTone(row.status)}`}>
                    {taskStatusLabel(row.status)}
                  </td>
                  <td className="py-1">
                    {row.appliedCount} / {row.rejectedCount} / {row.skippedCount}
                  </td>
                  <td className="py-1">
                    {money(row.totalIncrease)} {row.currency}
                  </td>
                  <td className="py-1">{timestampText(row.executedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {taskDetail ? (
          <div className="flex flex-col gap-1 text-sm">
            <p>
              <strong>{taskDetail.strategyCode}</strong> · 本地第 {taskDetail.localHour} 小时（
              {taskDetail.shopTimezone}）· {taskDetail.runMode === 'DRY_RUN' ? '试运行' : '真调'} ·{' '}
              <span className={taskStatusTone(taskDetail.status)}>
                {taskStatusLabel(taskDetail.status)}
              </span>
            </p>
            <p className="text-muted-foreground text-xs">{taskDetail.message}</p>
            {taskDetail.lines.length === 0 ? (
              <p className="text-muted-foreground text-sm">这一轮没有任何逐条明细。</p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">对象</th>
                    <th className="py-1">小时</th>
                    <th className="py-1">调整前</th>
                    <th className="py-1">调整后</th>
                    <th className="py-1">增减</th>
                    <th className="py-1">结果</th>
                    <th className="py-1">被哪道闸拦下 / 原因</th>
                  </tr>
                </thead>
                <tbody>
                  {taskDetail.lines.map((line) => (
                    <tr key={line.id} className="border-t">
                      <td className="py-1">
                        {line.campaignName || line.campaignId}
                        {line.targetType === 'KEYWORD_BID' && line.targetRef
                          ? ` / ${line.targetRef}`
                          : ''}
                      </td>
                      <td className="py-1">{hourLabel(line.localHour)}</td>
                      <td className="py-1">{money(line.beforeValue)}</td>
                      <td className="py-1">{money(line.afterValue)}</td>
                      <td className="py-1">{money(line.delta)}</td>
                      <td className={`py-1 ${taskResultTone(line.result)}`}>
                        {taskResultLabel(line.result)}
                      </td>
                      <td className="text-muted-foreground py-1 text-xs">
                        {guardrailLabel(line.guardrailCode) ? `${guardrailLabel(line.guardrailCode)}：` : ''}
                        {line.message}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ) : null}
      </section>

      {/* ============================================================ 调整上限（下游取用） */}
      {caps ? (
        <section className="flex flex-col gap-2">
          <h2 className="text-lg font-medium">调整上限（供 14.2.2.2 自动规则取用）</h2>
          <p className="text-muted-foreground text-xs">{caps.note}</p>
          {caps.strategies.length === 0 ? (
            <p className="text-muted-foreground text-sm">该店铺还没有分时策略。</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">策略</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">单次上限（金额）</th>
                  <th className="py-1">单次上限（比例）</th>
                  <th className="py-1">总额上限</th>
                  <th className="py-1">次数 / 窗口</th>
                </tr>
              </thead>
              <tbody>
                {caps.strategies.map((cap) => (
                  <tr key={cap.strategyId} className="border-t">
                    <td className="py-1">{cap.strategyName || cap.strategyCode}</td>
                    <td className={`py-1 ${daypartStatusTone(cap.status)}`}>
                      {daypartStatusLabel(cap.status)}
                    </td>
                    <td className="py-1">{money(cap.singleAdjustCapAmount)}</td>
                    <td className="py-1">{multiplierText(cap.singleAdjustCapPercent)}</td>
                    <td className="py-1">{money(cap.totalCapAmount)}</td>
                    <td className="py-1">
                      {cap.totalCapCount} 次 / {cap.totalCapWindowHours} 小时
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {caps.effectiveCaps ? (
            <p className="text-muted-foreground text-xs">
              生效中的有效上限：单次 {money(caps.effectiveCaps.singleAdjustCapAmount)} /{' '}
              {multiplierText(caps.effectiveCaps.singleAdjustCapPercent)}，总额{' '}
              {money(caps.effectiveCaps.totalCapAmount)} / {caps.effectiveCaps.totalCapCount} 次 /{' '}
              {caps.effectiveCaps.totalCapWindowHours} 小时。
            </p>
          ) : null}
        </section>
      ) : null}
    </main>
  );
}
