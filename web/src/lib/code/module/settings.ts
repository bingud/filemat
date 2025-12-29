import type { SystemPermission } from "../auth/types";
import { appState, filePagePaths } from "../stateObjects/appState.svelte";
import type { SettingSectionId } from "../stateObjects/uiState.svelte";
import { valuesOf, includesList, run, keysOf } from "../util/codeUtil.svelte";
import { getCurrentPermissions } from "./permissions";

export type PreferenceSetting = "load_all_previews" | "default_page_path" | "click_to_open_file"

export function loadPreferenceSettings() {
    const loadAllPreviewsStr = getPreferenceSetting("load_all_previews")
    if (loadAllPreviewsStr) {
        const bool = loadAllPreviewsStr === "true"
        appState.settings.loadAllPreviews = bool
    }

    const defaultPagePath = getPreferenceSetting("default_page_path")
    if (defaultPagePath) {
        if (keysOf(filePagePaths).includes(defaultPagePath as any)) { // :)
            appState.settings.defaultPagePath = defaultPagePath as any
        }
    }

    const clickToOpenFile = getPreferenceSetting("click_to_open_file")
    if (clickToOpenFile === "true") {
        appState.settings.clickToOpenFile = true
    }
}

function getPreferenceSetting(id: PreferenceSetting): string | null {
    return localStorage.getItem(`preference-${id}`) || null
}
export function setPreferenceSetting(id: PreferenceSetting, value: any) {
    localStorage.setItem(`preference-${id}`, value.toString())
}


export const settingSections = {
    all() {
        return valuesOf(settingSections.sections);
    },
    allUser() {
        return valuesOf(settingSections.sections).filter(v => !v.admin);
    },
    allAdmin() {
        return valuesOf(settingSections.sections).filter(v => v.admin);
    },
    hasPermission(sectionName: SettingSectionId): boolean {
        if (!sectionName) return false;
        const section = settingSections.sections[sectionName.toLowerCase()];
        if (!section) return false;
        if (!section.admin) return true;

        const permissionMetas = getCurrentPermissions();
        if (!permissionMetas) return false;
        const permissions = permissionMetas.map(v => v.id);

        const hasPermission = includesList(permissions, section.permissions) || permissions.includes("SUPER_ADMIN");

        return hasPermission;
    },
    sections: {
        "preferences": { name: "preferences", permissions: [], admin: false },
        "system": { name: "system", permissions: ["MANAGE_SYSTEM"], admin: true },
        "users": { name: "users", permissions: ["MANAGE_USERS"], admin: true },
        "roles": { name: "roles", permissions: ["EDIT_ROLES"], admin: true },
    } as Record<string, SettingsSection>
}

export type SettingsSection = { name: SettingSectionId; permissions: SystemPermission[]; admin: boolean; }