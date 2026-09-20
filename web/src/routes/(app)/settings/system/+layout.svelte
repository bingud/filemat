<script lang="ts">
    import { goto } from '$app/navigation';
    import { hasPermissionLevel } from '$lib/code/module/permissions';
    import { fetchAdminSystemState } from '$lib/code/state/adminSystemFetcher.svelte';
    import { onMount } from 'svelte';


    let { children } = $props()

    onMount(() => {
        if (!hasPermissionLevel(3)) {
            goto(`/settings`)
            return
        }
        fetchAdminSystemState()
    })
</script>

{@render children()}