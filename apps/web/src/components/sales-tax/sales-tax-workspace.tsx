'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  FileUp,
  Loader2,
  RefreshCw,
  ScrollText,
  Scale,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  FILING_CYCLES,
  NEXUS_TYPES,
  RECONCILIATION_STATUSES,
  REGISTRATION_STATUSES,
  archiveRateRule,
  computeReconciliations,
  createFacilitatorScope,
  createRateRule,
  createShopState,
  emptyState,
  emptyStateKind,
  fetchCapabilities,
  fetchFacilitatorReports,
  fetchFacilitatorScopes,
  fetchObligations,
  fetchRateRules,
  fetchReconciliations,
  fetchShopStates,
  fetchShops,
  fetchStates,
  filingObligationLabel,
  formatAmount,
  formatRate,
  groupTotalsByCurrency,
  importFacilitatorReport,
  reconciliationStatusLabel,
  reconciliationStatusTone,
  registrationStatusLabel,
  updateRateRule,
  varianceMeaning,
  varianceTone,
  type SalesTaxCapabilities,
  type SalesTaxFacilitatorReportDetail,
  type SalesTaxFacilitatorReportView,
  type SalesTaxFacilitatorScopeView,
  type SalesTaxObligationPage,
  type SalesTaxRateRuleView,
  type SalesTaxReconciliationPage,
  type SalesTaxReconciliationView,
  type SalesTaxShopStateView,
  type SalesTaxStatePage,
} from './sales-tax-model';

/**
 * 16.3.2.3 销售税与 Marketplace Facilitator 工作区。
 *
 * 蓝图：「处理美国各州销售税与 Marketplace Facilitator 制度：应缴/代缴状态、州级阈值、免征额、
 * 按平台的代扣代缴说明与对账。输入：店铺所在州数据；平台代扣代缴报表；自营应税销售额。
 * 输出：各州税务状态 + 代扣代缴对账结果。关系：上游 = 2 店铺、12.3.1.2；下游 = 12.6 申报。
 * **平台代扣代缴不等于免税**，卖家的申报义务依然存在，只是由平台代收代缴，需要正确对账。」
 *
 * 🔴 页面上的八条纪律：
 *
 * 1. **应缴与已代缴分成两列**，界面上**不出现「销售税净额」** —— 净额看不出该补缴还是该退款。
 * 2. **差异的符号约定写在界面上**：正数 = 平台少收了（卖家要补缴）。
 * 3. **「判不了」是独立的一档**（没有生效规则 / 没登记所在州）——
 *    界面**不**把它渲染成「应缴 0」，那是最贵的假结论。
 * 4. **税率、阈值、免征额全部来自后端规则**，页面**不内置州税率表**。
 * 5. **规则是版本化的**：改规则 = 发新版本，界面显示版本号与生效区间。
 * 6. **报表是真上传**：粘贴原文 → 后端解析 → 逐行结果 + 逐条告警回显。
 * 7. **金额按币种分组**：**不同币种绝不直接相加**（合计卡只显示单币种）。
 * 8. **未交付的下游不编造、不跳转**：12.6 申报未交付 ⇒ 只给结论，不给「去申报」入口。
 */
const PAGE_SIZE = 10;

interface Props {
  /** 从别处跳进来时的定位（真实存在的页面参数）。 */
  focus?: { stateCode?: string };
}

interface RuleForm {
  stateCode: string;
  effectiveFrom: string;
  effectiveTo: string;
  baseRate: string;
  thresholdAmount: string;
  exemptionAmount: string;
  marketplaceFacilitator: boolean;
  filingCycle: string;
  note: string;
}

const EMPTY_RULE_FORM: RuleForm = {
  stateCode: '',
  effectiveFrom: '',
  effectiveTo: '',
  baseRate: '',
  thresholdAmount: '0',
  exemptionAmount: '0',
  marketplaceFacilitator: true,
  filingCycle: 'QUARTERLY',
  note: '',
};

interface ShopStateForm {
  shopId: string;
  stateCode: string;
  nexusType: string;
  registered: boolean;
  registrationNo: string;
  registeredFrom: string;
  note: string;
}

const EMPTY_SHOP_STATE_FORM: ShopStateForm = {
  shopId: '',
  stateCode: '',
  nexusType: 'INVENTORY_STORED',
  registered: true,
  registrationNo: '',
  registeredFrom: '',
  note: '',
};

interface ScopeForm {
  stateCode: string;
  platformCode: string;
  facilitatorEnabled: boolean;
  remitTo: string;
  reportRequired: boolean;
  note: string;
}

const EMPTY_SCOPE_FORM: ScopeForm = {
  stateCode: '',
  platformCode: '',
  facilitatorEnabled: true,
  remitTo: '',
  reportRequired: true,
  note: '',
};

interface ImportForm {
  platformCode: string;
  fileKey: string;
  fileName: string;
  contentType: 'CSV' | 'JSON';
  content: string;
  periodStart: string;
  periodEnd: string;
}

const EMPTY_IMPORT_FORM: ImportForm = {
  platformCode: '',
  fileKey: '',
  fileName: '',
  contentType: 'CSV',
  content: '',
  periodStart: '',
  periodEnd: '',
};

