import { formData, handleErr, safeFetch, unixNow } from "$lib/code/util/codeUtil.svelte"
import { toast } from "@jill64/svelte-toast"

class SensitiveAuthState {
    dialogOpen = $state(false)
    dialogMessage = $state(`Enter the authentication code.`)
    codeInput = $state(``)
    loading = $state(false)
    verifiedCode: { code: string, expirationDate: number } | null = $state(null)
    remainingSeconds: number | null = $state(null)

    verified = $derived(this.verifiedCode != null)
    code = $derived(this.verifiedCode?.code ?? null)

    #interval: ReturnType<typeof setInterval> | null = null

    open(message: string) {
        this.dialogMessage = message
        this.codeInput = ``
        this.dialogOpen = true
        this.generate()
    }

    async generate() {
        if (this.loading) return
        this.loading = true
        const response = await safeFetch(`/api/v1/admin/system/generate-sensitive-code`)
        this.loading = false
        if (response.failed) {
            handleErr({
                description: `Failed to generate auth OTP.`,
                notification: `Failed to generate code.`,
            })
            return
        }
        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: `Failed to generate code.`,
                notification: json.message || `Failed to generate code.`,
                isServerDown: response.code.serverDown,
            })
        }
    }

    async verify() {
        if (this.codeInput.length !== 16) {
            toast.error(`The code must be 16 letters long.`)
            return
        }
        if (this.loading) return
        this.loading = true
        const response = await safeFetch(`/api/v1/admin/system/authenticate-sensitive-code`, {
            body: formData({ code: this.codeInput }),
        })
        this.loading = false
        if (response.failed) {
            handleErr({
                description: `Failed to verify auth OTP.`,
                notification: `Failed to verify code.`,
            })
            return
        }
        if (response.code.failed) {
            const json = response.json()
            handleErr({
                description: `Failed to verify code.`,
                notification: json.message || `Failed to verify code.`,
                isServerDown: response.code.serverDown,
            })
            return
        }

        this.verifiedCode = {
            code: this.codeInput,
            expirationDate: parseInt(response.content),
        }
        this.#startTicker()
        this.dialogOpen = false
    }

    #tick() {
        if (!this.verifiedCode) {
            this.#stopTicker()
            return
        }
        this.remainingSeconds = this.verifiedCode.expirationDate - unixNow()
        if (this.remainingSeconds <= 0) {
            this.verifiedCode = null
            this.remainingSeconds = null
            this.#stopTicker()
        }
    }

    #startTicker() {
        this.#stopTicker()
        this.#interval = setInterval(() => this.#tick(), 3000)
        this.#tick()
    }

    #stopTicker() {
        if (this.#interval) {
            clearInterval(this.#interval)
            this.#interval = null
        }
    }
}

export const sensitiveAuth = new SensitiveAuthState()
