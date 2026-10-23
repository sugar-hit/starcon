/**
 * 1.1.3.1 库存风险的**前端聚合模型**（卷 01 工作台与门户）。
 *
 * <p>蓝图原文：「集中呈现缺货、超卖风险、呆滞、超储四类库存问题，区分本地仓/海外仓/FBA/在途。」
 * 输入「07 库存的可用量投影（按 bucket 汇总）；销售速度；06 采购的在途量；10 的头程在途。」
 * 输出「风险清单（SKU、仓库、可用量、日均销、可用天数、风险类型、建议动作如"生成采购单"）。」
 *
 * <p><b>为什么判定在前端做</b>：卷 01 是「前端聚合层 + 各服务的只读投影（不新增业务服务）」。
 * 可用量、阈值、预占、在途属 warehouse 的事实表；日均销登记属 trade 的事实表。
 * 跨服务聚合必须在前端做 —— 后端谁都不该替对方读表（AGENTS.md 模块边界纪律）。
 *
 * <p><b>四条判定规则（阈值全部来自投影行，没有一个是本文件写死的常量）</b>：
 *
 * <table>
 *   <tr><th>风险</th><th>判定</th><th>阈值来源</th></tr>
 *   <tr><td>缺货</td><td>可用量 < 最低保有量</td><td>inventory_policy.min_on_hand（SKU > WAREHOUSE > DEFAULT）</td></tr>
 *   <tr><td>超卖</td><td>活跃预占未了结量 > 可用量</td><td><b>无阈值</b>（结构性判定）</td></tr>
 *   <tr><td>呆滞</td><td>库龄 ≥ 呆滞线</td><td>inventory_aging_policy.obsolete_days（未配置回落 7.6.1.1 兜底）</td></tr>
 *   <tr><td>超储</td><td>在手量 > 最高保有量</td><td>inventory_policy.max_on_hand（SKU > WAREHOUSE > DEFAULT）</td></tr>
 * </table>
 *
 * <p><b>三件本模块最容易做错的事，这里都显式处理了</b>：
 *
 * <ol>
 *   <li><b>「没配置阈值」不是「阈值 = 0」</b>。没配 {@code min_on_hand} 的行**不参与缺货判定**，
 *       并在界面上标「未配置阈值」—— 把它当成 0 会让每个 SKU 都变成缺货。</li>
 *   <li><b>「没登记销售速度」不是「日均销 = 0」</b>。没有登记时可用天数是<b>不可判定</b>（null），
 *       既不是 0 也不是无穷大；登记为 0（登记事实：本 SKU 无消耗）同样答不出天数。</li>
 *   <li><b>除法不碰浮点</b>。可用天数 = 可用量 ÷ 日均销量，全程在缩放整数（BigInt，scale 6）上做，
 *       向下取整为整数天 —— {@code 0.3 ÷ 0.1} 在二进制浮点上是 2.9999999999999996，
 *       直接 {@code Math.floor} 会得到 2 而不是 3。</li>
 * </ol>
 */

import type {
  RiskProjectionCapabilities,
  RiskProjectionPage,
  RiskProjectionRow,
  StockoutVelocityPage,
  StockoutVelocityRow,
} from '../../../../../packages/api-contracts/src/index';

export type { RiskProjectionCapabilities, RiskProjectionPage, RiskProjectionRow };

/** 风险类型。四类，与蓝图 1.1.3.1 的「缺货、超卖风险、呆滞、超储」逐字对应。 */
export const RISK_TYPES = ['STOCKOUT', 'OVERSELL', 'SLOW_MOVING', 'OVERSTOCK'] as const;

export type RiskType = (typeof RISK_TYPES)[number];

/** 风险类型的中文标签。 */
export const RISK_TYPE_LABELS: Record<RiskType, string> = {
  STOCKOUT: '缺货',
  OVERSELL: '超卖风险',
  SLOW_MOVING: '呆滞',
  OVERSTOCK: '超储',
};

/** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
export const QUANTITY_SCALE = 6;

/**
 * warehouse 的 (仓 × SKU) 库存事实投影行。
 *
 * <p>类型来自 OpenAPI 契约（codegen 产物，**只读**）：后端
 * {@code InventoryRiskProjectionModels.RiskProjectionRow}。
 * 数量字段是 JSON number；本模型把它们转成缩放整数后再做除法（见 {@link toScaledDecimal}）。
 */
