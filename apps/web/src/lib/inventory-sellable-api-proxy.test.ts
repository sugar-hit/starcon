import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 7.1.1.3 可用量计算在 api-proxy 白名单里的守卫。
 *
 * <p><b>为什么这个测试必须存在</b>：前端调了但白名单没登记 = **浏览器端静默 404**，
 * 而 `typecheck` / `lint` / `vitest` 全绿。它是本车道唯一能提前发现这件事的地方。
 *
 * <p><b>两个方向都要断言，反向断言不许省</b>：
 *
 * <ul>
 *   <li><b>正向</b>：本模块真实会发出的三个形状逐条验放行。</li>
 *   <li><b>反向</b>：未知形状<b>仍然必须被拒</b>。只断言放行的话，一个「全部放行」的正则
 *       也能让正向用例全绿 —— 而那等于把整个 warehouse 服务变成浏览器可直连的裸服务。</li>
 * </ul>
 *
 * <p><b>并且断言既有分支没被改掉</b>（7.1.1.1 总览 / 7.1.1.2 明细 / 7.2.2.1 可售量口径）：
 * 白名单是**联合追加**，取错一侧会静默删掉已交付路由，表现为页面 404 而所有 CI 都是绿的。
 * 本车道那条巨型单行正则里 `inventory\/` 分组出现两次（内容逐字相同的历史重复），
 * 测试只校验**第一处**——与本车道的编辑位置一致。
 */
const WAREHOUSE_PREFIX = 'warehouse/';

/**
 * 从 api-proxy.ts 抽出 warehouse 服务的**全部**白名单正则（同一前缀可能有多条元素）。
 *
 * <p><b>为什么必须收全部、而不是只收第一条</b>：`api-proxy.ts` 是并行车道共同的
 * **累积型**文件，同一个 prefix 出现多条元素是**合法且已经发生**的
 * （`channel/` 现有 6 条、`trade/` 现有 2 条）。运行时 {@code resolve()} 逐条
 * fallthrough：前缀不匹配就 {@code continue}，正则不匹配也继续试下一条，
 * 所以后登记的那条不会因为前面那条先存在而失效，两者构成并集。
 * 裸 {@code indexOf} 取第一条更糟：它既可能命中注释，也可能抽到下一条元素的正则 ——
 * `trade/` 上已经真实发生过后者，导致 11 个用例假红。
 *
 * <p><b>为什么锚在行首而不是裸子串</b>：`prefix: 'warehouse/'` 这个字面量同样会出现在
 * 解释「插入锚点固定在 … 之前」的注释里，那是有意的文档约定。真实元素行以缩进的
 * {@code prefix:} 开头（注释行以 {@code //} 开头），所以按行首匹配元素属性本身。
 *
 * <p><b>为什么每条元素单独定界</b>：元素边界 = 下一个元素的 prefix 行（或数组结尾）。
 * 不定界的话，从本元素 {@code prefix:} 往后找第一个 {@code /^}，找到的会是**下一条元素**的
 * 正则。本车道目前只有一条 warehouse 元素，所以这是**尚未触发**的隐患，
 * 而第二条 warehouse 元素落地的当天就会变成假红。
 */
function warehousePatterns(): RegExp[] {
  const source = readFileSync(join(__dirname, 'api-proxy.ts'), 'utf8');
  // 所有真实元素的 prefix 行，按出现顺序。注释行以 // 开头，匹配不到这一条。
  const starts = [...source.matchAll(/^\s*prefix: '([^']+)'/gm)].map((m) => ({
    prefix: m[1] ?? '',
    at: m.index ?? 0,
  }));

  const out: RegExp[] = [];
  for (const [i, element] of starts.entries()) {
    if (element.prefix !== WAREHOUSE_PREFIX) continue;
    const end = starts[i + 1]?.at ?? source.lastIndexOf('];');
    const block = source.slice(element.at, end);
    const start = block.indexOf('/^');
    if (start < 0) {
      throw new Error(`未能从 api-proxy.ts 的 ${WAREHOUSE_PREFIX} 元素里定位白名单正则起点`);
    }
    let literal: string | undefined;
    for (let j = start + 1; j < block.length; j++) {
      const ch = block[j];
      if (ch === '\\') {
        j++; // 转义字符：跳过，不当作字面量结束
        continue;
      }
      if (ch === '/' && block[j + 1] === ',') {
        literal = block.slice(start + 1, j);
        break;
      }
    }
    if (literal === undefined) {
      throw new Error(`未能从 api-proxy.ts 的 ${WAREHOUSE_PREFIX} 元素里定位白名单正则终点`);
    }
    out.push(new RegExp(literal));
  }
  if (out.length === 0) {
    throw new Error(`未能从 api-proxy.ts 里定位任何真实元素 prefix: '${WAREHOUSE_PREFIX}'`);
  }
  return out;
}

const ALLOWED = warehousePatterns();

/**
 * 与 resolve() 同构：逐条 fallthrough，**任一条**命中即放行。
 *
 * <p>入参是已经切掉 `warehouse/` 前缀的余段（白名单正则只匹配 prefix 之后的部分）。
 * 返回 false 意味着**全部** warehouse 元素都不匹配，所以反向用例同样有效 ——
 * 一条只覆盖别的车道的窄正则不可能把本车道的未知形状放行掉。
 */
const allows = (path: string) => ALLOWED.some((pattern) => pattern.test(path));

