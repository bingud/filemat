<script lang="ts">
    import Noindex from "$lib/component/head/Noindex.svelte"
    import CodeChunk from "$lib/component/CodeChunk.svelte"
    import { toast } from "@jill64/svelte-toast";
    import { makeIdempotent } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    
    let section = $state(2)

    // Section 1
    let codeInput = $state("")
    let codeInputValid = $derived(codeInput.length === 12)

    // Section 2
    let emailInput = $state("")
    let usernameInput = $state("")
    let passwordInput = $state("")
    let repeatPasswordInput = $state("")

    const submit_1 = makeIdempotent(async (isRunning) => {
        if (isRunning) return

        const codeValid = Validator.setupCode(codeInput)
        if (codeValid) return toast.error(codeValid)

        const body = new FormData()
        body.append("setup-code", codeInput)

        const response = await fetch(`/api/v1/setup/verify`, { method: "POST", body: body })
        const status = response.status
        const text = await response.text()

        if (status === 200) {
            section++
        } else {
            toast.error(text)
        }
    })

    const submit_2 = makeIdempotent(async (isRunning) => {
        if (isRunning) return

        const emailValid = Validator.email(emailInput)
        if (emailValid) return toast.error(emailValid)

        const passwordValid = Validator.password(passwordInput)
        if (passwordValid) return toast.error(passwordValid)
        if (passwordInput !== repeatPasswordInput) return toast.error(passwordValid)

        const usernameValid = Validator.username(usernameInput)
        if (usernameValid) return toast.error(usernameValid)

        
    })
</script>


<svelte:head>
    <Noindex />
</svelte:head>


<div class="page items-center gap-12 pt-12">
    {#if section === 1}
        <div class="flex flex-col items-center gap-6">
            <h1 class="text-2xl font">Welcome to Filemat</h1>
        </div>
        
        <div class="flex flex-col gap-2">
            <p>To set up Filemat, please enter the setup code.</p>
            <div>
                <p>You can find it in these places:</p>
                <ol class="list-decimal list-inside marker:text-blue-700 dark:marker:text-blue-400">
                    <li>The console or logs of the application</li>
                    <li>The file <br class="xs:hidden"><CodeChunk>/var/lib/filemat/setup-code.txt</CodeChunk></li>
                </ol>
            </div>
        </div>
        
        <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={submit_1}>
            <input placeholder="Setup code (12 letters)" type="text" bind:value={codeInput} minlength="12" maxlength="12" required title="Enter the generated code." id="code-input" class="">
            <button type="submit" class="w-full py-2 bg-neutral-200 dark:bg-neutral-900 hover:bg-blue-300 dark:hover:bg-blue-800 disabled:pointer-events-none disabled:opacity-50" disabled={!codeInputValid}>Continue</button>
        </form>

    {:else if section === 2}
        <div class="flex flex-col items-center gap-6">
            <h1 class="text-2xl font">Create an admin account</h1>
        </div>
        
        <div class="flex flex-col gap-2">
            <!-- <p>To set up Filemat, please enter the setup code.</p> -->
            <!-- <div>
                <p>You can find it in these places:</p>
                <ol class="list-decimal list-inside marker:text-blue-700 dark:marker:text-blue-400">
                    <li>The console or logs of the application</li>
                    <li>The file <br class="xs:hidden"><CodeChunk>/var/lib/filemat/setup-code.txt</CodeChunk></li>
                </ol>
            </div> -->
        </div>
        
        <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={submit_1} title="Create a Filemat admin account">
            <label for="email-input">Email</label>
            <input type="email" bind:value={emailInput} minlength="3" maxlength="256" required title="Enter your email" id="email-input" class="">

            <label for="username-input">Username</label>
            <input type="text" bind:value={usernameInput} minlength="1" maxlength="48" required title="Enter your username" id="username-input" class="">

            <label for="password-input">Password</label>
            <input type="password" bind:value={passwordInput} minlength="1" maxlength="256" required title="Enter your password" id="password-input" class="">

            <label for="repeat-password-input">Repeat Password</label>
            <input type="password" bind:value={repeatPasswordInput} minlength="1" maxlength="256" required title="Repeat your password" id="repeat-password-input" class="">

            <button type="submit" class="w-full py-2 bg-neutral-200 dark:bg-neutral-900 hover:bg-blue-300 dark:hover:bg-blue-800 disabled:pointer-events-none disabled:opacity-50">Continue</button>
        </form>
    {/if}
</div>