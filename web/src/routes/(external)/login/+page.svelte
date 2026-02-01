<script lang="ts">
    import { goto } from "$app/navigation";
    import type { TotpMfaCredentials } from "$lib/code/auth/types";
    import { formData,  handleErr,  parseJson,  safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import MfaSetupDialog from "$lib/component/auth/MfaSetupDialog.svelte";
    import { toast } from "@jill64/svelte-toast";
    import QRCode from 'qrcode'

    let running = $state(false)
    let phase: "login" | "totp" | "setup-mfa" = $state("login")

    // Phase Login
    let usernameInput = $state("")
    let passwordInput = $state("")

    // Phase Totp
    let totpInput = $state("")

    // 2FA setup
    let mfaCredentials: TotpMfaCredentials | null = $state(null)
    let mfaSetupOpen = $state(false)
    let mfaSetupPhase = $state(1)
    let mfaQrCodeBase64: string | null = $state(null)
    let mfaSetupTotpInput: string | undefined = $state()

    $effect(() => {
        if (!mfaCredentials || !mfaCredentials.url) {
            mfaQrCodeBase64 = null
            return
        }
        QRCode.toDataURL(mfaCredentials.url).then((qrCodeImage) => {
            mfaQrCodeBase64 = qrCodeImage
        })
    })

    async function submit_password(preventNavigation: boolean = false) {
        if (running) return
        running = true

        try {
            const passwordValid = Validator.password(passwordInput)
            if (passwordValid) return toast.error(passwordValid)

            const usernameValid = Validator.emailOrUsername(usernameInput)
            if (usernameValid) return toast.error(usernameValid)

            const body = new FormData()
            body.append("username", usernameInput)
            body.append("password", passwordInput)

            if (phase === "setup-mfa") {
                const totpValidation = Validator.totp(mfaSetupTotpInput)
                if (totpValidation) return toast.error(totpValidation)

                body.append("totp", mfaSetupTotpInput!.toString())
                body.append("mfa-codes", JSON.stringify(mfaCredentials!.codes))
            }

            const response = await safeFetch(`/api/v1/auth/login/initiate`, { method: "POST", body: body })
            if (response.failed) {
                handleErr({
                    description: response.exception,
                    notification: "Failed to log in. (Local error)"
                })
                return
            }
            const status = response.code
            const json = response.json()
            const messageJson = parseJson(json?.message)

            if (status.raw === 403 && json.error === "mfa-enforced" && messageJson) {
                mfaCredentials = messageJson
                phase = "setup-mfa"
                mfaSetupOpen = true
                return
            } else if (status.failed) {
                handleErr({
                    description: "Failed to log in.",
                    notification: json.message || "Failed to log in.",
                    isServerDown: status.serverDown
                })
                return
            }


            const loginStatus = response.content as "ok" | "mfa-totp"
            if (loginStatus === "ok") {
                if (!preventNavigation) await goto("/")
                mfaSetupPhase++
            } else if (loginStatus === "mfa-totp") {
                phase = "totp"
            }
        } finally {
            running = false
        }
    }

    async function submit_totp() {
        if (running) return
        running = true

        try {
            const totpValidation = Validator.totp(totpInput)
            if (totpValidation) return toast.error(totpValidation)

            const response = await safeFetch(`/api/v1/auth/login/verify-totp-mfa`, {
                body: formData({ "totp": totpInput })
            })
            if (response.failed) {
                console.log(response.exception)
                toast.error("Failed to verify 2FA. (Local error)")
                return
            }
            const status = response.code

            if (status.ok) {
                await goto(`/`)
            } else if (status.failed) {
                const json = response.json()
                const error = json.message
                toast.error(error || `Failed to verify 2FA.`)
            }
        } finally {
            running = false
        }
    }

    function finishMfaSetup() {
        goto(`/`)
    }

    function cancelMfaSetup() {
        mfaSetupOpen = false
        phase = "login"
        totpInput = ""
        mfaCredentials = null
        mfaSetupPhase = 1
        mfaQrCodeBase64 = null
        mfaSetupTotpInput = ""
    }
    $inspect(phase)
</script>


<div class="page flex-col items-center gap-12 pt-12">
    <h1>Login</h1>

    {#if phase === "login" || phase === "setup-mfa"}
        <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={() => { submit_password() }} title="Login to Filemat">
            <label for="username-input">Email or username</label>
            <input type="text" bind:value={usernameInput} minlength="1" maxlength="256" required title="Enter email or username" id="username-input" class="basic-input" autocomplete="username" autofocus>

            <label for="password-input">Password</label>
            <input type="password" bind:value={passwordInput} minlength="4" maxlength="256" required title="Enter your password" id="password-input" class="basic-input" autocomplete="current-password">

            <button type="submit" class="basic-input-button">{running ? "..." : "Login"}</button>
        </form>

        {#if phase === "setup-mfa" && mfaQrCodeBase64}
            <MfaSetupDialog
                bind:isOpen={mfaSetupOpen}
                credentials={mfaCredentials!}
                bind:phase={mfaSetupPhase}
                qrCodeBase64={mfaQrCodeBase64}
                onCancel={cancelMfaSetup}
                onSubmit={() => { submit_password(true) }}
                onFinish={finishMfaSetup}
                bind:totpInput={mfaSetupTotpInput}
            />
        {/if}
    {:else if phase === "totp"}
        <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={submit_totp} title="Login to Filemat">
            <label for="password-input">2FA code</label>
            <input type="text" inputmode="numeric" bind:value={totpInput} minlength="6" maxlength="6" required title="Enter 2FA code" id="totp-input" class="basic-input" placeholder="000 000">

            <button type="submit" class="basic-input-button">{running ? "..." : "Login"}</button>
        </form>
    {/if}
</div>