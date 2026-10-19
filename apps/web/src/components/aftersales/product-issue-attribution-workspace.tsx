'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  AttributionDetail,
  AttributionPage,
  LotFlagPage,
  ProductAttributionCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import {
  comparePhotos,
  concludeAttribution,
  concludeBlockers,
  createAttribution,
  dominantStageText,
  fetchAttribution,
  fetchAttributions,
  fetchCapabilities,
  fetchLotFlags,
  flagTone,
  registerPhotos,
  STAGE_LABELS,
  stageBreakdownRows,
  stageRequiresLot,
  stageTone,
} from './product-issue-attribution-model';

/**
 * 11.1.2.2 商品问题归因。
 *
 * <p>蓝图：「把质量问题归因到具体环节（生产、运输、仓储、描述），支持批次追溯与照片比对，
 * 形成对供应商或流程的改进建议。」
 * 输出：「<b>归因结论 + 批次质量标记</b>。」
 * 加粗句：「<b>批次归因是「同一个批次退回来 10 件」和「10 个批次各退 1 件」的区别</b>，
 * 前者是供应商责任，后者可能是运输或买家因素。」
 *
 * <p><b>页面围绕那件加粗句组织</b>：批次质量标记那一块把
 * 「这个批次退回来多少件 / 归到了哪些环节 / 主导环节是谁」并排显示，
 * 并把口径（低于 N 件不标）原样印出来 —— 判据不透明的话，
 * 「为什么这个批次没被标」会变成一件谁也答不上来的事。
 *
 * <p><b>页面刻意没有的四个东西</b>：
 *
 * <ol>
 *   <li><b>相似度滑块 / 照片比对结果选择</b>：本仓没有图像库、没有模型、没有视觉服务接入点，
 *       比对口径是<b>内容指纹一致 / 不一致</b>。渲染一个相似度控件就是在冒充图像识别能力。</li>
 *   <li><b>批次标记计数的输入框</b>：{@code suspectReturnCount} / {@code affectedReturnQty}
 *       是统计结论，由后端聚合 + DB 触发器重算比对，传输层根本收不到。</li>
 *   <li><b>「AI 生成改进建议」</b>：改进建议是<b>人写的必填文字</b>，
 *       标成 AI 生成就是在冒充智能能力。</li>
 *   <li><b>「发起供应商索赔」「写入评级」</b>：属 6.4.2.2 / 6.1.1.3，<b>均未交付</b>。
 *       渲染一个注定被拒的按钮等于告诉运营系统坏了，而真相是这条路径本就不该存在 ——
 *       capabilities 会如实把它们报成 available=false。</li>
 * </ol>
 */
