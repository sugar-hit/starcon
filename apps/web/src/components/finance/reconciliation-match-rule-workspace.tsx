'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
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
import type {
  FinanceMatchRateMonitoring,
  FinanceMatchRateSnapshotPage,
  FinanceMatchRuleCapabilities,
  FinanceMatchRuleEventPage,
  FinanceMatchRuleOptions,
  FinanceMatchRulePage,
  FinanceMatchRuleRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  MATCH_RATE_BELOW_TARGET_HINT,
  MATCH_RULE_HARD_CONSTRAINT_HINT,
  MATCH_RULE_KEY_OPTIONS,
  MATCH_RULE_MIN_DIMENSION_COUNT,
  MATCH_RULE_MIN_KEYS,
  activateMatchRule,
  createMatchRateSnapshot,
  createMatchRule,
  deactivateMatchRule,
  fetchMatchRateMonitoring,
  fetchMatchRateSnapshots,
  fetchMatchRuleCapabilities,
  fetchMatchRuleEvents,
  fetchMatchRuleOptions,
  fetchMatchRules,
  formatMatchRate,
  matchRateReasonHint,
  matchRateTargetVerdict,
  matchRuleActivateBlockReason,
  matchRuleEventLabel,
  matchRuleKeysLabel,
  matchRuleReasonTone,
  matchRuleScopeLabel,
  matchRuleSpecLabel,
  matchRuleStatusLabel,
  matchRuleStatusTone,
  updateMatchRule,
} from './reconciliation-match-rule-model';

/**
 * 12.3.2.3 自动匹配规则。
 *
 * <p>蓝图 12-finance.md：「配置对账自动匹配规则：匹配键（订单号 + 金额 + 日期 + 类型）、
 * 容差（汇率差、手续费）、优先级、模糊匹配兜底，配置匹配率目标与监控。」输出：「规则集 +
 * 匹配率统计 + 未匹配原因分布。」
 *
 * <p><b>页面围绕三块内容组织</b>：
 *
 * <ol>
 *   <li><b>规则集</b>：列表（真分页 + 真筛选 + 真搜索）+ 建 / 改规则（匹配键、至少对上的
 *       维度数、日期窗口、三种容差、优先级、模糊兜底、匹配率目标、适用范围）+ 启用 / 停用；</li>
 *   <li><b>匹配率监控</b>：匹配率 vs 目标（**按币种分组**）+ 未匹配原因分布 + 快照历史；</li>
 *   <li><b>规则事件流</b>：append-only 举证（建 / 改 / 启用 / 停用 / 记录快照）。</li>
 * </ol>
 *
 * <p>🔴 <b>页面上根本没有「金额」这个匹配键</b> —— 不只是后端拦，界面上也写不出来。
 * 金额只以**容差**出现（金额容差 / 手续费容差 / 汇率差容差）。
 *
 * <p>🔴 <b>「判不了」是一档独立状态</b>：没有启用中的规则（没有目标）或还没有可比的结果时，
 * 页面显示「判不了」而**不是**「未达标」；匹配率分母为 0 时显示「—」而**不是 0**。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「还没有规则」与「筛选无结果」。
 */

/** 规则状态筛选（受控词表）。 */
const STATUSES = ['DRAFT', 'ACTIVE', 'DISABLED'] as const;

const PAGE_SIZE = 20;

