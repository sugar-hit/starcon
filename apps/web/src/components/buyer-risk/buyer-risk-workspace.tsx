'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  CircleHelp,
  ClipboardCheck,
  History,
  Loader2,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  actionLabel,
  actionTone,
  contributionText,
  emptyState,
  evaluate,
  fetchBuyers,
  fetchCapabilities,
  fetchRule,
  fetchScore,
  fetchScores,
  normalizedText,
  saveRule,
  scoreText,
  statusLabel,
  unknownFeatures,
  weightSum,
  weightText,
  writebackLabel,
  type BuyerRiskBuyer,
  type BuyerRiskCapabilities,
  type BuyerRiskRule,
  type BuyerRiskRuleInput,
  type BuyerRiskScore,
  type BuyerRiskScorePage,
} from './buyer-risk-model';

/**
 * 16.5.1.2 风险评分 工作区。
 *
 * 蓝图：「计算买家风险分：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，
 * 输出评分与建议动作。输入：买家行为数据；风险特征规则；权重配置。输出：风险评分 + 特征解释。
 * 关系：上游 = 15 客户、11 售后、9 订单；下游 = 16.5.1.1、9.2.2.2。
 * **评分要能解释**（为什么这个买家 80 分），黑盒分数客服无法向平台申诉。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **量不到显示成「未知」，不显示 0 分**。后端给 `null` 就是 `null`，
 *    这里渲染成「未知」，并把「量不到的原因 + 归属模块」一起显示出来。
 * 2. **前端不重算分数**。总分与各特征贡献分全部原样显示后端算好的值 ——
 *    乘一遍权重就会出现「界面上的分项加起来不等于界面上的总分」。
 * 3. **未交付的下游不编造**：16.5.1.1 黑名单、16.5.2.2 关联风险都未交付，
 *    页面上只显示能力声明里的「量不到 + 归属模块」，**没有拉黑按钮、没有关联账号图谱**。
 * 4. **回写失败要说清「未回写」**，不能显示成「已同步」。
 *
 * 🔴 三态**文案不同**（不是同一个空态换个图标）：
 * - 还没登记规则 ⇒ 提示先登记权重与缺信号口径。
 * - 上游读不到买家名册 ⇒ 说清是「读不到」而不是「这个租户没有买家」。
 * - 还没跑过评分 ⇒ 说清评分是真去上游读数据算出来的，不会凭空造一个分数。
 */