export type StockProjectionRow = RiskProjectionRow;

/** warehouse 的投影页。 */
export type StockProjectionPage = RiskProjectionPage;

/** trade 的销售速度登记行（6.2.1.1 / V234）。 */
export type VelocityRow = StockoutVelocityRow;

/** trade 的销售速度登记页。 */
export type VelocityPage = StockoutVelocityPage;

/** 可用天数的判定状态：三种情形必须分得开。 */
export type CoverageStatus = 'OK' | 'NO_VELOCITY' | 'ZERO_VELOCITY';

/** 风险清单的一行。 */
export interface InventoryRiskRow {
  key: string;
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  warehouseKind: string;
  warehouseKindLabel: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  onHand: number;
  reserved: number;
  available: number;
  activeReserved: number;
  activeReservationCount: number;
  inTransit: number;
  inTransitFirstMile: number;
  inTransitPurchase: number;
  inTransitTransfer: number;
  inTransitLegCount: number;
  agingDays: number | null;
  obsoleteDays: number | null;
  minOnHand: number | null;
  maxOnHand: number | null;
  reorderPoint: number | null;
  thresholdScopeType: string;
  thresholdScopeKey: string;
  dailySales: number | null;
  dailySalesWindow: string | null;
  coverageDays: number | null;
  coverageStatus: CoverageStatus;
  risks: RiskType[];
  suggestedAction: string;
  actionBasis: string;
}

/** 风险清单的汇总。 */
export interface InventoryRiskSummary {
  rowCount: number;
  riskRowCount: number;
  byRisk: Record<RiskType, number>;
  byWarehouseKind: { kind: string; label: string; rowCount: number; riskRowCount: number }[];
  noThresholdCount: number;
  noVelocityCount: number;
}

// ================================================================ 精确十进制

/**
 * 把一个数量转成**缩放整数**（BigInt，scale 6）。
 *
 * <p>为什么不用 `Number`：可用天数是「可用量 ÷ 日均销量」的除法结果，而数量是 NUMERIC(19,6)。
 * 二进制浮点存不下 0.1，`0.3 / 0.1 === 2.9999999999999996`，`Math.floor` 之后是 2 而不是 3 ——
 * 于是「还有 3 天可卖」会显示成「还有 2 天」。本模型全程在缩放整数上算，不碰浮点。
 *
 * <p><b>精度规则</b>：一律取 6 位小数（与 numeric(19,6) 同精度），末位四舍五入。
 * 数据库里的数量本就不超过 6 位小数，所以这一步只是把 JSON 的十进制原样搬过来。
 *
 * @returns 缩放后的整数；输入为 null / undefined / 非数字时返回 null（「没有这个数」）
 */
export function toScaledDecimal(value: number | string | null | undefined): bigint | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number' && !Number.isFinite(value)) return null;
  // toFixed 不会产出科学计数法（1e21 以下），正好避开 JSON 里 "1e+21" 那种形态。
  const text = (typeof value === 'number' ? value.toFixed(QUANTITY_SCALE) : value.trim()).trim();
  if (!text) return null;
  const match = /^([+-]?)(\d+)(?:\.(\d*))?$/.exec(text);
  if (!match) return null;
  const sign = match[1] === '-' ? -1n : 1n;
  const integer = match[2] ?? '0';
  const fraction = (match[3] ?? '').padEnd(QUANTITY_SCALE, '0').slice(0, QUANTITY_SCALE);
  return sign * (BigInt(integer) * 10n ** BigInt(QUANTITY_SCALE) + BigInt(fraction || '0'));
}

/**
 * 精确十进制除法，向下取整为整数。
 *
 * @returns 结果；分母为 0 或缺失时返回 null（**不是 0，也不是无穷大**）
 */
export function divideFloor(
  numerator: number | string | null | undefined,
  denominator: number | string | null | undefined,
): number | null {
  const a = toScaledDecimal(numerator);
  const b = toScaledDecimal(denominator);
  if (a === null || b === null || b === 0n) return null;
  const quotient = a / b; // BigInt 除法向零截断；数量恒非负，即向下取整
  return Number(quotient);
}

