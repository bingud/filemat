<script lang="ts">
	import '../app.css';
    import {dev} from "$app/environment"
    import { Toaster } from '@jill64/svelte-toast'
    import type { ToastOptions } from 'svelte-french-toast';


	let { children } = $props();

    const palette = {}
    const toastOptions: ToastOptions = {
        duration: 5000
    }
</script>


<Toaster {palette} {toastOptions} />

<div class="w-full min-h-0 h-full flex flex-col">
    {@render children()}
</div>


{#if dev}
    <div class="fixed left-0 top-1/2 flex h-12 w-12 items-center justify-center bg-neutral-200 text-lg font-medium text-black pointer-events-none">
        <span class="block xs:hidden">-</span>
        <span class="hidden xs:block sm:hidden">XS</span>
        <span class="hidden sm:block md:hidden">SM</span>
        <span class="hidden md:block lg:hidden">MD</span>
        <span class="hidden lg:block xl:hidden">LG</span>
        <span class="hidden xl:block 2xl:hidden">XL</span>
        <span class="hidden 2xl:block">2XL</span>
    </div>
{/if}