<script lang="ts">
    import { goto } from "$app/navigation";
    import { parseJson, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { toast } from "@jill64/svelte-toast";


    let running = $state(false)
    let phase = $state(1)

    let usernameInput = $state("")
    let passwordInput = $state("")

    async function submit_2() {
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

            const response = await safeFetch(`/api/v1/auth/login`, { method: "POST", body: body })
            if (response.failed) {
                console.log(response.exception)
                toast.error("Failed to log in. (Local error)")
                return
            }
            const text = await response.text()
            const json = parseJson(text)
            const status = response.status

            if (status === 200) {
                await goto("/")
            } else {
                if (json.message) {
                    toast.error(json.message)
                } else {
                    toast.error("Failed to log in. Server provided no details.")
                }
            }
        } finally {
            running = false
        }
    }
</script>


<div class="page items-center gap-12 pt-12">
    <h1>Login</h1>

    <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={submit_2} title="Login to Filemat">
        <label for="username-input">Email or username</label>
        <input type="text" bind:value={usernameInput} minlength="1" maxlength="256" required title="Enter email or username" id="username-input" class="">

        <label for="password-input">Password</label>
        <input type="password" bind:value={passwordInput} minlength="4" maxlength="256" required title="Enter your password" id="password-input" class="">

        <button type="submit" class="tw-form-button">{running ? "..." : "Login"}</button>
    </form>
</div>