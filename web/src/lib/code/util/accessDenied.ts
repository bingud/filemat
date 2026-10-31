import { appState } from "$lib/code/stateObjects/appState.svelte"

const accessDenied = /Missing permission to (open this folder|(copy|move|delete|replace) file)\.?$/

const hint = `Windows refused access: the account that runs Filemat may need access to it (icacls), or Controlled folder access may block it. See docs/windows.md.`

/**
 * Adds a hint to the message of an operating system access denied error on Windows, where the fix is outside Filemat.
 * Other messages, and every message on other systems, stay as they are.
 */
export function withAccessDeniedHint(notification: string): string {
    if (appState.platform.pathStyle !== `windows` || !accessDenied.test(notification.trim())) return notification
    return `${notification.trim()} ${hint}`
}
