<script lang="ts">
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { formData, handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte";

    let showConfirmation = $state(false)
    let savingSymlinks = $state(false)

    async function saveSymlinkSettings() {
        if (savingSymlinks) return
        savingSymlinks = true

        try {
            const body = formData({})
            body.append("new-state", (!appState.followSymlinks).toString())

            const response = await safeFetch(`/api/v1/admin/system/set/follow-symlinks`, { body })

            if (response.failed) {
                handleErr({
                    description: "Failed to save settings",
                    notification: "Could not save system settings",
                })
                return
            }

            const status = response.code
            const json = response.json()

            if (status.failed) {
                handleErr({
                    description: "Failed to save system settings",
                    notification: json.message || "Failed to save system settings",
                    isServerDown: status.serverDown
                })
                return
            }

            appState.followSymlinks = !appState.followSymlinks
            showConfirmation = false
        } finally {
            savingSymlinks = false
        }
    }
</script>

<div class="flex flex-col gap-4">
    <div class="flex flex-col gap-2">
        <h3 class="font-medium">Symbolic Links</h3>
        <div class="flex items-center gap-2 my-1">
            <div class={`w-3 h-3 rounded-full ${appState.followSymlinks ? 'bg-green-500' : 'bg-neutral-500'}`}></div>
            <p class="text-base font-medium">
                {appState.followSymlinks ? 'Following symbolic links' : 'Not following symbolic links'}
            </p>
        </div>
        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            When enabled, symbolic links are followed to their targets. When disabled, they're shown as regular files.
            <span class="italic">Note: Environment variable settings override this setting.</span>
        </p>
    </div>
    
    {#if !showConfirmation}
        <button 
            on:click={() => showConfirmation = true} 
            class="mt-2 w-fit px-4 py-2 bg-neutral-300 dark:bg-neutral-700 hover:bg-neutral-400 dark:hover:bg-neutral-600 rounded-md">
            {appState.followSymlinks ? 'Disable' : 'Enable'} Symbolic Links
        </button>
    {:else}
        <div class="flex flex-col gap-3 p-4 border border-neutral-400 bg-neutral-100 dark:bg-neutral-800 dark:border-neutral-600 rounded-md">
            <p class="text-sm">
                {appState.followSymlinks ? 'Disable' : 'Enable'} symbolic link following?
            </p>
            <div class="flex gap-3 mt-1">
                <button 
                    disabled={savingSymlinks} 
                    on:click={saveSymlinkSettings} 
                    class="px-4 py-2 bg-blue-500 text-white hover:bg-blue-600 rounded-md disabled:opacity-50 disabled:cursor-not-allowed">
                    {savingSymlinks ? 'Saving...' : appState.followSymlinks ? 'Disable' : 'Enable'}
                </button>
                <button 
                    on:click={() => showConfirmation = false} 
                    class="px-4 py-2 bg-neutral-300 dark:bg-neutral-700 hover:bg-neutral-400 dark:hover:bg-neutral-600 rounded-md">
                    Cancel
                </button>
            </div>
        </div>
    {/if}
</div>