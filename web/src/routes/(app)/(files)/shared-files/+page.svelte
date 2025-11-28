<script lang="ts">
    import { onMount } from "svelte";
    import FilesPage from "../../(files)/files/[...path]/+page.svelte"
    import type { StateMetadata } from "$lib/code/stateObjects/filesState.svelte";
    import { explicitEffect } from "$lib/code/util/codeUtil.svelte";
    import { sharedFilesPageState } from "./state.svelte";
    import { reloadCurrentFolder } from "../files/[...path]/_code/pageLogic";

    const meta: StateMetadata = {
        type: "allShared",
        fileEntriesUrlPath: `/api/v1/file/all-shared`,
        pagePath: "/files",
        pageTitle: "Shared Files",
        isArrayOnly: true
    }

    onMount(() => {
        
    })

    let lastState = sharedFilesPageState.showAll
    explicitEffect(() => [
        sharedFilesPageState.showAll
    ], () => {
        if (lastState === sharedFilesPageState.showAll) return
        lastState = sharedFilesPageState.showAll

        reloadCurrentFolder()
    })
</script>


<FilesPage meta={meta}></FilesPage>