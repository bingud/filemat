<script lang="ts">
    import { onMount } from "svelte";
    import FilesPage from "../files/[...path]/content/component/FilesPage.svelte";


    onMount(() => {
        
    })
</script>


<FilesPage overrideTopLevelFolderUrlPath="/api/v1/file/all-permitted" requireFolderMeta={false}></FilesPage>