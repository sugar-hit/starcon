'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { Coins, Gift, RefreshCw, Sliders, TriangleAlert } from 'lucide-react';
import type {
  CrmPointsAccountPage,
  CrmPointsCapabilities,
  CrmPointsEntitlementPage,
  CrmPointsLedgerPage,
  CrmPointsLiability,
  CrmPointsRedemptionPage,
  CrmPointsRulePage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  confirmPointsRedemption,
  createPointsEntitlement,
  createPointsRule,
  earnPoints,
  expirePoints,
  fetchPointsAccounts,
  fetchPointsCapabilities,
  fetchPointsEntitlements,
  fetchPointsLedger,
  fetchPointsLiability,
  fetchPointsRedemptions,
  fetchPointsRules,
  pointsAccountKeyText,
  pointsBalanceText,
  pointsDirectionLabel,
  pointsEmptyStateText,
  pointsEntitlementNote,
  pointsLedgerEmptyStateText,
  pointsLiabilityNote,
  pointsReasonLabel,
  pointsRedemptionEmptyStateText,
  pointsRedemptionNote,
  pointsRedemptionStatusLabel,
  pointsRuleNote,
  pointsSnapshotValue,
  pointsStatsNote,
  redeemPoints,
  spendPoints,
} from './customer-points-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.6.1.2 积分与权益看板。
 *
 * <p>页面按「先看清口径、再看数据、最后才动手」的顺序排。蓝图加粗句是
 * 「**未兑付积分是负债**，需在财务侧有对应科目或备忘，不能只记在业务库」——
 * 所以界面上第一件要让人看到的就是<b>负债汇总是什么口径、以及它**没有**入账</b>，
 * 而不是一张看起来「一切正常」的余额表。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       12 财务侧入账、5 定价侧抵扣写入、积分冲正三块 available=false，
 *       页面把它们**显式灰掉**。</li>
 *   <li><b>积分比率是租户数据</b> —— 每一档规则都写着「拿多少分 / 抵多少比例 /
 *       单积分值多少 / 有效期多久」，页面**不假装**有一套默认比率。</li>
 *   <li><b>余额可审阅</b> —— 流水行显示「变动前余额 → 变动后余额」，
 *       页面**不做**任何前端积分算术。</li>
 *   <li><b>负债如实标注</b> —— 负债卡片明写「**未**在 12 财务侧入账」，
 *       业务侧读数 ≠ 账。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有发过积分」。</li>
 * </ul>
 */
