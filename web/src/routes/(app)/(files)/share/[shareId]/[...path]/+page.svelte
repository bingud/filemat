<script lang="ts">
    import { page } from "$app/state";
    import type { StateMetadata } from "$lib/code/stateObjects/filesState.svelte";
    import { explicitEffect, formData, handleErr, handleException, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import FilesPage from "../../../files/[...path]/+page.svelte"
    
    let shareId = $derived(page.params.shareId)
    let passwordStatus: boolean | null | undefined = $state(undefined)
    let shareMeta: { shareId: string, topLevelFilename: string } | null = $state(null)

    let passwordInput = $state('')
    let shareToken: string | null = $state(null)
    let loading = $state(false)

    let errorMessage = $state("")

    const pageMeta: StateMetadata | undefined = $derived.by(() => {
        if (!shareId || passwordStatus == null || !shareMeta) return undefined

        console.log(`Load page metadata`)
        return {
            type: "shared",
            fileEntriesUrlPath: "/api/v1/folder/file-and-folder-entries",
            shareId: shareId,
            pagePath: `/share/${shareId}`,
            pageTitle: "Shared file",
            isArrayOnly: false,
            shareToken: shareToken ?? shareId,
            shareTopLevelFilename: shareMeta.topLevelFilename,
        }
    })

    // Load share data
    // When share ID changes
    explicitEffect(() => [shareId], () => {
        passwordInput = ''
        passwordStatus = undefined
        shareToken = null
        shareMeta = null
        errorMessage = ""

        const id = shareId
        if (!id) return

        loadPasswordStatus(id)
            .then((result) => {
                if (shareId !== id) return

                passwordStatus = result
            })
    })

    explicitEffect(() => [
        shareId,
        passwordStatus,
        shareMeta,
        shareToken
    ], () => {
        if (shareMeta && shareMeta.shareId === shareId) return
        if (passwordStatus == null) return
        if (!shareToken) return

        console.log(`Load share metadata`)
        loadShareMetadata(shareToken)
    })

    async function loadPasswordStatus(shareId: string): Promise<boolean | null> {
        console.log(`Load password status`)
        
        const response = await safeFetch(`/api/v1/file/share/get-password-status`, {
            body: formData({ shareId: shareId })
        })

        const errorText = `Failed to check if this file has a password.`
        if (response.failed) {
            handleException(errorText, null,response.exception)
            errorMessage = errorText
            return null
        }

        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: errorText,
                notification: json.message || undefined,
                isServerDown: response.code.serverDown
            })
            errorMessage = errorText
            return null
        }

        const text = response.content
        const status = text === `true` ? true : false
        if (status === false) {
            shareToken = shareId
        }

        errorMessage = ""
        console.log(`Loaded password status`)
        return status
    }

    async function submit_login() {
        if (loading) return
        if (!passwordInput) return

        const response = await safeFetch(`/api/v1/file/share/login`, {
            body: formData({ shareId: shareId, password: passwordInput })
        })
        loading = false
        if (response.failed) {
            errorMessage = `Failed to verify password.`
            handleException(
                `Failed to login to shared file.`,
                errorMessage,
                response.exception
            )
            return null
        }

        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: `Failed to login to shared file.`,
                notification: json.message || `Failed to verify password.`,
                isServerDown: response.code.serverDown
            })
            return null
        }

        shareToken = response.content
        errorMessage = ""
        console.log(`Share login successful`)
    }

    async function loadShareMetadata(token: string) {
        if (!shareToken || loading) return

        loading = true
        const response = await safeFetch(`/api/v1/file/share/get-metadata`, {
            body: formData({ shareToken: token })
        })
        loading = false
        
        if (shareToken !== token) return

        if (response.failed) {
            errorMessage = `Failed to load shared file metadata.`
            handleErr({
                notification: errorMessage,
                exception: response.exception
            })
            return null
        }

        const json = response.json()
        if (response.code.failed) {
            errorMessage = json.message || `Failed to load shared file metadata.`
            handleErr({
                description: `Failed to load shared file metadata.`,
                notification: errorMessage,
                isServerDown: response.code.serverDown
            })
            return null
        }

        errorMessage = ""
        shareMeta = json
        console.log(`Share metadata loaded`)
    }
</script>

{#if errorMessage}
    <div class="page flex flex-col items-center justify-center gap-4">
        <p class="text-lg">{errorMessage}</p>
        <button on:click={() => { window.location.reload() }} class="basic-button w-full">Reload</button>
    </div>
{:else if passwordStatus == null || (shareToken && !pageMeta)}
    <div class="page flex items-center justify-center">
        <Loader></Loader>
    </div>
{:else if shareId}
    {#if (passwordStatus === false || shareToken) && pageMeta}
        <FilesPage meta={pageMeta}></FilesPage>
    {:else if passwordStatus === true}
        <div class="page flex-col items-center justify-center">
            <form on:submit={submit_login} class="flex flex-col gap-4">
                <div class="flex flex-col gap-2">
                    <label for="password">File Password</label>

                    <input id="chrome-stfu" name="username" value="" autocomplete="username" class="fixed size-0 invisible" disabled>
                    <input bind:value={passwordInput} id="password" type="password" class="basic-input" autocomplete="current-password">
                </div>
                
                <button type="submit" class="basic-button w-full">Open file</button>
            </form>
        </div>
    {/if}
{:else}
    <p>Shared file link is invalid.</p>
{/if}