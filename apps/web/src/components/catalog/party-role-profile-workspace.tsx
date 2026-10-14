'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { FileWarning, RefreshCw, Save, Trash2, TriangleAlert } from 'lucide-react';
import type {
  PartyCarrierProfile,
  PartyCarrierProfileInput,
  PartyCustomerProfile,
  PartyCustomerProfileInput,
  PartyRoleProfileOverview,
  PartyRoleProfileRow,
  PartyRoleProfiles,
  PartySupplierProfile,
  PartySupplierProfileInput,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  COOPERATION_HINTS,
  CUSTOMER_LEVEL_HINTS,
  INTERFACE_HINTS,
  PROBE_HINTS,
  ROLE_PROFILE_KINDS,
  SETTLEMENT_HINTS,
  deleteRoleProfile,
  emptyCarrierForm,
  emptyCustomerForm,
  emptyProfileQuery,
  emptySupplierForm,
  fetchCustomerProfile,
  fetchProfileOptions,
  fetchProfileOverview,
  fetchRoleProfiles,
  fetchRoleProfilesOfParty,
  fetchSupplierProfile,
  kindLabel,
  kindOf,
  profileErrorText,
  rowHeadline,
  roleStatusLabel,
  saveCarrierProfile,
  saveCustomerProfile,
  saveSupplierProfile,
  settlementLabel,
  slotState,
  slotStateText,
  toCarrierForm,
  toCarrierInput,
  toCustomerForm,
  toCustomerInput,
  toSupplierForm,
  toSupplierInput,
  supplierFormProblem,
  carrierFormProblem,
  customerFormProblem,
  type CarrierProfileForm,
  type CustomerProfileForm,
  type RoleProfileListQuery,
  type SupplierProfileForm,
} from './party-role-profile-model';

/**
 * 3.6.1.2 角色档案工作区（两个区块）。
 *
 * <ul>
 *   <li><b>档案台账</b> —— 跨主体地问「哪些供应商还没建档案」「哪些风险客户仍然留着额度」。
 *       这是本模块的治理面：主体是谁不固定，所以它是一个集合资源而不是某个主体的属性。
 *   <li><b>三个角色档案面板</b> —— 挂在某个主体的详情里。三个身份各一格，
 *       每格显式区分<b>没指派身份 / 指派了但没建档案 / 已建档案</b>三种状态
 *       —— 那是三种不同的下一步动作，混成一个「暂无」会让运营不知道该做什么。
 * </ul>
 *
 * <p>所有判定与文案都来自后端（`PartyRoleProfileService` 的 warnings 与
 * `PartyRoleProfileRules` 是唯一实现），前端只在提交前少一次无谓的往返。
 */
