/**
 * 17.5.1.1 业务参数的展示口径与前端预校验。
 *
 * <p>后端 {@code BusinessParameterController} 是最终判定（校验规则读自
 * {@code business_parameter_definition} 码表），这里的校验与后端同口径同顺序，
 * 目的是让用户在提交前就看到"为什么不行"，而不是提交后吃一个 400。
 * 后端仍然是权威：即使前端校验放过，后端也会拒。
 *
 * <p>生效范围优先级 **店铺 > 法人 > 全局 > 出厂默认**，与后端 `RESOLVE_SQL` 里的
 * `order by case scope_type ...` 一致。两处必须同时改，否则界面上标着"店铺生效"的值
 * 与后端真正解析出来的值不是一回事。
 */

export type ValueType = 'ENUM' | 'MULTI_ENUM' | 'INTEGER' | 'STRING';
export type ScopeType = 'GLOBAL' | 'LEGAL_ENTITY' | 'SHOP';

/** 生效范围优先级，数字越小越优先。 */
export const SCOPE_PRIORITY: Record<string, number> = {
  SHOP: 1,
  LEGAL_ENTITY: 2,
  GLOBAL: 3,
  DEFAULT: 4,
};

export const CATEGORY_LABELS: Record<string, string> = {
  ORDER_REVIEW: '审单',
  INVENTORY: '库存',
  AFTERSALE: '售后',
  COST: '成本',
  NUMBERING: '编号',
  DISPLAY: '展示',
};

export const VALUE_TYPE_LABELS: Record<string, string> = {
  ENUM: '单选',
  MULTI_ENUM: '多选',
  INTEGER: '数值',
  STRING: '文本',
};

export const SCOPE_TYPE_LABELS: Record<string, string> = {
  GLOBAL: '全租户',
  LEGAL_ENTITY: '按法人',
  SHOP: '按店铺',
};

export const SOURCE_SCOPE_LABELS: Record<string, string> = {
  DEFAULT: '出厂默认',
  GLOBAL: '全租户配置',
  LEGAL_ENTITY: '法人配置',
  SHOP: '店铺配置',
};

export const ACTION_LABELS: Record<string, string> = {
  CREATED: '新增',
  UPDATED: '修改',
  DELETED: '删除（退回继承）',
};

/** 参数定义的最小形状：前端只需要校验与展示用到的这些字段。 */
export type ParameterDefinition = {
  code: string;
  name: string;
  category: string;
  valueType: string;
  defaultValue: string;
  allowedValueCodes: string[];
  validationPattern: string;
  unit: string;
  description: string;
};

/** 生效值里"这个值从哪一档来"的最小形状。 */
export type EffectiveRow = {
  parameterCode: string;
  value: string;
  valueCodes: string[];
  sourceScope: string;
  inherited: boolean;
};

