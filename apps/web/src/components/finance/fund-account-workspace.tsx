'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
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
  FinanceFundAccountBalanceInput,
  FinanceFundAccountCreateInput,
  FinanceFundAccountDetail,
  FinanceFundAccountOptions,
  FinanceFundAccountPage,
  FinanceFundAccountRow,
  FinanceFundAccountUpdateInput,
  FinanceFundCurrencyBalanceSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  activateFundAccount,
  createFundAccount,
  deactivateFundAccount,
  fetchFundAccount,
  fetchFundAccountBalances,
  fetchFundAccountBalanceSummary,
  fetchFundAccountCapabilities,
  fetchFundAccountOptions,
  fetchFundAccounts,
  formatFundAccountMoney,
  formatFundAccountNumber,
  fundAccountStatusLabel,
  fundAccountTypeLabel,
  fundBalanceSourceLabel,
  recordFundAccountBalance,
  updateFundAccount,
} from './fund-account-model';

/**
 * 12.7.1.1 账户管理。
 *
 * <p>蓝图 12-finance.md：「管理资金账户：银行账户、支付平台账户、平台备付金账户，记录币种、账号、余额、开户主体（法人）、账户类型。」输入：「账户定义；余额同步或手工更新；关联法人。」输出：「账户档案 + 余额历史。」加粗句：「<b>账户必须绑定法人</b>，跨法人混用资金账户会导致收入归属错误。」
 *
 * <p><b>页面围绕三块内容组织</b>：
 *
 * <ol>
 *   <li><b>账户档案</b>：列表（真分页 + 真筛选）+ 建账户 + 改档案 + 启用/停用；</li>
 *   <li><b>余额历史</b>：选中某个账户后看它的余额轨迹（改前 → 改后 + 理由 + 来源 + 操作人 + 事实时刻），并可登记一次新余额；</li>
 *   <li><b>按币种分组的余额汇总</b>：USD 一行、EUR 一行，<b>绝不</b>加在一起。</li>
 * </ol>
 *
 * <p><b>页面不做业务算术</b>：合计、占比都由服务端算好，页面只原样展示。
 * 账号掩码也**只有服务端一处实现**，页面不自己算。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有账户」。
 */

/** 余额来源的两个可选项（建档初始只能在建户时给，不在这里登记）。 */
const BALANCE_SOURCES = ['SYNC', 'MANUAL'] as const;

/** 事实时刻输入框用的格式（UTC，页面明确标注，避免本地时区歧义）。 */
function utcNowLocalInput(): string {
  return new Date().toISOString().slice(0, 16);
}

/** datetime-local 的值（UTC）⇒ 带 Z 的 ISO 时刻。 */
function localInputToUtcIso(value: string): string {
  return value ? `${value}:00Z` : new Date().toISOString();
}