export function PartyRoleProfileLedger({
  writable,
  initialQuery,
}: {
  writable: boolean;
  initialQuery?: RoleProfileListQuery;
}) {
  const [overview, setOverview] = useState<PartyRoleProfileOverview | null>(null);
  const [rows, setRows] = useState<PartyRoleProfileRow[]>([]);
  const [total, setTotal] = useState(0);
  const [options, setOptions] = useState<{
    levels: { code: string; label: string }[];
    settlementMethods: { code: string; label: string }[];
    interfaceTypes: { code: string; label: string }[];
    serviceCapabilities: { code: string; label: string }[];
  }>({ levels: [], settlementMethods: [], interfaceTypes: [], serviceCapabilities: [] });
  const [query, setQuery] = useState<RoleProfileListQuery>(initialQuery ?? emptyProfileQuery());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ov, page, opts] = await Promise.all([
        fetchProfileOverview(),
        fetchRoleProfiles(query),
        fetchProfileOptions(query.roleType),
      ]);
      setOverview(ov);
      setRows(page.items);
      setTotal(page.total);
      setOptions({
        levels: opts.levels,
        settlementMethods: opts.settlementMethods,
        interfaceTypes: opts.interfaceTypes,
        serviceCapabilities: opts.serviceCapabilities,
      });
    } catch (e) {
      setError(profileErrorText(e));
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = (next: Partial<RoleProfileListQuery>) =>
    setQuery((current) => ({ ...current, ...next, page: 0 }));

  const kind = kindOf(query.roleType);
  const pages = Math.max(1, Math.ceil(total / query.size));

  return (
    <SectionCard
      title="角色档案台账（3.6.1.2）"
      description="按身份看档案覆盖率。缺档案的那些，就是 06 采购 / 12 应收取不到默认值的对手方。"
      extra={
        <Button size="sm" variant="ghost" disabled={loading} onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      }
    >
      {error ? (
        <p className="text-destructive text-sm">加载失败：{error}</p>
      ) : (
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="供应商档案覆盖"
              value={`${overview?.supplierProfileCount ?? 0} / ${overview?.supplierRoleCount ?? 0}`}
            />
            <StatCard
              label="客户档案覆盖"
              value={`${overview?.customerProfileCount ?? 0} / ${overview?.customerRoleCount ?? 0}`}
            />
            <StatCard
              label="承运商档案覆盖"
              value={`${overview?.carrierProfileCount ?? 0} / ${overview?.carrierRoleCount ?? 0}`}
            />
            <StatCard
              label="缺档案的身份"
              value={overview?.missingProfileCount ?? 0}
              icon={<FileWarning className="h-4 w-4" />}
            />
          </div>

          {overview &&
          (overview.riskyCustomerWithCreditCount > 0 || overview.termsWithoutCreditCount > 0) ? (
            <div className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border p-3 text-xs">
              <div className="flex items-center gap-2 font-medium">
                <TriangleAlert className="h-4 w-4" />
                发现绕过服务层写进来的脏数据
              </div>
              <ul className="mt-1 list-disc pl-5">
                {overview.riskyCustomerWithCreditCount > 0 && (
                  <li>
                    风险客户仍留信用额度 {overview.riskyCustomerWithCreditCount} 条 ——{' '}
                    {PROBE_HINTS.riskyCustomerWithCreditCount}
                  </li>
                )}
                {overview.termsWithoutCreditCount > 0 && (
                  <li>
                    有账期但结算方式不是账期结算 {overview.termsWithoutCreditCount} 条 ——{' '}
                    {PROBE_HINTS.termsWithoutCreditCount}
                  </li>
                )}
              </ul>
            </div>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="身份">
              <select
                className={control}
                value={query.roleType}
                onChange={(e) =>
                  patch({ roleType: e.target.value as RoleProfileListQuery['roleType'] })
                }
              >
                {ROLE_PROFILE_KINDS.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="关键词" hint="主体编码 / 名称 / 邮箱 / 电话">
              <input
                className={control}
                value={query.keyword}
                onChange={(e) => patch({ keyword: e.target.value })}
                placeholder="例如 SUP-001"
              />
            </Field>
            {query.roleType === 'SUPPLIER' && (
              <>
                <Field label="合作等级">
                  <select
                    className={control}
                    value={query.level}
                    onChange={(e) => patch({ level: e.target.value })}
                  >
                    <option value="">全部</option>
                    {options.levels.map((item) => (
                      <option key={item.code} value={item.code}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="结算方式">
                  <select
                    className={control}
                    value={query.settlementMethod}
                    onChange={(e) => patch({ settlementMethod: e.target.value })}
                  >
                    <option value="">全部</option>
                    {options.settlementMethods.map((item) => (
                      <option key={item.code} value={item.code}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </Field>
              </>
            )}
            {query.roleType === 'CUSTOMER' && (
              <>
                <Field label="客户分级">
                  <select
                    className={control}
                    value={query.level}
                    onChange={(e) => patch({ level: e.target.value })}
                  >
                    <option value="">全部</option>
                    {options.levels.map((item) => (
                      <option key={item.code} value={item.code}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="默认币种" hint="ISO 4217 三位代码">
                  <input
                    className={control}
                    value={query.defaultCurrency}
                    onChange={(e) => patch({ defaultCurrency: e.target.value.toUpperCase() })}
                    placeholder="USD"
                  />
                </Field>
              </>
            )}
            {query.roleType === 'CARRIER' && (
              <>
                <Field label="接口类型">
                  <select
                    className={control}
                    value={query.level}
                    onChange={(e) => patch({ level: e.target.value })}
                  >
                    <option value="">全部</option>
                    {options.interfaceTypes.map((item) => (
                      <option key={item.code} value={item.code}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="具备能力" hint="筛选「至少具备这一项」的承运商">
                  <select
                    className={control}
                    value={query.capability}
                    onChange={(e) => patch({ capability: e.target.value })}
                  >
                    <option value="">全部</option>
                    {options.serviceCapabilities.map((item) => (
                      <option key={item.code} value={item.code}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </Field>
              </>
            )}
            <Field label="只看缺档案" hint="已指派身份却没建档案的那些">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={query.missingOnly}
                  onChange={(e) => patch({ missingOnly: e.target.checked })}
                />
                只看缺档案
              </label>
            </Field>
            <Field label="含已停用身份" hint="停用的身份当下不会被下游用来取默认值，默认不显示">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={query.includeInactive}
                  onChange={(e) => patch({ includeInactive: e.target.checked })}
                />
                含已停用
              </label>
            </Field>
          </div>

          {rows.length === 0 ? (
            <EmptyState
              icon={<FileWarning className="h-8 w-8" />}
              title={query.missingOnly ? '这个筛选下没有缺档案的身份' : '还没有这个身份的角色档案'}
              description={
                query.missingOnly
                  ? '所有已指派的身份都建了档案。要看全部记录请取消「只看缺档案」。'
                  : '先在往来单位里指派' +
                    kind.label +
                    '身份，再回到这里建它的角色档案 —— 档案只能挂在已指派的身份上。'
              }
            />
          ) : (
            <div className="space-y-2">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2">往来单位</th>
                    <th className="py-2">档案</th>
                    <th className="py-2">分级</th>
                    <th className="py-2">下游默认值</th>
                    <th className="py-2">备注</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.roleId} className="border-border border-t align-top">
                      <td className="py-2">
                        <div className="font-medium">{row.partyName}</div>
                        <div className="text-muted-foreground text-xs">{row.partyCode}</div>
                        <Badge tone={row.roleStatus === 'ACTIVE' ? 'info' : 'neutral'}>
                          {roleStatusLabel(row.roleStatus)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {row.profileFound ? (
                          <Badge tone="success">已建档案</Badge>
                        ) : (
                          <Badge tone="warning">尚未建档案</Badge>
                        )}
                      </td>
                      <td className="py-2">
                        {row.levelLabel ?? <span className="text-muted-foreground">—</span>}
                      </td>
                      <td className="py-2">
                        {rowHeadline(row)}
                        {row.profileFound && row.note && (
                          <div className="text-muted-foreground text-xs">{row.note}</div>
                        )}
                      </td>
                      <td className="py-2 text-xs">
                        {row.profileFound && row.updatedAt ? (
                          row.updatedAt.replace('T', ' ').slice(0, 19)
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="text-muted-foreground flex items-center justify-between text-xs">
                <span>
                  共 {total} 条，第 {query.page + 1} / {pages} 页
                </span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={query.page <= 0 || loading}
                    onClick={() => setQuery((c) => ({ ...c, page: c.page - 1 }))}
                  >
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={query.page + 1 >= pages || loading}
                    onClick={() => setQuery((c) => ({ ...c, page: c.page + 1 }))}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}
          {!writable && (
            <p className="text-muted-foreground text-xs">
              当前身份只有读权限：可以查档案与覆盖率，不能新建或修改。
            </p>
          )}
        </div>
      )}
    </SectionCard>
  );
}

// ================================================================ 单主体的三个档案面板

/**
 * 某个主体的三张角色档案面板。
 *
 * <p>每格显式区分三种状态（未指派身份 / 尚未建档案 / 已建档案），
 * 因为那是三种不同的下一步动作。
 */
export function PartyRoleProfilePanels({
  partyId,
  writable,
  onChanged,
}: {
  partyId: string;
  writable: boolean;
  onChanged?: () => void;
}) {
  const [data, setData] = useState<PartyRoleProfiles | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchRoleProfilesOfParty(partyId));
    } catch (e) {
      setError(profileErrorText(e));
    } finally {
      setLoading(false);
    }
  }, [partyId]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setActionError(null);
      try {
        await work();
        await load();
        onChanged?.();
      } catch (e) {
        setActionError(profileErrorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, onChanged],
  );

  if (loading) {
    return (
      <SectionCard title="角色档案（3.6.1.2）" description="加载中…">
        <p className="text-muted-foreground text-sm">正在读取这个主体的三个身份档案…</p>
      </SectionCard>
    );
  }
  if (error || !data) {
    return (
      <SectionCard title="角色档案（3.6.1.2）">
        <p className="text-destructive text-sm">加载失败：{error ?? '未知错误'}</p>
        <Button className="mt-2" size="sm" variant="outline" onClick={() => void load()}>
          重试
        </Button>
      </SectionCard>
    );
  }

  return (
    <SectionCard
      title="角色档案（3.6.1.2）"
      description={`${data.partyCode} · 账期、MOQ、合作等级、结算方式、客户分级、默认币种、信用额度、接口类型、账号、服务能力`}
      extra={
        <Button size="sm" variant="ghost" disabled={loading} onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      }
    >
      <div className="space-y-4">
        {actionError && (
          <div className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border p-3 text-sm">
            {actionError}
          </div>
        )}
        {data.supplier && (
          <SupplierPanel
            profile={data.supplier}
            writable={writable}
            busy={busy}
            onSave={(input) => run(() => saveSupplierProfile(partyId, input))}
            onDelete={(version) => run(() => deleteRoleProfile(partyId, 'SUPPLIER', version))}
          />
        )}
        {data.customer && (
          <CustomerPanel
            profile={data.customer}
            writable={writable}
            busy={busy}
            onSave={(input) => run(() => saveCustomerProfile(partyId, input))}
            onDelete={(version) => run(() => deleteRoleProfile(partyId, 'CUSTOMER', version))}
          />
        )}
        {data.carrier && (
          <CarrierPanel
            profile={data.carrier}
            writable={writable}
            busy={busy}
            onSave={(input) => run(() => saveCarrierProfile(partyId, input))}
            onDelete={(version) => run(() => deleteRoleProfile(partyId, 'CARRIER', version))}
          />
        )}
        <p className="text-muted-foreground text-xs">{data.note}</p>
      </div>
    </SectionCard>
  );
}

// ================================================================ 三态槽位外壳

function SlotShell({
  roleType,
  roleId,
  profileFound,
  warnings,
  busy,
  writable,
  onOpen,
  onDelete,
  children,
  summary,
}: {
  roleType: string;
  roleId: string | null;
  profileFound: boolean;
  warnings: string[];
  busy: boolean;
  writable: boolean;
  onOpen: () => void;
  onDelete: () => void;
  children: React.ReactNode;
  summary: string;
}) {
  const state = slotState(roleId, profileFound);
  const text = slotStateText(state);
  return (
    <div className="border-border rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">{kindLabel(roleType)}角色档案</span>
          <Badge
            tone={state === 'saved' ? 'success' : state === 'no-profile' ? 'warning' : 'neutral'}
          >
            {text.label}
          </Badge>
        </div>
        {writable && state !== 'not-assigned' && (
          <div className="flex gap-2">
            <Button size="sm" variant="outline" disabled={busy} onClick={onOpen}>
              {state === 'saved' ? '编辑档案' : '建立档案'}
            </Button>
            {state === 'saved' && (
              <Button size="sm" variant="ghost" disabled={busy} onClick={onDelete}>
                <Trash2 className="h-4 w-4" />
                删除档案
              </Button>
            )}
          </div>
        )}
      </div>
      <p className="text-muted-foreground mt-1 text-xs">{text.hint}</p>
      {state === 'saved' && (
        <>
          <p className="mt-2 text-sm">{summary}</p>
          {children}
        </>
      )}
      {warnings.length > 0 && (
        <ul className="text-muted-foreground mt-2 list-disc space-y-0.5 pl-5 text-xs">
          {warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

function SaveBar({
  busy,
  problem,
  onSave,
  onCancel,
}: {
  busy: boolean;
  problem: string | null;
  onSave: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="mt-3 space-y-2">
      {problem && <p className="text-destructive text-xs">{problem}</p>}
      <div className="flex gap-2">
        <Button size="sm" disabled={busy || problem !== null} onClick={onSave}>
          <Save className="h-4 w-4" />
          保存档案
        </Button>
        <Button size="sm" variant="ghost" disabled={busy} onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

// ================================================================ 供应商面板

function SupplierPanel({
  profile,
  writable,
  busy,
  onSave,
  onDelete,
}: {
  profile: PartySupplierProfile;
  writable: boolean;
  busy: boolean;
  onSave: (input: PartySupplierProfileInput) => void;
  onDelete: (version: number) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<SupplierProfileForm>(emptySupplierForm());
  const problem = useMemo(() => supplierFormProblem(form), [form]);

  const open = async () => {
    if (!profile.profileFound) {
      setForm(emptySupplierForm());
      setEditing(true);
      return;
    }
    // 编辑前取一次最新详情：表单里的 version 必须来自库里，不能用列表缓存的旧值。
    try {
      const fresh = await fetchSupplierProfile(profile.partyId);
      setForm(toSupplierForm(fresh));
    } catch {
      setForm(toSupplierForm(profile));
    }
    setEditing(true);
  };

  const summary = `${profile.paymentTermsLabel} · ${profile.cooperationLevelLabel} · ${profile.settlementMethodLabel}${
    profile.moqLabel ? ` · 起订 ${profile.moqLabel}` : ''
  }`;

  return (
    <SlotShell
      roleType="SUPPLIER"
      roleId={profile.roleId}
      profileFound={profile.profileFound}
      warnings={profile.warnings}
      busy={busy}
      writable={writable}
      onOpen={() => void open()}
      onDelete={() => onDelete(profile.version)}
      summary={summary}
    >
      <dl className="text-muted-foreground mt-2 grid gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
        <div>账期：{profile.paymentTermsLabel}</div>
        <div>结算方式：{profile.settlementMethodLabel}</div>
        <div>合作等级：{profile.cooperationLevelLabel}</div>
        <div>最小起订量：{profile.moqLabel ?? '未设（采购单不按起订量校验）'}</div>
        {profile.note && <div className="sm:col-span-2">备注：{profile.note}</div>}
      </dl>
      {editing && (
        <div className="mt-3 space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field
              label="账期（天）"
              hint={`账期结算必须大于 0；其余方式必须 0（0 天 = 现结）。当前结算方式：${settlementLabel(form.settlementMethod)}`}
            >
              <input
                className={control}
                inputMode="numeric"
                value={form.paymentTermsDays}
                onChange={(e) => setForm({ ...form, paymentTermsDays: e.target.value })}
                placeholder="留空 = 现结"
              />
            </Field>
            <Field label="结算方式" hint={SETTLEMENT_HINTS[form.settlementMethod] ?? ''}>
              <select
                className={control}
                value={form.settlementMethod}
                onChange={(e) => setForm({ ...form, settlementMethod: e.target.value })}
              >
                <option value="PREPAID">预付（先款后货）</option>
                <option value="ON_DELIVERY">货到付款</option>
                <option value="CREDIT">账期结算（先货后款）</option>
                <option value="LETTER_OF_CREDIT">信用证</option>
              </select>
            </Field>
            <Field label="合作等级" hint={COOPERATION_HINTS[form.cooperationLevel] ?? ''}>
              <select
                className={control}
                value={form.cooperationLevel}
                onChange={(e) => setForm({ ...form, cooperationLevel: e.target.value })}
              >
                <option value="STRATEGIC">战略供应商</option>
                <option value="PREFERRED">优选供应商</option>
                <option value="STANDARD">常规供应商</option>
                <option value="TRIAL">试单供应商</option>
                <option value="RISK">风险供应商</option>
              </select>
            </Field>
            <Field label="最小起订量" hint="与采购单位必须成对填；不设请两处都留空。">
              <input
                className={control}
                inputMode="decimal"
                value={form.moqQuantity}
                onChange={(e) => setForm({ ...form, moqQuantity: e.target.value })}
                placeholder="例如 500"
              />
            </Field>
            <Field label="采购单位" hint="3.3.3 计量单位的 code，必须已登记、启用且允许采购。">
              <input
                className={control}
                value={form.moqUnitCode}
                onChange={(e) => setForm({ ...form, moqUnitCode: e.target.value })}
                placeholder="例如 PCS"
              />
            </Field>
          </div>
          <Field label="备注" hint="交期口径、退货条款、质检要求等不能进枚举的约定。">
            <textarea
              className={control}
              rows={2}
              value={form.note}
              onChange={(e) => setForm({ ...form, note: e.target.value })}
            />
          </Field>
          <SaveBar
            busy={busy}
            problem={problem}
            onSave={() => {
              onSave(toSupplierInput(form));
              setEditing(false);
            }}
            onCancel={() => setEditing(false)}
          />
        </div>
      )}
    </SlotShell>
  );
}

// ================================================================ 客户面板

function CustomerPanel({
  profile,
  writable,
  busy,
  onSave,
  onDelete,
}: {
  profile: PartyCustomerProfile;
  writable: boolean;
  busy: boolean;
  onSave: (input: PartyCustomerProfileInput) => void;
  onDelete: (version: number) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<CustomerProfileForm>(emptyCustomerForm());
  const problem = useMemo(() => customerFormProblem(form), [form]);

  const open = async () => {
    if (!profile.profileFound) {
      setForm(emptyCustomerForm());
      setEditing(true);
      return;
    }
    try {
      const fresh = await fetchCustomerProfile(profile.partyId);
      setForm(toCustomerForm(fresh));
    } catch {
      setForm(toCustomerForm(profile));
    }
    setEditing(true);
  };

  const summary = `${profile.customerLevelLabel} · ${profile.creditLimitLabel ?? '未设额度'} · ${profile.paymentTermsLabel}`;

  return (
    <SlotShell
      roleType="CUSTOMER"
      roleId={profile.roleId}
      profileFound={profile.profileFound}
      warnings={profile.warnings}
      busy={busy}
      writable={writable}
      onOpen={() => void open()}
      onDelete={() => onDelete(profile.version)}
      summary={summary}
    >
      <dl className="text-muted-foreground mt-2 grid gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
        <div>客户分级：{profile.customerLevelLabel}</div>
        <div>信用额度：{profile.creditLimitLabel ?? '未设（不限制赊账）'}</div>
        <div>默认币种：{profile.defaultCurrency ?? '未设（单据需单独指定币种）'}</div>
        <div>账期：{profile.paymentTermsLabel}</div>
        {profile.note && <div className="sm:col-span-2">备注：{profile.note}</div>}
      </dl>
      {editing && (
        <div className="mt-3 space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="客户分级" hint={CUSTOMER_LEVEL_HINTS[form.customerLevel] ?? ''}>
              <select
                className={control}
                value={form.customerLevel}
                onChange={(e) => setForm({ ...form, customerLevel: e.target.value })}
              >
                <option value="STRATEGIC">战略客户</option>
                <option value="KEY">重点客户</option>
                <option value="STANDARD">普通客户</option>
                <option value="PROSPECT">潜在客户</option>
                <option value="RISK">风险客户</option>
              </select>
            </Field>
            <Field
              label="默认币种"
              hint="ISO 4217 三位代码。填了信用额度就必须填它 —— 12 应收拿它和发票币种对比。"
            >
              <input
                className={control}
                value={form.defaultCurrency}
                onChange={(e) =>
                  setForm({ ...form, defaultCurrency: e.target.value.toUpperCase() })
                }
                placeholder="USD"
              />
            </Field>
            <Field
              label="信用额度"
              hint="留空 = 不限制赊账；0 不合法（那表示完全不许赊，与「没设」相反）。"
            >
              <input
                className={control}
                inputMode="decimal"
                value={form.defaultCreditLimit}
                onChange={(e) => setForm({ ...form, defaultCreditLimit: e.target.value })}
                placeholder="留空 = 不限制赊账"
              />
            </Field>
            <Field label="账期（天）" hint="12 应收的到期日 = 单据日 + 账期天数；0 = 现结。">
              <input
                className={control}
                inputMode="numeric"
                value={form.paymentTermsDays}
                onChange={(e) => setForm({ ...form, paymentTermsDays: e.target.value })}
                placeholder="留空 = 现结"
              />
            </Field>
          </div>
          <Field label="备注">
            <textarea
              className={control}
              rows={2}
              value={form.note}
              onChange={(e) => setForm({ ...form, note: e.target.value })}
            />
          </Field>
          <SaveBar
            busy={busy}
            problem={problem}
            onSave={() => {
              onSave(toCustomerInput(form));
              setEditing(false);
            }}
            onCancel={() => setEditing(false)}
          />
        </div>
      )}
    </SlotShell>
  );
}

// ================================================================ 承运商面板

function CarrierPanel({
  profile,
  writable,
  busy,
  onSave,
  onDelete,
}: {
  profile: PartyCarrierProfile;
  writable: boolean;
  busy: boolean;
  onSave: (input: PartyCarrierProfileInput) => void;
  onDelete: (version: number) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<CarrierProfileForm>(emptyCarrierForm());
  const problem = useMemo(() => carrierFormProblem(form), [form]);

  const open = async () => {
    if (!profile.profileFound) {
      setForm(emptyCarrierForm());
      setEditing(true);
      return;
    }
    setForm(toCarrierForm(profile));
    setEditing(true);
  };

  const toggle = (code: string) =>
    setForm((current) => ({
      ...current,
      serviceCapabilities: current.serviceCapabilities.includes(code)
        ? current.serviceCapabilities.filter((item) => item !== code)
        : [...current.serviceCapabilities, code],
    }));

  const summary = `${profile.interfaceTypeLabel} · ${profile.serviceCapabilitiesLabel}`;

  return (
    <SlotShell
      roleType="CARRIER"
      roleId={profile.roleId}
      profileFound={profile.profileFound}
      warnings={profile.warnings}
      busy={busy}
      writable={writable}
      onOpen={() => void open()}
      onDelete={() => onDelete(profile.version)}
      summary={summary}
    >
      <dl className="text-muted-foreground mt-2 grid gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
        <div>接口类型：{profile.interfaceTypeLabel}</div>
        <div>账号：{profile.accountRef ?? '未登记'}</div>
        <div className="sm:col-span-2">服务能力：{profile.serviceCapabilitiesLabel}</div>
        {profile.serviceScopeNote && (
          <div className="sm:col-span-2">服务范围：{profile.serviceScopeNote}</div>
        )}
        {profile.note && <div className="sm:col-span-2">备注：{profile.note}</div>}
      </dl>
      {editing && (
        <div className="mt-3 space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="接口类型" hint={INTERFACE_HINTS[form.interfaceType] ?? ''}>
              <select
                className={control}
                value={form.interfaceType}
                onChange={(e) => setForm({ ...form, interfaceType: e.target.value })}
              >
                <option value="API">系统对接（API）</option>
                <option value="SFTP_FILE">文件交换（EDI / CSV）</option>
                <option value="WEB_PORTAL">平台下单（登录对方后台）</option>
                <option value="EMAIL">邮件往来</option>
              </select>
            </Field>
            <Field
              label="账号标识"
              hint="对方系统里的账号名 / 客户号。只存账号，密钥由部署侧从 secret manager 注入。"
            >
              <input
                className={control}
                value={form.accountRef}
                onChange={(e) => setForm({ ...form, accountRef: e.target.value })}
                placeholder="例如 UPS-12345"
              />
            </Field>
          </div>
          <Field label="服务能力" hint="10 物流按它决定运单能否自动跟踪、能否按航线筛选。">
            <div className="flex flex-wrap gap-2">
              {profile.serviceCapabilityOptions.map((option) => {
                const active = form.serviceCapabilities.includes(option.code);
                return (
                  <label
                    key={option.code}
                    className="border-border flex cursor-pointer items-center gap-1.5 rounded-md border px-2 py-1 text-sm"
                  >
                    <input type="checkbox" checked={active} onChange={() => toggle(option.code)} />
                    {option.label}
                  </label>
                );
              })}
            </div>
          </Field>
          <Field label="服务范围说明" hint="航线限制、截单时间、禁运品类。">
            <textarea
              className={control}
              rows={2}
              value={form.serviceScopeNote}
              onChange={(e) => setForm({ ...form, serviceScopeNote: e.target.value })}
            />
          </Field>
          <Field label="备注">
            <textarea
              className={control}
              rows={2}
              value={form.note}
              onChange={(e) => setForm({ ...form, note: e.target.value })}
            />
          </Field>
          <SaveBar
            busy={busy}
            problem={problem}
            onSave={() => {
              onSave(toCarrierInput(form));
              setEditing(false);
            }}
            onCancel={() => setEditing(false)}
          />
        </div>
      )}
    </SlotShell>
  );
}