export function BuyerRiskWorkspace() {
  const [caps, setCaps] = useState<BuyerRiskCapabilities | null>(null);
  const [rule, setRule] = useState<BuyerRiskRule | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<BuyerRiskRuleInput | null>(null);

  const [buyers, setBuyers] = useState<BuyerRiskBuyer[]>([]);
  const [buyersAvailable, setBuyersAvailable] = useState(true);
  const [buyersNote, setBuyersNote] = useState('');
  const [buyerTotal, setBuyerTotal] = useState(0);
  const [buyerPage, setBuyerPage] = useState(0);
  const [buyerKeyword, setBuyerKeyword] = useState('');

  const [scores, setScores] = useState<BuyerRiskScorePage | null>(null);
  const [scorePage, setScorePage] = useState(0);
  const [selected, setSelected] = useState<BuyerRiskScore | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const BUYER_PAGE_SIZE = 10;
  const SCORE_PAGE_SIZE = 10;

  const loadBuyers = useCallback(async (page: number, keyword: string) => {
    const result = await fetchBuyers(keyword, page, BUYER_PAGE_SIZE);
    setBuyers(result.items);
    setBuyersAvailable(result.available);
    setBuyersNote(result.note);
    setBuyerTotal(result.total);
  }, []);

  const loadScores = useCallback(async (page: number) => {
    const result = await fetchScores(page, SCORE_PAGE_SIZE);
    setScores(result);
    return result;
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, ruleResult] = await Promise.all([fetchCapabilities(), fetchRule()]);
      setCaps(capsResult);
      setRule(ruleResult);
      await loadBuyers(0, buyerKeyword);
      setBuyerPage(0);
      await loadScores(0);
      setScorePage(0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [buyerKeyword, loadBuyers, loadScores]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startEdit = useCallback(() => {
    if (!rule) return;
    setDraft({
      ruleName: rule.ruleName || '买家风险评分规则',
      weightRefundHistory: rule.weightRefundHistory ?? 0,
      weightDisputeHistory: rule.weightDisputeHistory ?? 0,
      weightFraudProfile: rule.weightFraudProfile ?? 0,
      weightAddressPattern: rule.weightAddressPattern ?? 0,
      weightOrderBehavior: rule.weightOrderBehavior ?? 0,
      missingSignalPolicy: rule.missingSignalPolicy ?? 'SCORE_KNOWN_ONLY',
      manualReviewThreshold: rule.manualReviewThreshold ?? 40,
      holdThreshold: rule.holdThreshold ?? 70,
      refundRateFullRisk: rule.refundRateFullRisk ?? 0.3,
      fraudBlacklistedScore: rule.fraudBlacklistedScore ?? 1,
      fraudRiskLevelScore: rule.fraudRiskLevelScore ?? 0.6,
      fraudCaseFullRisk: rule.fraudCaseFullRisk ?? 0.2,
      addressSingleRatioThreshold: rule.addressSingleRatioThreshold ?? 0.5,
      orderBurstWindowHours: rule.orderBurstWindowHours ?? 24,
      orderBurstThreshold: rule.orderBurstThreshold ?? 3,
      orderFrequencyFullRisk: rule.orderFrequencyFullRisk ?? 8,
      orderLookbackDays: rule.orderLookbackDays ?? 180,
      version: rule.version,
    });
    setEditing(true);
  }, [rule]);

  const submitRule = useCallback(async () => {
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await saveRule(draft);
      setRule(saved);
      setEditing(false);
      setNotice('规则已保存。🔴 权重改了**不追溯**改变历史评分 —— 旧评分原样留着，新评分按新权重另起一条。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [draft]);

  const runEvaluate = useCallback(
    async (buyerKey: string) => {
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        const result = await evaluate(buyerKey);
        setNotice(
          `评分完成：扫到 ${result.ordersScanned} 张订单，` +
            `${result.ordersUnresolved} 张的收货地址读不到；` +
            `共 ${result.buyerCount} 个买家，${result.scoredCount} 个出了分，` +
            `${result.insufficientCount} 个信号不足。`,
        );
        await loadScores(0);
        setScorePage(0);
        const first = result.scores[0];
        if (first) {
          setSelected(first);
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : '评分失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [loadScores],
  );

  const openScore = useCallback(async (scoreId: string) => {
    setError(null);
    try {
      setSelected(await fetchScore(scoreId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载评分详情失败，请重试');
    }
  }, []);

  const draftWeightSum = useMemo(() => {
    if (!draft) return 0;
    return weightSum([
      draft.weightRefundHistory,
      draft.weightDisputeHistory,
      draft.weightFraudProfile,
      draft.weightAddressPattern,
      draft.weightOrderBehavior,
    ]);
  }, [draft]);

  if (loading) {
    return (
      <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载买家风险评分…
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="买家风险评分"
        description="计算买家风险分并逐条解释：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号。"
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
        </div>
      )}
      {notice && (
        <div className="border-info-soft bg-info-soft text-info flex items-start gap-2 rounded-lg border px-4 py-3">
          <CircleHelp className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13 whitespace-pre-line">{notice}</span>
        </div>
      )}

      {/* ---------------- 概览 ---------------- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="评分记录" value={scores?.counts.total ?? 0} polarity="neutral" />
        <StatCard label="已出分" value={scores?.counts.scored ?? 0} polarity="neutral" />
        <StatCard
          label="信号不足（没出分）"
          value={scores?.counts.insufficientSignals ?? 0}
          polarity="negative"
        />
        <StatCard label="建议暂缓" value={scores?.counts.hold ?? 0} polarity="negative" />
      </div>

      {/* ---------------- 能力声明 ---------------- */}
      {caps && (
        <SectionCard
          title="能力声明"
          description="哪些维度现在量得到、量不到的归哪个模块 —— 界面不替上游猜。"
        >
          <div className="space-y-4">
            <div className="grid gap-2 md:grid-cols-2">
              {caps.dimensions.map((d) => (
                <div key={d.dimensionCode} className="border-border rounded-md border px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{d.dimensionLabel}</span>
                    <Badge tone={d.available ? 'success' : 'warning'}>
                      {d.available ? '量得到' : '量不到'}
                    </Badge>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    依据：{d.source}
                    {!d.available && d.ownerModule ? ` · 归属模块：${d.ownerModule}` : ''}
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">{d.impact}</p>
                </div>
              ))}
            </div>
            <details className="text-copy-12 text-muted-foreground">
              <summary className="cursor-pointer">口径说明（主体键 / 缺特征 / 可解释性 / 回写 / 边界）</summary>
              <div className="mt-2 space-y-2">
                <p>{caps.subjectKeySemantics}</p>
                <p>{caps.identityChoiceNote}</p>
                <p>{caps.scoreSemantics}</p>
                <p>{caps.missingSignalSemantics}</p>
                <p>{caps.explainSemantics}</p>
                <p>{caps.writebackNote}</p>
                <p>{caps.downstreamNote}</p>
                <p>{caps.boundaryNote}</p>
              </div>
            </details>
            <div className="flex flex-wrap gap-2">
              {caps.sources.map((s) => (
                <Badge key={s.sourceCode} tone={s.available ? 'info' : 'warning'}>
                  {s.sourceLabel}：{s.available ? '可读' : '读不到'}
                </Badge>
              ))}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 评分规则 ---------------- */}
      <SectionCard
        title="评分规则"
        description="权重合计必须恰好 1.0000；缺信号口径必须由租户显式选，系统不给默认值。"
        extra={
          !editing && (
            <Button size="sm" variant="outline" onClick={startEdit}>
              {rule?.configured ? '修改规则' : '登记规则'}
            </Button>
          )
        }
      >
        {!rule?.configured && !editing ? (
          <EmptyState
            icon={<ClipboardCheck className="h-6 w-6" />}
            title={emptyState('NO_RULE').title}
            description={emptyState('NO_RULE').description}
            action={
              <Button size="sm" onClick={startEdit}>
                登记规则
              </Button>
            }
          />
        ) : editing && draft ? (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>规则名</span>
                <Input
                  value={draft.ruleName}
                  onChange={(e) => setDraft({ ...draft, ruleName: e.target.value })}
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>缺信号口径</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={draft.missingSignalPolicy}
                  onChange={(e) => setDraft({ ...draft, missingSignalPolicy: e.target.value })}
                >
                  <option value="SCORE_KNOWN_ONLY">按已有维度归一化后打分</option>
                  <option value="BLOCK_EVALUATION">缺信号就不出分</option>
                </select>
              </label>
              <div className="text-copy-13 text-muted-foreground space-y-1">
                <span>权重合计</span>
                <div>
                  <Badge tone={Math.abs(draftWeightSum - 1) < 1e-9 ? 'success' : 'danger'}>
                    {draftWeightSum.toFixed(4)} / 1.0000
                  </Badge>
                </div>
              </div>
            </div>
            <div className="grid gap-3 md:grid-cols-5">
              {(
                [
                  ['weightRefundHistory', '历史退款'],
                  ['weightDisputeHistory', '纠纷历史'],
                  ['weightFraudProfile', '欺诈特征'],
                  ['weightAddressPattern', '地址模式'],
                  ['weightOrderBehavior', '下单行为'],
                ] as const
              ).map(([key, label]) => (
                <label key={key} className="text-copy-13 text-foreground space-y-1">
                  <span>{label}</span>
                  <Input
                    type="number"
                    step="0.0001"
                    min="0"
                    max="1"
                    value={draft[key]}
                    onChange={(e) =>
                      setDraft({ ...draft, [key]: Number(e.target.value) || 0 })
                    }
                  />
                </label>
              ))}
            </div>
            <div className="grid gap-3 md:grid-cols-4">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>建议人工复核阈值（分）</span>
                <Input
                  type="number"
                  step="0.0001"
                  value={draft.manualReviewThreshold}
                  onChange={(e) =>
                    setDraft({ ...draft, manualReviewThreshold: Number(e.target.value) || 0 })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>建议暂缓阈值（分）</span>
                <Input
                  type="number"
                  step="0.0001"
                  value={draft.holdThreshold}
                  onChange={(e) =>
                    setDraft({ ...draft, holdThreshold: Number(e.target.value) || 0 })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>退款率满档</span>
                <Input
                  type="number"
                  step="0.0001"
                  min="0.0001"
                  max="1"
                  value={draft.refundRateFullRisk}
                  onChange={(e) =>
                    setDraft({ ...draft, refundRateFullRisk: Number(e.target.value) || 0 })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>统计窗口（天）</span>
                <Input
                  type="number"
                  min="1"
                  value={draft.orderLookbackDays}
                  onChange={(e) =>
                    setDraft({ ...draft, orderLookbackDays: Number(e.target.value) || 1 })
                  }
                />
              </label>
            </div>
            <details className="text-copy-13 text-foreground">
              <summary className="cursor-pointer">更多特征阈值</summary>
              <div className="mt-2 grid gap-3 md:grid-cols-4">
                {(
                  [
                    ['fraudBlacklistedScore', '已拉黑买家的欺诈特征分'],
                    ['fraudRiskLevelScore', '等级 RISK 的欺诈特征分'],
                    ['fraudCaseFullRisk', '欺诈退货占比满档'],
                    ['addressSingleRatioThreshold', '地址分散度阈值'],
                    ['orderBurstWindowHours', '短时多单窗口（小时）'],
                    ['orderBurstThreshold', '短时多单笔数阈值'],
                    ['orderFrequencyFullRisk', '每 30 天笔数满档'],
                  ] as const
                ).map(([key, label]) => (
                  <label key={key} className="text-copy-13 text-foreground space-y-1">
                    <span>{label}</span>
                    <Input
                      type="number"
                      step="0.0001"
                      value={draft[key]}
                      onChange={(e) =>
                        setDraft({ ...draft, [key]: Number(e.target.value) || 0 })
                      }
                    />
                  </label>
                ))}
              </div>
            </details>
            <div className="flex gap-2">
              <Button size="sm" onClick={submitRule} disabled={busy}>
                {busy ? '保存中…' : '保存规则'}
              </Button>
              <Button size="sm" variant="outline" onClick={() => setEditing(false)}>
                取消
              </Button>
            </div>
          </div>
        ) : rule ? (
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-copy-14 text-foreground font-medium">{rule.ruleName}</span>
              <Badge tone="neutral">版本 {rule.version}</Badge>
              <Badge tone={rule.missingSignalPolicy === 'BLOCK_EVALUATION' ? 'warning' : 'info'}>
                {rule.missingSignalPolicy === 'BLOCK_EVALUATION'
                  ? '缺信号就不出分'
                  : '按已有维度归一化后打分'}
              </Badge>
            </div>
            <div className="text-copy-13 text-muted-foreground grid gap-1 md:grid-cols-2">
              <span>历史退款权重：{weightText(rule.weightRefundHistory)}</span>
              <span>纠纷历史权重：{weightText(rule.weightDisputeHistory)}</span>
              <span>欺诈特征权重：{weightText(rule.weightFraudProfile)}</span>
              <span>地址模式权重：{weightText(rule.weightAddressPattern)}</span>
              <span>下单行为权重：{weightText(rule.weightOrderBehavior)}</span>
              <span>
                建议人工复核 ≥ {rule.manualReviewThreshold ?? '—'} 分 · 建议暂缓 ≥{' '}
                {rule.holdThreshold ?? '—'} 分
              </span>
              <span>退款率满档：{rule.refundRateFullRisk ?? '—'}</span>
              <span>统计窗口：{rule.orderLookbackDays ?? '—'} 天</span>
            </div>
          </div>
        ) : null}
      </SectionCard>

      {/* ---------------- 买家名册 ---------------- */}
      <SectionCard
        title="买家名册"
        description="来自 9.2.1.2 的租户自维护买家画像（带 buyer_key）。🔴 本模块不拉黑、不声称已拉黑 —— 那是 16.5.1.1 的职责。"
        extra={
          <div className="flex gap-2">
            <Input
              className="h-8 w-48"
              placeholder="按姓名 / 邮箱 / 电话搜"
              value={buyerKeyword}
              onChange={(e) => setBuyerKeyword(e.target.value)}
            />
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                void loadBuyers(0, buyerKeyword);
                setBuyerPage(0);
              }}
            >
              搜索
            </Button>
            <Button
              size="sm"
              onClick={() => void runEvaluate('')}
              disabled={busy || !rule?.configured}
              title={rule?.configured ? '' : '先登记评分规则才能评分'}
            >
              为全部买家评分
            </Button>
          </div>
        }
      >
        {!buyersAvailable ? (
          <EmptyState
            icon={<ShieldAlert className="h-6 w-6" />}
            title={emptyState('NO_BUYER').title}
            description={`${emptyState('NO_BUYER').description}（上游说明：${buyersNote}）`}
          />
        ) : buyers.length === 0 ? (
          <EmptyState
            icon={<ClipboardCheck className="h-6 w-6" />}
            title="这个租户还没有登记买家画像"
            description="买家名册来自 9.2.1.2 的租户自维护画像。画像里才有 buyer_key —— 没有画像的买家，欺诈特征这一维会标成「量不到」。"
          />
        ) : (
          <div className="space-y-3">
            <table className="w-full text-copy-13">
              <thead>
                <tr className="text-copy-12 text-muted-foreground border-border border-b text-left">
                  <th className="py-2 pr-3">买家</th>
                  <th className="py-2 pr-3">等级</th>
                  <th className="py-2 pr-3">最近评分</th>
                  <th className="py-2 pr-3 text-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {buyers.map((b) => (
                  <tr key={b.buyerKey} className="border-border border-b">
                    <td className="py-2 pr-3">
                      <div className="text-foreground">{b.buyerName || '（未填姓名）'}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {b.buyerEmail || '（未填邮箱）'} · {b.buyerKey.slice(0, 8)}…
                      </div>
                    </td>
                    <td className="py-2 pr-3">
                      <div className="flex flex-wrap gap-1">
                        <Badge tone="neutral">{b.buyerLevel || '未登记'}</Badge>
                        {b.blacklisted && <Badge tone="danger">已拉黑</Badge>}
                      </div>
                    </td>
                    <td className="py-2 pr-3">
                      {b.latestRiskScore === null || b.latestRiskScore === undefined ? (
                        <span className="text-muted-foreground">未评分</span>
                      ) : (
                        <div className="flex flex-wrap items-center gap-1">
                          <span className="text-foreground">{scoreText(b.latestRiskScore)}</span>
                          <Badge tone={actionTone(b.latestRecommendedAction ?? '')}>
                            {actionLabel(b.latestRecommendedAction ?? '')}
                          </Badge>
                        </div>
                      )}
                    </td>
                    <td className="py-2 pr-3 text-right">
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy || !rule?.configured}
                        onClick={() => void runEvaluate(b.buyerKey)}
                      >
                        评分
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="text-copy-12 text-muted-foreground flex items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={buyerPage === 0}
                onClick={() => {
                  const next = buyerPage - 1;
                  void loadBuyers(next, buyerKeyword);
                  setBuyerPage(next);
                }}
              >
                上一页
              </Button>
              <span>
                第 {buyerPage + 1} 页 · 共 {buyerTotal} 个买家
              </span>
              <Button
                size="sm"
                variant="outline"
                disabled={(buyerPage + 1) * BUYER_PAGE_SIZE >= buyerTotal}
                onClick={() => {
                  const next = buyerPage + 1;
                  void loadBuyers(next, buyerKeyword);
                  setBuyerPage(next);
                }}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 评分详情 ---------------- */}
      <SectionCard
        title="评分详情"
        description="逐特征贡献明细：为什么这个买家是这个分 —— 客服要能逐条答出来。"
      >
        {!selected ? (
          <EmptyState
            icon={<History className="h-6 w-6" />}
            title={emptyState('NO_SCORE').title}
            description={emptyState('NO_SCORE').description}
          />
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-h-16 text-foreground font-medium">
                {selected.buyerName || selected.buyerKey.slice(0, 8)}
              </span>
              <span className="text-copy-13 text-muted-foreground">
                总分 {scoreText(selected.riskScore)}
              </span>
              <Badge tone={actionTone(selected.recommendedAction)}>
                {actionLabel(selected.recommendedAction)}
              </Badge>
              <Badge tone={selected.evaluationStatus === 'SCORED' ? 'info' : 'warning'}>
                {statusLabel(selected.evaluationStatus)}
              </Badge>
              <span className="text-copy-12 text-muted-foreground">
                {selected.scoredDimensionCount} 个维度参与计分 · {selected.missingDimensionCount} 个量不到未计入
              </span>
            </div>
            <p className="text-copy-13 text-foreground">{selected.conclusion}</p>
            <p className="text-copy-12 text-muted-foreground">{selected.note}</p>

            <div className="space-y-2">
              {selected.features.map((f) => (
                <div key={f.dimensionCode} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground font-medium">
                      {f.dimensionLabel}
                    </span>
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-12 text-muted-foreground">
                        标准化值 {normalizedText(f.normalizedValue)} · 权重 {weightText(f.weight)} ·
                        贡献分 {contributionText(f.contribution)}
                      </span>
                      <Badge tone={f.available ? 'success' : 'warning'}>
                        {f.available ? '已计入' : '量不到'}
                      </Badge>
                    </div>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">{f.reason}</p>
                  {!f.available && f.ownerModule && (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      归属模块：{f.ownerModule}
                    </p>
                  )}
                </div>
              ))}
            </div>

            {unknownFeatures(selected).length > 0 && (
              <p className="text-copy-12 text-muted-foreground">
                🔴 量到的 {unknownFeatures(selected).length} 个维度**没有**被当成 0 分：它们不进分母，
                贡献分是「—」，总分是按剩下的维度归一化算出来的。
              </p>
            )}

            {selected.writebacks.length > 0 && (
              <div className="space-y-1">
                <h4 className="text-copy-13 text-foreground font-medium">回写 9.2.2.2 风控信号</h4>
                {selected.writebacks.map((w) => (
                  <div key={w.signalCode} className="text-copy-12 text-muted-foreground flex flex-wrap gap-2">
                    <Badge
                      tone={
                        w.writebackStatus === 'SUCCEEDED'
                          ? 'success'
                          : w.writebackStatus === 'FAILED'
                            ? 'danger'
                            : 'neutral'
                      }
                    >
                      {w.signalCode}：{writebackLabel(w.writebackStatus)}
                    </Badge>
                    <span>{w.writebackNote}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 评分历史 ---------------- */}
      <SectionCard
        title="评分历史"
        description="每一次评分都是一次判定凭据：append-only，事后改不动。"
        extra={
          <Button size="sm" variant="outline" onClick={() => void refresh()}>
            <RefreshCw className="mr-1 h-3.5 w-3.5" />
            刷新
          </Button>
        }
      >
        {!scores || scores.items.length === 0 ? (
          <EmptyState
            icon={<History className="h-6 w-6" />}
            title={emptyState('NO_SCORE').title}
            description={emptyState('NO_SCORE').description}
          />
        ) : (
          <div className="space-y-3">
            <table className="w-full text-copy-13">
              <thead>
                <tr className="text-copy-12 text-muted-foreground border-border border-b text-left">
                  <th className="py-2 pr-3">买家</th>
                  <th className="py-2 pr-3">总分</th>
                  <th className="py-2 pr-3">建议动作</th>
                  <th className="py-2 pr-3">订单 / 退款</th>
                  <th className="py-2 pr-3">判定时刻</th>
                </tr>
              </thead>
              <tbody>
                {scores.items.map((s) => (
                  <tr
                    key={s.scoreId}
                    className="border-border cursor-pointer border-b"
                    onClick={() => void openScore(s.scoreId)}
                  >
                    <td className="py-2 pr-3">
                      <div className="text-foreground">{s.buyerName || s.buyerKey.slice(0, 8)}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {s.buyerLevel || '未登记画像'}
                      </div>
                    </td>
                    <td className="py-2 pr-3">
                      <span className="text-foreground">{scoreText(s.riskScore)}</span>
                      {s.riskScore === null && (
                        <Badge className="ml-2" tone="warning">
                          {statusLabel(s.evaluationStatus)}
                        </Badge>
                      )}
                    </td>
                    <td className="py-2 pr-3">
                      <Badge tone={actionTone(s.recommendedAction)}>
                        {actionLabel(s.recommendedAction)}
                      </Badge>
                    </td>
                    <td className="py-2 pr-3">
                      {s.orderCount} / {s.refundedOrderCount}
                    </td>
                    <td className="py-2 pr-3">{s.evaluatedReferenceAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="text-copy-12 text-muted-foreground flex items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={scorePage === 0}
                onClick={() => {
                  const next = scorePage - 1;
                  void loadScores(next);
                  setScorePage(next);
                }}
              >
                上一页
              </Button>
              <span>
                第 {scorePage + 1} 页 · 共 {scores.total} 条
              </span>
              <Button
                size="sm"
                variant="outline"
                disabled={(scorePage + 1) * SCORE_PAGE_SIZE >= scores.total}
                onClick={() => {
                  const next = scorePage + 1;
                  void loadScores(next);
                  setScorePage(next);
                }}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>
    </div>
  );
}
