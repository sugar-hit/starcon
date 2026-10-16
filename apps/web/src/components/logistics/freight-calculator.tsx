'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  basisLabel,
  chargeableText,
  emptyEstimateInput,
  emptyResultText,
  eligibilityLabel,
  eligibilityTone,
  estimateFreight,
  fetchFreightCapabilities,
  fetchFreightQuote,
  fetchFreightQuotes,
  fetchRateCards,
  moneyText,
  quoteOutcomeText,
  rateCardSummary,
  rateSourceLabel,
  saveFreightQuote,
  totalText,
  transitText,
  validateEstimate,
} from './freight-calculator-model';
import type {
  FreightCapabilities,
  FreightEstimateInput,
  FreightEstimateResult,
  FreightQuoteDetail,
  FreightQuotePage,
  RateCardOverview,
} from './freight-calculator-model';

/**
 * 10.1.2.1 运费计算器。
 *
 * 🔴 页面顶部那句话不是提示语，是这一页的设计前提：**运费在服务端算**。
 * 页面把包裹参数发给 `/channel/freight/estimate`，换回服务端算好的
 * 渠道 × (运费、时效、可承运性) 对照表；浏览器里**没有任何一处**拿费率卡自己乘一遍 ——
 * 前端算一遍就会让「界面上的钱」和「落库的钱」有两个算法，而对账时没人知道该信哪个。
 *
 * 🔴 第二句同样是约束：**试算用规则、结算用实际**。每一张试算单都带
 * `costBasis=RULE_ESTIMATE`（DB 层写不进实际结算口径），页面把它挂在对照表顶部 ——
 * 不写清这句，这个数字会被当实收成本入账，而差异会静默吞掉利润。
 */
