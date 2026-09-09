<script lang="ts">

	let { children } = $props();
</script>



<main class="w-full min-h-0 h-full flex flex-col gap-1">
    {@render children()}
</main>