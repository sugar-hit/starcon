'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinanceDeferredSettlementCapabilities,
  FinanceDeferredSettlementOptions,
  FinanceDeferredSettlementPage,
  FinanceDeferredSettlementRuleRow,
  FinanceDeferredSettlementScanResult,
  FinanceDeferredSettlementSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  deferredSettlementBasisLines,
  deferredSettlementCurrencyLine,
  deferredSettlementModeLabel,
  deferredSettlementScanSummary,
  deferredSettlementStatusLabel,
  deferredSettlementStatusTone,
  fetchDeferredSettlementCapabilities,
  fetchDeferredSettlementOptions,
  fetchDeferredSettlementRules,
  fetchDeferredSettlementSummary,
  fetchDeferredSettlements,
  formatDeferredSettlementDateTime,
  formatDeferredSettlementDay,
  formatDeferredSettlementMoney,
  formatSettlementCondition,
  scanDeferredSettlements,
  upsertDeferredSettlementRule,
  voidDeferredSettlement,
} from './deferred-settlement-model';

/**
 * 12.3.1.3 延迟结算。
 *
 * <p>蓝图 12-finance.md：「管理平台延迟结算（部分平台按周期后结算或滚动结算）：单列在途未结算金额、
 * 结算条件、预计到账时间，支持查询「订单已发货但钱未到账」的明细。」输出：「延迟结算明细 +
 * 在途资金统计 + 预计到账时间。」关系：「下游 = 13 利润（按已结算/未结算区分口径）。」
 *
 * <p><b>页面围绕三条纪律组织</b>：
 *
 * <ol>
 *   <li><b>两套口径必须同屏出现</b>：下游 13 利润要「按已结算 / 未结算区分口径」，
 *       所以页面**同时**显示<b>订单口径</b>、<b>已结算口径</b>与两者的差<b>在途未结算金额</b>，
 *       并把服务端口径定义原样列出来。只显示一个数就等于替下游做了取舍。</li>
 *   <li><b>预计到账时间与结算条件是主角</b>：「什么时候能到账、按什么条件结算」比金额更先被追问 ——
 *       所以这两项摆在明细表的前面。</li>
 *   <li><b>上游没交付的事就说没交付</b>：自动对账任务（12.3.2.1）与利润（13）未交付 ⇒
 *       页面**不提供**「跑对账 / 看利润」的按钮；平台结算报告自动同步（上游「卷 02 同步」）
 *       未交付 ⇒ 页面**不提供**「同步结算报告」的入口。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有延迟结算台账」。
 */
