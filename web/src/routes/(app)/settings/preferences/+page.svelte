<script lang="ts">
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { handleErrorResponse, pageTitle, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";
    import QRCode from 'qrcode'
    import CustomDialog from "$lib/component/CustomDialog.svelte";
    import ChevronLeftIcon from "$lib/component/icons/ChevronLeftIcon.svelte";
    import ChevronRightIcon from "$lib/component/icons/ChevronRightIcon.svelte";

    const title = "Preferences"
    let newTotp: { secret: string, url: string, codes: string[] } | null = $state(null)
    let qrCodeBase64: string | null = $state(null) 

    let dialogOpen = $state(false)
    let phase = $state(1)

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

    onMount(() => {
        uiState.settings.title = title
        toggleMfa()
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
        if (!json) return
        newTotp = json
    }

    function enable_two_next() {
        phase++
    }

    function enable_three_confirm() {
    }

    function goBack() {
        phase--
    }
    
</script>


<svelte:head>
    <title>{pageTitle("Preferences")}</title>
</svelte:head>


<div class="page flex-col gap-8">
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-850">
        <h2 class="text-lg font-medium">Account Settings</h2>
        
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
    </div>
</div>


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
                    <button on:click={enable_two_next} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                        Next
                        <ChevronRightIcon classes="h-[1rem] my-auto" />
                    </button>
                </div>
            {:else if phase === 2}
                <h2 class="text-2xl">Confirm 2FA</h2>

                <div>
                    <input >
                </div>

                <div class="w-full flex gap-10 items-center justify-center h-[4rem] mt-auto">
                    <button on:click={enable_two_next} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                        Confirm
                        <ChevronRightIcon classes="h-[1rem] my-auto" />
                    </button>
                    <button on:click={goBack} class="rounded-lg bg-neutral-900 border border-neutral-700 px-6 py-3 flex gap-2 hover:bg-neutral-800">
                        <ChevronLeftIcon classes="h-[1rem] my-auto" />
                        Back
                    </button>
                </div>
            {/if}
        {/if}
    </div>
</CustomDialog>