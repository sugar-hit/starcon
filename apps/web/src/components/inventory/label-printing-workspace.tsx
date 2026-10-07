'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { FileText, Printer, RefreshCw, ScrollText, Tag } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  LabelPrintCapabilities,
  LabelPrintDevice,
  LabelPrintJob,
  LabelPrintJobDetail,
  LabelPrintJobPage,
  LabelPrintLog,
  LabelPrintLogPage,
  LabelPrintTarget,
  LabelPrintTargetPage,
  LabelPrintTemplate,
  LabelPrintTemplatePage,
  LabelReprintRule,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  cancelJob,
  contentUrl,
  createJob,
  errorText,
  executeJob,
  fetchDevices,
  fetchJob,
  fetchJobs,
  fetchLogs,
  fetchPrintCapabilities,
  fetchReprintRules,
  fetchTargetHistory,
  fetchTargets,
  fetchTemplates,
  formatMoment,
  jobStatusLabel,
  jobStatusOptions,
  jobStatusTone,
  limitText,
  objectTypeLabel,
  objectTypeOptions,
  registerTarget,
  remainingText,
  upsertReprintRule,
  verdictLabel,
  verdictTone,
  warningText,
  weightText,
} from './label-printing-model';
import { useIdentity } from '../iam/identity-shell';

type View = 'TARGETS' | 'JOBS' | 'LOGS' | 'TEMPLATES' | 'RULES';

/**
 * 8.3.4.2 面单与标签打印工作区。
 *
 * <p>蓝图：「打印面单、装箱单、SKU 条码、库位标签、箱唛，支持批量打印、模板配置、
 * 打印次数记录（平台对重复打印有要求）。」输出：「打印任务记录 + 打印内容（PDF/指令流）；
 * 打印日志。」
 *
 * <p>界面纪律（每一条都对应一个会被误操作的坑）：
 *
 * <ul>
 *   <li>**打印不是无副作用的按钮**。点一次就是这张面单的第 N 次打印，N 进库、
 *       会被平台判违规。所以每一行都要显示「第几次 / 还剩几次 / 判据是什么」，
 *       不能让人点了才知道。
 *   <li>**超限默认拦下来**。选中超限对象后批量打印按钮会说明原因并要求勾「强制重打 +
 *       填理由」，理由随任务留痕 —— 不是打个警告让人点忽略绕过去。
 *   <li>**不限次 ≠ 上限 0 次**。没有配规则的平台显示「不限次」，仍然计数、仍然留凭据。
 *   <li>**面单数据来自调用方**这件事要显示出来（upstreamNote），不然会被理解成
 *       系统已经接了物流。下载的是渲染内容，不是图片占位。
 * </ul>
 */
