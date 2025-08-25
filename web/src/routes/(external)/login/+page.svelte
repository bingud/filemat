<script lang="ts">
    import { goto, invalidate } from "$app/navigation";
    import { formData, handleError, handleErrorResponse, parseJson, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { toast } from "@jill64/svelte-toast";

    let running = $state(false)
    let phase: "login" | "totp" = $state("login")

    // Phase Login
    let usernameInput = $state("")
    let passwordInput = $state("")

    // Phase Totp
    let totpInput = $state("")

    async function submit_password() {
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

            const response = await safeFetch(`/api/v1/auth/login/initiate`, { method: "POST", body: body })
            if (response.failed) {
                console.log(response.exception)
                toast.error("Failed to log in. (Local error)")
                return
            }
            const status = response.code

            if (status.ok) {
                const loginStatus = response.content as "ok" | "mfa-totp"

                if (loginStatus === "ok") {
                    await goto("/")
                } else if (loginStatus === "mfa-totp") {
                    phase = "totp"
                }
            } else if (status.serverDown) {
                handleError(`Server ${status} while logging in`, "Server is unavailable.")
            } else {
                const json = response.json()
                handleErrorResponse(json, "Failed to log in.")
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
</script>


<div class="page flex-col items-center gap-12 pt-12">
    <h1>Login</h1>

    {#if phase === "login"}
        <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={submit_password} title="Login to Filemat">
            <label for="username-input">Email or username</label>
            <input type="text" bind:value={usernameInput} minlength="1" maxlength="256" required title="Enter email or username" id="username-input" class="">

            <label for="password-input">Password</label>
            <input type="password" bind:value={passwordInput} minlength="4" maxlength="256" required title="Enter your password" id="password-input" class="">

            <button type="submit" class="tw-form-button">{running ? "..." : "Login"}</button>
        </form>
    {:else if phase === "totp"}
        <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={submit_totp} title="Login to Filemat">
            <label for="password-input">2FA code</label>
            <input type="text" bind:value={totpInput} minlength="6" maxlength="6" required title="Enter 2FA code" id="totp-input" class="" placeholder="000 000">

            <button type="submit" class="tw-form-button">{running ? "..." : "Login"}</button>
        </form>
    {/if}
</div>