<script lang="ts">
    import { setPreferenceSetting } from "$lib/code/module/settings";
    import { appState } from "$lib/code/stateObjects/appState.svelte";

    function toggle() {
        const current = appState.settings.clickToOpenFile
        const changed = !current
        setPreferenceSetting("click_to_open_file", changed)
        appState.settings.clickToOpenFile = changed
    }
</script>




<div class="flex flex-col gap-4">
    <div class="flex flex-col gap-2">
        <h3 class="font-medium">Click to open file</h3>

        <div class="flex items-center gap-2 my-1">
            <div class={`w-3 h-3 rounded-full ${appState.settings.clickToOpenFile ? 'bg-green-500' : 'bg-neutral-500'}`}></div>
            <p class="text-base font-medium">
                {appState.settings.clickToOpenFile ? 'Enabled' : 'Disabled'}
            </p>
        </div>

        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            Whether to select or open a file when clicked
        </p>
    </div>

    <button
        on:click={toggle} 
        class="mt-2 w-fit px-4 py-2 bg-surface-content rounded-md">
        {appState.settings.clickToOpenFile ? 'Disable' : 'Enable'}
    </button>
</div>