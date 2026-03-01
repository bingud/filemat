<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { type Component } from "svelte";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import ClearThumbnailCacheSetting from "./_setting-components/ClearThumbnailCacheSetting.svelte";

    const title = "Preferences"

    $effect(() => {
        uiState.settings.title = title
        return appState.title.register(title)
    })
</script>


<div class="page !h-fit settings-margin flex-col gap-8">
    {@render settingCell(ClearThumbnailCacheSetting)}
</div>

{#snippet settingCell(Component: Component<any>)}
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-surface">
        <Component></Component>
    </div>
{/snippet}