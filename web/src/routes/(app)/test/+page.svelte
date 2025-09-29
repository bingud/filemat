<script lang="ts">
    import { tick } from 'svelte'

    let stateValue: string[] | null = $state(null)

    const value = Array(10000).fill("")

    async function start() {
        console.log('start')

        // first "place"
        stateValue = null
        console.log('nulled')
        await tick() // lets Svelte flush and consider this one update source

        // second "place"
        stateValue = value
        console.log('set array')
        await tick()

        // third "place" if needed
        stateValue = null
        console.log('set null')
        await tick()
    }
</script>

<button on:click={start}>Start</button>


{#if stateValue}
    {console.log(`Condition true`)}
    {#each stateValue as item}
        <p>{item}</p>
    {/each}
{/if}