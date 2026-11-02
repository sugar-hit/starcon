'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { AlertTriangle, Eye, RefreshCw, ShieldAlert, Users } from 'lucide-react';
import type {
  CrmCustomerListCapabilities,
  CrmCustomerListPage,
  CrmCustomerListRow,
  CrmCustomerListSyncRunPage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  customerAmountText,
  customerContactIsPlain,
  customerContactText,
  customerEmptyStateText,
  customerOptOutStateLabel,
  customerPurchaseWindowText,
  customerRefundRateText,
  customerRiskFlagLabel,
  customerValueTierLabel,
  fetchCustomerList,
  fetchCustomerListCapabilities,
  fetchCustomerSyncRuns,
  registerCustomerContact,
  syncCustomerList,
} from './customer-list-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.3.1.1 客户列表工作区。
 *
 * <p>页面按「先看清边界、再看数据、最后才动手」的顺序排：
 * 蓝图加粗句是「**列表默认脱敏显示联系方式**，需要显式授权才完整展示」——
 * 界面上第一件要让人看到的就是**能力边界**与**脱敏口径**，
 * 而不是一张能直接点「批量导出手机号」的表。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       逻辑客户（15.3.1.3 跨店合并）与客户详情（15.3.1.2）两块 available=false，
 *       页面把它们**显式灰掉**并写清归属模块。</li>
 *   <li><b>分层统计</b> —— 价值分层 / 来源店铺 / 风险标记 / 币种。
 *       🔴 这些数字来自**独立查询**，**不受分页影响**（翻到第 3 页分布也不变）。</li>
 *   <li><b>客户列表</b> —— 一行 = 一个来源店铺下的一个客户。
 *       同一个客户标识在两个来源下是**两行**：那是 15.3.1.3 跨店合并要做的事，
 *       本模块**不提前合并**，页面把「来源店铺」摆在最显眼的位置。</li>
 *   <li><b>联系方式默认脱敏</b> —— 明文要**另外**的权限码（crm.customers.pii.read）
 *       + 页面上的显式开关；没有权限时开关**禁用**（不是点了之后报 403）。</li>
 *   <li><b>刷新客户档案</b> —— 客户档案来自上游 9 订单的订单事实（授权 HTTP API 只读）。
 *       上游读不到会明确失败，页面如实显示失败原因。</li>
 * </ul>
 */
