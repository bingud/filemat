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
        shareMeta
    ], () => {
        if (shareMeta && shareMeta.shareId === shareId) return
        if (passwordStatus == null) return
        if (!shareToken) return

        loadShareMetadata(shareToken)
    })

    async function loadPasswordStatus(shareId: string): Promise<boolean | null> {
        const response = await safeFetch(`/api/v1/file/share/get-password-status`, {
            body: formData({ shareId: shareId })
        })
        if (response.failed) {
            handleException(
                `Failed to fetch password status of file share.`,
                `Failed to check if this file has a password.`,
                response.exception
            )
            return null
        }

        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: `Failed to fetch password status of file share.`,
                notification: json.message || `Failed to check if this file has a password.`,
                isServerDown: response.code.serverDown
            })
            return null
        }

        const text = response.content
        const status = text === `true` ? true : false
        if (status === false) {
            shareToken = shareId
        }

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
            handleException(
                `Failed to login to shared file.`,
                `Failed to verify password.`,
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
            handleException(
                `Failed to load shared file metadata.`,
                `Failed to load shared file metadata.`,
                response.exception
            )
            return null
        }

        const json = response.json()
        if (response.code.failed) {
            errorMessage = json.message || `Failed to load shared file metadata.`
            handleErr({
                description: `Failed to load shared file metadata.`,
                notification: json.message || `Failed to load shared file metadata.`,
                isServerDown: response.code.serverDown
            })
            return null
        }

        errorMessage = ""
        shareMeta = json
    }
</script>

{#if shareId && pageMeta}
    {#if passwordStatus === false || shareToken}
        <FilesPage meta={pageMeta}></FilesPage>
    {:else if passwordStatus === true}
        <div class="page flex-col items-center justify-center">
            <form on:submit={submit_login} class="flex flex-col gap-4">
                <div class="flex flex-col gap2">
                    <label for="password">File Password</label>
                    <input bind:value={passwordInput} id="password" type="password" class="basic-input">
                </div>
                
                <button type="submit" class="basic-button w-full">Open file</button>
            </form>
        </div>
    {:else if passwordStatus === null}
        <p>Failed to check if this file has a password.</p>
    {/if}
{:else if errorMessage}
    <div class="page flex items-center justify-center">
        <p class="text-lg">{errorMessage}</p>
    </div>    
{:else if !pageMeta}
    <div class="page flex items-center justify-center">
        <Loader></Loader>
    </div>
{:else}
    <p>Shared file link is invalid.</p>
{/if}