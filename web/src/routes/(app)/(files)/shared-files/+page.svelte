<script lang="ts">
    import { onMount } from "svelte";
    import FilesPage from "../../(files)/files/[...path]/+page.svelte"
    import type { StateMetadata } from "$lib/code/stateObjects/filesState.svelte";

    const meta: StateMetadata = {
        type: "allShared",
        fileEntriesUrlPath: `/api/v1/file/all-shared`,
        pagePath: "/files",
        pageTitle: "Shared Files",
        isArrayOnly: true
    }

    onMount(() => {
        
    })
</script>


<FilesPage meta={meta}></FilesPage>