export function SalesTaxWorkspace({ focus }: Props) {
  const [caps, setCaps] = useState<SalesTaxCapabilities | null>(null);
  const [states, setStates] = useState<SalesTaxStatePage | null>(null);
  const [rules, setRules] = useState<SalesTaxRateRuleView[]>([]);
  const [shopStates, setShopStates] = useState<SalesTaxShopStateView[]>([]);
  const [scopes, setScopes] = useState<SalesTaxFacilitatorScopeView[]>([]);
  const [reports, setReports] = useState<SalesTaxFacilitatorReportView[]>([]);
  const [reconciliations, setReconciliations] = useState<SalesTaxReconciliationPage | null>(null);
  const [obligations, setObligations] = useState<SalesTaxObligationPage | null>(null);
  const [shops, setShops] = useState<{ id: string; code: string; name: string }[]>([]);
  const [reportDetail, setReportDetail] = useState<SalesTaxFacilitatorReportDetail | null>(null);

  const [stateFilter, setStateFilter] = useState(focus?.stateCode ?? '');
  const [ruleStatusFilter, setRuleStatusFilter] = useState('');
  const [statePage, setStatePage] = useState(0);

  const [ruleOpen, setRuleOpen] = useState(false);
  const [ruleForm, setRuleForm] = useState<RuleForm>(EMPTY_RULE_FORM);
  const [ruleEditing, setRuleEditing] = useState<SalesTaxRateRuleView | null>(null);

  const [shopStateOpen, setShopStateOpen] = useState(false);
  const [shopStateForm, setShopStateForm] = useState<ShopStateForm>(EMPTY_SHOP_STATE_FORM);

  const [scopeOpen, setScopeOpen] = useState(false);
  const [scopeForm, setScopeForm] = useState<ScopeForm>(EMPTY_SCOPE_FORM);

  const [importOpen, setImportOpen] = useState(false);
  const [importForm, setImportForm] = useState<ImportForm>(EMPTY_IMPORT_FORM);
  const [importResult, setImportResult] = useState<{
    scanned: number;
    imported: number;
    skipped: number;
    warnings: string[];
  } | null>(null);

  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, stateResult, ruleResult, shopStateResult, scopeResult, reportResult, shopResult] =
        await Promise.all([
          fetchCapabilities(),
          fetchStates({
            stateCode: stateFilter || undefined,
            ruleStatus: ruleStatusFilter || undefined,
            page: statePage,
            size: PAGE_SIZE,
          }),
          fetchRateRules({ stateCode: stateFilter || undefined, page: 0, size: 50 }),
          fetchShopStates({ stateCode: stateFilter || undefined, page: 0, size: 50 }),
          fetchFacilitatorScopes({ stateCode: stateFilter || undefined, page: 0, size: 50 }),
          fetchFacilitatorReports({ page: 0, size: 50 }),
          fetchShops(),
        ]);
      setCaps(capsResult);
      setStates(stateResult);
      setRules(ruleResult.items);
      setShopStates(shopStateResult.items);
      setScopes(scopeResult.items);
      setReports(reportResult.items);
      setShops(shopResult.items.map((shop) => ({ id: shop.id, code: shop.code, name: shop.name })));

      // 对账与义务是**可选**的：期间没选就不拉，避免打开页面就打两个空请求。
      if (periodStart && periodEnd) {
        const [reconResult, obligationResult] = await Promise.all([
          fetchReconciliations({
            stateCode: stateFilter || undefined,
            periodStart,
            periodEnd,
            page: 0,
            size: 50,
          }),
          fetchObligations({
            stateCode: stateFilter || undefined,
            periodStart,
            periodEnd,
            page: 0,
            size: 50,
          }),
        ]);
        setReconciliations(reconResult);
        setObligations(obligationResult);
      } else {
        setReconciliations(null);
        setObligations(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [stateFilter, ruleStatusFilter, statePage, periodStart, periodEnd]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  // ==================================================================
  // 合计卡（**按币种分组**：不同币种绝不直接相加）
  // ==================================================================

  const currencyTotals = useMemo(() => {
    const rows = reconciliations?.items ?? [];
    return groupTotalsByCurrency(rows);
  }, [reconciliations]);

  const varianceStates = useMemo(
    () => (states?.items ?? []).filter((row) => row.status === 'VARIANCE').length,
    [states],
  );

  const filingRequired = useMemo(
    () => (obligations?.items ?? []).filter((row) => row.filingObligation === 'FILING_REQUIRED').length,
    [obligations],
  );

  // ==================================================================
  // 操作
  // ==================================================================

  const submitRule = useCallback(
    () =>
      run(async () => {
        const input = {
          stateCode: ruleForm.stateCode.trim().toUpperCase(),
          effectiveFrom: ruleForm.effectiveFrom,
          effectiveTo: ruleForm.effectiveTo || null,
          baseRate: ruleForm.baseRate,
          thresholdAmount: ruleForm.thresholdAmount || '0',
          exemptionAmount: ruleForm.exemptionAmount || '0',
          marketplaceFacilitator: ruleForm.marketplaceFacilitator,
          filingCycle: ruleForm.filingCycle,
          note: ruleForm.note,
        };
        if (ruleEditing) {
          // 🔴 改规则 = **发新版本**（封旧版 + 建新版），不是就地改数字。
          await updateRateRule(ruleEditing.id, input);
        } else {
          await createRateRule(input);
        }
        setRuleOpen(false);
        setRuleForm(EMPTY_RULE_FORM);
        setRuleEditing(null);
        await refresh();
      }),
    [run, ruleForm, ruleEditing, refresh],
  );

  const submitShopState = useCallback(
    () =>
      run(async () => {
        await createShopState({
          shopId: shopStateForm.shopId,
          stateCode: shopStateForm.stateCode.trim().toUpperCase(),
          nexusType: shopStateForm.nexusType,
          registered: shopStateForm.registered,
          registrationNo: shopStateForm.registrationNo,
          registeredFrom: shopStateForm.registeredFrom || null,
          note: shopStateForm.note,
        });
        setShopStateOpen(false);
        setShopStateForm(EMPTY_SHOP_STATE_FORM);
        await refresh();
      }),
    [run, shopStateForm, refresh],
  );

  const submitScope = useCallback(
    () =>
      run(async () => {
        await createFacilitatorScope({
          stateCode: scopeForm.stateCode.trim().toUpperCase(),
          platformCode: scopeForm.platformCode.trim(),
          facilitatorEnabled: scopeForm.facilitatorEnabled,
          remitTo: scopeForm.remitTo,
          reportRequired: scopeForm.reportRequired,
          note: scopeForm.note,
        });
        setScopeOpen(false);
        setScopeForm(EMPTY_SCOPE_FORM);
        await refresh();
      }),
    [run, scopeForm, refresh],
  );

  const submitImport = useCallback(
    () =>
      run(async () => {
        const result = await importFacilitatorReport({
          platformCode: importForm.platformCode.trim(),
          fileKey: importForm.fileKey.trim(),
          fileName: importForm.fileName.trim() || importForm.fileKey.trim(),
          contentType: importForm.contentType,
          content: importForm.content,
          periodStart: importForm.periodStart,
          periodEnd: importForm.periodEnd,
        });
        setImportResult({
          scanned: result.scanned,
          imported: result.imported,
          skipped: result.skipped,
          warnings: result.warnings,
        });
        await refresh();
      }),
    [run, importForm, refresh],
  );

  const computeNow = useCallback(
    () =>
      run(async () => {
        await computeReconciliations({
          stateCode: stateFilter || undefined,
          periodStart,
          periodEnd,
        });
        await refresh();
      }),
    [run, stateFilter, periodStart, periodEnd, refresh],
  );

  const openReport = useCallback(
    (id: string) =>
      run(async () => {
        const { fetchFacilitatorReport } = await import('./sales-tax-model');
        setReportDetail(await fetchFacilitatorReport(id));
      }),
    [run],
  );

  const archiveRule = useCallback(
    (id: string) =>
      run(async () => {
        await archiveRateRule(id);
        await refresh();
      }),
    [run, refresh],
  );

  const stateEmpty = emptyStateKind(
    { stateCode: stateFilter || undefined, ruleStatus: ruleStatusFilter || undefined },
    states?.total ?? 0,
  );
  const stateEmptyCopy = emptyState(stateEmpty);

  return (
    <div className="space-y-4">
      <PageHeader
        title="销售税与 Marketplace Facilitator"
        description="美国各州销售税的应缴/代缴状态、州级阈值、免征额、按平台的代扣代缴说明与对账。"
        actions={
          <Button variant="outline" onClick={() => void refresh()} disabled={loading || busy}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="操作失败">
          {error}
        </Notice>
      )}

      {caps && (
        <Notice tone="info" title="能力声明">
          <ul className="mt-1 list-disc space-y-1 pl-4">
            {caps.gaps.map((gap) => (
              <li key={gap.moduleId}>
                <strong>{gap.moduleId} {gap.moduleName}</strong>（归 {gap.ownerModule}）未交付：{gap.impact}
              </li>
            ))}
          </ul>
          <p className="mt-2">{caps.reconciliationSemantics}</p>
          <p className="mt-1">{caps.rateSemantics}</p>
        </Notice>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="销售税州" value={states?.total ?? 0} icon={<Scale className="h-4 w-4" />} />
        <StatCard
          label="有差异的州"
          value={varianceStates}
          polarity="negative"
          icon={<TriangleAlert className="h-4 w-4" />}
        />
        <StatCard
          label="需要申报的州（期间）"
          value={filingRequired}
          polarity="negative"
          icon={<ScrollText className="h-4 w-4" />}
        />
        <StatCard
          label="应缴合计"
          value={
            currencyTotals.length === 0
              ? '—'
              : `${formatAmount(currencyTotals[0]?.expectedTax)} ${currencyTotals[0]?.currency ?? ''}`
          }
          {...(currencyTotals.length > 1
            ? { comparisonLabel: `另有 ${currencyTotals.length - 1} 个币种（不同币种不合并）` }
            : {})}
          icon={<ShieldCheck className="h-4 w-4" />}
        />
      </div>

      {/* ---- 各州税务状态 ---- */}
      <SectionCard
        title="各州税务状态"
        description="州清单 = 本租户有销售税联系的州 ∪ 有代扣代缴报表的州 ∪ 已有对账结果的州 ∪ 已配税率规则的州。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="h-8 w-40"
              placeholder="州码（如 US-CA）"
              value={stateFilter}
              onChange={(e) => setStateFilter(e.target.value.toUpperCase())}
            />
            <select
              className="border-control-border bg-card text-copy-13 h-8 rounded-md border px-2"
              value={ruleStatusFilter}
              onChange={(e) => setRuleStatusFilter(e.target.value)}
            >
              <option value="">全部规则状态</option>
              <option value="RULE_FOUND">有生效规则</option>
              <option value="RULE_MISSING">没有生效规则</option>
            </select>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setStatePage(0);
                void refresh();
              }}
            >
              筛选
            </Button>
          </div>
        }
      >
        {loading && !states ? (
          <div className="text-muted-foreground flex items-center gap-2 px-5 py-6 text-copy-13">
            <Loader2 className="h-4 w-4 animate-spin" />
            加载中…
          </div>
        ) : !states || states.items.length === 0 ? (
          <div className="px-5 py-6">
            <EmptyState
              icon={<Scale className="h-6 w-6" />}
              title={stateEmptyCopy.title}
              description={stateEmptyCopy.description}
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-5 py-2 font-medium">州</th>
                  <th className="px-3 py-2 font-medium">登记</th>
                  <th className="px-3 py-2 font-medium">规则</th>
                  <th className="px-3 py-2 font-medium">自营应税销售额</th>
                  <th className="px-3 py-2 font-medium">应缴</th>
                  <th className="px-3 py-2 font-medium">平台已代缴</th>
                  <th className="px-3 py-2 font-medium">差异</th>
                  <th className="px-3 py-2 font-medium">申报义务</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {states.items.map((row) => (
                  <tr key={row.stateCode} className="border-border border-b last:border-0">
                    <td className="px-5 py-2">
                      <div className="font-medium">{row.stateName}</div>
                      <div className="text-muted-foreground text-copy-12">{row.stateCode}</div>
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={row.registrationStatus === 'REGISTERED' ? 'success' : 'warning'}>
                        {registrationStatusLabel(row.registrationStatus)}
                      </Badge>
                      <div className="text-muted-foreground text-copy-12">
                        {row.registeredShopCount}/{row.shopCount} 家店
                      </div>
                    </td>
                    <td className="px-3 py-2">
                      {row.ruleStatus === 'RULE_FOUND' ? (
                        <>
                          <div>
                            {formatRate(row.baseRate)} · 第 {row.ruleVersion} 版
                          </div>
                          <div className="text-muted-foreground text-copy-12">
                            阈值 {formatAmount(row.thresholdAmount)} / 免征 {formatAmount(row.exemptionAmount)}
                          </div>
                        </>
                      ) : (
                        <Badge tone="warning">没有生效规则（判不了）</Badge>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      {row.currency ? `${formatAmount(row.selfTaxableSales)} ${row.currency}` : '—'}
                    </td>
                    <td className="px-3 py-2">
                      {row.currency ? `${formatAmount(row.expectedTax)} ${row.currency}` : '—'}
                    </td>
                    <td className="px-3 py-2">
                      {row.currency ? `${formatAmount(row.facilitatorTax)} ${row.currency}` : '—'}
                    </td>
                    <td className="px-3 py-2">
                      {row.currency ? (
                        <>
                          <Badge tone={varianceTone(row.variance ?? 0)}>
                            {formatAmount(row.variance)} {row.currency}
                          </Badge>
                          <div className="text-muted-foreground text-copy-12">
                            {varianceMeaning(row.variance ?? 0)}
                          </div>
                        </>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={row.filingObligation === 'FILING_REQUIRED' ? 'danger' : 'neutral'}>
                        {filingObligationLabel(row.filingObligation)}
                      </Badge>
                    </td>
                    <td className="px-3 py-2">
                      <Badge
                        tone={
                          row.status === 'BALANCED'
                            ? 'success'
                            : row.status === 'VARIANCE'
                              ? 'danger'
                              : row.status === 'INDETERMINATE'
                                ? 'warning'
                                : 'neutral'
                        }
                      >
                        {row.statusLabel}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {states.items.length > 0 && (
              <ul className="text-muted-foreground space-y-1 px-5 py-3 text-copy-12">
                {states.items.flatMap((row) => row.notes.map((note) => <li key={`${row.stateCode}-${note}`}>· {note}</li>))}
              </ul>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---- 税率规则 ---- */}
      <SectionCard
        title="销售税率规则"
        description="税率 / 州级阈值 / 免征额是**可配置、可版本化**的规则数据：一个州可以有多版，生效区间左闭右开且不许重叠。"
        extra={
          <Button
            size="sm"
            onClick={() => {
              setRuleEditing(null);
              setRuleForm({ ...EMPTY_RULE_FORM, stateCode: stateFilter });
              setRuleOpen(true);
            }}
          >
            录入规则
          </Button>
        }
      >
        {rules.length === 0 ? (
          <div className="px-5 py-6">
            <EmptyState
              icon={<Scale className="h-6 w-6" />}
              title="还没有税率规则"
              description="没有生效规则时，应缴是**判不了**的 —— 页面不会拿 0 税率替你算出一个「不用交税」的结论。"
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-5 py-2 font-medium">州</th>
                  <th className="px-3 py-2 font-medium">版本</th>
                  <th className="px-3 py-2 font-medium">生效区间</th>
                  <th className="px-3 py-2 font-medium">税率</th>
                  <th className="px-3 py-2 font-medium">阈值</th>
                  <th className="px-3 py-2 font-medium">免征额</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                  <th className="px-3 py-2 font-medium" />
                </tr>
              </thead>
              <tbody>
                {rules.map((rule) => (
                  <tr key={rule.id} className="border-border border-b last:border-0">
                    <td className="px-5 py-2">{rule.stateName}</td>
                    <td className="px-3 py-2">第 {rule.ruleVersion} 版</td>
                    <td className="px-3 py-2">
                      {rule.effectiveFrom} → {rule.effectiveTo ?? '（当前）'}
                    </td>
                    <td className="px-3 py-2">{formatRate(rule.baseRate)}</td>
                    <td className="px-3 py-2">{formatAmount(rule.thresholdAmount)}</td>
                    <td className="px-3 py-2">{formatAmount(rule.exemptionAmount)}</td>
                    <td className="px-3 py-2">
                      <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {rule.status === 'ACTIVE' ? '当前生效' : rule.status === 'SUPERSEDED' ? '已封版' : '已撤下'}
                      </Badge>
                    </td>
                    <td className="px-3 py-2 text-right">
                      {rule.status === 'ACTIVE' && (
                        <div className="flex justify-end gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => {
                              setRuleEditing(rule);
                              setRuleForm({
                                stateCode: rule.stateCode,
                                effectiveFrom: '',
                                effectiveTo: '',
                                baseRate: String(rule.baseRate),
                                thresholdAmount: String(rule.thresholdAmount),
                                exemptionAmount: String(rule.exemptionAmount),
                                marketplaceFacilitator: rule.marketplaceFacilitator,
                                filingCycle: rule.filingCycle,
                                note: rule.note,
                              });
                              setRuleOpen(true);
                            }}
                          >
                            发新版本
                          </Button>
                          <Button size="sm" variant="ghost" onClick={() => void archiveRule(rule.id)}>
                            撤下
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 店铺所在州 ---- */}
      <SectionCard
        title="店铺所在州"
        description="自营应税销售额按这里归集到州。没有登记所在州 ⇒ 该州的应缴**判不了**。"
        extra={
          <Button
            size="sm"
            onClick={() => {
              setShopStateForm({ ...EMPTY_SHOP_STATE_FORM, stateCode: stateFilter });
              setShopStateOpen(true);
            }}
          >
            登记所在州
          </Button>
        }
      >
        {shopStates.length === 0 ? (
          <div className="px-5 py-6">
            <EmptyState
              icon={<CircleAlert className="h-6 w-6" />}
              title="还没有登记店铺所在州"
              description="先登记「哪家店在哪个州有销售税联系」，自营应税销售额才有地方归集。"
            />
          </div>
        ) : (
          <ul className="divide-border divide-y">
            {shopStates.map((row) => (
              <li key={row.id} className="flex flex-wrap items-center justify-between gap-2 px-5 py-2">
                <div>
                  <span className="font-medium">{row.shopName}</span>
                  <span className="text-muted-foreground text-copy-12">（{row.shopCode}）</span>
                  <span className="text-muted-foreground text-copy-12">
                    {' '}
                    · {row.stateName}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone="neutral">{row.nexusType}</Badge>
                  <Badge tone={row.registered ? 'success' : 'warning'}>
                    {row.registered ? `已登记 ${row.registrationNo || ''}` : '未登记'}
                  </Badge>
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 按平台的代扣代缴说明 ---- */}
      <SectionCard
        title="按平台的代扣代缴说明"
        description="逐（州 × 平台）：平台在这个州代不代收、汇给谁、要不要单独报表。"
        extra={
          <Button
            size="sm"
            onClick={() => {
              setScopeForm({ ...EMPTY_SCOPE_FORM, stateCode: stateFilter });
              setScopeOpen(true);
            }}
          >
            登记说明
          </Button>
        }
      >
        {scopes.length === 0 ? (
          <div className="px-5 py-6">
            <EmptyState
              icon={<FileUp className="h-6 w-6" />}
              title="还没有代扣代缴说明"
              description="同一个州，不同平台的代收口径不一样 —— 说明要逐（州 × 平台）登记。"
            />
          </div>
        ) : (
          <ul className="divide-border divide-y">
            {scopes.map((row) => (
              <li key={row.id} className="flex flex-wrap items-center justify-between gap-2 px-5 py-2">
                <div>
                  <span className="font-medium">
                    {row.stateName} · {row.platformCode}
                  </span>
                  {row.remitTo && (
                    <span className="text-muted-foreground text-copy-12"> · 汇给 {row.remitTo}</span>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={row.facilitatorEnabled ? 'info' : 'neutral'}>
                    {row.facilitatorEnabled ? '平台代收' : '平台不代收'}
                  </Badge>
                  <Badge tone={row.reportRequired ? 'neutral' : 'warning'}>
                    {row.reportRequired ? '需交报表' : '免报表'}
                  </Badge>
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 代扣代缴报表 ---- */}
      <SectionCard
        title="平台代扣代缴报表"
        description="上传原文 → 服务端真解析 → 逐行落库；原文与内容指纹都留着，改一次版本号 +1。"
        extra={
          <Button
            size="sm"
            onClick={() => {
              setImportResult(null);
              setImportForm(EMPTY_IMPORT_FORM);
              setImportOpen(true);
            }}
          >
            上传报表
          </Button>
        }
      >
        {reports.length === 0 ? (
          <div className="px-5 py-6">
            <EmptyState
              icon={<FileUp className="h-6 w-6" />}
              title="还没有代扣代缴报表"
              description="没有报表就没有「平台已代缴」这一条线，对账只能算出应缴、无法比对差异。"
            />
          </div>
        ) : (
          <ul className="divide-border divide-y">
            {reports.map((row) => (
              <li key={row.id} className="flex flex-wrap items-center justify-between gap-2 px-5 py-2">
                <div>
                  <span className="font-medium">{row.platformCode}</span>
                  <span className="text-muted-foreground text-copy-12"> · {row.fileKey}</span>
                  <div className="text-muted-foreground text-copy-12">
                    {row.periodStart} → {row.periodEnd} · 第 {row.parseVersion} 版 · {row.lineCount} 行
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={row.parseStatus === 'PARSED' ? 'success' : 'warning'}>
                    {row.parseStatus === 'PARSED' ? '已解析' : '无可用行'}
                  </Badge>
                  <Button size="sm" variant="ghost" onClick={() => void openReport(row.id)}>
                    看明细
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 对账 ---- */}
      <SectionCard
        title="代扣代缴对账"
        description="自营应税销售额来自 12.3.1.2 交易明细，平台已代缴来自已上传的报表，税率来自生效规则 —— 三条线各自独立。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="h-8 w-36"
              type="date"
              value={periodStart}
              onChange={(e) => setPeriodStart(e.target.value)}
              aria-label="期间起"
            />
            <span className="text-muted-foreground text-copy-12">→</span>
            <Input
              className="h-8 w-36"
              type="date"
              value={periodEnd}
              onChange={(e) => setPeriodEnd(e.target.value)}
              aria-label="期间止"
            />
            <Button
              size="sm"
              onClick={() => void refresh()}
              disabled={!periodStart || !periodEnd}
            >
              查看
            </Button>
            <Button
              size="sm"
              variant="brand"
              onClick={() => void computeNow()}
              disabled={!periodStart || !periodEnd || busy}
            >
              算一次对账
            </Button>
          </div>
        }
      >
        {!periodStart || !periodEnd ? (
          <div className="px-5 py-6">
            <EmptyState
              icon={<ScrollText className="h-6 w-6" />}
              title="先选对账期间"
              description="期间决定「自营应税销售额」从上游取哪一段、平台报表取哪几份。"
            />
          </div>
        ) : !reconciliations || reconciliations.items.length === 0 ? (
          <div className="px-5 py-6">
            <EmptyState
              icon={<ScrollText className="h-6 w-6" />}
              title={emptyState('NO_RECONCILIATION').title}
              description={emptyState('NO_RECONCILIATION').description}
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-5 py-2 font-medium">州</th>
                  <th className="px-3 py-2 font-medium">期间</th>
                  <th className="px-3 py-2 font-medium">币种</th>
                  <th className="px-3 py-2 font-medium">自营应税销售额</th>
                  <th className="px-3 py-2 font-medium">免征额内</th>
                  <th className="px-3 py-2 font-medium">应缴</th>
                  <th className="px-3 py-2 font-medium">平台已代缴</th>
                  <th className="px-3 py-2 font-medium">差异</th>
                  <th className="px-3 py-2 font-medium">结算单口径（交叉验证）</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {reconciliations.items.map((row) => (
                  <tr key={row.id} className="border-border border-b last:border-0">
                    <td className="px-5 py-2">{row.stateName}</td>
                    <td className="px-3 py-2">
                      {row.periodStart} → {row.periodEnd}
                    </td>
                    <td className="px-3 py-2">{row.currency}</td>
                    <td className="px-3 py-2">{formatAmount(row.selfTaxableSales)}</td>
                    <td className="px-3 py-2">{formatAmount(row.exemptSales)}</td>
                    <td className="px-3 py-2">{formatAmount(row.expectedTax)}</td>
                    <td className="px-3 py-2">{formatAmount(row.facilitatorTax)}</td>
                    <td className="px-3 py-2">
                      <Badge tone={varianceTone(row.variance)}>{formatAmount(row.variance)}</Badge>
                      <div className="text-muted-foreground text-copy-12">{varianceMeaning(row.variance)}</div>
                    </td>
                    <td className="px-3 py-2">{formatAmount(row.settlementTax)}</td>
                    <td className="px-3 py-2">
                      <Badge tone={reconciliationStatusTone(row.status)}>
                        {row.statusLabel || reconciliationStatusLabel(row.status)}
                      </Badge>
                      {row.rateChangedInPeriod && (
                        <div className="text-warning text-copy-12">期间内税率发过新版，需人工复核</div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 申报义务 ---- */}
      <SectionCard
        title="申报义务（下游 12.6 接手用）"
        description="🔴 平台代扣代缴**不等于免税**：每一行都同时给出「应缴」与「已由平台代缴」，申报义务不因为平台代收而消失。本模块**不做申报**（12.6 未交付）。"
      >
        {!obligations || obligations.items.length === 0 ? (
          <div className="px-5 py-6">
            <EmptyState
              icon={<ScrollText className="h-6 w-6" />}
              title="还没有申报义务结论"
              description="选好期间并算一次对账，这里就会出现逐州逐期的应缴 / 已代缴 / 差异。"
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-5 py-2 font-medium">州</th>
                  <th className="px-3 py-2 font-medium">期间</th>
                  <th className="px-3 py-2 font-medium">应缴</th>
                  <th className="px-3 py-2 font-medium">平台已代缴</th>
                  <th className="px-3 py-2 font-medium">差异</th>
                  <th className="px-3 py-2 font-medium">申报义务</th>
                  <th className="px-3 py-2 font-medium">说明</th>
                </tr>
              </thead>
              <tbody>
                {obligations.items.map((row) => (
                  <tr key={row.reconciliationId} className="border-border border-b last:border-0">
                    <td className="px-5 py-2">{row.stateName}</td>
                    <td className="px-3 py-2">
                      {row.periodStart} → {row.periodEnd}
                    </td>
                    <td className="px-3 py-2">
                      {formatAmount(row.expectedTax)} {row.currency}
                    </td>
                    <td className="px-3 py-2">
                      {formatAmount(row.facilitatorTax)} {row.currency}
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={varianceTone(row.variance)}>{formatAmount(row.variance)}</Badge>
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={row.filingObligation === 'FILING_REQUIRED' ? 'danger' : 'neutral'}>
                        {filingObligationLabel(row.filingObligation)}
                      </Badge>
                    </td>
                    <td className="text-muted-foreground px-3 py-2 text-copy-12">{row.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 弹窗：录规则 ---- */}
      <Modal open={ruleOpen} onOpenChange={setRuleOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{ruleEditing ? '发新版本（封旧版 + 建新版）' : '录入一版税率规则'}</ModalTitle>
            <ModalDescription>
              税率 / 阈值 / 免征额是**规则数据**，不是代码常量；改规则 = 发新版本，历史版本内容冻结。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3 px-5 py-3 sm:grid-cols-2">
            <label className="text-label-13 block">
              州码
              <Input
                className="mt-1"
                value={ruleForm.stateCode}
                placeholder="US-CA"
                onChange={(e) => setRuleForm({ ...ruleForm, stateCode: e.target.value.toUpperCase() })}
              />
            </label>
            <label className="text-label-13 block">
              {ruleEditing ? '新版本生效起始日' : '生效起始日'}
              <Input
                className="mt-1"
                type="date"
                value={ruleForm.effectiveFrom}
                onChange={(e) => setRuleForm({ ...ruleForm, effectiveFrom: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              生效终止日（空 = 当前这版）
              <Input
                className="mt-1"
                type="date"
                value={ruleForm.effectiveTo}
                onChange={(e) => setRuleForm({ ...ruleForm, effectiveTo: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              州基准税率（小数率，0.0725 = 7.25%）
              <Input
                className="mt-1"
                value={ruleForm.baseRate}
                placeholder="0.0725"
                onChange={(e) => setRuleForm({ ...ruleForm, baseRate: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              州级阈值
              <Input
                className="mt-1"
                value={ruleForm.thresholdAmount}
                onChange={(e) => setRuleForm({ ...ruleForm, thresholdAmount: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              免征额
              <Input
                className="mt-1"
                value={ruleForm.exemptionAmount}
                onChange={(e) => setRuleForm({ ...ruleForm, exemptionAmount: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              申报周期
              <select
                className="border-control-border bg-card text-copy-14 mt-1 h-9 w-full rounded-md border px-3"
                value={ruleForm.filingCycle}
                onChange={(e) => setRuleForm({ ...ruleForm, filingCycle: e.target.value })}
              >
                {FILING_CYCLES.filter((c) => c.value).map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-label-13 flex items-center gap-2">
              <input
                type="checkbox"
                checked={ruleForm.marketplaceFacilitator}
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, marketplaceFacilitator: e.target.checked })
                }
              />
              该州实施 Marketplace Facilitator
            </label>
            <label className="text-label-13 block sm:col-span-2">
              备注
              <Input
                className="mt-1"
                value={ruleForm.note}
                onChange={(e) => setRuleForm({ ...ruleForm, note: e.target.value })}
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setRuleOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitRule()} disabled={busy}>
              {ruleEditing ? '发新版本' : '录入'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 弹窗：登记所在州 ---- */}
      <Modal open={shopStateOpen} onOpenChange={setShopStateOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记店铺所在州</ModalTitle>
            <ModalDescription>
              这条记录决定该店的销售额归集到哪个州 —— 没有它，应缴**判不了**。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3 px-5 py-3 sm:grid-cols-2">
            <label className="text-label-13 block">
              店铺
              <select
                className="border-control-border bg-card text-copy-14 mt-1 h-9 w-full rounded-md border px-3"
                value={shopStateForm.shopId}
                onChange={(e) => setShopStateForm({ ...shopStateForm, shopId: e.target.value })}
              >
                <option value="">请选择店铺</option>
                {shops.map((shop) => (
                  <option key={shop.id} value={shop.id}>
                    {shop.name}（{shop.code}）
                  </option>
                ))}
              </select>
            </label>
            <label className="text-label-13 block">
              州码
              <Input
                className="mt-1"
                value={shopStateForm.stateCode}
                placeholder="US-CA"
                onChange={(e) =>
                  setShopStateForm({ ...shopStateForm, stateCode: e.target.value.toUpperCase() })
                }
              />
            </label>
            <label className="text-label-13 block">
              联系类型
              <select
                className="border-control-border bg-card text-copy-14 mt-1 h-9 w-full rounded-md border px-3"
                value={shopStateForm.nexusType}
                onChange={(e) => setShopStateForm({ ...shopStateForm, nexusType: e.target.value })}
              >
                {NEXUS_TYPES.filter((c) => c.value).map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-label-13 block">
              销售税登记号
              <Input
                className="mt-1"
                value={shopStateForm.registrationNo}
                onChange={(e) =>
                  setShopStateForm({ ...shopStateForm, registrationNo: e.target.value })
                }
              />
            </label>
            <label className="text-label-13 block">
              登记生效日
              <Input
                className="mt-1"
                type="date"
                value={shopStateForm.registeredFrom}
                onChange={(e) =>
                  setShopStateForm({ ...shopStateForm, registeredFrom: e.target.value })
                }
              />
            </label>
            <label className="text-label-13 flex items-center gap-2">
              <input
                type="checkbox"
                checked={shopStateForm.registered}
                onChange={(e) => setShopStateForm({ ...shopStateForm, registered: e.target.checked })}
              />
              已登记销售税账号
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setShopStateOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitShopState()} disabled={busy}>
              登记
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 弹窗：代扣代缴说明 ---- */}
      <Modal open={scopeOpen} onOpenChange={setScopeOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记代扣代缴说明</ModalTitle>
            <ModalDescription>逐（州 × 平台）：平台代不代收、汇给谁、要不要单独报表。</ModalDescription>
          </ModalHeader>
          <div className="grid gap-3 px-5 py-3 sm:grid-cols-2">
            <label className="text-label-13 block">
              州码
              <Input
                className="mt-1"
                value={scopeForm.stateCode}
                placeholder="US-CA"
                onChange={(e) => setScopeForm({ ...scopeForm, stateCode: e.target.value.toUpperCase() })}
              />
            </label>
            <label className="text-label-13 block">
              平台编码
              <Input
                className="mt-1"
                value={scopeForm.platformCode}
                placeholder="AMAZON"
                onChange={(e) => setScopeForm({ ...scopeForm, platformCode: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              汇缴对象
              <Input
                className="mt-1"
                value={scopeForm.remitTo}
                onChange={(e) => setScopeForm({ ...scopeForm, remitTo: e.target.value })}
              />
            </label>
            <label className="text-label-13 flex items-center gap-2">
              <input
                type="checkbox"
                checked={scopeForm.facilitatorEnabled}
                onChange={(e) => setScopeForm({ ...scopeForm, facilitatorEnabled: e.target.checked })}
              />
              该平台在该州代扣代缴
            </label>
            <label className="text-label-13 flex items-center gap-2">
              <input
                type="checkbox"
                checked={scopeForm.reportRequired}
                onChange={(e) => setScopeForm({ ...scopeForm, reportRequired: e.target.checked })}
              />
              需要单独提交代扣代缴报表
            </label>
            <label className="text-label-13 block sm:col-span-2">
              说明
              <Input
                className="mt-1"
                value={scopeForm.note}
                onChange={(e) => setScopeForm({ ...scopeForm, note: e.target.value })}
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setScopeOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitScope()} disabled={busy}>
              登记
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 弹窗：上传报表 ---- */}
      <Modal open={importOpen} onOpenChange={setImportOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>上传平台代扣代缴报表</ModalTitle>
            <ModalDescription>
              粘贴平台导出的原文 → 服务端**真解析** → 逐行落库。文件级错误整份失败，行级错误只跳过那一行并写明行号。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3 px-5 py-3 sm:grid-cols-2">
            <label className="text-label-13 block">
              平台编码
              <Input
                className="mt-1"
                value={importForm.platformCode}
                placeholder="AMAZON"
                onChange={(e) => setImportForm({ ...importForm, platformCode: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              报表身份（file_key）
              <Input
                className="mt-1"
                value={importForm.fileKey}
                placeholder="amz-2026-02"
                onChange={(e) => setImportForm({ ...importForm, fileKey: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              文件名
              <Input
                className="mt-1"
                value={importForm.fileName}
                onChange={(e) => setImportForm({ ...importForm, fileName: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              文件类型
              <select
                className="border-control-border bg-card text-copy-14 mt-1 h-9 w-full rounded-md border px-3"
                value={importForm.contentType}
                onChange={(e) =>
                  setImportForm({ ...importForm, contentType: e.target.value as 'CSV' | 'JSON' })
                }
              >
                <option value="CSV">CSV</option>
                <option value="JSON">JSON</option>
              </select>
            </label>
            <label className="text-label-13 block">
              申报期间起
              <Input
                className="mt-1"
                type="date"
                value={importForm.periodStart}
                onChange={(e) => setImportForm({ ...importForm, periodStart: e.target.value })}
              />
            </label>
            <label className="text-label-13 block">
              申报期间止
              <Input
                className="mt-1"
                type="date"
                value={importForm.periodEnd}
                onChange={(e) => setImportForm({ ...importForm, periodEnd: e.target.value })}
              />
            </label>
            <label className="text-label-13 block sm:col-span-2">
              报表原文（CSV 表头需含 state_code / tax_collected / currency）
              <textarea
                className="border-control-border bg-card text-copy-13 mt-1 h-32 w-full rounded-md border p-2 font-mono"
                value={importForm.content}
                placeholder={
                  'state_code,tax_collected,currency\nUS-CA,700,USD\nUS-NV,130,USD\n'
                }
                onChange={(e) => setImportForm({ ...importForm, content: e.target.value })}
              />
            </label>
          </div>
          {importResult && (
            <div className="px-5 pb-3">
              <Notice
                tone={importResult.skipped > 0 ? 'warning' : 'success'}
                title={`读到 ${importResult.scanned} 条，入库 ${importResult.imported} 条，跳过 ${importResult.skipped} 条`}
              >
                {importResult.warnings.length > 0 && (
                  <ul className="mt-1 list-disc space-y-1 pl-4">
                    {importResult.warnings.map((warning) => (
                      <li key={warning}>{warning}</li>
                    ))}
                  </ul>
                )}
              </Notice>
            </div>
          )}
          <ModalFooter>
            <Button variant="ghost" onClick={() => setImportOpen(false)}>
              关闭
            </Button>
            <Button onClick={() => void submitImport()} disabled={busy}>
              上传并解析
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 弹窗：报表明细 ---- */}
      <Modal
        open={reportDetail !== null}
        onOpenChange={(open) => {
          if (!open) setReportDetail(null);
        }}
      >
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              {reportDetail ? `${reportDetail.report.fileKey} · 第 ${reportDetail.report.parseVersion} 版` : ''}
            </ModalTitle>
            <ModalDescription>
              原文一字不改地留着（指纹 {reportDetail?.report.contentHash?.slice(0, 12)}…）；下面是逐行解析结果。
            </ModalDescription>
          </ModalHeader>
          <div className="max-h-80 overflow-auto px-5 py-3">
            {reportDetail && reportDetail.lines.length > 0 ? (
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-2 py-1 font-medium">行号</th>
                    <th className="px-2 py-1 font-medium">州</th>
                    <th className="px-2 py-1 font-medium">平台</th>
                    <th className="px-2 py-1 font-medium">应税销售额</th>
                    <th className="px-2 py-1 font-medium">已代缴</th>
                    <th className="px-2 py-1 font-medium">笔数</th>
                    <th className="px-2 py-1 font-medium">币种</th>
                  </tr>
                </thead>
                <tbody>
                  {reportDetail.lines.map((line) => (
                    <tr key={line.id} className="border-border border-b last:border-0">
                      <td className="px-2 py-1">{line.sourceLineNo}</td>
                      <td className="px-2 py-1">{line.stateName}</td>
                      <td className="px-2 py-1">{line.platformCode}</td>
                      <td className="px-2 py-1">{formatAmount(line.taxableAmount)}</td>
                      <td className="px-2 py-1">{formatAmount(line.taxCollected)}</td>
                      <td className="px-2 py-1">{line.orderCount}</td>
                      <td className="px-2 py-1">{line.currency}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-muted-foreground text-copy-13">这份报表没有可用行。</p>
            )}
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setReportDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
