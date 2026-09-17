<script lang="ts">
    import type { FilePermission } from "$lib/code/auth/types";
    import { filePermissionMeta } from "$lib/code/data/permissions";
    import type { ulid } from "$lib/code/types/types";
    import { filterObject, formData, handleErr, keysOf, mapToObject, safeFetch, valuesOf } from "$lib/code/util/codeUtil.svelte";
    import RoleIcon from "$lib/component/icons/RoleIcon.svelte";
    import UserIcon from "$lib/component/icons/UserIcon.svelte";
    import { untrack } from "svelte";
    import type { EntityPermissionMeta } from "../../_code/fileUtilities";
    import AllowDenySwitch from "./AllowDenySwitch.svelte";


    let { editedPermission, onPermissionUpdated }: {
        editedPermission: EntityPermissionMeta,
        onPermissionUpdated: (id: ulid, newPermissions: FilePermission[] | null, deleted: boolean) => any
    } = $props()

    const username = untrack(() => editedPermission.username)
    const role = untrack(() => editedPermission.role)
    const perm = untrack(() => editedPermission.permission)

    const permissions = valuesOf(filePermissionMeta)
    let selectedPermissions = $state(mapToObject(permissions, (v) => { 
        return { key: v.id, value: perm.permissions.includes(v.id) }
    }))

    let allValue = $derived.by(() => {
        const values = permissions.map((p) => selectedPermissions[p.id])
        if (values.every((v) => v === true)) return true
        if (values.every((v) => v === false)) return false
        return null
    })

    function setAll(allowed: boolean) {
        for (const permission of permissions) {
            selectedPermissions[permission.id] = allowed
        }
    }

    let loading = $state(false)
    let deleting = $state(false)

    async function editPermission() {
        if (loading) return
        loading = true

        try {
            const permissionList = keysOf(filterObject(selectedPermissions, (k, v) => v))
            const response = await safeFetch(`/api/v1/permission/update-entity`, {
                body: formData({
                    permissionId: perm.permissionId,
                    newPermissionList: JSON.stringify(permissionList)
                })
            })
            if (response.failed) {
                handleErr({
                    description: `Failed to update file permission`,
                    notification: `Failed to update permission.`,
                })
                return
            }
            const status = response.code
            const json = await response.json()

            if (status.notFound) {
                handleErr({
                    description: `File not found when updating permission`,
                    notification: `This file was not found.`
                })
                return
            } else if (status.failed) {
                handleErr({
                    description: `Failed to update permission.`,
                    notification: json.message || `Failed to update permission.`,
                    isServerDown: status.serverDown
                })
                return
            }

            onPermissionUpdated(perm.permissionId, permissionList, false)
        } finally {
            loading = false
        }
    }

    async function deletePermission() {
        if (deleting) return
        deleting = true

        try {
            const response = await safeFetch(`/api/v1/permission/delete-entity`, { body: formData({ permissionId: perm.permissionId }) })
            if (response.failed) {
                handleErr({
                    description: `Failed to delete file permission`,
                    notification: `Failed to delete permission.`,
                })
                return
            }
            const status = response.code
            const json = await response.json()

            if (status.notFound) {
                handleErr({
                    description: `File not found when deleting permission`,
                    notification: `This file was not found.`
                })
            } else if (status.failed) {
                handleErr({
                    description: `Failed to delete permission.`,
                    notification: json.message || `Failed to delete permission.`,
                    isServerDown: status.serverDown
                })
            } else {
                onPermissionUpdated(perm.permissionId, null, true)
            }
        } finally {
            deleting = false
        }
    }

</script>


<div class="w-full flex flex-col gap-4">
    <div class="p-4 rounded-lg w-full bg-surface-content-button flex items-center gap-3">
        <div class="aspect-square h-[1.2rem]">
            {#if perm.permissionType === "ROLE"}
                <RoleIcon></RoleIcon>
            {:else}
                <UserIcon></UserIcon>
            {/if}
        </div>
        <p>{username ?? role!.name}</p>
    </div>

    <p>Permissions:</p>
    <div class="flex flex-col gap-2 select-none mb-2">
        <div class="flex items-center gap-4 px-1 py-1">
            <AllowDenySwitch value={allValue} onchange={setAll} />
            <span>All</span>
        </div>

        <hr class="basic-hr">

        {#each permissions as permission (permission.id)}
            <div class="flex items-center gap-4 px-1 py-1">
                <AllowDenySwitch
                    value={selectedPermissions[permission.id]}
                    onchange={(allowed) => {
                        selectedPermissions[permission.id] = allowed
                    }}
                />
                <span>{permission.name}</span>
            </div>
        {/each}
    </div>

    <button disabled={loading} onclick={editPermission} class="w-full rounded-lg py-2 mt-2 bg-surface-content-button disabled:opacity-50">{#if !loading}Update permission{:else}Updating...{/if}</button>

    <hr class="basic-hr">
    
    <button disabled={deleting} onclick={deletePermission} class="w-full rounded-lg py-2 bg-surface-content-button hover:ring-2 hover:ring-red-500 disabled:opacity-50">{#if !deleting}Delete permission{:else}Deleting...{/if}</button>
</div>