export function FreightCalculatorWorkspace() {
  const [input, setInput] = useState<FreightEstimateInput>(emptyEstimateInput);
  const [validation, setValidation] = useState<ReturnType<typeof validateEstimate>>({
    ok: true,
    errors: [],
  });
  const [result, setResult] = useState<FreightEstimateResult | null>(null);
  const [quoteDetail, setQuoteDetail] = useState<FreightQuoteDetail | null>(null);
  const [capabilities, setCapabilities] = useState<FreightCapabilities | null>(null);
  const [rules, setRules] = useState<RateCardOverview | null>(null);
  const [quotes, setQuotes] = useState<FreightQuotePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [estimating, setEstimating] = useState(false);
  const [error, setError] = useState('');

  const loadQuotes = useCallback(async () => {
    setQuotes(await fetchFreightQuotes({ size: 20 }));
  }, []);

  useEffect(() => {
    let alive = true;
    void (async () => {
      setLoading(true);
      setError('');
      try {
        const [caps, cards, page] = await Promise.all([
          fetchFreightCapabilities(),
          fetchRateCards(),
          fetchFreightQuotes({ size: 20 }),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setRules(cards);
        setQuotes(page);
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
    setEstimating(true);
    setError('');
    try {
      await work();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setEstimating(false);
    }
  }, []);

  const onEstimate = () =>
    run(async () => {
      const check = validateEstimate(input);
      setValidation(check);
      if (!check.ok) return;
      const next = await estimateFreight(input);
      setResult(next);
      setQuoteDetail(null);
    });

  const onSaveQuote = () =>
    run(async () => {
      const check = validateEstimate(input);
      setValidation(check);
      if (!check.ok) return;
      const saved = await saveFreightQuote(input);
      setResult(saved);
      if (saved.savedQuoteId) {
        setQuoteDetail(await fetchFreightQuote(saved.savedQuoteId));
      }
      await loadQuotes();
    });

  const update = <K extends keyof FreightEstimateInput>(key: K, value: FreightEstimateInput[K]) =>
    setInput((prev) => ({ ...prev, [key]: value }));

  const emptyText = emptyResultText(result);

  return (
    <div className="flex flex-col gap-6 p-6">
      <header>
        <h1 className="text-2xl font-semibold">运费计算器</h1>
        <p className="text-muted-foreground mt-1 text-sm">
          输入收货国家、重量、体积、件数、申报价值与带电/带液/带磁标记，输出各可用渠道的运费、
          预计时效与可承运性。
        </p>
        <p className="text-muted-foreground mt-1 text-sm">
          <strong className="text-foreground">运费在服务端算</strong>
          ：页面不做任何前端算术（不求和、不比较、不换算），避免界面上的钱与落库的钱出现两个算法。
        </p>
        <p className="text-muted-foreground mt-1 text-sm">
          <strong className="text-foreground">试算用规则、结算用实际</strong>
          ：下方每个金额都带 <code>RULE_ESTIMATE</code> 标记，它不是承运商账单，与实际运费的差异要进
          下游费用差异项，不能当实际成本入账。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {loading ? (
        <p className="text-muted-foreground text-sm">正在加载运费规则与试算记录…</p>
      ) : (
        <>
          <section className="flex flex-col gap-3">
            <h2 className="text-lg font-medium">包裹参数</h2>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
              <label className="flex flex-col gap-1 text-sm">
                <span>收货国家（两位代码）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.destCountry}
                  onChange={(e) => update('destCountry', e.target.value)}
                  placeholder="DE"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>适用店铺编码（可空）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.shopCode}
                  onChange={(e) => update('shopCode', e.target.value)}
                  placeholder="SHOP-DE-1"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>计费重量（kg）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.weightKg}
                  onChange={(e) => update('weightKg', e.target.value)}
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>件数</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.pieces}
                  onChange={(e) => update('pieces', e.target.value)}
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>长（cm）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.lengthCm}
                  onChange={(e) => update('lengthCm', e.target.value)}
                  placeholder="按体积重 / 按体积计费才需要"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>宽（cm）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.widthCm}
                  onChange={(e) => update('widthCm', e.target.value)}
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>高（cm）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.heightCm}
                  onChange={(e) => update('heightCm', e.target.value)}
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>申报价值（可空）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.declaredValue}
                  onChange={(e) => update('declaredValue', e.target.value)}
                  placeholder="影响按申报价值投保的保费"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span>申报币种（可空）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.declaredCurrency}
                  onChange={(e) => update('declaredCurrency', e.target.value)}
                  placeholder="USD"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm md:col-span-3">
                <span>品类编码（逗号或空格分隔，可空）</span>
                <input
                  className="border rounded px-2 py-1"
                  value={input.categoryCodes}
                  onChange={(e) => update('categoryCodes', e.target.value)}
                  placeholder="HOME_GOODS, 3C"
                />
              </label>
            </div>

            <div className="flex flex-wrap gap-4 text-sm">
              <label className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={input.containsBattery}
                  onChange={(e) => update('containsBattery', e.target.checked)}
                />
                含电池
              </label>
              <label className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={input.containsLiquid}
                  onChange={(e) => update('containsLiquid', e.target.checked)}
                />
                含液体
              </label>
              <label className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={input.containsMagnet}
                  onChange={(e) => update('containsMagnet', e.target.checked)}
                />
                含磁力
              </label>
            </div>

            {validation.errors.length > 0 ? (
              <ul className="text-destructive list-disc pl-5 text-sm">
                {validation.errors.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            ) : null}

            <div className="flex gap-2">
              <button
                type="button"
                className="border rounded px-3 py-1 text-sm disabled:opacity-50"
                disabled={estimating}
                onClick={onEstimate}
              >
                {estimating ? '试算中…' : '试算（不落库）'}
              </button>
              <button
                type="button"
                className="rounded border px-3 py-1 text-sm disabled:opacity-50"
                disabled={estimating}
                onClick={onSaveQuote}
              >
                试算并保存试算单
              </button>
            </div>
            <p className="text-muted-foreground text-xs">
              限制判定（禁限品类 / 电池液体磁力 / 保价上限 / 申报上下限）由上游 10.1.1.2 的服务端判定端点给出，
              页面只展示结论与逐条理由，不自己比对限制字段。
            </p>
          </section>

          {result ? (
            <section className="flex flex-col gap-2">
              <h2 className="text-lg font-medium">
                对照表（{result.channelCount} 条渠道，{result.quoteableCount} 条算得出运费）
              </h2>
              <p className="text-muted-foreground text-xs">
                估算口径 <code>{result.costBasis}</code> · 求值时刻 {result.quotedAt}
                {result.mixedCurrency ? ' · 🔴 存在多币种渠道，最低价只在主币种内统计' : ''}
              </p>
              {result.mixedCurrency ? (
                <p className="text-warning text-xs">
                  不同币种的金额不能直接比大小，本页不做换算（换算就等于前端引入汇率口径）。
                </p>
              ) : null}
              {emptyText ? <p className="text-warning text-sm">{emptyText}</p> : null}

              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-2">渠道</th>
                      <th className="p-2">承运商</th>
                      <th className="p-2">可承运性</th>
                      <th className="p-2">计费量</th>
                      <th className="p-2">基础运费</th>
                      <th className="p-2">燃油</th>
                      <th className="p-2">偏远</th>
                      <th className="p-2">旺季</th>
                      <th className="p-2">保费</th>
                      <th className="p-2">合计</th>
                      <th className="p-2">时效</th>
                      <th className="p-2">口径</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.lines.map((line) => (
                      <tr key={line.channelId} className="border-b align-top">
                        <td className="p-2">
                          {line.channelName}
                          <div className="text-muted-foreground text-xs">{line.channelCode}</div>
                        </td>
                        <td className="p-2">{line.carrierName}</td>
                        <td className="p-2">
                          <span
                            className={
                              line.eligibility === 'PASS'
                                ? 'text-success'
                                : line.eligibility === 'REVIEW'
                                  ? 'text-warning'
                                  : 'text-destructive'
                            }
                          >
                            {eligibilityLabel(line.eligibility)}
                          </span>
                          {line.eligibilityReasons.map((reason) => (
                            <div key={reason} className="text-muted-foreground text-xs">
                              {reason}
                            </div>
                          ))}
                        </td>
                        <td className="p-2">{chargeableText(line)}</td>
                        <td className="p-2">{moneyText(line.baseCharge, line.currency)}</td>
                        <td className="p-2">{moneyText(line.fuelFee)}</td>
                        <td className="p-2">{moneyText(line.remoteFee)}</td>
                        <td className="p-2">{moneyText(line.peakFee)}</td>
                        <td className="p-2">{moneyText(line.insuranceFee)}</td>
                        <td className="font-medium p-2">{totalText(line)}</td>
                        <td className="p-2">{transitText(line.transitMinDays, line.transitMaxDays)}</td>
                        <td className="p-2">
                          {rateSourceLabel(line.rateSource)}
                          <div className="text-muted-foreground text-xs">{basisLabel(line.basis)}</div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {result.lines.some((line) => line.notes.length > 0) ? (
                <ul className="text-muted-foreground list-disc pl-5 text-xs">
                  {result.lines.flatMap((line) =>
                    line.notes.map((note) => <li key={`${line.channelId}-${note}`}>{`${line.channelCode}：${note}`}</li>),
                  )}
                </ul>
              ) : null}
              <p className="text-muted-foreground text-xs">{result.note}</p>

              {quoteDetail ? (
                <details className="border rounded p-3 text-sm">
                  <summary className="cursor-pointer">
                    试算单 #{quoteDetail.quote.id.slice(0, 8)} 的计算轨迹（口径 {quoteDetail.quote.costBasis}）
                  </summary>
                  <ul className="mt-2 flex list-disc flex-col gap-1 pl-5 text-xs">
                    {quoteDetail.lines.map((line) => (
                      <li key={line.channelId}>
                        <strong>{line.channelCode}</strong>：{line.calcTrace || '（无轨迹）'}
                      </li>
                    ))}
                  </ul>
                </details>
              ) : null}
            </section>
          ) : null}

          <section className="flex flex-col gap-2">
            <h2 className="text-lg font-medium">费率卡与分国家规则</h2>
            {rules && rules.cards.length > 0 ? (
              <div className="flex flex-col gap-1 text-sm">
                {rules.cards.map((card) => (
                  <div key={card.id} className="border-b py-1">
                    <strong>{card.channelCode}</strong>（{card.status}） · {rateCardSummary(card)}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-muted-foreground text-sm">
                还没有费率卡。此时试算会回退到渠道单值（线性估价），并在计算轨迹里标出用的是回退口径。
              </p>
            )}
            {rules && rules.countryRules.length > 0 ? (
              <p className="text-muted-foreground text-xs">
                分国家规则 {rules.countryRules.length} 条（偏远标记 + 试算时效区间）；未登记时效的渠道试算时不给
                时效，不编天数。
              </p>
            ) : null}
          </section>

          <section className="flex flex-col gap-2">
            <h2 className="text-lg font-medium">试算单（最近 {quotes?.total ?? 0} 条）</h2>
            {quotes && quotes.items.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-2">求值时刻</th>
                      <th className="p-2">目的国</th>
                      <th className="p-2">重量</th>
                      <th className="p-2">件数</th>
                      <th className="p-2">最低运费</th>
                      <th className="p-2">渠道数</th>
                      <th className="p-2">结果</th>
                      <th className="p-2">口径</th>
                    </tr>
                  </thead>
                  <tbody>
                    {quotes.items.map((row) => (
                      <tr key={row.id} className="border-b">
                        <td className="p-2">{row.quotedAt}</td>
                        <td className="p-2">{row.destCountry}</td>
                        <td className="p-2">{row.weightKg} kg</td>
                        <td className="p-2">{row.pieces}</td>
                        <td className="p-2">{moneyText(row.cheapestTotal, row.currency)}</td>
                        <td className="p-2">{row.channelCount}</td>
                        <td className="p-2">{quoteOutcomeText(row)}</td>
                        <td className="p-2">
                          <code>{row.costBasis}</code>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-muted-foreground text-sm">
                还没有试算单。「试算（不落库）」不会写库，「试算并保存试算单」才会。
              </p>
            )}
          </section>

          {capabilities ? (
            <section className="flex flex-col gap-1">
              <h2 className="text-lg font-medium">能力边界</h2>
              <p className="text-muted-foreground text-xs">{capabilities.estimationNote}</p>
              <ul className="text-muted-foreground list-disc pl-5 text-xs">
                {capabilities.dimensions
                  .filter((dimension) => !dimension.available)
                  .map((dimension) => (
                    <li key={dimension.dimension}>
                      {dimension.description}：未交付（归属 {dimension.ownerModule}）—— {dimension.impact}
                    </li>
                  ))}
              </ul>
            </section>
          ) : null}
        </>
      )}
    </div>
  );
}
