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
  EMPTY_FREIGHT_ACTUAL_FILTERS,
  EMPTY_FREIGHT_RULE_FILTERS,
  EMPTY_FREIGHT_SURCHARGE_FILTERS,
  EMPTY_HEAD_STRUCTURE_FILTERS,
  actualSourceLabel,
  chargeBasisLabel,
  createFreightRule,
  createFreightSurcharge,
  createHeadStructure,
  dayText,
  disableFreightRule,
  disableFreightSurcharge,
  disableHeadStructure,
  fetchFreightActuals,
  fetchFreightEstimate,
  fetchFreightRule,
  fetchFreightRuleList,
  fetchFreightSurcharges,
  fetchFreightVariance,
  fetchHeadFreightEstimate,
  fetchHeadStructure,
  fetchHeadStructures,
  fetchLogisticsFreightCapabilities,
  headCalcBasisLabel,
  headComponentTypeLabel,
  headScopeText,
  moneyText,
  moneyWithCurrency,
  priceModeLabel,
  rateText,
  recordFreightActual,
  ruleScopeText,
  statusLabel,
  surchargeBasisLabel,
  surchargeTypeLabel,
  supersedeFreightRule,
  timeText,
  validateActual,
  validateDisableReason,
  validateHeadStructure,
  validateRule,
  validateSurcharge,
  varianceHeadline,
  varianceText,
  varianceTone,
  voidFreightActual,
} from './logistics-freight-model';
import type {
  FreightActualFilters,
  FreightActualInput,
  FreightActualListPage,
  FreightEstimate,
  FreightRuleDetail,
  FreightRuleFilters,
  FreightRuleInput,
  FreightRuleListPage,
  FreightSurchargeFilters,
  FreightSurchargeInput,
  FreightSurchargeListPage,
  FreightTierInput,
  FreightVarianceReport,
  HeadFreightEstimate,
  HeadFreightStructureInput,
  HeadFreightStructureListPage,
  HeadStructureFilters,
  LogisticsFreightCapabilities,
} from './logistics-freight-model';

/**
 * 5.2.3.2 物流与头程费用工作区。
 *
 * <p>蓝图：「维护物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、偏远附加）
 * 与头程费用结构，用于测算运费占成本比。」输出「费用规则集 + 测算输入」，
 * 上游 = 10 物流与头程（卷级引用），下游 = 5.3.1.1 测算。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 规则与实际运费是**两个独立的事实**，差额要能算出来并被展示；
 * 算不出来的时候必须说「算不出来」，**绝不显示 0.00**。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>两个口径并排</b>：「渠道计费规则（事前测算口径）」与「实际运费（事后对账口径）」
 *       是两块独立的区域，各自带自己的来源标注。实际运费当前**只有人工登记**一种来源
 *       （上游 10 还没有可读的实际运费结算事实），页面上如实写明，**不冒充结算结果**。
 *   <li><b>测算逐步拆解</b>：运费测算器把「计费量 → 命中档位 → 基础运费 → 附加费 → 合计」
 *       每一步的算式写出来，页面上**没有**任何一列是裸的运费数字。
 *   <li><b>🔴 算不出来就说算不出来</b>：没有生效规则 / 规则落不进档 / 附加费币种不一致时，
 *       显示「算不出来」+ 原因 —— **任何情况下都不显示「运费 0.00」**。
 * </ol>
 *
 * <p>🔴 **null 不渲染成 0**：规则没配 ≠ 这票运费是 0；物流商没收钱 ≠ 算不出来。
 * 两者的区别在页面上必须看得出来。
 *
 * <p>🔴 **分区块结构**：5.2.3.2 物流与头程费用与 5.2.3.1 平台费用共用 L3 路由
 * {@code /pricing/fees}。本模块只占「物流与头程费用」这一块，不重写兄弟模块的页面骨架。
 */
