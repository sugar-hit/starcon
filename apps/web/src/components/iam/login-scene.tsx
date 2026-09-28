import { Logo } from '@starcon/ui';
import desktop from '../../assets/login/harbor-desktop.webp';
import tablet from '../../assets/login/harbor-tablet.webp';
import mobile from '../../assets/login/harbor-mobile.webp';

/** Local, content-hashed WebP variants: no remote image host or runtime image conversion. */
export function LoginScene({ children }: { children: React.ReactNode }) {
  return (
    <main className="login-scene relative isolate h-dvh overflow-y-auto overscroll-contain bg-[#091e32] text-white">
      <div
        className="pointer-events-none absolute inset-0 -z-10 overflow-hidden"
        aria-hidden="true"
      >
        <picture>
          <source media="(max-width: 767px)" srcSet={mobile.src} />
          {/* Native picture selects the precompressed asset without a runtime optimizer request. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={desktop.src}
            srcSet={`${tablet.src} 1024w, ${desktop.src} 1600w`}
            sizes="100vw"
            width={1600}
            height={900}
            alt=""
            fetchPriority="high"
            loading="eager"
            decoding="async"
            className="h-full w-full object-cover object-center"
          />
        </picture>
        <div className="absolute inset-0 bg-[linear-gradient(90deg,rgba(4,18,33,0.72)_0%,rgba(4,18,33,0.36)_55%,rgba(4,18,33,0.16)_100%)]" />
        <div className="absolute inset-0 bg-gradient-to-t from-[#061524]/80 via-transparent to-[#061524]/20" />
      </div>
      <div className="relative mx-auto flex min-h-full w-full max-w-[1800px] flex-col px-6 py-7 sm:px-10 sm:py-10 lg:px-16 xl:px-24">
        <header className="flex shrink-0 items-center justify-between gap-4">
          <Logo tone="dark" size="md" />
          <span className="hidden text-xs tracking-[0.16em] text-white/70 sm:block">
            跨境业务 · 协同有序
          </span>
        </header>
        <div className="grid flex-1 items-center gap-9 py-10 md:grid-cols-[minmax(0,1fr)_minmax(320px,1fr)] md:gap-10 md:py-14 lg:grid-cols-[minmax(0,1fr)_minmax(320px,420px)] lg:gap-20 xl:grid-cols-[minmax(0,1fr)_440px]">
          <section aria-label="品牌宣言" className="max-w-2xl">
            <div className="mb-5 flex items-center gap-3 text-[11px] font-medium tracking-[0.22em] text-white/75 md:mb-8">
              <span className="h-px w-8 bg-white/60" /> BUILT FOR GLOBAL COMMERCE
            </div>
            <h2 className="text-[34px] font-semibold leading-[1.35] tracking-tight sm:text-[42px] lg:text-[56px] xl:text-[64px]">
              让跨境生意，
              <br />
              <span className="text-[#d3e7f7]">有序抵达全球。</span>
            </h2>
            <p className="mt-5 max-w-md text-sm leading-7 text-white/80 md:mt-7 md:text-base md:leading-8">
              从商品、订单到库存与履约，
              <br className="hidden sm:block" />
              连接每一个环节，让团队专注下一程增长。
            </p>
            <div className="mt-10 hidden items-center gap-6 text-xs text-white/75 md:flex">
              <span>多平台协同</span>
              <span className="h-3 w-px bg-white/30" />
              <span>多租户隔离</span>
              <span className="h-3 w-px bg-white/30" />
              <span>全球业务视野</span>
            </div>
          </section>
          <section
            aria-label="登录表单"
            className="text-foreground w-full rounded-2xl border border-white/60 bg-[#fcfcfd] p-7 shadow-2xl sm:p-9 lg:p-10"
          >
            {children}
          </section>
        </div>
        <footer className="flex shrink-0 flex-wrap items-center justify-between gap-3 text-[11px] leading-5 text-white/65">
          <p>© {new Date().getFullYear()} 湃宁科技（深圳）有限责任公司</p>
          <p className="tracking-[0.12em]">PIONEERING INNOVATION.</p>
        </footer>
      </div>
    </main>
  );
}