/**
 * 数量格式化：去掉多余的尾随零。
 *
 * <p>数量是 NUMERIC，可能带小数；整数就不带小数点，免得「3000.000000」在页面上晃。
 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 1e6) / 1e6;
  return String(rounded);
}

// ================================================================ 销售速度

/**
 * 取每个 SKU **当前生效**的销售速度登记。
 *
 * <p>生效期语义与 6.2.1.1 一致：区间 {@code [effective_from, effective_to)}，
 * 覆盖 {@code asOfDate} 的那条才是「当前生效」的。同一 SKU 同时只允许一条未收口的登记
 * （DB 的部分唯一索引钉死），所以按 {@code effectiveFrom} 取最新的一条即唯一解。
 *
 * <p>没有命中的 SKU **不在结果里** —— 那是「没登记」，不是「日均销 0」。
 */
export function selectCurrentVelocities(
  velocities: VelocityRow[],
  asOfDate: string,
): Map<string, VelocityRow> {
  const result = new Map<string, VelocityRow>();
  for (const velocity of velocities) {
    if (velocity.effectiveFrom > asOfDate) continue;
    if (velocity.effectiveTo !== null && velocity.effectiveTo <= asOfDate) continue;
    const current = result.get(velocity.skuId);
    if (!current || velocity.effectiveFrom > current.effectiveFrom) {
      result.set(velocity.skuId, velocity);
    }
  }
  return result;
}

// ================================================================ 风险判定

/** 阈值档位的中文标签。 */
export function thresholdScopeLabel(scopeType: string): string {
  switch (scopeType) {
    case 'SKU':
      return 'SKU 档';
    case 'WAREHOUSE':
      return '仓库档';
    case 'DEFAULT':
      return '全租户兜底档';
    default:
      return '未配置';
  }
}

/**
 * 判定一行命中哪几类风险。
 *
 * <p>四类判定彼此独立 —— 一行可以同时是「缺货」与「呆滞」（一个 SKU 常年不动、卖不动）。
 * 合并成一类就丢掉了「同时有两种问题」这个事实，而那恰恰是补货决策需要的信息。
 */
export function classifyRisks(row: StockProjectionRow): RiskType[] {
  const risks: RiskType[] = [];
  // 缺货：可用量低于最低保有量。**未配置阈值 → 不判定**（不是「阈值 = 0」）。
  if (row.minOnHand !== null && row.available < row.minOnHand) risks.push('STOCKOUT');
  // 超卖：活跃预占未了结量已经超过可发货的量 —— 结构性判定，无阈值。
  if (row.activeReservedQuantity > row.available) risks.push('OVERSELL');
  // 呆滞：库龄达到呆滞线。后端未配置时已回落 7.6.1.1 的兜底默认值（obsoleteDays 恒有值）。
  if (Number.isFinite(row.agingDays) && Number.isFinite(row.obsoleteDays)
    && row.agingDays >= row.obsoleteDays) risks.push('SLOW_MOVING');
  // 超储：在手量高于最高保有量。未配置阈值 → 不判定。
  if (row.maxOnHand !== null && row.onHand > row.maxOnHand) risks.push('OVERSTOCK');
  return risks;
}

/**
 * 给一行风险建议动作。
 *
 * <p>动作是**建议**（卷 01 不写业务数据，一切状态变更回到归属系统完成），
 * 但建议的依据必须来自这一行的真实事实：有没有在途、别的仓有没有货、库龄多少天。
 */
