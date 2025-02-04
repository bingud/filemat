<script lang="ts">
	import '../app.css';
    import {dev} from "$app/environment"
	let { children } = $props();
</script>

<nav class="flex px-4 items-center justify-between bg-blue-900/20 dark:bg-blue-950/50 h-(--navbar-height)">
    <a href="/">Filemat</a>
    <div class="flex gap-3">
        <a href="/setup">Setup</a>
        <a href="/login">Login</a>
        <a href="/register">Register</a>
    </div>
</nav>

<main class="px-4 py-4 w-full min-h-fit h-[calc(100%-var(--navbar-height))] flex flex-col gap-1">
    {@render children()}
</main>


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