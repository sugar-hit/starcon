'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ChangeEvent } from 'react';
import {
  AlertTriangle,
  FileText,
  Printer,
  RefreshCw,
  Search,
  ShieldCheck,
  XCircle,
} from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import type {
  LabelBatteryMark,
  LabelBindingResult,
  LabelDeclaration,
  LabelElement,
  LabelFilters,
  LabelRenderPage,
  LabelRenderPreview,
  LabelSampleValue,
  LabelTemplateCapabilities,
  LabelTemplateForm,
  LabelTemplateOverview,
  LabelTemplatePage,
  LabelTemplateRow,
} from './label-template-model';
import {
  batteryTypeLabel,
  blankBatteryMark,
  blankDeclaration,
  blankElement,
  declarationKindLabel,
  elementKindLabel,
  EMPTY_LABEL_FILTERS,
  EMPTY_LABEL_FORM,
  fetchLabelCapabilities,
  fetchLabelOverview,
  fetchLabelRenders,
  fetchLabelTemplates,
  findingTone,
  judgeLabelBinding,
  labelFormToInput,
  previewLabelTemplate,
  readinessMeta,
  removeLabelTemplate,
  sampleValuesFor,
  saveLabelTemplate,
  setLabelTemplateStatus,
  symbologyLabel,
  templateStatusLabel,
  toLabelForm,
  triggerSourceLabel,
  validateLabelForm,
  verdictMeta,
} from './label-template-model';

/** Badge 的 tone 取值（@starcon/ui Badge 有 7 种 tone，没有 variant）。 */
type Tone = 'neutral' | 'success' | 'warning' | 'danger' | 'brand' | 'info' | 'primary';

/**
 * 枚举输入：@starcon/ui **没有** Select 组件（本仓库的既有约定是用 {@link Input} + 枚举值），
 * 这里补一个带 `<datalist>` 的版本：既有下拉可选，也允许手输（现场偶尔会遇到白名单外的值，
 * 后端会给出明确理由而不是默默接受）。
 */
function EnumField({
  id,
  label,
  value,
  options,
  onChange,
  placeholder,
}: {
  id: string;
  label: string;
  value: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="text-label-13 text-foreground block">
        {label}
      </label>
      <Input
        id={id}
        list={`${id}-options`}
        value={value}
        placeholder={placeholder}
        onChange={(event: ChangeEvent<HTMLInputElement>) => onChange(event.target.value)}
      />
      <datalist id={`${id}-options`}>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </datalist>
    </div>
  );
}

/** 固定的 Y / N 开关选项。 */
function yesNo(label: (value: string) => string): Array<{ value: string; label: string }> {
  return [
    { value: 'Y', label: label('Y') },
    { value: 'N', label: label('N') },
  ];
}

/**
 * 10.2.2.2 面单模板工作区。
 *
 * <p>四个区块按**现场判断顺序**排（照抄 10.1.1.2 的同一个取舍）：
 * ① 能力与缺口 → ② 渠道规则绑定核对 → ③ 模板清单与编辑 → ④ 真渲染预览。
 *
 * <p>为什么绑定核对排在清单前面：蓝图加粗句是「附加声明（如电池、液体）漏打会导致清关问题，
 * 模板配置要与渠道规则强绑定」。现场真正的问题从来不是「我有哪些模板」，
 * 而是「这一票带电池，我该用哪张、缺哪个声明」—— 所以先给那一个答案。
 *
 * <p>🔴 三个「不假装」的地方：
 * <ul>
 *   <li>绑定结论来自服务端 {@code POST /binding}，页面不自己比对渠道要求；
 *   <li>预览展示的是后端**真渲染**的 ZPL 指令流与逐条 findings，不是浏览器画的示意图；
 *   <li>打印下发（打印机 / 任务队列）标为 <b>available=false</b> 并点名 8.3.4.2 ——
 *       本模块只到模板与渲染产物，出纸是 8.3.4.2 的主权。
 * </ul>
 */
