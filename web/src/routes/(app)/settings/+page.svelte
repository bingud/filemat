<script lang="ts">
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import MenuIcon from "$lib/component/icons/MenuIcon.svelte";
    import PreferencesSettings from "./components/PreferencesSettings.svelte";
    import SettingsSidebar from "./components/SettingsSidebar.svelte";

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
            {#if uiState.settings.section === "preferences"}
                <PreferencesSettings></PreferencesSettings>
            {/if}
        </div>
    </div>
</div>