<script lang="ts">
    import { page } from "$app/state";
    import { PermissionType, type MiniUser, type Role, type RoleMeta } from "$lib/code/auth/types";
    import { formatPermission, getPermissionInfo } from "$lib/code/data/permissions";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import type { ulid } from "$lib/code/types";
    import { formatUnixTimestamp, formData, handleError, handleErrorResponse, handleException, pageTitle, parseJson, safeFetch, toStatus } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte";
    import { fade } from "svelte/transition";

    // undefined loading, null failed
    let role = $state(null) as RoleMeta & { miniUsers: MiniUser[] } | null
    let status: "loading" | "failed" | "ready" | undefined = $state()
    let title = $derived(role ? `Manage role: ${role.name}` : "Manage role")

    onMount(() => {
        uiState.settings.title = title
    })

    $effect(() => {
        const roleId = page.params.roleId
        if (!Validator.isUlidValid(roleId)) {
            role = null
            status = "failed"
            return
        }

        status = "loading"
        loadRoleData(roleId)
    })

    async function loadRoleData(id: string) {
        const response = await safeFetch(`/api/v1/admin/role/get`, { method: "POST", credentials: "same-origin", body: formData({ roleId: id }) })
        if (response.failed) {
            role = null
            status = "failed"
            handleException(`Failed to fetch role data.`, `Failed to load role.`, response.exception)
            return
        }
        const httpStatus = toStatus(response.status)
        const text = await response.text()
        const json = parseJson(text)

        if (httpStatus.ok) {
            if (json.userIds) {
                const miniUsers = await loadMiniUsers(json.userIds)
                json.miniUsers = miniUsers
            }

            role = json
            status = "ready"
        } else if (httpStatus.serverDown) {
            handleError(`Server ${httpStatus} while fetching role data`, `Server is unavailable.`)
            role = null
            status = "failed"
        } else {
            handleErrorResponse(json, `Failed to load role.`)
            role = null
            status = "failed"
        }
    }

    async function loadMiniUsers(userIds: ulid[]): Promise<MiniUser[] | null> {
        const response = await safeFetch(`/api/v1/admin/user/minilist`, 
            { method: "POST", credentials: "same-origin", body: formData({ userIdList: JSON.stringify(userIds) }) }
        )
        if (response.failed) {
            handleException(`Failed to fetch list of user mini metadata: ${status}`, "Failed to load users with this role.", response.exception)
            return null
        }
        const st = toStatus(response.status)
        const text = await response.text()
        const json = parseJson(text)

        if (st.ok) {
            return json
        } else if (st.serverDown) {
            handleError(`Server ${st} while fetching yser mini metadata`, `Server is unavailable.`)
            return null
        } else {
            handleErrorResponse(json, `Failed to load users with this role.`)
            return null
        }
    }
</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


{#if role}
    <div in:fade={{duration: 70}} class="page flex-col gap-8">
        <div class="flex flex-col gap-4 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-800/50">
            <h1 class="text-lg">{role.name}</h1>
            <p class="dark:text-neutral-300">Created on: {formatUnixTimestamp(role.createdDate)}</p>
            {#if auth.systemRoleIds?.admin === role.roleId}
                <p>This is a system role that was created automatically. It has all available permissions.</p>
            {:else if auth.systemRoleIds?.user === role.roleId}
                <p>This is a system role that was created automatically. Every user has this role.</p>
            {/if}
        </div>

        <div class="flex flex-col gap-4">
            {#if role.miniUsers}
                <h2 class="text-lg">Users with this role:</h2>
                {#if role.roleId !== auth.systemRoleIds?.user}
                    <div class="flex gap-3 flex-wrap w-full">
                        {#each role.miniUsers as mini}
                            <a href="/settings/users/{mini.userId}" title={mini.userId} class="p-3 rounded bg-neutral-200 dark:bg-neutral-900 hover:text-blue-400 hover:underline">{mini.username}</a>
                        {/each}
                    </div>
                {:else}
                    <a href="/settings/users" class="p-3 rounded bg-neutral-200 dark:bg-neutral-900 hover:text-blue-400 hover:underline w-fit">All users have this role</a>
                {/if}
            {:else}
                <p class="p-6 bg-neutral-300 dark:bg-neutral-800">Failed to load users with this role.</p>
            {/if}
        </div>

        <div class="flex flex-col gap-4">
            <h2 class="text-lg">Permissions:</h2>
            <div class="flex gap-3 flex-wrap">
                {#each role.permissions as permission}
                    {@const meta = getPermissionInfo(permission)}
                    {#if meta.type !== PermissionType.file}
                        <span title={meta.description} class="px-2 py-1 bg-neutral-300 dark:bg-neutral-700/40 rounded w-fit">
                            {meta.name}
                        </span>
                    {/if}
                {:else}
                    <p class="text-sm text-neutral-300 p-2 rounded bg-neutral-900">No permissions</p>
                {/each}
            </div>
        </div>
    </div>
{:else if status === "failed"}
    <div class="py-4">
        <p class="p-6 rounded bg-neutral-300 dark:bg-neutral-800">Failed to load role data.</p>
    </div>
{:else if status === "loading"}
    <div in:fade={{duration: 400}} class="center">
        <Loader></Loader>
    </div>
{/if}


<style>
    @import "/src/app.css" reference;

</style>