'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { CalendarClock, FlaskConical, RefreshCw, Save, Trash2 } from 'lucide-react';
import type {
  CrmReviewAssignmentPage,
  CrmReviewAssignmentRow,
  CrmReviewEffectComparison,
  CrmReviewEffectivePolicy,
  CrmReviewHolidayPage,
  CrmReviewHolidayRow,
  CrmReviewRuleCapabilities,
  CrmReviewRuleCandidatePage,
  CrmReviewRulePage,
  CrmReviewRuleRow,
  CrmReviewRuleShopView,
  CrmReviewTemplatePage,
  CrmReviewTemplateRow,
  CrmReviewTemplateVariantInput,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  applyPolicy,
  averageRatingText,
  createHoliday,
  createRule,
  createTemplate,
  deleteHoliday,
  deleteRule,
  deleteTemplate,
  effectiveSummary,
  fetchAssignment,
  fetchAssignments,
  fetchCandidates,
  fetchEffectComparison,
  fetchEffective,
  fetchHolidays,
  fetchRuleCapabilities,
  fetchRules,
  fetchRuleShops,
  fetchTemplates,
  holidayKindLabel,
  logisticsModeLabel,
  pickedVariant,
  recoveryRateText,
  requestStatusLabel,
  ruleStatusLabel,
  templateStatusLabel,
  updateHoliday,
  updateRule,
  updateTemplate,
} from './review-rule-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.2.2.2 索评规则工作区。
 *
 * <p>页面按「先看谁说了算、再配规则与话术、最后落策略看效果」的顺序排，
 * 因为蓝图输出句要的是两样东西：**生效策略**与**效果对比** ——
 * 界面上第一件要让人看到的就是「此刻哪条规则说了算」。
 *
 * <ul>
 *   <li><b>能力声明</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       下游 15.1.1.2 消息发送未交付、平台评价同步未交付、订单履约方式不可核实，
 *       三块显式标出并写清归属模块，<b>不给任何看起来能点的假入口</b>。</li>
 *   <li><b>生效策略预览</b> —— 选店铺 + 物流方式（+ 可选分桶种子），
 *       看命中哪条规则、排期到什么时候、避开了哪个节假日、分到哪个话术臂。</li>
 *   <li><b>索评规则</b> —— 增删改查；同一范围同一优先级不允许有两条生效规则
 *       （数据库层挡住），页面因此**不提供**「指定用哪条规则」的入口。</li>
 *   <li><b>话术模板</b> —— 模板 + A / B 两个变体与权重；变体码重复会被服务端拒。</li>
 *   <li><b>节假日</b> —— 支持连休；命中后按规则时区顺延，
 *       顺延到<b>第一个不是节假日的日子</b>。</li>
 *   <li><b>应用策略</b> —— 把生效策略落到具体的索评请求上（哪个变体、排期到什么时候）。
 *       这是效果对比唯一的归属事实来源。</li>
 *   <li><b>效果对比</b> —— 按变体拆开分配数 / 已发出 / 回收 / 平均星级；
 *       分母为 0 时回收率报「不计算」而不是 0%。</li>
 * </ul>
 */
