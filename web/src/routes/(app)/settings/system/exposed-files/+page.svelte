<script lang="ts">
    import { sensitiveAuth } from "$lib/code/state/sensitiveAuth.svelte"
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import { doRequest, entriesOf, formatDuration, formData, handleErr, handleException, safeFetch } from "$lib/code/util/codeUtil.svelte"
    import { prefixSlash } from "$lib/code/util/uiUtil"
    import TrashIcon from "$lib/component/icons/TrashIcon.svelte"
    import Loader from "$lib/component/Loader.svelte"
    import { Dialog } from "bits-ui"
    import { onMount } from "svelte"

    const title = `Exposed files`

    type VisibilityMap = { [key: string]: boolean }
    let visibilities: VisibilityMap | null = $state(null)

    let loading = $state(false)

    let newFile: {
        path: string,
        isExposed: boolean,
        isDialogOpen: boolean,
        isLoading: false,
        reset: Function,
    } = $state({
        path: `/`,
        isExposed: true,
        isDialogOpen: false,
        isLoading: false,
        reset: function () {
            this.path = `/`
            this.isExposed = true
            this.isDialogOpen = false
        }
    })

    onMount(() => {
        uiState.settings.title = title
        loadVisibilities()
    })

    async function loadVisibilities() {
        const response = await safeFetch(`/api/v1/admin/system/file-visibility-entries`, { method: `GET` })
        if (response.failed) {
            visibilities = null
            handleErr({
                description: `failed to load file visibilities`,
                notification: `Failed to load list of exposed files.`,
            })
            return
        }

        const status = response.code
        const json = response.json()
        if (status.failed) {
            visibilities = null
            handleErr({
                description: `Failed to load file visibilities`,
                notification: json.message || `Failed to load list of exposed files.`,
                isServerDown: status.serverDown
            })
            return
        }

        visibilities = json
    }

    function openLogin() {
        sensitiveAuth.open(`Enter the authentication code to configure exposed files.`)
    }

    function openNewConfigurationDialog() {
        newFile.isDialogOpen = true
    }

    async function addNewConfiguration() {
        if (!sensitiveAuth.code) {
            openLogin()
            return
        }

        if (!visibilities) return

        if (loading) return
        loading = true
        const response = await safeFetch(`/api/v1/admin/system/add-file-visibility`, {
            body: formData({ auth_code: sensitiveAuth.code, path: newFile.path, isExposed: newFile.isExposed })
        })
        loading = false

        if (response.failed) {
            handleException(`Failed to add file visibility configuration.`, `Failed to add new file.`, response.exception)
            return
        }

        const status = response.code
        if (status.failed) {
            const json = response.json()
            handleErr({
                description: `Failed to add file visibility`,
                notification: json.message || `Failed to add new file.`,
                isServerDown: status.serverDown
            })
            return
        }

        visibilities[newFile.path] = newFile.isExposed
        newFile.reset()
    }

    async function deleteConfiguration(path: string) {
        if (!sensitiveAuth.code) {
            openLogin()
            return
        }

        if (!visibilities) return
        if (loading) return

        loading = true
        await doRequest({
            method: `POST`,
            path: `/api/v1/admin/system/remove-file-visibility`,
            body: formData({ auth_code: sensitiveAuth.code, path: path }),
            afterResponse: () => {
                loading = false
            },
            errors: {
                exception: {
                    description: `Failed to remove file visibility configuration.`,
                    notification: `Failed to remove file.`
                },
                failed: {
                    description: `Failed to remove file visibility configuration.`,
                    notification: `Failed to remove file.`
                }
            }
        })

        delete visibilities[path]
    }
</script>


