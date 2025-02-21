<script lang="ts">

    import {onDestroy} from "svelte"

    let rawData = $state("")
    let data: any[] = $state([])
    let timeout: any = null
    async function get() {
        const r = await fetch("/api/test/auth")
        const d = await r.text()
        rawData = d
        data = JSON.parse(d)
        
        // timeout = setTimeout(() => {
        //     get()
        // }, 10000)
    }
    
    onDestroy(() => {
        clearTimeout(timeout)
    })
    
</script>


<div class="">
    <button on:click={get}>Diddlebob</button>
    <p>-----------</p>
    <div class="flex flex-wrap gap-2">
        <p>{rawData}</p>
    </div>
</div>