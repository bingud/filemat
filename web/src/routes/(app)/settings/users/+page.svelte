<script lang="ts">
    import type { PublicUser } from "$lib/code/auth/types";
    import { formatUnixTimestamp, handleError, handleErrorResponse, isServerDown, pageTitle, parseJson, safeFetch } from "$lib/code/util/codeUtil.svelte";
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


<div class="page px-4">
    {#if users}
        <div class="w-full overflow-y-auto scrollbar">
            <table class="w-full max-w-fit">
                <thead>
                    <tr class="dark:bg-neutral-900 text-left">
                        <th class="py-2 px-4">Email</th>
                        <th class="py-2 px-4">Username</th>
                        <th class="py-2 px-4">MFA Status</th>
                        <th class="py-2 px-4">Created Date</th>
                        <th class="py-2 px-4">Last Login</th>
                        <th class="py-2 px-4">Banned</th>
                        <th class="py-2 px-4">User ID</th>
                    </tr>
                </thead>
                <tbody>
                    {#each users as user, index}
                        <tr class="{index % 2 === 0 ? 'bg-neutral-200 dark:bg-neutral-800' : 'bg-neutral-100 dark:bg-neutral-900'} whitespace-nowrap">
                            <td class="py-2 px-4">{user.email}</td>
                            <td class="py-2 px-4">{user.username}</td>
                            <td class="py-2 px-4">{user.mfaTotpStatus ? 'Enabled' : 'Disabled'}</td>
                            <td class="py-2 px-4">{formatUnixTimestamp(user.createdDate)}</td>
                            <td class="py-2 px-4">
                                {user.lastLoginDate 
                                    ? formatUnixTimestamp(user.lastLoginDate) 
                                    : 'N/A'}
                            </td>
                            <td class="py-2 px-4">{user.isBanned ? 'Yes' : 'No'}</td>
                            <td class="py-2 px-4">{user.userId}</td>
                        </tr>
                    {/each}
                </tbody>
            </table>
        </div>
    {/if}
</div>