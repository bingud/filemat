<script lang="ts">
    import { goto } from "$app/navigation";
    import { hasPermissionLevel } from "$lib/code/module/permissions";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { formData, handleError, handleErrorResponse, handleException, pageTitle, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";
    import { toast } from "@jill64/svelte-toast";

    const title = "System Settings"
    let loading = $state(false)
    let savingSymlinks = $state(false)
    let showConfirmation = $state(false)

    onMount(() => {
        uiState.settings.title = title
        if (!hasPermissionLevel(3)) {
            goto(`/settings`)
            return
        }
    })

    
    async function saveSymlinkSettings() {
        if (savingSymlinks) return
        savingSymlinks = true
        
        try {
            const body = formData({})
            body.append("new-state", (!appState.followSymlinks).toString())

            const response = await safeFetch(`/api/v1/admin/system/set/follow-symlinks`, { body })
            
            if (response.failed) {
                handleException("Failed to save settings", "Could not save system settings", response.exception)
                return
            }
            
            const status = response.code
            const json = response.json()
            
            if (status.ok) {
                appState.followSymlinks = !appState.followSymlinks
                showConfirmation = false
            } else if (status.serverDown) {
                handleError(`Server ${status} when saving settings`, "Server is unavailable")
            } else {
                handleErrorResponse(json, "Failed to save system settings")
            }
        } finally {
            savingSymlinks = false
        }
    }

</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


<div class="page flex-col gap-8">
    {#if loading}
        <div class="flex justify-center items-center p-8">
            <div class="size-8 border-4 border-neutral-300 border-t-neutral-600 rounded-full animate-spin"></div>
        </div>
    {:else}
        <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-850">
            <h2 class="text-lg font-medium">File System Settings</h2>
            
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
                        <span class="italic">Note: Environment variable settings override this value.</span>
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
        </div>
    {/if}
</div>