export function FundAccountWorkspace() {
  const [options, setOptions] = useState<FinanceFundAccountOptions | null>(null);
  const [page, setPage] = useState<FinanceFundAccountPage | null>(null);
  const [summary, setSummary] = useState<FinanceFundCurrencyBalanceSummary | null>(null);
  const [detail, setDetail] = useState<FinanceFundAccountDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [accountType, setAccountType] = useState('');
  const [currency, setCurrency] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageNo, setPageNo] = useState(0);

  // 建账户草稿
  const [draftNo, setDraftNo] = useState('');
  const [draftName, setDraftName] = useState('');
  const [draftType, setDraftType] = useState('');
  const [draftCurrency, setDraftCurrency] = useState('');
  const [draftLegalEntityId, setDraftLegalEntityId] = useState('');
  const [draftBankName, setDraftBankName] = useState('');
  const [draftAccountNumber, setDraftAccountNumber] = useState('');
  const [draftHolder, setDraftHolder] = useState('');
  const [draftNote, setDraftNote] = useState('');
  const [draftInitialBalance, setDraftInitialBalance] = useState('');

  // 余额登记草稿
  const [balanceAmount, setBalanceAmount] = useState('');
  const [balanceSource, setBalanceSource] = useState<(typeof BALANCE_SOURCES)[number]>('MANUAL');
  const [balanceReason, setBalanceReason] = useState('');
  const [balanceSourceSystem, setBalanceSourceSystem] = useState('');
  const [balanceSourceReference, setBalanceSourceReference] = useState('');
  const [balanceRecordedAt, setBalanceRecordedAt] = useState(utcNowLocalInput());

  // 账户类型是**受控词表**：候选取自服务端，页面不自己写死一份。
  const effectiveType = draftType || options?.accountTypes[0]?.code || '';

  const params = useMemo(
    () => ({
      ...(accountType ? { accountType } : {}),
      ...(currency ? { currency } : {}),
      ...(legalEntityId ? { legalEntityId } : {}),
      ...(status ? { status } : {}),
      ...(keyword ? { keyword } : {}),
      sort: 'ACCOUNT_NO',
      direction: 'ASC',
    }),
    [accountType, currency, legalEntityId, status, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [o, p, s] = await Promise.all([
        fetchFundAccountOptions(),
        fetchFundAccounts(params, pageNo, 20),
        fetchFundAccountBalanceSummary(),
      ]);
      setOptions(o);
      setPage(p);
      setSummary(s);
    } catch (err) {
      setError(err instanceof Error ? err.message : '账户管理加载失败');
    } finally {
      setLoading(false);
    }
  }, [params, pageNo]);

  useEffect(() => {
    void load();
  }, [load]);

  // 选中一个账户 → 拉它的档案 + 余额历史。
  const openDetail = useCallback(async (id: string) => {
    setActionError('');
    try {
      const [d, b] = await Promise.all([
        fetchFundAccount(id),
        fetchFundAccountBalances(id, 0, 50),
      ]);
      setDetail({ ...d, recentBalances: b.items, balanceTotal: b.total });
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '账户详情加载失败');
    }
  }, []);

  async function submitAccount() {
    setActionError('');
    setNotice('');
    try {
      const input: FinanceFundAccountCreateInput = {
        accountNo: draftNo.trim(),
        accountName: draftName.trim(),
        accountType: effectiveType as FinanceFundAccountCreateInput['accountType'],
        currency: draftCurrency.trim().toUpperCase(),
        legalEntityId: draftLegalEntityId,
        bankName: draftBankName.trim(),
        accountNumber: draftAccountNumber.trim(),
        accountHolder: draftHolder.trim(),
        note: draftNote.trim(),
        initialBalance: Number(draftInitialBalance || '0'),
      };
      const created = await createFundAccount(input);
      setNotice(`账户「${created.accountName}」已建档（${created.accountNo}）`);
      setDraftNo('');
      setDraftName('');
      setDraftCurrency('');
      setDraftBankName('');
      setDraftAccountNumber('');
      setDraftHolder('');
      setDraftNote('');
      setDraftInitialBalance('');
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '建账户失败');
    }
  }

  async function submitUpdate() {
    if (!detail) return;
    setActionError('');
    setNotice('');
    try {
      const input: FinanceFundAccountUpdateInput = {
        accountName: detail.account.accountName,
        accountType: detail.account.accountType as FinanceFundAccountUpdateInput['accountType'],
        legalEntityId: detail.account.legalEntityId,
        bankName: detail.account.bankName,
        accountNumber: '',
        accountHolder: detail.account.accountHolder,
        note: detail.account.note,
      };
      const saved = await updateFundAccount(detail.account.id, input);
      setNotice(`账户「${saved.accountName}」档案已更新`);
      await openDetail(saved.id);
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '改账户档案失败');
    }
  }

  async function submitBalance() {
    if (!detail) return;
    setActionError('');
    setNotice('');
    try {
      const input: FinanceFundAccountBalanceInput = {
        balanceAmount: Number(balanceAmount),
        changeSource: balanceSource,
        reason: balanceSource === 'MANUAL' ? balanceReason.trim() : '',
        sourceSystem: balanceSource === 'SYNC' ? balanceSourceSystem.trim() : '',
        sourceReference: balanceSource === 'SYNC' ? balanceSourceReference.trim() : '',
        recordedAt: localInputToUtcIso(balanceRecordedAt),
      };
      const row = await recordFundAccountBalance(detail.account.id, input);
      setNotice(
        `已追加一条余额记录：${formatFundAccountMoney(row.previousAmount, row.currency)} → ${formatFundAccountMoney(row.balanceAmount, row.currency)}`,
      );
      setBalanceAmount('');
      setBalanceReason('');
      setBalanceSourceSystem('');
      setBalanceSourceReference('');
      setBalanceRecordedAt(utcNowLocalInput());
      await openDetail(detail.account.id);
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '登记余额失败');
    }
  }

  async function toggleStatus(account: FinanceFundAccountRow) {
    setActionError('');
    setNotice('');
    try {
      const saved =
        account.status === 'ACTIVE'
          ? await deactivateFundAccount(account.id)
          : await activateFundAccount(account.id);
      setNotice(`账户「${saved.accountName}」已${fundAccountStatusLabel(saved.status)}`);
      if (detail && detail.account.id === account.id) await openDetail(account.id);
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '状态变更失败');
    }
  }

  const unavailable = (options?.legalEntitiesAvailable ?? true)
    ? []
    : [
        {
          module: '17 法人',
          name: '法人档案',
          ownerModule: 'identity',
          detail: options?.legalEntityNote || '法人清单读不到',
        },
      ];
  const total = page?.total ?? 0;
  const activeCount = page?.items.filter((a) => a.status === 'ACTIVE').length ?? 0;

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="账户管理"
        description="银行账户 / 支付平台账户 / 平台备付金账户：账户档案 + 余额历史（追加式，币种分开、法人绑定）"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
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
          {unavailable.length > 0 && (
            <Notice tone="danger" title="法人档案读不到，账户无法建档">
              {unavailable.map((c) => `${c.module} ${c.name}：${c.detail}`).join('；')}
              <br />
              账户<b>必须绑定法人</b> —— 法人清单读不到时不会退化成「随便写个 id」。
            </Notice>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="账户总数" value={total} unit="个" />
            <StatCard label="本页启用" value={activeCount} unit="个" />
            <StatCard label="涉及币种" value={options?.currencies.length ?? 0} unit="种" />
            <StatCard
              label="法人候选"
              value={options?.legalEntities.length ?? 0}
              unit="个"
              comparisonLabel={options?.legalEntitiesAvailable ? '实时读 identity' : '读不到'}
            />
          </div>

          {/* ---------- 筛选 ---------- */}
          <SectionCard
            title="筛选"
            description="账户类型是受控词表；币种候选取自本租户已建账户上真实出现过的币种。"
          >
            <div className="grid gap-3 md:grid-cols-5">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={accountType}
                onChange={(e) => setAccountType(e.target.value)}
                aria-label="账户类型"
              >
                <option value="">全部类型</option>
                {(options?.accountTypes ?? []).map((t) => (
                  <option key={t.code} value={t.code}>
                    {t.label}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                aria-label="币种"
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={legalEntityId}
                onChange={(e) => setLegalEntityId(e.target.value)}
                aria-label="开户法人"
              >
                <option value="">全部法人</option>
                {(options?.legalEntities ?? []).map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.code} {e.legalName}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={status}
                onChange={(e) => setStatus(e.target.value)}
                aria-label="账户状态"
              >
                <option value="">全部状态</option>
                <option value="ACTIVE">启用</option>
                <option value="INACTIVE">停用</option>
              </select>
              <Input
                value={keyword}
                placeholder="账户编号 / 名称 / 开户行"
                aria-label="关键字"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageNo(0);
                }}
              />
            </div>
          </SectionCard>

          {/* ---------- 账户档案 ---------- */}
          <SectionCard
            title="账户档案"
            description="一个账户一个币种、一个开户法人。账号只存掩码 + 后四位，原始账号不落库。"
            extra={
              <div className="text-muted-foreground text-copy-13">
                第 {pageNo + 1} 页 / 共 {total} 个
              </div>
            }
          >
            {!page || page.items.length === 0 ? (
              <EmptyState
                title={total === 0 ? '还没有资金账户' : '没有符合筛选条件的账户'}
                description={
                  total === 0
                    ? '用下面的「建账户」登记第一个银行账户 / 支付平台账户 / 平台备付金账户。'
                    : '换一个类型、币种或法人再试。'
                }
              />
            ) : (
              <div className="space-y-3">
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">账户编号</th>
                      <th className="py-2">名称</th>
                      <th className="py-2">类型</th>
                      <th className="py-2">币种</th>
                      <th className="py-2">开户法人</th>
                      <th className="py-2">账号（脱敏）</th>
                      <th className="py-2">当前余额</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((a) => (
                      <tr key={a.id} className="border-border border-t">
                        <td className="py-2 font-medium">{a.accountNo}</td>
                        <td className="py-2">{a.accountName}</td>
                        <td className="py-2">
                          {fundAccountTypeLabel(a.accountType, a.accountTypeLabel)}
                        </td>
                        <td className="py-2">{a.currency}</td>
                        <td className="py-2">
                          {a.legalEntityCode} {a.legalEntityName}
                        </td>
                        <td className="py-2">
                          {formatFundAccountNumber(a.accountNumberMasked, a.accountNumberTail)}
                        </td>
                        <td className="py-2">
                          {formatFundAccountMoney(a.currentBalance, a.currency)}
                        </td>
                        <td className="py-2">
                          <Badge tone={a.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {fundAccountStatusLabel(a.status, a.statusLabel)}
                          </Badge>
                        </td>
                        <td className="py-2">
                          <div className="flex gap-2">
                            <Button
                              variant="secondary"
                              size="sm"
                              onClick={() => void openDetail(a.id)}
                            >
                              详情
                            </Button>
                            <Button
                              variant="secondary"
                              size="sm"
                              onClick={() => void toggleStatus(a)}
                            >
                              {a.status === 'ACTIVE' ? '停用' : '启用'}
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="flex gap-2">
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={pageNo === 0}
                    onClick={() => setPageNo((n) => Math.max(0, n - 1))}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={(page?.items.length ?? 0) < 20}
                    onClick={() => setPageNo((n) => n + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            )}
          </SectionCard>

          {/* ---------- 建账户 ---------- */}
          <SectionCard
            title="建账户"
            description="开户主体（法人）必填：服务端会实时校验它确实存在，未登记的法人会被拒。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="fa-no" label="账户编号" hint="留空由系统生成（FA-xxxxxxxxxxxx）">
                <Input
                  value={draftNo}
                  placeholder="BANK-001"
                  onChange={(e) => setDraftNo(e.target.value.toUpperCase())}
                />
              </FormField>
              <FormField id="fa-name" label="账户名称" required>
                <Input
                  value={draftName}
                  placeholder="招商银行美元户"
                  onChange={(e) => setDraftName(e.target.value)}
                />
              </FormField>
              <FormField id="fa-type" label="账户类型" required>
                <select
                  className="border-border bg-background h-9 w-full rounded-md border px-3 text-sm"
                  value={effectiveType}
                  onChange={(e) => setDraftType(e.target.value)}
                  aria-label="账户类型"
                >
                  {(options?.accountTypes ?? []).map((t) => (
                    <option key={t.code} value={t.code}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="fa-currency" label="币种" required hint="单账户单币种；建档后不可改">
                <Input
                  value={draftCurrency}
                  maxLength={3}
                  placeholder="USD"
                  onChange={(e) => setDraftCurrency(e.target.value.toUpperCase())}
                />
              </FormField>
              <FormField id="fa-entity" label="开户法人" required>
                <select
                  className="border-border bg-background h-9 w-full rounded-md border px-3 text-sm"
                  value={draftLegalEntityId}
                  onChange={(e) => setDraftLegalEntityId(e.target.value)}
                  aria-label="开户法人"
                >
                  <option value="">请选择法人</option>
                  {(options?.legalEntities ?? []).map((e) => (
                    <option key={e.id} value={e.id}>
                      {e.code} {e.legalName}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="fa-bank" label="开户行" hint="只有银行账户需要填">
                <Input
                  value={draftBankName}
                  placeholder="招商银行"
                  onChange={(e) => setDraftBankName(e.target.value)}
                />
              </FormField>
              <FormField
                id="fa-number"
                label="账号"
                hint="敏感信息：只存掩码 + 后四位，原始账号不落库；至少 8 位"
              >
                <Input
                  value={draftAccountNumber}
                  onChange={(e) => setDraftAccountNumber(e.target.value)}
                />
              </FormField>
              <FormField id="fa-holder" label="户名">
                <Input value={draftHolder} onChange={(e) => setDraftHolder(e.target.value)} />
              </FormField>
              <FormField id="fa-initial" label="建档初始余额" hint="会写成第一条余额历史（建档初始）">
                <Input
                  value={draftInitialBalance}
                  inputMode="decimal"
                  placeholder="0"
                  onChange={(e) => setDraftInitialBalance(e.target.value)}
                />
              </FormField>
              <FormField id="fa-note" label="备注">
                <Input value={draftNote} onChange={(e) => setDraftNote(e.target.value)} />
              </FormField>
            </div>
            <div className="mt-3">
              <Button
                onClick={() => void submitAccount()}
                disabled={!draftName || !draftCurrency || !draftLegalEntityId}
              >
                建档
              </Button>
            </div>
          </SectionCard>

          {/* ---------- 余额汇总（按币种分组） ---------- */}
          <SectionCard
            title="余额汇总（按币种分组）"
            description="USD 一行、EUR 一行 —— 跨币种绝不相加；本模块也不做本币折算。"
          >
            {!summary || summary.totals.length === 0 ? (
              <EmptyState title="还没有余额" description="建账户并登记余额之后，这里才会有数。" />
            ) : (
              <div className="space-y-3">
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">币种</th>
                      <th className="py-2">账户数</th>
                      <th className="py-2">其中启用</th>
                      <th className="py-2">余额合计</th>
                      <th className="py-2">按法人</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.totals.map((t) => (
                      <tr key={t.currency} className="border-border border-t">
                        <td className="py-2 font-medium">{t.currency}</td>
                        <td className="py-2">{t.accountCount}</td>
                        <td className="py-2">{t.activeAccountCount}</td>
                        <td className="py-2">{formatFundAccountMoney(t.totalBalance, t.currency)}</td>
                        <td className="py-2">
                          {t.byLegalEntity.map((e) => (
                            <div key={e.legalEntityId}>
                              {e.legalEntityCode} {e.legalEntityName}：
                              {formatFundAccountMoney(e.totalBalance, t.currency)}
                            </div>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Notice tone="warning" title="为什么没有「全部账户加起来」这一行">
                  {summary.note}（折算归属模块 {summary.conversionOwnerModule}）
                </Notice>
              </div>
            )}
          </SectionCard>

          {/* ---------- 账户详情 + 余额历史 ---------- */}
          {detail && (
            <SectionCard
              title={`账户详情：${detail.account.accountName}`}
              description={`${detail.account.accountNo} · ${detail.account.currency} · 开户法人 ${detail.account.legalEntityCode} ${detail.account.legalEntityName}`}
              extra={
                <Button variant="secondary" size="sm" onClick={() => setDetail(null)}>
                  关闭
                </Button>
              }
            >
              <div className="space-y-4">
                <div className="text-copy-13 grid gap-2 md:grid-cols-4">
                  <div>
                    <div className="text-muted-foreground">账户类型</div>
                    <div>
                      {fundAccountTypeLabel(detail.account.accountType, detail.account.accountTypeLabel)}
                    </div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">账号（脱敏）</div>
                    <div>
                      {formatFundAccountNumber(
                        detail.account.accountNumberMasked,
                        detail.account.accountNumberTail,
                      )}
                    </div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">当前余额</div>
                    <div>
                      {formatFundAccountMoney(
                        detail.account.currentBalance,
                        detail.account.currency,
                      )}
                    </div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">状态</div>
                    <div>
                      {fundAccountStatusLabel(detail.account.status, detail.account.statusLabel)}
                    </div>
                  </div>
                </div>

                <Notice tone="info" title="余额历史是追加式的（append-only）">
                  每次同步 / 手工更新都<b>追加</b>一条记录，历史记录不可改写；
                  「当前余额」= 最新一条历史推导出来的值。手工更新必须写理由，
                  同步必须带来源系统与来源流水号（重复同步不重复入账）。
                </Notice>

                <div className="grid gap-3 md:grid-cols-4">
                  <FormField id="fb-amount" label="余额" required>
                    <Input
                      value={balanceAmount}
                      inputMode="decimal"
                      placeholder="0.00"
                      onChange={(e) => setBalanceAmount(e.target.value)}
                    />
                  </FormField>
                  <FormField id="fb-source" label="来源" required>
                    <select
                      className="border-border bg-background h-9 w-full rounded-md border px-3 text-sm"
                      value={balanceSource}
                      onChange={(e) =>
                        setBalanceSource(e.target.value as (typeof BALANCE_SOURCES)[number])
                      }
                      aria-label="余额来源"
                    >
                      {BALANCE_SOURCES.map((s) => (
                        <option key={s} value={s}>
                          {fundBalanceSourceLabel(s)}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField
                    id="fb-reason"
                    label="理由"
                    hint={balanceSource === 'MANUAL' ? '手工更新必填' : '手工更新才需要'}
                    required={balanceSource === 'MANUAL'}
                  >
                    <Input
                      value={balanceReason}
                      onChange={(e) => setBalanceReason(e.target.value)}
                    />
                  </FormField>
                  <FormField id="fb-at" label="事实时刻（UTC）" hint="补录更早的余额不会倒推当前余额">
                    <Input
                      type="datetime-local"
                      value={balanceRecordedAt}
                      onChange={(e) => setBalanceRecordedAt(e.target.value)}
                    />
                  </FormField>
                  {balanceSource === 'SYNC' && (
                    <>
                      <FormField id="fb-system" label="来源系统" required>
                        <Input
                          value={balanceSourceSystem}
                          placeholder="BANK_FEED"
                          onChange={(e) => setBalanceSourceSystem(e.target.value)}
                        />
                      </FormField>
                      <FormField id="fb-ref" label="来源流水号" required>
                        <Input
                          value={balanceSourceReference}
                          placeholder="FEED-2026-10-24-001"
                          onChange={(e) => setBalanceSourceReference(e.target.value)}
                        />
                      </FormField>
                    </>
                  )}
                </div>
                <div className="flex gap-2">
                  <Button onClick={() => void submitBalance()} disabled={!balanceAmount}>
                    登记余额
                  </Button>
                  <Button variant="secondary" onClick={() => void submitUpdate()}>
                    保存档案
                  </Button>
                </div>

                <div>
                  <h4 className="text-h-16 mb-2">
                    余额历史（{detail.balanceTotal} 条 · 最新的在前）
                  </h4>
                  {detail.recentBalances.length === 0 ? (
                    <EmptyState title="还没有余额记录" description="建档那一条也算在里面。" />
                  ) : (
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">事实时刻</th>
                          <th className="py-2">来源</th>
                          <th className="py-2">改前 → 改后</th>
                          <th className="py-2">理由 / 来源</th>
                          <th className="py-2">操作人</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.recentBalances.map((b) => (
                          <tr key={b.id} className="border-border border-t">
                            <td className="py-2">{b.recordedAt}</td>
                            <td className="py-2">
                              {fundBalanceSourceLabel(b.changeSource, b.changeSourceLabel)}
                            </td>
                            <td className="py-2">
                              {formatFundAccountMoney(b.previousAmount, b.currency)} →{' '}
                              {formatFundAccountMoney(b.balanceAmount, b.currency)}
                            </td>
                            <td className="py-2">
                              {b.reason || '—'}
                              {b.sourceSystem && (
                                <div className="text-muted-foreground">
                                  {b.sourceSystem} / {b.sourceReference}
                                </div>
                              )}
                            </td>
                            <td className="py-2">{b.operatorUserId}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
