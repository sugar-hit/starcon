'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { FileCheck2, FilePlus2, PackagePlus, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Button,
  EmptyState,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  CustomsCapabilities,
  CustomsDeclarationRow,
  CustomsDocumentRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionLabel,
  amendCustomsDeclaration,
  cancelCustomsDeclaration,
  createCustomsDeclaration,
  declarationActions,
  declarationSortOptions,
  declarationStatusLabels,
  documentTypeLabels,
  documentsReady,
  elementComplete,
  elementStatusLabels,
  eventKindLabels,
  fetchCustomsCapabilities,
  fetchCustomsDeclarationDetail,
  fetchCustomsDeclarations,
  fetchCustomsSourceShipmentLines,
  fetchCustomsSourceShipments,
  finalizeCustomsDocument,
  fmt,
  generateCustomsDocument,
  money,
  moment,
  rateText,
  recordCustomsResult,
  rejectCustomsReview,
  resultTypeLabels,
  reviewCustomsDeclaration,
  submitCustomsDeclaration,
  submitCustomsForReview,
  withdrawCustomsReview,
  type CustomsDeclarationParams,
} from './customs-declaration-model';
import { errorText, fetchLegalEntities } from '../inventory/inventory-model';

type Detail = Awaited<ReturnType<typeof fetchCustomsDeclarationDetail>>;

interface LegalEntityOption {
  id: string;
  code: string;
  name: string;
}

/**
 * 改一个筛选条件。
 *
 * <p>值为空就**删掉这个键**，而不是写 `undefined`：`exactOptionalPropertyTypes` 下
 * `undefined` 不等于「没填」，写进去会一路传到查询串里变成 `status=undefined`。
 * 顺带把页码复位 —— 任何筛选变化都必须回到第一页，否则会停在一个已经空掉的页码上。
 */
function patchParam(
  prev: CustomsDeclarationParams,
  patch: Partial<Record<keyof CustomsDeclarationParams, string | boolean | string[] | number>>,
): CustomsDeclarationParams {
  const next: Record<string, unknown> = { ...prev };
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined || value === '' || value === false) delete next[key];
    else next[key] = value;
  }
  return { ...next, page: 0 } as CustomsDeclarationParams;
}

/**
 * 10.5.1.1 报关单管理工作区。
 *
 * <p>蓝图：「管理报关单：按发货单生成申报资料（报关单、装箱单、发票），提交报关行/IOR，
 * 接收放行、退单、改单结果」。
 *
 * <p>界面纪律（三条都写进了常驻口径卡，因为它们是本模块最容易被误读的地方）：
 *
 * <ul>
 *   <li>**人工复核是硬闸**。只有「已复核」能提交报关行，复核时还会逐行检查
 *       HS 编码 / 申报要素 / 货值 / 毛重 / 箱数 / 计量单位。所以「提交报关行」按钮
 *       只在已复核时出现 —— 常驻一个点下去必然被拒的按钮比没有更糟。
 *   <li>**报关数据错误导致的扣货与罚款由卖家承担**（蓝图原话）。所以申报要素齐备性、
 *       汇率快照、三单据齐备这三条都摆在界面上，不藏进详情深处。
 *   <li>**提交痕迹是历史，不会消失**。退单改单退回草稿后「提交于…」仍留着 ——
 *       海关事后复核要能回答「这张单报没报过」。
 * </ul>
 */
