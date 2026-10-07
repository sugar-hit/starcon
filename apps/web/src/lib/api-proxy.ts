import { NextRequest, NextResponse } from 'next/server';

const cookieName = 'starcon_session';
const legacyCookieName = 'starcon_catalog_session';

/**
 * Upstream routing.
 *
 * <p>Each backend service owns a prefix and its own database; the browser never talks to them
 * directly. A path is only proxied when it matches a service's allowlist — unknown paths must 404
 * here rather than being forwarded, so a typo cannot reach a service that would treat it as valid.
 *
 * <p>The full path is forwarded upstream, prefix included: the services expose their own namespace
 * (`/identity/roles`, `/catalog/products`) and the browser's /api mount is only a local alias.
 */
const services: readonly {
  prefix: string;
  pattern: RegExp;
  env: string;
  fallback: string;
}[] = [
  {
    prefix: 'auth/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern: /^(login|logout|session|memberships|switch)$/,
  },
  {
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      // audit 是 17.5.3.1 审计日志：列表、变更详情与 CSV 导出。
      // 导出走 GET + query（与列表同一套条件），所以 allowlist 只需放行读路径。
      /^(permissions|owner-transfer|legal-entities(?:\/[0-9a-f-]+)?|legal-entities\/references|(?:roles|members|departments|children)(?:\/[0-9a-f-]+)?|audit(?:\/(?:export|[0-9a-f-]+))?)$/,
  },
  {
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      // skus 是 3.1.2.1 SKU 列表：分页检索、能力声明、批量操作与 CSV 导出。
      // unit-conversion 是 3.1.2.4 单位与换算：单位字典、SKU 换算版本、基础数量换算与单据快照。
      //   units 后面可选一段 id（字典行的更新/删除）；skus/{id} 取面板，skus/{id}/conversions 建换算版本；
      //   snapshots 后面不接 id —— 按 ?documentRef= 查询，快照是按单据号寻址的不可变行。
      // spec-axis 是 3.1.3.1 规格轴定义：租户级规格轴字典、选项、SPU 绑定与类目规则。
      //   axes/{id}/options 按 ?value= 删除，不接第三段 id；category-rules 用 query 传 specAxisId。
      // variant-combination 是 3.1.3.2 组合唯一性校验：组合清单、预校验、矩阵草稿。
      //   spus/{id} 下面挂 combinations / check / drafts；drafts/{draftId} 可选一段 id（详情与作废）。
      // attributes 是 3.2.1.1 属性字典：租户级字典分页、概览、选项集合与类目规则。
      //   与 products/{id}/attributes（3.2.2.1 商品的 SPU 属性）**不是同一个前缀**：
      //   后者第一段是 UUID，这里第一段是字面量 attributes，字典页不需要带商品 id。
      //   options 按 ?value= 寻址，不接第三段 id；category-rules 用 query 传 category/attributeId。
      //   `options`（不带 id 段）是 3.2.1.2 的**跨属性选项总览**：
      //   它与 `{uuid}/options` 靠第一段区分——一个是字面量 options，一个是 UUID。
      //   `options/lifecycle` 与 `options/order` 是 3.2.1.2 的启用停用与批量重排，两者都用 PUT。
      // sku-attribute-override 是 3.2.2.2 SKU 属性覆写：按 SKU 寻址的有效值解析面板。
      //   与 SkuCodeController / UnitConversionController 同形（`<模块>/skus/{id}`），
      //   所以第一段是字面量 sku-attribute-override，不是 products —— 两者不冲突。
      //   下面挂 attribute-overrides/{attributeId}（PUT 覆写、DELETE 取消覆写）
      //   与 attribute-overrides/{attributeId}/clear（PUT 显式清空）。
      // categories 是 3.3.1.1 分类树管理：多棵独立的分类树、多级层级、拖拽、启用停用、
      //   多语言分类名与 SPU 主/辅分类。
      //   第一段是字面量 categories，与 `products/{uuid}`（UUID）、`attributes`（字面量）
      //   三者互不冲突：products/attributes 首段是 products，其余两个首段各不相同。
      //   trees/{id}/nodes 后面**不接第三段 id** —— 一次取回整棵树（结构 + 路径串 + 商品数
      //   来自同一次查询，拆成多个端点会让前端拿到互相不一致的三个瞬间）。
      //   nodes/{id}/move 是拖拽落点（PUT），nodes/{id}/enabled 是启用停用（PUT）。
      //   spus/{id} 是 SPU 的主分类与辅助分类（GET 读、PUT 整体替换）。
      //   attribute-requirements 是 3.3.1.2 类目属性要求，按类目寻址：
      //   overview 是覆盖率概览；{categoryId} 是配置视图（GET）与规则保存（POST，同一路径两种方法）；
      //   {categoryId}/effective 只要生效规则集；entry-form-schema（POST 生成录入表单 schema）
      //   与 validate（POST 保存前逐字段校验）是本模块的两个输出；
      //   {categoryId}/{attributeId} 同一路径 DELETE 删规则、PUT .../enabled 启停。
      // brands 是 3.3.2.1 品牌管理：租户级品牌字典 + 授权凭证 + 刊登判定。
      //   第一段是字面量 brands，与 `products/{uuid}`（UUID）不冲突。
      //   overview / candidates 是只读聚合；listing-eligibility 有两条路径——GET 单个品牌
      //   与 POST 批量（id 数量由调用方决定，放请求体而不是拼进 URL，避免撞长度上限）。
      //   {id}/authorizations 下面挂 {authorizationId} 与 {authorizationId}/enabled（撤销，不是删除）。
      // unit-groups 是 3.3.3.1 计量单位（字典视角）：单位组 + 组内换算版本 + 换算查询。
      //   与 3.1.2.4 的 unit-conversion **并存不合并**：后者是 SKU 视角的换算与单据冻结快照，
      //   前者是字典视角的单位组，两条路径共用同一份 unit_of_measure 单位字典。
      //   {id}/conversions 下面挂 {conversionId}（DELETE 删已闭合的历史版本；
      //   当前开放生效的那一版后端会拒，否则等于静默让该单位不可换算）。
      //   convert 是 POST（带请求体），不是 GET + query——试算的数量与基准日放 body 更合适。
      // media 是 3.4.1.1 媒体库：素材上传与版本、分类标签、引用计数、失效检测。
      //   与 `records/assets` **不是同一个东西**：后者是 3.1.x 的通用记录面
      //   （落 product_asset，形状是 title/notes/reference），不是媒体表。
      //   overview / verify-sweep / references 是只读或聚合路径；
      //   {id} 下面挂 versions / references / integrity-checks / enabled；
      //   references 下面可再挂一段 {referenceId}（DELETE 解除引用）；
      //   tags 是标签字典，tags/{tagId} 后面可挂 enabled。
      //   上传走 POST + multipart（文件是字节，不能走 JSON），路径与其它 POST 同形。
      //   processing 是 3.4.1.2 图片处理：处理配方（目标平台规格）+ 异步处理任务。
      //   overview / recipes / jobs / assets/{id}/history 是聚合与只读路径；
      //   recipes/{id} 后面可挂 enabled；jobs/{jobId} 下面挂 items（条目与产物）与 cancel（请求取消）。
      //   提交走 POST /processing/jobs（JSON：配方 id + 素材 id 列表），语义是「已受理」
      //   而不是「已完成」——像素加工在线程池里跑，前端轮询任务详情看进度。
      // titles 是 3.4.2.1 标题库：标题模板（变量占位）+ 词根字典 + 批量生成批次 + 候选选用。
      //   与 `media`（3.4.1.x）不是一回事：那是二进制素材资产，这里是文本文案资产，
      //   被 04 刊登同时引用但维护节奏不同（素材跟拍摄批次走，文案跟平台规则走）。
      //   templates/{id} 与 keywords/{id} 后面各可挂 enabled；
      //   generations/{id} 下面挂 skus（逐 SKU 结果与未产出原因）；
      //   records/{id} 下面挂 select / deselect / reject（选用状态机：
      //   同一 SKU 同一平台只能选用一条，所以要能取消与淘汰）。
      //   records/export 是 CSV 导出，走 GET + query 而不是 POST——导出结果与当前筛选完全一致。
      //   提交走 POST /generations，语义是「已生成」而不是「已受理」：标题生成是纯字符串计算
      //   （重复度比较范围收在单个 SKU 内），几十毫秒就完事，所以返回时候选已经落库，
      //   前端**不轮询**。
      // descriptions 是 3.4.2.2 描述与卖点库：内容模板 + 多语言译文 + 卖点短语库 + 批量填充。
      //   与 `titles` 并列而不是合并：那边是**有平台硬字符上限的短文本**（矛盾是重复铺货判定），
      //   这边是**无字符上限的长文本**（矛盾是「变量缺失必须报告」）。排版约束不同，路由分开。
      //   templates/{id} 下面挂 enabled、translations（可只到二级，也可带 {translationId}）
      //   与 translation-coverage（GET + locale query：这几种语言能不能直接填）；
      //   phrases/{id} 与 renders/{id} 后面可挂 enabled / skus / missing-variables；
      //   translations/{id} 是**不带模板 id** 的一级寻址（与 phrases/{id} 同一形状：
      //   删一条译文时前端手上只有译文 id，而 URL 的首段是字面量 translations 而非 UUID）；
      //   sections 与 sections/export 分别是段落列表与 CSV 导出（GET + query，
      //   导出结果与当前筛选完全一致）。
      //   提交走 POST /renders，语义是「已填充」而不是「已受理」。
      /^(products(?:\/[0-9a-f-]+(?:\/(?:attributes|skus)(?:\/[0-9a-f-]+)?)?)?|attributes(?:\/(?:overview|category-rules|options(?:\/(?:lifecycle|order))?|[0-9a-f-]+(?:\/(?:options|category-rules))?))?|brands(?:\/(?:overview|candidates|listing-eligibility|[0-9a-f-]+(?:\/(?:listing-eligibility|enabled|authorizations(?:\/[0-9a-f-]+(?:\/enabled)?)?))?))?|categories(?:\/(?:overview|trees(?:\/[0-9a-f-]+(?:\/nodes)?)?|nodes\/[0-9a-f-]+(?:\/(?:move|enabled))?|spus\/[0-9a-f-]+|attribute-requirements(?:\/overview|\/[0-9a-f-]+(?:\/[0-9a-f-]+(?:\/enabled)?|\/(?:effective|entry-form-schema|validate))?)))|records\/(research|compliance|assets)(?:\/[0-9a-f-]+)?|media(?:\/(?:overview|verify-sweep|references|tags|processing(?:\/(?:overview|recipes(?:\/[0-9a-f-]+(?:\/enabled)?)?|jobs(?:\/[0-9a-f-]+(?:\/(?:items|cancel))?)?|assets\/[0-9a-f-]+\/history)?)?)|[0-9a-f-]+(?:\/(?:versions|references(?:\/[0-9a-f-]+)?|integrity-checks|enabled))?|tags\/[0-9a-f-]+(?:\/enabled)?)?|skus(?:\/(?:capabilities|batch|export))?|unit-groups(?:\/(?:overview|convert|[0-9a-f-]+(?:\/(?:enabled|conversions(?:\/[0-9a-f-]+)?))?))?|unit-conversion(?:\/(?:units(?:\/[0-9a-f-]+)?|overview|skus\/[0-9a-f-]+(?:\/conversions)?|convert|snapshots))?|spec-axis(?:\/(?:overview|axes(?:\/[0-9a-f-]+(?:\/options)?)?|spus\/[0-9a-f-]+(?:\/axes(?:\/[0-9a-f-]+)?)?|category-rules))?|variant-combination(?:\/(?:overview|spus\/[0-9a-f-]+(?:\/(?:combinations|check|drafts(?:\/[0-9a-f-]+)?))?))?|sku-attribute-override\/skus\/[0-9a-f-]+(?:\/attribute-overrides\/[0-9a-f-]+(?:\/clear)?)?|titles(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|keywords(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|generations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/skus)?)?|records(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:select|deselect|reject))?)?))?|descriptions(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:enabled|translation-coverage|translations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?)?|phrases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|renders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:skus|missing-variables))?)?|translations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|sections(?:\/export)?))?)$/,
  },
  {
    // 02 渠道与店铺：平台目录、授权连接、店铺与站点。
    // health/reminders 属于连接健康，marketplace 挂在店铺下，transfer 是负责人转让。
    // platforms/{code}/enums 后面可选一段 id：租户映射覆盖按 id 删除。
    // oauth/* 是 2.2.1.2 授权向导：callback 由平台重定向直接打进来（浏览器导航，GET），
    // sessions 后面可选一段 id 与 shops/confirm/cancel。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^(platforms(?:\/[A-Za-z0-9_]+\/enums(?:\/[0-9a-f-]+)?)?|connections(?:\/(?:reminders|[0-9a-f-]+(?:\/(?:credential|health|revoke))?))?|shops(?:\/[0-9a-f-]+(?:\/(?:marketplaces(?:\/[0-9a-f-]+)?|transfer))?)?|sync-cursors(?:\/[0-9a-f-]+(?:\/(?:pause|resume|rewind|trigger))?)?|sync-runs(?:\/[0-9a-f-]+(?:\/(?:complete|resolve))?)?|sync-failures(?:\/summary|[0-9a-f-]+\/replay)?|platform-operations(?:\/[0-9a-f-]+(?:\/(?:start|succeed|fail|unknown|verify))?)?|oauth\/(?:callback|sessions(?:\/[0-9a-f-]+(?:\/(?:shops|confirm|cancel))?)?))$/,
  },
  {
    // 07 库存：库存总览（7.1.1.1）、库存明细 bucket（7.1.1.2）、筛选下拉参照数据与超储阈值。
    // overview/capabilities/policies 是总览本身；warehouses/legal-entities/skus/quality-statuses
    // 是筛选下拉项。policies/{id} 后面接一段 id（更新与删除）。
    // buckets/bucket-actions 是 7.1.1.2 的明细读端点（纯读，不需要写权限）；
    // locations/lots 是逐级下钻的库位与批次下拉项，与其余参照数据同形。
    // 注意 7.1.2 流水（ledger）、7.2 预占、7.4 调整、7.5 调拨仍未交付，
    // 前端拿到的行内操作入口 available=false，这里**不**放行它们的路径。
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    pattern:
      /^(inventory\/(?:overview|buckets|bucket-actions|capabilities|policies(?:\/[0-9a-f-]+)?)|warehouses|legal-entities|skus|quality-statuses|locations|lots)$/,
  },
];

