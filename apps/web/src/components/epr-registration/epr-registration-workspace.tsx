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
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  Globe2,
  Loader2,
  RefreshCw,
  ScrollText,
  ShieldAlert,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  FEE_STATUSES,
  FEE_TYPES,
  FILING_CYCLES,
  PAID_BY,
  REGIME_TYPES,
  VALIDITY_KINDS,
  countryStatusLabel,
  countryStatusTone,
  createExemption,
  createFee,
  createRegistration,
  emptyState,
  emptyStateKind,
  fetchCapabilities,
  fetchCategories,
  fetchComplianceStatus,
  fetchCoverage,
  fetchExemptions,
  fetchFeeSummary,
  fetchFees,
  fetchFilings,
  fetchRegimes,
  fetchRegistrations,
  fetchShops,
  feeStatusLabel,
  filingStatusLabel,
  filingStatusTone,
  paidByLabel,
  publishGate,
  publishGateBatch,
  reasonLabel,
  reasonTone,
  revokeExemption,
  statusTone,
  updateFee,
  updateRegistration,
  type EprCapabilities,
  type EprCategoryList,
  type EprComplianceStatus,
  type EprCoveragePage,
  type EprExemptionPage,
  type EprFeeInput,
  type EprFeeSummary,
  type EprFilingPage,
  type EprPublishGate,
  type EprPublishGateBatch,
  type EprRegimeList,
  type EprRegistrationInput,
  type EprRegistrationPage,
  type EprRegistrationView,
  type EprShopList,
} from './epr-registration-model';

/**
 * 16.3.2.2 EPR/包装法 工作区。
 *
 * 蓝图：「管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、适用国家与品类、
 * 申报周期与关联费用。输入：注册信息；适用规则；申报数据。输出：EPR 档案 + 合规状态 + 费用记录。
 * 关系：上游 = 16.3.1.1；下游 = 12 财务（费用）、4 刊登（是否允许在相关国家销售）。
 * **EPR 未注册的国家应直接限制刊登**，这是避免下架与罚款最有效的做法。」
 *
 * 🔴 页面上的七条纪律：
 *
 * 1. **刊登闸门排在最上面**：这是本模块存在的理由 —— EPR 未注册的国家**真的会**被拦住
 *    （DB 层触发器拦在 4 刊登的两个真实放行口上），不是页面上的一个红字。
 * 2. **「不适用」与「未注册」分开列**：美国不要求 EPR 就是「不适用」，不拦。
 * 3. **「判不了」是第三态**：仍然不放行，但界面要说清「这是判不了，不是已知违规」。
 * 4. **口径一致性**：闸门给出**两份**结论（品类维度 + 国家维度），必须都显示 ——
 *    DB 层强制的是国家维度那份。
 * 5. **适用品类是多对多**：覆盖视图反查「这个国家这个品类有没有覆盖」。
 * 6. **金额按币种分组**：**不同币种绝不直接相加**（EUR + USD 不是钱）。
 * 7. **三态空态各不相同**：「没有店铺」「筛选无结果」「一条登记都没有」分开说。
 */
const PAGE_SIZE = 10;

interface Props {
  /** 从别处跳进来时的定位（真实存在的页面参数）。 */
  focus?: { countryCode?: string };
}

interface RegistrationForm {
  regimeCode: string;
  registrationNo: string;
  status: string;
  validityKind: string;
  validFrom: string;
  validUntil: string;
  filingCycle: string;
  filingStartMonth: string;
  filingEndMonth: string;
  countryCodes: string[];
  categoryCodes: string[];
  note: string;
}

const EMPTY_FORM: RegistrationForm = {
  regimeCode: 'LUCID',
  registrationNo: '',
  status: 'ACTIVE',
  validityKind: 'OPEN_ENDED',
  validFrom: '',
  validUntil: '',
  filingCycle: 'ANNUAL',
  filingStartMonth: '1',
  filingEndMonth: '3',
  countryCodes: [],
  categoryCodes: [],
  note: '',
};

interface FeeForm {
  registrationKey: string;
  feeType: string;
  amount: string;
  currency: string;
  billingCycle: string;
  status: string;
  paidBy: string;
  note: string;
}

const EMPTY_FEE_FORM: FeeForm = {
  registrationKey: '',
  feeType: 'REGISTRATION_FEE',
  amount: '',
  currency: 'EUR',
  billingCycle: 'ANNUAL',
  status: 'RECORDED',
  paidBy: 'SELLER',
  note: '',
};