export function suggestAction(
  row: StockProjectionRow,
  risks: RiskType[],
  siblings: StockProjectionRow[],
): { action: string; basis: string } {
  if (risks.length === 0) {
    return { action: '无需处理', basis: '未命中任何风险判定' };
  }
  const parts: string[] = [];
  const basis: string[] = [];

  if (risks.includes('STOCKOUT')) {
    const arriving = row.inTransitPurchaseQuantity + row.inTransitFirstMileQuantity;
    if (arriving > 0) {
      parts.push('催在途到货');
      basis.push(`在途 ${formatQuantity(arriving)}（采购 ${formatQuantity(row.inTransitPurchaseQuantity)} + 头程 ${formatQuantity(row.inTransitFirstMileQuantity)}）`);
    } else {
      parts.push('生成采购单');
      basis.push(
        row.reorderPoint !== null
          ? `补货点 ${formatQuantity(row.reorderPoint)}`
          : '未配置补货点',
      );
    }
  }
  if (risks.includes('OVERSELL')) {
    parts.push('核对预占需求');
    basis.push(
      `活跃预占 ${formatQuantity(row.activeReservedQuantity)} > 可用量 ${formatQuantity(row.available)}`,
    );
  }
  if (risks.includes('SLOW_MOVING')) {
    parts.push('清仓 / 降价促销');
    basis.push(
      `库龄 ${row.agingDays ?? '—'} 天 ≥ 呆滞线 ${row.obsoleteDays ?? '—'} 天`,
    );
  }
  if (risks.includes('OVERSTOCK')) {
    const donor = siblings.find(
      (other) =>
        other.skuId === row.skuId &&
        other.warehouseId !== row.warehouseId &&
        other.available > 0,
    );
    if (donor) {
      parts.push(`调拨到 ${donor.warehouseCode}`);
      basis.push(`${donor.warehouseCode} 可用量 ${formatQuantity(donor.available)}`);
    } else {
      parts.push('暂停补货');
      basis.push('其它仓没有可调拨的可用量');
    }
  }

  return { action: parts.join(' · '), basis: basis.join('；') };
}

// ================================================================ 聚合

/**
 * 把 warehouse 的库存事实与 trade 的销售速度合成风险清单。
 *
 * @param projections warehouse 的 (仓 × SKU) 投影行
 * @param velocities trade 的销售速度登记行（含历史版本）
 * @param asOfDate 评估基准日（warehouse 投影返回的数据库时钟日期）
 */
export function buildInventoryRiskList(
  projections: StockProjectionRow[],
  velocities: VelocityRow[],
  asOfDate: string,
): InventoryRiskRow[] {
  const current = selectCurrentVelocities(velocities, asOfDate);
  const rows: InventoryRiskRow[] = [];

  for (const projection of projections) {
    const risks = classifyRisks(projection);
    const velocity = current.get(projection.skuId);
    const dailySales = velocity ? velocity.dailySalesQuantity : null;
    let coverageDays: number | null = null;
    let coverageStatus: CoverageStatus;
    if (!velocity) {
      coverageStatus = 'NO_VELOCITY';
    } else if (dailySales === null || dailySales === 0) {
      coverageStatus = 'ZERO_VELOCITY';
    } else {
      coverageStatus = 'OK';
      coverageDays = divideFloor(projection.available, dailySales);
    }
    const suggestion = suggestAction(projection, risks, projections);

    rows.push({
      key: `${projection.warehouseId}|${projection.skuId}`,
      warehouseId: projection.warehouseId,
      warehouseCode: projection.warehouseCode,
      warehouseName: projection.warehouseName,
      warehouseKind: projection.warehouseKind,
      warehouseKindLabel: projection.warehouseKindLabel,
      skuId: projection.skuId,
      skuCode: projection.skuCode,
      skuName: projection.skuName,
      onHand: projection.onHand,
      reserved: projection.reserved,
      available: projection.available,
      activeReserved: projection.activeReservedQuantity,
      activeReservationCount: projection.activeReservationCount,
      inTransit: projection.inTransitQuantity,
      inTransitFirstMile: projection.inTransitFirstMileQuantity,
      inTransitPurchase: projection.inTransitPurchaseQuantity,
      inTransitTransfer: projection.inTransitTransferQuantity,
      inTransitLegCount: projection.inTransitLegCount,
      agingDays: projection.agingDays,
      obsoleteDays: projection.obsoleteDays,
      minOnHand: projection.minOnHand,
      maxOnHand: projection.maxOnHand,
      reorderPoint: projection.reorderPoint,
      thresholdScopeType: projection.thresholdScopeType,
      thresholdScopeKey: projection.thresholdScopeKey,
      dailySales,
      dailySalesWindow: velocity
        ? `${velocity.windowStart} ~ ${velocity.windowEnd}`
        : null,
      coverageDays,
      coverageStatus,
      risks,
      suggestedAction: suggestion.action,
      actionBasis: suggestion.basis,
    });
  }

  return rows;
}

