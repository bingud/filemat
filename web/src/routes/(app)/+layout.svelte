<script lang="ts">
    import { goto } from '$app/navigation';
    import { auth, auth_load } from '$lib/code/auth/auth.svelte';
    import { onDestroy, onMount } from 'svelte';

    let { children } = $props()

    onMount(() => {
        (async () => {
            const principal = await auth_load()
            if (auth.authenticated == null) {
                await goto("/login")
            }
        })()
    })

    onDestroy(() => {
        auth.reset()
    })
</script>


{#if auth.authenticated === true}
    <main>
        {@render children()}
    </main>
{:else if auth.authenticated === null}
    <div class="w-full h-full flex flex-col items-center justify-center">
        <div class="loader"></div>
    </div>
{/if}