export function ProductIssueAttributionWorkspace() {
  const [list, setList] = useState<AttributionPage | null>(null);
  const [flags, setFlags] = useState<LotFlagPage | null>(null);
  const [caps, setCaps] = useState<ProductAttributionCapabilities | null>(null);
  const [selected, setSelected] = useState<AttributionDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');

  // 归因表单（草稿态本地状态；真正的校验在后端与 DB 触发器各一次）。
  const [stage, setStage] = useState('');
  const [note, setNote] = useState('');
  const [suggestion, setSuggestion] = useState('');
  const [newCaseId, setNewCaseId] = useState('');
  const [newLotId, setNewLotId] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [l, f, c] = await Promise.all([
        fetchAttributions({ page: 0, size: 20 }),
        fetchLotFlags({ page: 0, size: 20 }),
        fetchCapabilities(),
      ]);
      setList(l);
      setFlags(f);
      setCaps(c);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const open = useCallback(async (id: string) => {
    setDetailLoading(true);
    setActionError('');
    try {
      setSelected(await fetchAttribution(id));
      setStage('');
      setNote('');
      setSuggestion('');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '读取归因单失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const act = useCallback(
    async (work: () => Promise<AttributionDetail>) => {
      setActionError('');
      try {
        const next = await work();
        setSelected(next);
        await load();
      } catch (e) {
        // ⚠️ 后端的拒绝消息**原样显示**：环节规则、件数口径、触发器守卫的理由都在里面，
        //   换成一句「操作失败」会把可操作的诊断信息丢掉。
        setActionError(e instanceof Error ? e.message : '操作失败');
      }
    },
    [load],
  );

  const draft = selected?.row.status === 'DRAFT';
  const blockers = concludeBlockers(stage, note, suggestion, Boolean(selected?.row.lotId));

  return (
    <div className="space-y-6">
      <PageHeader
        title="商品问题归因"
        description="把质量问题归因到具体环节（生产 / 运输 / 仓储 / 描述），支持批次追溯与照片比对，形成改进建议。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
          </Button>
        }
      />

      {caps?.sections.some((s) => !s.available) ? (
        <SectionCard title="本模块刻意不做的事">
          <ul className="text-muted-foreground space-y-1 text-sm">
            {caps.sections
              .filter((s) => !s.available)
              .map((s) => (
                <li key={s.section}>
                  <Badge tone="warning">{s.ownerModule}</Badge> {s.label} —— {s.reason}
                </li>
              ))}
          </ul>
        </SectionCard>
      ) : null}

      {error ? (
        <div className="text-destructive flex items-center gap-2 text-sm">
          <TriangleAlert className="h-4 w-4" /> {error}
        </div>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-4">
        <StatCard label="归因单" value={list?.summary.total ?? 0} />
        <StatCard label="已归因" value={list?.summary.attributed ?? 0} />
        <StatCard label="被取代（改判留痕）" value={list?.summary.superseded ?? 0} />
        {/* ⚠️ 数量在契约里是十进制字符串（BigDecimal，禁浮点），这里转成数字只为了喂 StatCard；
            展示与判读一律用后端那个字符串，不在前端做任何数量运算。 */}
        <StatCard label="已归因件数" value={list?.summary.attributedQty ?? 0} />
      </div>

      {/* ---- 批次质量标记：蓝图加粗句的计数落点 ---- */}
      <SectionCard title="批次质量标记（归因结论 + 批次质量标记）">
        <p className="text-muted-foreground mb-3 text-sm">{flags?.thresholdNote}</p>
        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : flags && flags.rows.length === 0 ? (
          <EmptyState
            title="还没有批次被标记"
            description="批次标记是**归因的产物**：一张归因单挂上批次并出归因结论后，标记才会出现。这不是数据缺失，是这件事还没发生。"
          />
        ) : flags ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">批次</th>
                <th className="py-2">SKU</th>
                <th className="py-2">退回件数</th>
                <th className="py-2">归因单数</th>
                <th className="py-2">主导环节</th>
                <th className="py-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {flags.rows.map((row) => (
                <tr key={row.id} className="hover:bg-muted/50 border-b">
                  <td className="py-2 font-mono text-xs">{row.lotCode || '—'}</td>
                  <td className="py-2">{row.skuCode || '—'}</td>
                  <td className="py-2">{row.affectedReturnQty}</td>
                  <td className="py-2">{row.suspectReturnCount}</td>
                  <td className="py-2">
                    <Badge tone={stageTone(row.stageBreakdown ? dominantRow(row) : null)}>
                      {dominantStageText(row.stageBreakdown)}
                    </Badge>
                  </td>
                  <td className="py-2">
                    <Badge tone={flagTone(row.flagStatus)}>{row.flagStatusLabel}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        <p className="text-muted-foreground mt-3 text-xs">{flags?.ownershipNote}</p>
      </SectionCard>

      {/* ---- 归因单列表 ---- */}
      <SectionCard title="归因单">
        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : list && list.rows.length === 0 ? (
          <EmptyState
            title="还没有归因单"
            description="归因单挂在售后单上：先有售后单与质检结论，再开一张归因单登记证据并出结论。"
          />
        ) : list ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">归因单</th>
                <th className="py-2">SKU</th>
                <th className="py-2">批次</th>
                <th className="py-2">数量</th>
                <th className="py-2">状态</th>
                <th className="py-2">环节</th>
              </tr>
            </thead>
            <tbody>
              {list.rows.map((row) => (
                <tr
                  key={row.id}
                  className="hover:bg-muted/50 cursor-pointer border-b"
                  onClick={() => void open(row.id)}
                >
                  <td className="py-2 font-mono text-xs">{row.attributionNo}</td>
                  <td className="py-2">{row.skuCode || '—'}</td>
                  <td className="py-2 font-mono text-xs">{row.lotCode || '未挂批次'}</td>
                  <td className="py-2">{row.attributedQty}</td>
                  <td className="py-2">{row.statusLabel}</td>
                  <td className="py-2">
                    <Badge tone={stageTone(row.attributedStage)}>
                      {row.attributedStage ? STAGE_LABELS[row.attributedStage] : '—'}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        <p className="text-muted-foreground mt-3 text-xs">{list?.stageNote}</p>
      </SectionCard>

      {/* ---- 新建归因单 ---- */}
      <SectionCard title="建归因单">
        <div className="flex flex-wrap items-end gap-2">
          <label className="text-sm">
            <span className="text-muted-foreground block text-xs">售后单 ID</span>
            <Input value={newCaseId} onChange={(e) => setNewCaseId(e.target.value)} />
          </label>
          <label className="text-sm">
            <span className="text-muted-foreground block text-xs">批次 ID（可空）</span>
            <Input value={newLotId} onChange={(e) => setNewLotId(e.target.value)} />
          </label>
          <Button
            size="sm"
            disabled={!newCaseId.trim()}
            onClick={() =>
              void act(async () => {
                const created = await createAttribution({
                  caseId: newCaseId.trim(),
                  // ⚠️ 契约里这些字段是「必填 + 可空」而不是「可选」
                  //   （exactOptionalPropertyTypes 开着，省略一个就不是同一个类型），
                  //   所以这里逐个显式给出 null，而不是只挑要填的那几个。
                  inspectionId: null,
                  reasonSetId: null,
                  lotId: newLotId.trim() || null,
                  lotCode: null,
                  lotProducedOn: null,
                  lotSupplierRefId: null,
                  skuId: null,
                  skuCode: null,
                  skuName: null,
                  // ⚠️ 数量在契约里是 number（本仓 codegen 对 BigDecimal 的统一映射），
                  // 真实精度由后端 NUMERIC(19,6) 兜底；前端不做任何数量运算。
                  attributedQty: 1,
                });
                setNewCaseId('');
                setNewLotId('');
                return created;
              })
            }
          >
            建单
          </Button>
        </div>
        <p className="text-muted-foreground mt-2 text-xs">
          批次由后端经 warehouse 的授权端点（7.3.1.1）真查一次并落成本地快照；留空则不做批次追溯。
        </p>
      </SectionCard>

      {actionError ? (
        <div className="text-destructive flex items-center gap-2 text-sm">
          <TriangleAlert className="h-4 w-4" /> {actionError}
        </div>
      ) : null}

      {/* ---- 归因单详情 ---- */}
      {selected ? (
        <SectionCard title={`归因单 ${selected.row.attributionNo}`}>
          <p className="text-muted-foreground mb-2 text-xs">{selected.photoBasisNote}</p>
          <p className="text-muted-foreground mb-3 text-xs">{selected.crossServiceNote}</p>

          <div className="mb-3 flex flex-wrap gap-2 text-sm">
            <Badge>{selected.row.statusLabel}</Badge>
            <Badge tone={stageTone(selected.row.attributedStage)}>
              {selected.row.attributedStage
                ? STAGE_LABELS[selected.row.attributedStage]
                : '尚未归因'}
            </Badge>
            <span className="text-muted-foreground">
              批次 {selected.row.lotCode || '未挂批次'}
            </span>
            <span className="text-muted-foreground">数量 {selected.row.attributedQty}</span>
          </div>

          {selected.row.attributedStage ? (
            <div className="space-y-2 text-sm">
              <p>
                <span className="text-muted-foreground">归因理由：</span>
                {selected.row.attributionNote}
              </p>
              <p>
                <span className="text-muted-foreground">改进建议：</span>
                {selected.row.improvementSuggestion}
              </p>
            </div>
          ) : null}

          {selected.photos.length > 0 ? (
            <div className="mt-3">
              <h4 className="mb-1 text-sm font-medium">照片（{selected.photos.length}）</h4>
              <ul className="text-muted-foreground space-y-1 text-xs">
                {selected.photos.map((p) => (
                  <li key={p.id} className="font-mono">
                    {p.objectUri} · {p.contentSha256.slice(0, 12)}… · {p.contentBytes} B
                  </li>
                ))}
              </ul>
              <p className="text-muted-foreground mt-1 text-xs">
                比对口径：<b>内容指纹（sha256）一致 / 不一致</b>，不是图像相似度。
              </p>
            </div>
          ) : null}

          {selected.comparisons.length > 0 ? (
            <div className="mt-3">
              <h4 className="mb-1 text-sm font-medium">比对结果</h4>
              <ul className="space-y-1 text-xs">
                {selected.comparisons.map((c) => (
                  <li key={c.id}>
                    <Badge tone={c.compareResult === 'IDENTITY_SAME' ? 'warning' : 'neutral'}>
                      {c.compareResultLabel}
                    </Badge>{' '}
                    <span className="text-muted-foreground font-mono">
                      {c.leftSha256.slice(0, 8)}… vs {c.rightSha256.slice(0, 8)}…
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {selected.lotFlags.length > 0 ? (
            <div className="mt-3">
              <h4 className="mb-1 text-sm font-medium">环节分布</h4>
              <ul className="space-y-1 text-xs">
                {stageBreakdownRows(selected.lotFlags[0]!.stageBreakdown).map((r) => (
                  <li key={r.stage} className="text-muted-foreground">
                    {r.label}：<b>{r.count}</b>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {draft ? (
            <div className="mt-4 space-y-2 border-t pt-3">
              <h4 className="text-sm font-medium">出归因结论</h4>
              <label className="block text-sm">
                <span className="text-muted-foreground block text-xs">归因环节</span>
                <select
                  className="border-input bg-background h-9 w-full rounded border px-2 text-sm"
                  value={stage}
                  onChange={(e) => setStage(e.target.value)}
                >
                  <option value="">请选择</option>
                  {Object.entries(STAGE_LABELS).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                      {stageRequiresLot(value) ? '（必须挂批次）' : ''}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-sm">
                <span className="text-muted-foreground block text-xs">归因理由（必填）</span>
                <textarea
                  className="border-input bg-background min-h-16 w-full rounded border p-2 text-sm"
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                />
              </label>
              <label className="block text-sm">
                <span className="text-muted-foreground block text-xs">
                  改进建议（必填，对供应商或流程）
                </span>
                <textarea
                  className="border-input bg-background min-h-16 w-full rounded border p-2 text-sm"
                  value={suggestion}
                  onChange={(e) => setSuggestion(e.target.value)}
                />
              </label>
              {blockers.length > 0 ? (
                <ul className="text-muted-foreground space-y-0.5 text-xs">
                  {blockers.map((b) => (
                    <li key={b}>· {b}</li>
                  ))}
                </ul>
              ) : null}
              <Button
                size="sm"
                disabled={blockers.length > 0}
                onClick={() =>
                  void act(() =>
                    concludeAttribution(selected.row.id, {
                      attributedStage: stage,
                      attributionNote: note,
                      improvementSuggestion: suggestion,
                    }),
                  )
                }
              >
                出归因结论
              </Button>
            </div>
          ) : (
            <p className="text-muted-foreground mt-4 border-t pt-3 text-xs">
              归因结论<b>终态不可逆</b>：判错了只能改判留痕另起一版，不能就地改数。
              页面刻意不提供「直接改结论」的入口。
            </p>
          )}
        </SectionCard>
      ) : detailLoading ? (
        <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
      ) : null}
    </div>
  );
}

/** 从环节分布取主导环节的枚举值（用于给徽标定语气）。 */
function dominantRow(row: LotFlagPage['rows'][number]): string | null {
  const b = row.stageBreakdown;
  if (!b) return null;
  const pairs: Array<[string, number]> = [
    ['PRODUCTION', b.production],
    ['TRANSPORT', b.transport],
    ['WAREHOUSE', b.warehouse],
    ['DESCRIPTION', b.description],
    ['BUYER_FACTOR', b.buyerFactor],
    ['UNDETERMINED', b.undetermined],
  ];
  const max = Math.max(...pairs.map(([, c]) => c));
  if (max === 0) return null;
  const winners = pairs.filter(([, c]) => c === max);
  return winners.length === 1 ? winners[0]![0] : null;
}
