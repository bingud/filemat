import adapter from "@sveltejs/adapter-static";
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	// Consult https://svelte.dev/docs/kit/integrations for more information about preprocessors
	preprocess: vitePreprocess({
		postcss: true
	}),

	kit: {
		// See https://svelte.dev/docs/kit/adapters for more information about adapters.
		adapter: adapter({
            pages: "build",
            assets: "build",
            fallback: "/index.html",
            precompress: false,
            strict: true,
        }),
        alias: {
        },
        prerender: {}
	},
	compilerOptions: {
		warningFilter: (w) => {
			if (
                w.message.includes("event_directive_deprecated")
                || w.message.includes("css_unused_selector") 
                || w.message.includes("a11y_no_static_element_interactions")
            ) return false
			return true
		},
        experimental: {
            async: true
        }
	},
};

export default config;
