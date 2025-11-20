<script lang="ts">
    import { page } from "$app/state";
    import type { StateMetadata } from "$lib/code/stateObjects/filesState.svelte";
    import FilesPage from "../../../files/[...path]/+page.svelte"
    
    let shareId = $derived(page.params.shareId)

    const meta: StateMetadata | undefined = $derived.by(() => {
        if (!shareId) return undefined
        return {
            isFiles: false,
            isSharedFiles: true,
            isAccessibleFiles: false,
            fileEntriesUrlPath: "/api/v1/folder/file-and-folder-entries",
            shareId: shareId,
            pagePath: `/share/${shareId}`,
            pageTitle: "Shared file"
        }
    })
</script>

{#if shareId && meta}
    <FilesPage meta={meta}></FilesPage>
{:else}
    <p>Shared file link is invalid.</p>
{/if}