function resolve(path: string) {
  for (const service of services) {
    if (!path.startsWith(service.prefix)) continue;
    if (service.pattern.test(path.slice(service.prefix.length)))
      return { base: process.env[service.env] ?? service.fallback };
  }
  return null;
}

export async function proxy(request: NextRequest, context: { params: { path: string[] } }) {
  const path = context.params.path.join('/');
  const target = resolve(path);
  if (!target) return NextResponse.json({ message: '接口不存在' }, { status: 404 });
  if (
    !['GET', 'HEAD'].includes(request.method) &&
    request.headers.get('origin') !== request.nextUrl.origin
  ) {
    return NextResponse.json({ message: '请求来源不正确' }, { status: 403 });
  }
  const { base } = target;
  const body = request.method === 'GET' ? undefined : await request.text();
  if (body && body.length > 200_000)
    return NextResponse.json({ message: '请求内容过大' }, { status: 413 });
  try {
    const upstream = await fetch(`${base}/${path}${request.nextUrl.search}`, {
      method: request.method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${request.cookies.get(cookieName)?.value ?? request.cookies.get(legacyCookieName)?.value ?? ''}`,
      },
      ...(body ? { body } : {}),
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000),
    });
    const text = await upstream.text();
    const contentType = upstream.headers.get('content-type') ?? '';
    // CSV 导出必须原样透传：走 JSON.parse 会把 CSV 解析失败变成 503，而且即使解析成功
    // 也会被重新序列化成 JSON，下载到的是一串引号转义的文本。
    // 只认 text/csv：JSON 接口一律走下面的正常分支，不受这里影响。
    if (text && contentType.includes('text/csv')) {
      const raw = new NextResponse(text, { status: upstream.status, headers: {} });
      raw.headers.set('Content-Type', contentType);
      const disposition = upstream.headers.get('content-disposition');
      if (disposition) raw.headers.set('Content-Disposition', disposition);
      raw.headers.set('Cache-Control', 'no-store');
      if (upstream.status === 401) {
        raw.cookies.delete(cookieName);
        raw.cookies.delete(legacyCookieName);
      }
      return raw;
    }
    const data = text ? JSON.parse(text) : null;
    let token: string | undefined;
    if (path === 'auth/login' && upstream.ok && data?.token) {
      token = data.token;
      delete data.token;
    }
    const response = NextResponse.json(data, {
      status: upstream.status,
      headers: { 'Cache-Control': 'no-store' },
    });
    if (token) {
      response.cookies.delete(legacyCookieName);
      response.cookies.set(cookieName, token, {
        httpOnly: true,
        sameSite: 'strict',
        secure: request.nextUrl.protocol === 'https:',
        path: '/',
        maxAge: 8 * 3600,
      });
    }
    if ((path === 'auth/logout' && upstream.ok) || upstream.status === 401) {
      response.cookies.delete(cookieName);
      response.cookies.delete(legacyCookieName);
    }
    return response;
  } catch {
    return NextResponse.json({ message: '服务暂时无法连接，请稍后重试' }, { status: 503 });
  }
}
