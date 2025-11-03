<script lang="ts">
    import { goto } from '$app/navigation';
    import { hasPermissionLevel } from '$lib/code/module/permissions';
    import { onMount } from 'svelte';


    let { children } = $props()

    const title = "System Settings"

    onMount(() => {
        if (!hasPermissionLevel(3)) {
            goto(`/settings`)
            return
        }
    })
</script>

{@render children()}