export function EprRegistrationWorkspace({ focus }: Props) {
  const [caps, setCaps] = useState<EprCapabilities | null>(null);
  const [regimes, setRegimes] = useState<EprRegimeList | null>(null);
  const [categories, setCategories] = useState<EprCategoryList | null>(null);
  const [shops, setShops] = useState<EprShopList | null>(null);
  const [registrations, setRegistrations] = useState<EprRegistrationPage | null>(null);
  const [coverage, setCoverage] = useState<EprCoveragePage | null>(null);
  const [compliance, setCompliance] = useState<EprComplianceStatus | null>(null);
  const [filings, setFilings] = useState<EprFilingPage | null>(null);
  const [fees, setFees] = useState<Awaited<ReturnType<typeof fetchFees>> | null>(null);
  const [feeSummary, setFeeSummary] = useState<EprFeeSummary | null>(null);
  const [exemptions, setExemptions] = useState<EprExemptionPage | null>(null);
  const [gate, setGate] = useState<EprPublishGate | null>(null);
  const [batch, setBatch] = useState<EprPublishGateBatch | null>(null);

  const [gateCountry, setGateCountry] = useState(focus?.countryCode ?? 'DE');
  const [gateCategory, setGateCategory] = useState('');

  const [regimeCode, setRegimeCode] = useState('');
  const [status, setStatus] = useState('');
  const [validityKind, setValidityKind] = useState('');
  const [countryCode, setCountryCode] = useState('');
  const [regPage, setRegPage] = useState(0);
  const [coveragePage, setCoveragePage] = useState(0);
  const [feePage, setFeePage] = useState(0);
  const [feeCurrency, setFeeCurrency] = useState('');

  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<RegistrationForm>(EMPTY_FORM);
  const [editing, setEditing] = useState<EprRegistrationView | null>(null);

  const [feeOpen, setFeeOpen] = useState(false);
  const [feeForm, setFeeForm] = useState<FeeForm>(EMPTY_FEE_FORM);

  const [exemptOpen, setExemptOpen] = useState(false);
  const [exemptCountry, setExemptCountry] = useState('');
  const [exemptRegime, setExemptRegime] = useState('');
  const [exemptReason, setExemptReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadRegistrations = useCallback(
    async (target: number) => {
      const result = await fetchRegistrations(target, PAGE_SIZE, {
        regimeCode: regimeCode || undefined,
        status: status || undefined,
        validityKind: validityKind || undefined,
        countryCode: countryCode || undefined,
      });
      setRegistrations(result);
      return result;
    },
    [regimeCode, status, validityKind, countryCode],
  );

  const loadCoverage = useCallback(
    async (target: number) => {
      const result = await fetchCoverage(target, PAGE_SIZE, {
        countryCode: countryCode || undefined,
        regimeCode: regimeCode || undefined,
      });
      setCoverage(result);
      return result;
    },
    [countryCode, regimeCode],
  );

  const loadFees = useCallback(
    async (target: number) => {
      const result = await fetchFees(target, PAGE_SIZE, { currency: feeCurrency || undefined });
      setFees(result);
      return result;
    },
    [feeCurrency],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [
        capsResult,
        regimeResult,
        categoryResult,
        shopResult,
        regResult,
        coverageResult,
        complianceResult,
        filingResult,
        feeResult,
        summaryResult,
        exemptionResult,
      ] = await Promise.all([
        fetchCapabilities(),
        fetchRegimes(),
        fetchCategories(),
        fetchShops(),
        fetchRegistrations(regPage, PAGE_SIZE, {
          regimeCode: regimeCode || undefined,
          status: status || undefined,
          validityKind: validityKind || undefined,
          countryCode: countryCode || undefined,
        }),
        fetchCoverage(coveragePage, PAGE_SIZE, {
          countryCode: countryCode || undefined,
          regimeCode: regimeCode || undefined,
        }),
        fetchComplianceStatus(),
        fetchFilings(0, PAGE_SIZE),
        fetchFees(feePage, PAGE_SIZE, { currency: feeCurrency || undefined }),
        fetchFeeSummary({ currency: feeCurrency || undefined }),
        fetchExemptions(0, PAGE_SIZE),
      ]);
      setCaps(capsResult);
      setRegimes(regimeResult);
      setCategories(categoryResult);
      setShops(shopResult);
      setRegistrations(regResult);
      setCoverage(coverageResult);
      setCompliance(complianceResult);
      setFilings(filingResult);
      setFees(feeResult);
      setFeeSummary(summaryResult);
      setExemptions(exemptionResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [regPage, coveragePage, feePage, regimeCode, status, validityKind, countryCode, feeCurrency]);

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

  /** 🔴 跑一次闸门 —— **结论是后端算的**，前端不重算。 */
  const runGate = useCallback(
    () =>
      run(async () => {
        const result = await publishGate({
          countryCode: gateCountry.trim().toUpperCase(),
          categoryCode: gateCategory.trim().toUpperCase(),
          shopId: '',
        });
        setGate(result);
      }),
    [run, gateCountry, gateCategory],
  );

  /** 🔴 批量判定：把这家店在**所有需要 EPR 的国家**一起问一遍。 */
  const runBatchGate = useCallback(
    () =>
      run(async () => {
        const countries = [
          ...new Set((shops?.items ?? []).flatMap((shop) => shop.eprCountryCodes)),
        ];
        if (countries.length === 0) {
          throw new Error('这个租户下还没有店铺在需要 EPR 的国家销售');
        }
        const result = await publishGateBatch(
          countries.map((code) => ({ countryCode: code, categoryCode: '', shopId: '' })),
        );
        setBatch(result);
      }),
    [run, shops],
  );

  const openCreate = useCallback(() => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setFormOpen(true);
  }, []);
  const openEdit = useCallback((item: EprRegistrationView) => {
    setEditing(item);
    setForm({
      regimeCode: item.regimeCode,
      registrationNo: item.registrationNo,
      status: item.status,
      validityKind: item.validityKind,
      validFrom: item.validFrom,
      validUntil: item.validUntil,
      filingCycle: item.filingCycle,
      filingStartMonth: item.filingStartMonth === null ? '' : String(item.filingStartMonth),
      filingEndMonth: item.filingEndMonth === null ? '' : String(item.filingEndMonth),
      countryCodes: [...item.countryCodes],
      categoryCodes: [...item.categoryCodes],
      note: item.note,
    });
    setFormOpen(true);
  }, []);

  const submitRegistration = useCallback(
    () =>
      run(async () => {
        if (!form.registrationNo.trim()) throw new Error('注册号不能为空');
        if (form.countryCodes.length === 0) throw new Error('至少要选一个适用国家');
        if (form.categoryCodes.length === 0) throw new Error('至少要选一个适用品类');
        const startMonth = form.filingStartMonth.trim() ? Number(form.filingStartMonth) : null;
        const endMonth = form.filingEndMonth.trim() ? Number(form.filingEndMonth) : null;
        const input: EprRegistrationInput = {
          regimeCode: form.regimeCode,
          registrationNo: form.registrationNo.trim(),
          status: form.status,
          validityKind: form.validityKind,
          validFrom: form.validFrom.trim(),
          validUntil: form.validUntil.trim(),
          filingCycle: form.filingCycle,
          filingStartMonth: startMonth,
          filingEndMonth: endMonth,
          effectiveFrom: '',
          countryCodes: form.countryCodes,
          categoryCodes: form.categoryCodes,
          note: form.note.trim(),
        };
        if (editing) {
          await updateRegistration(editing.id, input);
        } else {
          await createRegistration(input);
        }
        setFormOpen(false);
        setEditing(null);
        setForm(EMPTY_FORM);
        await refresh();
      }),
    [run, form, editing, refresh],
  );

  const submitFee = useCallback(
    () =>
      run(async () => {
        if (!feeForm.registrationKey) throw new Error('必须选一份登记');
        if (!feeForm.amount.trim()) throw new Error('金额不能为空');
        if (!feeForm.currency.trim()) throw new Error('币种不能为空');
        const input: EprFeeInput = {
          registrationKey: feeForm.registrationKey,
          feeType: feeForm.feeType,
          amount: feeForm.amount.trim(),
          currency: feeForm.currency.trim().toUpperCase(),
          billingCycle: feeForm.billingCycle,
          periodStart: '',
          periodEnd: '',
          status: feeForm.status,
          paidBy: feeForm.paidBy,
          note: feeForm.note.trim(),
        };
        await createFee(input);
        setFeeOpen(false);
        setFeeForm(EMPTY_FEE_FORM);
        await refresh();
      }),
    [run, feeForm, refresh],
  );

  const submitExemption = useCallback(
    () =>
      run(async () => {
        if (!exemptCountry.trim()) throw new Error('必须选国家');
        if (!exemptReason.trim())
          throw new Error('理由必填 —— 没有理由的「不适用」就是漏登记的遮羞布');
        await createExemption({
          countryCode: exemptCountry.trim().toUpperCase(),
          regimeCode: exemptRegime,
          reason: exemptReason.trim(),
          effectiveFrom: '',
          note: '',
        });
        setExemptOpen(false);
        setExemptReason('');
        await refresh();
      }),
    [run, exemptCountry, exemptRegime, exemptReason, refresh],
  );

  const registrationItems = useMemo(() => registrations?.items ?? [], [registrations]);
  const feeItems = useMemo(() => fees?.items ?? [], [fees]);

  /** 🔴 空态三态：没有店铺 ≠ 筛选无结果 ≠ 一条登记都没有。 */
  const emptyKind = useMemo(
    () =>
      emptyStateKind(shops?.total ?? 0, registrations?.total ?? 0, (registrations?.total ?? 0) > 0),
    [shops, registrations],
  );

  /** 制度码表里出现过的国家（用于国家下拉）。 */
  const knownCountries = useMemo(() => {
    const map = new Map<string, string>();
    for (const regime of regimes?.items ?? []) {
      for (const country of regime.countries) {
        map.set(country.countryCode, country.countryName);
      }
    }
    return [...map.entries()].sort((left, right) => left[1].localeCompare(right[1]));
  }, [regimes]);

  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center justify-center gap-2 py-16">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载 EPR / 包装法…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="EPR / 包装法">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="EPR / 包装法加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="EPR / 包装法"
        description="管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、适用国家与品类、申报周期与关联费用。EPR 未注册的国家会直接限制刊登。"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="outline" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
            <Button onClick={openCreate}>
              <ScrollText className="mr-2 size-4" />
              登记一条
            </Button>
          </div>
        }
      />

      {error && (
        <SectionCard>
          <div className="text-destructive text-copy-13 flex items-center gap-2">
            <TriangleAlert className="size-4" />
            {error}
          </div>
        </SectionCard>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="未覆盖国家"
          value={compliance?.nonCompliantCount ?? 0}
          polarity="negative"
          icon={<ShieldAlert className="size-4" />}
        />
        <StatCard
          label="判不了的国家"
          value={compliance?.indeterminateCount ?? 0}
          polarity="neutral"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="EPR 登记条数"
          value={registrations?.total ?? 0}
          polarity="positive"
          icon={<ShieldCheck className="size-4" />}
        />
        <StatCard
          label="在申报期内"
          value={filings?.inPeriodCount ?? 0}
          polarity="neutral"
          icon={<ScrollText className="size-4" />}
        />
      </div>

      {caps && (
        <SectionCard
          title="能力声明"
          description="下面这些是**本模块之外**、当前尚未交付的能力，或读不到的上游。缺了它们，本模块仍然能算 EPR 义务 —— 但界面上不会拿编造的数据去填那些维度。"
        >
          <ul className="space-y-2">
            {caps.gaps.map((gap) => (
              <li key={gap.moduleId} className="text-copy-13 flex items-start gap-2">
                <Badge tone="warning">{gap.moduleId}</Badge>
                <span className="text-foreground">{gap.moduleName}</span>
                <span className="text-muted-foreground">{gap.impact}</span>
              </li>
            ))}
          </ul>
          {caps.upstreams.length > 0 && (
            <ul className="mt-3 space-y-2">
              {caps.upstreams.map((upstream) => (
                <li key={upstream.moduleId} className="text-copy-13 flex items-start gap-2">
                  <Badge tone={upstream.available ? 'success' : 'danger'}>
                    {upstream.available ? '读得到' : '读不到'}
                  </Badge>
                  <span className="text-foreground">{upstream.moduleName}</span>
                  <span className="text-muted-foreground">{upstream.impact}</span>
                </li>
              ))}
            </ul>
          )}
          <p className="text-muted-foreground text-copy-12 mt-3">{caps.enforcementNote}</p>
        </SectionCard>
      )}

      {/* ---------- 刊登闸门（本模块存在的理由） ---------- */}
      <SectionCard
        title="刊登闸门"
        description="🔴 服务端真算的结论：EPR 未注册的国家**真的会**被拦住（DB 层触发器拦在 4 刊登的两个真实放行口上）。前端显示不是防线。"
      >
        <div className="mb-3 flex flex-wrap items-end gap-2">
          <label className="block space-y-1">
            <span className="text-copy-13 text-muted-foreground">目标国家</span>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={gateCountry}
              onChange={(e) => setGateCountry(e.target.value)}
            >
              {knownCountries.map(([code, name]) => (
                <option key={code} value={code}>
                  {name}（{code}）
                </option>
              ))}
              {!knownCountries.some(([code]) => code === gateCountry) && (
                <option value={gateCountry}>{gateCountry}</option>
              )}
            </select>
          </label>
          <label className="block space-y-1">
            <span className="text-copy-13 text-muted-foreground">品类（可空 = 按国家维度判）</span>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={gateCategory}
              onChange={(e) => setGateCategory(e.target.value)}
            >
              <option value="">按国家维度（与 DB 层一致）</option>
              {(categories?.items ?? []).map((item) => (
                <option key={item.code} value={item.code}>
                  {item.name}（{item.code}）
                </option>
              ))}
            </select>
          </label>
          <Button onClick={() => void runGate()}>判定</Button>
          <Button variant="secondary" onClick={() => void runBatchGate()}>
            批量判定所有需要 EPR 的国家
          </Button>
        </div>

        {batch && (
          <div className="border-border mb-3 rounded-md border px-3 py-2">
            <div className="text-copy-13 flex flex-wrap items-center gap-2">
              <Badge tone={batch.allowed ? 'success' : 'danger'}>
                {batch.allowed ? '整批放行' : `整批不放行（${batch.blockedCount} 个国家被阻断）`}
              </Badge>
              <span className="text-muted-foreground">{batch.note}</span>
            </div>
            <div className="mt-1 flex flex-wrap gap-2">
              {batch.gates.map((item) => (
                <Badge key={item.countryCode} tone={item.allowed ? 'success' : 'danger'}>
                  {item.countryName || item.countryCode} · {item.conclusionLabel}
                </Badge>
              ))}
            </div>
          </div>
        )}

        {!gate ? (
          <EmptyState
            icon={<ShieldAlert className="size-8" />}
            title="还没有跑过判定"
            description="选一个目标国家，点「判定」。结论由服务端算出（含逐条原因），前端**不重算**。"
          />
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={gate.allowed ? 'success' : 'danger'}>{gate.conclusionLabel}</Badge>
              <span className="text-copy-13 text-foreground">
                {gate.countryName}（{gate.countryCode}）
                {gate.categoryCode ? ` · ${gate.categoryCode}` : ' · 国家维度'}
              </span>
              <span className="text-copy-12 text-muted-foreground">{gate.evaluatedAt}</span>
            </div>

            {/* 🔴 两份口径都必须显示：DB 层强制的是「国家维度」那份 */}
            {gate.categoryCode !== '' && (
              <div className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 flex flex-wrap items-center gap-2">
                  <Badge tone={gate.countryScope.allowed ? 'success' : 'danger'}>
                    国家维度 · {gate.countryScope.conclusionLabel}
                  </Badge>
                  <span className="text-muted-foreground">
                    这一份才是 DB 层刊登触发器强制的口径 —— 品类维度更细，但不改变实际拦不拦。
                  </span>
                </div>
              </div>
            )}

            {gate.reasons.length === 0 ? (
              <EmptyState
                icon={<ShieldCheck className="size-8" />}
                title="该国不要求任何 EPR 制度"
                description="NOT_APPLICABLE ⇒ 放行。这**不是**「查不到」，是「本来就不适用」。"
              />
            ) : (
              <div className="space-y-2">
                {gate.reasons.map((reason, index) => (
                  <div
                    key={`${reason.regimeCode}-${reason.countryCode}-${index}`}
                    className="border-border rounded-md border px-3 py-2"
                  >
                    <div className="text-copy-13 flex flex-wrap items-center gap-2">
                      <Badge tone={reasonTone(reason.reasonCode)}>
                        {reason.reasonLabel || reasonLabel(reason.reasonCode)}
                      </Badge>
                      <span className="text-foreground font-medium">{reason.regimeName}</span>
                      <Badge tone="info">{reason.countryName || reason.countryCode}</Badge>
                      {reason.categoryCode !== '' && (
                        <Badge tone="neutral">{reason.categoryCode}</Badge>
                      )}
                    </div>
                    <div className="text-copy-12 text-muted-foreground mt-1">{reason.message}</div>
                  </div>
                ))}
              </div>
            )}
            <p className="text-muted-foreground text-copy-12">{gate.note}</p>
          </div>
        )}
      </SectionCard>

      {/* ---------- 按国家的合规状态 ---------- */}
      <SectionCard title="按国家的合规状态" description={compliance?.note}>
        {(compliance?.countries.length ?? 0) === 0 ? (
          <EmptyState
            icon={<Globe2 className="size-8" />}
            title="这个租户下还没有店铺"
            description="合规状态是**按店铺启用的站点国家**算出来的：先在「渠道与店铺」里建好店铺并启用站点。"
          />
        ) : (
          <div className="space-y-2">
            {compliance?.countries.map((row) => (
              <div key={row.countryCode} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 flex flex-wrap items-center gap-2">
                  <Badge tone={countryStatusTone(row.status)}>
                    {row.statusLabel || countryStatusLabel(row.status)}
                  </Badge>
                  <span className="text-foreground font-medium">
                    {row.countryName}（{row.countryCode}）
                  </span>
                  <span className="text-muted-foreground">
                    要求 {row.applicableRegimeCount} 条制度 · 已覆盖 {row.coveredRegimeCount} 条 ·
                    未覆盖 {row.blockedRegimeCount} 条
                  </span>
                </div>
                {row.reasons.length > 0 && (
                  <div className="mt-1 flex flex-wrap gap-2">
                    {row.reasons.map((reason, index) => (
                      <Badge
                        key={`${reason.regimeCode}-${index}`}
                        tone={reasonTone(reason.reasonCode)}
                      >
                        {reason.regimeName} · {reason.reasonLabel || reasonLabel(reason.reasonCode)}
                      </Badge>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------- 覆盖反查（国家 × 品类） ---------- */}
      <SectionCard
        title="覆盖情况（这个国家这个品类有没有覆盖）"
        description={coverage?.note}
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={countryCode}
              onChange={(e) => {
                setCountryCode(e.target.value);
                setCoveragePage(0);
              }}
            >
              <option value="">全部国家</option>
              {knownCountries.map(([code, name]) => (
                <option key={code} value={code}>
                  {name}（{code}）
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={regimeCode}
              onChange={(e) => {
                setRegimeCode(e.target.value);
                setCoveragePage(0);
              }}
            >
              {REGIME_TYPES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {(coverage?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<Globe2 className="size-8" />}
            title="没有匹配的覆盖记录"
            description="覆盖情况是**反查**出来的：制度 × 适用国家 × 适用品类。先录一条 EPR 登记，或放宽筛选。"
          />
        ) : (
          <div className="space-y-2">
            {coverage?.items.map((row, index) => (
              <div
                key={`${row.countryCode}-${row.regimeCode}-${index}`}
                className="border-border rounded-md border px-3 py-2"
              >
                <div className="text-copy-13 flex flex-wrap items-center gap-2">
                  <Badge tone={reasonTone(row.reasonCode)}>{row.reasonLabel}</Badge>
                  <span className="text-foreground font-medium">{row.regimeName}</span>
                  <Badge tone="info">{row.countryName || row.countryCode}</Badge>
                  {row.registrationNo !== '' && (
                    <span className="text-muted-foreground">登记号 {row.registrationNo}</span>
                  )}
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">{row.message}</div>
                {row.categoryCodes.length > 0 && (
                  <div className="mt-1 flex flex-wrap gap-2">
                    {row.categoryCodes.map((code) => (
                      <Badge key={code} tone="neutral">
                        {code}
                      </Badge>
                    ))}
                  </div>
                )}
              </div>
            ))}
            <Pager
              page={coverage?.page ?? 0}
              size={PAGE_SIZE}
              total={coverage?.total ?? 0}
              onChange={(target) => {
                setCoveragePage(target);
                void loadCoverage(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- EPR 档案 ---------- */}
      <SectionCard
        title="EPR 登记档案"
        description="一条档案 = **制度 × 适用国家 × 适用品类**。改一条登记 = 关掉当前版本 + 插入新版本（**不回填**），历史版本留得住。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={regimeCode}
              onChange={(e) => {
                setRegimeCode(e.target.value);
                setRegPage(0);
              }}
            >
              {REGIME_TYPES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setRegPage(0);
              }}
            >
              <option value="">全部状态</option>
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={validityKind}
              onChange={(e) => {
                setValidityKind(e.target.value);
                setRegPage(0);
              }}
            >
              {VALIDITY_KINDS.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {registrationItems.length === 0 ? (
          <EmptyState
            icon={<ScrollText className="size-8" />}
            title={emptyState(emptyKind).title}
            description={emptyState(emptyKind).description}
            action={<Button onClick={openCreate}>登记一条</Button>}
          />
        ) : (
          <div className="space-y-2">
            {registrationItems.map((item) => (
              <div key={item.id} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 flex flex-wrap items-center gap-2">
                  <Badge tone={statusTone(item.status)}>
                    {item.status === 'ACTIVE' ? '启用' : '停用'}
                  </Badge>
                  <span className="text-foreground font-medium">{item.regimeName}</span>
                  <span className="text-muted-foreground">{item.registrationNo}</span>
                  <Badge tone="info">v{item.versionNo}</Badge>
                  {item.daysToExpiry !== null && (
                    <span className="text-muted-foreground">
                      {item.daysToExpiry < 0
                        ? `已过期 ${Math.abs(item.daysToExpiry)} 天`
                        : `${item.daysToExpiry} 天后到期`}
                    </span>
                  )}
                  <Button variant="ghost" size="sm" onClick={() => openEdit(item)}>
                    编辑（生成新版本）
                  </Button>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  适用国家 {item.countryCodes.join('、') || '未填'} · 适用品类{' '}
                  {item.categoryCodes.join('、') || '未填'} · 申报周期 {item.filingCycleLabel} ·{' '}
                  {item.filingStatusLabel}
                  {item.filingPeriodLabel ? `（${item.filingPeriodLabel}）` : ''}
                  {item.daysToNextFiling !== null && item.daysToNextFiling > 0
                    ? ` · 距下一期 ${item.daysToNextFiling} 天`
                    : ''}
                </div>
              </div>
            ))}
            <Pager
              page={registrations?.page ?? 0}
              size={PAGE_SIZE}
              total={registrations?.total ?? 0}
              onChange={(target) => {
                setRegPage(target);
                void loadRegistrations(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 申报期 ---------- */}
      <SectionCard title="申报期" description={filings?.note}>
        {(filings?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<ScrollText className="size-8" />}
            title="还没有可申报的登记"
            description="申报期由**周期 + 起止月份**算出。没记起止月份是**判不了**，不是「不在申报期内」。"
          />
        ) : (
          <div className="space-y-2">
            {filings?.items.map((row) => (
              <div key={row.registrationId} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 flex flex-wrap items-center gap-2">
                  <Badge tone={filingStatusTone(row.filingStatus)}>{row.filingStatusLabel}</Badge>
                  <span className="text-foreground font-medium">{row.regimeName}</span>
                  <span className="text-muted-foreground">{row.registrationNo}</span>
                  <span className="text-muted-foreground">{row.filingCycleLabel}</span>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  {row.filingPeriodLabel || '没记申报月份'}
                  {row.daysToNextFiling !== null && row.daysToNextFiling > 0
                    ? ` · 距下一期还有 ${row.daysToNextFiling} 天`
                    : ''}
                </div>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------- 费用 ---------- */}
      <SectionCard
        title="EPR 关联费用"
        description={feeSummary?.note}
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={feeCurrency}
              onChange={(e) => {
                setFeeCurrency(e.target.value);
                setFeePage(0);
              }}
            >
              <option value="">全部币种</option>
              {(feeSummary?.currencies ?? []).map((currency) => (
                <option key={currency} value={currency}>
                  {currency}
                </option>
              ))}
            </select>
            <Button
              variant="secondary"
              onClick={() => {
                setFeeForm(EMPTY_FEE_FORM);
                setFeeOpen(true);
              }}
            >
              记一笔费用
            </Button>
          </div>
        }
      >
        {/* 🔴 汇总按币种分组 —— 不同币种绝不直接相加 */}
        {(feeSummary?.totals.length ?? 0) > 0 && (
          <div className="mb-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
            {feeSummary?.totals.map((item) => (
              <div key={item.currency} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">
                  {item.currency} 合计（{item.count} 笔）
                </div>
                <div className="text-copy-15 text-foreground font-medium">
                  {item.currency} {item.total}
                </div>
              </div>
            ))}
          </div>
        )}
        {feeItems.length === 0 ? (
          <EmptyState
            icon={<ScrollText className="size-8" />}
            title="还没有费用记录"
            description="费用是**本服务自己的费用记录**（谁付的、哪个周期、多少钱、什么币种）—— **不是**财务凭证，12 财务在本仓尚未落地。"
          />
        ) : (
          <div className="space-y-2">
            {feeItems.map((item) => (
              <div key={item.id} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 flex flex-wrap items-center gap-2">
                  <Badge tone="neutral">{item.feeTypeLabel}</Badge>
                  <span className="text-foreground font-medium">
                    {item.currency} {item.amount}
                  </span>
                  <Badge tone="info">{item.billingCycleLabel}</Badge>
                  <span className="text-muted-foreground">
                    {item.statusLabel || feeStatusLabel(item.status)}
                  </span>
                  <span className="text-muted-foreground">
                    {item.paidByLabel || paidByLabel(item.paidBy)}
                  </span>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  {item.regimeName} {item.registrationNo} {item.note ? `· ${item.note}` : ''}
                </div>
              </div>
            ))}
            <Pager
              page={fees?.page ?? 0}
              size={PAGE_SIZE}
              total={fees?.total ?? 0}
              onChange={(target) => {
                setFeePage(target);
                void loadFees(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 不适用声明 ---------- */}
      <SectionCard
        title="不适用声明"
        description={exemptions?.note}
        extra={
          <Button variant="secondary" onClick={() => setExemptOpen(true)}>
            声明不适用
          </Button>
        }
      >
        {(exemptions?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="size-8" />}
            title="还没有不适用声明"
            description="「该制度在本国不适用我的业务」是**租户声明**（不是事实）。理由**必填** —— 没有理由的「不适用」就是漏登记的遮羞布。"
          />
        ) : (
          <div className="space-y-2">
            {exemptions?.items.map((item) => (
              <div key={item.id} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 flex flex-wrap items-center gap-2">
                  <Badge tone={item.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {item.status === 'ACTIVE' ? '生效中' : '已撤销'}
                  </Badge>
                  <span className="text-foreground font-medium">{item.regimeName}</span>
                  <Badge tone="info">{item.countryName || item.countryCode}</Badge>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">理由：{item.reason}</div>
                {item.status === 'ACTIVE' && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      void run(async () => {
                        await revokeExemption(item.id);
                        await refresh();
                      })
                    }
                  >
                    撤销
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------- 登记 / 编辑弹窗 ---------- */}
      <Modal open={formOpen} onOpenChange={(open) => !open && setFormOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{editing ? '改 EPR 登记（生成新版本）' : '登记一条 EPR 登记'}</ModalTitle>
            <ModalDescription>
              制度从**受控码表**里选（值域钉死在 DB 层，不接受自由文本）。国家与品类都是**多对多**：
              一条登记适用多个国家 + 多个品类。改一条 = 关掉当前版本 +
              插入新版本，**生效时刻不许回填**。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">制度</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.regimeCode}
                onChange={(e) => setForm({ ...form, regimeCode: e.target.value })}
              >
                {REGIME_TYPES.filter((item) => item.value !== '').map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">注册号</span>
              <Input
                value={form.registrationNo}
                onChange={(e) => setForm({ ...form, registrationNo: e.target.value })}
                placeholder="例：DE1234567890123"
              />
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">状态</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.status}
                onChange={(e) => setForm({ ...form, status: e.target.value })}
              >
                <option value="ACTIVE">启用</option>
                <option value="DISABLED">停用</option>
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">有效期形态</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.validityKind}
                onChange={(e) => setForm({ ...form, validityKind: e.target.value })}
              >
                {VALIDITY_KINDS.filter((item) => item.value !== '').map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">
                  有效期起（ISO-8601 带偏移量）
                </span>
                <Input
                  value={form.validFrom}
                  onChange={(e) => setForm({ ...form, validFrom: e.target.value })}
                  placeholder="2026-01-01T00:00:00+08:00"
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">
                  有效期止（ISO-8601 带偏移量）
                </span>
                <Input
                  value={form.validUntil}
                  onChange={(e) => setForm({ ...form, validUntil: e.target.value })}
                  placeholder="2027-12-31T23:59:59+08:00"
                />
              </label>
            </div>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">申报周期</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.filingCycle}
                onChange={(e) => setForm({ ...form, filingCycle: e.target.value })}
              >
                {FILING_CYCLES.filter((item) => item.value !== '').map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">申报期起始月（1..12）</span>
                <Input
                  value={form.filingStartMonth}
                  onChange={(e) => setForm({ ...form, filingStartMonth: e.target.value })}
                  placeholder="留空 = 判不了"
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">申报期结束月（1..12）</span>
                <Input
                  value={form.filingEndMonth}
                  onChange={(e) => setForm({ ...form, filingEndMonth: e.target.value })}
                  placeholder="留空 = 判不了"
                />
              </label>
            </div>
            <div className="space-y-1">
              <span className="text-copy-13 text-muted-foreground">适用国家（多选）</span>
              <div className="flex flex-wrap gap-2">
                {knownCountries.map(([code, name]) => (
                  <label key={code} className="text-copy-13 flex items-center gap-1">
                    <input
                      type="checkbox"
                      checked={form.countryCodes.includes(code)}
                      onChange={(e) =>
                        setForm({
                          ...form,
                          countryCodes: e.target.checked
                            ? [...form.countryCodes, code]
                            : form.countryCodes.filter((item) => item !== code),
                        })
                      }
                    />
                    {name}（{code}）
                  </label>
                ))}
              </div>
            </div>
            <div className="space-y-1">
              <span className="text-copy-13 text-muted-foreground">适用品类（多选）</span>
              <div className="flex flex-wrap gap-2">
                {(categories?.items ?? []).map((item) => (
                  <label key={item.code} className="text-copy-13 flex items-center gap-1">
                    <input
                      type="checkbox"
                      checked={form.categoryCodes.includes(item.code)}
                      onChange={(e) =>
                        setForm({
                          ...form,
                          categoryCodes: e.target.checked
                            ? [...form.categoryCodes, item.code]
                            : form.categoryCodes.filter((value) => value !== item.code),
                        })
                      }
                    />
                    {item.name}（{item.code}）
                  </label>
                ))}
              </div>
            </div>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">备注</span>
              <Input
                value={form.note}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setFormOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitRegistration()}>
              {editing ? '保存为新版本' : '登记'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------- 记费用弹窗 ---------- */}
      <Modal open={feeOpen} onOpenChange={(open) => !open && setFeeOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>记一笔 EPR 费用</ModalTitle>
            <ModalDescription>
              金额是**十进制**（禁浮点），**币种必带**。这是本服务自己的费用记录，**不是**财务凭证。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">挂在哪份登记上</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={feeForm.registrationKey}
                onChange={(e) => setFeeForm({ ...feeForm, registrationKey: e.target.value })}
              >
                <option value="">请选择</option>
                {registrationItems.map((item) => (
                  <option key={item.registrationKey} value={item.registrationKey}>
                    {item.regimeName} · {item.registrationNo}
                  </option>
                ))}
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">费用类型</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={feeForm.feeType}
                onChange={(e) => setFeeForm({ ...feeForm, feeType: e.target.value })}
              >
                {FEE_TYPES.filter((item) => item.value !== '').map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">金额（十进制）</span>
                <Input
                  value={feeForm.amount}
                  onChange={(e) => setFeeForm({ ...feeForm, amount: e.target.value })}
                  placeholder="100.50"
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">币种（三个大写字母）</span>
                <Input
                  value={feeForm.currency}
                  onChange={(e) => setFeeForm({ ...feeForm, currency: e.target.value })}
                  placeholder="EUR"
                />
              </label>
            </div>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">计费周期</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={feeForm.billingCycle}
                onChange={(e) => setFeeForm({ ...feeForm, billingCycle: e.target.value })}
              >
                {FILING_CYCLES.filter((item) => item.value !== '').map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">状态</span>
                <select
                  className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={feeForm.status}
                  onChange={(e) => setFeeForm({ ...feeForm, status: e.target.value })}
                >
                  {FEE_STATUSES.filter((item) => item.value !== '').map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">承担方</span>
                <select
                  className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={feeForm.paidBy}
                  onChange={(e) => setFeeForm({ ...feeForm, paidBy: e.target.value })}
                >
                  {PAID_BY.filter((item) => item.value !== '').map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">备注</span>
              <Input
                value={feeForm.note}
                onChange={(e) => setFeeForm({ ...feeForm, note: e.target.value })}
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setFeeOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitFee()}>记一笔</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------- 不适用声明弹窗 ---------- */}
      <Modal open={exemptOpen} onOpenChange={(open) => !open && setExemptOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>声明「本制度在本国不适用我的业务」</ModalTitle>
            <ModalDescription>
              这是**租户声明**，不是事实（事实是「有没有登记」）。理由**必填** ——
              没有理由的「不适用」 就是漏登记的遮羞布。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">国家</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={exemptCountry}
                onChange={(e) => setExemptCountry(e.target.value)}
              >
                <option value="">请选择</option>
                {knownCountries.map(([code, name]) => (
                  <option key={code} value={code}>
                    {name}（{code}）
                  </option>
                ))}
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">制度</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={exemptRegime}
                onChange={(e) => setExemptRegime(e.target.value)}
              >
                {REGIME_TYPES.filter((item) => item.value !== '').map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">理由（必填）</span>
              <Input
                value={exemptReason}
                onChange={(e) => setExemptReason(e.target.value)}
                placeholder="例：我们只卖包装类商品，不销售电子电器"
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setExemptOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitExemption()}>声明</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {busy && (
        <div className="text-copy-12 text-muted-foreground flex items-center gap-2">
          <Loader2 className="size-3 animate-spin" />
          处理中…
        </div>
      )}
    </div>
  );
}

function Pager({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (total === 0) return null;
  return (
    <div className="text-copy-12 text-muted-foreground flex items-center justify-end gap-2">
      <span>
        第 {page + 1} / {pages} 页 · 共 {total} 条
      </span>
      <Button variant="secondary" size="sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        上一页
      </Button>
      <Button
        variant="secondary"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}
