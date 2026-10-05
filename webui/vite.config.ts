import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import Icons from 'unplugin-icons/vite';
import { defineConfig } from 'vite';

// `pnpm dev` proxies the API to a running app. With an emulator:
//   adb forward tcp:8765 tcp:8765
// or point at a TV on the LAN: ANSTELLE_DEVICE=http://192.168.1.20:8765 pnpm dev
const device = process.env.ANSTELLE_DEVICE ?? 'http://127.0.0.1:8765';

export default defineConfig({
	plugins: [
		// SvelteKit 3 takes its configuration here instead of svelte.config.js.
		sveltekit({
			// Android's asset packager skips directories that start with "_", so the
			// default "_app" output folder would never reach the APK.
			appDir: 'app',
			// Single-page app served by the TV from APK assets. Unknown paths fall back
			// to index.html, which the embedded server also does.
			adapter: adapter({
				pages: 'build',
				assets: 'build',
				fallback: 'index.html',
				strict: true
			})
		}),
		Icons({ compiler: 'svelte' })
	],
	server: {
		proxy: {
			'/api': { target: device, changeOrigin: false },
			'/ws': { target: device.replace(/^http/, 'ws'), ws: true }
		}
	},
	build: {
		target: 'es2020'
	}
});
