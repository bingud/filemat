<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { onMount, type Component } from "svelte";
    import MfaSetting from "./_setting-components/MfaSetting.svelte";
    import LogoutSetting from "./_setting-components/LogoutSetting.svelte";

    const title = "Preferences"

    onMount(() => {
        uiState.settings.title = title
    })
</script>


<div class="page flex-col gap-8">
    <h2 class="text-lg font-medium">Account Settings</h2>

    {@render settingCell(MfaSetting)}
    <LogoutSetting></LogoutSetting>
</div>

{#snippet settingCell(Component: Component<any>)}
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-850">
        <Component></Component>
    </div>
{/snippet}