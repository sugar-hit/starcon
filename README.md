[**English**](README.md) | [简体中文](README.zh-CN.md)

<div align="center">

<img src="apps/web/public/logo-mark-brand.svg" alt="starcon logo" width="88" height="88">

# starcon

### Bring products, orders, and inventory together for cross-border commerce.

A SaaS ERP for multi-platform businesses · 9 Java microservices · PostgreSQL row-level security

[![License: PolyForm Noncommercial](docs/design/readme/license.svg)](LICENSE)
[![Java 21](docs/design/readme/java.svg)](backend/pom.xml)
[![Next.js](docs/design/readme/next.svg)](apps/web/package.json)
[![TypeScript](docs/design/readme/typescript.svg)](apps/web/tsconfig.json)

[Quick start](#quick-start) · [Highlights](#highlights) · [Project status](#project-status) · [Architecture](#architecture) · [License](#license)

</div>

---

## Why starcon

Cross-border commerce spans marketplace dashboards, product spreadsheets, warehouse systems, settlement reports, advertising consoles, and support tickets. Product identifiers differ between platforms, orders and inventory become difficult to reconcile, and growing teams need clear permissions and data boundaries.

**starcon aims to provide a shared business foundation for products, channels, orders, inventory, fulfillment, finance, marketing, and service**: consistent product records, traceable inventory movements, explicit authorization, and well-defined service ownership.

Target business scenarios include Amazon, Shopify, eBay, TikTok Shop, Temu, Lazada, Shopee, and Walmart. Platform OAuth adapters for these vendors are implemented in the channel service; live business API access (order ingestion, listing sync, advertising data) still has to be verified against acceptance records.

## Highlights

- **One product backbone for the whole chain.** SPU/SKU with variant axes, inherited attributes, and explicit overrides; brands, units, bundle BOMs, and product content sit next to listings, pricing, and purchase records.
- **Tenant isolation enforced in PostgreSQL.** Tenant context comes from authenticated membership. Business tables carry `tenant_id NOT NULL` with row-level security, and roles decide page operations and data scopes together. Parent-child tenant relationships do not automatically grant access.
- **Nine services, one fact table each.** identity, catalog, channel, trade, warehouse, crm, finance, reporting, and platform each own their database and Flyway migrations. Cross-domain collaboration goes through authorized APIs or events — no cross-service SQL, foreign keys, or shared views.
- **The capability blueprint is fully covered.** 18 business systems and 465 L4 modules, each recorded in the delivery ledger with its migration, API, page, and test evidence.
- **Finance and analytics are first-class, not an afterthought.** Receivables and payables, payments, vouchers, reconciliation, costing, deferred settlement, profit reports, custom dashboards, and SKU-level profit, with `BigDecimal` money over `NUMERIC` columns end to end.
- **Automation and AI live inside the platform.** Smart query, AI diagnosis and forecasting, automation rules with effect evaluation, an API gateway, event subscriptions, and external integrations.
- **Contracts connect backend and frontend.** Backend OpenAPI generates the TypeScript contracts. CI runs PostgreSQL integration tests per service, frontend unit tests, browser tests, and a blueprint/ledger consistency check.
- **Delivery evidence lives with the code.** A four-level blueprint, architecture decisions, the module ledger, and session acceptance records let reviewers trace requirements to implementation.

## Project status

> Active development. This overview reflects **main as reviewed on November 4, 2026**; it does not claim full product completion or production readiness.

The [delivery ledger](docs/product/capability-blueprint/module-progress.md) records **465 planned modules, all 465 marked delivered (100.0%)**. Modules are L4 capability units, and a module counts as delivered only when its migration, API, page, and tests are all in place — so this percentage measures blueprint coverage, not production maturity or live platform connectivity. `pnpm progress:check` keeps the ledger and the blueprint from drifting apart.

| Area                              | Volumes | Modules | Implementation on main                                                                                     | Next steps                                          |
| --------------------------------- | ------- | ------- | ---------------------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| Workbench and portal              | 01      | 23      | Role-aware workbench, to-do intake, metric cards, store overview, personal settings                        | Metric definitions and live refresh                 |
| Channels and shops                | 02      | 25      | Shop connections, platform OAuth adapters, listing mappings, sync tasks, write-back                        | Live platform API acceptance                        |
| Product master data               | 03      | 40      | SPU/SKU, attributes, variants, brands, units, BOMs, content, business partners                             | Product data governance                             |
| Listings and content              | 04      | 26      | Listing editor, category mappings, keywords, lifecycle, publish tasks, content write-back                  | Real listing publication and platform sync          |
| Pricing                           | 05      | 19      | Price strategies, price lists, exchange rates, batch maintenance, conflict detection                       | Live platform repricing                             |
| Purchase and supply               | 06      | 26      | Purchase orders, arrivals, demand planning, supplier qualification and ratings                             | Supplier settlement integration                     |
| Inventory and warehousing         | 07, 08  | 53      | Inventory ledger, reservations, receipts, putaway, transfers, stocktakes, picking, packing, handovers      | Multi-warehouse ownership and end-to-end acceptance |
| Orders, logistics, and aftersales | 09–11   | 76      | Order aggregation, changes and cancellation, routing, pending shipments, freight, customs, returns         | Platform order ingestion                            |
| Finance and profit                | 12, 13  | 55      | Receivables/payables, payments, vouchers, reconciliation, costing, settlement, profit and custom reports   | Deferred multi-currency settlement                  |
| Ads and growth                    | 14      | 22      | Campaigns, budgets, bidding, attribution, promotions, influencer outreach, ad diagnostics                  | Platform advertising API                            |
| CRM and service                   | 15      | 24      | Customer profiles, lifecycle, membership levels, points, reviews, service tickets, auto-replies            | Omnichannel conversation intake                     |
| Compliance and risk               | 16      | 23      | Linkage and IP risk, buyer blacklists, platform violations and appeals, EPR/VAT and data retention         | Rule operations and external compliance services    |
| Organization and permissions      | 17      | 29      | Members, roles, page permissions, data scopes, departments, child tenants, approval rules                  | Production identity provider integration            |
| Integration and automation        | 18      | 24      | Smart query, AI diagnosis and forecast, automation rules, API gateway, event subscriptions, effect reports | Model and external system acceptance                |

A page, configuration record, or task endpoint does not establish a working external integration. Consult the [capability blueprint](docs/product/capability-blueprint/README.md), [delivery ledger](docs/product/capability-blueprint/module-progress.md), and [acceptance records](docs/sessions/2026-11/) for scope and evidence. Most detailed project documentation is currently in Chinese.

## Quick start

### Requirements

- **Node.js ≥ 22.12.0** and **pnpm 9**; the repository pins `pnpm@9.0.0`.
- Docker Desktop, or Docker Engine with Compose, for PostgreSQL and backend services.
- **JDK 21** only when building the backend directly on the host; container startup includes its Java toolchain.

### Run locally

```bash
git clone https://github.com/sugar-hit/starcon.git
cd starcon
nvm install
nvm use
corepack enable
pnpm install

# Build and start PostgreSQL and the nine backend services
pnpm dev:backend

# Start the frontend and keep this terminal running
pnpm --filter @starcon/web dev
```

Open [http://localhost:3000/products](http://localhost:3000/products). The first backend build downloads dependencies; allow the services to become ready before signing in.

First startup generates `deploy/.env.catalog.local`. Sign in as `local-admin` using the file's `LOCAL_ADMIN_PASSWORD` value. Keep this file private and out of version control. Bootstrap creates a development tenant and an Owner member; it does not populate demo products. See the [local development guide](docs/operations/catalog-local-development.md) for details.

| Service    | Local port | Responsibility                            |
| ---------- | ---------- | ----------------------------------------- |
| Web        | 3000       | Browser workspace                         |
| identity   | 8081       | Identity, organization, and authorization |
| catalog    | 8082       | Products and master data                  |
| channel    | 8083       | Channels, shops, and listings             |
| warehouse  | 8084       | Inventory and warehouse operations        |
| trade      | 8085       | Orders, logistics, and aftersales         |
| crm        | 8086       | Customers and service                     |
| finance    | 8087       | Accounting, settlement, and costing       |
| reporting  | 8088       | Dashboards, analytics, and exports        |
| platform   | 8089       | Automation, AI, and open integration      |
| PostgreSQL | 5433       | Separate databases for each service       |

Port bindings are defined in [`docker-compose.catalog.yml`](deploy/docker/docker-compose.catalog.yml).

### Suggested demonstration

1. **Start at the workbench:** open the dashboard for to-do intake, metric cards, and store overview.
2. **Build a product record:** create an SPU, define attributes and variant axes, add SKUs, and demonstrate inherited values and overrides.
3. **Show permission boundaries:** configure roles and members, test restricted visibility, and demonstrate independent parent and child tenant data boundaries.
4. **Trace the operational chain:** walk inventory reservations through warehouse picking, packing, and shipment handovers, then follow the order through aftersales.
5. **Close the financial loop:** inspect vouchers, reconciliation tasks, and settlement records, and read the profit reports built on them.
6. **Try the automation layer:** run a smart query, an AI diagnosis, and an automation rule with its effect report.

The warehouse, order, and finance steps require upstream records and permissions. Page prerequisites and limitations are documented in the acceptance records linked from the [delivery ledger](docs/product/capability-blueprint/module-progress.md).

## Architecture

![starcon service architecture](docs/design/readme/architecture.svg)

The diagram shows current services and database ownership. Business services also verify identity and permissions through authorized interfaces; they do not access another service's database directly. See [ADR-0004](docs/decisions/0004-java-spring-boot-microservices.md) for service boundaries and [architecture overview](docs/architecture/overview.md) for the wider view.

| Layer        | Technology                                                      |
| ------------ | --------------------------------------------------------------- |
| Frontend     | Next.js 15, React 18, strict TypeScript, Tailwind CSS, Radix UI |
| Backend      | Java 21, Spring Boot, Maven Wrapper, jOOQ                       |
| Data         | PostgreSQL 16, Flyway SQL migrations, Row-Level Security        |
| Contracts    | SpringDoc OpenAPI, TypeScript generation                        |
| Verification | Vitest, Playwright, JUnit 5, AssertJ, Testcontainers PostgreSQL |
| Engineering  | pnpm workspaces, Turborepo, Docker Compose, GitHub Actions      |

Cloud deployment and event delivery evolve in stages. Planned infrastructure and messaging components are not presented as current runtime requirements.

## Repository layout

```text
starcon/
├── apps/
│   ├── web/                 # Next.js workspace (the implemented frontend)
│   └── admin/ api/ worker/ scheduler/ notification-gateway/   # scaffolding only
├── backend/
│   ├── services/            # identity / catalog / channel / trade / warehouse /
│   │                       # crm / finance / reporting / platform
│   └── libraries/           # Shared technical capabilities, including tenancy
├── packages/                # Shared frontend libraries: ui (design system) and
│                            # api-contracts (generated) carry the implementation;
│                            # tenancy / money / observability / i18n / ... are scaffolding
├── services/                # Heterogeneous services (OCR, data pipeline) — scaffolding
├── workers/                 # Standalone workers (marketplace sync, mail, reports) — scaffolding
├── db/schemas/              # Data design; executable Flyway migrations live in services
├── deploy/                  # Container images, Compose, local startup and test scripts
├── infra/                   # Terraform / Helm / Kubernetes / ArgoCD — scaffolding
├── tests/                   # Cross-module, contract, integration, load, browser tests
├── tools/                   # Internal CLI, contract codegen, ledger synchronization
└── docs/                    # Blueprint, decisions, design, compliance, acceptance records
```

## Development and verification

```bash
pnpm lint
pnpm typecheck
pnpm test                    # Frontend unit tests
pnpm build                   # Frontend and shared-package build
pnpm test:backend            # Java 21 + PostgreSQL integration tests in Docker
pnpm test:e2e                # Prepare services required by the browser test configuration
pnpm contracts:generate      # Regenerate TypeScript contracts from backend OpenAPI
pnpm db:migrate              # Apply each service's Flyway migrations
pnpm db:generate             # Generate jOOQ code from a migrated database
pnpm progress:check          # Check blueprint/ledger synchronization
```

With JDK 21 on the host, run `./mvnw verify` from `backend/`. Add new Flyway migrations for database changes. Generate frontend contracts from backend OpenAPI rather than editing generated files.

## Documentation

| Topic                              | Entry point                                                                                                                                   |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Product scope and roadmap          | [18-system capability blueprint](docs/product/capability-blueprint/README.md)                                                                 |
| Delivery status and evidence       | [Module ledger](docs/product/capability-blueprint/module-progress.md)                                                                         |
| Service boundaries and decisions   | [Architecture](docs/architecture/overview.md) · [ADRs](docs/decisions/README.md)                                                              |
| Tenant isolation and authorization | [Multi-tenancy](docs/architecture/multi-tenancy.md) · [Tenant hierarchy and page RBAC](docs/decisions/0005-tenant-hierarchy-and-page-rbac.md) |
| Local development                  | [Development guide](docs/operations/catalog-local-development.md)                                                                             |
| Interface design                   | [Visual specification](docs/design/visual-spec.md)                                                                                            |
| Acceptance records                 | [Session archive](docs/sessions/README.md)                                                                                                    |
| Contributing                       | [Contribution guide](CONTRIBUTING.md) · [Agent instructions](AGENTS.md)                                                                       |
| Usage and commercial authorization | [Licensing guide](LICENSING.md) · [License text](LICENSE)                                                                                     |

## License

**Source is available for permitted noncommercial use, learning, research, modification, and sharing. Commercial use outside the license's permitted purposes requires separate prior written authorization from the relevant rights holders.**

starcon uses the [PolyForm Noncommercial License 1.0.0](LICENSE), SPDX identifier `PolyForm-Noncommercial-1.0.0`. Because commercial use is restricted, this is **source-available software**, not open source under the OSI definition.

Public access and fork permissions do not grant commercial rights for business operations, commercial SaaS, derivative product sales, or commercial delivery. Contact the maintainer through the [GitHub profile](https://github.com/sugar-hit), or submit a licensing inquiry without sensitive information in [Issues](https://github.com/sugar-hit/starcon/issues). An inquiry is not authorization.

The full rights, obligations, and express exceptions are governed by [`LICENSE`](LICENSE). See [`LICENSING.md`](LICENSING.md) for English and Chinese guidance, third-party materials, and the authorization process. Preserve the license and the Required Notices in [`NOTICE`](NOTICE) when distributing copies.
