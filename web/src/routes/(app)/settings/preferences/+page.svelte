<script lang="ts">
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { handleErrorResponse, pageTitle, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";

    const title = "Preferences"
    let newTotp: { secret: string, url: string } | null = $state(null)

    onMount(() => {
        uiState.settings.title = title
    })

    async function enable_one_requestTotpSecret() {
        const response = await safeFetch(`/api/v1/user/mfa/enable/generate-secret`)
        if (response.failed) {
            handleErrorResponse(response, `Failed to enable 2FA.`)
            return
        }

        const json = response.json()
        if (!json) return

        
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
                class="mt-2 w-fit px-4 py-2 bg-neutral-300 dark:bg-neutral-700 hover:bg-neutral-400 dark:hover:bg-neutral-600 rounded-md">
                {auth.principal?.mfaTotpStatus ? 'Disable' : 'Enable'} 2FA
            </button>
        </div>
    </div>
</div>