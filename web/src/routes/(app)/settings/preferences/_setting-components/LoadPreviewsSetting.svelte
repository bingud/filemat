<script lang="ts">
    import { setPreferenceSetting } from "$lib/code/module/settings";
    import { appState } from "$lib/code/stateObjects/appState.svelte";

    function toggle() {
        const bool = !appState.settings.loadAllPreviews
        appState.settings.loadAllPreviews = bool
        setPreferenceSetting("load_all_previews", bool)
    }
</script>




<div class="flex flex-col gap-4">
    <div class="flex flex-col gap-2">
        <h3 class="font-medium">File Preview Loading</h3>
        <div class="flex items-center gap-2 my-1">
            <div class={`w-3 h-3 rounded-full ${appState.settings.loadAllPreviews ? 'bg-green-500' : 'bg-neutral-500'}`}></div>
            <p class="text-base font-medium">
                {appState.settings.loadAllPreviews ? 'Enabled' : 'Disabled'}
            </p>
        </div>
        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            Enable loading of all file previews. When enabled, previews will load even if you have not scrolled to them.
        </p>
    </div>
    
    <button
        on:click={toggle} 
        class="mt-2 w-fit px-4 py-2 bg-neutral-300 dark:bg-neutral-700 hover:bg-neutral-400 dark:hover:bg-neutral-600 rounded-md">
        {appState.settings.loadAllPreviews ? 'Disable' : 'Enable'}
    </button>
</div>