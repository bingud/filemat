<script lang="ts">
    import { auth } from "$lib/code/state/authState.svelte";
    import { onMount } from "svelte";

    let data: any = $state(null)

    onMount(() => {
        
    })

    async function openFolder() {
        const body = new FormData()
        body.append("path", "/home/wsl/test")
        const r = await fetch(`/api/v1/folder/list`, { credentials: "same-origin", method: "POST", body: body })
        data = await r.json()
    }

</script>


<div class="size-full flex flex-col items-center justify-center">
    <button class="tw-form-button" on:click={openFolder}>Open folder</button>

    <div id="file-browser" class="">
        {#if data}
            {#each data as item}
                <p>{item}</p>
            {/each}
        {/if}
    </div>
</div>