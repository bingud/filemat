<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import { type SettingSectionId } from "$lib/code/module/settings";
    import { goto } from "$app/navigation"
    
    import { type SettingsSection } from "$lib/code/module/settings";
    import { settingSections } from "$lib/code/module/settings";
    import CustomSidebar from "$lib/component/CustomSidebar.svelte";

    let { classes }: { classes: string } = $props()

    async function openSection(section: SettingSectionId) {
        await goto(`/settings/${section}`)
        uiState.settings.section = section
        uiState.settings.menuOpen = false
    }

    const adminSections = $derived(settingSections.allAdmin().filter(v => 
        settingSections.hasPermission(v.name)
    ))
</script>

{#snippet sectionButton(section: SettingsSection)}
    <a 
        href="/settings/{section.name}" 
        on:click|preventDefault={() => { openSection(section.name) }} 
        class="flex items-center px-4 py-2 w-full rounded-md duration-[50ms] hover:bg-neutral-300 dark:hover:bg-neutral-800 select-none capitalize {uiState.settings.section === section.name ? 'bg-neutral-300 dark:bg-neutral-800' : ''}"
    >
        {section.name}
    </a>
{/snippet}

<CustomSidebar 
    bind:open={uiState.settings.menuOpen}
    {classes}
>
    {#snippet children()}
        {#each settingSections.allUser() as section}
            {@render sectionButton(section)}
        {/each}

        {#if adminSections.length > 0}
            <p class="w-full px-4 border-b border-neutral-700 my-4 py-2 font-medium">Admin Settings</p>
        {/if}

        {#each adminSections as section}
            {@render sectionButton(section)}
        {/each}
    {/snippet}
</CustomSidebar>