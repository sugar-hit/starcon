# 2026-11-04: Kubernetes 部署件 —— 两个 Helm chart 的设计与落地

## 摘要

用户决定用 Kubernetes 部署，要求生成 Helm chart 部署件。本轮在 `infra/helm/` 下产出两个
chart：

- **`starcon-backend`** —— 9 个 Java Spring Boot 微服务（`backend/services/` 下的
  identity / catalog / channel / warehouse / trade / crm / finance / reporting / platform），
  各自独占数据库、镜像、Service、Storage。
- **`starcon-web`** —— Next.js 15 standalone 前端，服务端代理 9 个后端服务。

原先 `infra/helm/` 下只有三个空占位目录（`starcon-api` / `starcon-worker` / `starcon-web`）。
本轮把 `starcon-api` 与 `starcon-worker` 删掉（见「与占位目录的关系」），`starcon-web`
原地填成真 chart，另建 `starcon-backend`。

设计上有三处**不看代码就一定会写错**的地方，都已处理并写进 chart 注释与本文：

1. 后端没有 actuator ⇒ 没有 `/actuator/health`，探针只能用 `tcpSocket`；
2. 跨服务地址在代码里的默认值是 `http://127.0.0.1:80xx`（容器内必然错，
   个别默认值还指向了**错误的端口**）⇒ chart 必须逐个显式下发集群内 DNS；
3. 存储根目录在**构造函数**里 `createDirectories` ⇒ 路径不存在 / 不可写，服务直接起不来。

## 决策 / 结论

### 1. 三个部署形态决策（用户拍板）

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| PostgreSQL 怎么给 | **外部托管**，chart 只接 `DB_URL` 与账号 | AGENTS.md 要求每服务独占库；`infra/terraform/envs/{dev,staging,prod}` 已在按环境管基础设施 |
| Chart 怎么组织 | **两个同级 chart**（backend + web） | 9 个后端服务同仓同 CI、通常一起发布，拆 9 个 chart 是 9 倍维护成本而收益有限 |
| 密钥怎么注入 | **ESO / Vault**（ExternalSecret） | AGENTS.md 安全条款已定「走 secret manager」 |

### 2. 探针用 tcpSocket，不是 httpGet

后端**没有** `spring-boot-starter-actuator`（9 个 `pom.xml` 里都没有这个依赖），
所以 `/actuator/health` **不存在**。springdoc 在，`/v3/api-docs` 能通，但探针打它会
每次重新生成整份 OpenAPI 文档 —— 太重，不适合当探针。

⇒ 后端用 `tcpSocket` 探针；前端用 `httpGet /api/health`（Next.js route handler，
返回 `{"status":"ok"}`）。

### 3. 跨服务地址逐个显式下发

后端代码里这批变量的默认值全是 `http://127.0.0.1:80xx`。容器内 127.0.0.1 是自己，
必然连错；而且有几个默认值指向了**错误的端口**：

| 服务 | 变量 | 代码默认值 | 正确端口 |
| --- | --- | --- | --- |
| warehouse | `CHANNEL_URL` | `:8082` | 8083（8082 是 catalog） |
| channel | `TRADE_URL` | `:8082` | 8085（8082 是 catalog） |
| reporting | `CHANNEL_URL` | `:8084` | 8083（8084 是 warehouse） |
| finance | `CATALOG_URL` | `:8083` | 8082（8083 是 channel） |

⇒ chart 从 `services.<名>.upstreams` 生成集群内 DNS
`http://<fullname>-<服务名>.<ns>.svc.cluster.local:<端口>`，**不依赖**代码默认值。
用完整 FQDN 而不是短名 —— 短名依赖 resolv.conf 的 search 域，配了自定义 search 域的
集群里会解析到别的地址。

### 4. 必需环境变量清单（从 `@Value` 反查得到）

所有服务都**没有默认值**、缺了就起不来的：

