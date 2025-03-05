<script lang="ts">
    import { auth } from "$lib/code/state/authState.svelte";
    import { onMount } from "svelte";

    let data: any = $state(null)

    onMount(() => {
        
    })

    async function openFolder() {
        data = []
        const body = new FormData();
        body.append("path", "/usr/share");

        const response = await fetch(`/api/v1/folder/list`, {
            credentials: "same-origin",
            method: "POST",
            body: body
        });

        const reader = response.body!.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });

            try {
                // Attempt to parse as JSON if the buffer contains a complete object
                while (buffer.includes("{") && buffer.includes("}")) {
                    const start = buffer.indexOf("{");
                    const end = buffer.indexOf("}") + 1;
                    const jsonChunk = buffer.slice(start, end);
                    
                    const parsed = JSON.parse(jsonChunk);
                    data.push(parsed)

                    // Remove processed data from the buffer
                    buffer = buffer.slice(end);
                }
            } catch (error) {
                // Ignore errors due to incomplete JSON, wait for more data
            }
        }
    }

</script>


<div class="size-full flex flex-col items-center justify-center">
    <button class="tw-form-button" on:click={openFolder}>Open folder</button>

    <div id="file-browser" class="flex flex-col gap-6">
        {#if data}
            {#each data as item}
                <div>
                    {#each Object.keys(item) as key}
                        <p>{key}: {item[key]}</p>
                    {/each}
                </div>
            {/each}
        {/if}
    </div>
</div>