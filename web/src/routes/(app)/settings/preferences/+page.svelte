<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { onMount, type Component } from "svelte";
    import MfaSetting from "./_setting-components/MfaSetting.svelte";
    import LogoutSetting from "./_setting-components/LogoutSetting.svelte";
    import LoadPreviewsSetting from "./_setting-components/LoadPreviewsSetting.svelte";
    import DefaultPageSetting from "./_setting-components/DefaultPageSetting.svelte";
    import ClickToOpenFileSetting from "./_setting-components/ClickToOpenFileSetting.svelte";
    import { appState } from "$lib/code/stateObjects/appState.svelte";

    const title = "Preferences"

    onMount(() => {
        uiState.settings.title = title
    })

    $effect(() => {
        return appState.title.register("Preferences")
    })
</script>


<div class="page !h-fit settings-margin flex-col gap-8 xoverflow-y-auto">
    <h2 class="text-lg font-medium">Account Settings</h2>

    {@render settingCell(MfaSetting)}
    {@render settingCell(LoadPreviewsSetting)}
    {@render settingCell(DefaultPageSetting)}
    {@render settingCell(ClickToOpenFileSetting)}
    <LogoutSetting></LogoutSetting>
</div>

{#snippet settingCell(Component: Component<any>)}
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-surface">
        <Component></Component>
    </div>
{/snippet}