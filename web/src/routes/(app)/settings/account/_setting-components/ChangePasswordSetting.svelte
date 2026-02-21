<script lang="ts">
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { explicitEffect, formData, handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import CustomDialog from "$lib/component/popover/CustomDialog.svelte";
    import { toast } from "@jill64/svelte-toast";

    const phases = {
        password: { title: 'Change password', description: 'Enter the current password:' },
        totp: { title: 'Change password', description: 'Enter the 2FA code:' },
        newPassword: { title: 'Change password', description: 'Enter the new password:' },
        finished: { title: 'Password has been changed.'}
        
    } as Record<typeof phase, { title: string, description: string }>

    let isOpen = $state(false)
    let phase = $state("password") as "password" | "totp" | "newPassword" | "finished"
    let mfaEnabled = $derived(auth.principal!.mfaTotpStatus)
    let loading = $state(false)

    // INPUTS
    let currentPasswordInput = $state("")
    let newPasswordInput = $state("")

    let totpInput = $state("")
    let totpInputValidation = $derived(Validator.totp(totpInput))

    let logoutAllSessions = $state(true)

    // UI
    let title = $derived(phases[phase].title)
    let description = $derived(phases[phase].description)

    // Util functions
    function setPhase(newPhase: typeof phase) { phase = newPhase }
    function close() { isOpen = false }
    explicitEffect(() => [isOpen], () => {
        if (!isOpen) { clear() }
    })
    function clear() {
        currentPasswordInput = ""
        newPasswordInput = ""
        totpInput = ""
        logoutAllSessions = true
        setPhase("password")
    }
    
    // Functions
    function password_submit() {
        if (!currentPasswordInput) return
        setPhase("newPassword")
    }

    function totp_submit() {
        if (totpInputValidation) {
            toast.plain(totpInputValidation)
            return
        }
        
        changePassword()
    }

    function newPassword_submit() {
        if (mfaEnabled) {
            setPhase("totp")
            return
        }

        changePassword()
    }

    async function changePassword() {
        if (loading) return
        loading = true

        try {
            if (!currentPasswordInput || !newPasswordInput) return

            const body = formData({
                "current-password": currentPasswordInput,
                "new-password": newPasswordInput,
                "logout-all-sessions": logoutAllSessions.toString()
            })

            if (totpInput) {
                body.append("mfa-totp", totpInput)
            }

            const response = await safeFetch(`/api/v1/user/change-password`, { body })
            if (response.failed) {
                handleErr({
                    notification: `Failed to change password.`,
                    exception: response.exception
                })
                return
            }

            if (response.code.failed) {
                const json = response.json()
                handleErr({
                    description: `Failed to change password.`,
                    notification: json.message || `Failed to change password.`,
                    isServerDown: response.code.serverDown
                })
                return
            }

            setPhase("finished")
        } finally {
            loading = false
        }
    }

</script>


<button on:click={() => { isOpen = true }} class="basic-button">
    Change account password
</button>


<CustomDialog
    bind:isOpen={isOpen}
    class="w-[30rem]!"
    title={title}
    description={description}
>    
    {#if phase === "password"}
        <input
            bind:value={currentPasswordInput}
            placeholder="Current password"
            type="password"
            class="basic-input-light"
            autocomplete="current-password"
            maxlength="256"
            autofocus
        />
        <div class="flex justify-end gap-2">
            <button 
                on:click={close}
                class="basic-button"
            >
                Cancel
            </button>
            <button 
                on:click={password_submit} 
                disabled={!currentPasswordInput || loading}
                class="basic-button bg-surface-content-button!"
            >
                Continue
            </button>
        </div>
    {:else if phase === "totp"}
        <input
            bind:value={totpInput}
            placeholder="2FA code"
            type="text"
            class="basic-input-light"
            autocomplete="one-time-code"
            maxlength="6"
            autofocus
            disabled={loading}
        />
        <div class="flex justify-end gap-2">
            <button 
                on:click={() => { setPhase("newPassword") }}
                class="basic-button"
            >
                Back
            </button>
            <button 
                on:click={totp_submit} 
                class="basic-button bg-surface-content-button!"
            >
                {#if loading}
                    ...
                {:else}
                    Change password
                {/if}
            </button>
        </div>
    {:else if phase === "newPassword"}
        <input
            bind:value={newPasswordInput}
            placeholder="New password"
            type="password"
            class="basic-input-light"
            autocomplete="new-password"
            disabled={loading}
            autofocus
        />
        <div class="flex gap-2 items-center">
            <input bind:checked={logoutAllSessions} id="logout-all-sessions" type="checkbox" class="!size-5">
            <label for="logout-all-sessions">Log out all other sessions</label>
        </div>

        <div class="flex justify-end gap-2">
            <button 
                on:click={close}
                class="basic-button"
            >
                Cancel
            </button>
            <button 
                on:click={newPassword_submit} 
                class="basic-button bg-surface-content-button!"
            >
                {#if loading}
                    ...
                {:else}
                    {#if mfaEnabled}Continue to 2FA{:else}Change password{/if}
                {/if}
            </button>
        </div>
    {:else if phase === "finished"}
        <div class="flex justify-end gap-2">
            <button 
                on:click={close} 
                class="basic-button bg-surface-content-button!"
            >Close</button>
        </div>
    {/if}
</CustomDialog>