import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, type Plugin } from 'vite';
import path, { resolve } from 'node:path';
import MagicString from 'magic-string'


export default defineConfig({
    plugins: [
        injectGlobalCSS(`@import "/src/app.css" reference;`),
        tailwindcss(),
        sveltekit(),
    ],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, './src'),
        }
    },
    css: {
        devSourcemap: true
    },
})


// function injectGlobalCSS(cssToInject: string): Plugin {
//     return {
//         name: 'inject-global-css',
//         enforce: 'pre',
//         transform(code, id) {
//             if (!id.endsWith('.svelte') || code.includes(cssToInject)) return null;
//             return code.replace(
//                 /<style(\s[^>]*)?>/g,
//                 `<style$1>\n${cssToInject}\n`
//             )
//         }
//     }
// }

function injectGlobalCSS(cssToInject: string): Plugin {
    return {
        name: 'inject-global-css',
        enforce: 'pre',
        transform(code, id) {
            if (!id.endsWith('.svelte') || code.includes(cssToInject)) return null
            
            const s = new MagicString(code)
            const regex = /<style(\s[^>]*)?>/g
            let match
            
            while ((match = regex.exec(code)) !== null) {
                const end = match.index + match[0].length
                s.appendLeft(end, `\n${cssToInject}\n`)
            }
            
            return {
                code: s.toString(),
                map: s.generateMap({ hires: true })
            }
        }
    }
}