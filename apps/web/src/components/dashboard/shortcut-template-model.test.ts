import { describe, expect, it } from 'vitest';
import {
  ENTRY_KEY_PATTERN,
  OVERRIDE_ACTION_LABELS,
  SCOPE_TYPE_LABELS,
  TEMPLATE_CODE_PATTERN,
  canRemove,
  describeEffectiveSource,
  emptyTemplateDraft,
  groupTemplatesByScope,
  indexOverrides,
  summarizeEffectiveSet,
  toEntryDraft,
  toTemplateDraft,
  toTemplateInput,
  validateEntryDraft,
  validateTemplateDraft,
  type EntryDraft,
  type EffectiveShortcut,
  type EffectiveShortcutSet,
  type ShortcutEntry,
  type ShortcutOverride,
  type ShortcutTemplate,
} from './shortcut-template-model';

/**
 * 1.2.2.2 快捷入口配置 —— 前端模型测试。
 *
 * <p>模型层只做三件事：形状校验（与后端逐字一致）、表单 ↔ 入参转换、
 * 汇总与分组。**合并本身在服务端做** —— 前端没有「我命中哪些模板」的真值，
 * 所以这里不存在「前端也算一遍合并」的实现（那会造出第二个真相）。
 */

const entry = (overrides: Partial<ShortcutEntry> = {}): ShortcutEntry => ({
  key: 'trade.orders',
  label: '订单',
  pageCode: 'trade.orders',
  path: '/orders',
  icon: null,
  position: 10,
  ...overrides,
});

const template = (overrides: Partial<ShortcutTemplate> = {}): ShortcutTemplate => ({
  id: '11111111-1111-1111-1111-111111111111',
  code: 'ops-base',
  name: '运营岗位模板',
  description: '',
  scopeType: 'ROLE',
  roleId: '22222222-2222-2222-2222-222222222222',
  departmentId: null,
  status: 'ACTIVE',
  priority: 10,
  entries: [entry()],
  createdBy: '33333333-3333-3333-3333-333333333333',
  createdAt: '2026-10-23T00:00:00Z',
  updatedAt: '2026-10-23T00:00:00Z',
  ...overrides,
});

const draft = (overrides: Partial<EntryDraft> = {}): EntryDraft => ({
  key: 'trade.orders',
  label: '订单',
  pageCode: 'trade.orders',
  path: '/orders',
  icon: '',
  position: '10',
  ...overrides,
});