/** 把逗号分隔的取值拆成数组；空串返回空数组。 */
export function splitCodes(value: string | null | undefined): string[] {
  if (!value) return [];
  return value
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

/** 生效值的展示文本：多选拼成可读列表，数值带上单位，其余原样。 */
export function formatValue(
  definition: Pick<ParameterDefinition, 'valueType' | 'unit' | 'allowedValueCodes'>,
  value: string | null | undefined,
): string {
  if (value === null || value === undefined) return '—';
  if (definition.valueType === 'MULTI_ENUM') {
    const selected = splitCodes(value);
    return selected.length === 0 ? '未设置' : selected.join('、');
  }
  if (value === '') return definition.valueType === 'MULTI_ENUM' ? '未设置' : '—';
  return definition.unit ? `${value} ${definition.unit}` : value;
}

/**
 * 来源标签：继承来的值必须显式标注「出厂默认」，否则用户会以为它是有人配过的。
 */
export function sourceLabel(row: Pick<EffectiveRow, 'sourceScope' | 'inherited'>): string {
  return SOURCE_SCOPE_LABELS[row.sourceScope] ?? row.sourceScope;
}

/** 覆盖档的可读范围描述。 */
export function scopeLabel(
  scopeType: string,
  target: {
    legalEntityCode?: string | null;
    legalEntityName?: string | null;
    shopLabel?: string | null;
  },
): string {
  if (scopeType === 'LEGAL_ENTITY')
    return target.legalEntityCode
      ? `法人 · ${target.legalEntityCode}`
      : `法人 · ${target.legalEntityName ?? '未知'}`;
  if (scopeType === 'SHOP') return `店铺 · ${target.shopLabel ?? '未命名'}`;
  return SCOPE_TYPE_LABELS[scopeType] ?? scopeType;
}

/**
 * 判断「参数 × 生效范围」是否已配置。
 *
 * <p>配置页要能在点保存之前就告诉用户"这一档已经配过了，请直接编辑"，
 * 而不是等后端用 409 挡下。返回已存在的那一条（找不到返回 null）。
 */
export function findExistingOverride<
  T extends {
    parameterCode: string;
    scopeType: string;
    legalEntityId?: string | null;
    shopId?: string | null;
  },
>(overrides: readonly T[], parameterCode: string, scope: ScopeTarget): T | null {
  for (const row of overrides) {
    if (row.parameterCode !== parameterCode) continue;
    if (row.scopeType !== scope.scopeType) continue;
    // 三档必须逐一比对目标：法人档比 legalEntityId，店铺档比 shopId，
    // 全局档两个 id 都是空。只比 scopeType 会把不同法人的两条配置混成一条。
    if (scope.scopeType === 'LEGAL_ENTITY' && row.legalEntityId === scope.legalEntityId) return row;
    if (scope.scopeType === 'SHOP' && row.shopId === scope.shopId) return row;
    if (scope.scopeType === 'GLOBAL') return row;
  }
  return null;
}

export type ScopeTarget = {
  scopeType: string;
  legalEntityId: string | null;
  shopId: string | null;
};

/**
 * 取值预校验，与后端 `BusinessParameterController.validated` 同口径同顺序：
 * ENUM 查合法取值、MULTI_ENUM 逐项查且查格式、INTEGER 必须能解析成非负数值、
 * STRING 不能为空并匹配正则。返回可读原因，通过校验时返回空串。
 */
export function validateValue(definition: ParameterDefinition, raw: string): string {
  const value = raw.trim();
  const allowed = definition.allowedValueCodes ?? [];
  switch (definition.valueType) {
    case 'ENUM': {
      if (!allowed.includes(value)) return `取值必须是 ${allowed.join(' / ')} 之一`;
      return '';
    }
    case 'MULTI_ENUM': {
      // 空串合法：「当前不设必退情形」是一个配置，不是漏填。
      if (value === '') return '';
      for (const item of splitCodes(value))
        if (!allowed.includes(item)) return `「${item}」不在合法范围内`;
      return patternProblem(definition, value);
    }
    case 'INTEGER': {
      if (value === '') return '需要一个数值';
      // 先按"是不是一个数"判断（允许前导负号），再单独报负数——
      // 否则 -5 会先撞上数字格式检查，报成"需要一个数值"，
      // 与后端 BigDecimal 解析后按 signum 报"不允许为负数"的口径不一致。
      if (!/^-?\d+(\.\d+)?$/.test(value)) return '需要一个数值';
      if (Number(value) < 0) return '不允许为负数';
      return patternProblem(definition, value);
    }
    default: {
      if (value === '') return '不能为空';
      return patternProblem(definition, value);
    }
  }
}

function patternProblem(definition: ParameterDefinition, value: string): string {
  if (!definition.validationPattern) return '';
  try {
    return new RegExp(definition.validationPattern).test(value)
      ? ''
      : `格式需满足 ${definition.validationPattern}`;
  } catch {
    // 码表里的正则坏了不该让界面崩掉：放行，让后端去报配置问题。
    return '';
  }
}

/**
 * 生效范围与目标对象是否搭配得上（与后端 `scope()` 与库里的 CHECK 同口径）。
 *
 * <p>最常见的一类错误是"选了店铺档但没填店铺名称"——店铺在 channel 服务，
 * identity 查不到可读名，标签必须由调用方带入。
 */
export function validateScope(scope: ScopeTarget, shopLabel: string): string {
  if (scope.scopeType === 'GLOBAL') {
    if (scope.legalEntityId || scope.shopId) return '全租户档不能指定法人或店铺';
    return '';
  }
  if (scope.scopeType === 'LEGAL_ENTITY') {
    if (!scope.legalEntityId) return '按法人档必须指定法人';
    if (scope.shopId) return '按法人档不能同时指定店铺';
    return '';
  }
  if (!scope.shopId) return '按店铺档必须指定店铺';
  if (!shopLabel.trim()) return '按店铺档必须填写店铺名称，它会作为历史可读标签一起冻结';
  return '';
}

/**
 * 生效值摘要：把「值 + 来源」合成一句。
 *
 * <p>来源一定要带出来：同一个值可能来自出厂默认也可能来自某次配置，
 * 只显示值的话用户无法判断该不该改这里。
 */
export function effectiveSummary(definition: ParameterDefinition, row: EffectiveRow): string {
  return `${formatValue(definition, row.value)}（${sourceLabel(row)}）`;
}

/** 变更历史摘要：谁在什么时候把哪一档从什么改成什么。 */
export function historySummary(row: {
  parameterName: string;
  action: string;
  beforeValue: string | null;
  afterValue: string | null;
  actorName: string | null;
}): string {
  const from = row.beforeValue === null || row.beforeValue === '' ? '（无）' : row.beforeValue;
  const to = row.afterValue === null || row.afterValue === '' ? '（无）' : row.afterValue;
  return `${row.parameterName} ${ACTION_LABELS[row.action] ?? row.action}：${from} → ${to}${
    row.actorName ? ` · ${row.actorName}` : ''
  }`;
}

/** 按分类分组，供配置页分区渲染。分类顺序按码表 category 固定，不随返回顺序漂移。 */
export function groupByCategory<T extends { category: string }>(
  rows: readonly T[],
  order: readonly string[] = Object.keys(CATEGORY_LABELS),
): { category: string; label: string; rows: T[] }[] {
  return order
    .map((category) => ({
      category,
      label: CATEGORY_LABELS[category] ?? category,
      rows: rows.filter((r) => r.category === category),
    }))
    .filter((g) => g.rows.length > 0);
}