export function CustomsWorkspace() {
  const [params, setParams] = useState<CustomsDeclarationParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchCustomsDeclarations>> | null>(
    null,
  );
  const [caps, setCaps] = useState<CustomsCapabilities | null>(null);
  const [legalEntities, setLegalEntities] = useState<LegalEntityOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      setData(await fetchCustomsDeclarations(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    // 能力清单与法人下拉各只读一次：它们决定「哪些控件可点」，不是筛选项。
    void (async () => {
      try {
        setCaps(await fetchCustomsCapabilities());
      } catch {
        // 能力清单读不到不该让整页不可用：下面的动作入口本来还有状态兜底。
      }
      try {
        setLegalEntities(await fetchLegalEntities());
      } catch {
        setLegalEntities([]);
      }
    })();
  }, []);

  const unavailable = useMemo(() => (caps?.dependencies ?? []).filter((d) => !d.available), [caps]);
  const summary = data?.summary;

  return (
    <div className="space-y-6">
      <PageHeader
        title="报关单管理"
        description="按头程发运单生成申报资料，人工复核后提交报关行/IOR，接收放行、退单、改单结果"
        actions={
          <>
            <Button variant="ghost" onClick={() => void load()}>
              <RefreshCw className="size-4" />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)}>
              <PackagePlus className="size-4" />
              新建报关单
            </Button>
          </>
        }
      />

      {/* 口径常驻：这不是页脚备注，而是本模块最容易读错的三件事 */}
      <SectionCard title="申报口径">
        <ul className="text-muted-foreground space-y-1 text-sm">
          <li>
            <strong className="text-foreground">人工复核是硬闸</strong>
            ：只有「已复核」才能提交报关行，且复核时逐行检查 HS 编码 / 申报要素 / 申报货值 / 毛重 /
            箱数 / 计量单位是否齐备。
          </li>
          <li>
            <strong className="text-foreground">罚款由卖家承担</strong>
            ：报关数据错误导致的扣货与罚款由卖家承担，所以货值与汇率是<strong>快照</strong>
            而不是实时值 —— 海关事后复核时要能回答「当时按哪个汇率申报的」。
          </li>
          <li>
            <strong className="text-foreground">一票货只报一次关</strong>
            ：来源固定是 10.4.1.1
            头程发运单，同一张发运单不允许再报第二张。改单走状态机与单据版本链。
          </li>
          <li>
            <strong className="text-foreground">提交痕迹不会消失</strong>
            ：退单 / 改单退回草稿后「提交于…」仍然留着，因为「这张单报没报过」是追责举证。
          </li>
          {caps && (
            <>
              <li>{caps.reviewGateNote}</li>
              <li>{caps.rateSnapshotNote}</li>
              <li>{caps.oneShipmentOneDeclarationNote}</li>
              <li>{caps.hsCodeNote}</li>
            </>
          )}
          {unavailable.map((d) => (
            <li key={d.value}>
              <strong className="text-foreground">
                {d.label}（归属 {d.ownerModule}）
              </strong>
              ：{d.note}
            </li>
          ))}
        </ul>
      </SectionCard>

      {summary && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="报关单"
            value={String(summary.total)}
            comparisonLabel={`草稿 ${summary.draft} · 待复核 ${summary.pendingReview} · 已复核 ${summary.reviewed}`}
          />
          <StatCard
            label="已提交"
            value={String(summary.submitted)}
            comparisonLabel={`已放行 ${summary.cleared} · 海关退单 ${summary.customsRejected}`}
          />
          <StatCard
            label="申报货值合计"
            value={money(summary.totalDeclarationValue, summary.totalDeclarationCurrency)}
            comparisonLabel="同一币种合计，不随分页变化"
          />
          <StatCard
            label="合计口径"
            value="不受分页"
            comparisonLabel="汇总与筛选条件一致，与当前页无关"
          />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="报关单号 / 发运单号 / 出口商 / 收货人 / 提单号 / 报关行"
              defaultValue={params.keyword ?? ''}
              onBlur={(e) => setParams((p) => patchParam(p, { keyword: e.target.value }))}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.status?.[0] ?? ''}
              onChange={(e) =>
                setParams((p) => {
                  const next: CustomsDeclarationParams = { ...p, page: 0 };
                  if (e.target.value) next.status = [e.target.value];
                  else delete next.status;
                  return next;
                })
              }
            >
              <option value="">全部状态</option>
              {Object.entries(declarationStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">贸易方式</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.tradeMode ?? ''}
              onChange={(e) => setParams((p) => patchParam(p, { tradeMode: e.target.value }))}
            >
              <option value="">全部</option>
              {(data?.tradeModes ?? caps?.tradeModes ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">运输方式</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.transportMode ?? ''}
              onChange={(e) => setParams((p) => patchParam(p, { transportMode: e.target.value }))}
            >
              <option value="">全部</option>
              {(data?.transportModes ?? caps?.transportModes ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目的国</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="如 US"
              defaultValue={params.destinationCountryCode ?? ''}
              onBlur={(e) =>
                setParams((p) =>
                  patchParam(p, { destinationCountryCode: e.target.value.trim().toUpperCase() }),
                )
              }
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.sort ?? 'UPDATED_AT'}
              onChange={(e) => setParams((p) => patchParam(p, { sort: e.target.value }))}
            >
              {declarationSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-end gap-2 text-sm">
            <input
              type="checkbox"
              checked={Boolean(params.pendingReviewOnly)}
              onChange={(e) =>
                setParams((p) =>
                  patchParam(p, { pendingReviewOnly: e.target.checked ? true : false }),
                )
              }
            />
            <span>只看待复核</span>
          </label>
          <div className="flex items-end">
            <Button variant="ghost" onClick={() => setParams({ page: 0, size: params.size ?? 20 })}>
              清除筛选
            </Button>
          </div>
        </div>
      </SectionCard>

      {error && (
        <SectionCard title="操作失败">
          <div className="flex items-start gap-2">
            <TriangleAlert className="text-destructive mt-0.5 size-5" />
            <p className="text-destructive text-sm">{error}</p>
          </div>
        </SectionCard>
      )}

      {loading && !data ? (
        <SectionCard title="报关单列表">
          <p className="text-muted-foreground text-sm">正在加载报关单…</p>
        </SectionCard>
      ) : (data?.rows.length ?? 0) === 0 ? (
        <SectionCard title="报关单列表">
          <EmptyState
            icon={<FilePlus2 className="size-8" />}
            title="还没有报关单"
            description="报关单按 10.4.1.1 头程发运单生成：先有一张已发出的发运单，才能据它建报关单。"
            action={
              <Button onClick={() => setCreating(true)}>
                <PackagePlus className="size-4" />
                新建报关单
              </Button>
            }
          />
        </SectionCard>
      ) : (
        <SectionCard
          title={`报关单列表（共 ${data?.total ?? 0} 张）`}
          extra={
            <div className="flex items-center gap-2 text-sm">
              <Button
                variant="ghost"
                disabled={(params.page ?? 0) <= 0}
                onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) - 1 }))}
              >
                上一页
              </Button>
              <span className="text-muted-foreground">
                第 {(params.page ?? 0) + 1} /{' '}
                {Math.max(1, Math.ceil((data?.total ?? 0) / (params.size ?? 20)))} 页
              </span>
              <Button
                variant="ghost"
                disabled={(data?.rows.length ?? 0) < (params.size ?? 20)}
                onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
              >
                下一页
              </Button>
            </div>
          }
          bodyClassName="p-0"
        >
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground border-b text-left">
                <tr>
                  <th className="px-4 py-3 font-medium">报关单号</th>
                  <th className="px-4 py-3 font-medium">来源发运单</th>
                  <th className="px-4 py-3 font-medium">出口商 → 收货人</th>
                  <th className="px-4 py-3 font-medium">贸易方式</th>
                  <th className="px-4 py-3 font-medium">申报货值</th>
                  <th className="px-4 py-3 font-medium">汇率快照</th>
                  <th className="px-4 py-3 font-medium">复核轮次</th>
                  <th className="px-4 py-3 font-medium">提交于</th>
                  <th className="px-4 py-3 font-medium">状态</th>
                  <th className="px-4 py-3 font-medium"></th>
                </tr>
              </thead>
              <tbody>
                {(data?.rows ?? []).map((row) => (
                  <tr key={row.id} className="hover:bg-muted/50 border-b last:border-0">
                    <td className="px-4 py-3 font-medium">{row.declarationCode}</td>
                    <td className="text-muted-foreground px-4 py-3">{row.sourceShipmentCode}</td>
                    <td className="px-4 py-3">
                      <div>{row.exporterName}</div>
                      <div className="text-muted-foreground text-xs">
                        {row.consigneeName}（{row.consigneeCountryCode}）
                      </div>
                    </td>
                    <td className="px-4 py-3">{row.tradeModeLabel}</td>
                    <td className="px-4 py-3">
                      {money(row.declarationValue, row.declarationCurrency)}
                    </td>
                    <td className="text-muted-foreground px-4 py-3 text-xs">
                      {rateText(row.exchangeRate, row.exchangeRateScale, row.exchangeRateDate)}
                    </td>
                    <td className="px-4 py-3">
                      {row.reviewRound}
                      {row.amendmentCount ? ` · 改单 ${row.amendmentCount}` : ''}
                    </td>
                    <td className="text-muted-foreground px-4 py-3 text-xs">
                      {moment(row.submittedAt)}
                    </td>
                    <td className="px-4 py-3">
                      {row.statusLabel || declarationStatusLabels[row.status] || row.status}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Button variant="ghost" onClick={() => setOpenId(row.id)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {openId && (
        <CustomsDetailModal
          id={openId}
          onClose={() => setOpenId(null)}
          onChanged={() => void load()}
        />
      )}
      {creating && (
        <CustomsCreateModal
          legalEntities={legalEntities}
          caps={caps}
          onClose={() => setCreating(false)}
          onCreated={() => {
            setCreating(false);
            void load();
          }}
        />
      )}
    </div>
  );
}

/**
 * 报关单详情：三单据状态表 + 明细 + 提交/结果回执 + 事件时间线 + 按状态算出的动作入口。
 *
 * <p>动作按钮由 {@link declarationActions} 按当前状态算出，不在这里手写 if 状态链 ——
 * 手写的状态链在状态机改表时会静默漂移，界面就会给出一个注定被拒的动作。
 */
function CustomsDetailModal({
  id,
  onClose,
  onChanged,
}: {
  id: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      setDetail(await fetchCustomsDeclarationDetail(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const row = detail?.declaration ?? null;
  const actions = row ? declarationActions(row.status) : [];
  const docs = detail?.documents ?? [];
  const ready = documentsReady(docs);

  /** 需要理由的动作统一走这里：理由为空时**前端就拦下**，不让用户白跑一趟。 */
  const withReason = async (label: string, run: (reason: string) => Promise<unknown>) => {
    const reason = window.prompt(`${label}（必填）`);
    if (!reason?.trim()) {
      setError(`${label}必须给原因`);
      return;
    }
    await run(reason.trim());
  };

  const run = async (action: string) => {
    if (!row) return;
    setBusy(true);
    setError(null);
    try {
      const i = row.id;
      switch (action) {
        case 'submitForReview':
          await submitCustomsForReview(i, { notes: '' });
          break;
        case 'review': {
          const notes = window.prompt('复核意见（必填）');
          if (!notes?.trim()) {
            setError('复核必须留意见：复核是本模块的责任落点');
            return;
          }
          await reviewCustomsDeclaration(i, { reviewNotes: notes.trim() });
          break;
        }
        case 'rejectReview':
          await withReason('复核退回原因', (reason) => rejectCustomsReview(i, { reason }));
          break;
        case 'withdrawReview':
          await withReason('撤回复核原因', (reason) => withdrawCustomsReview(i, { reason }));
          break;
        case 'submit': {
          const reference = window.prompt('报关行受理参考号（可留空）') ?? '';
          const broker = window.prompt('报关行名称', row.statusLabel ? '报关行' : '') ?? '';
          await submitCustomsDeclaration(i, {
            submissionReference: reference.trim(),
            brokerName: broker.trim(),
            submissionResult: 'ACCEPTED',
            resultReason: '',
          });
          break;
        }
        case 'recordResult': {
          const type = window.prompt('海关结果：CLEARED 放行 / REJECTED 退单 / AMENDED 改单');
          const normalized = type?.trim().toUpperCase() ?? '';
          if (!['CLEARED', 'REJECTED', 'AMENDED'].includes(normalized)) {
            setError('海关结果只能是 CLEARED / REJECTED / AMENDED 三选一');
            return;
          }
          const reason = window.prompt('结果原因（必填：放行 / 退单 / 改单都要能追责）');
          if (!reason?.trim()) {
            setError('海关结果原因必填');
            return;
          }
          const resultReference = window.prompt('海关 / 报关行单号（可留空）') ?? '';
          await recordCustomsResult(i, {
            resultType: normalized,
            resultReference: resultReference.trim(),
            reason: reason.trim(),
            // 结果时间显式给「现在」而不是留空：契约里 resultAt 是必填字符串，
            // 后端对 null 才会回落成 now，显式给值两种读法一致且不依赖回落行为。
            resultAt: new Date().toISOString(),
            sourceSystem: 'MANUAL_ENTRY',
            sourceReference: 'WEB',
          });
          break;
        }
        case 'amend':
          await withReason('改单原因', (reason) =>
            amendCustomsDeclaration(i, { reason, resultReference: '' }),
          );
          break;
        case 'cancel':
          await withReason('取消原因', (reason) => cancelCustomsDeclaration(i, { reason }));
          break;
        default:
          return;
      }
      await load();
      onChanged();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const generateDoc = async (type: string) => {
    if (!row) return;
    setBusy(true);
    setError(null);
    try {
      await generateCustomsDocument(row.id, { documentType: type, documentNo: '' });
      await load();
      onChanged();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const finalizeDoc = async (doc: CustomsDocumentRow) => {
    if (!row) return;
    setBusy(true);
    setError(null);
    try {
      await finalizeCustomsDocument(row.id, doc.id, { documentNo: '' });
      await load();
      onChanged();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-5xl">
        <ModalHeader>
          <ModalTitle>{row?.declarationCode ?? '报关单详情'}</ModalTitle>
          <ModalDescription>
            {row
              ? `来源 ${row.sourceShipmentCode} · ${row.tradeModeLabel} · ${row.transportMode}`
              : '正在加载…'}
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-4">
          {error && (
            <div className="flex items-start gap-2">
              <TriangleAlert className="text-destructive mt-0.5 size-5" />
              <p className="text-destructive text-sm">{error}</p>
            </div>
          )}

          {row && (
            <div className="grid gap-3 md:grid-cols-4">
              <Fact
                label="状态"
                value={row.statusLabel || declarationStatusLabels[row.status] || row.status}
              />
              <Fact label="复核轮次" value={String(row.reviewRound)} />
              <Fact label="改单次数" value={String(row.amendmentCount ?? 0)} />
              <Fact label="明细行数" value={String(row.totalLineCount ?? 0)} />
              <Fact label="申报货值" value={money(row.declarationValue, row.declarationCurrency)} />
              <Fact label="折算本位币" value={money(row.declarationValueBase, row.baseCurrency)} />
              <Fact
                label="汇率快照"
                value={rateText(row.exchangeRate, row.exchangeRateScale, row.exchangeRateDate)}
              />
              <Fact label="出口国 → 目的国" value={`${row.consigneeCountryCode}`} />
              <Fact label="复核于" value={moment(row.reviewedAt)} />
              <Fact label="提交于" value={moment(row.submittedAt)} />
              <Fact label="放行于" value={moment(row.clearedAt)} />
              <Fact label="版本" value={String(row.version)} />
            </div>
          )}

          {detail?.reviewGateNote && (
            <p className="text-muted-foreground border-l-2 pl-3 text-sm">{detail.reviewGateNote}</p>
          )}
          {detail?.sourceShipmentNote && (
            <p className="text-muted-foreground border-l-2 pl-3 text-sm">
              {detail.sourceShipmentNote}
            </p>
          )}

          {/* 三单据：齐备且都定稿才能提交报关行，所以做成一张状态表 */}
          <SectionCard
            title="三单据（报关单 / 装箱单 / 商业发票）"
            description={
              ready.ready
                ? '三份齐备且已定稿，可以提交报关行。'
                : `还差：${
                    [
                      ...ready.missing.map((t) => `${documentTypeLabels[t]} 未生成`),
                      ...ready.drafts.map((t) => `${documentTypeLabels[t]} 未定稿`),
                    ].join('、') || '—'
                  }`
            }
          >
            <div className="space-y-2">
              {['CUSTOMS_DECLARATION', 'PACKING_LIST', 'COMMERCIAL_INVOICE'].map((type) => {
                const latest = docs
                  .filter((d) => d.documentType === type && d.status !== 'SUPERSEDED')
                  .sort((a, b) => b.documentVersion - a.documentVersion)[0];
                return (
                  <div
                    key={type}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3"
                  >
                    <div className="text-sm">
                      <strong>{documentTypeLabels[type]}</strong>
                      {latest ? (
                        <span className="text-muted-foreground ml-2">
                          v{latest.documentVersion} · {latest.documentNo || '无单号'} ·{' '}
                          {latest.status === 'FINAL' ? '已定稿' : latest.status} ·{' '}
                          {money(latest.totalAmount, latest.currency)}
                        </span>
                      ) : (
                        <span className="text-muted-foreground ml-2">未生成</span>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <Button
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void generateDoc(type)}
                      >
                        <FilePlus2 className="size-4" />
                        生成新版本
                      </Button>
                      {latest && latest.status !== 'FINAL' && (
                        <Button
                          variant="ghost"
                          disabled={busy}
                          onClick={() => void finalizeDoc(latest)}
                        >
                          <FileCheck2 className="size-4" />
                          定稿
                        </Button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </SectionCard>

          {/* 明细：申报要素齐备性是复核闸检查的东西，必须看得见 */}
          <SectionCard title={`申报明细（${detail?.lines.length ?? 0} 行）`}>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground border-b text-left">
                  <tr>
                    <th className="px-3 py-2 font-medium">行</th>
                    <th className="px-3 py-2 font-medium">商品</th>
                    <th className="px-3 py-2 font-medium">申报品名</th>
                    <th className="px-3 py-2 font-medium">HS 编码</th>
                    <th className="px-3 py-2 font-medium">申报要素</th>
                    <th className="px-3 py-2 font-medium">数量</th>
                    <th className="px-3 py-2 font-medium">申报货值</th>
                    <th className="px-3 py-2 font-medium">毛重</th>
                    <th className="px-3 py-2 font-medium">箱数</th>
                  </tr>
                </thead>
                <tbody>
                  {(detail?.lines ?? []).map((line) => (
                    <tr key={line.id} className="border-b last:border-0">
                      <td className="px-3 py-2">{line.lineNo}</td>
                      <td className="px-3 py-2">
                        <div>{line.skuCode}</div>
                        <div className="text-muted-foreground text-xs">{line.skuName}</div>
                      </td>
                      <td className="px-3 py-2">{line.declaredItemName}</td>
                      <td className="px-3 py-2">
                        {line.hsCode}
                        <span className="text-muted-foreground ml-1 text-xs">
                          {line.hsCodeVerified ? '已核' : '人工'}
                        </span>
                      </td>
                      <td className="px-3 py-2">
                        <span
                          className={
                            elementComplete(line.declarationElementStatus)
                              ? 'text-foreground'
                              : 'text-destructive'
                          }
                        >
                          {line.declarationElementStatusLabel ||
                            elementStatusLabels[line.declarationElementStatus] ||
                            line.declarationElementStatus}
                        </span>
                      </td>
                      <td className="px-3 py-2">
                        {fmt(line.quantity, 4)} {line.uom}
                      </td>
                      <td className="px-3 py-2">{money(line.declaredValue)}</td>
                      <td className="px-3 py-2">{fmt(line.grossWeightKg, 3)} kg</td>
                      <td className="px-3 py-2">{line.packageCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {(detail?.lines.length ?? 0) === 0 && (
              <p className="text-muted-foreground text-sm">这张报关单还没有明细行。</p>
            )}
          </SectionCard>

          <SectionCard title={`海关结果回执（${detail?.results.length ?? 0} 条）`}>
            {detail?.results.length ? (
              <ul className="space-y-2 text-sm">
                {detail.results.map((r) => (
                  <li key={r.id} className="rounded-md border p-3">
                    <strong>
                      {r.resultTypeLabel || resultTypeLabels[r.resultType] || r.resultType}
                    </strong>
                    <span className="text-muted-foreground ml-2 text-xs">
                      {r.resultReference || '无单号'} · {moment(r.resultAt)}
                    </span>
                    <div className="mt-1">{r.reason}</div>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground text-sm">还没有收到海关结果回执。</p>
            )}
          </SectionCard>

          <SectionCard title={`事件时间线（${detail?.events.length ?? 0} 条）`}>
            <ul className="space-y-2 text-sm">
              {(detail?.events ?? []).map((e) => (
                <li key={e.id} className="flex items-start gap-2">
                  <span className="text-muted-foreground w-40 shrink-0 text-xs">
                    {moment(e.occurredAt)}
                  </span>
                  <span>
                    <strong>{eventKindLabels[e.kind] ?? e.kind}</strong>
                    {e.fromStatus && e.toStatus && (
                      <span className="text-muted-foreground ml-2 text-xs">
                        {declarationStatusLabels[e.fromStatus] ?? e.fromStatus} →{' '}
                        {declarationStatusLabels[e.toStatus] ?? e.toStatus}
                      </span>
                    )}
                    {e.reason && <div className="text-muted-foreground">{e.reason}</div>}
                  </span>
                </li>
              ))}
            </ul>
          </SectionCard>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
          {actions.map((action) => (
            <Button key={action} disabled={busy} onClick={() => void run(action)}>
              {actionLabel(action)}
            </Button>
          ))}
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border p-3">
      <div className="text-muted-foreground text-xs">{label}</div>
      <div className="text-sm font-medium">{value}</div>
    </div>
  );
}

interface DraftLine {
  sourceLineId: string;
  skuCode: string;
  declaredItemName: string;
  hsCode: string;
  declarationElementStatus: string;
  specMaterial: string;
  usage: string;
  brand: string;
  modelNo: string;
  originCountryCode: string;
  quantity: string;
  uom: string;
  declaredUnitValue: string;
  declaredValue: string;
  grossWeightKg: string;
  netWeightKg: string;
  volumeCbm: string;
  packageCount: string;
}

/** 建单表单。明细行从**真实头程发运单的行**预填，不让用户凭空敲 SKU。 */
function CustomsCreateModal({
  legalEntities,
  caps,
  onClose,
  onCreated,
}: {
  legalEntities: LegalEntityOption[];
  caps: CustomsCapabilities | null;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [shipmentKeyword, setShipmentKeyword] = useState('');
  const [shipmentId, setShipmentId] = useState('');
  const [shipmentLabel, setShipmentLabel] = useState('');
  const [shipments, setShipments] = useState<
    { id: string; shipmentCode: string; statusLabel: string }[]
  >([]);
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [legalEntityId, setLegalEntityId] = useState('');
  const [tradeMode, setTradeMode] = useState('GENERAL_TRADE');
  const [transportMode, setTransportMode] = useState('SEA');
  const [exporterName, setExporterName] = useState('');
  const [exporterCountryCode, setExporterCountryCode] = useState('CN');
  const [consigneeName, setConsigneeName] = useState('');
  const [consigneeCountryCode, setConsigneeCountryCode] = useState('US');
  const [iorName, setIorName] = useState('');
  const [iorCountryCode, setIorCountryCode] = useState('US');
  const [brokerName, setBrokerName] = useState('');
  const [brokerCode, setBrokerCode] = useState('');
  const [brokerContact, setBrokerContact] = useState('');
  const [destinationCountryCode, setDestinationCountryCode] = useState('US');
  const [destinationCustomsDistrict, setDestinationCustomsDistrict] = useState('');
  const [departurePort, setDeparturePort] = useState('');
  const [destinationPort, setDestinationPort] = useState('');
  const [billOfLadingNo, setBillOfLadingNo] = useState('');
  const [declarationCurrency, setDeclarationCurrency] = useState('USD');
  const [baseCurrency, setBaseCurrency] = useState('CNY');
  const [exchangeRate, setExchangeRate] = useState('');
  const [exchangeRateScale, setExchangeRateScale] = useState('6');
  const [exchangeRateDate, setExchangeRateDate] = useState('');
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const page = await fetchCustomsSourceShipments({ keyword: shipmentKeyword, size: 50 });
        setShipments(
          page.rows.map((r) => ({
            id: r.id,
            shipmentCode: r.shipmentCode,
            statusLabel: r.statusLabel,
          })),
        );
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [shipmentKeyword]);

  /** 选中来源发运单后真实拉它的明细行，逐行预填申报要素。 */
  const chooseShipment = async (id: string) => {
    setShipmentId(id);
    setError(null);
    const picked = shipments.find((s) => s.id === id);
    setShipmentLabel(picked?.shipmentCode ?? '');
    if (!id) return;
    setBusy(true);
    try {
      const sourceLines = await fetchCustomsSourceShipmentLines(id);
      setLines(
        sourceLines.map((l) => ({
          sourceLineId: l.id,
          skuCode: l.skuCode,
          declaredItemName: l.declaredItemName,
          hsCode: l.hsCode,
          declarationElementStatus: 'MANUAL_PENDING',
          specMaterial: '',
          usage: '',
          brand: l.brand,
          modelNo: '',
          originCountryCode: l.originCountryCode || 'CN',
          quantity: String(l.shippedQuantity ?? ''),
          uom: 'PCS',
          declaredUnitValue: String(l.declaredUnitValue ?? ''),
          declaredValue: String(l.declaredValue ?? ''),
          grossWeightKg: String(l.grossWeightKg ?? ''),
          netWeightKg: String(l.netWeightKg ?? ''),
          volumeCbm: String(l.volumeCbm ?? ''),
          packageCount: String(l.cartonCount ?? ''),
        })),
      );
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const patchLine = (index: number, patch: Partial<DraftLine>) =>
    setLines((prev) => prev.map((l, i) => (i === index ? { ...l, ...patch } : l)));

  const submit = async () => {
    setError(null);
    if (!shipmentId) {
      setError('必须选择来源头程发运单：报关单按发货单生成，不能凭空建');
      return;
    }
    if (lines.length === 0) {
      setError('至少要有一条明细：没有货物就没有申报');
      return;
    }
    setBusy(true);
    try {
      await createCustomsDeclaration({
        sourceShipmentId: shipmentId,
        legalEntityId,
        tradeMode,
        exporterName,
        exporterCountryCode,
        consigneeName,
        consigneeCountryCode,
        iorName,
        iorCountryCode,
        brokerName,
        brokerCode,
        brokerContact,
        destinationCountryCode,
        destinationCustomsDistrict,
        departurePort,
        destinationPort,
        transportMode,
        billOfLadingNo,
        declarationCurrency,
        baseCurrency,
        exchangeRate: num(exchangeRate) ?? 0,
        exchangeRateScale: Number(exchangeRateScale) || 0,
        exchangeRateDate: exchangeRateDate || new Date().toISOString().slice(0, 10),
        notes,
        lines: lines.map((l) => ({
          sourceLineId: l.sourceLineId,
          declaredItemName: l.declaredItemName,
          hsCode: l.hsCode,
          declarationElementStatus: l.declarationElementStatus,
          declarationElements: buildDeclarationElements(l),
          specMaterial: l.specMaterial,
          usage: l.usage,
          brand: l.brand,
          modelNo: l.modelNo,
          originCountryCode: l.originCountryCode,
          quantity: num(l.quantity) ?? 0,
          uom: l.uom,
          declaredUnitValue: num(l.declaredUnitValue) ?? 0,
          declaredValue: num(l.declaredValue) ?? 0,
          grossWeightKg: num(l.grossWeightKg) ?? 0,
          netWeightKg: num(l.netWeightKg) ?? 0,
          volumeCbm: num(l.volumeCbm) ?? 0,
          packageCount: Number(l.packageCount) || 0,
        })),
      });
      onCreated();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-5xl">
        <ModalHeader>
          <ModalTitle>新建报关单</ModalTitle>
          <ModalDescription>
            来源固定是 10.4.1.1
            头程发运单（一票货只报一次关）；建出来是草稿，提交报关行前必须走人工复核。
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-4">
          {error && (
            <div className="flex items-start gap-2">
              <TriangleAlert className="text-destructive mt-0.5 size-5" />
              <p className="text-destructive text-sm">{error}</p>
            </div>
          )}

          <SectionCard title="来源发运单" description="按关键字找出已发出的头程发运单">
            <div className="grid gap-3 md:grid-cols-2">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">搜索发运单</span>
                <input
                  className="rounded-md border px-3 py-2"
                  placeholder="发运单号 / 订舱号 / 承运人"
                  value={shipmentKeyword}
                  onChange={(e) => setShipmentKeyword(e.target.value)}
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">选择发运单</span>
                <select
                  className="rounded-md border px-3 py-2"
                  value={shipmentId}
                  onChange={(e) => void chooseShipment(e.target.value)}
                >
                  <option value="">请选择</option>
                  {shipments.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.shipmentCode}（{s.statusLabel}）
                    </option>
                  ))}
                </select>
              </label>
            </div>
            {shipmentId && (
              <p className="text-muted-foreground mt-2 text-sm">
                已选 {shipmentLabel}，明细行已预填 {lines.length} 行 ——
                请逐行补齐申报要素并把齐备性标为「已齐备」。
              </p>
            )}
          </SectionCard>

          <SectionCard title="申报单头">
            <div className="grid gap-3 md:grid-cols-4">
              <Field label="法人主体" value={legalEntityId} onChange={setLegalEntityId} select>
                <option value="">请选择</option>
                {legalEntities.map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.code} {e.name}
                  </option>
                ))}
              </Field>
              <Field
                label="贸易方式"
                value={tradeMode}
                onChange={setTradeMode}
                select
                options={(caps?.tradeModes ?? []).map((o) => [o.value, o.label])}
              />
              <Field
                label="运输方式"
                value={transportMode}
                onChange={setTransportMode}
                select
                options={(caps?.transportModes ?? []).map((o) => [o.value, o.label])}
              />
              <Field label="出口商" value={exporterName} onChange={setExporterName} />
              <Field label="出口国" value={exporterCountryCode} onChange={setExporterCountryCode} />
              <Field label="收货人" value={consigneeName} onChange={setConsigneeName} />
              <Field
                label="收货国"
                value={consigneeCountryCode}
                onChange={setConsigneeCountryCode}
              />
              <Field
                label="目的国"
                value={destinationCountryCode}
                onChange={setDestinationCountryCode}
              />
              <Field label="IOR 名称" value={iorName} onChange={setIorName} />
              <Field label="IOR 国别" value={iorCountryCode} onChange={setIorCountryCode} />
              <Field label="报关行" value={brokerName} onChange={setBrokerName} />
              <Field label="报关行代码" value={brokerCode} onChange={setBrokerCode} />
              <Field label="报关行联系人" value={brokerContact} onChange={setBrokerContact} />
              <Field
                label="目的关区"
                value={destinationCustomsDistrict}
                onChange={setDestinationCustomsDistrict}
              />
              <Field label="启运港" value={departurePort} onChange={setDeparturePort} />
              <Field label="目的港" value={destinationPort} onChange={setDestinationPort} />
              <Field label="提单号" value={billOfLadingNo} onChange={setBillOfLadingNo} />
              <Field
                label="申报币种"
                value={declarationCurrency}
                onChange={setDeclarationCurrency}
              />
              <Field label="本位币" value={baseCurrency} onChange={setBaseCurrency} />
              <Field label="汇率" value={exchangeRate} onChange={setExchangeRate} />
              <Field
                label="汇率精度刻度"
                value={exchangeRateScale}
                onChange={setExchangeRateScale}
              />
              <Field
                label="汇率日期"
                value={exchangeRateDate}
                onChange={setExchangeRateDate}
                placeholder="YYYY-MM-DD"
              />
              <Field label="备注" value={notes} onChange={setNotes} />
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              汇率是<strong>快照</strong>：填进去之后海关事后复核要能回答「当时按哪个汇率申报的」，
              所以刻度与日期一并落库，读取时不重新取当前汇率。
            </p>
          </SectionCard>

          <SectionCard title={`申报明细（${lines.length} 行）`}>
            {lines.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                先选择一张来源发运单，明细行会据此预填。
              </p>
            ) : (
              <div className="space-y-3">
                {lines.map((line, index) => (
                  <div key={line.sourceLineId} className="rounded-md border p-3">
                    <div className="mb-2 text-sm">
                      <strong>{line.skuCode}</strong>
                      <span className="text-muted-foreground ml-2">
                        申报品名与 HS 编码是海关口径，与销售标题独立
                      </span>
                    </div>
                    <div className="grid gap-3 md:grid-cols-4">
                      <Field
                        label="申报品名"
                        value={line.declaredItemName}
                        onChange={(v) => patchLine(index, { declaredItemName: v })}
                      />
                      <Field
                        label="HS 编码"
                        value={line.hsCode}
                        onChange={(v) => patchLine(index, { hsCode: v })}
                      />
                      <Field
                        label="申报要素齐备性"
                        value={line.declarationElementStatus}
                        onChange={(v) => patchLine(index, { declarationElementStatus: v })}
                        select
                        options={Object.entries(elementStatusLabels)}
                      />
                      <Field
                        label="品牌"
                        value={line.brand}
                        onChange={(v) => patchLine(index, { brand: v })}
                      />
                      <Field
                        label="规格材质"
                        value={line.specMaterial}
                        onChange={(v) => patchLine(index, { specMaterial: v })}
                      />
                      <Field
                        label="用途"
                        value={line.usage}
                        onChange={(v) => patchLine(index, { usage: v })}
                      />
                      <Field
                        label="型号"
                        value={line.modelNo}
                        onChange={(v) => patchLine(index, { modelNo: v })}
                      />
                      <Field
                        label="原产国"
                        value={line.originCountryCode}
                        onChange={(v) => patchLine(index, { originCountryCode: v })}
                      />
                      <Field
                        label="数量"
                        value={line.quantity}
                        onChange={(v) => patchLine(index, { quantity: v })}
                      />
                      <Field
                        label="计量单位"
                        value={line.uom}
                        onChange={(v) => patchLine(index, { uom: v })}
                      />
                      <Field
                        label="申报单价"
                        value={line.declaredUnitValue}
                        onChange={(v) => patchLine(index, { declaredUnitValue: v })}
                      />
                      <Field
                        label="申报货值"
                        value={line.declaredValue}
                        onChange={(v) => patchLine(index, { declaredValue: v })}
                      />
                      <Field
                        label="毛重 kg"
                        value={line.grossWeightKg}
                        onChange={(v) => patchLine(index, { grossWeightKg: v })}
                      />
                      <Field
                        label="净重 kg"
                        value={line.netWeightKg}
                        onChange={(v) => patchLine(index, { netWeightKg: v })}
                      />
                      <Field
                        label="体积 CBM"
                        value={line.volumeCbm}
                        onChange={(v) => patchLine(index, { volumeCbm: v })}
                      />
                      <Field
                        label="箱数"
                        value={line.packageCount}
                        onChange={(v) => patchLine(index, { packageCount: v })}
                      />
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button disabled={busy} onClick={() => void submit()}>
            创建草稿
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 申报要素是可后补的 jsonb 结构：现在由人工逐项填，3.7.1.1 交付后由它回填。 */
function buildDeclarationElements(line: DraftLine) {
  const elements: Record<string, string> = {};
  if (line.specMaterial.trim()) elements.material = line.specMaterial.trim();
  if (line.usage.trim()) elements.usage = line.usage.trim();
  if (line.brand.trim()) elements.brand = line.brand.trim();
  if (line.modelNo.trim()) elements.model = line.modelNo.trim();
  return elements;
}

function num(value: string): number | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function Field({
  label,
  value,
  onChange,
  select,
  options,
  placeholder,
  children,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  select?: boolean;
  options?: readonly (readonly [string, string])[];
  placeholder?: string;
  /** 直接给 option 子节点（数据来自别的参照接口、形状不是 [value,label] 时用它）。 */
  children?: ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-muted-foreground">{label}</span>
      {select ? (
        <select
          className="rounded-md border px-3 py-2"
          value={value}
          onChange={(e) => onChange(e.target.value)}
        >
          {children ??
            options?.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
        </select>
      ) : (
        <input
          className="rounded-md border px-3 py-2"
          value={value}
          placeholder={placeholder}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
    </label>
  );
}
