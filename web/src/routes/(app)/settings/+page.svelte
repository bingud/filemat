<script lang="ts">
    import { goto } from "$app/navigation";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { openSettingsSection } from "$lib/code/util/uiUtil";
    import { onMount } from "svelte";


    onMount(() => {
        openSettingsSection(uiState.settings.section)
    })
</script>