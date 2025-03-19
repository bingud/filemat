import type { uiState } from "$lib/code/stateObjects/uiState.svelte";
import { valuesOf } from "$lib/code/util/codeUtil.svelte";


export const settingSectionLists: {[key: string]: (typeof uiState.settings.section)[]} = {
    user: [
        "preferences"
    ],
    admin: [
        "users",
        "roles",
    ]
}


export const settingSections = valuesOf(settingSectionLists).flat()
export function isAdminSettingsSection(section: typeof uiState.settings.section) {
    return settingSectionLists.admin.includes(section)
}