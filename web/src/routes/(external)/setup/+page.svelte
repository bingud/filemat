<script lang="ts">
    import Noindex from "$lib/component/head/Noindex.svelte"
    import CodeChunk from "$lib/component/CodeChunk.svelte"
    import { toast } from "@jill64/svelte-toast";
    import { handleException, isBlank, pageTitle, parseJson, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { onMount } from "svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { fetchSensitiveFolderList } from "$lib/code/data/sensitiveFolders";
    import { appState } from "$lib/code/state/appState.svelte";
    import { pushState } from "$app/navigation";
    import { page } from "$app/state";
    import Close from "$lib/component/icons/Close.svelte";
    import Checkmark from "$lib/component/icons/Checkmark.svelte";
    import { dev } from "$app/environment";
    import { envVars } from "$lib/code/data/environmentVariables";

    let alreadySetup: boolean | null = $state(null)
    let running = $state(false)
    let phase = $state(1)
    let lastPhase = 1
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
        if (dev) {
            // codeInput = "aaaaaaaaaaaa"
            // emailInput = "a@a.a"
            // usernameInput = "a"
            // passwordInput = "aaaa"
            // repeatPasswordInput = "aaaa"
            // phase = 3
        }
    })

    function increasePhase() { lastPhase = phase; phase++ }
    function decreasePhase() { lastPhase = phase; phase-- }
    function setPhase(newPhase: typeof phase) { lastPhase = phase; phase = newPhase }

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
                if (lastPhase !== 1) {
                    setPhase(lastPhase)
                } else {
                    increasePhase()
                }
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
            
            increasePhase()
        } finally {
            running = false
        }
    }

    async function submit_3() {
        // if (running) return
        // running = true

        try {
            await submit_finish()
        } finally {
            // running = false
        }
    }

    async function submit_finish() {
        if (running) return
        running = true

        try {
            const serializedFolderVisibilities = JSON.stringify(exposedFolders.filter((v) => !isBlank(v.path)))
            
            const body = new FormData()
            body.append("email", emailInput)
            body.append("password", passwordInput)
            body.append("username", usernameInput)
            body.append("folder-visibility-list", serializedFolderVisibilities)
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
                    setPhase(1)
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
    <title>{pageTitle("Setup")}</title>
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
                
                <div class="flex flex-col gap-2 shrink-0 max-w-[min(100%,32rem)] w-fit">
                    <p class="">If a folder is visible, then it will show up in Filemat. <br class="max-sm:hidden">If it is hidden, it will be fully blocked, as if it didn't exist. <br class="max-sm:hidden">All folders are hidden by default.</p>

                    <div class="">
                        <p>Some sensitive system folders are blocked by default, and cannot be unblocked from the web UI.</p>
                        <button on:click={openSensitiveFolderInfo} class="hover:text-blue-400"><span class="underline">Click here</span> to learn about blocking sensitive system folders.</button>
                    </div>
                </div>

                <div class="w-[35rem] max-w-full h-fit max-h-svh sm:max-h-fit sm:flex-grow flex flex-col gap-8 items-center sm:overflow-y-hidden py-12 border-y-2 border-neutral-900">
                    <div class="flex flex-col sm:flex-grow h-fit max-h-full sm:max-h-fit gap-2 overflow-y-auto desktop-scrollbar gutter-stable-both px-6 w-full" class:hidden={!exposedFolders || exposedFolders.length < 1}>
                        {#each exposedFolders as folder, index}
                            <div class="flex flex-col sm:flex-row items-center gap-2 shrink-0">
                                <input placeholder="Full folder path" class="sm:order-2 max-sm:w-full sm:flex-grow shrink-0" bind:value={folder.path}>
                                <div class="flex items-center justify-between w-full sm:contents">
                                    <button on:click={() => { exposedFolders.splice(index, 1) }} title="Remove this folder" class="sm:order-1 flex items-center justify-center w-[1.5rem] hover:bg-neutral-800 h-full rounded opacity-60">
                                        <div class="size-4">
                                            <Close />
                                        </div>
                                    </button>
                                    <button on:click={() => { folder.isExposed = !folder.isExposed }} class="sm:order-3 h-full rounded px-4 py-2 flex gap-2 bg-neutral-900 hover:bg-neutral-800 items-center w-[7rem] select-none" title={`Make the selected folder ${folder.isExposed ? 'hidden' : 'visible'} in Filemat`}>
                                        <div class="size-[1.2rem] {folder.isExposed ? 'fill-green-500' : 'fill-red-500'}">
                                            {#if folder.isExposed}
                                                <Checkmark />
                                            {:else}
                                                <Close />
                                            {/if}
                                        </div>
                                        <p>{#if folder.isExposed}Visible{:else}Hidden{/if}</p>
                                    </button>
                                </div>
                            </div>
                        {/each}
                    </div>
                    <div class="w-full px-8">
                        <button on:click={addExposedFolder} class="tw-form-button w-full shrink-0 select-none" title="Configure the visibility of another folder">Add folder</button>
                    </div>
                </div>

                <div class="flex flex-col items-center gap-6 mt-auto shrink-0 pb-4 md:pb-8">
                    <button on:click={submit_3} class="tw-form-button">{#if !running}Finish setup{:else}...{/if}</button>
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
                <p>When automatic hiding of sensitive folders is enabled, these folders will never show up in Filemat.<br>You can disable it by setting the <CodeChunk>{envVars.FM_HIDE_SENSITIVE_FOLDERS}</CodeChunk> environment variable to <CodeChunk>false</CodeChunk>.</p>
                <p>You can unblock any path so that you can expose or hide it with the web UI by using the <CodeChunk>{envVars.FM_NON_SENSITIVE_FOLDERS}</CodeChunk> environment variable.</p>
                <p>The <CodeChunk>*</CodeChunk> in the following paths is a wildcard.</p>
            </div>

            {#if appState.sensitiveFolders}
                <div class="flex-grow overflow-y-auto scrollbar flex flex-col gap-2 max-w-full">
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
