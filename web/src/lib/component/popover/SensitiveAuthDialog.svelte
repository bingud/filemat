<script lang="ts">
    import { sensitiveAuth } from "$lib/code/state/sensitiveAuth.svelte"
    import CodeChunk from "$lib/component/CodeChunk.svelte"
    import { Dialog } from "bits-ui"
</script>

<Dialog.Root bind:open={sensitiveAuth.dialogOpen}>
    <Dialog.Portal>
        <Dialog.Overlay class="fixed inset-0 z-50 bg-black/50" />
        <Dialog.Content>
            <div class="rounded-lg bg-surface shadow-popover fixed left-[50%] top-[50%] z-50 w-[30rem] max-w-[calc(100%-2rem)] translate-x-[-50%] translate-y-[-50%] p-8 flex flex-col gap-8">
                <p>{sensitiveAuth.dialogMessage}</p>
                <div class="flex flex-col gap-2">
                    <p>The code can be found in:</p>
                    <ul class="list-disc list-inside">
                        <li>Application console or logs</li>
                        <li><CodeChunk>/var/lib/filemat/auth-code.txt</CodeChunk></li>
                    </ul>
                </div>
                <form on:submit|preventDefault={() => sensitiveAuth.verify()} class="flex flex-col w-full max-w-[18rem] mx-auto gap-2">
                    <label for="sensitive-auth-code-input">Code:</label>
                    <input id="sensitive-auth-code-input" required minlength="16" maxlength="16" bind:value={sensitiveAuth.codeInput} class="basic-input bg-surface-content!">
                    <button type="submit" class="basic-input-button bg-surface-content!">{#if !sensitiveAuth.loading}Continue{:else}...{/if}</button>
                </form>
            </div>
        </Dialog.Content>
    </Dialog.Portal>
</Dialog.Root>
