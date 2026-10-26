/**
 * 1.2.2.2 快捷入口配置 —— 前端模型（卷 01 工作台与门户）。
 *
 * <p>蓝图原文：「**职责**：按角色/团队预置快捷入口模板，新成员加入时自动获得岗位常用入口，
 * 减少培训成本。**输入**：17 系统配置的岗位模板；用户所属部门/角色；用户个人覆盖项。
 * **输出**：最终生效的快捷入口集合（模板 + 个人覆盖的合并结果）。」
 *
 * <p><b>合并规则在后端做，前端不重算一遍</b>：模板命中、跨模板去重、个人覆盖、权限闸
 * 全部在 identity 服务里算完（那里才有「我持有哪些权限」「我命中哪些模板」的真值）。
 * 前端拿到的是**最终生效集合**，只负责渲染与「个人覆盖」的操作。
 *
 * <p><b>但前端仍然要有一份形状校验</b>：不是为了替代后端校验（后端才是权威），
 * 而是为了让用户在提交前就知道「入口标识只能小写」这件事 —— 否则只能等 400 才看到错误。
 * 两边的规则**逐字一致**，改一处必须改另一处。
 *
 * <p><b>1.2.2.1 常用页面收藏不在这里</b>：本模块只负责「岗位模板 → 个人覆盖 → 生效集合」，
 * 收藏列表的渲染与拖拽排序留给 1.2.2.1。覆盖项只能改模板给的入口，不能凭空新增。
 */

import type {
  EffectiveShortcut,
  EffectiveShortcutSet,
  ShortcutCapabilities,
  ShortcutEntry,
  ShortcutOverride,
  ShortcutOverrideInput,
  ShortcutTemplate,
  ShortcutTemplateInput,
  ShortcutTemplateList,
} from '../../../../../packages/api-contracts/src/index';

export type {
  EffectiveShortcut,
  EffectiveShortcutSet,
  ShortcutCapabilities,
  ShortcutEntry,
  ShortcutOverride,
  ShortcutOverrideInput,
  ShortcutTemplate,
  ShortcutTemplateInput,
  ShortcutTemplateList,
};

/** 模板适用范围的三种取值。 */
export const SCOPE_TYPES = ['TENANT', 'ROLE', 'DEPARTMENT'] as const;
export type ScopeType = (typeof SCOPE_TYPES)[number];

/** 适用范围的中文标签。 */
export const SCOPE_TYPE_LABELS: Record<ScopeType, string> = {
  TENANT: '全员',
  ROLE: '岗位',
  DEPARTMENT: '团队',
};

/** 覆盖动作的两种取值。 */
export const OVERRIDE_ACTIONS = ['UPSERT', 'REMOVE'] as const;
export type OverrideAction = (typeof OVERRIDE_ACTIONS)[number];

/** 覆盖动作的中文标签。 */
export const OVERRIDE_ACTION_LABELS: Record<OverrideAction, string> = {
  UPSERT: '覆盖',
  REMOVE: '移除',
};

/** 模板状态。 */
export const TEMPLATE_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  INACTIVE: '已停用',
};

/**
 * 入口标识的格式：与后端 {@code shortcut_entry_shape_ok} 的正则**逐字一致**。
 *
 * <p>入口标识是「模板项」与「个人覆盖项」的合并单元，必须能当稳定的键用，
 * 所以限定成受控标识符（小写开头，可含 {@code . _ -}），而不是自由文本。
 */
export const ENTRY_KEY_PATTERN = /^[a-z0-9][a-z0-9._-]{0,99}$/;

/** 模板编码的格式：与后端 {@code shortcut_entry_template_code_chk} 逐字一致。 */
export const TEMPLATE_CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

/** 一条入口在表单里的形状（position 是字符串，便于直接进输入框）。 */
export interface EntryDraft {
  key: string;
  label: string;
  pageCode: string;
  path: string;
  icon: string;
  position: string;
}

/** 一张模板在表单里的形状。 */
export interface TemplateDraft {
  id: string;
  code: string;
  name: string;
  description: string;
  scopeType: ScopeType;
  roleId: string;
  departmentId: string;
  status: 'ACTIVE' | 'INACTIVE';
  priority: string;
  entries: EntryDraft[];
}

/** 表单校验的结果：字段名 → 错误文案（空对象表示通过）。 */
export type DraftErrors = Record<string, string>;

/** 把一条入口转成表单草稿。 */
export function toEntryDraft(entry: ShortcutEntry): EntryDraft {
  return {
    key: entry.key ?? '',
    label: entry.label ?? '',
    pageCode: entry.pageCode ?? '',
    path: entry.path ?? '',
    icon: entry.icon ?? '',
    position: entry.position === null || entry.position === undefined ? '' : String(entry.position),
  };
}

/** 把一张模板转成表单草稿。 */
export function toTemplateDraft(template: ShortcutTemplate): TemplateDraft {
  return {
    id: template.id,
    code: template.code ?? '',
    name: template.name ?? '',
    description: template.description ?? '',
    scopeType: (template.scopeType ?? 'TENANT') as ScopeType,
    roleId: template.roleId ?? '',
    departmentId: template.departmentId ?? '',
    status: (template.status ?? 'ACTIVE') === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    priority: String(template.priority ?? 100),
    entries: (template.entries ?? []).map(toEntryDraft),
  };
}

/** 造一张空草稿。 */
export function emptyTemplateDraft(): TemplateDraft {
  return {
    id: '',
    code: '',
    name: '',
    description: '',
    scopeType: 'TENANT',
    roleId: '',
    departmentId: '',
    status: 'ACTIVE',
    priority: '100',
    entries: [],
  };
}