<div class="page settings-margin flex-col gap-8">
    <div class="flex flex-col gap-2">
        <p>Configure which files are accessible in Filemat.</p>
        <p class="opacity-50">To configure file visibility, you must enter a code from application console/logs or a special file.</p>

        {#if sensitiveAuth.verified && sensitiveAuth.remainingSeconds}
            <p class="p-4 rounded-lg bg-neutral-300 dark:bg-neutral-800 my-4">You can change exposed files for the next {formatDuration(sensitiveAuth.remainingSeconds)}.</p>
            <button on:click={openNewConfigurationDialog} class="basic-button">Add new file</button>
        {:else}
            <button class="basic-button" on:click={openLogin}>Configure files</button>
        {/if}
    </div>

    {#if visibilities}
        <table class="table-auto w-full border-collapse">
            <!-- <thead>
                <tr>
                    <th class="text-left p-2">Path</th>
                    <th class="text-left p-2">Visibility</th>
                    <th class="text-left p-2">Delete</th>
                </tr>
            </thead> -->
            <tbody>
                {#each entriesOf(visibilities) as [path, isExposed]}
                    <tr class="border-b border-neutral-700">
                        <td class="p-2">{path}</td>
                        <td class="p-2 flex items-center gap-2">
                            {#if isExposed}
                                <span class="text-green-500">⬤</span>
                                Exposed
                            {:else}
                                <span class="text-red-500">⬤</span>
                                Hidden
                            {/if}
                        </td>

                        <!-- Delete button -->
                        <td class="ho">
                            <button
                                title="Delete this file configuration"
                                class="flex items-center justify-center rounded-lg p-2 my-1 hover:bg-neutral-300 hover:dark:bg-neutral-800 hover:fill-red-500"
                                on:click={() => { deleteConfiguration(path) }}
                            >
                                <div class="h-[1.2rem]">
                                    <TrashIcon></TrashIcon>
                                </div>
                            </button>
                        </td>
                    </tr>
                {:else}
                    <tr>
                        <td colspan="2" class="p-6 text-center">
                            No files are configured.
                        </td>
                    </tr>
                {/each}
            </tbody>
        </table>
    {:else}
        <Loader class="m-auto"></Loader>
    {/if}
</div>

<!-- New file configuration dialog -->
<Dialog.Root bind:open={newFile.isDialogOpen}>
    <Dialog.Portal>
        <Dialog.Overlay
            class="fixed inset-0 z-50 bg-black/50"
        />
        <Dialog.Content>
            <div class="rounded-lg bg-neutral-50 dark:bg-neutral-900 shadow-popover fixed left-[50%] top-[50%] z-50 w-[30rem] max-w-[calc(100%-2rem)] translate-x-[-50%] translate-y-[-50%] p-8 flex flex-col gap-8">
                <p>Configure a new file</p>
                <form on:submit={addNewConfiguration} class="flex flex-col w-full gap-6">
                    <fieldset class="contents" disabled={newFile.isLoading}>
                        <div class="flex flex-col gap-2 w-full">
                            <label for="path-input">Full path:</label>
                            <input id="path-input" required minlength="1" bind:value={newFile.path} use:prefixSlash class="basic-input">
                        </div>

                        <!-- Visibility switcher -->
                        <div class="flex flex-col items-center select-none gap-2">
                            <p>Visibility:</p>
                            <div class="flex h-[2rem] rounded-lg overflow-hidden">
                                <button
                                    type="button"
                                    disabled={newFile.isLoading}
                                    class="h-full w-1/2 px-4 rounded-l-lg bg-neutral-300 dark:bg-neutral-800
                                        {newFile.isExposed
                                            ? 'inset-ring-2 inset-ring-blue-500 bg-neutral-400/50 dark:bg-neutral-600'
                                            : ''}"
                                    aria-pressed={newFile.isExposed}
                                    on:click={() => newFile.isExposed = true}
                                >
                                    Exposed
                                </button>
                                <button
                                    type="button"
                                    disabled={newFile.isLoading}
                                    class="h-full w-1/2 px-4 rounded-r-lg bg-neutral-300 dark:bg-neutral-800
                                        {!newFile.isExposed
                                            ? 'inset-ring-2 inset-ring-blue-500 bg-neutral-400/50 dark:bg-neutral-600'
                                            : ''}"
                                    aria-pressed={newFile.isExposed === false}
                                    on:click={() => newFile.isExposed = false}
                                >
                                    Hidden
                                </button>
                            </div>
                        </div>

                        <button type="submit" class="basic-input-button">{#if !newFile.isLoading}Add file{:else}...{/if}</button>
                    </fieldset>
                </form>
            </div>
        </Dialog.Content>
    </Dialog.Portal>
</Dialog.Root>
