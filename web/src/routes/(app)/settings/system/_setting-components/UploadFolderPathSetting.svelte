<script lang="ts">
    import { adminSystemState } from "$lib/code/state/adminSystemFetcher.svelte";
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import { formData, handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";

    let isSaving = $state(false)
    let pathInput = $state(``)

    let isUnchanged = $derived(pathInput === (adminSystemState.uploadFolderPath ?? ``))
    let failedToLoad = $derived(adminSystemState.loadFailed)
    let isLoading = $derived(adminSystemState.loading && adminSystemState.uploadFolderPath == null)

    let placeholder = $derived.by(() => {
        if (isLoading) return `Loading...`
        if (failedToLoad) return `Failed to load upload folder path.`
        return `Upload folder path`
    })

    $effect(() => {
        if (adminSystemState.uploadFolderPath != null) {
            pathInput = adminSystemState.uploadFolderPath
        }
    })

    async function changeUploadFolderPath() {
        const conf = await confirmDialogState.show({
            title: `Change upload folder path?`,
            message: `This will pause all uploads, and move all upload files to the new folder.`,
            cancelText: `Cancel`,
            confirmText: `Yes`
        })
        if (!conf) return

        if (isSaving) return
        isSaving = true

        try {
            const response = await safeFetch(`/api/v1/admin/system/set/upload-folder-path`, {
                body: formData({ "new-path": pathInput }) 
            })

            if (response.failed) {
                handleErr({
                    notification: `Failed to update upload folder path.`,
                })
                return
            }

            const status = response.code
            const json = response.json()

            if (status.failed) {
                handleErr({
                    description: `Failed to update upload folder path.`,
                    notification: json.message || `Failed to update upload folder path.`,
                    isServerDown: status.serverDown
                })
                return
            }

            adminSystemState.uploadFolderPath = response.content
            pathInput = response.content
        } finally {
            isSaving = false
        }
    }

    function cancel() {
        pathInput = adminSystemState.uploadFolderPath ?? ``
    }
</script>




<div class="flex flex-col gap-4 w-full">
    <h3 class="font-medium">Upload folder path</h3>
    
    <input bind:value={pathInput} class="basic-input w-[45rem] max-w-full" disabled={failedToLoad || isLoading || isSaving} placeholder={placeholder}>

    <div class="flex gap-4">
        {#if !isSaving}
            <button
                on:click={changeUploadFolderPath}
                disabled={isUnchanged || !pathInput}
                class="basic-button bg-surface-content-button! disabled:opacity-50"
            >
                Save
            </button>
            {#if !isUnchanged}
                <button
                    on:click={cancel}
                    disabled={isUnchanged}
                    class="basic-button"
                >
                    Cancel
                </button>
            {/if}
        {:else}
            <div class="size-5">
                <Loader></Loader>
            </div>
        {/if}
    </div>
</div>
