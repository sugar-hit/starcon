'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import {
  ArrowLeft,
  ListFilter,
  Plus,
  RefreshCw,
  Save,
  Tags,
  Trash2,
  TriangleAlert,
} from 'lucide-react';
import type {
  AttributeDefinitionRow,
  AttributeDictionaryOverview,
  AttributeOptionRow,
  CategoryAttributeRule,
} from '../../../../../packages/api-contracts/src/index';
import {
  DATA_TYPES,
  USAGES,
  VALUE_MODES,
  addOption,
  attributeDraft,
  createAttribute,
  deleteAttribute,
  deleteCategoryRule,
  deleteOption,
  draftToAttributeInput,
  draftToRuleInput,
  emptyAttributeDraft,
  emptyRuleDraft,
  errorText,
  fetchAttributeOverview,
  fetchAttributes,
  fetchCategoryRules,
  fetchOptions,
  isConflict,
  labelOf,
  optionCountHint,
  ruleRangeText,
  saveCategoryRule,
  timestamp,
  updateAttribute,
  updateOption,
  usageText,
  validateAttribute,
  validateRule,
  variantAxisHint,
  type AttributeDraft,
  type RuleDraft,
} from './attribute-dictionary-model';
import { Editor, Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.2.1.1 属性定义列表工作区。
 *
 * <p>分区按「先把字典准备好、再挂到类目上」的顺序排：概览与治理缺口 → 属性字典（可分页筛选）
 * → 选中属性的选项集合 → 类目绑定规则。
 *
 * <p>四条界面纪律：
 *
 * <ul>
 *   <li>**字典与商品的 SPU 属性不是一回事**。这里只列租户级字典条目；商品自己用哪些属性、
 *       取值是什么，在商品档案里维护。混在一起会让运营以为「这里改一下所有商品都变了」。
 *   <li>**「枚举属性没有取值」要分两种说法**。一条取值都没有 vs 取值全被停用，
 *       下一步动作完全不同，都画成「0 个选项」会让人以为配置已生效。
 *   <li>**停用与删除分开**。有引用（商品取值 / 类目规则）的取值只能停用，停用挡住的是
 *       「不许再新建取用」，历史数据照常可读。界面上在点删除之前就把引用标出来。
 *   <li>**类目规则要把取值范围讲出来**。只显示「必填」的话，运营不知道录入时能选什么值。
 * </ul>
 */
export function AttributeDictionaryWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('catalog.attribute.read');
  const writable = session.permissions.includes('catalog.attribute.update');

  const [overview, setOverview] = useState<AttributeDictionaryOverview | null>(null);
  const [rows, setRows] = useState<AttributeDefinitionRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [dataType, setDataType] = useState('');
  const [usage, setUsage] = useState('');
  const [enabledFilter, setEnabledFilter] = useState<boolean | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [editing, setEditing] = useState<{ id: string | null; draft: AttributeDraft } | null>(null);
  const [draftErrors, setDraftErrors] = useState<string[]>([]);
  const [saveBusy, setSaveBusy] = useState(false);
  const [actionError, setActionError] = useState('');

  const [selected, setSelected] = useState<AttributeDefinitionRow | null>(null);
  const [optionRows, setOptionRows] = useState<AttributeOptionRow[]>([]);
  const [optionsLoading, setOptionsLoading] = useState(false);
  const [optionError, setOptionError] = useState('');
  const [optionValue, setOptionValue] = useState('');
  const [optionEnabled, setOptionEnabled] = useState(true);

  const [ruleCategory, setRuleCategory] = useState('');
  const [rules, setRules] = useState<CategoryAttributeRule[]>([]);
  const [rulesLoading, setRulesLoading] = useState(false);
  const [ruleError, setRuleError] = useState('');
  const [ruleDraft, setRuleDraft] = useState<RuleDraft>(emptyRuleDraft());
  const [ruleErrors, setRuleErrors] = useState<string[]>([]);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [nextOverview, next] = await Promise.all([
        fetchAttributeOverview(),
        fetchAttributes({
          keyword,
          dataType,
          usage,
          enabled: enabledFilter,
          page,
          size: PAGE_SIZE,
        }),
      ]);
      setOverview(nextOverview);
      setRows(next.items);
      setTotal(next.total);
    } catch (e) {
      setError(errorText(e, '属性字典加载失败'));
    } finally {
      setLoading(false);
    }
  }, [readable, keyword, dataType, usage, enabledFilter, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadOptions = useCallback(async (row: AttributeDefinitionRow) => {
    setOptionsLoading(true);
    setOptionError('');
    try {
      setOptionRows(await fetchOptions(row.id));
    } catch (e) {
      setOptionRows([]);
      setOptionError(errorText(e, '选项集合加载失败'));
    } finally {
      setOptionsLoading(false);
    }
  }, []);

  const loadRules = useCallback(async () => {
    if (!ruleCategory.trim()) {
      setRules([]);
      return;
    }
    setRulesLoading(true);
    setRuleError('');
    try {
      setRules(await fetchCategoryRules(ruleCategory.trim(), null));
    } catch (e) {
      setRules([]);
      setRuleError(errorText(e, '类目规则加载失败'));
    } finally {
      setRulesLoading(false);
    }
  }, [ruleCategory]);

  useEffect(() => {
    void loadRules();
  }, [loadRules]);

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const selectedHint = useMemo(() => (selected ? optionCountHint(selected) : null), [selected]);
  const enabledOptions = useMemo(() => optionRows.filter((row) => row.enabled), [optionRows]);

  function startCreate() {
    setEditing({ id: null, draft: emptyAttributeDraft() });
    setDraftErrors([]);
    setActionError('');
  }

  function startEdit(row: AttributeDefinitionRow) {
    setEditing({ id: row.id, draft: attributeDraft(row) });
    setDraftErrors([]);
    setActionError('');
  }

  async function submitDefinition() {
    if (!editing) return;
    const problems = validateAttribute(editing.draft, !editing.id);
    setDraftErrors(problems);
    if (problems.length > 0) return;
    setSaveBusy(true);
    setActionError('');
    try {
      const input = draftToAttributeInput(editing.draft);
      if (editing.id) await updateAttribute(editing.id, input);
      else await createAttribute(input);
      setEditing(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '属性定义保存失败'));
    } finally {
      setSaveBusy(false);
    }
  }

  async function removeDefinition(row: AttributeDefinitionRow) {
    setActionError('');
    try {
      await deleteAttribute(row.id, row.version);
      if (selected?.id === row.id) {
        setSelected(null);
        setOptionRows([]);
      }
      await load();
    } catch (e) {
      setActionError(
        isConflict(e)
          ? `${errorText(e, '删除失败')}　这类情况请改为「停用」：停用只挡住新建使用，历史数据仍可读。`
          : errorText(e, '属性定义删除失败'),
      );
    }
  }

  async function select(row: AttributeDefinitionRow) {
    setSelected(row);
    setOptionValue('');
    setRuleDraft((draft) => ({ ...draft, allowedOptions: [] }));
    setActionError('');
    await loadOptions(row);
  }

  async function submitOption() {
    if (!selected) return;
    const value = optionValue.trim();
    if (value === '') {
      setOptionError('选项值不能为空');
      return;
    }
    setOptionError('');
    try {
      await addOption(selected.id, { value, position: 0, enabled: optionEnabled });
      setOptionValue('');
      await loadOptions(selected);
      await load();
    } catch (e) {
      setOptionError(errorText(e, '选项登记失败'));
    }
  }

  async function toggleOption(option: AttributeOptionRow) {
    if (!selected) return;
    setOptionError('');
    try {
      await updateOption(selected.id, option.value, {
        value: option.value,
        position: option.position,
        enabled: !option.enabled,
      });
      await loadOptions(selected);
      await load();
    } catch (e) {
      setOptionError(errorText(e, '选项更新失败'));
    }
  }

  async function removeOption(option: AttributeOptionRow) {
    if (!selected) return;
    setOptionError('');
    try {
      await deleteOption(selected.id, option.value);
      await loadOptions(selected);
      await load();
    } catch (e) {
      setOptionError(
        errorText(e, '选项删除失败') + (isConflict(e) ? '　有引用的取值只能停用，不能删除。' : ''),
      );
    }
  }

  async function submitRule() {
    if (!selected) return;
    const problems = validateRule(ruleDraft, selected.dataType, optionRows);
    setRuleErrors(problems);
    if (problems.length > 0) return;
    setRuleError('');
    try {
      await saveCategoryRule(selected.id, draftToRuleInput(ruleDraft));
      setRuleDraft(emptyRuleDraft(ruleDraft.category));
      await Promise.all([loadRules(), load()]);
    } catch (e) {
      setRuleError(errorText(e, '类目规则保存失败'));
    }
  }

  async function removeRule(rule: CategoryAttributeRule) {
    if (!selected) return;
    setRuleError('');
    try {
      await deleteCategoryRule(selected.id, rule.category);
      await Promise.all([loadRules(), load()]);
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
          正在加载属性字典…
        </p>
      </Section>
    );

  if (error || !overview)
    return (
      <Section frame>
        <BackLink />
        <p role="alert" className="text-destructive text-sm">
          {error || '属性字典加载失败'}
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
          <h2 className="text-xl font-semibold">属性定义</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            维护本租户的<b>属性字典</b>：名称、编码、数据类型、用途、是否必填 / 参与筛选 / 多值，
            以及按类目配置的必填与可选范围。字典条目被所有商品共用；
            商品自己用哪些属性、取值是什么，在商品档案里维护。
          </p>
        </div>
        <div className="flex items-center gap-2">
          {writable && (
            <Button size="sm" onClick={startCreate}>
              <Plus className="h-4 w-4" />
              新建属性
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
          label="属性字典"
          value={`${overview.enabledAttributeCount} / ${overview.dictionaryAttributeCount}`}
          hint="启用中 / 全部（租户级字典）"
        />
        <Stat
          label="选项"
          value={`${overview.optionCount - overview.disabledOptionCount} / ${overview.optionCount}`}
          hint="启用中 / 全部（与规格轴共享值源）"
        />
        <Stat label="类目规则" value={overview.categoryRuleCount} hint="按类目配的必填与取值范围" />
        <Stat
          label="商品侧属性定义"
          value={overview.spuLevelDefinitionCount}
          hint="各商品自建的属性，与字典分开统计"
        />
      </div>

      {overview.categoryWithoutRuleCount > 0 && (
        <div className="border-warning/30 bg-warning/5 text-warning rounded-md border p-3 text-sm">
          <p className="flex items-center gap-2 font-medium">
            <TriangleAlert className="h-4 w-4" />有 {overview.categoryWithoutRuleCount}{' '}
            个类目还没配任何属性要求
          </p>
          <p className="mt-1 text-xs">
            这些类目下的商品录入不会做必填校验。在下方「类目绑定规则」里选一个类目即可补齐。
          </p>
        </div>
      )}

      {actionError && (
        <p role="alert" className="text-destructive rounded-md border p-3 text-sm">
          {actionError}
        </p>
      )}

      {/* 字典列表 */}
      <Card
        title="属性字典"
        hint="只列租户级字典条目；商品自建的 SPU 属性不在这里，两者靠编码对齐。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-56">
            <Field label="搜索">
              <input
                className={control}
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPage(0);
                }}
                placeholder="编码或名称"
              />
            </Field>
          </div>
          <div className="w-36">
            <Field label="数据类型">
              <select
                aria-label="按数据类型筛选"
                className={control}
                value={dataType}
                onChange={(e) => {
                  setDataType(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部</option>
                {DATA_TYPES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <div className="w-36">
            <Field label="用途">
              <select
                aria-label="按用途筛选"
                className={control}
                value={usage}
                onChange={(e) => {
                  setUsage(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部</option>
                {USAGES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <div className="w-36">
            <Field label="启用状态">
              <select
                aria-label="按启用状态筛选"
                className={control}
                value={enabledFilter === null ? '' : String(enabledFilter)}
                onChange={(e) => {
                  const next = e.target.value;
                  setEnabledFilter(next === '' ? null : next === 'true');
                  setPage(0);
                }}
              >
                <option value="">全部</option>
                <option value="true">仅启用</option>
                <option value="false">仅停用</option>
              </select>
            </Field>
          </div>
        </div>

        {rows.length === 0 ? (
          <p className="text-muted-foreground mt-4 text-sm">
            {keyword || dataType || usage || enabledFilter !== null
              ? '当前筛选条件下没有匹配的属性。换个关键词，或把筛选条件清空。'
              : '还没有任何属性字典条目。点右上角「新建属性」开始。'}
          </p>
        ) : (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground border-b text-left text-xs">
                <tr>
                  <th className="py-2 pr-3">编码</th>
                  <th className="py-2 pr-3">名称</th>
                  <th className="py-2 pr-3">类型</th>
                  <th className="py-2 pr-3">用途</th>
                  <th className="py-2 pr-3">标记</th>
                  <th className="py-2 pr-3">使用情况</th>
                  <th className="py-2 pr-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-b last:border-0">
                    <td className="py-2 pr-3 font-mono text-xs">{row.code}</td>
                    <td className="py-2 pr-3">{row.name}</td>
                    <td className="py-2 pr-3">
                      {labelOf(DATA_TYPES, row.dataType)}
                      {row.unit && <span className="text-muted-foreground"> ({row.unit})</span>}
                    </td>
                    <td className="py-2 pr-3">{labelOf(USAGES, row.usage)}</td>
                    <td className="py-2 pr-3">
                      <span className="flex flex-wrap gap-1">
                        {row.variantAxis && <Badge tone="info">规格轴</Badge>}
                        {row.required && <Badge tone="warning">必填</Badge>}
                        {row.filterable && <Badge tone="info">可筛选</Badge>}
                        {row.multiple && <Badge>多值</Badge>}
                        {!row.enabled && <Badge tone="neutral">已停用</Badge>}
                      </span>
                    </td>
                    <td className="text-muted-foreground py-2 pr-3 text-xs">
                      {usageText(row)}
                      {optionCountHint(row) && (
                        <span className="text-warning block">{optionCountHint(row)}</span>
                      )}
                    </td>
                    <td className="py-2 pr-3">
                      <span className="flex items-center gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void select(row)}
                          aria-label={`管理 ${row.name} 的选项`}
                        >
                          <Tags className="h-4 w-4" />
                          选项
                        </Button>
                        {writable && (
                          <>
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => startEdit(row)}
                              aria-label={`编辑 ${row.name}`}
                            >
                              编辑
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => void removeDefinition(row)}
                              aria-label={`删除 ${row.name}`}
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </>
                        )}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="text-muted-foreground mt-3 flex items-center justify-between text-xs">
          <span>
            共 {total} 条，第 {page + 1} / {totalPages} 页
          </span>
          <span className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((value) => Math.max(0, value - 1))}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((value) => value + 1)}
            >
              下一页
            </Button>
          </span>
        </div>
      </Card>

      {/* 选中属性的选项集合 */}
      {selected && (
        <Card
          title={`选项集合 · ${selected.name}`}
          hint="取值与规格轴共享同一个值源，所以这里登记的值也能被规格轴直接引用。停用不删除：历史商品还要靠它解析。"
        >
          {selectedHint && (
            <p className="text-warning mb-3 flex items-start gap-2 text-xs">
              <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              {selectedHint}
            </p>
          )}
          {selected.dataType !== 'OPTION' ? (
            <p className="text-muted-foreground text-sm">
              「{selected.name}」是{labelOf(DATA_TYPES, selected.dataType)}属性，没有取值集合。
              只有枚举属性才需要在这里登记可选值。
            </p>
          ) : optionsLoading ? (
            <p role="status" className="text-muted-foreground text-sm">
              正在加载选项…
            </p>
          ) : optionRows.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有任何取值。在下方输入取值并登记。</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground border-b text-left text-xs">
                  <tr>
                    <th className="py-2 pr-3">取值</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3">引用</th>
                    <th className="py-2 pr-3">创建时间</th>
                    <th className="py-2 pr-3">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {optionRows.map((option) => (
                    <tr key={option.value} className="border-b last:border-0">
                      <td className="py-2 pr-3">{option.value}</td>
                      <td className="py-2 pr-3">
                        {option.enabled ? (
                          <Badge tone="success">启用</Badge>
                        ) : (
                          <Badge tone="neutral">已停用</Badge>
                        )}
                      </td>
                      <td className="text-muted-foreground py-2 pr-3 text-xs">
                        {option.inUse
                          ? `${option.productValueCount} 处商品取值 · ${option.categoryRuleCount} 条类目规则`
                          : '未被引用'}
                      </td>
                      <td className="text-muted-foreground py-2 pr-3 text-xs">
                        {timestamp(option.createdAt)}
                      </td>
                      <td className="py-2 pr-3">
                        <span className="flex items-center gap-1">
                          {writable && (
                            <>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => void toggleOption(option)}
                                aria-label={`${option.enabled ? '停用' : '启用'} ${option.value}`}
                              >
                                {option.enabled ? '停用' : '启用'}
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                disabled={option.inUse}
                                title={
                                  option.inUse ? '有引用的取值只能停用，不能删除' : '删除这个取值'
                                }
                                onClick={() => void removeOption(option)}
                                aria-label={`删除 ${option.value}`}
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </>
                          )}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {optionError && (
            <p role="alert" className="text-destructive mt-3 text-xs">
              {optionError}
            </p>
          )}

          {writable && selected.dataType === 'OPTION' && (
            <div className="mt-3 flex flex-wrap items-end gap-3">
              <div className="w-56">
                <Field label="新增取值">
                  <input
                    className={control}
                    value={optionValue}
                    onChange={(e) => setOptionValue(e.target.value)}
                    placeholder="如：深空灰"
                  />
                </Field>
              </div>
              <Toggle label="启用" checked={optionEnabled} onChange={setOptionEnabled} />
              <Button size="sm" onClick={() => void submitOption()}>
                <Plus className="h-4 w-4" />
                登记取值
              </Button>
            </div>
          )}
        </Card>
      )}

      {/* 类目绑定规则 */}
      <Card
        title="类目绑定规则"
        hint="按类目配置属性的必填与取值范围。类目沿用商品的分类文本，分类树模块交付后应改挂类目主键。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-56">
            <Field label="类目">
              <input
                className={control}
                value={ruleCategory}
                onChange={(e) => {
                  setRuleCategory(e.target.value);
                  setRuleDraft((draft) => ({ ...draft, category: e.target.value }));
                }}
                placeholder="如：服饰"
              />
            </Field>
          </div>
          <Button variant="outline" size="sm" onClick={() => void loadRules()}>
            <ListFilter className="h-4 w-4" />
            查询
          </Button>
        </div>

        {!ruleCategory.trim() ? (
          <p className="text-muted-foreground mt-4 text-sm">
            先在上面填一个类目，才能查看或配置它的属性要求。
          </p>
        ) : rulesLoading ? (
          <p role="status" className="text-muted-foreground mt-4 text-sm">
            正在加载类目规则…
          </p>
        ) : rules.length === 0 ? (
          <p className="text-muted-foreground mt-4 text-sm">
            类目「{ruleCategory}」还没有任何属性要求。在下方选中一条属性并配置。
          </p>
        ) : (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground border-b text-left text-xs">
                <tr>
                  <th className="py-2 pr-3">属性</th>
                  <th className="py-2 pr-3">必填</th>
                  <th className="py-2 pr-3">取值范围</th>
                  <th className="py-2 pr-3">类目内商品</th>
                  <th className="py-2 pr-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {rules.map((rule) => (
                  <tr key={rule.attributeId} className="border-b last:border-0">
                    <td className="py-2 pr-3">
                      <span className="font-mono text-xs">{rule.attributeCode}</span>
                      <span className="ml-2">{rule.attributeName}</span>
                    </td>
                    <td className="py-2 pr-3">
                      {rule.required ? <Badge tone="warning">必填</Badge> : <Badge>可选</Badge>}
                    </td>
                    <td className="py-2 pr-3 text-xs">{ruleRangeText(rule)}</td>
                    <td className="text-muted-foreground py-2 pr-3 text-xs">
                      {rule.productCount} 个商品
                    </td>
                    <td className="py-2 pr-3">
                      {writable && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void removeRule(rule)}
                          aria-label={`删除 ${rule.attributeName} 在 ${rule.category} 的规则`}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {ruleError && (
          <p role="alert" className="text-destructive mt-3 text-xs">
            {ruleError}
          </p>
        )}

        {writable && selected && (
          <div className="mt-4 space-y-3 border-t pt-4">
            <p className="text-sm">
              为「{selected.name}」配置类目「{ruleCategory || '（未填写）'}」的要求：
            </p>
            <div className="grid gap-3 md:grid-cols-2">
              {selected.dataType === 'DECIMAL' && (
                <>
                  <Field label="取值下限" hint="留空表示不限">
                    <input
                      className={control}
                      value={ruleDraft.minValue}
                      onChange={(e) =>
                        setRuleDraft((draft) => ({ ...draft, minValue: e.target.value }))
                      }
                      placeholder="留空表示不限"
                    />
                  </Field>
                  <Field label="取值上限" hint="留空表示不限">
                    <input
                      className={control}
                      value={ruleDraft.maxValue}
                      onChange={(e) =>
                        setRuleDraft((draft) => ({ ...draft, maxValue: e.target.value }))
                      }
                      placeholder="留空表示不限"
                    />
                  </Field>
                </>
              )}
              {selected.dataType === 'OPTION' && (
                <div className="md:col-span-2">
                  <Field label="可选值范围" hint="不勾任何一项表示允许全部可用取值">
                    <div className="flex flex-wrap gap-2">
                      {enabledOptions.map((option) => {
                        const checked = ruleDraft.allowedOptions.includes(option.value);
                        return (
                          <label key={option.value} className="flex items-center gap-1.5 text-sm">
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={() =>
                                setRuleDraft((draft) => ({
                                  ...draft,
                                  allowedOptions: checked
                                    ? draft.allowedOptions.filter((v) => v !== option.value)
                                    : [...draft.allowedOptions, option.value],
                                }))
                              }
                            />
                            {option.value}
                          </label>
                        );
                      })}
                      {enabledOptions.length === 0 && (
                        <span className="text-muted-foreground text-xs">
                          这条属性还没有启用的取值，先在上方登记取值。
                        </span>
                      )}
                    </div>
                  </Field>
                </div>
              )}
              <Field label="排序位置">
                <input
                  className={control}
                  value={ruleDraft.position}
                  onChange={(e) =>
                    setRuleDraft((draft) => ({ ...draft, position: e.target.value }))
                  }
                  placeholder="0"
                />
              </Field>
            </div>
            <Toggle
              label="该类目下必填"
              checked={ruleDraft.required}
              onChange={(required) => setRuleDraft((draft) => ({ ...draft, required }))}
            />
            {ruleErrors.length > 0 && (
              <ul role="alert" className="text-destructive space-y-1 text-xs">
                {ruleErrors.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            )}
            <Button size="sm" onClick={() => void submitRule()}>
              <Save className="h-4 w-4" />
              保存类目规则
            </Button>
          </div>
        )}

        {writable && !selected && (
          <p className="text-muted-foreground mt-4 text-sm">
            先在上方字典列表里点一条属性的「选项」按钮选中它，才能配置类目规则。
          </p>
        )}
      </Card>

      {/* 新建 / 编辑：走 Modal，与规格轴同一形态 */}
      {editing && (
        <Editor
          title={editing.id ? '编辑属性定义' : '新建属性定义'}
          description={
            editing.id
              ? '编码、数据类型与「是否规格轴」建后不可改：商品的 SPU 级属性与规格轴都按同 code 对齐这条字典条目。'
              : '枚举属性需要至少一个取值（每行一个）；建好之后也能随时增删改。'
          }
          close={() => setEditing(null)}
        >
          <div className="space-y-3">
            <Field
              label="编码"
              hint={
                editing.id
                  ? '建后不可改'
                  : '机器标识，使用英文字母、数字、下划线或短横线，如 COLOR / NET_WEIGHT。'
              }
            >
              <input
                className={control}
                value={editing.draft.code}
                disabled={Boolean(editing.id)}
                onChange={(e) =>
                  setEditing({ ...editing, draft: { ...editing.draft, code: e.target.value } })
                }
                placeholder="COLOR"
              />
            </Field>
            <Field label="名称">
              <input
                className={control}
                value={editing.draft.name}
                onChange={(e) =>
                  setEditing({ ...editing, draft: { ...editing.draft, name: e.target.value } })
                }
                placeholder="颜色"
              />
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="数据类型">
                <select
                  aria-label="数据类型"
                  className={control}
                  value={editing.draft.dataType}
                  disabled={Boolean(editing.id)}
                  onChange={(e) => {
                    const next = e.target.value;
                    setEditing({
                      ...editing,
                      draft: { ...editing.draft, dataType: next, options: '' },
                    });
                  }}
                >
                  {DATA_TYPES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="取值模式" hint="值放在商品层还是 SPU 层">
                <select
                  aria-label="取值模式"
                  className={control}
                  value={editing.draft.valueMode}
                  onChange={(e) =>
                    setEditing({
                      ...editing,
                      draft: { ...editing.draft, valueMode: e.target.value },
                    })
                  }
                >
                  {VALUE_MODES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="用途" hint="描述 / 规格 / 物流 / 合规">
                <select
                  aria-label="用途"
                  className={control}
                  value={editing.draft.usage}
                  onChange={(e) =>
                    setEditing({ ...editing, draft: { ...editing.draft, usage: e.target.value } })
                  }
                >
                  {USAGES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="分组名称" hint="留空按用途自动归类">
                <input
                  className={control}
                  value={editing.draft.groupName}
                  onChange={(e) =>
                    setEditing({
                      ...editing,
                      draft: { ...editing.draft, groupName: e.target.value },
                    })
                  }
                  placeholder="基本参数"
                />
              </Field>
              <Field label="单位" hint="仅数字属性有意义">
                <input
                  className={control}
                  value={editing.draft.unit}
                  onChange={(e) =>
                    setEditing({ ...editing, draft: { ...editing.draft, unit: e.target.value } })
                  }
                  placeholder="如 % / cm / kg"
                />
              </Field>
              <Field label="排序位置">
                <input
                  className={control}
                  value={editing.draft.position}
                  onChange={(e) =>
                    setEditing({
                      ...editing,
                      draft: { ...editing.draft, position: e.target.value },
                    })
                  }
                  placeholder="0"
                />
              </Field>
            </div>

            <div className="flex flex-wrap gap-4">
              <Toggle
                label="必填"
                checked={editing.draft.required}
                onChange={(required) =>
                  setEditing({ ...editing, draft: { ...editing.draft, required } })
                }
              />
              <Toggle
                label="参与筛选"
                checked={editing.draft.filterable}
                onChange={(filterable) =>
                  setEditing({ ...editing, draft: { ...editing.draft, filterable } })
                }
              />
              <Toggle
                label="多值"
                checked={editing.draft.multiple}
                onChange={(multiple) =>
                  setEditing({ ...editing, draft: { ...editing.draft, multiple } })
                }
              />
              <Toggle
                label="作为规格轴"
                checked={editing.draft.variantAxis}
                disabled={Boolean(editing.id)}
                onChange={(variantAxis) =>
                  setEditing({ ...editing, draft: { ...editing.draft, variantAxis } })
                }
              />
              <Toggle
                label="启用"
                checked={editing.draft.enabled}
                onChange={(enabled) =>
                  setEditing({ ...editing, draft: { ...editing.draft, enabled } })
                }
              />
            </div>

            {editing.draft.variantAxis && variantAxisHint(editing.draft) && (
              <p className="text-warning flex items-start gap-2 text-xs">
                <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                {variantAxisHint(editing.draft)}
              </p>
            )}

            {!editing.id && editing.draft.dataType === 'OPTION' && (
              <Field label="选项取值" hint="每行一个">
                <textarea
                  aria-label="选项取值"
                  className={`${control} min-h-24`}
                  value={editing.draft.options}
                  onChange={(e) =>
                    setEditing({
                      ...editing,
                      draft: { ...editing.draft, options: e.target.value },
                    })
                  }
                  placeholder={'红色\n蓝色\n黑色'}
                />
              </Field>
            )}

            {draftErrors.length > 0 && (
              <ul role="alert" className="text-destructive space-y-1 text-xs">
                {draftErrors.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            )}

            <div className="flex items-center gap-2">
              <Button size="sm" disabled={saveBusy} onClick={() => void submitDefinition()}>
                <Save className="h-4 w-4" />
                {saveBusy ? '保存中…' : '保存'}
              </Button>
              <Button variant="outline" size="sm" onClick={() => setEditing(null)}>
                取消
              </Button>
            </div>
          </div>
        </Editor>
      )}
    </Section>
  );
}

function Toggle({
  label,
  checked,
  onChange,
  disabled,
}: {
  label: string;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label className="flex items-center gap-1.5 text-sm">
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
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
      href="/products"
      className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 text-sm"
    >
      <ArrowLeft className="h-4 w-4" />
      返回商品中心
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
