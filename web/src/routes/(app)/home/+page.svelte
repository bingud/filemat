<script lang="ts">
    import { goto } from "$app/navigation";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { getFileRoutePath } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";

    onMount(() => {
        const path = auth.principal?.homeFolderPath ?? "/"
        goto(getFileRoutePath(path, "/files"))
    })
</script>