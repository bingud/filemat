import { streamFileContent } from "$lib/code/module/files"
import { parseJson, handleException, unixNowMillis } from "$lib/code/util/codeUtil.svelte"
import { filesState } from "./filesState.svelte"
import * as tus from 'tus-js-client'



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


/**
 * Initiate a file upload with TUS.
 */
export function uploadWithTus() {
    const input = document.createElement('input')
    input.type = 'file'
    input.style.display = 'none' // Keep it hidden

    input.onchange = (e) => {
        const file = (e.target as HTMLInputElement).files?.[0]
        if (!file) {
            return
        }

        // Construct the full target path
        const currentPath = filesState.path === '/' ? '' : filesState.path
        const targetPath = `${currentPath}/${file.name}`

        console.log(`Attempting to upload ${file.name} to ${targetPath}`)

        // Get the actual uploaded filename from the server
        let actualFilename: string | null = null

        const upload = new tus.Upload(file, {
            endpoint: "/api/v1/file/upload", // Your TUS endpoint
            retryDelays: [0, 3000, 5000, 10000, 20000],
            metadata: {
                filename: targetPath,
                // Add any other metadata your server needs
                // filetype: file.type 
            },
            onAfterResponse: (response) => {
                // Get the actual uploaded filename from the server
                // If the file already exists, the server will add a number to the end of the filename
                const res = response.getUnderlyingObject() as XMLHttpRequest | null
                const actualFilenameHeader = res?.getResponseHeader("actual-uploaded-filename")
                if (actualFilenameHeader) {
                    actualFilename = actualFilenameHeader
                }
            },
            onError: (error) => {
                const res = (error as tus.DetailedError).originalResponse?.getUnderlyingObject() as XMLHttpRequest | null
                const text = res?.responseText
                const json = parseJson(text || "")
                const message = json.message || text || error.message || "Failed to upload file."

                const isCustomError = json.error === "custom"
                handleException(`Failed to upload file with TUS. Is custom error: ${isCustomError}`, message, error)
            },
            onProgress: (bytesUploaded, bytesTotal) => {
                const percentage = ((bytesUploaded / bytesTotal) * 100).toFixed(2)
                console.log(bytesUploaded, bytesTotal, percentage + "%")
                // Update UI with progress if needed
            },
            onSuccess: () => {
                const uploadedFile = upload.file as File
                
                const uploadFolder = targetPath.substring(0, targetPath.lastIndexOf('/')) || "/"
                const actualUploadedPath = actualFilename ? (`${uploadFolder === "/" ? "/" : `${uploadFolder}/`}${actualFilename}`) : null
                
                // Add the uploaded file to entries if it belongs in the current folder
                if (filesState.path === uploadFolder) {
                    filesState.data.entries?.push({
                        path: actualUploadedPath || targetPath,
                        modifiedDate: unixNowMillis(),
                        createdDate: unixNowMillis(),
                        fileType: "FILE",
                        size: uploadedFile.size
                    })
                }
            },
            onShouldRetry: (err, retryAttempt, options) => {
                // Try to extract JSON from the failed response
                const xhr = err.originalResponse?.getUnderlyingObject() as XMLHttpRequest | null
                if (!xhr) return false
                
                const text = xhr.responseText
                const json = parseJson(text || "")

                // If it's our custom error, abort retries
                if (json?.error === "custom") {
                    return false
                }

                // Otherwise retry if we still have attempts left
                return retryAttempt < (options.retryDelays?.length || 0)
            },
        })

        // Start the upload
        upload.start()

        // Clean up the input element
        document.body.removeChild(input)
    }

    // Append to body, trigger click, and remove
    document.body.appendChild(input)
    input.click()
    // Note: Removal is now handled in the onchange event after selection or cancellation
}