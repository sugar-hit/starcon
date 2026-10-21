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
  EMPTY_DIFF_FILTERS,
  EMPTY_RECORD_FILTERS,
  createRule,
  deltaText,
  deviationText,
  diffHeadline,
  emptyRuleInput,
  fetchCapabilities,
  fetchDiffs,
  fetchRecord,
  fetchRecords,
  fetchRules,
  numberOrNull,
  outcomeLabel,
  outcomeTone,
  priceText,
  recordHeadline,
  ruleScopeText,
  ruleSummaryText,
  ruleTypeHint,
  ruleTypeLabel,
  runSummaryText,
  runSync,
  updateRule,
  validateRuleInput,
} from './platform-price-sync-model';
import type {
  PlatformPriceDiff,
  PlatformPriceDiffFilters,
  PlatformPriceDiffPage,
  PlatformPriceRule,
  PlatformPriceRuleInput,
  PlatformPriceRulePage,
  PlatformPriceRuleType,
  PlatformPriceSyncCapabilities,
  PlatformPriceSyncRecord,
  PlatformPriceSyncRecordDetail,
  PlatformPriceSyncRecordPage,
} from './platform-price-sync-model';

/**
 * 5.4.2.1 平台价格同步工作区。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**平台可能拒绝或调整价格**（如低于最低价触发），差异必须显性反馈，
 * 不能假设回写即成功」。
 *
 * <p>所以本页把四件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>三个价分三列</b>：期望价（我们要求平台收的）/ 平台承接价（规则判定出来的）/
 *       平台实际价（平台回执给的）。合成一列就看不出「平台到底改了多少」。</li>
 *   <li><b>差额带符号、带偏离比例</b>：直接给整句「期望 120.0000 USD → 实际 118.0000
 *       （差 -2.0000，偏离 1.67%）」，运营不用对着两列数字做减法。</li>
 *   <li><b>「还没结论」与「结论是 0」分得开</b>：平台还没回执时实际价显示「待平台回执」，
 *       <b>绝不显示 0</b> —— 0 会让页面显示成「平台把价压到了 0」。</li>
 *   <li><b>平台定价规则是本页可配置的真实数据</b>：最低价 / 自动比价 / 多站点联动三条规则
 *       都能建、能停用；最低价触发时这一轮**不回写**，但原因显性写在结果与差异台账里。</li>
 * </ol>
 *
 * <p>🔴 <b>PENDING（已投递、等平台回执）与成功/失败分开显示</b>：
 * 把它算进成功就是那句加粗句的字面反面。
 *
 * <p>路由取 {@code /pricing/sync}。与同 L3 的 5.4.1 调价（{@code /pricing/adjust}）
 * **不共用路由**：一个是「这次改价批不批」，一个是「这个价推上平台会怎样」。
 */
