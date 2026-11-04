# starcon-backend

starcon 后端 —— **9 个 Java Spring Boot 微服务**的 Helm 部署件。

这不是「一个可水平扩的应用」，而是 9 个互相独立的服务：各占一个端口、各自独占一个
PostgreSQL 库、各自一个镜像。任一服务挂了只影响它自己那条链路。

| 服务 | 端口 | 数据库 | 运行角色 | 落盘目录 |
| --- | --- | --- | --- | --- |
| identity | 8081 | starcon_identity | identity_app | — |
| catalog | 8082 | starcon_catalog | catalog_app | 商品图片 |
| channel | 8083 | starcon_channel | channel_app | 渠道密钥 / 授权书 / 认证扫描件 |
| warehouse | 8084 | starcon_warehouse | warehouse_app | — |
| trade | 8085 | starcon_trade | trade_app | — |
| crm | 8086 | starcon_crm | crm_app | — |
| finance | 8087 | starcon_finance | finance_app | — |
| reporting | 8088 | starcon_reporting | reporting_app | 报表导出 |
| platform | 8089 | starcon_platform | platform_app | 导入导出任务 |

端口与库名的对应关系来自 `deploy/docker/docker-compose.catalog.yml` —— 那份文件是本地
拓扑的事实来源，改动前先看它。

## 前置条件

- Kubernetes ≥ 1.25
- 外部托管 PostgreSQL 16（**每个服务一个库**，由 Terraform / 云侧 PG 提供；本 chart 不内置 PG）
- External Secrets Operator + 一个可用的 SecretStore（默认 `ClusterSecretStore/vault`）
- 9 个后端镜像已推到仓库

## 安装

```sh
helm upgrade --install starcon-backend ./infra/helm/starcon-backend \
  -n starcon --create-namespace \
  -f values-prod.yaml
```

`values-prod.yaml` 至少要改这几项：

```yaml
global:
  imageRegistry: registry.example.com/starcon
  imagePullSecrets:
    - name: registry-credentials

postgres:
  host: pg-starcon.internal.svc.cluster.local
  port: 5432
  sslMode: verify-full

web:
  publicUrl: https://erp.example.com

secretManager:
  storeRef:
    kind: ClusterSecretStore
    name: vault
```

### ⚠️ `postgres.host` 不填会直接报错

chart 在模板期就会 `fail`，不会渲染出 `jdbc:postgresql://:5432/...` 这种坏连接串 ——
那种串要等到 Pod 启动、Spring 建连接池时才炸，排查成本高得多。

## 镜像构建

镜像名约定为 `starcon/<服务名>:<tag>`，与 `deploy/docker/Dockerfile.backend` 的
`--build-arg SERVICE=<服务名>` 约定对齐。`chart.appVersion` 默认 `0.1.0-SNAPSHOT`
（= `backend/pom.xml` 的 version），所以：

```sh
for s in identity catalog channel warehouse trade crm finance reporting platform; do
  docker build -f deploy/docker/Dockerfile.backend \
    --build-arg SERVICE="$s" \
    -t "starcon/$s:0.1.0-SNAPSHOT" .
done
```

**Dockerfile 不含版本号**：产物 jar 由构建时定位（`target/` 下恰好一个
`<服务名>-*.jar`），所以改 `backend/pom.xml` 的 `<version>` 不会让镜像构建失败。
镜像 tag 由你决定 —— 但要**同步改 `chart.appVersion`**，否则 chart 会去拉旧 tag。

> 网络受限（如 Docker Hub 不可达）时，按 `deploy/scripts/catalog-dev.sh` 里的
> `rewrite_proxy` 把代理回环地址改写成 `host.docker.internal` 再作为 build arg 传入：
> `--build-arg HTTP_PROXY=... --build-arg HTTPS_PROXY=...`
> （Dockerfile 不需要显式声明这两个 ARG —— Docker 的预定义代理 ARG 自动生效。）

## 密钥

chart 为每个服务创建一份 `ExternalSecret`，生成同名 k8s Secret，Deployment 用
`secretKeyRef` 引用。每个服务需要三个通用键：

| Secret key | 远端路径（`secretManager.remoteKeys` 可改） | 用途 |
| --- | --- | --- |
| `db-password` | `{service}.db.password` | 运行账号密码 |
| `db-migration-password` | `postgres.migrator.password` | Flyway 迁移账号密码 |
| `internal-key` | `internal.key` | 服务间授权密钥（**所有**服务必需，无默认值） |