/**
 * 校验一条入口草稿。
 *
 * <p>规则与后端一致：入口标识受控、标题非空、目标页面码必填、跳转路径必须是站内相对路径。
 * 后端仍然会再校验一遍（权威），这里只是让用户当场看到问题。
 */
export function validateEntryDraft(draft: EntryDraft, seen: Set<string>): DraftErrors {
  const errors: DraftErrors = {};
  const key = draft.key.trim();
  if (!key) {
    errors.key = '入口标识不能为空';
  } else if (!ENTRY_KEY_PATTERN.test(key)) {
    errors.key = '入口标识只能是小写字母、数字与 . _ -，且必须以字母或数字开头';
  } else if (seen.has(key)) {
    errors.key = '同一张模板里的入口标识不能重复';
  }

  if (!draft.label.trim()) errors.label = '入口标题不能为空';
  if (!draft.pageCode.trim()) errors.pageCode = '入口目标页面码不能为空';

  const path = draft.path.trim();
  if (!path) {
    errors.path = '跳转路径不能为空';
  } else if (!path.startsWith('/')) {
    errors.path = '跳转路径必须以 / 开头';
  } else if (path.includes('..')) {
    errors.path = '跳转路径不能包含 ..';
  }
  return errors;
}

/** 校验一张模板草稿。 */
export function validateTemplateDraft(draft: TemplateDraft, creating: boolean): DraftErrors {
  const errors: DraftErrors = {};
  if (creating) {
    const code = draft.code.trim();
    if (!code) errors.code = '模板编码不能为空';
    else if (!TEMPLATE_CODE_PATTERN.test(code)) errors.code = '模板编码只能包含字母、数字与 . _ -';
  }
  if (!draft.name.trim()) errors.name = '模板名称不能为空';

  if (draft.scopeType === 'ROLE' && !draft.roleId.trim()) {
    errors.roleId = '岗位模板必须指定岗位';
  }
  if (draft.scopeType === 'DEPARTMENT' && !draft.departmentId.trim()) {
    errors.departmentId = '团队模板必须指定部门';
  }

  const priority = Number(draft.priority);
  if (!Number.isInteger(priority) || priority < 0 || priority > 9999) {
    errors.priority = '优先级必须是 0 到 9999 之间的整数';
  }

  const seen = new Set<string>();
  draft.entries.forEach((entry, index) => {
    const entryErrors = validateEntryDraft(entry, seen);
    seen.add(entry.key.trim());
    for (const [field, message] of Object.entries(entryErrors)) {
      errors[`entries.${index}.${field}`] = message;
    }
  });
  return errors;
}

/** 把模板草稿转成后端入参（草稿里的字符串要还原成数字与 null）。 */
export function toTemplateInput(draft: TemplateDraft): ShortcutTemplateInput {
  const priority = Number(draft.priority);
  const entries = draft.entries.map((entry) => {
    const position = entry.position.trim() === '' ? null : Number(entry.position);
    return {
      key: entry.key.trim(),
      label: entry.label.trim(),
      pageCode: entry.pageCode.trim(),
      path: entry.path.trim(),
      icon: entry.icon.trim() === '' ? null : entry.icon.trim(),
      position: position === null || Number.isNaN(position) ? null : position,
    };
  });
  return {
    code: draft.code.trim(),
    name: draft.name.trim(),
    description: draft.description.trim(),
    scopeType: draft.scopeType,
    roleId: draft.scopeType === 'ROLE' ? draft.roleId.trim() : null,
    departmentId: draft.scopeType === 'DEPARTMENT' ? draft.departmentId.trim() : null,
    status: draft.status,
    priority: Number.isInteger(priority) ? priority : 100,
    entries,
  };
}

/** 一条入口的来源说明：来自哪张模板、是否被个人改过。 */
export function describeEffectiveSource(entry: EffectiveShortcut): string {
  const template = entry.sourceTemplateCode ? `模板 ${entry.sourceTemplateCode}` : '模板';
  return entry.overridden ? `${template}（已被个人覆盖）` : template;
}

/** 生效集合的汇总。 */
export interface EffectiveSummary {
  total: number;
  overridden: number;
  removedByOverride: number;
  hiddenByPermission: number;
  templateCount: number;
}

/** 汇总生效集合。 */
export function summarizeEffectiveSet(set: EffectiveShortcutSet | null): EffectiveSummary {
  const entries = set?.entries ?? [];
  return {
    total: entries.length,
    overridden: entries.filter((entry) => entry.overridden).length,
    removedByOverride: set?.removedByOverride ?? 0,
    hiddenByPermission: set?.hiddenByPermission ?? 0,
    templateCount: set?.templateCount ?? 0,
  };
}

/** 按适用范围把模板分组（保持「全员 / 岗位 / 团队」的固定顺序）。 */
export function groupTemplatesByScope(
  templates: ShortcutTemplate[],
): { scopeType: ScopeType; label: string; templates: ShortcutTemplate[] }[] {
  return SCOPE_TYPES.map((scopeType) => ({
    scopeType,
    label: SCOPE_TYPE_LABELS[scopeType],
    templates: templates.filter((template) => template.scopeType === scopeType),
  })).filter((group) => group.templates.length > 0);
}

/**
 * 一个入口能不能被「移除」。
 *
 * <p>已经被移除的入口根本不在生效集合里，所以界面上不需要提供「再移除一次」——
 * 「恢复默认」走的是**删掉覆盖项**，那才是把入口放回模板原样的动作。
 */
export function canRemove(entry: EffectiveShortcut): boolean {
  return entry.source === 'TEMPLATE';
}

/** 覆盖项按 targetKey 索引。 */
export function indexOverrides(overrides: ShortcutOverride[]): Map<string, ShortcutOverride> {
  const map = new Map<string, ShortcutOverride>();
  for (const override of overrides) map.set(override.targetKey, override);
  return map;
}
