import type { ulid } from "../types/types"
import { isBlank } from "./codeUtil.svelte"


/**
 * Contains value validation methods
 */
export class Validator {
    public static email(email: string): string | null {
        if (!email.includes("@") || !email.includes(".")) return "Email is invalid."
        if (email.length < 5) return "Email is too short."
        if (email.length > 256) return "Email is too long."

        return null
    }

    public static isUlidValid(ulid: ulid | string): boolean {
        if (isBlank(ulid) || ulid.length !== 26) return false
        return true
    }
  
    public static password(
        password: string,
    ): string | null {
        if (password.length < 4) return "Password is too short."
        if (password.length > 256) return "Password is too long."
        if (isBlank(password)) return "Password is blank."

        return null
    }

    public static username(
        username: string,
    ): string | null {
        if (username.length < 1 || isBlank(username)) return "Username is blank."
        if (username.length > 48) return "Username is too long."
        
        const regex = /^[A-Za-z0-9_-]+$/
        if (!regex.test(username)) return "Username contains invalid characters."
        return null
    }

    public static setupCode(
        code: string,
    ): string | null {
        if (code.length !== 12) return "Setup code must be 12 letters long."

        return null
    }

    public static emailOrUsername(
        u: string,
    ): string | null {
        if (u.includes("@")) {
            const v = this.email(u)
            if (v) return v
        } else {
            const v = this.username(u)
            if (v) return v
        }

        return null
    }
}