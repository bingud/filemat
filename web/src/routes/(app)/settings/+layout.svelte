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
        const url = page.url.pathname
        const urlSection = url.replace(/^\/settings\/?/, '').split('?')[0] as any
        if (settingSections.includes(urlSection)) {
            if (isAdminSettingsSection(urlSection) && !auth.isAdmin) {
                openSettingsSection(null)
                return
            }
            uiState.settings.section = urlSection
        }
    })
</script>


<div class="page flex-col py-4 md:py-0 md:flex-row">
    <SettingsSidebar classes="border-l border-neutral-800"></SettingsSidebar>

    <div class="contents md:flex flex-col">
        <div class="flex items-center md:p-6">
            <button on:click={() => { uiState.settings.menuOpen = true }} aria-label="Open settings navigation menu" class="p-4 size-[3.5rem] md:hidden">
                <MenuIcon />
            </button>
            <h2 class="text-lg capitalize">{uiState.settings.section}</h2>
        </div>

        <div class="w-full h-fit px-4 py-4">
            {@render children()}
        </div>
    </div>
</div>