export function CustomerListWorkspace() {
  const { session } = useIdentity();
  const canUpdate = session.permissions.includes('crm.customers.update');
  const canSeePlainContact = session.permissions.includes('crm.customers.pii.read');

  const [capabilities, setCapabilities] = useState<CrmCustomerListCapabilities | null>(null);
  const [page, setPage] = useState<CrmCustomerListPage | null>(null);
  const [runs, setRuns] = useState<CrmCustomerListSyncRunPage | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);

  // 筛选条件
  const [sourceShop, setSourceShop] = useState('');
  const [tagCode, setTagCode] = useState('');
  const [valueTier, setValueTier] = useState('');
  const [riskFlag, setRiskFlag] = useState('');
  const [keyword, setKeyword] = useState('');
  const [minOrderCount, setMinOrderCount] = useState('');
  const [minTotalAmount, setMinTotalAmount] = useState('');
  const [currency, setCurrency] = useState('');
  const [sort, setSort] = useState('ORDER_COUNT_DESC');
  const [revealContact, setRevealContact] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // 联系方式登记
  const [contactRef, setContactRef] = useState('');
  const [contactShop, setContactShop] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactPhone, setContactPhone] = useState('');

  const filtered = useMemo(
    () =>
      sourceShop !== '' ||
      tagCode !== '' ||
      valueTier !== '' ||
      riskFlag !== '' ||
      keyword !== '' ||
      minOrderCount !== '' ||
      minTotalAmount !== '',
    [sourceShop, tagCode, valueTier, riskFlag, keyword, minOrderCount, minTotalAmount],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, runPage] = await Promise.all([
        fetchCustomerList({
          sourceShop: sourceShop || undefined,
          tagCode: tagCode || undefined,
          valueTier: valueTier || undefined,
          riskFlag: riskFlag || undefined,
          keyword: keyword || undefined,
          minOrderCount: minOrderCount === '' ? undefined : Number(minOrderCount),
          minTotalAmount: minTotalAmount === '' ? undefined : minTotalAmount,
          currency: currency || undefined,
          sort,
          revealContact: revealContact || undefined,
          page: pageIndex,
          size: pageSize,
        }),
        fetchCustomerSyncRuns().catch(() => null),
      ]);
      setPage(list);
      if (runPage) setRuns(runPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '客户列表读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [
    sourceShop,
    tagCode,
    valueTier,
    riskFlag,
    keyword,
    minOrderCount,
    minTotalAmount,
    currency,
    sort,
    revealContact,
    pageIndex,
    pageSize,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void fetchCustomerListCapabilities()
      .then(setCapabilities)
      .catch(() => setCapabilities(null));
  }, []);

  async function runSync() {
    setSyncing(true);
    setNotice(null);
    setError(null);
    try {
      const result = await syncCustomerList();
      setNotice(
        `刷新完成：客户 ${result.customerCount} 位 · 订单 ${result.orderCount} 单 · 退款单 ${result.caseCount} 张` +
          (result.contactUnavailableCount > 0
            ? ` · 联系方式没取到 ${result.contactUnavailableCount} 位`
            : '') +
          (result.truncated ? ' · 🔴 本次扫描被截断，名单不是全量' : '') +
          (result.failureReason ? ` · ${result.failureReason}` : ''),
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新失败，请稍后重试');
    } finally {
      setSyncing(false);
    }
  }

  async function saveContact() {
    setError(null);
    try {
      await registerCustomerContact({
        customerRef: contactRef.trim(),
        sourceShop: contactShop.trim(),
        email: contactEmail.trim() || undefined,
        phone: contactPhone.trim() || undefined,
      });
      setNotice('联系方式已登记（列表里默认仍是脱敏形态）。');
      setContactRef('');
      setContactEmail('');
      setContactPhone('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '联系方式登记失败');
    }
  }

  const stats = page?.stats;
  const empty = customerEmptyStateText(filtered, (runs?.total ?? 0) > 0);

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
        description="这个列表回答什么、不回答什么 —— 先看清边界再动数据。"
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
                <ShieldAlert className="h-4 w-4" />
                联系方式口径
              </div>
              <p className="text-muted-foreground text-copy-12 mt-1">
                {capabilities.maskingPolicy}
              </p>
            </div>
            <p className="text-muted-foreground text-copy-12">{capabilities.highlight}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 分层统计 ---------------- */}
      <SectionCard
        title="分层统计"
        description="🔴 这些数字来自独立查询，不受分页影响：翻到第 3 页看到的分布与第 1 页完全一样。"
      >
        {stats ? (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-3">
              <StatCard
                label="客户数"
                value={stats.totalCustomers}
                icon={<Users className="h-4 w-4" />}
              />
              <StatCard label="订单数" value={stats.totalOrders} />
              <StatCard
                label="退款单数"
                value={stats.totalRefundCases}
                polarity="negative"
                icon={<AlertTriangle className="h-4 w-4" />}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">价值分层</div>
                {stats.byValueTier.map((bucket) => (
                  <div key={bucket.valueTier} className="text-copy-13">
                    {bucket.valueTierLabel}：{bucket.customerCount} 位 / {bucket.orderCount} 单
                  </div>
                ))}
              </div>
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">来源店铺</div>
                {stats.bySourceShop.map((bucket) => (
                  <div key={bucket.sourceShop} className="text-copy-13">
                    {bucket.sourceShop}：{bucket.customerCount} 位 / {bucket.orderCount} 单
                  </div>
                ))}
              </div>
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">风险标记</div>
                {stats.byRiskFlag.map((bucket) => (
                  <div key={bucket.riskFlag} className="text-copy-13">
                    {bucket.riskFlagLabel}：{bucket.customerCount} 位
                  </div>
                ))}
              </div>
            </div>
            <div className="text-muted-foreground text-copy-12">
              累计消费（按币种分开）：
              {stats.amountsByCurrency.length === 0
                ? '无'
                : stats.amountsByCurrency
                    .map(
                      (amount) =>
                        `${amount.currency} ${amount.amount}（${amount.customerCount} 位）`,
                    )
                    .join(' / ')}
            </div>
            <p className="text-muted-foreground text-copy-12">{stats.note}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">统计读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 筛选 ---------------- */}
      <SectionCard
        title="筛选"
        description="按来源店铺、标签、价值分层、风险筛选；金额筛选必须给币种（跨币种不能相加）。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="来源店铺">
            <select
              className={control}
              value={sourceShop}
              onChange={(e) => {
                setSourceShop(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.filterOptions.sourceShops ?? []).map((shop) => (
                <option key={shop} value={shop}>
                  {shop}
                </option>
              ))}
            </select>
          </Field>
          <Field label="标签">
            <select
              className={control}
              value={tagCode}
              onChange={(e) => {
                setTagCode(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.filterOptions.tagCodes ?? []).map((tag) => (
                <option key={tag} value={tag}>
                  {tag}
                </option>
              ))}
            </select>
          </Field>
          <Field label="价值分层">
            <select
              className={control}
              value={valueTier}
              onChange={(e) => {
                setValueTier(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.filterOptions.valueTiers ?? []).map((tier) => (
                <option key={tier} value={tier}>
                  {customerValueTierLabel(tier)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="风险">
            <select
              className={control}
              value={riskFlag}
              onChange={(e) => {
                setRiskFlag(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.filterOptions.riskFlags ?? []).map((flag) => (
                <option key={flag} value={flag}>
                  {customerRiskFlagLabel(flag)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="客户标识 / 姓名">
            <input
              className={control}
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageIndex(0);
              }}
              placeholder="包含即可"
            />
          </Field>
          <Field label="最少订单数">
            <input
              className={control}
              value={minOrderCount}
              inputMode="numeric"
              onChange={(e) => {
                setMinOrderCount(e.target.value);
                setPageIndex(0);
              }}
            />
          </Field>
          <Field label="累计消费 ≥" hint="必须同时给币种">
            <input
              className={control}
              value={minTotalAmount}
              inputMode="decimal"
              onChange={(e) => {
                setMinTotalAmount(e.target.value);
                setPageIndex(0);
              }}
            />
          </Field>
          <Field label="币种">
            <select
              className={control}
              value={currency}
              onChange={(e) => {
                setCurrency(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">不限</option>
              {(capabilities?.filterOptions.currencies ?? []).map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </Field>
          <Field label="排序">
            <select className={control} value={sort} onChange={(e) => setSort(e.target.value)}>
              <option value="ORDER_COUNT_DESC">订单数（多 → 少）</option>
              <option value="ORDER_COUNT_ASC">订单数（少 → 多）</option>
              <option value="REFUND_RATE_DESC">退款率（高 → 低）</option>
              <option value="LAST_ORDER_AT_DESC">最后购买时间（新 → 旧）</option>
              <option value="CONSUMPTION_DESC">累计消费（高 → 低，需币种）</option>
              <option value="CONSUMPTION_ASC">累计消费（低 → 高，需币种）</option>
            </select>
          </Field>
          <Field
            label="显示明文联系方式"
            hint={
              canSeePlainContact
                ? '需要额外的权限码 crm.customers.pii.read；没有它就只给脱敏形态。'
                : '当前角色没有 crm.customers.pii.read，只能看脱敏形态。'
            }
          >
            <label className="text-copy-13 flex items-center gap-2">
              <input
                type="checkbox"
                disabled={!canSeePlainContact}
                checked={revealContact}
                onChange={(e) => {
                  setRevealContact(e.target.checked);
                  setPageIndex(0);
                }}
              />
              <Eye className="h-4 w-4" />
              显示明文
            </label>
          </Field>
          <div className="flex items-end gap-2">
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              重新加载
            </Button>
            <Button
              onClick={() => {
                setSourceShop('');
                setTagCode('');
                setValueTier('');
                setRiskFlag('');
                setKeyword('');
                setMinOrderCount('');
                setMinTotalAmount('');
                setPageIndex(0);
              }}
            >
              清空筛选
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------- 客户列表 ---------------- */}
      <SectionCard
        title="客户列表"
        description="一行 = 一个来源店铺下的一个客户。同一个客户标识在两个来源下是两行（跨店合并由 15.3.1.3 负责）。"
      >
        {loading && !page ? (
          <p className="text-muted-foreground text-copy-13">客户列表读取中…</p>
        ) : page && page.rows.length === 0 ? (
          <EmptyState title={empty.title} description={empty.description} />
        ) : (
          <div className="space-y-3">
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">来源店铺</th>
                    <th className="px-2 py-1 text-left">客户标识</th>
                    <th className="px-2 py-1 text-left">买家名</th>
                    <th className="px-2 py-1 text-left">首末购买</th>
                    <th className="px-2 py-1 text-right">订单数</th>
                    <th className="px-2 py-1 text-left">累计消费 / 客单价</th>
                    <th className="px-2 py-1 text-left">退款率</th>
                    <th className="px-2 py-1 text-left">价值分层</th>
                    <th className="px-2 py-1 text-left">标签</th>
                    <th className="px-2 py-1 text-left">联系方式</th>
                    <th className="px-2 py-1 text-left">风险标记</th>
                  </tr>
                </thead>
                <tbody>
                  {page?.rows.map((row) => (
                    <CustomerRow key={row.id} row={row} />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center justify-between gap-2">
              <div className="text-muted-foreground text-copy-12">
                共 {page?.total ?? 0} 位客户 · 第 {(page?.page ?? 0) + 1} /{' '}
                {Math.max(page?.totalPages ?? 1, 1)} 页
                {page?.dataScope ? ` · 数据范围 ${page.dataScope}` : ''}
              </div>
              <div className="flex items-center gap-2">
                <select
                  className={control}
                  value={String(pageSize)}
                  onChange={(e) => {
                    setPageSize(Number(e.target.value));
                    setPageIndex(0);
                  }}
                >
                  {[10, 20, 50, 100].map((size) => (
                    <option key={size} value={size}>
                      每页 {size}
                    </option>
                  ))}
                </select>
                <Button
                  variant="outline"
                  disabled={(page?.page ?? 0) <= 0}
                  onClick={() => setPageIndex(Math.max((page?.page ?? 0) - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  disabled={(page?.page ?? 0) + 1 >= (page?.totalPages ?? 1)}
                  onClick={() => setPageIndex((page?.page ?? 0) + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
            {page?.dataScopeNote && (
              <p className="text-muted-foreground text-copy-12">{page.dataScopeNote}</p>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 刷新 ---------------- */}
      <SectionCard
        title="刷新客户档案"
        description="客户档案来自上游 9 订单的订单事实（授权 HTTP API 只读）。上游读不到会明确失败，不会伪装成「没有客户」。"
      >
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <Button onClick={() => void runSync()} disabled={!canUpdate || syncing}>
              <RefreshCw className="h-4 w-4" />
              {syncing ? '刷新中…' : '刷新客户档案'}
            </Button>
            {!canUpdate && (
              <span className="text-muted-foreground text-copy-12">
                当前角色没有 crm.customers.update，不能刷新。
              </span>
            )}
          </div>
          {runs && runs.rows.length > 0 && (
            <div className="space-y-1">
              {runs.rows.map((run) => (
                <div key={run.id} className="text-copy-12 text-muted-foreground">
                  {run.runNo} · {run.status} · 客户 {run.customerCount} 位 · 订单 {run.orderCount}{' '}
                  单 · 联系方式补齐 {run.contactResolvedCount}/
                  {run.contactResolvedCount + run.contactUnavailableCount}
                  {run.truncated ? ' · 🔴 扫描被截断' : ''}
                  {run.failureReason ? ` · ${run.failureReason}` : ''}
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 登记联系方式 ---------------- */}
      <SectionCard
        title="登记联系方式"
        description="人工登记 / 修正联系方式。客户必须已经在客户主档里（按来源店铺 + 客户标识定位）。"
      >
        <div className="grid gap-3 md:grid-cols-5">
          <Field label="客户标识">
            <input
              className={control}
              value={contactRef}
              onChange={(e) => setContactRef(e.target.value)}
              placeholder="上游客户标识"
            />
          </Field>
          <Field label="来源店铺">
            <input
              className={control}
              value={contactShop}
              onChange={(e) => setContactShop(e.target.value)}
              placeholder="AMAZON"
            />
          </Field>
          <Field label="邮箱">
            <input
              className={control}
              value={contactEmail}
              onChange={(e) => setContactEmail(e.target.value)}
            />
          </Field>
          <Field label="手机">
            <input
              className={control}
              value={contactPhone}
              onChange={(e) => setContactPhone(e.target.value)}
            />
          </Field>
          <div className="flex items-end">
            <Button
              onClick={() => void saveContact()}
              disabled={!canUpdate || contactRef.trim() === '' || contactShop.trim() === ''}
            >
              登记
            </Button>
          </div>
        </div>
      </SectionCard>
    </div>
  );
}

function CustomerRow({ row }: { row: CrmCustomerListRow }) {
  return (
    <tr className="border-control-border border-t">
      <td className="px-2 py-1.5 font-medium">{row.sourceShop}</td>
      <td className="text-copy-12 px-2 py-1.5 font-mono">{row.customerRef}</td>
      <td className="px-2 py-1.5">{row.customerName || '—'}</td>
      <td className="text-copy-12 px-2 py-1.5">{customerPurchaseWindowText(row)}</td>
      <td className="px-2 py-1.5 text-right">{row.orderCount}</td>
      <td className="text-copy-12 px-2 py-1.5">{customerAmountText(row.amounts)}</td>
      <td className="text-copy-12 px-2 py-1.5">{customerRefundRateText(row.refundRate)}</td>
      <td className="text-copy-12 px-2 py-1.5">
        {row.valueTierLabel || customerValueTierLabel(row.valueTier)}
      </td>
      <td className="px-2 py-1.5">
        <div className="flex flex-wrap gap-1">
          {(row.tags ?? []).map((tag) => (
            <Badge key={tag.tagCode} tone="info">
              {tag.tagName || tag.tagCode}
            </Badge>
          ))}
          {(row.tags ?? []).length === 0 && <span className="text-muted-foreground">—</span>}
        </div>
      </td>
      <td className="text-copy-12 px-2 py-1.5">
        {customerContactText(row)}
        {customerContactIsPlain(row) && (
          <Badge tone="warning" className="ml-1">
            明文
          </Badge>
        )}
        {row.optOutState && row.optOutState !== 'NONE' && (
          <Badge tone="warning" className="ml-1">
            {customerOptOutStateLabel(row.optOutState)}
          </Badge>
        )}
      </td>
      <td className="text-copy-12 px-2 py-1.5">
        {row.riskFlagLabel || customerRiskFlagLabel(row.riskFlag)}
        {row.riskReason && <div className="text-muted-foreground">{row.riskReason}</div>}
      </td>
    </tr>
  );
}
