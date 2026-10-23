'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { ExternalLink, Pencil, RefreshCw, RotateCcw, Trash2, TriangleAlert } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import {
  OVERRIDE_ACTION_LABELS,
  SCOPE_TYPE_LABELS,
  TEMPLATE_STATUS_LABELS,
  describeEffectiveSource,
  emptyTemplateDraft,
  groupTemplatesByScope,
  indexOverrides,
  summarizeEffectiveSet,
  toTemplateDraft,
  toTemplateInput,
  validateTemplateDraft,
  type DraftErrors,
  type EffectiveShortcut,
  type EffectiveShortcutSet,
  type OverrideAction,
  type ScopeType,
  type ShortcutCapabilities,
  type ShortcutOverride,
  type ShortcutTemplate,
  type ShortcutTemplateList,
  type TemplateDraft,
} from './shortcut-template-model';

/**
 * 1.2.2.2 快捷入口配置 —— 工作区（`/dashboard/shortcuts`）。
 *
 * <p>蓝图原文：「**职责**：按角色/团队预置快捷入口模板，新成员加入时自动获得岗位常用入口，
 * 减少培训成本。**输入**：17 系统配置的岗位模板；用户所属部门/角色；用户个人覆盖项。
 * **输出**：最终生效的快捷入口集合（模板 + 个人覆盖的合并结果）。」
 *
 * <p><b>页面的两个面</b>：
 *
 * <ul>
 *   <li><b>我的快捷入口</b>（个人面）：后端算好的**最终生效集合**。这里只渲染与操作，
 *       不在前端重算合并 —— 前端没有「我命中哪些模板」「我持有哪些权限」的真值。</li>
 *   <li><b>岗位模板</b>（组织面）：租户级配置，只有持 {@code identity.shortcut.manage} 的人能改。</li>
 * </ul>
 *
 * <p><b>「没权限的入口」与「没有入口」必须分开</b>：模板里有指向无权访问页面的入口时，
 * 后端把它从生效集合里剔掉并单独报数（{@code hiddenByPermission}）——
 * 界面上要写清「有 N 个入口因为你没有权限而隐藏」，而不是让人以为模板没配。
 *
 * <p><b>1.2.2.1 常用页面收藏不在这里</b>：本页面只负责岗位模板、个人覆盖与生效集合。
 * 覆盖项**只能改模板给的入口**，不能凭空新增 —— 新增是收藏的职责。
 */
