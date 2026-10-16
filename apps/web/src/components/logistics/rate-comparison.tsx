'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  blockingReasons,
  compareChannels,
  emptyCompareInput,
  emptyRatingInput,
  emptyResultText,
  eligibilityLabel,
  fetchComparisons,
  fetchComparison,
  fetchRateCapabilities,
  fetchRatings,
  fetchShopPriorities,
  moneyText,
  rateText,
  scoreSourceLabel,
  rankBasisLabel,
  saveComparison,
  saveRating,
  saveShopPriority,
  scoreText,
  scoreTone,
  selectionBasisLabel,
  transitText,
  validateCompare,
  validateRating,
} from './rate-comparison-model';
import type {
  CompareInput,
  CompareResult,
  ComparisonDetail,
  ComparisonPage,
  RateComparisonCapabilities,
  RatingInput,
  RatingOverview,
  ShopPriorityOverview,
} from './rate-comparison-model';

/**
 * 10.1.2.2 渠道比价。
 *
 * 🔴 页面顶部那句话不是提示语，是这一页的设计前提：**得分在服务端算**。
 * 页面把比价条件发给 `/channel/rate-comparison/compare`，换回服务端算好的三维得分、
 * 综合分与推荐；浏览器里**没有任何一处**做归一化或加权 —— 前端算一遍就会让
 * 「界面上的名次」和「落库的名次」有两个算法，而对账时没人知道该信哪个。
 *
 * 🔴 第二句同样是约束：**只看价格选渠道是常见错误**。所以界面上被投诉率红线 /
 * 样本量 / 评分新鲜度三个闸拦下的渠道**不删掉**，而是留在对照表里、给出完整得分，
 * 再用一句话说明「它为什么便宜却没被选」—— 从表里删掉会让人以为它不存在。
 */
