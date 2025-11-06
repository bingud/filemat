<script lang="ts">
    import { goto } from '$app/navigation';
    import { hasPermissionLevel } from '$lib/code/module/permissions';
    import { uiState } from '$lib/code/stateObjects/uiState.svelte';
    import { onMount } from 'svelte';


    let { children } = $props()

    onMount(() => {
        if (!hasPermissionLevel(3)) {
            goto(`/settings`)
            return
        }
    })
</script>

{@render children()}