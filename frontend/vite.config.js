import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Minimal Vite config: just the Vue plugin so .vue single-file components
// compile. Default dev port is 5173, which matches the backend's CORS allow-list.
export default defineConfig({
  plugins: [vue()]
})
