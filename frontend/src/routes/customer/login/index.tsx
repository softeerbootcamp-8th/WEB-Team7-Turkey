import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/customer/login/')({
  component: CustomerLogin,
})

function CustomerLogin() {
  return (
    <>
      {/* TopAppBar */}
      <header className="bg-surface text-on-surface docked full-width top-0 border-b border-surface-container flat no shadows hover:bg-surface-container-low flex justify-between items-center w-full px-container-margin h-14 sticky z-10">
        <div className="flex items-center gap-xs">
          <button className="p-2 -ml-2 rounded-full hover:bg-surface-variant transition-colors" aria-label="Go back">
            <span className="material-symbols-outlined text-[24px]">arrow_back</span>
          </button>
          <h1 className="text-headline-sm-mobile font-headline-sm-mobile text-on-surface font-bold">로그인</h1>
        </div>
        <button className="text-label-lg-mobile font-label-lg-mobile text-on-surface-variant hover:text-on-surface transition-colors px-2 py-1">
          회원가입
        </button>
      </header>

      {/* Main Content Canvas */}
      <main className="flex-grow flex flex-col px-container-margin pt-xl pb-safe gap-lg">

        {/* Welcome Area */}
        <div className="flex flex-col items-center justify-center pt-lg pb-md gap-sm">
          <div className="w-16 h-16 bg-primary-container text-on-primary-container rounded-2xl flex items-center justify-center mb-2">
            <span className="material-symbols-outlined text-[32px] font-[500]" style={{ fontVariationSettings: "'FILL' 1" }}>local_shipping</span>
          </div>
          <h2 className="text-headline-lg-mobile font-headline-lg-mobile font-bold text-center">환영합니다!</h2>
          <p className="text-body-md font-body-md text-on-surface-variant text-center">빠르고 안전한 퀵 서비스를 경험해보세요.</p>
        </div>

        {/* Login Form */}
        <form className="flex flex-col gap-md w-full max-w-md mx-auto">

          {/* ID Input */}
          <div className="flex flex-col gap-xs">
            <label htmlFor="userId" className="text-label-md font-label-md text-on-surface-variant px-1">아이디</label>
            <div className="relative">
              <input type="text" id="userId" placeholder="아이디를 입력해주세요" className="w-full h-[52px] bg-surface-container-lowest border border-surface-container rounded-xl px-4 text-body-lg font-body-lg focus:outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-all" />
            </div>
          </div>

          {/* Password Input */}
          <div className="flex flex-col gap-xs">
            <label htmlFor="password" className="text-label-md font-label-md text-on-surface-variant px-1">비밀번호</label>
            <div className="relative">
              <input type="password" id="password" placeholder="비밀번호를 입력해주세요" className="w-full h-[52px] bg-surface-container-lowest border border-surface-container rounded-xl px-4 text-body-lg font-body-lg focus:outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-all" />
              <button type="button" className="absolute right-4 top-1/2 transform -translate-y-1/2 text-on-surface-variant hover:text-on-surface">
                <span className="material-symbols-outlined text-[20px]">visibility_off</span>
              </button>
            </div>
          </div>

          {/* Options Row */}
          <div className="flex items-center justify-between px-1 pt-xs">

            <div className="flex items-center gap-sm">
              <a className="text-body-md font-body-md text-tertiary hover:underline" href="#">아이디 찾기</a>
              <span className="w-[1px] h-3 bg-surface-container-high"></span>
              <a className="text-body-md font-body-md text-tertiary hover:underline" href="#">비밀번호 찾기</a>
            </div>
          </div>

          {/* Primary Action */}
          <button type="submit" className="w-full h-[52px] bg-primary-container text-[#191919] text-headline-sm-mobile font-headline-sm-mobile font-bold rounded-xl mt-sm flex items-center justify-center active:scale-[0.98] transition-transform">
            로그인
          </button>
        </form>

        {/* Divider */}
      </main>
    </>
  )
}