export function RateComparisonWorkspace() {
  const [input, setInput] = useState<CompareInput>(emptyCompareInput);
  const [validation, setValidation] = useState<ReturnType<typeof validateCompare>>({
    ok: true,
    errors: [],
  });
  const [result, setResult] = useState<CompareResult | null>(null);
  const [detail, setDetail] = useState<ComparisonDetail | null>(null);
  const [capabilities, setCapabilities] = useState<RateComparisonCapabilities | null>(null);
  const [ratings, setRatings] = useState<RatingOverview | null>(null);
  const [priorities, setPriorities] = useState<ShopPriorityOverview | null>(null);
  const [history, setHistory] = useState<ComparisonPage | null>(null);
  const [ratingInput, setRatingInput] = useState<RatingInput>(emptyRatingInput);
  const [ratingValidation, setRatingValidation] = useState<ReturnType<typeof validateRating>>({
    ok: true,
    errors: [],
  });
  const [priorityForm, setPriorityForm] = useState({
    shopCode: '',
    channelId: '',
    channelCode: '',
    priorityNo: '1',
    autoSelectAllowed: true,
    minCompositeScore: '0',
    note: '',
  });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    void (async () => {
      setLoading(true);
      setError('');
      try {
        const [caps, ratingPage, priorityPage, historyPage] = await Promise.all([
          fetchRateCapabilities(),
          fetchRatings({ sort: 'CHANNEL' }),
          fetchShopPriorities({}),
          fetchComparisons({ size: 20 }),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setRatings(ratingPage);
        setPriorities(priorityPage);
        setHistory(historyPage);
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

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError('');
    try {
      await work();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const onCompare = () =>
    run(async () => {
      const check = validateCompare(input);
      setValidation(check);
      if (!check.ok) return;
      const next = await compareChannels(input);
      setResult(next);
      setDetail(null);
    });

  const onSave = () =>
    run(async () => {
      const check = validateCompare(input);
      setValidation(check);
      if (!check.ok) return;
      const saved = await saveComparison(input);
      setResult(saved);
      setDetail(saved.savedComparisonId ? await fetchComparison(saved.savedComparisonId) : null);
      setHistory(await fetchComparisons({ size: 20 }));
    });

  const onSaveRating = () =>
    run(async () => {
      const check = validateRating(ratingInput);
      setRatingValidation(check);
      if (!check.ok) return;
      await saveRating(ratingInput);
      setRatings(await fetchRatings({ sort: 'CHANNEL' }));
    });

  const onSavePriority = () =>
    run(async () => {
      await saveShopPriority(priorityForm);
      const shop = priorityForm.shopCode.trim();
      setPriorities(await fetchShopPriorities(shop ? { shopCode: shop } : {}));
    });

  const update = <K extends keyof CompareInput>(key: K, value: CompareInput[K]) =>
    setInput((prev) => ({ ...prev, [key]: value }));

  const updateRating = <K extends keyof RatingInput>(key: K, value: RatingInput[K]) =>
    setRatingInput((prev) => ({ ...prev, [key]: value }));

  const emptyText = emptyResultText(result);

  return (
    <div className="flex flex-col gap-6 p-6">
      <header>
        <h1 className="text-2xl font-semibold">渠道比价</h1>
        <p className="text-muted-foreground mt-1 text-sm">
          在满足时效与限制的渠道中，按成本 / 时效 / 成功率多目标排序，并按店铺预设优先级自动选择。
        </p>
        <p className="text-muted-foreground mt-1 text-sm">
          <strong className="text-foreground">得分在服务端算</strong>
          ：页面把条件发给 <code>/channel/rate-comparison/compare</code>，
          换回服务端算好的三维得分、综合分与推荐；浏览器里不做任何归一化或加权。
        </p>
        <p className="text-muted-foreground mt-1 text-sm">
          <strong className="text-foreground">只看价格选渠道是常见错误</strong>
          ：便宜但高丢件的渠道会推高售后与差评成本。所以被
          <strong>投诉率红线 / 最小样本量 / 评分新鲜度</strong>三个闸拦下的渠道
          <strong>不会被自动选中</strong>，但仍留在对照表里并给出完整得分 ——
          它为什么便宜却没被选，必须一眼看得见。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {loading ? (
        <p className="text-muted-foreground text-sm">正在加载渠道评分与比价记录…</p>
      ) : (
        <>
          {/* ============ 1. 比价条件 ============ */}
          <section className="flex flex-col gap-3">
            <h2 className="text-lg font-medium">比价条件</h2>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
              <Field label="收货国家（两位代码）">
                <input
                  className="border rounded px-2 py-1"
                  value={input.destCountry}
                  onChange={(e) => update('destCountry', e.target.value)}
                  placeholder="DE"
                />
              </Field>
              <Field label="适用店铺编码（可空）">
                <input
                  className="border rounded px-2 py-1"
                  value={input.shopCode}
                  onChange={(e) => update('shopCode', e.target.value)}
                  placeholder="SHOP-DE-1"
                />
              </Field>
              <Field label="计费重量（kg）">
                <input
                  className="border rounded px-2 py-1"
                  value={input.weightKg}
                  onChange={(e) => update('weightKg', e.target.value)}
                />
              </Field>
              <Field label="时效上限（天，可空）">
                <input
                  className="border rounded px-2 py-1"
                  value={input.maxTransitDays}
                  onChange={(e) => update('maxTransitDays', e.target.value)}
                  placeholder="9"
                />
              </Field>
              <Field label="申报价值（可空）">
                <input
                  className="border rounded px-2 py-1"
                  value={input.declaredValue}
                  onChange={(e) => update('declaredValue', e.target.value)}
                />
              </Field>
              <Field label="申报币种（可空）">
                <input
                  className="border rounded px-2 py-1"
                  value={input.declaredCurrency}
                  onChange={(e) => update('declaredCurrency', e.target.value)}
                  placeholder="USD"
                />
              </Field>
              <Field label="成本权重">
                <input
                  className="border rounded px-2 py-1"
                  value={input.costWeight}
                  onChange={(e) => update('costWeight', e.target.value)}
                />
              </Field>
              <Field label="时效权重">
                <input
                  className="border rounded px-2 py-1"
                  value={input.transitWeight}
                  onChange={(e) => update('transitWeight', e.target.value)}
                />
              </Field>
              <Field label="可靠性权重">
                <input
                  className="border rounded px-2 py-1"
                  value={input.reliabilityWeight}
                  onChange={(e) => update('reliabilityWeight', e.target.value)}
                />
              </Field>
              <Field label="评分新鲜度上限（天）">
                <input
                  className="border rounded px-2 py-1"
                  value={input.maxRatingAgeDays}
                  onChange={(e) => update('maxRatingAgeDays', e.target.value)}
                />
              </Field>
              <Field label="最低可接受综合分（可空）">
                <input
                  className="border rounded px-2 py-1"
                  value={input.minCompositeScore}
                  onChange={(e) => update('minCompositeScore', e.target.value)}
                />
              </Field>
              <Field label="比价时刻（留空取当前）">
                <input
                  className="border rounded px-2 py-1"
                  type="datetime-local"
                  value={input.comparedAt}
                  onChange={(e) => update('comparedAt', e.target.value)}
                />
              </Field>
            </div>
            <div className="flex flex-wrap gap-4 text-sm">
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={input.applyShopPriority}
                  onChange={(e) => update('applyShopPriority', e.target.checked)}
                />
                按店铺预设优先级自动选择
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={input.requirePassOnly}
                  onChange={(e) => update('requirePassOnly', e.target.checked)}
                />
                只取 PASS（排除需人工确认的渠道）
              </label>
            </div>
            {!validation.ok ? (
              <ul className="text-destructive list-disc pl-5 text-sm">
                {validation.errors.map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            ) : null}
            <div className="flex gap-2">
              <button
                className="border rounded px-3 py-1 text-sm disabled:opacity-50"
                disabled={busy}
                onClick={onCompare}
              >
                比价（不落库）
              </button>
              <button
                className="border rounded px-3 py-1 text-sm disabled:opacity-50"
                disabled={busy}
                onClick={onSave}
              >
                比价并落库
              </button>
            </div>
          </section>

          {/* ============ 2. 推荐结果 ============ */}
          <section className="flex flex-col gap-3">
            <h2 className="text-lg font-medium">推荐结果</h2>
            {!result ? (
              <p className="text-muted-foreground text-sm">尚未比价。填好上面的条件后点「比价」。</p>
            ) : emptyText ? (
              <p className="text-muted-foreground text-sm">{emptyText}</p>
            ) : (
              <div className="flex flex-col gap-2">
                <p className="text-sm">
                  推荐渠道：
                  <strong className="text-foreground">
                    {result.selectedChannelCode || '—'}
                    {result.selectedChannelName ? `（${result.selectedChannelName}）` : ''}
                  </strong>
                  <span className="text-muted-foreground">
                    {' '}
                    · {selectionBasisLabel(result.selectionBasis)}
                  </span>
                </p>
                <p className="text-muted-foreground text-sm">{result.selectionNote}</p>
                <p className="text-muted-foreground text-sm">
                  参与排名 {result.rankedCount} / 候选 {result.candidateCount}；风险闸命中{' '}
                  {result.riskFlaggedCount}，样本不足 {result.insufficientSampleCount}，评分过期{' '}
                  {result.staleRatingCount}
                  {result.mixedCurrency
                    ? `；主币种 ${result.currency}，其它币种的渠道未参与排序`
                    : ''}
                </p>
                <p className="text-muted-foreground text-sm">
                  权重（服务端归一化后的实际值）：成本 {result.costWeight} · 时效{' '}
                  {result.transitWeight} · 可靠性 {result.reliabilityWeight}
                </p>
              </div>
            )}
          </section>

          {/* ============ 3. 对照表（排序后的渠道 + 评分明细）=========== */}
          <section className="flex flex-col gap-3">
            <h2 className="text-lg font-medium">排序结果与评分明细</h2>
            {!result || result.lines.length === 0 ? (
              <p className="text-muted-foreground text-sm">还没有比价结果。</p>
            ) : (
              <div className="flex flex-col gap-4">
                {result.lines.map((line) => (
                  <article
                    key={line.channelId}
                    className={`flex flex-col gap-2 rounded border p-3 ${
                      line.selected ? 'border-emerald-500' : ''
                    }`}
                  >
                    <div className="flex flex-wrap items-baseline gap-2">
                      <span className="font-medium">
                        {line.rankNo !== null ? `#${line.rankNo} ` : ''}
                        {line.channelCode}
                      </span>
                      <span className="text-muted-foreground text-sm">{line.carrierName}</span>
                      <span className="text-muted-foreground text-sm">
                        {eligibilityLabel(line.eligibility)}
                      </span>
                      {line.selected ? (
                        <span className="text-emerald-600 text-sm">本次推荐选中</span>
                      ) : null}
                    </div>
                    <div className="text-muted-foreground grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
                      <span>
                        运费：{line.priced ? moneyText(line.totalCharge, line.currency) : '算不出（不是 0 元）'}
                      </span>
                      <span>时效：{transitText(line.transitMinDays, line.transitMaxDays)}</span>
                      <span>成本分：{scoreText(line.costScore)}</span>
                      <span>时效分：{scoreText(line.transitScore)}</span>
                      <span>可靠性分：{scoreText(line.reliabilityScore)}</span>
                      <span>综合分：{scoreText(line.compositeScore)}</span>
                      <span>
                        评分样本：{line.ratingDispatched ?? '—'} 单（成功{' '}
                        {rateText(line.ratingSuccessRate)} / 达成 {rateText(line.ratingOnTimeRate)} /
                        投诉 {rateText(line.ratingComplaintRate)}）
                      </span>
                      <span>评分来源：{scoreSourceLabel(line.ratingSource)}</span>
                    </div>
                    {line.rankBasis !== 'RANKED' ? (
                      <p className="text-sm">
                        <span className="text-muted-foreground">落选原因：</span>
                        {rankBasisLabel(line.rankBasis)}
                        {line.exclusionReason ? ` —— ${line.exclusionReason}` : ''}
                      </p>
                    ) : null}
                    {blockingReasons(line).length > 0 ? (
                      <ul className="text-amber-700 list-disc pl-5 text-sm">
                        {blockingReasons(line).map((r) => (
                          <li key={r}>{r}</li>
                        ))}
                      </ul>
                    ) : null}
                    {line.scoreBreakdown.length > 0 ? (
                      <details className="text-sm">
                        <summary className="cursor-pointer">评分算式</summary>
                        <ul className="text-muted-foreground list-disc pl-5">
                          {line.scoreBreakdown.map((b) => (
                            <li key={b}>{b}</li>
                          ))}
                        </ul>
                      </details>
                    ) : null}
                  </article>
                ))}
              </div>
            )}
            {result?.note ? <p className="text-muted-foreground text-sm">{result.note}</p> : null}
          </section>

          {/* ============ 4. 渠道评分（比价的输入）=========== */}
          <section className="flex flex-col gap-3">
            <h2 className="text-lg font-medium">渠道评分</h2>
            <p className="text-muted-foreground text-sm">
              成功率 / 时效达成率 / 投诉率由原始计数在服务端派生，写不进去。
              10.6.2.1 时效分析<b>尚未交付</b>，当前只能人工登记（来源标记为
              <code> MANUAL_ENTERED</code>）。
            </p>
            {ratings ? (
              ratings.ratings.length === 0 ? (
                <p className="text-muted-foreground text-sm">{ratings.note}</p>
              ) : (
                <div className="flex flex-col gap-2">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left">
                        <th className="p-1">渠道</th>
                        <th className="p-1">国家</th>
                        <th className="p-1">发运</th>
                        <th className="p-1">妥投</th>
                        <th className="p-1">按时</th>
                        <th className="p-1">投诉</th>
                        <th className="p-1">成功率</th>
                        <th className="p-1">时效达成率</th>
                        <th className="p-1">投诉率</th>
                        <th className="p-1">红线</th>
                        <th className="p-1">最小样本</th>
                        <th className="p-1">来源</th>
                      </tr>
                    </thead>
                    <tbody>
                      {ratings.ratings.map((r) => (
                        <tr key={r.id} className="border-b">
                          <td className="p-1">{r.channelCode}</td>
                          <td className="p-1">{r.countryCode}</td>
                          <td className="p-1">{r.dispatched}</td>
                          <td className="p-1">{r.delivered}</td>
                          <td className="p-1">{r.onTime}</td>
                          <td className="p-1">{r.complaint}</td>
                          <td className="p-1">{rateText(r.deliverySuccessRate)}</td>
                          <td className="p-1">{rateText(r.onTimeRate)}</td>
                          <td className="p-1">{rateText(r.complaintRate)}</td>
                          <td className="p-1">{r.maxComplaintRatePct}%</td>
                          <td className="p-1">{r.minSampleThreshold}</td>
                          <td className="p-1">{scoreSourceLabel(r.scoreSource)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {ratings.unratedChannelCodes.length > 0 ? (
                    <p className="text-amber-700 text-sm">
                      以下渠道<b>没有任何评分</b>，比价时它们会以「没有渠道评分」落选（不会拿 0 分参与）：{' '}
                      {ratings.unratedChannelCodes.join('、')}
                    </p>
                  ) : null}
                  <p className="text-muted-foreground text-sm">{ratings.note}</p>
                </div>
              )
            ) : null}

            <details className="flex flex-col gap-2">
              <summary className="cursor-pointer text-sm">登记一条渠道评分</summary>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                <Field label="渠道 ID（uuid）">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.channelId}
                    onChange={(e) => updateRating('channelId', e.target.value)}
                  />
                </Field>
                <Field label="国家">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.countryCode}
                    onChange={(e) => updateRating('countryCode', e.target.value)}
                  />
                </Field>
                <Field label="已发运">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.dispatched}
                    onChange={(e) => updateRating('dispatched', e.target.value)}
                  />
                </Field>
                <Field label="已妥投">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.delivered}
                    onChange={(e) => updateRating('delivered', e.target.value)}
                  />
                </Field>
                <Field label="按时妥投">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.onTime}
                    onChange={(e) => updateRating('onTime', e.target.value)}
                  />
                </Field>
                <Field label="投诉">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.complaint}
                    onChange={(e) => updateRating('complaint', e.target.value)}
                  />
                </Field>
                <Field label="统计窗口开始">
                  <input
                    className="border rounded px-2 py-1"
                    type="datetime-local"
                    value={ratingInput.windowFrom}
                    onChange={(e) => updateRating('windowFrom', e.target.value)}
                  />
                </Field>
                <Field label="统计窗口结束">
                  <input
                    className="border rounded px-2 py-1"
                    type="datetime-local"
                    value={ratingInput.windowTo}
                    onChange={(e) => updateRating('windowTo', e.target.value)}
                  />
                </Field>
                <Field label="观测时刻">
                  <input
                    className="border rounded px-2 py-1"
                    type="datetime-local"
                    value={ratingInput.observedAt}
                    onChange={(e) => updateRating('observedAt', e.target.value)}
                  />
                </Field>
                <Field label="投诉率红线（%）">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.maxComplaintRatePct}
                    onChange={(e) => updateRating('maxComplaintRatePct', e.target.value)}
                  />
                </Field>
                <Field label="最小样本量">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.minSampleThreshold}
                    onChange={(e) => updateRating('minSampleThreshold', e.target.value)}
                  />
                </Field>
                <Field label="来源凭据">
                  <input
                    className="border rounded px-2 py-1"
                    value={ratingInput.sourceRef}
                    onChange={(e) => updateRating('sourceRef', e.target.value)}
                  />
                </Field>
              </div>
              {!ratingValidation.ok ? (
                <ul className="text-destructive list-disc pl-5 text-sm">
                  {ratingValidation.errors.map((e) => (
                    <li key={e}>{e}</li>
                  ))}
                </ul>
              ) : null}
              <button
                className="border w-fit rounded px-3 py-1 text-sm disabled:opacity-50"
                disabled={busy}
                onClick={onSaveRating}
              >
                登记评分
              </button>
            </details>
          </section>

          {/* ============ 5. 店铺预设优先级 ============ */}
          <section className="flex flex-col gap-3">
            <h2 className="text-lg font-medium">店铺预设优先级</h2>
            {priorities && priorities.priorities.length > 0 ? (
              <>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-1">店铺</th>
                      <th className="p-1">位次</th>
                      <th className="p-1">渠道</th>
                      <th className="p-1">允许自动选</th>
                      <th className="p-1">最低可接受综合分</th>
                    </tr>
                  </thead>
                  <tbody>
                    {priorities.priorities.map((p) => (
                      <tr key={p.id} className="border-b">
                        <td className="p-1">{p.shopCode}</td>
                        <td className="p-1">{p.priorityNo}</td>
                        <td className="p-1">{p.channelCode}</td>
                        <td className="p-1">{p.autoSelectAllowed ? '是' : '否（仅兜底候选）'}</td>
                        <td className="p-1">{p.minCompositeScore}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="text-muted-foreground text-sm">{priorities.note}</p>
              </>
            ) : (
              <p className="text-muted-foreground text-sm">{priorities?.note ?? '—'}</p>
            )}
            <details className="flex flex-col gap-2">
              <summary className="cursor-pointer text-sm">登记一条预设优先级</summary>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                <Field label="店铺编码">
                  <input
                    className="border rounded px-2 py-1"
                    value={priorityForm.shopCode}
                    onChange={(e) => setPriorityForm((p) => ({ ...p, shopCode: e.target.value }))}
                  />
                </Field>
                <Field label="渠道 ID（uuid）">
                  <input
                    className="border rounded px-2 py-1"
                    value={priorityForm.channelId}
                    onChange={(e) => setPriorityForm((p) => ({ ...p, channelId: e.target.value }))}
                  />
                </Field>
                <Field label="位次（从 1 开始，需连续）">
                  <input
                    className="border rounded px-2 py-1"
                    value={priorityForm.priorityNo}
                    onChange={(e) => setPriorityForm((p) => ({ ...p, priorityNo: e.target.value }))}
                  />
                </Field>
                <Field label="最低可接受综合分">
                  <input
                    className="border rounded px-2 py-1"
                    value={priorityForm.minCompositeScore}
                    onChange={(e) =>
                      setPriorityForm((p) => ({ ...p, minCompositeScore: e.target.value }))
                    }
                  />
                </Field>
              </div>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={priorityForm.autoSelectAllowed}
                  onChange={(e) =>
                    setPriorityForm((p) => ({ ...p, autoSelectAllowed: e.target.checked }))
                  }
                />
                允许被自动选中（关掉 = 仅兜底候选）
              </label>
              <button
                className="border w-fit rounded px-3 py-1 text-sm disabled:opacity-50"
                disabled={busy}
                onClick={onSavePriority}
              >
                登记预设
              </button>
            </details>
          </section>

          {/* ============ 6. 比价单历史 ============ */}
          <section className="flex flex-col gap-3">
            <h2 className="text-lg font-medium">比价单</h2>
            {!history || history.items.length === 0 ? (
              <p className="text-muted-foreground text-sm">还没有落库的比价单。</p>
            ) : (
              <>
                <p className="text-muted-foreground text-sm">
                  共 {history.summary.total} 张，其中 {history.summary.withSelection} 张给出了推荐、
                  {history.summary.noSelection} 张「无可选渠道」、{history.summary.shopPreset} 张按店铺预设选出；
                  {history.summary.riskFlagged} 张命中过风险闸。成本口径恒为{' '}
                  <code>RULE_ESTIMATE</code>。
                </p>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-1">比价时刻</th>
                      <th className="p-1">目的国</th>
                      <th className="p-1">店铺</th>
                      <th className="p-1">候选 / 排名</th>
                      <th className="p-1">推荐</th>
                      <th className="p-1">选择口径</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.items.map((c) => (
                      <tr key={c.id} className="border-b">
                        <td className="p-1">{new Date(c.comparedAt).toLocaleString()}</td>
                        <td className="p-1">{c.destCountry}</td>
                        <td className="p-1">{c.shopCode || '—'}</td>
                        <td className="p-1">
                          {c.candidateCount} / {c.rankedCount}
                        </td>
                        <td className="p-1">{c.selectedChannelCode || '—'}</td>
                        <td className="p-1">{selectionBasisLabel(c.selectionBasis)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </section>

          {/* ============ 7. 已落库比价单详情 ============ */}
          {detail ? (
            <section className="flex flex-col gap-3">
              <h2 className="text-lg font-medium">比价单详情（逐渠道快照）</h2>
              <p className="text-muted-foreground text-sm">
                {new Date(detail.comparison.comparedAt).toLocaleString()} ·{' '}
                {selectionBasisLabel(detail.comparison.selectionBasis)} ·{' '}
                {detail.comparison.selectedChannelCode || '无可选渠道'}
              </p>
              <ul className="flex flex-col gap-2">
                {detail.lines.map((line) => (
                  <li key={line.channelId} className="border rounded p-2 text-sm">
                    <span className={line.selected ? 'font-medium' : ''}>
                      {line.rankNo !== null ? `#${line.rankNo} ` : ''}
                      {line.channelCode}
                    </span>
                    <span className="text-muted-foreground"> · 综合分 </span>
                    <span className={scoreTone(line.compositeScore)}>
                      {scoreText(line.compositeScore)}
                    </span>
                    <span className="text-muted-foreground">
                      {' '}
                      · {moneyText(line.totalCharge, line.currency)}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {/* ============ 8. 能力与边界 ============ */}
          {capabilities ? (
            <section className="flex flex-col gap-3">
              <h2 className="text-lg font-medium">能力与边界</h2>
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-1">维度</th>
                    <th className="p-1">说明</th>
                    <th className="p-1">状态</th>
                    <th className="p-1">缺失影响</th>
                  </tr>
                </thead>
                <tbody>
                  {capabilities.dimensions.map((d) => (
                    <tr key={d.dimension} className="border-b">
                      <td className="p-1">{d.dimension}</td>
                      <td className="p-1">{d.description}</td>
                      <td className="p-1">
                        {d.available ? '已具备' : `未交付（${d.ownerModule}）`}
                      </td>
                      <td className="text-muted-foreground p-1">{d.impact}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="text-muted-foreground text-sm">{capabilities.weightingNote}</p>
              <p className="text-muted-foreground text-sm">{capabilities.gatingNote}</p>
              <p className="text-muted-foreground text-sm">{capabilities.estimationNote}</p>
              <p className="text-muted-foreground text-sm">{capabilities.dataNote}</p>
            </section>
          ) : null}
        </>
      )}
    </div>
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
