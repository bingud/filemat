<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { type Component } from "svelte";
    import DefaultPageSetting from "./_setting-components/DefaultPageSetting.svelte";
    import ClickToOpenFileSetting from "./_setting-components/ClickToOpenFileSetting.svelte";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import LoadPreviewsSetting from "./_setting-components/LoadPreviewsSetting.svelte";

    const title = "Preferences"

    $effect(() => {
        uiState.settings.title = title
        return appState.title.register(title)
    })
</script>


<div class="page !h-fit settings-margin flex-col gap-8 xoverflow-y-auto">
    <!-- <h2 class="text-lg font-medium">{title}</h2> -->

    {@render settingCell(LoadPreviewsSetting)}
    {@render settingCell(DefaultPageSetting)}
    {@render settingCell(ClickToOpenFileSetting)}
</div>

{#snippet settingCell(Component: Component<any>)}
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-surface">
        <Component></Component>
    </div>
{/snippet}