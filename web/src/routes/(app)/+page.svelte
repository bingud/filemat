<script lang="ts">
    import { goto } from "$app/navigation";
    import { onMount } from "svelte";

    let data: any = $state(null)
    let error = $state("")

    onMount(() => {
        goto("/files")
    })

</script>