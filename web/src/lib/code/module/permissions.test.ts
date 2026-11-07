import { afterEach, describe, expect, it } from "vitest"
import { hasAnyPermission } from "./permissions"
import { auth } from "$lib/code/stateObjects/authState.svelte"
import type { SystemPermission } from "$lib/code/auth/types"

describe(`hasAnyPermission`, () => {
    afterEach(() => {
        delete (auth as any).permissions
    })

    // `auth.permissions` is a `$derived` that doesn't update in unit tests, so the user's permissions are set directly
    function signInWith(permissions: SystemPermission[]) {
        Object.defineProperty(auth, `permissions`, { value: permissions, configurable: true, writable: true })
    }

    it(`does not change the array it is given`, () => {
        signInWith([`MANAGE_USERS`])
        const required: SystemPermission[] = [`MANAGE_SYSTEM`]

        expect(hasAnyPermission(required)).toBe(false)
        expect(required).toEqual([`MANAGE_SYSTEM`])
    })

    it(`accepts a held permission and the super admin`, () => {
        signInWith([`MANAGE_USERS`])
        expect(hasAnyPermission([`MANAGE_USERS`])).toBe(true)

        signInWith([`SUPER_ADMIN`])
        expect(hasAnyPermission([`MANAGE_SYSTEM`])).toBe(true)
        expect(hasAnyPermission([`MANAGE_SYSTEM`], true)).toBe(false)
    })
})
