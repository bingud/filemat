import type { PublicUser } from "../auth/types";
import type { ulid } from "../types/types";
import { safeFetch, formData, handleErr, handleException} from "../util/codeUtil.svelte";




export async function loadUserList(): Promise<PublicUser[] | null> {
    const response = await safeFetch(`/api/v1/admin/user/list`, { method: "POST", credentials: "same-origin" });
    if (response.failed) {
        handleErr({
            description: response.exception,
            notification: `Failed to load list of users.`,
        })
        return null;
    }
    const status = response.code;
    const json = response.json();

    if (status.failed) {
        handleErr({
            description: `Failed to load the list of all users.`,
            notification: json.message || "Failed to load the list of all users.",
            isServerDown: status.serverDown
        })
        return null
    }
    return json
}


/**
 * Creates a user
 * 
 * @return users new userID
 */
export async function createUser(email: string, username: string, password: string): Promise<ulid | null> {
    const body = formData({ email: email, username: username, password: password })
    const response = await safeFetch(`/api/v1/admin/user/create`, { body: body })
    if (response.failed) {
        handleErr({
            description: `Failed to create user`,
            notification: `Failed to create user.`,
        })
        return null
    }
    const status = response.code
    const json = response.json()

    if (status.failed) {
        handleErr({
            description: `Failed to create user.`,
            notification: json.message || `Failed to create user.`,
            isServerDown: status.serverDown
        })
        return null
    }

    return json.userId
}

export async function deleteUser(userId: ulid): Promise<boolean> {
    const response = await safeFetch(`/api/v1/admin/user/delete`, { body: formData({ userId: userId }) })
    if (response.failed) {
        handleException(`Failed to delete user account.`, `Failed to delete user.`, response.exception)
        return false
    }
    const status = response.code
    const json = response.json()

    if (status.notFound) {
        handleErr({
            description: `User not found when deleting account`,
            notification: `This user was not found.`,
        })
        return false
    } else if (status.failed) {
        handleErr({
            description: `Failed to delete user account.`,
            notification: json.message || `Failed to delete user.`,
            isServerDown: status.serverDown
        })
        return false
    }

    return true
}