'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  ArrowLeft,
  ClipboardCheck,
  Info,
  ListChecks,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  TriangleAlert,
} from 'lucide-react';
import type {
  AttributeDefinitionPage,
  AttributeDefinitionRow,
  AttributeOptionRow,
  CategoryAttributeRequirement,
  CategoryAttributeRequirementInput,
  CategoryNode,
  CategoryRequirementsOverview,
  CategoryRequirementsView,
  CategoryTree,
  EntryFormSchema,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  CONDITION_OPERATORS,
  REQUIREMENTS,
  conditionText,
  conditionValueTexts,
  deleteRequirement,
  emptyHint,
  emptyRequirementInput,
  errorText,
  fetchEntryFormSchema,
  fetchOverview,
  fetchNodes,
  fetchRequirements,
  fetchTrees,
  internalCategories,
  operatorLabel,
  operatorsFor,
  overviewHint,
  requirementInputProblem,
  requirementLabel,
  saveRequirement,
  setRequirementEnabled,
  sourceText,
  toRequirementInput,
  validateAttributes,
} from './category-requirements-model';
import { api } from './api';

/**
 * 3.3.1.2 类目属性要求工作区。
 *
 * <p>页面按「先选类目、再配要求、最后看表单效果」的顺序排。三个区块各自回答一个问题：
 *
 * <ul>
 *   <li><b>类目属性要求</b> —— 这个类目最终要求哪些属性（生效规则集，标注每条来自本层还是祖先）；
 *   <li><b>录入表单预览</b> —— 商品录入时会渲染成什么样；带一组示例值就能看到条件必填当场亮起来；
 *   <li><b>保存前校验</b> —— 拿一组示例值跑一遍，逐字段列出通过 / 缺失 / 越界 / 不在允许选项内。
 * </ul>
 *
 * <p>后两个区块是本模块对下游（3.1.1.2 商品编辑、04 刊登表单）的**输出预览**：
 * 它们消费的就是下游会调用的那两个端点，所见即所得，不是另做一套演示。
 */
