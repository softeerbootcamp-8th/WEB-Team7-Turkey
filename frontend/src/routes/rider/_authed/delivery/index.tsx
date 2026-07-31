import { createFileRoute } from '@tanstack/react-router'
import { useState } from 'react'
import { GotoPickupStage } from './-components/GotoPickupStage'
import { BeforePickupStage } from './-components/BeforePickupStage'
import { WhilePickupStage } from './-components/WhilePickupStage'
import { AfterPickupStage } from './-components/AfterPickupStage'
import { CompleteDeliveryStage } from './-components/CompleteDeliveryStage'

export const Route = createFileRoute('/rider/_authed/delivery/')({
  component: RiderDelivery,
})

type DeliveryStage =
  | 'goto_pickup'
  | 'before_pickup'
  | 'while_pickup'
  | 'after_pickup'
  | 'complete'

const STAGES: { key: DeliveryStage; label: string }[] = [
  { key: 'goto_pickup', label: '픽업지로 이동' },
  { key: 'before_pickup', label: '픽업 전(도착)' },
  { key: 'while_pickup', label: '픽업 중' },
  { key: 'after_pickup', label: '배송 중' },
  { key: 'complete', label: '배송 완료' },
]

function renderStage(stage: DeliveryStage) {
  switch (stage) {
    case 'goto_pickup':
      return <GotoPickupStage />
    case 'before_pickup':
      return <BeforePickupStage />
    case 'while_pickup':
      return <WhilePickupStage />
    case 'after_pickup':
      return <AfterPickupStage />
    case 'complete':
      return <CompleteDeliveryStage />
  }
}

function RiderDelivery() {
  const [stage, setStage] = useState<DeliveryStage>('goto_pickup')

  return (
    <>
      {/* TODO: 실제 배송 상태(delivery status)와 연동 — 현재는 시안 미리보기용 로컬 상태 */}
      {/* 시안 미리보기용 스테이지 전환기 (프레젠테이션 스캐폴딩, 비즈니스 로직 아님) */}
      <label className="fixed top-2 right-2 z-[100] flex items-center gap-1 rounded-full bg-black/70 px-3 py-1.5 text-xs font-medium text-white shadow-md">
        <span className="sr-only">미리보기 단계 선택</span>
        <select
          aria-label="미리보기 단계 선택"
          value={stage}
          onChange={(e) => setStage(e.target.value as DeliveryStage)}
          className="bg-transparent text-white outline-none"
        >
          {STAGES.map((s) => (
            <option key={s.key} value={s.key} className="text-black">
              {s.label}
            </option>
          ))}
        </select>
      </label>
      {renderStage(stage)}
    </>
  )
}
