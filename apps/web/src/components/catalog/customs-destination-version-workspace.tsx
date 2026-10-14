'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import {
  Badge,
  Button,
  EmptyState,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@starcon/ui';
import { ArrowLeft, Info, Plus, RefreshCw, Save, Send, Trash2, TriangleAlert } from 'lucide-react';
import type {
  DestinationVersionProfile,
  EffectiveDestinationVersion,
  ResolvedDeclaration,
} from '../../../../../packages/api-contracts/src/index';
import {
  canPublish,
  draftFromVersion,
  draftToPayload,
  emptyDraft,
  emptyRequirement,
  errorText,
  fetchEffective,
  fetchResolved,
  fetchVersions,
  isConflict,
  isNoEffectiveVersion,
  MAX_REQUIREMENTS,
  NO_EFFECTIVE_HINT,
  publishable,
  publishVersion,
  requiredRequirements,
  requirementLabel,
  resolutionText,
  saveVersion,
  sourceLabel,
  validateDraft,
  versionState,
  writable,
  type DestinationVersionDraft,
  type RequirementDraft,
} from './customs-destination-version-model';
import { Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.7.1.2 目的地报关版本工作区。
 *
 * <p>这一页要承担一件别的属性页不用承担的事：<b>它必须让「这个目的地与别处不同」
 * 变得一眼可见</b>。蓝图原文是「<b>不同目的地必须用不同版本，不能用一套申报资料
 * 打通全球</b>」，而人在页面上判断「这一格是继承的还是覆盖的」靠的不是数据，是排版。
 *
 * <p>所以界面纪律：
 * <ul>
 *   <li><b>基础资料永远并排显示</b>：只列差异列的话，「空着」和「忘了填」长得一样，
 *       而漏维护一个目的地正是从这里开始的。
 *   <li><b>生效状态与发布状态分成两格</b>：已发布 ≠ 此刻生效。把两者塌缩成一个标签，
 *       运营会把历史版本当成现在该用的版本 —— 那就是拿旧税号去报关。
 *   <li><b>无生效版本是一条醒目横幅</b>，不是空表格：后端在这三条路径上都返 409，
 *       页面把那句话原样说出来。
 *   <li><b>写与发布按两个权限分开收敛</b>（{@code catalog.compliance.update} /
 *       {@code catalog.compliance.publish}），发布二次确认。
 *   <li><b>加载 / 错误 / 空态三态齐全</b>。
 * </ul>
 */
export function CustomsDestinationVersionWorkspace({ skuId }: { skuId: string }) {
  const { session } = useIdentity();
  const canWrite = writable(session.permissions);
  const canPublishIt = publishable(session.permissions);

  const [destination, setDestination] = useState('DE');
  const [panel, setPanel] = useState<DestinationVersionProfile | null>(null);
  const [draft, setDraft] = useState<DestinationVersionDraft | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirmPublish, setConfirmPublish] = useState(false);
  const [effective, setEffective] = useState<EffectiveDestinationVersion | null>(null);
  const [effectiveError, setEffectiveError] = useState<string | null>(null);
  const [resolved, setResolved] = useState<ResolvedDeclaration | null>(null);
  const [resolveError, setResolveError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchVersions(skuId, destination);
      setPanel(result);
    } catch (e) {
      setError(errorText(e, '目的地报关版本加载失败'));
    } finally {
      setLoading(false);
    }
  }, [skuId, destination]);

  useEffect(() => {
    void load();
    // 切换目的地时清掉上一个目的地的编辑态与取数结果 ——
    // 否则 DE 的草稿会留在界面上，而它要表达的是 DE 的差异。
    setDraft(null);
    setEditingId(null);
    setEffective(null);
    setEffectiveError(null);
    setResolved(null);
    setResolveError(null);
  }, [load]);

  const errors = useMemo(() => (draft ? validateDraft(draft) : []), [draft]);

  function patch(changes: Partial<DestinationVersionDraft>) {
    setDraft((current) => (current ? { ...current, ...changes } : current));
  }

  function patchRequirement(index: number, changes: Partial<RequirementDraft>) {
    setDraft((current) =>
      current
        ? {
            ...current,
            requirements: current.requirements.map((item, at) =>
              at === index ? { ...item, ...changes } : item,
            ),
          }
        : current,
    );
  }

  /** 开始编辑某一版草稿。已发布的版本也允许编辑 —— 但只能收窄生效终点（后端会拒其它改法）。 */
  function startEdit(versionId: string) {
    const version = panel?.versions.find((item) => item.id === versionId);
    if (!version || !panel) return;
    setEditingId(versionId);
    setDraft(draftFromVersion(version));
    setActionError(null);
  }

  function startNew() {
    setEditingId(null);
    setDraft({ ...emptyDraft(), destinationCountryCode: destination });
    setActionError(null);
  }

  async function save() {
    if (!draft || errors.length > 0) return;
    setBusy(true);
    setActionError(null);
    try {
      // 用写端点回传的行刷新：不必为了看结果再拉一次（也避免两次请求之间被别人改掉）。
      await saveVersion(skuId, draftToPayload(draft));
      await load();
      setDraft(null);
      setEditingId(null);
      setEffective(null);
      setResolved(null);
    } catch (e) {
      setActionError(
        isConflict(e)
          ? '这一步没有生效：版本可能已被他人修改，或已发布的内容不可直接改。请刷新后重试。'
          : errorText(e, '保存失败，请检查输入后重试'),
      );
    } finally {
      setBusy(false);
    }
  }

  async function runPublish() {
    if (!editingId || !panel) return;
    const version = panel.versions.find((item) => item.id === editingId);
    if (!version) return;
    setBusy(true);
    setActionError(null);
    try {
      await publishVersion(editingId, version.version ?? 0);
      await load();
      setConfirmPublish(false);
      setDraft(null);
      setEditingId(null);
      setEffective(null);
      setResolved(null);
    } catch (e) {
      setActionError(
        errorText(e, '发布失败：可能与已发布版本的生效区间重叠，或已被他人处理过。请刷新后重试。'),
      );
      setConfirmPublish(false);
    } finally {
      setBusy(false);
    }
  }

  /** 按目的地查生效版本：后端在无生效版本时 409，这里把那句话原样带回。 */
  async function peekEffective() {
    setBusy(true);
    setEffectiveError(null);
    setEffective(null);
    try {
      setEffective(await fetchEffective(skuId, destination));
    } catch (e) {
      setEffectiveError(isNoEffectiveVersion(e) ? NO_EFFECTIVE_HINT : errorText(e, '取数失败'));
    } finally {
      setBusy(false);
    }
  }

  /** 合成基础 + 目的地覆盖：这就是 10 报关清关时真正拿到的。 */
  async function peekResolved() {
    setBusy(true);
    setResolveError(null);
    setResolved(null);
    try {
      setResolved(await fetchResolved(skuId, destination));
    } catch (e) {
      setResolveError(errorText(e, '合成失败'));
    } finally {
      setBusy(false);
    }
  }

  const editable = draft !== null && canWrite && !busy;
  const base = panel?.base;
  const hasBase = Boolean(base?.attributeId);
  /** 正在编辑的那一行（新建时为 null）。发布按钮只对「已存在的草稿」出现。 */
  const editing = editingId
    ? (panel?.versions.find((item) => item.id === editingId) ?? null)
    : null;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-h-20 text-primary font-semibold tracking-tight">目的地报关版本</h1>
          <p className="text-muted-foreground text-copy-13 mt-1">
            {panel
              ? `${panel.skuCode} · ${panel.skuName}（所属 ${panel.spuCode}）`
              : '同一件货在不同目的地按不同税号、申报品名与认证要求申报。'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link href={`/products/sku/${skuId}/customs`}>
            <Button variant="ghost" size="sm">
              <ArrowLeft className="h-4 w-4" />
              回到基础报关属性
            </Button>
          </Link>
          <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </Button>
        </div>
      </div>

      <p className="border-warning-soft bg-warning-soft/30 text-warning rounded-md border px-3 py-2 text-xs">
        不同目的地必须用不同版本，不能用一套申报资料打通全球 ——
        同一份资料申报到欧盟会因归类不符被退单， 申报到美国会因 FCC
        要求缺失被扣货，而扣货与罚款由卖家承担。 所以本页面<b>不接受「不限目的地」</b>
        ，也不会在某个目的地缺资料时退回基础属性。
      </p>

      {/* 目的地选择器：这是本页的主轴。切目的地 = 切一整套申报口径。 */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-48">
          <Field label="目的地国家" hint="ISO 3166-1 alpha-2，如 DE / US / GB">
            <input
              className={control}
              value={destination}
              placeholder="DE"
              maxLength={2}
              aria-label="目的地国家"
              onChange={(e) => setDestination(e.target.value.toUpperCase())}
            />
          </Field>
        </div>
        {canWrite && (
          <Button variant="outline" size="sm" onClick={startNew} disabled={!hasBase || busy}>
            <Plus className="h-4 w-4" />
            新建版本
          </Button>
        )}
      </div>

      {actionError && (
        <div
          role="alert"
          className="border-destructive-soft bg-destructive-soft/40 text-destructive flex items-start gap-2 rounded-md border px-3 py-2 text-sm"
        >
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {loading ? (
        <div className="text-muted-foreground rounded-md border border-dashed p-10 text-center text-sm">
          正在加载目的地报关版本…
        </div>
      ) : error ? (
        <EmptyState
          icon={<TriangleAlert className="h-5 w-5" />}
          title="目的地报关版本加载失败"
          description={error}
          action={
            <Button variant="ghost" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      ) : !panel ? (
        <EmptyState
          icon={<Info className="h-5 w-5" />}
          title="没有可显示的目的地版本"
          description="这个 SKU 可能不存在，或者当前租户看不到它。"
        />
      ) : (
        <>
          {/* 基础资料：永远并排显示，否则「空着」与「漏填」在屏幕上长得一样。 */}
          <Card
            title="基础报关属性（3.7.1.1）"
            hint="目的地版本是在这份资料之上声明差异。基础资料未审核通过时，下游取数一律被拒绝。"
          >
            {hasBase ? (
              <div className="space-y-2">
                <div className="grid gap-3 sm:grid-cols-3">
                  <Metric label="基础 HS 编码" value={base?.hsCode ?? '—'} />
                  <Metric label="基础申报品名" value={base?.declaredName ?? '—'} />
                  <Metric label="法定计量单位" value={base?.statutoryUnitCode ?? '—'} />
                </div>
                <p className="text-muted-foreground text-xs">
                  法定计量单位与申报要素<b>只能来自基础属性</b>，目的地版本不覆盖也不复制 ——
                  复制一份就是第二个事实来源，而两个来源不一致时「按哪份申报」没人能回答。
                </p>
                {base && !base.baseApproved && (
                  <p className="text-warning text-xs">
                    基础属性尚未审核通过：本目的地即便发布了版本，10 报关取数仍会被拒绝。
                  </p>
                )}
              </div>
            ) : (
              <p className="border-warning-soft bg-warning-soft/40 rounded-md border px-3 py-2 text-sm">
                <span className="text-warning font-medium">这个 SKU 还没有基础报关属性</span>
                <span className="text-muted-foreground mt-1 block text-xs">
                  目的地版本是「相对基础属性的差异」，没有基础属性就说不上差异相对什么。 请先在「HS
                  编码与申报要素」里维护并保存。
                </span>
              </p>
            )}
          </Card>

          {/* 无生效版本是一等公民：后端在三条路径上都返 409，这里显式说出来。 */}
          {panel.effectiveVersionId === null && (
            <div className="border-warning-soft bg-warning-soft/40 rounded-md border px-3 py-2 text-sm">
              <p className="text-warning flex items-center gap-2 font-medium">
                <TriangleAlert className="h-4 w-4" />
                该目的地此刻没有生效版本
              </p>
              <p className="text-muted-foreground mt-1 text-xs">{NO_EFFECTIVE_HINT}</p>
            </div>
          )}

          <Card
            title={`版本列表（${panel.versions.length} 个，${panel.draftCount} 个草稿）`}
            hint="生效区间是左闭右开的 [起点, 终点)；相邻版本在终点处不重叠。只有已发布版本参与重叠判定。"
          >
            {panel.versions.length === 0 ? (
              <p className="text-muted-foreground rounded-md border border-dashed p-6 text-center text-sm">
                还没有这个目的地的版本。点右上角「新建版本」声明它与基础资料的差异。
              </p>
            ) : (
              <ul className="space-y-2">
                {panel.versions.map((version) => {
                  const state = versionState(version);
                  const required = requiredRequirements(version.requirements);
                  return (
                    <li
                      key={version.id}
                      className={`rounded-md border p-3 ${
                        version.effective
                          ? 'border-success-soft bg-success-soft/20'
                          : 'border-control-border'
                      }`}
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <span className="font-medium tabular-nums">v{version.versionNo}</span>
                          <Badge tone={state.tone}>{state.statusLabel}</Badge>
                          <Badge tone={version.effective ? 'success' : 'neutral'}>
                            {state.effectiveLabel}
                          </Badge>
                        </div>
                        {canWrite && (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => startEdit(version.id)}
                            disabled={busy}
                          >
                            编辑
                          </Button>
                        )}
                      </div>
                      <p className="text-muted-foreground mt-1 text-xs">
                        生效区间 [{version.effectiveFrom} → {version.effectiveTo ?? '一直有效'})
                        {state.next && ` · ${state.next}`}
                      </p>
                      {/* 覆盖 vs 继承逐格标出来：空值写「沿用基础」，不留空白让人猜。 */}
                      <dl className="mt-2 grid gap-2 text-xs sm:grid-cols-3">
                        <FieldValue label="目的地税号">
                          {version.destinationHsCode === '' ? (
                            <span className="text-muted-foreground">
                              沿用基础（{base?.hsCode ?? '—'}）
                            </span>
                          ) : (
                            <span className="text-accent">{version.destinationHsCode}</span>
                          )}
                        </FieldValue>
                        <FieldValue label="目的地申报品名">
                          {version.destinationDeclaredName === '' ? (
                            <span className="text-muted-foreground">沿用基础</span>
                          ) : (
                            <span className="text-accent">{version.destinationDeclaredName}</span>
                          )}
                        </FieldValue>
                        <FieldValue label="该目的地承认优惠">
                          {version.preferentialApplicable ? '承认' : '不承认'}
                        </FieldValue>
                      </dl>
                      {version.requirements.length > 0 && (
                        <p className="text-muted-foreground mt-2 text-xs">
                          认证要求 {version.requirements.length} 项（强制 {required.length} 项）：
                          {version.requirements.map((item) => requirementLabel(item)).join('、')}
                        </p>
                      )}
                      {version.note && (
                        <p className="text-muted-foreground mt-1 text-xs">备注：{version.note}</p>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>

          {draft && (
            <Card
              title={editingId ? '编辑版本' : '新建版本'}
              hint="留空 = 沿用基础。目的地版本只覆盖税号与申报品名两项。"
            >
              <div className="space-y-4">
                <div className="grid gap-3 sm:grid-cols-3">
                  <Field label="目的地国家" hint="两位大写字母，不接受 '*'">
                    <input
                      className={control}
                      value={draft.destinationCountryCode}
                      disabled={!editable || editingId !== null}
                      placeholder="DE"
                      onChange={(e) => patch({ destinationCountryCode: e.target.value })}
                    />
                  </Field>
                  <Field label="版本号" hint="同一 SKU 同一目的地内唯一，从 1 开始">
                    <input
                      className={control}
                      value={draft.versionNo}
                      disabled={!editable || editingId !== null}
                      inputMode="numeric"
                      onChange={(e) => patch({ versionNo: e.target.value })}
                    />
                  </Field>
                  <Field
                    label="该目的地承认优惠"
                    hint="与基础的「有资格」是两件事，两者都满足才享惠"
                  >
                    <label className="flex items-center gap-2 py-2 text-sm">
                      <input
                        type="checkbox"
                        checked={draft.preferentialApplicable}
                        disabled={!editable}
                        onChange={(e) => patch({ preferentialApplicable: e.target.checked })}
                      />
                      承认（可按协定税率）
                    </label>
                  </Field>
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field
                    label="目的地税号"
                    hint="留空 = 沿用基础 HS 编码；非空必须是 6–10 位纯数字"
                  >
                    <input
                      className={control}
                      value={draft.destinationHsCode}
                      disabled={!editable}
                      placeholder="留空表示沿用基础"
                      onChange={(e) => patch({ destinationHsCode: e.target.value })}
                    />
                  </Field>
                  <Field label="目的地申报品名" hint="留空 = 沿用基础申报品名">
                    <input
                      className={control}
                      value={draft.destinationDeclaredName}
                      disabled={!editable}
                      placeholder="留空表示沿用基础"
                      onChange={(e) => patch({ destinationDeclaredName: e.target.value })}
                    />
                  </Field>
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label="生效起点" hint="必填。填的是当地时间，提交时按本机时区换算">
                    <input
                      className={control}
                      type="datetime-local"
                      value={draft.effectiveFrom}
                      disabled={!editable}
                      onChange={(e) => patch({ effectiveFrom: e.target.value })}
                    />
                  </Field>
                  <Field label="生效终点" hint="留空 = 一直有效（+∞）。左闭右开，终点不包含">
                    <input
                      className={control}
                      type="datetime-local"
                      value={draft.effectiveTo}
                      disabled={!editable}
                      onChange={(e) => patch({ effectiveTo: e.target.value })}
                    />
                  </Field>
                </div>

                <div>
                  <p className="text-muted-foreground text-xs">
                    认证要求（{draft.requirements.length} 项，强制{' '}
                    {draft.requirements.filter((item) => item.required).length} 项）—— CE / FCC /
                    RoHS / UKCA / 危包证等。保存时按完整清单替换。
                  </p>
                  <div className="mt-2 space-y-2">
                    {draft.requirements.map((item, index) => (
                      <div key={index} className="grid gap-2 sm:grid-cols-[8rem_1fr_auto_2rem]">
                        <input
                          className={control}
                          value={item.requirementCode}
                          disabled={!editable}
                          placeholder="CE"
                          aria-label={`第 ${index + 1} 条认证代码`}
                          onChange={(e) =>
                            patchRequirement(index, { requirementCode: e.target.value })
                          }
                        />
                        <input
                          className={control}
                          value={item.requirementName}
                          disabled={!editable}
                          placeholder="名称（选填）"
                          aria-label={`第 ${index + 1} 条认证名称`}
                          onChange={(e) =>
                            patchRequirement(index, { requirementName: e.target.value })
                          }
                        />
                        <label className="flex items-center gap-1 text-xs">
                          <input
                            type="checkbox"
                            checked={item.required}
                            disabled={!editable}
                            onChange={(e) =>
                              patchRequirement(index, { required: e.target.checked })
                            }
                          />
                          强制
                        </label>
                        <button
                          type="button"
                          className="text-muted-foreground hover:text-destructive hover:bg-destructive-soft/40 duration-base ease-out-expo self-center rounded-md border p-2 transition-colors disabled:opacity-50"
                          disabled={!editable}
                          aria-label={`删除第 ${index + 1} 条认证要求`}
                          onClick={() =>
                            setDraft((current) =>
                              current
                                ? {
                                    ...current,
                                    requirements: current.requirements.filter(
                                      (_, at) => at !== index,
                                    ),
                                  }
                                : current,
                            )
                          }
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </div>
                    ))}
                  </div>
                  {canWrite && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="mt-2"
                      disabled={!editable || draft.requirements.length >= MAX_REQUIREMENTS}
                      onClick={() =>
                        setDraft((current) =>
                          current && current.requirements.length < MAX_REQUIREMENTS
                            ? {
                                ...current,
                                requirements: [...current.requirements, emptyRequirement()],
                              }
                            : current,
                        )
                      }
                    >
                      <Plus className="h-4 w-4" />
                      添加认证要求
                    </Button>
                  )}
                </div>

                <Field label="备注（不参与判定）">
                  <input
                    className={control}
                    value={draft.note}
                    disabled={!editable}
                    placeholder="例：DE 税号已与报关行确认"
                    onChange={(e) => patch({ note: e.target.value })}
                  />
                </Field>

                {errors.length > 0 && (
                  <div className="border-destructive-soft bg-destructive-soft/40 rounded-md border px-3 py-2">
                    <p className="text-destructive text-copy-13 font-medium">
                      这次修改没有保存，请先解决：
                    </p>
                    <ul className="text-muted-foreground mt-1 space-y-0.5 text-xs">
                      {errors.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  </div>
                )}

                <div className="flex flex-wrap items-center gap-2">
                  <Button onClick={() => void save()} disabled={!editable || errors.length > 0}>
                    <Save className="h-4 w-4" />
                    保存
                  </Button>
                  {canPublishIt && editing && canPublish(editing) && (
                    <Button
                      variant="outline"
                      disabled={busy}
                      title="发布后这一版成为 10 报关清关时引用的那一份"
                      onClick={() => setConfirmPublish(true)}
                    >
                      <Send className="h-4 w-4" />
                      发布
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    onClick={() => {
                      setDraft(null);
                      setEditingId(null);
                    }}
                    disabled={busy}
                  >
                    取消
                  </Button>
                  {editing && (
                    <span className="text-muted-foreground text-xs">
                      {editing.status === 'PUBLISHED'
                        ? '已发布的版本只允许「把生效终点向前收窄」，改内容会被拒绝。'
                        : '草稿对 10 报关不可见；发布后才参与生效区间的重叠判定。'}
                    </span>
                  )}
                  {!canPublishIt && canWrite && (
                    <span className="text-muted-foreground text-xs">
                      你可以编辑目的地版本，但发布需要另外的 `catalog.compliance.publish` 权限 ——
                      能填不等于有权把它发布成下游要用的那一份。
                    </span>
                  )}
                </div>
              </div>
            </Card>
          )}

          <Card
            title="10 报关取数口径"
            hint="这两条就是清关时真正被调用的接口。没有生效版本时它们返回 409，不会外发半成品。"
          >
            <div className="space-y-3">
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => void peekEffective()}
                  disabled={busy}
                >
                  <RefreshCw className="h-4 w-4" />
                  查看该目的地的生效版本
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => void peekResolved()}
                  disabled={busy}
                >
                  <RefreshCw className="h-4 w-4" />
                  查看报关将拿到的合成资料
                </Button>
              </div>
              {effectiveError && (
                <div className="border-warning-soft bg-warning-soft/40 rounded-md border px-3 py-2 text-sm">
                  <p className="text-warning text-copy-13 font-medium">取不到生效版本：</p>
                  <p className="text-muted-foreground mt-1 text-xs">{effectiveError}</p>
                </div>
              )}
              {effective && (
                <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <Metric label="版本号" value={`v${effective.versionNo}`} />
                  <Metric label="判定时刻" value={effective.effectiveAt} />
                  <Metric label="目的地税号" value={effective.destinationHsCode || '沿用基础'} />
                  <Metric label="认证要求" value={`${effective.requirements.length} 项`} />
                </dl>
              )}

              {resolveError && (
                <div className="border-destructive-soft bg-destructive-soft/40 rounded-md border px-3 py-2 text-sm">
                  <p className="text-destructive text-copy-13 font-medium">合成失败：</p>
                  <p className="text-muted-foreground mt-1 text-xs">{resolveError}</p>
                </div>
              )}
              {resolved && (
                <div className="space-y-3">
                  <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                    <Metric
                      label="HS 编码"
                      value={`${resolved.hsCode}（${sourceLabel(resolved.hsCodeSource)}）`}
                    />
                    <Metric
                      label="申报品名"
                      value={`${resolved.declaredName}（${sourceLabel(resolved.declaredNameSource)}）`}
                    />
                    <Metric label="法定计量单位" value={`${resolved.statutoryUnitCode}（基础）`} />
                    <Metric
                      label="要素状态"
                      value={`${resolved.declarationElementStatus}（${sourceLabel(
                        resolved.declarationElementsSource,
                      )}）`}
                    />
                  </dl>
                  <p className="text-muted-foreground text-xs">{resolutionText(resolved)}</p>
                </div>
              )}
            </div>
          </Card>
        </>
      )}

      {/* 二次确认：发布把这一版变成下游要用的那一份，不能一点就生效。 */}
      <Modal open={confirmPublish} onOpenChange={(open) => !open && setConfirmPublish(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>确认发布这一版？</ModalTitle>
            <ModalDescription>
              发布后，10 报关在清关时引用的就是这个目的地的版本，
              并且它会参与生效区间的重叠判定（同一 SKU 同一目的地同一时刻只能有一个生效版本）。
              发布不可撤回 —— 已发布的版本只能「让下一个版本接替」或「向前关闭生效区间」，
              不能改内容，也不能删除。要停止使用请发布新版本让新版本接替。
            </ModalDescription>
          </ModalHeader>
          <ModalFooter>
            <Button variant="outline" onClick={() => setConfirmPublish(false)}>
              取消
            </Button>
            <Button onClick={() => void runPublish()} disabled={busy}>
              {busy ? '处理中…' : '确认发布'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border p-3">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="mt-1 text-sm font-medium tabular-nums">{value}</dd>
    </div>
  );
}

/** 版本列表里的一格字段：标签 + 值（值的样式由内容决定：覆盖用 accent，沿用用 muted）。 */
function FieldValue({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  );
}

/** 与同目录其他工作区（customs-attribute / physical-safety）同形状的分区卡。 */
function Card({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="bg-card rounded-lg border p-4">
      <h3 className="font-medium">{title}</h3>
      {hint && <p className="text-muted-foreground mt-1 text-xs">{hint}</p>}
      <div className="mt-3">{children}</div>
    </div>
  );
}
