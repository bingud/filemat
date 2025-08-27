<script lang="ts">
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { explicitEffect, formData, handleError, handleErrorResponse, pageTitle, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";
    import QRCode from 'qrcode'
    import CustomDialog from "$lib/component/CustomDialog.svelte";
    import ChevronLeftIcon from "$lib/component/icons/ChevronLeftIcon.svelte";
    import ChevronRightIcon from "$lib/component/icons/ChevronRightIcon.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { toast } from "@jill64/svelte-toast";
    import { autofocus, disabledFor } from "$lib/code/util/uiUtil";
    import Loader from "$lib/component/Loader.svelte";

    const title = "Preferences"
    let newTotp: { secret: string, url: string, codes: string[] } | null = $state(null)
    let qrCodeBase64: string | null = $state(null) 

    let dialogOpen = $state(false)
    let phase = $state(1)
    let previousMfaStatus: boolean | null = $state(null)

    let totpInput: number | undefined = $state()

    // QR code generation
    $effect(() => {
        if (!newTotp || !newTotp.url) {
            qrCodeBase64 = null
            return
        }
        QRCode.toDataURL(newTotp.url).then((qrCodeImage) => {
            qrCodeBase64 = qrCodeImage
        })
    })

    // Data clearing on close
    explicitEffect(() => {
        if (!dialogOpen) {
            newTotp = null
            qrCodeBase64
            phase = 1
            totpInput = undefined
        }
    }, () => [dialogOpen])

    function cancel() { dialogOpen = false } // Resetting values done by effect
    function goBack() { phase-- }

    // Cancel operation if 2FA is toggled from elsewhere
    explicitEffect(() => {
        const status = auth.principal?.mfaTotpStatus || null
        if (previousMfaStatus !== status) {
            cancel()
            previousMfaStatus = status
        }
    }, () => [ auth.principal?.mfaTotpStatus ])
    
    onMount(() => {
        uiState.settings.title = title
    })

    function toggleMfa() {
        if (auth.principal?.mfaTotpStatus == true) {
            disable_init()
        } else {
            enable_init_requestTotpSecret()
        }
    }

    /**
     * Enabling functions
     */

    async function enable_init_requestTotpSecret() {
        dialogOpen = true

        const response = await safeFetch(`/api/v1/user/mfa/enable/generate-secret`)
        if (response.failed) {
            handleErrorResponse(response, `Failed to enable 2FA.`)
            return
        }

        const json = response.json()
        if (response.code.ok) {
            if (!json) return
            newTotp = json
        } else {
            const error = json.message
            handleError(`Status ${response.status} when enabling 2FA.`, error.error || `Failed to enable 2FA.`)
        }
    }

    function enable_one_next() {
        phase++
    }

    async function enable_two_confirm() {
        if (!newTotp) return
        const totpValidation = Validator.totp(totpInput?.toString())
        if (totpValidation) {
            toast.error(totpValidation)
            return
        }

        const body = formData({ totp: totpInput, codes: JSON.stringify(newTotp.codes) })
        const response = await safeFetch(`/api/v1/user/mfa/enable/confirm`, {
            body: body
        })
        
        if (response.failed) {
            handleErrorResponse(response, `Failed to enable 2FA. ${response.status}`)
            return
        }

        if (response.code.ok) {
            if (auth.principal) auth.principal.mfaTotpStatus = true
            phase++
        } else {
            const json = response.json()
            const error = json.message
            handleError(`Status ${response.status} when confirming 2FA.`, error || `Failed to enable 2FA.`)
        }
    }

    function enable_three_finish() {
        if (!newTotp) return
        dialogOpen = false
    }

    /**
     * Disabling functions
     */

    async function disable_init() {
        dialogOpen = true
    }

    async function disable_one_submit() {
        const totpValidation = Validator.totp(totpInput?.toString())
        if (totpValidation) {
            toast.error(totpValidation)
            return
        }

        const response = await safeFetch(`/api/v1/user/mfa/disable/confirm`, {
            body: formData({ totp: totpInput })
        })
        if (response.failed) {
            handleErrorResponse(response, `Failed to enable 2FA. ${response.status}`)
            return
        }

        if (response.code.ok) {
            if (auth.principal) auth.principal.mfaTotpStatus = false
            phase++
        } else {
            const json = response.json()
            const error = json.message
            handleError(`Status ${response.status} when disabling 2FA.`, error || `Failed to disable 2FA.`)
        }
    }
    
</script>


<svelte:head>
    <title>{pageTitle("Preferences")}</title>
</svelte:head>


<div class="flex flex-col gap-4">
    <div class="flex flex-col gap-2">
        <h3 class="font-medium">Two-factor authentication</h3>
        <div class="flex items-center gap-2 my-1">
            <div class={`w-3 h-3 rounded-full ${auth.principal?.mfaTotpStatus ? 'bg-green-500' : 'bg-neutral-500'}`}></div>
            <p class="text-base font-medium">
                {auth.principal?.mfaTotpStatus ? '2FA enabled' : '2FA disabled'}
            </p>
        </div>
        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            When enabled, you will have to enter a code generated on your phone when logging in.
        </p>
    </div>
    
    <button
        on:click={toggleMfa} 
        class="mt-2 w-fit px-4 py-2 bg-neutral-300 dark:bg-neutral-700 hover:bg-neutral-400 dark:hover:bg-neutral-600 rounded-md">
        {auth.principal?.mfaTotpStatus ? 'Disable' : 'Enable'} 2FA
    </button>
</div>

<!-- Dialog element for enabling 2FA -->

{#if auth.principal?.mfaTotpStatus === false}
    <CustomDialog bind:isOpen={dialogOpen} classes="w-[35rem] h-[40rem]">
        <div class="size-full flex flex-col items-center px-4 py-4 gap-8 overflow-hidden">
            {#if newTotp}
                {#if phase === 1}
                    <h2 class="text-2xl">Set up 2FA</h2>

                    <div class="flex flex-col gap-4 w-full items-center">
                        <img src={qrCodeBase64} alt="2FA QR code" class="aspect-square w-[20rem] max-w-full">
                        <p class="break-all">{newTotp.secret}</p>
                    </div>

                    <div class="w-full flex gap-10 items-center justify-center h-[4rem] mt-auto">
                        <button on:click={cancel} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                            Cancel
                        </button>
                        <button on:click={enable_one_next} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                            Next
                            <ChevronRightIcon classes="h-[1rem] my-auto" />
                        </button>
                    </div>
                {:else if phase === 2}
                    <h2 class="text-2xl">Confirm 2FA</h2>

                    <div class="flex flex-col gap-2">
                        <label for="input-totp" class="">Enter the 6-digit code</label>
                        <input use:autofocus id="input-totp" class="!rounded-lg w-[10rem] max-w-full" type="number" max="999999" bind:value={totpInput}>
                    </div>

                    <div class="w-full flex gap-10 items-center justify-center h-[4rem] mt-auto">
                        <button on:click={goBack} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                            <ChevronLeftIcon classes="h-[1rem] my-auto" />
                            Back
                        </button>
                        <button on:click={enable_two_confirm} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                            Confirm
                            <ChevronRightIcon classes="h-[1rem] my-auto" />
                        </button>
                    </div>
                {:else if phase === 3}
                    <h2 class="text-2xl">Backup Codes</h2>

                    <div class="flex flex-col gap-8 w-full items-center">
                        <p class="text-sm text-neutral-700 dark:text-neutral-300 text-center">
                            2FA was enabled.<br>Save these backup codes in a secure location. You can use them to access your account if you lose access to your 2FA.
                        </p>
                        <div class="grid grid-cols-2 gap-3 w-full max-w-[20rem]">
                            {#each newTotp.codes as code}
                                <div class="bg-neutral-100 dark:bg-neutral-800 px-3 py-2 rounded-md text-center font-mono text-sm border">
                                    {code}
                                </div>
                            {/each}
                        </div>
                    </div>

                    <div class="w-full flex gap-10 items-center justify-center h-[4rem] mt-auto">
                        <button on:click={goBack} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                            <ChevronLeftIcon classes="h-[1rem] my-auto" />
                            Back
                        </button>
                        <button use:disabledFor={6000} on:click={enable_three_finish} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800 disabled:opacity-50">
                            Continue
                            <ChevronRightIcon classes="h-[1rem] my-auto" />
                        </button>
                    </div>
                {/if}
            {:else}
                <Loader class="m-auto"></Loader>
            {/if}
        </div>
    </CustomDialog>
{:else if auth.principal?.mfaTotpStatus === true}
    <CustomDialog bind:isOpen={dialogOpen} classes="w-fit max-w-[32rem] h-[25rem]">
        <div class="size-full flex flex-col px-4 py-4 gap-8 overflow-hidden">
            {#if phase === 1}
                <h2 class="text-2xl">Disable 2FA</h2>

                <div class="flex flex-col gap-6">
                    <label for="input-totp" class="">Enter the 6-digit code to disable 2FA:</label>
                    <input use:autofocus id="input-totp" class="!rounded-lg w-[10rem] max-w-full" type="number" max="999999" bind:value={totpInput}>
                </div>

                <div class="flex gap-6 mt-auto">
                    <button on:click={cancel} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                        Cancel
                    </button>
                    <button on:click={disable_one_submit} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                        Disable 2FA
                    </button>
                </div>
            {/if}
        </div>
    </CustomDialog>
{/if}


