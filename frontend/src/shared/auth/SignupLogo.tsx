import signupLogo from '@/assets/image.png'

export function SignupLogo() {
  return (
    <div className="relative h-48 w-44" role="img" aria-label="TURKEY">
      <img className="mx-auto h-36 w-36 object-contain" src={signupLogo} alt="" aria-hidden="true" />
      <svg
        className="absolute inset-x-0 -bottom-1 h-20 w-full text-[#b59b00]"
        viewBox="0 0 176 80"
        aria-hidden="true"
      >
        <defs>
          <path id="signup-logo-arc" d="M 18 16 Q 88 68 158 16" />
        </defs>
        <text fill="currentColor" fontSize="20" fontWeight="800" letterSpacing="3.5">
          <textPath href="#signup-logo-arc" startOffset="50%" textAnchor="middle">
            TURKEY
          </textPath>
        </text>
      </svg>
    </div>
  )
}
