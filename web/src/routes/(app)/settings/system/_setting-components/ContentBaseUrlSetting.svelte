<script lang="ts">
    import { fetchState } from "$lib/code/state/stateFetcher"
    import { adminSystemState, fetchAdminSystemState } from "$lib/code/state/adminSystemFetcher.svelte"
    import { sensitiveAuth } from "$lib/code/state/sensitiveAuth.svelte"
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte"
    import { envVars } from "$lib/code/data/environmentVariables"
    import { formatDuration, formData, handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte"
    import { credentialsForUrl, joinWithContentBase } from "$lib/code/util/contentUrl"
    import CodeChunk from "$lib/component/CodeChunk.svelte"
    import { toast } from "@jill64/svelte-toast"

    let urlInput = $state(``)
    let loading = $state(false)
    let pinging = $state(false)
    let savingUnauth = $state(false)

    const urlLocked = $derived(adminSystemState.contentBaseUrl.lockedByEnv)
    const unauthLocked = $derived(adminSystemState.contentBaseUrl.forUnauthenticatedLockedByEnv)
    const inputDisabled = $derived(urlLocked || !sensitiveAuth.verified || loading)

    $effect(() => {
        urlInput = adminSystemState.contentBaseUrl.url
    })

    function openLogin() {
        sensitiveAuth.open(`Enter the authentication code to change the content base URL.`)
    }

    async function saveUrl() {
        if (!sensitiveAuth.code || urlLocked) return
        const conf = await confirmDialogState.show({
            title: `Change content base URL?`,
            message: `File downloads, uploads, and thumbnails will use this prefix. Leave empty to use the app origin.`,
            cancelText: `Cancel`,
            confirmText: `Save`,
        })
        if (!conf) return

        if (loading) return
        loading = true
        const response = await safeFetch(`/api/v1/admin/system/set/content-base-url`, {
            body: formData({ auth_code: sensitiveAuth.code, url: urlInput }),
        })
        loading = false
        if (response.failed) {
            handleErr({ notification: `Failed to update content base URL.` })
            return
        }
        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: `Failed to update content base URL.`,
                notification: json.message || `Failed to update content base URL.`,
                isServerDown: response.code.serverDown,
            })
            return
        }

        await fetchAdminSystemState()
        await fetchState()
        toast.success(`Content base URL was saved.`)
    }

    async function onUnauthCheckboxClick() {
        if (!sensitiveAuth.verified || unauthLocked || savingUnauth) return
        await saveUnauthToggle(!adminSystemState.contentBaseUrl.forUnauthenticated)
    }

    async function saveUnauthToggle(enabled: boolean) {
        if (!sensitiveAuth.code || unauthLocked) return
        const conf = await confirmDialogState.show({
            title: enabled ? `Send content URL to unauthenticated users?` : `Stop sending content URL to unauthenticated users?`,
            message: enabled
                ? `Visitors without a login, including public share pages, will receive the content base URL.`
                : `Visitors without a login will keep using the app origin. Logged-in users still use the content URL, including on shared files.`,
            cancelText: `Cancel`,
            confirmText: `Save`,
        })
        if (!conf) return

        if (savingUnauth) return
        savingUnauth = true
        const response = await safeFetch(`/api/v1/admin/system/set/content-base-url-for-unauthenticated`, {
            body: formData({ auth_code: sensitiveAuth.code, enabled: enabled.toString() }),
        })
        savingUnauth = false
        if (response.failed) {
            handleErr({ notification: `Failed to update unauthenticated content URL setting.` })
            return
        }
        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: `Failed to update unauthenticated content URL setting.`,
                notification: json.message || `Failed to update unauthenticated content URL setting.`,
                isServerDown: response.code.serverDown,
            })
            return
        }

        await fetchAdminSystemState()
        await fetchState()
    }

    async function ping() {
        if (!urlInput || pinging) return
        pinging = true
        try {
            const url = joinWithContentBase(urlInput, `/api/v1/state/select`)
            const response = await fetch(url, {
                method: `POST`,
                credentials: credentialsForUrl(url),
            })
            toast.success(`Host responded (${response.status}).`)
        } catch {
            toast.error(`No response from that URL.`)
        } finally {
            pinging = false
        }
    }
</script>

<div class="flex flex-col gap-4 w-full">
    <div class="flex flex-col gap-2">
        <h3 class="font-medium">Override server URL for heavy file operations</h3>
        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            Absolute URL prefix for downloads, uploads, and thumbnails.<br>Can be used as a bypass if the app is proxied.
        </p>
        {#if sensitiveAuth.verified && sensitiveAuth.remainingSeconds}
            <p class="text-sm text-neutral-600 dark:text-neutral-400">
                You can change this setting for the next {formatDuration(sensitiveAuth.remainingSeconds)}.
            </p>
        {/if}
    </div>

    <div class="flex flex-wrap items-center gap-2 w-full">
        <input
            bind:value={urlInput}
            class="basic-input w-[45rem] max-w-full"
            disabled={inputDisabled}
            placeholder="https://123.123.123.123:8080"
        >
        {#if !urlLocked}
            {#if sensitiveAuth.verified}
                <button
                    on:click={saveUrl}
                    disabled={loading}
                    class="basic-button bg-surface-content-button! disabled:opacity-50"
                >
                    Save
                </button>
            {:else}
                <button
                    on:click={openLogin}
                    disabled={sensitiveAuth.loading}
                    class="basic-button bg-surface-content-button! disabled:opacity-50"
                >
                    Change
                </button>
            {/if}
        {/if}
        <button
            on:click={ping}
            disabled={!urlInput || pinging}
            class="basic-button disabled:opacity-50"
        >
            {pinging ? `Pinging...` : `Ping`}
        </button>
    </div>

    {#if urlLocked}
        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            This value is owned by the <CodeChunk>{envVars.FM_CONTENT_BASE_URL}</CodeChunk> environment variable.
        </p>
    {/if}

    <div class="flex flex-col gap-2">
        <div class="flex items-center gap-2 my-1">
            <input
                id="content-url-unauth-toggle"
                type="checkbox"
                class="!size-5"
                checked={adminSystemState.contentBaseUrl.forUnauthenticated}
                disabled={!sensitiveAuth.verified || unauthLocked || savingUnauth}
                on:click|preventDefault={onUnauthCheckboxClick}
            >
            <label for="content-url-unauth-toggle" class="text-base font-medium">
                Provide URL to unauthenticated users
            </label>
        </div>
        {#if unauthLocked}
            <p class="text-sm text-neutral-600 dark:text-neutral-400">
                This toggle is owned by the <CodeChunk>{envVars.FM_CONTENT_BASE_URL_FOR_UNAUTHENTICATED}</CodeChunk> environment variable.
            </p>
        {/if}
    </div>
</div>
