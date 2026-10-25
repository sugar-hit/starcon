'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  FileDown,
  FileSpreadsheet,
  Landmark,
  Loader2,
  RefreshCw,
  Scale,
  ShieldCheck,
  TriangleAlert,
  Unplug,
} from 'lucide-react';
import {
  ACCOUNT_STATUS_OPTIONS,
  DIRECTION_OPTIONS,
  FILE_FORMAT_OPTIONS,
  MASTER_TYPE_OPTIONS,
  VENDOR_OPTIONS,
  canPush,
  capabilityTone,
  computeReconcile,
  createReconcileTask,
  deleteMasterMap,
  deleteSubjectMap,
  diffExternalCodeLabel,
  disableAccount,
  exportSummary,
  exportVoucherFile,
  fetchAccounts,
  fetchCapabilities,
  fetchCurrencies,
  fetchMasterMaps,
  fetchOverview,
  fetchPeriods,
  fetchReconcileDetail,
  fetchReconcileTasks,
  fetchSubjectMaps,
  fetchVoucherFile,
  fetchVoucherFiles,
  formatMoney,
  importBalances,
  pushVouchers,
  pushCapability,
  reconcileReasonLabel,
  reconcileReasonTone,
  reconcileStatusTone,
  saveAccount,
  saveMasterMap,
  saveSubjectMap,
  shortSha,
  sortDiffs,
  unmappedTone,
  unavailableHint,
  accountStatusTone,
  directionLabel,
  downloadName,
  type Tone,
} from './finance-software-model';
import type {
  FinanceSoftwareAccountRow,
  FinanceSoftwareCapabilities,
  FinanceSoftwareMasterMapRow,
  FinanceSoftwareOverview,
  FinanceSoftwarePeriodOption,
  FinanceSoftwareReconcileDetail,
  FinanceSoftwareReconcileTaskRow,
  FinanceSoftwareSubjectMapRow,
  FinanceSoftwareVoucherFileDetail,
  FinanceSoftwareVoucherFileRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.2.1.1「财务软件」工作区（卷 18 前端入口 `/integrations/connectors/finance-software`）。
 *
 * 蓝图：「**财务软件**：对接第三方财务软件（金蝶、用友、SAP、Odoo）：凭证推送、科目映射、
 * 主数据同步、余额核对；导出标准凭证文件（Excel/XML）。输入：凭证数据；科目映射配置；
 * 对账需求。输出：推送结果；凭证文件；核对差异。关系：上游 = 12.5.2.1 凭证；下游 = 外部财务软件。
 * **业财一体化的常见做法是本系统管业务账、外部软件管法定账**，两边通过凭证文件与科目映射衔接。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **未接入就明说**。本仓目前没有任何真实的财务软件适配器，所以「直连推送」这一格
 *    `available=false`：页面**不提供**这个按钮 —— 一个点了必然 409 的按钮不如没有，
 *    它会让运营以为「点一下就能推」。缺的那一段按能力声明原样显示（含归谁）。
 * 2. **不做本地兜底生成**。页面不会在本地拼一份凭证文件来「假装导出了」。
 *    导出就是调后端那一个端点，后端说 409 就是 409。
 * 3. **导出的文件要能原样取回**。列表里点开一条导出记录，能看到当初那份文件的
 *    **真内容**与 **sha256** —— 「导出过」这三个字没有用，能对上那一份才有用。
 * 4. **未映射行数必须显示**。那是「这份文件不完整」的唯一信号。
 * 5. **本系统管业务账、外部软件管法定账**。导出的文件是**法定账的输入**，
 *    页面不把它说成「已入账」。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「还没登记过」与「筛选无结果」。
 */

type Tab = 'accounts' | 'subjects' | 'masters' | 'files' | 'reconcile' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

interface AccountForm {
  name: string;
  vendor: string;
  bookCode: string;
  companyName: string;
  currency: string;
  fiscalStartMonth: string;
}

const EMPTY_ACCOUNT: AccountForm = {
  name: '',
  vendor: 'KINGDEE',
  bookCode: '',
  companyName: '',
  currency: 'USD',
  fiscalStartMonth: '1',
};

interface SubjectMapForm {
  accountId: string;
  localSubjectCode: string;
  localSubjectName: string;
  externalSubjectCode: string;
  externalSubjectName: string;
  taxCode: string;
}

const EMPTY_SUBJECT_MAP: SubjectMapForm = {
  accountId: '',
  localSubjectCode: '',
  localSubjectName: '',
  externalSubjectCode: '',
  externalSubjectName: '',
  taxCode: '',
};

interface MasterMapForm {
  accountId: string;
  masterType: string;
  localCode: string;
  localName: string;
  externalCode: string;
  externalName: string;
}

const EMPTY_MASTER_MAP: MasterMapForm = {
  accountId: '',
  masterType: 'CUSTOMER',
  localCode: '',
  localName: '',
  externalCode: '',
  externalName: '',
};

interface ExportForm {
  accountId: string;
  format: string;
  dateFrom: string;
  dateTo: string;
}

const EMPTY_EXPORT: ExportForm = {
  accountId: '',
  format: 'CSV',
  dateFrom: '',
  dateTo: '',
};

interface ReconcileForm {
  accountId: string;
  periodFromId: string;
  periodToId: string;
  currency: string;
}

const EMPTY_RECONCILE: ReconcileForm = {
  accountId: '',
  periodFromId: '',
  periodToId: '',
  currency: 'USD',
};

export function FinanceSoftwareWorkspace() {
  const [tab, setTab] = useState<Tab>('accounts');

  const [capabilities, setCapabilities] = useState<FinanceSoftwareCapabilities | null>(null);
  const [overview, setOverview] = useState<FinanceSoftwareOverview | null>(null);
  const [accounts, setAccounts] = useState<FinanceSoftwareAccountRow[]>([]);
  const [subjectMaps, setSubjectMaps] = useState<FinanceSoftwareSubjectMapRow[]>([]);
  const [masterMaps, setMasterMaps] = useState<FinanceSoftwareMasterMapRow[]>([]);
  const [voucherFiles, setVoucherFiles] = useState<FinanceSoftwareVoucherFileRow[]>([]);
  const [fileDetail, setFileDetail] = useState<FinanceSoftwareVoucherFileDetail | null>(null);
  const [reconcileTasks, setReconcileTasks] = useState<FinanceSoftwareReconcileTaskRow[]>([]);
  const [reconcileDetail, setReconcileDetail] = useState<FinanceSoftwareReconcileDetail | null>(null);
  const [periods, setPeriods] = useState<FinanceSoftwarePeriodOption[]>([]);
  const [currencies, setCurrencies] = useState<string[]>([]);

  const [accountFilter, setAccountFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  const [accountForm, setAccountForm] = useState<AccountForm>(EMPTY_ACCOUNT);
  const [subjectForm, setSubjectForm] = useState<SubjectMapForm>(EMPTY_SUBJECT_MAP);
  const [masterForm, setMasterForm] = useState<MasterMapForm>(EMPTY_MASTER_MAP);
  const [exportForm, setExportForm] = useState<ExportForm>(EMPTY_EXPORT);
  const [reconcileForm, setReconcileForm] = useState<ReconcileForm>(EMPTY_RECONCILE);
  const [balanceText, setBalanceText] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    const message = e instanceof Error ? e.message : '请求失败，请稍后重试';
    setError(message);
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, accs, periods_, ccy] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchAccounts({}),
        fetchPeriods(),
        fetchCurrencies(),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setAccounts(accs);
      setPeriods(periods_);
      setCurrencies(ccy);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // 映射 / 凭证文件 / 核对任务：按需加载，切换标签页时再取。
  useEffect(() => {
    if (tab === 'subjects' && subjectMaps.length === 0) {
      fetchSubjectMaps({ accountId: accountFilter, keyword })
        .then(setSubjectMaps)
        .catch(fail);
    }
    if (tab === 'masters' && masterMaps.length === 0) {
      fetchMasterMaps({ accountId: accountFilter })
        .then(setMasterMaps)
        .catch(fail);
    }
    if (tab === 'files' && voucherFiles.length === 0) {
      fetchVoucherFiles({ accountId: accountFilter })
        .then(setVoucherFiles)
        .catch(fail);
    }
    if (tab === 'reconcile' && reconcileTasks.length === 0) {
      fetchReconcileTasks({ accountId: accountFilter })
        .then(setReconcileTasks)
        .catch(fail);
    }
  }, [tab, accountFilter, keyword, subjectMaps.length, masterMaps.length, voucherFiles.length, reconcileTasks.length]);

  const run = async (work: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
      setNotice(ok);
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const reloadMappings = async () => {
    setSubjectMaps(await fetchSubjectMaps({ accountId: accountFilter, keyword }));
    setMasterMaps(await fetchMasterMaps({ accountId: accountFilter }));
    setVoucherFiles(await fetchVoucherFiles({ accountId: accountFilter }));
    setReconcileTasks(await fetchReconcileTasks({ accountId: accountFilter }));
  };

  // ------------------------------------------------------------ 科目映射

  const submitSubjectMap = async () => {
    await run(async () => {
      await saveSubjectMap({
        accountId: subjectForm.accountId,
        localSubjectCode: subjectForm.localSubjectCode,
        localSubjectName: subjectForm.localSubjectName,
        externalSubjectCode: subjectForm.externalSubjectCode,
        externalSubjectName: subjectForm.externalSubjectName,
        taxCode: subjectForm.taxCode,
        direction: '',
        enabled: true,
        remark: '',
      });
      await reloadMappings();
    }, '科目映射已保存。');
  };

  // ------------------------------------------------------------ 主数据映射

  const submitMasterMap = async () => {
    await run(async () => {
      await saveMasterMap({
        accountId: masterForm.accountId,
        masterType: masterForm.masterType,
        localCode: masterForm.localCode,
        localName: masterForm.localName,
        externalCode: masterForm.externalCode,
        externalName: masterForm.externalName,
        enabled: true,
        remark: '',
      });
      await reloadMappings();
    }, '主数据映射已保存。');
  };

  // ------------------------------------------------------------ 凭证文件导出

  const submitExport = async () => {
    await run(async () => {
      await exportVoucherFile({
        accountId: exportForm.accountId,
        format: exportForm.format,
        dateFrom: exportForm.dateFrom,
        dateTo: exportForm.dateTo,
        remark: '',
      });
      await reloadMappings();
    }, '凭证文件已导出。');
  };

  const openFile = async (id: string) => {
    setBusy(true);
    setError('');
    try {
      setFileDetail(await fetchVoucherFile(id));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const downloadFile = (detail: FinanceSoftwareVoucherFileDetail) => {
    const blob = new Blob([detail.content], { type: detail.contentType });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = downloadName(detail.file);
    anchor.click();
    URL.revokeObjectURL(url);
  };

  // ------------------------------------------------------------ 余额核对

  const submitReconcileTask = async () => {
    await run(async () => {
      await createReconcileTask({
        accountId: reconcileForm.accountId,
        periodFromId: reconcileForm.periodFromId,
        periodToId: reconcileForm.periodToId,
        currency: reconcileForm.currency,
        remark: '',
      });
      await reloadMappings();
    }, '核对任务已建立。');
  };

  /**
   * 解析导入的外部余额。
   *
   * <p>格式：每行 `外部科目编码,币种,方向(D/C),金额`。
   * 🔴 方向**必须**在行里 —— 方向缺了，同一个余额会差出一倍。
   */
  const parseBalances = (text: string) => {
    const balances = [];
    for (const raw of text.split('\n')) {
      const line = raw.trim();
      if (!line) continue;
      const [code, ccy, direction, amount] = line.split(',').map((p) => p.trim());
      if (!code || !ccy || !direction || amount === undefined) {
        throw new Error('余额行格式应为「外部科目编码,币种,D/C,金额」');
      }
      const dir = direction.toUpperCase();
      if (dir !== 'DEBIT' && dir !== 'CREDIT') {
        throw new Error(`余额方向只能是 D 或 C（借方/贷方）：${line}`);
      }
      const value = Number(amount);
      if (!Number.isFinite(value)) throw new Error(`余额金额不是数字：${line}`);
      balances.push({
        externalSubjectCode: code,
        externalSubjectName: code,
        currency: ccy.toUpperCase(),
        direction: dir,
        externalBalance: value,
      });
    }
    if (balances.length === 0) throw new Error('还没有导入任何外部余额');
    return balances;
  };

  const submitBalances = async (taskId: string) => {
    await run(async () => {
      const balances = parseBalances(balanceText);
      await importBalances(taskId, { balances });
      setBalanceText('');
    }, '外部余额已导入。');
  };

  const computeTask = async (taskId: string) => {
    await run(async () => {
      await computeReconcile(taskId);
      setReconcileDetail(await fetchReconcileDetail(taskId));
      await reloadMappings();
    }, '核对差异已算出。');
  };

  const openTask = async (taskId: string) => {
    setBusy(true);
    setError('');
    try {
      setReconcileDetail(await fetchReconcileDetail(taskId));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  // ------------------------------------------------------------ 直连推送

  const push = async (accountId: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await pushVouchers({ accountId, dateFrom: '', dateTo: '' });
      setNotice('凭证已推送给外部财务软件。');
    } catch (e) {
      // 🔴 适配器未接入时后端如实返回 409 —— 这里**原样显示**它的说明，
      //    不做任何本地兜底（那等于伪造一条「已经推过去了」的凭证）。
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const pushCap = useMemo(() => pushCapability(capabilities), [capabilities]);
  const pushAvailable = useMemo(() => canPush(capabilities), [capabilities]);
  const sortedDiffs = useMemo(() => sortDiffs(reconcileDetail?.diffs ?? []), [reconcileDetail]);
  const hasAccountFilter = Boolean(accountFilter || keyword);

  return (
    <div className="space-y-4">
      <PageHeader
        title="财务软件"
        description="把本系统的凭证交给外部财务软件（金蝶 / 用友 / SAP / Odoo）：科目映射、主数据映射、标准凭证文件导出、余额核对。直连推送尚未接入。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="text-destructive flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {notice && (
        <div className="text-success flex items-start gap-2 rounded-md border p-3 text-sm">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="连接档案" value={overview.accounts} unit="个" icon={<Landmark className="h-4 w-4" />} />
          <StatCard label="科目映射" value={overview.subjectMaps} unit="条" />
          <StatCard label="凭证文件" value={overview.voucherFiles} unit="份" icon={<FileSpreadsheet className="h-4 w-4" />} />
          <StatCard
            label="核对差异"
            value={overview.reconcileDiffs}
            unit="处"
            icon={<Scale className="h-4 w-4" />}
          />
        </div>
      )}

      <Segmented
        value={tab}
        onChange={(value) => setTab(value)}
        options={[
          { value: 'accounts', label: '连接档案' },
          { value: 'subjects', label: '科目映射' },
          { value: 'masters', label: '主数据映射' },
          { value: 'files', label: '凭证文件' },
          { value: 'reconcile', label: '余额核对' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {/* ------------------------------------------------------ 连接档案 */}
      {tab === 'accounts' && (
        <div className="space-y-3">
          <SectionCard
            title="外部财务软件连接档案"
            description="只记厂商 / 账套号 / 本位币 / 启用状态 —— 不存任何账号口令（接入凭据走 secret manager）。停用不删：历史凭证文件要能复现。"
          >
            {loading ? (
              <div className="text-muted flex items-center gap-2 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
              </div>
            ) : accounts.length === 0 ? (
              <EmptyState
                icon={<Landmark className="h-6 w-6" />}
                title="还没有登记过外部财务软件连接"
                description="登记一个账套之后，科目映射、凭证文件导出、余额核对才有归属。"
              />
            ) : (
              <div className="space-y-2">
                {accounts.map((account) => (
                  <div
                    key={account.id}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm"
                  >
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{account.name}</span>
                        <Badge tone={BADGE_TONE[accountStatusTone(account.status)]}>
                          {account.statusLabel}
                        </Badge>
                        <Badge tone="info">{account.vendorLabel}</Badge>
                      </div>
                      <div className="text-muted text-xs">
                        账套 {account.bookCode} · 本位币 {account.currency} · 科目映射{' '}
                        {account.subjectMapCount} 条 · 主数据映射 {account.masterMapCount} 条 · 凭证文件{' '}
                        {account.voucherFileCount} 份
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {/* 🔴 只有后端明确声明可推送时才有这个按钮。 */}
                      {pushAvailable && (
                        <Button variant="outline" size="sm" disabled={busy} onClick={() => void push(account.id)}>
                          <FileDown className="h-4 w-4" /> 直连推送
                        </Button>
                      )}
                      {account.status === 'ACTIVE' && (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() =>
                            void run(async () => {
                              await disableAccount(account.id);
                            }, '连接档案已停用。')
                          }
                        >
                          停用
                        </Button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="登记连接档案">
            <div className="grid gap-3 md:grid-cols-3">
              <label className="space-y-1 text-sm">
                <span className="text-muted">连接名</span>
                <Input
                  value={accountForm.name}
                  placeholder="美国主体 - 金蝶"
                  onChange={(e) => setAccountForm({ ...accountForm, name: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">厂商</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={accountForm.vendor}
                  onChange={(e) => setAccountForm({ ...accountForm, vendor: e.target.value })}
                >
                  {VENDOR_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">账套号</span>
                <Input
                  value={accountForm.bookCode}
                  placeholder="BOOK-001"
                  onChange={(e) => setAccountForm({ ...accountForm, bookCode: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">外部主体名称</span>
                <Input
                  value={accountForm.companyName}
                  placeholder="Acme US Inc."
                  onChange={(e) => setAccountForm({ ...accountForm, companyName: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">本位币</span>
                <Input
                  value={accountForm.currency}
                  placeholder="USD"
                  onChange={(e) => setAccountForm({ ...accountForm, currency: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">会计年度起始月</span>
                <Input
                  value={accountForm.fiscalStartMonth}
                  placeholder="1"
                  onChange={(e) => setAccountForm({ ...accountForm, fiscalStartMonth: e.target.value })}
                />
              </label>
            </div>
            <Button
              className="mt-3"
              disabled={busy || !accountForm.name || !accountForm.bookCode || !accountForm.currency}
              onClick={() =>
                void run(async () => {
                  await saveAccount({
                    name: accountForm.name,
                    vendor: accountForm.vendor,
                    bookCode: accountForm.bookCode,
                    companyName: accountForm.companyName,
                    currency: accountForm.currency,
                    fiscalStartMonth: Number(accountForm.fiscalStartMonth) || 1,
                    status: 'ACTIVE',
                    remark: '',
                  });
                  setAccountForm(EMPTY_ACCOUNT);
                }, '连接档案已登记。')
              }
            >
              登记连接
            </Button>
          </SectionCard>
        </div>
      )}

      {/* ------------------------------------------------------ 科目映射 */}
      {tab === 'subjects' && (
        <div className="space-y-3">
          <SectionCard
            title="科目映射"
            description="导出凭证文件时本地科目被换成外部科目编码；余额核对时外部科目被换回本地科目编码。没配映射的行会被标成「未映射」，不会被静默丢掉。"
            extra={
              <div className="flex items-center gap-2">
                <Input
                  className="w-44"
                  placeholder="搜索科目…"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                />
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    void reloadMappings().catch(fail)
                  }
                >
                  筛选
                </Button>
              </div>
            }
          >
            {subjectMaps.length === 0 ? (
              <EmptyState
                icon={<Scale className="h-6 w-6" />}
                title={hasAccountFilter ? '没有匹配的科目映射' : '还没有配置科目映射'}
                description={
                  hasAccountFilter
                    ? '换个关键词或清掉筛选再试。'
                    : '配一条映射，导出的凭证文件里才会写对方系统认得的科目编码。'
                }
              />
            ) : (
              <div className="space-y-2">
                {subjectMaps.map((row) => (
                  <div key={row.id} className="flex items-center justify-between gap-2 rounded-md border p-3 text-sm">
                    <div>
                      <span className="font-medium">{row.localSubjectCode}</span>
                      <span className="text-muted"> {row.localSubjectName}</span>
                      <span className="text-muted"> → </span>
                      <span className="font-medium">{row.externalSubjectCode}</span>
                      <span className="text-muted"> {row.externalSubjectName}</span>
                      <span className="text-muted ml-2 text-xs">{row.accountName}</span>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      onClick={() =>
                        void run(async () => {
                          await deleteSubjectMap(row.id);
                          await reloadMappings();
                        }, '科目映射已删除。')
                      }
                    >
                      删除
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="登记科目映射">
            <div className="grid gap-3 md:grid-cols-3">
              <label className="space-y-1 text-sm">
                <span className="text-muted">所属连接</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={subjectForm.accountId}
                  onChange={(e) => setSubjectForm({ ...subjectForm, accountId: e.target.value })}
                >
                  <option value="">请选择</option>
                  {accounts.map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">本系统科目编码</span>
                <Input
                  value={subjectForm.localSubjectCode}
                  placeholder="1122"
                  onChange={(e) => setSubjectForm({ ...subjectForm, localSubjectCode: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">本系统科目名称</span>
                <Input
                  value={subjectForm.localSubjectName}
                  placeholder="应收账款"
                  onChange={(e) => setSubjectForm({ ...subjectForm, localSubjectName: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">外部科目编码</span>
                <Input
                  value={subjectForm.externalSubjectCode}
                  placeholder="EX-AR-1122"
                  onChange={(e) =>
                    setSubjectForm({ ...subjectForm, externalSubjectCode: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">外部科目名称</span>
                <Input
                  value={subjectForm.externalSubjectName}
                  placeholder="外部应收账款"
                  onChange={(e) =>
                    setSubjectForm({ ...subjectForm, externalSubjectName: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">外部税码</span>
                <Input
                  value={subjectForm.taxCode}
                  placeholder="（可留空）"
                  onChange={(e) => setSubjectForm({ ...subjectForm, taxCode: e.target.value })}
                />
              </label>
            </div>
            <Button
              className="mt-3"
              disabled={
                busy ||
                !subjectForm.accountId ||
                !subjectForm.localSubjectCode ||
                !subjectForm.externalSubjectCode
              }
              onClick={() => void submitSubjectMap()}
            >
              保存映射
            </Button>
          </SectionCard>
        </div>
      )}

      {/* ------------------------------------------------------ 主数据映射 */}
      {tab === 'masters' && (
        <div className="space-y-3">
          <SectionCard
            title="主数据映射"
            description="给客户 / 供应商 / 平台 / 资金账户 / 商品 / 部门换外部编码。导出凭证文件时按辅助核算名称匹配 —— 所以同一类型下重名会被挡。"
          >
            {masterMaps.length === 0 ? (
              <EmptyState
                icon={<Scale className="h-6 w-6" />}
                title="还没有配置主数据映射"
                description="配了之后，凭证行上的辅助核算项会带出对方系统认得的编码。"
              />
            ) : (
              <div className="space-y-2">
                {masterMaps.map((row) => (
                  <div key={row.id} className="flex items-center justify-between gap-2 rounded-md border p-3 text-sm">
                    <div>
                      <Badge tone="info">{row.masterTypeLabel}</Badge>
                      <span className="ml-2 font-medium">{row.localName}</span>
                      <span className="text-muted"> ({row.localCode}) → </span>
                      <span className="font-medium">{row.externalCode}</span>
                      <span className="text-muted ml-2 text-xs">{row.accountName}</span>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      onClick={() =>
                        void run(async () => {
                          await deleteMasterMap(row.id);
                          await reloadMappings();
                        }, '主数据映射已删除。')
                      }
                    >
                      删除
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="登记主数据映射">
            <div className="grid gap-3 md:grid-cols-3">
              <label className="space-y-1 text-sm">
                <span className="text-muted">所属连接</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={masterForm.accountId}
                  onChange={(e) => setMasterForm({ ...masterForm, accountId: e.target.value })}
                >
                  <option value="">请选择</option>
                  {accounts.map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">类型</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={masterForm.masterType}
                  onChange={(e) => setMasterForm({ ...masterForm, masterType: e.target.value })}
                >
                  {MASTER_TYPE_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">本地编码</span>
                <Input
                  value={masterForm.localCode}
                  placeholder="C-001"
                  onChange={(e) => setMasterForm({ ...masterForm, localCode: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">本地名称</span>
                <Input
                  value={masterForm.localName}
                  placeholder="Acme US"
                  onChange={(e) => setMasterForm({ ...masterForm, localName: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">外部编码</span>
                <Input
                  value={masterForm.externalCode}
                  placeholder="CUST-EX-001"
                  onChange={(e) => setMasterForm({ ...masterForm, externalCode: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">外部名称</span>
                <Input
                  value={masterForm.externalName}
                  placeholder="（可留空）"
                  onChange={(e) => setMasterForm({ ...masterForm, externalName: e.target.value })}
                />
              </label>
            </div>
            <Button
              className="mt-3"
              disabled={
                busy ||
                !masterForm.accountId ||
                !masterForm.localCode ||
                !masterForm.localName ||
                !masterForm.externalCode
              }
              onClick={() => void submitMasterMap()}
            >
              保存映射
            </Button>
          </SectionCard>
        </div>
      )}

      {/* ------------------------------------------------------ 凭证文件 */}
      {tab === 'files' && (
        <div className="space-y-3">
          <SectionCard
            title="导出标准凭证文件"
            description="真读上游已过账凭证 → 套科目映射 → 生成真文件内容 → 落 sha256 与字节数。导出的文件是**法定账的输入**，不是「本系统已经入账了」。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <label className="space-y-1 text-sm">
                <span className="text-muted">所属连接</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={exportForm.accountId}
                  onChange={(e) => setExportForm({ ...exportForm, accountId: e.target.value })}
                >
                  <option value="">请选择</option>
                  {accounts.map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">文件格式</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={exportForm.format}
                  onChange={(e) => setExportForm({ ...exportForm, format: e.target.value })}
                >
                  {FILE_FORMAT_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">起始日期</span>
                <Input
                  type="date"
                  value={exportForm.dateFrom}
                  onChange={(e) => setExportForm({ ...exportForm, dateFrom: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">截止日期</span>
                <Input
                  type="date"
                  value={exportForm.dateTo}
                  onChange={(e) => setExportForm({ ...exportForm, dateTo: e.target.value })}
                />
              </label>
            </div>
            <Button
              className="mt-3"
              disabled={busy || !exportForm.accountId || !exportForm.dateFrom || !exportForm.dateTo}
              onClick={() => void submitExport()}
            >
              导出凭证文件
            </Button>
          </SectionCard>

          <SectionCard title="导出记录">
            {voucherFiles.length === 0 ? (
              <EmptyState
                icon={<FileSpreadsheet className="h-6 w-6" />}
                title="还没有导出过凭证文件"
                description="导出一次之后，这里会留下文件号、内容指纹与真内容 —— 事后能原样取回当初那一份。"
              />
            ) : (
              <div className="space-y-2">
                {voucherFiles.map((row) => (
                  <div key={row.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{row.fileNo}</span>
                        <Badge tone="neutral">{row.format}</Badge>
                        <Badge tone={BADGE_TONE[unmappedTone(row.unmappedLineCount)]}>
                          {row.unmappedLineCount > 0
                            ? `${row.unmappedLineCount} 行未映射`
                            : '全部已映射'}
                        </Badge>
                      </div>
                      <div className="text-muted text-xs">
                        {row.filename} · {row.voucherCount} 张凭证 / {row.lineCount} 行 · 借{' '}
                        {formatMoney(row.totalDebit, row.currency)} / 贷{' '}
                        {formatMoney(row.totalCredit, row.currency)} · sha256 {shortSha(row.contentSha256)}
                      </div>
                    </div>
                    <Button variant="outline" size="sm" disabled={busy} onClick={() => void openFile(row.id)}>
                      查看内容
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {fileDetail && (
            <SectionCard
              title={`${fileDetail.file.fileNo} · ${fileDetail.file.filename}`}
              description={`${exportSummary(fileDetail.file)} · ${fileDetail.file.contentBytes} 字节 · sha256 ${fileDetail.file.contentSha256}`}
              extra={
                <Button variant="outline" size="sm" onClick={() => downloadFile(fileDetail)}>
                  <FileDown className="h-4 w-4" /> 下载
                </Button>
              }
            >
              <pre className="text-muted max-h-96 overflow-auto rounded-md border bg-muted/30 p-3 text-xs">
                {fileDetail.content}
              </pre>
            </SectionCard>
          )}
        </div>
      )}

      {/* ------------------------------------------------------ 余额核对 */}
      {tab === 'reconcile' && (
        <div className="space-y-3">
          <SectionCard
            title="核对任务"
            description="本地余额真读上游试算平衡表，外部余额来自导入的快照行；两者同口径后逐科目相减，差异是**算出来的**。"
            extra={
              <Button variant="outline" size="sm" onClick={() => void reloadMappings().catch(fail)}>
                刷新
              </Button>
            }
          >
            {reconcileTasks.length === 0 ? (
              <EmptyState
                icon={<Scale className="h-6 w-6" />}
                title="还没有核对任务"
                description="选一段会计期间建一个任务，再把对方系统的余额表导进来。"
              />
            ) : (
              <div className="space-y-2">
                {reconcileTasks.map((task) => (
                  <div key={task.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{task.reconcileNo}</span>
                        <Badge tone={BADGE_TONE[reconcileStatusTone(task.status)]}>
                          {task.statusLabel}
                        </Badge>
                        <span className="text-muted text-xs">{task.accountName}</span>
                      </div>
                      <div className="text-muted text-xs">
                        {task.currency} · 匹配 {task.matchedCount} · 差异 {task.differenceCount} · 差异合计{' '}
                        {formatMoney(task.totalDifference, task.currency)}
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <Button variant="outline" size="sm" disabled={busy} onClick={() => void openTask(task.id)}>
                        查看
                      </Button>
                      <Button variant="outline" size="sm" disabled={busy} onClick={() => void computeTask(task.id)}>
                        计算差异
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="建立核对任务">
            <div className="grid gap-3 md:grid-cols-4">
              <label className="space-y-1 text-sm">
                <span className="text-muted">所属连接</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={reconcileForm.accountId}
                  onChange={(e) => setReconcileForm({ ...reconcileForm, accountId: e.target.value })}
                >
                  <option value="">请选择</option>
                  {accounts.map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">起始会计期间</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={reconcileForm.periodFromId}
                  onChange={(e) => setReconcileForm({ ...reconcileForm, periodFromId: e.target.value })}
                >
                  <option value="">请选择</option>
                  {periods.map((period) => (
                    <option key={period.id} value={period.id}>
                      {period.periodCode}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">截止会计期间</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={reconcileForm.periodToId}
                  onChange={(e) => setReconcileForm({ ...reconcileForm, periodToId: e.target.value })}
                >
                  <option value="">请选择</option>
                  {periods.map((period) => (
                    <option key={period.id} value={period.id}>
                      {period.periodCode}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">核对币种</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-3 text-sm"
                  value={reconcileForm.currency}
                  onChange={(e) => setReconcileForm({ ...reconcileForm, currency: e.target.value })}
                >
                  {(currencies.length > 0 ? currencies : ['USD']).map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <Button
              className="mt-3"
              disabled={
                busy ||
                !reconcileForm.accountId ||
                !reconcileForm.periodFromId ||
                !reconcileForm.periodToId
              }
              onClick={() => void submitReconcileTask()}
            >
              建立任务
            </Button>
          </SectionCard>

          <SectionCard
            title="导入外部余额"
            description="每行一条：外部科目编码,币种,D/C,金额。方向必须在行里 —— 方向缺了，同一个余额会差出一倍。"
          >
            <textarea
              className="border-control-border min-h-28 w-full rounded-md border bg-card p-3 text-sm"
              placeholder={'EX-AR-1122,USD,D,1000\nEX-REV-6001,USD,C,1000'}
              value={balanceText}
              onChange={(e) => setBalanceText(e.target.value)}
            />
            <Button
              className="mt-3"
              disabled={busy || !balanceText.trim() || reconcileTasks.length === 0}
              onClick={() => {
                const task = reconcileTasks[0];
                if (task) void submitBalances(task.id);
              }}
            >
              导入到最近一个任务
            </Button>
          </SectionCard>

          {reconcileDetail && (
            <SectionCard
              title={`${reconcileDetail.task.reconcileNo} · 核对差异`}
              description={`匹配 ${reconcileDetail.task.matchedCount} · 差异 ${reconcileDetail.task.differenceCount} · 差异合计 ${formatMoney(reconcileDetail.task.totalDifference, reconcileDetail.task.currency)}`}
            >
              {sortedDiffs.length === 0 ? (
                <EmptyState
                  icon={<Scale className="h-6 w-6" />}
                  title="这次核对没有差异"
                  description="两边逐科目都对上了。"
                />
              ) : (
                <div className="space-y-2">
                  {sortedDiffs.map((diff) => {
                    const code = diffExternalCodeLabel(diff.externalSubjectCode);
                    return (
                      <div key={diff.id} className="rounded-md border p-3 text-sm">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone={BADGE_TONE[reconcileReasonTone(diff.reason)]}>
                            {reconcileReasonLabel(diff.reason)}
                          </Badge>
                          <span className="font-medium">
                            {code.placeholder ? code.label : diff.externalSubjectCode}
                          </span>
                          {diff.localSubjectCode && (
                            <span className="text-muted">← 本地 {diff.localSubjectCode} {diff.localSubjectName}</span>
                          )}
                        </div>
                        <div className="text-muted mt-1 text-xs">
                          外部 {formatMoney(diff.externalBalance, diff.currency)} · 本地{' '}
                          {formatMoney(diff.localBalance, diff.currency)} · 差额{' '}
                          {formatMoney(diff.difference, diff.currency)}
                        </div>
                        {diff.detail && <div className="text-muted mt-1 text-xs">{diff.detail}</div>}
                      </div>
                    );
                  })}
                </div>
              )}
            </SectionCard>
          )}
        </div>
      )}

      {/* ------------------------------------------------------ 能力声明 */}
      {tab === 'capabilities' && (
        <div className="space-y-3">
          <SectionCard
            title="能力声明"
            description="哪些真接通、哪些还没交付、缺在谁身上 —— 都是可查的事实，不是注释。"
          >
            {loading ? (
              <div className="text-muted flex items-center gap-2 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
              </div>
            ) : !capabilities ? (
              <EmptyState title="能力声明读不到" description="上游没返回，请稍后重试。" />
            ) : (
              <div className="space-y-2">
                {capabilities.capabilities.map((capability) => (
                  <div key={capability.name} className="rounded-md border p-3 text-sm">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={BADGE_TONE[capabilityTone(capability.available)]}>
                        {capability.available ? '已接通' : '未接入'}
                      </Badge>
                      <span className="font-medium">{capability.name.replace(/\*/g, '')}</span>
                      {capability.ownerModule && (
                        <Badge tone="warning">归 {capability.ownerModule}</Badge>
                      )}
                    </div>
                    <div className="text-muted mt-1 text-xs">{capability.evidence}</div>
                    {capability.note && <div className="text-muted mt-1 text-xs">{capability.note}</div>}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {!pushAvailable && (
            <SectionCard title="直连推送">
              <div className="text-muted flex items-start gap-2 text-sm">
                <Unplug className="mt-0.5 h-4 w-4 shrink-0" />
                <div className="space-y-1">
                  <div>{unavailableHint(pushCap)}</div>
                  <div className="text-xs">
                    🔴 页面**不提供**「直连推送」这个按钮 —— 一个点了必然 409 的按钮，
                    会让运营以为「点一下就能推」。
                  </div>
                  <div className="text-xs">
                    今天就能用的是这条完整路径：导出凭证文件 → 导入对方系统 → 把对方的余额表导回来核对。
                  </div>
                </div>
              </div>
            </SectionCard>
          )}

          {capabilities && capabilities.gaps.length > 0 && (
            <SectionCard title="缺口清单" description="本车道不依赖它们，也没有顺手实现它们。">
              <div className="space-y-2">
                {capabilities.gaps.map((gap) => (
                  <div key={gap.moduleId} className="flex items-start gap-2 rounded-md border p-3 text-sm">
                    <TriangleAlert className="text-muted mt-0.5 h-4 w-4 shrink-0" />
                    <div>
                      <span className="font-medium">{gap.name}</span>
                      <span className="text-muted ml-2 text-xs">
                        {gap.moduleId} · {gap.relation}
                      </span>
                      <div className="text-muted text-xs">{gap.note}</div>
                    </div>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}
        </div>
      )}
    </div>
  );
}
