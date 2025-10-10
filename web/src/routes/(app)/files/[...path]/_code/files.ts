import { streamFileContent } from "$lib/code/module/files"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"



export async function loadFileContent(filePath: string) {
    const blob = await streamFileContent(filePath, filesState.abortController.signal)
    if (filesState.lastFilePathLoaded !== filePath) return    
    if (!blob) return
    filesState.data.content = blob
}


export class SingleChildNode {
    children: Map<string, SingleChildNode> = new Map();
    value?: boolean;
    selectedChildName?: string;
}

export class SingleChildBooleanTree {
    private root = new SingleChildNode();

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
}