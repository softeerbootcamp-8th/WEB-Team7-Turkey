import type { Config } from 'tailwindcss'

/**
 * Turkey 고객/라이더 화면 공통 디자인 토큰.
 * Stitch 디자인 시안(Efficient Motion System)의 색상·타이포·간격 토큰을 통합한다.
 * 시안 HTML 안에 인라인으로 들어 있던 tailwind.config 를 프로젝트 레벨로 승격한 것.
 */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        primary: '#6a5f00',
        'on-primary': '#ffffff',
        'primary-container': '#fee500',
        'on-primary-container': '#716600',
        'primary-fixed': '#fde400',
        'primary-fixed-dim': '#dec800',
        'on-primary-fixed': '#201c00',
        'on-primary-fixed-variant': '#504700',
        'inverse-primary': '#dec800',
        'surface-tint': '#6a5f00',

        secondary: '#5f5e5e',
        'on-secondary': '#ffffff',
        'secondary-container': '#e2dfde',
        'on-secondary-container': '#636262',
        'secondary-fixed': '#e5e2e1',
        'secondary-fixed-dim': '#c8c6c5',
        'on-secondary-fixed': '#1c1b1b',
        'on-secondary-fixed-variant': '#474746',

        tertiary: '#005ac1',
        'on-tertiary': '#ffffff',
        'tertiary-container': '#d9e3ff',
        'on-tertiary-container': '#0061ce',
        'tertiary-fixed': '#d8e2ff',
        'tertiary-fixed-dim': '#adc6ff',
        'on-tertiary-fixed': '#001a41',
        'on-tertiary-fixed-variant': '#004494',

        error: '#ba1a1a',
        'on-error': '#ffffff',
        'error-container': '#ffdad6',
        'on-error-container': '#93000a',

        background: '#f9f9f9',
        'on-background': '#1a1c1c',
        surface: '#f9f9f9',
        'on-surface': '#1a1c1c',
        'on-surface-variant': '#4b4732',
        'surface-dim': '#dadada',
        'surface-bright': '#f9f9f9',
        'surface-variant': '#e2e2e2',
        'surface-container-lowest': '#ffffff',
        'surface-container-low': '#f3f3f3',
        'surface-container': '#eeeeee',
        'surface-container-high': '#e8e8e8',
        'surface-container-highest': '#e2e2e2',
        'inverse-surface': '#2f3131',
        'inverse-on-surface': '#f1f1f1',

        outline: '#7c775f',
        'outline-variant': '#cdc7aa',
      },
      borderRadius: {
        DEFAULT: '0.25rem',
        lg: '0.5rem',
        xl: '0.75rem',
        full: '9999px',
      },
      spacing: {
        base: '4px',
        xs: '4px',
        sm: '8px',
        gutter: '12px',
        md: '16px',
        'container-margin': '20px',
        lg: '24px',
        xl: '32px',
      },
      fontFamily: {
        sans: ['Be Vietnam Pro', 'sans-serif'],
      },
      fontSize: {
        'label-sm': ['11px', { lineHeight: '14px', letterSpacing: '0.02em', fontWeight: '500' }],
        'label-md': ['12px', { lineHeight: '16px', fontWeight: '600' }],
        'label-lg': ['14px', { lineHeight: '20px', fontWeight: '600' }],
        'body-md': ['14px', { lineHeight: '20px', fontWeight: '400' }],
        'body-lg': ['16px', { lineHeight: '24px', fontWeight: '400' }],
        'headline-sm': ['18px', { lineHeight: '26px', fontWeight: '600' }],
        'headline-md': ['20px', { lineHeight: '28px', fontWeight: '700' }],
        'headline-lg': ['24px', { lineHeight: '32px', fontWeight: '700' }],
      },
    },
  },
  plugins: [],
} satisfies Config