- `INTERNAL_KEY` —— 9 个服务全部（服务间授权密钥）
- `IDENTITY_URL` —— 除 identity 外的 8 个
- `CHANNEL_WEBHOOK_SIGNING_KEY` —— channel（32 字节 base64，AES-256）

其余（`DB_*`、`*_STORAGE_ROOT`、`*_OAUTH_*`、`PAYMENT_*` 等）从
`deploy/docker/docker-compose.catalog.yml` 与 `@Value` 注解合并得到。

### 5. 迁移 Job 挂 emptyDir，不挂 PVC

默认**不开**独立迁移 Job —— 应用启动时自己跑 Flyway（`spring.flyway.url` 有配），
与本地开发行为一致，最省事。

要「先迁移、后发版」时（`common.migration.enabled=true`），chart 建 9 个
`pre-install,pre-upgrade` hook Job，按 `common.migration.order` 顺序执行
（identity 最先 —— 其它服务的权限码与租户关系要落在 identity 建好的结构上），
Deployment 上追加 `SPRING_FLYWAY_ENABLED=false`。

Job 挂 `emptyDir` 而不是 PVC，原因是 **`pre-install` hook 在普通资源创建之前执行**，
那时 PVC 还不存在，挂 PVC 的 Job 会卡在「卷未找到」。迁移只需要「目录可写」——
各存储实现的构造器里会 `createDirectories(root)`，目录不存在会抛
`IllegalStateException` 让 Spring 起不来；而 Flyway 写的是数据库，不写磁盘。

前提：所有 `*_STORAGE_ROOT` 都在 `/app/data` 之下（当前 4 个服务都是）。
若将来某服务在迁移期真要往磁盘写业务数据，这个 Job 得改成普通资源。

### 6. 存储卷与 UID 10001

`deploy/docker/Dockerfile.backend` 里那几行 `mkdir` + `chown 10001` + `chmod 750/700`
不是装饰 —— 存储实现**在构造函数**里就 `Files.createDirectories(root)`，失败直接抛
`IllegalStateException` ⇒ Spring 起不来。PVC 挂上去后由
`podSecurityContext.fsGroup: 10001` 保证组可写。

需要落盘的只有 4 个服务：catalog（图片）、channel（密钥/授权书/扫描件）、
reporting（导出）、platform（导入导出）。PVC 名带
`helm.sh/resource-policy: keep`，删 Pod 不删数据。

### 7. 前端的同源校验依赖 Ingress 的两个头

`apps/web/src/lib/api-proxy.ts` 对写请求（POST/PUT/DELETE）比对
`request.headers.get('origin')` 与 `request.nextUrl.origin`，不一致返回 403。
`request.nextUrl` 来自 Next.js 对 `X-Forwarded-Proto` / `X-Forwarded-Host` 的解析。

⇒ Ingress **必须**转发这两个头，否则所有写操作 403。nginx-ingress 默认就带，
chart 里明确写了「不要用注解覆盖掉」。

### 8. 渠道 OAuth 的 `publicBaseUrl` 要对外可达

平台的 `redirect_uri` 只能指向后端，而 Amazon / Shopify 回跳时必须能访问到它。
⇒ 生产要给 channel 开 Ingress（`services.channel.ingress.enabled`），
并把 `services.channel.oauth.publicBaseUrl` 填成同一个地址。
留空则用集群内地址，只适合联调。

## 关键 trade-off

| 选择 | 代价 | 为什么仍然这么选 |
| --- | --- | --- |
| 探针用 tcpSocket | 只能判断「端口通了」，判断不了应用内部健康 | 加 actuator 会动 9 个 `pom.xml`，超出「生成部署件」的范围；且 actuator 会暴露 `/actuator/**`，多一个攻击面 |
| 迁移 Job 默认关闭 | 多副本同时启动时可能并发触发 Flyway | Flyway 自带 PG advisory lock，默认行为与本地开发一致，最不容易出错；要严格控制再打开 |
| 网络策略默认关闭 | 集群内网段不做限制 | 策略写错的表现是「Pod 全灭但看不出原因」；首次上生产不适合默认打开 |
| 后端 fullname 与前端耦合 | 后端换 release 名要同步改前端 `backend.fullname` | 两个独立 chart 无法互相查询。缓解手段：后端 NOTES.txt 打印实际值，两个 README 都给出对照表 |
| 不做 worker chart | —— | `apps/worker`、`apps/scheduler`、`workers/*` 全是空占位，**没有代码可部署**；后端的定时任务跑在各自服务的 `@Scheduled` 里 |

