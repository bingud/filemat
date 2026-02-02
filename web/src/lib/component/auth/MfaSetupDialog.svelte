<script lang="ts">
    import { autofocus, disabledFor } from "$lib/code/util/uiUtil";
    import ChevronLeftIcon from "../icons/ChevronLeftIcon.svelte";
    import ChevronRightIcon from "../icons/ChevronRightIcon.svelte";
    import Loader from "../Loader.svelte";
    import CustomDialog from "../popover/CustomDialog.svelte";

    let {
        isOpen = $bindable(),
        credentials,
        phase = $bindable(),
        qrCodeBase64,
        onCancel,
        onSubmit: two_confirm,
        onFinish: three_finish,
        totpInput = $bindable(),
    }: {
        isOpen: boolean,
        credentials: { secret: string, url: string, codes: string[] },
        phase: number,
        qrCodeBase64: string,
        onCancel: () => any,
        onSubmit: () => any,
        onFinish?: () => any,
        totpInput: string | undefined,
    } = $props()

    function goBack() { phase -= 1 }
    function one_next() {
        phase += 1
    }
    
</script>


<CustomDialog bind:isOpen={isOpen} class="w-[35rem]! h-[40rem]!" onOpenChange={(open) => { if (!open) onCancel() }}>
    <div class="size-full flex flex-col items-center px-4 py-4 gap-8 overflow-hidden">
        {#if credentials}
            {#if phase === 1}
                <h2 class="text-2xl">Set up 2FA</h2>

                <div class="flex flex-col gap-4 w-full items-center">
                    <img src={qrCodeBase64} alt="2FA QR code" class="aspect-square w-[20rem] max-w-full">
                    <p class="break-all">{credentials.secret}</p>
                </div>

                <div class="w-full flex gap-10 items-center justify-center h-[4rem] mt-auto">
                    <button on:click={onCancel} class="rounded-lg border border-neutral-700 px-6 py-3 flex gap-2 bg-surface-content-button">
                        Cancel
                    </button>
                    <button on:click={one_next} class="rounded-lg border border-neutral-700 px-6 py-3 flex gap-2 bg-surface-content-button">
                        Next
                        <ChevronRightIcon class="h-[1rem] my-auto" />
                    </button>
                </div>
            {:else if phase === 2}
                <h2 class="text-2xl">Confirm 2FA</h2>

                <div class="flex flex-col gap-2">
                    <label for="input-totp" class="">Enter the 6-digit code</label>
                    <input use:autofocus id="input-totp" class="w-[10rem] max-w-full basic-input" type="text" inputmode="numeric" bind:value={totpInput}>
                </div>

                <div class="w-full flex gap-10 items-center justify-center h-[4rem] mt-auto">
                    <button on:click={goBack} class="rounded-lg border border-neutral-700 px-6 py-3 flex gap-2 bg-surface-content-button">
                        <ChevronLeftIcon class="h-[1rem] my-auto" />
                        Back
                    </button>
                    <button on:click={two_confirm} class="rounded-lg border border-neutral-700 px-6 py-3 flex gap-2 bg-surface-content-button">
                        Confirm
                        <ChevronRightIcon class="h-[1rem] my-auto" />
                    </button>
                </div>
            {:else if phase === 3}
                <h2 class="text-2xl">Backup Codes</h2>

                <div class="flex flex-col gap-8 w-full items-center">
                    <p class="text-neutral-700 dark:text-neutral-300 text-center">
                        2FA was enabled.<br>Save these backup codes in a secure location. You can use them to access your account if you lose access to your 2FA.
                    </p>
                    <div class="grid grid-cols-2 gap-3 w-full max-w-[20rem]">
                        {#each credentials.codes as code}
                            <div class="bg-neutral-100 dark:bg-neutral-800 px-3 py-2 rounded-md text-center font-mono text-sm border">
                                {code}
                            </div>
                        {/each}
                    </div>
                </div>

                <div class="w-full flex gap-10 items-center justify-center h-[4rem] mt-auto">
                    <button use:disabledFor={6000} on:click={three_finish} class="rounded-lg border border-neutral-700 px-6 py-3 flex gap-2 disabled:opacity-50 bg-surface-content-button">
                        Continue
                        <ChevronRightIcon class="h-[1rem] my-auto" />
                    </button>
                </div>
            {/if}
        {:else}
            <Loader class="m-auto"></Loader>
        {/if}
    </div>
</CustomDialog>