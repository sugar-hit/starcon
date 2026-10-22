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
  GAP_TYPES,
  SEVERITIES,
  SUBJECT_TYPES,
  VAT_STATUSES,
  createRegistration,
  coverageStateLabel,
  effectiveStatusLabel,
  effectiveStatusTone,
  emptyState,
  emptyStateKind,
  fetchCapabilities,
  fetchCoverage,
  fetchGapSummary,
  fetchGaps,
  fetchJurisdictions,
  fetchRegistrations,
  fetchShops,
  filingCycleLabel,
  gapTypeLabel,
  severityLabel,
  severityTone,
  subjectText,
  updateRegistration,
  vatStatusLabel,
  type GapFilters,
  type TaxCoverageList,
  type TaxGapPage,
  type TaxGapSummary,
  type TaxJurisdictionList,
  type TaxRegistrationCapabilities,
  type TaxRegistrationInput,
  type TaxRegistrationPage,
  type TaxRegistrationView,
  type TaxShopList,
} from './tax-registration-model';

/**
 * 16.3.1.1 税号与辖区 工作区。
 *
 * 蓝图：「维护各店铺/法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、
 * 注册有效期与申报周期。输入：税号录入；辖区选择；有效期。输出：税务登记档案 + 缺失项提示。
 * 关系：上游 = 17 法人、2 店铺；下游 = 16.3.2.1、12.6 税务。
 * **未注册辖区的销售额是合规炸弹**：欧洲没有 VAT 号还在欧盟销售，是稽查重点。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **缺失项排在最上面**：这是本模块存在的理由 —— 未注册辖区的销售额是合规炸弹。
 *    每一条都带类型、严重度、人话说明与整改建议（后端算的，前端原样显示）。
 * 2. **「未注册」与「缺号」分开列**：两者是两件事、两种整改动作，合并成「不合规」就没法干活了。
 * 3. **主体读不到就显示「读不到」**：不显示空白，也不显示成「没绑定法人」。
 * 4. **「推不出要求」不等于「都齐了」**：美国各州登记推不出来（没有销售事实），
 *    页面原样转述后端口径，不把「没有缺口」说成「都齐了」。
 * 5. **辖区从码表选，不自由输入**：值域钉死在 DB 层，页面不提供自由文本入口。
 * 6. **三态空态各不相同**：「没有店铺」「筛选无结果」「一条登记都没有」分开说。
 */
const PAGE_SIZE = 10;

interface Props {
  /** 从缺失项跳进来时的定位（真实存在的页面参数）。 */
  focus?: { shopId?: string };
}

interface RegistrationForm {
  subjectType: string;
  subjectId: string;
  jurisdictionCode: string;
  taxNumber: string;
  vatStatus: string;
  isRegistered: boolean;
  vatNumber: string;
  eprRegistrationNo: string;
  filingCycle: string;
  registrationValidFrom: string;
  registrationValidUntil: string;
  status: string;
  note: string;
}

const EMPTY_FORM: RegistrationForm = {
  subjectType: 'SHOP',
  subjectId: '',
  jurisdictionCode: '',
  taxNumber: '',
  vatStatus: 'REGISTERED',
  isRegistered: true,
  vatNumber: '',
  eprRegistrationNo: '',
  filingCycle: '',
  registrationValidFrom: '',
  registrationValidUntil: '',
  status: 'ACTIVE',
  note: '',
};