describe('1.2.2.2 快捷入口配置 —— 前端模型', () => {
  describe('受控标识格式', () => {
    it('入口标识只接受小写字母、数字与 . _ -', () => {
      expect(ENTRY_KEY_PATTERN.test('trade.orders')).toBe(true);
      expect(ENTRY_KEY_PATTERN.test('a')).toBe(true);
      expect(ENTRY_KEY_PATTERN.test('a1._-')).toBe(true);
      expect(ENTRY_KEY_PATTERN.test('Trade.orders')).toBe(false);
      expect(ENTRY_KEY_PATTERN.test('.leading')).toBe(false);
      expect(ENTRY_KEY_PATTERN.test('')).toBe(false);
      expect(ENTRY_KEY_PATTERN.test('a'.repeat(101))).toBe(false);
    });

    it('模板编码允许大小写但不允许其它符号', () => {
      expect(TEMPLATE_CODE_PATTERN.test('ops-base')).toBe(true);
      expect(TEMPLATE_CODE_PATTERN.test('OPS_BASE')).toBe(true);
      expect(TEMPLATE_CODE_PATTERN.test('ops base')).toBe(false);
      expect(TEMPLATE_CODE_PATTERN.test('-ops')).toBe(false);
    });
  });

  describe('入口草稿校验（与后端逐字一致）', () => {
    it('合法草稿没有错误', () => {
      expect(validateEntryDraft(draft(), new Set())).toEqual({});
    });

    it('入口标识重复会被拒', () => {
      const seen = new Set(['trade.orders']);
      expect(validateEntryDraft(draft(), seen).key).toContain('不能重复');
    });

    it('入口标识含大写会被拒', () => {
      expect(validateEntryDraft(draft({ key: 'Trade.Orders' }), new Set()).key).toBeTruthy();
    });

    it('标题与目标页面码必填', () => {
      const errors = validateEntryDraft(draft({ label: '  ', pageCode: '' }), new Set());
      expect(errors.label).toBeTruthy();
      expect(errors.pageCode).toBeTruthy();
    });

    it('跳转路径必须是站内相对路径', () => {
      expect(validateEntryDraft(draft({ path: '' }), new Set()).path).toBeTruthy();
      expect(validateEntryDraft(draft({ path: 'orders' }), new Set()).path).toBeTruthy();
      expect(validateEntryDraft(draft({ path: '/a/../b' }), new Set()).path).toBeTruthy();
      expect(validateEntryDraft(draft({ path: '/orders' }), new Set()).path).toBeUndefined();
    });
  });

  describe('模板草稿校验', () => {
    it('新建时模板编码必填且要合法', () => {
      const errors = validateTemplateDraft(emptyTemplateDraft(), true);
      expect(errors.code).toBeTruthy();
      expect(errors.name).toBeTruthy();
    });

    it('编辑时不再要求模板编码', () => {
      const draftTemplate = { ...emptyTemplateDraft(), name: '有名字' };
      expect(validateTemplateDraft(draftTemplate, false).code).toBeUndefined();
    });

    it('岗位模板必须指定岗位、团队模板必须指定部门', () => {
      const roleErrors = validateTemplateDraft(
        { ...emptyTemplateDraft(), name: 'x', scopeType: 'ROLE' },
        true,
      );
      expect(roleErrors.roleId).toBeTruthy();
      const deptErrors = validateTemplateDraft(
        { ...emptyTemplateDraft(), name: 'x', scopeType: 'DEPARTMENT' },
        true,
      );
      expect(deptErrors.departmentId).toBeTruthy();
    });

    it('优先级必须是 0 到 9999 的整数', () => {
      const draftTemplate = { ...emptyTemplateDraft(), name: 'x', priority: '-1' };
      expect(validateTemplateDraft(draftTemplate, true).priority).toBeTruthy();
      const ok = { ...emptyTemplateDraft(), name: 'x', priority: '10' };
      expect(validateTemplateDraft(ok, true).priority).toBeUndefined();
    });

    it('入口校验的错误会带上下标定位到具体那条', () => {
      const draftTemplate = {
        ...emptyTemplateDraft(),
        name: 'x',
        entries: [draft(), draft({ key: 'trade.orders' })],
      };
      const errors = validateTemplateDraft(draftTemplate, true);
      expect(errors['entries.1.key']).toContain('不能重复');
    });
  });

  describe('表单 ↔ 入参转换', () => {
    it('空字符串的图标与排序位转成 null', () => {
      const input = toTemplateInput({
        ...emptyTemplateDraft(),
        code: 'ops',
        name: '运营',
        entries: [draft({ icon: '  ', position: '' })],
      });
      expect(input.entries[0]?.icon).toBeNull();
      expect(input.entries[0]?.position).toBeNull();
      expect(input.entries[0]?.key).toBe('trade.orders');
    });

    it('适用范围决定带岗位还是带部门', () => {
      const roleInput = toTemplateInput({
        ...emptyTemplateDraft(),
        code: 'ops',
        name: '运营',
        scopeType: 'ROLE',
        roleId: '22222222-2222-2222-2222-222222222222',
        departmentId: '33333333-3333-3333-3333-333333333333',
      });
      expect(roleInput.roleId).toBe('22222222-2222-2222-2222-222222222222');
      expect(roleInput.departmentId).toBeNull();

      const deptInput = toTemplateInput({
        ...emptyTemplateDraft(),
        code: 'ops',
        name: '运营',
        scopeType: 'DEPARTMENT',
        roleId: '22222222-2222-2222-2222-222222222222',
        departmentId: '33333333-3333-3333-3333-333333333333',
      });
      expect(deptInput.roleId).toBeNull();
      expect(deptInput.departmentId).toBe('33333333-3333-3333-3333-333333333333');
    });

    it('模板 ↔ 草稿往返不丢字段', () => {
      const original = template({ entries: [entry({ icon: 'Truck', position: 20 })] });
      const restored = toTemplateDraft(original);
      expect(restored.code).toBe('ops-base');
      expect(restored.scopeType).toBe('ROLE');
      expect(restored.entries[0]?.icon).toBe('Truck');
      expect(restored.entries[0]?.position).toBe('20');
      expect(toEntryDraft(original.entries[0]!).key).toBe('trade.orders');
    });
  });

  describe('汇总与分组', () => {
    const effective = (overrides: Partial<EffectiveShortcutSet> = {}): EffectiveShortcutSet => ({
      entries: [
        {
          key: 'a',
          label: 'A',
          pageCode: 'trade.orders',
          path: '/a',
          icon: null,
          position: 10,
          source: 'TEMPLATE',
          sourceTemplateCode: 'ops',
          overridden: false,
        },
        {
          key: 'b',
          label: 'B',
          pageCode: 'catalog.sku',
          path: '/b',
          icon: null,
          position: 20,
          source: 'TEMPLATE',
          sourceTemplateCode: 'ops',
          overridden: true,
        },
      ],
      templateCount: 2,
      overrideCount: 1,
      removedByOverride: 1,
      hiddenByPermission: 3,
      mergeRules: [],
      ...overrides,
    });

    it('汇总数出生效 / 改过 / 移除 / 隐藏', () => {
      const summary = summarizeEffectiveSet(effective());
      expect(summary.total).toBe(2);
      expect(summary.overridden).toBe(1);
      expect(summary.removedByOverride).toBe(1);
      expect(summary.hiddenByPermission).toBe(3);
      expect(summary.templateCount).toBe(2);
    });

    it('没有数据时汇总全是 0 而不是空对象报错', () => {
      const summary = summarizeEffectiveSet(null);
      expect(summary.total).toBe(0);
      expect(summary.templateCount).toBe(0);
    });

    it('按适用范围分组并保持全员/岗位/团队的固定顺序', () => {
      const groups = groupTemplatesByScope([
        template({ id: '1', scopeType: 'DEPARTMENT' }),
        template({ id: '2', scopeType: 'TENANT' }),
        template({ id: '3', scopeType: 'ROLE' }),
      ]);
      expect(groups.map((group) => group.scopeType)).toEqual(['TENANT', 'ROLE', 'DEPARTMENT']);
      expect(groups.every((group) => group.templates.length === 1)).toBe(true);
    });

    it('没有模板时分组是空数组', () => {
      expect(groupTemplatesByScope([])).toEqual([]);
    });
  });

  describe('覆盖项', () => {
    it('按 targetKey 建索引，同一个 key 只有一条', () => {
      const overrides: ShortcutOverride[] = [
        {
          id: '1',
          targetKey: 'a',
          action: 'UPSERT',
          label: 'X',
          pageCode: null,
          path: null,
          icon: null,
          position: null,
          createdAt: '2026-10-23T00:00:00Z',
          updatedAt: '2026-10-23T00:00:00Z',
        },
        {
          id: '2',
          targetKey: 'b',
          action: 'REMOVE',
          label: null,
          pageCode: null,
          path: null,
          icon: null,
          position: null,
          createdAt: '2026-10-23T00:00:00Z',
          updatedAt: '2026-10-23T00:00:00Z',
        },
      ];
      const index = indexOverrides(overrides);
      expect(index.size).toBe(2);
      expect(index.get('a')?.action).toBe('UPSERT');
      expect(index.get('b')?.action).toBe('REMOVE');
    });

    it('动作标签覆盖 UPSERT 与 REMOVE 两态', () => {
      expect(OVERRIDE_ACTION_LABELS.UPSERT).toBe('覆盖');
      expect(OVERRIDE_ACTION_LABELS.REMOVE).toBe('移除');
    });
  });

  describe('来源说明', () => {
    it('被个人改过的入口要写明', () => {
      const base: EffectiveShortcut = {
        key: 'a',
        label: 'A',
        pageCode: 'trade.orders',
        path: '/a',
        icon: null,
        position: 10,
        source: 'TEMPLATE',
        sourceTemplateCode: 'ops',
        overridden: false,
      };
      expect(describeEffectiveSource(base)).toBe('模板 ops');
      expect(describeEffectiveSource({ ...base, overridden: true })).toBe('模板 ops（已被个人覆盖）');
      expect(describeEffectiveSource({ ...base, sourceTemplateCode: null })).toBe('模板');
    });

    it('入口来源是模板时可以移除', () => {
      expect(
        canRemove({
          key: 'a',
          label: 'A',
          pageCode: 'trade.orders',
          path: '/a',
          icon: null,
          position: 10,
          source: 'TEMPLATE',
          sourceTemplateCode: 'ops',
          overridden: false,
        }),
      ).toBe(true);
    });
  });

  describe('适用范围标签', () => {
    it('三种适用范围都有中文标签', () => {
      expect(SCOPE_TYPE_LABELS.TENANT).toBe('全员');
      expect(SCOPE_TYPE_LABELS.ROLE).toBe('岗位');
      expect(SCOPE_TYPE_LABELS.DEPARTMENT).toBe('团队');
    });
  });
});
