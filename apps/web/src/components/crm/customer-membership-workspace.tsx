'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { Crown, RefreshCw, Sliders, Ticket } from 'lucide-react';
import type {
  CrmMembershipCapabilities,
  CrmMembershipLevelChangePage,
  CrmMembershipLevelRulePage,
  CrmMembershipMemberPage,
  CrmMembershipRunPage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  computeMembership,
  createMembershipLevelRule,
  fetchMembershipBenefits,
  fetchMembershipCapabilities,
  fetchMembershipLevelChanges,
  fetchMembershipLevelRules,
  fetchMembershipMembers,
  fetchMembershipRuns,
  membershipBenefitConfigNote,
  membershipBenefitText,
  membershipChangeKindLabel,
  membershipEmptyStateText,
  membershipLevelChangeEmptyStateText,
  membershipLevelLabel,
  membershipLevelRuleNote,
  membershipMemberKeyText,
  membershipMonetaryText,
  membershipOriginLabel,
  membershipRepurchaseText,
  membershipStatsNote,
  membershipThresholdsText,
} from './customer-membership-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.6.1.1 会员等级看板（会员与等级）。
 *
 * <p>页面按「先看清口径、再看数据、最后才动手」的顺序排。蓝图加粗句是
 * 「**会员等级必须由真实消费数据驱动**，手工挂等级会导致权益成本失控」——
 * 所以界面上第一件要让人看到的就是<b>等级从哪来、且没有手工挂等级的入口</b>，
 * 而不是一张可以直接拖拽改等级的表。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       人工覆盖等级与平台会员体系对接两块 available=false，页面把它们**显式灰掉**。</li>
 *   <li><b>等级来源常驻</b> —— 每一行都写着「规则算出来的（真实消费数据驱动）」，
 *       页面**没有**任何设置等级的入口。</li>
 *   <li><b>判定理由能看见</b> —— 每一行带「凭什么落这一级」的理由文本，
 *       等级变更记录带判定依据快照。</li>
 *   <li><b>「未达任何等级」是一等状态</b> —— 不画成「普通会员」。</li>
 *   <li><b>金额不跨币种相加</b> —— 分币种分行显示，页面**不做**任何前端金额算术。</li>
 *   <li><b>权益只输出不代劳</b> —— 权益生效配置交给 5 定价 / 14 促销，
 *       本页面**不改**价目表或促销表。</li>
 * </ul>
 */
