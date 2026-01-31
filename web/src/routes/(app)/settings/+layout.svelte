<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import MenuIcon from "$lib/component/icons/MenuIcon.svelte";
    import { onMount } from "svelte";
    import SettingsSidebar from "./_components/SettingsSidebar.svelte";
    import { page } from "$app/state";
    
    import { settingSections } from "$lib/code/module/settings";
    import { openSettingsSection } from "$lib/code/util/uiUtil";
    import { appState } from "$lib/code/stateObjects/appState.svelte";

    let { children } = $props()

    onMount(() => {
    })

    /**
     * Subscribe to changes to the URL and change JS state based on it
    */
    $effect(() => {
        const urlSection = page.url.pathname.toLowerCase().split("/settings/")[1]?.split("/")[0]?.split("?")[0] as any
        setSectionFromUrl(urlSection)
    })

    // Set title
    $effect(() => {
        return appState.title.register(uiState.settings.title || "Settings")
    })

    function setSectionFromUrl(urlSection: any) {
        if (settingSections.hasPermission(urlSection) === false) {
            openSettingsSection(null)
            return
        }
        uiState.settings.section = urlSection
    }
</script>


<div class="flex w-full h-full flex-col lg:flex-row">
    <SettingsSidebar classes="lg:border-l border-neutral-300 dark:border-neutral-800"></SettingsSidebar>

    <div class="flex flex-col w-full lg:w-without-sidebar-desktop h-full overflow-y-auto custom-scrollbar">
        <div class="settings-info-bar-height w-full flex items-center bg-surface lg:p-6">
            <button on:click={() => { uiState.settings.menuOpen = true }} aria-label="Open settings navigation menu" class="p-4 size-[3.5rem] lg:hidden">
                <MenuIcon />
            </button>
            <h2 class="text-lg capitalize">{uiState.settings.title}</h2>
        </div>

        <div id="setting-page" class="w-full settings-content-height">
            {@render children()}
        </div>
    </div>
</div>

<style lang="postcss">
    :root {
        --info-bar-content-height: 1.75rem;
        --info-bar-padding: 1rem;
        --info-bar-height: calc(var(--info-bar-content-height) + (var(--info-bar-padding) * 2));

        --settings-content-height: calc(100% - var(--info-bar-height));
    }

    .settings-info-bar-height {
        height: var(--info-bar-height);
        padding-top: var(--info-bar-padding);
        padding-bottom: var(--info-bar-padding);
    }

    .settings-content-height {
        height: var(--settings-content-height);
    }

    :global(.settings-margin) {
        @apply p-4 lg:p-6;
    }
</style>