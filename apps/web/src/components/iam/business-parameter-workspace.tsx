'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RefreshCw, Search, Snowflake } from 'lucide-react';
import type {
  BusinessParameterDefinitionView,
  BusinessParameterEffectiveView,
  BusinessParameterFreezeView,
  BusinessParameterHistoryView,
  BusinessParameterOverrideView,
  LegalEntityView,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';
import {
  ACTION_LABELS,
  CATEGORY_LABELS,
  SCOPE_TYPE_LABELS,
  effectiveSummary,
  findExistingOverride,
  formatValue,
  groupByCategory,
  historySummary,
  scopeLabel,
  sourceLabel,
  validateScope,
  validateValue,
  type ParameterDefinition,
} from './business-parameter-model';

/**
 * 17.5.1.1 业务参数。
 *
 * 三个区块对应蓝图的三件事：**生效值**（默认 + 生效范围解析）、**覆盖配置**（改哪一档）、
 * **单据冻结**（改参数不改历史单据已冻结的行为）。
 *
 * 店铺在 channel 服务的独立库里，identity 按服务边界只存逻辑 ID，所以这里让用户
 * 直接填店铺 ID 与名称——名称会作为历史可读标签一起冻结，店铺改名后仍能读懂旧单据。
 */
export function BusinessParameterWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  // 生效范围上下文：法人与店铺决定解析出哪一档的值
  const [entityId, setEntityId] = useState('');
  const [shopId, setShopId] = useState('');
  const [shopLabel, setShopLabel] = useState('');

  const [definitions, setDefinitions] = useState<BusinessParameterDefinitionView[]>([]);
  const [effective, setEffective] = useState<BusinessParameterEffectiveView[]>([]);
  const [overrides, setOverrides] = useState<BusinessParameterOverrideView[]>([]);
  const [history, setHistory] = useState<BusinessParameterHistoryView[]>([]);
  const [entities, setEntities] = useState<LegalEntityView[]>([]);
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<BusinessParameterOverrideView | 'new' | null>(null);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.business_parameter.read');

  const load = useCallback(async () => {
    setError('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「没有配置参数」，那是骗人。
      setReady(true);
      return;
    }
    try {
      const query = new URLSearchParams();
      if (entityId) query.set('legalEntityId', entityId);
      if (shopId.trim()) query.set('shopId', shopId.trim());
      const suffix = query.size > 0 ? `?${query.toString()}` : '';
      const [definitionList, effectiveList, overrideList, historyList, entityList] =
        await Promise.all([
          api<BusinessParameterDefinitionView[]>('identity/business-parameters'),
          api<BusinessParameterEffectiveView[]>(`identity/business-parameters/effective${suffix}`),
          api<BusinessParameterOverrideView[]>('identity/business-parameters/overrides'),
          api<BusinessParameterHistoryView[]>('identity/business-parameters/history'),
          api<LegalEntityView[]>('identity/legal-entities'),
        ]);
      setDefinitions(definitionList);
      setEffective(effectiveList);
      setOverrides(overrideList);
      setHistory(historyList);
      setEntities(entityList);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [readable, entityId, shopId]);

  useEffect(() => {
    void load();
  }, [load]);

  const definitionByCode = useMemo(() => {
    const map = new Map<string, ParameterDefinition>();
    for (const d of definitions)
      map.set(d.code, {
        code: d.code,
        name: d.name,
        category: d.category,
        valueType: d.valueType,
        defaultValue: d.defaultValue,
        allowedValueCodes: d.allowedValueCodes,
        validationPattern: d.validationPattern,
        unit: d.unit,
        description: d.description,
      });
    return map;
  }, [definitions]);

  const term = search.trim().toLowerCase();
  const visibleEffective = useMemo(
    () =>
      effective.filter((row) =>
        `${row.parameterCode} ${row.parameterName} ${row.value}`.toLowerCase().includes(term),
      ),
    [effective, term],
  );
  const visibleOverrides = useMemo(
    () =>
      overrides.filter((row) =>
        `${row.parameterCode} ${row.parameterName} ${row.value} ${row.shopLabel ?? ''}`
          .toLowerCase()
          .includes(term),
      ),
    [overrides, term],
  );

  async function remove(row: BusinessParameterOverrideView) {
    if (
      !window.confirm(
        `删除「${row.parameterName}」在${scopeLabel(row.scopeType, row)}的覆盖配置？删除后该档退回继承。`,
      )
    )
      return;
    setBusy(true);
    setError('');
    try {
      await api(`identity/business-parameters/overrides/${row.id}`, 'DELETE');
      setNotice('覆盖配置已删除，该档退回继承');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载业务参数…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有业务参数查看权限，请联系租户所有者开通。
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          参数按「店铺 &gt; 法人 &gt; 全租户 &gt; 出厂默认」解析生效值。改动只影响之后新建的单据，
          已冻结的单据仍按创建时的参数执行。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索参数"
              aria-label="搜索参数"
              className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-48 rounded-md border pl-8 pr-2 outline-none"
            />
          </label>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {can('identity.business_parameter.create') && (
            <Button size="sm" onClick={() => setEditing('new')}>
              <Plus className="h-4 w-4" />
              新增覆盖配置
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {/* ---------------- 生效范围上下文 ---------------- */}
      <section className="bg-card space-y-3 rounded-lg border p-4">
        <div>
          <p className="text-copy-14 font-medium">生效范围上下文</p>
          <p className="text-copy-12 text-muted-foreground mt-1">
            选择法人与店铺后，下方生效值按「店铺 &gt; 法人 &gt; 全租户 &gt; 出厂默认」解析。
          </p>
        </div>
        <div className="grid gap-3 md:grid-cols-3">
          <Field label="法人" hint="决定按法人档是否参与解析">
            <select
              value={entityId}
              onChange={(e) => setEntityId(e.target.value)}
              aria-label="选择法人"
              className={inputClass}
            >
              <option value="">不限定（按全租户档）</option>
              {entities.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.code} · {e.legalName}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺 ID" hint="店铺在渠道服务库中，此处填逻辑 ID">
            <input
              value={shopId}
              onChange={(e) => setShopId(e.target.value)}
              placeholder="店铺 UUID"
              className={inputClass}
            />
          </Field>
          <Field label="店铺名称" hint="作为历史可读标签一起冻结">
            <input
              value={shopLabel}
              onChange={(e) => setShopLabel(e.target.value)}
              placeholder="例如：美国站主店铺"
              className={inputClass}
            />
          </Field>
        </div>
        {shopId.trim() && !shopLabel.trim() && (
          <p className="text-copy-12 text-muted-foreground">
            已填写店铺 ID。查询生效值不需要店铺名称，但配置店铺档覆盖值与冻结单据时需要它。
          </p>
        )}
      </section>

      {/* ---------------- 生效值 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">当前生效值</h2>
          <p className="text-copy-12 text-muted-foreground">
            共 {visibleEffective.length} 项。标注「出厂默认」表示没有任何档覆盖它。
          </p>
        </div>
        {visibleEffective.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {definitions.length === 0
                ? '业务参数码表为空，请联系平台管理员'
                : '没有匹配当前搜索的参数'}
            </p>
          </div>
        ) : (
          groupByCategory(visibleEffective).map((group) => (
            <div key={group.category} className="space-y-2">
              <p className="text-copy-12 text-muted-foreground">{group.label}</p>
              <ul className="space-y-2">
                {group.rows.map((row) => {
                  const definition = definitionByCode.get(row.parameterCode);
                  return (
                    <li key={row.parameterCode} className="bg-card rounded-md border p-3">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div className="min-w-0">
                          <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                            {row.parameterName}
                            <Badge
                              tone={
                                row.sourceScope === 'DEFAULT'
                                  ? 'neutral'
                                  : row.sourceScope === 'SHOP'
                                    ? 'success'
                                    : 'info'
                              }
                            >
                              {sourceLabel(row)}
                            </Badge>
                          </p>
                          <p className="text-copy-12 text-muted-foreground mt-1">
                            <span className="font-mono">{row.parameterCode}</span>
                            {definition?.description ? ` · ${definition.description}` : ''}
                          </p>
                        </div>
                        <div className="text-right">
                          <p className="text-copy-14 font-medium">
                            {definition ? effectiveSummary(definition, row) : row.value}
                          </p>
                          {row.definitionDefault !== undefined && !row.inherited && definition && (
                            <p className="text-copy-12 text-muted-foreground">
                              出厂默认：{formatValue(definition, row.definitionDefault)}
                            </p>
                          )}
                          {row.effectiveFrom && (
                            <p className="text-copy-12 text-muted-foreground">
                              生效自 {row.effectiveFrom}
                            </p>
                          )}
                        </div>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))
        )}
      </section>

      {/* ---------------- 覆盖配置 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">覆盖配置</h2>
          <p className="text-copy-12 text-muted-foreground">
            删除一条覆盖值等于该档退回继承，不会留下空洞。
          </p>
        </div>
        {visibleOverrides.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {overrides.length === 0
                ? '还没有任何覆盖配置，当前所有参数都按出厂默认值生效'
                : '没有匹配当前搜索的覆盖配置'}
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {visibleOverrides.map((row) => (
              <li key={row.id} className="bg-card rounded-md border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      {row.parameterName}
                      <Badge tone="info">{scopeLabel(row.scopeType, row)}</Badge>
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      取值{' '}
                      {formatValue(
                        definitionByCode.get(row.parameterCode) ?? {
                          valueType: row.valueType,
                          unit: row.unit,
                          allowedValueCodes: [],
                        },
                        row.value,
                      )}{' '}
                      · 生效自 {row.effectiveFrom}
                      {row.note ? ` · ${row.note}` : ''}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    {can('identity.business_parameter.update') && (
                      <Button size="sm" variant="ghost" onClick={() => setEditing(row)}>
                        编辑
                      </Button>
                    )}
                    {can('identity.business_parameter.delete') && (
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void remove(row)}
                      >
                        删除
                      </Button>
                    )}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {editing && (
        <OverrideForm
          editing={editing === 'new' ? null : editing}
          definitions={definitions}
          entities={entities}
          busy={busy}
          onCancel={() => setEditing(null)}
          onSubmit={async (input) => {
            setBusy(true);
            setError('');
            try {
              if (editing === 'new') {
                await api('identity/business-parameters/overrides', 'POST', input);
                setNotice('覆盖配置已创建');
              } else {
                await api(`identity/business-parameters/overrides/${editing.id}`, 'PUT', input);
                setNotice('覆盖配置已更新');
              }
              setEditing(null);
              await load();
            } catch (e) {
              setError(e instanceof Error ? e.message : '保存失败，请重试');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {/* ---------------- 单据冻结 ---------------- */}
      <FreezePanel
        definitions={definitions}
        entities={entities}
        busy={busy}
        onError={(message) => setError(message)}
        onDone={(message) => {
          setNotice(message);
          void load();
        }}
      />

      {/* ---------------- 变更历史 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">变更历史</h2>
          <p className="text-copy-12 text-muted-foreground">
            只追加、不可修改，按时间倒序。最近 {history.length} 条。
          </p>
        </div>
        {history.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">还没有参数变更记录</p>
          </div>
        ) : (
          <ol className="space-y-2">
            {history.map((row) => (
              <li key={row.id} className="bg-card rounded-md border p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <p className="text-copy-13">{historySummary(row)}</p>
                  <div className="text-copy-12 text-muted-foreground text-right">
                    <p>
                      {SCOPE_TYPE_LABELS[row.scopeType] ?? row.scopeType}
                      {row.shopLabel ? ` · ${row.shopLabel}` : ''}
                      {row.legalEntityCode ? ` · ${row.legalEntityCode}` : ''}
                    </p>
                    <p>{row.changedAt}</p>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

// ------------------------------------------------------------------
// 覆盖配置表单
// ------------------------------------------------------------------

type OverrideInput = {
  parameterCode: string;
  scopeType: string;
  legalEntityId: string | null;
  shopId: string | null;
  shopLabel: string | null;
  value: string;
  note: string;
};

function OverrideForm({
  editing,
  definitions,
  entities,
  busy,
  onCancel,
  onSubmit,
}: {
  editing: BusinessParameterOverrideView | null;
  definitions: BusinessParameterDefinitionView[];
  entities: LegalEntityView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: OverrideInput) => Promise<void>;
}) {
  const [parameterCode, setParameterCode] = useState(
    editing?.parameterCode ?? definitions[0]?.code ?? '',
  );
  const [scopeType, setScopeType] = useState(editing?.scopeType ?? 'GLOBAL');
  const [legalEntityId, setLegalEntityId] = useState(editing?.legalEntityId ?? '');
  const [shopId, setShopId] = useState(editing?.shopId ?? '');
  const [shopLabel, setShopLabel] = useState(editing?.shopLabel ?? '');
  const [value, setValue] = useState(editing?.value ?? '');
  const [note, setNote] = useState(editing?.note ?? '');

  const definition =
    definitions.find((d) => d.code === parameterCode) ??
    ({
      code: parameterCode,
      name: parameterCode,
      category: '',
      valueType: 'STRING',
      defaultValue: '',
      defaultValueNumeric: null,
      allowedValueCodes: [],
      validationPattern: '',
      unit: '',
      description: '',
      status: 'ACTIVE',
    } satisfies BusinessParameterDefinitionView);

  const scopeProblem = validateScope(
    { scopeType, legalEntityId: legalEntityId || null, shopId: shopId.trim() || null },
    shopLabel,
  );
  const valueProblem = validateValue(definition, value);
  const valid = scopeProblem === '' && valueProblem === '';

  return (
    <div className="bg-card space-y-4 rounded-md border p-4">
      <p className="text-copy-14 font-medium">{editing ? '编辑覆盖配置' : '新增覆盖配置'}</p>
      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
        <Field label="参数" {...(definition.description ? { hint: definition.description } : {})}>
          <select
            value={parameterCode}
            onChange={(e) => {
              setParameterCode(e.target.value);
              setValue('');
            }}
            // 编辑时参数不可改：换参数等于换一条记录，历史会分不清是改值还是换参数
            disabled={!!editing}
            className={inputClass}
          >
            {definitions.map((d) => (
              <option key={d.code} value={d.code}>
                {CATEGORY_LABELS[d.category] ?? d.category} · {d.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="生效范围" hint="店铺 &gt; 法人 &gt; 全租户 &gt; 出厂默认">
          <select
            value={scopeType}
            onChange={(e) => setScopeType(e.target.value)}
            disabled={!!editing}
            className={inputClass}
          >
            <option value="GLOBAL">全租户</option>
            <option value="LEGAL_ENTITY">按法人</option>
            <option value="SHOP">按店铺</option>
          </select>
        </Field>
        <Field label="备注">
          <input value={note} onChange={(e) => setNote(e.target.value)} className={inputClass} />
        </Field>
        {scopeType === 'LEGAL_ENTITY' && (
          <Field label="法人">
            <select
              value={legalEntityId}
              onChange={(e) => setLegalEntityId(e.target.value)}
              className={inputClass}
            >
              <option value="">请选择法人</option>
              {entities.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.code} · {e.legalName}
                </option>
              ))}
            </select>
          </Field>
        )}
        {scopeType === 'SHOP' && (
          <>
            <Field label="店铺 ID" hint="渠道服务中的逻辑 ID">
              <input
                value={shopId}
                onChange={(e) => setShopId(e.target.value)}
                className={inputClass}
              />
            </Field>
            <Field label="店铺名称" hint="写入历史与冻结快照，店铺改名后仍可读">
              <input
                value={shopLabel}
                onChange={(e) => setShopLabel(e.target.value)}
                className={inputClass}
              />
            </Field>
          </>
        )}
        <Field label="取值" hint={`出厂默认：${formatValue(definition, definition.defaultValue)}`}>
          <ValueInput definition={definition} value={value} onChange={setValue} />
        </Field>
      </div>
      {(scopeProblem || valueProblem) && (
        <p role="alert" className="text-destructive text-sm">
          {scopeProblem || valueProblem}
        </p>
      )}
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !valid}
          onClick={() =>
            void onSubmit({
              parameterCode,
              scopeType,
              legalEntityId: scopeType === 'LEGAL_ENTITY' ? legalEntityId || null : null,
              shopId: scopeType === 'SHOP' ? shopId.trim() || null : null,
              shopLabel: scopeType === 'SHOP' ? shopLabel.trim() || null : null,
              value: value.trim(),
              note: note.trim(),
            })
          }
        >
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

/** 按值类型给合适的输入控件：枚举/多选用选择器，数值用数字，其余用文本。 */
function ValueInput({
  definition,
  value,
  onChange,
}: {
  definition: BusinessParameterDefinitionView;
  value: string;
  onChange: (value: string) => void;
}) {
  const [selected, setSelected] = useState(() => new Set(value ? value.split(',') : []));

  if (definition.valueType === 'ENUM')
    return (
      <select value={value} onChange={(e) => onChange(e.target.value)} className={inputClass}>
        <option value="">请选择</option>
        {definition.allowedValueCodes.map((code) => (
          <option key={code} value={code}>
            {code}
          </option>
        ))}
      </select>
    );

  if (definition.valueType === 'MULTI_ENUM')
    return (
      <div className="space-y-1">
        {definition.allowedValueCodes.map((code) => (
          <label key={code} className="text-copy-13 flex items-center gap-2">
            <input
              type="checkbox"
              checked={selected.has(code)}
              onChange={(e) => {
                const next = new Set(selected);
                if (e.target.checked) next.add(code);
                else next.delete(code);
                setSelected(next);
                onChange([...next].join(','));
              }}
            />
            {code}
          </label>
        ))}
      </div>
    );

  return (
    <input
      type={definition.valueType === 'INTEGER' ? 'number' : 'text'}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={inputClass}
    />
  );
}

// ------------------------------------------------------------------
// 单据冻结
// ------------------------------------------------------------------

function FreezePanel({
  definitions,
  entities,
  busy,
  onError,
  onDone,
}: {
  definitions: BusinessParameterDefinitionView[];
  entities: LegalEntityView[];
  busy: boolean;
  onError: (message: string) => void;
  onDone: (message: string) => void;
}) {
  const [documentType, setDocumentType] = useState('ORDER');
  const [documentNo, setDocumentNo] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [shopId, setShopId] = useState('');
  const [shopLabel, setShopLabel] = useState('');
  const [snapshot, setSnapshot] = useState<BusinessParameterFreezeView | null>(null);
  const [lookupError, setLookupError] = useState('');
  const [lookupNote, setLookupNote] = useState('');

  const definitionByCode = useMemo(() => {
    const map = new Map<string, BusinessParameterDefinitionView>();
    for (const d of definitions) map.set(d.code, d);
    return map;
  }, [definitions]);

  async function freeze() {
    onError('');
    try {
      await api('identity/business-parameters/freezes', 'POST', {
        documentType: documentType.trim().toUpperCase(),
        documentNo: documentNo.trim(),
        legalEntityId: legalEntityId || null,
        shopId: shopId.trim() || null,
        shopLabel: shopLabel.trim() || null,
      });
      onDone('参数已冻结，该单据之后不受参数改动影响');
      setSnapshot(null);
    } catch (e) {
      onError(e instanceof Error ? e.message : '冻结失败，请重试');
    }
  }

  async function lookup() {
    setLookupError('');
    setLookupNote('');
    setSnapshot(null);
    try {
      const found = await api<BusinessParameterFreezeView>(
        `identity/business-parameters/freezes/${encodeURIComponent(documentType.trim().toUpperCase())}/${encodeURIComponent(documentNo.trim())}`,
      );
      setSnapshot(found);
      setLookupNote(
        `已读取单据 ${found.documentType} ${found.documentNo} 的冻结参数（冻结于 ${found.frozenAt}）`,
      );
    } catch {
      // 没冻结过就是 404：说清楚"这张单据没有冻结快照"，而不是显示一个空列表。
      setLookupError(`单据 ${documentType} ${documentNo} 没有冻结的参数快照`);
    }
  }

  return (
    <section className="bg-card space-y-3 rounded-lg border p-4">
      <div>
        <p className="text-copy-14 flex items-center gap-2 font-medium">
          <Snowflake className="h-4 w-4" />
          单据参数冻结
        </p>
        <p className="text-copy-12 text-muted-foreground mt-1">
          单据创建时冻结一次当前生效值。之后改参数，这张单据读到的仍是冻结那一刻的值。
          一张单据只冻结一次，重复冻结会被拒绝。
        </p>
      </div>
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="单据类型" hint="大写标识符，如 ORDER / AFTERSALE">
          <input
            value={documentType}
            onChange={(e) => setDocumentType(e.target.value.toUpperCase())}
            className={inputClass}
          />
        </Field>
        <Field label="单据编号">
          <input
            value={documentNo}
            onChange={(e) => setDocumentNo(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="法人" hint="参与解析的上下文">
          <select
            value={legalEntityId}
            onChange={(e) => setLegalEntityId(e.target.value)}
            className={inputClass}
          >
            <option value="">不限定</option>
            {entities.map((e) => (
              <option key={e.id} value={e.id}>
                {e.code} · {e.legalName}
              </option>
            ))}
          </select>
        </Field>
        <Field label="店铺 ID" hint="可选">
          <input
            value={shopId}
            onChange={(e) => setShopId(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="店铺名称" hint="可选，随快照一起留存">
          <input
            value={shopLabel}
            onChange={(e) => setShopLabel(e.target.value)}
            className={inputClass}
          />
        </Field>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          disabled={busy || documentNo.trim() === '' || documentType.trim() === ''}
          onClick={() => void freeze()}
        >
          冻结该单据参数
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={documentNo.trim() === '' || documentType.trim() === ''}
          onClick={() => void lookup()}
        >
          读取冻结快照
        </Button>
      </div>
      {lookupError && (
        <p role="alert" className="text-destructive text-sm">
          {lookupError}
        </p>
      )}
      {lookupNote && (
        <p role="status" className="text-copy-12 text-muted-foreground">
          {lookupNote}
        </p>
      )}
      {snapshot && (
        <div className="space-y-2">
          <ul className="grid gap-2 sm:grid-cols-2">
            {snapshot.values.map((v) => {
              const definition = definitionByCode.get(v.parameterCode);
              return (
                <li key={v.parameterCode} className="rounded-md border p-2">
                  <p className="text-copy-13 flex flex-wrap items-center gap-2">
                    {v.parameterName}
                    <Badge tone="neutral">
                      {sourceLabel({
                        sourceScope: v.sourceScope,
                        inherited: v.sourceScope === 'DEFAULT',
                      })}
                    </Badge>
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    {definition ? formatValue(definition, v.value) : v.value}
                  </p>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </section>
  );
}

const inputClass =
  'border-border bg-background text-copy-13 focus-visible:border-ring h-9 rounded-md border px-2 outline-none';

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-copy-13 mb-1 block font-medium">{label}</span>
      {children}
      {hint && <span className="text-copy-12 text-muted-foreground mt-1 block">{hint}</span>}
    </label>
  );
}