## 过程中改掉的真实缺陷

| 缺陷 | 后果 | 修法 |
| --- | --- | --- |
| `helm.sh/hook-weight` 渲染成裸数字 | K8s annotation 只能是字符串，API server 会拒（`cannot unmarshal number into string`） | 加 `\| quote` |
| PVC 名用了驼峰的 storage 键 | 违反 RFC 1123，`helm lint` 直接警告 | 新增 `starcon-backend.storageKey` helper 转小写，PVC 名与 volume 名共用 |
| `postgres.host` 空时渲染出 `jdbc:postgresql://:5432/...` | 坏串要等到 Pod 启动、Spring 建连接池才炸 | 模板期 `fail` 并给中文报错 |
| `.prettierignore` 没排除 `infra/helm/` | `pnpm format:check`（CI 第 40 行）解析 Helm 模板失败 → CI 红 | 排除 `infra/helm/`，并写清原因（Go 模板不是合法 YAML） |

## 补：去掉 `Dockerfile.backend` 里的版本硬编码

同一轮追加的改动。原先第 8 行是：

```dockerfile
COPY --from=build /workspace/backend/services/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT.jar /app/service.jar
```

版本号 `0.1.0-SNAPSHOT` 写死在 COPY 路径里，与 `backend/pom.xml` 的 `<version>` 是一根
暗绳：版本一改，镜像构建就断。而且**失败点在 COPY** —— 现场表现是「镜像构建失败」，
排查方向会被带偏（真正的原因是 pom 里的版本变了，不是 Dockerfile 写错了）。

改法：构建阶段把产物收敛到固定路径，运行阶段只管拷。

```dockerfile
RUN set -eux; \
    dir="backend/services/${SERVICE}/target"; \
    set -- "$dir/${SERVICE}-"*.jar; \
    if [ ! -f "$1" ] || [ "$#" -ne 1 ]; then ... exit 1; fi; \
    cp "$1" /tmp/service.jar
```

- `*.jar` **不匹配** spring-boot repackage 留下的 `*.jar.original`（它以 `.original` 结尾）
- 找到 0 个或多个都直接失败并列出目录 —— 不猜。猜错会带进一个错的 jar，
  而且要到服务运行时才暴露

顺带把 `SERVICE` build arg 提到了构建阶段，用它只构建目标模块：

```dockerfile
mvn -f backend/pom.xml -B -ntp -DskipTests -pl "services/${SERVICE}" -am package
```

原先是一次 `package` 构建全部 9 个服务，而每个镜像最终只带走其中一个 jar ——
CI 要出 9 个镜像，总量会变成 81 次服务编译。改成 `-pl` + `-am` 后，每个镜像只编译
自己 + `libraries/tenancy`。

前提已核实：
- **服务之间没有互相依赖**（各自只依赖 `libraries/tenancy`）⇒ `-am` 不会漏东西
- **jOOQ 代码生成在 `jooq-codegen` profile 里**（需要活库），默认不激活 ⇒
  Docker 构建不需要数据库
- 实测 `./mvnw -pl services/identity -am package`：reactor 只有 3 个模块
  （parent / tenancy / identity），耗时 4.9s

> `chart.appVersion` 仍然默认 `0.1.0-SNAPSHOT` —— 它是**镜像 tag 的默认值**，
> 与 pom 版本解耦后不再有「构建失败」风险，但仍要与 CI 推的 tag 保持一致，
> 否则 chart 会去拉旧镜像。

### 顺带查出来的一件事：版本号在 11 个 pom 里各写了一遍

