import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, type Plugin } from 'vite';
import { resolve } from 'node:path';


export default defineConfig({
    plugins: [
        injectGlobalCSS(`@import "/src/app.css" reference;`),
        tailwindcss(),
        sveltekit(),
    ],
})


function injectGlobalCSS(cssToInject: string): Plugin {
    return {
        name: 'inject-global-css',
        enforce: 'pre',
        transform(code, id) {
            if (!id.endsWith('.svelte') || code.includes(cssToInject)) return null;
            return code.replace(
                /<style(\s[^>]*)?>/g,
                `<style$1>\n${cssToInject}\n`
            )
        }
    }
}