export function ReviewRuleWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.rule.update');

  const [capabilities, setCapabilities] = useState<CrmReviewRuleCapabilities | null>(null);
  const [shops, setShops] = useState<CrmReviewRuleShopView[]>([]);
  const [rules, setRules] = useState<CrmReviewRulePage | null>(null);
  const [templates, setTemplates] = useState<CrmReviewTemplatePage | null>(null);
  const [holidays, setHolidays] = useState<CrmReviewHolidayPage | null>(null);
  const [candidates, setCandidates] = useState<CrmReviewRuleCandidatePage | null>(null);
  const [assignments, setAssignments] = useState<CrmReviewAssignmentPage | null>(null);
  const [assignmentDetail, setAssignmentDetail] = useState<CrmReviewAssignmentRow | null>(null);
  const [comparison, setComparison] = useState<CrmReviewEffectComparison | null>(null);
  const [policy, setPolicy] = useState<CrmReviewEffectivePolicy | null>(null);

  const [shopFilter, setShopFilter] = useState('');
  const [modeFilter, setModeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [holidayYear, setHolidayYear] = useState('');
  const [effectDays, setEffectDays] = useState('30');

  // 生效策略预览的输入。
  const [previewShopId, setPreviewShopId] = useState('');
  const [previewMode, setPreviewMode] = useState('SELF_FULFILLED');
  const [seedKey, setSeedKey] = useState('');

  // 应用策略的输入。
  const [applyRequestId, setApplyRequestId] = useState('');
  const [applyMode, setApplyMode] = useState('SELF_FULFILLED');

  // 表单草稿。
  const [ruleDraft, setRuleDraft] = useState<RuleDraft>(emptyRuleDraft());
  const [editingRuleId, setEditingRuleId] = useState<string | null>(null);
  const [templateDraft, setTemplateDraft] = useState<TemplateDraft>(emptyTemplateDraft());
  const [editingTemplateId, setEditingTemplateId] = useState<string | null>(null);
  const [holidayDraft, setHolidayDraft] = useState<HolidayDraft>(emptyHolidayDraft());
  const [editingHolidayId, setEditingHolidayId] = useState<string | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, shopList, rulePage, templatePage, holidayPage, candidatePage, assignmentPage, effect] =
        await Promise.all([
          fetchRuleCapabilities(),
          fetchRuleShops(),
          fetchRules({
            shopId: shopFilter || undefined,
            logisticsMode: modeFilter || undefined,
            status: statusFilter || undefined,
            keyword: keyword || undefined,
            size: 50,
          }),
          fetchTemplates({ size: 50 }),
          fetchHolidays({ year: holidayYear || undefined, size: 50 }),
          fetchCandidates({ size: 50 }),
          fetchAssignments({ size: 50 }),
          fetchEffectComparison({ days: Number(effectDays) || 30 }),
        ]);
      setCapabilities(caps);
      setShops(shopList);
      setRules(rulePage);
      setTemplates(templatePage);
      setHolidays(holidayPage);
      setCandidates(candidatePage);
      setAssignments(assignmentPage);
      setComparison(effect);
    } catch (e) {
      setError(errorText(e, '索评规则工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [shopFilter, modeFilter, statusFilter, keyword, holidayYear, effectDays]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadPolicy = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      setPolicy(
        await fetchEffective({
          shopId: previewShopId || undefined,
          logisticsMode: previewMode,
          seedKey: seedKey || undefined,
        }),
      );
    } catch (e) {
      setActionError(errorText(e, '生效策略读取失败'));
    } finally {
      setBusy(false);
    }
  }, [previewShopId, previewMode, seedKey]);

  const submitRule = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const input = {
        ruleName: ruleDraft.ruleName,
        shopId: ruleDraft.shopId || null,
        logisticsMode: ruleDraft.logisticsMode,
        delayHours: Number(ruleDraft.delayHours) || 0,
        templateId: ruleDraft.templateId || null,
        abTestEnabled: ruleDraft.abTestEnabled,
        avoidHolidays: ruleDraft.avoidHolidays,
        holidayRegion: ruleDraft.holidayRegion,
        holidayShiftDays: Number(ruleDraft.holidayShiftDays) || 0,
        timezone: ruleDraft.timezone,
        priority: Number(ruleDraft.priority) || 100,
        status: ruleDraft.status,
        effectiveFrom: ruleDraft.effectiveFrom || null,
        effectiveTo: ruleDraft.effectiveTo || null,
        note: ruleDraft.note,
      };
      if (editingRuleId) {
        await updateRule(editingRuleId, input);
        setNotice(`索评规则已更新：${ruleDraft.ruleName}`);
      } else {
        const created = await createRule(input);
        setNotice(`索评规则已创建：${created.ruleNo}（${created.ruleName}）`);
      }
      setRuleDraft(emptyRuleDraft());
      setEditingRuleId(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '索评规则保存失败'));
    } finally {
      setBusy(false);
    }
  }, [ruleDraft, editingRuleId, load]);

  const removeRule = useCallback(
    async (rule: CrmReviewRuleRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteRule(rule.id);
        setNotice(`索评规则 ${rule.ruleNo} 已删除。`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '索评规则删除失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitTemplate = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const variants: CrmReviewTemplateVariantInput[] = templateDraft.variants
        .filter((variant) => variant.variantCode !== '')
        .map((variant) => ({
          variantCode: variant.variantCode,
          subject: variant.subject,
          body: variant.body,
          weight: Number(variant.weight) || 0,
          status: variant.status,
        }));
      if (variants.length === 0) {
        setActionError('至少要有一个话术变体：没有变体的模板发不出任何话术。');
        return;
      }
      const input = {
        templateName: templateDraft.templateName,
        locale: templateDraft.locale,
        status: templateDraft.status,
        variants,
        note: templateDraft.note,
      };
      if (editingTemplateId) {
        await updateTemplate(editingTemplateId, input);
        setNotice(`话术模板已更新：${templateDraft.templateName}`);
      } else {
        const created = await createTemplate(input);
        setNotice(`话术模板已创建：${created.templateNo}（${created.templateName}）`);
      }
      setTemplateDraft(emptyTemplateDraft());
      setEditingTemplateId(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '话术模板保存失败'));
    } finally {
      setBusy(false);
    }
  }, [templateDraft, editingTemplateId, load]);

  const removeTemplate = useCallback(
    async (template: CrmReviewTemplateRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteTemplate(template.id);
        setNotice(`话术模板 ${template.templateNo} 已删除。`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '话术模板删除失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitHoliday = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const input = {
        holidayDate: holidayDraft.holidayDate,
        holidayEndDate: holidayDraft.holidayEndDate || null,
        holidayName: holidayDraft.holidayName,
        region: holidayDraft.region,
        kind: holidayDraft.kind,
        note: holidayDraft.note,
      };
      if (editingHolidayId) {
        await updateHoliday(editingHolidayId, input);
        setNotice(`节假日已更新：${holidayDraft.holidayName}`);
      } else {
        const created = await createHoliday(input);
        setNotice(`节假日已登记：${created.holidayName}（${created.holidayDate}）`);
      }
      setHolidayDraft(emptyHolidayDraft());
      setEditingHolidayId(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '节假日保存失败'));
    } finally {
      setBusy(false);
    }
  }, [holidayDraft, editingHolidayId, load]);

  const removeHoliday = useCallback(
    async (holiday: CrmReviewHolidayRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteHoliday(holiday.id);
        setNotice(`节假日 ${holiday.holidayName} 已删除。`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '节假日删除失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const openAssignment = useCallback(async (assignmentId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setAssignmentDetail(await fetchAssignment(assignmentId));
    } catch (e) {
      setActionError(errorText(e, '生效策略落地记录详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitApply = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await applyPolicy({
        reviewRequestId: applyRequestId,
        logisticsMode: applyMode,
        note: '',
      });
      setNotice(
        `生效策略已落到索评请求 ${created.requestNo}：话术变体 ${
          created.variantCode || '（无）'
        }，排期 ${created.scheduledAt}${
          created.shiftedDays > 0 ? `（避开节假日顺延 ${created.shiftedDays} 天）` : ''
        }。`,
      );
      setApplyRequestId('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '应用生效策略失败'));
    } finally {
      setBusy(false);
    }
  }, [applyRequestId, applyMode, load]);

  const variant = useMemo(() => pickedVariant(policy), [policy]);

  if (loading) {
    return (
      <SectionCard title="索评规则工作区">
        <p className="text-muted-foreground text-copy-13">加载中…</p>
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
          {error}
        </div>
      )}
      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 rounded-md px-3 py-2 text-copy-13">{notice}</div>
      )}

      {/* ---------------------------------------------------------- 能力声明 */}
      <SectionCard title="能力声明">
        <ul className="space-y-2 text-copy-13">
          {(capabilities?.dimensions ?? []).map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone={dimension.available ? 'success' : 'warning'}>
                {dimension.available ? '可用' : '未接入'}
              </Badge>
              <span className="font-medium">{dimension.description}</span>
              {!dimension.available && (
                <span className="text-muted-foreground">
                  （归属模块 {dimension.ownerModule}）—— {dimension.impact}
                </span>
              )}
            </li>
          ))}
        </ul>
        {capabilities?.highlight && (
          <p className="text-muted-foreground mt-3 text-copy-12">{capabilities.highlight}</p>
        )}
        {capabilities?.boundaryNote && (
          <p className="text-muted-foreground mt-1 text-copy-12">{capabilities.boundaryNote}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 生效策略预览 */}
      <SectionCard title="生效策略（此刻谁说了算）">
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="适用店铺" hint="留空 = 查适用于全部店铺的规则。">
              <select
                className={control}
                value={previewShopId}
                onChange={(e) => setPreviewShopId(e.target.value)}
              >
                <option value="">（全部店铺）</option>
                {shops.map((shop) => (
                  <option key={shop.id} value={shop.id}>
                    {shop.name} · {shop.platformCode}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="物流方式">
              <select
                className={control}
                value={previewMode}
                onChange={(e) => setPreviewMode(e.target.value)}
              >
                <option value="SELF_FULFILLED">自发货</option>
                <option value="PLATFORM_WAREHOUSE">平台仓</option>
                <option value="ANY">不限</option>
              </select>
            </Field>
            <Field
              label="A/B 分桶种子（可选）"
              hint="同一个种子永远落到同一个臂；留空则只能说清权重分布，说不出分到哪个臂。"
            >
              <input
                className={control}
                value={seedKey}
                onChange={(e) => setSeedKey(e.target.value)}
                placeholder="例：AMZN-BUYER-8821"
              />
            </Field>
          </div>
          <Button onClick={() => void loadPolicy()} size="sm" variant="secondary" disabled={busy}>
            <RefreshCw className="mr-1 h-4 w-4" />
            看生效策略
          </Button>

          {policy && (
            <div className="border-border space-y-2 rounded-md px-3 py-2 text-copy-13">
              <p className="font-medium">{effectiveSummary(policy)}</p>
              {!policy.ruleId ? (
                <p className="text-muted-foreground text-copy-12">
                  没有命中规则时页面**不**回退到一组写死的默认值 —— 那会让运营以为策略已经配好了。
                </p>
              ) : (
                <>
                  <p className="text-muted-foreground text-copy-12">
                    排期 {policy.scheduledAt}
                    {policy.holidayAvoided
                      ? `（原 ${policy.baseScheduledAt}，避开节假日「${policy.holidayName}」顺延 ${policy.shiftedDays} 天）`
                      : `（原 ${policy.baseScheduledAt}，未命中节假日）`}
                    。顺延口径：先按顺延天数往后挪，再往后找**第一个不是节假日的日子**。
                  </p>
                  <p className="text-muted-foreground text-copy-12">
                    分到的话术臂：{variant ? `变体 ${variant.variantCode} · ${variant.subject}` : '（没有种子 / 没有启用中的变体）'}
                  </p>
                  {policy.variants.length > 0 && (
                    <ul className="text-muted-foreground space-y-1 text-copy-12">
                      {policy.variants.map((item) => (
                        <li key={item.id}>
                          变体 {item.variantCode} · 权重 {item.weight} · {item.subject}
                          {item.id === policy.pickedVariantId && ' ← 本次分到'}
                        </li>
                      ))}
                    </ul>
                  )}
                  <p className="text-muted-foreground text-copy-12">{policy.note}</p>
                </>
              )}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 索评规则 */}
      <SectionCard title="索评规则（按店铺 / 按物流方式）">
        <div className="mb-3 flex flex-wrap gap-2">
          <select
            className={control}
            value={shopFilter}
            onChange={(e) => setShopFilter(e.target.value)}
          >
            <option value="">全部店铺</option>
            {shops.map((shop) => (
              <option key={shop.id} value={shop.id}>
                {shop.name}
              </option>
            ))}
          </select>
          <select
            className={control}
            value={modeFilter}
            onChange={(e) => setModeFilter(e.target.value)}
          >
            <option value="">全部物流方式</option>
            <option value="ANY">不限</option>
            <option value="SELF_FULFILLED">自发货</option>
            <option value="PLATFORM_WAREHOUSE">平台仓</option>
          </select>
          <select
            className={control}
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
          >
            <option value="">全部状态</option>
            <option value="DRAFT">草稿</option>
            <option value="ACTIVE">生效中</option>
            <option value="PAUSED">已暂停</option>
          </select>
          <input
            className={control}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="按规则名 / 规则号 / 店铺名筛选"
          />
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        </div>

        {rules && rules.items.length === 0 ? (
          <EmptyState
            title="还没有索评规则"
            description="配一条规则之后，索评才有「什么时候问、问哪个话术臂」可依；没有规则时生效策略是空的。"
          />
        ) : (
          <ul className="space-y-2 text-copy-13">
            {(rules?.items ?? []).map((rule) => (
              <li key={rule.id} className="border-border rounded-md px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {ruleStatusLabel(rule.status)}
                  </Badge>
                  <span className="font-medium">{rule.ruleName}</span>
                  <span className="text-muted-foreground">
                    {rule.ruleNo} · {rule.shopName || '全部店铺'} ·{' '}
                    {logisticsModeLabel(rule.logisticsMode)} · 延后 {rule.delayHours} 小时 · 优先级{' '}
                    {rule.priority}
                  </span>
                  <span className="text-muted-foreground">
                    话术 {rule.templateName || '未绑定模板'}
                    {rule.abTestEnabled ? '（A/B 已开）' : ''} · 已落地 {rule.assignmentCount} 次
                  </span>
                </div>
                {writable && (
                  <div className="mt-1 flex gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setEditingRuleId(rule.id);
                        setRuleDraft({
                          ruleName: rule.ruleName,
                          shopId: rule.shopId ?? '',
                          logisticsMode: rule.logisticsMode,
                          delayHours: String(rule.delayHours),
                          templateId: rule.templateId ?? '',
                          abTestEnabled: rule.abTestEnabled,
                          avoidHolidays: rule.avoidHolidays,
                          holidayRegion: rule.holidayRegion,
                          holidayShiftDays: String(rule.holidayShiftDays),
                          timezone: rule.timezone,
                          priority: String(rule.priority),
                          status: rule.status,
                          effectiveFrom: rule.effectiveFrom ?? '',
                          effectiveTo: rule.effectiveTo ?? '',
                          note: rule.note,
                        });
                      }}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void removeRule(rule)}
                      disabled={busy}
                    >
                      <Trash2 className="mr-1 h-4 w-4" />
                      删除
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}

        {writable && (
          <div className="border-border mt-4 space-y-3 rounded-md px-3 py-3">
            <h3 className="font-medium">
              {editingRuleId ? '编辑索评规则' : '新建索评规则'}
            </h3>
            <RuleForm
              draft={ruleDraft}
              setDraft={setRuleDraft}
              shops={shops}
              templates={templates?.items ?? []}
            />
            <div className="flex gap-2">
              <Button onClick={() => void submitRule()} size="sm" disabled={busy}>
                <Save className="mr-1 h-4 w-4" />
                {editingRuleId ? '保存修改' : '创建规则'}
              </Button>
              {editingRuleId && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setEditingRuleId(null);
                    setRuleDraft(emptyRuleDraft());
                  }}
                >
                  取消编辑
                </Button>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 话术模板 */}
      <SectionCard title="索评话术模板（A / B 变体）">
        {templates && templates.items.length === 0 ? (
          <EmptyState
            title="还没有话术模板"
            description="模板是话术的身份（叫什么、什么语言），变体是内容（A / B 两臂 + 权重）。"
          />
        ) : (
          <ul className="space-y-2 text-copy-13">
            {(templates?.items ?? []).map((template) => (
              <li key={template.id} className="border-border rounded-md px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={template.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {templateStatusLabel(template.status)}
                  </Badge>
                  <span className="font-medium">{template.templateName}</span>
                  <span className="text-muted-foreground">
                    {template.templateNo} · {template.locale}
                  </span>
                </div>
                <ul className="text-muted-foreground mt-1 space-y-1 text-copy-12">
                  {template.variants.map((item) => (
                    <li key={item.id}>
                      变体 {item.variantCode} · 权重 {item.weight} · {templateStatusLabel(item.status)} ·{' '}
                      {item.subject || '（无标题）'}
                      {item.body ? ` · ${item.body}` : ''}
                    </li>
                  ))}
                </ul>
                {writable && (
                  <div className="mt-1 flex gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setEditingTemplateId(template.id);
                        setTemplateDraft({
                          templateName: template.templateName,
                          locale: template.locale,
                          status: template.status,
                          note: template.note,
                          variants: template.variants.map((item) => ({
                            variantCode: item.variantCode,
                            subject: item.subject,
                            body: item.body,
                            weight: String(item.weight),
                            status: item.status,
                          })),
                        });
                      }}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void removeTemplate(template)}
                      disabled={busy}
                    >
                      <Trash2 className="mr-1 h-4 w-4" />
                      删除
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}

        {writable && (
          <div className="border-border mt-4 space-y-3 rounded-md px-3 py-3">
            <h3 className="font-medium">
              {editingTemplateId ? '编辑话术模板' : '新建话术模板'}
            </h3>
            <TemplateForm draft={templateDraft} setDraft={setTemplateDraft} />
            <div className="flex gap-2">
              <Button onClick={() => void submitTemplate()} size="sm" disabled={busy}>
                <Save className="mr-1 h-4 w-4" />
                {editingTemplateId ? '保存修改' : '创建模板'}
              </Button>
              {editingTemplateId && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setEditingTemplateId(null);
                    setTemplateDraft(emptyTemplateDraft());
                  }}
                >
                  取消编辑
                </Button>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 节假日 */}
      <SectionCard title="节假日（索评要避开的日子）">
        <div className="mb-3 flex flex-wrap gap-2">
          <input
            className={control}
            value={holidayYear}
            onChange={(e) => setHolidayYear(e.target.value)}
            placeholder="按年份筛选，例如 2026"
          />
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        </div>
        {holidays && holidays.items.length === 0 ? (
          <EmptyState
            title="还没有登记节假日"
            description="节假日是日历概念：填日期即可（结束日期留空 = 单日，填了 = 连休）。"
          />
        ) : (
          <ul className="space-y-2 text-copy-13">
            {(holidays?.items ?? []).map((holiday) => (
              <li key={holiday.id} className="border-border flex flex-wrap items-center gap-2 rounded-md px-3 py-2">
                <Badge tone="warning">{holidayKindLabel(holiday.kind)}</Badge>
                <span className="font-medium">{holiday.holidayName}</span>
                <span className="text-muted-foreground">
                  {holiday.holidayDate}
                  {holiday.holidayEndDate && holiday.holidayEndDate !== holiday.holidayDate
                    ? ` ~ ${holiday.holidayEndDate}`
                    : ''}{' '}
                  · {holiday.region || '全部区域'}
                </span>
                {writable && (
                  <span className="ml-auto flex gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setEditingHolidayId(holiday.id);
                        setHolidayDraft({
                          holidayDate: holiday.holidayDate,
                          holidayEndDate: holiday.holidayEndDate ?? '',
                          holidayName: holiday.holidayName,
                          region: holiday.region,
                          kind: holiday.kind,
                          note: holiday.note,
                        });
                      }}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void removeHoliday(holiday)}
                      disabled={busy}
                    >
                      <Trash2 className="mr-1 h-4 w-4" />
                      删除
                    </Button>
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}

        {writable && (
          <div className="border-border mt-4 space-y-3 rounded-md px-3 py-3">
            <h3 className="font-medium">
              {editingHolidayId ? '编辑节假日' : '登记节假日'}
            </h3>
            <HolidayForm draft={holidayDraft} setDraft={setHolidayDraft} />
            <div className="flex gap-2">
              <Button onClick={() => void submitHoliday()} size="sm" disabled={busy}>
                <CalendarClock className="mr-1 h-4 w-4" />
                {editingHolidayId ? '保存修改' : '登记节假日'}
              </Button>
              {editingHolidayId && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setEditingHolidayId(null);
                    setHolidayDraft(emptyHolidayDraft());
                  }}
                >
                  取消编辑
                </Button>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 应用策略 */}
      <SectionCard title="应用生效策略（落到具体的索评请求）">
        <div className="space-y-3">
          <p className="text-muted-foreground text-copy-12">
            规则由服务端按生效策略**解析**得出，这里**不提供**「指定用哪条规则」的入口 ——
            指定规则就绕过了「生效策略」这件事本身。落地记录是效果对比唯一的归属事实来源。
          </p>
          {candidates && candidates.items.length === 0 ? (
            <EmptyState
              title="没有可用的索评任务"
              description="先在「索评（Request a Review）」里发起索评，任务才会出现在这里。"
            />
          ) : (
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="索评请求">
                <select
                  className={control}
                  value={applyRequestId}
                  onChange={(e) => setApplyRequestId(e.target.value)}
                >
                  <option value="">（选择一条索评任务）</option>
                  {(candidates?.items ?? []).map((candidate) => (
                    <option key={candidate.requestId} value={candidate.requestId}>
                      {candidate.requestNo} · {candidate.shopName} · {candidate.salesOrderNo} ·{' '}
                      {requestStatusLabel(candidate.status)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field
                label="这一单的物流方式"
                hint="订单的履约方式不可从上游核实（15.2.2.1 不带这个维度），由运营给出。"
              >
                <select
                  className={control}
                  value={applyMode}
                  onChange={(e) => setApplyMode(e.target.value)}
                >
                  <option value="SELF_FULFILLED">自发货</option>
                  <option value="PLATFORM_WAREHOUSE">平台仓</option>
                </select>
              </Field>
              <div className="flex items-end">
                <Button
                  onClick={() => void submitApply()}
                  size="sm"
                  disabled={busy || !writable || !applyRequestId}
                >
                  <FlaskConical className="mr-1 h-4 w-4" />
                  应用生效策略
                </Button>
              </div>
            </div>
          )}

          {assignments && assignments.items.length === 0 ? (
            <EmptyState
              title="还没有生效策略落地记录"
              description="落地记录回答「哪条索评请求用了哪个话术臂」—— 没有它，效果对比无从按 A / B 拆开。"
            />
          ) : (
            <ul className="space-y-2 text-copy-13">
              {(assignments?.items ?? []).map((assignment) => (
                <li key={assignment.id} className="border-border rounded-md px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={assignment.requestStatus === 'SENT' ? 'success' : 'neutral'}>
                      {requestStatusLabel(assignment.requestStatus)}
                    </Badge>
                    <span className="font-medium">{assignment.requestNo}</span>
                    <span className="text-muted-foreground">
                      {assignment.shopName} · 变体 {assignment.variantCode || '（无）'} · 排期{' '}
                      {assignment.scheduledAt}
                    </span>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void openAssignment(assignment.id)}
                      disabled={busy}
                    >
                      详情
                    </Button>
                  </div>
                  <p className="text-muted-foreground mt-1 text-copy-12">
                    规则 {assignment.ruleNo}（{assignment.ruleName}）· 延后 {assignment.delayHours} 小时
                    {assignment.shiftedDays > 0
                      ? ` · 避开节假日「${assignment.holidayName}」顺延 ${assignment.shiftedDays} 天`
                      : ''}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 落地详情 */}
      {assignmentDetail && (
        <SectionCard title={`生效策略落地详情 · ${assignmentDetail.requestNo}`}>
          <div className="space-y-2 text-copy-13">
            <p className="text-muted-foreground">
              规则 {assignmentDetail.ruleNo}（{assignmentDetail.ruleName}）· 变体{' '}
              {assignmentDetail.variantCode || '（无）'} · 模板 {assignmentDetail.templateName || '（无）'}
            </p>
            <p className="text-muted-foreground">
              目标店铺 {assignmentDetail.shopName} · 物流方式{' '}
              {logisticsModeLabel(assignmentDetail.logisticsMode)} · 买家 {assignmentDetail.buyerRef} ·
              订单 {assignmentDetail.salesOrderNo}
            </p>
            <p className="text-muted-foreground">
              签收 {assignmentDetail.deliveredAt || '（未知）'} · 延后 {assignmentDetail.delayHours} 小时 ·
              排期 {assignmentDetail.scheduledAt}
              {assignmentDetail.shiftedDays > 0
                ? ` · 避开节假日「${assignmentDetail.holidayName}」顺延 ${assignmentDetail.shiftedDays} 天`
                : ''}
            </p>
            <p className="text-muted-foreground text-copy-12">
              这一行是**快照**：规则改了也不改写它 —— 改了会让「哪个变体带来了这次回收」在历史上出现两个答案。
            </p>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 效果对比 */}
      <SectionCard title="效果对比（A / B 两个臂）">
        <div className="mb-3 flex flex-wrap gap-2">
          <input
            className={control}
            value={effectDays}
            onChange={(e) => setEffectDays(e.target.value)}
            placeholder="统计窗口天数，例如 30"
          />
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        </div>
        {!comparison ? (
          <p className="text-muted-foreground text-copy-13">效果对比暂不可用。</p>
        ) : (
          <div className="space-y-3 text-copy-13">
            <p>
              最近 {comparison.scopeDays} 天：落地 {comparison.totalAssignments} 条。
            </p>
            {comparison.variants.length === 0 ? (
              <EmptyState
                title="还没有可对比的样本"
                description="先把生效策略应用到索评请求上，这里才会有 A / B 两臂的分布与回收。"
              />
            ) : (
              <ul className="space-y-2">
                {comparison.variants.map((row, index) => (
                  <li key={row.variantId ?? index} className="border-border rounded-md px-3 py-2">
                    <span className="font-medium">变体 {row.variantCode}</span>{' '}
                    <span className="text-muted-foreground">
                      {row.templateName || '（模板已删）'} · 权重 {row.weight}
                    </span>
                    <p className="text-muted-foreground mt-1 text-copy-12">
                      分配 {row.assigned} · 已发出 {row.sent} · 回收 {row.recovered} · 回收率{' '}
                      {recoveryRateText(row.recoveryRate)} · 平均星级{' '}
                      {averageRatingText(row.averageRating)}
                    </p>
                    <p className="text-muted-foreground mt-1 text-copy-12">{row.note}</p>
                  </li>
                ))}
              </ul>
            )}
            {comparison.byRule.length > 0 && (
              <div>
                <h3 className="font-medium">按规则</h3>
                <ul className="space-y-1 text-copy-12">
                  {comparison.byRule.map((row) => (
                    <li key={row.ruleId}>
                      {row.ruleNo}（{row.ruleName}）· 分配 {row.assigned} · 已发出 {row.sent} · 回收{' '}
                      {row.recovered} · 回收率 {recoveryRateText(row.recoveryRate)} · 平均星级{' '}
                      {averageRatingText(row.averageRating)}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <p className="text-muted-foreground text-copy-12">{comparison.note}</p>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

// ================================================================ 表单草稿与表单

interface RuleDraft {
  ruleName: string;
  shopId: string;
  logisticsMode: string;
  delayHours: string;
  templateId: string;
  abTestEnabled: boolean;
  avoidHolidays: boolean;
  holidayRegion: string;
  holidayShiftDays: string;
  timezone: string;
  priority: string;
  status: string;
  effectiveFrom: string;
  effectiveTo: string;
  note: string;
}

function emptyRuleDraft(): RuleDraft {
  return {
    ruleName: '',
    shopId: '',
    logisticsMode: 'SELF_FULFILLED',
    delayHours: '48',
    templateId: '',
    abTestEnabled: false,
    avoidHolidays: true,
    holidayRegion: '',
    holidayShiftDays: '1',
    timezone: 'Asia/Shanghai',
    priority: '100',
    status: 'ACTIVE',
    effectiveFrom: '',
    effectiveTo: '',
    note: '',
  };
}

interface TemplateDraft {
  templateName: string;
  locale: string;
  status: string;
  note: string;
  variants: {
    variantCode: string;
    subject: string;
    body: string;
    weight: string;
    status: string;
  }[];
}

function emptyTemplateDraft(): TemplateDraft {
  return {
    templateName: '',
    locale: 'zh-CN',
    status: 'ACTIVE',
    note: '',
    variants: [
      { variantCode: 'A', subject: '', body: '', weight: '60', status: 'ACTIVE' },
      { variantCode: 'B', subject: '', body: '', weight: '40', status: 'ACTIVE' },
    ],
  };
}

interface HolidayDraft {
  holidayDate: string;
  holidayEndDate: string;
  holidayName: string;
  region: string;
  kind: string;
  note: string;
}

function emptyHolidayDraft(): HolidayDraft {
  return {
    holidayDate: '',
    holidayEndDate: '',
    holidayName: '',
    region: '',
    kind: 'HOLIDAY',
    note: '',
  };
}

function RuleForm({
  draft,
  setDraft,
  shops,
  templates,
}: {
  draft: RuleDraft;
  setDraft: (draft: RuleDraft) => void;
  shops: CrmReviewRuleShopView[];
  templates: CrmReviewTemplateRow[];
}) {
  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="规则名称">
          <input
            className={control}
            value={draft.ruleName}
            onChange={(e) => setDraft({ ...draft, ruleName: e.target.value })}
            placeholder="例：自发货 48 小时"
          />
        </Field>
        <Field label="适用店铺" hint="留空 = 适用于全部店铺。">
          <select
            className={control}
            value={draft.shopId}
            onChange={(e) => setDraft({ ...draft, shopId: e.target.value })}
          >
            <option value="">（全部店铺）</option>
            {shops.map((shop) => (
              <option key={shop.id} value={shop.id}>
                {shop.name} · {shop.platformCode}
              </option>
            ))}
          </select>
        </Field>
        <Field label="适用物流方式">
          <select
            className={control}
            value={draft.logisticsMode}
            onChange={(e) => setDraft({ ...draft, logisticsMode: e.target.value })}
          >
            <option value="ANY">不限</option>
            <option value="SELF_FULFILLED">自发货</option>
            <option value="PLATFORM_WAREHOUSE">平台仓</option>
          </select>
        </Field>
      </div>

      <div className="grid gap-3 md:grid-cols-4">
        <Field label="延后小时数" hint="签收后延后多久才发索评。">
          <input
            className={control}
            value={draft.delayHours}
            onChange={(e) => setDraft({ ...draft, delayHours: e.target.value })}
          />
        </Field>
        <Field label="话术模板" hint="留空 = 只定「什么时候问」，不定「怎么问」。">
          <select
            className={control}
            value={draft.templateId}
            onChange={(e) => setDraft({ ...draft, templateId: e.target.value })}
          >
            <option value="">（不绑定）</option>
            {templates.map((template) => (
              <option key={template.id} value={template.id}>
                {template.templateName}
              </option>
            ))}
          </select>
        </Field>
        <Field label="优先级" hint="同一范围内数字小的先用。">
          <input
            className={control}
            value={draft.priority}
            onChange={(e) => setDraft({ ...draft, priority: e.target.value })}
          />
        </Field>
        <Field label="状态">
          <select
            className={control}
            value={draft.status}
            onChange={(e) => setDraft({ ...draft, status: e.target.value })}
          >
            <option value="DRAFT">草稿</option>
            <option value="ACTIVE">生效中</option>
            <option value="PAUSED">已暂停</option>
          </select>
        </Field>
      </div>

      <div className="grid gap-3 md:grid-cols-4">
        <Field label="节假日区域" hint="留空 = 全部区域。">
          <input
            className={control}
            value={draft.holidayRegion}
            onChange={(e) => setDraft({ ...draft, holidayRegion: e.target.value })}
          />
        </Field>
        <Field label="命中节假日后顺延天数">
          <input
            className={control}
            value={draft.holidayShiftDays}
            onChange={(e) => setDraft({ ...draft, holidayShiftDays: e.target.value })}
          />
        </Field>
        <Field label="时区" hint="节假日按这个时区换算日界（默认东八区）。">
          <input
            className={control}
            value={draft.timezone}
            onChange={(e) => setDraft({ ...draft, timezone: e.target.value })}
          />
        </Field>
        <Field label="生效起点（可选）" hint="带时区偏移量，例如 2026-10-10T08:00:00+08:00。">
          <input
            className={control}
            value={draft.effectiveFrom}
            onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
          />
        </Field>
      </div>

      <div className="flex flex-wrap gap-4 text-copy-13">
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={draft.abTestEnabled}
            onChange={(e) => setDraft({ ...draft, abTestEnabled: e.target.checked })}
          />
          开启 A/B 测试（按权重分桶）
        </label>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={draft.avoidHolidays}
            onChange={(e) => setDraft({ ...draft, avoidHolidays: e.target.checked })}
          />
          避开节假日
        </label>
      </div>

      <Field label="备注">
        <input
          className={control}
          value={draft.note}
          onChange={(e) => setDraft({ ...draft, note: e.target.value })}
        />
      </Field>
    </div>
  );
}

function TemplateForm({
  draft,
  setDraft,
}: {
  draft: TemplateDraft;
  setDraft: (draft: TemplateDraft) => void;
}) {
  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="模板名称">
          <input
            className={control}
            value={draft.templateName}
            onChange={(e) => setDraft({ ...draft, templateName: e.target.value })}
            placeholder="例：索评标准话术"
          />
        </Field>
        <Field label="语言">
          <input
            className={control}
            value={draft.locale}
            onChange={(e) => setDraft({ ...draft, locale: e.target.value })}
          />
        </Field>
        <Field label="状态">
          <select
            className={control}
            value={draft.status}
            onChange={(e) => setDraft({ ...draft, status: e.target.value })}
          >
            <option value="ACTIVE">启用中</option>
            <option value="PAUSED">已停用</option>
          </select>
        </Field>
      </div>

      {draft.variants.map((variant, index) => (
        <div key={variant.variantCode || index} className="border-border space-y-2 rounded-md px-3 py-2">
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="变体码" hint="只能是 A 或 B。">
              <select
                className={control}
                value={variant.variantCode}
                onChange={(e) => {
                  const variants = draft.variants.map((item, i) =>
                    i === index ? { ...item, variantCode: e.target.value } : item,
                  );
                  setDraft({ ...draft, variants });
                }}
              >
                <option value="A">A</option>
                <option value="B">B</option>
              </select>
            </Field>
            <Field label="标题">
              <input
                className={control}
                value={variant.subject}
                onChange={(e) => {
                  const variants = draft.variants.map((item, i) =>
                    i === index ? { ...item, subject: e.target.value } : item,
                  );
                  setDraft({ ...draft, variants });
                }}
              />
            </Field>
            <Field label="权重（百分比）" hint="两个变体的权重决定 A / B 的分流比例。">
              <input
                className={control}
                value={variant.weight}
                onChange={(e) => {
                  const variants = draft.variants.map((item, i) =>
                    i === index ? { ...item, weight: e.target.value } : item,
                  );
                  setDraft({ ...draft, variants });
                }}
              />
            </Field>
          </div>
          <Field label="正文">
            <textarea
              className={control}
              rows={2}
              value={variant.body}
              onChange={(e) => {
                const variants = draft.variants.map((item, i) =>
                  i === index ? { ...item, body: e.target.value } : item,
                );
                setDraft({ ...draft, variants });
              }}
            />
          </Field>
        </div>
      ))}
    </div>
  );
}

function HolidayForm({
  draft,
  setDraft,
}: {
  draft: HolidayDraft;
  setDraft: (draft: HolidayDraft) => void;
}) {
  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="开始日期" hint="YYYY-MM-DD。">
          <input
            className={control}
            value={draft.holidayDate}
            onChange={(e) => setDraft({ ...draft, holidayDate: e.target.value })}
            placeholder="2026-10-01"
          />
        </Field>
        <Field label="结束日期（可选）" hint="留空 = 单日；填了 = 连休。">
          <input
            className={control}
            value={draft.holidayEndDate}
            onChange={(e) => setDraft({ ...draft, holidayEndDate: e.target.value })}
            placeholder="2026-10-03"
          />
        </Field>
        <Field label="名称">
          <input
            className={control}
            value={draft.holidayName}
            onChange={(e) => setDraft({ ...draft, holidayName: e.target.value })}
            placeholder="例：国庆"
          />
        </Field>
      </div>
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="区域" hint="留空 = 全部区域。">
          <input
            className={control}
            value={draft.region}
            onChange={(e) => setDraft({ ...draft, region: e.target.value })}
          />
        </Field>
        <Field label="类型">
          <select
            className={control}
            value={draft.kind}
            onChange={(e) => setDraft({ ...draft, kind: e.target.value })}
          >
            <option value="HOLIDAY">法定节假日</option>
            <option value="PROMOTION">大促</option>
          </select>
        </Field>
        <Field label="备注">
          <input
            className={control}
            value={draft.note}
            onChange={(e) => setDraft({ ...draft, note: e.target.value })}
          />
        </Field>
      </div>
    </div>
  );
}

/** 错误文案：优先用后端给的人话。 */
function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}