export function DeferredSettlementWorkspace() {
  const [caps, setCaps] = useState<FinanceDeferredSettlementCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceDeferredSettlementOptions | null>(null);
  const [rules, setRules] = useState<FinanceDeferredSettlementRuleRow[]>([]);
  const [page, setPage] = useState<FinanceDeferredSettlementPage | null>(null);
  const [summary, setSummary] = useState<FinanceDeferredSettlementSummary | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [scan, setScan] = useState<FinanceDeferredSettlementScanResult | null>(null);

  // 筛选
  const [status, setStatus] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [currency, setCurrency] = useState('');
  const [keyword, setKeyword] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [currentPage, setCurrentPage] = useState(0);

  // 结算规则
  const [rulePlatform, setRulePlatform] = useState('');
  const [ruleShop, setRuleShop] = useState('');
  const [ruleMode, setRuleMode] = useState<'PERIODIC' | 'ROLLING'>('PERIODIC');
  const [ruleCycle, setRuleCycle] = useState('14');
  const [ruleLag, setRuleLag] = useState('3');
  const [ruleNote, setRuleNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = {
        status: status || undefined,
        platformCode: platformCode || undefined,
        shopCode: shopCode || undefined,
        currency: currency || undefined,
        keyword: keyword || undefined,
        overdueOnly: overdueOnly || undefined,
        page: currentPage,
        size: 50,
      };
      const [capsData, optionsData, rulesData, pageData, summaryData] = await Promise.all([
        fetchDeferredSettlementCapabilities(),
        fetchDeferredSettlementOptions(),
        fetchDeferredSettlementRules(),
        fetchDeferredSettlements(params),
        fetchDeferredSettlementSummary(params),
      ]);
      setCaps(capsData);
      setOptions(optionsData);
      setRules(rulesData);
      setPage(pageData);
      setSummary(summaryData);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [status, platformCode, shopCode, currency, keyword, overdueOnly, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  async function runScan() {
    setActionError('');
    setNotice('');
    setScan(null);
    try {
      const result = await scanDeferredSettlements();
      setScan(result);
      setNotice(deferredSettlementScanSummary(result));
      await load();
    } catch (e) {
      // ⚠️ 上游读不到时后端返回 503 —— 这里**如实显示**失败，绝不显示成「没有订单」。
      setActionError(e instanceof Error ? e.message : '扫描失败');
    }
  }

  async function runUpsertRule() {
    setActionError('');
    setNotice('');
    if (!rulePlatform.trim()) {
      setActionError('请写明平台：没有平台就匹配不到任何订单');
      return;
    }
    const cycle = Number(ruleCycle);
    const lag = Number(ruleLag);
    if (!Number.isInteger(cycle) || cycle < 1 || cycle > 365) {
      setActionError('结算周期天数必须是 1 到 365 的整数');
      return;
    }
    if (!Number.isInteger(lag) || lag < 0 || lag > 365) {
      setActionError('到账偏移天数必须是 0 到 365 的整数');
      return;
    }
    try {
      const saved = await upsertDeferredSettlementRule({
        platformCode: rulePlatform.trim(),
        shopCode: ruleShop.trim() || null,
        shopId: null,
        settlementMode: ruleMode,
        cycleDays: cycle,
        payoutLagDays: lag,
        currency: null,
        conditionNote: ruleNote.trim() || null,
        status: 'ACTIVE',
      });
      setNotice(
        `已保存 ${saved.platformCode}${saved.shopCode ? ` / ${saved.shopCode}` : '（平台级默认规则）'} 的结算规则`,
      );
      setRuleNote('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '保存结算规则失败');
    }
  }

  async function runVoid(id: string, orderNo: string) {
    setActionError('');
    setNotice('');
    const reason = window.prompt(`作废 ${orderNo} 的延迟结算台账行，请写明理由：`);
    if (reason === null) return;
    if (!reason.trim()) {
      setActionError('作废必须写明理由');
      return;
    }
    try {
      await voidDeferredSettlement(id, reason.trim());
      setNotice(`已作废 ${orderNo} 的延迟结算台账行`);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '作废失败');
    }
  }

  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;
  const inTransitCount = summary
    ? summary.summaryByCurrency.reduce((sum, s) => sum + s.pendingCount, 0)
    : 0;
  const overdueCount = summary
    ? summary.summaryByCurrency.reduce((sum, s) => sum + s.overdueCount, 0)
    : 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="延迟结算"
        description="平台按周期后结算 / 滚动结算：在途未结算金额 · 结算条件 · 预计到账时间 · 「订单已发货但钱未到账」的明细"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            <Button onClick={() => void runScan()} disabled={loading}>
              扫描已发货订单
            </Button>
          </div>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 能力声明：上游没交付的事必须说在界面上 ---------- */}
          {caps && caps.capabilities.some((c) => !c.available) && (
            <SectionCard
              title="能力声明"
              description="🔴 标红的这几项**当前不可用** —— 界面不提供它们的入口，也不拿假数据顶替。"
            >
              <ul className="space-y-2 text-sm">
                {caps.capabilities
                  .filter((c) => !c.available)
                  .map((c) => (
                    <li key={`${c.module}-${c.name}`} className="flex items-start gap-2">
                      <Badge tone="danger">不可用</Badge>
                      <span>
                        <b>{c.name}</b>
                        {c.ownerModule ? `（归属模块 ${c.ownerModule}）` : ''}：{c.detail}
                      </span>
                    </li>
                  ))}
              </ul>
            </SectionCard>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="台账笔数"
              value={page?.total ?? 0}
              unit="行"
              comparisonLabel="按当前筛选条件"
            />
            <StatCard
              label="钱未到账"
              value={inTransitCount}
              unit="笔"
              comparisonLabel="已发货但还没出现在已到账结算单里"
            />
            <StatCard
              label="已逾期"
              value={overdueCount}
              unit="笔"
              comparisonLabel="预计到账时间已过，钱仍未到账"
            />
            <StatCard
              label="币种数"
              value={page?.summaryByCurrency.length ?? 0}
              unit="种"
              comparisonLabel="不同币种不合并"
            />
          </div>

          {/* ---------- 在途资金统计（两套口径） ---------- */}
          <SectionCard
            title="在途资金统计"
            description="下游 13 利润要求「按已结算 / 未结算区分口径」，所以这里**同时**给出两套口径与两者的差。⚠️ 金额按币种分组，绝不跨币种相加。"
          >
            {!summary || summary.summaryByCurrency.length === 0 ? (
              <EmptyState
                title="还没有在途资金"
                description="先登记结算规则，再扫描已发货的订单，这里就会显示订单口径、已结算口径与在途未结算金额。"
              />
            ) : (
              <div className="space-y-3">
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">币种</th>
                      <th className="py-2">订单口径</th>
                      <th className="py-2">已结算口径</th>
                      <th className="py-2">在途未结算</th>
                      <th className="py-2">口径基数差</th>
                      <th className="py-2">笔数</th>
                      <th className="py-2">钱未到账</th>
                      <th className="py-2">已逾期</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.summaryByCurrency.map((s) => (
                      <tr key={s.currency} className="border-border border-t">
                        <td className="py-2 font-medium">{s.currency}</td>
                        <td className="py-2">
                          {formatDeferredSettlementMoney(s.orderBasisAmount, s.currency)}
                        </td>
                        <td className="py-2">
                          {formatDeferredSettlementMoney(s.settledBasisAmount, s.currency)}
                        </td>
                        <td className="py-2 font-medium">
                          {formatDeferredSettlementMoney(s.inTransitAmount, s.currency)}
                        </td>
                        <td className="py-2">
                          {formatDeferredSettlementMoney(s.basisGapAmount, s.currency)}
                        </td>
                        <td className="py-2">{s.count}</td>
                        <td className="py-2">{s.pendingCount}</td>
                        <td className="py-2">{s.overdueCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Notice tone="info" title="口径定义">
                  <ul className="space-y-1">
                    {summary.basis &&
                      deferredSettlementBasisLines(summary.basis).map((line) => (
                        <li key={line}>· {line}</li>
                      ))}
                  </ul>
                </Notice>
                {summary.earliestExpectedArrival && (
                  <p className="text-muted-foreground text-xs">
                    最早一笔预计到账：{formatDeferredSettlementDay(summary.earliestExpectedArrival)}
                    （{summary.daysToEarliestExpectedArrival} 天后）
                  </p>
                )}
              </div>
            )}
          </SectionCard>

          {/* ---------- 结算规则 ---------- */}
          <SectionCard
            title="结算规则"
            description="规则是本模块自己的输入：平台 + 店铺 → 结算模式 + 结算周期 + 到账偏移。⚠️ 预计到账时间由服务端从规则派生，页面不提供输入框。"
          >
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-5">
                <Input
                  value={rulePlatform}
                  onChange={(e) => setRulePlatform(e.target.value)}
                  placeholder="平台（如 amazon）"
                />
                <Input
                  value={ruleShop}
                  onChange={(e) => setRuleShop(e.target.value)}
                  placeholder="店铺 code（留空 = 平台级默认规则）"
                />
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={ruleMode}
                  onChange={(e) => setRuleMode(e.target.value as 'PERIODIC' | 'ROLLING')}
                >
                  <option value="PERIODIC">{deferredSettlementModeLabel('PERIODIC')}</option>
                  <option value="ROLLING">{deferredSettlementModeLabel('ROLLING')}</option>
                </select>
                <Input
                  value={ruleCycle}
                  onChange={(e) => setRuleCycle(e.target.value)}
                  placeholder="结算周期天数"
                />
                <Input
                  value={ruleLag}
                  onChange={(e) => setRuleLag(e.target.value)}
                  placeholder="到账偏移天数"
                />
              </div>
              <Input
                value={ruleNote}
                onChange={(e) => setRuleNote(e.target.value)}
                placeholder="结算条件说明（如：发货后进入结算周期，周期结束后 3 天到账）"
              />
              <Button onClick={() => void runUpsertRule()}>保存结算规则</Button>

              {rules.length === 0 ? (
                <EmptyState
                  title="还没有结算规则"
                  description="没有生效规则，扫描时这一批订单会被跳过 —— 页面会把原因逐条列出来。"
                />
              ) : (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">平台</th>
                      <th className="py-2">店铺</th>
                      <th className="py-2">结算模式</th>
                      <th className="py-2">周期天数</th>
                      <th className="py-2">到账偏移</th>
                      <th className="py-2">结算条件</th>
                      <th className="py-2">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rules.map((r) => (
                      <tr key={r.id} className="border-border border-t">
                        <td className="py-2 font-medium">{r.platformCode}</td>
                        <td className="py-2">{r.shopCode ?? '（平台级默认）'}</td>
                        <td className="py-2">{r.settlementModeLabel}</td>
                        <td className="py-2">{r.cycleDays}</td>
                        <td className="py-2">{r.payoutLagDays}</td>
                        <td className="py-2">{formatSettlementCondition(r.conditionNote)}</td>
                        <td className="py-2">
                          <Badge tone={r.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {r.status}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </SectionCard>

          {/* ---------- 筛选 ---------- */}
          <SectionCard title="筛选" description="筛选项全部来自真实数据，不是写死的词表。">
            <div className="grid gap-3 md:grid-cols-5">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部状态</option>
                {(options?.statuses ?? []).map((s) => (
                  <option key={s} value={s}>
                    {deferredSettlementStatusLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={platformCode}
                onChange={(e) => {
                  setPlatformCode(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部平台</option>
                {(options?.platforms ?? []).map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={shopCode}
                onChange={(e) => {
                  setShopCode(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部店铺</option>
                {(options?.shops ?? []).map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                onChange={(e) => {
                  setCurrency(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <Input
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
                placeholder="订单号 / 平台单号 / 店铺"
              />
            </div>
            <label className="text-copy-13 mt-3 flex items-center gap-2">
              <input
                type="checkbox"
                checked={overdueOnly}
                onChange={(e) => {
                  setOverdueOnly(e.target.checked);
                  setCurrentPage(0);
                }}
              />
              只看已逾期（预计到账时间已过、钱仍未到账）
            </label>
          </SectionCard>

          {/* ---------- 扫描结果 ---------- */}
          {scan && (
            <SectionCard
              title="最近一次扫描"
              description="没进来的订单要逐条说明原因 —— 「扫了 0 笔」如果不说清原因，现场会以为平台没有订单。"
            >
              <div className="space-y-2">
                <p className="text-copy-13">{deferredSettlementScanSummary(scan)}</p>
                {scan.warnings.length > 0 ? (
                  <Notice tone="warning" title={`${scan.warnings.length} 条说明`}>
                    <ul className="space-y-1">
                      {scan.warnings.map((w) => (
                        <li key={w}>· {w}</li>
                      ))}
                    </ul>
                  </Notice>
                ) : (
                  <p className="text-muted-foreground text-xs">这次扫描没有跳过任何订单。</p>
                )}
              </div>
            </SectionCard>
          )}

          {/* ---------- 台账明细 ---------- */}
          <SectionCard
            title="延迟结算明细"
            description="「订单已发货但钱未到账」的逐笔台账：预计到账时间与结算条件在前面，两套口径的金额在后面。"
            extra={
              page && page.total > 0 ? (
                <span className="text-muted-foreground text-xs">
                  第 {currentPage + 1} / {totalPages} 页 · 共 {page.total} 笔
                </span>
              ) : null
            }
          >
            {!page || page.items.length === 0 ? (
              <EmptyState
                title={
                  status || platformCode || shopCode || currency || keyword || overdueOnly
                    ? '没有符合条件的延迟结算台账'
                    : '还没有延迟结算台账'
                }
                description={
                  status || platformCode || shopCode || currency || keyword || overdueOnly
                    ? '换个筛选条件试试 —— 台账为空和「筛选没命中」是两回事，这里分开说。'
                    : '先登记结算规则，再点「扫描已发货订单」，把已发货但钱还没到账的订单收进台账。'
                }
              />
            ) : (
              <>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">预计到账</th>
                      <th className="py-2">订单</th>
                      <th className="py-2">平台 / 店铺</th>
                      <th className="py-2">发货时间</th>
                      <th className="py-2">结算周期</th>
                      <th className="py-2">结算条件</th>
                      <th className="py-2">订单口径</th>
                      <th className="py-2">已结算口径</th>
                      <th className="py-2">在途未结算</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((row) => (
                      <tr key={row.id} className="border-border border-t">
                        <td className="py-2 font-medium">
                          {formatDeferredSettlementDay(row.expectedArrivedAt)}
                        </td>
                        <td className="py-2">
                          {row.orderNo}
                          <div className="text-muted-foreground text-xs">{row.externalOrderNo}</div>
                        </td>
                        <td className="py-2">
                          {row.platformCode}
                          <div className="text-muted-foreground text-xs">{row.shopCode || '—'}</div>
                        </td>
                        <td className="py-2">{formatDeferredSettlementDateTime(row.shippedAt)}</td>
                        <td className="py-2">
                          {formatDeferredSettlementDay(row.settlementCycleStart)} ~
                          {formatDeferredSettlementDay(row.settlementCycleEnd)}
                          <div className="text-muted-foreground text-xs">
                            {row.settlementModeLabel}
                          </div>
                        </td>
                        <td className="py-2">{formatSettlementCondition(row.conditionNote)}</td>
                        <td className="py-2">
                          {formatDeferredSettlementMoney(row.orderAmount, row.currency)}
                        </td>
                        <td className="py-2">
                          {formatDeferredSettlementMoney(row.settledAmount, row.currency)}
                        </td>
                        <td className="py-2 font-medium">
                          {formatDeferredSettlementMoney(row.unsettledAmount, row.currency)}
                        </td>
                        <td className="py-2">
                          <Badge tone={deferredSettlementStatusTone(row.status)}>
                            {deferredSettlementStatusLabel(row.status)}
                          </Badge>
                        </td>
                        <td className="py-2">
                          {row.status !== 'VOID' && (
                            <Button
                              variant="secondary"
                              onClick={() => void runVoid(row.id, row.orderNo)}
                            >
                              作废
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="mt-3 flex items-center gap-2">
                  <Button
                    variant="secondary"
                    disabled={currentPage <= 0}
                    onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={currentPage + 1 >= totalPages}
                    onClick={() => setCurrentPage((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </>
            )}
          </SectionCard>

          {/* ---------- 按币种的口径速览 ---------- */}
          {page && page.summaryByCurrency.length > 0 && (
            <SectionCard
              title="按币种的口径速览"
              description="与上面「在途资金统计」同源：三个数由服务端算好，页面只原样显示，不做加减。"
            >
              <ul className="space-y-1 text-sm">
                {page.summaryByCurrency.map((s) => (
                  <li key={s.currency}>{deferredSettlementCurrencyLine(s)}</li>
                ))}
              </ul>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
