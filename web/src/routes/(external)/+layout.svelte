<script lang="ts">

	let { children } = $props();
</script>


<nav class="flex px-4 items-center justify-between bg-blue-900/20 dark:bg-blue-950/50 h-(--navbar-height)">
    <a href="/">Filemat</a>
    <div class="flex gap-3">
        <a href="/setup">Setup</a>
        <a href="/login">Login</a>
    </div>
</nav>

<main class="px-4 py-4 w-full min-h-0 h-[calc(100%-var(--navbar-height))] flex flex-col gap-1">
    {@render children()}
</main>