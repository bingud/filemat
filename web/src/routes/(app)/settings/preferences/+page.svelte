<script lang="ts">
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { explicitEffect, formData, handleError, handleErrorResponse, pageTitle, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount, type Component } from "svelte";
    import QRCode from 'qrcode'
    import CustomDialog from "$lib/component/CustomDialog.svelte";
    import ChevronLeftIcon from "$lib/component/icons/ChevronLeftIcon.svelte";
    import ChevronRightIcon from "$lib/component/icons/ChevronRightIcon.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { toast } from "@jill64/svelte-toast";
    import { autofocus, disabledFor } from "$lib/code/util/uiUtil";
    import Loader from "$lib/component/Loader.svelte";
    import MfaSetting from "./_setting-components/MfaSetting.svelte";
    import LogoutSetting from "./_setting-components/LogoutSetting.svelte";

    const title = "Preferences"
    let newTotp: { secret: string, url: string, codes: string[] } | null = $state(null)
    let qrCodeBase64: string | null = $state(null) 

    let dialogOpen = $state(false)
    let phase = $state(1)

    let confirmTotpInput: number | undefined = $state()

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
            confirmTotpInput = undefined
        }
    }, () => [dialogOpen])

    onMount(() => {
        uiState.settings.title = title
    })

    function toggleMfa() {
        if (auth.principal?.mfaTotpStatus == true) {
            
        } else {
            enable_one_requestTotpSecret()
        }
    }

    async function enable_one_requestTotpSecret() {
        phase = 1
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
        const totpValidation = Validator.totp(confirmTotpInput?.toString())
        if (totpValidation) {
            toast.error(totpValidation)
            return
        }

        const body = formData({ totp: `${confirmTotpInput}`, codes: JSON.stringify(newTotp.codes) })
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

    function goBack() {
        phase--
    }
    function cancel() { dialogOpen = false }
    
</script>


<svelte:head>
    <title>{pageTitle("Preferences")}</title>
</svelte:head>


<div class="page flex-col gap-8">
    <h2 class="text-lg font-medium">Account Settings</h2>

    {@render settingCell(MfaSetting)}
    <LogoutSetting></LogoutSetting>
</div>

{#snippet settingCell(Component: Component<any>)}
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-850">
        <Component></Component>
    </div>
{/snippet}