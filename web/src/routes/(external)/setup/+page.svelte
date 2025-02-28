<script lang="ts">
    import Noindex from "$lib/component/head/Noindex.svelte"
    import CodeChunk from "$lib/component/CodeChunk.svelte"
    import { toast } from "@jill64/svelte-toast";
    import { handleException, parseJson, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { onMount } from "svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { fetchSensitiveFolderList } from "$lib/code/data/sensitiveFolders";
    import { appState } from "$lib/code/state/appState.svelte";
    import { pushState } from "$app/navigation";
    import { page } from "$app/state";

    let alreadySetup: boolean | null = $state(null)
    let running = $state(false)
    let phase = $state(3)
    page.state.popupPhase = null

    // Section 1
    let codeInput = $state("")
    let codeInputValid = $derived(codeInput.length === 12)

    // Section 2
    let emailInput = $state("")
    let usernameInput = $state("")
    let passwordInput = $state("")
    let repeatPasswordInput = $state("")

    // Section 3
    let exposedFolders: { path: string, isExposed: boolean }[] = $state([])

    onMount(async () => {
        await getSetupStatus()
       
        let i = 25
        while (i--) {
            exposedFolders.push({path: "", isExposed: true})
        }
    })

    function increasePhase() { phase++ }
    function decreasePhase() { phase-- }

    function closePopup() { history.back() }
    function openPopup(newPopupPhase: typeof page.state.popupPhase) {
        pushState('', {
            popupPhase: newPopupPhase
        })
    }
    
    async function submit_1() {
        if (running) return
        running = true
        
        try {
            const codeValid = Validator.setupCode(codeInput)
            if (codeValid) return toast.error(codeValid)

            const body = new FormData()
            body.append("setup-code", codeInput)

            const response = await safeFetch(`/api/v1/setup/verify`, { method: "POST", body: body })
            if (response.failed) {
                console.log("Failed to verify setup code" + response.exception)
                toast.error(`Failed to verify code. (Local issue)`)
            }
            const status = response.status
            const text = await response.text()

            if (status === 200) {
                increasePhase()
            } else {
                const json = parseJson(text)
                if (json?.message) {
                    toast.error(json.message)
                } else {
                    toast.error(`Failed to verify setup code. Server provided no details. (${status})`)
                }
            }
        } finally {
            running = false
        }
    }

    async function submit_2() {
        if (running) return
        running = true

        try {
            const emailValid = Validator.email(emailInput)
            if (emailValid) return toast.error(emailValid)

            const usernameValid = Validator.username(usernameInput)
            if (usernameValid) return toast.error(usernameValid)

            const passwordValid = Validator.password(passwordInput)
            if (passwordValid) return toast.error(passwordValid)
            if (passwordInput !== repeatPasswordInput) return toast.error("Passwords do not match.")

            const body = new FormData()
            body.append("email", emailInput)
            body.append("password", passwordInput)
            body.append("username", usernameInput)
            body.append("setup-code", codeInput)

            const response = await safeFetch(`/api/v1/setup/submit`, { method: "POST", body: body })
            if (response.failed) {
                console.log(response.exception)
                toast.error("Failed to setup. (Local issue)")
                return
            }
            const status = response.status
            const text = await response.text()

            if (status === 200) {
                increasePhase()
            } else {
                const json = parseJson(text)
                if (json?.error === "setup-code-invalid") {
                    toast.error(json.message)
                    decreasePhase()
                    return
                }

                if (json?.message) {
                    toast.error(json.message)
                } else {
                    toast.error(`Failed to setup. Server provided no details. (${status})`)
                }
            }
        } finally {
            running = false
        }
    }

    async function getSetupStatus() {
        try {
            const response = await fetch(`/api/v1/setup/status`, { method: "GET" })
            const text = await response.text()
            if (text === "true") {
                alreadySetup = true 
            } else if (text === "false") {
                alreadySetup = false
            } else {
                throw "Server provided invalid response for setup status."
            }
        } catch (e) {
            handleException("Failed to fetch setup status boolean", "Failed to check if Filemat is already set up.", e)
        }
    }

    let openSensitiveInfoRunning = false
    async function openSensitiveFolderInfo() {
        if (openSensitiveInfoRunning) return
        openSensitiveInfoRunning = true
        try {
            openPopup("sensitive-folders")
            await fetchSensitiveFolderList()
        } finally {
            openSensitiveInfoRunning = false
        }
    }

    function addExposedFolder() {
        exposedFolders.push({ path: '', isExposed: true })
    }
</script>


<svelte:head>
    <Noindex />
</svelte:head>

<div class="page items-center gap-12 pt-6 md:pt-12">
    {#if alreadySetup === false}
        {#if page.state.popupPhase == null}
            {#if phase === 1}
                <div class="flex flex-col items-center gap-6">
                    <h1 class="">Welcome to Filemat</h1>
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
                    <button type="submit" class="tw-form-button" disabled={!codeInputValid}>{running ? "..." : "Continue"}</button>
                </form>

            {:else if phase === 2}
                <div class="flex flex-col items-center gap-6">
                    <h1 class="text-2xl font">Create an admin account</h1>
                </div>
                
                
                <form class="flex flex-col gap-2 w-[15rem]" on:submit|preventDefault={submit_2} title="Create a Filemat admin account">
                    <label for="email-input">Email</label>
                    <input type="email" bind:value={emailInput} minlength="3" maxlength="256" required title="Enter your email" id="email-input" class="">

                    <label for="username-input">Username</label>
                    <input type="text" bind:value={usernameInput} minlength="1" maxlength="48" required title="Enter your username" id="username-input" class="">

                    <label for="password-input">Password</label>
                    <input type="password" bind:value={passwordInput} minlength="1" maxlength="256" required title="Enter your password" id="password-input" class="">

                    <label for="repeat-password-input">Repeat Password</label>
                    <input type="password" bind:value={repeatPasswordInput} minlength="1" maxlength="256" required title="Repeat your password" id="repeat-password-input" class="">

                    <button type="submit" class="tw-form-button">{running ? "..." : "Continue"}</button>
                </form>
            {:else if phase === 3}
                <div class="flex flex-col items-center gap-2 shrink-0">
                    <h1 class="text-2xl font">Select exposed folders</h1>
                    <p>Configure which folders will show up in Filemat</p>
                </div>

                <div class="max-w-full flex-grow flex flex-col gap-4 items-center overflow-y-hidden py-4 bg-neutral-900/50">
                    <div class="flex flex-col flex-grow max-w-full gap-2 overflow-y-auto desktop-scrollbar px-6">
                        {#each exposedFolders as folder, index}
                            <div>
                                <input bind:value={folder.path}>
                            </div>
                        {/each}
                    </div>
                    <button on:click={addExposedFolder} class="tw-form-button w-fit shrink-0">Add folder</button>
                </div>

                <div class="flex flex-col items-center gap-6 mt-auto shrink-0 pb-4 md:pb-8">
                    <div class="flex flex-col">
                        <div class="flex items-center gap-2">
                            <input id="hide-sensitive-folders-checkbox" type="checkbox">
                            <label for="hide-sensitive-folders-checkbox">Hide sensitive folders</label>
                        </div>
                        <button on:click={openSensitiveFolderInfo}><span class="underline">Learn more</span> about sensitive folders</button>
                    </div>
                    <button class="tw-form-button">Confirm</button>
                </div>
            {:else if phase === 4}
                <div class="flex flex-col items-center gap-6">
                    <h1 class="text-2xl font">Filemat was set up.</h1>
                    <a href="/" class="tw-form-button">Continue</a>
                </div>
            {:else}
                <div class="loader"></div>
            {/if}
        {:else if page.state.popupPhase  === "sensitive-folders"}
            <div class="flex flex-col gap-6 shrink-0 max-w-full w-[30rem]">
                <h1 class="mx-auto">Sensitive folders</h1>
                <p>When automatic hiding of sensitive folders is enabled, these folders will never show up in Filemat, unless you explicitly configure them to be exposed.</p>
                <p>The <CodeChunk>*</CodeChunk> in the following paths is a wildcard.</p>
            </div>

            {#if appState.sensitiveFolders}
                <div class="flex-grow overflow-y-scroll flex flex-col gap-2 max-w-full">
                    {#each appState.sensitiveFolders as folder}
                        <div>
                            {folder}
                        </div>
                    {/each}
                </div>
            {:else}
                <Loader />
            {/if}
            
            <div class="shrink-0 mt-auto">
                <button on:click={closePopup} title="Go back to setup" class="max-w-full w-[10rem] tw-form-button">Go back</button>
            </div>
        {/if}
    {:else if alreadySetup === true}
        <div class="flex flex-col gap-6 items-center">
            <h1 class="text-2xl">Filemat has already been set up.</h1>
            <a href="/" class="underline">Go to Filemat</a>
        </div>
    {:else}
        <Loader class="m-auto" />
    {/if}
</div>