export function LabelTemplateWorkspace() {
  const [capabilities, setCapabilities] = useState<LabelTemplateCapabilities | null>(null);
  const [overview, setOverview] = useState<LabelTemplateOverview | null>(null);
  const [page, setPage] = useState<LabelTemplatePage | null>(null);
  const [filters, setFilters] = useState<LabelFilters>(EMPTY_LABEL_FILTERS);
  const [selected, setSelected] = useState<LabelTemplateRow | null>(null);
  const [form, setForm] = useState<LabelTemplateForm>(EMPTY_LABEL_FORM);
  const [errors, setErrors] = useState<string[]>([]);
  const [preview, setPreview] = useState<LabelRenderPreview | null>(null);
  const [sample, setSample] = useState<LabelSampleValue[]>([]);
  const [renders, setRenders] = useState<LabelRenderPage | null>(null);
  const [binding, setBinding] = useState<LabelBindingResult | null>(null);
  const [bindingInput, setBindingInput] = useState({
    countryCode: 'DE',
    categories: '',
    containsBattery: false,
    containsLiquid: false,
    containsMagnet: false,
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, stats, list] = await Promise.all([
        fetchLabelCapabilities(),
        fetchLabelOverview(),
        fetchLabelTemplates(filters),
      ]);
      setCapabilities(caps);
      setOverview(stats);
      setPage(list);
    } catch (e) {
      setError(e instanceof Error ? e.message : '面单模板加载失败');
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  // 选中一行时把详情与预览样例一起准备好（样例按元素形状自动填，不用从空白开始）
  const select = useCallback((row: LabelTemplateRow) => {
    setSelected(row);
    setForm(toLabelForm(row));
    setErrors([]);
    setPreview(null);
    setRenders(null);
    setSample(sampleValuesFor(row.elements));
  }, []);

  const patch = useCallback(
    <K extends keyof LabelTemplateForm>(key: K, value: LabelTemplateForm[K]) => {
      setForm((previous) => ({ ...previous, [key]: value }));
    },
    [],
  );

  const patchElement = useCallback(
    (index: number, key: keyof LabelElement, value: string | number | boolean | null) => {
      setForm((previous) => ({
        ...previous,
        elements: previous.elements.map((element, position) =>
          position === index ? { ...element, [key]: value } : element,
        ),
      }));
    },
    [],
  );

  const patchDeclaration = useCallback(
    (index: number, key: keyof LabelDeclaration, value: string | boolean) => {
      setForm((previous) => ({
        ...previous,
        declarations: previous.declarations.map((declaration, position) =>
          position === index ? { ...declaration, [key]: value } : declaration,
        ),
      }));
    },
    [],
  );

  const patchBattery = useCallback(
    (index: number, key: keyof LabelBatteryMark, value: string | number | boolean | null) => {
      setForm((previous) => ({
        ...previous,
        batteryMarks: previous.batteryMarks.map((mark, position) =>
          position === index ? { ...mark, [key]: value } : mark,
        ),
      }));
    },
    [],
  );

  const submit = useCallback(async () => {
    const found = validateLabelForm(form);
    setErrors(found);
    if (found.length > 0) return;
    setSaving(true);
    setError(null);
    try {
      const row = await saveLabelTemplate(labelFormToInput(form));
      setNotice(`已保存「${row.templateCode}」：${row.effectiveReadinessMessage}`);
      await load();
      select(row);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  }, [form, load, select]);

  const runPreview = useCallback(
    async (persist: boolean) => {
      if (!selected) return;
      setBusy(true);
      setError(null);
      try {
        const result = await previewLabelTemplate(selected.id, sample, null, persist);
        setPreview(result);
        setRenders(await fetchLabelRenders(selected.id, 0, 20));
      } catch (e) {
        setError(e instanceof Error ? e.message : '渲染预览失败');
      } finally {
        setBusy(false);
      }
    },
    [sample, selected],
  );

  const runBinding = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      setBinding(
        await judgeLabelBinding({
          channelId: selected ? selected.channelId : null,
          countryCode: bindingInput.countryCode.toUpperCase(),
          categoryCodes: bindingInput.categories
            .split(/[,，\s]+/)
            .map((item) => item.trim())
            .filter(Boolean),
          containsBattery: bindingInput.containsBattery,
          containsLiquid: bindingInput.containsLiquid,
          containsMagnet: bindingInput.containsMagnet,
          templateIds: null,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '渠道规则绑定核对失败');
    } finally {
      setBusy(false);
    }
  }, [bindingInput, selected]);

  const changeStatus = useCallback(
    async (status: string) => {
      if (!selected) return;
      setBusy(true);
      setError(null);
      try {
        const row = await setLabelTemplateStatus(selected.id, status, selected.version);
        setNotice(
          status === 'ACTIVE'
            ? `已启用「${row.templateCode}」，启用前的渲染留在了渲染历史里`
            : `「${row.templateCode}」已切到 ${templateStatusLabel(status)}`,
        );
        await load();
        select(row);
      } catch (e) {
        setError(e instanceof Error ? e.message : '状态切换失败');
      } finally {
        setBusy(false);
      }
    },
    [load, select, selected],
  );

  const drop = useCallback(async () => {
    if (!selected) return;
    setBusy(true);
    setError(null);
    try {
      await removeLabelTemplate(selected.id);
      setSelected(null);
      setForm(EMPTY_LABEL_FORM);
      setPreview(null);
      setNotice(`已撤下「${selected.templateCode}」`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '撤下失败');
    } finally {
      setBusy(false);
    }
  }, [load, selected]);

  const dimensions = capabilities?.dimensions ?? [];
  const blocked = dimensions.filter((dimension) => !dimension.available);
  const rows = page?.items ?? [];
  const elementKeys = useMemo(
    () => form.elements.map((element) => element.elementKey).filter(Boolean),
    [form.elements],
  );
  const languageCodes = capabilities?.languageCodes ?? ['de-DE'];
  const symbologies = capabilities?.symbologies ?? ['CODE128_B'];
  const elementKinds = capabilities?.elementKinds ?? ['TEXT'];
  const declarationKinds = capabilities?.declarationKinds ?? ['BATTERY'];
  const batteryTypes = capabilities?.batteryTypes ?? ['LITHIUM_ION'];

  return (
    <div className="space-y-6">
      <PageHeader
        title="面单模板"
        description="维护面单模板与打印规格（尺寸、条码类型、附加声明、电池标识、语言版本），支持按渠道与国家配置。"
      />

      {error && (
        <Notice tone="danger" title="操作失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      )}

      {/* ① 能力与缺口 —— 未交付的段显式说清楚，不预告空页入口 */}
      <SectionCard title="能力与缺口">
        <div className="grid gap-3 md:grid-cols-4">
          <StatCard label="模板总数" value={overview?.summary.total ?? 0} />
          <StatCard label="可用" value={overview?.summary.ready ?? 0} />
          <StatCard label="启用中" value={overview?.summary.active ?? 0} />
          <StatCard
            label="声明漏打风险"
            value={overview?.summary.declarationGap ?? 0}
            polarity="negative"
          />
        </div>
        {capabilities && (
          <div className="mt-4 space-y-2">
            <p className="text-copy-13 text-muted-foreground">{capabilities.bindingNote}</p>
            <ul className="space-y-1">
              {blocked.map((dimension) => (
                <li key={dimension.dimension} className="text-copy-13 text-muted-foreground">
                  <Badge tone="warning">缺 {dimension.ownerModule}</Badge>{' '}
                  {dimension.description} —— {dimension.impact}
                </li>
              ))}
            </ul>
            <p className="text-copy-13 text-muted-foreground">{capabilities.boundaryNote}</p>
          </div>
        )}
      </SectionCard>

      {/* ② 渠道规则绑定核对 —— 现场真正的问题不是「我有哪些模板」 */}
      <SectionCard title="渠道规则绑定核对">
        <p className="text-copy-13 text-muted-foreground mb-3">
          结论由服务端给出（POST /binding），页面不自己比对渠道要求：附加声明漏打会导致清关问题，
          规则只有一份。渠道禁了本票货的品类时，结论对所有模板一致 —— 换模板救不了承运商拒收。
        </p>
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="bind-country" label="目的国">
            <Input
              value={bindingInput.countryCode}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                setBindingInput({ ...bindingInput, countryCode: event.target.value })
              }
              placeholder="DE"
            />
          </FormField>
          <FormField id="bind-categories" label="品类编码（逗号分隔）">
            <Input
              value={bindingInput.categories}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                setBindingInput({ ...bindingInput, categories: event.target.value })
              }
              placeholder="HOME_GOODS"
            />
          </FormField>
          <div className="space-y-2">
            <span className="text-label-13 text-foreground block">货件属性</span>
            <div className="flex gap-3 pt-1">
              {(
                [
                  ['containsBattery', '带电'],
                  ['containsLiquid', '带液'],
                  ['containsMagnet', '带磁'],
                ] as const
              ).map(([key, label]) => (
                <label key={key} className="flex items-center gap-1 text-copy-13">
                  <input
                    type="checkbox"
                    checked={bindingInput[key]}
                    onChange={(event: ChangeEvent<HTMLInputElement>) =>
                      setBindingInput({ ...bindingInput, [key]: event.target.checked })
                    }
                  />
                  {label}
                </label>
              ))}
            </div>
          </div>
          <div className="flex items-end">
            <Button onClick={() => void runBinding()} disabled={busy}>
              <ShieldCheck className="mr-2 h-4 w-4" />
              核对
            </Button>
          </div>
        </div>

        {binding && (
          <div className="mt-4 space-y-3">
            <div className="flex gap-2">
              <Badge tone="success">PASS {binding.pass}</Badge>
              <Badge tone="warning">REVIEW {binding.review}</Badge>
              <Badge tone="danger">BLOCK {binding.block}</Badge>
            </div>
            {binding.verdicts.length === 0 && (
              <EmptyState
                title="这个渠道该国家下还没有面单模板"
                description="先去下面登记一张，再回来看绑定结论。"
              />
            )}
            {binding.verdicts.map((verdict) => (
              <div key={verdict.templateId} className="rounded border p-3">
                <div className="flex items-center gap-2">
                  <Badge tone={verdictMeta(verdict.verdict).variant as Tone}>
                    {verdictMeta(verdict.verdict).label}
                  </Badge>
                  <span className="font-mono text-copy-13">{verdict.templateCode}</span>
                  <span className="text-copy-13 text-muted-foreground">{verdict.templateName}</span>
                </div>
                {verdict.missingDeclarations.length > 0 && (
                  <p className="text-copy-13 text-destructive mt-2">
                    🔴 渠道要求「{verdict.missingDeclarations.join('、')}」声明，而这张模板没有登记
                  </p>
                )}
                {verdict.missingBatteryMarks.length > 0 && (
                  <p className="text-copy-13 text-destructive mt-2">
                    🔴 只有声明没有电池标识：只印一行「含电池」过不了清关
                  </p>
                )}
                {verdict.redundantDeclarations.length > 0 && (
                  <p className="text-copy-13 text-muted-foreground mt-2">
                    本票用不到、但模板印了：{verdict.redundantDeclarations.join('、')}
                  </p>
                )}
                <ul className="mt-2 space-y-1">
                  {verdict.findings.map((finding, position) => (
                    <li key={position} className="text-copy-13 text-muted-foreground">
                      <Badge tone={findingTone(finding.severity).variant as Tone}>
                        {findingTone(finding.severity).label}
                      </Badge>{' '}
                      {finding.message}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ③ 模板清单与编辑 */}
      <Tabs defaultValue="list">
        <TabsList>
          <TabsTrigger value="list">模板清单</TabsTrigger>
          <TabsTrigger value="edit">
            {form.templateId ? `编辑 ${form.templateCode}` : '登记新模板'}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="list">
          <SectionCard
            title="面单模板"
            extra={
              <>
                <Button variant="secondary" onClick={() => void load()}>
                  <RefreshCw className="mr-2 h-4 w-4" />
                  刷新
                </Button>
                <Button
                  variant="secondary"
                  onClick={() => {
                    setForm(EMPTY_LABEL_FORM);
                    setSelected(null);
                    setPreview(null);
                    setErrors([]);
                  }}
                >
                  <FileText className="mr-2 h-4 w-4" />
                  新建
                </Button>
              </>
            }
          >
            <div className="mb-4 grid gap-3 md:grid-cols-5">
              <FormField id="label-kw" label="关键字">
                <Input
                  value={filters.keyword}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    setFilters({ ...filters, keyword: event.target.value, page: 0 })
                  }
                  placeholder="模板代码或名称"
                />
              </FormField>
              <FormField id="label-country" label="国家">
                <Input
                  value={filters.countryCode}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    setFilters({ ...filters, countryCode: event.target.value, page: 0 })
                  }
                  placeholder="DE"
                />
              </FormField>
              <EnumField
                id="label-status"
                label="状态"
                value={filters.status}
                options={[
                  { value: '', label: '全部' },
                  { value: 'DRAFT', label: templateStatusLabel('DRAFT') },
                  { value: 'ACTIVE', label: templateStatusLabel('ACTIVE') },
                  { value: 'ARCHIVED', label: templateStatusLabel('ARCHIVED') },
                ]}
                onChange={(value) => setFilters({ ...filters, status: value, page: 0 })}
              />
              <EnumField
                id="label-readiness"
                label="可用性"
                value={filters.readiness}
                options={[
                  { value: '', label: '全部' },
                  ...(['READY', 'NO_LAYOUT', 'NO_BARCODE', 'DECLARATION_GAP', 'NO_CHANNEL'] as const).map(
                    (code) => ({ value: code, label: readinessMeta(code).label }),
                  ),
                ]}
                onChange={(value) => setFilters({ ...filters, readiness: value, page: 0 })}
              />
              <div className="flex items-end">
                <Button onClick={() => void load()}>
                  <Search className="mr-2 h-4 w-4" />
                  查询
                </Button>
              </div>
            </div>

            {loading && <p className="text-copy-13 text-muted-foreground">加载中…</p>}
            {!loading && rows.length === 0 && (
              <EmptyState
                title="还没有面单模板"
                description="面单模板挂在渠道配置之下：先在 /logistics/channels 登记渠道，再来这里按渠道与国家排版。"
              />
            )}
            {!loading && rows.length > 0 && (
              <>
                <div className="text-copy-13 text-muted-foreground mb-3">
                  共 {page?.total} 张（汇总口径覆盖筛选后的全部行，不随分页变化）：可用{' '}
                  {page?.summary.ready} · 声明漏打风险 {page?.summary.declarationGap} · 渠道不可用{' '}
                  {page?.summary.noChannel}
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-copy-13">
                    <thead>
                      <tr className="border-b text-left">
                        <th className="p-2">模板</th>
                        <th className="p-2">渠道</th>
                        <th className="p-2">国家 / 语言</th>
                        <th className="p-2">尺寸 / 条码</th>
                        <th className="p-2">状态</th>
                        <th className="p-2">可用性</th>
                        <th className="p-2">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => {
                        const tone = readinessMeta(row.effectiveReadiness);
                        return (
                          <tr key={row.id} className="border-b">
                            <td className="p-2">
                              <div className="font-mono">{row.templateCode}</div>
                              <div className="text-copy-12 text-muted-foreground">{row.templateName}</div>
                            </td>
                            <td className="p-2">
                              <div className="font-mono text-copy-12">{row.channelCode}</div>
                              <div className="text-copy-12 text-muted-foreground">{row.carrierName}</div>
                            </td>
                            <td className="p-2 text-copy-12">
                              {row.countryCode} / {row.languageCode}
                            </td>
                            <td className="p-2 text-copy-12">
                              {row.paperWidthMm} × {row.paperHeightMm} mm @ {row.dpi}dpi
                              <div className="text-muted-foreground">
                                {symbologyLabel(row.primaryBarcodeSymbology)}
                              </div>
                            </td>
                            <td className="p-2">
                              <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                                {templateStatusLabel(row.status)}
                              </Badge>
                            </td>
                            <td className="p-2">
                              <Badge tone={tone.variant as Tone}>{tone.label}</Badge>
                              <div className="text-copy-12 text-muted-foreground mt-1 max-w-xs">
                                {row.effectiveReadinessMessage}
                              </div>
                            </td>
                            <td className="p-2">
                              <Button size="sm" onClick={() => select(row)}>
                                选中
                              </Button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                <div className="mt-3 flex items-center gap-2">
                  <Button
                    variant="secondary"
                    disabled={(page?.page ?? 0) === 0}
                    onClick={() => setFilters({ ...filters, page: (filters.page ?? 1) - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-copy-13 text-muted-foreground">
                    第 {(page?.page ?? 0) + 1} / {Math.max(page?.totalPages ?? 1, 1)} 页
                  </span>
                  <Button
                    variant="secondary"
                    disabled={(page?.page ?? 0) + 1 >= (page?.totalPages ?? 1)}
                    onClick={() => setFilters({ ...filters, page: (filters.page ?? 0) + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </>
            )}
          </SectionCard>
        </TabsContent>

        <TabsContent value="edit">
          <SectionCard
            title={form.templateId ? `编辑 ${form.templateCode}` : '登记新面单模板'}
            description="尺寸一律按毫米填，单位在字段名上写死；毫米到打印点的换算只在后端做一遍。"
          >
            {errors.length > 0 && (
              <Notice tone="danger" title="先修掉这几处再提交">
                <ul className="list-disc pl-4">
                  {errors.map((message) => (
                    <li key={message}>{message}</li>
                  ))}
                </ul>
              </Notice>
            )}

            <div className="mt-4 grid gap-3 md:grid-cols-4">
              <FormField id="tpl-channel" label="所属渠道 ID">
                <Input
                  value={form.channelId}
                  disabled={form.templateId !== null}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('channelId', event.target.value)
                  }
                  placeholder="渠道配置 UUID"
                />
              </FormField>
              <FormField id="tpl-code" label="模板代码（建后不可改）">
                <Input
                  value={form.templateCode}
                  disabled={form.templateId !== null}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('templateCode', event.target.value)
                  }
                />
              </FormField>
              <FormField id="tpl-name" label="模板名称">
                <Input
                  value={form.templateName}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('templateName', event.target.value)
                  }
                />
              </FormField>
              <FormField id="tpl-country" label="国家（两位大写）">
                <Input
                  value={form.countryCode}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('countryCode', event.target.value)
                  }
                />
              </FormField>
                <EnumField
                id="tpl-lang"
              label="语言版本"
              value={form.languageCode}
                options={languageCodes.map((code) => ({ value: code, label: code }))}
                onChange={(value) => patch('languageCode', value)}
              />
              <FormField id="tpl-width" label="纸张宽（mm）">
                <Input
                  value={form.paperWidthMm}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('paperWidthMm', event.target.value)
                  }
                />
              </FormField>
              <FormField id="tpl-height" label="纸张高（mm）">
                <Input
                  value={form.paperHeightMm}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('paperHeightMm', event.target.value)
                  }
                />
              </FormField>
              <EnumField
                id="tpl-dpi"
                label="DPI"
                value={form.dpi}
                options={[152, 203, 300, 600].map((code) => ({
                  value: String(code),
                  label: `${code} dpi`,
                }))}
                onChange={(value) => patch('dpi', value)}
              />
              <EnumField
                id="tpl-orient"
                label="方向"
                value={form.orientation}
                options={[
                  { value: 'PORTRAIT', label: '纵向' },
                  { value: 'LANDSCAPE', label: '横向' },
                ]}
                onChange={(value) => patch('orientation', value)}
              />
              <EnumField
                id="tpl-symbology"
                label="主条码类型"
                value={form.primaryBarcodeSymbology}
                options={symbologies.map((code) => ({ value: code, label: symbologyLabel(code) }))}
                onChange={(value) => patch('primaryBarcodeSymbology', value)}
              />
              <FormField id="tpl-barcode-height" label="主条码高（mm）">
                <Input
                  value={form.primaryBarcodeHeightMm}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('primaryBarcodeHeightMm', event.target.value)
                  }
                />
              </FormField>
              <FormField id="tpl-offset-x" label="零点校准 X（mm）">
                <Input
                  value={form.originOffsetXMm}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('originOffsetXMm', event.target.value)
                  }
                />
              </FormField>
              <FormField id="tpl-offset-y" label="零点校准 Y（mm）">
                <Input
                  value={form.originOffsetYMm}
                  onChange={(event: ChangeEvent<HTMLInputElement>) =>
                    patch('originOffsetYMm', event.target.value)
                  }
                />
              </FormField>
              <FormField id="tpl-note" label="备注">
                <Input
                  value={form.note}
                  onChange={(event: ChangeEvent<HTMLInputElement>) => patch('note', event.target.value)}
                />
              </FormField>
            </div>

            {/* 打印元素 */}
            <div className="mt-6">
              <div className="mb-2 flex items-center justify-between">
                <h3 className="text-label-13 font-medium">
                  打印元素（位置与尺寸，单位 mm）
                </h3>
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() =>
                    patch('elements', [...form.elements, blankElement(form.elements.length)])
                  }
                >
                  加一个打印位
                </Button>
              </div>
              {form.elements.length === 0 && (
                <EmptyState
                  title="还没有打印位"
                  description="一条打印位都没有的模板印出来是一张白纸（齐备度会是「未排版」）。"
                />
              )}
              {form.elements.map((element, index) => (
                <div key={index} className="mb-2 rounded border p-3">
                  <div className="grid gap-2 md:grid-cols-3">
                    <FormField id={`el-${index}-key`} label="编码">
                      <Input
                        value={element.elementKey}
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          patchElement(index, 'elementKey', event.target.value)
                        }
                      />
                    </FormField>
                      <EnumField
                      id={`el-${index}-kind`}
                      label="类型"
                      value={element.elementKind}
                      options={elementKinds.map((code) => ({ value: code, label: elementKindLabel(code) }))}
                      onChange={(value) => patchElement(index, 'elementKind', value)}
                    />
                    <FormField id={`el-${index}-sym`} label="条码类型（非条码位留空）">
                      <Input
                        value={element.symbology ?? ''}
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          patchElement(
                            index,
                            'symbology',
                            event.target.value === '' ? null : event.target.value,
                          )
                        }
                      />
                    </FormField>
                    <FormField id={`el-${index}-xy`} label="X / Y（mm）">
                      <div className="flex gap-2">
                        <Input
                          value={String(element.xMm)}
                          onChange={(event: ChangeEvent<HTMLInputElement>) =>
                            patchElement(index, 'xMm', Number(event.target.value))
                          }
                        />
                        <Input
                          value={String(element.yMm)}
                          onChange={(event: ChangeEvent<HTMLInputElement>) =>
                            patchElement(index, 'yMm', Number(event.target.value))
                          }
                        />
                      </div>
                    </FormField>
                    <FormField id={`el-${index}-wh`} label="宽 / 高（mm）">
                      <div className="flex gap-2">
                        <Input
                          value={String(element.wMm)}
                          onChange={(event: ChangeEvent<HTMLInputElement>) =>
                            patchElement(index, 'wMm', Number(event.target.value))
                          }
                        />
                        <Input
                          value={String(element.hMm)}
                          onChange={(event: ChangeEvent<HTMLInputElement>) =>
                            patchElement(index, 'hMm', Number(event.target.value))
                          }
                        />
                      </div>
                    </FormField>
                    <div className="flex items-end">
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={`删除打印位 ${element.elementKey || index}`}
                        onClick={() =>
                          patch(
                            'elements',
                            form.elements.filter((_, position) => position !== index),
                          )
                        }
                      >
                        <XCircle className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                </div>
              ))}
            </div>

            {/* 附加声明 */}
            <div className="mt-6">
              <div className="mb-2 flex items-center justify-between">
                <h3 className="text-label-13 font-medium">
                  附加声明（必须挂在某个打印位上，否则无法证明它印出来了）
                </h3>
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => patch('declarations', [...form.declarations, blankDeclaration()])}
                >
                  加一条声明
                </Button>
              </div>
              {form.declarations.length === 0 && (
                <p className="text-copy-13 text-muted-foreground">
                  没有声明。渠道一旦要求电池或液体声明，绑定核对会 BLOCK。
                </p>
              )}
              {form.declarations.map((declaration, index) => (
                <div key={index} className="mb-2 rounded border p-3">
                  <div className="grid gap-2 md:grid-cols-3">
                      <EnumField
                      id={`dec-${index}-kind`}
                      label="种类"
                      value={declaration.declarationKind}
                      options={declarationKinds.map((code) => ({ value: code, label: declarationKindLabel(code) }))}
                      onChange={(value) => patchDeclaration(index, 'declarationKind', value)}
                    />
                    <FormField id={`dec-${index}-code`} label="声明代码（如 UN3481）">
                      <Input
                        value={declaration.declarationCode}
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          patchDeclaration(index, 'declarationCode', event.target.value)
                        }
                      />
                    </FormField>
                    <FormField id={`dec-${index}-text`} label="印面文字">
                      <Input
                        value={declaration.displayText}
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          patchDeclaration(index, 'displayText', event.target.value)
                        }
                      />
                    </FormField>
                      <EnumField
                      id={`dec-${index}-anchor`}
                      label="印在哪个打印位"
                      value={declaration.elementKey}
                      options={elementKeys.map((key) => ({ value: key, label: key }))}
                      onChange={(value) => patchDeclaration(index, 'elementKey', value)}
                    />
                      <EnumField
                      id={`dec-${index}-mandatory`}
                      label="强制（缺了不许启用）"
                      value={declaration.mandatory ? 'Y' : 'N'}
                      options={yesNo((value) => (value === 'Y' ? '强制（缺了不许启用）' : '非强制'))}
                      onChange={(value) => patchDeclaration(index, 'mandatory', value === 'Y')}
                    />
                    <div className="flex items-end">
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={`删除声明 ${declaration.declarationKind}`}
                        onClick={() =>
                          patch(
                            'declarations',
                            form.declarations.filter((_, position) => position !== index),
                          )
                        }
                      >
                        <XCircle className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                </div>
              ))}
            </div>

            {/* 电池标识 */}
            <div className="mt-6">
              <div className="mb-2 flex items-center justify-between">
                <h3 className="text-label-13 font-medium">
                  电池标识（瓦时 / UN38.3 —— 缺了会在机场被扣下）
                </h3>
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() =>
                    patch('batteryMarks', [...form.batteryMarks, blankBatteryMark()])
                  }
                >
                  加一条电池标识
                </Button>
              </div>
              {form.batteryMarks.length === 0 && (
                <p className="text-copy-13 text-muted-foreground">
                  没有电池标识。只有声明没有标识，海关要的是后者。
                </p>
              )}
              {form.batteryMarks.map((mark, index) => (
                <div key={index} className="mb-2 rounded border p-3">
                  <div className="grid gap-2 md:grid-cols-3">
                      <EnumField
                      id={`bat-${index}-type`}
                      label="电池类型"
                      value={mark.batteryType}
                      options={batteryTypes.map((code) => ({ value: code, label: batteryTypeLabel(code) }))}
                      onChange={(value) => patchBattery(index, 'batteryType', value)}
                    />
                    <FormField id={`bat-${index}-wh`} label="瓦时 Wh">
                      <Input
                        value={mark.wattHours === null ? '' : String(mark.wattHours)}
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          patchBattery(
                            index,
                            'wattHours',
                            event.target.value === '' ? null : Number(event.target.value),
                          )
                        }
                      />
                    </FormField>
                    <FormField id={`bat-${index}-weight`} label="净重 kg">
                      <Input
                        value={mark.batteryWeightKg === null ? '' : String(mark.batteryWeightKg)}
                        onChange={(event: ChangeEvent<HTMLInputElement>) =>
                          patchBattery(
                            index,
                            'batteryWeightKg',
                            event.target.value === '' ? null : Number(event.target.value),
                          )
                        }
                      />
                    </FormField>
                      <EnumField
                      id={`bat-${index}-un38`}
                      label="UN38.3 测试摘要"
                      value={mark.un38_3Attested ? 'Y' : 'N'}
                      options={yesNo((value) => (value === 'Y' ? '已提供' : '未提供'))}
                      onChange={(value) => patchBattery(index, 'un38_3Attested', value === 'Y')}
                    />
                      <EnumField
                      id={`bat-${index}-anchor`}
                      label="印在哪个打印位"
                      value={mark.elementKey}
                      options={elementKeys.map((key) => ({ value: key, label: key }))}
                      onChange={(value) => patchBattery(index, 'elementKey', value)}
                    />
                    <div className="flex items-end">
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={`删除电池标识 ${mark.batteryType}`}
                        onClick={() =>
                          patch(
                            'batteryMarks',
                            form.batteryMarks.filter((_, position) => position !== index),
                          )
                        }
                      >
                        <XCircle className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                </div>
              ))}
            </div>

            <div className="mt-6 flex gap-2">
              <Button onClick={() => void submit()} disabled={saving}>
                保存模板
              </Button>
              {selected && selected.status !== 'ACTIVE' && (
                <Button
                  variant="secondary"
                  disabled={busy || !selected.activatable}
                  onClick={() => void changeStatus('ACTIVE')}
                >
                  启用（先渲染一遍并留证）
                </Button>
              )}
              {selected && selected.status === 'ACTIVE' && (
                <Button
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void changeStatus('DRAFT')}
                >
                  停用
                </Button>
              )}
              {selected && (
                <Button variant="destructive" disabled={busy} onClick={() => void drop()}>
                  撤下
                </Button>
              )}
            </div>
            {selected && !selected.activatable && (
              <p className="text-copy-13 text-muted-foreground mt-2">
                启用按钮是灰的：{selected.effectiveReadinessMessage}
              </p>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>

      {/* ④ 真渲染预览 */}
      {selected && (
        <SectionCard
          title={`渲染预览 · ${selected.templateCode}`}
          description="下面是后端真渲染出来的 ZPL 指令流（含真条码编码与校验位），不是浏览器画的示意图。"
          extra={
            <Button onClick={() => void runPreview(true)} disabled={busy}>
              <Printer className="mr-2 h-4 w-4" />
              渲染并留证
            </Button>
          }
        >
          <div className="grid gap-2 md:grid-cols-3">
            {sample.map((value, index) => (
              <FormField id={`sample-${index}`} key={value.elementKey} label={value.elementKey}>
                <Input
                  value={value.value}
                  onChange={(event: ChangeEvent<HTMLInputElement>) => {
                    const next = [...sample];
                    next[index] = { elementKey: value.elementKey, value: event.target.value };
                    setSample(next);
                  }}
                />
              </FormField>
            ))}
          </div>
          <Button
            className="mt-3"
            variant="secondary"
            disabled={busy}
            onClick={() => void runPreview(false)}
          >
            只渲染不留记录
          </Button>

          {preview && (
            <div className="mt-4 space-y-3">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={verdictMeta(preview.verdict).variant as Tone}>
                  {verdictMeta(preview.verdict).label}
                </Badge>
                <span className="text-copy-13 text-muted-foreground">{preview.verdictMessage}</span>
                {preview.blockCount > 0 && <Badge tone="danger">阻断 {preview.blockCount}</Badge>}
                {preview.reviewCount > 0 && (
                  <Badge tone="warning">需确认 {preview.reviewCount}</Badge>
                )}
              </div>
              {preview.findings.length > 0 && (
                <ul className="space-y-1">
                  {preview.findings.map((finding, position) => (
                    <li key={position} className="text-copy-13">
                      <Badge tone={findingTone(finding.severity).variant as Tone}>
                        {findingTone(finding.severity).label}
                      </Badge>{' '}
                      <span className="font-mono text-copy-12 text-muted-foreground">
                        {finding.code}
                      </span>{' '}
                      {finding.message}
                    </li>
                  ))}
                </ul>
              )}
              <dl className="grid gap-1 text-copy-13 text-muted-foreground md:grid-cols-2">
                <div>
                  条码类型：{symbologyLabel(preview.symbology)} · 编码值：
                  <span className="font-mono">{preview.encodedValue || '（未编码）'}</span>
                </div>
                <div className="truncate">
                  模块序列：<span className="font-mono">{preview.barcodeModules || '—'}</span>
                </div>
                <div className="truncate md:col-span-2">
                  内容校验和（sha256）：<span className="font-mono">{preview.contentSha256}</span>
                </div>
              </dl>
              <pre className="max-h-72 overflow-auto rounded bg-muted p-3 text-copy-12">
                {preview.zplContent}
              </pre>
              <p className="text-copy-13 text-muted-foreground">{preview.previewNote}</p>
            </div>
          )}

          {renders && renders.items.length > 0 && (
            <div className="mt-6">
              <h3 className="text-label-13 mb-2 flex items-center gap-2 font-medium">
                <AlertTriangle className="h-4 w-4" />
                渲染历史（启用前的证据链）
              </h3>
              <table className="w-full text-copy-13">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-2">触发</th>
                    <th className="p-2">结论</th>
                    <th className="p-2">条码</th>
                    <th className="p-2">时间</th>
                    <th className="p-2">校验和</th>
                  </tr>
                </thead>
                <tbody>
                  {renders.items.map((item) => (
                    <tr key={item.id} className="border-b">
                      <td className="p-2">{triggerSourceLabel(item.triggerSource)}</td>
                      <td className="p-2">
                        <Badge tone={verdictMeta(item.verdict).variant as Tone}>
                          {verdictMeta(item.verdict).label}
                        </Badge>
                      </td>
                      <td className="p-2">{symbologyLabel(item.symbology)}</td>
                      <td className="p-2 text-copy-12">{item.renderedAt}</td>
                      <td className="truncate font-mono text-copy-12">{item.contentSha256}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      )}
    </div>
  );
}