改版本做验证时发现的 —— `backend/pom.xml` 的 `<version>` 只是 11 处之一：

```
backend/pom.xml                    <version>0.1.0-SNAPSHOT</version>
backend/libraries/tenancy/pom.xml   <parent><version>0.1.0-SNAPSHOT</version>
backend/services/{9 个}/pom.xml     <parent><version>0.1.0-SNAPSHOT</version>
```

每个子模块的 `<parent>` 块里都**硬编码**了父 pom 的版本。后果：

- 改版本必须 11 处一起改，漏一处就会「Non-resolvable parent POM」；
- 只改父 pom 一个，子模块仍指向旧版本 —— Maven 在 reactor 里能靠 `relativePath`
  兜住并**不报错**，于是产物版本与预期不一致，是静默偏差。

⇒ 改版本要用 `./mvnw versions:set -DnewVersion=X -DprocessAllModules=true`
（它会连带更新子模块的 `<parent>`），不要手改。

本次验证就是按这个来做的：11 处一起改到 `0.2.0-SNAPSHOT` → 产物变成
`identity-0.2.0-SNAPSHOT.jar` → Dockerfile 的定位逻辑正确找到它 ⇒ 版本无关性成立。
验证完已全部回滚（`git checkout`），工作树里的 pom 仍是 `0.1.0-SNAPSHOT`。


**新增**

- `infra/helm/starcon-backend/Chart.yaml`
- `infra/helm/starcon-backend/values.yaml` —— 9 个服务的端口/库/角色/上游/存储全在这
- `infra/helm/starcon-backend/README.md`
- `infra/helm/starcon-backend/templates/` —— `_helpers.tpl` `deployment.yaml` `service.yaml`
  `pvc.yaml` `externalsecret.yaml` `migration-job.yaml` `ingress.yaml` `pdb.yaml`
  `hpa.yaml` `networkpolicy.yaml` `serviceaccount.yaml` `NOTES.txt`
- `infra/helm/starcon-web/` —— 同上结构（无 PVC / 迁移 Job，多 Ingress）
- `docs/operations/kubernetes-deployment.md` —— 部署手册

**修改**

- `deploy/docker/Dockerfile.backend` —— 去掉版本硬编码；`SERVICE` 提到构建阶段；只构建目标模块
- `.prettierignore` —— 排除 `infra/helm/`
- `docs/operations/README.md` —— 加部署文档入口
- `infra/helm/starcon-backend/README.md`、`docs/operations/kubernetes-deployment.md` —— 补镜像构建说明（版本解耦、代理）

**删除**

- `infra/helm/starcon-api/.gitkeep`、`infra/helm/starcon-worker/.gitkeep`、
  `infra/helm/starcon-web/.gitkeep` —— 空占位，见下

## 与占位目录的关系

`infra/helm/` 原本规划了三个 chart：`starcon-api` / `starcon-worker` / `starcon-web`。
那是 **ADR-0004 之前的设想**（Kotlin / NestJS 时代）：`apps/api` 是 NestJS API，
`apps/worker` 是独立 worker 进程。

ADR-0004 换成 Java 微服务之后，`apps/api` 与 `apps/worker` 都成了**空目录**
（只剩 `.gitkeep`），`apps/web` 是唯一有代码的前端。所以：

- `starcon-api` → 被 `starcon-backend` 取代（后端现在是 9 个 Java 服务，不是一个 API）
- `starcon-worker` → **没有对应物**。后端定时任务在各自服务的 `@Scheduled` 里
  （`@EnableScheduling`），不需要独立 worker 进程。`workers/{email-sender,
  marketplace-sync,report-generator}` 与 `apps/{worker,scheduler,notification-gateway}`
  全是空占位。
- `starcon-web` → 原地填成真 chart

## 验证

- `helm lint` 两个 chart 全绿（只剩 `icon is recommended` 的 INFO）
- `helm template` 默认场景 + 全开场景（迁移 Job / HPA / Ingress / NetworkPolicy / TLS /
  imageRegistry / imagePullSecrets / extraHosts）都渲染通过