export function PointsWorkspace() {
  const { session } = useIdentity();
  const canUpdate = session.permissions.includes('crm.points.update');

  const [capabilities, setCapabilities] = useState<CrmPointsCapabilities | null>(null);
  const [accounts, setAccounts] = useState<CrmPointsAccountPage | null>(null);
  const [ledger, setLedger] = useState<CrmPointsLedgerPage | null>(null);
  const [rules, setRules] = useState<CrmPointsRulePage | null>(null);
  const [entitlements, setEntitlements] = useState<CrmPointsEntitlementPage | null>(null);
  const [redemptions, setRedemptions] = useState<CrmPointsRedemptionPage | null>(null);
  const [liability, setLiability] = useState<CrmPointsLiability | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 账户筛选
  const [memberKey, setMemberKey] = useState('');
  const [sourceShop, setSourceShop] = useState('');
  const [keyword, setKeyword] = useState('');
  const [onlyWithBalance, setOnlyWithBalance] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // 流水筛选
  const [ledgerDirection, setLedgerDirection] = useState('');
  const [ledgerMemberKey, setLedgerMemberKey] = useState('');

  // 积分操作表单
  const [earnMemberKey, setEarnMemberKey] = useState('');
  const [earnSource, setEarnSource] = useState('PURCHASE');
  const [earnReferenceId, setEarnReferenceId] = useState('');
  const [earnPointsAmount, setEarnPointsAmount] = useState('');
  const [earnNote, setEarnNote] = useState('');

  const [spendMemberKey, setSpendMemberKey] = useState('');
  const [spendOrderId, setSpendOrderId] = useState('');
  const [spendPointsAmount, setSpendPointsAmount] = useState('');

  const [redeemMemberKey, setRedeemMemberKey] = useState('');
  const [redeemEntitlementCode, setRedeemEntitlementCode] = useState('');

  // 规则新建
  const [ruleCode, setRuleCode] = useState('');
  const [ruleName, setRuleName] = useState('');
  const [ruleType, setRuleType] = useState('EARN');
  const [ruleEarnSource, setRuleEarnSource] = useState('PURCHASE');
  const [rulePointsPerUnit, setRulePointsPerUnit] = useState('');
  const [ruleFixedPoints, setRuleFixedPoints] = useState('');
  const [ruleValidMonths, setRuleValidMonths] = useState('');
  const [ruleMaxDeductRate, setRuleMaxDeductRate] = useState('');
  const [rulePointValueRate, setRulePointValueRate] = useState('');
  const [ruleLiabilityCurrency, setRuleLiabilityCurrency] = useState('USD');
  const [ruleEffectiveFrom, setRuleEffectiveFrom] = useState('');
  const [ruleNote, setRuleNote] = useState('');

  // 权益新建
  const [entitlementCode, setEntitlementCode] = useState('');
  const [entitlementName, setEntitlementName] = useState('');
  const [entitlementCost, setEntitlementCost] = useState('');
  const [entitlementType, setEntitlementType] = useState('COUPON');
  const [entitlementValue, setEntitlementValue] = useState('');
  const [entitlementCurrency, setEntitlementCurrency] = useState('USD');

  const filtered = useMemo(
    () => memberKey !== '' || sourceShop !== '' || keyword !== '' || onlyWithBalance,
    [memberKey, sourceShop, keyword, onlyWithBalance],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [accountPage, ledgerPage, rulePage, entitlementPage, redemptionPage, liabilityRow] =
        await Promise.all([
          fetchPointsAccounts({
            memberKey: memberKey || undefined,
            sourceShop: sourceShop || undefined,
            keyword: keyword || undefined,
            onlyWithBalance: onlyWithBalance || undefined,
            page: pageIndex,
            size: pageSize,
          }),
          fetchPointsLedger({
            memberKey: ledgerMemberKey || undefined,
            direction: ledgerDirection || undefined,
            size: 20,
          }),
          fetchPointsRules({ size: 50 }).catch(() => null),
          fetchPointsEntitlements({ size: 50 }).catch(() => null),
          fetchPointsRedemptions({ size: 20 }).catch(() => null),
          fetchPointsLiability().catch(() => null),
        ]);
      setAccounts(accountPage);
      setLedger(ledgerPage);
      if (rulePage) setRules(rulePage);
      if (entitlementPage) setEntitlements(entitlementPage);
      if (redemptionPage) setRedemptions(redemptionPage);
      if (liabilityRow) setLiability(liabilityRow);
    } catch (e) {
      setError(e instanceof Error ? e.message : '积分账户读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [
    memberKey,
    sourceShop,
    keyword,
    onlyWithBalance,
    pageIndex,
    pageSize,
    ledgerDirection,
    ledgerMemberKey,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void fetchPointsCapabilities()
      .then(setCapabilities)
      .catch(() => setCapabilities(null));
  }, []);

  async function run(action: () => Promise<string>, ok: (message: string) => void) {
    setBusy(true);
    setNotice(null);
    setError(null);
    try {
      ok(await action());
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }

  async function submitEarn() {
    await run(
      () =>
        earnPoints({
          memberKey: earnMemberKey.trim(),
          earnSource,
          referenceId: earnReferenceId.trim() || undefined,
          points: earnSource === 'MANUAL' ? earnPointsAmount.trim() : undefined,
          note: earnNote.trim() || undefined,
        }).then((r) => r.note),
      (message) => setNotice(message),
    );
  }

  async function submitSpend() {
    await run(
      () =>
        spendPoints({
          memberKey: spendMemberKey.trim(),
          orderId: spendOrderId.trim(),
          points: spendPointsAmount.trim() || undefined,
        }).then((r) => r.note),
      (message) => setNotice(message),
    );
  }

  async function submitRedeem() {
    await run(
      () =>
        redeemPoints({
          memberKey: redeemMemberKey.trim(),
          entitlementCode: redeemEntitlementCode.trim(),
        }).then((r) => r.note),
      (message) => setNotice(message),
    );
  }

  async function submitRule() {
    await run(
      () =>
        createPointsRule({
          ruleCode: ruleCode.trim().toUpperCase(),
          ruleName: ruleName.trim(),
          ruleType,
          earnSource: ruleType === 'EARN' ? ruleEarnSource : undefined,
          pointsPerUnit:
            ruleType === 'EARN' && ruleEarnSource === 'PURCHASE' && rulePointsPerUnit !== ''
              ? rulePointsPerUnit
              : undefined,
          fixedPoints:
            ruleType === 'EARN' && ruleEarnSource !== 'PURCHASE' && ruleFixedPoints !== ''
              ? ruleFixedPoints
              : undefined,
          validMonths:
            ruleType === 'EARN' && ruleValidMonths !== '' ? Number(ruleValidMonths) : undefined,
          maxDeductRate:
            ruleType === 'SPEND' && ruleMaxDeductRate !== '' ? ruleMaxDeductRate : undefined,
          pointValueRate:
            ruleType === 'VALUATION' && rulePointValueRate !== '' ? rulePointValueRate : undefined,
          liabilityCurrency:
            ruleType === 'VALUATION' && ruleLiabilityCurrency !== ''
              ? ruleLiabilityCurrency.toUpperCase()
              : undefined,
          effectiveFrom: ruleEffectiveFrom
            ? new Date(ruleEffectiveFrom).toISOString()
            : new Date().toISOString(),
          note: ruleNote.trim() || undefined,
        }).then(
          (r) =>
            `积分规则「${r.ruleCode}」已新建。生效区间从现在开始 —— 之前的发放仍按当时的规则。`,
        ),
      (message) => setNotice(message),
    );
    setRuleCode('');
    setRuleName('');
    setRulePointsPerUnit('');
    setRuleFixedPoints('');
    setRuleNote('');
  }

  async function submitEntitlement() {
    await run(
      () =>
        createPointsEntitlement({
          entitlementCode: entitlementCode.trim().toUpperCase(),
          entitlementName: entitlementName.trim(),
          pointCost: entitlementCost.trim(),
          benefitType: entitlementType,
          benefitValue: entitlementType !== 'FREE_SHIPPING' ? entitlementValue.trim() : undefined,
          benefitCurrency:
            entitlementType !== 'FREE_SHIPPING' && entitlementCurrency !== ''
              ? entitlementCurrency.toUpperCase()
              : undefined,
          effectiveFrom: new Date().toISOString(),
        }).then((r) => `权益「${r.entitlementCode}」已新建（${r.pointCost} 分）。`),
      (message) => setNotice(message),
    );
    setEntitlementCode('');
    setEntitlementName('');
    setEntitlementCost('');
    setEntitlementValue('');
  }

  async function completeRedemption(redemptionId: string) {
    await run(
      () =>
        confirmPointsRedemption(redemptionId).then((r) => `权益核销完成（${r.redemptionNo}）。`),
      (message) => setNotice(message),
    );
  }

  async function runExpire() {
    await run(
      () => expirePoints().then((r) => r.note),
      (message) => setNotice(message),
    );
  }

  const stats = accounts?.stats;
  const empty = pointsEmptyStateText(filtered);
  const ledgerEmpty = pointsLedgerEmptyStateText(ledgerMemberKey !== '' || ledgerDirection !== '');
  const redemptionEmpty = pointsRedemptionEmptyStateText(false);

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {error}
        </div>
      )}
      {notice && (
        <div className="border-primary/40 bg-primary/10 text-copy-13 rounded-md px-3 py-2">
          {notice}
        </div>
      )}

      {/* ---------------- 能力边界 ---------------- */}
      <SectionCard
        title="能力边界"
        description="这个看板回答什么、不回答什么 —— 先看清口径再动数据。"
      >
        {capabilities ? (
          <div className="space-y-3">
            <p className="text-muted-foreground text-copy-13">{capabilities.boundaryNote}</p>
            <ul className="space-y-2">
              {capabilities.dimensions.map((dimension) => (
                <li key={dimension.dimension} className="flex items-start gap-2">
                  <Badge tone={dimension.available ? 'success' : 'warning'}>
                    {dimension.available ? '可用' : '未交付'}
                  </Badge>
                  <div className="min-w-0">
                    <div className="text-copy-13 font-medium">{dimension.description}</div>
                    <div className="text-muted-foreground text-copy-12">
                      依据：{dimension.source}
                      {dimension.ownerModule ? ` · 归属模块：${dimension.ownerModule}` : ''}
                    </div>
                    {dimension.impact && (
                      <div className="text-muted-foreground text-copy-12">{dimension.impact}</div>
                    )}
                  </div>
                </li>
              ))}
            </ul>
            <div className="bg-muted/60 rounded-md px-3 py-2">
              <div className="text-copy-13 flex items-center gap-2 font-medium">
                <Sliders className="h-4 w-4" />
                积分比率口径
              </div>
              <p className="text-muted-foreground text-copy-12 mt-1">{capabilities.pointsPolicy}</p>
            </div>
            <div className="border-destructive/40 bg-destructive/10 rounded-md px-3 py-2">
              <div className="text-copy-13 flex items-center gap-2 font-medium">
                <TriangleAlert className="h-4 w-4" />
                积分负债口径
              </div>
              <p className="text-muted-foreground text-copy-12 mt-1">
                {capabilities.liabilityPolicy}
              </p>
            </div>
            <p className="text-muted-foreground text-copy-12">{capabilities.highlight}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 积分概览 + 负债 ---------------- */}
      <SectionCard
        title="积分概览与负债"
        description="🔴 未兑付积分是负债 —— 下面是**业务侧读数**，不是账；12 财务侧尚未交付积分负债科目。"
      >
        {stats ? (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard
                label="积分账户"
                value={stats.totalAccounts}
                icon={<Coins className="h-4 w-4" />}
              />
              <StatCard label="有余额账户" value={stats.accountsWithBalance} />
              <StatCard label="未兑付积分" value={stats.outstandingPoints} unit="分" />
              <StatCard
                label="30 天内到期"
                value={stats.expiringPoints}
                unit="分"
                polarity="negative"
              />
            </div>
            <p className="text-muted-foreground text-copy-12">{pointsStatsNote(stats)}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">统计读取中…</p>
        )}
        <div className="border-destructive/40 bg-destructive/10 mt-3 rounded-md px-3 py-2">
          <div className="text-copy-13 font-medium">积分负债汇总（→ 12 财务）</div>
          {liability ? (
            <p className="text-muted-foreground text-copy-12 mt-1">
              {pointsLiabilityNote(liability)}
            </p>
          ) : (
            <p className="text-muted-foreground text-copy-12 mt-1">负债汇总读取中…</p>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 积分账户 ---------------- */}
      <SectionCard
        title="积分账户"
        description="积分挂在**真实会员**身份上（15.6.1.1 的会员主键）；余额 DB 层硬保证不为负。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-5">
            <Field label="会员主键">
              <input
                className={control}
                value={memberKey}
                onChange={(e) => {
                  setPageIndex(0);
                  setMemberKey(e.target.value);
                }}
              />
            </Field>
            <Field label="来源店铺">
              <input
                className={control}
                value={sourceShop}
                onChange={(e) => {
                  setPageIndex(0);
                  setSourceShop(e.target.value);
                }}
              />
            </Field>
            <Field label="关键词（客户标识 / 买家名 / 账户主键）">
              <input
                className={control}
                value={keyword}
                onChange={(e) => {
                  setPageIndex(0);
                  setKeyword(e.target.value);
                }}
              />
            </Field>
            <Field label="只看有余额的">
              <select
                className={control}
                value={onlyWithBalance ? 'yes' : 'no'}
                onChange={(e) => {
                  setPageIndex(0);
                  setOnlyWithBalance(e.target.value === 'yes');
                }}
              >
                <option value="no">全部</option>
                <option value="yes">仅有余额</option>
              </select>
            </Field>
            <Field label="每页条数">
              <select
                className={control}
                value={String(pageSize)}
                onChange={(e) => {
                  setPageIndex(0);
                  setPageSize(Number(e.target.value));
                }}
              >
                {[10, 20, 50, 100].map((size) => (
                  <option key={size} value={String(size)}>
                    {size}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          {loading && !accounts ? (
            <p className="text-muted-foreground text-copy-13">积分账户读取中…</p>
          ) : !accounts || accounts.rows.length === 0 ? (
            <EmptyState title={empty.title} description={empty.description} />
          ) : (
            <div className="space-y-3">
              <div className="overflow-x-auto">
                <table className="text-copy-13 w-full">
                  <thead className="text-muted-foreground text-copy-12">
                    <tr>
                      <th className="px-2 py-1 text-left">会员</th>
                      <th className="px-2 py-1 text-left">余额</th>
                      <th className="px-2 py-1 text-left">累计</th>
                      <th className="px-2 py-1 text-left">最近获取</th>
                      <th className="px-2 py-1 text-left">最近消耗</th>
                    </tr>
                  </thead>
                  <tbody>
                    {accounts.rows.map((row) => (
                      <tr key={row.id} className="border-control-border border-t">
                        <td className="px-2 py-1">
                          <div>{row.customerName || '—'}</div>
                          <div className="text-muted-foreground text-copy-12">{row.accountKey}</div>
                        </td>
                        <td className="px-2 py-1 font-medium">{row.balance} 分</td>
                        <td className="text-muted-foreground text-copy-12 px-2 py-1">
                          {pointsBalanceText(row)}
                        </td>
                        <td className="text-muted-foreground text-copy-12 px-2 py-1">
                          {row.lastEarnedAt ?? '—'}
                        </td>
                        <td className="text-muted-foreground text-copy-12 px-2 py-1">
                          {row.lastSpentAt ?? '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="text-muted-foreground text-copy-12 flex items-center gap-2">
                <span>
                  第 {accounts.page + 1} / {Math.max(accounts.totalPages, 1)} 页 · 共{' '}
                  {accounts.total} 个
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={accounts.page <= 0}
                  onClick={() => setPageIndex(Math.max(accounts.page - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={accounts.page >= accounts.totalPages - 1}
                  onClick={() => setPageIndex(accounts.page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 积分流水 ---------------- */}
      <SectionCard
        title="积分流水"
        description="每一行都带「变动前余额 → 变动后余额」；流水**只追加**，改不了也删不了。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-3">
            <Field label="会员主键">
              <input
                className={control}
                value={ledgerMemberKey}
                onChange={(e) => setLedgerMemberKey(e.target.value)}
              />
            </Field>
            <Field label="方向">
              <select
                className={control}
                value={ledgerDirection}
                onChange={(e) => setLedgerDirection(e.target.value)}
              >
                <option value="">全部</option>
                <option value="EARN">获取</option>
                <option value="SPEND">消耗</option>
                <option value="EXPIRE">过期</option>
              </select>
            </Field>
          </div>

          {!ledger ? (
            <p className="text-muted-foreground text-copy-13">积分流水读取中…</p>
          ) : ledger.rows.length === 0 ? (
            <EmptyState title={ledgerEmpty.title} description={ledgerEmpty.description} />
          ) : (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">时刻</th>
                    <th className="px-2 py-1 text-left">方向</th>
                    <th className="px-2 py-1 text-left">事由</th>
                    <th className="px-2 py-1 text-left">变动</th>
                    <th className="px-2 py-1 text-left">余额</th>
                    <th className="px-2 py-1 text-left">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {ledger.rows.map((row) => (
                    <tr key={row.id} className="border-control-border border-t">
                      <td className="text-muted-foreground text-copy-12 px-2 py-1">
                        {row.occurredAt}
                      </td>
                      <td className="px-2 py-1">
                        <Badge
                          tone={
                            row.direction === 'EARN'
                              ? 'success'
                              : row.direction === 'EXPIRE'
                                ? 'warning'
                                : 'brand'
                          }
                        >
                          {pointsDirectionLabel(row.direction)}
                        </Badge>
                      </td>
                      <td className="px-2 py-1">{pointsReasonLabel(row.reasonCode)}</td>
                      <td className="px-2 py-1">{row.amount} 分</td>
                      <td className="text-muted-foreground text-copy-12 px-2 py-1">
                        {row.balanceBefore} → {row.balanceAfter}
                      </td>
                      <td className="text-muted-foreground text-copy-12 px-2 py-1">{row.note}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 积分操作 ---------------- */}
      <SectionCard
        title="积分操作"
        description="获取 / 消耗 / 兑换都在这里做 —— 数据真的落 PostgreSQL，不是演示。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-4">
            <Field label="会员主键" hint="15.6.1.1 的会员主键（如 店铺:客户标识）">
              <input
                className={control}
                value={earnMemberKey}
                onChange={(e) => setEarnMemberKey(e.target.value)}
              />
            </Field>
            <Field label="获取来源">
              <select
                className={control}
                value={earnSource}
                onChange={(e) => setEarnSource(e.target.value)}
              >
                <option value="PURCHASE">消费获取（要订单 id）</option>
                <option value="REVIEW">评价获取（要评价号）</option>
                <option value="CHECKIN">签到获取</option>
                <option value="MANUAL">人工发放（要写明积分数与理由）</option>
              </select>
            </Field>
            <Field label="外部引用" hint="消费 = 订单 id；评价 = 评价号；其余留空">
              <input
                className={control}
                value={earnReferenceId}
                onChange={(e) => setEarnReferenceId(e.target.value)}
              />
            </Field>
            <Field label="积分数（仅人工发放）" hint="其它来源由规则算出来">
              <input
                className={control}
                value={earnPointsAmount}
                onChange={(e) => setEarnPointsAmount(e.target.value)}
              />
            </Field>
          </div>
          <Field label="备注" hint="人工发放必须写明理由">
            <input
              className={control}
              value={earnNote}
              onChange={(e) => setEarnNote(e.target.value)}
            />
          </Field>
          <Button onClick={() => void submitEarn()} disabled={!canUpdate || busy || !earnMemberKey}>
            发放积分
          </Button>

          <div className="grid gap-3 sm:grid-cols-3">
            <Field label="抵扣会员主键">
              <input
                className={control}
                value={spendMemberKey}
                onChange={(e) => setSpendMemberKey(e.target.value)}
              />
            </Field>
            <Field label="抵扣订单 id" hint="抵扣上限 = 订单金额 × 抵扣率 ÷ 单积分价值">
              <input
                className={control}
                value={spendOrderId}
                onChange={(e) => setSpendOrderId(e.target.value)}
              />
            </Field>
            <Field label="抵扣积分数" hint="留空 = 用满额（抵扣率上限）">
              <input
                className={control}
                value={spendPointsAmount}
                onChange={(e) => setSpendPointsAmount(e.target.value)}
              />
            </Field>
          </div>
          <Button
            onClick={() => void submitSpend()}
            disabled={!canUpdate || busy || !spendMemberKey || !spendOrderId}
          >
            积分抵扣
          </Button>

          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="兑换会员主键">
              <input
                className={control}
                value={redeemMemberKey}
                onChange={(e) => setRedeemMemberKey(e.target.value)}
              />
            </Field>
            <Field label="权益代码">
              <input
                className={control}
                value={redeemEntitlementCode}
                onChange={(e) => setRedeemEntitlementCode(e.target.value)}
              />
            </Field>
          </div>
          <Button
            onClick={() => void submitRedeem()}
            disabled={!canUpdate || busy || !redeemMemberKey || !redeemEntitlementCode}
          >
            兑换权益
          </Button>

          <div className="border-control-border rounded-md px-3 py-2">
            <div className="text-copy-13 font-medium">到期结算</div>
            <p className="text-muted-foreground text-copy-12 mt-1">
              把**已过期**批次的剩余积分清零，并留一条 EXPIRE 流水。有效期按**绝对时刻**判定。
            </p>
            <Button className="mt-2" onClick={() => void runExpire()} disabled={!canUpdate || busy}>
              <RefreshCw className={busy ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} />
              结算到期积分
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------- 积分规则 ---------------- */}
      <SectionCard
        title="积分规则"
        description="🔴 拿多少分 / 抵多少比例 / 单积分值多少 / 积分有效期 —— **全部是租户数据**，代码里没有写死的比率。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-4">
            <Field label="规则代码">
              <input
                className={control}
                value={ruleCode}
                onChange={(e) => setRuleCode(e.target.value)}
              />
            </Field>
            <Field label="规则名称">
              <input
                className={control}
                value={ruleName}
                onChange={(e) => setRuleName(e.target.value)}
              />
            </Field>
            <Field label="规则类型">
              <select
                className={control}
                value={ruleType}
                onChange={(e) => setRuleType(e.target.value)}
              >
                <option value="EARN">获取规则</option>
                <option value="SPEND">消耗规则（抵扣）</option>
                <option value="VALUATION">估值规则（单积分价值）</option>
              </select>
            </Field>
            {ruleType === 'EARN' && (
              <Field label="获取来源">
                <select
                  className={control}
                  value={ruleEarnSource}
                  onChange={(e) => setRuleEarnSource(e.target.value)}
                >
                  <option value="PURCHASE">消费</option>
                  <option value="REVIEW">评价</option>
                  <option value="CHECKIN">签到</option>
                  <option value="MANUAL">人工</option>
                </select>
              </Field>
            )}
          </div>

          <div className="grid gap-3 sm:grid-cols-4">
            {ruleType === 'EARN' && ruleEarnSource === 'PURCHASE' && (
              <Field label="每 1 单位换多少积分">
                <input
                  className={control}
                  value={rulePointsPerUnit}
                  onChange={(e) => setRulePointsPerUnit(e.target.value)}
                />
              </Field>
            )}
            {ruleType === 'EARN' && ruleEarnSource !== 'PURCHASE' && (
              <Field label="固定积分">
                <input
                  className={control}
                  value={ruleFixedPoints}
                  onChange={(e) => setRuleFixedPoints(e.target.value)}
                />
              </Field>
            )}
            {ruleType === 'EARN' && (
              <Field label="有效期（月）" hint="留空 = 永不过期">
                <input
                  className={control}
                  value={ruleValidMonths}
                  onChange={(e) => setRuleValidMonths(e.target.value)}
                />
              </Field>
            )}
            {ruleType === 'SPEND' && (
              <Field label="抵扣率上限" hint="0.05 = 订单金额最多抵 5%">
                <input
                  className={control}
                  value={ruleMaxDeductRate}
                  onChange={(e) => setRuleMaxDeductRate(e.target.value)}
                />
              </Field>
            )}
            {ruleType === 'VALUATION' && (
              <>
                <Field label="每 1 积分折算金额">
                  <input
                    className={control}
                    value={rulePointValueRate}
                    onChange={(e) => setRulePointValueRate(e.target.value)}
                  />
                </Field>
                <Field label="负债币种">
                  <input
                    className={control}
                    value={ruleLiabilityCurrency}
                    onChange={(e) => setRuleLiabilityCurrency(e.target.value)}
                  />
                </Field>
              </>
            )}
            <Field label="生效起点" hint="留空 = 现在">
              <input
                className={control}
                value={ruleEffectiveFrom}
                onChange={(e) => setRuleEffectiveFrom(e.target.value)}
              />
            </Field>
            <Field label="备注">
              <input
                className={control}
                value={ruleNote}
                onChange={(e) => setRuleNote(e.target.value)}
              />
            </Field>
          </div>

          <Button
            onClick={() => void submitRule()}
            disabled={!canUpdate || busy || !ruleCode || !ruleName}
          >
            新建积分规则
          </Button>

          {!rules ? (
            <p className="text-muted-foreground text-copy-13">积分规则读取中…</p>
          ) : rules.rows.length === 0 ? (
            <EmptyState
              title="还没有积分规则"
              description="不配规则就没有积分 —— 拿多少分、抵多少比例、单积分值多少，全部由租户自己定。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">规则</th>
                    <th className="px-2 py-1 text-left">类型</th>
                    <th className="px-2 py-1 text-left">口径</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.rows.map((rule) => (
                    <tr key={rule.id} className="border-control-border border-t">
                      <td className="px-2 py-1">
                        <div>{rule.ruleName}</div>
                        <div className="text-muted-foreground text-copy-12">{rule.ruleCode}</div>
                      </td>
                      <td className="px-2 py-1">
                        <Badge tone={rule.ruleType === 'VALUATION' ? 'brand' : 'neutral'}>
                          {rule.ruleType}
                        </Badge>
                      </td>
                      <td className="text-muted-foreground text-copy-12 px-2 py-1">
                        {pointsRuleNote(rule)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 权益目录 ---------------- */}
      <SectionCard
        title="权益目录"
        description="可兑换的权益与兑换所需积分 —— 同样是**租户数据**，代码里不写死任何一份权益。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-5">
            <Field label="权益代码">
              <input
                className={control}
                value={entitlementCode}
                onChange={(e) => setEntitlementCode(e.target.value)}
              />
            </Field>
            <Field label="权益名称">
              <input
                className={control}
                value={entitlementName}
                onChange={(e) => setEntitlementName(e.target.value)}
              />
            </Field>
            <Field label="兑换所需积分">
              <input
                className={control}
                value={entitlementCost}
                onChange={(e) => setEntitlementCost(e.target.value)}
              />
            </Field>
            <Field label="权益形态">
              <select
                className={control}
                value={entitlementType}
                onChange={(e) => setEntitlementType(e.target.value)}
              >
                <option value="COUPON">优惠券</option>
                <option value="DISCOUNT">折扣</option>
                <option value="FREE_SHIPPING">免邮</option>
                <option value="PHYSICAL_GIFT">实物</option>
              </select>
            </Field>
            {entitlementType !== 'FREE_SHIPPING' && (
              <>
                <Field label="权益内容（面额 / 折扣率）">
                  <input
                    className={control}
                    value={entitlementValue}
                    onChange={(e) => setEntitlementValue(e.target.value)}
                  />
                </Field>
                <Field label="币种">
                  <input
                    className={control}
                    value={entitlementCurrency}
                    onChange={(e) => setEntitlementCurrency(e.target.value)}
                  />
                </Field>
              </>
            )}
          </div>
          <Button
            onClick={() => void submitEntitlement()}
            disabled={
              !canUpdate || busy || !entitlementCode || !entitlementName || !entitlementCost
            }
          >
            新建权益
          </Button>

          {!entitlements ? (
            <p className="text-muted-foreground text-copy-13">权益目录读取中…</p>
          ) : entitlements.rows.length === 0 ? (
            <EmptyState
              title="还没有权益"
              description="建一项权益之后，积分才能被兑换成它 —— 兑换会同时扣积分并留下一条核销记录。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">权益</th>
                    <th className="px-2 py-1 text-left">兑换</th>
                    <th className="px-2 py-1 text-left">口径</th>
                  </tr>
                </thead>
                <tbody>
                  {entitlements.rows.map((row) => (
                    <tr key={row.id} className="border-control-border border-t">
                      <td className="px-2 py-1">
                        <div className="flex items-center gap-2">
                          <Gift className="h-4 w-4" />
                          <span>{row.entitlementName}</span>
                        </div>
                        <div className="text-muted-foreground text-copy-12">
                          {row.entitlementCode}
                        </div>
                      </td>
                      <td className="px-2 py-1">{row.pointCost} 分</td>
                      <td className="text-muted-foreground text-copy-12 px-2 py-1">
                        {pointsEntitlementNote(row)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 权益核销记录 ---------------- */}
      <SectionCard
        title="权益核销记录"
        description="兑换 → 核销是**单向**的（已兑换待核销 → 已核销）；已核销的事实不许撤回。"
      >
        {!redemptions ? (
          <p className="text-muted-foreground text-copy-13">权益核销记录读取中…</p>
        ) : redemptions.rows.length === 0 ? (
          <EmptyState title={redemptionEmpty.title} description={redemptionEmpty.description} />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full">
              <thead className="text-muted-foreground text-copy-12">
                <tr>
                  <th className="px-2 py-1 text-left">兑换时刻</th>
                  <th className="px-2 py-1 text-left">权益</th>
                  <th className="px-2 py-1 text-left">消耗积分</th>
                  <th className="px-2 py-1 text-left">状态</th>
                  <th className="px-2 py-1 text-left">操作</th>
                </tr>
              </thead>
              <tbody>
                {redemptions.rows.map((row) => (
                  <tr key={row.id} className="border-control-border border-t">
                    <td className="text-muted-foreground text-copy-12 px-2 py-1">
                      {row.redeemedAt}
                    </td>
                    <td className="px-2 py-1">
                      <div>{row.entitlementName}</div>
                      <div className="text-muted-foreground text-copy-12">
                        {pointsRedemptionNote(row)}
                      </div>
                      <div className="text-muted-foreground text-copy-12">
                        权益内容：{pointsSnapshotValue(row.benefitSnapshot, 'benefitValue')}{' '}
                        {pointsSnapshotValue(row.benefitSnapshot, 'benefitCurrency')}
                      </div>
                    </td>
                    <td className="px-2 py-1">{row.pointCost} 分</td>
                    <td className="px-2 py-1">
                      <Badge tone={row.status === 'USED' ? 'success' : 'warning'}>
                        {pointsRedemptionStatusLabel(row.status)}
                      </Badge>
                    </td>
                    <td className="px-2 py-1">
                      {row.status === 'REDEEMED' ? (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={!canUpdate || busy}
                          onClick={() => void completeRedemption(row.id)}
                        >
                          核销
                        </Button>
                      ) : (
                        <span className="text-muted-foreground text-copy-12">
                          {row.usedAt ?? '—'}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 账户主键提示 ---------------- */}
      <SectionCard title="会员主键怎么填" description="积分挂在 15.6.1.1 的会员身份上。">
        <p className="text-muted-foreground text-copy-13">
          会员主键 = 15.6.1.1 会员档案里的 <code>memberKey</code>： 已合并（15.3.1.3
          跨店合并）是逻辑客户号（如 <code>LC-20261102-000001</code>）， 未合并是{' '}
          <code>来源店铺 客户标识</code>（如 <code>AMAZON CUST-A</code>）。
          {pointsAccountKeyText({ accountKey: 'AMAZON CUST-A', customerName: '买家甲' })}
        </p>
      </SectionCard>
    </div>
  );
}
