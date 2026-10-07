'use client';

import { DuplicateWorkspace } from '@/components/catalog/duplicate-workspace';

/**
 * 3.9.1.1 重复 SKU 识别（路由 `/products/duplicates`）。
 *
 * <p><b>为什么独立成页而不挂在 SKU 列表或导入报告下面</b>：蓝图给这一模块的定位是
 * 「<b>同一商品被拆成两条，各算各的</b>，污染销量、采购、库存与利润统计」。
 * 也就是说它回答的是<b>跨商品、跨 SPU</b>的问题 —— 起点既不是某一个 SKU，
 * 也不是某一次导入，而是「整个商品主数据现在干净吗」。
 * 挂在 SKU 列表下会让「这两条是不是重复」这个问题看起来属于某一条记录，
 * 而治理的收益恰恰在报表那一侧。
 *
 * <p>页面上必须写清的四条纪律：
 *
 * <ul>
 *   <li><b>本模块只识别与登记决定，不执行合并。</b>执行归并是 3.9.1.2 的职责；
 *       这里点完「登记合并决定」之后，商品数据一个字节都没变。</li>
 *   <li><b>历史订单行冻结当时 SKU 快照，不随合并回溯改写</b>（实体目录 §3.6）——
 *       合并只迁移「当前引用」（映射 / 库存 / 价格）。</li>
 *   <li><b>范围收窄过的扫描，0 个候选不等于干净。</b>分片扫描会漏掉跨范围的重复，
 *       页面必须显式标出来，否则「已经治理干净」这个结论会建立在一次不完整的扫描上。</li>
 *   <li><b>影响引用数只覆盖 catalog 侧</b>（条码 / 素材 / 属性值 / 报价 / 套装 / BOM /
 *       包装 / 安全属性 / 单位换算）。04 映射、07 库存、09 订单的引用在别的服务的库里，
 *       本模块不跨库读，那部分由 3.9.1.2 走各自授权 API 补齐。</li>
 * </ul>
 */
export default function DuplicateDetectionPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">重复 SKU 识别</h1>
        <p className="text-muted-foreground text-copy-13">
          找出被拆成多条的商品：<strong>同 SPU 重复规格组合</strong>、
          <strong>同商品不同 SPU 重复建档</strong>、<strong>同条码多 SKU</strong>、
          <strong>近似标题 + 相似图片疑似同品</strong>。
          输出<strong>相似度、重叠维度与影响引用数</strong>供人工确认。
          重复 SKU 会污染销量、采购、库存与利润统计 —— 同一商品被拆成两条，各算各的，
          所以治理的价值在于<strong>报表可信</strong>。
        </p>
      </header>

      <DuplicateWorkspace />
    </div>
  );
}