- `helm package` 两个 chart 成功
- 写了结构化校验脚本检查渲染结果：资源计数、Service selector 与 Pod 标签一致、
  volumeMount 与 volumes/PVC 对得上、每个服务的必需环境变量都在、
  **没有残留 127.0.0.1**、前端 9 个 URL 都指向存在的后端 Service 且端口一致、
  所有 annotation 都是字符串 —— 0 问题
- `pnpm format:check`（CI 用的那条）全仓通过

> ⚠️ 未做：没有真实集群，`kubectl apply --dry-run=server` 与 ESO 实际同步未验证。
> 渲染与结构校验是**静态检查**，不等于「装得上」。

### Docker 镜像构建验证（本轮追加）

| 验证项 | 方法 | 结果 |
| --- | --- | --- |
| 单模块构建命令 | `./mvnw -pl services/identity -am package` | reactor 只有 3 个模块（parent / tenancy / identity），4.9s |
| jar 定位逻辑 · 版本不同 | 合成 `identity-0.2.0-SNAPSHOT.jar` | 正确找到 |
| jar 定位逻辑 · 产物缺失 | 合成空 target 目录 | 退出码 1，并列出目录 |
| jar 定位逻辑 · 多个 jar | 合成 2 个 jar | 退出码 1，不猜 |
| jar 定位逻辑 · **真版本变更** | 11 个 pom 一起改到 `0.2.0-SNAPSHOT` → 重建 → 定位 | 产物变 `identity-0.2.0-SNAPSHOT.jar`，定位正确 ⇒ **版本无关性成立** |
| 运行阶段机械步骤 | 构建镜像后 `docker run` 检查 | `/app/service.jar` 就位；数据目录属主 10001；权限 750 / 700；进程 UID 10001 |
| 完整镜像构建 | `docker build --build-arg SERVICE=identity` | 成功；容器内日志可见定位步骤命中 `identity-*.jar` |
| 镜像里是**正确**的服务 | `docker run` catalog 镜像 | 启动的是 `com.starcon.catalog.CatalogApplication`（不是 identity）⇒ `SERVICE` 参数确实选中了对应模块 |
| 应用可正常装配 | `docker run`（不给 DB 配置） | 打印 Spring Boot banner 后报 `Could not resolve placeholder 'DB_URL'` —— 正是未配置服务应有的失败点，说明 jar 正确、应用装配正常 |

> 本机 Docker Hub 不可达，验证时用了一份**临时** Dockerfile 把 FROM 指向
> `docker.m.daocloud.io` 镜像源（仅 FROM 行不同），验证完已删除。
> 仓库里的 `Dockerfile.backend` 未为此做任何改动。

## 后续待办

- [ ] **补 actuator 或专用 health 端点** —— 现在只能用 tcpSocket 探针，
      探不出「应用起来了但业务不可用」。加 actuator 时注意别把 `/actuator/**` 暴露到集群外。
- [ ] **实际集群验证** —— 起一个 kind / k3d，装 ESO + 9 个后端 + 前端，跑通登录与一次写操作
      （写操作才会触发前端那道同源校验）
- [ ] **build 脚本** —— 本轮只在 README 里给了 `for` 循环版镜像构建命令，
      还没进 `deploy/scripts/`
- [ ] **Terraform 侧建 9 个库与角色** —— chart 只接线，库还不存在；
      `infra/terraform/envs/prod` 目前是空占位
- [ ] **`infra/k8s/` 与 `infra/argocd/` 占位** —— 另一条部署路径，当前以 Helm 为准，
      要么补齐要么删掉，避免两条路径并存造成误解
- [ ] **`channel` 的 OAuth 公开地址** —— 生产要给 channel 开 Ingress，
      并确认平台侧应用的 `redirect_uri` 已登记
- [ ] **reporting 的 `REPORTING_PDF_FONT_PATH`** —— 默认内置字体，部分 CJK 字形会缺，
      需要完整字形时得挂字体卷