export function ReconciliationMatchRuleWorkspace() {
  const [capabilities, setCapabilities] = useState<FinanceMatchRuleCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceMatchRuleOptions | null>(null);
  const [rules, setRules] = useState<FinanceMatchRulePage | null>(null);
  const [monitoring, setMonitoring] = useState<FinanceMatchRateMonitoring | null>(null);
  const [snapshots, setSnapshots] = useState<FinanceMatchRateSnapshotPage | null>(null);
  const [events, setEvents] = useState<FinanceMatchRuleEventPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // 列表筛选
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageNo, setPageNo] = useState(0);

  // 建 / 改规则草稿
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftName, setDraftName] = useState('');
  const [draftKeys, setDraftKeys] = useState<string[]>([]);
  const [draftMinDimensions, setDraftMinDimensions] = useState('2');
  const [draftWindowDays, setDraftWindowDays] = useState('7');
  const [draftAmountTolerance, setDraftAmountTolerance] = useState('0');
  const [draftFeeTolerance, setDraftFeeTolerance] = useState('0');
  const [draftExchangeTolerance, setDraftExchangeTolerance] = useState('0');
  const [draftPriority, setDraftPriority] = useState('100');
  const [draftFuzzyEnabled, setDraftFuzzyEnabled] = useState(false);
  const [draftFuzzyPrefix, setDraftFuzzyPrefix] = useState('8');
  const [draftFuzzyWindowDays, setDraftFuzzyWindowDays] = useState('31');
  const [draftFuzzyTolerance, setDraftFuzzyTolerance] = useState('0');
  const [draftRateTarget, setDraftRateTarget] = useState('90');
  const [draftCurrency, setDraftCurrency] = useState('');
  const [draftAccountId, setDraftAccountId] = useState('');
  const [draftShopId, setDraftShopId] = useState('');
  const [draftNote, setDraftNote] = useState('');

  const params = useMemo(
    () => ({
      ...(statusFilter ? { status: statusFilter } : {}),
      ...(keyword ? { keyword } : {}),
    }),
    [statusFilter, keyword],
  );

  const loadRules = useCallback(async () => {
    const page = await fetchMatchRules({ ...params, page: pageNo, size: PAGE_SIZE });
    setRules(page);
  }, [params, pageNo]);

  const loadMonitoring = useCallback(async () => {
    setMonitoring(await fetchMatchRateMonitoring());
  }, []);

  const loadSelected = useCallback(
    async (ruleId: string) => {
      const [eventPage, snapshotPage] = await Promise.all([
        fetchMatchRuleEvents(ruleId, { page: 0, size: PAGE_SIZE }),
        fetchMatchRateSnapshots(ruleId, { page: 0, size: PAGE_SIZE }),
      ]);
      setEvents(eventPage);
      setSnapshots(snapshotPage);
    },
    [],
  );

  useEffect(() => {
    let alive = true;
    (async () => {
      setLoading(true);
      setError('');
      try {
        const [caps, opts] = await Promise.all([
          fetchMatchRuleCapabilities(),
          fetchMatchRuleOptions(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
        await Promise.all([loadRules(), loadMonitoring()]);
      } catch (e) {
        if (!alive) return;
        setError(e instanceof Error ? e.message : '加载失败');
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [loadRules, loadMonitoring]);

  useEffect(() => {
    if (!selectedId) return;
    void loadSelected(selectedId);
  }, [selectedId, loadSelected]);

  // ---------------------------------------------------------------- 动作

  async function guard(action: () => Promise<void>) {
    setActionError('');
    setNotice('');
    try {
      await action();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    }
  }

  function resetDraft() {
    setEditingId(null);
    setDraftName('');
    setDraftKeys([]);
    setDraftMinDimensions('2');
    setDraftWindowDays('7');
    setDraftAmountTolerance('0');
    setDraftFeeTolerance('0');
    setDraftExchangeTolerance('0');
    setDraftPriority('100');
    setDraftFuzzyEnabled(false);
    setDraftFuzzyPrefix('8');
    setDraftFuzzyWindowDays('31');
    setDraftFuzzyTolerance('0');
    setDraftRateTarget('90');
    setDraftCurrency('');
    setDraftAccountId('');
    setDraftShopId('');
    setDraftNote('');
  }

  function loadDraft(rule: FinanceMatchRuleRow) {
    setEditingId(rule.id);
    setDraftName(rule.ruleName);
    setDraftKeys(rule.matchKeys);
    setDraftMinDimensions(String(rule.minDimensionCount));
    setDraftWindowDays(String(rule.dateWindowDays));
    setDraftAmountTolerance(String(rule.amountTolerance));
    setDraftFeeTolerance(String(rule.feeTolerance));
    setDraftExchangeTolerance(String(rule.exchangeRateTolerance));
    setDraftPriority(String(rule.priority));
    setDraftFuzzyEnabled(rule.fuzzyEnabled);
    setDraftFuzzyPrefix(String(rule.fuzzyReferencePrefixLength));
    setDraftFuzzyWindowDays(String(rule.fuzzyDateWindowDays));
    setDraftFuzzyTolerance(String(rule.fuzzyAmountTolerance));
    setDraftRateTarget(String(rule.matchRateTarget));
    setDraftCurrency(rule.currency ?? '');
    setDraftAccountId(rule.accountId ?? '');
    setDraftShopId(rule.shopId ?? '');
    setDraftNote(rule.note);
  }

  function toggleKey(key: string) {
    setDraftKeys((current) =>
      current.includes(key) ? current.filter((k) => k !== key) : [...current, key],
    );
  }

  async function submitRule() {
    const input = {
      ruleName: draftName,
      priority: Number(draftPriority),
      matchKeys: draftKeys,
      minDimensionCount: Number(draftMinDimensions),
      dateWindowDays: Number(draftWindowDays),
      amountTolerance: Number(draftAmountTolerance),
      feeTolerance: Number(draftFeeTolerance),
      exchangeRateTolerance: Number(draftExchangeTolerance),
      fuzzyEnabled: draftFuzzyEnabled,
      fuzzyReferencePrefixLength: Number(draftFuzzyPrefix),
      fuzzyDateWindowDays: Number(draftFuzzyWindowDays),
      fuzzyAmountTolerance: Number(draftFuzzyTolerance),
      matchRateTarget: Number(draftRateTarget),
      currency: draftCurrency === '' ? null : draftCurrency,
      accountId: draftAccountId === '' ? null : draftAccountId,
      shopId: draftShopId === '' ? null : draftShopId,
      note: draftNote === '' ? null : draftNote,
    };
    return guard(async () => {
      const saved = editingId
        ? await updateMatchRule(editingId, input)
        : await createMatchRule(input);
      setSelectedId(saved.id);
      setNotice(editingId ? `已更新匹配规则 ${saved.ruleNo}` : `已建立匹配规则 ${saved.ruleNo}`);
      resetDraft();
      await Promise.all([loadRules(), loadMonitoring()]);
    });
  }

  async function doActivate(rule: FinanceMatchRuleRow) {
    return guard(async () => {
      await activateMatchRule(rule.id);
      setNotice(`已启用匹配规则 ${rule.ruleNo}`);
      await Promise.all([loadRules(), loadMonitoring()]);
      if (selectedId === rule.id) await loadSelected(rule.id);
    });
  }

  async function doDeactivate(rule: FinanceMatchRuleRow) {
    return guard(async () => {
      await deactivateMatchRule(rule.id);
      setNotice(`已停用匹配规则 ${rule.ruleNo}`);
      await Promise.all([loadRules(), loadMonitoring()]);
      if (selectedId === rule.id) await loadSelected(rule.id);
    });
  }

  async function doSnapshot(rule: FinanceMatchRuleRow) {
    return guard(async () => {
      await createMatchRateSnapshot(rule.id, { taskId: null });
      setNotice(`已记录匹配率快照（规则 ${rule.ruleNo}）`);
      await loadMonitoring();
      if (selectedId === rule.id) await loadSelected(rule.id);
    });
  }

  // ---------------------------------------------------------------- 渲染

  const selected = rules?.items.find((r) => r.id === selectedId) ?? null;
  const draftReady =
    draftName.trim() !== '' &&
    draftKeys.length >= MATCH_RULE_MIN_KEYS &&
    Number(draftMinDimensions) >= MATCH_RULE_MIN_DIMENSION_COUNT &&
    Number(draftMinDimensions) <= draftKeys.length;
  const verdict = monitoring
    ? matchRateTargetVerdict(monitoring.matchRate, monitoring.matchRateTarget, monitoring.targetMet)
    : null;
  const accounts = options?.accounts ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="自动匹配规则"
        description="配置对账自动匹配规则：匹配键、容差（汇率差 / 手续费）、优先级、模糊匹配兜底，并监控匹配率与未匹配原因分布。"
        actions={
          <Button
            variant="ghost"
            onClick={() => {
              void Promise.all([loadRules(), loadMonitoring()]);
            }}
            aria-label="刷新匹配规则"
          >
            <RefreshCw className="size-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="加载失败">
          {error}
        </Notice>
      ) : null}
      {actionError ? (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      <Notice tone="info" title="🔴 硬约束：金额相同即匹配不构成匹配">
        {MATCH_RULE_HARD_CONSTRAINT_HINT}
      </Notice>

      {capabilities ? (
        <SectionCard title="能力声明" description="哪些真接通、哪些没交付、归谁">
          <ul className="space-y-2">
            {capabilities.capabilities.map((c) => (
              <li key={`${c.moduleId}-${c.name}`} className="text-copy-13">
                <Badge tone={c.delivered ? 'success' : 'warning'}>
                  {c.moduleId} {c.name}
                </Badge>
                <span className="text-muted-foreground ml-2">{c.source}</span>
                <p className="text-muted-foreground mt-1">{c.note}</p>
              </li>
            ))}
          </ul>
          {capabilities.gaps.length > 0 ? (
            <div className="mt-4 space-y-2">
              <p className="text-label-13">缺口</p>
              <ul className="space-y-2">
                {capabilities.gaps.map((g) => (
                  <li key={`${g.moduleId}-${g.name}`} className="text-copy-13">
                    <Badge tone={g.delivered ? 'success' : 'warning'}>
                      {g.moduleId} {g.name}
                    </Badge>
                    <span className="text-muted-foreground ml-2">{g.owner}</span>
                    <p className="text-muted-foreground mt-1">{g.note}</p>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </SectionCard>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-3">
        <SectionCard
          title="匹配规则集"
          description="数字越小越先试；激活中的规则优先级在租户内唯一"
          className="lg:col-span-2"
        >
          {loading ? (
            <p className="text-muted-foreground text-copy-13">加载中…</p>
          ) : rules && rules.items.length === 0 ? (
            <EmptyState
              title={Object.keys(params).length > 0 ? '没有符合条件的规则' : '还没有匹配规则'}
              description={
                Object.keys(params).length > 0
                  ? '换一个筛选条件试试。'
                  : '先在右边建一条规则：匹配键至少两个（订单号 / 日期 / 类型）。'
              }
            />
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-end gap-2">
                <FormField id="mrr-status" label="状态">
                  <Input
                    value={statusFilter}
                    onChange={(e) => {
                      setStatusFilter(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="DRAFT / ACTIVE / DISABLED"
                    list="mrr-status-options"
                  />
                </FormField>
                <FormField id="mrr-keyword" label="关键字">
                  <Input
                    value={keyword}
                    onChange={(e) => {
                      setKeyword(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="规则号 / 规则名 / 备注"
                  />
                </FormField>
              </div>
              <datalist id="mrr-status-options">
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {matchRuleStatusLabel(s)}
                  </option>
                ))}
              </datalist>
              <div className="overflow-x-auto">
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3">规则号</th>
                      <th className="py-2 pr-3">名称</th>
                      <th className="py-2 pr-3">优先级</th>
                      <th className="py-2 pr-3">匹配键</th>
                      <th className="py-2 pr-3">模糊</th>
                      <th className="py-2 pr-3">目标</th>
                      <th className="py-2 pr-3">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rules?.items.map((r) => (
                      <tr
                        key={r.id}
                        className="cursor-pointer border-b last:border-0"
                        onClick={() => setSelectedId(r.id)}
                      >
                        <td className="py-2 pr-3">{r.ruleNo}</td>
                        <td className="py-2 pr-3">{r.ruleName}</td>
                        <td className="py-2 pr-3">{r.priority}</td>
                        <td className="py-2 pr-3">
                          {matchRuleKeysLabel(r.matchKeys)}
                          <span className="text-muted-foreground ml-1">
                            （至少 {r.minDimensionCount} 个）
                          </span>
                        </td>
                        <td className="py-2 pr-3">{r.fuzzyEnabled ? '开' : '关'}</td>
                        <td className="py-2 pr-3">{r.matchRateTarget}%</td>
                        <td className="py-2 pr-3">
                          <Badge tone={matchRuleStatusTone(r.status)}>
                            {matchRuleStatusLabel(r.status)}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {rules && rules.total > PAGE_SIZE ? (
                <div className="flex items-center gap-2">
                  <Button
                    variant="ghost"
                    disabled={pageNo === 0}
                    onClick={() => setPageNo((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <span className="text-copy-12 text-muted-foreground">
                    第 {pageNo + 1} 页 · 共 {rules.total} 条
                  </span>
                  <Button
                    variant="ghost"
                    disabled={(pageNo + 1) * PAGE_SIZE >= rules.total}
                    onClick={() => setPageNo((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              ) : null}
            </div>
          )}
        </SectionCard>

        <SectionCard title="建 / 改规则" description="匹配键至少两个；至少对上的维度数至少 2">
          <div className="space-y-3">
            <FormField id="mrr-name" label="规则名" required>
              <Input
                value={draftName}
                onChange={(e) => setDraftName(e.target.value)}
                placeholder="例：平台结算默认口径"
              />
            </FormField>

            <div>
              <p className="text-label-13 mb-1">匹配键（至少 {MATCH_RULE_MIN_KEYS} 个）</p>
              <div className="flex flex-wrap gap-3">
                {MATCH_RULE_KEY_OPTIONS.map((option) => (
                  <label key={option.value} className="text-copy-13 flex items-center gap-1">
                    <input
                      type="checkbox"
                      checked={draftKeys.includes(option.value)}
                      onChange={() => toggleKey(option.value)}
                    />
                    {option.label}
                  </label>
                ))}
              </div>
              <p className="text-copy-12 text-muted-foreground mt-1">
                🔴 **没有「金额」这一档**：金额相同只是候选，必须结合订单号、日期、类型。
              </p>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <FormField
                id="mrr-min-dims"
                label="至少对上的维度数"
                hint="下限 2（金额 + 单个维度不构成匹配）"
              >
                <Input
                  type="number"
                  min={MATCH_RULE_MIN_DIMENSION_COUNT}
                  max={3}
                  value={draftMinDimensions}
                  onChange={(e) => setDraftMinDimensions(e.target.value)}
                />
              </FormField>
              <FormField id="mrr-window" label="日期窗口（天）" hint="1 ~ 31">
                <Input
                  type="number"
                  min={1}
                  max={31}
                  value={draftWindowDays}
                  onChange={(e) => setDraftWindowDays(e.target.value)}
                />
              </FormField>
              <FormField id="mrr-amount-tol" label="金额容差" hint="候选配对带">
                <Input
                  type="number"
                  step="0.0001"
                  min={0}
                  value={draftAmountTolerance}
                  onChange={(e) => setDraftAmountTolerance(e.target.value)}
                />
              </FormField>
              <FormField id="mrr-fee-tol" label="手续费容差" hint="残差归因带">
                <Input
                  type="number"
                  step="0.0001"
                  min={0}
                  value={draftFeeTolerance}
                  onChange={(e) => setDraftFeeTolerance(e.target.value)}
                />
              </FormField>
              <FormField id="mrr-fx-tol" label="汇率差容差" hint="同币种折算残差带">
                <Input
                  type="number"
                  step="0.0001"
                  min={0}
                  value={draftExchangeTolerance}
                  onChange={(e) => setDraftExchangeTolerance(e.target.value)}
                />
              </FormField>
              <FormField id="mrr-priority" label="优先级" hint="数字越小越先试">
                <Input
                  type="number"
                  min={1}
                  max={9999}
                  value={draftPriority}
                  onChange={(e) => setDraftPriority(e.target.value)}
                />
              </FormField>
              <FormField id="mrr-target" label="匹配率目标（%）" hint="低于目标时先查上游数据质量">
                <Input
                  type="number"
                  min={0}
                  max={100}
                  step="0.01"
                  value={draftRateTarget}
                  onChange={(e) => setDraftRateTarget(e.target.value)}
                />
              </FormField>
              <FormField id="mrr-currency" label="适用币种" hint="留空 = 全部">
                <Input
                  value={draftCurrency}
                  onChange={(e) => setDraftCurrency(e.target.value)}
                  placeholder="USD"
                  list="mrr-currency-options"
                />
              </FormField>
              <datalist id="mrr-currency-options">
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c} />
                ))}
              </datalist>
              <FormField id="mrr-account" label="适用账户" hint="留空 = 全部">
                <Input
                  value={draftAccountId}
                  onChange={(e) => setDraftAccountId(e.target.value)}
                  placeholder="账户 id"
                  list="mrr-account-options"
                />
              </FormField>
              <datalist id="mrr-account-options">
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.accountNo} {a.accountName}
                  </option>
                ))}
              </datalist>
              <FormField id="mrr-shop" label="适用店铺" hint="留空 = 全部">
                <Input
                  value={draftShopId}
                  onChange={(e) => setDraftShopId(e.target.value)}
                  placeholder="店铺 id"
                />
              </FormField>
            </div>

            <div>
              <p className="text-label-13 mb-1">模糊匹配兜底</p>
              <label className="text-copy-13 flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={draftFuzzyEnabled}
                  onChange={(e) => setDraftFuzzyEnabled(e.target.checked)}
                />
                开启（订单号改按前缀比、日期窗口放宽，**仍需对上至少 2 个维度**）
              </label>
              {draftFuzzyEnabled ? (
                <div className="mt-2 grid gap-3 sm:grid-cols-3">
                  <FormField id="mrr-fuzzy-prefix" label="订单号前缀长度" hint="4 ~ 32">
                    <Input
                      type="number"
                      min={4}
                      max={32}
                      value={draftFuzzyPrefix}
                      onChange={(e) => setDraftFuzzyPrefix(e.target.value)}
                    />
                  </FormField>
                  <FormField id="mrr-fuzzy-window" label="模糊日期窗口（天）" hint="≥ 精确窗口">
                    <Input
                      type="number"
                      min={1}
                      max={31}
                      value={draftFuzzyWindowDays}
                      onChange={(e) => setDraftFuzzyWindowDays(e.target.value)}
                    />
                  </FormField>
                  <FormField id="mrr-fuzzy-tol" label="模糊金额容差">
                    <Input
                      type="number"
                      step="0.0001"
                      min={0}
                      value={draftFuzzyTolerance}
                      onChange={(e) => setDraftFuzzyTolerance(e.target.value)}
                    />
                  </FormField>
                </div>
              ) : null}
            </div>

            <FormField id="mrr-note" label="备注">
              <Input value={draftNote} onChange={(e) => setDraftNote(e.target.value)} />
            </FormField>

            <div className="flex gap-2">
              <Button disabled={!draftReady} onClick={() => void submitRule()}>
                {editingId ? '保存规则' : '建立规则'}
              </Button>
              {editingId ? (
                <Button variant="ghost" onClick={resetDraft}>
                  取消编辑
                </Button>
              ) : null}
            </div>
            {!draftReady ? (
              <p className="text-copy-12 text-muted-foreground">
                规则名不能为空，匹配键至少 {MATCH_RULE_MIN_KEYS} 个，「至少对上的维度数」在{' '}
                {MATCH_RULE_MIN_DIMENSION_COUNT} ~ {draftKeys.length} 之间。
              </p>
            ) : null}
          </div>
        </SectionCard>
      </div>

      <SectionCard
        title="匹配率监控"
        description="匹配率 = 匹配行数 ÷（匹配行数 + 未匹配条数），全部来自 12.3.2.1 的真实结果行"
      >
        {monitoring ? (
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard
                label="匹配率"
                value={formatMatchRate(monitoring.matchRate)}
                comparisonLabel={verdict ? verdict.label : ''}
              />
              <StatCard
                label="匹配行数"
                value={String(monitoring.matchedLineCount)}
                comparisonLabel={`未匹配 ${monitoring.unmatchedLineCount} 条`}
              />
              <StatCard
                label="匹配率目标"
                value={
                  monitoring.matchRateTarget === null
                    ? '—（没有启用中的规则）'
                    : `${monitoring.matchRateTarget}%`
                }
                comparisonLabel={monitoring.ruleNo ? `口径规则 ${monitoring.ruleNo}` : '没有口径规则'}
              />
              <StatCard
                label="口径"
                value={monitoring.ruleNo ? monitoring.ruleNo : '—'}
                comparisonLabel={monitoring.taskNo ? `任务 ${monitoring.taskNo}` : '租户级汇总'}
              />
            </div>

            {verdict && verdict.tone === 'danger' ? (
              <Notice tone="warning" title="低于匹配率目标">
                {MATCH_RATE_BELOW_TARGET_HINT}
              </Notice>
            ) : null}
            <Notice tone="info" title="口径说明">
              {monitoring.note}
            </Notice>

            <div>
              <p className="text-label-13 mb-1">按币种分组（绝不跨币种合计）</p>
              {monitoring.currencies.length === 0 ? (
                <EmptyState
                  title="还没有可比的结果"
                  description="先跑一次对账任务（12.3.2.1），这里才有匹配率。"
                />
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-copy-13">
                    <thead>
                      <tr className="text-muted-foreground border-b text-left">
                        <th className="py-2 pr-3">币种</th>
                        <th className="py-2 pr-3">匹配行数</th>
                        <th className="py-2 pr-3">未匹配条数</th>
                        <th className="py-2 pr-3">总计</th>
                        <th className="py-2 pr-3">匹配率</th>
                      </tr>
                    </thead>
                    <tbody>
                      {monitoring.currencies.map((c) => (
                        <tr key={c.currency} className="border-b last:border-0">
                          <td className="py-2 pr-3">{c.currency}</td>
                          <td className="py-2 pr-3">{c.matchedLineCount}</td>
                          <td className="py-2 pr-3">{c.unmatchedLineCount}</td>
                          <td className="py-2 pr-3">{c.totalLineCount}</td>
                          <td className="py-2 pr-3">{formatMatchRate(c.matchRate)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div>
              <p className="text-label-13 mb-1">未匹配原因分布</p>
              {monitoring.reasons.length === 0 ? (
                <EmptyState title="没有未匹配原因" description="这一批全部配上了（或还没有结果）。" />
              ) : (
                <ul className="space-y-1">
                  {monitoring.reasons.map((r) => (
                    <li key={`${r.currency}-${r.reasonType}`} className="text-copy-13">
                      <Badge tone={matchRuleReasonTone(r.reasonType)}>
                        {r.reasonLabel}
                      </Badge>
                      <span className="text-muted-foreground ml-2">
                        {matchRateReasonHint(r)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">加载中…</p>
        )}
      </SectionCard>

      {selected ? (
        <div className="grid gap-4 lg:grid-cols-2">
          <SectionCard title={`规则详情 · ${selected.ruleNo}`} description={selected.ruleName}>
            <div className="space-y-3">
              <p className="text-copy-13">{matchRuleSpecLabel(selected)}</p>
              <p className="text-copy-13">适用范围：{matchRuleScopeLabel(selected)}</p>
              <p className="text-copy-13 text-muted-foreground">{selected.note}</p>
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="ghost"
                  disabled={Boolean(matchRuleActivateBlockReason(selected))}
                  onClick={() => void doActivate(selected)}
                >
                  启用
                </Button>
                <Button variant="ghost" onClick={() => void doDeactivate(selected)}>
                  停用
                </Button>
                <Button variant="ghost" onClick={() => void doSnapshot(selected)}>
                  记录匹配率快照
                </Button>
                <Button variant="ghost" onClick={() => loadDraft(selected)}>
                  编辑
                </Button>
              </div>
              {matchRuleActivateBlockReason(selected) ? (
                <p className="text-copy-12 text-muted-foreground">
                  {matchRuleActivateBlockReason(selected)}
                </p>
              ) : null}
            </div>
          </SectionCard>

          <SectionCard title="匹配率快照历史" description="一次监控事实，不可改写">
            {snapshots && snapshots.items.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3">币种</th>
                      <th className="py-2 pr-3">匹配</th>
                      <th className="py-2 pr-3">未匹配</th>
                      <th className="py-2 pr-3">匹配率</th>
                      <th className="py-2 pr-3">达标</th>
                    </tr>
                  </thead>
                  <tbody>
                    {snapshots.items.map((s) => (
                      <tr key={s.id} className="border-b last:border-0">
                        <td className="py-2 pr-3">{s.currency}</td>
                        <td className="py-2 pr-3">{s.matchedLineCount}</td>
                        <td className="py-2 pr-3">{s.unmatchedLineCount}</td>
                        <td className="py-2 pr-3">{formatMatchRate(s.matchRate)}</td>
                        <td className="py-2 pr-3">{s.targetMet ? '是' : '否'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState
                title="还没有快照"
                description="点「记录匹配率快照」把当前匹配率留一份历史（append-only）。"
              />
            )}
          </SectionCard>

          <SectionCard title="规则事件流" description="append-only 举证" className="lg:col-span-2">
            {events && events.items.length > 0 ? (
              <ul className="space-y-2">
                {events.items.map((e) => (
                  <li key={e.id} className="text-copy-13">
                    <Badge tone="neutral">{matchRuleEventLabel(e.eventType)}</Badge>
                    <span className="ml-2">{e.summary}</span>
                    <span className="text-muted-foreground ml-2">{e.occurredAt}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState title="还没有事件" description="建 / 改 / 启用 / 停用规则都会留痕。" />
            )}
          </SectionCard>
        </div>
      ) : null}
    </div>
  );
}

