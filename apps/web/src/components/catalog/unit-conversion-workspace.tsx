'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import {
  ArrowLeft,
  Calculator,
  Plus,
  RefreshCw,
  Save,
  Scale,
  Snowflake,
  Trash2,
  TriangleAlert,
} from 'lucide-react';
import type {
  UnitConversionGroup,
  UnitConvertResult,
  UnitOfMeasure,
  UnitOverview,
  UnitPanel,
  UnitSnapshot,
} from '../../../../../packages/api-contracts/src/index';
import {
  addConversion,
  convert,
  createUnit,
  deleteUnit,
  dimensionLabels,
  dimensions,
  documentTypeLabels,
  documentTypes,
  draftToConversionInput,
  draftToUnitInput,
  emptyConversionDraft,
  emptyUnitDraft,
  errorText,
  fetchOverview,
  fetchPanel,
  fetchUnits,
  freezeSnapshot,
  isConflict,
  isNotFound,
  quantityProblem,
  ratioSummary,
  snapshotLineText,
  timestamp,
  unregisteredHint,
  unitDraft,
  updateUnit,
  validateConversion,
  validateUnit,
  windowText,
  type ConversionDraft,
  type UnitDraft,
} from './unit-conversion-model';
import { Editor, Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.1.2.4 单位与换算工作区。
 *
 * <p>分区按「一次换算治理要走的顺序」排：先看字典与缺口（概览）→ 维护单位字典 →
 * 配某个 SKU 的换算版本 → 试算与冻结单据。
 *
 * <p>三条界面纪律：
 *
 * <ul>
 *   <li>**基础单位不在换算表里**。界面上基础单位单独一行，标注它来自 SKU 自身；
 *       其余单位才在换算分组里。混在一起会让人以为"基础单位也能配比例"。
 *   <li>**当前生效与历史版本分开标注**。每一版都带生效区间，当前生效的那版打标；
 *       界面上永远能看到"现在用的是几比几"，而不是只看到一串历史比例。
 *   <li>**「没有」不画成 0**。未登记基础单位的 SKU 数、开放生效的版本数，
 *       都和自己的含义一起出现，避免把治理缺口当成正常。
 * </ul>
 */
export function UnitConversionWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('catalog.sku.read');
  const writable = session.permissions.includes('catalog.sku.update');

  const [overview, setOverview] = useState<UnitOverview | null>(null);
  const [units, setUnits] = useState<UnitOfMeasure[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editingUnit, setEditingUnit] = useState<{ id: string | null; draft: UnitDraft } | null>(
    null,
  );
  const [unitError, setUnitError] = useState('');
  const [unitBusy, setUnitBusy] = useState(false);

  const [skuId, setSkuId] = useState('');
  const [panel, setPanel] = useState<UnitPanel | null>(null);
  const [panelBusy, setPanelBusy] = useState(false);
  const [panelMissing, setPanelMissing] = useState(false);
  const [panelError, setPanelError] = useState('');
  const [editingConversion, setEditingConversion] = useState<ConversionDraft | null>(null);
  const [conversionError, setConversionError] = useState('');

  const [calc, setCalc] = useState({ quantity: '1', unitCode: '', role: 'PURCHASE', asOf: '' });
  const [calcResult, setCalcResult] = useState<UnitConvertResult | null>(null);
  const [calcError, setCalcError] = useState('');
  const [calcBusy, setCalcBusy] = useState(false);

  const [docType, setDocType] = useState('PURCHASE');
  const [docRef, setDocRef] = useState('');
  const [docQuantity, setDocQuantity] = useState('1');
  const [docUnit, setDocUnit] = useState('');
  const [snapshot, setSnapshot] = useState<UnitSnapshot | null>(null);
  const [snapshotError, setSnapshotError] = useState('');
  const [snapshotBusy, setSnapshotBusy] = useState(false);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [nextOverview, nextUnits] = await Promise.all([fetchOverview(), fetchUnits()]);
      setOverview(nextOverview);
      setUnits(nextUnits);
    } catch (e) {
      setError(errorText(e, '单位与换算加载失败'));
    } finally {
      setLoading(false);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadPanel = useCallback(async () => {
    if (!skuId.trim()) {
      setPanel(null);
      return;
    }
    setPanelBusy(true);
    setPanelError('');
    setPanelMissing(false);
    setCalcResult(null);
    try {
      const next = await fetchPanel(skuId.trim());
      setPanel(next);
      setPanelMissing(false);
      setCalc((current) => ({ ...current, unitCode: current.unitCode || next.baseUnit }));
      setDocUnit((current) => current || next.baseUnit);
    } catch (e) {
      setPanel(null);
      if (isNotFound(e)) setPanelMissing(true);
      else setPanelError(errorText(e, 'SKU 单位与换算加载失败'));
    } finally {
      setPanelBusy(false);
    }
  }, [skuId]);

  const enabledUnits = useMemo(() => units.filter((u) => u.enabled), [units]);

  if (!readable)
    return (
      <Section frame>
        <p className="text-sm">当前角色没有 SKU 查看权限，请联系租户管理员。</p>
      </Section>
    );

  if (loading)
    return (
      <Section frame>
        <p role="status" className="text-muted-foreground text-sm">
          正在加载单位与换算…
        </p>
      </Section>
    );

  if (error || !overview)
    return (
      <Section frame>
        <BackLink />
        <p role="alert" className="text-destructive text-sm">
          {error || '单位与换算加载失败'}
        </p>
        <Button variant="outline" size="sm" className="mt-3" onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          重试
        </Button>
      </Section>
    );

  return (
    <Section frame>
      <BackLink />

      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-semibold">单位与换算</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            单位字典、SKU 的采购/销售/包装单位换算比例（1 箱 = 12 件）、按生效日期取版本，
            以及单据生成时的比例冻结快照。供 06 采购、07 库存、08 拣货统一到基础单位记账。
          </p>
        </div>
        <div className="flex items-center gap-2">
          {writable && (
            <Button size="sm" onClick={() => setEditingUnit({ id: null, draft: emptyUnitDraft() })}>
              <Plus className="h-4 w-4" />
              新建单位
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </header>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="单位字典"
          value={`${overview.enabledUnitCount} / ${overview.unitCount}`}
          hint="启用中 / 全部"
        />
        <Stat label="换算版本" value={overview.conversionCount} hint="含历史版本，全部可读" />
        <Stat
          label="基础单位未登记"
          value={overview.unregisteredUnitCount}
          hint="这些 SKU 无法配置换算"
        />
        <Stat label="单据快照" value={overview.snapshotCount} hint="已冻结，不可修改" />
      </div>

      <div className="border-warning/30 bg-warning/5 text-warning rounded-md border p-3 text-sm">
        <p className="flex items-center gap-2 font-medium">
          <TriangleAlert className="h-4 w-4" />
          量纲校验与字典覆盖
        </p>
        <p className="mt-1 text-xs">{unregisteredHint(overview.unregisteredUnitCount)}</p>
        <p className="mt-1 text-xs">
          量纲不同的单位之间不允许换算（例如「件」不能换算成「千克」），在新建换算版本时就会被拒绝。
        </p>
      </div>

      {/* 单位字典 */}
      <Card
        title="单位字典"
        hint="单位编码允许中文（件 / 箱 / 千克）——它不是 SKU 内部编码。编码与量纲建后不可改：换算行与已冻结快照按编码引用它。"
      >
        {units.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有任何单位定义。没有字典就无法配置换算（量纲无从校验），请先新建基础单位。
          </p>
        ) : (
          <div className="space-y-2">
            {units.map((unit) => (
              <div
                key={unit.id}
                className="border-control-border bg-muted/30 flex flex-wrap items-center gap-3 rounded-md border p-3"
              >
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                    {unit.name}
                    <Badge tone="neutral">{unit.code}</Badge>
                    <Badge tone="neutral">{unit.dimensionLabel}</Badge>
                    {unit.enabled ? (
                      <Badge tone="success">启用中</Badge>
                    ) : (
                      <Badge tone="neutral">已停用</Badge>
                    )}
                  </p>
                  <p className="text-muted-foreground mt-1 text-xs">
                    数量精度 {unit.scale} 位小数 · 采购 {unit.allowPurchase ? '可用' : '不可用'} ·
                    销售 {unit.allowSale ? '可用' : '不可用'} · 存储{' '}
                    {unit.allowStorage ? '可用' : '不可用'} · {unit.conversionCount} 条换算版本引用
                  </p>
                </div>
                {writable && (
                  <>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setEditingUnit({ id: unit.id, draft: unitDraft(unit) })}
                    >
                      编辑
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={unitBusy || unit.conversionCount > 0}
                      onClick={() => void removeUnit(unit)}
                    >
                      <Trash2 className="h-4 w-4" />
                      删除
                    </Button>
                  </>
                )}
              </div>
            ))}
          </div>
        )}
        {units.some((u) => !u.enabled) && (
          <p className="text-muted-foreground mt-2 text-xs">
            停用的单位不再出现在新建换算的候选里，但历史换算版本与已冻结快照仍可读——停用挡住的是「新建」，不是「抹掉过去」。
          </p>
        )}
      </Card>

      {/* 单 SKU 换算版本 */}
      <Card
        title="单个 SKU 的换算版本"
        hint="基础单位来自 SKU 自身，比例恒为 1，因此不占换算行；这里维护的是其他单位相对基础单位的比例。同一单位同一角色的生效区间不能重叠。"
      >
        <div className="flex flex-wrap items-end gap-2">
          <div className="min-w-64 flex-1">
            <Field label="SKU ID">
              <input
                className={control}
                value={skuId}
                placeholder="粘贴 SKU ID"
                onChange={(e) => {
                  setSkuId(e.target.value);
                  setPanel(null);
                  setPanelMissing(false);
                  setPanelError('');
                }}
              />
            </Field>
          </div>
          <Button size="sm" variant="outline" disabled={panelBusy} onClick={() => void loadPanel()}>
            打开
          </Button>
        </div>

        {panelBusy && (
          <p role="status" className="text-muted-foreground mt-3 text-sm">
            正在加载…
          </p>
        )}
        {panelMissing && (
          <p role="alert" className="text-destructive mt-3 text-sm">
            SKU 不存在或当前角色没有访问权限。这两种情况返回同一个结果，避免用 403 / 404
            的差别探测别人的 SKU 是否存在。
          </p>
        )}
        {panelError && (
          <p role="alert" className="text-destructive mt-3 text-sm">
            {panelError}
          </p>
        )}

        {panel && (
          <div className="mt-4 space-y-4">
            <div className="border-control-border rounded-md border p-3">
              <p className="text-sm font-medium">基础单位</p>
              <p className="text-muted-foreground mt-1 text-xs">
                {panel.baseUnit}
                {panel.baseUnitName ? `（${panel.baseUnitName}）` : ''} · 来自 SKU 自身属性，
                比例恒为 1，不在换算表里维护。基础单位本身在 3.1.2.2 的 SKU 详情里修改。
              </p>
              {!panel.baseUnitRegistered && (
                <p className="text-destructive mt-2 text-xs">
                  该基础单位尚未登记到单位字典，量纲校验对它无法进行，因此这个 SKU
                  现在不能配置换算。 请先在上方字典里补登记。
                </p>
              )}
            </div>

            <div className="border-control-border rounded-md border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-sm font-medium">换算版本</p>
                {writable && (
                  <Button
                    size="sm"
                    onClick={() => {
                      setEditingConversion(emptyConversionDraft(panel.baseUnit));
                      setConversionError('');
                    }}
                  >
                    <Plus className="h-4 w-4" />
                    新增版本
                  </Button>
                )}
              </div>
              {panel.groups.length === 0 ? (
                <p className="text-muted-foreground mt-2 text-sm">
                  这个 SKU 还没有任何换算关系。没有换算时，单据只能用基础单位记账；
                  若要用箱/套等采购单位，必须先配置换算版本。
                </p>
              ) : (
                <div className="mt-2 space-y-3">
                  {panel.groups.map((group) => (
                    <ConversionGroupCard key={`${group.unitCode}-${group.role}`} group={group} />
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </Card>

      {/* 换算试算 */}
      {panel && (
        <Card
          title="基础数量换算试算"
          hint="给定 SKU、数量、单位与生效日期，返回统一到基础单位后的数量。这是纯计算，不写库。查不到生效版本会直接报错，不回退到旧比例。"
        >
          <div className="grid gap-3 sm:grid-cols-[repeat(4,1fr)_auto]">
            <Field label="数量">
              <input
                className={control}
                value={calc.quantity}
                onChange={(e) => setCalc({ ...calc, quantity: e.target.value })}
              />
            </Field>
            <Field label="单位">
              <input
                className={control}
                list="unit-codes"
                value={calc.unitCode}
                placeholder="箱 / 件"
                onChange={(e) => setCalc({ ...calc, unitCode: e.target.value })}
              />
            </Field>
            <Field label="角色" hint="留「全部」则忽略角色">
              <select
                className={control}
                value={calc.role}
                onChange={(e) => setCalc({ ...calc, role: e.target.value })}
              >
                <option value="">全部</option>
                {['PURCHASE', 'SALE', 'PACKAGING'].map((role) => (
                  <option key={role} value={role}>
                    {role === 'PURCHASE' ? '采购单位' : role === 'SALE' ? '销售单位' : '包装单位'}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="生效日期" hint="留空取今天">
              <input
                className={control}
                type="date"
                value={calc.asOf}
                onChange={(e) => setCalc({ ...calc, asOf: e.target.value })}
              />
            </Field>
            <div className="flex items-end">
              <Button
                size="sm"
                disabled={calcBusy || quantityProblem(calc.quantity) !== null}
                onClick={() => void runConvert()}
              >
                <Calculator className="h-4 w-4" />
                换算
              </Button>
            </div>
          </div>
          <datalist id="unit-codes">
            {units.map((unit) => (
              <option key={unit.id} value={unit.code}>
                {unit.name}
              </option>
            ))}
          </datalist>
          {calcError && (
            <p role="alert" className="text-destructive mt-3 text-sm">
              {calcError}
            </p>
          )}
          {calcResult && (
            <div className="border-control-border bg-muted/30 mt-3 rounded-md border p-3">
              <p className="text-sm">
                <span className="tabular-nums">{calcResult.quantity}</span> {calcResult.unitCode} ={' '}
                <span className="font-medium tabular-nums">{calcResult.baseQuantity}</span>{' '}
                {calcResult.baseUnit}
              </p>
              <p className="text-muted-foreground mt-1 text-xs">
                比例 1 {calcResult.unitCode} ={' '}
                <span className="tabular-nums">{calcResult.quantityPerBase}</span> · 命中版本{' '}
                {windowText(calcResult.effectiveFrom, calcResult.effectiveTo)} · 角色{' '}
                {calcResult.roleLabel}
                {calcResult.role === 'BASE' ? '（直接用基础单位，比例恒为 1）' : ''}
              </p>
            </div>
          )}
        </Card>
      )}

      {/* 单据冻结快照 */}
      {panel && (
        <Card
          title="单据冻结快照"
          hint="单据生成时把当时生效的比例连同换算结果一起落库，并禁止修改删除——之后改比例不影响已发生单据。同一单据同一行重复冻结会返回原行，不重复计数。"
        >
          <div className="grid gap-3 sm:grid-cols-[repeat(4,1fr)_auto]">
            <Field label="单据类型">
              <select
                className={control}
                value={docType}
                onChange={(e) => setDocType(e.target.value)}
              >
                {documentTypes.map((type) => (
                  <option key={type} value={type}>
                    {documentTypeLabels[type]}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="单据号">
              <input
                className={control}
                value={docRef}
                placeholder="PO-2026-0001"
                onChange={(e) => setDocRef(e.target.value)}
              />
            </Field>
            <Field label="单位">
              <input
                className={control}
                value={docUnit}
                placeholder="箱"
                onChange={(e) => setDocUnit(e.target.value)}
              />
            </Field>
            <Field label="数量">
              <input
                className={control}
                value={docQuantity}
                onChange={(e) => setDocQuantity(e.target.value)}
              />
            </Field>
            <div className="flex items-end">
              <Button
                size="sm"
                disabled={
                  snapshotBusy || docRef.trim() === '' || quantityProblem(docQuantity) !== null
                }
                onClick={() => void runFreeze()}
              >
                <Snowflake className="h-4 w-4" />
                冻结
              </Button>
            </div>
          </div>
          {snapshotError && (
            <p role="alert" className="text-destructive mt-3 text-sm">
              {snapshotError}
            </p>
          )}
          {snapshot && (
            <div className="mt-3">
              <p className="text-sm font-medium">
                {snapshot.documentTypeLabel} {snapshot.documentRef} · 新冻结 {snapshot.createdCount}{' '}
                行 · 复用 {snapshot.reusedCount} 行
              </p>
              <p className="text-muted-foreground mt-1 text-xs">
                业务时间 {timestamp(snapshot.occurredAt)}
                。比例与结果都冻结在行上，之后改换算比例不影响这里。
              </p>
              <table className="mt-2 w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="w-12 py-1 font-normal">行</th>
                    <th className="py-1 font-normal">SKU</th>
                    <th className="py-1 font-normal">换算</th>
                    <th className="py-1 font-normal">命中版本</th>
                    <th className="py-1 font-normal">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {snapshot.lines.map((line) => (
                    <tr key={line.lineNo} className="border-control-border border-t">
                      <td className="py-1.5 tabular-nums">{line.lineNo}</td>
                      <td className="py-1.5">{line.skuCode}</td>
                      <td className="py-1.5 tabular-nums">{snapshotLineText(line)}</td>
                      <td className="text-muted-foreground py-1.5 text-xs">
                        {windowText(line.effectiveFrom, line.effectiveTo)}
                      </td>
                      <td className="py-1.5">
                        <Badge tone={line.created ? 'success' : 'neutral'}>
                          {line.created ? '本次冻结' : '此前已冻结'}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}

      {panel && panel.snapshots.length > 0 && (
        <Card title="该 SKU 的快照留痕" hint="这个 SKU 参与过的最近单据及其当时冻结的比例。">
          <ul className="space-y-1 text-xs">
            {panel.snapshots.map((line) => (
              <li
                key={`${line.lineNo}-${line.occurredAt}-${line.unitCode}`}
                className="text-muted-foreground"
              >
                {timestamp(line.occurredAt)} · {line.skuCode} · {line.roleLabel} ·{' '}
                <span className="tabular-nums">{snapshotLineText(line)}</span> ·{' '}
                {windowText(line.effectiveFrom, line.effectiveTo)}
              </li>
            ))}
          </ul>
        </Card>
      )}

      {editingUnit && (
        <UnitEditor
          id={editingUnit.id}
          draft={editingUnit.draft}
          error={unitError}
          onChange={(draft) => setEditingUnit({ id: editingUnit.id, draft })}
          onClose={() => {
            setEditingUnit(null);
            setUnitError('');
          }}
          onSubmit={async (draft) => {
            const problems = validateUnit(draft, editingUnit.id === null);
            if (problems.length > 0) {
              setUnitError(problems.join('；'));
              return;
            }
            setUnitBusy(true);
            setUnitError('');
            try {
              if (editingUnit.id === null) await createUnit(draftToUnitInput(draft));
              else await updateUnit(editingUnit.id, draftToUnitInput(draft));
              setEditingUnit(null);
              await load();
            } catch (e) {
              setUnitError(
                isConflict(e)
                  ? errorText(e, '单位已被他人修改，请刷新后重试')
                  : errorText(e, '单位保存失败'),
              );
            } finally {
              setUnitBusy(false);
            }
          }}
        />
      )}

      {editingConversion && panel && (
        <ConversionEditor
          draft={editingConversion}
          baseUnit={panel.baseUnit}
          units={enabledUnits}
          error={conversionError}
          onChange={setEditingConversion}
          onClose={() => {
            setEditingConversion(null);
            setConversionError('');
          }}
          onSubmit={async (draft) => {
            const problems = validateConversion(draft, panel.baseUnit, units);
            if (problems.length > 0) {
              setConversionError(problems.join('；'));
              return;
            }
            setConversionError('');
            try {
              await addConversion(panel.skuId, draftToConversionInput(draft));
              setEditingConversion(null);
              await loadPanel();
            } catch (e) {
              setConversionError(
                isConflict(e)
                  ? errorText(e, '换算版本生效区间与已有版本重叠')
                  : errorText(e, '换算版本保存失败'),
              );
            }
          }}
        />
      )}
    </Section>
  );

  // ------------------------------------------------------------ 动作

  async function removeUnit(unit: UnitOfMeasure) {
    setUnitBusy(true);
    setUnitError('');
    try {
      await deleteUnit(unit.id, unit.version);
      await load();
    } catch (e) {
      setUnitError(errorText(e, '单位删除失败'));
    } finally {
      setUnitBusy(false);
    }
  }

  async function runConvert() {
    if (!panel) return;
    setCalcBusy(true);
    setCalcError('');
    setCalcResult(null);
    try {
      const next = await convert({
        skuId: panel.skuId,
        quantity: Number(calc.quantity),
        unitCode: calc.unitCode.trim(),
        role: calc.role === '' ? null : calc.role,
        asOf: calc.asOf === '' ? null : calc.asOf,
      });
      setCalcResult(next);
    } catch (e) {
      setCalcError(errorText(e, '换算失败'));
    } finally {
      setCalcBusy(false);
    }
  }

  async function runFreeze() {
    if (!panel) return;
    setSnapshotBusy(true);
    setSnapshotError('');
    setSnapshot(null);
    try {
      const next = await freezeSnapshot({
        documentType: docType,
        documentRef: docRef.trim(),
        occurredAt: null,
        lines: [
          {
            lineNo: 1,
            skuId: panel.skuId,
            unitCode: docUnit.trim(),
            role: calc.role === '' ? 'PURCHASE' : calc.role,
            quantity: Number(docQuantity),
          },
        ],
      });
      setSnapshot(next);
      await loadPanel();
    } catch (e) {
      setSnapshotError(errorText(e, '单据冻结失败'));
    } finally {
      setSnapshotBusy(false);
    }
  }
}

// ------------------------------------------------------------ 组件

/** 一个「单位 + 角色」下的版本列表：当前生效的那版打标，历史版本保留可读。 */
function ConversionGroupCard({ group }: { group: UnitConversionGroup }) {
  return (
    <div className="border-control-border bg-muted/20 rounded-md border p-3">
      <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
        <Scale className="h-4 w-4" />
        {group.unitName ?? group.unitCode}
        <Badge tone="neutral">{group.unitCode}</Badge>
        <Badge tone="neutral">{group.roleLabel}</Badge>
        {!group.registered && <Badge tone="warning">未登记</Badge>}
      </p>
      <p className="text-muted-foreground mt-1 text-xs">{ratioSummary(group)}</p>
      <table className="mt-2 w-full text-sm">
        <thead>
          <tr className="text-muted-foreground text-left text-xs">
            <th className="py-1 font-normal">生效区间</th>
            <th className="py-1 font-normal">1 单位 = 多少基础单位</th>
            <th className="py-1 font-normal">快照</th>
          </tr>
        </thead>
        <tbody>
          {group.versions.map((version) => (
            <tr key={version.id} className="border-control-border border-t">
              <td className="py-1.5 text-xs">
                {windowText(version.effectiveFrom, version.effectiveTo)}{' '}
                {version.current && <Badge tone="success">当前生效</Badge>}
              </td>
              <td className="py-1.5 tabular-nums">{version.quantityPerBase}</td>
              <td className="text-muted-foreground py-1.5 text-xs">
                {version.snapshotted ? '已被单据冻结' : '尚未被单据使用'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function UnitEditor({
  id,
  draft,
  error,
  onChange,
  onClose,
  onSubmit,
}: {
  id: string | null;
  draft: UnitDraft;
  error: string;
  onChange: (draft: UnitDraft) => void;
  onClose: () => void;
  onSubmit: (draft: UnitDraft) => Promise<void>;
}) {
  return (
    <Editor
      title={id === null ? '新建单位' : '编辑单位'}
      description="单位编码与量纲建后不可改：换算行与已冻结快照按编码引用它们，改了会让历史数据指向不存在的定义。"
      close={onClose}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="单位编码" hint="允许中文，如 件 / 箱 / 千克">
          <input
            className={control}
            value={draft.code}
            readOnly={id !== null}
            disabled={id !== null}
            onChange={(e) => onChange({ ...draft, code: e.target.value })}
          />
        </Field>
        <Field label="单位名称">
          <input
            className={control}
            value={draft.name}
            onChange={(e) => onChange({ ...draft, name: e.target.value })}
          />
        </Field>
        <Field label="量纲" hint="量纲不同的单位之间不允许换算">
          <select
            className={control}
            value={draft.dimension}
            onChange={(e) => onChange({ ...draft, dimension: e.target.value })}
          >
            {dimensions.map((dimension) => (
              <option key={dimension} value={dimension}>
                {dimensionLabels[dimension]}
              </option>
            ))}
          </select>
        </Field>
        <Field label="数量精度（0~6 位小数）">
          <input
            className={control}
            value={draft.scale}
            onChange={(e) => onChange({ ...draft, scale: e.target.value })}
          />
        </Field>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-4">
        {(
          [
            ['allowPurchase', '可用于采购'],
            ['allowSale', '可用于销售'],
            ['allowStorage', '可用于存储'],
            ['enabled', '启用'],
          ] as const
        ).map(([key, label]) => (
          <label key={key} className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={draft[key]}
              onChange={(e) => onChange({ ...draft, [key]: e.target.checked })}
            />
            {label}
          </label>
        ))}
      </div>
      {error && (
        <p role="alert" className="text-destructive mt-3 text-sm">
          {error}
        </p>
      )}
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={onClose}>
          取消
        </Button>
        <Button size="sm" onClick={() => void onSubmit(draft)}>
          <Save className="h-4 w-4" />
          保存
        </Button>
      </div>
    </Editor>
  );
}

function ConversionEditor({
  draft,
  baseUnit,
  units,
  error,
  onChange,
  onClose,
  onSubmit,
}: {
  draft: ConversionDraft;
  baseUnit: string;
  units: UnitOfMeasure[];
  error: string;
  onChange: (draft: ConversionDraft) => void;
  onClose: () => void;
  onSubmit: (draft: ConversionDraft) => Promise<void>;
}) {
  return (
    <Editor
      title="新增换算版本"
      description={`比例是「1 个该单位 = 多少个基础单位」。基础单位「${baseUnit}」比例恒为 1，不需要换算行。生效区间不能与已有版本重叠。`}
      close={onClose}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="单位" hint={`基础单位 ${baseUnit} 不在候选内`}>
          <select
            className={control}
            value={draft.unitCode}
            onChange={(e) => onChange({ ...draft, unitCode: e.target.value })}
          >
            <option value="">请选择单位</option>
            {units
              .filter((u) => u.code !== baseUnit)
              .map((unit) => (
                <option key={unit.id} value={unit.code}>
                  {unit.name}（{unit.code}）· {unit.dimensionLabel}
                </option>
              ))}
          </select>
        </Field>
        <Field label="角色">
          <select
            className={control}
            value={draft.role}
            onChange={(e) => onChange({ ...draft, role: e.target.value })}
          >
            <option value="PURCHASE">采购单位（06 采购）</option>
            <option value="SALE">销售单位</option>
            <option value="PACKAGING">包装单位</option>
          </select>
        </Field>
        <Field label="换算比例" hint="1 箱 = 12 件则填 12">
          <input
            className={control}
            value={draft.quantityPerBase}
            onChange={(e) => onChange({ ...draft, quantityPerBase: e.target.value })}
          />
        </Field>
        <Field label="生效起始日">
          <input
            className={control}
            type="date"
            value={draft.effectiveFrom}
            onChange={(e) => onChange({ ...draft, effectiveFrom: e.target.value })}
          />
        </Field>
        <Field label="生效截止日" hint="留空表示至今仍生效">
          <input
            className={control}
            type="date"
            value={draft.effectiveTo}
            onChange={(e) => onChange({ ...draft, effectiveTo: e.target.value })}
          />
        </Field>
      </div>
      {error && (
        <p role="alert" className="text-destructive mt-3 text-sm">
          {error}
        </p>
      )}
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={onClose}>
          取消
        </Button>
        <Button size="sm" onClick={() => void onSubmit(draft)}>
          <Save className="h-4 w-4" />
          保存
        </Button>
      </div>
    </Editor>
  );
}

function Stat({ label, value, hint }: { label: string; value: number | string; hint?: string }) {
  return (
    <div className="bg-card rounded-lg border p-3">
      <p className="text-muted-foreground text-xs">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
      {hint && <p className="text-muted-foreground mt-1 text-xs">{hint}</p>}
    </div>
  );
}

function Section({ children, frame = false }: { children: React.ReactNode; frame?: boolean }) {
  return <section className={frame ? 'space-y-4' : undefined}>{children}</section>;
}

function BackLink() {
  return (
    <Link
      href="/products/sku"
      className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 text-sm"
    >
      <ArrowLeft className="h-4 w-4" />
      返回 SKU 列表
    </Link>
  );
}

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