/** 汇总：各风险类型的命中行数、按仓库类型的分布、以及「判不了」的行数。 */
export function summarizeRiskList(rows: InventoryRiskRow[]): InventoryRiskSummary {
  const byRisk: Record<RiskType, number> = {
    STOCKOUT: 0,
    OVERSELL: 0,
    SLOW_MOVING: 0,
    OVERSTOCK: 0,
  };
  const kinds = new Map<string, { kind: string; label: string; rowCount: number; riskRowCount: number }>();
  let riskRowCount = 0;
  let noThresholdCount = 0;
  let noVelocityCount = 0;

  for (const row of rows) {
    for (const risk of row.risks) byRisk[risk] += 1;
    if (row.risks.length > 0) riskRowCount += 1;
    if (row.thresholdScopeType === 'NONE') noThresholdCount += 1;
    if (row.coverageStatus === 'NO_VELOCITY') noVelocityCount += 1;
    const bucket = kinds.get(row.warehouseKind) ?? {
      kind: row.warehouseKind,
      label: row.warehouseKindLabel,
      rowCount: 0,
      riskRowCount: 0,
    };
    bucket.rowCount += 1;
    if (row.risks.length > 0) bucket.riskRowCount += 1;
    kinds.set(row.warehouseKind, bucket);
  }

  return {
    rowCount: rows.length,
    riskRowCount,
    byRisk,
    byWarehouseKind: [...kinds.values()],
    noThresholdCount,
    noVelocityCount,
  };
}

/**
 * 一行风险的详情文案：每个数字都带出处，口径与列表一致。
 *
 * <p>蓝图要求「任何在此处出现的指标都必须能点进去看到明细，且明细口径与此处一致」——
 * 所以详情里写的每一句都能在清单的同一行找到对应的数。
 */
export function describeRiskRow(row: InventoryRiskRow): string[] {
  const lines: string[] = [];
  lines.push(
    `可用量 ${formatQuantity(row.available)}（在手 ${formatQuantity(row.onHand)} − 预占 ${formatQuantity(row.reserved)}）`,
  );
  lines.push(
    row.minOnHand !== null || row.maxOnHand !== null
      ? `库存阈值：最低保有量 ${row.minOnHand !== null ? formatQuantity(row.minOnHand) : '未配置'} / 最高保有量 ${row.maxOnHand !== null ? formatQuantity(row.maxOnHand) : '未配置'}（${thresholdScopeLabel(row.thresholdScopeType)}）`
      : '库存阈值：未配置（最低/最高保有量都没有，这一行不参与缺货与超储判定）',
  );
  lines.push(
    `活跃预占 ${formatQuantity(row.activeReserved)}（${row.activeReservationCount} 笔未了结）`,
  );
  lines.push(
    `在途 ${formatQuantity(row.inTransit)}（头程 ${formatQuantity(row.inTransitFirstMile)} / 采购 ${formatQuantity(row.inTransitPurchase)} / 调拨 ${formatQuantity(row.inTransitTransfer)}，${row.inTransitLegCount} 条腿）`,
  );
  if (row.coverageStatus === 'NO_VELOCITY') {
    lines.push('可用天数：不可判定（该 SKU 没有登记销售速度）');
  } else if (row.coverageStatus === 'ZERO_VELOCITY') {
    lines.push('可用天数：不可判定（登记的日均销量是 0，除法没有意义）');
  } else {
    lines.push(
      `可用天数 ${row.coverageDays ?? '—'} 天（可用量 ${formatQuantity(row.available)} ÷ 日均销 ${formatQuantity(row.dailySales)}${row.dailySalesWindow ? `，统计窗口 ${row.dailySalesWindow}` : ''}，向下取整）`,
    );
  }
  lines.push(
    row.agingDays !== null
      ? `库龄 ${row.agingDays} 天（呆滞线 ${row.obsoleteDays ?? '—'} 天）`
      : '库龄：无入库日期，判不了',
  );
  lines.push(`建议动作：${row.suggestedAction}（${row.actionBasis}）`);
  return lines;
}
