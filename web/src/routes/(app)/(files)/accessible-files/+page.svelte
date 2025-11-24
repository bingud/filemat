<script lang="ts">
    import { onMount } from "svelte";
    import FilesPage from "../../(files)/files/[...path]/+page.svelte"
    import type { StateMetadata } from "$lib/code/stateObjects/filesState.svelte";

    const meta: StateMetadata = {
        type: "accessible",
        fileEntriesUrlPath: `/api/v1/file/all-permitted`,
        pagePath: "/accessible-files",
        pageTitle: "Accessible Files"
    }

    onMount(() => {
        
    })
</script>


<FilesPage meta={meta}></FilesPage>