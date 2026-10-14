'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import {
  ArrowLeft,
  GitBranch,
  Link2,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  TriangleAlert,
  Unlink,
} from 'lucide-react';
import type {
  CategorySpecAxisRule,
  SpecAxis,
  SpecAxisOption,
  SpecAxisOverview,
  SpecAxisPanel,
} from '../../../../../packages/api-contracts/src/index';
import {
  addOption,
  axisDraft,
  axisUsageText,
  bindAxis,
  createAxis,
  deleteAxis,
  deleteCategoryRule,
  deleteOption,
  draftToAxisInput,
  draftToCategoryRuleInput,
  draftToOptionInput,
  emptyAxisDraft,
  emptyCategoryRuleDraft,
  emptyOptionDraft,
  errorText,
  fetchAxes,
  fetchCategoryRules,
  fetchOptions,
  fetchOverview,
  fetchPanel,
  isNotFound,
  matrixHint,
  optionSourceText,
  requiredAxisHint,
  saveCategoryRule,
  unbindAxis,
  updateAxis,
  validateAxis,
  validateCategoryRule,
  validateOption,
  type AxisDraft,
  type CategoryRuleDraft,
  type OptionDraft,
} from './spec-axis-model';
import { Editor, Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.1.3.1 规格轴定义工作区。
 *
 * <p>分区按「先把值源准备好、再挂到 SPU 上」的顺序排：概览与治理缺口 → 规格轴字典 →
 * 某条轴的选项 → SPU 绑定与矩阵 → 类目允许规则。
 *
 * <p>四条界面纪律：
 *
 * <ul>
 *   <li>**规格选项必须显式讲出它同时是属性选项**。值源只有一处（蓝图硬约束），
 *       但用户看到的是两个入口，不点破就会以为颜色值被存了两份。
 *   <li>**「矩阵为空」要分两种说法**。一条轴都没绑 vs 绑了但没选项，下一步动作完全不同，
 *       都画成「0 个组合」会让人以为配置已生效。
 *   <li>**绑定要问「是否同时创建承载属性」**。3.2.1.1 未开发，属性管理页面不存在，
 *       规格轴必须能在这条链路里自洽走完，否则运营走到一半发现无路可走。
 *   <li>**停用与删除分开**。轴被 SPU 绑定、选项被 SKU 取用时只能停用——停用挡住的是
 *       「新建」，历史规格照常可解析。
 * </ul>
 */
export function SpecAxisWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('catalog.attribute.read');
  const writable = session.permissions.includes('catalog.attribute.update');

  const [overview, setOverview] = useState<SpecAxisOverview | null>(null);
  const [axes, setAxes] = useState<SpecAxis[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [editingAxis, setEditingAxis] = useState<{ id: string | null; draft: AxisDraft } | null>(
    null,
  );
  const [axisError, setAxisError] = useState('');
  const [axisBusy, setAxisBusy] = useState(false);

  const [optionsFor, setOptionsFor] = useState<SpecAxis | null>(null);
  const [optionRows, setOptionRows] = useState<SpecAxisOption[]>([]);
  const [optionsBusy, setOptionsBusy] = useState(false);
  const [optionsError, setOptionsError] = useState('');
  const [optionDraft, setOptionDraft] = useState<OptionDraft>(emptyOptionDraft());
  const [optionError, setOptionError] = useState('');

  const [spuId, setSpuId] = useState('');
  const [panel, setPanel] = useState<SpecAxisPanel | null>(null);
  const [panelBusy, setPanelBusy] = useState(false);
  const [panelMissing, setPanelMissing] = useState(false);
  const [panelError, setPanelError] = useState('');
  const [bindDraft, setBindDraft] = useState({ specAxisId: '', createAttribute: true, name: '' });
  const [bindError, setBindError] = useState('');
  const [bindBusy, setBindBusy] = useState(false);

  const [ruleCategory, setRuleCategory] = useState('');
  const [rules, setRules] = useState<CategorySpecAxisRule[]>([]);
  const [rulesBusy, setRulesBusy] = useState(false);
  const [ruleError, setRuleError] = useState('');
  const [editingRule, setEditingRule] = useState<CategoryRuleDraft | null>(null);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [nextOverview, nextAxes] = await Promise.all([fetchOverview(), fetchAxes()]);
      setOverview(nextOverview);
      setAxes(nextAxes);
    } catch (e) {
      setError(errorText(e, '规格轴加载失败'));
    } finally {
      setLoading(false);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadOptions = useCallback(async (axis: SpecAxis) => {
    setOptionsBusy(true);
    setOptionsError('');
    try {
      setOptionRows(await fetchOptions(axis.id));
      setOptionDraft(emptyOptionDraft());
    } catch (e) {
      setOptionRows([]);
      setOptionsError(errorText(e, '规格选项加载失败'));
    } finally {
      setOptionsBusy(false);
    }
  }, []);

  const loadPanel = useCallback(async () => {
    if (!spuId.trim()) {
      setPanel(null);
      return;
    }
    setPanelBusy(true);
    setPanelError('');
    setPanelMissing(false);
    try {
      const next = await fetchPanel(spuId.trim());
      setPanel(next);
      setPanelMissing(false);
    } catch (e) {
      setPanel(null);
      if (isNotFound(e)) setPanelMissing(true);
      else setPanelError(errorText(e, 'SPU 规格轴面板加载失败'));
    } finally {
      setPanelBusy(false);
    }
  }, [spuId]);

  const loadRules = useCallback(async () => {
    if (!ruleCategory.trim()) {
      setRules([]);
      return;
    }
    setRulesBusy(true);
    setRuleError('');
    try {
      setRules(await fetchCategoryRules(ruleCategory.trim()));
    } catch (e) {
      setRules([]);
      setRuleError(errorText(e, '类目规则加载失败'));
    } finally {
      setRulesBusy(false);
    }
  }, [ruleCategory]);

  const enabledAxes = useMemo(() => axes.filter((axis) => axis.enabled), [axes]);
  const unboundAxes = useMemo(
    () =>
      panel ? axes.filter((axis) => !panel.axes.some((bound) => bound.specAxisId === axis.id)) : [],
    [axes, panel],
  );

  async function saveAxisDraft() {
    if (!editingAxis) return;
    setAxisBusy(true);
    setAxisError('');
    try {
      const input = draftToAxisInput(editingAxis.draft);
      if (editingAxis.id) await updateAxis(editingAxis.id, input);
      else await createAxis(input);
      setEditingAxis(null);
      await load();
    } catch (e) {
      setAxisError(errorText(e, '规格轴保存失败'));
    } finally {
      setAxisBusy(false);
    }
  }

  async function removeAxis(axis: SpecAxis) {
    try {
      await deleteAxis(axis.id, axis.version);
      await load();
      if (optionsFor?.id === axis.id) {
        setOptionsFor(null);
        setOptionRows([]);
      }
    } catch (e) {
      setAxisError(errorText(e, '规格轴删除失败'));
    }
  }

  async function submitOption(axisId: string) {
    setOptionError('');
    setOptionsBusy(true);
    try {
      await addOption(axisId, draftToOptionInput(optionDraft));
      setOptionDraft(emptyOptionDraft());
      await loadOptions({ id: axisId } as SpecAxis);
      await load();
    } catch (e) {
      setOptionError(errorText(e, '规格选项登记失败'));
    } finally {
      setOptionsBusy(false);
    }
  }

  async function removeOption(axisId: string, value: string) {
    setOptionError('');
    try {
      await deleteOption(axisId, value);
      await loadOptions({ id: axisId } as SpecAxis);
      await load();
    } catch (e) {
      setOptionError(errorText(e, '规格选项删除失败'));
    }
  }

  async function submitBind() {
    setBindBusy(true);
    setBindError('');
    try {
      await bindAxis(panel!.spuId, {
        specAxisId: bindDraft.specAxisId,
        position: 0,
        createAttribute: bindDraft.createAttribute,
        attributeName: bindDraft.name.trim() === '' ? null : bindDraft.name.trim(),
      });
      setBindDraft({ specAxisId: '', createAttribute: true, name: '' });
      await loadPanel();
      await load();
    } catch (e) {
      setBindError(errorText(e, '绑定规格轴失败'));
    } finally {
      setBindBusy(false);
    }
  }

  async function removeBinding(axisId: string) {
    try {
      await unbindAxis(panel!.spuId, axisId);
      await loadPanel();
      await load();
    } catch (e) {
      setBindError(errorText(e, '解除绑定失败'));
    }
  }

  async function removeRule(rule: CategorySpecAxisRule) {
    try {
      await deleteCategoryRule(rule.category, rule.specAxisId);
      await loadRules();
      await load();
    } catch (e) {
      setRuleError(errorText(e, '类目规则删除失败'));
    }
  }

  if (!readable)
    return (
      <Section frame>
        <p className="rounded-lg border p-6 text-sm">
          当前角色没有商品属性查看权限，请联系租户管理员。
        </p>
      </Section>
    );

  if (loading)
    return (
      <Section frame>
        <p role="status" className="text-muted-foreground text-sm">
          正在加载规格轴…
        </p>
      </Section>
    );

  if (error || !overview)
    return (
      <Section frame>
        <BackLink />
        <p role="alert" className="text-destructive text-sm">
          {error || '规格轴加载失败'}
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
          <h2 className="text-xl font-semibold">规格轴定义</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            维护可复用的规格维度（颜色 / 尺码 / 容量）与其可选值，并绑定到 SPU 上驱动 SKU 矩阵。
            规格轴是<b>租户级字典</b>：同一条轴可以被多个 SPU 复用，选项与属性选项共享同一个值源。
          </p>
        </div>
        <div className="flex items-center gap-2">
          {writable && (
            <Button size="sm" onClick={() => setEditingAxis({ id: null, draft: emptyAxisDraft() })}>
              <Plus className="h-4 w-4" />
              新建规格轴
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
          label="规格轴字典"
          value={`${overview.enabledAxisCount} / ${overview.axisCount}`}
          hint="启用中 / 全部"
        />
        <Stat label="规格选项" value={overview.optionCount} hint="与属性选项共享值源" />
        <Stat label="已绑定 SPU" value={overview.boundSpuCount} hint="使用规格轴的商品族" />
        <Stat label="类目规则" value={overview.categoryRuleCount} hint="限制类目可绑的轴" />
      </div>

      {overview.unboundVariantAxisAttributes > 0 && (
        <div className="border-warning/30 bg-warning/5 text-warning rounded-md border p-3 text-sm">
          <p className="flex items-center gap-2 font-medium">
            <TriangleAlert className="h-4 w-4" />
            值源治理缺口
          </p>
          <p className="mt-1 text-xs">{overview.note}</p>
        </div>
      )}

      {axisError && (
        <p role="alert" className="text-destructive rounded-md border p-3 text-sm">
          {axisError}
        </p>
      )}

      {/* 规格轴字典 */}
      <Card
        title="规格轴字典"
        hint="轴编码是机器标识（COLOR / SIZE），必须与承载它的 variant_axis 属性定义同名——两边各叫各的会让值源永远对不齐，因此建后不可改。"
      >
        {axes.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有任何规格轴。规格轴决定一个商品有哪些维度（颜色、尺码…）与可选值，
            请先新建一条，例如编码 COLOR、名称「颜色」。
          </p>
        ) : (
          <div className="space-y-2">
            {axes.map((axis) => (
              <div
                key={axis.id}
                className="border-control-border bg-muted/30 flex flex-wrap items-center gap-3 rounded-md border p-3"
              >
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                    {axis.name}
                    <Badge tone="neutral">{axis.code}</Badge>
                    {axis.enabled ? (
                      <Badge tone="success">启用中</Badge>
                    ) : (
                      <Badge tone="neutral">已停用</Badge>
                    )}
                  </p>
                  <p className="text-muted-foreground mt-1 text-xs">{axisUsageText(axis)}</p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setOptionsFor(axis);
                    setOptionError('');
                    void loadOptions(axis);
                  }}
                >
                  <GitBranch className="h-4 w-4" />
                  选项
                </Button>
                {writable && (
                  <>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setAxisError('');
                        setEditingAxis({ id: axis.id, draft: axisDraft(axis) });
                      }}
                    >
                      编辑
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={axisBusy || axis.boundSpuCount > 0 || axis.optionCount > 0}
                      onClick={() => void removeAxis(axis)}
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
        <p className="text-muted-foreground mt-2 text-xs">
          被 SPU 绑定或挂着选项的轴不能删除——那些 SPU 的规格会凭空消失。请改为停用：
          停用只挡住新建绑定，已绑定的 SPU 与既有选项照常可读。
        </p>
      </Card>

      {/* 规格选项 */}
      {optionsFor && (
        <Card
          title={`规格选项 · ${optionsFor.name}`}
          hint="每个选项都会同时写入属性选项值源与规格轴选项（同一事务）。这不是两份拷贝：同一个值只维护一处，SKU 的规格与属性读的都是它。"
        >
          {optionsBusy && optionRows.length === 0 ? (
            <p role="status" className="text-muted-foreground text-sm">
              正在加载选项…
            </p>
          ) : optionRows.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              这条轴还没有任何选项。请先为 SPU 绑定该轴（下方「SPU 规格轴与矩阵」），
              再登记选项——选项必须挂在某个 SPU 的 variant_axis 属性的选项集合上。
            </p>
          ) : (
            <ul className="space-y-2">
              {optionRows.map((option) => (
                <li
                  key={option.value}
                  className="border-control-border bg-muted/30 flex flex-wrap items-center gap-3 rounded-md border p-2"
                >
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-2 text-sm">
                      {option.value}
                      {option.enabled ? (
                        <Badge tone="success">启用中</Badge>
                      ) : (
                        <Badge tone="neutral">已停用</Badge>
                      )}
                    </p>
                    <p className="text-muted-foreground mt-0.5 text-xs">
                      排序 {option.position} · {optionSourceText(option)}
                    </p>
                  </div>
                  {writable && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => void removeOption(optionsFor.id, option.value)}
                    >
                      <Trash2 className="h-4 w-4" />
                      删除
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {optionsError && (
            <p role="alert" className="text-destructive mt-3 text-sm">
              {optionsError}
            </p>
          )}
          {optionError && (
            <p role="alert" className="text-destructive mt-3 text-sm">
              {optionError}
            </p>
          )}

          {writable && (
            <div className="mt-4 space-y-2">
              <Field
                label="新增选项值"
                hint="提交后会同时写入属性选项值源；值需与属性选项保持一致（中文、字母、数字、空格、下划线、短横线、点或斜杠，1~100 位）。"
              >
                <input
                  className={control}
                  value={optionDraft.value}
                  placeholder="深空灰 / XL / 500ml"
                  onChange={(e) => setOptionDraft({ ...optionDraft, value: e.target.value })}
                />
              </Field>
              <div className="flex flex-wrap items-end gap-2">
                <div className="w-32">
                  <Field label="排序位置">
                    <input
                      className={control}
                      value={optionDraft.position}
                      inputMode="numeric"
                      onChange={(e) => setOptionDraft({ ...optionDraft, position: e.target.value })}
                    />
                  </Field>
                </div>
                <Button
                  size="sm"
                  disabled={optionsBusy || validateOption(optionDraft, optionRows).length > 0}
                  onClick={() => void submitOption(optionsFor.id)}
                >
                  <Save className="h-4 w-4" />
                  登记选项
                </Button>
              </div>
              {validateOption(optionDraft, optionRows).length > 0 &&
                optionDraft.value.trim() !== '' && (
                  <p className="text-muted-foreground text-xs">
                    {validateOption(optionDraft, optionRows).join('；')}
                  </p>
                )}
            </div>
          )}
        </Card>
      )}

      {/* SPU 绑定与矩阵 */}
      <Card
        title="SPU 规格轴与矩阵"
        hint="绑定一行 = 「这个 SPU 用这条轴，由这个 variant_axis 属性承载它的选项」。矩阵行数是各轴可用选项的笛卡尔积，即按当前配置能生成多少个 SKU。"
      >
        <div className="flex flex-wrap items-end gap-2">
          <div className="min-w-64 flex-1">
            <Field label="SPU ID">
              <input
                className={control}
                value={spuId}
                placeholder="粘贴 SPU ID"
                onChange={(e) => {
                  setSpuId(e.target.value);
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
            找不到该 SPU，或当前角色无权查看——租户隔离已生效，两边不会互相看到对方的 SPU。
          </p>
        )}

        {panelError && (
          <p role="alert" className="text-destructive mt-3 text-sm">
            {panelError}
          </p>
        )}

        {panel && (
          <div className="mt-4 space-y-3">
            <div className="bg-muted/30 rounded-md border p-3">
              <p className="text-sm font-medium">
                {panel.spuName} <Badge tone="neutral">{panel.spuCode}</Badge>
              </p>
              <p className="text-muted-foreground mt-1 text-xs">
                类目：{panel.spuCategory || '（未分类）'} · 已绑定 {panel.boundAxisCount} 条轴
              </p>
              <p className="text-muted-foreground mt-1 text-xs">{matrixHint(panel)}</p>
              <p
                className={
                  panel.missingRequiredAxisCount > 0
                    ? 'text-warning mt-1 text-xs'
                    : 'text-muted-foreground mt-1 text-xs'
                }
              >
                {requiredAxisHint(panel)}
              </p>
              {panel.requiredAxisNotes.map((note) => (
                <p key={note} className="text-muted-foreground mt-0.5 text-xs">
                  · {note}
                </p>
              ))}
            </div>

            {panel.axes.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                该 SPU 还没有绑定任何规格轴。用下面的表单挑一条轴绑定。
              </p>
            ) : (
              <ul className="space-y-2">
                {panel.axes.map((axis) => (
                  <li key={axis.specAxisId} className="border-control-border rounded-md border p-3">
                    <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                      {axis.specAxisName}
                      <Badge tone="neutral">{axis.specAxisCode}</Badge>
                      <Badge tone="neutral">
                        {axis.enabledOptionCount} / {axis.totalOptionCount} 选项可用
                      </Badge>
                    </p>
                    <p className="text-muted-foreground mt-1 text-xs">
                      承载属性「{axis.attributeName}」（{axis.attributeCode}）· {axis.skuCount} 个
                      SKU · 排序 {axis.position}
                    </p>
                    <p className="text-muted-foreground mt-1 text-xs">
                      选项：
                      {axis.options.length === 0
                        ? '尚未登记（矩阵为空）'
                        : axis.options
                            .filter((option) => option.enabled)
                            .map((option) => option.value)
                            .join('、') || '全部停用（矩阵为空）'}
                    </p>
                    {writable && (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="mt-2"
                        onClick={() => void removeBinding(axis.specAxisId)}
                      >
                        <Unlink className="h-4 w-4" />
                        解除绑定
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}

            {bindError && (
              <p role="alert" className="text-destructive text-sm">
                {bindError}
              </p>
            )}

            {writable && (
              <div className="space-y-2 rounded-md border p-3">
                <p className="flex items-center gap-2 text-sm font-medium">
                  <Link2 className="h-4 w-4" />
                  绑定一条规格轴
                </p>
                <Field label="规格轴">
                  <select
                    className={control}
                    value={bindDraft.specAxisId}
                    onChange={(e) => setBindDraft({ ...bindDraft, specAxisId: e.target.value })}
                  >
                    <option value="">请选择</option>
                    {unboundAxes.map((axis) => (
                      <option key={axis.id} value={axis.id}>
                        {axis.name}（{axis.code}）{axis.enabled ? '' : ' · 已停用'}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field
                  label="承载属性名称（可选）"
                  hint="不填则与轴同名。承载属性是一条 variant_axis 属性定义（OPTION / SKU_ONLY / 单值 / 必填）——V1 的 CHECK 唯一允许的规格轴形状。"
                >
                  <input
                    className={control}
                    value={bindDraft.name}
                    placeholder={bindDraft.specAxisId ? '留空则与轴同名' : ''}
                    onChange={(e) => setBindDraft({ ...bindDraft, name: e.target.value })}
                  />
                </Field>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={bindDraft.createAttribute}
                    onChange={(e) =>
                      setBindDraft({ ...bindDraft, createAttribute: e.target.checked })
                    }
                  />
                  同时创建承载属性（该 SPU 上还没有同编码的 variant_axis 属性时）
                </label>
                <Button
                  size="sm"
                  disabled={bindBusy || bindDraft.specAxisId === ''}
                  onClick={() => void submitBind()}
                >
                  <Plus className="h-4 w-4" />
                  绑定
                </Button>
              </div>
            )}
          </div>
        )}
      </Card>

      {/* 类目规则 */}
      <Card
        title="类目允许的规格轴规则"
        hint="类目是商品上的自由文本分类（3.3.1.1 分类树尚未开发）。配了允许名单的类目，其 SPU 只能绑定名单内的轴；标记为必绑的轴会在面板上提示缺口。"
      >
        <div className="flex flex-wrap items-end gap-2">
          <div className="min-w-64 flex-1">
            <Field label="类目名称">
              <input
                className={control}
                value={ruleCategory}
                placeholder="如：服饰 / 3C 配件"
                onChange={(e) => setRuleCategory(e.target.value)}
              />
            </Field>
          </div>
          <Button size="sm" variant="outline" disabled={rulesBusy} onClick={() => void loadRules()}>
            查看
          </Button>
        </div>

        {rulesBusy && (
          <p role="status" className="text-muted-foreground mt-3 text-sm">
            正在加载规则…
          </p>
        )}
        {ruleError && (
          <p role="alert" className="text-destructive mt-3 text-sm">
            {ruleError}
          </p>
        )}

        {ruleCategory.trim() !== '' && !rulesBusy && rules.length === 0 && (
          <p className="text-muted-foreground mt-3 text-sm">
            该类目还没有配置规格轴规则。未配置规则的类目不受限制，SPU 可以绑定任意轴。
          </p>
        )}

        {rules.length > 0 && (
          <ul className="mt-3 space-y-2">
            {rules.map((rule) => (
              <li
                key={`${rule.category}-${rule.specAxisId}`}
                className="border-control-border bg-muted/30 flex flex-wrap items-center gap-3 rounded-md border p-2"
              >
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 text-sm">
                    {rule.specAxisName}
                    <Badge tone="neutral">{rule.specAxisCode}</Badge>
                    {rule.required ? (
                      <Badge tone="warning">必绑</Badge>
                    ) : (
                      <Badge tone="neutral">可选</Badge>
                    )}
                  </p>
                  <p className="text-muted-foreground mt-0.5 text-xs">
                    排序 {rule.position} · 该类目下有 {rule.boundSpuCount} 个 SPU
                  </p>
                </div>
                {writable && (
                  <Button variant="ghost" size="sm" onClick={() => void removeRule(rule)}>
                    <Trash2 className="h-4 w-4" />
                    删除
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}

        {writable && (
          <div className="mt-3 space-y-2 rounded-md border p-3">
            <Field label="新增 / 更新规则" hint="选择一条轴，然后按「查看规则」里的类目名配置。">
              <select
                className={control}
                value={bindDraft.specAxisId}
                onChange={(e) => setBindDraft({ ...bindDraft, specAxisId: e.target.value })}
              >
                <option value="">请选择规格轴</option>
                {enabledAxes.map((axis) => (
                  <option key={axis.id} value={axis.id}>
                    {axis.name}（{axis.code}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="类目">
              <input
                className={control}
                value={ruleCategory}
                onChange={(e) => setRuleCategory(e.target.value)}
              />
            </Field>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={editingRule?.required ?? false}
                onChange={(e) =>
                  setEditingRule(
                    editingRule ? { ...editingRule, required: e.target.checked } : null,
                  )
                }
              />
              该类目下每个 SPU 必须绑定这条轴
            </label>
            <Button
              size="sm"
              disabled={bindDraft.specAxisId === '' || ruleCategory.trim() === ''}
              onClick={() => {
                const draft = emptyCategoryRuleDraft(ruleCategory);
                draft.required = editingRule?.required ?? false;
                setEditingRule(draft);
                const errors = validateCategoryRule(draft);
                if (errors.length > 0) {
                  setRuleError(errors.join('；'));
                  return;
                }
                void saveRuleFor(bindDraft.specAxisId, draft);
              }}
            >
              <Save className="h-4 w-4" />
              保存规则
            </Button>
          </div>
        )}
        <p className="text-muted-foreground mt-2 text-xs">
          删除规则**不会**解绑已有
          SPU：规则只是新建绑定时的准入条件，已经合法的绑定不该被一条新规则连坐改掉。
        </p>
      </Card>

      {editingAxis && (
        <Editor
          title={editingAxis.id ? '编辑规格轴' : '新建规格轴'}
          description="轴编码必须与承载它的 variant_axis 属性定义同名，建后不可改。"
          close={() => {
            setEditingAxis(null);
            setAxisError('');
          }}
        >
          <div className="space-y-3">
            <Field
              label="轴编码"
              hint={
                editingAxis.id
                  ? '建后不可改：已绑定的 SPU 按同 code 的属性承载选项，改码会让全部绑定对不齐。'
                  : '机器标识，使用英文字母、数字、下划线或短横线，如 COLOR / SIZE。'
              }
            >
              <input
                className={control}
                value={editingAxis.draft.code}
                disabled={Boolean(editingAxis.id)}
                onChange={(e) =>
                  setEditingAxis({
                    ...editingAxis,
                    draft: { ...editingAxis.draft, code: e.target.value },
                  })
                }
              />
            </Field>
            <Field label="轴名称">
              <input
                className={control}
                value={editingAxis.draft.name}
                placeholder="颜色 / 尺码 / 容量"
                onChange={(e) =>
                  setEditingAxis({
                    ...editingAxis,
                    draft: { ...editingAxis.draft, name: e.target.value },
                  })
                }
              />
            </Field>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={editingAxis.draft.enabled}
                onChange={(e) =>
                  setEditingAxis({
                    ...editingAxis,
                    draft: { ...editingAxis.draft, enabled: e.target.checked },
                  })
                }
              />
              启用中（停用后不再出现在绑定候选里，历史照常可读）
            </label>
            {axisError && (
              <p role="alert" className="text-destructive text-sm">
                {axisError}
              </p>
            )}
            <Button
              size="sm"
              disabled={axisBusy || validateAxis(editingAxis.draft, !editingAxis.id).length > 0}
              onClick={() => void saveAxisDraft()}
            >
              <Save className="h-4 w-4" />
              保存
            </Button>
          </div>
        </Editor>
      )}

      {panel && overview.topAxes.length > 0 && (
        <Card title="规格轴复用情况" hint="被多个 SPU 复用的轴才是字典发挥价值的地方。">
          <ul className="space-y-1">
            {overview.topAxes.map((axis) => (
              <li key={axis.specAxisId} className="text-muted-foreground text-sm">
                <span className="text-foreground">{axis.name}</span>（{axis.code}）·{' '}
                {axis.boundSpuCount} 个 SPU 在用 · {axis.optionCount} 个选项
              </li>
            ))}
          </ul>
        </Card>
      )}
    </Section>
  );

  async function saveRuleFor(axisId: string, draft: CategoryRuleDraft) {
    setRuleError('');
    try {
      await saveCategoryRule(axisId, draftToCategoryRuleInput(draft));
      await loadRules();
      await load();
    } catch (e) {
      setRuleError(errorText(e, '类目规则保存失败'));
    }
  }
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
