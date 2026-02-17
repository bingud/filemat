<script lang="ts">
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import { formData, handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte";

    let isLoading = $state(false)
    let isSaving = $state(false)

    let currentPath: string | null = $state(null)
    let pathInput: string | null = $derived(currentPath)

    let isUnchanged = $derived((pathInput === currentPath))
    let failedToLoad = $derived(!isLoading && !currentPath)

    let placeholder = $derived.by(() => {
        if (isLoading) return "Loading..."
        if (failedToLoad) return "Failed to load upload folder path."
        return "Upload folder path"
    })

    onMount(() => {
        loadCurrentPath()
    })

    async function loadCurrentPath() {
        if (isLoading) return
        isLoading = true

        try {
            const response = await safeFetch(`/api/v1/admin/system/get-upload-folder-path`, { method:"GET" })
            if (response.failed) {
                handleErr({
                    notification: "Failed to load uplad folder path.",
                })
                return
            }

            const text = response.content
            if (response.code.failed) {
                const json = response.json()
                handleErr({
                    description: "Failed to load upload folder path.",
                    notification: `Failed to load upload folder path: ${json.message || "unknown server error."}`,
                    isServerDown: response.code.serverDown
                })
                return
            }

            currentPath = text
            pathInput = currentPath
        } finally {
            isLoading = false
        }
    }

    async function changeUploadFolderPath() {
        const conf = await confirmDialogState.show({
            title: "Change upload folder path?",
            message: "This will pause all uploads, and move all upload files to the new folder.",
            cancelText: "Cancel",
            confirmText: "Yes"
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
                    notification: "Failed to update upload folder path.",
                })
                return
            }

            const status = response.code
            const json = response.json()

            if (status.failed) {
                handleErr({
                    description: "Failed to update upload folder path.",
                    notification: json.message || "Failed to update upload folder path.",
                    isServerDown: status.serverDown
                })
                return
            }

        } finally {
            isSaving = false
        }
    }

    function cancel() {
        pathInput = currentPath
    }
</script>




<div class="flex flex-col gap-4 w-full">
    <h3 class="font-medium">Upload folder path</h3>
    
    <input bind:value={pathInput} class="basic-input min-w-[35rem] w-fit max-w-[min(100%,70rem)] field-sizing-content" disabled={failedToLoad} placeholder={placeholder}>

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