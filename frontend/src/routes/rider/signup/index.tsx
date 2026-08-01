import { createFileRoute, Link } from '@tanstack/react-router'

export const Route = createFileRoute('/rider/signup/')({
  component: RiderSignup,
})

function RiderSignup() {
  return (
    <div className="max-w-md mx-auto min-h-screen bg-surface-container-lowest relative flex flex-col shadow-sm">
      {/* TopAppBar */}
      <header className="bg-surface dark:bg-surface-container-lowest text-on-surface dark:text-on-surface docked full-width top-0 border-b border-surface-container dark:border-outline-variant flat no shadows flex justify-between items-center w-full px-container-margin h-14 sticky z-20">
        <Link to="/rider/login" className="text-on-surface-variant dark:text-on-secondary-container hover:bg-surface-container-low dark:hover:bg-surface-variant rounded-full w-10 h-10 flex items-center justify-center transition-colors" aria-label="Go back">
          <span className="material-symbols-outlined">arrow_back</span>
        </Link>
        <h1 className="text-headline-sm font-headline-sm font-bold text-on-surface flex-1 text-center">라이더 회원가입</h1>
        <Link to="/" className="text-on-surface-variant dark:text-on-secondary-container hover:bg-surface-container-low dark:hover:bg-surface-variant rounded-lg px-2 h-10 flex items-center justify-center transition-colors">
          <span className="text-label-lg font-label-lg">취소</span>
        </Link>
      </header>
      {/* Main Content Canvas */}
      <main aria-label="라이더 회원가입" className="flex-1 px-container-margin pt-lg pb-32 flex flex-col gap-xl">
        {/* Section: Login Info */}
        <section className="flex flex-col gap-lg">
          <h2 className="text-headline-md font-headline-md text-on-surface">로그인 정보</h2>
          {/* ID Input */}
          <div className="flex flex-col gap-2">
            <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="userId">아이디</label>
            <div className="flex gap-2">
              <input className="flex-1 h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim" id="userId" placeholder="아이디 입력" type="text" />
              <button className="h-[52px] px-4 bg-surface-container border border-surface-container text-on-surface font-label-lg text-label-lg rounded-xl hover:bg-surface-variant transition-colors whitespace-nowrap" type="button">
                중복 확인
              </button>
            </div>
          </div>
          {/* Password Input */}
          <div className="flex flex-col gap-2">
            <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="password">비밀번호</label>
            <div className="flex flex-col gap-2">
              <input className="w-full h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim" id="password" placeholder="비밀번호 (영문, 숫자, 특수문자 조합)" type="password" />
              <input className="w-full h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim" id="passwordConfirm" placeholder="비밀번호 확인" type="password" />
            </div>
          </div>
        </section>
        {/* Divider */}
        <div className="w-full h-px bg-surface-container"></div>
        {/* Section: Personal Info */}
        <section className="flex flex-col gap-lg">
          <h2 className="text-headline-md font-headline-md text-on-surface">개인 정보</h2>
          {/* Name Input */}
          <div className="flex flex-col gap-2">
            <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="userName">이름</label>
            <input className="w-full h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim" id="userName" placeholder="이름 입력" type="text" />
          </div>
          {/* Phone Input */}
          <div className="flex flex-col gap-2">
            <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="userPhone">휴대전화 번호</label>
            <div className="flex gap-2">
              <input className="flex-1 h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim" id="userPhone" placeholder="'-' 없이 숫자만 입력" type="tel" />
              <button className="h-[52px] px-4 bg-surface-container border border-surface-container text-on-surface font-label-lg text-label-lg rounded-xl hover:bg-surface-variant transition-colors whitespace-nowrap" type="button">
                인증번호 전송
              </button>
            </div>
          </div>
        </section>
        {/* Divider */}
        <div className="w-full h-px bg-surface-container"></div>
        {/* Section: Delivery Vehicle (라이더 전용) */}
        {/* TODO: 운송수단 종류·차량번호는 백엔드 rider 스펙 확정 후 필드/검증 연결 (현재 프레젠테이션) */}
        <section className="flex flex-col gap-lg">
          <h2 className="text-headline-md font-headline-md text-on-surface">운송수단 정보</h2>
          {/* Vehicle Type */}
          <div className="flex flex-col gap-2">
            <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="vehicleType">운송수단</label>
            <select className="w-full h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors" id="vehicleType" defaultValue="">
              <option value="" disabled>운송수단 선택</option>
              <option value="WALK">도보</option>
              <option value="BICYCLE">자전거</option>
              <option value="MOTORCYCLE">오토바이</option>
              <option value="CAR">자동차</option>
            </select>
          </div>
          {/* Vehicle Number */}
          <div className="flex flex-col gap-2">
            <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="vehicleNumber">차량 번호</label>
            <input className="w-full h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim" id="vehicleNumber" placeholder="예: 12가 3456 (도보/자전거는 미입력)" type="text" />
          </div>
        </section>
        {/* Divider */}
        <div className="w-full h-px bg-surface-container"></div>
        {/* Section: Terms */}
        <section className="flex flex-col gap-4 bg-surface-bright p-5 rounded-xl border border-surface-container">
          {/* Agree All */}
          {/* TODO: 약관 전체동의 토글 로직 연결 */}
          <label className="flex items-center gap-3 cursor-pointer">
            <input className="k-checkbox w-6 h-6 rounded-full border-surface-container text-primary-container focus:ring-primary-container transition-colors cursor-pointer" id="agreeAll" type="checkbox" defaultChecked={false} />
            <span className="text-body-lg font-headline-sm text-on-surface font-bold">약관 전체동의</span>
          </label>
          <div className="w-full h-px bg-surface-container my-1"></div>
          {/* Individual Terms */}
          <div className="flex flex-col gap-4 pl-1">
            <label className="flex items-center justify-between cursor-pointer group">
              <div className="flex items-center gap-3">
                <input className="k-checkbox term-check w-5 h-5 rounded border-surface-container text-primary-container focus:ring-primary-container transition-colors cursor-pointer" type="checkbox" defaultChecked={false} />
                <span className="text-body-md font-body-md text-on-surface-variant">[필수] 서비스 이용약관 동의</span>
              </div>
              <span className="material-symbols-outlined text-surface-dim group-hover:text-on-surface-variant transition-colors" style={{ fontSize: '20px' }}>chevron_right</span>
            </label>
            <label className="flex items-center justify-between cursor-pointer group">
              <div className="flex items-center gap-3">
                <input className="k-checkbox term-check w-5 h-5 rounded border-surface-container text-primary-container focus:ring-primary-container transition-colors cursor-pointer" type="checkbox" defaultChecked={false} />
                <span className="text-body-md font-body-md text-on-surface-variant">[필수] 개인정보 수집 및 이용 동의</span>
              </div>
              <span className="material-symbols-outlined text-surface-dim group-hover:text-on-surface-variant transition-colors" style={{ fontSize: '20px' }}>chevron_right</span>
            </label>
            <label className="flex items-center justify-between cursor-pointer group">
              <div className="flex items-center gap-3">
                <input className="k-checkbox term-check w-5 h-5 rounded border-surface-container text-primary-container focus:ring-primary-container transition-colors cursor-pointer" type="checkbox" defaultChecked={false} />
                <span className="text-body-md font-body-md text-on-surface-variant">[선택] 마케팅 정보 수신 동의</span>
              </div>
              <span className="material-symbols-outlined text-surface-dim group-hover:text-on-surface-variant transition-colors" style={{ fontSize: '20px' }}>chevron_right</span>
            </label>
          </div>
        </section>
      </main>
      {/* Fixed Bottom Action */}
      <div className="fixed bottom-0 left-0 w-full max-w-md left-1/2 -translate-x-1/2 p-container-margin pb-8 bg-surface-container-lowest border-t border-surface-container z-20">
        <button className="w-full h-14 bg-primary-container text-[#191919] font-headline-md text-headline-sm rounded-xl flex items-center justify-center active:scale-[0.98] transition-transform shadow-sm" type="button">
          가입하기
        </button>
      </div>
    </div>
  )
}
