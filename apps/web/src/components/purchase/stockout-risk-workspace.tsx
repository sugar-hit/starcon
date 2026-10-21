'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Calculator, PackageSearch, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
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
  StockoutCapabilities,
  StockoutRiskItemDetail,
  StockoutVelocityRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  computeSnapshot,
  coverageText,
  dailySalesText,
  fetchCapabilities,
  fetchItem,
  fetchItems,
  fetchVelocities,
  fmt,
  isNoData,
  registerVelocity,
  shopText,
  shortageText,
  stockoutDateText,
  urgencyLabel,
  urgencyTone,
  velocitySourceLabel,
  type StockoutRiskParams,
} from './stockout-risk-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.2.1.1 缺货与断货风险工作区。
 *
 * <p>蓝图：「识别断货风险 SKU，输出补货优先级与缺口量。」输出「断货风险清单
 * （SKU、可用量、日均销、可支撑天数、预计断货日、缺口量、涉及店铺），按紧急度排序」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>七项一个不能少</b>：SKU / 可用量 / 日均销 / 可支撑天数 / 预计断货日 /
 *       缺口量 / 涉及店铺 全部同排可见 —— 少一列就会有人把「没日均销」当成「不缺货」。</li>
 *   <li><b>「无数据」显示成「无数据」</b>，不显示 0。0 日均销量会让可支撑天数变成无穷。</li>
 *   <li><b>紧急度带依据</b>：清单行直接显示后端给的排序依据原文，
 *       「为什么这条排在前面」不用点进去再猜。</li>
 *   <li><b>缺口量与可用量分开</b>：缺口量回答「还差多少」，可用量回答「现在还有多少」。</li>
 *   <li><b>销售速度登记在页面上</b>：清单里「无数据」那一档要能就地补登记 ——
 *       否则运营看到一堆无数据只能干瞪眼。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function StockoutRiskWorkspace() {
  const [params, setParams] = useState<StockoutRiskParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchItems>> | null>(null);
  const [caps, setCaps] = useState<StockoutCapabilities | null>(null);
  const [velocities, setVelocities] = useState<StockoutVelocityRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [urgency, setUrgency] = useState('');
  const [onlyShortage, setOnlyShortage] = useState(false);
  const [onlyWithoutVelocity, setOnlyWithoutVelocity] = useState(false);
  const [detail, setDetail] = useState<StockoutRiskItemDetail | null>(null);
  const [computing, setComputing] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchItems(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  const loadVelocities = useCallback(async () => {
    try {
      setVelocities((await fetchVelocities({ page: 0, size: 50 })).rows);
    } catch {
      // 登记列表拿不到不该让整页空掉：清单仍然可用，只是补登记的入口退化。
      setVelocities([]);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：页面不假装自己知道规则。
        setCaps(null);
      }
    })();
    void loadVelocities();
  }, [loadVelocities]);

  const rows = data?.rows ?? [];

  const stats = useMemo(() => {
    const items = data?.rows ?? [];
    let shortage = 0;
    let critical = 0;
    let noData = 0;
    for (const row of items) {
      shortage += row.shortageQuantity;
      if (row.urgency === 'CRITICAL' || row.urgency === 'HIGH') critical += 1;
      if (isNoData(row.urgency)) noData += 1;
    }
    return { shortage, critical, noData };
  }, [data]);

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchItem(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
        await loadVelocities();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, loadVelocities],
  );

  const nextParams = (): StockoutRiskParams => {
    const next: StockoutRiskParams = { page: 0, size: 20 };
    if (keyword.trim()) next.keyword = keyword.trim();
    if (urgency) next.urgency = urgency;
    if (onlyShortage) next.onlyShortage = true;
    if (onlyWithoutVelocity) next.onlyWithoutVelocity = true;
    return next;
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="缺货与断货风险"
        description="按日均销量、可用库存、在途量与备货周期算出预计断货日，按紧急度排出补货优先级。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setComputing(true)} disabled={busy}>
              <Calculator />
              计算风险清单
            </Button>
          </div>
        }
      />

      {/* 口径常驻：这一页的数从哪来、缺什么，必须一直摆在眼前 */}
      <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
        <PackageSearch className="mt-0.5 size-4 shrink-0" />
        <div>
          <p className="font-medium">有效可用量取仓库的可售量，本页不重算</p>
          <p className="text-muted-foreground">
            {caps?.note ??
              '可用量来自 warehouse 7.1.1.3 的可售量，在途来自 7.5.1.2，涉及店铺来自 channel 的映射；日均销量在本页登记。'}
          </p>
        </div>
      </div>

      {data && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="清单 SKU" value={data.total} />
          <StatCard label="紧急 / 高" value={stats.critical} />
          <StatCard label="本页缺口合计" value={fmt(stats.shortage)} />
          <StatCard label="无数据（待补登记）" value={stats.noData} />
        </div>
      )}

      {data?.note && (
        <p className="text-muted-foreground text-xs">{data.note}</p>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="SKU 编码 / 名称"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-64"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">紧急度</span>
            <select
              className="h-9 rounded-md border px-2"
              value={urgency}
              onChange={(e) => setUrgency(e.target.value)}
            >
              <option value="">全部</option>
              <option value="CRITICAL">紧急</option>
              <option value="HIGH">高</option>
              <option value="MEDIUM">中</option>
              <option value="LOW">低</option>
              <option value="NO_DATA">无数据</option>
            </select>
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={onlyShortage}
              onChange={(e) => setOnlyShortage(e.target.checked)}
            />
            只看有缺口
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={onlyWithoutVelocity}
              onChange={(e) => setOnlyWithoutVelocity(e.target.checked)}
            />
            只看无销售速度
          </label>
          <Button onClick={() => setParams(nextParams())}>查询</Button>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载断货风险清单…</p>}

      {!loading && rows.length === 0 && !error && (
        <EmptyState
          title="没有断货风险"
          description="当前筛选条件下没有 SKU。先点「计算风险清单」生成一份快照，或放宽筛选条件。"
        />
      )}

      {!loading && rows.length > 0 && (
        <SectionCard title={`断货风险清单（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">SKU</th>
                  <th className="px-2 py-1">可用量</th>
                  <th className="px-2 py-1">日均销</th>
                  <th className="px-2 py-1">可支撑天数</th>
                  <th className="px-2 py-1">预计断货日</th>
                  <th className="px-2 py-1">缺口量</th>
                  <th className="px-2 py-1">涉及店铺</th>
                  <th className="px-2 py-1">紧急度</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1 font-medium">
                      {row.skuCode}
                      {row.skuName && (
                        <span className="text-muted-foreground"> / {row.skuName}</span>
                      )}
                    </td>
                    <td className="px-2 py-1">{fmt(row.availableQuantity)}</td>
                    <td className="px-2 py-1">{dailySalesText(row.dailySalesQuantity)}</td>
                    <td className="px-2 py-1">{coverageText(row.coverageDays)}</td>
                    <td className="px-2 py-1">{stockoutDateText(row.expectedStockoutDate)}</td>
                    <td className="px-2 py-1">{shortageText(row.shortageQuantity)}</td>
                    <td className="px-2 py-1">{shopText(row.shopCodes)}</td>
                    <td className="px-2 py-1">
                      <Badge tone={urgencyTone(row.urgency)} title={row.urgencyBasis}>
                        {urgencyLabel(row.urgency)}
                      </Badge>
                    </td>
                    <td className="px-2 py-1 text-right">
                      <Button variant="ghost" onClick={() => void openDetail(row.id)}>
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

      {/* 排序依据常驻在详情里：清单的顺序必须能被复现 */}
      {detail && (
        <Modal open onOpenChange={(open) => !open && setDetail(null)}>
          <ModalContent>
            <ModalHeader>
              <ModalTitle>断货风险详情 · {detail.item.skuCode}</ModalTitle>
              <ModalDescription>{detail.item.urgencyBasis}</ModalDescription>
            </ModalHeader>
            <div className="flex flex-col gap-3">
              <SectionCard title="涉及店铺">
                {detail.shops.length === 0 ? (
                  <p className="text-muted-foreground text-sm">
                    这个 SKU 还没挂到任何店铺（未挂店铺是真实状态，不是查不到）。
                  </p>
                ) : (
                  <ul className="flex flex-wrap gap-2">
                    {detail.shops.map((shop) => (
                      <li
                        key={shop.shopCode}
                        className="rounded-md border px-2 py-1 text-xs"
                        title={`${shop.listingCount} 个平台变体`}
                      >
                        {shop.shopCode}
                        <span className="text-muted-foreground"> · {shop.listingCount}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </SectionCard>
              <SectionCard title="计算过程">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">步骤</th>
                      <th className="px-2 py-1">量</th>
                      <th className="px-2 py-1">口径</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.deductions.map((step) => (
                      <tr key={step.stepOrder} className="border-t">
                        <td className="px-2 py-1">{step.stepKindLabel}</td>
                        <td className="px-2 py-1">{fmt(step.quantity)}</td>
                        <td className="text-muted-foreground px-2 py-1">{step.basis}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </SectionCard>
            </div>
            <ModalFooter>
              <Button variant="ghost" onClick={() => setDetail(null)}>
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      )}

      {computing && (
        <ComputeDialog
          busy={busy}
          onCancel={() => setComputing(false)}
          onRun={async (asOfDate, leadTimeDays) => {
            setComputing(false);
            await run(() =>
              computeSnapshot({
                asOfDate: asOfDate || null,
                leadTimeDays,
                horizonDays: null,
                note: null,
              }),
            );
          }}
        />
      )}

      <VelocitySection
        velocities={velocities}
        busy={busy}
        onRegister={async (input) => {
          await run(() => registerVelocity(input));
        }}
      />
    </div>
  );
}

/** 计算一次风险清单的弹窗。 */
function ComputeDialog({
  busy,
  onCancel,
  onRun,
}: {
  busy: boolean;
  onCancel: () => void;
  onRun: (asOfDate: string, leadTimeDays: number) => Promise<void>;
}) {
  const [asOfDate, setAsOfDate] = useState('');
  const [leadTime, setLeadTime] = useState('30');
  const leadTimeDays = Number(leadTime);
  const valid = Number.isFinite(leadTimeDays) && leadTimeDays >= 1;
  return (
    <Modal open onOpenChange={(open) => !open && onCancel()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>计算断货风险清单</ModalTitle>
          <ModalDescription>
            可用量与在途来自 warehouse 的授权只读接口，涉及店铺来自 channel；算完落成一份快照。
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-wrap gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">评估基准日（留空 = 今天）</span>
            <Input
              type="date"
              value={asOfDate}
              onChange={(e) => setAsOfDate(e.target.value)}
              className="w-48"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">备货周期（天）</span>
            <Input
              type="number"
              min={1}
              value={leadTime}
              onChange={(e) => setLeadTime(e.target.value)}
              className="w-32"
            />
          </label>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onCancel}>
            取消
          </Button>
          <Button disabled={!valid || busy} onClick={() => void onRun(asOfDate, leadTimeDays)}>
            开始计算
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 销售速度登记区（无数据那一档要能就地补登记）。 */
function VelocitySection({
  velocities,
  busy,
  onRegister,
}: {
  velocities: StockoutVelocityRow[];
  busy: boolean;
  onRegister: (input: {
    skuId: string;
    skuCode: string;
    skuName: string | null;
    dailySalesQuantity: number;
    windowStart: string;
    windowEnd: string;
    effectiveFrom: string;
    effectiveTo: string | null;
    sourceKind: 'MANUAL' | 'IMPORT' | 'EXTERNAL_SYNC';
    sourceRef: string | null;
    note: string | null;
  }) => Promise<void>;
}) {
  const [skuId, setSkuId] = useState('');
  const [skuCode, setSkuCode] = useState('');
  const [daily, setDaily] = useState('');
  const [windowStart, setWindowStart] = useState('');
  const [windowEnd, setWindowEnd] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');

  const dailyValue = Number(daily);
  const valid =
    skuId.trim() !== '' &&
    skuCode.trim() !== '' &&
    Number.isFinite(dailyValue) &&
    dailyValue >= 0 &&
    windowStart !== '' &&
    windowEnd !== '' &&
    effectiveFrom !== '';

  return (
    <SectionCard title="销售速度登记（日均销量）">
      <p className="text-muted-foreground mb-3 text-xs">
        日均销量是本模块四个输入里唯一没有其它服务能给的（13 销量服务不存在），所以在这里登记。
        登记是<b>版本化</b>的：同 SKU 再登记一次会把上一条收口，而不是覆盖它 ——
        这样「当时按哪个数算的断货日」永远查得回来。
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">SKU 逻辑 ID</span>
          <Input
            value={skuId}
            placeholder="uuid"
            onChange={(e) => setSkuId(e.target.value)}
            className="w-72"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">SKU 编码</span>
          <Input
            value={skuCode}
            onChange={(e) => setSkuCode(e.target.value)}
            className="w-40"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">日均销量</span>
          <Input
            type="number"
            min={0}
            step="0.000001"
            value={daily}
            onChange={(e) => setDaily(e.target.value)}
            className="w-32"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">统计窗口起</span>
          <Input
            type="date"
            value={windowStart}
            onChange={(e) => setWindowStart(e.target.value)}
            className="w-40"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">统计窗口止</span>
          <Input
            type="date"
            value={windowEnd}
            onChange={(e) => setWindowEnd(e.target.value)}
            className="w-40"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">生效起点</span>
          <Input
            type="date"
            value={effectiveFrom}
            onChange={(e) => setEffectiveFrom(e.target.value)}
            className="w-40"
          />
        </label>
        <Button
          disabled={!valid || busy}
          onClick={() =>
            void onRegister({
              skuId: skuId.trim(),
              skuCode: skuCode.trim(),
              skuName: null,
              dailySalesQuantity: dailyValue,
              windowStart,
              windowEnd,
              effectiveFrom,
              effectiveTo: null,
              sourceKind: 'MANUAL',
              sourceRef: '页面人工登记',
              note: null,
            })
          }
        >
          登记
        </Button>
      </div>
      {velocities.length > 0 && (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left">
              <tr>
                <th className="px-2 py-1">SKU</th>
                <th className="px-2 py-1">日均销量</th>
                <th className="px-2 py-1">统计窗口</th>
                <th className="px-2 py-1">生效期</th>
                <th className="px-2 py-1">来源</th>
              </tr>
            </thead>
            <tbody>
              {velocities.map((v) => (
                <tr key={v.id} className="border-t">
                  <td className="px-2 py-1 font-medium">{v.skuCode}</td>
                  <td className="px-2 py-1">{fmt(v.dailySalesQuantity)}</td>
                  <td className="px-2 py-1">
                    {v.windowStart} ~ {v.windowEnd}
                  </td>
                  <td className="px-2 py-1">
                    {v.effectiveFrom} ~ {v.effectiveTo ?? '至今'}
                  </td>
                  <td className="px-2 py-1">{velocitySourceLabel(v.sourceKind)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </SectionCard>
  );
}
