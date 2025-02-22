<script lang="ts">
    import { auth_load } from '$lib/code/auth/auth.svelte';
    import { onMount } from 'svelte';


    let { children } = $props()

    onMount(() => {
        auth_load()
    })
</script>


<main>
    {@render children()}
</main>