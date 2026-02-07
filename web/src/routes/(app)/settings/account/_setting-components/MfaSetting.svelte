<script lang="ts">
    import { autofocus, disabledFor } from "$lib/code/util/uiUtil";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { explicitEffect, formData, handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import QRCode from 'qrcode'
    import CustomDialog from "$lib/component/popover/CustomDialog.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { toast } from "@jill64/svelte-toast";
    import MfaSetupDialog from "$lib/component/auth/MfaSetupDialog.svelte";
    import type { TotpMfaCredentials } from "$lib/code/auth/types";

    let credentials: TotpMfaCredentials | null = $state(null)
    let qrCodeBase64: string | null = $state(null) 

    let dialogOpen = $state(false)
    let phase = $state(1)
    let previousMfaStatus: boolean | null = $state(null)

    let totpInput: string | undefined = $state()

    // QR code generation
    $effect(() => {
        if (!credentials || !credentials.url) {
            qrCodeBase64 = null
            return
        }
        QRCode.toDataURL(credentials.url).then((qrCodeImage) => {
            qrCodeBase64 = qrCodeImage
        })
    })

    // Data clearing on close
    explicitEffect(() => [
        dialogOpen
    ], () => {
        if (!dialogOpen) {
            credentials = null
            qrCodeBase64
            phase = 1
            totpInput = undefined
        }
    })

    function cancel() { dialogOpen = false } // Resetting values done by effect

    // Cancel operation if 2FA is toggled from elsewhere
    explicitEffect(() => [ 
        auth.principal?.mfaTotpStatus
    ], () => {
        const status = auth.principal?.mfaTotpStatus || null
        if (previousMfaStatus !== status) {
            cancel()
            previousMfaStatus = status
        }
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
            handleErr({
                description: `Failed to enable 2FA.`,
                notification: response.exception,
            })
            return
        }

        const json = response.json()
        if (response.code.failed) {
            handleErr({
                description: `Failed to enable 2FA.`,
                notification: json?.message || `Failed to enable 2FA.`,
                isServerDown: response.code.serverDown
            })
            return
        }

        if (!json) return
        credentials = json
    }

    async function enable_two_confirm() {
        if (!credentials) return
        const totpValidation = Validator.totp(totpInput?.toString())
        if (totpValidation) {
            toast.error(totpValidation)
            return
        }

        const body = formData({ totp: totpInput, codes: JSON.stringify(credentials.codes) })
        const response = await safeFetch(`/api/v1/user/mfa/enable/confirm`, {
            body: body
        })
        
        if (response.failed) {
            handleErr({
                description: `Failed to enable 2FA.`,
                notification: `Failed to enable 2FA. ${response.status}`
            })
            return
        }

        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: `Status ${response.status} when confirming 2FA.`,
                notification: json.message || `Failed to enable 2FA.`,
                isServerDown: response.code.serverDown
            })
            return
        }

        if (auth.principal) auth.principal.mfaTotpStatus = true
        phase++
    }

    function enable_three_finish() {
        if (!credentials) return
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
            handleErr({
                description: `Failed to disable 2FA.`,
                notification: `Failed to disable 2FA. ${response.status}`
            })
            return
        }

        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: `Status ${response.status} when disabling 2FA.`,
                notification: json.message || `Failed to disable 2FA.`,
                isServerDown: response.code.serverDown
            })
            return
        }

        if (auth.principal) auth.principal.mfaTotpStatus = false
        phase++
    }
    
</script>


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
        class="mt-2 w-fit px-4 py-2 bg-surface-content-button rounded-md">
        {auth.principal?.mfaTotpStatus ? 'Disable' : 'Enable'} 2FA
    </button>
</div>

<!-- Dialog element for enabling 2FA -->
{#if auth.principal?.mfaTotpStatus === false}
    {#if credentials && qrCodeBase64}
        <MfaSetupDialog
            bind:isOpen={dialogOpen}
            credentials={credentials}
            bind:phase={phase}
            qrCodeBase64={qrCodeBase64}
            onCancel={cancel}
            onSubmit={enable_two_confirm}
            onFinish={enable_three_finish}
            bind:totpInput={totpInput}
        />
    {/if}
{:else if auth.principal?.mfaTotpStatus === true}
    <CustomDialog bind:isOpen={dialogOpen} class="w-fit! h-[25rem]!">
        <div class="size-full flex flex-col px-4 py-4 gap-8 overflow-hidden">
            {#if phase === 1}
                <h2 class="text-2xl">Disable 2FA</h2>

                <div class="flex flex-col gap-6">
                    <label for="in
                    put-totp" class="">Enter the 6-digit code to disable 2FA:</label>
                    <input use:autofocus id="input-totp" class="!rounded-lg w-[10rem] max-w-full basic-input" type="number" max="999999" bind:value={totpInput}>
                </div>

                <div class="flex gap-6 mt-auto">
                    <button on:click={cancel} class="basic-button bg-transparent">
                        Cancel
                    </button>
                    <button on:click={disable_one_submit} class="basic-button bg-surface-content-button!">
                        Disable 2FA
                    </button>
                </div>
            {/if}
        </div>
    </CustomDialog>
{/if}