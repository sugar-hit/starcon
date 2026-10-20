'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  assumptionGaps,
  calculateProfit,
  decisionLabel,
  decisionTone,
  deleteProfitScenario,
  emptyRequest,
  fetchCalculatorCapabilities,
  fetchCalculatorSites,
  fetchProfitScenario,
  fetchProfitScenarios,
  missingGaps,
  missingRows,
  moneyText,
  moneyWithCurrency,
  percentText,
  quantityText,
  rowStatusText,
  rowStatusTone,
  saveProfitScenario,
  sourceKindLabel,
  sourceTone,
  summaryStats,
  tierSummary,
  validateRequest,
  waterfallRows,
} from './profit-calculator-model';
import type {
  CalculateRequest,
  ProfitCalculation,
  ProfitCalculatorCapabilities,
  ScenarioSummary,
  SiteOption,
  TierInput,
  ValidationError,
  WaterfallLine,
} from './profit-calculator-model';

/**
 * 5.3.1.1 利润测算器工作区。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「<b>这是定价决策的核心工具</b>，必须显式列出每一项扣减并允许展开查看来源，
 * 不能只给一个毛利率数字」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>决策就绪度</b>（成本不完整 / 含手工假设 / 全部来自上游规则）——
 *       成本不完整时**不给毛利率数字**，只给「剩余可覆盖缺失扣减的金额」，
 *       而且这个提示是页面顶部的常驻横幅，不是 tooltip。
 *   <li><b>利润瀑布图的每一项扣减</b>：缺的那几项也在行里，标成「未交付 / 该扣减未计入」，
 *       并写明归谁管、缺了会怎样、怎么补。
 *   <li><b>每一行都能展开看来源</b>：哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间。
 * </ol>
 *
 * <p>🔴 **null 不渲染成 0**：上游规则模块（5.2.3.1 平台费用、5.2.3.2 物流费用）未交付
 * ≠ 这笔费用是 0；包装成本没维护 ≠ 包装成本是 0。
 * 渲染成 0.00 的后果是单位成本偏低、毛利偏高，而页面上看不出任何异常。
 *
 * <p>路由取 {@code /pricing/calculator}，与 5.3.1.2 批量测算**共用** ——
 * 兄弟模块后续在别处加内容，不重写本页骨架。
 */