export function LogisticsFreightWorkspace() {
  const [capabilities, setCapabilities] = useState<LogisticsFreightCapabilities | null>(null);
  const [rulePage, setRulePage] = useState<FreightRuleListPage | null>(null);
  const [ruleFilters, setRuleFilters] = useState<FreightRuleFilters>(EMPTY_FREIGHT_RULE_FILTERS);
  const [ruleDetail, setRuleDetail] = useState<FreightRuleDetail | null>(null);
  const [surchargePage, setSurchargePage] = useState<FreightSurchargeListPage | null>(null);
  const [surchargeFilters, setSurchargeFilters] = useState<FreightSurchargeFilters>(
    EMPTY_FREIGHT_SURCHARGE_FILTERS,
  );
  const [headPage, setHeadPage] = useState<HeadFreightStructureListPage | null>(null);
  const [headFilters, setHeadFilters] = useState<HeadStructureFilters>(
    EMPTY_HEAD_STRUCTURE_FILTERS,
  );
  const [actualPage, setActualPage] = useState<FreightActualListPage | null>(null);
  const [actualFilters, setActualFilters] = useState<FreightActualFilters>(
    EMPTY_FREIGHT_ACTUAL_FILTERS,
  );
  const [estimate, setEstimate] = useState<FreightEstimate | null>(null);
  const [headEstimate, setHeadEstimate] = useState<HeadFreightEstimate | null>(null);
  const [variance, setVariance] = useState<FreightVarianceReport | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const [ruleForm, setRuleForm] = useState<FreightRuleInput>(emptyRuleForm());
  const [ruleProblems, setRuleProblems] = useState<string[]>([]);
  const [ruleTierRows, setRuleTierRows] = useState<FreightTierInput[]>([emptyTier(1)]);
  const [supersedingRuleId, setSupersedingRuleId] = useState<string | null>(null);

  const [surchargeForm, setSurchargeForm] = useState<FreightSurchargeInput>(emptySurchargeForm());
  const [surchargeProblems, setSurchargeProblems] = useState<string[]>([]);

  const [headForm, setHeadForm] = useState<HeadFreightStructureInput>(emptyHeadForm());
  const [headProblems, setHeadProblems] = useState<string[]>([]);

  const [actualForm, setActualForm] = useState<FreightActualInput>(emptyActualForm());
  const [actualProblems, setActualProblems] = useState<string[]>([]);

  const [estimateForm, setEstimateForm] = useState({
    channelCode: '',
    carrierCode: '',
    shippingMethodCode: '',
    chargeBasis: '',
    weightKg: '',
    lengthCm: '',
    widthCm: '',
    heightCm: '',
    pieces: '',
  });
  const [headEstimateForm, setHeadEstimateForm] = useState({
    shippingMethodCode: '',
    originCode: '',
    destinationCode: '',
    shipments: '1',
    cbm: '',
    containers: '',
    weightKg: '',
  });

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [caps, rules, surcharges, heads, actuals] = await Promise.all([
        fetchLogisticsFreightCapabilities(),
        fetchFreightRuleList(ruleFilters),
        fetchFreightSurcharges(surchargeFilters),
        fetchHeadStructures(headFilters),
        fetchFreightActuals(actualFilters),
      ]);
      setCapabilities(caps);
      setRulePage(rules);
      setSurchargePage(surcharges);
      setHeadPage(heads);
      setActualPage(actuals);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '物流与头程费用读不到');
    } finally {
      setBusy(false);
    }
  }, [ruleFilters, surchargeFilters, headFilters, actualFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  const varianceSummary = useMemo(() => varianceHeadline(variance), [variance]);

  // ================================================================ 动作

  async function submitRule() {
    const input = { ...ruleForm, tiers: ruleTierRows };
    const problems = validateRule(input);
    setRuleProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      if (supersedingRuleId) {
        await supersedeFreightRule(supersedingRuleId, input);
        setNotice(
          '已新增一个版本并取代旧版本：已生效的规则不可改写，改规则 = 新增版本 + 旧版本让位。',
        );
        setSupersedingRuleId(null);
      } else {
        await createFreightRule(input);
        setNotice('渠道计费规则已建立（规则与阶梯同事务写入）。');
      }
      setRuleForm(emptyRuleForm());
      setRuleTierRows([emptyTier(1)]);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function stopRule(ruleId: string) {
    const reason = window.prompt('停用理由（必填）：停用不是删除，旧版本仍在库里可追溯。');
    if (reason === null) return;
    const problems = validateDisableReason(reason);
    if (problems.length) {
      setError(problems[0] ?? '必须写理由');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await disableFreightRule(ruleId, reason);
      setNotice('规则已停用（生效区间已关到停用时刻，之后不再参与测算）。');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '停用失败');
    } finally {
      setBusy(false);
    }
  }

  async function openRule(ruleId: string) {
    setBusy(true);
    setError('');
    try {
      setRuleDetail(await fetchFreightRule(ruleId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '规则明细读不到');
    } finally {
      setBusy(false);
    }
  }

  async function submitSurcharge() {
    const problems = validateSurcharge(surchargeForm);
    setSurchargeProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      await createFreightSurcharge(surchargeForm);
      setNotice('附加费已登记。');
      setSurchargeForm(emptySurchargeForm());
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function stopSurcharge(surchargeId: string) {
    const reason = window.prompt('停用理由（必填）：');
    if (reason === null) return;
    const problems = validateDisableReason(reason);
    if (problems.length) {
      setError(problems[0] ?? '必须写理由');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await disableFreightSurcharge(surchargeId, reason);
      setNotice('附加费已停用。');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '停用失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitHeadStructure() {
    const problems = validateHeadStructure(headForm);
    setHeadProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      await createHeadStructure(headForm);
      setNotice('头程费用结构已建立（结构与成本项同事务写入）。');
      setHeadForm(emptyHeadForm());
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function stopHeadStructure(structureId: string) {
    const reason = window.prompt('停用理由（必填）：');
    if (reason === null) return;
    const problems = validateDisableReason(reason);
    if (problems.length) {
      setError(problems[0] ?? '必须写理由');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await disableHeadStructure(structureId, reason);
      setNotice('头程费用结构已停用。');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '停用失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitActual() {
    const problems = validateActual(actualForm);
    setActualProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      await recordFreightActual(actualForm);
      setNotice('实际运费已登记（人工登记的物流商账单观察值）。');
      setActualForm(emptyActualForm());
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function voidActual(actualId: string) {
    const reason = window.prompt('作废理由（必填）：作废不是删除，旧行仍在库里。');
    if (reason === null) return;
    const problems = validateDisableReason(reason);
    if (problems.length) {
      setError(problems[0] ?? '必须写理由');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await voidFreightActual(actualId, reason);
      setNotice('实际运费已作废，不再进入运费差异。');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '作废失败');
    } finally {
      setBusy(false);
    }
  }

  async function runEstimate() {
    setBusy(true);
    setError('');
    try {
      setEstimate(
        await fetchFreightEstimate({
          channelCode: estimateForm.channelCode.trim(),
          carrierCode: estimateForm.carrierCode.trim() || null,
          shippingMethodCode: estimateForm.shippingMethodCode.trim(),
          chargeBasis: estimateForm.chargeBasis || null,
          weightKg: estimateForm.weightKg.trim() || null,
          lengthCm: estimateForm.lengthCm.trim() || null,
          widthCm: estimateForm.widthCm.trim() || null,
          heightCm: estimateForm.heightCm.trim() || null,
          pieces: estimateForm.pieces.trim() || null,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '运费测算失败');
    } finally {
      setBusy(false);
    }
  }

  async function runHeadEstimate() {
    setBusy(true);
    setError('');
    try {
      setHeadEstimate(
        await fetchHeadFreightEstimate({
          shippingMethodCode: headEstimateForm.shippingMethodCode.trim(),
          originCode: headEstimateForm.originCode.trim(),
          destinationCode: headEstimateForm.destinationCode.trim(),
          shipments: headEstimateForm.shipments.trim() || null,
          cbm: headEstimateForm.cbm.trim() || null,
          containers: headEstimateForm.containers.trim() || null,
          weightKg: headEstimateForm.weightKg.trim() || null,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '头程费测算失败');
    } finally {
      setBusy(false);
    }
  }

  async function runVariance() {
    setBusy(true);
    setError('');
    try {
      setVariance(
        await fetchFreightVariance({
          channelCode: actualFilters.channelCode.trim() || null,
          shippingMethodCode: actualFilters.shippingMethodCode.trim() || null,
          from: actualFilters.from || today(),
          to: actualFilters.to || today(),
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '运费差异读不到');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="物流与头程费用"
        description="物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、偏远附加）与头程费用结构，用于测算运费占成本比。"
      />

      {error ? (
        <Notice tone="danger" title="出错">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {/* ============================================================ 能力声明 */}
      <SectionCard
        title="口径声明（必看）"
        description="规则与实际运费是两个独立的事实；差额要算得出来并被展示，算不出来的时候必须说「算不出来」。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <Notice tone="warning" title="两个口径，两份独立的事实">
              <div className="space-y-1">
                <p className="font-semibold">规则（事前测算口径）＝ 这票按规则应该付多少运费</p>
                <p>实际运费（事后对账口径）＝ 物流商实际收了多少</p>
                <p>运费差异 ＝ 实际 − 按规则测算。两者永不合并成一个字段。</p>
              </div>
            </Notice>

            <div>
              <p className="text-label-13 mb-2 font-semibold">
                逐维度能力（取不到 / 未交付的写明归谁管）
              </p>
              <div className="space-y-2">
                {capabilities.dimensions.map((d) => (
                  <div key={d.code} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 font-semibold">{d.label}</span>
                      <Badge tone={d.available ? 'success' : 'warning'}>
                        {d.available ? '已交付' : '未交付'}
                      </Badge>
                      <span className="text-copy-12 text-muted-foreground">{d.ownerModule}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{d.impact}</p>
                    {d.note ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">{d.note}</p>
                    ) : null}
                  </div>
                ))}
              </div>
            </div>

            <div className="grid gap-2 md:grid-cols-2">
              <Notice tone="info" title="渠道计费规则口径">
                {capabilities.ruleBasis}
              </Notice>
              <Notice tone="info" title="附加费口径">
                {capabilities.surchargeBasis}
              </Notice>
              <Notice tone="info" title="头程费用结构口径">
                {capabilities.headBasis}
              </Notice>
              <Notice tone="info" title="实际运费口径">
                {capabilities.actualBasis}
              </Notice>
              <Notice tone="info" title="运费差异口径">
                {capabilities.varianceBasis}
              </Notice>
              <Notice tone="info" title="上游">
                {capabilities.upstreamNote}
              </Notice>
              <Notice tone="info" title="下游">
                {capabilities.downstreamNote}
              </Notice>
              <Notice tone="info" title="可做的动作">
                {capabilities.actionsNote}
              </Notice>
            </div>
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在读能力声明……</p>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读不到。</p>
        )}
      </SectionCard>

      {/* ============================================================ 渠道计费规则台账 */}
      <SectionCard
        title="渠道计费规则（事前测算口径）"
        description="一条规则 = 一个（渠道 / 承运商 / 运输方式 / 计费口径）的费率口径，按生效区间取用。已生效的规则不可改写。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="freight-kw" label="关键词" hint="渠道码 / 承运商 / 运输方式 / 备注">
              <Input
                id="freight-kw"
                value={ruleFilters.keyword}
                placeholder="全部"
                onChange={(e) =>
                  setRuleFilters({ ...ruleFilters, keyword: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="freight-channel" label="渠道码">
              <Input
                id="freight-channel"
                value={ruleFilters.channelCode}
                placeholder="全部"
                onChange={(e) =>
                  setRuleFilters({ ...ruleFilters, channelCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="freight-method" label="运输方式码">
              <Input
                id="freight-method"
                value={ruleFilters.shippingMethodCode}
                placeholder="全部"
                onChange={(e) =>
                  setRuleFilters({ ...ruleFilters, shippingMethodCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="freight-basis" label="计费口径">
              <select
                id="freight-basis"
                className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                value={ruleFilters.chargeBasis}
                onChange={(e) =>
                  setRuleFilters({ ...ruleFilters, chargeBasis: e.target.value, page: 0 })
                }
              >
                <option value="">全部</option>
                {(capabilities?.chargeBases ?? []).map((b) => (
                  <option key={b.value} value={b.value}>
                    {b.label}
                  </option>
                ))}
              </select>
            </FormField>
          </div>

          {busy && !loaded ? (
            <p className="text-muted-foreground text-copy-13">正在读规则……</p>
          ) : rulePage && rulePage.items.length ? (
            <div className="space-y-2">
              {rulePage.items.map((rule) => (
                <div key={rule.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 font-semibold">{ruleScopeText(rule)}</span>
                    <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'warning'}>
                      {statusLabel(rule.status)}
                    </Badge>
                    <Badge tone="info">第 {rule.versionNo} 版</Badge>
                    <span className="text-copy-12 text-muted-foreground">
                      {rule.currency} · {rule.tierCount} 档
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    生效 {timeText(rule.effectiveFrom)} ~ {timeText(rule.effectiveTo, '长期有效')}
                  </p>
                  {rule.disableReason ? (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      停用理由：{rule.disableReason}
                    </p>
                  ) : null}
                  <div className="mt-2 flex flex-wrap gap-2">
                    <Button size="sm" variant="secondary" onClick={() => void openRule(rule.id)}>
                      看阶梯
                    </Button>
                    {rule.status === 'ACTIVE' ? (
                      <>
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => {
                            setSupersedingRuleId(rule.id);
                            setRuleForm({
                              channelCode: rule.channelCode,
                              channelName: rule.channelName,
                              carrierCode: rule.carrierCode,
                              carrierName: rule.carrierName,
                              shippingMethodCode: rule.shippingMethodCode,
                              shippingMethodName: rule.shippingMethodName,
                              chargeBasis: rule.chargeBasis,
                              currency: rule.currency,
                              volumetricDivisor: rule.volumetricDivisor,
                              minimumCharge: rule.minimumCharge,
                              effectiveFrom: nowIso(),
                              effectiveTo: rule.effectiveTo,
                              note: rule.note,
                              tiers: [],
                            });
                            setRuleTierRows([emptyTier(1)]);
                            setRuleProblems([]);
                          }}
                        >
                          新增版本
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => void stopRule(rule.id)}>
                          停用
                        </Button>
                      </>
                    ) : null}
                  </div>
                </div>
              ))}
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={rulePage.page <= 0}
                  onClick={() => setRuleFilters({ ...ruleFilters, page: rulePage.page - 1 })}
                >
                  上一页
                </Button>
                <span className="text-copy-12 text-muted-foreground">
                  第 {rulePage.page + 1} / {Math.max(1, Math.ceil(rulePage.total / rulePage.size))}{' '}
                  页 · 共 {rulePage.total} 条
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={rulePage.page + 1 >= Math.ceil(rulePage.total / rulePage.size)}
                  onClick={() => setRuleFilters({ ...ruleFilters, page: rulePage.page + 1 })}
                >
                  下一页
                </Button>
              </div>
            </div>
          ) : (
            <EmptyState
              title="还没有渠道计费规则"
              description="规则没配 ⇒ 运费测算不出来，而页面上看不出任何异常。渠道计费规则挂在「渠道 / 承运商 / 运输方式 / 计费口径」四个维度上。"
            />
          )}

          {ruleDetail ? (
            <div className="border-border rounded-md border p-3">
              <p className="text-label-13 font-semibold">阶梯明细 · {ruleDetail.scopeBasis}</p>
              <div className="mt-2 space-y-1">
                {ruleDetail.tiers.length ? (
                  ruleDetail.tiers.map((tier) => (
                    <p key={tier.id} className="text-copy-12">
                      第 {tier.seq} 档 [{tier.minValue ?? '—'}, {tier.maxValue ?? '∞'}) ·{' '}
                      {priceModeLabel(tier.priceMode)} ·{' '}
                      {tier.priceMode === 'FLAT_PER_TIER'
                        ? `整档价 ${moneyWithCurrency(tier.flatPrice, ruleDetail.rule.currency)}`
                        : `首段上限 ${tier.firstLimit ?? '—'} · 首段单价 ${moneyWithCurrency(
                            tier.firstPrice,
                            ruleDetail.rule.currency,
                          )} · 续段单价 ${moneyWithCurrency(
                            tier.additionalPrice,
                            ruleDetail.rule.currency,
                          )}`}
                    </p>
                  ))
                ) : (
                  <p className="text-copy-12 text-muted-foreground">这条规则没有阶梯。</p>
                )}
              </div>
              <p className="text-copy-12 text-muted-foreground mt-2">{ruleDetail.note}</p>
            </div>
          ) : null}
        </div>
      </SectionCard>

      {/* ============================================================ 规则表单 */}
      <SectionCard
        title={supersedingRuleId ? '新增规则版本' : '新建渠道计费规则'}
        description="规则与阶梯同事务写入。已生效的规则不可改写 —— 改规则 = 新增一条新版本 + 旧版本让位。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="rule-channel" label="渠道码" required>
              <Input
                id="rule-channel"
                value={ruleForm.channelCode}
                placeholder="YUNDA"
                onChange={(e) => setRuleForm({ ...ruleForm, channelCode: e.target.value })}
              />
            </FormField>
            <FormField id="rule-channel-name" label="渠道名（快照）">
              <Input
                id="rule-channel-name"
                value={ruleForm.channelName ?? ''}
                placeholder="韵达"
                onChange={(e) => setRuleForm({ ...ruleForm, channelName: e.target.value })}
              />
            </FormField>
            <FormField id="rule-carrier" label="承运商码（可空 = 通用）">
              <Input
                id="rule-carrier"
                value={ruleForm.carrierCode ?? ''}
                placeholder="留空 = 该渠道通用费率"
                onChange={(e) => setRuleForm({ ...ruleForm, carrierCode: e.target.value })}
              />
            </FormField>
            <FormField id="rule-method" label="运输方式码" required>
              <Input
                id="rule-method"
                value={ruleForm.shippingMethodCode}
                placeholder="EXPRESS"
                onChange={(e) => setRuleForm({ ...ruleForm, shippingMethodCode: e.target.value })}
              />
            </FormField>
            <FormField id="rule-charge-basis" label="计费口径" required>
              <select
                id="rule-charge-basis"
                className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                value={ruleForm.chargeBasis}
                onChange={(e) => setRuleForm({ ...ruleForm, chargeBasis: e.target.value })}
              >
                {(capabilities?.chargeBases ?? []).map((b) => (
                  <option key={b.value} value={b.value}>
                    {b.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="rule-currency" label="币种" required>
              <Input
                id="rule-currency"
                value={ruleForm.currency}
                placeholder="CNY"
                onChange={(e) => setRuleForm({ ...ruleForm, currency: e.target.value })}
              />
            </FormField>
            <FormField id="rule-vol" label="体积重换算系数（cm³/kg，可空）">
              <Input
                id="rule-vol"
                value={ruleForm.volumetricDivisor ?? ''}
                placeholder="5000"
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, volumetricDivisor: e.target.value || null })
                }
              />
            </FormField>
            <FormField id="rule-min" label="最低收费（可空）">
              <Input
                id="rule-min"
                value={ruleForm.minimumCharge ?? ''}
                placeholder="20.00"
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, minimumCharge: e.target.value || null })
                }
              />
            </FormField>
            <FormField id="rule-from" label="生效起点" required>
              <Input
                id="rule-from"
                type="datetime-local"
                value={ruleForm.effectiveFrom}
                onChange={(e) => setRuleForm({ ...ruleForm, effectiveFrom: e.target.value })}
              />
            </FormField>
            <FormField id="rule-to" label="生效终点（可空）">
              <Input
                id="rule-to"
                type="datetime-local"
                value={ruleForm.effectiveTo ?? ''}
                onChange={(e) => setRuleForm({ ...ruleForm, effectiveTo: e.target.value || null })}
              />
            </FormField>
          </div>

          <div className="space-y-2">
            <p className="text-label-13 font-semibold">阶梯档位</p>
            {ruleTierRows.map((tier, index) => (
              <div key={index} className="grid gap-2 md:grid-cols-7">
                <FormField id={`tier-seq-${index}`} label="序号">
                  <Input
                    id={`tier-seq-${index}`}
                    type="number"
                    value={String(tier.seq)}
                    onChange={(e) =>
                      setRuleTierRows(
                        ruleTierRows.map((t, i) =>
                          i === index ? { ...t, seq: Number(e.target.value) } : t,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`tier-min-${index}`} label="下界（含）">
                  <Input
                    id={`tier-min-${index}`}
                    value={tier.minValue}
                    onChange={(e) =>
                      setRuleTierRows(
                        ruleTierRows.map((t, i) =>
                          i === index ? { ...t, minValue: e.target.value } : t,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`tier-max-${index}`} label="上界（不含）">
                  <Input
                    id={`tier-max-${index}`}
                    value={tier.maxValue ?? ''}
                    placeholder="∞"
                    onChange={(e) =>
                      setRuleTierRows(
                        ruleTierRows.map((t, i) =>
                          i === index ? { ...t, maxValue: e.target.value || null } : t,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`tier-mode-${index}`} label="计价形态">
                  <select
                    id={`tier-mode-${index}`}
                    className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                    value={tier.priceMode}
                    onChange={(e) =>
                      setRuleTierRows(
                        ruleTierRows.map((t, i) =>
                          i === index ? { ...t, priceMode: e.target.value } : t,
                        ),
                      )
                    }
                  >
                    {(capabilities?.priceModes ?? []).map((m) => (
                      <option key={m.value} value={m.value}>
                        {m.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id={`tier-flat-${index}`} label="整档价">
                  <Input
                    id={`tier-flat-${index}`}
                    value={tier.flatPrice ?? ''}
                    onChange={(e) =>
                      setRuleTierRows(
                        ruleTierRows.map((t, i) =>
                          i === index ? { ...t, flatPrice: e.target.value || null } : t,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`tier-limit-${index}`} label="首段上限">
                  <Input
                    id={`tier-limit-${index}`}
                    value={tier.firstLimit ?? ''}
                    onChange={(e) =>
                      setRuleTierRows(
                        ruleTierRows.map((t, i) =>
                          i === index ? { ...t, firstLimit: e.target.value || null } : t,
                        ),
                      )
                    }
                  />
                </FormField>
                <FormField id={`tier-first-${index}`} label="首段 / 续段单价">
                  <div className="flex gap-1">
                    <Input
                      id={`tier-first-${index}`}
                      value={tier.firstPrice ?? ''}
                      placeholder="首段"
                      onChange={(e) =>
                        setRuleTierRows(
                          ruleTierRows.map((t, i) =>
                            i === index ? { ...t, firstPrice: e.target.value || null } : t,
                          ),
                        )
                      }
                    />
                    <Input
                      id={`tier-additional-${index}`}
                      value={tier.additionalPrice ?? ''}
                      placeholder="续段"
                      onChange={(e) =>
                        setRuleTierRows(
                          ruleTierRows.map((t, i) =>
                            i === index ? { ...t, additionalPrice: e.target.value || null } : t,
                          ),
                        )
                      }
                    />
                  </div>
                </FormField>
              </div>
            ))}
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="secondary"
                onClick={() =>
                  setRuleTierRows([...ruleTierRows, emptyTier(ruleTierRows.length + 1)])
                }
              >
                加一档
              </Button>
              {ruleTierRows.length > 1 ? (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setRuleTierRows(ruleTierRows.slice(0, -1))}
                >
                  删最后一档
                </Button>
              ) : null}
            </div>
          </div>

          {ruleProblems.length ? (
            <Notice tone="danger" title="规则没通过校验">
              <ul className="list-disc pl-4">
                {ruleProblems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            </Notice>
          ) : null}

          <div className="flex gap-2">
            <Button onClick={() => void submitRule()} disabled={busy}>
              {supersedingRuleId ? '新增版本并取代旧版本' : '建立规则'}
            </Button>
            {supersedingRuleId ? (
              <Button variant="secondary" onClick={() => setSupersedingRuleId(null)}>
                取消新增版本
              </Button>
            ) : null}
          </div>
        </div>
      </SectionCard>

      {/* ============================================================ 运费测算器 */}
      <SectionCard
        title="运费测算器（逐步拆解）"
        description="计费量 → 命中档位 → 基础运费 → 附加费 → 合计。每一步都写出算式，不是裸数字。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="est-channel" label="渠道码" required>
              <Input
                id="est-channel"
                value={estimateForm.channelCode}
                placeholder="YUNDA"
                onChange={(e) => setEstimateForm({ ...estimateForm, channelCode: e.target.value })}
              />
            </FormField>
            <FormField id="est-carrier" label="承运商码（可空）">
              <Input
                id="est-carrier"
                value={estimateForm.carrierCode}
                placeholder="留空 = 通用费率"
                onChange={(e) => setEstimateForm({ ...estimateForm, carrierCode: e.target.value })}
              />
            </FormField>
            <FormField id="est-method" label="运输方式码" required>
              <Input
                id="est-method"
                value={estimateForm.shippingMethodCode}
                placeholder="EXPRESS"
                onChange={(e) =>
                  setEstimateForm({ ...estimateForm, shippingMethodCode: e.target.value })
                }
              />
            </FormField>
            <FormField id="est-basis" label="计费口径（可空 = 自动判定）">
              <select
                id="est-basis"
                className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                value={estimateForm.chargeBasis}
                onChange={(e) => setEstimateForm({ ...estimateForm, chargeBasis: e.target.value })}
              >
                <option value="">自动判定</option>
                {(capabilities?.chargeBases ?? []).map((b) => (
                  <option key={b.value} value={b.value}>
                    {b.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="est-weight" label="实重（kg）">
              <Input
                id="est-weight"
                value={estimateForm.weightKg}
                placeholder="3.5"
                onChange={(e) => setEstimateForm({ ...estimateForm, weightKg: e.target.value })}
              />
            </FormField>
            <FormField id="est-length" label="长（cm）">
              <Input
                id="est-length"
                value={estimateForm.lengthCm}
                placeholder="30"
                onChange={(e) => setEstimateForm({ ...estimateForm, lengthCm: e.target.value })}
              />
            </FormField>
            <FormField id="est-width" label="宽（cm）">
              <Input
                id="est-width"
                value={estimateForm.widthCm}
                placeholder="20"
                onChange={(e) => setEstimateForm({ ...estimateForm, widthCm: e.target.value })}
              />
            </FormField>
            <FormField id="est-height" label="高（cm）">
              <Input
                id="est-height"
                value={estimateForm.heightCm}
                placeholder="10"
                onChange={(e) => setEstimateForm({ ...estimateForm, heightCm: e.target.value })}
              />
            </FormField>
            <FormField id="est-pieces" label="件数">
              <Input
                id="est-pieces"
                value={estimateForm.pieces}
                placeholder="1"
                onChange={(e) => setEstimateForm({ ...estimateForm, pieces: e.target.value })}
              />
            </FormField>
          </div>
          <Button onClick={() => void runEstimate()} disabled={busy}>
            测算运费
          </Button>

          {estimate ? (
            <div className="space-y-2">
              <div className="grid gap-2 md:grid-cols-4">
                <StatCard
                  label="基础运费"
                  value={moneyWithCurrency(estimate.baseAmount, estimate.currency)}
                />
                <StatCard
                  label="附加费合计"
                  value={moneyWithCurrency(estimate.surchargeAmount, estimate.currency)}
                />
                <StatCard
                  label="运费合计"
                  value={moneyWithCurrency(estimate.totalAmount, estimate.currency)}
                />
                <StatCard
                  label="计费量"
                  value={
                    estimate.chargeableQuantity
                      ? `${estimate.chargeableQuantity} ${estimate.quantityUnit ?? ''}`
                      : '算不出来'
                  }
                />
              </div>
              <div className="border-border rounded-md border p-3">
                {estimate.steps.map((step) => (
                  <div key={step.step} className="border-border border-b py-2 last:border-b-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 font-semibold">{step.label}</span>
                      <Badge tone={step.applicable ? 'success' : 'warning'}>
                        {step.applicable ? '已算出' : '算不出来'}
                      </Badge>
                      <span className="text-copy-13 font-semibold">
                        {moneyWithCurrency(step.amount, step.currency)}
                      </span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{step.basis}</p>
                    {step.note ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">{step.note}</p>
                    ) : null}
                  </div>
                ))}
              </div>
              <Notice tone={estimate.complete ? 'info' : 'warning'} title="测算口径">
                {estimate.note}
              </Notice>
            </div>
          ) : (
            <p className="text-muted-foreground text-copy-13">还没有测算结果。</p>
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 附加费台账 */}
      <SectionCard
        title="附加费参数（燃油 / 旺季 / 偏远）"
        description="同一时刻同一类型只能命中一条（DB 层守卫触发器保证）；三种类型可以同时命中。附加费一律加在基础运费上。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="sur-kw" label="关键词">
              <Input
                id="sur-kw"
                value={surchargeFilters.keyword}
                placeholder="全部"
                onChange={(e) =>
                  setSurchargeFilters({ ...surchargeFilters, keyword: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="sur-channel" label="渠道码">
              <Input
                id="sur-channel"
                value={surchargeFilters.channelCode}
                placeholder="全部"
                onChange={(e) =>
                  setSurchargeFilters({ ...surchargeFilters, channelCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="sur-method" label="运输方式码">
              <Input
                id="sur-method"
                value={surchargeFilters.shippingMethodCode}
                placeholder="全部"
                onChange={(e) =>
                  setSurchargeFilters({
                    ...surchargeFilters,
                    shippingMethodCode: e.target.value,
                    page: 0,
                  })
                }
              />
            </FormField>
            <FormField id="sur-type" label="附加费类型">
              <select
                id="sur-type"
                className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                value={surchargeFilters.surchargeType}
                onChange={(e) =>
                  setSurchargeFilters({
                    ...surchargeFilters,
                    surchargeType: e.target.value,
                    page: 0,
                  })
                }
              >
                <option value="">全部</option>
                {(capabilities?.surchargeTypes ?? []).map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </FormField>
          </div>

          {surchargePage && surchargePage.items.length ? (
            <div className="space-y-2">
              {surchargePage.items.map((item) => (
                <div key={item.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 font-semibold">
                      {item.channelCode} / {item.shippingMethodCode} ·{' '}
                      {surchargeTypeLabel(item.surchargeType)}
                    </span>
                    <Badge tone={item.status === 'ACTIVE' ? 'success' : 'warning'}>
                      {statusLabel(item.status)}
                    </Badge>
                    <span className="text-copy-12 text-muted-foreground">
                      {surchargeBasisLabel(item.calcBasis)} ·{' '}
                      {item.calcBasis === 'PERCENT_OF_BASE_FREIGHT'
                        ? rateText(item.rate)
                        : moneyWithCurrency(item.fixedAmount, item.currency)}
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    生效 {timeText(item.effectiveFrom)} ~ {timeText(item.effectiveTo, '长期有效')}
                  </p>
                  {item.status === 'ACTIVE' ? (
                    <Button
                      className="mt-2"
                      size="sm"
                      variant="ghost"
                      onClick={() => void stopSurcharge(item.id)}
                    >
                      停用
                    </Button>
                  ) : null}
                </div>
              ))}
            </div>
          ) : (
            <EmptyState
              title="还没有附加费"
              description="附加费没配 ⇒ 旺季与油价上浮那几笔会在测算里凭空消失，而页面上看不出任何异常。"
            />
          )}

          <div className="border-border space-y-3 rounded-md border p-3">
            <p className="text-label-13 font-semibold">登记一条附加费</p>
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="sur-form-channel" label="渠道码" required>
                <Input
                  id="sur-form-channel"
                  value={surchargeForm.channelCode}
                  placeholder="YUNDA"
                  onChange={(e) =>
                    setSurchargeForm({ ...surchargeForm, channelCode: e.target.value })
                  }
                />
              </FormField>
              <FormField id="sur-form-method" label="运输方式码" required>
                <Input
                  id="sur-form-method"
                  value={surchargeForm.shippingMethodCode}
                  placeholder="EXPRESS"
                  onChange={(e) =>
                    setSurchargeForm({ ...surchargeForm, shippingMethodCode: e.target.value })
                  }
                />
              </FormField>
              <FormField id="sur-form-type" label="附加费类型" required>
                <select
                  id="sur-form-type"
                  className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                  value={surchargeForm.surchargeType}
                  onChange={(e) =>
                    setSurchargeForm({ ...surchargeForm, surchargeType: e.target.value })
                  }
                >
                  {(capabilities?.surchargeTypes ?? []).map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="sur-form-basis" label="计算口径" required>
                <select
                  id="sur-form-basis"
                  className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                  value={surchargeForm.calcBasis}
                  onChange={(e) =>
                    setSurchargeForm({ ...surchargeForm, calcBasis: e.target.value })
                  }
                >
                  {(capabilities?.surchargeBases ?? []).map((b) => (
                    <option key={b.value} value={b.value}>
                      {b.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="sur-form-rate" label="费率（比例形态用）">
                <Input
                  id="sur-form-rate"
                  value={surchargeForm.rate ?? ''}
                  placeholder="0.15"
                  onChange={(e) =>
                    setSurchargeForm({ ...surchargeForm, rate: e.target.value || null })
                  }
                />
              </FormField>
              <FormField id="sur-form-fixed" label="固定额（固定形态用）">
                <Input
                  id="sur-form-fixed"
                  value={surchargeForm.fixedAmount ?? ''}
                  placeholder="2.50"
                  onChange={(e) =>
                    setSurchargeForm({ ...surchargeForm, fixedAmount: e.target.value || null })
                  }
                />
              </FormField>
              <FormField id="sur-form-currency" label="币种" required>
                <Input
                  id="sur-form-currency"
                  value={surchargeForm.currency}
                  placeholder="CNY"
                  onChange={(e) => setSurchargeForm({ ...surchargeForm, currency: e.target.value })}
                />
              </FormField>
              <FormField id="sur-form-from" label="生效起点" required>
                <Input
                  id="sur-form-from"
                  type="datetime-local"
                  value={surchargeForm.effectiveFrom}
                  onChange={(e) =>
                    setSurchargeForm({ ...surchargeForm, effectiveFrom: e.target.value })
                  }
                />
              </FormField>
            </div>
            {surchargeProblems.length ? (
              <Notice tone="danger" title="附加费没通过校验">
                <ul className="list-disc pl-4">
                  {surchargeProblems.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              </Notice>
            ) : null}
            <Button onClick={() => void submitSurcharge()} disabled={busy}>
              登记附加费
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ============================================================ 头程费用结构 */}
      <SectionCard
        title="头程费用结构"
        description="结构头（运输方式 / 起运地 / 目的地）+ 成本项（主运费 / 报关 / 拖车 / 保险…）。成本项可以有不同的币种，混币时合计算不出来。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="head-kw" label="关键词">
              <Input
                id="head-kw"
                value={headFilters.keyword}
                placeholder="全部"
                onChange={(e) =>
                  setHeadFilters({ ...headFilters, keyword: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="head-method" label="运输方式码">
              <Input
                id="head-method"
                value={headFilters.shippingMethodCode}
                placeholder="全部"
                onChange={(e) =>
                  setHeadFilters({ ...headFilters, shippingMethodCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="head-origin" label="起运地码">
              <Input
                id="head-origin"
                value={headFilters.originCode}
                placeholder="全部"
                onChange={(e) =>
                  setHeadFilters({ ...headFilters, originCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="head-destination" label="目的地码">
              <Input
                id="head-destination"
                value={headFilters.destinationCode}
                placeholder="全部"
                onChange={(e) =>
                  setHeadFilters({ ...headFilters, destinationCode: e.target.value, page: 0 })
                }
              />
            </FormField>
          </div>

          {headPage && headPage.items.length ? (
            <div className="space-y-2">
              {headPage.items.map((item) => (
                <div key={item.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 font-semibold">{headScopeText(item)}</span>
                    <Badge tone={item.status === 'ACTIVE' ? 'success' : 'warning'}>
                      {statusLabel(item.status)}
                    </Badge>
                    <span className="text-copy-12 text-muted-foreground">
                      {item.currency} · {item.componentCount} 个成本项
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    生效 {timeText(item.effectiveFrom)} ~ {timeText(item.effectiveTo, '长期有效')}
                  </p>
                  {item.status === 'ACTIVE' ? (
                    <Button
                      className="mt-2"
                      size="sm"
                      variant="ghost"
                      onClick={() => void stopHeadStructure(item.id)}
                    >
                      停用
                    </Button>
                  ) : null}
                </div>
              ))}
            </div>
          ) : (
            <EmptyState
              title="还没有头程费用结构"
              description="缺了头程费用结构，到岸成本里的头程那一段就是空的 —— 运费占成本比会被系统性低估。"
            />
          )}

          <div className="border-border space-y-3 rounded-md border p-3">
            <p className="text-label-13 font-semibold">新建一条头程费用结构</p>
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="head-form-method" label="运输方式码" required>
                <Input
                  id="head-form-method"
                  value={headForm.shippingMethodCode}
                  placeholder="SEA"
                  onChange={(e) => setHeadForm({ ...headForm, shippingMethodCode: e.target.value })}
                />
              </FormField>
              <FormField id="head-form-origin" label="起运地码" required>
                <Input
                  id="head-form-origin"
                  value={headForm.originCode}
                  placeholder="CN-SZ"
                  onChange={(e) => setHeadForm({ ...headForm, originCode: e.target.value })}
                />
              </FormField>
              <FormField id="head-form-destination" label="目的地码" required>
                <Input
                  id="head-form-destination"
                  value={headForm.destinationCode}
                  placeholder="US-LAX"
                  onChange={(e) => setHeadForm({ ...headForm, destinationCode: e.target.value })}
                />
              </FormField>
              <FormField id="head-form-currency" label="币种" required>
                <Input
                  id="head-form-currency"
                  value={headForm.currency}
                  placeholder="CNY"
                  onChange={(e) => setHeadForm({ ...headForm, currency: e.target.value })}
                />
              </FormField>
              <FormField id="head-form-from" label="生效起点" required>
                <Input
                  id="head-form-from"
                  type="datetime-local"
                  value={headForm.effectiveFrom}
                  onChange={(e) => setHeadForm({ ...headForm, effectiveFrom: e.target.value })}
                />
              </FormField>
            </div>
            <div className="space-y-2">
              <p className="text-label-13 font-semibold">成本项</p>
              {headForm.components.map((component, index) => (
                <div key={index} className="grid gap-2 md:grid-cols-5">
                  <FormField id={`head-comp-seq-${index}`} label="序号">
                    <Input
                      id={`head-comp-seq-${index}`}
                      type="number"
                      value={String(component.seq)}
                      onChange={(e) =>
                        setHeadForm({
                          ...headForm,
                          components: headForm.components.map((c, i) =>
                            i === index ? { ...c, seq: Number(e.target.value) } : c,
                          ),
                        })
                      }
                    />
                  </FormField>
                  <FormField id={`head-comp-type-${index}`} label="成本项类型">
                    <select
                      id={`head-comp-type-${index}`}
                      className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                      value={component.componentType}
                      onChange={(e) =>
                        setHeadForm({
                          ...headForm,
                          components: headForm.components.map((c, i) =>
                            i === index ? { ...c, componentType: e.target.value } : c,
                          ),
                        })
                      }
                    >
                      {(capabilities?.headComponentTypes ?? []).map((t) => (
                        <option key={t.value} value={t.value}>
                          {t.label}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField id={`head-comp-basis-${index}`} label="计价基数">
                    <select
                      id={`head-comp-basis-${index}`}
                      className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                      value={component.calcBasis}
                      onChange={(e) =>
                        setHeadForm({
                          ...headForm,
                          components: headForm.components.map((c, i) =>
                            i === index ? { ...c, calcBasis: e.target.value } : c,
                          ),
                        })
                      }
                    >
                      {(capabilities?.headCalcBases ?? []).map((b) => (
                        <option key={b.value} value={b.value}>
                          {b.label}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField id={`head-comp-amount-${index}`} label="单位费用">
                    <Input
                      id={`head-comp-amount-${index}`}
                      value={component.unitAmount}
                      onChange={(e) =>
                        setHeadForm({
                          ...headForm,
                          components: headForm.components.map((c, i) =>
                            i === index ? { ...c, unitAmount: e.target.value } : c,
                          ),
                        })
                      }
                    />
                  </FormField>
                  <FormField id={`head-comp-currency-${index}`} label="币种">
                    <Input
                      id={`head-comp-currency-${index}`}
                      value={component.currency}
                      onChange={(e) =>
                        setHeadForm({
                          ...headForm,
                          components: headForm.components.map((c, i) =>
                            i === index ? { ...c, currency: e.target.value } : c,
                          ),
                        })
                      }
                    />
                  </FormField>
                </div>
              ))}
              <Button
                size="sm"
                variant="secondary"
                onClick={() =>
                  setHeadForm({
                    ...headForm,
                    components: [
                      ...headForm.components,
                      {
                        seq: headForm.components.length + 1,
                        componentType: 'LINE_HAUL',
                        label: '',
                        calcBasis: 'PER_CBM',
                        unitAmount: '',
                        currency: headForm.currency,
                      },
                    ],
                  })
                }
              >
                加一个成本项
              </Button>
            </div>
            {headProblems.length ? (
              <Notice tone="danger" title="头程费用结构没通过校验">
                <ul className="list-disc pl-4">
                  {headProblems.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              </Notice>
            ) : null}
            <Button onClick={() => void submitHeadStructure()} disabled={busy}>
              建立头程费用结构
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ============================================================ 头程费测算器 */}
      <SectionCard
        title="头程费测算器（逐步拆解）"
        description="按头程费用结构逐项拆开：每票 / 每立方米 / 每柜 / 每公斤。混币时合计算不出来（跨币种绝不相加）。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="head-est-method" label="运输方式码" required>
              <Input
                id="head-est-method"
                value={headEstimateForm.shippingMethodCode}
                placeholder="SEA"
                onChange={(e) =>
                  setHeadEstimateForm({ ...headEstimateForm, shippingMethodCode: e.target.value })
                }
              />
            </FormField>
            <FormField id="head-est-origin" label="起运地码" required>
              <Input
                id="head-est-origin"
                value={headEstimateForm.originCode}
                placeholder="CN-SZ"
                onChange={(e) =>
                  setHeadEstimateForm({ ...headEstimateForm, originCode: e.target.value })
                }
              />
            </FormField>
            <FormField id="head-est-destination" label="目的地码" required>
              <Input
                id="head-est-destination"
                value={headEstimateForm.destinationCode}
                placeholder="US-LAX"
                onChange={(e) =>
                  setHeadEstimateForm({ ...headEstimateForm, destinationCode: e.target.value })
                }
              />
            </FormField>
            <FormField id="head-est-shipments" label="票数">
              <Input
                id="head-est-shipments"
                value={headEstimateForm.shipments}
                onChange={(e) =>
                  setHeadEstimateForm({ ...headEstimateForm, shipments: e.target.value })
                }
              />
            </FormField>
            <FormField id="head-est-cbm" label="立方米">
              <Input
                id="head-est-cbm"
                value={headEstimateForm.cbm}
                placeholder="12"
                onChange={(e) => setHeadEstimateForm({ ...headEstimateForm, cbm: e.target.value })}
              />
            </FormField>
            <FormField id="head-est-containers" label="柜数">
              <Input
                id="head-est-containers"
                value={headEstimateForm.containers}
                placeholder="2"
                onChange={(e) =>
                  setHeadEstimateForm({ ...headEstimateForm, containers: e.target.value })
                }
              />
            </FormField>
            <FormField id="head-est-weight" label="公斤">
              <Input
                id="head-est-weight"
                value={headEstimateForm.weightKg}
                placeholder="300"
                onChange={(e) =>
                  setHeadEstimateForm({ ...headEstimateForm, weightKg: e.target.value })
                }
              />
            </FormField>
          </div>
          <Button onClick={() => void runHeadEstimate()} disabled={busy}>
            测算头程费
          </Button>
          {headEstimate ? (
            <div className="space-y-2">
              <StatCard
                label="头程费合计"
                value={moneyWithCurrency(headEstimate.totalAmount, headEstimate.currency)}
              />
              <div className="border-border rounded-md border p-3">
                {headEstimate.steps.map((step) => (
                  <div key={step.step} className="border-border border-b py-2 last:border-b-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 font-semibold">{step.label}</span>
                      <Badge tone={step.applicable ? 'success' : 'warning'}>
                        {step.applicable ? '已算出' : '算不出来'}
                      </Badge>
                      <span className="text-copy-13 font-semibold">
                        {moneyWithCurrency(step.amount, step.currency)}
                      </span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{step.basis}</p>
                    {step.note ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">{step.note}</p>
                    ) : null}
                  </div>
                ))}
              </div>
              <Notice tone={headEstimate.complete ? 'info' : 'warning'} title="测算口径">
                {headEstimate.note}
              </Notice>
            </div>
          ) : (
            <p className="text-muted-foreground text-copy-13">还没有头程费测算结果。</p>
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 实际运费台账 */}
      <SectionCard
        title="实际运费（事后对账口径）"
        description="与规则是两个独立的事实。当前唯一来源是人工登记的物流商账单观察值（上游 10 还没有可读的实际运费结算事实）。只追加，录错了作废。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="act-channel" label="渠道码">
              <Input
                id="act-channel"
                value={actualFilters.channelCode}
                placeholder="全部"
                onChange={(e) =>
                  setActualFilters({ ...actualFilters, channelCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="act-method" label="运输方式码">
              <Input
                id="act-method"
                value={actualFilters.shippingMethodCode}
                placeholder="全部"
                onChange={(e) =>
                  setActualFilters({
                    ...actualFilters,
                    shippingMethodCode: e.target.value,
                    page: 0,
                  })
                }
              />
            </FormField>
            <FormField id="act-from" label="区间起">
              <Input
                id="act-from"
                type="date"
                value={actualFilters.from}
                onChange={(e) => setActualFilters({ ...actualFilters, from: e.target.value })}
              />
            </FormField>
            <FormField id="act-to" label="区间止">
              <Input
                id="act-to"
                type="date"
                value={actualFilters.to}
                onChange={(e) => setActualFilters({ ...actualFilters, to: e.target.value })}
              />
            </FormField>
          </div>

          {actualPage && actualPage.items.length ? (
            <div className="space-y-2">
              {actualPage.items.map((item) => (
                <div key={item.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 font-semibold">
                      {item.channelCode} / {item.shippingMethodCode} ·{' '}
                      {moneyWithCurrency(item.amount, item.currency)}
                    </span>
                    <Badge tone={item.isVoid ? 'warning' : 'success'}>
                      {item.isVoid ? '已作废' : '已登记'}
                    </Badge>
                    <span className="text-copy-12 text-muted-foreground">
                      {dayText(item.occurredOn)} · {actualSourceLabel(item.source)}
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    计费量 {item.chargeableQuantity ?? '—'} · 件数 {item.quantity ?? '—'} ·{' '}
                    {chargeBasisLabel(item.chargeBasis)}
                  </p>
                  {item.voidReason ? (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      作废理由：{item.voidReason}
                    </p>
                  ) : null}
                  {!item.isVoid ? (
                    <Button
                      className="mt-2"
                      size="sm"
                      variant="ghost"
                      onClick={() => void voidActual(item.id)}
                    >
                      作废
                    </Button>
                  ) : null}
                </div>
              ))}
            </div>
          ) : (
            <EmptyState
              title="还没有实际运费"
              description="没有实际运费时，下面的「运费差异」会显示「无法计算差异」—— 而不是「差异 0.00」。"
            />
          )}

          <div className="border-border space-y-3 rounded-md border p-3">
            <p className="text-label-13 font-semibold">登记一条实际运费</p>
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="act-form-channel" label="渠道码" required>
                <Input
                  id="act-form-channel"
                  value={actualForm.channelCode}
                  placeholder="YUNDA"
                  onChange={(e) => setActualForm({ ...actualForm, channelCode: e.target.value })}
                />
              </FormField>
              <FormField id="act-form-method" label="运输方式码" required>
                <Input
                  id="act-form-method"
                  value={actualForm.shippingMethodCode}
                  placeholder="EXPRESS"
                  onChange={(e) =>
                    setActualForm({ ...actualForm, shippingMethodCode: e.target.value })
                  }
                />
              </FormField>
              <FormField id="act-form-basis" label="计费口径" required>
                <select
                  id="act-form-basis"
                  className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                  value={actualForm.chargeBasis}
                  onChange={(e) => setActualForm({ ...actualForm, chargeBasis: e.target.value })}
                >
                  {(capabilities?.chargeBases ?? []).map((b) => (
                    <option key={b.value} value={b.value}>
                      {b.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="act-form-amount" label="实际发生额" required>
                <Input
                  id="act-form-amount"
                  value={actualForm.amount}
                  placeholder="30.00"
                  onChange={(e) => setActualForm({ ...actualForm, amount: e.target.value })}
                />
              </FormField>
              <FormField id="act-form-currency" label="币种" required>
                <Input
                  id="act-form-currency"
                  value={actualForm.currency}
                  placeholder="CNY"
                  onChange={(e) => setActualForm({ ...actualForm, currency: e.target.value })}
                />
              </FormField>
              <FormField id="act-form-quantity" label="计费量" required>
                <Input
                  id="act-form-quantity"
                  value={actualForm.chargeableQuantity}
                  placeholder="3.5"
                  onChange={(e) =>
                    setActualForm({ ...actualForm, chargeableQuantity: e.target.value })
                  }
                />
              </FormField>
              <FormField id="act-form-pieces" label="件数" required>
                <Input
                  id="act-form-pieces"
                  value={actualForm.quantity}
                  placeholder="1"
                  onChange={(e) => setActualForm({ ...actualForm, quantity: e.target.value })}
                />
              </FormField>
              <FormField id="act-form-date" label="运费发生日" required>
                <Input
                  id="act-form-date"
                  type="date"
                  value={actualForm.occurredOn}
                  onChange={(e) => setActualForm({ ...actualForm, occurredOn: e.target.value })}
                />
              </FormField>
              <FormField id="act-form-ref" label="物流商账单号">
                <Input
                  id="act-form-ref"
                  value={actualForm.sourceRef}
                  placeholder="选填"
                  onChange={(e) => setActualForm({ ...actualForm, sourceRef: e.target.value })}
                />
              </FormField>
            </div>
            {actualProblems.length ? (
              <Notice tone="danger" title="实际运费没通过校验">
                <ul className="list-disc pl-4">
                  {actualProblems.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              </Notice>
            ) : null}
            <Button onClick={() => void submitActual()} disabled={busy}>
              登记实际运费
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ============================================================ 运费差异 */}
      <SectionCard
        title="运费差异（规则测算 vs 实际）"
        description="差异 = 实际 − 按规则测算，由服务层现算、不落库。没有实际运费时显示「无法计算差异」，绝不显示「差异 0.00」。"
      >
        <div className="space-y-3">
          <Button onClick={() => void runVariance()} disabled={busy}>
            算运费差异
          </Button>
          {variance ? (
            <>
              <Notice tone={varianceSummary.tone} title="差异结论">
                {varianceSummary.text}
              </Notice>
              {variance.groups.length ? (
                <div className="space-y-2">
                  {variance.groups.map((group) => (
                    <div
                      key={`${group.channelCode}-${group.shippingMethodCode}-${group.currency}`}
                      className="border-border rounded-md border p-3"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-label-13 font-semibold">
                          {group.channelCode} / {group.shippingMethodCode}
                        </span>
                        <Badge tone={varianceTone(group)}>
                          {group.varianceAmount === null
                            ? '算不出来'
                            : moneyText(group.varianceAmount)}
                        </Badge>
                        <span className="text-copy-12 text-muted-foreground">
                          实际 {moneyWithCurrency(group.actualAmount, group.currency)} · 按规则测算{' '}
                          {moneyWithCurrency(group.estimatedAmount, group.currency)}
                        </span>
                      </div>
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        {varianceText(group)}
                      </p>
                    </div>
                  ))}
                </div>
              ) : null}
              <p className="text-copy-12 text-muted-foreground">{variance.note}</p>
            </>
          ) : (
            <p className="text-muted-foreground text-copy-13">还没有运费差异报告。</p>
          )}
        </div>
      </SectionCard>
    </div>
  );
}

// ================================================================== 夹具

function emptyRuleForm(): FreightRuleInput {
  return {
    channelCode: '',
    channelName: null,
    carrierCode: null,
    carrierName: null,
    shippingMethodCode: '',
    shippingMethodName: null,
    chargeBasis: 'BY_WEIGHT',
    currency: 'CNY',
    volumetricDivisor: null,
    minimumCharge: null,
    effectiveFrom: nowIso(),
    effectiveTo: null,
    note: '',
    tiers: [],
  };
}

function emptyTier(seq: number): FreightTierInput {
  return {
    seq,
    minValue: '0',
    maxValue: null,
    priceMode: 'FIRST_PLUS_ADDITIONAL',
    flatPrice: null,
    firstLimit: '1',
    firstPrice: null,
    additionalPrice: null,
  };
}

function emptySurchargeForm(): FreightSurchargeInput {
  return {
    channelCode: '',
    channelName: null,
    carrierCode: null,
    carrierName: null,
    shippingMethodCode: '',
    shippingMethodName: null,
    surchargeType: 'FUEL',
    calcBasis: 'PERCENT_OF_BASE_FREIGHT',
    rate: null,
    fixedAmount: null,
    currency: 'CNY',
    effectiveFrom: nowIso(),
    effectiveTo: null,
    note: '',
  };
}

function emptyHeadForm(): HeadFreightStructureInput {
  return {
    shippingMethodCode: '',
    shippingMethodName: null,
    originCode: '',
    originName: null,
    destinationCode: '',
    destinationName: null,
    currency: 'CNY',
    effectiveFrom: nowIso(),
    effectiveTo: null,
    note: '',
    components: [
      {
        seq: 1,
        componentType: 'LINE_HAUL',
        label: '',
        calcBasis: 'PER_CBM',
        unitAmount: '',
        currency: 'CNY',
      },
    ],
  };
}

function emptyActualForm(): FreightActualInput {
  return {
    channelCode: '',
    channelName: null,
    carrierCode: null,
    carrierName: null,
    shippingMethodCode: '',
    shippingMethodName: null,
    chargeBasis: 'BY_WEIGHT',
    amount: '',
    baseAmount: null,
    surchargeAmount: null,
    currency: 'CNY',
    chargeableQuantity: '',
    quantity: '1',
    occurredOn: today(),
    sourceRef: '',
    note: '',
  };
}

/** 本地日历日（YYYY-MM-DD）。 */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** 当前时刻（datetime-local 输入框要的 `YYYY-MM-DDTHH:mm` 形状）。 */
function nowIso(): string {
  return new Date().toISOString().slice(0, 16);
}
