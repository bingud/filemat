import type { ulid } from "../types/types"
import { isBlank } from "./codeUtil.svelte"
import { appState } from "../stateObjects/appState.svelte"


/**
 * Contains value validation methods
 */
export class Validator {
    public static totp(t: any): string | null {
        if (!t) return "Code is blank."
        const str = t.toString()
        if (str.length !== 6) return "Code must be 6 digits long."
        const int = parseInt(t)
        if (Number.isNaN(int)) return "Code can only contain digits."
        return null
    }

    public static email(email: string): string | null {
        if (!email.includes("@") || !email.includes(".")) return "Email is invalid."
        if (email.length < 5) return "Email is too short."
        if (email.length > 256) return "Email is too long."

        return null
    }

    public static isUlidValid(ulid: ulid | string | undefined): ulid is ulid {
        if (isBlank(ulid) || ulid?.length !== 26) return false
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

    /**
     * Checks a single file or folder name against the rules of the server OS.
     * The server checks again, this is only early feedback.
     */
    public static filename(name: string): string | null {
        const rules = appState.platform.filenameRules
        if (name.length === 0) return `Name is empty.`
        for (const char of name) {
            if (rules.invalidChars.includes(char)) return `Name contains invalid characters.`
            if (rules.rejectControlChars && char.charCodeAt(0) < 32) return `Name contains invalid characters.`
        }

        const base = name.split(`.`)[0].trimEnd().toLowerCase()
        const isReserved = rules.reservedNames.some(reserved => {
            if (!rules.reservedNamesIgnoreExtension) return reserved === name
            const lower = reserved.toLowerCase()
            return lower === name.toLowerCase() || lower === base
        })
        if (isReserved) return `This name is reserved.`

        if (rules.rejectTrailingDotOrSpace && (name.endsWith(`.`) || name.endsWith(` `))) return `Name cannot end with a dot or space.`

        const length = rules.lengthUnit === `utf8Bytes` ? new TextEncoder().encode(name).length : name.length
        if (length > rules.maxLength) return `Name is too long.`

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