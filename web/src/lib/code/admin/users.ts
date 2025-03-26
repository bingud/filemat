import type { PublicUser } from "../auth/types";
import type { ulid } from "../types";
import { safeFetch, handleError, handleErrorResponse, formData, handleException, isServerDown } from "../util/codeUtil.svelte";




export async function loadUserList(): Promise<PublicUser[] | null> {
    const response = await safeFetch(`/api/v1/admin/user/list`, { method: "POST", credentials: "same-origin" });
    if (response.failed) {
        handleError(response.exception, `Failed to load list of users.`);
        return null;
    }
    const status = response.code;
    const json = response.json();

    if (status.ok) {
        return json;
    } else if (status.serverDown) {
        handleError(`Server ${status} when fetching user list.`, "Failed to load users. The server is unavailable.");
        return null;
    } else {
        handleErrorResponse(json, `Failed to load the list of all users. (${status})`);
        return null;
    }
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
        handleException(`Failed to create user`, `Failed to create user.`, response.exception)
        return null
    }
    const status = response.code
    const json = response.json()

    if (status.ok) {
        return json.userId
    } else if (status.serverDown) {
        handleError(`Server ${status} when creating user`, `Failed to create user. Server is unavailable.`)
    } else {
        handleErrorResponse(json, `Failed to create user.`)
    }
    return null
}