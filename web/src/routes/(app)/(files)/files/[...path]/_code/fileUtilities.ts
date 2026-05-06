import type { EntityPermission, Role } from "$lib/code/auth/types"
import { streamFileContent } from "$lib/code/module/files"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"

export type EntityPermissionMeta = { permission: EntityPermission & { permissionType: "USER"} ; username: string; role: null}  |
        { permission: EntityPermission & { permissionType: "ROLE"} ; username: null; role: Role} 


export async function loadFileContent(filePath: string) {
    if (filesState.data.contentFilePath === filePath && filesState.data.content) return

    const blob = await streamFileContent(filePath,  { signal: filesState.abortController.signal, shareToken: filesState.getShareToken() })
    if (filesState.data.fileMeta?.path !== filePath) return console.log(`Loaded blob of file that is not open anymore.`)
    if (!blob) return console.log(`Loaded blob is null.`)
    filesState.data.content = blob
    filesState.data.contentFilePath = filePath
}


/** One node in the path tree: path segments are folder/file names without leading slashes. */
export class SingleChildNode {
    /** Deeper segments, e.g. under `/foo` the key might be `bar` for `/foo/bar`. */
    children: Map<string, SingleChildNode> = new Map();
    /** Whether this path segment itself is marked selected (leaf use). */
    value?: boolean;
    /** At most one direct child name can be "the" selected child under this folder. */
    selectedChildName?: string;
}

/**
 * Remembers which **single** entry is highlighted under each folder along a path.
 * Paths use `/a/b/c` style; each segment is a map key. Selecting a new sibling under the same
 * parent replaces the previous sibling (hence "single child" per folder level).
 * 
 * E.g. `set("/docs/a.txt", true)` → `getChild("/docs")` is `"a.txt"`.
 */
export class SingleChildBooleanTree {
    private root = new SingleChildNode();

    /** Mark `path` selected or not; `true` enforces only one selected child per parent folder. */
    set(path: string, val: boolean): void {
        const parts = path.split("/").filter(Boolean);
        let current = this.root;

        for (let i = 0; i < parts.length; i++) {
            const part = parts[i];

            // Ensure child exists
            let child = current.children.get(part);
            if (!child) {
                child = new SingleChildNode();
                current.children.set(part, child);
            }

            // On the final segment, set or unset
            if (i === parts.length - 1) {
                child.value = val;
                if (val) {
                    // Remove the previously selected child subtree if different
                    if (current.selectedChildName && current.selectedChildName !== part) {
                        current.children.delete(current.selectedChildName);
                    }
                    current.selectedChildName = part;
                } else {
                    // Remove this node subtree if it was selected
                    if (current.selectedChildName === part) {
                        current.selectedChildName = undefined;
                    }
                    current.children.delete(part);
                }
            } else {
                // Descend
                current = child;
            }
        }
    }

    /** Whether the exact path’s leaf segment is marked selected. */
    get(path: string): boolean | undefined {
        const parts = path.split("/").filter(Boolean);
        let current = this.root;

        for (let i = 0; i < parts.length; i++) {
            const part = parts[i];
            const child = current.children.get(part);
            if (!child) return undefined;
            if (i === parts.length - 1) {
                return child.value;
            }
            current = child;
        }
        return undefined;
    }

    /**
     * After walking to `path` (usually a **folder** path), returns the selected **child name**
     * under that folder (not the full path), or undefined.
     */
    getChild(path: string): string | undefined {
        const parts = path.split("/").filter(Boolean);
        let current = this.root;

        for (const part of parts) {
            const child = current.children.get(part);
            if (!child) return undefined;
            current = child;
        }
        return current.selectedChildName;
    }

    /** Drop the remembered selected child under this folder (see {@link getChild}). */
    clearPersistedChildSelection(folderPath: string): void {
        const parts = folderPath.split("/").filter(Boolean);
        let current = this.root;

        for (const part of parts) {
            const child = current.children.get(part);
            if (!child) return;
            current = child;
        }

        if (current.selectedChildName !== undefined) {
            current.children.delete(current.selectedChildName);
            current.selectedChildName = undefined;
        }
    }
}