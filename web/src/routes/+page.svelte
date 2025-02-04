<script lang="ts">

    import {onDestroy} from "svelte"

    let rawData = $state("")
    let data: any[] = $state([])
    let timeout: any = null
    async function get() {
        const r = await fetch("/api/test")
        const d = await r.text()
        rawData = d
        data = JSON.parse(d)
        
        timeout = setTimeout(() => {
            get()
        }, 10000)
    }
    
    onDestroy(() => {
        clearTimeout(timeout)
    })
    
</script>


<div class="">
    <button on:click={get}>Diddlebob</button>
    <div class="flex flex-wrap gap-2">
        {#each data as item}
            <div class="px-4 bg-neutral-900/50">
                <p>{item.name}</p>
                <p>{item.type}</p>
                <p>{item.inode}</p>
                <p>{item.xattr}</p>
            </div>
        {/each}
    </div>
</div>