export function TaxRegistrationWorkspace({ focus }: Props) {
  const [caps, setCaps] = useState<TaxRegistrationCapabilities | null>(null);
  const [jurisdictions, setJurisdictions] = useState<TaxJurisdictionList | null>(null);
  const [shops, setShops] = useState<TaxShopList | null>(null);
  const [gaps, setGaps] = useState<TaxGapPage | null>(null);
  const [summary, setSummary] = useState<TaxGapSummary | null>(null);
  const [coverage, setCoverage] = useState<TaxCoverageList | null>(null);
  const [registrations, setRegistrations] = useState<TaxRegistrationPage | null>(null);

  const [gapType, setGapType] = useState('');
  const [severity, setSeverity] = useState('');
  const [subjectType, setSubjectType] = useState('');
  const [shopId, setShopId] = useState(focus?.shopId ?? '');
  const [gapPage, setGapPage] = useState(0);
  const [regPage, setRegPage] = useState(0);

  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<RegistrationForm>(EMPTY_FORM);
  const [editing, setEditing] = useState<TaxRegistrationView | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const gapFilters: GapFilters = useMemo(
    () => ({ gapType, severity, subjectType, shopId }),
    [gapType, severity, subjectType, shopId],
  );

  const loadGaps = useCallback(
    async (target: number) => {
      const result = await fetchGaps(target, PAGE_SIZE, gapFilters);
      setGaps(result);
      return result;
    },
    [gapFilters],
  );

  const loadRegistrations = useCallback(async (target: number) => {
    const result = await fetchRegistrations(target, PAGE_SIZE);
    setRegistrations(result);
    return result;
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, jurisdictionResult, shopResult, gapResult, summaryResult, coverageResult, regResult] =
        await Promise.all([
          fetchCapabilities(),
          fetchJurisdictions(),
          fetchShops(),
          fetchGaps(gapPage, PAGE_SIZE, gapFilters),
          fetchGapSummary(gapFilters),
          fetchCoverage(),
          fetchRegistrations(regPage),
        ]);
      setCaps(capsResult);
      setJurisdictions(jurisdictionResult);
      setShops(shopResult);
      setGaps(gapResult);
      setSummary(summaryResult);
      setCoverage(coverageResult);
      setRegistrations(regResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [gapPage, regPage, gapFilters]);

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

  const applyFilters = useCallback(
    (next: Partial<{ gapType: string; severity: string; subjectType: string; shopId: string }>) => {
      setGapType(next.gapType ?? gapType);
      setSeverity(next.severity ?? severity);
      setSubjectType(next.subjectType ?? subjectType);
      setShopId(next.shopId ?? shopId);
      setGapPage(0);
    },
    [gapType, severity, subjectType, shopId],
  );

  const openCreate = useCallback(() => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setFormOpen(true);
  }, []);

  const openEdit = useCallback((item: TaxRegistrationView) => {
    setEditing(item);
    setForm({
      subjectType: item.subjectType,
      subjectId: item.subjectId,
      jurisdictionCode: item.jurisdictionCode,
      taxNumber: item.taxNumber,
      vatStatus: item.vatStatus,
      isRegistered: item.isRegistered,
      vatNumber: item.vatNumber,
      eprRegistrationNo: item.eprRegistrationNo,
      filingCycle: item.filingCycle,
      registrationValidFrom: item.registrationValidFrom,
      registrationValidUntil: item.registrationValidUntil,
      status: item.status,
      note: item.note,
    });
    setFormOpen(true);
  }, []);

  const submit = useCallback(
    () =>
      run(async () => {
        if (!form.subjectId || !form.jurisdictionCode) {
          throw new Error('必须选主体与辖区');
        }
        const input: TaxRegistrationInput = {
          subjectType: form.subjectType,
          subjectId: form.subjectId,
          jurisdictionCode: form.jurisdictionCode,
          taxNumber: form.taxNumber.trim(),
          vatStatus: form.vatStatus,
          isRegistered: form.isRegistered,
          vatNumber: form.vatNumber.trim(),
          eprRegistrationNo: form.eprRegistrationNo.trim(),
          filingCycle: form.filingCycle,
          registrationValidFrom: form.registrationValidFrom,
          registrationValidUntil: form.registrationValidUntil,
          status: form.status,
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
        await Promise.all([refresh(), loadRegistrations(regPage)]);
      }),
    [run, form, editing, refresh, loadRegistrations, regPage],
  );

  const gapItems = useMemo(() => gaps?.items ?? [], [gaps]);
  const registrationItems = useMemo(() => registrations?.items ?? [], [registrations]);

  /** 🔴 空态三态：没有店铺 ≠ 筛选无结果 ≠ 一条登记都没有。 */
  const emptyKind = useMemo(
    () =>
      emptyStateKind(
        shops?.total ?? 0,
        registrations?.total ?? 0,
        (registrations?.total ?? 0) > 0,
      ),
    [shops, registrations],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载税号与辖区…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="税号与辖区">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="税号与辖区加载失败"
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
        title="税号与辖区"
        description="维护各店铺 / 法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、注册有效期与申报周期。未注册辖区的销售额是合规炸弹。"
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
          <div className="text-destructive flex items-center gap-2 text-copy-13">
            <TriangleAlert className="size-4" />
            {error}
          </div>
        </SectionCard>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="高严重度缺失"
          value={summary?.highCount ?? 0}
          polarity="negative"
          icon={<ShieldAlert className="size-4" />}
        />
        <StatCard
          label="中严重度缺失"
          value={summary?.mediumCount ?? 0}
          polarity="neutral"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="受影响店铺"
          value={summary?.affectedShopCount ?? 0}
          polarity="negative"
          icon={<TriangleAlert className="size-4" />}
        />
        <StatCard
          label="登记条数"
          value={registrations?.total ?? 0}
          polarity="positive"
          icon={<ShieldCheck className="size-4" />}
        />
      </div>

      {caps && (
        <SectionCard
          title="能力声明"
          description="下面这些是**本模块之外**、当前尚未交付的能力，或读不到的上游。缺了它们，本模块仍然能算出缺失项 —— 但界面上不会拿编造的数据去填那些维度。"
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
          <p className="text-muted-foreground mt-3 text-copy-12">{caps.reportingScopeNote}</p>
        </SectionCard>
      )}

      {/* ---------- 缺失项（本模块存在的理由） ---------- */}
      <SectionCard
        title="缺失项提示"
        description="后端按「店铺启用的站点 ⇒ 应当登记的辖区 ⇒ 到底有没有在册登记」算出来的**真实清单**。每一条都带整改建议。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={gapType}
              onChange={(e) => applyFilters({ gapType: e.target.value })}
            >
              {GAP_TYPES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={severity}
              onChange={(e) => applyFilters({ severity: e.target.value })}
            >
              {SEVERITIES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={subjectType}
              onChange={(e) => applyFilters({ subjectType: e.target.value })}
            >
              {SUBJECT_TYPES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {gapItems.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="size-8" />}
            title={
              (summary?.total ?? 0) === 0
                ? '当前没有缺失项'
                : emptyState(emptyKind).title
            }
            description={
              (summary?.total ?? 0) === 0
                ? '按当前口径算下来，没有该补的登记。**注意**：这不等于「所有辖区都查过了」——'
                  + '推不出要求的辖区（美国的州一级登记）不在这个口径里，能力声明里写明了这条边界。'
                : emptyState(emptyKind).description
            }
          />
        ) : (
          <div className="space-y-2">
            {gapItems.map((gap, index) => (
              <div
                key={`${gap.subjectType}-${gap.subjectId}-${gap.jurisdictionCode}-${gap.gapType}-${index}`}
                className="border-border rounded-md border px-3 py-2"
              >
                <div className="text-copy-13 text-foreground flex flex-wrap items-center gap-2">
                  <Badge tone={severityTone(gap.severity)}>{severityLabel(gap.severity)}</Badge>
                  <span className="font-medium">{gap.gapTypeLabel || gapTypeLabel(gap.gapType)}</span>
                  <span className="text-muted-foreground">
                    {subjectText(
                      gap.subjectType,
                      gap.subjectCode,
                      gap.subjectName,
                      gap.shopId !== '' || gap.subjectCode !== '',
                      '',
                    )}
                  </span>
                  <Badge tone="info">{gap.jurisdictionName || gap.jurisdictionCode}</Badge>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">{gap.message}</div>
                <div className="text-copy-12 text-foreground mt-1">👉 {gap.remediation}</div>
              </div>
            ))}
            <Pager
              page={gaps?.page ?? 0}
              size={PAGE_SIZE}
              total={gaps?.total ?? 0}
              onChange={(target) => {
                setGapPage(target);
                void loadGaps(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 缺失项汇总 ---------- */}
      {summary && summary.gapTypeCounts.length > 0 && (
        <SectionCard title="缺失项汇总" description={summary.note}>
          <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
            {summary.gapTypeCounts.map((count) => (
              <div key={count.gapType} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 flex items-center gap-2">
                  <Badge tone={severityTone(count.severity)}>{severityLabel(count.severity)}</Badge>
                  <span>{count.gapTypeLabel || gapTypeLabel(count.gapType)}</span>
                  <span className="text-foreground ml-auto font-medium">{count.count}</span>
                </div>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      {/* ---------- 辖区覆盖情况 ---------- */}
      <SectionCard title="辖区覆盖情况" description={coverage?.note}>
        {(coverage?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<Globe2 className="size-8" />}
            title="这个租户下还没有店铺"
            description="覆盖情况是**按店铺**算的：店铺启用了哪些站点、那些站点落在哪些辖区。"
          />
        ) : (
          <div className="space-y-2">
            {coverage?.items.map((row) => (
              <div key={row.shopId} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 flex flex-wrap items-center gap-2">
                  <span className="text-foreground">{row.shopName}</span>
                  <span className="text-muted-foreground">{row.shopCode}</span>
                  <Badge tone={row.coverageStatus === 'COMPLETE' ? 'success' : 'warning'}>
                    {row.coverageStatusLabel}
                  </Badge>
                  <span className="text-muted-foreground">
                    应登记 {row.requiredJurisdictionCount} 个辖区 · 已登记{' '}
                    {row.coveredJurisdictionCount} 个
                    {row.gapCount > 0 ? ` · 另有 ${row.gapCount} 项缺失` : ''}
                  </span>
                </div>
                {row.jurisdictions.length > 0 && (
                  <div className="mt-1 flex flex-wrap gap-2">
                    {row.jurisdictions.map((item) => (
                      <Badge key={item.jurisdictionCode} tone={item.covered ? 'success' : 'danger'}>
                        {item.jurisdictionName || item.jurisdictionCode} ·{' '}
                        {coverageStateLabel(item.registrationState)}
                      </Badge>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------- 税务登记档案 ---------- */}
      <SectionCard
        title="税务登记档案"
        description="一个主体在一个辖区**只有一条登记**：重录是改那一条（订正笔误），不是另起一条。登记是事实，撤下走 status=ARCHIVED，不做物理删除。"
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
                  <Badge tone={effectiveStatusTone(item.effectiveStatus)}>
                    {effectiveStatusLabel(item.effectiveStatus)}
                  </Badge>
                  <span className="text-foreground">
                    {subjectText(
                      item.subjectType,
                      item.subjectCode,
                      item.subjectName,
                      item.subjectAvailable,
                      item.subjectNote,
                    )}
                  </span>
                  <Badge tone="info">{item.jurisdictionName || item.jurisdictionCode}</Badge>
                  <Badge tone="neutral">{vatStatusLabel(item.vatStatus)}</Badge>
                  {item.daysToExpiry !== null && (
                    <span className="text-muted-foreground">
                      {item.daysToExpiry < 0
                        ? `已过期 ${Math.abs(item.daysToExpiry)} 天`
                        : `${item.daysToExpiry} 天后到期`}
                    </span>
                  )}
                  <Button variant="ghost" size="sm" onClick={() => openEdit(item)}>
                    编辑
                  </Button>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  税号 {item.taxNumber || '未填'} · VAT 号 {item.vatNumber || '未填'} · EPR{' '}
                  {item.eprRegistrationNo || '未填'} · 申报周期{' '}
                  {filingCycleLabel(item.filingCycle)}
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

      {/* ---------- 登记 / 编辑弹窗 ---------- */}
      <Modal open={formOpen} onOpenChange={(open) => !open && setFormOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{editing ? '改税务登记' : '登记一条税务登记'}</ModalTitle>
            <ModalDescription>
              辖区从**受控码表**里选（值域钉死在 DB 层，不接受自由文本）。
              有效期用带偏移量的 ISO 时间（写错了 8 小时是常事，页面会原样回显实际时刻）。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">主体类型</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.subjectType}
                disabled={editing !== null}
                onChange={(e) => setForm({ ...form, subjectType: e.target.value, subjectId: '' })}
              >
                <option value="SHOP">店铺</option>
                <option value="LEGAL_ENTITY">法人</option>
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">主体</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.subjectId}
                disabled={editing !== null}
                onChange={(e) => setForm({ ...form, subjectId: e.target.value })}
              >
                <option value="">请选择</option>
                {form.subjectType === 'SHOP'
                  ? shops?.items.map((shop) => (
                      <option key={shop.shopId} value={shop.shopId}>
                        {shop.name}（{shop.code}）
                      </option>
                    ))
                  : null}
              </select>
              {form.subjectType === 'LEGAL_ENTITY' && (
                <span className="text-copy-12 text-muted-foreground">
                  法人来自 identity（17）的法人档案，本服务**只读**。法人 ID 由运营从法人档案里取。
                </span>
              )}
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">辖区</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.jurisdictionCode}
                onChange={(e) => setForm({ ...form, jurisdictionCode: e.target.value })}
              >
                <option value="">请选择</option>
                {jurisdictions?.items.map((item) => (
                  <option key={item.code} value={item.code}>
                    {item.name}（{item.code}）
                    {item.vatRequired ? ' · 要登记号' : ''}
                    {item.requiresEpr ? ' · 要 EPR' : ''}
                  </option>
                ))}
              </select>
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">税号</span>
                <Input
                  value={form.taxNumber}
                  onChange={(e) => setForm({ ...form, taxNumber: e.target.value })}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">VAT 号（登记号）</span>
                <Input
                  value={form.vatNumber}
                  onChange={(e) => setForm({ ...form, vatNumber: e.target.value })}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">EPR 注册号</span>
                <Input
                  value={form.eprRegistrationNo}
                  onChange={(e) => setForm({ ...form, eprRegistrationNo: e.target.value })}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">VAT 状态</span>
                <select
                  className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={form.vatStatus}
                  onChange={(e) => setForm({ ...form, vatStatus: e.target.value })}
                >
                  {VAT_STATUSES.filter((item) => item.value !== '').map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">申报周期</span>
                <select
                  className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={form.filingCycle}
                  onChange={(e) => setForm({ ...form, filingCycle: e.target.value })}
                >
                  <option value="">未登记</option>
                  <option value="MONTHLY">按月</option>
                  <option value="QUARTERLY">按季</option>
                  <option value="SEMI_ANNUAL">按半年</option>
                  <option value="ANNUAL">按年</option>
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">状态</span>
                <select
                  className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={form.status}
                  onChange={(e) => setForm({ ...form, status: e.target.value })}
                >
                  <option value="ACTIVE">生效中</option>
                  <option value="ARCHIVED">已撤下</option>
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">注册有效期起</span>
                <Input
                  value={form.registrationValidFrom}
                  onChange={(e) => setForm({ ...form, registrationValidFrom: e.target.value })}
                  placeholder="2026-01-01T00:00:00+08:00"
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">注册有效期止</span>
                <Input
                  value={form.registrationValidUntil}
                  onChange={(e) => setForm({ ...form, registrationValidUntil: e.target.value })}
                  placeholder="2027-12-31T23:59:59+08:00"
                />
              </label>
            </div>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">备注</span>
              <Input
                value={form.note}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
                placeholder="例如：从税务局回执抄下来的"
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setFormOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submit()}>保存</Button>
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
