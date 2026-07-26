export function AddressSearch() {
  {/* dotted-line 유틸 클래스는 globals.css에 정의 필요 */}
  return (
    <div className="bg-surface text-on-surface antialiased min-h-screen flex flex-col">
      {/* TopAppBar */}
      <header className="bg-surface w-full top-0 sticky flex items-center px-container-margin h-[56px] z-50">
        <button
          aria-label="Back"
          className="flex items-center justify-center w-10 h-10 -ml-2 text-primary hover:bg-surface-container active:scale-95 duration-200 transition-colors rounded-full"
        >
          <span className="material-symbols-outlined">arrow_back</span>
        </button>
        <h1 className="ml-2 font-headline-sm text-headline-sm text-on-surface flex-1 text-center font-bold mr-10">
          주소 검색
        </h1>
      </header>
      {/* Main Content Canvas */}
      <main className="flex-1 flex flex-col w-full max-w-md mx-auto relative pb-20">
        {/* Search Input Section */}
        <section className="bg-surface-container-lowest px-container-margin py-md shadow-sm z-10 relative">
          <div className="relative flex flex-col gap-3">
            {/* Location Connector Line */}
            <div className="absolute left-5 top-8 bottom-8 w-px dotted-line z-0"></div>
            {/* Starting Point Input */}
            <div className="relative z-10 flex items-center bg-surface-container-low border border-surface-container rounded-xl h-[52px] focus-within:border-primary-container focus-within:bg-surface-container-lowest transition-colors">
              <div className="w-12 h-full flex items-center justify-center">
                <div className="w-2.5 h-2.5 rounded-full bg-primary-container border-2 border-on-surface"></div>
              </div>
              <input
                className="flex-1 h-full bg-transparent border-none text-body-lg font-body-lg text-on-surface focus:ring-0 placeholder:text-on-surface-variant pr-4"
                placeholder="어디서 보낼까요?"
                type="text"
                defaultValue="현재 위치 (서울시 강남구)"
              />
              <button className="w-10 h-full flex items-center justify-center text-on-surface-variant hover:text-on-surface">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>
            {/* Destination Input */}
            <div className="relative z-10 flex items-center bg-surface-container-lowest border border-primary-container rounded-xl h-[52px] shadow-[0_0_0_2px_rgba(254,229,0,0.2)]">
              <div className="w-12 h-full flex items-center justify-center text-error">
                <span
                  className="material-symbols-outlined"
                  style={{ fontVariationSettings: "'FILL' 1" }}
                >
                  location_on
                </span>
              </div>
              <input
                autoFocus
                className="flex-1 h-full bg-transparent border-none text-body-lg font-body-lg text-on-surface focus:ring-0 placeholder:text-on-surface-variant/60 pr-4"
                placeholder="어디로 보낼까요?"
                type="text"
              />
            </div>
          </div>
          {/* Map Select Button */}
          <button className="mt-4 w-full flex items-center justify-center gap-2 h-10 rounded-lg text-tertiary bg-tertiary-fixed/30 hover:bg-tertiary-fixed/50 transition-colors font-label-lg text-label-lg">
            <span className="material-symbols-outlined text-[18px]">map</span>
            지도에서 위치 지정
          </button>
        </section>
        {/* Recent Addresses Section */}
        <section className="flex-1 bg-surface mt-sm">
          <div className="px-container-margin py-lg flex items-center justify-between">
            <h2 className="font-headline-sm text-headline-sm text-on-surface font-bold">최근 주소</h2>
            <button className="font-label-md text-label-md text-on-surface-variant hover:text-on-surface transition-colors">
              전체 삭제
            </button>
          </div>
          <ul className="flex flex-col">
            {/* Recent Item 1 */}
            <li className="flex items-center py-4 px-container-margin bg-surface-container-lowest hover:bg-surface-container-low transition-colors border-b border-surface-container">
              <div className="w-10 h-10 rounded-full bg-surface-container-low flex items-center justify-center text-on-surface-variant mr-4 shrink-0">
                <span className="material-symbols-outlined">schedule</span>
              </div>
              <div className="flex-1 min-w-0 pr-2 cursor-pointer">
                <p className="font-label-lg text-label-lg text-on-surface truncate">서울 강남구 테헤란로 123</p>
                <p className="font-body-md text-body-md text-on-surface-variant truncate mt-0.5">삼성빌딩 5층 (우리회사)</p>
              </div>
              <button
                aria-label="Delete"
                className="p-2 text-on-surface-variant hover:text-error transition-colors shrink-0"
              >
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </li>
            {/* Recent Item 2 */}
            <li className="flex items-center py-4 px-container-margin bg-surface-container-lowest hover:bg-surface-container-low transition-colors border-b border-surface-container">
              <div className="w-10 h-10 rounded-full bg-surface-container-low flex items-center justify-center text-on-surface-variant mr-4 shrink-0">
                <span className="material-symbols-outlined">schedule</span>
              </div>
              <div className="flex-1 min-w-0 pr-2 cursor-pointer">
                <p className="font-label-lg text-label-lg text-on-surface truncate">경기 성남시 분당구 판교역로 146</p>
                <p className="font-body-md text-body-md text-on-surface-variant truncate mt-0.5">판교역 1번 출구 앞</p>
              </div>
              <button
                aria-label="Delete"
                className="p-2 text-on-surface-variant hover:text-error transition-colors shrink-0"
              >
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </li>
            {/* Recent Item 3 */}
            <li className="flex items-center py-4 px-container-margin bg-surface-container-lowest hover:bg-surface-container-low transition-colors border-b border-surface-container">
              <div className="w-10 h-10 rounded-full bg-surface-container-low flex items-center justify-center text-on-surface-variant mr-4 shrink-0">
                <span className="material-symbols-outlined">schedule</span>
              </div>
              <div className="flex-1 min-w-0 pr-2 cursor-pointer">
                <p className="font-label-lg text-label-lg text-on-surface truncate">서울 용산구 한강대로 405</p>
                <p className="font-body-md text-body-md text-on-surface-variant truncate mt-0.5">서울역 KTX 매표소 앞</p>
              </div>
              <button
                aria-label="Delete"
                className="p-2 text-on-surface-variant hover:text-error transition-colors shrink-0"
              >
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </li>
          </ul>
        </section>
      </main>
    </div>
  )
}
