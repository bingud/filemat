<script lang="ts">
    import type { PublicUser } from "$lib/code/auth/types";
    import { handleError, handleErrorResponse, isServerDown, pageTitle, parseJson, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";

    let users: PublicUser[] | null = $state(null)

    onMount(() => {
        loadUserList()
    })


    async function loadUserList() {
        const response = await safeFetch(`/api/v1/admin/user/list`, { method: "POST", credentials: "same-origin" })
        if (response.failed) {
            handleError(response.exception, `Failed to load list of users.`)
            users = null
            return
        }
        const text = await response.text()
        const status = response.status
        const json = parseJson(text)

        if (status === 200) {
            if (json) {
                users = json
            }
        } else if (isServerDown(status)) {
            handleError(`Server ${status} when fetching user list.`, "The server is unavailable.")
        } else {
            handleErrorResponse(json, `Failed to load the list of all users. (${status})`)
        }
    }

</script>


<svelte:head>
    <title>{pageTitle("User Settings")}</title>
</svelte:head>


<div class="page">
    {#if users}
        <ul class="flex flex-col w-full py-2">
            {#each users as user}
                <li class="flex w-full items-center justify-around rounded bg-neutral-200 dark:bg-neutral-900 py-2 px-4">
                    <p>{user.username}</p>
                    <p>{user.email}</p>
                </li>
            {/each}
        </ul>
    {/if}
</div>