`channel` 额外需要 `channel-webhook-signing-key`（`channel.webhook.signing.key`）——
它同样没有默认值，缺失会让 channel 起不来。

用现成的 k8s Secret 而不用 ESO 时：

```yaml
secretManager:
  enabled: false
services:
  identity:
    secret:
      existingSecret: my-prebuilt-secret
```

## 跨服务地址

服务之间走集群内 DNS，形如
`http://starcon-backend-<服务名>.<ns>.svc.cluster.local:<端口>`。

**这些地址由 chart 逐个显式下发，不依赖代码里的默认值。** 原因是后端代码里这批变量的
默认值是 `http://127.0.0.1:80xx`（容器内那是自己，必然连错），个别默认值还指向了错误
的端口（例如 warehouse 的 `CHANNEL_URL` 默认值是 8082，那是 catalog 的端口）。

## 迁移

默认**关闭**独立迁移 Job —— 应用启动时自己跑 Flyway（`spring.flyway.url` 有配），
与本地开发行为一致，也是最省事的一种。

需要「先迁移、后发版」时打开：

```yaml
common:
  migration:
    enabled: true
```

打开后 chart 会创建 9 个 `pre-install` / `pre-upgrade` hook Job，按
`common.migration.order` 的顺序执行（identity 必须最先 —— 其它服务的权限码与租户关系
要落在 identity 建好的结构上），并在 Deployment 上追加 `SPRING_FLYWAY_ENABLED=false`，
避免多副本同时迁移。任一 Job 失败即中止整个发布。

> 迁移 Job 挂的是 `emptyDir` 而不是 PVC：pre-install hook 在普通资源创建**之前**执行，
> 那时 PVC 还不存在。迁移只需要「目录可写」（各存储实现的构造器里会
> `createDirectories(root)`，目录不存在会抛 `IllegalStateException`），临时目录足够 ——
> Flyway 写的是数据库，不写磁盘。

## 需要落盘的服务

catalog / channel / reporting / platform 有落盘目录，chart 给它们建 PVC：

```yaml
services:
  catalog:
    storage:
      media:
        size: 20Gi
        storageClass: fast-ssd
```

`storageClass` 留空则用集群默认 StorageClass。PVC 打了 `helm.sh/resource-policy: keep`，
删 Pod 不删数据。

## 探针

后端**没有** `spring-boot-starter-actuator`，因此**没有** `/actuator/health`。
chart 用 `tcpSocket` 探针，不要想当然地改成 `httpGet /actuator/health` —— 那个路径不存在。

## 其它开关

| 值 | 默认 | 说明 |
| --- | --- | --- |
| `common.autoscaling.enabled` | false | 需要集群有 metrics-server |
| `common.networkPolicy.enabled` | false | 打开前确认 CNI 支持 NetworkPolicy |
| `common.pdb.enabled` | true | `minAvailable: 1`；单副本场景请调成 0，否则节点永远排空不掉 |
| `services.channel.ingress.enabled` | false | 渠道 OAuth 回跳需要 channel 对外可达 |

渠道 OAuth：平台的 `redirect_uri` 只能指向后端，而 Amazon / Shopify 回跳时必须能访问到
它。生产环境要给 channel 开一个 Ingress，并把 `services.channel.oauth.publicBaseUrl`
填成同一个地址（留空则用集群内地址，只适合联调）。
`CHANNEL_OAUTH_UI_RETURN_URL` 默认取 `web.publicUrl` + `/channels`。

## 校验

```sh
helm lint ./infra/helm/starcon-backend
helm template starcon-backend ./infra/helm/starcon-backend \
  -n starcon --set postgres.host=pg.internal
```

## 排错

| 现象 | 多半是 |
| --- | --- |
| Pod 卡在 `ContainerCreating`，事件里写 `secret "..." not found` | ESO 没同步出 Secret，或 SecretStore 不存在 |
| Pod `CreateContainerConfigError` | 同一个 Secret 缺 key（看 `kubectl describe pod` 里的具体 key 名） |
| 日志里连库超时 | `postgres.host` / 库名 / 角色对不上，或 RLS 策略与运行角色不匹配 |
| channel 启动就退出 | `CHANNEL_WEBHOOK_SIGNING_KEY` 缺失（32 字节 base64） |
| 各服务之间调用 503 | `IDENTITY_URL` 指向的 Service 没有就绪（看 identity 的 Pod） |
