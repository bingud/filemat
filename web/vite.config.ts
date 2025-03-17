import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, type Plugin } from 'vite';
import { resolve } from 'node:path';


export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit(),
		SvelteTailwindApply(),
	],
	resolve: {
		alias: [
			{
				find: "@app-css",
				replacement: resolve(__dirname, 'src/app.css')
			}
		]
	}
});










async function SvelteTailwindApply(): Promise<Plugin> {
  return {
    name: "svelte-tailwind-apply",
    api: {
      sveltePreprocess: {
        style: async ({ content, filename }: { content: any, filename: any }) => {
          if (filename.endsWith(".svelte")) {
            const newContent = `@import "@app-css" reference;\n${content}`;
            return { code: newContent };
          }
          return { code: content };
        },
      },
    },
  }
};