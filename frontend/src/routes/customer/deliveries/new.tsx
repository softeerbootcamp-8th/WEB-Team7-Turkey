import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/customer/deliveries/new')({
  component: NewDelivery,
})

function NewDelivery() {
  return (
    <div className="bg-surface text-gray-900 font-sans min-h-screen pb-32">
      {/* BEGIN: AppHeader */}
      <header className="sticky top-0 z-50 bg-white border-b border-gray-100 pb-4">
        <div className="flex items-center justify-between px-4 pt-4">
          <button className="p-2 -ml-2 text-gray-600">
            <svg className="w-6 h-6" fill="none" stroke="currentColor" strokeWidth="1.5" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M10.5 19.5L3 12m0 0l7.5-7.5M3 12h18" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className="flex bg-gray-100 rounded-full p-1" />
        </div>
      </header>
      {/* END: AppHeader */}

      <main className="bg-white">
        {/* BEGIN: ItemInformationSection */}
        <section className="px-4 py-6 border-b-8 border-surface">
          <div className="flex justify-between items-center mb-4">
            <h2 className="text-xl font-bold">물품 정보</h2>
            <button className="px-3 py-1.5 border border-gray-200 rounded-full text-xs text-gray-500">물품 크기 및 유의사항</button>
          </div>
          <div className="border border-gray-200 rounded-xl overflow-hidden mb-4">
            {/* Selected Item */}
            <label className="flex items-center p-4 bg-blue-50/50 border-b border-blue-200 cursor-pointer">
              <div className="w-12 h-12 bg-gray-100 rounded-lg mr-4 flex items-center justify-center">
                {/* TODO: 초소형 아이콘 이미지 연결 */}
                <div className="w-10 h-10 bg-surface-container-high rounded" />
              </div>
              <div className="flex-1">
                <h3 className="font-bold text-gray-900">초소형 (예. 휴대폰)</h3>
                <p className="text-sm text-gray-500 mt-0.5">세 변의 합 70cm · 2kg 이하</p>
              </div>
            </label>
            {/* Unselected Items */}
            <label className="flex items-center p-4 border-b border-gray-100 cursor-pointer hover:bg-gray-50">
              <div className="w-12 h-12 bg-gray-100 rounded-lg mr-4 flex items-center justify-center">
                {/* TODO: 소형 아이콘 이미지 연결 */}
                <div className="w-10 h-10 bg-surface-container-high rounded" />
              </div>
              <div className="flex-1">
                <h3 className="font-medium text-gray-700">소형 (예. A4박스)</h3>
                <p className="text-sm text-gray-500 mt-0.5">세 변의 합 100cm · 5kg 이하</p>
              </div>
            </label>
            <label className="flex items-center p-4 border-b border-gray-100 cursor-pointer hover:bg-gray-50">
              <div className="w-12 h-12 bg-gray-100 rounded-lg mr-4 flex items-center justify-center">
                {/* TODO: 중형 아이콘 이미지 연결 */}
                <div className="w-10 h-10 bg-surface-container-high rounded" />
              </div>
              <div className="flex-1">
                <h3 className="font-medium text-gray-700">중형 (예. 라면박스)</h3>
                <p className="text-sm text-gray-500 mt-0.5">세 변의 합 140cm · 20kg 이하</p>
              </div>
            </label>
            <label className="flex items-center p-4 cursor-not-allowed opacity-50 bg-gray-50">
              <div className="w-12 h-12 bg-gray-200 rounded-lg mr-4 flex items-center justify-center">
                {/* TODO: 대형 아이콘 이미지 연결 */}
                <div className="w-10 h-10 bg-surface-container-high rounded grayscale" />
              </div>
              <div className="flex-1">
                <h3 className="font-medium text-gray-500">대형 (예. 가구,자전거 등)</h3>
                <p className="text-sm text-gray-400 mt-0.5">다마스 이상만 접수 가능해요</p>
              </div>
            </label>
          </div>
          {/* High Value Toggle */}
          <button className="w-full flex items-center justify-start p-4 border border-gray-200 rounded-xl mb-4 text-left">
            <svg className="w-5 h-5 text-gray-400 mr-2" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M19.5 8.25l-7.5 7.5-7.5-7.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span className="font-medium text-gray-800">물품 총 가격이 50만원 보다 비싸요</span>
          </button>
          {/* Information List */}
          <ul className="text-sm text-gray-500 space-y-1">
            <li className="flex items-start">
              <span className="mr-1">-</span>
              <span><a className="text-blue-600 underline" href="#">보내려는 물품 전체를 기준으로 선택해 주세요.</a></span>
            </li>
            <li className="flex items-start">
              <span className="mr-1">-</span>
              <span>취급불가 품목(약품류, 현금류, 생물, 신분증 등)은 배송이 불가하며 적재물 책임 보험이 적용되지 않습니다.</span>
            </li>
          </ul>
        </section>
        {/* END: ItemInformationSection */}

        {/* BEGIN: RiderInstructionsSection */}
        <section className="px-4 py-6 border-b-8 border-surface">
          <div className="flex items-center mb-3">
            <h2 className="text-lg font-bold">기사님 전달사항</h2>
            <span className="text-sm text-gray-400 ml-2">(선택)</span>
          </div>
          <div className="bg-gray-50 rounded-xl p-4 border border-gray-100 min-h-[80px]">
            <p className="text-gray-400 text-sm">기사님께 전달할 내용을 아래에서 선택하거나 직접 입력하세요.</p>
          </div>
        </section>
        {/* END: RiderInstructionsSection */}

        {/* BEGIN: AddressInformationSection */}
        <section className="px-4 py-6 border-b-8 border-surface">
          <div className="flex justify-between items-center mb-4">
            <h2 className="text-xl font-bold">주소 정보</h2>
            <div className="flex gap-2 relative" />
          </div>
          {/* 주소 검색은 -components/AddressSearch 화면으로 진입 */}
          <div className="relative">
            {/* Departure */}
            <div className="mb-6">
              <div className="flex items-start mb-2">
                <span className="text-sm font-medium text-gray-500 w-12 pt-0.5">출발</span>
                <div className="flex-1 cursor-pointer">
                  <div className="flex justify-between items-center">
                    <h3 className="font-bold text-gray-900 text-lg">역삼소나무공원</h3>
                    <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                      <path d="M8.25 4.5l7.5 7.5-7.5 7.5" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </div>
                  <p className="text-sm text-gray-500 mt-0.5">서울 강남구 역삼동 836-21</p>
                </div>
              </div>
              <div className="ml-12 space-y-2">
                <div className="flex relative">
                  <input className="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 text-sm focus:ring-0 focus:border-gray-300" readOnly type="text" defaultValue="민석" />
                  <button className="absolute right-2 top-2 bg-white border border-gray-200 px-3 py-1 rounded text-xs font-medium text-gray-600">연락처</button>
                </div>
                <input className="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 text-sm focus:ring-0 focus:border-gray-300" placeholder="상세주소 (예 : 층, 동/호수)" type="text" />
              </div>
            </div>
            {/* Swap Button */}
            <div className="absolute left-4 top-[140px] z-10">
              <button className="w-8 h-8 bg-white border border-gray-200 rounded-full flex items-center justify-center shadow-sm">
                <svg className="w-4 h-4 text-gray-600" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <path d="M3 7.5L7.5 3m0 0L12 7.5M7.5 3v13.5m13.5 0L16.5 21m0 0L12 16.5m4.5 4.5V7.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>
            </div>
            {/* Separator Line */}
            <div className="border-t border-gray-100 ml-12 mb-6" />
            {/* Arrival */}
            <div>
              <div className="flex items-start mb-2">
                <span className="text-sm font-medium text-gray-500 w-12 pt-0.5">도착</span>
                <div className="flex-1 cursor-pointer">
                  <div className="flex justify-between items-center">
                    <h3 className="font-bold text-gray-900 text-lg">강남역 2호선</h3>
                    <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                      <path d="M8.25 4.5l7.5 7.5-7.5 7.5" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </div>
                  <p className="text-sm text-gray-500 mt-0.5">서울 강남구 강남대로 지하 396</p>
                </div>
              </div>
              <div className="ml-12 space-y-2">
                <div className="flex relative">
                  <input className="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 text-sm focus:ring-0 focus:border-gray-300" placeholder="이름 *" type="text" />
                  <button className="absolute right-2 top-2 bg-white border border-gray-200 px-3 py-1 rounded text-xs font-medium text-gray-600">연락처</button>
                </div>
                <input className="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 text-sm focus:ring-0 focus:border-gray-300" placeholder="연락처 *" type="text" />
                <input className="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 text-sm focus:ring-0 focus:border-gray-300" placeholder="상세주소 (예 : 층, 동/호수)" type="text" />
              </div>
            </div>
          </div>
        </section>
        {/* END: AddressInformationSection */}

        {/* BEGIN: PaymentSummarySection */}
        <section className="px-4 py-6 bg-white">
          <div className="flex justify-between items-center mb-4">
            <span className="text-gray-500 text-sm font-medium">배송 요금</span>
            <span className="text-gray-900 font-medium">8,700원</span>
          </div>
          <div className="flex justify-between items-center">
            <span className="text-gray-900 font-bold">최종 결제 금액</span>
            <span className="text-black font-bold text-xl">8,700원</span>
          </div>
        </section>
        {/* END: PaymentSummarySection */}
      </main>

      {/* BEGIN: StickyBottomActionBar */}
      <div className="fixed bottom-0 left-0 right-0 bg-white rounded-t-2xl px-4 pt-4 pb-8 z-50 shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.05)]">
        <div className="flex justify-between items-center mb-4">
          <div className="text-sm text-gray-500">16:46까지 배송 · 초소형</div>
        </div>
        {/* TODO: 요금 API 소비 후 활성화 (요금 확정 시 disabled 해제) */}
        <button className="w-full bg-gray-100 text-gray-400 font-bold py-4 rounded-xl text-lg flex justify-center items-center" disabled>
          8,700원 결제하기
        </button>
        <div className="w-1/3 h-1 bg-gray-300 rounded-full mx-auto mt-6" />
      </div>
      {/* END: StickyBottomActionBar */}
    </div>
  )
}
