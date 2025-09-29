<script lang="ts">

    let value = $state(0)
    
    $inspect(value)

    async function incr() {
        setTimeout(() => {
            value++
        })
    }
</script>

{#if value === 0}
    {console.log(`Rendered 1`, value)}
    {incr()}
    {console.log(`Rendered 2`, value)}
{/if}