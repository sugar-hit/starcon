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
      // operation-log 是 17.5.3.2 操作日志：检索、单条详情、CSV 导出与链路关联视图。
      //   trace/{requestId} 放行但**不接 uuid 段**——请求 ID 可能是网关传来的非 UUID 串，
      //   用字符集而不是 {uuid} 段来界定。purge 是 POST 的留存期清理，
      //   同样在读路径正则里放行（前端页面只读，清理动作留给运维脚本调用）。
      // tenants 是 17.1.1.1 租户列表：只有 tenants 与 tenants/current 两个形状，
      //   **刻意不接 {uuid} 段** —— 按任意 tenant id 读取租户不是这个模块该有的能力，
      //   可切换集合只能由服务端从成员关系推导（实体目录 §4 的窄权限路径）。
      // department-leaders 是 17.2.1.2 部门负责人：负责人清单（没有负责人的部门也在列，
      //   因为它们要在审批流里配兜底）。departments/{id}/leader 下面挂 leader、proxies、
      //   grants 与 approval-chain 四类**已实现**的子资源：多放一段会让一个还不存在的
      //   路径看起来像已交付，所以逐段列出而不是一条通配。
      // hierarchy-authorizations 是 17.1.1.3 层级授权：授权记录的增改查与判定。
      //   check 放在 {uuid} 之前判别（第一段是字面量，不是 UUID），两条不冲突；
      //   {id}/revoke 是撤销（POST，见下），撤销是审计事件所以不是 DELETE。
      // accounting-books 是 17.1.2.2 本位币与账簿：账簿挂在**法人**上，所以写入形状是
      //   legal-entities/{uuid}/accounting-books（POST 新建），而更新/删除按账簿自身 id 寻址：
      //   accounting-books/{uuid}。两个前缀第一段不同，不会互相吃掉。
      // offboarding 是 17.2.2.3 离职与停用：只登记「停用」，不提供任何 DELETE，
      //   所以这一支没有删除动作分支。四种形状分别是案卷本身、撤销、
      //   责任转移条目（登记 / 执行转移）、回收条目（登记 / 执行回收），
      //   后两者的动作后缀各只认一个，多一层仍然 404。
      //   第一段 offboarding 是字面量，与 members/{uuid} 靠首段区分，不会互相吃掉。
      // hierarchy-authorizations 是 17.1.1.3 层级授权：授权记录的增改查与判定。
      //   check 放在 {uuid} 之前判别（第一段是字面量，不是 UUID），两条不冲突；
      //   {id}/revoke 是撤销（POST，见下），撤销是审计事件所以不是 DELETE。
      // accounting-books 是 17.1.2.2 本位币与账簿：账簿挂在**法人**上，所以写入形状是
      //   legal-entities/{uuid}/accounting-books（POST 新建），而更新/删除按账簿自身 id 寻址：
      //   accounting-books/{uuid}。两个前缀第一段不同，不会互相吃掉。
      // business-parameters 是 17.5.1.1 业务参数：三种形状分处同一前缀下——
      //   business-parameters（参数码表）/effective（生效值解析）/history（变更历史）
      //   都是单个字面量子路径；overrides 是集合读 + POST + {uuid} 的 PUT/DELETE；
      //   freezes 是 POST，读取是 {documentType}/{documentNo} 两段普通字符串。
      //   两段路径里都不含横杠，与 overrides 后只接一段 [0-9a-f-]+ 的形状不会互相吃掉。
      // numbering 是 17.5.1.2 编号规则：entities（单据实体码表）/rules（集合读 + POST）/
      //   rules/conflicts（冲突检测）/rules/{uuid}（GET·PUT·DELETE）/rules/{uuid}/next（取号）
      //   /preview（POST 预览）/sequences（计数器）/issued（已发放编号）。
      //   conflicts 与 {uuid} 靠第一段是字面量还是 UUID 区分；next 与 {uuid}/preview 不冲突。
      // onboardings 是 17.2.2.2 成员开通与入职：列表、开通（创建）与完成入职。
      //   形状逐字列出：onboardings 是集合读 + POST 开通；
      //   onboardings/{id}/complete 是完成入职的状态流转（POST）。
      //   **刻意不放行 onboardings/{id}**：后端没有「按 id 取单条入职记录」这个端点，
      //   放行它会让界面以为已交付——放行不该存在的路径比 404 更糟。
      /^(permissions|owner-transfer|legal-entities(?:\/[0-9a-f-]+)?|legal-entities\/(?:[0-9a-f-]+\/accounting-books|references)|(?:roles|members|departments|children)(?:\/[0-9a-f-]+)?|tenants(?:\/current)?|audit(?:\/(?:export|[0-9a-f-]+))?|operation-log(?:\/(?:export|trace\/[0-9a-zA-Z-]+|[0-9a-f-]+))?|hierarchy-authorizations(?:\/check|\/[0-9a-f-]+(?:\/revoke)?)?|accounting-books(?:\/[0-9a-f-]+)?|business-parameters(?:\/(?:effective|history|overrides(?:\/[0-9a-f-]+)?|freezes(?:\/[^/]+\/[^/]+)?))?|numbering(?:\/(?:entities|preview|sequences|issued|rules(?:\/conflicts|\/[0-9a-f-]+(?:\/next)?)?))?|onboardings(?:\/[0-9a-f-]+\/complete)?|department-leaders|departments\/[0-9a-f-]+\/(?:leader(?:\/(?:proxies|grants)(?:\/[0-9a-f-]+)?)?|approval-chain)|offboarding(?:\/[0-9a-f-]+(?:\/revert|\/responsibility-items(?:\/[0-9a-f-]+(?:\/transfer)?)?|\/reclaim-items(?:\/[0-9a-f-]+(?:\/reclaim)?)?)?)?)$/,
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
      // bundles 与 versions 是 3.5.1.1 套装版本管理：套装档案 + 版本（草稿/已发布）+ 生效区间 + 组件清单。
      //   与 titles / descriptions / translations 并列而不合并：那几套的「版本」是**文案版本**，
      //   这里的「版本」是**套装版本**，两者名字一样而语义完全不同，合并会让「v2 指哪个版本」无法回答。
      //   bundles 下面挂 overview（概览）、{id}/status（归档启用）、{id}/effective（**当前生效版本的时刻查询**，
      //   04 刊登与 09 订单取版本走的就是它）、{id}/versions（某个套装的版本列表 / 建草稿版本）；
      //   versions 是跨套件的版本列表与单版本操作：publish（发布）、close（**向前关闭生效区间**，
      //   已发布版本上唯一合法的写操作）、components（组件清单的读与整体替换）。
      //   虚拟套装不持有独立库存：扣的是组件库存（实体目录 §3.4），本模块不写库存。
      // components 与 bundle-skus 是 3.5.1.2 组件维护：跨版本组件清单 + 逐行增删改 +
      //   循环引用检测。与 versions 下的 components **并列而不合并**：
      //   PUT /versions/{id}/components 是 3.5.1.1 的「整体替换清单」，
      //   POST /versions/{id}/components 是本模块的「加一行」，两者语义不同必须能被 URL 区分；
      //   PUT|DELETE /components/{id} 改/删单行（带行级乐观锁 rowVersion）。
      //   components 下的 overview 是组件工作台概览，{id} 是单行组件详情。
      //   versions/{id}/component-check 是**结构化校验结果**（一次列全循环/数量非法/组件不存在），
      //   与「保存时报错」分开：保存只给一条原因，校验让运营一次改完而不是反复提交试错。
      //   bundle-skus 是「哪些 SKU 代表哪些套装」这条指向 —— 嵌套套装在数据上靠它表达，
      //   没有它「套装 A 含套装 B」无法表达，循环也就无从发生；改指向同样要过循环检测。
      // boms / bom-versions / bom-components 是 3.5.2.1 BOM 版本管理：物料清单（成品由哪些组件组成）
      //   的版本。与 bundles / versions **并列而不合并**：名字都叫「版本」但语义不同 ——
      //   那边是「几个 SKU 捆在一起卖」（套装：虚拟扣组件、不持有独立库存），
      //   这边是「这个成品要领哪些料」（BOM：产实物成品、有自己的成品 SKU）。
      //   合并会让「v2 指哪个版本」在 URL 上无法回答。
      //   boms 下面挂 overview（概览）、{id}/status（归档启用）、{id}/effective（**当前生效版本的
      //   时刻查询**，06 采购建议与 08 加工单取版本走的就是它）、{id}/versions（建草稿版本）。
      //   bom-versions 是跨 BOM 的版本列表与单版本操作：publish（发布）、close（**向前关闭生效区间**，
      //   已发布版本上唯一合法的写操作）、copy（版本复制：整份复制成新草稿，用量与损耗率都带走）、
      //   components（组件清单的读与整体替换）、requirement（**单层**需求量，规则 R7，不取整）。
      //   bom-components/usage 是**倒排查询**：某个 SKU 被哪些 BOM 版本当组件用着 ——
      //   改用量之前要先看这个方向。它不接 id 段（按 ?skuId= 寻址，首段是字面量 bom-components）。
      //   多级递归展开不在本模块：那是 3.5.2.2（BOM 展开）的独立职责。
      // bom-explosions 是 3.5.2.2 BOM 展开：**多层递归展开树**（子 BOM 当作组件继续往下走）、
      //   按仓库过滤、需求量/可用/在途/缺口，以及**展开运行记录**（读与列表）。
      //   展开结果会落库成一条运行记录，因为 06 的采购单引用的是**某一次展开的 id** ——
      //   「现在的 BOM × 现在的库存」随时会变，单子发出去之后就无法解释「当时为什么按这个数下单」。
      //   与 bom-versions 并列而不合并：那边是「物料清单长什么样」，这边是「按它生产 N 个成品缺多少料」。
      //   它只有 {id} 段（读与列表），没有 overview 之类的子路径 —— 展开是一次动作不是一个子资源。
      //   循环引用返回 409（不放行任何路径去绕过它）。
      // parties 是 3.6.1.1 往来单位档案：业务主体（企业或个人）+ 角色指派
      //   （供应商 / 客户 / 承运商）+ 状态流转 + 软删除。
      //   与 products/** 并列而不挂在它下面：往来单位不是商品的属性，而是与商品平级的主数据
      //   —— 06 采购、11 售后、12 财务也引用它，而那些都不是商品页面。
      //   第一段是字面量 parties，与 products/{uuid}、attributes、brands 互不冲突。
      //   overview / candidates 是只读聚合（candidates 是 06/11/12 的下拉数据源，默认只给可用的）；
      //   {id}/status 是状态流转（进出拉黑要理由，**走独立端点**而不是复用 PUT {id}，
      //   否则「改个名字」也会被迫填理由）；
      //   {id}/roles 下面可挂 {roleId}（PUT 改状态备注、DELETE 解除指派）——
      //   角色指派与主体是「整体替换 + 单条增删改」两种语义并存，所以两条路径都要放。
      //   {id}/role-profiles 是 3.6.1.2 的汇总读出口（三个身份一次拿全，避免详情页拼出旧数据）；
      //   {id}/{supplier|customer|carrier}-profile 是 3.6.1.2 的角色档案读写（GET / PUT / DELETE），
      //   字面量三个而不是一条通配 —— 放行 {id}/xxx 的任意子段会让「一个还没实现的子路径」
      //   看起来像已交付（那正是本文件之前对这三条刻意不放行的原因）。
      // role-profiles 是 3.6.1.2 角色档案台账：overview（覆盖率 + 两个脏数据探针）、
      //   options（词表，与后端同源）、列表（按身份分页 + 缺档案筛选）。
      //   它与 parties 并列而不是挂在 {id} 下面：运营问的是「哪些供应商还没建档案」，
      //   主体不固定，所以那是集合资源。
      // supplier-quotes 是 3.6.2.1 供应商报价：报价台账 + 蓝图点名的「有效报价」输出。
      //   第一段是**字面量** supplier-quotes，与 products/{uuid}、parties、role-profiles
      //   各自首段都不同，不存在冲突。
      //   子路径逐字列出而不是一条通配：
      //     overview / options  —— 概览与词表（只读聚合）
      //     effective           —— 蓝图输出「有效报价列表」（歧义时 selected 为 null 并逐条标状态）
      //     effective/resolve   —— 06 采购带出价格走它，歧义时**必须 409**（规则 R2）
      //     {id}                —— 单条报价的读写（GET / PUT / DELETE）
      //     {id}/tiers          —— 阶梯价明细（编辑面板单独刷新阶梯时用）
      //   放行 {id} 之后任意一段会让「一个还没实现的子路径」看起来像已交付，
      //   所以 {id} 只带 /tiers 一个字面量子段。
      // translations 是 3.4.3.1 商品多语言内容：SPU/SKU 的多语言名称与描述 + 语言需求 + AI 建议。
      //   与 descriptions 并列而不是合并：那边带 locale 的是「描述模板与其渲染段落」的多语言，
      //   这边是「商品档案本身」的多语言（V22 文件头有完整边界说明）。
      //   locales/requirements 与 locales/sync 是「需要哪些语言由店铺启用站点推导」的一侧：
      //   sync 走 POST，02 渠道未接入时返回 409 并写明归属，不是一个 HTTP 错误页。
      //   spus/{id} 与 skus/{id} 是商品翻译工作台（可带 entries 建译文）；
      //   entries 下的 status 是人工校对（PUT，会记校对人与校对时刻），entries/export 是 CSV 导出；
      // export-sources 与 export-jobs 是 3.8.2.1 导出：能力声明（字段/排序/筛选词表）、
      //   导出记录（条件、字段、行数、时间、操作人）、以及 {id}/file 下载与 {id}/cancel 作废。
      //   第一段是字面量 export-*，与 products/{uuid}、import-jobs 三者互不冲突。
      //   {id} 后面最多再挂一段 file 或 cancel，不放行更深层 —— 放行一个还没实现的子路径
      //   会让它看起来像已交付。
      //   suggestions/{id}/adopt 与 /reject 是建议处置（POST，处置是终态）；
      //   batches 可带 {batchId} 读批次详情（逐条结果）。
      // import-jobs 是 3.8.1.1 导入向导的任务面，3.8.1.2 的校验报告挂在它的 report 子树上
      //   （报告不是独立资源：它是「某一个导入任务那一轮预校验结论」的视图，
      //   挂在任务下之后租户隔离只需守住一条父路径，父任务读不到则子报告读不到）。
      //   {id}/report 是总览（第一次 GET 顺带生成，之后原样返回快照 + 过期标记），
      //   同一个 POST 是显式重新生成；
      //   {id}/report/issues 是错误明细表（分页 + severity/category/rowNo 三种筛选，可组合）；
      //   {id}/report/exports 是导出的登记与下载（GET 列表 / POST 发起 / {exportId}/file 下载字节），
      //   文件走普通链接下载而不是 JSON，与模板下载同一处理方式。
      // completeness 是 3.9.2.1 完整度评分：评分台账 + 评分卡 + 缺失项清单 +
      //   批量补全入口 + 报关/认证声明读写 + 刊登/清关预检。
      //   第一段是字面量 completeness，与 products/{uuid}、parties、supplier-quotes
      //   各自首段都不同，不存在冲突。
      //   子路径逐字列出（不放行任意一段）：
      //     overview / options                     —— 概览与词表（六维度、分档、阈值）
      //     gaps / gaps/summary                    —— 缺失项清单（按缺失码聚合）与统计
      //     evaluations / evaluations/{id}          —— 评估快照（回答「当时为什么被拦住」）
      //     precheck/listing                       —— 4.2.3.2 刊登预检（POST，带 SPU 清单）
      //     precheck/customs                       —— 报关流程的清关预检（POST）
      //     customs/skus/{skuId}                   —— 报关属性声明（GET / PUT / DELETE）
      //     certifications/skus/{skuId}[/{certId}] —— 认证声明（一个 SKU 多条）
      //     SPU|SKU/{ownerId}                      —— 评分卡；主体类型写成字面量而不是
      //       {ownerType}，否则它会连带放行「一个还没实现的子路径」——
      //       放行不该存在的路径比 404 更糟：界面会以为功能已交付。
      /^(products(?:\/[0-9a-f-]+(?:\/(?:attributes|skus)(?:\/[0-9a-f-]+)?)?)?|attributes(?:\/(?:overview|category-rules|options(?:\/(?:lifecycle|order))?|[0-9a-f-]+(?:\/(?:options|category-rules))?))?|brands(?:\/(?:overview|candidates|listing-eligibility|[0-9a-f-]+(?:\/(?:listing-eligibility|enabled|authorizations(?:\/[0-9a-f-]+(?:\/enabled)?)?))?))?|parties(?:\/(?:overview|candidates|[0-9a-f-]+(?:\/(?:status|roles(?:\/[0-9a-f-]+)?|role-profiles|(?:supplier|customer|carrier)-profile|addresses(?:\/(?:defaults|candidates|[0-9a-f-]+(?:\/(?:usages|default|versions(?:\/[0-9]+)?))?))?|contacts(?:\/(?:default|candidates|[0-9a-f-]+(?:\/primary)?))?))?))?|categories(?:\/(?:overview|trees(?:\/[0-9a-f-]+(?:\/nodes)?)?|nodes\/[0-9a-f-]+(?:\/(?:move|enabled))?|spus\/[0-9a-f-]+|attribute-requirements(?:\/overview|\/[0-9a-f-]+(?:\/[0-9a-f-]+(?:\/enabled)?|\/(?:effective|entry-form-schema|validate))?)))|records\/(research|compliance|assets)(?:\/[0-9a-f-]+)?|media(?:\/(?:overview|verify-sweep|references|tags|processing(?:\/(?:overview|recipes(?:\/[0-9a-f-]+(?:\/enabled)?)?|jobs(?:\/[0-9a-f-]+(?:\/(?:items|cancel))?)?|assets\/[0-9a-f-]+\/history)?)?)|[0-9a-f-]+(?:\/(?:versions|references(?:\/[0-9a-f-]+)?|integrity-checks|enabled))?|tags\/[0-9a-f-]+(?:\/enabled)?)?|skus(?:\/(?:capabilities|batch|export))?|unit-groups(?:\/(?:overview|convert|[0-9a-f-]+(?:\/(?:enabled|conversions(?:\/[0-9a-f-]+)?))?))?|unit-conversion(?:\/(?:units(?:\/[0-9a-f-]+)?|overview|skus\/[0-9a-f-]+(?:\/conversions)?|convert|snapshots))?|spec-axis(?:\/(?:overview|axes(?:\/[0-9a-f-]+(?:\/options)?)?|spus\/[0-9a-f-]+(?:\/axes(?:\/[0-9a-f-]+)?)?|category-rules))?|variant-combination(?:\/(?:overview|spus\/[0-9a-f-]+(?:\/(?:combinations|check|drafts(?:\/[0-9a-f-]+)?))?))?|sku-attribute-override\/skus\/[0-9a-f-]+(?:\/attribute-overrides\/[0-9a-f-]+(?:\/clear)?)?|titles(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|keywords(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|generations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/skus)?)?|records(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:select|deselect|reject))?)?))?|descriptions(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:enabled|translation-coveragetranslations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?)?|phrases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|renders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:skus|missing-variables))?)?|translations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|sections(?:\/export)?))?|bundles(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|effective|versions))?))?|versions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|close|components|component-check))?)?|components(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?|bundle-skus(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|boms(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|effective|versions))?))?|bom-versions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|close|copy|components|requirement))?)?|bom-components\/usage|bom-explosions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|import-templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/file)?)?|import-field-mappings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|import-jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:mapping|prevalidate|execute|cancel|rows|errors|report(?:\/(?:issues|exports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/file)?))?))?)?|supplier-quotes(?:\/(?:overview|options|effective(?:\/resolve)?|[0-9a-f-]+(?:\/tiers)?))?|role-profiles(?:\/(?:overview|options))?|translations(?:\/(?:overview|locales(?:\/(?:requirements|sync|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?))?|spus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/entries)?|skus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/entries)?|entries(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|coverage|suggestions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:adopt|reject))?)?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))|duplicate-scans(?:\/(?:overview|[0-9a-f-]+(?:\/candidates)?)?)?|duplicate-candidates\/[0-9a-f-]+(?:\/(?:decision|merge-preview|merge)?)?|export-sources|export-jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:file|cancel))?)?|zombie-candidates(?:\/(?:overview|options|rules(?:\/[A-Za-z0-9_]+)?|evidence|reference-stats|deactivate|records|deactivation-targets))?|completeness(?:\/(?:overview|options|gaps(?:\/summary)?|evaluations(?:\/[0-9a-f-]+)?|precheck\/(?:listing|customs)|customs\/skus\/[0-9a-f-]+|certifications\/skus\/[0-9a-f-]+(?:\/[0-9a-f-]+)?|(?:SPU|SKU)\/[0-9a-f-]+))?|sku-merges(?:\/overview|\/by-candidate\/[0-9a-f-]+|\/by-sku\/[0-9a-f-]+|\/[0-9a-f-]+)?)$/,
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
    // sellable-policies 是 7.2.2.1 可售量口径：列表 / effective / pools / pools/{poolKey} / {id}
    // 五个读形状，calculation（POST 试算）、{id}/versions（换版）、{id}/retire（作废）三个动作。
    // 口径路径上刻意**没有** DELETE 段：作废是审计事件，档位行永久保留，
    // 放行一个删除形状会让"能不能物理删掉一条防超卖配置"看起来是能。
    // effective / pools / pools/{poolKey} 是字面量，排在 {id} 之前判别，两者不会互相吃掉。
    // stocktakes 是 7.4.2.1 盘点单：列表 / capabilities / {id} / {id}/lines /
    // {id}/lines/{lineId}/count / {id}/tasks / {id}/tasks/{taskId}/assign。
    // 盘点路径上同样**没有** DELETE：账面数是冻结快照，删一张盘点单等于让这次盘从未发生过。
    // capabilities 是字面量，排在 {id} 之前判别。
    // stocktake-variances 是 7.4.2.2 盘点差异处理：capabilities / sheets/{sheetId}/
    // lines|report|audit|adjustments / adjustments/{id}(+submit|approve|reject|cancel|post)。
    // 同样**没有** DELETE：差异处理全程只追加审计，归因/调整单/审计行都不可删，
    // 放行一个删除形状会让「能不能把一条差异的痕迹抹掉」看起来是能。
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    pattern:
      /^(inventory\/(?:overview|buckets|bucket-actions|capabilities|policies(?:\/[0-9a-f-]+)?|sellable-policies(?:\/(?:effective|pools(?:\/[^/]+)?|calculation)|[0-9a-f-]+(?:\/(?:versions|retire))?)?)|stocktakes(?:\/capabilities|[0-9a-f-]+(?:\/lines(?:\/[0-9a-f-]+\/count)?|\/tasks(?:\/[0-9a-f-]+\/assign)?)?)|stocktake-variances(?:\/capabilities|sheets\/[0-9a-f-]+\/(?:lines(?:\/[0-9a-f-]+\/attribute)?|report|audit|adjustments)|adjustments(?:\/[0-9a-f-]+(?:\/(?:submit|approve|reject|cancel|post))?)?)?|warehouses|legal-entities|skus|quality-statuses|locations|lots)$/,
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
