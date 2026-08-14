import { useState, type ChangeEventHandler } from 'react'

interface PasswordVisibilityInputProps {
  className: string
  value: string
  onChange: ChangeEventHandler<HTMLInputElement>
  invalid: boolean
  describedBy?: string
}

export function PasswordVisibilityInput({
  className,
  value,
  onChange,
  invalid,
  describedBy,
}: PasswordVisibilityInputProps) {
  const [isVisible, setIsVisible] = useState(false)

  return (
    <div className="relative">
      <input
        className={`${className} pr-12`}
        id="password"
        name="password"
        autoComplete="new-password"
        value={value}
        onChange={onChange}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        placeholder="비밀번호 입력"
        type={isVisible ? 'text' : 'password'}
      />
      <button
        type="button"
        className="absolute right-1 top-1/2 flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container"
        onClick={() => setIsVisible((current) => !current)}
        aria-label={isVisible ? '비밀번호 숨기기' : '비밀번호 보기'}
        aria-pressed={isVisible}
      >
        <span className="material-symbols-outlined text-xl" aria-hidden="true">
          {isVisible ? 'visibility_off' : 'visibility'}
        </span>
      </button>
    </div>
  )
}