export function ShortcutTemplateWorkspace() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [capabilities, setCapabilities] = useState<ShortcutCapabilities | null>(null);
  const [effective, setEffective] = useState<EffectiveShortcutSet | null>(null);
  const [overrides, setOverrides] = useState<ShortcutOverride[]>([]);
  const [templates, setTemplates] = useState<ShortcutTemplate[]>([]);

  const [editing, setEditing] = useState<TemplateDraft | null>(null);
  const [draftErrors, setDraftErrors] = useState<DraftErrors>({});
  const [saving, setSaving] = useState(false);
  const [renaming, setRenaming] = useState('');
  const [renameFor, setRenameFor] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const capabilityView = await api<ShortcutCapabilities>('identity/shortcut-templates/capabilities');
      setCapabilities(capabilityView);

      const effectiveView = await api<EffectiveShortcutSet>('identity/shortcut-templates/effective');
      setEffective(effectiveView);

      const overrideView = await api<ShortcutOverride[]>('identity/shortcut-templates/overrides');
      setOverrides(overrideView ?? []);

      // 模板列表读不到（没有读权限）不该让整个页面失败 —— 生效集合才是本页的主输出。
      try {
        const templateView = await api<ShortcutTemplateList>(
          'identity/shortcut-templates?limit=100',
        );
        setTemplates(templateView?.templates ?? []);
      } catch (templateError) {
        setTemplates([]);
        setNotice(
          templateError instanceof ApiError && templateError.status === 403
            ? '当前角色没有快捷入口模板的查看权限，页面只显示与你个人相关的部分。'
            : `模板列表读取失败：${templateError instanceof Error ? templateError.message : '未知错误'}`,
        );
      }
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '快捷入口读取失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const summary = useMemo(() => summarizeEffectiveSet(effective), [effective]);
  const overrideByKey = useMemo(() => indexOverrides(overrides), [overrides]);
  const scopeGroups = useMemo(() => groupTemplatesByScope(templates), [templates]);

  // ================================================================ 个人覆盖

  const writeOverride = useCallback(
    async (targetKey: string, action: OverrideAction, label?: string) => {
      setNotice('');
      try {
        await api('identity/shortcut-templates/overrides', 'POST', {
          targetKey,
          action,
          label: label ?? null,
          pageCode: null,
          path: null,
          icon: null,
          position: null,
        });
        await load();
      } catch (overrideError) {
        setNotice(
          overrideError instanceof Error ? overrideError.message : '保存个人覆盖项失败，请重试',
        );
      }
    },
    [load],
  );

  const removeOverride = useCallback(
    async (id: string) => {
      setNotice('');
      try {
        await api(`identity/shortcut-templates/overrides/${id}`, 'DELETE');
        await load();
      } catch (overrideError) {
        setNotice(
          overrideError instanceof Error ? overrideError.message : '恢复默认失败，请重试',
        );
      }
    },
    [load],
  );

  const submitRename = useCallback(
    async (entry: EffectiveShortcut) => {
      const label = renaming.trim();
      if (!label) {
        setNotice('入口标题不能为空');
        return;
      }
      await writeOverride(entry.key, 'UPSERT', label);
      setRenameFor('');
      setRenaming('');
    },
    [renaming, writeOverride],
  );

  // ================================================================ 模板管理

  const startEdit = useCallback((template: ShortcutTemplate) => {
    setEditing(toTemplateDraft(template));
    setDraftErrors({});
  }, []);

  const startCreate = useCallback(() => {
    setEditing(emptyTemplateDraft());
    setDraftErrors({});
  }, []);

  const saveTemplate = useCallback(async () => {
    if (!editing) return;
    const errors = validateTemplateDraft(editing, !editing.id);
    setDraftErrors(errors);
    if (Object.keys(errors).length > 0) {
      setNotice('表单还有未通过校验的字段，请按提示修正后再保存。');
      return;
    }
    setSaving(true);
    setNotice('');
    try {
      const payload = toTemplateInput(editing);
      if (editing.id) {
        await api(`identity/shortcut-templates/${editing.id}`, 'PUT', payload);
      } else {
        await api('identity/shortcut-templates', 'POST', payload);
      }
      setEditing(null);
      await load();
    } catch (saveError) {
      setNotice(saveError instanceof Error ? saveError.message : '保存模板失败，请重试');
    } finally {
      setSaving(false);
    }
  }, [editing, load]);

  const deleteTemplate = useCallback(
    async (id: string) => {
      setNotice('');
      try {
        await api(`identity/shortcut-templates/${id}`, 'DELETE');
        if (editing?.id === id) setEditing(null);
        await load();
      } catch (deleteError) {
        setNotice(deleteError instanceof Error ? deleteError.message : '删除模板失败，请重试');
      }
    },
    [editing, load],
  );

  // ================================================================ 渲染

  if (loading) {
    return (
      <div className="space-y-4">
        <SectionCard title="快捷入口配置" description="正在读取你的岗位模板与最终生效的快捷入口……">
          <p className="text-copy-13 text-muted-foreground">加载中……</p>
        </SectionCard>
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-4">
        <SectionCard title="快捷入口配置">
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="快捷入口读取失败"
            description={error}
            action={
              <Button onClick={() => void load()}>
                <RefreshCw className="mr-2 h-4 w-4" />
                重试
              </Button>
            }
          />
        </SectionCard>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <SectionCard
        title="快捷入口配置"
        description="按岗位/团队预置的快捷入口模板，加上你的个人覆盖项，合并成最终生效的入口集合。"
        extra={
          <Button variant="outline" onClick={() => void load()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      >
        {capabilities ? (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <Badge tone="brand">模板管理已交付</Badge>
              <Badge tone="brand">个人覆盖已交付</Badge>
              <Badge tone="brand">权限范围内生效</Badge>
              <Badge tone="neutral">
                常用页面收藏（{capabilities.favoritesOwnerModule}）未交付
              </Badge>
            </div>
            <details className="text-copy-13 text-muted-foreground">
              <summary className="cursor-pointer">合并规则（为什么是这个结果）</summary>
              <ol className="mt-2 list-decimal space-y-1 pl-5">
                {capabilities.mergeRules.map((rule) => (
                  <li key={rule}>{rule}</li>
                ))}
              </ol>
            </details>
            <details className="text-copy-13 text-muted-foreground">
              <summary className="cursor-pointer">本模块的边界</summary>
              <ul className="mt-2 list-disc space-y-1 pl-5">
                {capabilities.boundaries.map((boundary) => (
                  <li key={boundary}>{boundary}</li>
                ))}
              </ul>
            </details>
          </div>
        ) : null}
      </SectionCard>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="生效入口" value={summary.total} icon={<ExternalLink className="h-4 w-4" />} />
        <StatCard label="命中模板" value={summary.templateCount} />
        <StatCard label="被我改过" value={summary.overridden} />
        <StatCard label="被我移除" value={summary.removedByOverride} />
        <StatCard label="因无权限隐藏" value={summary.hiddenByPermission} />
      </div>

      {notice ? (
        <div className="border-destructive-soft bg-destructive-soft text-destructive rounded-md border px-4 py-3 text-copy-13">
          {notice}
        </div>
      ) : null}

      <SectionCard
        title="我的快捷入口"
        description="这是最终生效的集合：模板项与你的个人覆盖合并后的结果。入口指向你无权访问的页面时不会出现在这里。"
      >
        {effective && effective.entries.length > 0 ? (
          <ul className="divide-border divide-y">
            {effective.entries.map((entry) => {
              const override = overrideByKey.get(entry.key);
              const renamingThis = renameFor === entry.key;
              return (
                <li key={entry.key} className="flex flex-wrap items-center gap-3 py-3">
                  <div className="min-w-0 flex-1">
                    {renamingThis ? (
                      <div className="flex items-center gap-2">
                        <input
                          className="border-input bg-background h-8 rounded-md border px-2 text-copy-13"
                          value={renaming}
                          onChange={(event) => setRenaming(event.target.value)}
                          aria-label={`修改「${entry.label}」的标题`}
                        />
                        <Button size="sm" onClick={() => void submitRename(entry)}>
                          保存
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            setRenameFor('');
                            setRenaming('');
                          }}
                        >
                          取消
                        </Button>
                      </div>
                    ) : (
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-foreground text-copy-13 font-medium">{entry.label}</span>
                        {entry.overridden ? <Badge tone="info">已改过</Badge> : null}
                        <span className="text-muted-foreground text-copy-13">
                          {describeEffectiveSource(entry)}
                        </span>
                      </div>
                    )}
                    <div className="text-muted-foreground mt-1 flex flex-wrap gap-3 text-copy-13">
                      <span>目标页面 {entry.pageCode}</span>
                      <span>路径 {entry.path}</span>
                      <span>排序位 {entry.position}</span>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <Link href={entry.path}>
                      <Button size="sm" variant="outline">
                        <ExternalLink className="mr-1 h-3.5 w-3.5" />
                        前往
                      </Button>
                    </Link>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setRenameFor(entry.key);
                        setRenaming(entry.label);
                      }}
                    >
                      <Pencil className="mr-1 h-3.5 w-3.5" />
                      改标题
                    </Button>
                    {override?.action === 'REMOVE' ? null : (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void writeOverride(entry.key, 'REMOVE')}
                      >
                        <Trash2 className="mr-1 h-3.5 w-3.5" />
                        移除
                      </Button>
                    )}
                    {override ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void removeOverride(override.id)}
                      >
                        <RotateCcw className="mr-1 h-3.5 w-3.5" />
                        恢复默认
                      </Button>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ul>
        ) : (
          <EmptyState
            icon={<ExternalLink className="h-6 w-6" />}
            title="还没有生效的快捷入口"
            description={
              summary.hiddenByPermission > 0
                ? `有 ${summary.hiddenByPermission} 个入口因为你没有对应页面的权限而被隐藏。`
                : '当前租户还没有配置适用于你的岗位模板。'
            }
          />
        )}

        {overrides.length > 0 ? (
          <div className="border-border mt-4 border-t pt-3">
            <p className="text-muted-foreground text-copy-13">
              你的个人覆盖项（{overrides.length} 条）：
              {overrides
                .map(
                  (override) =>
                    `${override.targetKey} → ${OVERRIDE_ACTION_LABELS[override.action as OverrideAction] ?? override.action}`,
                )
                .join('；')}
            </p>
          </div>
        ) : null}
      </SectionCard>

      <SectionCard
        title="岗位快捷入口模板"
        description="租户级配置：按全员 / 岗位 / 团队预置入口。新成员加入时自动获得岗位常用入口。"
        extra={
          <Button onClick={startCreate}>
            <Pencil className="mr-2 h-4 w-4" />
            新建模板
          </Button>
        }
      >
        {scopeGroups.length > 0 ? (
          <div className="space-y-4">
            {scopeGroups.map((group) => (
              <div key={group.scopeType}>
                <p className="text-muted-foreground mb-2 text-copy-13">
                  {group.label}（{group.templates.length} 张）
                </p>
                <ul className="divide-border divide-y">
                  {group.templates.map((template) => (
                    <li key={template.id} className="flex flex-wrap items-center gap-3 py-3">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-foreground text-copy-13 font-medium">
                            {template.name}
                          </span>
                          <Badge tone="neutral">{template.code}</Badge>
                          <Badge tone={template.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {TEMPLATE_STATUS_LABELS[template.status] ?? template.status}
                          </Badge>
                        </div>
                        <div className="text-muted-foreground mt-1 flex flex-wrap gap-3 text-copy-13">
                          <span>适用范围 {SCOPE_TYPE_LABELS[template.scopeType as ScopeType] ?? template.scopeType}</span>
                          <span>优先级 {template.priority}</span>
                          <span>入口 {template.entries.length} 个</span>
                          {template.entries.map((entry) => (
                            <span key={entry.key}>
                              {entry.label} → {entry.path}
                            </span>
                          ))}
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <Button size="sm" variant="outline" onClick={() => startEdit(template)}>
                          <Pencil className="mr-1 h-3.5 w-3.5" />
                          编辑
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => void deleteTemplate(template.id)}
                        >
                          <Trash2 className="mr-1 h-3.5 w-3.5" />
                          删除
                        </Button>
                      </div>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            icon={<Pencil className="h-6 w-6" />}
            title="还没有岗位模板"
            description="新建一张模板，为某个岗位预置常用入口，新成员加入时就会自动拿到。"
            action={
              <Button onClick={startCreate}>
                <Pencil className="mr-2 h-4 w-4" />
                新建模板
              </Button>
            }
          />
        )}
      </SectionCard>

      {editing ? (
        <SectionCard
          title={editing.id ? '编辑模板' : '新建模板'}
          description="入口标识在同一张模板内唯一；目标页面码必须已在权限表登记，否则后端会拒。"
          extra={
            <>
              <Button variant="ghost" onClick={() => setEditing(null)}>
                取消
              </Button>
              <Button disabled={saving} onClick={() => void saveTemplate()}>
                {saving ? '保存中……' : '保存'}
              </Button>
            </>
          }
        >
          <div className="space-y-3">
            {editing.id ? null : (
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="text-copy-13">
                  <span className="text-muted-foreground">模板编码</span>
                  <input
                    className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2"
                    value={editing.code}
                    onChange={(event) =>
                      setEditing({ ...editing, code: event.target.value })
                    }
                  />
                  {draftErrors.code ? (
                    <span className="text-destructive text-copy-13">{draftErrors.code}</span>
                  ) : null}
                </label>
                <label className="text-copy-13">
                  <span className="text-muted-foreground">模板名称</span>
                  <input
                    className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2"
                    value={editing.name}
                    onChange={(event) =>
                      setEditing({ ...editing, name: event.target.value })
                    }
                  />
                  {draftErrors.name ? (
                    <span className="text-destructive text-copy-13">{draftErrors.name}</span>
                  ) : null}
                </label>
              </div>
            )}

            <div className="grid gap-3 sm:grid-cols-3">
              <label className="text-copy-13">
                <span className="text-muted-foreground">适用范围</span>
                <select
                  className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2"
                  value={editing.scopeType}
                  onChange={(event) =>
                    setEditing({ ...editing, scopeType: event.target.value as ScopeType })
                  }
                >
                  {(['TENANT', 'ROLE', 'DEPARTMENT'] as ScopeType[]).map((scopeType) => (
                    <option key={scopeType} value={scopeType}>
                      {SCOPE_TYPE_LABELS[scopeType]}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13">
                <span className="text-muted-foreground">岗位 ID（scope=ROLE 时必填）</span>
                <input
                  className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2"
                  value={editing.roleId}
                  onChange={(event) => setEditing({ ...editing, roleId: event.target.value })}
                />
                {draftErrors.roleId ? (
                  <span className="text-destructive text-copy-13">{draftErrors.roleId}</span>
                ) : null}
              </label>
              <label className="text-copy-13">
                <span className="text-muted-foreground">部门 ID（scope=DEPARTMENT 时必填）</span>
                <input
                  className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2"
                  value={editing.departmentId}
                  onChange={(event) =>
                    setEditing({ ...editing, departmentId: event.target.value })
                  }
                />
                {draftErrors.departmentId ? (
                  <span className="text-destructive text-copy-13">{draftErrors.departmentId}</span>
                ) : null}
              </label>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <label className="text-copy-13">
                <span className="text-muted-foreground">优先级（小的先合并）</span>
                <input
                  className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2"
                  value={editing.priority}
                  onChange={(event) => setEditing({ ...editing, priority: event.target.value })}
                />
                {draftErrors.priority ? (
                  <span className="text-destructive text-copy-13">{draftErrors.priority}</span>
                ) : null}
              </label>
              <label className="text-copy-13">
                <span className="text-muted-foreground">状态</span>
                <select
                  className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2"
                  value={editing.status}
                  onChange={(event) =>
                    setEditing({ ...editing, status: event.target.value as 'ACTIVE' | 'INACTIVE' })
                  }
                >
                  <option value="ACTIVE">生效中</option>
                  <option value="INACTIVE">已停用</option>
                </select>
              </label>
            </div>

            <div>
              <div className="flex items-center justify-between">
                <p className="text-muted-foreground text-copy-13">入口清单</p>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setEditing({
                      ...editing,
                      entries: [
                        ...editing.entries,
                        { key: '', label: '', pageCode: '', path: '', icon: '', position: '' },
                      ],
                    })
                  }
                >
                  增加入口
                </Button>
              </div>
              {editing.entries.length === 0 ? (
                <p className="text-muted-foreground mt-2 text-copy-13">
                  这张模板还没有入口。模板不配任何入口时，命中它的人不会多出任何快捷入口。
                </p>
              ) : (
                <ul className="mt-2 space-y-3">
                  {editing.entries.map((entry, index) => (
                    <li key={index} className="border-border rounded-md border p-3">
                      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                        {(
                          [
                            ['key', '入口标识'],
                            ['label', '标题'],
                            ['pageCode', '目标页面码'],
                            ['path', '跳转路径'],
                            ['icon', '图标名'],
                            ['position', '排序位'],
                          ] as const
                        ).map(([field, label]) => (
                          <label key={field} className="text-copy-13">
                            <span className="text-muted-foreground">{label}</span>
                            <input
                              className="border-input bg-background mt-1 h-8 w-full rounded-md border px-2"
                              value={entry[field]}
                              onChange={(event) => {
                                const next = [...editing.entries];
                                next[index] = { ...entry, [field]: event.target.value };
                                setEditing({ ...editing, entries: next });
                              }}
                            />
                            {draftErrors[`entries.${index}.${field}`] ? (
                              <span className="text-destructive text-copy-13">
                                {draftErrors[`entries.${index}.${field}`]}
                              </span>
                            ) : null}
                          </label>
                        ))}
                      </div>
                      <Button
                        size="sm"
                        variant="ghost"
                        className="mt-2"
                        onClick={() =>
                          setEditing({
                            ...editing,
                            entries: editing.entries.filter((_, i) => i !== index),
                          })
                        }
                      >
                        <Trash2 className="mr-1 h-3.5 w-3.5" />
                        移除这条入口
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