export function CategoryRequirementsWorkspace() {
  const [overview, setOverview] = useState<CategoryRequirementsOverview | null>(null);
  const [trees, setTrees] = useState<CategoryTree[]>([]);
  const [nodes, setNodes] = useState<CategoryNode[]>([]);
  const [view, setView] = useState<CategoryRequirementsView | null>(null);
  const [attributes, setAttributes] = useState<AttributeDefinitionRow[]>([]);
  const [categoryId, setCategoryId] = useState('');
  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<CategoryAttributeRequirementInput | 'new' | null>(null);
  const [onlyOwn, setOnlyOwn] = useState(false);
  const [probe, setProbe] = useState<Record<string, string>>({});
  const [schema, setSchema] = useState<EntryFormSchema | null>(null);
  const [report, setReport] = useState<string>('');

  const categories = useMemo(() => internalCategories(trees, nodes), [trees, nodes]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [overviewResult, treesResult, dictionary] = await Promise.all([
        fetchOverview(),
        fetchTrees(),
        api<AttributeDefinitionPage>('catalog/attributes?size=200'),
      ]);
      setOverview(overviewResult);
      setTrees(treesResult);
      setAttributes(dictionary.items ?? []);
      // 一次取回内部树全部节点作为类目下拉；树多时仍是可控的一次往返。
      const internal = treesResult.filter((tree) => tree.treeKind === 'INTERNAL');
      const lists = await Promise.all(internal.map((tree) => fetchNodes(tree.id)));
      setNodes(lists.flat());
      setReady(true);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const loadCategory = useCallback(async (id: string) => {
    setCategoryId(id);
    setView(null);
    setSchema(null);
    setReport('');
    setProbe({});
    setNotice('');
    setFormError('');
    if (!id) return;
    setLoading(true);
    try {
      setView(await fetchRequirements(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  /** 用当前示例值重算表单预览与校验结果——这两个都是下游会真实调用的端点。 */
  const refreshDerived = useCallback(async () => {
    if (!categoryId) return;
    try {
      setSchema(await fetchEntryFormSchema(categoryId, probe));
      const result = await validateAttributes(categoryId, {
        values: Object.entries(probe)
          .filter(([, value]) => value !== '')
          .map(([attributeCode, value]) => ({ attributeCode, value, attributeId: null })),
      });
      setReport(
        result.valid
          ? `全部 ${result.checkedCount} 个字段通过`
          : `${result.failedCount} / ${result.checkedCount} 个字段未通过`,
      );
    } catch (e) {
      setFormError(errorText(e));
    }
  }, [categoryId, probe]);

  useEffect(() => {
    if (view) void refreshDerived();
  }, [view, refreshDerived]);

  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    setFormError('');
    try {
      await action();
      setNotice(success);
      setEditing(null);
      if (categoryId) setView(await fetchRequirements(categoryId));
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const effective = view?.effective ?? [];
  const rows = onlyOwn ? (view?.own ?? []) : effective;
  const dictionaryOf = useCallback(
    (id: string) => attributes.find((item) => item.id === id) ?? null,
    [attributes],
  );

  if (!ready) {
    return (
      <div className="text-muted-foreground rounded-md border border-dashed p-10 text-center text-sm">
        正在加载类目属性要求…
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-h-20 text-primary font-semibold tracking-tight">类目属性要求</h1>
          <p className="text-muted-foreground text-copy-13 mt-1">
            按类目配置必填、可选与条件必填，以及取值范围。商品录入时按这里的规则生成表单并校验。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link href="/products/categories">
            <Button variant="ghost" size="sm">
              <ArrowLeft className="h-4 w-4" />
              回到分类树
            </Button>
          </Link>
          <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </Button>
        </div>
      </div>

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="内部类目" value={overview.categoryCount} />
          <StatCard label="已配要求" value={overview.categoriesWithRequirement} />
          <StatCard label="规则总数" value={overview.requirementCount} />
          <StatCard label="条件必填" value={overview.conditionalRequirementCount} />
        </div>
      )}
      {overview && <p className="text-muted-foreground text-copy-13">{overviewHint(overview)}</p>}

      <SectionCard
        title="选择类目"
        description="属性要求挂在类目上。子类目默认继承祖先的要求，本类目可以加严或豁免。"
        extra={
          <>
            {notice && <span className="text-copy-13 text-primary">{notice}</span>}
            <Button
              size="sm"
              disabled={!categoryId}
              onClick={() => {
                setEditing('new');
                setFormError('');
              }}
            >
              <Plus className="h-4 w-4" />
              新增要求
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <Field
            label="类目"
            hint="只列内部管理分类树的启用节点；平台类目要求按平台维护，不在这里配。"
          >
            <select
              className={control}
              value={categoryId}
              onChange={(e) => void loadCategory(e.target.value)}
            >
              <option value="">请选择类目</option>
              {categories.map((node) => (
                <option key={node.id} value={node.id}>
                  {node.path || node.name}
                </option>
              ))}
            </select>
          </Field>
          {categories.length === 0 && (
            <p className="text-muted-foreground text-copy-13">
              还没有内部管理类目。先到
              <Link href="/products/categories" className="text-primary underline">
                分类树
              </Link>
              建一个。
            </p>
          )}
        </div>
      </SectionCard>

      {formError && (
        <div className="text-danger flex items-center gap-2 rounded-md border p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {formError}
        </div>
      )}

      {error ? (
        <EmptyState
          icon={<TriangleAlert className="h-5 w-5" />}
          title="类目属性要求加载失败"
          description={error}
          action={
            <Button variant="ghost" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      ) : loading && !view ? (
        <div className="text-muted-foreground rounded-md border border-dashed p-10 text-center text-sm">
          正在加载这个类目的属性要求…
        </div>
      ) : !categoryId ? (
        <EmptyState
          icon={<Info className="h-5 w-5" />}
          title="先选一个类目"
          description="属性要求是「类目要求哪些属性」的规则。先在上面选一个类目，就能看到它最终要求什么、哪些来自祖先。"
        />
      ) : !view || rows.length === 0 ? (
        <EmptyState
          icon={<Info className="h-5 w-5" />}
          title={onlyOwn ? '本类目还没有自己的规则' : '这个类目还没有生效的属性要求'}
          description={emptyHint(view)}
          action={
            onlyOwn ? (
              <Button variant="ghost" onClick={() => setOnlyOwn(false)}>
                显示生效规则（含继承）
              </Button>
            ) : (
              <Button
                variant="ghost"
                onClick={() => {
                  setEditing('new');
                  setFormError('');
                }}
              >
                配置第一条要求
              </Button>
            )
          }
        />
      ) : (
        <>
          <SectionCard
            title="生效的属性要求"
            description={
              view
                ? `${view.categoryPath || view.categoryName} · 共 ${view.summary.effectiveCount} 条，其中必填 ${view.summary.requiredCount} 条、条件必填 ${view.summary.conditionalCount} 条、继承 ${view.summary.inheritedCount} 条`
                : undefined
            }
            extra={
              <Button variant="ghost" size="sm" onClick={() => setOnlyOwn(!onlyOwn)}>
                <ListChecks className="h-4 w-4" />
                {onlyOwn ? '显示生效规则（含继承）' : '只看本类目配置的'}
              </Button>
            }
          >
            <div className="space-y-2">
              {rows.map((rule) => (
                <RequirementRow
                  key={`${rule.sourceCategoryId}-${rule.attributeId}`}
                  rule={rule}
                  canEdit={onlyOwn ? false : !rule.inherited}
                  busy={busy}
                  onEdit={() => {
                    setEditing(toRequirementInput(rule));
                    setFormError('');
                  }}
                  onToggle={(enabled) =>
                    void run(
                      () => setRequirementEnabled(categoryId, rule.attributeId, enabled),
                      enabled ? '已启用这条要求' : '已停用：这个类目不再继承祖先对该属性的要求',
                    )
                  }
                  onDelete={() =>
                    void run(
                      () => deleteRequirement(categoryId, rule.attributeId),
                      '已删除：属性回退到祖先链上的上一条要求',
                    )
                  }
                />
              ))}
            </div>
          </SectionCard>

          <SectionCard
            title="录入表单预览"
            description="这是商品录入时真正会拿到的表单 schema。填一组示例值，就能看到条件必填当场亮起来。"
            extra={report && <span className="text-copy-13 text-muted-foreground">{report}</span>}
          >
            <ProbeEditor
              fields={schema?.fields ?? []}
              probe={probe}
              onChange={(key, value) => setProbe((prev) => ({ ...prev, [key]: value }))}
            />
            {schema && schema.fields.length === 0 ? (
              <p className="text-muted-foreground text-copy-13">
                这个类目没有生效的属性要求，录入表单不会做必填校验。
              </p>
            ) : (
              <SchemaPreview schema={schema} />
            )}
          </SectionCard>
        </>
      )}

      {editing && view && (
        <RequirementEditor
          initial={editing === 'new' ? null : editing}
          attributes={attributes}
          effective={effective}
          existing={view.own}
          busy={busy}
          onClose={() => setEditing(null)}
          onSubmit={(input) =>
            void run(
              () => saveRequirement(categoryId, input),
              editing === 'new' ? '已新增属性要求' : '已更新属性要求',
            )
          }
        />
      )}
    </div>
  );
}

// ------------------------------------------------------------ 规则行

function RequirementRow({
  rule,
  canEdit,
  busy,
  onEdit,
  onToggle,
  onDelete,
}: {
  rule: CategoryAttributeRequirement;
  canEdit: boolean;
  busy: boolean;
  onEdit: () => void;
  onToggle: (enabled: boolean) => void;
  onDelete: () => void;
}) {
  const constraints: string[] = [];
  if (rule.minValue !== null) constraints.push(`下限 ${rule.minValue}`);
  if (rule.maxValue !== null) constraints.push(`上限 ${rule.maxValue}`);
  if (rule.maxLength !== null) constraints.push(`最多 ${rule.maxLength} 字`);
  if (rule.allowedOptions.length > 0) constraints.push(`限定 ${rule.allowedOptions.join(' / ')}`);
  if (rule.allowFreeText) constraints.push('允许自由填写');

  return (
    <div className="rounded-md border p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{rule.attributeName}</span>
            <Badge tone={rule.requirement === 'REQUIRED' ? 'danger' : 'neutral'}>
              {requirementLabel(rule.requirement)}
            </Badge>
            {rule.condition && <Badge tone="warning">条件必填</Badge>}
            {rule.inherited && <Badge tone="neutral">继承</Badge>}
            {!rule.enabled && <Badge tone="neutral">已停用</Badge>}
          </div>
          <p className="text-muted-foreground text-copy-13">
            {sourceText(rule)}
            {rule.attributeCode ? ` · ${rule.attributeCode}` : ''}
            {constraints.length > 0 ? ` · ${constraints.join(' · ')}` : ''}
          </p>
          {rule.condition && (
            <p className="text-copy-13">{conditionText(rule.condition, rule.conditionValues)}</p>
          )}
        </div>
        <div className="flex items-center gap-2">
          {canEdit ? (
            <>
              <Button variant="ghost" size="sm" onClick={onEdit} disabled={busy}>
                <Pencil className="h-4 w-4" />
                编辑
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => onToggle(!rule.enabled)}
                disabled={busy}
              >
                {rule.enabled ? '停用' : '启用'}
              </Button>
              <Button variant="ghost" size="sm" onClick={onDelete} disabled={busy}>
                <Trash2 className="h-4 w-4" />
                删除
              </Button>
            </>
          ) : (
            <span className="text-muted-foreground text-xs">
              继承来的要求请到【{rule.sourceCategoryName}】修改
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

// ------------------------------------------------------------ 预览

function ProbeEditor({
  fields,
  probe,
  onChange,
}: {
  fields: { key: string; label: string; control: string }[];
  probe: Record<string, string>;
  onChange: (key: string, value: string) => void;
}) {
  if (fields.length === 0) return null;
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {fields.map((field) => (
        <Field key={field.key} label={`${field.label}（示例值）`}>
          <input
            className={control}
            value={probe[field.key] ?? ''}
            placeholder="留空表示未填"
            onChange={(e) => onChange(field.key, e.target.value)}
          />
        </Field>
      ))}
    </div>
  );
}

function SchemaPreview({ schema }: { schema: EntryFormSchema | null }) {
  if (!schema || schema.fields.length === 0) return null;
  return (
    <div className="mt-4 space-y-2">
      <p className="text-muted-foreground text-copy-13">
        共 {schema.fieldCount} 个字段，其中 {schema.requiredFieldCount} 个必填、
        {schema.conditionalFieldCount} 个条件必填。
      </p>
      <div className="space-y-2">
        {schema.fields.map((field) => (
          <div key={field.key} className="rounded-md border p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{field.label}</span>
              <Badge tone={field.required ? 'danger' : 'neutral'}>
                {field.required ? '必填' : '可填'}
              </Badge>
              <Badge tone="neutral">{field.control}</Badge>
              {field.inherited && <Badge tone="neutral">继承自 {field.sourceCategoryName}</Badge>}
            </div>
            <p className="text-muted-foreground text-copy-13 mt-1">
              {field.dependency
                ? `${conditionText(field.dependency, field.dependency.values)}${
                    field.dependency.active ? '（当前示例值已触发）' : '（当前示例值未触发）'
                  }`
                : `要求形态：${requirementLabel(field.requirement)}`}
              {field.constraints.allowedOptions
                ? ` · 限定 ${field.constraints.allowedOptions.join(' / ')}`
                : ''}
              {field.constraints.maxValue !== null ? ` · 不超过 ${field.constraints.maxValue}` : ''}
              {field.constraints.minValue !== null ? ` · 不小于 ${field.constraints.minValue}` : ''}
              {field.constraints.maxLength !== null
                ? ` · 最多 ${field.constraints.maxLength} 字`
                : ''}
            </p>
            {field.options.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1">
                {field.options.map((option) => (
                  <Badge key={option.value} tone={option.enabled ? 'neutral' : 'warning'}>
                    {option.value}
                    {option.enabled ? '' : '（已停用）'}
                  </Badge>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// ------------------------------------------------------------ 编辑器

function RequirementEditor({
  initial,
  attributes,
  effective,
  existing,
  busy,
  onClose,
  onSubmit,
}: {
  initial: CategoryAttributeRequirementInput | null;
  attributes: AttributeDefinitionRow[];
  effective: CategoryAttributeRequirement[];
  existing: CategoryAttributeRequirement[];
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: CategoryAttributeRequirementInput) => void;
}) {
  const [attributeId, setAttributeId] = useState(initial?.attributeId ?? '');
  const [requirement, setRequirement] = useState(initial?.requirement ?? 'OPTIONAL');
  const [minValue, setMinValue] = useState(
    initial?.minValue === null || initial?.minValue === undefined ? '' : String(initial.minValue),
  );
  const [maxValue, setMaxValue] = useState(
    initial?.maxValue === null || initial?.maxValue === undefined ? '' : String(initial.maxValue),
  );
  const [maxLength, setMaxLength] = useState(
    initial?.maxLength === null || initial?.maxLength === undefined
      ? ''
      : String(initial.maxLength),
  );
  const [allowFreeText, setAllowFreeText] = useState(initial?.allowFreeText ?? false);
  const [allowedOptions, setAllowedOptions] = useState<string[]>(initial?.allowedOptions ?? []);
  const [position, setPosition] = useState(initial?.position ?? 0);
  const [conditionAttributeId, setConditionAttributeId] = useState(
    initial?.condition?.attributeId ?? '',
  );
  const [operator, setOperator] = useState(initial?.condition?.operator ?? 'IN');
  const [conditionValues, setConditionValues] = useState<string[]>(
    initial ? conditionValueTexts(initial.conditionValues) : [],
  );
  const [conditionValue, setConditionValue] = useState('');
  const [error, setError] = useState('');
  // 选项集合按属性现取：字典列表只给选项**数量**（3.2.1.1 的列表口径），
  // 白名单与条件取值都要拿到具体值。选哪个属性才查哪个，不提前拉全量。
  const [selectedOptions, setSelectedOptions] = useState<AttributeOptionRow[]>([]);
  const [dependencyOptions, setDependencyOptions] = useState<AttributeOptionRow[]>([]);

  const selected = attributes.find((item) => item.id === attributeId) ?? null;
  const dataType = selected?.dataType ?? '';
  const dependency = attributes.find((item) => item.id === conditionAttributeId) ?? null;

  useEffect(() => {
    let cancelled = false;
    if (!attributeId || selected?.dataType !== 'OPTION') {
      setSelectedOptions([]);
      return;
    }
    void api<AttributeOptionRow[]>(`catalog/attributes/${attributeId}/options`)
      .then((rows) => {
        if (!cancelled) setSelectedOptions(rows);
      })
      .catch(() => {
        if (!cancelled) setSelectedOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [attributeId, selected?.dataType]);

  useEffect(() => {
    let cancelled = false;
    if (!conditionAttributeId || dependency?.dataType !== 'OPTION') {
      setDependencyOptions([]);
      return;
    }
    void api<AttributeOptionRow[]>(`catalog/attributes/${conditionAttributeId}/options`)
      .then((rows) => {
        if (!cancelled) setDependencyOptions(rows);
      })
      .catch(() => {
        if (!cancelled) setDependencyOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [conditionAttributeId, dependency?.dataType]);

  const selectedOptionValues = selectedOptions.map((row) => row.value);
  const dependencyOptionValues = dependencyOptions.map((row) => row.value);

  // 已在本类目配置过的属性不再重复出现在候选里（DB 唯一约束会拒绝，写界面先挡掉）。
  // 属性字典列表本身就是 spu_id is null 的口径（3.2.1.1 的语义），前端不需要再过滤。
  const candidates = attributes.filter(
    (item) => !existing.some((rule) => rule.attributeId === item.id) || item.id === attributeId,
  );

  const payload = (): CategoryAttributeRequirementInput => ({
    attributeId,
    requirement,
    minValue: minValue.trim() ? Number(minValue) : null,
    maxValue: maxValue.trim() ? Number(maxValue) : null,
    maxLength: maxLength.trim() ? Number(maxLength) : null,
    allowFreeText,
    position,
    allowedOptions,
    condition:
      requirement === 'CONDITIONAL_REQUIRED' && dependency
        ? {
            attributeId: dependency.id,
            attributeCode: dependency.code,
            attributeName: dependency.name,
            dataType: dependency.dataType,
            operator,
          }
        : null,
    conditionValues: conditionValues.map((value, index) => ({ value, position: index })),
    enabled: initial?.enabled ?? true,
    version: initial?.version ?? 0,
  });

  function submit() {
    if (!attributeId) {
      setError('请选择属性');
      return;
    }
    const problem = requirementInputProblem(payload(), dataType);
    if (problem) {
      setError(problem);
      return;
    }
    if (
      requirement === 'CONDITIONAL_REQUIRED' &&
      dependency?.dataType === 'OPTION' &&
      conditionValues.some((value) => !dependencyOptionValues.includes(value))
    ) {
      // 条件取值必须在前置属性的选项值源里，否则条件永远不会成立。
      setError(`条件取值必须来自【${dependency.name}】的选项集合`);
      return;
    }
    setError('');
    onSubmit(payload());
  }

  return (
    <div className="space-y-4 rounded-md border p-4">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold">{initial ? '编辑属性要求' : '新增属性要求'}</h2>
        <Button variant="ghost" size="sm" onClick={onClose} disabled={busy}>
          关闭
        </Button>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="属性" hint="只能选属性字典里的租户级属性；同一类目同一属性只有一条要求。">
          <select
            className={control}
            value={attributeId}
            disabled={Boolean(initial)}
            onChange={(e) => {
              const id = e.target.value;
              setAttributeId(id);
              const picked = attributes.find((item) => item.id === id) ?? null;
              setAllowedOptions([]);
              if (picked && picked.dataType !== 'OPTION') {
                setConditionAttributeId('');
                setConditionValues([]);
              }
              setError('');
            }}
          >
            <option value="">请选择属性</option>
            {candidates.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}（{item.dataType}）
              </option>
            ))}
          </select>
        </Field>

        <Field label="要求形态">
          <select
            className={control}
            value={requirement}
            onChange={(e) => {
              setRequirement(e.target.value);
              if (e.target.value !== 'CONDITIONAL_REQUIRED') {
                setConditionValues([]);
                setConditionAttributeId('');
              }
            }}
          >
            {REQUIREMENTS.map((item) => (
              <option key={item} value={item}>
                {requirementLabel(item)}
              </option>
            ))}
          </select>
        </Field>

        <Field label="表单字段顺序" hint="数字小的排在前面，决定录入表单里的字段顺序。">
          <input
            className={control}
            type="number"
            min={0}
            value={position}
            onChange={(e) => setPosition(Number(e.target.value))}
          />
        </Field>

        {dataType === 'DECIMAL' && (
          <>
            <Field label="取值下限">
              <input
                className={control}
                value={minValue}
                onChange={(e) => setMinValue(e.target.value)}
              />
            </Field>
            <Field label="取值上限">
              <input
                className={control}
                value={maxValue}
                onChange={(e) => setMaxValue(e.target.value)}
              />
            </Field>
          </>
        )}

        {dataType === 'TEXT' && (
          <Field label="文本长度上限" hint="留空表示不限制。">
            <input
              className={control}
              type="number"
              min={1}
              value={maxLength}
              onChange={(e) => setMaxLength(e.target.value)}
            />
          </Field>
        )}

        {dataType === 'OPTION' && (
          <>
            <Field
              label="允许的取值"
              hint="留空表示属性选项值源里的全部选项都可选；勾了「允许自由填写」就不能再限定子集。"
            >
              <div className="max-h-40 space-y-1 overflow-auto rounded-md border p-2">
                {selectedOptionValues.length === 0 && (
                  <p className="text-muted-foreground text-xs">
                    {dataType === 'OPTION' ? '正在读取选项…' : '这个属性不是枚举属性，没有选项值'}
                  </p>
                )}
                {selectedOptionValues.map((option) => (
                  <label key={option} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={allowedOptions.includes(option)}
                      onChange={(e) =>
                        setAllowedOptions((prev) =>
                          e.target.checked ? [...prev, option] : prev.filter((v) => v !== option),
                        )
                      }
                    />
                    {option}
                  </label>
                ))}
              </div>
            </Field>
            <Field label="允许自由填写" hint="开启后录入可以提交不在选项集里的值。">
              <input
                type="checkbox"
                checked={allowFreeText}
                onChange={(e) => setAllowFreeText(e.target.checked)}
              />
            </Field>
          </>
        )}
      </div>

      {requirement === 'CONDITIONAL_REQUIRED' && (
        <div className="space-y-3 rounded-md border p-3">
          <p className="text-copy-13 font-medium">条件：满足时才必填</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field
              label="前置属性"
              hint="只有当前类目生效的属性才能当条件来源，否则这个条件永远无法被满足。"
            >
              <select
                className={control}
                value={conditionAttributeId}
                onChange={(e) => {
                  setConditionAttributeId(e.target.value);
                  setConditionValues([]);
                  setError('');
                }}
              >
                <option value="">请选择前置属性</option>
                {effective
                  .filter((item) => item.attributeId !== attributeId)
                  .map((item) => (
                    <option key={item.attributeId} value={item.attributeId}>
                      {item.attributeName}（{item.dataType}）
                    </option>
                  ))}
              </select>
            </Field>
            <Field label="比较算子" hint="枚举与文本属性没有大小关系，不提供大于/小于这类算子。">
              <select
                className={control}
                value={operator}
                onChange={(e) => setOperator(e.target.value)}
              >
                {operatorsFor(dependency?.dataType ?? 'OPTION').map((item) => (
                  <option key={item} value={item}>
                    {operatorLabel(item)}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <Field label="条件取值" hint="至少一个。取值集合为空时条件恒不成立，这条要求等于没配。">
            <div className="flex gap-2">
              <input
                className={control}
                value={conditionValue}
                list="condition-value-options"
                onChange={(e) => setConditionValue(e.target.value)}
              />
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  const value = conditionValue.trim();
                  if (!value || conditionValues.includes(value)) return;
                  setConditionValues((prev) => [...prev, value]);
                  setConditionValue('');
                }}
              >
                <Plus className="h-4 w-4" />
                加入
              </Button>
            </div>
            <datalist id="condition-value-options">
              {dependencyOptionValues.map((option) => (
                <option key={option} value={option} />
              ))}
            </datalist>
          </Field>
          <div className="flex flex-wrap gap-2">
            {conditionValues.map((value) => (
              <Badge key={value} tone="neutral">
                {value}
                <button
                  type="button"
                  className="ml-1"
                  onClick={() => setConditionValues((prev) => prev.filter((v) => v !== value))}
                >
                  ×
                </button>
              </Badge>
            ))}
          </div>
          {dependency && conditionValues.length > 0 && (
            <p className="text-copy-13 text-muted-foreground">
              <ClipboardCheck className="mr-1 inline h-3.5 w-3.5" />
              {conditionText(
                {
                  attributeId: dependency.id,
                  attributeCode: dependency.code,
                  attributeName: dependency.name,
                  dataType: dependency.dataType,
                  operator,
                },
                conditionValues,
              )}
            </p>
          )}
        </div>
      )}

      {error && (
        <div className="text-danger flex items-center gap-2 rounded-md border p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}

      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose} disabled={busy}>
          取消
        </Button>
        <Button onClick={submit} disabled={busy || !attributeId}>
          <Save className="h-4 w-4" />
          保存
        </Button>
      </div>
    </div>
  );
}
