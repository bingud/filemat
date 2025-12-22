<script lang="ts">
    import { onMount } from "svelte";
    import FilesPage from "../../(files)/files/[...path]/+page.svelte"
    import type { StateMetadata } from "$lib/code/stateObjects/filesState.svelte";

    const meta: StateMetadata = {
        type: "saved",
        fileEntriesUrlPath: `/api/v1/saved-file/get-all`,
        pagePath: "/files",
        pageTitle: "Saved Files",
        isArrayOnly: true
    }

    onMount(() => {
        
    })
</script>


<FilesPage meta={meta}></FilesPage>