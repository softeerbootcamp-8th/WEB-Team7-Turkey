import { createFileRoute, Link } from '@tanstack/react-router'
import { Map, Settings, Truck } from 'lucide-react'

export const Route = createFileRoute('/customer/')({
  component: CustomerHome,
})

function CustomerHome() {
  return (
    <div className="customer-home">
      <header className="customer-home__header">
        <h1 className="customer-home__title">Quick</h1>
        <Link to="/account/settings" aria-label="설정" className="customer-home__icon-button">
          <Settings size={22} />
        </Link>
      </header>

      <main className="customer-home__main">
        <div className="customer-home__content">
          <div className="customer-home__greeting">
            <h2>반갑습니다!</h2>
            <p>어디로 보낼까요?</p>
          </div>

          {/* TODO: 배송 일러스트 이미지 자산 연결 */}
          <div className="customer-home__hero" aria-hidden="true">
            배송 일러스트
          </div>

          <div className="customer-home__actions">
            <Link to="/customer/deliveries/new" className="btn btn--primary">
              <Truck size={22} />
              <span>퀵 부르기</span>
            </Link>
            <Link to="/customer/deliveries" className="btn btn--secondary">
              <Map size={20} />
              <span>현재 배송 상태 보기</span>
            </Link>
          </div>
        </div>
      </main>
    </div>
  )
}
