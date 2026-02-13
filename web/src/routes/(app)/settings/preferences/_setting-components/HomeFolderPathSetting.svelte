<script lang="ts">
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { formData, handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";

    let isLoading = $state(false)
    let pathInput = $derived(auth.principal?.homeFolderPath)

    let isUnchanged = $derived(pathInput === auth.principal?.homeFolderPath)

    onMount(() => {

    })

    async function savePath() {
        if (isLoading) return
        isLoading = true

        try {
            const response = await safeFetch(`/api/v1/user/update-home-folder-path`, {
                body: formData({ path: pathInput })
            })
            if (response.failed) {
                    handleErr({
                    description: response.exception,
                    notification: `Failed to update home folder path.`,
                })
                return
            }
            
            const text = response.content
            const json = response.json()
            if (response.code.failed) {
                handleErr({
                    description: `Failed to update home folder path.`,
                    notification: json.message || "Failed to update home folder path.",
                    isServerDown: response.code.serverDown
                })

                return
            }

            if (auth.principal) auth.principal.homeFolderPath = text
            console.log(`Changed home path:`, text)
        } finally {
            isLoading = false
        }
    }
    
    function cancel() {
        pathInput = auth.principal!.homeFolderPath
    }
</script>




<div class="flex flex-col gap-4">
    <h3 class="font-medium">Home folder path</h3>
    
    <input bind:value={pathInput} class="basic-input" placeholder="Home folder path">

    <div class="flex gap-4">
        <button
            on:click={savePath}
            disabled={isUnchanged}
            class="basic-button bg-surface-content-button! disabled:opacity-50"
        >
            Save
        </button>
        {#if pathInput !== auth.principal?.homeFolderPath}
            <button
                on:click={cancel}
                disabled={isUnchanged}
                class="basic-button"
            >
                Cancel
            </button>
        {/if}
    </div>
</div>