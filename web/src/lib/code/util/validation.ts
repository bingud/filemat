import { isBlank } from "./codeUtil.svelte"


export class Validator {
    public static email(email: string): string | null {
        if (!email.includes("@") || !email.includes(".")) return "Email is invalid."
        if (email.length < 5) return "Email is too short."
        if (email.length > 256) return "Email is too long."

        return null
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
        if (this.email(u) || this.username(u)) return "Email or username is invalid."

        return null
    }
}