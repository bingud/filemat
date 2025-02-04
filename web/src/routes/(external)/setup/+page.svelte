<script lang="ts">
    import Noindex from "$lib/component/head/Noindex.svelte"
    import CodeChunk from "$lib/component/CodeChunk.svelte"
    
    let codeInput = $state("")
    let codeInputValid = $derived(codeInput.length === 12)
    
    function submit() {
    
    }
</script>

<svelte:head>
    <Noindex />
</svelte:head>

<div class="page items-center gap-12 pt-12">
    <div class="flex flex-col items-center gap-6">
        <h1 class="text-2xl font">Welcome to Filemat</h1>
    </div>
    
    <div class="flex flex-col gap-2">
        <p>To set up Filemat, please enter the setup code.</p>
        <div>
            <p>You can find it in these places:</p>
            <ol class="list-decimal list-inside marker:text-blue-700 dark:marker:text-blue-400">
                <li>The console or logs of the application</li>
                <li>The file <br class="xs:hidden"><CodeChunk>/var/lib/filemat/setup-code.txt</CodeChunk></li>
            </ol>
        </div>
    </div>
    
    <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={submit}>
        <input placeholder="Setup code (12 letters)" type="text" bind:value={codeInput} minlength="12" maxlength="12" required title="Enter the generated code." id="code-input" class="">
        <button type="submit" class="w-full py-2 bg-neutral-200 dark:bg-neutral-900 hover:bg-blue-300 dark:hover:bg-blue-800 disabled:pointer-events-none disabled:opacity-50" disabled={!codeInputValid}>Continue</button>
    </form>
</div>