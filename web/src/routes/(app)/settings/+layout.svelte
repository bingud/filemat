<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import MenuIcon from "$lib/component/icons/MenuIcon.svelte";
    import { onMount } from "svelte";
    import SettingsSidebar from "./components/SettingsSidebar.svelte";
    import { page } from "$app/state";
    import { isAdminSettingsSection, settingSectionLists, settingSections } from "./settings";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { openSettingsSection } from "$lib/code/util/uiUtil";

    let { children } = $props()

    onMount(() => {
    })

    /**
     * Subscribe to changes to the URL and change JS state based on it
    */
    $effect(() => {
        const urlSection = page.url.pathname.split("/settings/")[1]?.split("/")[0]?.split("?")[0] as any
        setSectionFromUrl(urlSection)
    })

    function setSectionFromUrl(urlSection: any) {
        if (settingSections.includes(urlSection)) {
            if (isAdminSettingsSection(urlSection) && !auth.isAdmin) {
                openSettingsSection(null)
                return;
            }
            uiState.settings.section = urlSection
        }
    }
</script>


<div class="page flex-col py-4 md:py-0 md:flex-row overflow-auto">
    <SettingsSidebar classes="border-l border-neutral-300 dark:border-neutral-800"></SettingsSidebar>

    <div class="contents md:flex flex-col md:w-without-sidebar-desktop">
        <div class="flex items-center md:p-6">
            <button on:click={() => { uiState.settings.menuOpen = true }} aria-label="Open settings navigation menu" class="p-4 size-[3.5rem] md:hidden">
                <MenuIcon />
            </button>
            <h2 class="text-lg capitalize">{uiState.settings.section}</h2>
        </div>

        <div id="setting-page" class="w-full h-fit p-4 md:p-6">
            {@render children()}
        </div>
    </div>
</div>