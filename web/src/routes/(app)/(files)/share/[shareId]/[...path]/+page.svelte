<script lang="ts">
    import { page } from "$app/state";
    import type { StateMetadata } from "$lib/code/stateObjects/filesState.svelte";
    import { explicitEffect, formData, handleErr, handleException, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import FilesPage from "../../../files/[...path]/+page.svelte"
    
    let shareId = $derived(page.params.shareId)
    let passwordStatus: boolean | null | undefined = $state(undefined)

    let passwordInput = $state('')
    let shareToken: string | null = $state(null)
    let loading = $state(false)

    const meta: StateMetadata | undefined = $derived.by(() => {
        if (!shareId || passwordStatus == null) return undefined

        return {
            isFiles: false,
            isSharedFiles: true,
            isAccessibleFiles: false,
            fileEntriesUrlPath: "/api/v1/folder/file-and-folder-entries",
            shareId: shareId,
            shareToken: shareToken ?? shareId,
            pagePath: `/share/${shareId}`,
            pageTitle: "Shared file"
        }
    })

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
</script>

{#if shareId && meta}
    {#if passwordStatus === false || shareToken}
        <FilesPage meta={meta}></FilesPage>
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
{:else}
    <p>Shared file link is invalid.</p>
{/if}