'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import {
  ArrowLeft,
  CalendarClock,
  Check,
  CircleAlert,
  RefreshCw,
  Settings2,
} from 'lucide-react';
import { Button, EmptyState, FormField, Input, Notice, PageHeader, SectionCard } from '@starcon/ui';
import type { ConfigImpact, ConfigOptions, ConfigOverview } from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  ALL,
  changeText,
  configProblem,
  createShift,
  draftOf,
  draftOfShift,
  durationText,
  emptyShiftDraft,
  errorText,
  fetchConfigOptions,
  fetchConfigOverview,
  isBoolField,
  isRatioField,
  ratioText,
  savedText,
  scopeLabel,
  shiftProblem,
  statusLabel,
  strategyLabel,
  strategyNote,
  unconfiguredText,
  updateConfig,
  updateShift,
  windowText,
  type ConfigDraft,
  type ShiftDraft,
} from './warehouse-config-model';

const PAGE_SIZE = 20;

/**
 * 8.1.1.3 仓库配置工作区。
 *
 * <p>蓝图：「配置仓库行为参数：是否支持批次/序列号、拣货策略（按订单/按波次/按库位）、
 * 默认复核比例、是否允许拆零发货、工作时段与班次。」以及「配置影响作业方式，不是纯展示项，
 * 修改需记录变更。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**「未配置」与「配成默认值」分开显示。**否则用户会以为复核比例 0 是有人特意设的。
 *   <li>**配置落库 ≠ 下游已在按它跑。**拣货（8.3.2）、复核（8.3.3）、批次（8.2.1.1）、
 *       序列号（7.1.1.2）、拆零（8.3.4）都还没接上，逐条按 available 分组显示。
 *   <li>**留痕占一整块**，逐字段「从什么 → 什么」，不压缩成「已保存」。
 *   <li>**归档是终态**：归档后编辑入口整个关掉（后端也会再拒一次）。
 *   <li>加载 / 空 / 错误三态分开渲染。
 * </ul>
 */