export function LabelPrintingWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.create');
  const admin = session.permissions.includes('warehouse.inventory.update');

  const [view, setView] = useState<View>('TARGETS');
  const [capabilities, setCapabilities] = useState<LabelPrintCapabilities | null>(null);
  const [targets, setTargets] = useState<LabelPrintTargetPage | null>(null);
  const [jobs, setJobs] = useState<LabelPrintJobPage | null>(null);
  const [logs, setLogs] = useState<LabelPrintLogPage | null>(null);
  const [templates, setTemplates] = useState<LabelPrintTemplatePage | null>(null);
  const [rules, setRules] = useState<LabelReprintRule[]>([]);
  const [devices, setDevices] = useState<LabelPrintDevice[]>([]);
  const [detail, setDetail] = useState<LabelPrintJobDetail | null>(null);
  const [history, setHistory] = useState<{ ref: string; rows: LabelPrintLog[] } | null>(null);

  const [loading, setLoading] = useState(true);
  const [drilling, setDrilling] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [objectType, setObjectType] = useState('');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [warningOnly, setWarningOnly] = useState(false);
  const [page, setPage] = useState(0);

  // 批量打印草稿
  const [selected, setSelected] = useState<string[]>([]);
  const [templateId, setTemplateId] = useState('');
  const [copies, setCopies] = useState(1);
  const [printerId, setPrinterId] = useState('');
  const [overrideReprint, setOverrideReprint] = useState(false);
  const [overrideReason, setOverrideReason] = useState('');

  // 登记打印对象草稿
  const [newRef, setNewRef] = useState('');
  const [newWaybill, setNewWaybill] = useState('');
  const [newPlatform, setNewPlatform] = useState('');
  const [newCarrier, setNewCarrier] = useState('');
  const [newSku, setNewSku] = useState('');
  const [newLocation, setNewLocation] = useState('');
  const [newCarton, setNewCarton] = useState('');
  const [newName, setNewName] = useState('');
  const [newAddress, setNewAddress] = useState('');
  const [newWeight, setNewWeight] = useState('');

  // 规则草稿
  const [rulePlatform, setRulePlatform] = useState('');
  const [ruleType, setRuleType] = useState('SHIPPING_LABEL');
  const [ruleWarning, setRuleWarning] = useState('');
  const [ruleMax, setRuleMax] = useState('');
  const [ruleNote, setRuleNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, deviceList] = await Promise.all([
        fetchPrintCapabilities(),
        fetchDevices().catch(() => [] as LabelPrintDevice[]),
      ]);
      setCapabilities(caps);
      setDevices(deviceList);
      if (!templateId && caps.objectTypes.length > 0) {
        const list = await fetchTemplates({ size: 100 }).catch(() => null);
        const first = list?.rows[0];
        if (first) setTemplateId(first.id);
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [templateId]);

  const loadTargets = useCallback(async () => {
    setError('');
    try {
      setTargets(await fetchTargets({ objectType, keyword, page, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    }
  }, [objectType, keyword, page]);

  const loadJobs = useCallback(async () => {
    setError('');
    try {
      setJobs(await fetchJobs({ status, keyword, page, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    }
  }, [status, keyword, page]);

  const loadLogs = useCallback(async () => {
    setError('');
    try {
      setLogs(await fetchLogs({ warningOnly, page, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    }
  }, [warningOnly, page]);

  const loadTemplates = useCallback(async () => {
    setError('');
    try {
      setTemplates(await fetchTemplates({ objectType, page, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    }
  }, [objectType, page]);

  const loadRules = useCallback(async () => {
    setError('');
    try {
      setRules(await fetchReprintRules());
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [readable, load]);

  useEffect(() => {
    if (!readable) return;
    if (view === 'TARGETS') void loadTargets();
    else if (view === 'JOBS') void loadJobs();
    else if (view === 'LOGS') void loadLogs();
    else if (view === 'TEMPLATES') void loadTemplates();
    else if (view === 'RULES') void loadRules();
  }, [readable, view, loadTargets, loadJobs, loadLogs, loadTemplates, loadRules]);

  // 选中的对象：判定与「能否直接打印」都在这里算，界面上与后端同一套口径
  const selectedTargets = useMemo(
    () => (targets?.rows ?? []).filter((t) => selected.includes(t.id)),
    [targets, selected],
  );
  const blockedSelected = selectedTargets.filter((t) => t.nextVerdict === 'BLOCKED');
  const warnedSelected = selectedTargets.filter((t) => t.nextVerdict === 'WARNING');
  const templateForSelection = (templates?.rows ?? []).find((t) => t.id === templateId);

  async function openJob(id: string) {
    setDrilling(true);
    setError('');
    try {
      setDetail(await fetchJob(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDrilling(false);
    }
  }

  async function openHistory(target: LabelPrintTarget) {
    setDrilling(true);
    setError('');
    try {
      setHistory({ ref: target.externalRef, rows: await fetchTargetHistory(target.id) });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDrilling(false);
    }
  }

  async function submitJob() {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const job = await createJob({
        templateId,
        targetIds: selected,
        copies,
        printerId: printerId || undefined,
        overrideReprint: overrideReprint || undefined,
        overrideReason: overrideReason || undefined,
      });
      const result = await executeJob(job.id);
      setNotice(
        `打印任务 ${job.jobNo} 已完成：${result.logs.length} 张凭据，` +
          `其中 ${result.warnedTargets} 个对象落在重复打印告警区间` +
          (result.blockedTargets > 0 ? `，${result.blockedTargets} 个对象超限后被强制放行` : ''),
      );
      setSelected([]);
      setOverrideReprint(false);
      setOverrideReason('');
      await Promise.all([loadTargets(), loadJobs(), loadLogs()]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  async function runCancel(id: string) {
    setSaving(true);
    setError('');
    try {
      await cancelJob(id);
      setNotice('打印任务已取消。取消不是打印 —— 打印次数与凭据都没有增加。');
      await Promise.all([loadJobs(), loadTargets(), loadLogs()]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  async function submitTarget() {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const created = await registerTarget({
        objectType,
        externalRef: newRef,
        waybillNo: newWaybill || undefined,
        platformCode: newPlatform || undefined,
        carrierCode: newCarrier || undefined,
        shipToName: newName || undefined,
        shipToAddress: newAddress || undefined,
        skuCode: newSku || undefined,
        locationCode: newLocation || undefined,
        cartonCode: newCarton || undefined,
        weightKg: newWeight ? Number(newWeight) : undefined,
      });
      setNotice(
        `已登记打印对象 ${created.externalRef}（打印次数 0 次，由打印动作产生首印）`,
      );
      setNewRef('');
      setNewWaybill('');
      setNewSku('');
      setNewLocation('');
      setNewCarton('');
      await Promise.all([loadTargets(), loadTemplates()]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  async function submitRule() {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      await upsertReprintRule({
        platformCode: rulePlatform,
        objectType: ruleType,
        warningCount: ruleWarning ? Number(ruleWarning) : undefined,
        maxCount: ruleMax ? Number(ruleMax) : undefined,
        note: ruleNote || undefined,
      });
      setNotice(`已保存 ${rulePlatform || '*'} · ${objectTypeLabel(ruleType)} 的重复打印规则`);
      setRuleWarning('');
      setRuleMax('');
      setRuleNote('');
      await loadRules();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  if (!readable) {
    return (
      <EmptyState
        title="没有面单与标签打印的查看权限"
        description="该模块需要 warehouse.inventory.read 权限。"
      />
    );
  }

  const total = targets?.total ?? 0;
  const blockedAll = (targets?.rows ?? []).filter((t) => t.nextVerdict === 'BLOCKED').length;
  const warnedAll = (targets?.rows ?? []).filter((t) => t.nextVerdict === 'WARNING').length;
  const printedAll = (targets?.rows ?? []).filter((t) => t.printCount > 0).length;

  return (
    <div className="space-y-6">
      <PageHeader
        title="面单与标签打印"
        description="打印面单、装箱单、SKU 条码、库位标签、箱唛：批量打印、模板配置与逐次打印次数记录。"
        actions={
          <div className="flex items-center gap-2">
            <div className="flex rounded-md border border-control-border p-0.5">
              {(
                [
                  ['TARGETS', '打印对象'],
                  ['JOBS', '打印任务'],
                  ['LOGS', '打印日志'],
                  ['TEMPLATES', '模板配置'],
                  ['RULES', '重复打印规则'],
                ] as const
              ).map(([key, label]) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => {
                    setView(key);
                    setPage(0);
                  }}
                  aria-pressed={view === key}
                  className={`rounded px-3 py-1.5 text-sm transition-colors ${
                    view === key ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
              刷新
            </Button>
          </div>
        }
      />

      {error && (
        <Notice tone="danger" title="操作未完成">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      )}

      {capabilities && (
        <Notice tone="info" title="面单数据来源说明">
          {capabilities.upstreamNote}
        </Notice>
      )}

      {/* ============================================================ 打印对象 */}
      {view === 'TARGETS' && (
        <>
          <div className="grid gap-4 sm:grid-cols-4">
            <StatCard label="打印对象" value={total} />
            <StatCard label="已打印过" value={printedAll} polarity="neutral" />
            <StatCard
              label="告警区间"
              value={warnedAll}
              polarity={warnedAll > 0 ? 'negative' : 'neutral'}
            />
            <StatCard
              label="已达上限"
              value={blockedAll}
              polarity={blockedAll > 0 ? 'negative' : 'neutral'}
            />
          </div>

          <SectionCard title="登记打印对象" description="面单号、路由等字段由调用方传入；登记不产生打印次数。">
            {writable && (
              <div className="grid gap-3 md:grid-cols-4">
                <FormField id="print-object-type" label="对象类型" required>
                  <select
                    className="w-full rounded-md border border-control-border bg-background px-2 py-1.5 text-sm"
                    value={objectType}
                    onChange={(e) => {
                      setObjectType(e.target.value);
                      setPage(0);
                    }}
                  >
                    <option value="">面单</option>
                    {objectTypeOptions.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="print-external-ref" label="外部引用" required hint="上游单据标识，如出库单号">
                  <Input value={newRef} onChange={(e) => setNewRef(e.target.value)} />
                </FormField>
                <FormField id="print-waybill" label="面单号 / 条码码值">
                  <Input value={newWaybill} onChange={(e) => setNewWaybill(e.target.value)} />
                </FormField>
                <FormField id="print-platform" label="平台编码" hint="用于取重复打印规则">
                  <Input value={newPlatform} onChange={(e) => setNewPlatform(e.target.value)} />
                </FormField>
                <FormField id="print-carrier" label="承运商">
                  <Input value={newCarrier} onChange={(e) => setNewCarrier(e.target.value)} />
                </FormField>
                <FormField id="print-sku" label="SKU 编码">
                  <Input value={newSku} onChange={(e) => setNewSku(e.target.value)} />
                </FormField>
                <FormField id="print-location" label="库位编码">
                  <Input value={newLocation} onChange={(e) => setNewLocation(e.target.value)} />
                </FormField>
                <FormField id="print-carton" label="箱号">
                  <Input value={newCarton} onChange={(e) => setNewCarton(e.target.value)} />
                </FormField>
                <FormField id="print-name" label="收件人">
                  <Input value={newName} onChange={(e) => setNewName(e.target.value)} />
                </FormField>
                <FormField id="print-weight" label="重量(kg)">
                  <Input value={newWeight} onChange={(e) => setNewWeight(e.target.value)} />
                </FormField>
                <div className="md:col-span-4">
                  <Button size="sm" onClick={() => void submitTarget()} disabled={saving || !newRef}>
                    登记
                  </Button>
                </div>
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="打印对象"
            description="每一行显示已打印次数与「再印一次」的判定 —— 超限的对象默认拦下来。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <FormField id="print-object-type" label="对象类型">
                <select
                  className="rounded-md border border-control-border bg-background px-2 py-1.5 text-sm"
                  value={objectType}
                  onChange={(e) => {
                    setObjectType(e.target.value);
                    setPage(0);
                  }}
                >
                  <option value="">全部</option>
                  {objectTypeOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="print-keyword" label="关键词">
                <Input
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  placeholder="外部引用 / 面单号 / SKU / 库位 / 箱号"
                />
              </FormField>
              <Button variant="outline" size="sm" onClick={() => void loadTargets()}>
                查询
              </Button>
            </div>

            {loading && <p className="mt-4 text-sm text-muted-foreground">正在加载打印对象…</p>}
            {!loading && targets && targets.rows.length === 0 && (
              <EmptyState
                title={keyword || objectType ? '没有符合条件的打印对象' : '尚未登记打印对象'}
                description={
                  keyword || objectType
                    ? '放宽筛选条件再试。'
                    : '面单数据来自上游（10 物流）传入，先登记打印对象才能打印。'
                }
              />
            )}
            {targets && targets.rows.length > 0 && (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full min-w-[1080px] text-sm">
                  <thead>
                    <tr className="text-left">
                      <th className="px-3 py-2">
                        <input
                          type="checkbox"
                          aria-label="全选本页"
                          checked={
                            selected.length > 0 &&
                            targets.rows.every((t) => selected.includes(t.id))
                          }
                          onChange={(e) =>
                            setSelected(
                              e.target.checked
                                ? Array.from(new Set([...selected, ...targets.rows.map((t) => t.id)]))
                                : selected.filter((id) => !targets.rows.some((t) => t.id === id)),
                            )
                          }
                        />
                      </th>
                      <th className="px-3 py-2">对象</th>
                      <th className="px-3 py-2">外部引用</th>
                      <th className="px-3 py-2">平台</th>
                      <th className="px-3 py-2">面单 / 码值</th>
                      <th className="px-3 py-2">重量</th>
                      <th className="px-3 py-2 text-right">已打印</th>
                      <th className="px-3 py-2">判据</th>
                      <th className="px-3 py-2">判定</th>
                      <th className="px-3 py-2">最近打印</th>
                      <th className="px-3 py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {targets.rows.map((t) => (
                      <tr key={t.id} className="border-t border-control-border">
                        <td className="px-3 py-2">
                          <input
                            type="checkbox"
                            aria-label={`选择 ${t.externalRef}`}
                            checked={selected.includes(t.id)}
                            onChange={(e) =>
                              setSelected(
                                e.target.checked
                                  ? [...selected, t.id]
                                  : selected.filter((id) => id !== t.id),
                              )
                            }
                          />
                        </td>
                        <td className="px-3 py-2">{objectTypeLabel(t.objectType)}</td>
                        <td className="px-3 py-2 font-mono text-xs">{t.externalRef}</td>
                        <td className="px-3 py-2">{t.platformCode || '—'}</td>
                        <td className="px-3 py-2 font-mono text-xs">
                          {t.waybillNo || t.skuCode || t.locationCode || t.cartonCode || '—'}
                        </td>
                        <td className="px-3 py-2 tabular-nums">{weightText(t.weightKg)}</td>
                        <td className="px-3 py-2 text-right tabular-nums font-medium">
                          {t.printCount} 次
                        </td>
                        <td className="px-3 py-2 text-xs text-muted-foreground">
                          {warningText(t.warningCount)} · {limitText(t.maxCount)}
                        </td>
                        <td className="px-3 py-2">
                          <span className={toneClass(verdictTone(t.nextVerdict))}>
                            {verdictLabel(t.nextVerdict)}
                          </span>
                          <span className="ml-2 text-xs text-muted-foreground">
                            {remainingText(t)}
                          </span>
                        </td>
                        <td className="px-3 py-2 text-xs text-muted-foreground">
                          {formatMoment(t.lastPrintedAt)}
                        </td>
                        <td className="px-3 py-2">
                          <button
                            type="button"
                            className="text-xs text-primary underline-offset-2 hover:underline"
                            onClick={() => void openHistory(t)}
                          >
                            打印历史
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {targets && targets.total > 50 && (
              <Pager page={page} size={50} total={targets.total} onChange={setPage} />
            )}
          </SectionCard>

          {writable && selected.length > 0 && (
            <SectionCard
              title={`批量打印（已选 ${selected.length} 个对象）`}
              description="批量是真批量：每个对象逐份渲染并落打印凭据，每个对象各自累加打印次数。"
            >
              <div className="grid gap-3 md:grid-cols-4">
                <FormField id="print-template" label="模板" required>
                  <select
                    className="w-full rounded-md border border-control-border bg-background px-2 py-1.5 text-sm"
                    value={templateId}
                    onChange={(e) => setTemplateId(e.target.value)}
                  >
                    <option value="">选择模板</option>
                    {(templates?.rows ?? []).map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}（{objectTypeLabel(t.objectType)}）
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="print-copies" label="份数" hint="1–20，份数会按份累加打印次数">
                  <Input
                    type="number"
                    min={1}
                    max={20}
                    value={copies}
                    onChange={(e) => setCopies(Math.max(1, Math.min(20, Number(e.target.value) || 1)))}
                  />
                </FormField>
                <FormField id="print-printer" label="打印机">
                  <select
                    className="w-full rounded-md border border-control-border bg-background px-2 py-1.5 text-sm"
                    value={printerId}
                    onChange={(e) => setPrinterId(e.target.value)}
                  >
                    <option value="">不指定</option>
                    {devices.map((d) => (
                      <option key={d.id} value={d.id}>
                        {d.name}（{d.paperSize}）
                      </option>
                    ))}
                  </select>
                </FormField>
                <div className="flex items-end">
                  <span className="text-sm text-muted-foreground">
                    将产出 {selected.length * copies} 张标签
                    {templateForSelection
                      ? `（${templateForSelection.paperSize} · ${templateForSelection.orientation}）`
                      : ''}
                  </span>
                </div>
              </div>

              {(blockedSelected.length > 0 || warnedSelected.length > 0) && (
                <div className="mt-3 space-y-2">
                  {warnedSelected.length > 0 && (
                    <Notice tone="warning" title={`${warnedSelected.length} 个对象落在重复打印告警区间`}>
                      {warnedSelected
                        .slice(0, 6)
                        .map((t) => `${t.externalRef}（已印 ${t.printCount} 次）`)
                        .join('；')}
                      {warnedSelected.length > 6 ? ' 等' : ''}
                      　仍然可以打印，但会在打印凭据上留告警标记。
                    </Notice>
                  )}
                  {blockedSelected.length > 0 && (
                    <Notice tone="danger" title={`${blockedSelected.length} 个对象已超出平台重复打印上限`}>
                      {blockedSelected
                        .slice(0, 6)
                        .map((t) => `${t.externalRef}（已印 ${t.printCount} 次，${limitText(t.maxCount)}）`)
                        .join('；')}
                      {blockedSelected.length > 6 ? ' 等' : ''}
                      　默认会整体拒绝这次批量打印。
                    </Notice>
                  )}
                </div>
              )}

              {blockedSelected.length > 0 && (
                <div className="mt-3 grid gap-3">
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={overrideReprint}
                      onChange={(e) => setOverrideReprint(e.target.checked)}
                    />
                    强制重打（超限放行）—— 强制重打会被平台追责，必须留理由
                  </label>
                  {overrideReprint && (
                    <FormField id="print-override-reason" label="强制重打理由" required>
                      <textarea
                        rows={2}
                        className="w-full rounded-md border border-control-border bg-background px-2 py-1.5 text-sm"
                        value={overrideReason}
                        onChange={(e) => setOverrideReason(e.target.value)}
                        placeholder="例：面单贴错，第 4 次强制重打（已与平台报备）"
                      />
                    </FormField>
                  )}
                </div>
              )}

              <div className="mt-4 flex items-center gap-2">
                <Button
                  size="sm"
                  onClick={() => void submitJob()}
                  disabled={
                    saving ||
                    !templateId ||
                    !templateForSelection ||
                    (blockedSelected.length > 0 && (!overrideReprint || !overrideReason))
                  }
                >
                  <Printer className="mr-2 h-3.5 w-3.5" />
                  {saving ? '打印中…' : `打印 ${selected.length * copies} 张`}
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setSelected([])} disabled={saving}>
                  清空选择
                </Button>
                {templateForSelection &&
                  selectedTargets.some(
                    (t) => t.objectType !== templateForSelection.objectType,
                  ) && (
                    <span className="text-sm text-destructive">
                      模板与所选对象类型不一致，打出来的标签没有意义
                    </span>
                  )}
              </div>
            </SectionCard>
          )}

          {history && (
            <SectionCard
              title={`打印历史 · ${history.ref}`}
              description="每一次打印的序号、是否告警、是否强制放行。凭据是 append-only，不可改。"
              extra={
                <button
                  type="button"
                  className="text-sm text-muted-foreground hover:text-foreground"
                  onClick={() => setHistory(null)}
                >
                  关闭
                </button>
              }
            >
              {history.rows.length === 0 ? (
                <EmptyState title="尚未打印过" description="该对象还没有任何打印凭据。" />
              ) : (
                <table className="w-full min-w-[720px] text-sm">
                  <thead>
                    <tr className="text-left">
                      <th className="px-3 py-2">第几次</th>
                      <th className="px-3 py-2">份序</th>
                      <th className="px-3 py-2">格式</th>
                      <th className="px-3 py-2">内容校验和</th>
                      <th className="px-3 py-2">告警</th>
                      <th className="px-3 py-2">强制放行</th>
                      <th className="px-3 py-2">时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.rows.map((l) => (
                      <tr key={l.id} className="border-t border-control-border">
                        <td className="px-3 py-2 font-medium tabular-nums">第 {l.printSequence} 次</td>
                        <td className="px-3 py-2 tabular-nums">{l.copyIndex}</td>
                        <td className="px-3 py-2 text-xs">{l.contentFormat}</td>
                        <td className="px-3 py-2 font-mono text-xs">{l.contentChecksum.slice(0, 16)}…</td>
                        <td className="px-3 py-2">
                          {l.reprintWarning ? (
                            <span className={toneClass('warn')}>重复打印告警</span>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="px-3 py-2">
                          {l.overrideApplied ? (
                            <span className={toneClass('bad')}>是</span>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="px-3 py-2 text-xs text-muted-foreground">
                          {formatMoment(l.printedAt)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}
        </>
      )}

      {/* ============================================================ 打印任务 */}
      {view === 'JOBS' && (
        <SectionCard title="打印任务" description="同一任务只能打印一次；要重打请新建任务。">
          <div className="flex flex-wrap items-end gap-3">
            <FormField id="job-status" label="状态">
              <select
                className="rounded-md border border-control-border bg-background px-2 py-1.5 text-sm"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部</option>
                {jobStatusOptions.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="print-keyword" label="关键词">
              <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="作业号 / 备注" />
            </FormField>
            <Button variant="outline" size="sm" onClick={() => void loadJobs()}>
              查询
            </Button>
          </div>

          {loading && <p className="mt-4 text-sm text-muted-foreground">正在加载打印任务…</p>}
          {!loading && jobs && jobs.rows.length === 0 && (
            <EmptyState
              title={status || keyword ? '没有符合条件的打印任务' : '尚未创建打印任务'}
              description="在「打印对象」页勾选对象后发起批量打印。"
            />
          )}
          {jobs && jobs.rows.length > 0 && (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full min-w-[960px] text-sm">
                <thead>
                  <tr className="text-left">
                    <th className="px-3 py-2">作业号</th>
                    <th className="px-3 py-2">模板</th>
                    <th className="px-3 py-2">打印机</th>
                    <th className="px-3 py-2">状态</th>
                    <th className="px-3 py-2 text-right">对象</th>
                    <th className="px-3 py-2 text-right">标签数</th>
                    <th className="px-3 py-2 text-right">告警</th>
                    <th className="px-3 py-2 text-right">超限</th>
                    <th className="px-3 py-2">创建时间</th>
                    <th className="px-3 py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.rows.map((j: LabelPrintJob) => (
                    <tr key={j.id} className="border-t border-control-border">
                      <td className="px-3 py-2 font-mono text-xs">{j.jobNo}</td>
                      <td className="px-3 py-2">{j.templateName || '—'}</td>
                      <td className="px-3 py-2">{j.printerName || '—'}</td>
                      <td className="px-3 py-2">
                        <span className={toneClass(jobStatusTone(j.status))}>
                          {jobStatusLabel(j.status)}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-right tabular-nums">{j.targetCount}</td>
                      <td className="px-3 py-2 text-right tabular-nums font-medium">
                        {j.labelCount}
                      </td>
                      <td className="px-3 py-2 text-right tabular-nums">{j.reprintWarningCount}</td>
                      <td className="px-3 py-2 text-right tabular-nums">
                        {j.blockedCount > 0 ? (
                          <span className={toneClass('bad')}>{j.blockedCount}</span>
                        ) : (
                          0
                        )}
                      </td>
                      <td className="px-3 py-2 text-xs text-muted-foreground">
                        {formatMoment(j.createdAt)}
                      </td>
                      <td className="px-3 py-2">
                        <div className="flex items-center gap-2">
                          <button
                            type="button"
                            className="text-xs text-primary underline-offset-2 hover:underline"
                            onClick={() => void openJob(j.id)}
                          >
                            详情
                          </button>
                          {admin && (j.status === 'DRAFT' || j.status === 'QUEUED') && (
                            <button
                              type="button"
                              className="text-xs text-muted-foreground underline-offset-2 hover:underline"
                              onClick={() => void runCancel(j.id)}
                              disabled={saving}
                            >
                              取消
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {jobs && jobs.total > 50 && (
            <Pager page={page} size={50} total={jobs.total} onChange={setPage} />
          )}
        </SectionCard>
      )}

      {detail && (
        <SectionCard
          title={`打印任务 ${detail.job.jobNo}`}
          description="每一行都是一份按模板与快照现算的渲染内容；下载端点返回的就是打印时落库的那一份。"
          extra={
            <button
              type="button"
              className="text-sm text-muted-foreground hover:text-foreground"
              onClick={() => setDetail(null)}
            >
              关闭
            </button>
          }
        >
          {detail.job.overrideReprint && (
            <Notice tone="danger" title="这次打印是超限强制放行">
              理由：{detail.job.overrideReason || '（未填写）'}
            </Notice>
          )}
          {drilling && (
            <p className="mt-3 text-sm text-muted-foreground">正在加载任务明细…</p>
          )}
          {!drilling && detail.items.length === 0 && (
            <EmptyState title="没有明细行" description="该任务没有可打印的对象。" />
          )}
          {!drilling && detail.items.length > 0 && (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full min-w-[840px] text-sm">
                <thead>
                  <tr className="text-left">
                    <th className="px-3 py-2">行</th>
                    <th className="px-3 py-2">对象</th>
                    <th className="px-3 py-2">外部引用</th>
                    <th className="px-3 py-2">类型</th>
                    <th className="px-3 py-2 text-right">渲染时次数</th>
                    <th className="px-3 py-2">内容</th>
                    <th className="px-3 py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.items.map((item) => (
                    <tr key={item.id} className="border-t border-control-border">
                      <td className="px-3 py-2 tabular-nums">{item.lineNo}</td>
                      <td className="px-3 py-2 font-mono text-xs">{item.externalRef}</td>
                      <td className="px-3 py-2 font-mono text-xs">{item.externalRef}</td>
                      <td className="px-3 py-2">{objectTypeLabel(item.objectType)}</td>
                      <td className="px-3 py-2 text-right tabular-nums">
                        {item.printCountAtRender} 次
                      </td>
                      <td className="px-3 py-2 text-xs text-muted-foreground">
                        {item.contentFormat} · {item.contentBytes} 字节
                      </td>
                      <td className="px-3 py-2">
                        <a
                          href={contentUrl(detail.job.id, item.id)}
                          className="inline-flex items-center gap-1 text-xs text-primary underline-offset-2 hover:underline"
                        >
                          <FileText className="h-3 w-3" />
                          下载内容
                        </a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      )}

      {/* ============================================================ 打印日志 */}
      {view === 'LOGS' && (
        <SectionCard
          title="打印日志"
          description="逐次打印凭据：第几次打印、是否落在告警区间、是否强制放行。凭据不可改也不可删。"
        >
          <div className="flex flex-wrap items-end gap-3">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={warningOnly}
                onChange={(e) => {
                  setWarningOnly(e.target.checked);
                  setPage(0);
                }}
              />
              只看重复打印告警
            </label>
          </div>

          {loading && <p className="mt-4 text-sm text-muted-foreground">正在加载打印日志…</p>}
          {!loading && logs && logs.rows.length === 0 && (
            <EmptyState
              title={warningOnly ? '没有重复打印告警记录' : '尚无打印日志'}
              description="每打印一张就会落一条凭据，这里会按时间倒序列出。"
            />
          )}
          {logs && logs.rows.length > 0 && (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full min-w-[900px] text-sm">
                <thead>
                  <tr className="text-left">
                    <th className="px-3 py-2">外部引用</th>
                    <th className="px-3 py-2">类型</th>
                    <th className="px-3 py-2 text-right">第几次</th>
                    <th className="px-3 py-2 text-right">份序</th>
                    <th className="px-3 py-2">格式</th>
                    <th className="px-3 py-2">告警</th>
                    <th className="px-3 py-2">强制放行</th>
                    <th className="px-3 py-2">时间</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.rows.map((l: LabelPrintLog) => (
                    <tr key={l.id} className="border-t border-control-border">
                      <td className="px-3 py-2 font-mono text-xs">{l.externalRef}</td>
                      <td className="px-3 py-2">{objectTypeLabel(l.objectType)}</td>
                      <td className="px-3 py-2 text-right tabular-nums font-medium">
                        {l.printSequence}
                      </td>
                      <td className="px-3 py-2 text-right tabular-nums">{l.copyIndex}</td>
                      <td className="px-3 py-2 text-xs">{l.contentFormat}</td>
                      <td className="px-3 py-2">
                        {l.reprintWarning ? (
                          <span className={toneClass('warn')}>告警</span>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        {l.overrideApplied ? (
                          <span className={toneClass('bad')}>是</span>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-xs text-muted-foreground">
                        {formatMoment(l.printedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {logs && logs.total > 50 && (
            <Pager page={page} size={50} total={logs.total} onChange={setPage} />
          )}
        </SectionCard>
      )}

      {/* ============================================================ 模板 */}
      {view === 'TEMPLATES' && (
        <SectionCard
          title="模板配置"
          description="五种打印对象各自的版式：纸张、方向与字段布局。渲染内容按模板与快照现算。"
        >
          <div className="flex flex-wrap items-end gap-3">
            <FormField id="print-object-type" label="对象类型">
              <select
                className="rounded-md border border-control-border bg-background px-2 py-1.5 text-sm"
                value={objectType}
                onChange={(e) => {
                  setObjectType(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部</option>
                {objectTypeOptions.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </FormField>
            <Button variant="outline" size="sm" onClick={() => void loadTemplates()}>
              查询
            </Button>
          </div>

          {capabilities && (
            <div className="mt-4 rounded-md border border-control-border p-3 text-xs">
              <p className="mb-2 font-medium">各对象的默认渲染形态与必填字段</p>
              <ul className="space-y-1 text-muted-foreground">
                {capabilities.objectTypes.map((t) => (
                  <li key={t.code}>
                    <span className="text-foreground">{t.label}</span>：{t.defaultFormat} ·
                    必填 {t.requiredFields.join('、')} · {t.description}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {loading && <p className="mt-4 text-sm text-muted-foreground">正在加载模板…</p>}
          {!loading && templates && templates.rows.length === 0 && (
            <EmptyState title="尚无打印模板" description="没有模板就无法建打印任务。" />
          )}
          {templates && templates.rows.length > 0 && (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full min-w-[760px] text-sm">
                <thead>
                  <tr className="text-left">
                    <th className="px-3 py-2">编码</th>
                    <th className="px-3 py-2">名称</th>
                    <th className="px-3 py-2">对象类型</th>
                    <th className="px-3 py-2">纸张</th>
                    <th className="px-3 py-2">方向</th>
                    <th className="px-3 py-2 text-right">已用任务</th>
                    <th className="px-3 py-2">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {templates.rows.map((t: LabelPrintTemplate) => (
                    <tr key={t.id} className="border-t border-control-border">
                      <td className="px-3 py-2 font-mono text-xs">{t.code}</td>
                      <td className="px-3 py-2">{t.name}</td>
                      <td className="px-3 py-2">{objectTypeLabel(t.objectType)}</td>
                      <td className="px-3 py-2">{t.paperSize}</td>
                      <td className="px-3 py-2">
                        {t.orientation === 'LANDSCAPE' ? '横向' : '纵向'}
                      </td>
                      <td className="px-3 py-2 text-right tabular-nums">{t.jobCount}</td>
                      <td className="px-3 py-2">
                        <span className={toneClass(t.active ? 'ok' : 'neutral')}>
                          {t.active ? '启用' : '停用'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      )}

      {/* ============================================================ 重复打印规则 */}
      {view === 'RULES' && (
        <>
          {capabilities && (
            <SectionCard
              title="判定口径"
              description="判定基准：下一次打印的序号 = 该对象当前打印次数 + 本次份数。"
            >
              <ul className="space-y-1 text-sm text-muted-foreground">
                {capabilities.reprint.ranges.map((r) => (
                  <li key={r.verdict}>
                    <span className={toneClass(verdictTone(r.verdict))}>{r.label}</span>：{r.condition}
                    {r.blocked ? '（默认拒绝，需强制重打 + 理由）' : ''}
                  </li>
                ))}
              </ul>
              <p className="mt-3 text-sm text-muted-foreground">{capabilities.reprint.overrideRule}</p>
              <p className="mt-1 text-sm text-muted-foreground">{capabilities.reprint.ruleTableNote}</p>
            </SectionCard>
          )}

          <SectionCard
            title="平台重复打印规则"
            description="按「平台 + 对象类型」配置。平台填 * 表示通配规则（该平台没有专属规则时生效）。"
          >
            {admin && (
              <div className="grid gap-3 md:grid-cols-5">
                <FormField id="print-platform" label="平台编码" required hint="* 表示通配">
                  <Input value={rulePlatform} onChange={(e) => setRulePlatform(e.target.value)} />
                </FormField>
                <FormField id="print-object-type" label="对象类型" required>
                  <select
                    className="w-full rounded-md border border-control-border bg-background px-2 py-1.5 text-sm"
                    value={ruleType}
                    onChange={(e) => setRuleType(e.target.value)}
                  >
                    {objectTypeOptions.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="-21" label="告警次数" hint="留空 = 无告警线">
                  <Input value={ruleWarning} onChange={(e) => setRuleWarning(e.target.value)} />
                </FormField>
                <FormField id="-22" label="上限次数" hint="留空 = 不限次">
                  <Input value={ruleMax} onChange={(e) => setRuleMax(e.target.value)} />
                </FormField>
                <FormField id="-23" label="备注">
                  <Input value={ruleNote} onChange={(e) => setRuleNote(e.target.value)} />
                </FormField>
                <div className="md:col-span-5">
                  <Button
                    size="sm"
                    onClick={() => void submitRule()}
                    disabled={saving || !rulePlatform}
                  >
                    保存规则
                  </Button>
                </div>
              </div>
            )}

            {loading && <p className="mt-4 text-sm text-muted-foreground">正在加载规则…</p>}
            {!loading && rules.length === 0 && (
              <EmptyState
                title="尚未配置重复打印规则"
                description="没有规则时所有平台都不限次 —— 但打印次数与凭据仍然逐次记录。"
              />
            )}
            {rules.length > 0 && (
              <table className="mt-4 w-full min-w-[720px] text-sm">
                <thead>
                  <tr className="text-left">
                    <th className="px-3 py-2">平台</th>
                    <th className="px-3 py-2">对象类型</th>
                    <th className="px-3 py-2">告警线</th>
                    <th className="px-3 py-2">上限</th>
                    <th className="px-3 py-2">备注</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.map((r) => (
                    <tr key={r.id} className="border-t border-control-border">
                      <td className="px-3 py-2 font-mono text-xs">
                        {r.platformCode === '*' ? '*（通配）' : r.platformCode}
                      </td>
                      <td className="px-3 py-2">{objectTypeLabel(r.objectType)}</td>
                      <td className="px-3 py-2">{warningText(r.warningCount)}</td>
                      <td className="px-3 py-2">{limitText(r.maxCount)}</td>
                      <td className="px-3 py-2 text-xs text-muted-foreground">{r.note || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </>
      )}

      {!readable && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Tag className="h-3.5 w-3.5" />
          当前会话没有查看权限，面板不会显示任何数据。
        </p>
      )}
      <p className="flex items-center gap-2 text-xs text-muted-foreground">
        <ScrollText className="h-3.5 w-3.5" />
        打印次数是权威事实：每次打印都会 +1 并留一条凭据，两者由数据库约束保持一致。
      </p>
    </div>
  );
}

function toneClass(tone: 'ok' | 'warn' | 'bad' | 'neutral'): string {
  switch (tone) {
    case 'ok':
      return 'text-emerald-600 dark:text-emerald-400';
    case 'warn':
      return 'text-amber-600 dark:text-amber-400';
    case 'bad':
      return 'text-destructive';
    default:
      return 'text-muted-foreground';
  }
}

function Pager({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const pages = Math.ceil(total / size);
  return (
    <div className="mt-3 flex items-center justify-end gap-2 text-sm">
      <span className="text-muted-foreground">
        共 {total} 条 · 第 {page + 1}/{pages} 页
      </span>
      <Button
        variant="outline"
        size="sm"
        disabled={page <= 0}
        onClick={() => onChange(page - 1)}
      >
        上一页
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}