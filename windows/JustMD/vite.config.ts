import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    // `mygo dev` 的应用窗口加载该地址（mygo.config.ts 的 devUrl）。
    port: 5173,
    strictPort: true,
    // 开发窗口与打包产物目录不属于页面自身。
    watch: { ignored: ["**/.mygo/**", "**/build/**"] },
  },
});
