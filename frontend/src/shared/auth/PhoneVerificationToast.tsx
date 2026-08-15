import { useEffect } from 'react'

interface PhoneVerificationToastProps {
  code: string
  onClose: () => void
}

export function PhoneVerificationToast({ code, onClose }: PhoneVerificationToastProps) {
  useEffect(() => {
    const timeoutId = window.setTimeout(onClose, 5000)

    return () => window.clearTimeout(timeoutId)
  }, [code])

  return (
    <div className="pointer-events-none fixed inset-x-0 top-[calc(env(safe-area-inset-top)+1rem)] z-50 flex justify-center px-4">
      <div
        className="pointer-events-auto flex w-full max-w-sm items-center gap-3 rounded-xl bg-inverse-surface px-4 py-3 text-inverse-on-surface shadow-lg"
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >
        <span className="material-symbols-outlined shrink-0" aria-hidden="true">sms</span>
        <div className="min-w-0 flex-1">
          <p className="text-label-md">문자 메시지가 도착했습니다.</p>
          <p className="text-body-md">인증번호는 <strong>{code}</strong>입니다.</p>
        </div>
        <button
          type="button"
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full hover:bg-white/10"
          onClick={onClose}
          aria-label="알림 닫기"
        >
          <span className="material-symbols-outlined text-xl" aria-hidden="true">close</span>
        </button>
      </div>
    </div>
  )
}
