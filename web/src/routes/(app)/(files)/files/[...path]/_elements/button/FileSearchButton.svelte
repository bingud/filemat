<script lang="ts">
    import type { FullFileMetadata } from "$lib/code/auth/types";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte"
    import { inputDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import { formData, handleErr, handleException, isServerDown, parseJson, streamNDJSON } from "$lib/code/util/codeUtil.svelte";
    import MagnifyingGlassIcon from "$lib/component/icons/MagnifyingGlassIcon.svelte"

    async function search() {
        const input = await inputDialogState.show({
            title: "Search files",
            message: "Enter searched file name:",
            confirmText: "Search",
            cancelText: "Close",
        })
        if (!input) return

        const path = filesState.path
        filesState.search.text = input

        streamNDJSON<FullFileMetadata>(`/api/v1/file/search`, {
            fetchProps: {
                method: "POST", body: formData({ path: path, shareToken: filesState.getShareToken(), text: input }), 
            }, 
            funs: {
                onStart: (cancel) => {
                    filesState.search.abortFunction = cancel
                },
                onMessage: (meta, cancel) => {
                    if (!meta) return
                    if (filesState.path !== path) {
                        cancel()
                        return
                    }
                },
                onError: async (response) => {
                    const text = await response.text()
                    const json = parseJson(text)
                    handleErr({
                        description: `Failed to stream file search results.`,
                        notification: json.message || `Failed to load search results.`,
                        isServerDown: isServerDown(response.status)
                    })
                },
                onException: (e) => {
                    handleException(`Failed to stream file search results`, `Failed to load search results.`, e)
                }
            }
        })
    }
</script>


<button title="Search files" on:click={() => { search() }} class="file-action-button">
    <MagnifyingGlassIcon />
</button>