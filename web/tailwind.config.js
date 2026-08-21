/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: { ink: '#172033', brand: { 50: '#eff4ff', 100: '#dce7ff', 300: '#8eaeff', 400: '#5b8cff', 500: '#315bea', 600: '#2447c6' }, mint: '#24b47e', coral: '#ef7658' },
      fontFamily: { display: ['"Plus Jakarta Sans"', 'sans-serif'], body: ['"DM Sans"', 'sans-serif'] },
      boxShadow: { soft: '0 18px 50px rgba(27, 42, 78, 0.11)' },
    },
  },
  plugins: [],
}