export function MembershipWorkspace() {
  const { session } = useIdentity();
  const canUpdate = session.permissions.includes('crm.membership.update');

  const [capabilities, setCapabilities] = useState<CrmMembershipCapabilities | null>(null);
  const [page, setPage] = useState<CrmMembershipMemberPage | null>(null);
  const [rules, setRules] = useState<CrmMembershipLevelRulePage | null>(null);
  const [changes, setChanges] = useState<CrmMembershipLevelChangePage | null>(null);
  const [benefits, setBenefits] = useState<Awaited<
    ReturnType<typeof fetchMembershipBenefits>
  > | null>(null);
  const [runs, setRuns] = useState<CrmMembershipRunPage | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [computing, setComputing] = useState(false);

  // 筛选
  const [levelCode, setLevelCode] = useState('');
  const [sourceShop, setSourceShop] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // 等级规则新建
  const [ruleCode, setRuleCode] = useState('');
  const [ruleName, setRuleName] = useState('');
  const [ruleRank, setRuleRank] = useState('1');
  const [ruleMinSpend, setRuleMinSpend] = useState('');
  const [ruleCurrency, setRuleCurrency] = useState('USD');
  const [ruleMinOrders, setRuleMinOrders] = useState('');
  const [ruleMinRepurchase, setRuleMinRepurchase] = useState('');
  const [ruleDiscount, setRuleDiscount] = useState('');
  const [ruleFreeShipping, setRuleFreeShipping] = useState(false);
  const [rulePriority, setRulePriority] = useState(false);
  const [ruleEffectiveFrom, setRuleEffectiveFrom] = useState('');
  const [ruleNote, setRuleNote] = useState('');

  const filtered = useMemo(
    () => levelCode !== '' || sourceShop !== '' || keyword !== '',
    [levelCode, sourceShop, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [memberPage, rulePage, changePage, benefitConfig, runPage] = await Promise.all([
        fetchMembershipMembers({
          levelCode: levelCode || undefined,
          sourceShop: sourceShop || undefined,
          keyword: keyword || undefined,
          page: pageIndex,
          size: pageSize,
        }),
        fetchMembershipLevelRules({ size: 50 }).catch(() => null),
        fetchMembershipLevelChanges({ size: 20 }).catch(() => null),
        fetchMembershipBenefits().catch(() => null),
        fetchMembershipRuns().catch(() => null),
      ]);
      setPage(memberPage);
      if (rulePage) setRules(rulePage);
      if (changePage) setChanges(changePage);
      if (benefitConfig) setBenefits(benefitConfig);
      if (runPage) setRuns(runPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '会员档案读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [levelCode, sourceShop, keyword, pageIndex, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void fetchMembershipCapabilities()
      .then(setCapabilities)
      .catch(() => setCapabilities(null));
  }, []);

  async function runCompute() {
    setComputing(true);
    setNotice(null);
    setError(null);
    try {
      const result = await computeMembership();
      setNotice(
        `重算完成：会员 ${result.memberCount} 位 · 等级变化 ${result.levelChangedCount} 位 · ` +
          `生效规则 ${result.ruleCount} 条` +
          (result.unmatchedCount > 0 ? ` · 未达任何等级 ${result.unmatchedCount} 位` : '') +
          (result.currencySkippedCount > 0
            ? ` · 因币种对不上跳过金额规则 ${result.currencySkippedCount} 位`
            : '') +
          (result.truncated ? ' · 🔴 本次扫描被截断，名单不是全量' : ''),
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '重算失败，请稍后重试');
    } finally {
      setComputing(false);
    }
  }

  async function saveRule() {
    setError(null);
    try {
      await createMembershipLevelRule({
        levelCode: ruleCode.trim().toUpperCase(),
        levelName: ruleName.trim(),
        levelRank: Number(ruleRank),
        minTotalSpend: ruleMinSpend === '' ? undefined : Number(ruleMinSpend),
        minTotalSpendCurrency: ruleMinSpend === '' ? undefined : ruleCurrency.trim().toUpperCase(),
        minOrderCount: ruleMinOrders === '' ? undefined : Number(ruleMinOrders),
        minRepurchaseRate: ruleMinRepurchase === '' ? undefined : Number(ruleMinRepurchase),
        discountRate: ruleDiscount === '' ? undefined : Number(ruleDiscount),
        freeShipping: ruleFreeShipping,
        priorityShipping: rulePriority,
        effectiveFrom: ruleEffectiveFrom
          ? new Date(ruleEffectiveFrom).toISOString()
          : new Date().toISOString(),
        note: ruleNote.trim() || undefined,
      });
      setNotice('等级规则已新建。生效区间从现在开始 —— 之前的判定仍按当时的规则。');
      setRuleCode('');
      setRuleName('');
      setRuleMinSpend('');
      setRuleMinOrders('');
      setRuleMinRepurchase('');
      setRuleDiscount('');
      setRuleNote('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '等级规则保存失败');
    }
  }

  const stats = page?.stats;
  const empty = membershipEmptyStateText(filtered, (runs?.total ?? 0) > 0);
  const changeEmpty = membershipLevelChangeEmptyStateText(filtered);

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
                等级门槛口径
              </div>
              <p className="text-muted-foreground text-copy-12 mt-1">
                {capabilities.levelRulePolicy}
              </p>
            </div>
            <p className="text-muted-foreground text-copy-12">{capabilities.highlight}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 会员概览 ---------------- */}
      <SectionCard
        title="会员概览"
        description="🔴 这些数字来自独立查询，不受分页影响：翻到第 3 页看到的分布与第 1 页完全一样。"
      >
        {stats ? (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard
                label="会员数"
                value={stats.totalMembers}
                icon={<Crown className="h-4 w-4" />}
              />
              <StatCard
                label="未达任何等级"
                value={stats.unmatchedMembers}
                polarity="negative"
                icon={<Ticket className="h-4 w-4" />}
              />
              <StatCard label="升级次数" value={stats.upgradedMembers} />
              <StatCard label="降级次数" value={stats.downgradedMembers} />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">等级分布</div>
                {stats.byLevel.length === 0 ? (
                  <div className="text-copy-13">无</div>
                ) : (
                  stats.byLevel.map((bucket) => (
                    <div key={bucket.levelCode} className="text-copy-13">
                      {bucket.levelName}（{bucket.levelCode}）：{bucket.memberCount} 位
                    </div>
                  ))
                )}
              </div>
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">
                  累计消费（按币种分行，**不跨币种相加**）
                </div>
                {stats.spendByCurrency.length === 0 ? (
                  <div className="text-copy-13">无</div>
                ) : (
                  stats.spendByCurrency.map((amount) => (
                    <div key={amount.currency} className="text-copy-13">
                      {membershipMonetaryText(amount.totalSpend, amount.currency)}（
                      {amount.orderCount} 单）
                    </div>
                  ))
                )}
              </div>
            </div>
            <p className="text-muted-foreground text-copy-12">{membershipStatsNote(stats)}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">统计读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 会员档案 ---------------- */}
      <SectionCard
        title="会员档案"
        description="等级**只能**由真实消费数据算出 —— 本页面没有任何「手工指定等级」的入口。"
        extra={
          <Button onClick={() => void runCompute()} disabled={!canUpdate || computing}>
            <RefreshCw className={computing ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} />
            重算会员等级
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-4">
            <Field label="等级">
              <select
                className={control}
                value={levelCode}
                onChange={(e) => {
                  setPageIndex(0);
                  setLevelCode(e.target.value);
                }}
              >
                <option value="">全部等级</option>
                <option value="NONE">未达任何等级</option>
                {(rules?.rows ?? []).map((rule) => (
                  <option key={rule.id} value={rule.levelCode}>
                    {rule.levelName}（{rule.levelCode}）
                  </option>
                ))}
              </select>
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
            <Field label="关键词（客户标识 / 买家名 / 会员主键）">
              <input
                className={control}
                value={keyword}
                onChange={(e) => {
                  setPageIndex(0);
                  setKeyword(e.target.value);
                }}
              />
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

          {loading && !page ? (
            <p className="text-muted-foreground text-copy-13">会员档案读取中…</p>
          ) : !page || page.rows.length === 0 ? (
            <EmptyState title={empty.title} description={empty.description} />
          ) : (
            <div className="space-y-3">
              <div className="overflow-x-auto">
                <table className="text-copy-13 w-full">
                  <thead className="text-muted-foreground text-copy-12">
                    <tr>
                      <th className="px-2 py-1 text-left">会员</th>
                      <th className="px-2 py-1 text-left">等级</th>
                      <th className="px-2 py-1 text-left">等级来源</th>
                      <th className="px-2 py-1 text-left">消费</th>
                      <th className="px-2 py-1 text-left">复购率</th>
                      <th className="px-2 py-1 text-left">判定理由</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.rows.map((row) => (
                      <tr key={row.id} className="border-control-border border-t">
                        <td className="px-2 py-1">
                          <div>{row.customerName || '—'}</div>
                          <div className="text-muted-foreground text-copy-12">
                            {membershipMemberKeyText(row)}
                          </div>
                        </td>
                        <td className="px-2 py-1">
                          <Badge tone={row.levelCode === 'NONE' ? 'neutral' : 'brand'}>
                            {membershipLevelLabel(row.levelCode)}
                          </Badge>
                        </td>
                        <td className="text-muted-foreground text-copy-12 px-2 py-1">
                          {membershipOriginLabel(row.levelOrigin)}
                        </td>
                        <td className="px-2 py-1">
                          <div>{membershipMonetaryText(row.totalSpend, row.spendCurrency)}</div>
                          <div className="text-muted-foreground text-copy-12">
                            {row.orderCount} 单
                          </div>
                        </td>
                        <td className="px-2 py-1">
                          {membershipRepurchaseText(row.repurchaseRate)}
                        </td>
                        <td className="text-muted-foreground text-copy-12 px-2 py-1">
                          {row.levelReason}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="text-muted-foreground text-copy-12 flex items-center gap-2">
                <span>
                  第 {page.page + 1} / {Math.max(page.totalPages, 1)} 页 · 共 {page.total} 位
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page <= 0}
                  onClick={() => setPageIndex(Math.max(page.page - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page + 1 >= page.totalPages}
                  onClick={() => setPageIndex(page.page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 等级规则 ---------------- */}
      <SectionCard
        title="等级规则与权益"
        description="门槛是**租户数据**（可配、可留空、带生效区间）。代码里没有任何写死的门槛。"
      >
        <div className="space-y-3">
          {rules && rules.rows.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">等级</th>
                    <th className="px-2 py-1 text-left">门槛</th>
                    <th className="px-2 py-1 text-left">权益</th>
                    <th className="px-2 py-1 text-left">生效</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.rows.map((rule) => (
                    <tr key={rule.id} className="border-control-border border-t">
                      <td className="px-2 py-1">
                        {rule.levelName}（{rule.levelCode}）
                        <div className="text-muted-foreground text-copy-12">
                          等级序 {rule.levelRank}
                        </div>
                      </td>
                      <td className="px-2 py-1">{membershipThresholdsText(rule)}</td>
                      <td className="px-2 py-1">{membershipBenefitText(rule)}</td>
                      <td className="text-muted-foreground text-copy-12 px-2 py-1">
                        {rule.effectiveNow ? '生效中' : rule.enabled ? '未生效' : '已停用'}
                        <div>{rule.effectiveFrom}</div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState
              title="还没有等级规则"
              description="没有规则就没有等级 —— 所有会员都会是「未达任何等级」。新建一条规则开始。"
            />
          )}

          {canUpdate && (
            <div className="border-control-border space-y-3 rounded-md px-3 py-3">
              <div className="text-copy-13 font-medium">新建等级规则</div>
              <div className="grid gap-3 sm:grid-cols-3">
                <Field label="等级代码（如 GOLD）">
                  <input
                    className={control}
                    value={ruleCode}
                    onChange={(e) => setRuleCode(e.target.value)}
                  />
                </Field>
                <Field label="等级名称（如 黄金会员）">
                  <input
                    className={control}
                    value={ruleName}
                    onChange={(e) => setRuleName(e.target.value)}
                  />
                </Field>
                <Field label="等级序（越大越高）">
                  <input
                    className={control}
                    value={ruleRank}
                    onChange={(e) => setRuleRank(e.target.value)}
                  />
                </Field>
                <Field label="累计消费门槛" hint="留空 = 不限这一维">
                  <input
                    className={control}
                    value={ruleMinSpend}
                    onChange={(e) => setRuleMinSpend(e.target.value)}
                  />
                </Field>
                <Field label="消费门槛币种" hint="给了金额门槛就必须给币种">
                  <input
                    className={control}
                    value={ruleCurrency}
                    onChange={(e) => setRuleCurrency(e.target.value)}
                  />
                </Field>
                <Field label="订单数门槛" hint="留空 = 不限这一维">
                  <input
                    className={control}
                    value={ruleMinOrders}
                    onChange={(e) => setRuleMinOrders(e.target.value)}
                  />
                </Field>
                <Field label="复购率门槛" hint="0~1；留空 = 不限这一维">
                  <input
                    className={control}
                    value={ruleMinRepurchase}
                    onChange={(e) => setRuleMinRepurchase(e.target.value)}
                  />
                </Field>
                <Field label="折扣率" hint="0.05 = 减免 5%">
                  <input
                    className={control}
                    value={ruleDiscount}
                    onChange={(e) => setRuleDiscount(e.target.value)}
                  />
                </Field>
                <Field label="生效起点" hint="留空 = 现在开始">
                  <input
                    type="datetime-local"
                    className={control}
                    value={ruleEffectiveFrom}
                    onChange={(e) => setRuleEffectiveFrom(e.target.value)}
                  />
                </Field>
              </div>
              <div className="flex flex-wrap items-center gap-4">
                <label className="text-copy-13 flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={ruleFreeShipping}
                    onChange={(e) => setRuleFreeShipping(e.target.checked)}
                  />
                  免邮权益
                </label>
                <label className="text-copy-13 flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={rulePriority}
                    onChange={(e) => setRulePriority(e.target.checked)}
                  />
                  优先发货权益
                </label>
              </div>
              <Field label="备注（写清这一档为什么是这一档）">
                <input
                  className={control}
                  value={ruleNote}
                  onChange={(e) => setRuleNote(e.target.value)}
                />
              </Field>
              <Button onClick={() => void saveRule()} disabled={!canUpdate}>
                新建等级规则
              </Button>
              <p className="text-muted-foreground text-copy-12">
                🔴 门槛与等级序**不可就地修改**：改了它，之前引用这条规则的会员判定就解释不通了。
                要改门槛请新建一条（新的生效起点），旧的那条会被自动关掉。
              </p>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 权益生效配置 ---------------- */}
      <SectionCard
        title="权益生效配置（→ 5 定价 / 14 促销）"
        description="本模块**只输出**这份配置 —— 不改价目表、不改促销表（跨服务禁止）。"
      >
        {benefits && benefits.rows.length > 0 ? (
          <div className="space-y-3">
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">等级</th>
                    <th className="px-2 py-1 text-left">权益</th>
                    <th className="px-2 py-1 text-left">生效条件</th>
                    <th className="px-2 py-1 text-left">下游</th>
                  </tr>
                </thead>
                <tbody>
                  {benefits.rows.map((row) => (
                    <tr key={row.levelCode} className="border-control-border border-t">
                      <td className="px-2 py-1">
                        {row.levelName}（{row.levelCode}）
                      </td>
                      <td className="px-2 py-1">{row.benefitSummary}</td>
                      <td className="px-2 py-1">{row.appliesTo}</td>
                      <td className="text-muted-foreground text-copy-12 px-2 py-1">
                        {row.downstreamConsumers.join('、')}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-muted-foreground text-copy-12">{benefits.note}</p>
          </div>
        ) : (
          <EmptyState
            title="当前没有生效中的等级权益"
            description="没有生效中的等级规则时，下游拿到的就是「没有等级权益」—— 这比给全员打折安全。"
          />
        )}
      </SectionCard>

      {/* ---------------- 等级变更记录 ---------------- */}
      <SectionCard
        title="等级变更记录"
        description="升级 / 降级 / 首次定级都留痕，每条带判定依据快照。"
      >
        {changes && changes.rows.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full">
              <thead className="text-muted-foreground text-copy-12">
                <tr>
                  <th className="px-2 py-1 text-left">会员</th>
                  <th className="px-2 py-1 text-left">变更</th>
                  <th className="px-2 py-1 text-left">来源</th>
                  <th className="px-2 py-1 text-left">理由</th>
                  <th className="px-2 py-1 text-left">时刻</th>
                </tr>
              </thead>
              <tbody>
                {changes.rows.map((row) => (
                  <tr key={row.id} className="border-control-border border-t">
                    <td className="px-2 py-1">
                      {row.customerName || '—'}
                      <div className="text-muted-foreground text-copy-12">{row.memberKey}</div>
                    </td>
                    <td className="px-2 py-1">
                      <Badge
                        tone={
                          row.changeKind === 'UPGRADE'
                            ? 'success'
                            : row.changeKind === 'DOWNGRADE'
                              ? 'warning'
                              : 'info'
                        }
                      >
                        {membershipChangeKindLabel(row.changeKind)}
                      </Badge>
                      <div className="text-muted-foreground text-copy-12">
                        {membershipLevelLabel(row.fromLevelCode)} →{' '}
                        {membershipLevelLabel(row.toLevelCode)}
                      </div>
                    </td>
                    <td className="text-muted-foreground text-copy-12 px-2 py-1">
                      {membershipOriginLabel(row.origin)}
                    </td>
                    <td className="text-muted-foreground text-copy-12 px-2 py-1">{row.reason}</td>
                    <td className="text-muted-foreground text-copy-12 px-2 py-1">
                      {row.changedAt}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState title={changeEmpty.title} description={changeEmpty.description} />
        )}
      </SectionCard>

      {/* ---------------- 重算运行记录 ---------------- */}
      {runs && runs.rows.length > 0 && (
        <SectionCard
          title="重算运行记录"
          description="这次扫了多少、变了多少级、有没有被截断 —— 逐次可查。"
        >
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full">
              <thead className="text-muted-foreground text-copy-12">
                <tr>
                  <th className="px-2 py-1 text-left">运行号</th>
                  <th className="px-2 py-1 text-left">窗口</th>
                  <th className="px-2 py-1 text-left">会员</th>
                  <th className="px-2 py-1 text-left">等级变化</th>
                  <th className="px-2 py-1 text-left">未达等级</th>
                  <th className="px-2 py-1 text-left">说明</th>
                </tr>
              </thead>
              <tbody>
                {runs.rows.map((run) => (
                  <tr key={run.id} className="border-control-border border-t">
                    <td className="px-2 py-1">{run.runNo}</td>
                    <td className="text-muted-foreground text-copy-12 px-2 py-1">
                      {run.windowFrom ?? '全部历史'} → {run.windowTo}
                    </td>
                    <td className="px-2 py-1">{run.memberCount}</td>
                    <td className="px-2 py-1">{run.levelChangedCount}</td>
                    <td className="px-2 py-1">{run.unmatchedCount}</td>
                    <td className="text-muted-foreground text-copy-12 px-2 py-1">
                      {run.failureReason || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 规则说明 ---------------- */}
      <SectionCard title="规则说明">
        <ul className="text-muted-foreground text-copy-12 space-y-1">
          <li>
            等级来源恒为「规则算出来的」；人工覆盖通道**本轮未交付**，页面与后端都**没有**写入口。
          </li>
          <li>累计消费 / 订单数 / 复购率三条门槛全部可配、可留空，全部由租户自己定。</li>
          <li>「未达任何等级」是一等状态，不画成「普通会员」。</li>
          <li>金额按币种分行，页面不做任何金额算术，也不跨币种相加。</li>
          <li>权益生效配置交给 5 定价 / 14 促销；本模块不代劳改价目表或促销表。</li>
        </ul>
      </SectionCard>
    </div>
  );
}