export function PlatformPriceSyncWorkspace() {
  const [capabilities, setCapabilities] = useState<PlatformPriceSyncCapabilities | null>(null);

  // 规则
  const [rules, setRules] = useState<PlatformPriceRulePage | null>(null);
  const [ruleFilters, setRuleFilters] = useState({
    ruleType: '',
    platformCode: '',
    siteCode: '',
    status: 'ACTIVE',
  });
  const [ruleForm, setRuleForm] = useState<PlatformPriceRuleInput>(emptyRuleInput());
  const [ruleProblems, setRuleProblems] = useState<string[]>([]);

  // 回写结果
  const [records, setRecords] = useState<PlatformPriceSyncRecordPage | null>(null);
  const [recordFilters, setRecordFilters] = useState(EMPTY_RECORD_FILTERS);
  const [detail, setDetail] = useState<PlatformPriceSyncRecordDetail | null>(null);

  // 差异台账
  const [diffs, setDiffs] = useState<PlatformPriceDiffPage | null>(null);
  const [diffFilters, setDiffFilters] = useState(EMPTY_DIFF_FILTERS);

  // 同步
  const [syncForm, setSyncForm] = useState({ platformCode: '', siteCode: '', skuId: '' });

  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [caps, rulePage, recordPage, diffPage] = await Promise.all([
        fetchCapabilities(),
        fetchRules(0, 50, ruleFilters),
        fetchRecords(0, 50, recordFilters),
        fetchDiffs(0, 50, diffFilters),
      ]);
      setCapabilities(caps);
      setRules(rulePage);
      setRecords(recordPage);
      setDiffs(diffPage);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '平台价格同步台账读不到');
    } finally {
      setBusy(false);
    }
  }, [ruleFilters, recordFilters, diffFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  const counts = useMemo(() => {
    const items = records?.items ?? [];
    return {
      total: records?.totalElements ?? 0,
      pending: items.filter((r) => r.outcome === 'PENDING').length,
      adjusted: items.filter((r) => r.outcome === 'ADJUSTED').length,
      rejected: items.filter((r) => r.outcome === 'REJECTED').length,
      failed: items.filter((r) => r.outcome === 'FAILED').length,
    };
  }, [records]);

  async function submitRule() {
    const problems = validateRuleInput(ruleForm);
    setRuleProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await createRule(ruleForm);
      setNotice(
        `规则已建立：${ruleTypeLabel(ruleForm.ruleType)}（${ruleScopeText({
          platformCode: ruleForm.platformCode,
          siteCode: ruleForm.siteCode,
          skuId: ruleForm.skuId,
        })}）。生效后它会参与下一次同步的判定。`,
      );
      setRuleForm(emptyRuleInput());
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '规则建立失败');
    } finally {
      setBusy(false);
    }
  }

  async function toggleRule(rule: PlatformPriceRule) {
    setBusy(true);
    setError('');
    setNotice('');
    const nextStatus = rule.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    try {
      await updateRule(rule.id, {
        ruleType: rule.ruleType,
        platformCode: rule.platformCode,
        siteCode: rule.siteCode,
        skuId: rule.skuId,
        status: nextStatus,
        priority: rule.priority,
        minPrice: rule.minPrice,
        currency: rule.currency,
        comparePlatformCode: rule.comparePlatformCode,
        compareSiteCode: rule.compareSiteCode,
        maxGapRatio: rule.maxGapRatio,
        referenceSiteCode: rule.referenceSiteCode,
        minRatio: rule.minRatio,
        maxRatio: rule.maxRatio,
        note: rule.note,
      });
      setNotice(
        nextStatus === 'DISABLED'
          ? '规则已停用：停用的规则保留历史口径，不再参与判定。'
          : '规则已启用：下一次同步就会按它判定。',
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '规则状态变更失败');
    } finally {
      setBusy(false);
    }
  }

  async function doSync() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const run = await runSync({
        platformCode: syncForm.platformCode.trim() || null,
        siteCode: syncForm.siteCode.trim() || null,
        skuId: syncForm.skuId.trim() || null,
        limit: null,
      });
      setNotice(runSummaryText(run));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '同步失败');
    } finally {
      setBusy(false);
    }
  }

  async function openDetail(record: PlatformPriceSyncRecord) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchRecord(record.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '回写详情读不到');
    } finally {
      setBusy(false);
    }
  }

  const ruleTypeOptions: PlatformPriceRuleType[] = ['MIN_PRICE', 'AUTO_COMPARE', 'MULTI_SITE_LINK'];

  return (
    <div className="space-y-5">
      <PageHeader
        title="平台价格同步"
        description="把生效价格回写到平台：处理平台定价规则（最低价 / 自动比价 / 多站点联动），记录回写结果与被平台调整后的实际价。"
        actions={
          <>
            <Badge tone="brand">5.4.2.1 平台价格同步</Badge>
            <Button variant="outline" size="sm" disabled={busy} onClick={() => void doSync()}>
              执行一次同步
            </Button>
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="操作失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      )}

      {/* 能力声明：平台可能拒绝或调整价格 —— 这句话是本页的设计前提 */}
      {capabilities && (
        <SectionCard
          title="本页口径声明"
          description="平台可能拒绝或调整价格 —— 差异必须显性反馈，不能假设回写即成功。"
        >
          <div className="space-y-3">
            <Notice tone="warning" title="回写结果不是「成功 / 失败」两分">
              {capabilities.notes.map((n) => (
                <p key={n} className="mb-1 last:mb-0">
                  {n}
                </p>
              ))}
            </Notice>
            <div className="grid gap-3 md:grid-cols-3">
              {capabilities.ruleTypeSemantics.map((semantic, index) => (
                <div key={semantic} className="border-control-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">
                    {capabilities.ruleTypes[index] ?? `规则 ${index + 1}`}
                  </p>
                  <p className="text-copy-13 text-muted-foreground mt-1">{semantic}</p>
                </div>
              ))}
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              <div className="border-control-border rounded-md border p-3">
                <p className="text-label-13 font-semibold">上游</p>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  {capabilities.upstreamOwner} · {capabilities.upstreamResource}
                </p>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  {capabilities.upstreamWriteOwner}
                </p>
              </div>
              <div className="border-control-border rounded-md border p-3">
                <p className="text-label-13 font-semibold">回写任务（02 队列）</p>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  {capabilities.queueService} · {capabilities.queueResource} ·{' '}
                  {capabilities.queueOperation}
                </p>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  对比价来源：{capabilities.comparisonPriceSource}
                </p>
              </div>
            </div>
          </div>
        </SectionCard>
      )}

      {/* 概览 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="回写记录总数" value={counts.total} />
        <StatCard label="已投递 · 待平台回执" value={counts.pending} />
        <StatCard
          label="被平台改价"
          value={counts.adjusted}
          polarity="negative"
          comparisonLabel="实际价 ≠ 期望价，差额见差异台账"
        />
        <StatCard
          label="被平台拒收 / 没发出去"
          value={counts.rejected + counts.failed}
          polarity="negative"
          comparisonLabel="原因逐条写在结果里"
        />
      </div>

      {/* 执行一次同步 */}
      <SectionCard
        title="执行一次同步"
        description="消费上游（5.4.1.1 调价单 / 5.4.1.2 批量调价）的待回写事实，按幂等键去重后投递到 02 队列。不填筛选条件就是「把本租户全部待回写事实都过一遍」。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="pps-sync-platform" label="平台码">
            <Input
              id="pps-sync-platform"
              value={syncForm.platformCode}
              placeholder="AMAZON"
              onChange={(e) => setSyncForm({ ...syncForm, platformCode: e.target.value })}
            />
          </FormField>
          <FormField id="pps-sync-site" label="站点码">
            <Input
              id="pps-sync-site"
              value={syncForm.siteCode}
              placeholder="US"
              onChange={(e) => setSyncForm({ ...syncForm, siteCode: e.target.value })}
            />
          </FormField>
          <FormField id="pps-sync-sku" label="SKU id">
            <Input
              id="pps-sync-sku"
              value={syncForm.skuId}
              placeholder="只同步这一个 SKU"
              onChange={(e) => setSyncForm({ ...syncForm, skuId: e.target.value })}
            />
          </FormField>
          <div className="flex items-end">
            <Button disabled={busy} onClick={() => void doSync()}>
              执行同步
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* 平台定价规则 */}
      <SectionCard
        title="平台定价规则"
        description="租户自己配的规则：最低价触发 ⇒ 平台拒收；自动比价 / 多站点联动 ⇒ 平台会改价。规则缺参数存不进来 —— 判不出结果的规则比没有规则更糟。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="pps-rule-type" label="类型">
            <select
              id="pps-rule-type"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={ruleFilters.ruleType}
              onChange={(e) => setRuleFilters({ ...ruleFilters, ruleType: e.target.value })}
            >
              <option value="">全部类型</option>
              {ruleTypeOptions.map((type) => (
                <option key={type} value={type}>
                  {ruleTypeLabel(type)}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="pps-rule-platform" label="平台码">
            <Input
              id="pps-rule-platform"
              value={ruleFilters.platformCode}
              placeholder="AMAZON"
              onChange={(e) => setRuleFilters({ ...ruleFilters, platformCode: e.target.value })}
            />
          </FormField>
          <FormField id="pps-rule-site" label="站点码">
            <Input
              id="pps-rule-site"
              value={ruleFilters.siteCode}
              placeholder="US"
              onChange={(e) => setRuleFilters({ ...ruleFilters, siteCode: e.target.value })}
            />
          </FormField>
          <FormField id="pps-rule-status" label="状态">
            <select
              id="pps-rule-status"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={ruleFilters.status}
              onChange={(e) => setRuleFilters({ ...ruleFilters, status: e.target.value })}
            >
              <option value="">全部状态</option>
              <option value="ACTIVE">生效</option>
              <option value="DISABLED">停用</option>
            </select>
          </FormField>
        </div>

        {rules && rules.items.length ? (
          <div className="mt-3 overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead>
                <tr className="border-b">
                  <th className="p-2">类型</th>
                  <th className="p-2">作用域</th>
                  <th className="p-2">参数</th>
                  <th className="p-2">优先级</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {rules.items.map((rule) => (
                  <tr key={rule.id} className="border-border border-b">
                    <td className="p-2">
                      <span className="font-semibold">{ruleTypeLabel(rule.ruleType)}</span>
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        {ruleTypeHint(rule.ruleType)}
                      </p>
                    </td>
                    <td className="p-2">{ruleScopeText(rule)}</td>
                    <td className="p-2">{ruleSummaryText(rule)}</td>
                    <td className="p-2">{rule.priority}</td>
                    <td className="p-2">
                      <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {rule.status === 'ACTIVE' ? '生效' : '停用'}
                      </Badge>
                    </td>
                    <td className="p-2">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() => void toggleRule(rule)}
                      >
                        {rule.status === 'ACTIVE' ? '停用' : '启用'}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="mt-3">
            <EmptyState
              title="还没有生效的平台定价规则"
              description="没有规则时平台按期望价原样收价；建一条最低价规则就能让「低于平台门槛」在派发前被拦下并显性回报。"
            />
          </div>
        )}

        <div className="mt-5 border-t pt-4">
          <p className="text-label-13 font-semibold">新建规则</p>
          <div className="mt-3 grid gap-3 md:grid-cols-4">
            <FormField id="pps-new-type" label="类型" required>
              <select
                id="pps-new-type"
                className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
                value={ruleForm.ruleType}
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, ruleType: e.target.value as PlatformPriceRuleType })
                }
              >
                {ruleTypeOptions.map((type) => (
                  <option key={type} value={type}>
                    {ruleTypeLabel(type)}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="pps-new-platform" label="平台码" required>
              <Input
                id="pps-new-platform"
                value={ruleForm.platformCode}
                placeholder="AMAZON"
                onChange={(e) => setRuleForm({ ...ruleForm, platformCode: e.target.value })}
              />
            </FormField>
            <FormField id="pps-new-site" label="站点码" hint="留空 = 该平台全部站点">
              <Input
                id="pps-new-site"
                value={ruleForm.siteCode}
                placeholder="US"
                onChange={(e) => setRuleForm({ ...ruleForm, siteCode: e.target.value })}
              />
            </FormField>
            <FormField id="pps-new-priority" label="优先级（越小越先判）">
              <Input
                id="pps-new-priority"
                value={ruleForm.priority === null ? '' : String(ruleForm.priority)}
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, priority: numberOrNull(e.target.value) })
                }
              />
            </FormField>
          </div>

          {ruleForm.ruleType === 'MIN_PRICE' && (
            <div className="mt-3 grid gap-3 md:grid-cols-4">
              <FormField id="pps-new-min" label="平台最低价" required>
                <Input
                  id="pps-new-min"
                  value={ruleForm.minPrice === null ? '' : String(ruleForm.minPrice)}
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, minPrice: numberOrNull(e.target.value) })
                  }
                />
              </FormField>
              <FormField id="pps-new-currency" label="币种" required>
                <Input
                  id="pps-new-currency"
                  value={ruleForm.currency ?? ''}
                  placeholder="USD"
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, currency: e.target.value.toUpperCase() })
                  }
                />
              </FormField>
            </div>
          )}

          {ruleForm.ruleType === 'AUTO_COMPARE' && (
            <div className="mt-3 grid gap-3 md:grid-cols-4">
              <FormField id="pps-new-compare-platform" label="对比平台码" required>
                <Input
                  id="pps-new-compare-platform"
                  value={ruleForm.comparePlatformCode ?? ''}
                  placeholder="SHOPIFY"
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, comparePlatformCode: e.target.value.toUpperCase() })
                  }
                />
              </FormField>
              <FormField id="pps-new-compare-site" label="对比站点码（空 = 全部站点）">
                <Input
                  id="pps-new-compare-site"
                  value={ruleForm.compareSiteCode ?? ''}
                  placeholder="US"
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, compareSiteCode: e.target.value.toUpperCase() })
                  }
                />
              </FormField>
              <FormField
                id="pps-new-gap"
                label="允许的最大价差比例"
                required
                hint="0.05 = 允许比对比价高 5%"
              >
                <Input
                  id="pps-new-gap"
                  value={ruleForm.maxGapRatio === null ? '' : String(ruleForm.maxGapRatio)}
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, maxGapRatio: numberOrNull(e.target.value) })
                  }
                />
              </FormField>
            </div>
          )}

          {ruleForm.ruleType === 'MULTI_SITE_LINK' && (
            <div className="mt-3 grid gap-3 md:grid-cols-4">
              <FormField id="pps-new-reference" label="参照站点码" required>
                <Input
                  id="pps-new-reference"
                  value={ruleForm.referenceSiteCode ?? ''}
                  placeholder="DE"
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, referenceSiteCode: e.target.value.toUpperCase() })
                  }
                />
              </FormField>
              <FormField
                id="pps-new-min-ratio"
                label="联动下限比例"
                required
                hint="0.9 = 参照价的 90%"
              >
                <Input
                  id="pps-new-min-ratio"
                  value={ruleForm.minRatio === null ? '' : String(ruleForm.minRatio)}
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, minRatio: numberOrNull(e.target.value) })
                  }
                />
              </FormField>
              <FormField
                id="pps-new-max-ratio"
                label="联动上限比例"
                required
                hint="1.1 = 参照价的 110%"
              >
                <Input
                  id="pps-new-max-ratio"
                  value={ruleForm.maxRatio === null ? '' : String(ruleForm.maxRatio)}
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, maxRatio: numberOrNull(e.target.value) })
                  }
                />
              </FormField>
            </div>
          )}

          {ruleProblems.length > 0 && (
            <Notice tone="warning" title="规则还差几项">
              <ul className="list-disc pl-4">
                {ruleProblems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            </Notice>
          )}

          <div className="mt-3">
            <Button disabled={busy} onClick={() => void submitRule()}>
              建立规则
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* 回写结果 */}
      <SectionCard
        title="回写结果"
        description="一条待回写事实在平台侧的真实处置。三个价分三列：期望价（我们要求的）、平台承接价（规则判定出的）、平台实际价（平台回执给的）。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="pps-rec-outcome" label="结局">
            <select
              id="pps-rec-outcome"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={recordFilters.outcome}
              onChange={(e) => setRecordFilters({ ...recordFilters, outcome: e.target.value })}
            >
              <option value="">全部结局</option>
              {['PENDING', 'ACCEPTED', 'ADJUSTED', 'REJECTED', 'FAILED'].map((outcome) => (
                <option key={outcome} value={outcome}>
                  {outcomeLabel(outcome)}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="pps-rec-platform" label="平台码">
            <Input
              id="pps-rec-platform"
              value={recordFilters.platformCode}
              placeholder="AMAZON"
              onChange={(e) => setRecordFilters({ ...recordFilters, platformCode: e.target.value })}
            />
          </FormField>
          <FormField id="pps-rec-site" label="站点码">
            <Input
              id="pps-rec-site"
              value={recordFilters.siteCode}
              placeholder="US"
              onChange={(e) => setRecordFilters({ ...recordFilters, siteCode: e.target.value })}
            />
          </FormField>
          <FormField id="pps-rec-sku" label="SKU id">
            <Input
              id="pps-rec-sku"
              value={recordFilters.skuId}
              onChange={(e) => setRecordFilters({ ...recordFilters, skuId: e.target.value })}
            />
          </FormField>
        </div>

        {records && records.items.length ? (
          <div className="mt-3 overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead>
                <tr className="border-b">
                  <th className="p-2">SKU</th>
                  <th className="p-2">平台 / 站点</th>
                  <th className="p-2">期望价</th>
                  <th className="p-2">平台承接价</th>
                  <th className="p-2">平台实际价</th>
                  <th className="p-2">差额</th>
                  <th className="p-2">结局</th>
                  <th className="p-2">说明</th>
                </tr>
              </thead>
              <tbody>
                {records.items.map((record) => (
                  <tr key={record.id} className="border-border border-b">
                    <td className="p-2">
                      <button
                        type="button"
                        className="font-semibold underline-offset-2 hover:underline"
                        onClick={() => void openDetail(record)}
                      >
                        {record.skuCode}
                      </button>
                    </td>
                    <td className="p-2">
                      {record.platformCode} / {record.siteCode}
                    </td>
                    <td className="p-2">
                      {priceText(record.expectedPrice)} {record.currency}
                    </td>
                    <td className="p-2">
                      {record.predictedPrice === null ? (
                        <span className="text-muted-foreground">本次判定未给出承接价</span>
                      ) : (
                        priceText(record.predictedPrice)
                      )}
                    </td>
                    <td className="p-2">
                      {record.actualPrice === null ? (
                        <span className="text-muted-foreground">待平台回执</span>
                      ) : (
                        priceText(record.actualPrice)
                      )}
                    </td>
                    <td className="p-2">
                      {deltaText(record.priceDelta)}
                      <span className="text-muted-foreground ml-1">
                        ({deviationText(record.deviationRatio)})
                      </span>
                    </td>
                    <td className="p-2">
                      <Badge tone={outcomeTone(record.outcome)}>
                        {outcomeLabel(record.outcome)}
                      </Badge>
                    </td>
                    <td className="p-2">
                      <p className="text-copy-12 text-muted-foreground">{recordHeadline(record)}</p>
                      {record.ruleReason ? (
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          {record.ruleReason}
                        </p>
                      ) : null}
                      {record.lastError ? (
                        <p className="text-copy-12 text-destructive mt-1">{record.lastError}</p>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="mt-3">
            <EmptyState
              title={loaded ? '还没有回写记录' : '正在读回写记录…'}
              description={
                loaded ? '上游的调价单生效后才会产生待回写事实。执行一次同步就会消费它们。' : ''
              }
            />
          </div>
        )}
      </SectionCard>

      {/* 选中记录的差异明细 */}
      {detail && (
        <SectionCard
          title="差异明细"
          description={`${detail.record.skuCode} · ${detail.record.platformCode} / ${detail.record.siteCode} · ${recordHeadline(detail.record)}`}
          extra={
            <Button variant="outline" size="sm" onClick={() => setDetail(null)}>
              收起
            </Button>
          }
        >
          {detail.diffs.length ? (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead>
                  <tr className="border-b">
                    <th className="p-2">来源</th>
                    <th className="p-2">差异</th>
                    <th className="p-2">结局</th>
                    <th className="p-2">原因</th>
                    <th className="p-2">时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.diffs.map((diff: PlatformPriceDiff) => (
                    <tr key={diff.id} className="border-border border-b">
                      <td className="p-2">
                        {diff.source === 'RULE_EVALUATION' ? '规则判定' : '平台回执'}
                      </td>
                      <td className="p-2">{diffHeadline(diff)}</td>
                      <td className="p-2">
                        <Badge tone={outcomeTone(diff.outcome)}>{diff.outcome}</Badge>
                      </td>
                      <td className="p-2">
                        <p className="text-copy-12">{diff.reasonCode}</p>
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          {diff.reasonMessage}
                        </p>
                      </td>
                      <td className="p-2">{diff.createdAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState
              title="这条回写还没有差异记录"
              description="差异记录在派发（规则判定）与平台回执时各追加一条。"
            />
          )}
        </SectionCard>
      )}

      {/* 差异台账 */}
      <SectionCard
        title="差异台账"
        description="实际价与期望价的差异逐条可查。只追加 —— 后来的结论不覆盖前面的历史。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="pps-diff-outcome" label="结局">
            <select
              id="pps-diff-outcome"
              className="border-border bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={diffFilters.outcome}
              onChange={(e) => setDiffFilters({ ...diffFilters, outcome: e.target.value })}
            >
              <option value="">全部结局</option>
              {['MATCHED', 'ADJUSTED', 'REJECTED', 'FAILED'].map((outcome) => (
                <option key={outcome} value={outcome}>
                  {outcome}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="pps-diff-platform" label="平台码">
            <Input
              id="pps-diff-platform"
              value={diffFilters.platformCode}
              onChange={(e) => setDiffFilters({ ...diffFilters, platformCode: e.target.value })}
            />
          </FormField>
          <FormField id="pps-diff-site" label="站点码">
            <Input
              id="pps-diff-site"
              value={diffFilters.siteCode}
              onChange={(e) => setDiffFilters({ ...diffFilters, siteCode: e.target.value })}
            />
          </FormField>
          <FormField id="pps-diff-sku" label="SKU id">
            <Input
              id="pps-diff-sku"
              value={diffFilters.skuId}
              onChange={(e) => setDiffFilters({ ...diffFilters, skuId: e.target.value })}
            />
          </FormField>
        </div>

        {diffs && diffs.items.length ? (
          <div className="mt-3 overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead>
                <tr className="border-b">
                  <th className="p-2">时刻</th>
                  <th className="p-2">SKU</th>
                  <th className="p-2">平台 / 站点</th>
                  <th className="p-2">差异</th>
                  <th className="p-2">来源</th>
                  <th className="p-2">结局</th>
                </tr>
              </thead>
              <tbody>
                {diffs.items.map((diff) => (
                  <tr key={diff.id} className="border-border border-b">
                    <td className="p-2">{diff.createdAt}</td>
                    <td className="p-2">{diff.skuId}</td>
                    <td className="p-2">
                      {diff.platformCode} / {diff.siteCode}
                    </td>
                    <td className="p-2">{diffHeadline(diff)}</td>
                    <td className="p-2">
                      {diff.source === 'RULE_EVALUATION' ? '规则判定' : '平台回执'}
                    </td>
                    <td className="p-2">
                      <Badge tone={outcomeTone(diff.outcome)}>{diff.outcome}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="mt-3">
            <EmptyState
              title={loaded ? '还没有差异记录' : '正在读差异台账…'}
              description={loaded ? '差异在派发（规则判定）与平台回执时各追加一条。' : ''}
            />
          </div>
        )}
      </SectionCard>
    </div>
  );
}
