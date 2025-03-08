<script lang="ts">
    import { auth } from "$lib/code/state/authState.svelte";
    import { toast } from "@jill64/svelte-toast";
    import { onMount } from "svelte";

    let data: any = $state(null)
    let error = $state("")

    onMount(() => {
        
    })

    async function openFolder() {
        data = []
        const body = new FormData();
        body.append("path", "/etc/ssh");

        const response = await fetch(`/api/v1/folder/list`, {
            credentials: "same-origin",
            method: "POST",
            body: body
        });

        if (response.status != 200) {
            error = await response.text()
        } else {
            const json = await response.json()
            data = json
        }
    }

</script>


<div class="size-full flex flex-col items-center justify-center">
    <button class="tw-form-button" on:click={openFolder}>Open folder</button>

    <div id="file-browser" class="flex flex-col gap-6">
        {#if data != null}
            {#each data as item}
                <div>
                    {#each Object.keys(item) as key}
                        <p>{key}: {item[key]}</p>
                    {/each}
                </div>
            {/each}
        {/if}

        {#if error != ""}
            <p>{error}</p>
        {/if}
    </div>
</div>