'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import {
  ArrowLeft,
  Barcode,
  Ban,
  ListChecks,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  ScanLine,
  Trash2,
  TriangleAlert,
  Upload,
  X,
} from 'lucide-react';
import type {
  SkuCodeBatchResult,
  SkuCodeOverview,
  SkuCodePanel,
  SkuCodeRule,
  SkuIdentifierImportResult,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionLabels,
  addIdentifier,
  batchSummary,
  barcodeProblem,
  createRule,
  deleteIdentifier,
  draftToRuleInput,
  emptyRuleDraft,
  errorText,
  fetchBatch,
  fetchOverview,
  fetchPanel,
  fetchRules,
  importIdentifiers,
  importSummary,
  isConflict,
  isNotFound,
  outcomeLabels,
  parseImportText,
  primaryIdentifier,
  ruleDraft,
  runBatch,
  saveCode,
  schemes,
  schemeLabels,
  sourceLabels,
  timestamp,
  updateRule,
  validateRule,
  type RuleDraft,
} from './sku-code-model';
import { Editor, Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.1.2.3 编码与条码工作区。
 *
 * <p>分区按「一次编码治理要走的顺序」排：先看覆盖率（概览）→ 定规则 → 补编码 → 管条码。
 * 概览里的「未编码 SKU」是这套东西的起点，先让人知道有多少要治理。
 *
 * <p>两条界面纪律：
 *
 * <ul>
 *   <li>**冲突拒绝清单逐行可读**。批量导入/补齐返回的每一行都带行号与原因，界面原样呈现，
 *       不能只弹一个「有 5 行失败」——运营需要知道改哪一行的哪一列。
 *   <li>**没接入的东西不画成 0**。编码计数为 0 表示真的一个都没有；未编码 SKU 数为 0
 *       表示全都有编码。两者含义不同，界面上分别写明。
 * </ul>
 */
export function SkuCodeWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('catalog.sku.read');
  const writable = session.permissions.includes('catalog.sku.update');

  const [overview, setOverview] = useState<SkuCodeOverview | null>(null);
  const [rules, setRules] = useState<SkuCodeRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editingRule, setEditingRule] = useState<{ id: string | null; draft: RuleDraft } | null>(
    null,
  );
  const [batchRuleId, setBatchRuleId] = useState('');
  const [batchAction, setBatchAction] = useState<'GENERATE' | 'BACKFILL'>('BACKFILL');
  const [batchResult, setBatchResult] = useState<SkuCodeBatchResult | null>(null);
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchError, setBatchError] = useState('');
  const [importText, setImportText] = useState('');
  const [importResult, setImportResult] = useState<SkuIdentifierImportResult | null>(null);
  const [importBusy, setImportBusy] = useState(false);
  const [importError, setImportError] = useState('');
  const [skuId, setSkuId] = useState('');
  const [panel, setPanel] = useState<SkuCodePanel | null>(null);
  const [panelMissing, setPanelMissing] = useState(false);
  const [panelError, setPanelError] = useState('');
  const [panelBusy, setPanelBusy] = useState(false);
  const [codeDraft, setCodeDraft] = useState('');
  const [scanDraft, setScanDraft] = useState({ scheme: 'EAN13', value: '', isPrimary: false });

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [nextOverview, nextRules] = await Promise.all([fetchOverview(), fetchRules()]);
      setOverview(nextOverview);
      setRules(nextRules);
      setBatchRuleId((current) => current || (nextRules.find((r) => r.enabled)?.id ?? ''));
    } catch (e) {
      setError(errorText(e, '编码与条码加载失败'));
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
    try {
      const next = await fetchPanel(skuId.trim());
      setPanel(next);
      setCodeDraft(next.internalCode);
      setPanelMissing(false);
    } catch (e) {
      setPanel(null);
      if (isNotFound(e)) setPanelMissing(true);
      else setPanelError(errorText(e, 'SKU 编码与条码加载失败'));
    } finally {
      setPanelBusy(false);
    }
  }, [skuId]);

  const scanProblem = useMemo(
    () => barcodeProblem(scanDraft.scheme, scanDraft.value),
    [scanDraft.scheme, scanDraft.value],
  );

  /** 批量只作用于概览里「未编码 SKU」的数量可见，所以执行前先把覆盖率摆在眼前。 */
  const selectedRule = rules.find((r) => r.id === batchRuleId) ?? null;

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
          正在加载编码与条码…
        </p>
      </Section>
    );

  if (error || !overview)
    return (
      <Section frame>
        <BackLink />
        <p role="alert" className="text-destructive text-sm">
          {error || '编码与条码加载失败'}
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
          <h2 className="text-xl font-semibold">编码与条码</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            内部 SKU 编码（手工输入 / 按规则自动生成 / 批量补齐）与外部条码
            （scheme + value 唯一），供 08 拣货扫码与 10 面单打印使用。
          </p>
        </div>
        <div className="flex items-center gap-2">
          {writable && (
            <Button
              size="sm"
              onClick={() => setEditingRule({ id: null, draft: emptyRuleDraft() })}
            >
              <Plus className="h-4 w-4" />
              新建规则
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </header>

      {/* 概览：编码治理的起点 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="SKU 总数" value={overview.totalSkus} />
        <Stat label="尚未编码" value={overview.uncodedSkus} hint="内部编码为空的 SKU，需要补齐" />
        <Stat label="外部条码" value={overview.identifierCount} hint="scheme + value 全租户唯一" />
        <Stat
          label="编码规则"
          value={`${overview.enabledRuleCount} / ${overview.ruleCount}`}
          hint="启用中 / 全部规则"
        />
      </div>

      <div className="border-warning/30 bg-warning/5 text-warning rounded-md border p-3 text-sm">
        <p className="flex items-center gap-2 font-medium">
          <TriangleAlert className="h-4 w-4" />
          编码与 seller_sku 是两回事
        </p>
        <p className="mt-1 text-xs">{overview.sellerSkuNote}</p>
      </div>

      {/* 编码规则 */}
      <Card
        title="编码规则"
        hint="规则 = 前缀 + 补零序号 + 后缀。序号由规则独占分配，同一规则内不会重号。"
      >
        {rules.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有编码规则。没有规则时只能手工输入编码，批量补齐与按规则生成都无从下手。
          </p>
        ) : (
          <div className="space-y-2">
            {rules.map((rule) => (
              <div
                key={rule.id}
                className="border-control-border bg-muted/30 flex flex-wrap items-center gap-3 rounded-md border p-3"
              >
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                    {rule.name}
                    <Badge tone="neutral">{rule.code}</Badge>
                    {rule.enabled ? (
                      <Badge tone="success">启用中</Badge>
                    ) : (
                      <Badge tone="neutral">已停用</Badge>
                    )}
                  </p>
                  <p className="text-muted-foreground mt-1 text-xs">
                    {rule.scopeLabel}
                    {rule.spuCode ? ` · ${rule.spuCode}` : ''} · 形状{' '}
                    <span className="tabular-nums">
                      {rule.prefix || '（无前缀）'}
                      {rule.seqWidth} 位序号
                      {rule.suffix || ''}
                    </span>
                    {' · 下一个序号 '}
                    <span className="tabular-nums">{rule.nextValue}</span>
                  </p>
                  <p className="text-muted-foreground mt-1 text-xs">
                    样例：
                    {rule.sampleCode ? (
                      <span className="text-foreground ml-1 tabular-nums">{rule.sampleCode}</span>
                    ) : (
                      <span className="text-destructive ml-1">展开后超出 64 位上限，请修改前后缀</span>
                    )}
                  </p>
                </div>
                {writable && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setEditingRule({ id: rule.id, draft: ruleDraft(rule) })}
                  >
                    <Pencil className="h-4 w-4" />
                    编辑
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* 批量补齐 / 按规则生成 */}
      <Card
        title="批量补齐与按规则生成"
        hint="补齐会把规则范围内「尚未按本规则赋码」的 SKU 规范化（允许覆盖旧编码并留痕）；按规则生成只填空编码，绝不覆盖既有编码。"
      >
        <div className="grid gap-3 sm:grid-cols-[1fr_auto_auto]">
          <Field label="选择规则">
            <select
              className={control}
              value={batchRuleId}
              onChange={(e) => {
                setBatchRuleId(e.target.value);
                setBatchResult(null);
                setBatchError('');
              }}
            >
              <option value="">请选择规则</option>
              {rules
                .filter((r) => r.enabled)
                .map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}（{r.code}）{r.spuCode ? ` · ${r.spuCode}` : ''}
                  </option>
                ))}
            </select>
          </Field>
          <Field label="方式">
            <select
              className={control}
              value={batchAction}
              onChange={(e) => {
                setBatchAction(e.target.value as 'GENERATE' | 'BACKFILL');
                setBatchResult(null);
                setBatchError('');
              }}
            >
              <option value="BACKFILL">批量补齐（按范围扫）</option>
              <option value="GENERATE">按规则生成（先在下方选中 SKU）</option>
            </select>
          </Field>
          <div className="flex items-end">
            {writable && (
              <Button
                size="sm"
                disabled={!batchRuleId || batchBusy}
                onClick={() => void executeBatch(false)}
              >
                <ListChecks className="h-4 w-4" />
                执行
              </Button>
            )}
            {writable && (
              <Button
                variant="outline"
                size="sm"
                className="ml-2"
                disabled={!batchRuleId || batchBusy}
                onClick={() => void executeBatch(true)}
              >
                预览
              </Button>
            )}
          </div>
        </div>
        <p className="text-muted-foreground mt-2 text-xs">
          {batchAction === 'BACKFILL'
            ? `补齐范围：${selectedRule?.scopeLabel ?? '规则适用范围'}内尚未按本规则赋码的 SKU。当前租户有 ${overview.uncodedSkus} 个 SKU 尚无编码。`
            : '按规则生成只处理下方「单个 SKU」里填的 ID，并且只填空编码——已有编码的会被跳过并给出原因。'}
        </p>
        {batchError && (
          <p role="alert" className="text-destructive mt-3 text-sm">
            {batchError}
          </p>
        )}
        {batchResult && (
          <BatchOutcome
            title={`${batchResult.dryRun ? '预览' : actionLabels[batchResult.action] ?? batchResult.action} · ${batchSummary(batchResult)}`}
            note={
              batchResult.dryRun
                ? '预览不写数据，也不消耗序号。确认无误后再点执行。'
                : '下方逐行列出每一行的处理结果；「被拒绝」的行按原因改完可以重新提交。'
            }
            result={batchResult}
            onReread={async () => {
              const reread = await fetchBatch(batchResult.batchId);
              setBatchResult(reread);
            }}
          />
        )}
      </Card>

      {/* 条码批量导入 */}
      <Card
        title="条码批量导入"
        hint="每行「SKU 编码 / scheme / 条码值 / 主条码(可选)」，用制表符、逗号或多个空格分隔。冲突逐行给出原因，不会整批回滚。"
      >
        <Field
          label="粘贴条码数据"
          hint="带 # 开头的行与空行会被跳过。scheme 支持 EAN8 / EAN13 / UPCA / UPCE / GTIN14 / CUSTOM，GTIN 家族会校验校验位。"
        >
          <textarea
            className={`${control} min-h-32 font-mono text-xs`}
            value={importText}
            placeholder={'SKU-001\tEAN13\t4006381333931\nSKU-001\tUPCA\t036000291452\t是'}
            onChange={(e) => {
              setImportText(e.target.value);
              setImportResult(null);
              setImportError('');
            }}
          />
        </Field>
        {writable && (
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Button size="sm" disabled={importBusy} onClick={() => void executeImport(false)}>
              <Upload className="h-4 w-4" />
              导入
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={importBusy}
              onClick={() => void executeImport(true)}
            >
              预览
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setImportResult(null)}>
              <X className="h-4 w-4" />
              清空结果
            </Button>
          </div>
        )}
        {importError && (
          <p role="alert" className="text-destructive mt-3 text-sm">
            {importError}
          </p>
        )}
        {importResult && (
          <div className="mt-3">
            <p className="text-sm font-medium">
              {importResult.dryRun ? '预览' : '导入'} · {importSummary(importResult)}
            </p>
            <p className="text-muted-foreground mt-1 text-xs">
              逐行结果如下。被拒绝的行按原因改完可以重新提交；行号对应你粘贴的原始行。
            </p>
            <table className="mt-2 w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="w-12 py-1 font-normal">行</th>
                  <th className="py-1 font-normal">scheme</th>
                  <th className="py-1 font-normal">条码值</th>
                  <th className="py-1 font-normal">结果</th>
                  <th className="py-1 font-normal">原因</th>
                </tr>
              </thead>
              <tbody>
                {importResult.items.map((item) => (
                  <tr key={item.rowNo} className="border-control-border border-t">
                    <td className="py-1.5 tabular-nums">{item.rowNo}</td>
                    <td className="py-1.5">{schemeLabels[item.scheme] ?? item.scheme}</td>
                    <td className="py-1.5 break-all tabular-nums">{item.value}</td>
                    <td className="py-1.5">
                      <Badge tone={outcomeTone(item.outcome)}>{outcomeLabels[item.outcome]}</Badge>
                    </td>
                    <td className="text-muted-foreground py-1.5 text-xs">{item.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* 单个 SKU 的编码与条码 */}
      <Card
        title="单个 SKU 的编码与条码"
        hint="手工输入编码走乐观锁；条码以 scheme + value 唯一，主条码会同步到 SKU 的内部条码位（下游拣货与面单取的就是它）。"
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
            <dl className="grid grid-cols-2 gap-3 text-sm">
              <Value label="SKU 名称" value={panel.skuName} />
              <Value label="所属 SPU" value={panel.spuCode} />
              <Value label="当前版本" value={String(panel.version)} />
              <Value
                label="当前编码来源"
                value={panel.internalSource ? (sourceLabels[panel.internalSource] ?? panel.internalSource) : '尚无分配留痕'}
              />
            </dl>

            <div className="border-control-border rounded-md border p-3">
              <p className="text-sm font-medium">内部编码（手工输入）</p>
              <p className="text-muted-foreground mt-1 text-xs">
                当前编码
                {panel.internalSource
                  ? ` 由「${sourceLabels[panel.internalSource] ?? panel.internalSource}」写入${
                      panel.internalRuleCode ? `（规则 ${panel.internalRuleCode}）` : ''
                    }`
                  : '（尚无分配留痕）'}
                。手工输入是唯一允许覆盖既有编码的路径。
              </p>
              <div className="mt-2 flex flex-wrap items-end gap-2">
                <div className="min-w-56 flex-1">
                  <Field label="内部编码">
                    <input
                      className={control}
                      value={codeDraft}
                      onChange={(e) => setCodeDraft(e.target.value)}
                    />
                  </Field>
                </div>
                {writable && (
                  <Button size="sm" onClick={() => void submitCode()}>
                    <Save className="h-4 w-4" />
                    保存编码
                  </Button>
                )}
              </div>
            </div>

            <div className="border-control-border rounded-md border p-3">
              <p className="flex items-center gap-2 text-sm font-medium">
                <Barcode className="h-4 w-4" />
                外部条码
              </p>
              {panel.identifiers.length === 0 ? (
                <p className="text-muted-foreground mt-1 text-sm">
                  这个 SKU 还没有任何外部条码。没有条码时 08 拣货扫码无法定位到它。
                </p>
              ) : (
                <table className="mt-2 w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground text-left text-xs">
                      <th className="py-1 font-normal">scheme</th>
                      <th className="py-1 font-normal">条码值</th>
                      <th className="py-1 font-normal">来源</th>
                      <th className="py-1 font-normal">录入时间</th>
                      <th className="py-1 font-normal" />
                    </tr>
                  </thead>
                  <tbody>
                    {panel.identifiers.map((identifier) => (
                      <tr key={identifier.id} className="border-control-border border-t">
                        <td className="py-1.5">
                          {schemeLabels[identifier.scheme] ?? identifier.scheme}
                        </td>
                        <td className="break-all tabular-nums">{identifier.value}</td>
                        <td className="text-muted-foreground py-1.5 text-xs">
                          {sourceLabels[identifier.source] ?? identifier.source}
                        </td>
                        <td className="text-muted-foreground py-1.5 text-xs">
                          {timestamp(identifier.occurredAt)}
                        </td>
                        <td className="py-1.5 text-right">
                          {identifier.isPrimary && <Badge tone="success">主条码</Badge>}
                          {writable && (
                            <Button
                              variant="ghost"
                              size="sm"
                              className="ml-1"
                              onClick={() => void removeIdentifier(identifier.id)}
                            >
                              <Trash2 className="h-4 w-4" />
                              删除
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {primaryIdentifier(panel.identifiers) && (
                <p className="text-muted-foreground mt-2 text-xs">
                  主条码是 SKU 的内部条码位：08 拣货扫码与 10 面单打印取的就是它。删掉主条码后该位会被清空，
                  避免留下一个已经不存在的条码。
                </p>
              )}

              {writable && (
                <div className="mt-3 grid gap-2 sm:grid-cols-[9rem_1fr_auto_auto]">
                  <Field label="scheme">
                    <select
                      className={control}
                      value={scanDraft.scheme}
                      onChange={(e) => setScanDraft({ ...scanDraft, scheme: e.target.value })}
                    >
                      {schemes.map((scheme) => (
                        <option key={scheme} value={scheme}>
                          {schemeLabels[scheme]}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="条码值（扫码枪可直接输入）">
                    <input
                      className={control}
                      value={scanDraft.value}
                      onChange={(e) => setScanDraft({ ...scanDraft, value: e.target.value })}
                    />
                  </Field>
                  <div className="flex items-end pb-1">
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={scanDraft.isPrimary}
                        onChange={(e) => setScanDraft({ ...scanDraft, isPrimary: e.target.checked })}
                      />
                      设为主条码
                    </label>
                  </div>
                  <div className="flex items-end">
                    <Button
                      size="sm"
                      disabled={panelBusy || scanProblem !== null || scanDraft.value.trim() === ''}
                      onClick={() => void submitIdentifier()}
                    >
                      <ScanLine className="h-4 w-4" />
                      录入
                    </Button>
                  </div>
                </div>
              )}
              {scanDraft.value.trim() !== '' && scanProblem !== null && (
                <p role="alert" className="text-destructive mt-2 text-xs">
                  {scanProblem}
                </p>
              )}
            </div>

            {panel.assignments.length > 0 && (
              <div className="border-control-border rounded-md border p-3">
                <p className="text-sm font-medium">编码变更留痕</p>
                <p className="text-muted-foreground mt-1 text-xs">
                  谁在什么时候把编码从哪个值改成了哪个值。批量补齐覆盖旧编码时同样会留痕。
                </p>
                <ul className="mt-2 space-y-1 text-xs">
                  {panel.assignments.map((assignment) => (
                    <li key={assignment.id} className="text-muted-foreground">
                      {timestamp(assignment.occurredAt)} ·{' '}
                      {assignment.sourceLabel ?? sourceLabels[assignment.source] ?? assignment.source}
                      {assignment.ruleCode ? `（规则 ${assignment.ruleCode}）` : ''} ·{' '}
                      <span className="tabular-nums">
                        {assignment.fromCode ?? '（空）'} → {assignment.toCode}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </Card>

      {editingRule && (
        <RuleEditor
          id={editingRule.id}
          draft={editingRule.draft}
          onClose={() => setEditingRule(null)}
          onChange={(draft) => setEditingRule({ id: editingRule.id, draft })}
          onSubmit={async (draft) => {
            await submitRule(editingRule.id, draft);
            setEditingRule(null);
            await load();
          }}
        />
      )}
    </Section>
  );

  // ------------------------------------------------------------ 动作

  async function submitCode() {
    if (!panel) return;
    setPanelBusy(true);
    setPanelError('');
    try {
      await saveCode(panel.skuId, codeDraft.trim(), panel.version);
      await loadPanel();
      await load();
    } catch (e) {
      setPanelError(
        isConflict(e)
          ? `这个 SKU 已被他人修改或编码已被占用，本次没有保存。${errorText(e)}`
          : errorText(e, '编码保存失败'),
      );
    } finally {
      setPanelBusy(false);
    }
  }

  async function submitIdentifier() {
    if (!panel) return;
    setPanelBusy(true);
    setPanelError('');
    try {
      await addIdentifier(
        panel.skuId,
        scanDraft.scheme,
        scanDraft.value.trim(),
        scanDraft.isPrimary,
        'SCAN',
      );
      setScanDraft({ ...scanDraft, value: '', isPrimary: false });
      await loadPanel();
      await load();
    } catch (e) {
      setPanelError(
        isConflict(e)
          ? `条码已被占用，本次没有保存。${errorText(e)}`
          : errorText(e, '条码录入失败'),
      );
    } finally {
      setPanelBusy(false);
    }
  }

  async function removeIdentifier(identifierId: string) {
    if (!panel) return;
    setPanelBusy(true);
    setPanelError('');
    try {
      await deleteIdentifier(panel.skuId, identifierId);
      await loadPanel();
      await load();
    } catch (e) {
      setPanelError(errorText(e, '条码删除失败'));
    } finally {
      setPanelBusy(false);
    }
  }

  async function executeBatch(dryRun: boolean) {
    setBatchBusy(true);
    setBatchError('');
    try {
      const result = await runBatch({
        action: batchAction,
        ruleId: batchRuleId,
        // GENERATE 需要显式 SKU；BACKFILL 由规则范围决定，忽略此字段
        skuIds: batchAction === 'GENERATE' ? [skuId.trim()].filter((v) => v !== '') : null,
        occurredAt: null,
        dryRun,
      });
      setBatchResult(result);
      if (!dryRun) {
        await load();
        if (skuId.trim() !== '') await loadPanel();
      }
    } catch (e) {
      setBatchError(errorText(e, '批量操作失败'));
    } finally {
      setBatchBusy(false);
    }
  }

  async function executeImport(dryRun: boolean) {
    setImportBusy(true);
    setImportError('');
    try {
      // 粘贴文本里的 SKU 编码要先解析成 UUID。本模块没有 SKU 检索端点，
      // 因此只支持「直接粘 SKU ID」的整列——解析不到的行原样报出来，不静默丢弃。
      const { rows, problems } = parseImportText(importText, (code) =>
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(code)
          ? code
          : null,
      );
      if (problems.length > 0)
        setImportError(
          `有 ${problems.length} 行无法解析，未提交。第 ` +
            problems
              .slice(0, 5)
              .map((p) => `${p.line} 行`)
              .join('、') +
            (problems.length > 5 ? ' 等' : '') +
            ' 行的问题：' +
            problems[0]?.reason +
            '。第一列请填 SKU 的 ID（可在 SKU 列表页复制）。',
        );
      if (rows.length === 0) return;
      const result = await importIdentifiers(rows, dryRun);
      setImportResult(result);
      if (!dryRun) {
        await load();
        if (skuId.trim() !== '') await loadPanel();
      }
    } catch (e) {
      setImportError(errorText(e, '条码导入失败'));
    } finally {
      setImportBusy(false);
    }
  }

  async function submitRule(id: string | null, draft: RuleDraft) {
    const input = draftToRuleInput(draft);
    if (id) await updateRule(id, input);
    else await createRule(input);
  }
}

/** 规则编辑面板。展开长度在前端先挡一次，不让用户走到批量生成才发现越界。 */
function RuleEditor({
  id,
  draft,
  onClose,
  onChange,
  onSubmit,
}: {
  id: string | null;
  draft: RuleDraft;
  onClose: () => void;
  /** 只回草稿：规则 ID 由外层单独持有，避免每次改字段都把它丢掉（那会把「编辑」变成「新建」）。 */
  onChange: (draft: RuleDraft) => void;
  onSubmit: (draft: RuleDraft) => Promise<void>;
}) {
  const [errors, setErrors] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState(false);
  const [preview, setPreview] = useState('');

  useEffect(() => {
    setErrors(validateRule(draft));
  }, [draft]);

  // 实时把规则形状展开出来：运营在保存前就该看到「这条规则会产出什么样的编码」。
  const sample = useMemo(() => {
    const width = Number(draft.seqWidth);
    if (!Number.isInteger(width) || width < 1 || width > 12) return '';
    return `${draft.prefix.trim().toUpperCase()}${String(draft.startFrom || '1').padStart(
      width,
      '0',
    )}${draft.suffix.trim().toUpperCase()}`;
  }, [draft.prefix, draft.suffix, draft.seqWidth, draft.startFrom]);

  return (
    <Editor
      title="编码规则"
      description="前缀 + 补零序号 + 后缀。序号由规则独占分配，同一规则内不会重号；已分配的编码不因改规则而变化。"
      close={onClose}
    >
      <div className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="规则编码">
            <input
              className={control}
              value={draft.code}
              onChange={(e) => onChange({ ...draft, code: e.target.value })}
            />
          </Field>
          <Field label="规则名称">
            <input
              className={control}
              value={draft.name}
              onChange={(e) => onChange({ ...draft, name: e.target.value })}
            />
          </Field>
        </div>
        <Field label="说明">
          <textarea
            className={`${control} min-h-16`}
            value={draft.description}
            onChange={(e) => onChange({ ...draft, description: e.target.value })}
          />
        </Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field
            label="适用范围"
            hint="限定商品族时，该商品族只能有一条规则——否则生成的编码无法预期。"
          >
            <select
              className={control}
              value={draft.scope}
              onChange={(e) => onChange({ ...draft, scope: e.target.value as 'ALL' | 'SPU' })}
            >
              <option value="ALL">全部 SKU</option>
              <option value="SPU">限定商品族</option>
            </select>
          </Field>
          {draft.scope === 'SPU' && (
            <Field label="商品族 SPU ID">
              <input
                className={control}
                value={draft.spuId}
                onChange={(e) => onChange({ ...draft, spuId: e.target.value })}
              />
            </Field>
          )}
        </div>
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label="前缀">
            <input
              className={control}
              value={draft.prefix}
              onChange={(e) => onChange({ ...draft, prefix: e.target.value })}
            />
          </Field>
          <Field label="序号宽度">
            <input
              className={control}
              inputMode="numeric"
              value={draft.seqWidth}
              onChange={(e) => onChange({ ...draft, seqWidth: e.target.value })}
            />
          </Field>
          <Field label="起始序号">
            <input
              className={control}
              inputMode="numeric"
              value={draft.startFrom}
              onChange={(e) => onChange({ ...draft, startFrom: e.target.value })}
            />
          </Field>
          <Field label="后缀">
            <input
              className={control}
              value={draft.suffix}
              onChange={(e) => onChange({ ...draft, suffix: e.target.value })}
            />
          </Field>
        </div>
        <p className="text-muted-foreground text-xs">
          规则形状预览：
          {preview ? <span className="text-foreground ml-1 tabular-nums">{preview}</span> : '—'}
        </p>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={draft.enabled}
            onChange={(e) => onChange({ ...draft, enabled: e.target.checked })}
          />
          启用（停用后不再参与自动生成，已分配的编码保留）
        </label>

        {(errors.length > 0 || error || conflict) && (
          <div
            role="alert"
            className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
          >
            {conflict ? (
              <p className="flex items-center gap-2">
                <Ban className="h-4 w-4" />
                规则已被他人修改或适用范围被占用，本次没有保存。请关闭后重新打开再改。
              </p>
            ) : (
              <>
                {error && <p>{error}</p>}
                {errors.length > 0 && (
                  <ul className="list-disc space-y-0.5 pl-4">
                    {errors.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </div>
        )}

        <div className="flex flex-wrap items-center justify-end gap-2">
          <span className="text-muted-foreground mr-auto text-xs">当前版本 {draft.version}</span>
          <Button variant="ghost" size="sm" onClick={onClose}>
            <X className="h-4 w-4" />
            取消
          </Button>
          <Button
            size="sm"
            disabled={saving || errors.length > 0}
            onClick={() => {
              setSaving(true);
              setError('');
              setConflict(false);
              void onSubmit(draft)
                .catch((e: unknown) => {
                  if (isConflict(e)) setConflict(true);
                  else setError(errorText(e, '规则保存失败'));
                })
                .finally(() => setSaving(false));
            }}
          >
            <Save className="h-4 w-4" />
            保存
          </Button>
        </div>
      </div>
    </Editor>
  );
}

/** 批量编码结果：逐行展示，含行号、旧值/新值与拒绝原因。 */
function BatchOutcome({
  title,
  note,
  result,
  onReread,
}: {
  title: string;
  note: string;
  result: SkuCodeBatchResult;
  onReread: () => Promise<void>;
}) {
  const [refreshing, setRefreshing] = useState(false);
  return (
    <div className="mt-3">
      <p className="text-sm font-medium">{title}</p>
      <p className="text-muted-foreground mt-1 text-xs">{note}</p>
      {result.items.length === 0 ? (
        <p className="text-muted-foreground mt-2 text-sm">没有需要处理的 SKU。</p>
      ) : (
        <table className="mt-2 w-full text-sm">
          <thead>
            <tr className="text-muted-foreground text-left text-xs">
              <th className="w-12 py-1 font-normal">行</th>
              <th className="py-1 font-normal">SKU</th>
              <th className="py-1 font-normal">结果</th>
              <th className="py-1 font-normal">编码变化</th>
              <th className="py-1 font-normal">原因</th>
            </tr>
          </thead>
          <tbody>
            {result.items.map((item) => (
              <tr key={item.rowNo} className="border-control-border border-t">
                <td className="py-1.5 tabular-nums">{item.rowNo}</td>
                <td className="py-1.5 break-all">{item.skuCode || '—'}</td>
                <td className="py-1.5">
                  <Badge tone={outcomeTone(item.outcome)}>{outcomeLabels[item.outcome]}</Badge>
                </td>
                <td className="py-1.5 tabular-nums">
                  {item.codeAfter ? (
                    <>
                      {item.codeBefore || '（空）'} → {item.codeAfter}
                    </>
                  ) : (
                    '—'
                  )}
                </td>
                <td className="text-muted-foreground py-1.5 text-xs">{item.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Button
        variant="ghost"
        size="sm"
        className="mt-2"
        disabled={refreshing}
        onClick={() => {
          setRefreshing(true);
          void onReread().finally(() => setRefreshing(false));
        }}
      >
        <RefreshCw className="h-4 w-4" />
        重新读取该批次
      </Button>
    </div>
  );
}

function outcomeTone(outcome: string): 'success' | 'info' | 'danger' {
  if (outcome === 'APPLIED') return 'success';
  if (outcome === 'REJECTED') return 'danger';
  return 'info';
}

/** 定义列表里的一项。刻意与表单用的 Field 分开命名：两者签名不同，混用会静默丢值。 */
function Value({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="break-words">{value || '—'}</dd>
    </div>
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