export function ProfitCalculatorWorkspace() {
  const [capabilities, setCapabilities] = useState<ProfitCalculatorCapabilities | null>(null);
  const [sites, setSites] = useState<SiteOption[]>([]);
  const [calculation, setCalculation] = useState<ProfitCalculation | null>(null);
  const [request, setRequest] = useState<CalculateRequest>(emptyRequest());
  const [scenarios, setScenarios] = useState<ScenarioSummary[]>([]);
  const [problem, setProblem] = useState<ValidationError | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [expanded, setExpanded] = useState<string[]>([]);

  const load = useCallback(async () => {
    try {
      const [caps, siteList, page] = await Promise.all([
        fetchCalculatorCapabilities(),
        fetchCalculatorSites().catch(() => [] as SiteOption[]),
        fetchProfitScenarios(0, 20).catch(() => null),
      ]);
      setCapabilities(caps);
      setSites(siteList);
      if (page) setScenarios(page.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '能力声明读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = useCallback((next: Partial<CalculateRequest>) => {
    setRequest((prev) => ({ ...prev, ...next }));
    setProblem(null);
  }, []);

  async function run() {
    const invalid = validateRequest(request);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await calculateProfit(request);
      setCalculation(result);
      setNotice(
        result.decisionReady
          ? '测算完成。'
          : '测算完成，但成本不完整 —— 本页**不给**毛利率数字，缺哪几项见下面的缺口清单。',
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '测算失败');
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    const invalid = validateRequest(request);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const saved = await saveProfitScenario(request);
      setNotice(
        `已保存为测算方案（${saved.summary.id.slice(0, 8)}）—— 参数、手工假设与成本窗口都在里面，可复算。`,
      );
      const page = await fetchProfitScenarios(0, 20).catch(() => null);
      if (page) setScenarios(page.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存方案失败');
    } finally {
      setBusy(false);
    }
  }

  async function openScenario(id: string) {
    setBusy(true);
    setError('');
    try {
      const detail = await fetchProfitScenario(id);
      setRequest(detail.request);
      setCalculation(detail.calculation);
      setNotice(`已打开方案（${id.slice(0, 8)}）并按当前成本复算。`);
    } catch (e) {
      setError(e instanceof Error ? e.message : '打开方案失败');
    } finally {
      setBusy(false);
    }
  }

  async function removeScenario(id: string) {
    setBusy(true);
    setError('');
    try {
      await deleteProfitScenario(id);
      const page = await fetchProfitScenarios(0, 20).catch(() => null);
      if (page) setScenarios(page.items);
      setNotice('方案已删除。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除方案失败');
    } finally {
      setBusy(false);
    }
  }

  function toggle(code: string) {
    setExpanded((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]));
  }

  const stats = useMemo(() => summaryStats(calculation), [calculation]);
  const gaps = useMemo(() => missingGaps(calculation), [calculation]);
  const assumptions = useMemo(() => assumptionGaps(calculation), [calculation]);
  const missing = useMemo(() => missingRows(calculation), [calculation]);
  const rows = useMemo(() => waterfallRows(calculation), [calculation]);
  const basis = calculation?.decisionBasis ?? null;

  return (
    <div className="space-y-4">
      <PageHeader
        title="利润测算器"
        description="输入 SKU + 目标店铺 + 售价，输出完整利润拆解：售价 → 平台费用 → 物流成本 → 采购成本 → 头程 → 包装 → 关税 → 其他 → 毛利额与毛利率；也可以给目标毛利率倒推可承受售价。"
      />

      {/* ============================================================ 决策就绪闸 */}
      <Notice
        tone={decisionTone(basis)}
        title={calculation ? `决策就绪度：${decisionLabel(basis)}` : '决策就绪度：还没测算'}
      >
        {calculation ? (
          <div className="space-y-1">
            <p className="font-semibold">{calculation.decisionNote}</p>
            {calculation.grossProfitSuppressedReason ? (
              <p>{calculation.grossProfitSuppressedReason}</p>
            ) : null}
            {calculation.revenueLeftForMissingDeductions !== null ? (
              <p>
                售价减掉已计入的扣减后，还剩{' '}
                <strong>
                  {moneyWithCurrency(
                    calculation.revenueLeftForMissingDeductions,
                    calculation.currency,
                  )}
                </strong>{' '}
                可用于覆盖缺失的扣减 —— <strong>这不是毛利</strong>
                ，缺的那几项扣掉之后可能就不剩什么了。
              </p>
            ) : null}
            <p className="text-copy-12">
              成本口径：{calculation.costMethod}
              {calculation.costEffectiveFrom ? ` · 生效于 ${calculation.costEffectiveFrom}` : ''} ·
              成本币种 {calculation.costCurrency}
              {calculation.fx.available && calculation.fx.sourceKind !== 'SAME_CURRENCY'
                ? ` · 汇率 ${calculation.fx.direction}`
                : ''}
            </p>
          </div>
        ) : (
          <p>填好下面的输入后点「测算」。</p>
        )}
      </Notice>

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="info" title="已处理">
          {notice}
        </Notice>
      ) : null}

      {/* ============================================================ 测算输入 */}
      <SectionCard
        title="测算输入"
        description="上游规则模块（5.2.3.1 平台费用、5.2.3.2 物流与头程费用、5.2.2.1 汇率查询）未交付 ⇒ 下面标「手工假设」的几项只能由人填，它们会显式标注来源，不会冒充系统口径。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField
            id="pc-sku"
            label="SKU ID"
            required
            error={problem?.field === 'skuId' ? problem.message : undefined}
          >
            <Input
              id="pc-sku"
              value={request.skuId}
              placeholder="UUID"
              onChange={(e) => patch({ skuId: e.target.value })}
            />
          </FormField>
          <FormField id="pc-site" label="目标店铺" hint="来自 2.1.1.1 平台目录">
            <select
              id="pc-site"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={request.siteCode}
              onChange={(e) => {
                const site = sites.find((s) => s.siteCode === e.target.value);
                patch({
                  siteCode: e.target.value,
                  platformCode: site?.platformCode ?? '',
                  currency: site?.defaultCurrency ?? request.currency,
                });
              }}
            >
              <option value="">不限定</option>
              {sites.map((site) => (
                <option key={site.siteCode} value={site.siteCode}>
                  {site.platformName} · {site.siteName}（{site.defaultCurrency ?? '—'}）
                </option>
              ))}
            </select>
          </FormField>
          <FormField
            id="pc-currency"
            label="售价币种"
            required
            error={problem?.field === 'currency' ? problem.message : undefined}
          >
            <Input
              id="pc-currency"
              value={request.currency}
              maxLength={3}
              onChange={(e) => patch({ currency: e.target.value.toUpperCase() })}
            />
          </FormField>
          <FormField
            id="pc-cost-currency"
            label="成本币种"
            hint="与售价币种不同就必须要汇率"
            error={problem?.field === 'costCurrency' ? problem.message : undefined}
          >
            <Input
              id="pc-cost-currency"
              value={request.costCurrency}
              maxLength={3}
              onChange={(e) => patch({ costCurrency: e.target.value.toUpperCase() })}
            />
          </FormField>
        </div>

        <div className="mt-3 grid gap-3 md:grid-cols-4">
          <FormField id="pc-mode" label="测算方向" required>
            <select
              id="pc-mode"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={request.mode}
              onChange={(e) => patch({ mode: e.target.value as CalculateRequest['mode'] })}
            >
              <option value="PRICE">按售价测算</option>
              <option value="TARGET_MARGIN">按目标毛利率倒推售价</option>
            </select>
          </FormField>
          {request.mode === 'PRICE' ? (
            <FormField
              id="pc-price"
              label="售价"
              required
              error={problem?.field === 'inputPrice' ? problem.message : undefined}
            >
              <Input
                id="pc-price"
                type="number"
                step="0.01"
                value={request.inputPrice ?? ''}
                onChange={(e) =>
                  patch({ inputPrice: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </FormField>
          ) : (
            <FormField
              id="pc-target-margin"
              label="目标毛利率（小数口径，0.25 = 25%）"
              required
              error={problem?.field === 'targetMarginRate' ? problem.message : undefined}
            >
              <Input
                id="pc-target-margin"
                type="number"
                step="0.01"
                value={request.targetMarginRate ?? ''}
                onChange={(e) =>
                  patch({ targetMarginRate: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </FormField>
          )}
          <FormField
            id="pc-quantity"
            label="数量（用于阶梯与运费）"
            error={problem?.field === 'quantity' ? problem.message : undefined}
          >
            <Input
              id="pc-quantity"
              type="number"
              step="1"
              value={request.quantity}
              onChange={(e) => patch({ quantity: Number(e.target.value) })}
            />
          </FormField>
          <FormField id="pc-note" label="方案备注" hint="为什么这样假设">
            <Input
              id="pc-note"
              value={request.note}
              onChange={(e) => patch({ note: e.target.value })}
            />
          </FormField>
        </div>

        <div className="border-border mt-4 rounded-md border p-3">
          <p className="text-label-13 font-semibold">
            手工假设（上游规则模块未交付时的唯一合法来源）
          </p>
          <p className="text-copy-12 text-muted-foreground mt-1">
            这几项在响应里会标成「手工假设」并点名将来由谁接管；能力声明里对应扣减项仍然是
            <strong>不可用</strong>。不填 ⇒ 该项显示「未交付 / 该扣减未计入」，且不给毛利率数字。
          </p>
          <div className="mt-3 grid gap-3 md:grid-cols-5">
            <FormField
              id="pc-fee-rate"
              label="平台费率（0.15 = 15%）"
              hint="5.2.3.1 平台费用"
              error={problem?.field === 'platformFeeRate' ? problem.message : undefined}
            >
              <Input
                id="pc-fee-rate"
                type="number"
                step="0.01"
                value={request.platformFeeRate ?? ''}
                onChange={(e) =>
                  patch({ platformFeeRate: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </FormField>
            <FormField id="pc-fee-fixed" label="平台固定费" hint="5.2.3.1 订阅费分摊">
              <Input
                id="pc-fee-fixed"
                type="number"
                step="0.01"
                value={request.platformFeeFixed ?? ''}
                onChange={(e) =>
                  patch({ platformFeeFixed: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </FormField>
            <FormField id="pc-logistics" label="物流每单位运费" hint="5.2.3.2 物流与头程费用">
              <Input
                id="pc-logistics"
                type="number"
                step="0.01"
                value={request.logisticsCostPerUnit ?? ''}
                onChange={(e) =>
                  patch({
                    logisticsCostPerUnit: e.target.value === '' ? null : Number(e.target.value),
                  })
                }
              />
            </FormField>
            <FormField
              id="pc-fx"
              label="汇率（1 成本币种 = X 售价币种）"
              hint="5.2.2.1 汇率查询"
              error={problem?.field === 'fxRate' ? problem.message : undefined}
            >
              <Input
                id="pc-fx"
                type="number"
                step="0.0001"
                value={request.fxRate ?? ''}
                onChange={(e) =>
                  patch({ fxRate: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </FormField>
            <FormField id="pc-fixed" label="月度固定成本" hint="算盈亏平衡销量用">
              <Input
                id="pc-fixed"
                type="number"
                step="0.01"
                value={request.monthlyFixedCost ?? ''}
                onChange={(e) =>
                  patch({ monthlyFixedCost: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </FormField>
          </div>

          <div className="mt-3">
            <div className="flex items-center justify-between">
              <p className="text-label-13 font-semibold">物流阶梯（数量 → 每单位运费）</p>
              <Button
                type="button"
                size="sm"
                onClick={() =>
                  patch({
                    logisticsTiers: [
                      ...request.logisticsTiers,
                      { upToQuantity: null, perUnitCost: 0 },
                    ] as TierInput[],
                  })
                }
              >
                加一段
              </Button>
            </div>
            <p className="text-copy-12 text-muted-foreground mt-1">
              {tierSummary(request.logisticsTiers)}；最后一段必须无上限（不填「覆盖到第几单」）。
            </p>
            {request.logisticsTiers.length ? (
              <div className="mt-2 space-y-2">
                {request.logisticsTiers.map((tier, index) => (
                  <div key={index} className="flex flex-wrap items-end gap-2">
                    <FormField id={`pc-tier-up-${index}`} label="覆盖到第几单（空 = 无上限）">
                      <Input
                        id={`pc-tier-up-${index}`}
                        type="number"
                        step="1"
                        value={tier.upToQuantity ?? ''}
                        onChange={(e) => {
                          const next = [...request.logisticsTiers];
                          next[index] = {
                            ...tier,
                            upToQuantity: e.target.value === '' ? null : Number(e.target.value),
                          };
                          patch({ logisticsTiers: next });
                        }}
                      />
                    </FormField>
                    <FormField id={`pc-tier-cost-${index}`} label="每单位运费">
                      <Input
                        id={`pc-tier-cost-${index}`}
                        type="number"
                        step="0.01"
                        value={tier.perUnitCost}
                        onChange={(e) => {
                          const next = [...request.logisticsTiers];
                          next[index] = { ...tier, perUnitCost: Number(e.target.value) };
                          patch({ logisticsTiers: next });
                        }}
                      />
                    </FormField>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      onClick={() =>
                        patch({
                          logisticsTiers: request.logisticsTiers.filter((_, i) => i !== index),
                        })
                      }
                    >
                      删掉这段
                    </Button>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        </div>

        <div className="mt-4 flex flex-wrap gap-2">
          <Button type="button" onClick={run} disabled={busy}>
            {busy ? '测算中……' : '测算'}
          </Button>
          <Button type="button" variant="secondary" onClick={save} disabled={busy}>
            保存为方案
          </Button>
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              setRequest(emptyRequest());
              setCalculation(null);
              setProblem(null);
              setNotice('');
            }}
            disabled={busy}
          >
            清空
          </Button>
        </div>
      </SectionCard>

      {/* ============================================================ 关键数字 */}
      {stats.length ? (
        <div className="grid gap-3 md:grid-cols-4">
          {stats.map((stat) => (
            <StatCard key={stat.label} label={stat.label} value={stat.value} />
          ))}
        </div>
      ) : null}

      {/* ============================================================ 利润瀑布图 */}
      <SectionCard
        title="利润瀑布图"
        description="每一项扣减都列出来，每一行都能展开看来源（哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间）。缺项也**在**行里 —— 把它删掉就等于假装它不存在。"
      >
        {rows.length ? (
          <div className="space-y-2">
            {rows.map((row) => (
              <WaterfallRow
                key={row.code}
                row={row}
                expanded={expanded.includes(row.code)}
                onToggle={() => toggle(row.code)}
              />
            ))}
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在测算……</p>
        ) : (
          <EmptyState
            title="还没有测算结果"
            description="填好 SKU 与售价，点「测算」就能看到完整拆解。"
          />
        )}
      </SectionCard>

      {/* ============================================================ 缺口与口径 */}
      {calculation ? (
        <SectionCard
          title="缺口与口径"
          description="每一项取不到的扣减都要写清：归谁管、缺了会怎样、怎么补。这一栏是本页存在的理由 —— 缺项不写清楚，页面上那个数字就会被人当成能用来改价的毛利率。"
        >
          <div className="space-y-3">
            {gaps.length ? (
              <div className="space-y-2">
                {gaps.map((gap) => (
                  <div key={gap.code} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="danger">取不到</Badge>
                      <span className="text-label-13 font-semibold">{gap.label}</span>
                      <span className="text-copy-12 text-muted-foreground">
                        归 {gap.ownerModule}
                      </span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">影响：{gap.impact}</p>
                    <p className="text-copy-12 mt-1">怎么补：{gap.remedy}</p>
                  </div>
                ))}
              </div>
            ) : (
              <Notice tone="success" title="没有取不到的扣减">
                每一项扣减都算进去了。
              </Notice>
            )}

            {assumptions.length ? (
              <div className="space-y-2">
                <p className="text-label-13 font-semibold">
                  口径提醒（已用人工假设补上，但不是系统口径）
                </p>
                {assumptions.map((gap) => (
                  <div key={gap.code} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="warning">手工假设</Badge>
                      <span className="text-label-13 font-semibold">{gap.label}</span>
                      <span className="text-copy-12 text-muted-foreground">
                        归 {gap.ownerModule}
                      </span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{gap.impact}</p>
                  </div>
                ))}
              </div>
            ) : null}

            {missing.length ? (
              <Notice tone="danger" title={`${missing.length} 项扣减未计入`}>
                {missing.map((row) => `${row.label}（${row.ownerModule}）`).join('、')} —— 页面上的
                「剩余可覆盖缺失扣减的金额」要先扣掉它们才剩得下。
              </Notice>
            ) : null}

            <Notice tone="info" title="汇率口径">
              {calculation.fx.note}
            </Notice>
          </div>
        </SectionCard>
      ) : null}

      {/* ============================================================ 能力声明 */}
      <SectionCard
        title="能力声明"
        description="本页的每一项扣减分别从哪来、能不能取到、归谁管。蓝图里的「上游 = 5.2 全部」是卷级引用，卷内每个模块的真实交付状态在这里逐项写明。"
      >
        {capabilities ? (
          <div className="space-y-3">
            <div className="space-y-2">
              {capabilities.deductions.map((deduction) => (
                <div key={deduction.code} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 font-semibold">{deduction.label}</span>
                    <Badge tone={deduction.available ? 'success' : 'danger'}>
                      {deduction.available ? '可取到' : '未交付'}
                    </Badge>
                    <Badge tone={sourceTone(deduction.sourceKind)}>
                      {sourceKindLabel(deduction.sourceKind)}
                    </Badge>
                    <span className="text-copy-12 text-muted-foreground">
                      归 {deduction.ownerModule}
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">口径：{deduction.basis}</p>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    影响：{deduction.impact}
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">{deduction.note}</p>
                </div>
              ))}
            </div>
            <div className="grid gap-2 md:grid-cols-2">
              <Notice tone="danger" title="决策就绪闸">
                {capabilities.decisionGateNote}
              </Notice>
              <Notice tone="info" title="汇率">
                {capabilities.fxNote}
              </Notice>
              <Notice tone="info" title="成本口径">
                {capabilities.costBasisNote}
              </Notice>
              <Notice tone="info" title="上游">
                {capabilities.upstreamNote}
              </Notice>
              <Notice tone="info" title="下游">
                {capabilities.downstreamNote}
              </Notice>
              <Notice tone="info" title="两种测算方向">
                {capabilities.modeNote}
              </Notice>
              <Notice tone="info" title="方案与分享">
                {capabilities.actionNote}
              </Notice>
              <Notice tone="info" title="精度">
                {capabilities.sourceNote}
              </Notice>
            </div>
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在读能力声明……</p>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读不到。</p>
        )}
      </SectionCard>

      {/* ============================================================ 测算方案 */}
      <SectionCard
        title="测算方案"
        description="把一次测算的参数、手工假设、物流阶梯与成本窗口存下来，同租户的人拿同一个方案能复算出同一个结果。"
      >
        {scenarios.length ? (
          <div className="space-y-2">
            {scenarios.map((scenario) => (
              <div
                key={scenario.id}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
              >
                <span className="text-label-13 font-semibold">
                  {scenario.skuCode ?? scenario.skuId.slice(0, 8)}
                </span>
                <span className="text-copy-12 text-muted-foreground">
                  {scenario.siteCode || '不限站点'} · {scenario.currency}
                  {scenario.mode === 'PRICE'
                    ? ` · 售价 ${moneyText(scenario.inputPrice)}`
                    : ` · 目标毛利率 ${percentText(scenario.targetMarginRate, '—')}`}
                  {' · 数量 '}
                  {quantityText(scenario.quantity)}
                </span>
                <Badge tone={decisionTone(scenario.decisionBasis)}>
                  {decisionLabel(scenario.decisionBasis)}
                </Badge>
                <span className="text-copy-12 text-muted-foreground">
                  保存于 {scenario.updatedAt}
                </span>
                <span className="ml-auto flex gap-2">
                  <Button type="button" size="sm" onClick={() => openScenario(scenario.id)}>
                    打开并复算
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    onClick={() => removeScenario(scenario.id)}
                  >
                    删除
                  </Button>
                </span>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="还没有测算方案"
            description="测算之后点「保存为方案」，参数与手工假设就会存下来，别人可以复算。"
          />
        )}
      </SectionCard>
    </div>
  );
}

/** 瀑布图的一行（可展开看来源）。 */
function WaterfallRow({
  row,
  expanded,
  onToggle,
}: {
  row: WaterfallLine;
  expanded: boolean;
  onToggle: () => void;
}) {
  const source = row.source;
  return (
    <div className="border-border rounded-md border">
      <div className="flex flex-wrap items-center gap-2 p-3">
        <span className="text-label-13 font-semibold">{row.label}</span>
        {row.deduction ? (
          <Badge tone={rowStatusTone(row)}>{rowStatusText(row)}</Badge>
        ) : (
          <Badge tone="info">{row.deduction ? '扣减' : '结果'}</Badge>
        )}
        <Badge tone={sourceTone(row.sourceKind)}>{sourceKindLabel(row.sourceKind)}</Badge>
        <span className="text-copy-12 text-muted-foreground">{row.ownerModule}</span>
        <span className="text-copy-13 ml-auto font-mono">
          {row.available
            ? `${moneyText(row.amount)} / 单位${
                row.totalAmount !== null && row.totalAmount !== row.amount
                  ? ` · 合计 ${moneyText(row.totalAmount)}`
                  : ''
              }`
            : (row.unavailableReason ?? '未计入')}
        </span>
        {source ? (
          <Button type="button" size="sm" variant="ghost" onClick={onToggle}>
            {expanded ? '收起来源' : '展开来源'}
          </Button>
        ) : null}
      </div>
      {expanded && source ? (
        <div className="border-border bg-muted/30 space-y-2 border-t p-3">
          <dl className="grid gap-1 md:grid-cols-2">
            <div>
              <dt className="text-copy-12 text-muted-foreground">归谁管</dt>
              <dd className="text-copy-13">{source.ownerModule}</dd>
            </div>
            <div>
              <dt className="text-copy-12 text-muted-foreground">哪张表</dt>
              <dd className="text-copy-13">{source.table}</dd>
            </div>
            <div>
              <dt className="text-copy-12 text-muted-foreground">什么口径</dt>
              <dd className="text-copy-13">{source.basis}</dd>
            </div>
            <div>
              <dt className="text-copy-12 text-muted-foreground">什么时候生效</dt>
              <dd className="text-copy-13">{source.effectiveFrom ?? '—'}</dd>
            </div>
          </dl>
          {source.trace ? <p className="text-copy-12 mt-1">{source.trace}</p> : null}
          {source.details.length ? (
            <dl className="space-y-1">
              {source.details.map((detail) => (
                <div key={detail.label} className="flex flex-wrap gap-2">
                  <dt className="text-copy-12 text-muted-foreground">{detail.label}</dt>
                  <dd className="text-copy-12">{detail.value}</dd>
                </div>
              ))}
            </dl>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
