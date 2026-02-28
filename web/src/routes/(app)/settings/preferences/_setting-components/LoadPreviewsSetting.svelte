<script lang="ts">
    import { setPreferenceSetting } from "$lib/code/module/settings";
    import { appState } from "$lib/code/stateObjects/appState.svelte";

    function toggle() {
        const bool = !appState.settings.loadAllPreviews
        appState.settings.loadAllPreviews = bool
        setPreferenceSetting("load_all_previews", bool)
        if (!bool) {
            appState.settings.alwaysRenderPreviews = false
            setPreferenceSetting("always_render_previews", false)
        }
    }

    function toggleAlwaysRender() {
        const bool = !appState.settings.alwaysRenderPreviews
        appState.settings.alwaysRenderPreviews = bool
        setPreferenceSetting("always_render_previews", bool)
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
        class="mt-2 w-fit px-4 py-2 bg-surface-content-button rounded-md">
        {appState.settings.loadAllPreviews ? 'Disable' : 'Enable'}
    </button>

    {#if appState.settings.loadAllPreviews}
        <div class="flex flex-col gap-2 mt-2 pl-4 border-l-2 border-neutral-300 dark:border-neutral-700">
            <h4 class="font-medium">Always Render Previews</h4>
            <div class="flex items-center gap-2 my-1">
                <div class={`w-3 h-3 rounded-full ${appState.settings.alwaysRenderPreviews ? 'bg-green-500' : 'bg-neutral-500'}`}></div>
                <p class="text-base font-medium">
                    {appState.settings.alwaysRenderPreviews ? 'Enabled' : 'Disabled'}
                </p>
            </div>
            <p class="text-sm text-neutral-600 dark:text-neutral-400">
                Keep all image previews rendered at all times instead of unloading them when off-screen.<br>
                Enable in case of issues with preview caching or loading problems.
            </p>
            <button
                on:click={toggleAlwaysRender}
                class="mt-2 w-fit px-4 py-2 bg-surface-content-button rounded-md">
                {appState.settings.alwaysRenderPreviews ? 'Disable' : 'Enable'}
            </button>
        </div>
    {/if}
</div>