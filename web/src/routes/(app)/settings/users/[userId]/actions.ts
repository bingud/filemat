import { addRoleToUser } from "$lib/code/admin/roles"
import type { FullPublicUser } from "$lib/code/auth/types"
import { rolesToPermissions } from "$lib/code/module/permissions"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { auth } from "$lib/code/stateObjects/authState.svelte"
import { confirmDialogState, inputDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte"
import type { ulid } from "$lib/code/types/types"
import { formData, handleErr, handleException, lockFunction, removeString, safeFetch } from "$lib/code/util/codeUtil.svelte"
import { getRole } from "$lib/code/util/stateUtils"
import { toast } from "@jill64/svelte-toast"
import { userPageState as state } from "./state.svelte"


export async function removeSelectedRoles() {
    if (state.removingRoles) return
    state.removingRoles = true
    try {
        if (state.selectedRoles.length < 1 || !state.selectingRoles || !state.user) return

        if (auth.principal!.userId === state.user.userId) {
            if (state.selectedRoles.includes(appState.systemRoleIds!.admin)) {
                if (!confirm("Are you sure you want to remove your own admin role?")) return
            }

            const roles = state.selectedRoles.map(v => getRole(v)!)
            const permissions = rolesToPermissions(roles).map(v => v.id)

            if (permissions.includes("SUPER_ADMIN")) {
                if (!confirm("Are you sure you want to remove your own super admin role?")) return
            }
        }

        const userId = state.user.userId
        const body = formData({ userId: userId, roleIdList: JSON.stringify(state.selectedRoles) })
        const response = await safeFetch(`/api/v1/admin/user-role/remove`, { body: body })
        if (response.failed) {
            handleErr({
                description: `Failed to remove selected roles from user`,
                notification: `Failed to remove selected roles.`,
            })
            return
        }
        const json = response.json()
        const status = response.code

        if (status.notFound) {
            handleErr({
                description: `User not found when removing roles`,
                notification: `This user was not found.`
            })
            return
        } else if (status.failed) {
            handleErr({
                description: `Failed to remove selected roles.`,
                notification: json.message || `Failed to remove selected roles.`,
                isServerDown: status.serverDown
            })
            return
        }

        const removedRoles = json as ulid[]
        if (state.user && state.user.userId === userId) {
            removedRoles.forEach((roleId) => {
                removeString(state.user!.roles, roleId)
            })
        }

        const removedRolesDifference = state.selectedRoles.length - removedRoles.length
        if (removedRolesDifference > 0) {
            handleErr({
                description: `Failed to remove ${removedRolesDifference} roles from user`,
                notification: `Failed to remove ${removedRolesDifference} ${removedRolesDifference === 1 ? "role" : "roles"}.`
            })
        }

        toggleRoleSelection()
    } finally {
        state.removingRoles = false
    }
}

export function toggleRoleSelection() {
    state.selectedRoles = []
    state.selectingRoles = !state.selectingRoles
}

export function selectRole(id: ulid) {
    const selectedRoles = state.selectedRoles
    
    if (!selectedRoles.includes(id)) {
        selectedRoles.push(id)
    } else {
        removeString(selectedRoles, id)
    }
}

export async function loadUser(userId: ulid) {
    const body = formData({ userId: userId })
    const response = await safeFetch(`/api/v1/admin/user/get`, { method: "POST", body: body, credentials: "same-origin" })
    if (response.failed) {
        handleErr({
            description: `Failed to fetch user by user id`,
            notification: `Failed to load user data.`,
        })
        return
    }
    const status = response.code
    const json = response.json()

    if (status.notFound) {
        handleErr({
            description: `User not found`,
            notification: `This user was not found.`
        })
        return
    } else if (status.failed) {
        handleErr({
            description: `Failed to load user data.`,
            notification: json.message || `Failed to load user data.`,
            isServerDown: status.serverDown
        })
        state.user = null
        return
    }
    
    state.user = json as FullPublicUser
}

export const assignRole = lockFunction(async (roleId: ulid) => {
    const user = state.user
    if (!user) return

    const result = await addRoleToUser(user.userId, roleId)
    if (result === true) {
        if (user) {
            user.roles.push(roleId)

            if (auth.principal!.userId === user.userId) {
                auth.principal!.roles.push(roleId)
            }
        }
    }
})

export async function changeUserPassword(user: typeof state.user) {
    if (!user) return

    const rawPassword = await inputDialogState.show({
        title: "Change password",
        message: `Input the new password for user '${user.username}'`,
        confirmText: "Change password",
        cancelText: "Cancel"
    })
    if (!rawPassword) return

    const logout = await confirmDialogState.show({
        title: "Log out user?",
        message: "Should all user's devices be logged out?"
    })

    const response = await safeFetch(`/api/v1/admin/user/chnage-password`, { 
        body: formData({ "userId": user.userId, "logout": logout, password: rawPassword })
    })
    if (response.failed) {
        handleException(`Failed to change user account password.`, `Failed to change password.`, response.exception)
        return
    }

    const json = response.json()
    if (response.code.failed) {
        handleErr({
            description: "Failed to change user account password" + response.status,
            notification: json.message || "Failed to change password.",
            isServerDown: response.code.serverDown,
        })
    } else {
        toast.success(`Password was changed.`)
    }
}