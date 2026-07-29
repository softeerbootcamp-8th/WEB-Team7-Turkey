import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/customer/points/charge')({ component: PointsCharge })

function PointsCharge() {
  return (
    <>
      {/* TopAppBar */}
      <header className="w-full top-0 sticky bg-surface flex items-center px-container-margin h-14 z-40">
        <button className="text-primary hover:opacity-80 transition-opacity active:scale-95 transition-transform mr-4 focus:outline-none">
          <span className="material-symbols-outlined" style={{ fontVariationSettings: "'FILL' 0" }}>arrow_back</span>
        </button>
        <h1 className="font-headline-md text-headline-md font-bold text-on-surface">포인트 충전</h1>
      </header>

      <main className="px-container-margin py-md space-y-lg max-w-lg mx-auto">

        {/* Section 1: Current Balance */}
        <section className="bg-surface-container-lowest rounded-xl p-md border border-[#EEEEEE] flex justify-between items-center">
          <span className="font-body-lg text-secondary">보유 포인트</span>
          <span className="font-headline-lg text-on-surface">5,000 P</span>
        </section>

        {/* Section 2: Recharge Amount */}
        <section>
          <h2 className="font-headline-sm text-headline-sm mb-md text-on-surface">충전 금액</h2>
          <div className="grid grid-cols-2 gap-sm">
            <button className="h-12 bg-surface-container-lowest border border-[#EEEEEE] rounded-xl font-label-lg text-on-surface flex items-center justify-center hover:bg-surface-container-low transition-colors active:border-primary-container">10,000원</button>
            <button className="h-12 bg-primary-container border-primary-container rounded-xl font-label-lg text-on-surface flex items-center justify-center transition-colors">30,000원</button>
            <button className="h-12 bg-surface-container-lowest border border-[#EEEEEE] rounded-xl font-label-lg text-on-surface flex items-center justify-center hover:bg-surface-container-low transition-colors active:border-primary-container">50,000원</button>
            <button className="h-12 bg-surface-container-lowest border border-[#EEEEEE] rounded-xl font-label-lg text-on-surface flex items-center justify-center hover:bg-surface-container-low transition-colors active:border-primary-container">100,000원</button>
          </div>
        </section>

        {/* Section 3: Payment Method */}
        <section>
          <h2 className="font-headline-sm text-headline-sm mb-md text-on-surface">결제수단</h2>
          <div className="bg-surface-container-lowest rounded-xl border border-[#EEEEEE] overflow-hidden">

            {/* Credit Card Option */}
            <label className="flex items-center p-md border-b border-[#EEEEEE] cursor-pointer hover:bg-surface-container-low transition-colors">
              <input type="radio" name="payment_method" className="form-radio text-[#2D7FF9] focus:ring-[#2D7FF9] w-5 h-5 border-[#EEEEEE]" />
              <span className="ml-sm font-body-lg text-on-surface">신용/체크카드</span>
            </label>

            {/* Kakao Pay Option (Selected) */}
            <div className="p-md border-b border-[#EEEEEE] bg-surface-container-low/30">
              <label className="flex items-center cursor-pointer mb-md">
                <input type="radio" name="payment_method" defaultChecked className="form-radio text-[#2D7FF9] focus:ring-[#2D7FF9] w-5 h-5 border-[#EEEEEE]" />
                <span className="ml-sm font-body-lg text-on-surface font-semibold">카카오페이</span>
              </label>

              {/* Card Carousel Visual (Image from Prompt) */}
              <div className="mt-md flex justify-center">
                {/* TODO: 카카오페이 카드 이미지 연결 */}
                <div className="w-[240px] h-[140px] object-cover rounded-xl shadow-[0_4px_12px_rgba(0,0,0,0.08)] border border-[#EEEEEE]" />
              </div>
            </div>

            {/* Other Methods */}
            <label className="flex items-start p-md cursor-pointer hover:bg-surface-container-low transition-colors">
              <input type="radio" name="payment_method" className="form-radio text-[#2D7FF9] focus:ring-[#2D7FF9] w-5 h-5 border-[#EEEEEE] mt-0.5" />
              <div className="ml-sm">
                <span className="block font-body-lg text-on-surface">다른 결제수단</span>
                <span className="block font-label-sm text-secondary mt-xs">휴대폰, 직접결제</span>
              </div>
            </label>

          </div>
        </section>

        {/* Summary Section (From Image) */}
        <section className="space-y-sm">



        </section>
      </main>
      <div className="fixed bottom-0 left-0 right-0 p-md bg-surface border-t border-[#EEEEEE] z-50">
        <div className="max-w-lg mx-auto">
          <button className="w-full h-14 bg-primary-container text-on-surface font-headline-sm rounded-xl flex items-center justify-center active:scale-95 transition-transform shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
            30,000원 충전하기
          </button>
        </div>
      </div>
    </>
  )
}