export function WarehouseConfigWorkspace({ warehouseId }: { warehouseId: string }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.facilities.read');
  // 写用独立的 warehouse.facilities.config，不复用 8.1.1.2 的 update：
  // 改仓库地址与改作业参数是两种风险面，授权时也要能分开授予。
  const writable = session.permissions.includes('warehouse.facilities.config');

  const [overview, setOverview] = useState<ConfigOverview | null>(null);
  const [options, setOptions] = useState<ConfigOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [scope, setScope] = useState(ALL);
  const [shiftFilter, setShiftFilter] = useState(ALL);
  const [pageNo, setPageNo] = useState(0);

  const [showEdit, setShowEdit] = useState(false);
  const [draft, setDraft] = useState<ConfigDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [draftError, setDraftError] = useState('');

  const [shiftDraft, setShiftDraft] = useState<ShiftDraft | null>(null);
  const [shiftEditingId, setShiftEditingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [data, opts] = await Promise.all([
        fetchConfigOverview(warehouseId, {
          ...(scope ? { scope } : {}),
          ...(shiftFilter ? { shiftId: shiftFilter } : {}),
          page: pageNo,
          size: PAGE_SIZE,
        }),
        fetchConfigOptions(warehouseId),
      ]);
      setOverview(data);
      setOptions(opts);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [warehouseId, scope, shiftFilter, pageNo, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const startEdit = () => {
    if (!overview) return;
    setDraft(draftOf(overview));
    setDraftError('');
    setShowEdit(true);
  };

  const submit = async () => {
    if (!draft) return;
    const problem = configProblem(draft);
    setDraftError(problem);
    if (problem) return;
    setSaving(true);
    try {
      const saved = await updateConfig(warehouseId, draft);
      setNotice(savedText(saved.changeCount));
      setShowEdit(false);
      await load();
    } catch (e) {
      setDraftError(errorText(e));
    } finally {
      setSaving(false);
    }
  };

  const submitShift = async () => {
    if (!shiftDraft) return;
    const editing = shiftEditingId !== null;
    const problem = shiftProblem(shiftDraft, editing);
    setDraftError(problem);
    if (problem) return;
    setSaving(true);
    try {
      const saved = editing
        ? await updateShift(warehouseId, shiftEditingId, shiftDraft)
        : await createShift(warehouseId, shiftDraft);
      setNotice(
        `${editing ? '班次已更新' : '班次已新建'}，${savedText(saved.changeCount).replace('已保存，', '')}`,
      );
      setShiftDraft(null);
      setShiftEditingId(null);
      setDraftError('');
      await load();
    } catch (e) {
      setDraftError(errorText(e));
    } finally {
      setSaving(false);
    }
  };

  const changes = overview?.changes;
  const changeRows = changes?.rows ?? [];
  const totalPages = changes ? Math.max(1, Math.ceil(changes.total / changes.size)) : 1;
  const impacts = overview?.impacts ?? [];
  const availableImpacts = impacts.filter((i) => i.available);
  const pendingImpacts = impacts.filter((i) => !i.available);
  const shifts = overview?.shifts ?? [];
  const editable = Boolean(overview?.editable);

  return (
    <>
      <PageHeader
        title={overview ? `${overview.warehouseName} · 仓库配置` : '仓库配置'}
        description="配置仓库的作业行为参数：批次与序列号、拣货策略、默认复核比例、拆零发货、工作时段与班次。每次修改逐字段留痕。"
        actions={
          <>
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            {writable && editable && (
              <Button onClick={() => (showEdit ? setShowEdit(false) : startEdit())}>
                <Settings2 className="mr-2 h-4 w-4" />
                维护作业参数
              </Button>
            )}
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="仓库配置加载失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="操作完成">
          {notice}
        </Notice>
      )}
      {!readable && (
        <Notice tone="warning" title="没有查看权限">
          当前角色未被授予仓库档案（warehouse.facilities.read）的权限。
        </Notice>
      )}
      {readable && !writable && (
        <Notice tone="warning" title="只读">
          当前角色只能查看配置。修改作业参数需要单独的 warehouse.facilities.config 权限 ——
          改仓库地址（update）与改作业参数（config）是两种风险面，刻意不共用一个码。
        </Notice>
      )}
      {overview?.archived && (
        <Notice tone="warning" title="仓库已归档">
          归档是终态：不能修改任何字段，也没有回退动作。归档前配置的参数与班次原样保留 ——
          归档的是仓库，不是它的作业参数。
        </Notice>
      )}

      {loading && !overview && (
        <p className="py-8 text-center text-copy-13 text-muted-foreground">加载中…</p>
      )}

      {overview && (
        <>
          <p className="mt-2 text-copy-12 text-muted-foreground">
            <Link href={`/warehouse/facilities/${warehouseId}`} className="inline-flex items-center hover:underline">
              <ArrowLeft className="mr-1 h-3.5 w-3.5" />
              返回仓库详情
            </Link>
            <span className="ml-3">
              {overview.warehouseCode} · {statusLabel(overview.warehouseStatus)} · 时区{' '}
              {overview.timezone}
            </span>
          </p>

          {unconfiguredText(overview.config) && (
            <Notice tone="warning" title="尚未配置">
              {unconfiguredText(overview.config)}
            </Notice>
          )}

          {/* 作业参数 */}
          <SectionCard className="mt-4" title="作业参数">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <Info
                label="批次追溯"
                value={overview.config.lotTrackingEnabled ? '支持' : '不支持'}
                hint="关闭时收货（8.2.1.1）不录批号"
              />
              <Info
                label="序列号追溯"
                value={overview.config.serialTrackingEnabled ? '支持' : '不支持'}
                hint="与批次独立：批次录批号、序列号逐件扫"
              />
              <Info
                label="拣货策略"
                value={strategyLabel(overview.config.pickingStrategy, options)}
                hint={strategyNote(overview.config.pickingStrategy, options)}
              />
              <Info
                label="默认复核比例"
                value={ratioText(overview.config.defaultReviewRatio)}
                hint={options?.reviewRatioNote}
              />
              <Info
                label="拆零发货"
                value={overview.config.allowSplitShipment ? '允许' : '不允许'}
                hint={overview.config.allowSplitShipment ? options?.splitShipmentNote : '拆零会破掉「一单一包裹」这个下游默认假设'}
              />
              <Info
                label="最后修改"
                value={overview.config.updatedAt ? momentOf(overview.config.updatedAt) : '—'}
                hint={overview.config.unconfigured ? '尚未配置' : undefined}
              />
            </div>
            {overview.config.note && (
              <p className="mt-3 border-t pt-3 text-copy-12 text-muted-foreground">
                备注：{overview.config.note}
              </p>
            )}
            {showEdit && draft && (
              <div className="mt-4 border-t pt-4">
                <div className="grid gap-3 sm:grid-cols-2">
                  <CheckField
                    id="cfg-lot"
                    label="支持批次追溯"
                    checked={draft.lotTrackingEnabled}
                    onChange={(v) => setDraft({ ...draft, lotTrackingEnabled: v })}
                  />
                  <CheckField
                    id="cfg-serial"
                    label="支持序列号追溯"
                    checked={draft.serialTrackingEnabled}
                    onChange={(v) => setDraft({ ...draft, serialTrackingEnabled: v })}
                  />
                  <FormField id="cfg-strategy" label="拣货策略" required>
                    <select
                      id="cfg-strategy"
                      className="h-9 w-full rounded-md border border-input bg-background px-3 text-copy-13"
                      value={draft.pickingStrategy}
                      onChange={(e) => setDraft({ ...draft, pickingStrategy: e.target.value })}
                    >
                      {(options?.strategies ?? []).map((s) => (
                        <option key={s.code} value={s.code}>
                          {s.label}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField
                    id="cfg-ratio"
                    label="默认复核比例"
                    required
                    hint="0 到 1：0 = 不复核，1 = 全量复核"
                  >
                    <Input
                      value={draft.defaultReviewRatio}
                      onChange={(e) => setDraft({ ...draft, defaultReviewRatio: e.target.value })}
                    />
                  </FormField>
                  <CheckField
                    id="cfg-split"
                    label="允许拆零发货"
                    checked={draft.allowSplitShipment}
                    onChange={(v) => setDraft({ ...draft, allowSplitShipment: v })}
                  />
                  <FormField id="cfg-note" label="备注">
                    <Input value={draft.note} onChange={(e) => setDraft({ ...draft, note: e.target.value })} />
                  </FormField>
                </div>
                {draftError && (
                  <p role="alert" className="mt-2 text-copy-12 text-destructive">
                    {draftError}
                  </p>
                )}
                <div className="mt-3 flex gap-2">
                  <Button onClick={() => void submit()} disabled={saving}>
                    {saving ? '保存中…' : '保存'}
                  </Button>
                  <Button variant="secondary" onClick={() => setShowEdit(false)}>
                    取消
                  </Button>
                </div>
              </div>
            )}
          </SectionCard>

          {/* 班次 */}
          <SectionCard className="mt-4" title={`工作时段与班次（${shifts.length}）`}>
            <p className="mb-3 text-copy-12 text-muted-foreground">{options?.shiftNote}</p>
            {shifts.length === 0 ? (
              <EmptyState
                title="还没有排班"
                description={
                  writable && editable
                    ? '班次是「这个仓几点上班」的真相来源。不排班的话，下游作业侧没有可依据的工作时段。'
                    : '本仓尚未排班。班次属 8.1.1.3 配置，需要 warehouse.facilities.config 权限才能新增。'
                }
              />
            ) : (
              <ul className="divide-y">
                {shifts.map((shift) => (
                  <li key={shift.id} className="flex items-center justify-between py-2">
                    <div>
                      <div className="text-copy-13">
                        {shift.name}（{shift.code}）
                        {!shift.enabled && (
                          <span className="ml-2 text-copy-12 text-muted-foreground">已停用</span>
                        )}
                      </div>
                      <div className="text-copy-12 text-muted-foreground">
                        当地 {windowText(shift.startLocal, shift.endLocal, shift.crossesMidnight)} ·{' '}
                        {durationText(shift.durationMinutes, shift.crossesMidnight)}
                      </div>
                    </div>
                    {writable && editable && (
                      <Button
                        variant="secondary"
                        onClick={() => {
                          setShiftEditingId(shift.id);
                          setShiftDraft(draftOfShift(shift));
                          setDraftError('');
                        }}
                      >
                        编辑
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}

            {shiftDraft && (
              <div className="mt-4 border-t pt-4">
                <p className="text-copy-13">
                  {shiftEditingId ? '编辑班次' : '新建班次'}
                </p>
                <div className="mt-2 grid gap-3 sm:grid-cols-2">
                  <FormField
                    id="shift-code"
                    label="班次编码"
                    required
                    hint={shiftEditingId ? '编码不可改：它是留痕里的引用键' : '同一仓内唯一，留痕按它回溯'}
                  >
                    <Input
                      value={shiftDraft.code}
                      disabled={shiftEditingId !== null}
                      onChange={(e) => setShiftDraft({ ...shiftDraft, code: e.target.value.toUpperCase() })}
                    />
                  </FormField>
                  <FormField id="shift-name" label="班次名称" required>
                    <Input
                      value={shiftDraft.name}
                      onChange={(e) => setShiftDraft({ ...shiftDraft, name: e.target.value })}
                    />
                  </FormField>
                  <FormField id="shift-start" label="上班时刻" required hint="当地 HH:mm">
                    <Input
                      value={shiftDraft.startLocal}
                      onChange={(e) => setShiftDraft({ ...shiftDraft, startLocal: e.target.value })}
                    />
                  </FormField>
                  <FormField id="shift-end" label="下班时刻" required hint="当地 HH:mm；早于上班表示跨夜班">
                    <Input
                      value={shiftDraft.endLocal}
                      onChange={(e) => setShiftDraft({ ...shiftDraft, endLocal: e.target.value })}
                    />
                  </FormField>
                  <CheckField
                    id="shift-enabled"
                    label="排班中"
                    checked={shiftDraft.enabled}
                    onChange={(v) => setShiftDraft({ ...shiftDraft, enabled: v })}
                  />
                  <FormField id="shift-note" label="备注">
                    <Input
                      value={shiftDraft.note}
                      onChange={(e) => setShiftDraft({ ...shiftDraft, note: e.target.value })}
                    />
                  </FormField>
                </div>
                {draftError && (
                  <p role="alert" className="mt-2 text-copy-12 text-destructive">
                    {draftError}
                  </p>
                )}
                <div className="mt-3 flex gap-2">
                  <Button onClick={() => void submitShift()} disabled={saving}>
                    {saving ? '保存中…' : '保存'}
                  </Button>
                  <Button
                    variant="secondary"
                    onClick={() => {
                      setShiftDraft(null);
                      setShiftEditingId(null);
                      setDraftError('');
                    }}
                  >
                    取消
                  </Button>
                </div>
              </div>
            )}

            {writable && editable && !shiftDraft && (
              <Button
                variant="secondary"
                className="mt-3"
                onClick={() => {
                  setShiftEditingId(null);
                  setShiftDraft(emptyShiftDraft());
                  setDraftError('');
                }}
              >
                <CalendarClock className="mr-2 h-4 w-4" />
                新建班次
              </Button>
            )}
          </SectionCard>

          {/* 下游影响 */}
          <SectionCard className="mt-4" title="这些参数现在会影响什么">
            <p className="mb-3 text-copy-12 text-muted-foreground">
              配置已经落库并留痕，但「落库」不等于「下游已经在按它跑」。逐条说清哪些现在真的会生效。
            </p>
            <ImpactList title="现在已生效" items={availableImpacts} tone="ok" />
            <ImpactList title="已落库，待下游接入" items={pendingImpacts} tone="pending" />
          </SectionCard>

          {/* 变更留痕 */}
          <SectionCard
            className="mt-4"
            title={`变更留痕（${changes?.total ?? 0}）`}
          >
            <p className="mb-3 text-copy-12 text-muted-foreground">
              蓝图要求「修改需记录变更」，所以每一次保存都会逐字段写入一条留痕：
              谁、什么时候、把哪个字段从什么改成了什么。这些记录只能追加 ——
              连超级用户都改不掉或删不掉某一条。
            </p>
            <div className="grid gap-3 sm:grid-cols-2">
              <FormField id="change-scope" label="留痕范围">
                <select
                  id="change-scope"
                  className="h-9 w-full rounded-md border border-input bg-background px-3 text-copy-13"
                  value={scope}
                  onChange={(e) => {
                    setScope(e.target.value);
                    setPageNo(0);
                  }}
                >
                  <option value="">全部</option>
                  {(options?.scopes ?? []).map((s) => (
                    <option key={s.code} value={s.code}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="change-shift" label="班次">
                <select
                  id="change-shift"
                  className="h-9 w-full rounded-md border border-input bg-background px-3 text-copy-13"
                  value={shiftFilter}
                  onChange={(e) => {
                    setShiftFilter(e.target.value);
                    setPageNo(0);
                  }}
                >
                  <option value="">全部班次</option>
                  {(options?.shifts ?? []).map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}（{s.code}）{s.enabled ? '' : ' · 已停用'}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>

            {changeRows.length === 0 ? (
              <EmptyState
                title="还没有变更留痕"
                description="修改作业参数或班次之后，每变一个字段就会在这里留下一条记录。"
              />
            ) : (
              <table className="mt-3 w-full text-copy-12">
                <thead className="text-left text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-3 font-normal">时刻</th>
                    <th className="py-2 pr-3 font-normal">范围</th>
                    <th className="py-2 pr-3 font-normal">班次</th>
                    <th className="py-2 pr-3 font-normal">字段</th>
                    <th className="py-2 font-normal">从 → 到</th>
                  </tr>
                </thead>
                <tbody>
                  {changeRows.map((change) => {
                    const line = changeText(change, isBoolField, isRatioField);
                    return (
                      <tr key={change.id} className="border-t">
                        <td className="py-2 pr-3 text-muted-foreground">
                          {momentOf(change.changedAt)}
                        </td>
                        <td className="py-2 pr-3">{scopeLabel(change.scope)}</td>
                        <td className="py-2 pr-3 text-muted-foreground">
                          {change.shiftCode ?? '—'}
                        </td>
                        <td className="py-2 pr-3">{line.field}</td>
                        <td className="py-2">
                          <span className="text-muted-foreground line-through">{line.from}</span>
                          <span className="mx-1">→</span>
                          <span>{line.to}</span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}

            {changes && changes.total > changes.size && (
              <div className="mt-3 flex items-center gap-2">
                <Button
                  variant="secondary"
                  disabled={pageNo === 0}
                  onClick={() => setPageNo(Math.max(0, pageNo - 1))}
                >
                  上一页
                </Button>
                <span className="text-copy-12 text-muted-foreground">
                  第 {pageNo + 1} / {totalPages} 页 · 共 {changes.total} 条
                </span>
                <Button
                  variant="secondary"
                  disabled={pageNo + 1 >= totalPages}
                  onClick={() => setPageNo(pageNo + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </SectionCard>
        </>
      )}
    </>
  );
}

function ImpactList({
  title,
  items,
  tone,
}: {
  title: string;
  items: ConfigImpact[];
  tone: 'ok' | 'pending';
}) {
  if (items.length === 0) return null;
  return (
    <div className="mt-3">
      <div className="text-copy-12 text-muted-foreground">{title}</div>
      <ul className="mt-1 divide-y">
        {items.map((item) => (
          <li key={item.item} className="flex items-start py-2">
            <span className="mr-2 mt-0.5">
              {tone === 'ok' ? (
                <Check className="h-3.5 w-3.5 text-emerald-600" />
              ) : (
                <CircleAlert className="h-3.5 w-3.5 text-muted-foreground" />
              )}
            </span>
            <div>
              <div className="text-copy-13">
                {item.label}
                <span className="ml-2 text-copy-12 text-muted-foreground">
                  {tone === 'ok' ? item.ownerModule : `属 ${item.ownerModule}`}
                </span>
              </div>
              <div className="text-copy-12 text-muted-foreground">{item.note}</div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Info({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  // exactOptionalPropertyTypes：可选属性不接受显式 undefined，
  // 所以 hint 的类型写成 `| undefined` 而不是靠调用方不传。
  hint?: string | undefined;
}) {
  return (
    <div>
      <div className="text-copy-12 text-muted-foreground">{label}</div>
      <div className="text-copy-13">{value}</div>
      {hint && <div className="text-copy-12 text-muted-foreground">{hint}</div>}
    </div>
  );
}

function CheckField({
  id,
  label,
  checked,
  onChange,
}: {
  id: string;
  label: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <label htmlFor={id} className="flex items-center gap-2 text-copy-13">
      <input
        id={id}
        type="checkbox"
        className="h-4 w-4 rounded border-input"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
  );
}

/**
 * 留痕时刻的展示。
 *
 * <p>用服务端给的偏移量直接渲染，不在这里换算到别的时区 ——
 * 留痕是「谁在什么时候改的」的事实，而审计要的是服务端当时记下的那个时刻。
 */
function momentOf(value: string): string {
  return value.replace('T', ' ').slice(0, 19);
}