describe('7.1.1.3 正向：三个真实形状必须放行', () => {
  it.each(['inventory/sellable', 'inventory/sellable/skus', 'inventory/sellable/capabilities'])(
    '放行 %s',
    (path) => {
      expect(allows(path)).toBe(true);
    },
  );

  it('三个形状与前端模型实际发出的路径一致（反向从模型源码抽取）', () => {
    const source = readFileSync(
      join(__dirname, '../components/inventory/inventory-sellable-model.ts'),
      'utf8',
    );
    const paths = [...source.matchAll(/['`](warehouse\/inventory\/sellable.*?)['`]/g)]
      .map((m) => m[1])
      .filter((p): p is string => typeof p === 'string')
      // 模板串里 `${queryString(params)}` 之后是运行时拼上的 query，它不参与白名单判别
      // （代理拿的是 path，query 由 request.nextUrl.search 单独带上），
      // 所以截断到第一个 `${`，只留下真实的路径形状。
      .map((p) => p.split('${')[0])
      // `noUncheckedIndexedAccess` 下 `split('${')[0]` 的类型是 `string | undefined`
      // （运行时 split 至少返回一个元素，所以这条过滤不会真的丢东西），
      // 在同一层用类型谓词窄化而不是断言，把「非空串」的语义原样保留给下游。
      .filter((p): p is string => typeof p === 'string' && p.length > 0)
      // 白名单正则只匹配 **prefix 之后** 的部分（代理的 resolve 就是先切掉 prefix 再 test），
      // 所以这里同样要切掉 warehouse/。
      .map((p) => p.slice(WAREHOUSE_PREFIX.length));
    // 挖到 0 条正是这套守卫最危险的假绿，所以这里要求三个形状全被挖到并逐条验放行
    expect([...paths].sort()).toEqual([
      'inventory/sellable',
      'inventory/sellable/capabilities',
      'inventory/sellable/skus',
    ]);
    for (const p of paths) {
      expect(allows(p)).toBe(true);
    }
  });
});

describe('7.1.1.3 反向：未登记形状仍然必须 404', () => {
  it.each([
    // 拼错的子路径：接口不存在，放行它会让界面以为功能可用
    'inventory/sellable/breakdown',
    'inventory/sellable/rows',
    'inventory/sellable/history',
    'inventory/sellable/sku/11111111-1111-1111-1111-111111111111',
    // 可售量是纯读端点：没有任何写入形状
    'inventory/sellable/recalculate',
    // 类目 / 品牌 / 店铺三个维度刻意未接入（属 catalog / channel），不得凭空放行
    'inventory/sellable/categories',
    'inventory/sellable/shops',
    // 空与额外一层
    'inventory/sellable/skus/0',
    'inventory/sellablepolicies',
    'inventory/sell',
  ])('拒绝 %s', (path) => {
    expect(allows(path)).toBe(false);
  });

  it('「全部放行」的正则会让反向用例红掉 —— 用一个已知非法形状自证', () => {
    // 极端反证：若白名单被改成 /.*/ ，下面这条必然放行，上面的反向用例则全红
    expect(new RegExp('^.*$').test('inventory/sellable/recalculate')).toBe(true);
    expect(allows('inventory/sellable/recalculate')).toBe(false);
  });

  it('sellable 不会被当成 [0-9a-f-]+ 通配分支里的 id（s / l 非十六进制）', () => {
    // 这是本车道最该钉住的一条：若某天有人把 sellable 挪到 [0-9a-f-]+ 分支之后，
    // 或者把 sellable-policies 排在 sellable 之前且不带 $ 锚点，
    // /capabilities 就会被当成「id=capabilities」凭空放行。
    expect(allows('inventory/sellable/capabilities')).toBe(true);
    // 而一个真的 UUID 形状在 sellable 下仍然必须被拒（没有 /{id} 这个形状）
    expect(allows('inventory/sellable/11111111-1111-1111-1111-111111111111')).toBe(false);
  });
});

describe('既有分支没被本车道改掉', () => {
  it.each([
    // 7.1.1.1 总览
    'inventory/overview',
    'inventory/capabilities',
    'inventory/policies',
    // 7.1.1.2 明细
    'inventory/buckets',
    'inventory/bucket-actions',
    // 7.2.2.1 可售量口径（本车道的分支紧跟在它后面，取错一侧会静默删掉它）
    'inventory/sellable-policies',
    'inventory/sellable-policies/effective',
    'inventory/sellable-policies/pools',
    'inventory/sellable-policies/pools/POOL-1',
    'inventory/sellable-policies/calculation',
    'inventory/sellable-policies/11111111-1111-1111-1111-111111111111/versions',
    // 7.4.2.1 盘点（紧跟在本车道分支之后）
    'inventory/stocktakes',
    'inventory/stocktakes/capabilities',
  ])('仍然放行 %s', (path) => {
    expect(allows(path)).toBe(true);
  });

  it('7.2.2.1 的既有形状没被本车道的分支吃掉', () => {
    // sellable 是 sellable-policies 的前缀。若本车道分支排在它之前且不锚定结尾，
    // sellable-policies/calculation 会被 sellable 分支先吃掉半截再落空。
    expect(allows('inventory/sellable-policies/calculation')).toBe(true);
    expect(allows('inventory/sellable-policies')).toBe(true);
  });
});
